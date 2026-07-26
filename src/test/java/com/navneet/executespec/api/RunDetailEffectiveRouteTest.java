package com.navneet.executespec.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.api.dto.RunDetail;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.billing.repository.ManagedAiUsageEventRepository;
import com.navneet.executespec.codeintel.repository.RunCodeMapRepository;
import com.navneet.executespec.cost.RunEffectiveRouteService;
import com.navneet.executespec.cost.RunStepUsage;
import com.navneet.executespec.cost.RunStepUsageRepository;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.IntegrationRunRepository;
import com.navneet.executespec.testgen.repository.TestPlanRepository;

import org.junit.jupiter.api.Test;

/**
 * C1 Step 5 (G4) — the run read model must carry the route that actually served the run, derived
 * from the physical-attempt ledger rather than stored, so it cannot drift from the usage facts.
 */
class RunDetailEffectiveRouteTest {

  private static final String RUN_ID = "run-detail-1";

  private final IntegrationRunRepository integrationRunRepository =
      mock(IntegrationRunRepository.class);
  private final RunCodeMapRepository runCodeMapRepository = mock(RunCodeMapRepository.class);
  private final ValidationReportRepository validationReportRepository =
      mock(ValidationReportRepository.class);
  private final ManagedAiUsageEventRepository managedAiUsageEventRepository =
      mock(ManagedAiUsageEventRepository.class);
  private final RunStepUsageRepository usageRepository = mock(RunStepUsageRepository.class);

  private RunMapper mapper() {
    when(integrationRunRepository.findByRunId(RUN_ID)).thenReturn(Optional.empty());
    when(runCodeMapRepository.findByRunId(RUN_ID)).thenReturn(Optional.empty());
    when(validationReportRepository.findByRunId(RUN_ID)).thenReturn(Optional.empty());
    return new RunMapper(
        integrationRunRepository,
        mock(ProjectRepository.class),
        mock(AppUserRepository.class),
        mock(TestPlanRepository.class),
        runCodeMapRepository,
        validationReportRepository,
        managedAiUsageEventRepository,
        new RunEffectiveRouteService(usageRepository),
        null,
        new ObjectMapper());
  }

  private static RunStepUsage success(String provider, String model) {
    return RunStepUsage.builder()
        .id(provider + ":" + model)
        .runId(RUN_ID)
        .provider(provider)
        .model(model)
        .attemptStatus("SUCCESS")
        .build();
  }

  private static PipelineRun run(String requestedProvider, String requestedModel) {
    PipelineRun r = new PipelineRun();
    r.setRunId(RUN_ID);
    r.setRequestedProvider(requestedProvider);
    r.setRequestedModel(requestedModel);
    return r;
  }

  @Test
  void reportsTheLedgerRouteAndFlagsDivergenceFromTheRequestedRoute() {
    when(usageRepository.findByRunIdOrderByCreatedAtAsc(RUN_ID))
        .thenReturn(List.of(success("openrouter", "glm-5.2")));

    RunDetail detail = mapper().toDetail(run("deepseek", "deepseek-v4-pro-native"), List.of());

    assertThat(detail.getRequestedProvider()).isEqualTo("deepseek");
    assertThat(detail.getEffectiveProvider()).isEqualTo("openrouter");
    assertThat(detail.getEffectiveModel()).isEqualTo("glm-5.2");
    assertThat(detail.getEffectiveRouteAttribution()).isEqualTo("LEDGER");
    assertThat(detail.getRouteDiverged()).isTrue();
  }

  @Test
  void doesNotFlagDivergenceWhenTheLedgerMatchesTheRequestedRoute() {
    when(usageRepository.findByRunIdOrderByCreatedAtAsc(RUN_ID))
        .thenReturn(List.of(success("deepseek", "deepseek-v4-pro-native")));

    RunDetail detail = mapper().toDetail(run("deepseek", "deepseek-v4-pro-native"), List.of());

    assertThat(detail.getEffectiveRouteAttribution()).isEqualTo("LEDGER");
    assertThat(detail.getRouteDiverged()).isFalse();
  }

