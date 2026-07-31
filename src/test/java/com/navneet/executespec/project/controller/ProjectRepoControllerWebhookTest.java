package com.navneet.executespec.project.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.project.service.LocalProjectSourceService;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.repo.ScmCredentialResolver;
import com.navneet.executespec.repo.ScmRepositoryClientRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ProjectRepoControllerWebhookTest {

  @Test
  void permissionFailureReturnsActionableStructuredBodyInsteadOfGenericGatewayFailure() {
    ProjectRepoService service = mock(ProjectRepoService.class);
    ProjectRepoController controller =
        new ProjectRepoController(
            service,
            mock(LocalProjectSourceService.class),
            mock(GitHubRepoService.class),
            mock(ScmRepositoryClientRegistry.class),
            mock(ScmCredentialResolver.class),
            mock(ProjectAccessGuard.class));
    UUID repoId = UUID.randomUUID();
    AppUser user =
        AppUser.builder()
            .id("user-1")
            .orgId("org-1")
            .email("user@example.com")
            .status("ACTIVE")
            .githubPat("secret-pat")
            .build();
    when(service.installWebhook(repoId, "secret-pat", "user-1", "org-1", false))
        .thenReturn(
            GitHubRepoService.WebhookInstallResult.rejected(
                "GITHUB_WEBHOOK_PERMISSION",
                "GITHUB_WEBHOOK_PERMISSION_DENIED",
                "GitHub denied permission to administer webhooks for this repository.",
                "Grant webhook administration permission, then retry.",
                false,
                403));

    var response = controller.installRepoWebhook(new AppUserDetails(user, null), repoId);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().installed()).isFalse();
    assertThat(response.getBody().eligibilityState()).isEqualTo("REJECTED");
    assertThat(response.getBody().blockingGate()).isEqualTo("GITHUB_WEBHOOK_PERMISSION");
    assertThat(response.getBody().errorCode()).isEqualTo("GITHUB_WEBHOOK_PERMISSION_DENIED");
    assertThat(response.getBody().message()).isEqualTo(response.getBody().reason());
    assertThat(response.getBody().remediation()).contains("retry");
    assertThat(response.getBody().upstreamStatus()).isEqualTo(403);
  }

  @Test
  void serverConfigurationFailureIsExplicitAndRetryDoesNotBlameCallerPat() {
    ProjectRepoService service = mock(ProjectRepoService.class);
    ProjectRepoController controller =
        new ProjectRepoController(
            service,
            mock(LocalProjectSourceService.class),
            mock(GitHubRepoService.class),
            mock(ScmRepositoryClientRegistry.class),
            mock(ScmCredentialResolver.class),
            mock(ProjectAccessGuard.class));
    UUID repoId = UUID.randomUUID();
    AppUser user =
        AppUser.builder()
            .id("user-1")
            .orgId("org-1")
            .email("user@example.com")
            .status("ACTIVE")
            .githubPat("secret-pat")
            .build();
    when(service.installWebhook(repoId, "secret-pat", "user-1", "org-1", false))
        .thenReturn(
            GitHubRepoService.WebhookInstallResult.blocked(
                "SERVER_WEBHOOK_CONFIGURATION",
                "WEBHOOK_CONFIGURATION_UNAVAILABLE",
                "Webhook installation is not configured in this ExecuteSpec environment.",
                "Ask an operator to configure it, then retry."));

    var response = controller.installRepoWebhook(new AppUserDetails(user, null), repoId);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody().blockingGate()).isEqualTo("SERVER_WEBHOOK_CONFIGURATION");
    assertThat(response.getBody().reason()).doesNotContain("PAT");
  }
}
