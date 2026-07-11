package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.junit.jupiter.api.Test;

class ClientCapabilitiesControllerTest {

  @Test
  void exposesTheStableCliRuntimeContract() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled("worker.local-hands.enabled")).thenReturn(true);

    var response = new ClientCapabilitiesController(flags).capabilities();

    assertThat(response.contractVersion()).isEqualTo("agent-protocol-v2");
    assertThat(response.localHands().enabled()).isTrue();
    assertThat(response.localHands().requiredAuthSource()).isEqualTo("PAT");
    assertThat(response.endpoints()).containsKey("agentTurns");
    assertThat(response.eventContracts().typedEventsSupported()).isTrue();
    assertThat(response.toolResultContract().strictEnvelope()).isTrue();
    assertThat(response.managedAi().balancePath()).isEqualTo("/api/billing/managed-ai/balance");
  }
}
