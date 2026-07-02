package com.navneet.executespec.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.service.ActivityAuditService;
import com.navneet.executespec.auth.service.AiProviderPreferenceService;
import com.navneet.executespec.auth.service.AuthService;
import com.navneet.executespec.auth.service.PersonalAccessTokenService;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.auth.service.TierPermissionService;
import com.navneet.executespec.auth.service.UserDataExportService;
import com.navneet.executespec.compliance.DataRetentionService;
import com.navneet.executespec.repo.BitbucketRepoService;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class AuthControllerDataExportTest {

  @Test
  void jsonAcceptGetsExplicitZipRequirementHint() {
    AuthController controller =
        new AuthController(
            mock(AuthService.class),
            mock(AppUserRepository.class),
            mock(TierPermissionService.class),
            mock(ActivityAuditService.class),
            mock(BitbucketRepoService.class),
            mock(DataRetentionService.class),
            mock(UserDataExportService.class),
            mock(PersonalAccessTokenService.class),
            mock(AiProviderPreferenceService.class),
            mock(TierEnforcementService.class));

    ResponseEntity<Map<String, String>> response = controller.exportMyDataJsonHint();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
    assertThat(response.getBody())
        .containsEntry("error", "data_export_requires_zip_accept")
        .containsEntry(
            "message", "This endpoint returns a ZIP bundle. Retry with Accept: application/zip.");
  }
}
