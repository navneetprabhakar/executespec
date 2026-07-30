package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

class RunEffectiveRouteServiceTest {

  private final RunStepUsageRepository repository = mock(RunStepUsageRepository.class);
  private final RunEffectiveRouteService service = new RunEffectiveRouteService(repository);

  @Test
  void plannerProjectionCoversOutlineExpansionAndCoherenceButNotClassifier() {
    when(repository.findByRunIdOrderByCreatedAtAsc("run-1"))
        .thenReturn(
            List.of(
                row("classifier", "SmartClassifierAgent", "anthropic", "haiku", "SUCCESS"),
                row("planner", "OutlinePlannerAgent", "openai", "gpt-5.4", "SUCCESS"),
                row("expander", "TaskExpanderAgent", "openai", "gpt-5.4", "SUCCESS"),
                row("coherence", "CoherencePlannerAgent", "openai", "gpt-5.4", "SUCCESS")));

    EffectiveRouteProjection projection = service.forPlanner("run-1");

    assertThat(projection.attribution()).isEqualTo(EffectiveRouteProjection.Attribution.LEDGER);
    assertThat(projection.provider()).isEqualTo("openai");
    assertThat(projection.model()).isEqualTo("gpt-5.4");
  }

  @Test
  void plannerProjectionIsMixedWhenExpansionOrCoherenceFailsOver() {
    when(repository.findByRunIdOrderByCreatedAtAsc("run-1"))
        .thenReturn(
            List.of(
                row("planner", "OutlinePlannerAgent", "anthropic", "sonnet", "SUCCESS"),
                row("expander", "TaskExpanderAgent", "openai", "gpt-5.4", "SUCCESS"),
                row("coherence", "CoherencePlannerAgent", "openai", "gpt-5.4", "SUCCESS")));

    EffectiveRouteProjection projection = service.forPlanner("run-1");

    assertThat(projection.attribution()).isEqualTo(EffectiveRouteProjection.Attribution.MIXED);
    assertThat(projection.provider()).isNull();
    assertThat(projection.model()).isNull();
  }

  @Test
  void reviewerProjectionIncludesReviewerPassesAndIgnoresWorkerRows() {
    when(repository.findByRunIdOrderByCreatedAtAsc("run-1"))
        .thenReturn(
            List.of(
                row("T1", "WorkerAgent", "anthropic", "sonnet", "SUCCESS"),
                row("run-reviewer-general", "ReviewerAgent", "gemini", "flash", "SUCCESS")));

    EffectiveRouteProjection projection = service.forReviewer("run-1");

    assertThat(projection.attribution()).isEqualTo(EffectiveRouteProjection.Attribution.LEDGER);
    assertThat(projection.provider()).isEqualTo("gemini");
    assertThat(projection.model()).isEqualTo("flash");
  }

  @Test
  void latestSuccessfulTaskRouteUsesTerminalPhysicalRouteAfterFailover() {
    when(repository.findByRunIdAndTaskIdOrderByCreatedAtAsc("run-1", "T1"))
        .thenReturn(
            List.of(
                row("T1", "WorkerAgent", "deepseek", "deepseek-v4", "FAILED"),
                row("T1", "WorkerAgent", "deepseek", "deepseek-v4", "SUCCESS"),
                row("T1", "WorkerAgent", "openrouter", "glm-5.2", "SUCCESS")));

    EffectiveRouteProjection projection = service.latestSuccessfulForTask("run-1", "T1");

    assertThat(projection.attribution()).isEqualTo(EffectiveRouteProjection.Attribution.LEDGER);
    assertThat(projection.provider()).isEqualTo("openrouter");
    assertThat(projection.model()).isEqualTo("glm-5.2");
  }

  @Test
  void attemptFenceRejectsPriorSuccessfulRouteAndProjectsOnlyNewCallIndexes() {
    RunStepUsage prior = attemptedRow("prior", "openrouter", "x-ai/grok-4.20", "SUCCESS", 7);
    RunStepUsage current = attemptedRow("current", "openrouter", "deepseek-v4-pro", "SUCCESS", 8);
    when(repository.findByRunIdAndTaskIdOrderByCreatedAtAsc("run-1", "T1"))
        .thenReturn(List.of(prior), List.of(prior, current));

    RunEffectiveRouteService.AttemptFence fence = service.captureAttemptFence("run-1", "T1", 7);
    EffectiveRouteProjection projection = service.successfulForTaskAfter(fence);

    assertThat(fence.priorAttemptIds()).containsExactly("prior");
    assertThat(projection.attribution()).isEqualTo(EffectiveRouteProjection.Attribution.LEDGER);
    assertThat(projection.provider()).isEqualTo("openrouter");
    assertThat(projection.model()).isEqualTo("deepseek-v4-pro");
  }

