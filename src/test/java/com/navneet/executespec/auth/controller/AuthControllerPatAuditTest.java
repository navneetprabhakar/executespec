package com.navneet.executespec.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import com.navneet.executespec.auth.dto.CreatePatRequest;
import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.PersonalAccessToken;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.ActivityAuditService;
import com.navneet.executespec.auth.service.AiProviderPreferenceService;
import com.navneet.executespec.auth.service.AuthService;
import com.navneet.executespec.auth.service.PersonalAccessTokenService;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.auth.service.TierPermissionService;
import com.navneet.executespec.auth.service.UserDataExportService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuthControllerPatAuditTest {

  @Test
  void createRecordsScopesAndEveryImplicitDeviceCapRevocation() {
    ActivityAuditService audit = mock(ActivityAuditService.class);
    PersonalAccessTokenService pats = mock(PersonalAccessTokenService.class);
    AuthController controller =
        new AuthController(
            mock(AuthService.class),
            mock(AppUserRepository.class),
            mock(TierPermissionService.class),
            audit,
            mock(com.navneet.executespec.repo.BitbucketRepoService.class),
            mock(com.navneet.executespec.compliance.DataRetentionService.class),
            mock(UserDataExportService.class),
            pats,
            mock(AiProviderPreferenceService.class),
            mock(TierEnforcementService.class),
            mock(com.navneet.executespec.auth.service.ExternalConnectionValidator.class));
    AppUser user = new AppUser();
    user.setId("u1");
    PersonalAccessToken token =
        PersonalAccessToken.builder()
            .id("new-token")
            .userId("u1")
            .name("CLI")
            .tokenHash("hash")
            .tokenPrefix("es_pat_prefix")
            .scopes("api:read,runs:write")
            .createdAt(Instant.now())
            .build();
    when(pats.create("u1", new CreatePatRequest("CLI", null)))
        .thenReturn(
            new PersonalAccessTokenService.CreatedToken(
                "es_pat_plaintext", token, List.of("old-1", "old-2")));

    controller.createToken(new AppUserDetails(user, null), new CreatePatRequest("CLI", null));

    ArgumentCaptor<ActivityAudit> events = ArgumentCaptor.forClass(ActivityAudit.class);
    verify(audit, org.mockito.Mockito.times(3)).record(events.capture());
    assertThat(events.getAllValues())
        .extracting(ActivityAudit::getEventType)
        .containsExactly("USER_PAT_CREATED", "USER_PAT_AUTO_REVOKED", "USER_PAT_AUTO_REVOKED");
    assertThat(events.getAllValues().get(0).getNewValue())
        .contains("new-token", "api:read,runs:write");
    assertThat(events.getAllValues().get(1).getNewValue()).contains("old-1", "DEVICE_CAP_LRU");
    assertThat(events.getAllValues().get(2).getNewValue()).contains("old-2", "DEVICE_CAP_LRU");
  }
}
