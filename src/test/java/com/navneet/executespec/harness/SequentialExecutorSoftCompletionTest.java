package com.navneet.executespec.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.agent.PlannedTask.Complexity;
import com.navneet.executespec.engine.WorkerAgent;
import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.engine.WorkerTaskRequest;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.tool.BuildProjectTool;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.ToolResult;

import org.junit.jupiter.api.Test;

/**
 * Pins the soft-completion gate introduced by Fix C (2026-05-03, run a076c876 T2 diagnosis).
 *
 * <p>Pre-Fix-C behavior: any worker outcome other than {@code COMPLETED} with files written → task
 * immediately persisted as {@code PARTIAL}, even for intermediate tasks in multi-task plans where
 * the reviewer's final build is the authoritative gate. T2 was a multi-task, intermediate,
 * LOW-complexity task that wrote 5 correct files but couldn't shut up — and landed PARTIAL when it
 * should have landed COMPLETE-with-warning.
 *
 * <p>Post-Fix-C behavior: outcome != COMPLETED + files written → fall through to the SAME gate the
 * COMPLETED path uses (multi-task skip / smoke test). Final status is decided by the gate; the
 * soft-completion warning rides along on {@code lastLlmFailure} so the user sees what happened
 * regardless of which final bucket the task landed in.
 *
 * <p>Tests cover the four corners:
 *
 * <ul>
 *   <li>Multi-task intermediate + iter-limit + writes → DB COMPLETE, warning persisted
 *   <li>Single-task + iter-limit + writes + smoke pass → DB COMPLETE, warning persisted
 *   <li>Single-task + iter-limit + writes + smoke fail → DB PARTIAL, both reasons persisted
 *   <li>Iter-limit + zero writes → DB FAILED (existing behavior preserved)
 * </ul>
 */
class SequentialExecutorSoftCompletionTest {

