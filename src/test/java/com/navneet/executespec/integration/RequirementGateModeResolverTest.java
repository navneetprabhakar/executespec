package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.agent.requirement.RequirementGateBindingService.RequestedMode;
import com.navneet.executespec.config.featureflag.FeatureFlag;
import com.navneet.executespec.config.featureflag.FeatureFlagRepository;

import org.junit.jupiter.api.Test;

class RequirementGateModeResolverTest {
  private final FeatureFlagRepository repository = mock(FeatureFlagRepository.class);
  private final RequirementGateModeResolver resolver = new RequirementGateModeResolver(repository);

  @Test
  void missingDurableModeFailsInsteadOfDowngrading() {
    when(repository.findByFlagKey(RequirementGateModeResolver.MODE_FLAG))
        .thenReturn(Optional.empty());

    assertThatThrownBy(resolver::resolve)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("gate mode is missing");
  }

  @Test
  void readsOneDurableCaseInsensitiveMode() {
    when(repository.findByFlagKey(RequirementGateModeResolver.MODE_FLAG))
        .thenReturn(Optional.of(FeatureFlag.builder().flagValue("shadow").build()));

    assertThat(resolver.resolve()).isEqualTo(RequestedMode.SHADOW);
  }

  @Test
  void invalidModeFailsInsteadOfDowngrading() {
    when(repository.findByFlagKey(RequirementGateModeResolver.MODE_FLAG))
        .thenReturn(Optional.of(FeatureFlag.builder().flagValue("maybe").build()));

    assertThatThrownBy(resolver::resolve)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Invalid RequirementIR gate mode");
  }
}
