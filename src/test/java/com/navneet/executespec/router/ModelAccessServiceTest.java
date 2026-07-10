package com.navneet.executespec.router;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.auth.service.TierEnforcementService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelAccessServiceTest {

  private static final String FREE_MODEL = "deepseek-v4-flash-native";
  private static final String PREMIUM_MODEL = "claude-opus-4-8";
  private static final String DISABLED_GEMINI_MODEL = "gemini-3.5-flash";
  private static final String RETIRED_MODEL = "retired-provider-model";

  @Mock private ModelRegistry modelRegistry;
  @Mock private TierEnforcementService tierService;
  @InjectMocks private ModelAccessService service;

  @BeforeEach
  void setUp() {
    when(modelRegistry.freeTierModels()).thenReturn(List.of(FREE_MODEL));
    when(modelRegistry.canonicalize(anyString())).thenAnswer(inv -> inv.getArgument(0));
    when(modelRegistry.providerFor(FREE_MODEL)).thenReturn("deepseek");
    when(modelRegistry.providerFor(PREMIUM_MODEL)).thenReturn("anthropic");
    when(modelRegistry.providerFor(DISABLED_GEMINI_MODEL)).thenReturn("gemini");
    when(tierService.resolveUserTier("free-user")).thenReturn("FREE");
    when(tierService.resolveUserTier("pro-user")).thenReturn("PRO");
  }

  @Test
  void classifiesModelsByFreeTierProvider() {
    assertTrue(service.isFreeModel(FREE_MODEL));
    assertFalse(service.isFreeModel(PREMIUM_MODEL));
    assertEquals("FREE", service.minTierFor(FREE_MODEL));
    assertEquals("PRO", service.minTierFor(PREMIUM_MODEL));
  }

  @Test
  void freeTierMayRunOnlyFreeModels() {
    assertTrue(service.isAllowed("free-user", FREE_MODEL));
    assertFalse(service.isAllowed("free-user", PREMIUM_MODEL));
  }

  @Test
  void paidTiersMayRunEverything() {
    assertTrue(service.isAllowed("pro-user", PREMIUM_MODEL));
    assertTrue(service.isAllowed("pro-user", FREE_MODEL));
  }

  @Test
  void disabledProviderModelsAreUnavailableForEveryTier() {
    assertFalse(service.isAllowed("pro-user", DISABLED_GEMINI_MODEL));
    assertEquals("DISABLED", service.minTierFor(DISABLED_GEMINI_MODEL));
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> service.assertModelAllowed("pro-user", DISABLED_GEMINI_MODEL));
    assertEquals(400, ex.getStatusCode().value());
  }

  @Test
  void assertRejectsPremiumModelForFreeTierWith403() {
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> service.assertModelAllowed("free-user", PREMIUM_MODEL));
    assertEquals(403, ex.getStatusCode().value());
  }

  @Test
  void assertAllowsFreeModelBlankAndPaidTier() {
    assertDoesNotThrow(() -> service.assertModelAllowed("free-user", FREE_MODEL));
    assertDoesNotThrow(() -> service.assertModelAllowed("free-user", ""));
    assertDoesNotThrow(() -> service.assertModelAllowed("free-user", null));
    assertDoesNotThrow(() -> service.assertModelAllowed("pro-user", PREMIUM_MODEL));
  }

  @Test
  void assertProviderRejectsTemporarilyDisabledGemini() {
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> ModelAccessService.assertProviderEnabled("GEMINI"));
    assertEquals(400, ex.getStatusCode().value());
  }

  @Test
  void detectsDisabledProviderModelSlugsFromAggregatorLists() {
    assertTrue(ModelAccessService.isDisabledProviderModelSlug("gemini-3.5-flash"));
    assertFalse(ModelAccessService.isDisabledProviderModelSlug("deepseek-v4-pro"));
    assertFalse(ModelAccessService.isDisabledProviderModelSlug("claude-sonnet-4-6"));
  }

  @Test
  void unknownRetiredModelsFailClosedForPaidTiers() {
    assertFalse(service.isAllowed("pro-user", RETIRED_MODEL));
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> service.assertModelAllowed("pro-user", RETIRED_MODEL));
    assertEquals(400, ex.getStatusCode().value());
  }
}