  /** An AUTO-routed run pins no model; that must not read as a divergence. */
  @Test
  void autoRoutedRunWithNoPinnedModelIsNotReportedAsDiverged() {
    when(usageRepository.findByRunIdOrderByCreatedAtAsc(RUN_ID))
        .thenReturn(List.of(success("anthropic", "claude-sonnet-5")));

    RunDetail detail = mapper().toDetail(run("anthropic", null), List.of());

    assertThat(detail.getRouteDiverged()).isFalse();
  }

  @Test
  void multiRouteRunStaysMixedAndIsNotCollapsedToOneProvider() {
    when(usageRepository.findByRunIdOrderByCreatedAtAsc(RUN_ID))
        .thenReturn(
            List.of(
                success("deepseek", "deepseek-v4-pro-native"), success("openrouter", "glm-5.2")));

    RunDetail detail = mapper().toDetail(run("deepseek", "deepseek-v4-pro-native"), List.of());

    assertThat(detail.getEffectiveRouteAttribution()).isEqualTo("MIXED");
    assertThat(detail.getEffectiveProvider()).isNull();
    assertThat(detail.getEffectiveModel()).isNull();
    assertThat(detail.getRouteDiverged()).isTrue();
  }

  @Test
  void runWithNoRecordedAttemptsStaysUnattributedRatherThanBorrowingTheRequestedRoute() {
    when(usageRepository.findByRunIdOrderByCreatedAtAsc(RUN_ID)).thenReturn(List.of());

    RunDetail detail = mapper().toDetail(run("deepseek", "deepseek-v4-pro-native"), List.of());

    assertThat(detail.getEffectiveRouteAttribution()).isEqualTo("UNATTRIBUTED");
    assertThat(detail.getEffectiveProvider()).isNull();
    assertThat(detail.getRouteDiverged()).isFalse();
  }

  @Test
  void preservesRequestedCapSeparatelyWhenPlanningFailedBeforeHardCapDerivation() {
    when(usageRepository.findByRunIdOrderByCreatedAtAsc(RUN_ID)).thenReturn(List.of());
    PipelineRun failedDuringPlanning = run("deepseek", null);
    failedDuringPlanning.setUserCostCap(1.50);
    failedDuringPlanning.setHardCapUsd(null);

    RunDetail detail = mapper().toDetail(failedDuringPlanning, List.of());

    assertThat(detail.getUserCostCapUsd()).isEqualTo(1.50);
    assertThat(detail.getHardCapUsd())
        .as("the read model must not claim a pre-fix cap was enforced when it was not")
        .isNull();
  }

  @Test
  void projectsDeterministicPlannerRejectionWithoutCallingItUnavailable() {
    when(usageRepository.findByRunIdOrderByCreatedAtAsc(RUN_ID)).thenReturn(List.of());
    PipelineRun rejected = run("deepseek", null);
    rejected.setStatus("FAILED");
    rejected.setStatusReason("PLANNER_UNAVAILABLE");
    rejected.setFailureReason(
        "Plan validation rejected: final plan contract validation failed: T9 has no producer");

    RunDetail detail = mapper().toDetail(rejected, List.of());

    assertThat(detail.getStatusReason()).isEqualTo("PLAN_VALIDATION_REJECTED");
  }

  @Test
  void preservesUnavailableReasonForWrappedProviderFailure() {
    when(usageRepository.findByRunIdOrderByCreatedAtAsc(RUN_ID)).thenReturn(List.of());
    PipelineRun unavailable = run("deepseek", null);
    unavailable.setStatus("FAILED");
    unavailable.setStatusReason("PLANNER_UNAVAILABLE");
    unavailable.setFailureReason("planning could not produce a safe executable plan");

    RunDetail detail = mapper().toDetail(unavailable, List.of());

    assertThat(detail.getStatusReason()).isEqualTo("PLANNER_UNAVAILABLE");
  }
}
