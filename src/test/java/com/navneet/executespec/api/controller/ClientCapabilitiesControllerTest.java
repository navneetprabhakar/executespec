package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;

import com.navneet.executespec.auth.security.JwtAuthenticationFilter;
import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.junit.jupiter.api.Test;

class ClientCapabilitiesControllerTest {

  private static HttpServletRequest requestWithAuthSource(String source) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getAttribute(JwtAuthenticationFilter.AUTH_SOURCE_ATTRIBUTE)).thenReturn(source);
    return request;
  }

  @Test
  void exposesTheStableCliRuntimeContract() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled("worker.local-hands.enabled")).thenReturn(true);

    var response =
        new ClientCapabilitiesController(flags)
            .capabilities(requestWithAuthSource(JwtAuthenticationFilter.AUTH_SOURCE_PAT));

    assertThat(response.contractVersion()).isEqualTo("agent-protocol-v2");
    assertThat(response.localHands().enabled()).isTrue();
    assertThat(response.localHands().requiredAuthSource()).isEqualTo("PAT");
    assertThat(response.endpoints()).containsKey("agentTurns");
    assertThat(response.eventContracts().typedEventsSupported()).isTrue();
    assertThat(response.toolResultContract().strictEnvelope()).isTrue();
    assertThat(response.managedAi().balancePath()).isEqualTo("/api/billing/managed-ai/balance");
    assertThat(response.providerCatalog().path()).isEqualTo("/api/v3/provider-readiness");
  }

  // RR-646 — a PAT caller with local-hands enabled is the only case that may submit local hands.
  @Test
  void patCallerCanSubmitLocalHandsWhenEnabled() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled("worker.local-hands.enabled")).thenReturn(true);

    var response =
        new ClientCapabilitiesController(flags)
            .capabilities(requestWithAuthSource(JwtAuthenticationFilter.AUTH_SOURCE_PAT));

    assertThat(response.authSource()).isEqualTo("PAT");
    assertThat(response.localHands().currentAuthSourceAllowed()).isTrue();
    assertThat(response.localHands().currentRequestCanSubmitLocalHands()).isTrue();
  }

  // RR-646 — a JWT/browser caller must NOT be told the current request can submit local hands,
  // because RunController rejects localExecution=true for browser/JWT sessions.
  @Test
  void jwtCallerCannotSubmitLocalHandsEvenWhenEnabled() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled("worker.local-hands.enabled")).thenReturn(true);

    var response =
        new ClientCapabilitiesController(flags)
            .capabilities(requestWithAuthSource(JwtAuthenticationFilter.AUTH_SOURCE_JWT));

    assertThat(response.authSource()).isEqualTo("JWT");
    assertThat(response.localHands().currentAuthSourceAllowed()).isFalse();
    assertThat(response.localHands().currentRequestCanSubmitLocalHands()).isFalse();
  }

  // RR-646 — a PAT caller cannot submit local hands while the flag is off.
  @Test
  void patCallerCannotSubmitLocalHandsWhenFlagOff() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled("worker.local-hands.enabled")).thenReturn(false);

    var response =
        new ClientCapabilitiesController(flags)
            .capabilities(requestWithAuthSource(JwtAuthenticationFilter.AUTH_SOURCE_PAT));

    assertThat(response.localHands().currentAuthSourceAllowed()).isTrue();
    assertThat(response.localHands().currentRequestCanSubmitLocalHands()).isFalse();
  }

  // RR-646 — no resolved credential renders as "anonymous", not "authenticated".
  @Test
  void anonymousCallerReportsAnonymousSource() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled("worker.local-hands.enabled")).thenReturn(true);

    var response =
        new ClientCapabilitiesController(flags).capabilities(requestWithAuthSource(null));

    assertThat(response.authSource()).isEqualTo("anonymous");
    assertThat(response.localHands().currentAuthSourceAllowed()).isFalse();
    assertThat(response.localHands().currentRequestCanSubmitLocalHands()).isFalse();
  }
}
