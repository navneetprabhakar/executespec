package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.navneet.executespec.agent.ExactPlannerRouteAttestation;
import com.navneet.executespec.agent.OutlinePlannerAgent;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.agent.PlannerRequest;
import com.navneet.executespec.agent.RequirementSpec;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.security.ContextSanitizer;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;

class RunPlanningCoordinatorExecutableStabilizationTest {

  @Test
  void cleanPlanCompilesWithoutProviderRepair() {
    Fixture fixture = fixture();
    Plan clean = plan("Implement the selected SQLite persistence stack.");

    Plan result =
        fixture
            .coordinator()
            .stabilizeExecutablePlan(
                "run", clean, request(), request().context(), recoveryPolicy());

    assertThat(result.tasks()).hasSize(1);
    verify(fixture.coherence(), never())
        .repairContractViolationsExact(any(), any(), any(), any(), any());
  }

  @Test
  void rejectedPlanGetsOneStrictExactRouteRepairBeforeGate() {
    Fixture fixture = fixture();
    Plan invalid = plan("Choose either PostgreSQL or SQLite before implementing persistence.");
    Plan repaired = plan("Implement the selected SQLite persistence stack.");
    ResolvedModel route = route();
    when(fixture.outline().resolveConfiguredRoute(any())).thenReturn(route);
    when(fixture.coherence().repairContractViolationsExact(any(), any(), any(), any(), eq(route)))
        .thenReturn(new CoherencePlannerAgent.PatchResult(repaired, 1, List.of("fixed")));

    Plan result =
        fixture
            .coordinator()
            .stabilizeExecutablePlan(
                "run", invalid, request(), request().context(), recoveryPolicy());

    assertThat(result.tasks().getFirst().description()).contains("selected SQLite");
    verify(fixture.coherence())
        .repairContractViolationsExact(eq(invalid), any(), any(), any(), eq(route));
  }

  @Test
  void exhaustedSameRouteRepairStopsWithTypedPolicyReason() {
    Fixture fixture = fixture();
    Plan invalid = plan("Choose either PostgreSQL or SQLite before implementing persistence.");
    ResolvedModel route = route();
    when(fixture.outline().resolveConfiguredRoute(any())).thenReturn(route);
    when(fixture.coherence().repairContractViolationsExact(any(), any(), any(), any(), eq(route)))
        .thenReturn(new CoherencePlannerAgent.PatchResult(invalid, 0, List.of()));
    when(fixture.recoverySelector().select(any(), eq(route), any()))
        .thenReturn(
            PlannerRecoveryRouteSelector.Selection.blocked(
                PlannerRecoveryRouteSelector.BlockReason.ROUTE_PINNED,
                "exact run route forbids escalation"));

    assertThatThrownBy(
            () ->
                fixture
                    .coordinator()
                    .stabilizeExecutablePlan(
                        "run", invalid, request(), request().context(), recoveryPolicy()))
        .isInstanceOf(PlannerRecoveryPolicyBlockedException.class)
        .satisfies(
            failure ->
                assertThat(((PlannerRecoveryPolicyBlockedException) failure).code())
                    .isEqualTo(PlannerRecoveryPolicyBlockedException.Code.ROUTE_PINNED))
        .hasMessageContaining("ROUTE_PINNED");
  }

  @Test
  void physicalRouteMismatchFailsClosedWithoutSelectingEscalation() {
    Fixture fixture = fixture();
    Plan invalid = plan("Choose either PostgreSQL or SQLite before implementing persistence.");
    ResolvedModel route = route();
    when(fixture.outline().resolveConfiguredRoute(any())).thenReturn(route);
    when(fixture.coherence().repairContractViolationsExact(any(), any(), any(), any(), eq(route)))
        .thenThrow(
            new ExactPlannerRouteAttestation.MismatchException(
                "exact planner repair route mismatch"));

    assertThatThrownBy(
            () ->
                fixture
                    .coordinator()
                    .stabilizeExecutablePlan(
                        "run", invalid, request(), request().context(), recoveryPolicy()))
        .isInstanceOf(PlannerRecoveryPolicyBlockedException.class)
        .satisfies(
            failure ->
                assertThat(((PlannerRecoveryPolicyBlockedException) failure).code())
                    .isEqualTo(PlannerRecoveryPolicyBlockedException.Code.PHYSICAL_ROUTE_MISMATCH));
    verify(fixture.recoverySelector(), never()).select(any(), any(), any());
  }

  @Test
  void exhaustedSameRouteRepairUsesOneRunAuthorizedMatrixEscalation() {
    Fixture fixture = fixture();
    Plan invalid = plan("Choose either PostgreSQL or SQLite before implementing persistence.");
    Plan repaired = plan("Implement the selected SQLite persistence stack.");
    ResolvedModel current = route();
    ResolvedModel stepUp =
        ResolvedModel.builder()
            .requestedProvider(LlmProvider.ANTHROPIC)
            .actualProvider(LlmProvider.ANTHROPIC)
            .model("claude-sonnet-4-6")
            .agentTier(AgentTier.PLANNING)
            .build();
    when(fixture.outline().resolveConfiguredRoute(any())).thenReturn(current);
    when(fixture.coherence().repairContractViolationsExact(any(), any(), any(), any(), eq(current)))
        .thenReturn(new CoherencePlannerAgent.PatchResult(invalid, 0, List.of()));
    when(fixture.recoverySelector().select(any(), eq(current), any()))
        .thenReturn(PlannerRecoveryRouteSelector.Selection.selected(stepUp, List.of("quality")));
    when(fixture.coherence().repairContractViolationsExact(any(), any(), any(), any(), eq(stepUp)))
        .thenReturn(new CoherencePlannerAgent.PatchResult(repaired, 1, List.of("fixed")));

    Plan result =
        fixture
            .coordinator()
            .stabilizeExecutablePlan(
                "run", invalid, request(), request().context(), recoveryPolicy());

    assertThat(result.tasks().getFirst().description()).contains("selected SQLite");
    verify(fixture.coherence())
        .repairContractViolationsExact(any(), any(), any(), any(), eq(stepUp));
  }

