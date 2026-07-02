package com.navneet.executespec.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AiProviderPreferenceServiceTest {

  private final AiProviderPreferenceService service = new AiProviderPreferenceService(null);

  @Test
  void normalizeProviderRejectsDisabledSarvam() {
    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> service.normalizeProvider("SARVAM"));
    assertEquals(400, ex.getStatusCode().value());
  }

  @Test
  void normalizeProviderRejectsTemporarilyDisabledGeminiAndGoogleAlias() {
    assertEquals(
        400,
        assertThrows(ResponseStatusException.class, () -> service.normalizeProvider("gemini"))
            .getStatusCode()
            .value());
    assertEquals(
        400,
        assertThrows(ResponseStatusException.class, () -> service.normalizeProvider("google"))
            .getStatusCode()
            .value());
  }

  @Test
  void preferredProviderRejectsStaleDisabledStoredProvider() {
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

    assertEquals(
        400,
        assertThrows(
                ResponseStatusException.class,
                () -> service.preferredProvider(new AppUserDetails(user, null)))
            .getStatusCode()
            .value());
  }
}
