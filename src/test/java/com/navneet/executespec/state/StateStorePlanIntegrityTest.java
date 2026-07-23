package com.navneet.executespec.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.model.TaskDefinition;
import com.navneet.executespec.agent.model.TaskPlan;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentMessageRepository;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.CompactionEventRepository;
import com.navneet.executespec.state.repository.GateEventRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.state.repository.PlanVersionRepository;
import com.navneet.executespec.state.repository.PromptAuditRepository;
import com.navneet.executespec.state.repository.RunRecoveryRepository;

import org.junit.jupiter.api.Test;

/**
 * State-integrity fixes: RR-072 (legacy completion honours FAILED tasks), RR-073 (plan graph
 * validation + idempotent prefixing), RR-074 (retry ownership guard), RR-075 (run-scoped subtask
 * ids), RR-076 (unknown model attribution).
 */
class StateStorePlanIntegrityTest {

  private final PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
  private final AgentTaskRepository taskRepo = mock(AgentTaskRepository.class);

  private StateStore store() {
    return new StateStore(
        runRepo,
        taskRepo,
        mock(AgentMessageRepository.class),
        mock(GateEventRepository.class),
        mock(CompactionEventRepository.class),
        new ObjectMapper(),
        mock(RunRecoveryRepository.class),
        mock(PromptAuditRepository.class),
        mock(AppUserRepository.class),
        mock(PlanVersionRepository.class),
        mock(ModelRegistry.class));
  }

  private static AgentTask task(String taskId, String runId, String status) {
    return AgentTask.builder().taskId(taskId).runId(runId).status(status).build();
  }

  // ---- RR-072: FAILED (and any non-clean) task downgrades markComplete to PARTIAL ----

  @Test
  void markComplete_downgradesToPartial_whenNonGapTaskFailed() {
    PipelineRun run = PipelineRun.builder().runId("run-1").build();
    when(runRepo.findById("run-1")).thenReturn(Optional.of(run));
    when(taskRepo.findByRunId("run-1"))
        .thenReturn(
            List.of(task("run-1_a", "run-1", "COMPLETE"), task("run-1_b", "run-1", "FAILED")));

    store().markComplete("run-1");

    assertThat(run.getStatus()).isEqualTo("PARTIAL");
  }

  @Test
  void markComplete_marksComplete_whenAllTasksCleanSuccess() {
    PipelineRun run = PipelineRun.builder().runId("run-2").build();
    when(runRepo.findById("run-2")).thenReturn(Optional.of(run));
    when(taskRepo.findByRunId("run-2"))
        .thenReturn(
            List.of(task("run-2_a", "run-2", "COMPLETE"), task("run-2_b", "run-2", "SKIPPED")));

    store().markComplete("run-2");

    assertThat(run.getStatus()).isEqualTo("COMPLETE");
  }

  @Test
  void markComplete_ignoresFailedGapTasks() {
    PipelineRun run = PipelineRun.builder().runId("run-3").build();
    when(runRepo.findById("run-3")).thenReturn(Optional.of(run));
    when(taskRepo.findByRunId("run-3"))
        .thenReturn(
            List.of(
                task("run-3_a", "run-3", "COMPLETE"),
                task("run-3_gap_x", "run-3", "FAILED"))); // gap failures do not downgrade

    store().markComplete("run-3");

    assertThat(run.getStatus()).isEqualTo("COMPLETE");
  }

  // ---- RR-074: retry with a mismatched (runId, taskId) pair mutates nothing ----

  @Test
  void markRetryingWithFeedback_ignoresTaskOwnedByAnotherRun() {
    AgentTask foreign = task("t-1", "run-OTHER", "FAILED");
    foreign.setInputTokens(500);
    foreign.setOutputTokens(700);
    when(taskRepo.findById("t-1")).thenReturn(Optional.of(foreign));
    when(runRepo.findById("run-1"))
        .thenReturn(Optional.of(PipelineRun.builder().runId("run-1").build()));

    store().markRetryingWithFeedback("run-1", "t-1", "please redo");

    // The foreign task must not be reset, and its tokens must never be saved.
    verify(taskRepo, never()).save(any(AgentTask.class));
    assertThat(foreign.getStatus()).isEqualTo("FAILED");
    assertThat(foreign.getInputTokens()).isEqualTo(500);
  }

  // ---- RR-075: run-scoped subtask id factory ----

  @Test
  void namespacedSubtaskId_prefixesUnderParentAndIsIdempotent() {
    String first = StateStore.namespacedSubtaskId("run1234_expand", "setup");
    assertThat(first).isEqualTo("run1234_expand::setup");
    // idempotent — a re-run must not double-namespace
    assertThat(StateStore.namespacedSubtaskId("run1234_expand", first)).isEqualTo(first);
  }

  @Test
  void namespacedSubtaskId_rejectsBlank() {
    assertThatThrownBy(() -> StateStore.namespacedSubtaskId("parent", "  "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- RR-073: idempotent run-prefixing ----

  @Test
  void prefixOnce_isIdempotent() {
    assertThat(StateStore.prefixOnce("ab12cd34_", "setup")).isEqualTo("ab12cd34_setup");
    assertThat(StateStore.prefixOnce("ab12cd34_", "ab12cd34_setup")).isEqualTo("ab12cd34_setup");
  }

  // ---- RR-073: plan graph validation ----

  private static TaskDefinition td(String id, List<String> deps) {
    return TaskDefinition.builder().taskId(id).title(id).dependsOn(deps).build();
  }

  @Test
  void planValidator_acceptsValidAcyclicGraph() {
    TaskPlan plan = TaskPlan.builder().tasks(List.of(td("a", null), td("b", List.of("a")))).build();
    PlanGraphValidator.validate(plan); // no throw
  }

  @Test
  void planValidator_rejectsDuplicateIds() {
    TaskPlan plan = TaskPlan.builder().tasks(List.of(td("a", null), td("a", null))).build();
    assertThatThrownBy(() -> PlanGraphValidator.validate(plan))
        .isInstanceOf(PlanGraphValidator.InvalidPlanException.class)
        .hasMessageContaining("duplicate");
  }

  @Test
  void planValidator_rejectsUnknownDependency() {
    TaskPlan plan = TaskPlan.builder().tasks(List.of(td("a", List.of("ghost")))).build();
    assertThatThrownBy(() -> PlanGraphValidator.validate(plan))
        .isInstanceOf(PlanGraphValidator.InvalidPlanException.class)
        .hasMessageContaining("unknown task");
  }

  @Test
  void planValidator_rejectsCycle() {
    TaskPlan plan =
        TaskPlan.builder().tasks(List.of(td("a", List.of("b")), td("b", List.of("a")))).build();
    assertThatThrownBy(() -> PlanGraphValidator.validate(plan))
        .isInstanceOf(PlanGraphValidator.InvalidPlanException.class)
        .hasMessageContaining("cycle");
  }

  // ---- RR-076: missing attribution is UNKNOWN/UNATTRIBUTED, never Anthropic ----

  @Test
  void modelComparisonKey_bucketsMissingAttributionAsUnknown() {
    assertThat(StateStore.modelComparisonKey(task("t", "r", "COMPLETE")))
        .isEqualTo(StateStore.UNKNOWN_PROVIDER + "/" + StateStore.UNATTRIBUTED_MODEL);

    AgentTask attributed = task("t", "r", "COMPLETE");
    attributed.setProvider("openai");
    attributed.setModel("gpt-5.4");
    assertThat(StateStore.modelComparisonKey(attributed)).isEqualTo("openai/gpt-5.4");
  }
}
