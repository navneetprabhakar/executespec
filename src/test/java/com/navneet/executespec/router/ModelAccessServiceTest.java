package com.navneet.executespec.router;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

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

  @Mock private ModelRegistry modelRegistry;
  @Mock private TierEnforcementService tierService;
  @InjectMocks private ModelAccessService service;

  @BeforeEach
  void setUp() {
    when(modelRegistry.freeTierProvider()).thenReturn("deepseek");
    when(modelRegistry.providerFor(FREE_MODEL)).thenReturn("deepseek");
    when(modelRegistry.providerFor(PREMIUM_MODEL)).thenReturn("anthropic");
    when(modelRegistry.canonicalize(anyString())).thenAnswer(inv -> inv.getArgument(0));
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
}
