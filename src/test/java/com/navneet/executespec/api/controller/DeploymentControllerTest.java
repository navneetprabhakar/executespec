package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.api.dto.DeployRequest;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.auth.service.TierPermissionService;
import com.navneet.executespec.auth.service.TierPermissionService.TierPermissions;
import com.navneet.executespec.config.OperationalReleasePolicy;
import com.navneet.executespec.deployment.DeploymentService;
import com.navneet.executespec.state.entity.Deployment;
import com.navneet.executespec.state.repository.DeploymentRepository;

import org.junit.jupiter.api.Test;

class DeploymentControllerTest {

  private final DeploymentRepository deploymentRepo = mock(DeploymentRepository.class);
  private final RunAccessGuard guard = mock(RunAccessGuard.class);
  private final DeploymentService deploymentService = mock(DeploymentService.class);
  private final TierPermissionService tierPermissionService = mock(TierPermissionService.class);
  private final OperationalReleasePolicy releasePolicy = mock(OperationalReleasePolicy.class);
  private final DeploymentController controller =
      new DeploymentController(
          deploymentService,
          deploymentRepo,
          null,
          guard,
          null,
          null,
          null,
          null,
          null,
          null,
          tierPermissionService,
          releasePolicy);

  @Test
  void deployFailsClosedBeforeAccessOrDeploymentMutationWhenReleasePolicyDisablesCreation() {
    when(releasePolicy.deploymentCreationEnabled()).thenReturn(false);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.deploy(
                    new DeployRequest("run-1", "NONE", null, null),
                    principal("user-1", "org-a", "USER")))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("503 SERVICE_UNAVAILABLE");

    verify(guard, never())
        .requireRunAccess(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(AppUserDetails.class));
    verify(deploymentService, never())
        .deploy(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void deployTreatsOmittedRepoModeAsDirectWorkspaceDeployment() {
    when(releasePolicy.deploymentCreationEnabled()).thenReturn(true);
    when(tierPermissionService.getPermissions("user-1")).thenReturn(permissions(true));
    Deployment deployment = deployment("dep-1", Deployment.DeployStatus.PENDING);
    when(deploymentService.deploy(
            "run-1", com.navneet.executespec.state.entity.RunRepo.RepoMode.NONE, null, null))
        .thenReturn(deployment);

    var response =
        controller.deploy(
            new DeployRequest("run-1", null, null, null), principal("user-1", "org-a", "USER"));

    assertThat(response.getStatusCode().value()).isEqualTo(202);
    verify(deploymentService)
        .deploy("run-1", com.navneet.executespec.state.entity.RunRepo.RepoMode.NONE, null, null);
  }

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
  void runScopedListUsesRunReadAuthorizationAndNeverRequiresOrgWideListing() {
    AppUserDetails principal = principal("user-1", "org-a", "USER");
    when(deploymentRepo.findByRunIdOrderByCreatedAtDesc("run-1"))
        .thenReturn(List.of(deployment("dep-1", Deployment.DeployStatus.DEPLOYING)));

    var summaries = controller.list("run-1", principal);

    assertThat(summaries).hasSize(1);
    verify(guard).requireRunAccess("run-1", principal);
    verify(deploymentRepo).findByRunIdOrderByCreatedAtDesc("run-1");
    verify(deploymentRepo, never()).findAll();
    verify(deploymentRepo, never())
        .findByOrgIdOrderByCreatedAtDesc(org.mockito.ArgumentMatchers.anyString());
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

  private static TierPermissions permissions(boolean canDeploy) {
    return new TierPermissions(
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
        false,
        0,
        0,
        0,
        0,
        0,
        0,
        canDeploy,
        1,
        "UNLIMITED",
        0L,
        true,
        "test");
  }
}
