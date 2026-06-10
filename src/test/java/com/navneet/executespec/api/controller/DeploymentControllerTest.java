package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.state.entity.Deployment;
import com.navneet.executespec.state.repository.DeploymentRepository;

import org.junit.jupiter.api.Test;

class DeploymentControllerTest {

  private final DeploymentRepository deploymentRepo = mock(DeploymentRepository.class);
  private final RunAccessGuard guard = mock(RunAccessGuard.class);
  private final DeploymentController controller =
      new DeploymentController(
          null, deploymentRepo, null, guard, null, null, null, null, null, null, null);

  @Test
  void listWithoutRunIdUsesCallerOrgForNonSuperAdmin() {
    when(deploymentRepo.findByOrgIdOrderByCreatedAtDesc("org-a"))
        .thenReturn(List.of(deployment("dep-1", Deployment.DeployStatus.LIVE)));

    var summaries = controller.list(null, principal("user-1", "org-a", "USER"));

    assertThat(summaries).hasSize(1);
    verify(deploymentRepo).findByOrgIdOrderByCreatedAtDesc("org-a");
    verify(deploymentRepo, never()).findAll();
  }

  @Test
  void liveUsesCallerOrgForNonSuperAdmin() {
    when(deploymentRepo.findByStatusAndOrgIdOrderByCreatedAtDesc(
            Deployment.DeployStatus.LIVE, "org-a"))
        .thenReturn(List.of(deployment("dep-1", Deployment.DeployStatus.LIVE)));

    var summaries = controller.live(principal("user-1", "org-a", "USER"));

    assertThat(summaries).hasSize(1);
    verify(deploymentRepo)
        .findByStatusAndOrgIdOrderByCreatedAtDesc(Deployment.DeployStatus.LIVE, "org-a");
    verify(deploymentRepo, never()).findByStatus(Deployment.DeployStatus.LIVE);
  }

  @Test
  void superAdminListWithoutRunIdKeepsGlobalView() {
    when(deploymentRepo.findAll())
        .thenReturn(List.of(deployment("dep-1", Deployment.DeployStatus.LIVE)));

    var summaries = controller.list(null, principal("admin-1", "admin-org", "SUPER_ADMIN"));

    assertThat(summaries).hasSize(1);
    verify(deploymentRepo).findAll();
    verify(deploymentRepo, never())
        .findByOrgIdOrderByCreatedAtDesc(org.mockito.ArgumentMatchers.anyString());
  }

  private static Deployment deployment(String id, Deployment.DeployStatus status) {
    return Deployment.builder()
        .id(id)
        .runId("run-" + id)
        .versionTag("v1")
        .strategy(Deployment.DeployStrategy.FULLSTACK)
        .status(status)
        .imageUri("image")
        .serviceName("svc")
        .gcpProject("gcp")
        .gcpRegion("us-east4")
        .createdAt("2026-06-10T00:00:00Z")
        .ttlExpiresAt("2026-06-10T01:00:00Z")
        .build();
  }

  private static AppUserDetails principal(String userId, String orgId, String globalRole) {
    AppUser user =
        AppUser.builder()
            .id(userId)
            .email(userId + "@example.com")
            .firstName("Test")
            .orgId(orgId)
            .globalRole(globalRole)
            .status("ACTIVE")
            .build();
    return new AppUserDetails(user, null);
  }
}
