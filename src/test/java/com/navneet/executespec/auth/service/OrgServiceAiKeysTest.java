package com.navneet.executespec.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.billing.gateway.StripePaymentGateway;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pins {@link OrgService#updateAiKeySettings} semantics:
 *
 * <ul>
 *   <li>A blank value means "leave unchanged", not "clear" — the settings UI saves one provider at
 *       a time and sends only the edited field, so an unconditional set would wipe the others.
 *   <li>OpenRouter is Enterprise-only: a key on a lower tier is rejected with 403; on ENTERPRISE it
 *       is stored.
 *   <li>Keys are trimmed before persistence.
 * </ul>
 *
 * <p>Pure Mockito; no Spring context.
 */
class OrgServiceAiKeysTest {

  private OrgRepository orgRepo;
  private OrgService svc;

  @BeforeEach
  void setUp() {
    orgRepo = mock(OrgRepository.class);
    svc =
        new OrgService(
            orgRepo,
            mock(OrgMemberRepository.class),
            mock(AppUserRepository.class),
            mock(PaymentAuditService.class),
            mock(ActivityAuditService.class),
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
  void openRouterRejectedOnNonEnterprise() {
    seedOrg("TEAM", null, null, null, null);

    assertThatThrownBy(() -> svc.updateAiKeySettings("org-1", "", "", "", "sk-or", "actor"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Enterprise");
  }

  @Test
  void openRouterAcceptedOnEnterprise() {
    Org org = seedOrg("ENTERPRISE", null, null, null, null);

    svc.updateAiKeySettings("org-1", "", "", "", "sk-or-new", "actor");

    assertThat(org.getOpenRouterApiKey()).isEqualTo("sk-or-new");
  }

  @Test
  void deepSeekAndFireworksRejectedOnNonEnterprise() {
    seedOrg("TEAM", null, null, null, null);

    assertThatThrownBy(() -> svc.updateAiKeySettings("org-1", "", "", "", "", "sk-ds", "", "actor"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Enterprise");
    assertThatThrownBy(
            () -> svc.updateAiKeySettings("org-1", "", "", "", "", "", "fw-key", "actor"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Enterprise");
  }

  @Test
  void deepSeekAndFireworksAcceptedOnEnterprise() {
    Org org = seedOrg("ENTERPRISE", null, null, null, null);

    svc.updateAiKeySettings("org-1", "", "", "", "", " sk-ds-new ", " fw-new ", "actor");

    assertThat(org.getDeepSeekApiKey()).isEqualTo("sk-ds-new");
    assertThat(org.getFireworksApiKey()).isEqualTo("fw-new");
  }

  @Test
  void keysAreTrimmedBeforePersistence() {
    Org org = seedOrg("TEAM", null, null, null, null);

    svc.updateAiKeySettings("org-1", "  sk-ant-spaced  ", "", "", "", "actor");

    assertThat(org.getAnthropicApiKey()).isEqualTo("sk-ant-spaced");
  }
}