  private final WorkerAgent worker = mock(WorkerAgent.class);
  private final BuildProjectTool buildProjectTool = mock(BuildProjectTool.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final com.navneet.executespec.observability.Metrics metrics =
      mock(com.navneet.executespec.observability.Metrics.class);
  private final com.navneet.executespec.sse.SseBroadcaster broadcaster =
      mock(com.navneet.executespec.sse.SseBroadcaster.class);
  private final AgentTaskRepository taskRepo = mock(AgentTaskRepository.class);
  private final com.navneet.executespec.router.ModelResolver modelResolver =
      mock(com.navneet.executespec.router.ModelResolver.class);
  private final com.navneet.executespec.project.rag.RetrievalService retrievalService =
      mock(com.navneet.executespec.project.rag.RetrievalService.class);
  private final com.navneet.executespec.cost.RunCostTracker costTracker =
      mock(com.navneet.executespec.cost.RunCostTracker.class);
  private final com.navneet.executespec.config.featureflag.FeatureFlagService featureFlagService =
      mock(com.navneet.executespec.config.featureflag.FeatureFlagService.class);

  private SequentialExecutor newExecutor() {
    // ModelResolver is called from tierConfigFor — stub it to throw so we fall back to the
    // run-level provider+model. That avoids needing real Spring config wiring in this unit
    // test.
    lenient()
        .when(modelResolver.resolve(anyString(), any()))
        .thenThrow(new RuntimeException("not wired in unit test"));
    // 2026-05-04 — ContextStore param added on PR #71 for the resume path. Soft-completion
    // tests cover the non-resume execute branch where ContextStore is never touched, so a mock
    // is fine.
    return new SequentialExecutor(
        worker,
        buildProjectTool,
        objectMapper,
        metrics,
        broadcaster,
        taskRepo,
        modelResolver,
        org.mockito.Mockito.mock(com.navneet.executespec.router.ModelMatrixProperties.class),
        retrievalService,
        org.mockito.Mockito.mock(com.navneet.executespec.smoketest.WorkspaceTestRunner.class),
        featureFlagService,
        org.mockito.Mockito.mock(com.navneet.executespec.context.ContextStore.class),
        costTracker,
        org.mockito.Mockito.mock(
            com.navneet.executespec.deployment.GcsTaskCheckpointService.class));
  }

  /** Snapshot of the (status, lastLlmFailure) tuple at the moment of saveAndFlush. */
  private record SaveSnapshot(String taskKey, String status, String lastLlmFailure) {}

  private final java.util.List<SaveSnapshot> saveLog = new java.util.ArrayList<>();

  /**
   * Make the task repo return a fresh AgentTask row per id and snapshot every saveAndFlush so the
   * test can assert what was actually persisted at each transition (vs the final mutated state of a
   * reused row instance).
   */
  private void stubTaskRow() {
    java.util.Map<String, AgentTask> rowsByKey = new java.util.HashMap<>();
    lenient()
        .when(taskRepo.findById(anyString()))
        .thenAnswer(
            inv -> {
              String key = inv.getArgument(0);
              return Optional.of(rowsByKey.computeIfAbsent(key, k -> new AgentTask()));
            });
    lenient()
        .when(taskRepo.saveAndFlush(any()))
        .thenAnswer(
            inv -> {
              AgentTask t = inv.getArgument(0);
              // Find the key for this row — reverse-lookup since AgentTask doesn't expose its
              // composite-key id directly in this test.
              String key = "(unknown)";
              for (var e : rowsByKey.entrySet()) {
                if (e.getValue() == t) {
                  key = e.getKey();
                  break;
                }
              }
              saveLog.add(new SaveSnapshot(key, t.getStatus(), t.getLastLlmFailure()));
              return t;
            });
  }

  private static Plan multiTaskPlan() {
    return Plan.builder()
        .planTitle("Test plan")
        .tasks(
            List.of(
                PlannedTask.builder()
                    .id("T1")
                    .title("write five files")
                    .complexity(Complexity.LOW)
                    .build(),
                PlannedTask.builder()
                    .id("T2")
                    .title("write more files")
                    .complexity(Complexity.LOW)
                    .build()))
        .buildCommand("echo build")
        .build();
  }

  private static Plan singleTaskPlan() {
    return Plan.builder()
        .planTitle("Single")
        .tasks(
            List.of(
                PlannedTask.builder()
                    .id("T1")
                    .title("write three files")
                    .complexity(Complexity.LOW)
                    .build()))
        .buildCommand("echo build")
        .build();
  }

  private static ExecutionRequest req(Plan plan, boolean enableSmoke) {
    return ExecutionRequest.builder()
        .plan(plan)
        .provider("anthropic")
        .model("claude-haiku-4-5-20251001")
        .runContext(new ExecutionContext("run-soft", "run-soft", Path.of("/tmp/ws-soft")))
        .enableSmokeTests(enableSmoke)
        .build();
  }

  /** Build a worker result with N writeFile entries → countFileWrites returns N. */
  private static WorkerResult workerHitIterLimit(int writes) {
    java.util.List<String> log = new java.util.ArrayList<>();
    for (int i = 0; i < writes; i++) log.add("writeFile(...)");
    return WorkerResult.builder()
        .outcome(WorkerResult.Outcome.ITERATION_LIMIT_EXCEEDED)
        .iterations(60)
        .toolCallsDispatched(66)
        .finalAssistantText("verifying changes...")
        .toolCallLog(log)
        .totalLatencyMs(115_000L)
        .build();
  }

  private static WorkerResult workerCompleted(int writes) {
    java.util.List<String> log = new java.util.ArrayList<>();
    for (int i = 0; i < writes; i++) log.add("writeFile(...)");
    return WorkerResult.builder()
        .outcome(WorkerResult.Outcome.COMPLETED)
        .iterations(12)
        .toolCallsDispatched(15)
        .completionSummary("done")
        .toolCallLog(log)
        .totalLatencyMs(20_000L)
        .build();
  }

  // 1: T2-class case — multi-task, intermediate, iter-limit + writes

  @Test
  void multitask_intermediate_iter_limit_with_writes_is_COMPLETE_not_PARTIAL() {
    // Worker fails to call completeTask but wrote 5 files. Multi-task plan,
    // first task (intermediate). Pre-fix this would have been PARTIAL.
    stubTaskRow();
    when(worker.run(any(WorkerTaskRequest.class), any())).thenReturn(workerHitIterLimit(5));

    SequentialExecutor executor = newExecutor();
    ExecutionResult result = executor.execute(req(multiTaskPlan(), true));

    assertNotNull(result);
    // T1 was the intermediate task we care about. Find its outcome.
    TaskOutcome t1 = result.perTask().get(0);
    assertEquals(
        TaskOutcome.Status.COMPLETED_WITH_WARNING,
        t1.status(),
        "Multi-task intermediate + iter-limit + writes should be COMPLETED_WITH_WARNING.");

    // Verify the T1-key save landed COMPLETE in the DB with the warning persisted.
    boolean sawCompleteWithWarningOnT1 =
        saveLog.stream()
            .anyMatch(
                s ->
                    s.taskKey().endsWith("_T1")
                        && "COMPLETE".equals(s.status())
                        && s.lastLlmFailure() != null
                        && s.lastLlmFailure().contains("Iteration cap exceeded"));
    assertTrue(
        sawCompleteWithWarningOnT1,
        "Expected T1 save with status=COMPLETE + lastLlmFailure containing the iter-cap warning."
            + " Saw: "
            + saveLog);
  }

  // 2: Single-task + iter-limit + writes + smoke pass → COMPLETE+warning

  @Test
  void singletask_iter_limit_with_writes_smoke_pass_is_COMPLETE() {
    stubTaskRow();
    when(worker.run(any(WorkerTaskRequest.class), any())).thenReturn(workerHitIterLimit(3));
    // Smoke pass — exit code 0.
    when(buildProjectTool.execute(any(), any())).thenReturn(new ToolResult.Ok("exit=0\nbuild ok"));

    SequentialExecutor executor = newExecutor();
    ExecutionResult result = executor.execute(req(singleTaskPlan(), true));

    TaskOutcome t1 = result.perTask().get(0);
    assertEquals(TaskOutcome.Status.COMPLETED_WITH_WARNING, t1.status());
    assertEquals(true, t1.smokeTestPassed());
    assertEquals(0, t1.smokeTestExitCode());
  }

  // 3: Single-task + iter-limit + writes + smoke FAIL → PARTIAL

  @Test
  void singletask_iter_limit_with_writes_smoke_fail_is_PARTIAL_with_combined_reason() {
    stubTaskRow();
    when(worker.run(any(WorkerTaskRequest.class), any())).thenReturn(workerHitIterLimit(2));
    // Smoke fail — non-zero exit. Worker is NOT retried in the soft-completion
    // path because it already burned its budget.
    when(buildProjectTool.execute(any(), any()))
        .thenReturn(new ToolResult.Ok("exit=1\nSyntaxError: unexpected token"));

    SequentialExecutor executor = newExecutor();
    ExecutionResult result = executor.execute(req(singleTaskPlan(), true));

    TaskOutcome t1 = result.perTask().get(0);
    assertEquals(TaskOutcome.Status.COMPLETED_WITH_WARNING, t1.status());
    assertEquals(false, t1.smokeTestPassed());
    assertEquals(1, t1.smokeTestExitCode());
    // Verify the persisted reason combines both signals.
    boolean sawPartialWithCombinedReason =
        saveLog.stream()
            .anyMatch(
                s ->
                    "PARTIAL".equals(s.status())
                        && s.lastLlmFailure() != null
                        && s.lastLlmFailure().contains("Iteration cap exceeded")
                        && s.lastLlmFailure().contains("Build smoke also failed"));
    assertTrue(
        sawPartialWithCombinedReason,
        "Expected DB PARTIAL with combined reason (iter-cap + smoke-fail). Saw: " + saveLog);
  }

  // 4: zero file writes → FAILED (regression check)

  @Test
  void iter_limit_with_zero_writes_is_FAILED_regression_check() {
    stubTaskRow();
    when(worker.run(any(WorkerTaskRequest.class), any())).thenReturn(workerHitIterLimit(0));

    SequentialExecutor executor = newExecutor();
    ExecutionResult result = executor.execute(req(singleTaskPlan(), true));

    TaskOutcome t1 = result.perTask().get(0);
    assertEquals(
        TaskOutcome.Status.WORKER_FAILED,
        t1.status(),
        "Worker that wrote zero files must still terminate as WORKER_FAILED — there was no"
            + " progress and the soft-completion path doesn't apply.");

    boolean sawFailed = saveLog.stream().anyMatch(s -> "FAILED".equals(s.status()));
    assertTrue(sawFailed, "Expected DB row to persist as FAILED. Saw: " + saveLog);
    verify(broadcaster)
        .emit(
            anyString(),
            argThat(
                event ->
                    isGoalDecisionLog(event)
                        && event.payload().contains("WASTE_RISK -> STOP_WASTE_GUARD")));
  }

  @Test
  void goal_decision_enforcement_default_off_does_not_skip_remaining_tasks() {
    stubTaskRow();
    when(worker.run(any(WorkerTaskRequest.class), any())).thenReturn(workerHitIterLimit(0));

    SequentialExecutor executor = newExecutor();
    ExecutionResult result = executor.execute(req(multiTaskPlan(), true));

    assertEquals(ExecutionResult.Overall.FAILED, result.overall());
    assertEquals(2, result.perTask().size());
    assertEquals(0, result.tasksSkipped());
    verify(worker, times(2)).run(any(WorkerTaskRequest.class), any());
  }

  @Test
  void goal_decision_enforcement_stops_after_waste_risk_and_skips_remaining_tasks() {
    stubTaskRow();
    when(featureFlagService.isEnabled(SequentialExecutor.FLAG_GOAL_DECISION_ENFORCEMENT))
        .thenReturn(true);
    when(worker.run(any(WorkerTaskRequest.class), any())).thenReturn(workerHitIterLimit(0));

    SequentialExecutor executor = newExecutor();
    ExecutionResult result = executor.execute(req(multiTaskPlan(), true));

    assertEquals(ExecutionResult.Overall.ABORTED, result.overall());
    assertEquals(2, result.perTask().size());
    assertEquals(TaskOutcome.Status.WORKER_FAILED, result.perTask().get(0).status());
    assertEquals(TaskOutcome.Status.SKIPPED, result.perTask().get(1).status());
    assertEquals(1, result.tasksSkipped());
    assertEquals(1, result.tasksExecuted());
    verify(worker, times(1)).run(any(WorkerTaskRequest.class), any());
    verify(broadcaster)
        .emit(
            anyString(),
            argThat(
                event ->
                    event != null
                        && "LOG".equals(event.type())
                        && event.payload() != null
                        && event.payload().startsWith("Goal stop enforced:")
                        && event.payload().contains("STOP_WASTE_GUARD")));
  }

  @Test
  void completed_worker_with_passing_smoke_emits_done_goal_decision() {
    stubTaskRow();
    when(worker.run(any(WorkerTaskRequest.class), any())).thenReturn(workerCompleted(1));
    when(buildProjectTool.execute(any(), any())).thenReturn(new ToolResult.Ok("exit=0\nbuild ok"));

    SequentialExecutor executor = newExecutor();
    ExecutionResult result = executor.execute(req(singleTaskPlan(), true));

    TaskOutcome t1 = result.perTask().get(0);
    assertEquals(TaskOutcome.Status.SUCCESS, t1.status());
    verify(broadcaster)
        .emit(
            anyString(),
            argThat(
                event ->
                    isGoalDecisionLog(event) && event.payload().contains("DONE -> STOP_DONE")));
  }

  // 5: COMPLETED_BY_STAGNATION + writes flows through soft-completion gate

  @Test
  void stagnation_outcome_with_writes_also_flows_through_soft_completion() {
    // Verify that the new COMPLETED_BY_STAGNATION outcome (Fix B) is treated
    // exactly like ITERATION_LIMIT_EXCEEDED for soft-completion purposes — not
    // FAILED, not silently mapped to a different bucket. With writes + smoke
    // disabled (multi-task intermediate equivalent), it should be COMPLETED_WITH_WARNING.
    stubTaskRow();
    java.util.List<String> log = java.util.List.of("writeFile(...)", "writeFile(...)");
    WorkerResult stagnant =
        WorkerResult.builder()
            .outcome(WorkerResult.Outcome.COMPLETED_BY_STAGNATION)
            .iterations(28)
            .toolCallsDispatched(28)
            .finalAssistantText("verifying...")
            .toolCallLog(log)
            .totalLatencyMs(60_000L)
            .build();
    when(worker.run(any(WorkerTaskRequest.class), any())).thenReturn(stagnant);

    SequentialExecutor executor = newExecutor();
    ExecutionResult result = executor.execute(req(multiTaskPlan(), true));

    TaskOutcome t1 = result.perTask().get(0);
    assertEquals(
        TaskOutcome.Status.COMPLETED_WITH_WARNING,
        t1.status(),
        "COMPLETED_BY_STAGNATION + writes must funnel through the soft-completion gate, not"
            + " FAILED.");
  }

  private static boolean isGoalDecisionLog(RunEvent event) {
    return event != null
        && "LOG".equals(event.type())
        && event.payload() != null
        && event.payload().startsWith("Goal decision:");
  }
}
