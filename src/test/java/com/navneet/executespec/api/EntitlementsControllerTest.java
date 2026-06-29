package com.navneet.executespec.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.JwtAuthenticationFilter;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.entitlements.EntitlementResolver;
import com.navneet.executespec.entitlements.GovernancePolicyResolver;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class EntitlementsControllerTest {

  private final EntitlementResolver entitlementResolver = mock(EntitlementResolver.class);
  private final GovernancePolicyResolver governancePolicyResolver =
      mock(GovernancePolicyResolver.class);
  private final FeatureFlagService featureFlagService = mock(FeatureFlagService.class);

  private final EntitlementsController controller =
      new EntitlementsController(
          entitlementResolver, governancePolicyResolver, featureFlagService);

  @Test
  void clientCapabilitiesReportsPatLocalHandsReadyWhenFlagEnabled() {
    when(featureFlagService.isEnabled("worker.local-hands.enabled")).thenReturn(true);
    MockHttpServletRequest request = requestWithAuthSource(JwtAuthenticationFilter.AUTH_SOURCE_PAT);

    ResponseEntity<EntitlementsController.ClientCapabilitiesResponse> response =
        controller.clientCapabilities(user(), request);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    EntitlementsController.ClientCapabilitiesResponse body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.authSource()).isEqualTo(JwtAuthenticationFilter.AUTH_SOURCE_PAT);
    assertThat(body.localHands().supported()).isTrue();
    assertThat(body.localHands().enabled()).isTrue();
    assertThat(body.localHands().requiredAuthSource()).isEqualTo("PAT");
    assertThat(body.localHands().currentAuthSourceAllowed()).isTrue();
    assertThat(body.localHands().currentRequestCanSubmitLocalHands()).isTrue();
    assertThat(body.endpoints()).containsKeys("runs.submit", "runs.toolResults", "runs.eventReplay");
    assertThat(body.eventContracts().eventTypes()).contains("TOOL_CALL", "TOOL_RESULT", "DONE");
    assertThat(body.toolResultContract().successContentMaxChars()).isEqualTo(60_000);
    assertThat(body.providerCatalog().disabledProviders()).containsExactly("sarvam");
    assertThat(body.webFetch().trustLabel()).isEqualTo("untrusted");
  }

  @Test
  void clientCapabilitiesReportsJwtCannotSubmitLocalHands() {
    when(featureFlagService.isEnabled("worker.local-hands.enabled")).thenReturn(true);
    MockHttpServletRequest request = requestWithAuthSource(JwtAuthenticationFilter.AUTH_SOURCE_JWT);

    EntitlementsController.ClientCapabilitiesResponse body =
        controller.clientCapabilities(user(), request).getBody();

    assertThat(body).isNotNull();
    assertThat(body.localHands().enabled()).isTrue();
    assertThat(body.localHands().currentAuthSourceAllowed()).isFalse();
    assertThat(body.localHands().currentRequestCanSubmitLocalHands()).isFalse();
  }

  @Test
  void clientCapabilitiesReportsDisabledLocalHandsEvenForPat() {
    when(featureFlagService.isEnabled("worker.local-hands.enabled")).thenReturn(false);
    MockHttpServletRequest request = requestWithAuthSource(JwtAuthenticationFilter.AUTH_SOURCE_PAT);

    EntitlementsController.ClientCapabilitiesResponse body =
        controller.clientCapabilities(user(), request).getBody();

    assertThat(body).isNotNull();
    assertThat(body.localHands().enabled()).isFalse();
    assertThat(body.localHands().currentAuthSourceAllowed()).isTrue();
    assertThat(body.localHands().currentRequestCanSubmitLocalHands()).isFalse();
  }

  @Test
  void clientCapabilitiesRejectsAnonymousCalls() {
    assertThat(controller.clientCapabilities(null, new MockHttpServletRequest()).getStatusCode().value())
        .isEqualTo(401);
  }

  private static MockHttpServletRequest requestWithAuthSource(String authSource) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(JwtAuthenticationFilter.AUTH_SOURCE_ATTRIBUTE, authSource);
    return request;
  }

  private static AppUserDetails user() {
    AppUser user =
        AppUser.builder()
            .id("user-1")
            .email("forge@example.com")
            .firstName("Forge")
            .status("ACTIVE")
            .globalRole("USER")
            .build();
    return new AppUserDetails(user, null);
  }
}
