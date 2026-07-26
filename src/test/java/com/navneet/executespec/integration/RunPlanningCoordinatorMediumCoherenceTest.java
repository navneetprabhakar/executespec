package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.Classification;
import com.navneet.executespec.agent.CoherencePlannerAgent;
import com.navneet.executespec.agent.OutlinePlannerAgent;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlanStrategy;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.agent.PlannerRequest;
import com.navneet.executespec.agent.RequirementSpec;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;

class RunPlanningCoordinatorMediumCoherenceTest {

  @Test
  void mediumFullPlanRunsDocumentedCoherencePhase() {
    Fixture fixture = fixture();
    Plan outline = plan("outline");
    Plan patched = plan("patched");
    when(fixture.outline().plan(any(), eq(OutlinePlannerAgent.Mode.FULL_PLAN))).thenReturn(outline);
    when(fixture.coherence().patchIfNeeded(eq(outline), any(), any()))
        .thenReturn(new CoherencePlannerAgent.PatchResult(patched, 2, List.of("safe")));

    Plan result =
        fixture
            .coordinator()
            .runOutlinePhase(
                "run-medium", request(PlanStrategy.MEDIUM), OutlinePlannerAgent.Mode.FULL_PLAN);

    assertThat(result).isSameAs(patched);
    verify(fixture.coherence()).patchIfNeeded(eq(outline), any(), any());
  }

  @Test
  void smallFullPlanGetsDeterministicSafetyWithoutModelCoherenceSpend() {
    Fixture fixture = fixture();
    Plan outline = reservationPlan();
    when(fixture.outline().plan(any(), eq(OutlinePlannerAgent.Mode.FULL_PLAN))).thenReturn(outline);

    Plan result =
        fixture
            .coordinator()
            .runOutlinePhase(
                "run-small", request(PlanStrategy.SMALL), OutlinePlannerAgent.Mode.FULL_PLAN);

    assertThat(result.tasks().getFirst().hardConstraints())
        .anyMatch(value -> value.contains("quantity <= 0"))
        .anyMatch(value -> value.contains("verified credential"));
    verify(fixture.coherence(), never()).patchIfNeeded(any(), any(), any());
  }

  private static Fixture fixture() {
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
    OutlinePlannerAgent outline = mock(OutlinePlannerAgent.class);
    CoherencePlannerAgent coherence = mock(CoherencePlannerAgent.class);
    RunCostTracker costs = mock(RunCostTracker.class);
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(runRepo.findById(any())).thenReturn(Optional.empty());
    when(costs.snapshot(any()))
        .thenReturn(
            new RunCostTracker.Snapshot(0, 0, 0, 0, 0, 0),
            new RunCostTracker.Snapshot(100, 20, 0, 80, 0.01, 1),
            new RunCostTracker.Snapshot(130, 25, 0, 100, 0.011, 2));
    RunPlanningCoordinator coordinator =
        new RunPlanningCoordinator(
            runRepo,
            new ObjectMapper(),
            costs,
            null,
            outline,
            null,
            null,
            null,
            coherence,
            null,
            null,
            flags,
            null,
            null);
    return new Fixture(coordinator, outline, coherence);
  }

  private static PlannerRequest request(PlanStrategy strategy) {
    return PlannerRequest.builder()
        .spec("Build a reservation service")
        .classification(Classification.MEDIUM)
        .planStrategy(strategy)
        .provider("deepseek")
        .model("deepseek-v4-flash-native")
        .context(new ExecutionContext("run", "__planner__", Path.of("/tmp/repo")))
        .build();
  }

  private static Plan plan(String title) {
    return Plan.builder()
        .planTitle(title)
        .tasks(List.of(PlannedTask.builder().id("T1").title("Task").description("Do work").build()))
        .build();
  }

  private static Plan reservationPlan() {
    return Plan.builder()
        .planTitle("reservation")
        .requirements(
            List.of(
                new RequirementSpec(
                    "R1",
                    "POST /reservations is scoped to the authenticated customer with atomic stock"
                        + " decrement.",
                    RequirementSpec.RequirementKind.FUNCTIONAL)))
        .tasks(
            List.of(
                PlannedTask.builder()
                    .id("T1")
                    .title("Reservation API")
                    .description("Implement POST /reservations with quantity and pytest coverage.")
                    .touchesFiles(List.of("app/routes.py", "tests/test_routes.py"))
                    .build()))
        .build();
  }

  private record Fixture(
      RunPlanningCoordinator coordinator,
      OutlinePlannerAgent outline,
      CoherencePlannerAgent coherence) {}
}
