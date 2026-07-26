package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.harness.TaskOutcome;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RunTokenAttributorTest {

  private static final String RUN_ID = "run-1";
  private static final String UUID_RUN_ID = "12345678-aaaa-bbbb-cccc-123456789abc";

  private final PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
  private final AgentTaskRepository taskRepo = mock(AgentTaskRepository.class);
  private final RunCostTracker costTracker = mock(RunCostTracker.class);
  private final RunStepUsageRepository usageRepo = mock(RunStepUsageRepository.class);
  private final RunEffectiveRouteService effectiveRoutes = new RunEffectiveRouteService(usageRepo);
  private final RunTokenAttributor attributor =
      new RunTokenAttributor(runRepo, taskRepo, costTracker, effectiveRoutes, usageRepo);

  @Test
  void terminalReconciliationRaisesRunTotalsFromCanonicalAttempts() {
    when(usageRepo.sumSuccessfulInputTokensByRunId(RUN_ID)).thenReturn(612_345L);
    when(usageRepo.sumSuccessfulOutputTokensByRunId(RUN_ID)).thenReturn(23_456L);
    when(usageRepo.sumKnownCostByRunId(RUN_ID)).thenReturn(0.08125d);

    attributor.reconcileRunTotalsFromLedger(RUN_ID);

    verify(runRepo).raiseUsageTotalsToAtLeast(RUN_ID, 612_345, 23_456, 0.08125d);
  }

  @Test
  void lateMaterializationReconcilesOnlyAfterRunIsTerminal() {
    when(runRepo.findById(RUN_ID))
        .thenReturn(Optional.of(PipelineRun.builder().runId(RUN_ID).status("CANCELLED").build()));
    when(usageRepo.sumSuccessfulInputTokensByRunId(RUN_ID)).thenReturn(12L);
    when(usageRepo.sumSuccessfulOutputTokensByRunId(RUN_ID)).thenReturn(3L);
    when(usageRepo.sumKnownCostByRunId(RUN_ID)).thenReturn(0.004d);

    attributor.reconcileRunTotalsIfTerminal(RUN_ID);

    verify(runRepo).raiseUsageTotalsToAtLeast(RUN_ID, 12, 3, 0.004d);
  }

  @Test
  void liveMaterializationDoesNotRunLedgerAggregates() {
    when(runRepo.findById(RUN_ID))
        .thenReturn(Optional.of(PipelineRun.builder().runId(RUN_ID).status("WORKING").build()));

    attributor.reconcileRunTotalsIfTerminal(RUN_ID);

    verify(usageRepo, never()).sumSuccessfulInputTokensByRunId(RUN_ID);
    verify(runRepo, never())
        .raiseUsageTotalsToAtLeast(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyDouble());
  }

  private static RunStepUsage successRow(String taskId, String provider, String model) {
    return RunStepUsage.builder()
        .id(taskId + ":" + provider)
        .runId(UUID_RUN_ID)
        .taskId(taskId)
        .provider(provider)
        .model(model)
        .attemptStatus("SUCCESS")
        .build();
  }

  private static ExecutionResult resultWithSingleTask(
      String taskId, String provider, String model) {
    TaskOutcome outcome =
        TaskOutcome.builder()
            .taskId(taskId)
            .status(TaskOutcome.Status.SUCCESS)
            .dispatchedProvider(provider)
            .dispatchedModel(model)
            .build();
    return ExecutionResult.builder().perTask(List.of(outcome)).build();
  }

  /**
   * Step 4 core pin: the ledger is the authority for the route that actually served a task. A
   * failed-over task must NOT be persisted under the tier-resolved requested route, because {@code
   * ModelProviderCandidateService} consumes task-level route rows as a quality prior.
   */
  @Test
  void attributeWorkerPersistsTheLedgerRouteNotTheRequestedDispatchedRoute() {
    AgentTask task = AgentTask.builder().taskId("12345678_T1").runId(UUID_RUN_ID).build();
    when(taskRepo.findById("12345678_T1")).thenReturn(Optional.of(task));
    when(costTracker.snapshotTask(UUID_RUN_ID, "T1"))
        .thenReturn(new RunCostTracker.Snapshot(10, 4, 0, 0, 0.0, 1));
    when(usageRepo.findByRunIdAndTaskIdOrderByCreatedAtAsc(UUID_RUN_ID, "T1"))
        .thenReturn(List.of(successRow("T1", "openrouter", "glm-5.2")));

    attributor.attributeWorker(
        UUID_RUN_ID,
        resultWithSingleTask("T1", "deepseek", "deepseek-v4-pro-native"),
        10,
        4,
        "DEEPSEEK",
        "deepseek-v4-pro-native");

    ArgumentCaptor<AgentTask> saved = ArgumentCaptor.forClass(AgentTask.class);
    verify(taskRepo).save(saved.capture());
    AgentTask persisted = saved.getValue();
    // Requested/tier-resolved columns keep their existing meaning.
    assertThat(persisted.getProvider()).isEqualTo("DEEPSEEK");
    assertThat(persisted.getModel()).isEqualTo("deepseek-v4-pro-native");
    // Effective columns carry the route that actually served the task.
    assertThat(persisted.getEffectiveProvider()).isEqualTo("OPENROUTER");
    assertThat(persisted.getEffectiveModel()).isEqualTo("glm-5.2");
    assertThat(persisted.getEffectiveRouteAttribution()).isEqualTo("LEDGER");
  }

  @Test
  void attributeWorkerFallsBackToTheRequestedRouteOnlyWhenTheLedgerIsEmpty() {
    AgentTask task = AgentTask.builder().taskId("12345678_T1").runId(UUID_RUN_ID).build();
    when(taskRepo.findById("12345678_T1")).thenReturn(Optional.of(task));
    when(costTracker.snapshotTask(UUID_RUN_ID, "T1"))
        .thenReturn(new RunCostTracker.Snapshot(10, 4, 0, 0, 0.0, 1));
    when(usageRepo.findByRunIdAndTaskIdOrderByCreatedAtAsc(UUID_RUN_ID, "T1"))
        .thenReturn(List.of());

    attributor.attributeWorker(
        UUID_RUN_ID,
        resultWithSingleTask("T1", "anthropic", "claude-sonnet-5"),
        10,
        4,
        "ANTHROPIC",
        "claude-sonnet-5");

    ArgumentCaptor<AgentTask> saved = ArgumentCaptor.forClass(AgentTask.class);
    verify(taskRepo).save(saved.capture());
    assertThat(saved.getValue().getEffectiveProvider()).isEqualTo("ANTHROPIC");
    assertThat(saved.getValue().getEffectiveModel()).isEqualTo("claude-sonnet-5");
    assertThat(saved.getValue().getEffectiveRouteAttribution()).isEqualTo("UNATTRIBUTED");
  }

  /**
   * A multi-route task must stay visibly multi-route. Collapsing it to one provider would defeat
   * the projection exactly where route truth matters most.
   */
  @Test
  void attributeWorkerRecordsAMultiRouteTaskAsMixedRatherThanPickingOneProvider() {
    AgentTask task = AgentTask.builder().taskId("12345678_T1").runId(UUID_RUN_ID).build();
    when(taskRepo.findById("12345678_T1")).thenReturn(Optional.of(task));
    when(costTracker.snapshotTask(UUID_RUN_ID, "T1"))
        .thenReturn(new RunCostTracker.Snapshot(10, 4, 0, 0, 0.0, 1));
    when(usageRepo.findByRunIdAndTaskIdOrderByCreatedAtAsc(UUID_RUN_ID, "T1"))
        .thenReturn(
            List.of(
                successRow("T1", "deepseek", "deepseek-v4-pro-native"),
                successRow("T1", "openrouter", "glm-5.2")));

    attributor.attributeWorker(
        UUID_RUN_ID,
        resultWithSingleTask("T1", "deepseek", "deepseek-v4-pro-native"),
        10,
        4,
        "DEEPSEEK",
        "deepseek-v4-pro-native");

    ArgumentCaptor<AgentTask> saved = ArgumentCaptor.forClass(AgentTask.class);
    verify(taskRepo).save(saved.capture());
    assertThat(saved.getValue().getEffectiveRouteAttribution()).isEqualTo("MIXED");
    assertThat(saved.getValue().getEffectiveProvider()).isNull();
    assertThat(saved.getValue().getEffectiveModel()).isNull();
  }

  /** Failed physical attempts did not serve the task and must not be projected as its route. */
  @Test
  void attributeWorkerIgnoresFailedAttemptsWhenProjectingTheEffectiveRoute() {
    AgentTask task = AgentTask.builder().taskId("12345678_T1").runId(UUID_RUN_ID).build();
    when(taskRepo.findById("12345678_T1")).thenReturn(Optional.of(task));
    when(costTracker.snapshotTask(UUID_RUN_ID, "T1"))
        .thenReturn(new RunCostTracker.Snapshot(10, 4, 0, 0, 0.0, 1));
    RunStepUsage failed =
        RunStepUsage.builder()
            .id("failed")
            .runId(UUID_RUN_ID)
            .taskId("T1")
            .provider("deepseek")
            .model("deepseek-v4-pro-native")
            .attemptStatus("FAILED")
            .build();
    when(usageRepo.findByRunIdAndTaskIdOrderByCreatedAtAsc(UUID_RUN_ID, "T1"))
        .thenReturn(List.of(failed, successRow("T1", "openrouter", "glm-5.2")));

    attributor.attributeWorker(
        UUID_RUN_ID,
        resultWithSingleTask("T1", "deepseek", "deepseek-v4-pro-native"),
        10,
        4,
        "DEEPSEEK",
        "deepseek-v4-pro-native");

    ArgumentCaptor<AgentTask> saved = ArgumentCaptor.forClass(AgentTask.class);
    verify(taskRepo).save(saved.capture());
    assertThat(saved.getValue().getEffectiveRouteAttribution()).isEqualTo("LEDGER");
    assertThat(saved.getValue().getEffectiveProvider()).isEqualTo("OPENROUTER");
  }

  @Test
  void attributePhaseAdditionalAddsToExistingReviewerColumnsAndRunTotalDeltaOnly() {
    PipelineRun run =
        PipelineRun.builder()
            .runId(RUN_ID)
            .reviewerInputTokens(100)
            .reviewerOutputTokens(20)
            .build();
    when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));

    attributor.attributePhaseAdditional(
        RUN_ID, RunTokenAttributor.Phase.REVIEWER, 30, 7, "ANTHROPIC", "claude-sonnet-4-6");

    ArgumentCaptor<PipelineRun> saved = ArgumentCaptor.forClass(PipelineRun.class);
    verify(runRepo).save(saved.capture());
    verify(runRepo).addTokens(RUN_ID, 30, 7);
    assertThat(saved.getValue().getReviewerInputTokens()).isEqualTo(130);
    assertThat(saved.getValue().getReviewerOutputTokens()).isEqualTo(27);
    assertThat(saved.getValue().getReviewerProvider()).isEqualTo("ANTHROPIC");
    assertThat(saved.getValue().getReviewerActualModel()).isEqualTo("claude-sonnet-4-6");
  }

  @Test
  void attributeWorkerAdditionalTaskUpdatesOnlyTargetTaskWithTaskSnapshot() {
    AgentTask task = AgentTask.builder().taskId("12345678_T2").runId(UUID_RUN_ID).build();
    when(taskRepo.findById("12345678_T2")).thenReturn(Optional.of(task));
    when(costTracker.snapshotTask(UUID_RUN_ID, "T2"))
        .thenReturn(new RunCostTracker.Snapshot(42, 9, 0, 0, 0.0, 1));

    attributor.attributeWorkerAdditionalTask(
        UUID_RUN_ID, "T2", 10, 3, "ANTHROPIC", "claude-sonnet-4-6");

    ArgumentCaptor<AgentTask> saved = ArgumentCaptor.forClass(AgentTask.class);
    verify(runRepo).addTokens(UUID_RUN_ID, 10, 3);
    verify(taskRepo).save(saved.capture());
    assertThat(saved.getValue().getTaskId()).isEqualTo("12345678_T2");
    assertThat(saved.getValue().getInputTokens()).isEqualTo(42);
    assertThat(saved.getValue().getOutputTokens()).isEqualTo(9);
    assertThat(saved.getValue().getProvider()).isEqualTo("ANTHROPIC");
    assertThat(saved.getValue().getModel()).isEqualTo("claude-sonnet-4-6");
  }

  /**
   * RR-1561 — the residual pass exists so per-task token columns reconcile to the worker aggregate.
   * Integer division discarded up to n-1 tokens per direction, which made exact reconciliation
   * impossible by construction. 7 residual input tokens across 3 tasks must land as 3/2/2, not
   * 2/2/2 with 1 silently lost.
   */
  @Test
  void theResidualRemainderIsDistributedSoPerTaskTokensReconcileToTheWorkerTotal() {
    List<TaskOutcome> outcomes =
        List.of(
            TaskOutcome.builder().taskId("T1").status(TaskOutcome.Status.SUCCESS).build(),
            TaskOutcome.builder().taskId("T2").status(TaskOutcome.Status.SUCCESS).build(),
            TaskOutcome.builder().taskId("T3").status(TaskOutcome.Status.SUCCESS).build());
    for (var outcome : outcomes) {
      String rowId = "12345678_" + outcome.taskId();
      when(taskRepo.findById(rowId))
          .thenReturn(Optional.of(AgentTask.builder().taskId(rowId).runId(UUID_RUN_ID).build()));
      // No per-task snapshot at all: the whole aggregate is residual.
      when(costTracker.snapshotTask(UUID_RUN_ID, outcome.taskId()))
          .thenReturn(new RunCostTracker.Snapshot(0, 0, 0, 0, 0.0, 0));
      when(usageRepo.findByRunIdAndTaskIdOrderByCreatedAtAsc(UUID_RUN_ID, outcome.taskId()))
          .thenReturn(List.of());
    }

    attributor.attributeWorker(
        UUID_RUN_ID,
        ExecutionResult.builder().perTask(outcomes).build(),
        7,
        5,
        "DEEPSEEK",
        "deepseek-v4-pro-native");

    ArgumentCaptor<AgentTask> saved = ArgumentCaptor.forClass(AgentTask.class);
    verify(taskRepo, org.mockito.Mockito.times(3)).save(saved.capture());
    int totalIn = saved.getAllValues().stream().mapToInt(AgentTask::getInputTokens).sum();
    int totalOut = saved.getAllValues().stream().mapToInt(AgentTask::getOutputTokens).sum();

    assertThat(totalIn)
        .as("per-task input tokens must sum exactly to the worker aggregate")
        .isEqualTo(7);
    assertThat(totalOut)
        .as("per-task output tokens must sum exactly to the worker aggregate")
        .isEqualTo(5);
  }
}
