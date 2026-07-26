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
}