  @Test
  void attemptFenceIsUnattributedWhenNoNewSuccessfulProviderCallExists() {
    RunStepUsage prior = attemptedRow("prior", "openrouter", "x-ai/grok-4.20", "SUCCESS", 7);
    when(repository.findByRunIdAndTaskIdOrderByCreatedAtAsc("run-1", "T1"))
        .thenReturn(List.of(prior));

    RunEffectiveRouteService.AttemptFence fence = service.captureAttemptFence("run-1", "T1", 7);
    EffectiveRouteProjection projection = service.successfulForTaskAfter(fence);

    assertThat(projection).isEqualTo(EffectiveRouteProjection.UNATTRIBUTED);
  }

  @Test
  void reviewerFenceIgnoresOldDeepSeekHistoryAndAttestsOnlyNewGrokAttempts() {
    RunStepUsage oldReviewer =
        attemptedRow(
            "review-old",
            "__root__-reviewer-general",
            "ReviewerAgent",
            "openrouter",
            "deepseek-v4-pro",
            "SUCCESS",
            5);
    RunStepUsage newReviewer =
        attemptedRow(
            "review-new",
            "__root__-reviewer-general",
            "ReviewerAgent",
            "openrouter",
            "x-ai/grok-4.20",
            "SUCCESS",
            9);
    when(repository.findByRunIdOrderByCreatedAtAsc("run-1"))
        .thenReturn(List.of(oldReviewer), List.of(oldReviewer, newReviewer));

    RunEffectiveRouteService.ReviewerAttemptFence fence =
        service.captureReviewerAttemptFence("run-1", 5);
    RunEffectiveRouteService.AttemptRouteEvidence evidence =
        service.successfulReviewerAttemptAfter(fence);

    assertThat(fence.priorAttemptIds()).containsExactly("review-old");
    assertThat(evidence.isExact("openrouter", "x-ai/grok-4.20")).isTrue();
    assertThat(evidence.physicalAttemptIds()).containsExactly("review-new");
    assertThat(evidence.firstCallIndex()).isEqualTo(9);
    assertThat(evidence.lastCallIndex()).isEqualTo(9);
  }

  @Test
  void persistedAttemptIdsMustAllRehydrateFromTheSameCanonicalRunLedger() {
    RunStepUsage worker =
        attemptedRow(
            "worker-new", "T1", "WorkerAgent", "openrouter", "x-ai/grok-4.20", "SUCCESS", 8);
    when(repository.findByRunIdAndPhysicalAttemptIdIn(
            "run-1", java.util.Set.of("worker-new", "missing")))
        .thenReturn(List.of(worker));

    assertThat(service.forPhysicalAttempts("run-1", List.of("worker-new", "missing")))
        .isEqualTo(RunEffectiveRouteService.AttemptRouteEvidence.UNATTRIBUTED);

    when(repository.findByRunIdAndPhysicalAttemptIdIn("run-1", java.util.Set.of("worker-new")))
        .thenReturn(List.of(worker));
    assertThat(
            service
                .forPhysicalAttempts("run-1", List.of("worker-new"))
                .isExact("openrouter", "x-ai/grok-4.20"))
        .isTrue();
  }

  private static RunStepUsage row(
      String taskId, String agentRole, String provider, String model, String status) {
    return RunStepUsage.builder()
        .id(taskId + ":" + provider + ":" + status)
        .runId("run-1")
        .taskId(taskId)
        .agentRole(agentRole)
        .provider(provider)
        .model(model)
        .attemptStatus(status)
        .build();
  }

  private static RunStepUsage attemptedRow(
      String physicalAttemptId, String provider, String model, String status, long callIndex) {
    return attemptedRow(physicalAttemptId, "T1", "WorkerAgent", provider, model, status, callIndex);
  }

  private static RunStepUsage attemptedRow(
      String physicalAttemptId,
      String taskId,
      String agentRole,
      String provider,
      String model,
      String status,
      long callIndex) {
    return RunStepUsage.builder()
        .id(physicalAttemptId)
        .physicalAttemptId(physicalAttemptId)
        .runId("run-1")
        .taskId(taskId)
        .agentRole(agentRole)
        .provider(provider)
        .model(model)
        .attemptStatus(status)
        .callIndex(callIndex)
        .build();
  }
}
