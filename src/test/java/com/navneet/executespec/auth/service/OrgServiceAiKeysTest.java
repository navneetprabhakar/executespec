package com.navneet.executespec.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.billing.gateway.StripePaymentGateway;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pins {@link OrgService#updateAiKeySettings} semantics:
 *
 * <ul>
 *   <li>A blank value means "leave unchanged", not "clear" — the settings UI saves one provider at
 *       a time and sends only the edited field, so an unconditional set would wipe the others.
 *   <li>OpenRouter follows the same BYOK-capable tier contract as the provider-key settings UI.
 *   <li>Keys are trimmed before persistence.
 * </ul>
 *
 * <p>Pure Mockito; no Spring context.
 */
class OrgServiceAiKeysTest {

  private OrgRepository orgRepo;
  private ActivityAuditService auditService;
  private OrgService svc;

  @BeforeEach
  void setUp() {
    orgRepo = mock(OrgRepository.class);
    auditService = mock(ActivityAuditService.class);
    svc =
        new OrgService(
            orgRepo,
            mock(OrgMemberRepository.class),
            mock(AppUserRepository.class),
            mock(PaymentAuditService.class),
            auditService,
            mock(MailService.class),
            mock(BillingSubscriptionRepository.class),
            mock(StripePaymentGateway.class));
  }

  private Org seedOrg(String tier, String anthropic, String openai, String gemini, String router) {
    Org org =
        Org.builder()
            .id("org-1")
            .name("Acme")
            .tier(tier)
            .status("ACTIVE")
            .anthropicApiKey(anthropic)
            .openAiApiKey(openai)
            .geminiApiKey(gemini)
            .openRouterApiKey(router)
            .build();
    when(orgRepo.findById("org-1")).thenReturn(Optional.of(org));
    when(orgRepo.save(any(Org.class))).thenAnswer(inv -> inv.getArgument(0));
    return org;
  }

  @Test
  void blankLeavesOtherKeysUnchanged() {
    Org org = seedOrg("TEAM", "sk-ant-old", "sk-openai-old", "sk-gem-old", null);

    // Save only the Anthropic key — the other fields arrive blank and must survive.
    svc.updateAiKeySettings("org-1", "sk-ant-new", "", "", "", "actor");

    assertThat(org.getAnthropicApiKey()).isEqualTo("sk-ant-new");
    assertThat(org.getOpenAiApiKey()).isEqualTo("sk-openai-old");
    assertThat(org.getGeminiApiKey()).isEqualTo("sk-gem-old");
  }

  @Test
  void allBlankClearsNothing() {
    Org org = seedOrg("TEAM", "sk-ant", "sk-oa", "sk-gem", null);

    svc.updateAiKeySettings("org-1", "", "", "", "", "actor");

    assertThat(org.getAnthropicApiKey()).isEqualTo("sk-ant");
    assertThat(org.getOpenAiApiKey()).isEqualTo("sk-oa");
    assertThat(org.getGeminiApiKey()).isEqualTo("sk-gem");
  }

  @Test
  void openRouterRejectedOnUnentitledTier() {
    seedOrg("PRO", null, null, null, null);

    assertThatThrownBy(() -> svc.updateAiKeySettings("org-1", "", "", "", "sk-or", "actor"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("TEAM, BYOK or Enterprise");
  }

  @Test
  void openRouterAcceptedOnTeamAndByok() {
    Org org = seedOrg("TEAM", null, null, null, null);

    svc.updateAiKeySettings("org-1", "", "", "", "sk-or-new", "actor");

    assertThat(org.getOpenRouterApiKey()).isEqualTo("sk-or-new");

    Org byok = seedOrg("BYOK", null, null, null, null);
    svc.updateAiKeySettings("org-1", "", "", "", "sk-or-byok", "actor");
    assertThat(byok.getOpenRouterApiKey()).isEqualTo("sk-or-byok");
  }

  @Test
  void keySourcePolicyAcceptsGeminiAndNormalizesLegacyGoogleAlias() {
    Org org = seedOrg("ENTERPRISE", null, null, "gemini-key", null);

    svc.updateKeySourceSettings("org-1", "OWN_KEYS", java.util.List.of("google"), "actor");

    assertThat(org.getKeySourceMode()).isEqualTo("OWN_KEYS");
    assertThat(org.getEnabledProviders()).isEqualTo("gemini");
  }

  @Test
  void keySourcePolicyRejectsCustomerKeysOnManagedTier() {
    Org org = seedOrg("PRO", null, null, null, null);
    org.setKeySourceMode("SYSTEM");

    assertThatThrownBy(
            () ->
                svc.updateKeySourceSettings(
                    "org-1", "OWN_KEYS", java.util.List.of("openai"), "actor"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("TEAM, BYOK or Enterprise");
    assertThat(org.getKeySourceMode()).isEqualTo("SYSTEM");
  }

  @Test
  void adminTierDowngradeRevokesCustomerKeyAuthority() {
    Org org = seedOrg("TEAM", "sk-ant", null, null, null);
    org.setKeySourceMode("OWN_KEYS");

    svc.changeOrgTier("org-1", "FREE", "super-admin");

    assertThat(org.getTier()).isEqualTo("FREE");
    assertThat(org.getKeySourceMode()).isEqualTo("SYSTEM");
  }

  @Test
  void deepSeekAndFireworksRejectedOnUnentitledTier() {
    // RR-834 — DeepSeek/Fireworks are BYOK lanes; a non-BYOK tier (PRO) is still rejected.
    seedOrg("PRO", null, null, null, null);

    assertThatThrownBy(() -> svc.updateAiKeySettings("org-1", "", "", "", "", "sk-ds", "", "actor"))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () -> svc.updateAiKeySettings("org-1", "", "", "", "", "", "fw-key", "actor"))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void deepSeekAndFireworksAcceptedOnEnterprise() {
    Org org = seedOrg("ENTERPRISE", null, null, null, null);

    svc.updateAiKeySettings("org-1", "", "", "", "", " sk-ds-new ", " fw-new ", "actor");

    assertThat(org.getDeepSeekApiKey()).isEqualTo("sk-ds-new");
    assertThat(org.getFireworksApiKey()).isEqualTo("fw-new");
  }

  @Test
  void deepSeekAndFireworksAcceptedOnTeamAndByok() {
    // RR-834 — TEAM and BYOK are BYOK-capable tiers and must be able to configure these org lanes,
    // matching canManageAiKeys and the personal-key path (V237).
    Org team = seedOrg("TEAM", null, null, null, null);
    svc.updateAiKeySettings("org-1", "", "", "", "", "sk-ds-team", "fw-team", "actor");
    assertThat(team.getDeepSeekApiKey()).isEqualTo("sk-ds-team");
    assertThat(team.getFireworksApiKey()).isEqualTo("fw-team");

    Org byok = seedOrg("BYOK", null, null, null, null);
    svc.updateAiKeySettings("org-1", "", "", "", "", "sk-ds-byok", "fw-byok", "actor");
    assertThat(byok.getDeepSeekApiKey()).isEqualTo("sk-ds-byok");
    assertThat(byok.getFireworksApiKey()).isEqualTo("fw-byok");
  }

  @Test
  void keysAreTrimmedBeforePersistence() {
    Org org = seedOrg("TEAM", null, null, null, null);

    svc.updateAiKeySettings("org-1", "  sk-ant-spaced  ", "", "", "", "actor");

    assertThat(org.getAnthropicApiKey()).isEqualTo("sk-ant-spaced");
  }

  @Test
  void explicitRevokeClearsOnlySelectedProviderAndAudits() {
    Org org = seedOrg("ENTERPRISE", "sk-ant", "sk-oa", "sk-gem", "sk-or");
    org.setDeepSeekApiKey("sk-ds");

    svc.revokeAiKey("org-1", "deepseek", "actor");

    assertThat(org.getDeepSeekApiKey()).isNull();
    assertThat(org.getAnthropicApiKey()).isEqualTo("sk-ant");
    assertThat(org.getOpenRouterApiKey()).isEqualTo("sk-or");
    var audit = ArgumentCaptor.forClass(ActivityAudit.class);
    verify(auditService).record(audit.capture());
    assertThat(audit.getValue().getEventType()).isEqualTo("ORG_AI_KEY_REVOKED");
    assertThat(audit.getValue().getNewValue()).contains("deepseek").doesNotContain("sk-ds");
  }

  @Test
  void explicitRevokeRejectsUnknownProviderWithoutMutation() {
    Org org = seedOrg("ENTERPRISE", "sk-ant", null, null, null);

    assertThatThrownBy(() -> svc.revokeAiKey("org-1", "unknown", "actor"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Unsupported provider");
    assertThat(org.getAnthropicApiKey()).isEqualTo("sk-ant");
  }
}
