package com.navneet.executespec.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.router.ProviderReadinessService;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AiProviderPreferenceServiceTest {

  private final AiProviderPreferenceService service = new AiProviderPreferenceService(null, null);

  @Test
  void normalizeProviderRejectsDisabledSarvam() {
    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> service.normalizeProvider("SARVAM"));
    assertEquals(400, ex.getStatusCode().value());
  }

  @Test
  void normalizeProviderAllowsGeminiAndGoogleAlias() {
    assertEquals("GEMINI", service.normalizeProvider("gemini"));
    assertEquals("GEMINI", service.normalizeProvider("google"));
  }

  @Test
  void preferredProviderAllowsGeminiStoredProvider() {
    AppUser user =
        AppUser.builder()
            .id("u1")
            .email("u1@example.com")
            .firstName("Test")
            .globalRole("USER")
            .tier("TEAM")
            .status("ACTIVE")
            .preferredAiProvider("GEMINI")
            .build();

    assertEquals("GEMINI", service.preferredProvider(new AppUserDetails(user, null)));
  }

  @Test
  void preferenceUpdateRejectsProviderOutsideEffectiveKeyPolicy() {
    AppUserRepository users = mock(AppUserRepository.class);
    ProviderReadinessService readiness = mock(ProviderReadinessService.class);
    AiProviderPreferenceService guarded = new AiProviderPreferenceService(users, readiness);
    AppUser user = AppUser.builder().id("u1").email("u1@example.com").status("ACTIVE").build();
    when(readiness.resolve("u1"))
        .thenReturn(
            List.of(
                new ProviderReadinessService.ProviderReadiness(
                    "DEEPSEEK",
                    ProviderReadinessService.State.AVAILABLE_ORG_KEY,
                    true,
                    "OWN_KEYS",
                    "available"),
                new ProviderReadinessService.ProviderReadiness(
                    "ANTHROPIC",
                    ProviderReadinessService.State.UNAVAILABLE,
                    false,
                    "OWN_KEYS",
                    "missing key")));

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> guarded.updatePreferredProvider(new AppUserDetails(user, null), "anthropic"));

    assertEquals(409, ex.getStatusCode().value());
    verify(users, never()).save(user);
  }

  @Test
  void preferenceUpdateAllowsCredentialedCircuitOpenProviderWithoutMarkingItDispatchable() {
    AppUserRepository users = mock(AppUserRepository.class);
    ProviderReadinessService readiness = mock(ProviderReadinessService.class);
    AiProviderPreferenceService guarded = new AiProviderPreferenceService(users, readiness);
    AppUser user = AppUser.builder().id("u1").email("u1@example.com").status("ACTIVE").build();
    when(readiness.resolve("u1"))
        .thenReturn(
            List.of(
                new ProviderReadinessService.ProviderReadiness(
                    "DEEPSEEK",
                    ProviderReadinessService.State.CIRCUIT_OPEN,
                    false,
                    "OWN_KEYS",
                    "temporary outage")));
    when(users.save(user)).thenReturn(user);

    assertEquals(
        "DEEPSEEK", guarded.updatePreferredProvider(new AppUserDetails(user, null), "deepseek"));
    assertEquals("DEEPSEEK", user.getPreferredAiProvider());
    verify(users).save(user);
  }
}
