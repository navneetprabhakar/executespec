package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;

import com.navneet.executespec.auth.security.JwtAuthenticationFilter;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.sandboxroute.SandboxCommandRoutingService;

import org.junit.jupiter.api.Test;

class ClientCapabilitiesControllerTest {

  private static ClientCapabilitiesController controller(
      FeatureFlagService flags, boolean sandboxEnabled) {
    SandboxCommandRoutingService routing = mock(SandboxCommandRoutingService.class);
    when(routing.readiness())
        .thenReturn(
            new SandboxCommandRoutingService.Readiness(
                sandboxEnabled,
                sandboxEnabled,
                sandboxEnabled,
                sandboxEnabled,
                sandboxEnabled,
                sandboxEnabled,
                sandboxEnabled,
                sandboxEnabled ? "ready" : "gates are off"));
    return new ClientCapabilitiesController(flags, routing);
  }

  private static HttpServletRequest requestWithAuthSource(String source) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getAttribute(JwtAuthenticationFilter.AUTH_SOURCE_ATTRIBUTE)).thenReturn(source);
    return request;
  }

  @Test
  void exposesTheStableCliRuntimeContract() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled("worker.local-hands.enabled")).thenReturn(true);
    when(flags.isEnabled("sandbox.lifecycle.enabled")).thenReturn(true);
    when(flags.isEnabled("sandbox.runtime.dispatch.enabled")).thenReturn(true);
    when(flags.isEnabled("sandbox.runtime.container-strict-v1.enabled")).thenReturn(true);

    var response =
        controller(flags, true)
            .capabilities(requestWithAuthSource(JwtAuthenticationFilter.AUTH_SOURCE_PAT));

    assertThat(response.contractVersion()).isEqualTo("agent-protocol-v2");
    assertThat(response.localHands().enabled()).isTrue();
    assertThat(response.localHands().requiredAuthSource()).isEqualTo("PAT");
    assertThat(response.endpoints()).containsKey("agentTurns");
    assertThat(response.eventContracts().typedEventsSupported()).isTrue();
    assertThat(response.toolResultContract().strictEnvelope()).isTrue();
    assertThat(response.managedAi().balancePath()).isEqualTo("/api/billing/managed-ai/balance");
    assertThat(response.providerCatalog().path()).isEqualTo("/api/v3/provider-readiness");
    assertThat(response.remoteSandbox().enabled()).isTrue();
    assertThat(response.remoteSandbox().isolationProfile()).isEqualTo("CONTAINER_STRICT_V1");
  }

  @Test
  void remoteSandboxIsDisabledUnlessEveryExecutionGateIsEnabled() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled("sandbox.lifecycle.enabled")).thenReturn(true);
    when(flags.isEnabled("sandbox.runtime.dispatch.enabled")).thenReturn(false);
    when(flags.isEnabled("sandbox.runtime.container-strict-v1.enabled")).thenReturn(true);

    var response =
        controller(flags, false)
            .capabilities(requestWithAuthSource(JwtAuthenticationFilter.AUTH_SOURCE_JWT));

    assertThat(response.remoteSandbox().enabled()).isFalse();
    assertThat(response.remoteSandbox().note()).contains("must not be advertised");
  }

  @Test
  void remoteSandboxDefaultsOffWhenNoExecutionGateIsEnabled() {
    FeatureFlagService flags = mock(FeatureFlagService.class);

    var response =
        controller(flags, false)
            .capabilities(requestWithAuthSource(JwtAuthenticationFilter.AUTH_SOURCE_JWT));

    assertThat(response.remoteSandbox().enabled()).isFalse();
    assertThat(response.remoteSandbox().lifecycleEnabled()).isFalse();
    assertThat(response.remoteSandbox().dispatchEnabled()).isFalse();
    assertThat(response.remoteSandbox().strictContainerEnabled()).isFalse();
    assertThat(response.remoteSandbox().isolationProfile()).isEqualTo("NOT_ENABLED");
  }

  // RR-646 — a PAT caller with local-hands enabled is the only case that may submit local hands.
  @Test
  void patCallerCanSubmitLocalHandsWhenEnabled() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled("worker.local-hands.enabled")).thenReturn(true);

    var response =
        controller(flags, false)
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
        controller(flags, false)
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
        controller(flags, false)
            .capabilities(requestWithAuthSource(JwtAuthenticationFilter.AUTH_SOURCE_PAT));

    assertThat(response.localHands().currentAuthSourceAllowed()).isTrue();
    assertThat(response.localHands().currentRequestCanSubmitLocalHands()).isFalse();
  }

  // RR-646 — no resolved credential renders as "anonymous", not "authenticated".
  @Test
  void anonymousCallerReportsAnonymousSource() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled("worker.local-hands.enabled")).thenReturn(true);

    var response = controller(flags, false).capabilities(requestWithAuthSource(null));

    assertThat(response.authSource()).isEqualTo("anonymous");
    assertThat(response.localHands().currentAuthSourceAllowed()).isFalse();
    assertThat(response.localHands().currentRequestCanSubmitLocalHands()).isFalse();
  }
}
