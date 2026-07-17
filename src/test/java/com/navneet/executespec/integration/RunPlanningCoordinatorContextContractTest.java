package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import com.navneet.executespec.agent.Classification;
import com.navneet.executespec.agent.OutlinePlannerAgent;
import com.navneet.executespec.agent.PlannerRequest;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.engine.RepoOrientationService;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;

/** Pins the shared deterministic pre-planner/worker repository-evidence contract. */
class RunPlanningCoordinatorContextContractTest {

  @Test
  void addsOrientationToPlannerContextWhenWorkerLocalizationIsEnabled() {
    RepoOrientationService orientation = mock(RepoOrientationService.class);
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled("worker.localization.enabled")).thenReturn(true);
    when(orientation.orient(Path.of("/tmp/repo"), "Fix WidgetLoader"))
        .thenReturn(
            "# REPO ORIENTATION (advisory — verify before relying on it)\n\n"
                + "- src/WidgetLoader.java");

    RunPlanningCoordinator coordinator = coordinator(orientation, flags);
    PlannerRequest request = request("base summary");

    PlannerRequest prepared = coordinator.preparePlannerRequest(request);

    assertThat(prepared).isNotSameAs(request);
    assertThat(prepared.workspaceSummary())
        .isEqualTo(
            "base summary\n\n"
                + "# REPO ORIENTATION (advisory — verify before relying on it)\n\n"
                + "- src/WidgetLoader.java");
    assertThat(prepared.spec()).isEqualTo(request.spec());
    assertThat(prepared.referenceDocs()).isEqualTo(request.referenceDocs());
  }

  @Test
  void leavesPlannerRequestByteIdenticalWhenFlagIsDisabled() {
    RepoOrientationService orientation = mock(RepoOrientationService.class);
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled("worker.localization.enabled")).thenReturn(false);

    RunPlanningCoordinator coordinator = coordinator(orientation, flags);
    PlannerRequest request = request("base summary");

    assertThat(coordinator.preparePlannerRequest(request)).isSameAs(request);
  }

  @Test
  void doesNotDuplicateOrientationWhenARecursivePlannerAlreadyCarriesIt() {
    RepoOrientationService orientation = mock(RepoOrientationService.class);
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled("worker.localization.enabled")).thenReturn(true);

    RunPlanningCoordinator coordinator = coordinator(orientation, flags);
    String existing =
        "base\n\n# REPO ORIENTATION (advisory — verify before relying on it)\n- src/A.java";
    PlannerRequest request = request(existing);

    assertThat(coordinator.preparePlannerRequest(request)).isSameAs(request);
  }

  private static PlannerRequest request(String summary) {
    return PlannerRequest.builder()
        .spec("Fix WidgetLoader")
        .classification(Classification.MEDIUM)
        .provider("deepseek")
        .model("deepseek-v4-pro-native")
        .context(new ExecutionContext("run-1", "__planner__", Path.of("/tmp/repo")))
        .workspaceSummary(summary)
        .projectMemory("memory")
        .projectBrief("brief")
        .referenceDocs("docs")
        .contracts("contracts")
        .projectAdrs("adrs")
        .tddMode(true)
        .prReviewComments("review")
        .build();
  }

  private static RunPlanningCoordinator coordinator(
      RepoOrientationService orientation, FeatureFlagService flags) {
    return new RunPlanningCoordinator(
        null,
        null,
        null,
        null,
        mock(OutlinePlannerAgent.class),
        null,
        null,
        null,
        null,
        null,
        orientation,
        flags,
        null);
  }
}
