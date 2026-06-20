package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.auth.service.OrgService;
import com.navneet.executespec.integration.IntegrationManager;
import com.navneet.executespec.integration.slack.model.OrgSlackIntegration;
import com.navneet.executespec.integration.slack.model.OrgSlackIntegrationRepository;
import com.navneet.executespec.integration.slack.model.ProjectSlackChannelRepository;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class IntegrationStatusControllerTest {

  private final RunAccessGuard guard = mock(RunAccessGuard.class);
  private final OrgService orgService = mock(OrgService.class);
  private final IntegrationManager manager = mock(IntegrationManager.class);
  private final OrgSlackIntegrationRepository slackIntegrationRepo =
      mock(OrgSlackIntegrationRepository.class);
  private final ProjectSlackChannelRepository slackChannelRepo =
      mock(ProjectSlackChannelRepository.class);

  @Test
  void statusUsesOrgCredentialsWhenUserHasOrg() {
    IntegrationStatusController controller =
        new IntegrationStatusController(manager, guard, orgService, null, null);
    AppUserDetails user = user("USER", "org-1");
    when(orgService.getEnabledIntegrations("org-1")).thenReturn(List.of("JIRA", "CONFLUENCE"));

    ResponseEntity<Map<String, Object>> response = controller.status(user);

    assertThat(response.getBody()).containsEntry("enabledSources", List.of("JIRA", "CONFLUENCE"));
    verify(guard).requireAuthenticated(user);
  }

  @Test
  void statusFallsBackToManagerForSuperAdminWithoutOrg() {
    IntegrationStatusController controller =
        new IntegrationStatusController(manager, guard, orgService, null, null);
    AppUserDetails user = user("SUPER_ADMIN", null);
    when(manager.getEnabledSources()).thenReturn(List.of("jira"));

    ResponseEntity<Map<String, Object>> response = controller.status(user);

    assertThat(response.getBody()).containsEntry("enabledSources", List.of("jira"));
    verify(guard).requireAuthenticated(user);
  }

  @Test
  void slackStatusReturnsDisconnectedWhenOptionalRepoIsUnavailable() {
    IntegrationStatusController controller =
        new IntegrationStatusController(manager, guard, orgService, null, null);
    AppUserDetails user = user("USER", "org-1");

    ResponseEntity<Map<String, Object>> response = controller.slackStatus(user);

    assertThat(response.getBody()).containsEntry("connected", false);
    verify(guard).requireAuthenticated(user);
  }

  @Test
  void slackStatusReturnsInstalledWorkspaceAndChannelCount() {
    IntegrationStatusController controller =
        new IntegrationStatusController(
            manager, guard, orgService, slackIntegrationRepo, slackChannelRepo);
    AppUserDetails user = user("USER", "org-1");
    Instant installedAt = Instant.parse("2026-06-20T12:00:00Z");
    OrgSlackIntegration integration =
        OrgSlackIntegration.builder()
            .orgId("org-1")
            .teamId("T123")
            .teamName("ExecuteSpec")
            .installedBy("u1")
            .installedAt(installedAt)
            .build();
    when(slackIntegrationRepo.findByOrgId("org-1")).thenReturn(Optional.of(integration));
    when(slackChannelRepo.countByOrgId("org-1")).thenReturn(3L);

    ResponseEntity<Map<String, Object>> response = controller.slackStatus(user);

    assertThat(response.getBody())
        .containsEntry("connected", true)
        .containsEntry("teamName", "ExecuteSpec")
        .containsEntry("teamId", "T123")
        .containsEntry("installedBy", "u1")
        .containsEntry("channelCount", 3L)
        .containsEntry("installedAt", "2026-06-20T12:00:00Z");
    verify(guard).requireAuthenticated(user);
  }

  private static AppUserDetails user(String globalRole, String orgId) {
    AppUser user =
        AppUser.builder()
            .id("u1")
            .email("user@example.com")
            .firstName("User")
            .globalRole(globalRole)
            .status("ACTIVE")
            .orgId(orgId)
            .build();
    return new AppUserDetails(user, null);
  }
}
