package com.navneet.executespec.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
  void normalizeProviderKeepsGoogleAlias() {
    assertEquals("GEMINI", service.normalizeProvider("google"));
  }
}
