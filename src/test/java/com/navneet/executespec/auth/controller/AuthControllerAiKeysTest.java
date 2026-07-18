package com.navneet.executespec.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.ActivityAuditService;
import com.navneet.executespec.auth.service.AiProviderPreferenceService;
import com.navneet.executespec.auth.service.AuthService;
import com.navneet.executespec.auth.service.PersonalAccessTokenService;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.auth.service.TierPermissionService;
import com.navneet.executespec.auth.service.UserDataExportService;
import com.navneet.executespec.compliance.DataRetentionService;
import com.navneet.executespec.repo.BitbucketRepoService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class AuthControllerAiKeysTest {

  private AppUserRepository users;
  private ActivityAuditService audits;
  private AuthController controller;
  private AppUser user;
  private AppUserDetails principal;

  @BeforeEach
  void setUp() {
    users = mock(AppUserRepository.class);
    audits = mock(ActivityAuditService.class);
    TierPermissionService permissions = mock(TierPermissionService.class);
    TierPermissionService.TierPermissions allowed =
        new TierPermissionService.TierPermissions(
            true,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            0,
            0,
            0,
            0,
            0,
            0,
            false,
            0,
            "UNLIMITED",
            0L,
            true,
            "test");
    when(permissions.getPermissions("user-1")).thenReturn(allowed);
    TierEnforcementService tiers = mock(TierEnforcementService.class);
    when(tiers.resolveUserTier("user-1")).thenReturn("BYOK");
    controller =
        new AuthController(
            mock(AuthService.class),
            users,
            permissions,
            audits,
            mock(BitbucketRepoService.class),
            mock(DataRetentionService.class),
            mock(UserDataExportService.class),
            mock(PersonalAccessTokenService.class),
            mock(AiProviderPreferenceService.class),
            tiers);
    user =
        AppUser.builder()
            .id("user-1")
            .email("dev@example.com")
            .globalRole("DEVELOPER")
            .status("ACTIVE")
            .anthropicApiKey("sk-ant-old")
            .openAiApiKey("sk-openai-old")
            .geminiApiKey("sk-gem-old")
            .build();
    principal = new AppUserDetails(user, null);
  }

  @Test
  void patchSaveDoesNotEraseUnmentionedKeys() {
    controller.updateAiKeySettings(
        principal, new AuthController.AiKeySettingsRequest("", " sk-ant-new ", "", "", ""));

    assertThat(user.getAnthropicApiKey()).isEqualTo("sk-ant-new");
    assertThat(user.getOpenAiApiKey()).isEqualTo("sk-openai-old");
    assertThat(user.getGeminiApiKey()).isEqualTo("sk-gem-old");
    verify(users).save(user);
  }

  @Test
  void explicitRevokeClearsOnlySelectedKeyAndWritesSecretFreeAudit() {
    controller.revokeAiKey(principal, "openai");

    assertThat(user.getOpenAiApiKey()).isNull();
    assertThat(user.getAnthropicApiKey()).isEqualTo("sk-ant-old");
    var event = ArgumentCaptor.forClass(ActivityAudit.class);
    verify(audits).record(event.capture());
    assertThat(event.getValue().getEventType()).isEqualTo("USER_AI_KEY_REVOKED");
    assertThat(event.getValue().getNewValue()).contains("openai").doesNotContain("sk-openai-old");
  }

  @Test
  void explicitRevokeRejectsUnknownProviderBeforeSave() {
    assertThatThrownBy(() -> controller.revokeAiKey(principal, "openrouter"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Unsupported personal-key provider");
    org.mockito.Mockito.verify(users, org.mockito.Mockito.never()).save(any());
  }
}
