package com.navneet.executespec.project.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.project.service.LocalProjectSourceService;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.repo.ScmCredentialResolver;
import com.navneet.executespec.repo.ScmProviderException;
import com.navneet.executespec.repo.ScmRepositoryClientRegistry;

import org.junit.jupiter.api.Test;

class ProjectRepoControllerAttachErrorTest {

  private final ProjectRepoController controller =
      new ProjectRepoController(
          mock(ProjectRepoService.class),
          mock(LocalProjectSourceService.class),
          mock(GitHubRepoService.class),
          mock(ScmRepositoryClientRegistry.class),
          mock(ScmCredentialResolver.class),
          mock(ProjectAccessGuard.class));

  @Test
  void accessFailureIsActionableAndLeakSafe() {
    var response =
        controller.handleScmProviderException(
            new ScmProviderException("GITHUB_REPOSITORY_ACCESS_DENIED", 403, false));

    assertThat(response.getStatusCode().value()).isEqualTo(403);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().errorCode()).isEqualTo("GITHUB_REPOSITORY_ACCESS_DENIED");
    assertThat(response.getBody().message()).contains("Grant").contains("reconnect GitHub");
    assertThat(response.getBody().message()).doesNotContain("secret", "exception", "PAT");
    assertThat(response.getBody().action()).isEqualTo("UPDATE_GITHUB_ACCESS");
    assertThat(response.getBody().retryable()).isFalse();
  }

  @Test
  void rateLimitAndOutageHaveOneBoundedRetryAction() {
    var rateLimit =
        controller.handleScmProviderException(
            new ScmProviderException("GITHUB_RATE_LIMITED", 429, true));
    var outage =
        controller.handleScmProviderException(
            new ScmProviderException("GITHUB_UNAVAILABLE", 503, true));

    assertThat(rateLimit.getStatusCode().value()).isEqualTo(429);
    assertThat(rateLimit.getBody().action()).isEqualTo("RETRY");
    assertThat(rateLimit.getBody().message()).contains("rate-limiting");
    assertThat(outage.getStatusCode().value()).isEqualTo(502);
    assertThat(outage.getBody().action()).isEqualTo("RETRY");
    assertThat(outage.getBody().message()).contains("temporarily unavailable");
  }

  @Test
  void bitbucketFailureDoesNotMislabelTheProvider() {
    var response =
        controller.handleScmProviderException(
            new ScmProviderException("BITBUCKET_REPOSITORY_NOT_FOUND", 404, false));

    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().message()).contains("Bitbucket").doesNotContain("GitHub");
    assertThat(response.getBody().action()).isEqualTo("UPDATE_BITBUCKET_ACCESS");
  }
}
