package com.navneet.executespec.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.ActivityAuditService;
import com.navneet.executespec.auth.service.InvitationService;
import com.navneet.executespec.auth.service.OrgService;
import com.navneet.executespec.router.ModelMatrixProperties;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.router.OrgModelOverrideService;
import com.navneet.executespec.router.ProviderReadinessService;

import org.junit.jupiter.api.Test;

class OrgControllerModelMatrixPolicyTest {

  @Test
  void ownKeysMatrixContainsOnlyCredentialedPolicyEligibleProviders() {
    OrgRepository orgRepo = mock(OrgRepository.class);
    Org org = Org.builder().id("org-1").tier("ENTERPRISE").keySourceMode("OWN_KEYS").build();
    when(orgRepo.findById("org-1")).thenReturn(Optional.of(org));

    OrgModelOverrideService overrides = mock(OrgModelOverrideService.class);
    when(overrides.canEditMatrix("ENTERPRISE")).thenReturn(true);
    when(overrides.currentOverrides("org-1"))
        .thenReturn(
            Map.of(
                "deepseek:planning", "deepseek-v4-pro-native",
                "anthropic:planning", "claude-sonnet-5"));

    ProviderReadinessService readiness = mock(ProviderReadinessService.class);
    when(readiness.resolve("admin-1"))
        .thenReturn(
            List.of(
                row("DEEPSEEK", ProviderReadinessService.State.AVAILABLE_ORG_KEY, true),
                row("ANTHROPIC", ProviderReadinessService.State.UNAVAILABLE, false),
                row("OPENROUTER", ProviderReadinessService.State.POLICY_BLOCKED, false)));

    ModelRegistry registry = mock(ModelRegistry.class);
    when(registry.planningModelFor("deepseek")).thenReturn("deepseek-v4-pro-native");
    when(registry.workhouseModelFor("deepseek")).thenReturn("deepseek-v4-pro-native");
    when(registry.lightweightModelFor("deepseek")).thenReturn("deepseek-v4-flash-native");
    when(registry.allModels())
        .thenReturn(
            Map.of(
                "deepseek-v4-pro-native", mock(ModelMatrixProperties.ModelConfig.class),
                "deepseek-v4-flash-native", mock(ModelMatrixProperties.ModelConfig.class)));
    when(registry.providerFor("deepseek-v4-pro-native")).thenReturn("deepseek");
    when(registry.providerFor("deepseek-v4-flash-native")).thenReturn("deepseek");
    when(registry.displayLabelFor("deepseek-v4-pro-native")).thenReturn("DeepSeek V4 Pro");
    when(registry.displayLabelFor("deepseek-v4-flash-native")).thenReturn("DeepSeek V4 Flash");

    OrgController controller =
        new OrgController(
            mock(OrgService.class),
            mock(InvitationService.class),
            mock(ActivityAuditService.class),
            orgRepo,
            mock(OrgMemberRepository.class),
            overrides,
            registry,
            readiness);
    AppUser admin =
        AppUser.builder().id("admin-1").orgId("org-1").globalRole("USER").status("ACTIVE").build();

    OrgController.ModelMatrixView view =
        controller.getModelMatrix(new AppUserDetails(admin, null)).getBody();

    assertThat(view).isNotNull();
    assertThat(view.providers()).containsExactly("deepseek");
    assertThat(view.defaults().keySet()).allMatch(key -> key.startsWith("deepseek:"));
    assertThat(view.modelsByProvider()).containsOnlyKeys("deepseek");
    assertThat(view.overrides())
        .containsExactlyEntriesOf(Map.of("deepseek:planning", "deepseek-v4-pro-native"));
  }

  private static ProviderReadinessService.ProviderReadiness row(
      String provider, ProviderReadinessService.State state, boolean dispatchable) {
    return new ProviderReadinessService.ProviderReadiness(
        provider, state, dispatchable, "OWN_KEYS", "secret-free test reason");
  }
}
