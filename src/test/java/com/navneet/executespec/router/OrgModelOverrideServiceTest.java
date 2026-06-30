package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.OrgRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * F3 — validation + persistence semantics for {@link OrgModelOverrideService}: BYOK/TEAM/ENTERPRISE
 * writes, strict per-cell validation against the registered matrix, OpenRouter validated via the
 * allow-list, and lenient run-time reads that drop cells which no longer validate.
 */
class OrgModelOverrideServiceTest {

  private OrgRepository orgRepo;
  private ModelRegistry registry;
  private OrgModelOverrideService svc;

  @BeforeEach
  void setUp() {
    orgRepo = mock(OrgRepository.class);
    registry = mock(ModelRegistry.class);
    // Lenient identity canonicalisation — most tests don't care about aliasing.
    lenient().when(registry.canonicalize(anyString())).thenAnswer(inv -> inv.getArgument(0));
    svc =
        new OrgModelOverrideService(
            orgRepo,
            registry,
            new ObjectMapper(),
            mock(com.navneet.executespec.auth.service.ActivityAuditService.class));
  }

  private Org seed(String tier, String json) {
    Org org = Org.builder().id("org-1").tier(tier).modelOverridesJson(json).build();
    when(orgRepo.findById("org-1")).thenReturn(Optional.of(org));
    lenient().when(orgRepo.save(org)).thenReturn(org);
    return org;
  }

  @Test
  void enterprise_validCell_persistsCanonicalModel() {
    Org org = seed("ENTERPRISE", null);
    when(registry.isKnownModel("claude-opus-4-6")).thenReturn(true);
    when(registry.providerFor("claude-opus-4-6")).thenReturn("anthropic");

    svc.updateOverrides("org-1", Map.of("anthropic:planning", "claude-opus-4-6"), "actor");

    assertThat(org.getModelOverridesJson())
        .contains("anthropic:planning")
        .contains("claude-opus-4-6");
  }

  @Test
  void lowerTier_rejectedWith403() {
    seed("PRO", null);
    assertThatThrownBy(
            () ->
                svc.updateOverrides("org-1", Map.of("anthropic:planning", "claude-opus-4-6"), "a"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("BYOK");
  }

  @Test
  void byok_validCell_persistsCanonicalModel() {
    Org org = seed("BYOK", null);
    when(registry.isKnownModel("claude-opus-4-6")).thenReturn(true);
    when(registry.providerFor("claude-opus-4-6")).thenReturn("anthropic");

    svc.updateOverrides("org-1", Map.of("anthropic:planning", "claude-opus-4-6"), "actor");

    assertThat(org.getModelOverridesJson())
        .contains("anthropic:planning")
        .contains("claude-opus-4-6");
  }

  @Test
  void unknownModel_rejectedWith400() {
    seed("ENTERPRISE", null);
    when(registry.isKnownModel("not-a-model")).thenReturn(false);
    assertThatThrownBy(
            () -> svc.updateOverrides("org-1", Map.of("anthropic:planning", "not-a-model"), "a"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Unknown model");
  }

  @Test
  void unknownProvider_rejectedWith400() {
    seed("ENTERPRISE", null);
    assertThatThrownBy(() -> svc.updateOverrides("org-1", Map.of("foo:planning", "x"), "a"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Unknown provider");
  }

  @Test
  void unknownTier_rejectedWith400() {
    seed("ENTERPRISE", null);
    assertThatThrownBy(() -> svc.updateOverrides("org-1", Map.of("anthropic:fast", "x"), "a"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Unknown tier");
  }

  @Test
  void providerMismatch_rejectedWith400() {
    seed("ENTERPRISE", null);
    when(registry.isKnownModel("gpt-5.4")).thenReturn(true);
    when(registry.providerFor("gpt-5.4")).thenReturn("openai");
    assertThatThrownBy(
            () -> svc.updateOverrides("org-1", Map.of("anthropic:planning", "gpt-5.4"), "a"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("not an anthropic model");
  }

  @Test
  void openRouterCell_validatedViaAllowList_notProviderOwner() {
    Org org = seed("ENTERPRISE", null);
    // gpt-5.4-mini is in the OpenRouter allow-list; providerFor would say "openai" but the
    // OpenRouter column is validated against the allow-list, not provider ownership.
    when(registry.isKnownModel("gpt-5.4-mini")).thenReturn(true);

    svc.updateOverrides("org-1", Map.of("openrouter:workhorse", "gpt-5.4-mini"), "actor");

    assertThat(org.getModelOverridesJson())
        .contains("openrouter:workhorse")
        .contains("gpt-5.4-mini");
  }

  @Test
  void emptyOverrides_clearsColumn() {
    Org org = seed("ENTERPRISE", "{\"anthropic:planning\":\"claude-opus-4-6\"}");
    svc.updateOverrides("org-1", Map.of(), "actor");
    assertThat(org.getModelOverridesJson()).isNull();
  }

  @Test
  void overridesForRun_isLenient_dropsInvalidCells() {
    seed(
        "ENTERPRISE",
        "{\"anthropic:planning\":\"claude-opus-4-6\",\"anthropic:workhorse\":\"retired\"}");
    when(registry.isKnownModel("claude-opus-4-6")).thenReturn(true);
    when(registry.providerFor("claude-opus-4-6")).thenReturn("anthropic");
    when(registry.isKnownModel("retired")).thenReturn(false);

    Map<String, String> live = svc.overridesForRun("org-1");

    assertThat(live).containsEntry("anthropic:planning", "claude-opus-4-6");
    assertThat(live).doesNotContainKey("anthropic:workhorse");
  }
}
