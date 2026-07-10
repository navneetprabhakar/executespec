package com.navneet.executespec.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.context.ContextStore;
import com.navneet.executespec.context.SavedContext;
import com.navneet.executespec.harness.SequentialExecutor.ResumeRequest;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins {@link SequentialExecutor#resolveResumeAction} key-format normalisation introduced
 * 2026-05-10 after dev run 7483896a re-ran every task on a per-task retry.
 *
 * <p>Pre-fix bug:
 *
 * <ul>
 *   <li>{@code taskId} parameter was the bare logical plan id ("T12" — comes from {@link
 *       com.navneet.executespec.agent.PlannedTask#id()}).
 *   <li>{@link AgentTaskRepository#findByRunIdAndTaskId} queries on the {@code task_id} column
 *       which holds the FULL prefixed primary key (runShort + "_" + plannedId, e.g.
 *       "7483896a_T12").
 *   <li>Lookup never matched → {@code persisted} always null → {@code isTerminal=false} → every
 *       non-target task hit the {@code RUN_FRESH} branch and got re-executed.
 * </ul>
 *
 * <p>Production impact: retryTask(T12) caused T1..T20 to all re-run; ~$5.65 spent before manual
 * cancel. Latent on {@code StartupRunResumer.resumeAfterCrash} too — every crash recovery silently
 * re-ran the whole plan instead of skipping COMPLETE tasks.
 *
 * <p>Tests use reflection to invoke the private {@code resolveResumeAction} so the public {@code
 * execute} contract doesn't have to be reshaped just to make the resume logic unit-testable. The
 * method returns a {@code ResumeAction} record we inspect via getter access.
 */
class SequentialExecutorResumeKeyTest {

  @TempDir Path tempDir;

  private static final String RUN_ID = "7483896a-efd6-46be-9ce7-035902797c02";
  private static final String RUN_SHORT = "7483896a";
  private static final String TASK_ID = "T12";
  private static final String FULL_KEY = RUN_SHORT + "_" + TASK_ID;

  private SequentialExecutor newExecutorWith(AgentTaskRepository taskRepo) {
    return newExecutorWith(taskRepo, mock(ContextStore.class));
  }

  private SequentialExecutor newExecutorWith(
      AgentTaskRepository taskRepo, ContextStore contextStore) {
    // Constructor args follow @RequiredArgsConstructor field-declaration order:
    // worker, buildProjectTool, objectMapper, metrics, broadcaster, taskRepo,
    // modelResolver, retrievalService, contextStore, costTracker. Only taskRepo
    // + contextStore are touched by resolveResumeAction; the rest are null.
    return new SequentialExecutor(
        null, // worker
        null, // buildProjectTool
        null, // objectMapper
        null, // metrics
        null, // broadcaster
        taskRepo,
        null, // modelResolver
        null, // matrixProps
        null, // retrievalService
        null, // inLoopTestRunner
        mock(com.navneet.executespec.config.featureflag.FeatureFlagService.class),
        contextStore,
        null /* costTracker */,
        null /* gcsTaskCheckpointService */,
        null /* availableProviderResolver */,
        null /* goalContinuationAdvisor */,
        null);
  }

  @Test
  void continuationHandoffLoadsLatestWorkerSnapshotForSameTask() {
    AgentTaskRepository repo = mock(AgentTaskRepository.class);
    ContextStore contextStore = mock(ContextStore.class);
    SequentialExecutor exec = newExecutorWith(repo, contextStore);
    ExecutionContext runContext = new ExecutionContext(RUN_ID, "__root__", Path.of("/tmp/ws"));
    PlannedTask task =
        PlannedTask.builder().id(TASK_ID).title("Fix auth").description("Fix auth").build();
    Plan plan = Plan.builder().planTitle("handoff").tasks(List.of(task)).build();
    ExecutionRequest request =
        ExecutionRequest.builder()
            .plan(plan)
            .provider("anthropic")
            .model("claude-sonnet-5")
            .runContext(runContext)
            .build();
    SavedContext snapshot =
        SavedContext.builder()
            .runId(RUN_ID)
            .taskId(TASK_ID)
            .iteration(15)
            .history(List.of())
            .savedAt(Instant.now())
            .sizeBytes(128)
            .build();
    ExecutionContext taskContext = new ExecutionContext(RUN_ID, TASK_ID, Path.of("/tmp/ws"));
    when(contextStore.load(taskContext)).thenReturn(Optional.of(snapshot));

    SavedContext actual = exec.loadContinuationSnapshot(request, task);

    assertEquals(snapshot, actual);
    verify(contextStore).load(taskContext);
  }

  @Test
  void continuationHandoffRejectsStaleEvidenceBundleBeforeLoadingSnapshot() throws Exception {
    AgentTaskRepository repo = mock(AgentTaskRepository.class);
    ContextStore contextStore = mock(ContextStore.class);
    SequentialExecutor exec = newExecutorWith(repo, contextStore);
    ExecutionContext runContext = new ExecutionContext(RUN_ID, "__root__", tempDir);
    PlannedTask task =
        PlannedTask.builder().id(TASK_ID).title("Fix auth").description("Fix auth").build();
    Plan plan = Plan.builder().planTitle("handoff").tasks(List.of(task)).build();
    ExecutionRequest request =
        ExecutionRequest.builder()
            .plan(plan)
            .provider("anthropic")
            .model("claude-sonnet-5")
            .runContext(runContext)
            .build();
    Path contextDir = Files.createDirectories(tempDir.resolve(".agent-context"));
    Files.writeString(contextDir.resolve("evidence-bundle.json"), "{}\n");

    assertNull(exec.loadContinuationSnapshot(request, task));
    verify(contextStore, org.mockito.Mockito.never())
        .load(org.mockito.ArgumentMatchers.any(ExecutionContext.class));
  }

  /**
   * Reflectively invoke {@code resolveResumeAction(runId, taskId, resume)} and return the {@code
   * ResumeKind} for assertion. Keeps the production method package-private (it is) without widening
   * to public just for tests.
   */
  private String resumeKind(
      SequentialExecutor exec, String runId, String taskId, ResumeRequest resume) throws Exception {
    Method m =
        SequentialExecutor.class.getDeclaredMethod(
            "resolveResumeAction", String.class, String.class, ResumeRequest.class);
    m.setAccessible(true);
    Object action = m.invoke(exec, runId, taskId, resume);
    assertNotNull(action, "resolveResumeAction returned null");
    Method kindM = action.getClass().getDeclaredMethod("kind");
    kindM.setAccessible(true);
    Object kind = kindM.invoke(action);
    return kind == null ? null : kind.toString();
  }

  private AgentTask taskWithStatus(String fullKey, String status) {
    AgentTask t = new AgentTask();
    t.setTaskId(fullKey);
    t.setRunId(RUN_ID);
    t.setStatus(status);
    return t;
  }

  // Pre-fix regression: terminal tasks were skipped ONLY when the lookup matched

  @Test
  void terminal_task_in_db_is_skipped_when_target_is_full_prefixed_key() throws Exception {
    AgentTaskRepository repo = mock(AgentTaskRepository.class);
    when(repo.findByRunIdAndTaskId(RUN_ID, FULL_KEY))
        .thenReturn(Optional.of(taskWithStatus(FULL_KEY, "COMPLETE")));

    SequentialExecutor exec = newExecutorWith(repo);
    // Caller (retryTask) passes the full prefixed key as target. The non-target task ("T11") is
    // already COMPLETE in DB → must be SKIP_TERMINAL after the fix.
    String kind = resumeKind(exec, RUN_ID, "T11", new ResumeRequest(FULL_KEY));
    // T11 isn't the target — but it IS terminal → SKIP_TERMINAL.
    when(repo.findByRunIdAndTaskId(RUN_ID, RUN_SHORT + "_T11"))
        .thenReturn(Optional.of(taskWithStatus(RUN_SHORT + "_T11", "COMPLETE")));
    kind = resumeKind(exec, RUN_ID, "T11", new ResumeRequest(FULL_KEY));
    assertEquals("SKIP_TERMINAL", kind);
  }

  @Test
  void terminal_task_in_db_is_skipped_when_target_is_bare_logical_id() throws Exception {
    AgentTaskRepository repo = mock(AgentTaskRepository.class);
    when(repo.findByRunIdAndTaskId(RUN_ID, RUN_SHORT + "_T7"))
        .thenReturn(Optional.of(taskWithStatus(RUN_SHORT + "_T7", "COMPLETE")));

    SequentialExecutor exec = newExecutorWith(repo);
    // Same surface, but caller passes the BARE id as target — should also work post-fix.
    String kind = resumeKind(exec, RUN_ID, "T7", new ResumeRequest("T12"));
    assertEquals("SKIP_TERMINAL", kind);
  }

  @Test
  void partial_task_in_db_is_skipped() throws Exception {
    AgentTaskRepository repo = mock(AgentTaskRepository.class);
    when(repo.findByRunIdAndTaskId(RUN_ID, RUN_SHORT + "_T17"))
        .thenReturn(Optional.of(taskWithStatus(RUN_SHORT + "_T17", "PARTIAL")));

    SequentialExecutor exec = newExecutorWith(repo);
    String kind = resumeKind(exec, RUN_ID, "T17", new ResumeRequest(FULL_KEY));
    // PARTIAL is in the terminal set (line 264 of SequentialExecutor) → SKIP_TERMINAL.
    assertEquals("SKIP_TERMINAL", kind);
  }

  @Test
  void failed_task_in_db_is_skipped_when_not_target() throws Exception {
    AgentTaskRepository repo = mock(AgentTaskRepository.class);
    when(repo.findByRunIdAndTaskId(RUN_ID, RUN_SHORT + "_T18"))
        .thenReturn(Optional.of(taskWithStatus(RUN_SHORT + "_T18", "FAILED")));

    SequentialExecutor exec = newExecutorWith(repo);
    // T18 is FAILED in DB but the caller asked to retry T12 — T18 must be skipped.
    String kind = resumeKind(exec, RUN_ID, "T18", new ResumeRequest(FULL_KEY));
    assertEquals("SKIP_TERMINAL", kind);
  }

  // Resume target must execute, not skip

  @Test
  void resume_target_with_full_prefixed_id_runs_fresh_when_pending() throws Exception {
    AgentTaskRepository repo = mock(AgentTaskRepository.class);
    when(repo.findByRunIdAndTaskId(RUN_ID, FULL_KEY))
        .thenReturn(Optional.of(taskWithStatus(FULL_KEY, "PENDING")));

    SequentialExecutor exec = newExecutorWith(repo);
    // retryTask reset T12 to PENDING + dispatched with target="7483896a_T12".
    String kind = resumeKind(exec, RUN_ID, "T12", new ResumeRequest(FULL_KEY));
    // No snapshot in Redis → RUN_FRESH (not SKIP_TERMINAL).
    assertEquals("RUN_FRESH", kind);
  }

  @Test
  void resume_target_with_bare_id_runs_fresh_when_pending() throws Exception {
    AgentTaskRepository repo = mock(AgentTaskRepository.class);
    when(repo.findByRunIdAndTaskId(RUN_ID, FULL_KEY))
        .thenReturn(Optional.of(taskWithStatus(FULL_KEY, "PENDING")));

    SequentialExecutor exec = newExecutorWith(repo);
    // Caller passes bare "T12" as target — also a valid format post-fix.
    String kind = resumeKind(exec, RUN_ID, "T12", new ResumeRequest("T12"));
    assertEquals("RUN_FRESH", kind);
  }

  // No-target (auto-detect) crash-recovery path

  @Test
  void null_target_with_terminal_task_skips() throws Exception {
    AgentTaskRepository repo = mock(AgentTaskRepository.class);
    when(repo.findByRunIdAndTaskId(RUN_ID, RUN_SHORT + "_T5"))
        .thenReturn(Optional.of(taskWithStatus(RUN_SHORT + "_T5", "COMPLETE")));

    SequentialExecutor exec = newExecutorWith(repo);
    // StartupRunResumer crash-recovery passes ResumeRequest(null) — the executor auto-detects
    // the first non-terminal task. T5 is COMPLETE → must skip, not become the auto-target.
    String kind = resumeKind(exec, RUN_ID, "T5", new ResumeRequest(null));
    assertEquals("SKIP_TERMINAL", kind);
  }

  @Test
  void null_target_with_pending_task_runs_fresh() throws Exception {
    AgentTaskRepository repo = mock(AgentTaskRepository.class);
    when(repo.findByRunIdAndTaskId(RUN_ID, RUN_SHORT + "_T6"))
        .thenReturn(Optional.of(taskWithStatus(RUN_SHORT + "_T6", "PENDING")));

    SequentialExecutor exec = newExecutorWith(repo);
    // Auto-detect path: first non-terminal becomes the target. No snapshot → RUN_FRESH.
    String kind = resumeKind(exec, RUN_ID, "T6", new ResumeRequest(null));
    assertEquals("RUN_FRESH", kind);
  }

  // DB row missing entirely (legacy / never persisted)

  @Test
  void task_not_in_db_falls_through_to_run_fresh_for_target() throws Exception {
    AgentTaskRepository repo = mock(AgentTaskRepository.class);
    when(repo.findByRunIdAndTaskId(RUN_ID, FULL_KEY)).thenReturn(Optional.empty());

    SequentialExecutor exec = newExecutorWith(repo);
    // Legacy run / never persisted — and caller targeted this task. Run fresh.
    String kind = resumeKind(exec, RUN_ID, "T12", new ResumeRequest(FULL_KEY));
    assertEquals("RUN_FRESH", kind);
  }

  @Test
  void task_not_in_db_for_non_target_runs_fresh_too() throws Exception {
    // 2026-05-10 — pre-fix, this was the SAME outcome as the bug case (RUN_FRESH for non-target),
    // but the cause was different: pre-fix EVERY non-target task hit RUN_FRESH because the lookup
    // never matched. Post-fix, only tasks genuinely missing from DB hit this branch. Behaviour
    // is preserved for the legitimate "never persisted" case.
    AgentTaskRepository repo = mock(AgentTaskRepository.class);
    when(repo.findByRunIdAndTaskId(RUN_ID, RUN_SHORT + "_T3")).thenReturn(Optional.empty());

    SequentialExecutor exec = newExecutorWith(repo);
    String kind = resumeKind(exec, RUN_ID, "T3", new ResumeRequest(FULL_KEY));
    assertEquals("RUN_FRESH", kind);
  }
}
