package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.requirement.RunRequirementContractShadowService;
import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class RunPlanRequirementShadowCoordinatorTest {
  private final RunPersistenceWriter writer = mock(RunPersistenceWriter.class);
  private final RunRequirementContractShadowService shadow =
      mock(RunRequirementContractShadowService.class);
  private final FeatureFlagService featureFlags = mock(FeatureFlagService.class);
  private final RunPlanRequirementShadowCoordinator coordinator =
      new RunPlanRequirementShadowCoordinator(writer, shadow, featureFlags);

  @Test
  void recordsShadowOnlyAfterLegacyWriterReturnsItsExactPersistedBytes() {
    enableShadow();
    Plan plan = Plan.builder().planTitle("final normalized plan").build();
    String exactSource = "Original user requirement.";
    String exactPlanJson = "{\"planTitle\":\"final normalized plan\"}";
    when(writer.persistCompiledPlan("run-1", plan))
        .thenReturn(new RunPersistenceWriter.PersistedCompiledPlan(exactPlanJson));

    var result = coordinator.persist("run-1", exactSource, plan);

    assertThat(result.exactPlanJson()).isEqualTo(exactPlanJson);
    InOrder order = inOrder(writer, shadow);
    order.verify(writer).persistCompiledPlan("run-1", plan);
    order.verify(shadow).persist("run-1", exactSource, exactPlanJson);
  }

  @Test
  void unexpectedShadowFailureCannotBlockTheAlreadyPersistedLegacyPlan() {
    enableShadow();
    Plan plan = Plan.builder().build();
    when(writer.persistCompiledPlan("run-2", plan))
        .thenReturn(new RunPersistenceWriter.PersistedCompiledPlan("{}"));
    when(shadow.persist("run-2", "source", "{}"))
        .thenThrow(new IllegalStateException("shadow down"));

    assertThat(coordinator.persist("run-2", "source", plan).exactPlanJson()).isEqualTo("{}");
    verify(shadow).persist("run-2", "source", "{}");
  }

  @Test
  void missingLegacyPlanBytesNeverProduceAStaleShadowBinding() {
    enableShadow();
    Plan plan = Plan.builder().build();
    when(writer.persistCompiledPlan("run-3", plan))
        .thenReturn(new RunPersistenceWriter.PersistedCompiledPlan(null));

    assertThat(coordinator.persist("run-3", "source", plan).exactPlanJson()).isNull();
    verify(shadow, never())
        .persist(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void disabledShadowFlagStillPersistsAndReturnsTheExactLegacyPlanWithoutCallingShadow() {
    Plan plan = Plan.builder().planTitle("legacy plan remains authoritative").build();
    String exactPlanJson = "{\"planTitle\":\"legacy plan remains authoritative\"}";
    when(writer.persistCompiledPlan("run-disabled", plan))
        .thenReturn(new RunPersistenceWriter.PersistedCompiledPlan(exactPlanJson));
    when(featureFlags.isEnabled(RunPlanRequirementShadowCoordinator.SHADOW_FLAG)).thenReturn(false);

    var persisted = coordinator.persist("run-disabled", "Original requirement", plan);

    assertThat(persisted.exactPlanJson()).isEqualTo(exactPlanJson);
    verify(writer).persistCompiledPlan("run-disabled", plan);
    verify(featureFlags).isEnabled(RunPlanRequirementShadowCoordinator.SHADOW_FLAG);
    verify(shadow, never())
        .persist(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void flagReadFailurePreservesTheLegacyResultAndDoesNotCallShadow() {
    Plan plan = Plan.builder().planTitle("legacy plan survives flag outage").build();
    String exactPlanJson = "{\"planTitle\":\"legacy plan survives flag outage\"}";
    when(writer.persistCompiledPlan("run-flag-error", plan))
        .thenReturn(new RunPersistenceWriter.PersistedCompiledPlan(exactPlanJson));
    when(featureFlags.isEnabled(RunPlanRequirementShadowCoordinator.SHADOW_FLAG))
        .thenThrow(new IllegalStateException("flag store unavailable"));

    var persisted = coordinator.persist("run-flag-error", "Original requirement", plan);

    assertThat(persisted.exactPlanJson()).isEqualTo(exactPlanJson);
    verify(writer).persistCompiledPlan("run-flag-error", plan);
    verify(featureFlags).isEnabled(RunPlanRequirementShadowCoordinator.SHADOW_FLAG);
    verify(shadow, never())
        .persist(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  private void enableShadow() {
    when(featureFlags.isEnabled(RunPlanRequirementShadowCoordinator.SHADOW_FLAG)).thenReturn(true);
  }
}
