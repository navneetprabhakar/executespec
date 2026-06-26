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
import com.navneet.executespec.execution.model.GoalContinuationAdvisorRequest;
import com.navneet.executespec.execution.model.GoalContinuationPolicyDecision;
import com.navneet.executespec.execution.model.RunContinuationDecision;
import com.navneet.executespec.execution.service.GoalContinuationAdvisor;
import com.navneet.executespec.router.ModelProviderScore;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.tool.BuildProjectTool;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.ToolResult;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

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
    return newExecutor(null);
  }

  private SequentialExecutor newExecutor(GoalContinuationAdvisor goalContinuationAdvisor) {
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
        org.mockito.Mockito.mock(com.navneet.executespec.deployment.GcsTaskCheckpointService.class),
        goalContinuationAdvisor);
  }

  /** Snapshot of the (status, lastLlmFailure) tuple at the moment of saveAndFlush. */
  private record SaveSnapshot(
      String taskKey,
      String status,
      String lastLlmFailure,
      String goalDonenessJson,
      String continuationDecisionJson) {}

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
              saveLog.add(
                  new SaveSnapshot(
                      key,
                      t.getStatus(),
                      t.getLastLlmFailure(),
                      t.getGoalDonenessJson(),
                      t.getContinuationDecisionJson()));
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

  private static Plan bugFixPlan() {
    return Plan.builder()
        .planTitle("Bug fix")
        .tasks(
            List.of(
                PlannedTask.builder()
                    .id("T1")
                    .title("Fix failing login regression")
                    .description(
                        "Fix the NullPointerException in AuthService without broad rewrites")
                    .complexity(Complexity.LOW)
                    .touchesFiles(List.of("src/main/java/app/AuthService.java"))
                    .build()))
        .buildCommand("echo build")
        .build();
  }

  private static Plan parallelSafePlan() {
    return Plan.builder()
        .planTitle("Parallel safe")
        .tasks(
            List.of(
                PlannedTask.builder()
                    .id("T1")
                    .title("edit auth")
                    .complexity(Complexity.LOW)
                    .touchesFiles(List.of("src/Auth.java"))
                    .build(),
                PlannedTask.builder()
                    .id("T2")
                    .title("edit billing")
                    .complexity(Complexity.LOW)
                    .touchesFiles(List.of("src/Billing.java"))
                    .build(),
                PlannedTask.builder()
                    .id("T3")
                    .title("wire app")
                    .complexity(Complexity.LOW)
                    .touchesFiles(List.of("src/App.java"))
                    .dependsOn(List.of("T1", "T2"))
                    .build()))
        .buildCommand("echo build")
        .build();
  }

  private static ExecutionRequest req(Plan plan, boolean enableSmoke) {
    return req(plan, enableSmoke, false);
  }

  private static ExecutionRequest req(
      Plan plan, boolean enableSmoke, boolean requireContinuationConfirmation) {
    return ExecutionRequest.builder()
        .plan(plan)
        .provider("anthropic")
        .model("claude-haiku-4-5-20251001")
        .runContext(new ExecutionContext("run-soft", "run-soft", Path.of("/tmp/ws-soft")))
        .enableSmokeTests(enableSmoke)
        .requireConfirmationBeforeExpensiveContinuation(requireContinuationConfirmation)
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

  private static WorkerResult workerStoppedWithoutComplete(int writes) {
    java.util.List<String> log = new java.util.ArrayList<>();
    for (int i = 0; i < writes; i++) log.add("writeFile(...)");
    return WorkerResult.builder()
        .outcome(WorkerResult.Outcome.STOPPED_WITHOUT_COMPLETE)
        .iterations(60)
        .toolCallsDispatched(66)
        .finalAssistantText("I stopped without completing the task.")
        .toolCallLog(log)
        .totalLatencyMs(115_000L)
        .build();
  }

  private static WorkerResult workerCompleted(int writes) {
    return workerCompleted(writes, 20_000L);
  }

  private static WorkerResult workerCompleted(int writes, long totalLatencyMs) {
    java.util.List<String> log = new java.util.ArrayList<>();
    for (int i = 0; i < writes; i++) log.add("writeFile(...)");
    return WorkerResult.builder()
        .outcome(WorkerResult.Outcome.COMPLETED)
        .iterations(12)
        .toolCallsDispatched(15)
        .completionSummary("done")
        .toolCallLog(log)
        .totalLatencyMs(totalLatencyMs)
        .build();
  }

  private static ModelProviderScore candidate(String provider, String model) {
    return new ModelProviderScore(
        provider, model, "TASK_LOW", 91.0, 8.0, 1_000L, 2_000L, 70.0, List.of("test candidate"));
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
  void goal_decision_uses_configured_no_progress_iteration_limit() {
    stubTaskRow();
    when(worker.run(any(WorkerTaskRequest.class), any()))
        .thenReturn(workerStoppedWithoutComplete(0));

    SequentialExecutor executor = newExecutor();
    ReflectionTestUtils.setField(executor, "maxGoalNoProgressIterations", 120);
    executor.execute(req(singleTaskPlan(), true));

    verify(broadcaster)
        .emit(
            anyString(),
            argThat(
                event ->
                    isGoalDecisionEvent(event)
                        && "UNCERTAIN".equals(event.data().get("state"))
                        && "CONTINUE_SAME_MODEL_PROVIDER"
                            .equals(event.data().get("recommendedDecision"))
                        && ((List<?>) event.data().get("evidence"))
                            .contains("noProgressIterations=60/120")));
  }

  @Test
  void task_wave_plan_uses_configured_parallel_task_cap_without_enabling_parallel_execution() {
    stubTaskRow();
    when(worker.run(any(WorkerTaskRequest.class), any()))
        .thenReturn(workerCompleted(1), workerCompleted(1), workerCompleted(1));
    when(buildProjectTool.execute(any(), any())).thenReturn(new ToolResult.Ok("exit=0"));

    SequentialExecutor executor = newExecutor();
    ReflectionTestUtils.setField(executor, "maxGoalParallelTasks", 2);
    executor.execute(req(parallelSafePlan(), true));

    verify(broadcaster)
        .emit(
            anyString(),
            argThat(
                event ->
                    event != null
                        && "TASK_WAVE_PLAN".equals(event.type())
                        && "SEQUENTIAL_ADVISORY".equals(event.data().get("executionMode"))
                        && Integer.valueOf(2).equals(event.data().get("maxParallelTasks"))
                        && Boolean.TRUE.equals(event.data().get("parallelEligible"))
                        && ((List<?>) event.data().get("waves"))
                            .equals(List.of(List.of("T1", "T2"), List.of("T3")))));
  }

  @Test
  void goal_decision_uses_configured_false_done_limit() {
    stubTaskRow();
    when(worker.run(any(WorkerTaskRequest.class), any())).thenReturn(workerCompleted(2));
    when(worker.run(any(WorkerTaskRequest.class))).thenReturn(workerCompleted(1));
    when(buildProjectTool.execute(any(), any()))
        .thenReturn(
            new ToolResult.Ok("exit=1\nSyntaxError: unexpected token"),
            new ToolResult.Ok("exit=1\nSyntaxError: unexpected token"));

    SequentialExecutor executor = newExecutor();
    ReflectionTestUtils.setField(executor, "maxGoalFalseDoneClaims", 1);
    executor.execute(req(singleTaskPlan(), true));

    verify(broadcaster)
        .emit(
            anyString(),
            argThat(
                event ->
                    isGoalDecisionEvent(event)
                        && "WASTE_RISK".equals(event.data().get("state"))
                        && "STOP_WASTE_GUARD".equals(event.data().get("recommendedDecision"))
                        && String.valueOf(event.data().get("recommendedNextStep"))
                            .contains("Stop this attempt")
                        && String.valueOf(event.data().get("reason"))
                            .contains("repeated false completion claims")
                        && ((List<?>) event.data().get("evidence"))
                            .contains("falseDoneClaims=1/1")));
  }

  @Test
  void goal_decision_uses_configured_repeated_failure_limit() {
    stubTaskRow();
    when(worker.run(any(WorkerTaskRequest.class), any())).thenReturn(workerCompleted(2));
    when(worker.run(any(WorkerTaskRequest.class))).thenReturn(workerCompleted(1));
    when(buildProjectTool.execute(any(), any()))
        .thenReturn(
            new ToolResult.Ok("exit=1\nSyntaxError: unexpected token"),
            new ToolResult.Ok("exit=1\nSyntaxError: unexpected token"));

    SequentialExecutor executor = newExecutor();
    ReflectionTestUtils.setField(executor, "maxGoalFalseDoneClaims", 5);
    ReflectionTestUtils.setField(executor, "maxGoalRepeatedFailures", 2);
    executor.execute(req(singleTaskPlan(), true));

    verify(broadcaster)
        .emit(
            anyString(),
            argThat(
                event ->
                    isGoalDecisionEvent(event)
                        && "WASTE_RISK".equals(event.data().get("state"))
                        && "STOP_WASTE_GUARD".equals(event.data().get("recommendedDecision"))
                        && String.valueOf(event.data().get("reason"))
                            .contains("repeated tool, command, or validation failures")
                        && ((List<?>) event.data().get("evidence"))
                            .contains("repeatedFailureCount=2/2")));
  }

  @Test
  void goal_decision_groups_repeated_shell_failures_by_command_signature() {
    stubTaskRow();
    WorkerResult repeatedShellFailure =
        WorkerResult.builder()
            .outcome(WorkerResult.Outcome.COMPLETED)
            .iterations(8)
            .toolCallsDispatched(5)
            .completionSummary("done")
            .toolCallLog(
                List.of(
                    "shell({\"command\":\"npm test\"}) -> ERR: first failure",
                    "shell({\"command\":\"npm test\"}) -> ERR: second failure",
                    "shell({\"command\":\"npm test\"}) -> ERR: third failure"))
            .totalLatencyMs(18_000L)
            .build();
    when(worker.run(any(WorkerTaskRequest.class), any())).thenReturn(workerCompleted(1));
    when(worker.run(any(WorkerTaskRequest.class))).thenReturn(repeatedShellFailure);
    when(buildProjectTool.execute(any(), any()))
        .thenReturn(
            new ToolResult.Ok("exit=1\nTests failed"),
            new ToolResult.Ok("exit=1\nTests failed again"));

    SequentialExecutor executor = newExecutor();
    ReflectionTestUtils.setField(executor, "maxGoalFalseDoneClaims", 5);
    ReflectionTestUtils.setField(executor, "maxGoalRepeatedFailures", 3);
    executor.execute(req(singleTaskPlan(), true));

    verify(broadcaster)
        .emit(
            anyString(),
            argThat(
                event ->
                    isGoalDecisionEvent(event)
                        && "WASTE_RISK".equals(event.data().get("state"))
                        && "STOP_WASTE_GUARD".equals(event.data().get("recommendedDecision"))
                        && String.valueOf(event.data().get("reason"))
                            .contains("repeated tool, command, or validation failures")
                        && ((List<?>) event.data().get("evidence"))
                            .contains("repeatedFailureCount=3/3")));
  }

  @Test
  void goal_decision_uses_configured_edit_churn_limit() {
    stubTaskRow();
    WorkerResult churned =
        WorkerResult.builder()
            .outcome(WorkerResult.Outcome.COMPLETED)
            .iterations(12)
            .toolCallsDispatched(15)
            .completionSummary("done")
            .toolCallLog(
                List.of(
                    "editFile({\"path\":\"src/App.tsx\",\"old\":\"a\",\"new\":\"b\"})",
                    "editFile({\"path\":\"src/App.tsx\",\"old\":\"b\",\"new\":\"c\"})",
                    "writeFile({\"path\":\"src/App.tsx\",\"content\":\"final\"})"))
            .totalLatencyMs(20_000L)
            .build();
    when(worker.run(any(WorkerTaskRequest.class), any())).thenReturn(workerCompleted(1));
    when(worker.run(any(WorkerTaskRequest.class))).thenReturn(churned);
    when(buildProjectTool.execute(any(), any()))
        .thenReturn(
            new ToolResult.Ok("exit=1\nSyntaxError: unexpected token"),
            new ToolResult.Ok("exit=1\nSyntaxError: unexpected token"));

    SequentialExecutor executor = newExecutor();
    ReflectionTestUtils.setField(executor, "maxGoalFalseDoneClaims", 5);
    ReflectionTestUtils.setField(executor, "maxGoalRepeatedFailures", 9);
    ReflectionTestUtils.setField(executor, "maxGoalEditChurn", 3);
    executor.execute(req(singleTaskPlan(), true));

    verify(broadcaster)
        .emit(
            anyString(),
            argThat(
                event ->
                    isGoalDecisionEvent(event)
                        && "WASTE_RISK".equals(event.data().get("state"))
                        && "STOP_WASTE_GUARD".equals(event.data().get("recommendedDecision"))
                        && String.valueOf(event.data().get("reason"))
                            .contains("same file was repeatedly mutated")
                        && ((List<?>) event.data().get("evidence"))
                            .contains("editChurnCount=3/3")));
  }

  @Test
  void goal_decision_uses_configured_stale_explanation_limit() {
    stubTaskRow();
    WorkerResult staleExplanation =
        WorkerResult.builder()
            .outcome(WorkerResult.Outcome.STOPPED_WITHOUT_COMPLETE)
            .iterations(6)
            .toolCallsDispatched(0)
            .finalAssistantText("I already explained the implementation is complete.")
            .repeatedAssistantTextCount(2)
            .toolCallLog(List.of())
            .totalLatencyMs(12_000L)
            .build();
    when(worker.run(any(WorkerTaskRequest.class), any())).thenReturn(staleExplanation);

    SequentialExecutor executor = newExecutor();
    ReflectionTestUtils.setField(executor, "maxGoalNoProgressIterations", 120);
    ReflectionTestUtils.setField(executor, "maxGoalStaleExplanations", 2);
    executor.execute(req(singleTaskPlan(), true));

    verify(broadcaster)
        .emit(
            anyString(),
            argThat(
                event ->
                    isGoalDecisionEvent(event)
                        && "WASTE_RISK".equals(event.data().get("state"))
                        && "STOP_WASTE_GUARD".equals(event.data().get("recommendedDecision"))
                        && String.valueOf(event.data().get("reason"))
                            .contains("same assistant explanation repeated")
                        && ((List<?>) event.data().get("evidence"))
                            .contains("staleExplanationCount=2/2")));
  }

  @Test
  void goal_decision_marks_clear_environment_failures_blocked() {
    stubTaskRow();
    when(worker.run(any(WorkerTaskRequest.class), any())).thenReturn(workerCompleted(1));
    when(worker.run(any(WorkerTaskRequest.class))).thenReturn(workerCompleted(1));
    when(buildProjectTool.execute(any(), any()))
        .thenReturn(
            new ToolResult.Ok("exit=1\nMissing API key: ANTHROPIC_API_KEY is not configured"),
            new ToolResult.Ok("exit=1\nMissing API key: ANTHROPIC_API_KEY is not configured"));

    SequentialExecutor executor = newExecutor();
    executor.execute(req(singleTaskPlan(), true));

    verify(broadcaster)
        .emit(
            anyString(),
            argThat(
                event ->
                    isGoalDecisionEvent(event)
                        && "BLOCKED".equals(event.data().get("state"))
                        && "STOP_BLOCKED".equals(event.data().get("recommendedDecision"))
                        && Boolean.TRUE.equals(event.data().get("userInputRequired"))
                        && String.valueOf(event.data().get("reason"))
                            .contains("blocked by environment")
                        && ((List<?>) event.data().get("evidence"))
                            .contains("blockedByEnvironment=true")));
  }

  @Test
  void goal_decision_marks_clear_unsafe_continuation_blocked() {
    stubTaskRow();
    when(worker.run(any(WorkerTaskRequest.class), any())).thenReturn(workerCompleted(1));
    when(worker.run(any(WorkerTaskRequest.class))).thenReturn(workerCompleted(1));
    when(buildProjectTool.execute(any(), any()))
        .thenReturn(
            new ToolResult.Ok("exit=1\nPath traversal not allowed: ../secrets.env"),
            new ToolResult.Ok("exit=1\nPath traversal not allowed: ../secrets.env"));

    SequentialExecutor executor = newExecutor();
    executor.execute(req(singleTaskPlan(), true));

    verify(broadcaster)
        .emit(
            anyString(),
            argThat(
                event ->
                    isGoalDecisionEvent(event)
                        && "BLOCKED".equals(event.data().get("state"))
                        && "STOP_BLOCKED".equals(event.data().get("recommendedDecision"))
                        && Boolean.TRUE.equals(event.data().get("userInputRequired"))
                        && String.valueOf(event.data().get("reason"))
                            .contains("unsafe or outside the goal scope")
                        && ((List<?>) event.data().get("evidence"))
                            .contains("unsafeContinuation=true")));
  }

  @Test
  void goal_decision_uses_configured_cost_ceiling_evidence() {
    stubTaskRow();
    when(worker.run(any(WorkerTaskRequest.class), any())).thenReturn(workerCompleted(2));
    when(worker.run(any(WorkerTaskRequest.class))).thenReturn(workerCompleted(1));
    when(buildProjectTool.execute(any(), any()))
        .thenReturn(
            new ToolResult.Ok("exit=1\nSyntaxError: unexpected token"),
            new ToolResult.Ok("exit=1\nSyntaxError: unexpected token"));
    when(costTracker.snapshot(anyString()))
        .thenReturn(
            new com.navneet.executespec.cost.RunCostTracker.Snapshot(120, 40, 0, 0, 0.25, 2));

    SequentialExecutor executor = newExecutor();
    ReflectionTestUtils.setField(executor, "maxGoalTotalCostUsd", 0.10);
    ExecutionResult result = executor.execute(req(singleTaskPlan(), true));

    assertEquals(TaskOutcome.Status.COMPLETED_WITH_WARNING, result.perTask().get(0).status());
    verify(broadcaster)
        .emit(
            anyString(),
            argThat(
                event ->
                    isGoalDecisionEvent(event)
                        && "WASTE_RISK".equals(event.data().get("state"))
                        && "STOP_WASTE_GUARD".equals(event.data().get("recommendedDecision"))
                        && String.valueOf(event.data().get("reason"))
                            .contains("configured total cost ceiling reached")
                        && ((List<?>) event.data().get("evidence")).contains("costUsd=0.250000")
                        && Double.valueOf(0.25).equals(event.data().get("costUsd"))
                        && Integer.valueOf(160).equals(event.data().get("totalTokens"))));
  }

  @Test
  void goal_decision_uses_configured_token_ceiling_evidence() {
    stubTaskRow();
    when(worker.run(any(WorkerTaskRequest.class), any())).thenReturn(workerCompleted(2));
    when(worker.run(any(WorkerTaskRequest.class))).thenReturn(workerCompleted(1));
    when(buildProjectTool.execute(any(), any()))
        .thenReturn(
            new ToolResult.Ok("exit=1\nSyntaxError: unexpected token"),
            new ToolResult.Ok("exit=1\nSyntaxError: unexpected token"));
    when(costTracker.snapshot(anyString()))
        .thenReturn(
            new com.navneet.executespec.cost.RunCostTracker.Snapshot(100, 80, 10, 15, 0.01, 2));

    SequentialExecutor executor = newExecutor();
    ReflectionTestUtils.setField(executor, "maxGoalTotalTokens", 200);
    executor.execute(req(singleTaskPlan(), true));

    verify(broadcaster)
        .emit(
            anyString(),
            argThat(
                event ->
                    isGoalDecisionEvent(event)
                        && "STOP_WASTE_GUARD".equals(event.data().get("recommendedDecision"))
                        && String.valueOf(event.data().get("reason"))
                            .contains("configured total token ceiling reached")
                        && ((List<?>) event.data().get("evidence"))
                            .contains("totalTrackedTokens=205")
                        && Integer.valueOf(205).equals(event.data().get("totalTokens"))));
  }

  @Test
  void goal_decision_uses_configured_task_wall_clock_ceiling_evidence() {
    stubTaskRow();
    when(worker.run(any(WorkerTaskRequest.class), any())).thenReturn(workerCompleted(2, 25_000L));
    when(worker.run(any(WorkerTaskRequest.class))).thenReturn(workerCompleted(1));
    when(buildProjectTool.execute(any(), any()))
        .thenReturn(
            new ToolResult.Ok("exit=1\nSyntaxError: unexpected token"),
            new ToolResult.Ok("exit=1\nSyntaxError: unexpected token"));

    SequentialExecutor executor = newExecutor();
    ReflectionTestUtils.setField(executor, "maxGoalTaskWallClockMs", 10_000L);
    executor.execute(req(singleTaskPlan(), true));

    verify(broadcaster)
        .emit(
            anyString(),
            argThat(
                event ->
                    isGoalDecisionEvent(event)
                        && "STOP_WASTE_GUARD".equals(event.data().get("recommendedDecision"))
                        && String.valueOf(event.data().get("reason"))
                            .contains("configured task wall-clock ceiling reached")
                        && ((List<?>) event.data().get("evidence")).contains("wallClockMs=20000")
                        && Long.valueOf(20_000L).equals(event.data().get("wallClockMs"))));
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
    verify(broadcaster)
        .emit(
            anyString(),
            argThat(
                event ->
                    isGoalDecisionEvent(event)
                        && "DONE".equals(event.data().get("state"))
                        && "STOP_DONE".equals(event.data().get("recommendedDecision"))
                        && String.valueOf(event.data().get("recommendedNextStep"))
                            .contains("No further work")
                        && "goal satisfied with validation evidence and required outputs present"
                            .equals(event.data().get("reason"))));
    verify(broadcaster)
        .emit(
            anyString(),
            argThat(
                event ->
                    isContinuationDecisionLog(event)
                        && event.payload().contains("STOP_DONE")
                        && event.payload().contains("goal satisfied with validation evidence")));
  }

  @Test
  void continuation_decision_uses_candidate_backed_advisor_when_available() {
    stubTaskRow();
    when(worker.run(any(WorkerTaskRequest.class), any())).thenReturn(workerCompleted(2));
    when(worker.run(any(WorkerTaskRequest.class))).thenReturn(workerCompleted(1));
    when(buildProjectTool.execute(any(), any()))
        .thenReturn(
            new ToolResult.Ok("exit=1\nSyntaxError: unexpected token"),
            new ToolResult.Ok("exit=1\nSyntaxError: unexpected token"),
            new ToolResult.Ok("exit=0\nbuild ok"));
    GoalContinuationAdvisor advisor = mock(GoalContinuationAdvisor.class);
    when(advisor.advise(any()))
        .thenReturn(
            new GoalContinuationPolicyDecision(
                RunContinuationDecision.STEP_UP_MODEL,
                Optional.of(candidate("openai", "gpt-5.4")),
                List.of("quality is insufficient; stronger eligible model clears threshold")),
            new GoalContinuationPolicyDecision(
                RunContinuationDecision.STOP_DONE,
                Optional.empty(),
                List.of("goal satisfied with validation evidence and required outputs present")));

    SequentialExecutor executor = newExecutor(advisor);
    ExecutionResult result = executor.execute(req(singleTaskPlan(), true));

    assertEquals(TaskOutcome.Status.SUCCESS, result.perTask().get(0).status());
    assertEquals("openai", result.perTask().get(0).dispatchedProvider());
    assertEquals("gpt-5.4", result.perTask().get(0).dispatchedModel());
    verify(worker, times(2)).run(any(WorkerTaskRequest.class), any());
    ArgumentCaptor<GoalContinuationAdvisorRequest> requestCaptor =
        ArgumentCaptor.forClass(GoalContinuationAdvisorRequest.class);
    verify(advisor, times(2)).advise(requestCaptor.capture());
    GoalContinuationAdvisorRequest request = requestCaptor.getAllValues().get(0);
    assertEquals("anthropic", request.currentProvider());
    assertEquals("claude-haiku-4-5-20251001", request.currentModel());
    assertEquals("TASK_LOW", request.taskCategory());
    assertTrue(request.qualityInsufficient());
    assertEquals(70.0, request.requiredQualityThreshold());
    assertEquals(1, request.maxProviderSwitches());
    assertEquals(1, request.maxModelStepUps());
    assertEquals(1, request.maxModelStepDowns());
    assertTrue(
        saveLog.stream()
            .anyMatch(
                s ->
                    s.taskKey().endsWith("_T1")
                        && s.goalDonenessJson() != null
                        && s.goalDonenessJson().contains("\"state\":\"NOT_DONE_BUT_PROGRESSING\"")
                        && s.goalDonenessJson().contains("\"recommendedNextStep\":")
                        && s.continuationDecisionJson() != null
                        && s.continuationDecisionJson().contains("\"decision\":\"STEP_UP_MODEL\"")
                        && s.continuationDecisionJson()
                            .contains("\"requiredQualityThreshold\":70.0")
                        && s.continuationDecisionJson().contains("\"maxProviderSwitches\":1")
                        && s.continuationDecisionJson().contains("\"maxModelStepUps\":1")
                        && s.continuationDecisionJson().contains("\"maxModelStepDowns\":1")),
        "Expected persisted goal/continuation JSON on T1. Saw: " + saveLog);
    verify(broadcaster)
        .emit(
            anyString(),
            argThat(
                event ->
                    isContinuationDecisionLog(event)
                        && event.payload().contains("STEP_UP_MODEL")
                        && event.payload().contains("stronger eligible model clears threshold")));
    verify(broadcaster)
        .emit(
            anyString(),
            argThat(
                event ->
                    isContinuationDecisionEvent(event)
                        && "STEP_UP_MODEL".equals(event.data().get("decision"))
                        && ((List<?>) event.data().get("reasons"))
                            .contains(
                                "quality is insufficient; stronger eligible model clears"
                                    + " threshold")
                        && ((java.util.Map<?, ?>) event.data().get("policy")).containsValue(70.0)));
  }

  @Test
  void continuation_decision_uses_configured_advisory_limits() {
    stubTaskRow();
    when(worker.run(any(WorkerTaskRequest.class), any())).thenReturn(workerCompleted(2));
    when(worker.run(any(WorkerTaskRequest.class))).thenReturn(workerCompleted(1));
    when(buildProjectTool.execute(any(), any()))
        .thenReturn(
            new ToolResult.Ok("exit=1\nSyntaxError: unexpected token"),
            new ToolResult.Ok("exit=1\nSyntaxError: unexpected token"));
    GoalContinuationAdvisor advisor = mock(GoalContinuationAdvisor.class);
    when(advisor.advise(any()))
        .thenReturn(
            new GoalContinuationPolicyDecision(
                RunContinuationDecision.STOP_BLOCKED,
                Optional.empty(),
                List.of("expensive continuation requires user confirmation before more spend")));

    SequentialExecutor executor = newExecutor(advisor);
    ReflectionTestUtils.setField(executor, "goalRequiredQualityThreshold", 85.0);
    ReflectionTestUtils.setField(executor, "advisoryProviderSwitchLimit", 0);
    ReflectionTestUtils.setField(executor, "advisoryModelStepUpLimit", 2);
    ReflectionTestUtils.setField(executor, "advisoryModelStepDownLimit", 3);
    ReflectionTestUtils.setField(executor, "maxGoalNoProgressIterations", 44);
    ReflectionTestUtils.setField(executor, "maxGoalFalseDoneClaims", 5);
    ReflectionTestUtils.setField(executor, "maxGoalRepeatedFailures", 7);
    ReflectionTestUtils.setField(executor, "maxGoalEditChurn", 8);
    ReflectionTestUtils.setField(executor, "maxGoalParallelTasks", 6);
    ReflectionTestUtils.setField(executor, "requireConfirmationBeforeExpensiveContinuation", true);
    ReflectionTestUtils.setField(executor, "maxGoalTotalCostUsd", 1.25);
    ReflectionTestUtils.setField(executor, "maxGoalTotalTokens", 12_345);
    ReflectionTestUtils.setField(executor, "maxGoalTaskWallClockMs", 67_890L);
    executor.execute(req(singleTaskPlan(), true));

    ArgumentCaptor<GoalContinuationAdvisorRequest> requestCaptor =
        ArgumentCaptor.forClass(GoalContinuationAdvisorRequest.class);
    verify(advisor).advise(requestCaptor.capture());
    GoalContinuationAdvisorRequest request = requestCaptor.getValue();
    assertEquals(85.0, request.requiredQualityThreshold());
    assertEquals(0, request.maxProviderSwitches());
    assertEquals(2, request.maxModelStepUps());
    assertEquals(3, request.maxModelStepDowns());
    assertTrue(request.expensiveContinuationRequiresConfirmation());
    assertTrue(
        saveLog.stream()
            .anyMatch(
                s ->
                    s.taskKey().endsWith("_T1")
                        && s.continuationDecisionJson() != null
                        && s.continuationDecisionJson().contains("\"decision\":\"STOP_BLOCKED\"")
                        && s.continuationDecisionJson()
                            .contains("\"requiredQualityThreshold\":85.0")
                        && s.continuationDecisionJson().contains("\"maxProviderSwitches\":0")
                        && s.continuationDecisionJson().contains("\"maxModelStepUps\":2")
                        && s.continuationDecisionJson().contains("\"maxModelStepDowns\":3")
                        && s.continuationDecisionJson().contains("\"maxNoProgressIterations\":44")
                        && s.continuationDecisionJson().contains("\"maxFalseDoneClaims\":5")
                        && s.continuationDecisionJson().contains("\"maxRepeatedFailures\":7")
                        && s.continuationDecisionJson().contains("\"maxEditChurn\":8")
                        && s.continuationDecisionJson().contains("\"maxParallelTasks\":6")
                        && s.continuationDecisionJson().contains("\"maxTotalCostUsd\":1.25")
                        && s.continuationDecisionJson().contains("\"maxTotalTokens\":12345")
                        && s.continuationDecisionJson().contains("\"maxTaskWallClockMs\":67890")
                        && s.continuationDecisionJson()
                            .contains("\"requireConfirmationBeforeExpensiveContinuation\":true")),
        "Expected configured continuation policy in persisted JSON. Saw: " + saveLog);

    verify(broadcaster)
        .emit(
            anyString(),
            argThat(
                event -> {
                  if (!isContinuationDecisionEvent(event)) {
                    return false;
                  }
                  @SuppressWarnings("unchecked")
                  java.util.Map<String, Object> policy =
                      (java.util.Map<String, Object>) event.data().get("policy");
                  return policy != null
                      && Double.valueOf(85.0).equals(policy.get("requiredQualityThreshold"))
                      && Integer.valueOf(0).equals(policy.get("maxProviderSwitches"))
                      && Integer.valueOf(2).equals(policy.get("maxModelStepUps"))
                      && Integer.valueOf(3).equals(policy.get("maxModelStepDowns"))
                      && Integer.valueOf(44).equals(policy.get("maxNoProgressIterations"))
                      && Integer.valueOf(5).equals(policy.get("maxFalseDoneClaims"))
                      && Integer.valueOf(7).equals(policy.get("maxRepeatedFailures"))
                      && Integer.valueOf(8).equals(policy.get("maxEditChurn"))
                      && Integer.valueOf(6).equals(policy.get("maxParallelTasks"))
                      && Double.valueOf(1.25).equals(policy.get("maxTotalCostUsd"))
                      && Integer.valueOf(12_345).equals(policy.get("maxTotalTokens"))
                      && Long.valueOf(67_890L).equals(policy.get("maxTaskWallClockMs"))
                      && Boolean.TRUE.equals(
                          policy.get("requireConfirmationBeforeExpensiveContinuation"));
                }));
  }

  @Test
  void continuation_decision_uses_per_run_confirmation_requirement() {
    stubTaskRow();
    when(worker.run(any(WorkerTaskRequest.class), any())).thenReturn(workerCompleted(2));
    when(worker.run(any(WorkerTaskRequest.class))).thenReturn(workerCompleted(1));
    when(buildProjectTool.execute(any(), any()))
        .thenReturn(
            new ToolResult.Ok("exit=1\nSyntaxError: unexpected token"),
            new ToolResult.Ok("exit=1\nSyntaxError: unexpected token"));
    GoalContinuationAdvisor advisor = mock(GoalContinuationAdvisor.class);
    when(advisor.advise(any()))
        .thenReturn(
            new GoalContinuationPolicyDecision(
                RunContinuationDecision.STOP_BLOCKED,
                Optional.of(candidate("openai", "gpt-5.4")),
                List.of(
                    "expensive continuation requires user confirmation before more spend;"
                        + " proposed action STEP_UP_MODEL -> openai/gpt-5.4")));

    SequentialExecutor executor = newExecutor(advisor);
    executor.execute(req(singleTaskPlan(), true, true));

    ArgumentCaptor<GoalContinuationAdvisorRequest> requestCaptor =
        ArgumentCaptor.forClass(GoalContinuationAdvisorRequest.class);
    verify(advisor).advise(requestCaptor.capture());
    assertTrue(requestCaptor.getValue().expensiveContinuationRequiresConfirmation());
    assertTrue(
        saveLog.stream()
            .anyMatch(
                s ->
                    s.taskKey().endsWith("_T1")
                        && s.continuationDecisionJson() != null
                        && s.continuationDecisionJson().contains("\"decision\":\"STOP_BLOCKED\"")
                        && s.continuationDecisionJson().contains("\"provider\":\"openai\"")
                        && s.continuationDecisionJson()
                            .contains("\"requireConfirmationBeforeExpensiveContinuation\":true")),
        "Expected per-run confirmation requirement in persisted continuation JSON. Saw: "
            + saveLog);
  }

  @Test
  void continuation_decision_uses_category_specific_quality_threshold() {
    stubTaskRow();
    when(worker.run(any(WorkerTaskRequest.class), any())).thenReturn(workerCompleted(2));
    when(worker.run(any(WorkerTaskRequest.class))).thenReturn(workerCompleted(1));
    when(buildProjectTool.execute(any(), any()))
        .thenReturn(
            new ToolResult.Ok("exit=1\nSyntaxError: unexpected token"),
            new ToolResult.Ok("exit=1\nSyntaxError: unexpected token"));
    GoalContinuationAdvisor advisor = mock(GoalContinuationAdvisor.class);
    when(advisor.advise(any()))
        .thenReturn(
            new GoalContinuationPolicyDecision(
                RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER,
                Optional.empty(),
                List.of("continue with current candidate")));

    SequentialExecutor executor = newExecutor(advisor);
    ReflectionTestUtils.setField(executor, "goalRequiredQualityThreshold", 70.0);
    ReflectionTestUtils.setField(executor, "goalRequiredQualityThresholdTaskLow", 64.0);
    executor.execute(req(singleTaskPlan(), true));

    ArgumentCaptor<GoalContinuationAdvisorRequest> requestCaptor =
        ArgumentCaptor.forClass(GoalContinuationAdvisorRequest.class);
    verify(advisor).advise(requestCaptor.capture());
    GoalContinuationAdvisorRequest request = requestCaptor.getValue();
    assertEquals("TASK_LOW", request.taskCategory());
    assertEquals(64.0, request.requiredQualityThreshold());
    assertTrue(
        saveLog.stream()
            .anyMatch(
                s ->
                    s.taskKey().endsWith("_T1")
                        && s.continuationDecisionJson() != null
                        && s.continuationDecisionJson().contains("\"taskCategory\":\"TASK_LOW\"")
                        && s.continuationDecisionJson()
                            .contains("\"requiredQualityThreshold\":64.0")
                        && s.continuationDecisionJson()
                            .contains("\"defaultRequiredQualityThreshold\":70.0")
                        && s.continuationDecisionJson().contains("\"TASK_LOW\":64.0")),
        "Expected category-specific continuation policy in persisted JSON. Saw: " + saveLog);

    verify(broadcaster)
        .emit(
            anyString(),
            argThat(
                event -> {
                  if (!isContinuationDecisionEvent(event)) {
                    return false;
                  }
                  @SuppressWarnings("unchecked")
                  java.util.Map<String, Object> policy =
                      (java.util.Map<String, Object>) event.data().get("policy");
                  if (policy == null) {
                    return false;
                  }
                  @SuppressWarnings("unchecked")
                  java.util.Map<String, Object> thresholds =
                      (java.util.Map<String, Object>) policy.get("requiredQualityThresholds");
                  return "TASK_LOW".equals(policy.get("taskCategory"))
                      && Double.valueOf(64.0).equals(policy.get("requiredQualityThreshold"))
                      && Double.valueOf(70.0).equals(policy.get("defaultRequiredQualityThreshold"))
                      && thresholds != null
                      && Double.valueOf(64.0).equals(thresholds.get("TASK_LOW"));
                }));
  }

  @Test
  void continuation_decision_uses_semantic_category_quality_threshold() {
    stubTaskRow();
    when(worker.run(any(WorkerTaskRequest.class), any())).thenReturn(workerCompleted(2));
    when(worker.run(any(WorkerTaskRequest.class))).thenReturn(workerCompleted(1));
    when(buildProjectTool.execute(any(), any()))
        .thenReturn(
            new ToolResult.Ok("exit=1\nNullPointerException"),
            new ToolResult.Ok("exit=1\nNullPointerException"));
    GoalContinuationAdvisor advisor = mock(GoalContinuationAdvisor.class);
    when(advisor.advise(any()))
        .thenReturn(
            new GoalContinuationPolicyDecision(
                RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER,
                Optional.empty(),
                List.of("continue with current candidate")));

    SequentialExecutor executor = newExecutor(advisor);
    ReflectionTestUtils.setField(executor, "goalRequiredQualityThreshold", 70.0);
    ReflectionTestUtils.setField(executor, "goalRequiredQualityThresholdTaskLow", 64.0);
    ReflectionTestUtils.setField(executor, "goalRequiredQualityThresholdBugFix", 88.0);
    executor.execute(req(bugFixPlan(), true));

    ArgumentCaptor<GoalContinuationAdvisorRequest> requestCaptor =
        ArgumentCaptor.forClass(GoalContinuationAdvisorRequest.class);
    verify(advisor).advise(requestCaptor.capture());
    GoalContinuationAdvisorRequest request = requestCaptor.getValue();
    assertEquals("BUG_FIX", request.taskCategory());
    assertEquals(88.0, request.requiredQualityThreshold());
    assertTrue(
        saveLog.stream()
            .anyMatch(
                s ->
                    s.taskKey().endsWith("_T1")
                        && s.continuationDecisionJson() != null
                        && s.continuationDecisionJson().contains("\"taskCategory\":\"BUG_FIX\"")
                        && s.continuationDecisionJson()
                            .contains("\"requiredQualityThreshold\":88.0")
                        && s.continuationDecisionJson().contains("\"BUG_FIX\":88.0")
                        && s.continuationDecisionJson().contains("\"TASK_LOW\":64.0")),
        "Expected semantic category continuation policy in persisted JSON. Saw: " + saveLog);
  }

  @Test
  void continuation_acting_reruns_once_with_selected_candidate_by_default() {
    stubTaskRow();
    when(worker.run(any(WorkerTaskRequest.class), any()))
        .thenReturn(workerCompleted(2), workerCompleted(1));
    when(worker.run(any(WorkerTaskRequest.class))).thenReturn(workerCompleted(1));
    when(buildProjectTool.execute(any(), any()))
        .thenReturn(
            new ToolResult.Ok("exit=1\nSyntaxError: unexpected token"),
            new ToolResult.Ok("exit=1\nSyntaxError: unexpected token"),
            new ToolResult.Ok("exit=0\nbuild ok"));
    GoalContinuationAdvisor advisor = mock(GoalContinuationAdvisor.class);
    when(advisor.advise(any()))
        .thenReturn(
            new GoalContinuationPolicyDecision(
                RunContinuationDecision.STEP_UP_MODEL,
                Optional.of(candidate("openai", "gpt-5.4")),
                List.of("quality is insufficient; stronger eligible model clears threshold")),
            new GoalContinuationPolicyDecision(
                RunContinuationDecision.STOP_DONE,
                Optional.empty(),
                List.of("goal satisfied with validation evidence and required outputs present")));

    SequentialExecutor executor = newExecutor(advisor);
    ExecutionResult result = executor.execute(req(singleTaskPlan(), true));

    assertEquals(ExecutionResult.Overall.ALL_SUCCESS, result.overall());
    assertEquals(TaskOutcome.Status.SUCCESS, result.perTask().get(0).status());
    assertEquals("openai", result.perTask().get(0).dispatchedProvider());
    assertEquals("gpt-5.4", result.perTask().get(0).dispatchedModel());
    assertEquals(45, result.perTask().get(0).toolCallsDispatched());
    ArgumentCaptor<WorkerTaskRequest> requestCaptor =
        ArgumentCaptor.forClass(WorkerTaskRequest.class);
    verify(worker, times(2)).run(requestCaptor.capture(), any());
    assertEquals("anthropic", requestCaptor.getAllValues().get(0).provider());
    assertEquals("claude-haiku-4-5-20251001", requestCaptor.getAllValues().get(0).model());
    assertEquals("openai", requestCaptor.getAllValues().get(1).provider());
    assertEquals("gpt-5.4", requestCaptor.getAllValues().get(1).model());
    verify(broadcaster)
        .emit(
            anyString(),
            argThat(
                event ->
                    isContinuationActionEvent(event)
                        && "STEP_UP_MODEL".equals(event.data().get("decision"))
                        && "openai".equals(event.data().get("selectedProvider"))
                        && "gpt-5.4".equals(event.data().get("selectedModel"))
                        && Integer.valueOf(1).equals(event.data().get("attempt"))
                        && Integer.valueOf(1).equals(event.data().get("maxAttempts"))));
    verify(broadcaster)
        .emit(
            anyString(),
            argThat(
                event ->
                    event != null
                        && "LOG".equals(event.type())
                        && event.payload() != null
                        && event
                            .payload()
                            .contains(
                                "Continuation action: STEP_UP_MODEL -> rerun task with"
                                    + " openai/gpt-5.4")));
  }

  @Test
  void continuation_acting_skips_step_up_when_step_up_limit_exhausted() {
    stubTaskRow();
    when(worker.run(any(WorkerTaskRequest.class), any())).thenReturn(workerCompleted(2));
    when(worker.run(any(WorkerTaskRequest.class))).thenReturn(workerCompleted(1));
    when(buildProjectTool.execute(any(), any()))
        .thenReturn(
            new ToolResult.Ok("exit=1\nSyntaxError: unexpected token"),
            new ToolResult.Ok("exit=1\nSyntaxError: unexpected token"));
    GoalContinuationAdvisor advisor = mock(GoalContinuationAdvisor.class);
    when(advisor.advise(any()))
        .thenReturn(
            new GoalContinuationPolicyDecision(
                RunContinuationDecision.STEP_UP_MODEL,
                Optional.of(candidate("openai", "gpt-5.4")),
                List.of("quality is insufficient; stronger eligible model clears threshold")));

    SequentialExecutor executor = newExecutor(advisor);
    ReflectionTestUtils.setField(executor, "continuationActingEnabled", true);
    ReflectionTestUtils.setField(executor, "advisoryModelStepUpLimit", 0);
    ExecutionResult result = executor.execute(req(singleTaskPlan(), true));

    assertEquals(ExecutionResult.Overall.PARTIAL_WITH_WARNINGS, result.overall());
    assertEquals(TaskOutcome.Status.COMPLETED_WITH_WARNING, result.perTask().get(0).status());
    assertEquals("anthropic", result.perTask().get(0).dispatchedProvider());
    assertEquals("claude-haiku-4-5-20251001", result.perTask().get(0).dispatchedModel());
    verify(worker, times(1)).run(any(WorkerTaskRequest.class), any());
    verify(broadcaster)
        .emit(
            anyString(),
            argThat(
                event ->
                    isContinuationActionSkippedEvent(event)
                        && "STEP_UP_MODEL".equals(event.data().get("decision"))
                        && "openai".equals(event.data().get("selectedProvider"))
                        && "gpt-5.4".equals(event.data().get("selectedModel"))
                        && "maxModelStepUps".equals(event.data().get("limitName"))
                        && Integer.valueOf(0).equals(event.data().get("usedLimit"))
                        && Integer.valueOf(0).equals(event.data().get("maxLimit"))));
  }

  @Test
  void continuation_acting_consumes_run_level_provider_switch_limit() {
    stubTaskRow();
    when(worker.run(any(WorkerTaskRequest.class), any()))
        .thenReturn(workerHitIterLimit(0), workerCompleted(1), workerHitIterLimit(0));
    GoalContinuationAdvisor advisor = mock(GoalContinuationAdvisor.class);
    when(advisor.advise(any()))
        .thenReturn(
            new GoalContinuationPolicyDecision(
                RunContinuationDecision.CHANGE_PROVIDER,
                Optional.of(candidate("openai", "gpt-5.4")),
                List.of("provider behavior is poor; selected better eligible provider")),
            new GoalContinuationPolicyDecision(
                RunContinuationDecision.STOP_DONE,
                Optional.empty(),
                List.of("goal satisfied with validation evidence and required outputs present")),
            new GoalContinuationPolicyDecision(
                RunContinuationDecision.CHANGE_PROVIDER,
                Optional.of(candidate("openai", "gpt-5.4")),
                List.of("provider behavior is poor; selected better eligible provider")));

    SequentialExecutor executor = newExecutor(advisor);
    ReflectionTestUtils.setField(executor, "continuationActingEnabled", true);
    ReflectionTestUtils.setField(executor, "advisoryProviderSwitchLimit", 1);
    ExecutionResult result = executor.execute(req(multiTaskPlan(), false));

    assertEquals(2, result.perTask().size());
    assertEquals(TaskOutcome.Status.SUCCESS, result.perTask().get(0).status());
    assertEquals("openai", result.perTask().get(0).dispatchedProvider());
    assertEquals(TaskOutcome.Status.WORKER_FAILED, result.perTask().get(1).status());
    assertEquals("anthropic", result.perTask().get(1).dispatchedProvider());
    ArgumentCaptor<WorkerTaskRequest> requestCaptor =
        ArgumentCaptor.forClass(WorkerTaskRequest.class);
    verify(worker, times(3)).run(requestCaptor.capture(), any());
    assertEquals("anthropic", requestCaptor.getAllValues().get(0).provider());
    assertEquals("openai", requestCaptor.getAllValues().get(1).provider());
    assertEquals("anthropic", requestCaptor.getAllValues().get(2).provider());
    verify(broadcaster)
        .emit(
            anyString(),
            argThat(
                event ->
                    isContinuationActionSkippedEvent(event)
                        && "CHANGE_PROVIDER".equals(event.data().get("decision"))
                        && "maxProviderSwitches".equals(event.data().get("limitName"))
                        && Integer.valueOf(1).equals(event.data().get("usedLimit"))
                        && Integer.valueOf(1).equals(event.data().get("maxLimit"))));
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

  private static boolean isGoalDecisionEvent(RunEvent event) {
    return event != null && "GOAL_DECISION".equals(event.type()) && event.data() != null;
  }

  private static boolean isContinuationDecisionLog(RunEvent event) {
    return event != null
        && "LOG".equals(event.type())
        && event.payload() != null
        && event.payload().startsWith("Continuation decision:");
  }

  private static boolean isContinuationDecisionEvent(RunEvent event) {
    return event != null && "CONTINUATION_DECISION".equals(event.type()) && event.data() != null;
  }

  private static boolean isContinuationActionEvent(RunEvent event) {
    return event != null && "CONTINUATION_ACTION".equals(event.type()) && event.data() != null;
  }

  private static boolean isContinuationActionSkippedEvent(RunEvent event) {
    return event != null
        && "CONTINUATION_ACTION_SKIPPED".equals(event.type())
        && event.data() != null;
  }
}