  @Test
  void fullPlanOmittingR7AndR9ReachesSharedSameRouteThenAuthorizedEscalation() {
    Fixture fixture = fixture();
    Plan invalid = ownershipPlan(List.of("R1"));
    Plan repaired = ownershipPlan(List.of("R1", "R7", "R9"));
    ResolvedModel current = route();
    ResolvedModel stepUp =
        ResolvedModel.builder()
            .requestedProvider(LlmProvider.ANTHROPIC)
            .actualProvider(LlmProvider.ANTHROPIC)
            .model("claude-sonnet-4-6")
            .agentTier(AgentTier.PLANNING)
            .build();
    when(fixture.outline().resolveConfiguredRoute(any())).thenReturn(current);
    when(fixture.coherence().repairContractViolationsExact(any(), any(), any(), any(), eq(current)))
        .thenReturn(new CoherencePlannerAgent.PatchResult(invalid, 0, List.of()));
    when(fixture.recoverySelector().select(any(), eq(current), any()))
        .thenReturn(PlannerRecoveryRouteSelector.Selection.selected(stepUp, List.of("quality")));
    when(fixture.coherence().repairContractViolationsExact(any(), any(), any(), any(), eq(stepUp)))
        .thenReturn(new CoherencePlannerAgent.PatchResult(repaired, 1, List.of("owned R7/R9")));

    Plan result =
        fixture
            .coordinator()
            .stabilizeExecutablePlan(
                "run", invalid, request(), request().context(), recoveryPolicy());

    assertThat(result.tasks().getFirst().meetsRequirements()).containsExactly("R1", "R7", "R9");
    verify(fixture.recoverySelector()).select(eq("run"), eq(current), any());
    verify(fixture.coherence())
        .repairContractViolationsExact(any(), any(), any(), any(), eq(stepUp));
  }

  private static Fixture fixture() {
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
    OutlinePlannerAgent outline = mock(OutlinePlannerAgent.class);
    CoherencePlannerAgent coherence = mock(CoherencePlannerAgent.class);
    PlannerRecoveryRouteSelector recoverySelector = mock(PlannerRecoveryRouteSelector.class);
    when(runRepo.findById(any())).thenReturn(Optional.empty());
    RunPlanningCoordinator coordinator =
        new RunPlanningCoordinator(
            runRepo,
            new ObjectMapper(),
            mock(RunCostTracker.class),
            null,
            outline,
            null,
            null,
            null,
            coherence,
            null,
            recoverySelector,
            null,
            mock(FeatureFlagService.class),
            new ContextSanitizer());
    return new Fixture(coordinator, outline, coherence, recoverySelector);
  }

  private static PlannerRequest request() {
    return PlannerRequest.builder()
        .spec("Implement persistence")
        .classification(Classification.MULTI_TASK)
        .provider("openrouter")
        .model("deepseek-v4-pro")
        .context(new ExecutionContext("run", "__planner__", Path.of("/tmp/repo")))
        .build();
  }

  private static ResolvedModel route() {
    return ResolvedModel.builder()
        .requestedProvider(LlmProvider.OPENROUTER)
        .actualProvider(LlmProvider.OPENROUTER)
        .model("deepseek-v4-pro")
        .agentTier(AgentTier.PLANNING)
        .fallback(false)
        .build();
  }

  private static PlannerRecoveryRouteSelector.Policy recoveryPolicy() {
    return PlannerRecoveryRouteSelector.Policy.runAuthorized(false, false, false);
  }

  private static Plan plan(String description) {
    return Plan.builder()
        .planTitle("Persistence")
        .requirements(
            List.of(
                new RequirementSpec(
                    "R1",
                    "Implement the selected persistence stack",
                    RequirementSpec.RequirementKind.FUNCTIONAL)))
        .tasks(
            List.of(
                PlannedTask.builder()
                    .id("T1")
                    .title("Implement persistence")
                    .description(description)
                    .touchesFiles(List.of("src/persistence.rs"))
                    .meetsRequirements(List.of("R1"))
                    .build()))
        .build();
  }

  private static Plan ownershipPlan(List<String> owned) {
    return Plan.builder()
        .planTitle("Release")
        .requirements(
            List.of(
                new RequirementSpec(
                    "R1", "Implement service", RequirementSpec.RequirementKind.FUNCTIONAL),
                new RequirementSpec(
                    "R7", "Preserve endpoint", RequirementSpec.RequirementKind.FUNCTIONAL),
                new RequirementSpec("R9", "Add CI", RequirementSpec.RequirementKind.FUNCTIONAL)))
        .tasks(
            List.of(
                PlannedTask.builder()
                    .id("T1")
                    .title("Release service")
                    .description("Implement the service and CI while preserving compatibility.")
                    .meetsRequirements(owned)
                    .build()))
        .build();
  }

  private record Fixture(
      RunPlanningCoordinator coordinator,
      OutlinePlannerAgent outline,
      CoherencePlannerAgent coherence,
      PlannerRecoveryRouteSelector recoverySelector) {}
}
