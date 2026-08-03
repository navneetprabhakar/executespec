package com.navneet.executespec.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class GitHubScmRepositoryClientTest {

  private final GitHubRepoService github = mock(GitHubRepoService.class);
  private final GitHubScmRepositoryClient client = new GitHubScmRepositoryClient(github);
  private final ScmCredential credential = new ScmCredential(null, "secret");

  @Test
  void mapsSuccessfulCanonicalRepository() {
    when(github.getRemoteRepoResult("acme/api", "secret"))
        .thenReturn(
            GitHubRepoService.RemoteRepoLookupResult.found(
                new GitHubRepoService.RemoteRepo(
                    "123", "acme/api", "https://github.com/acme/api.git", "main", true, 12L)));

    ScmRemoteRepository repository = client.inspect("acme/api", credential);

    assertThat(repository.immutableId()).isEqualTo("123");
    assertThat(repository.fullName()).isEqualTo("acme/api");
    assertThat(repository.isPrivate()).isTrue();
  }

  @Test
  void preservesAccessDenialInsteadOfInventingGatewayFailure() {
    when(github.getRemoteRepoResult("acme/private", "secret"))
        .thenReturn(GitHubRepoService.remoteRepoFailure(403));

    assertThatThrownBy(() -> client.inspect("acme/private", credential))
        .isInstanceOfSatisfying(
            ScmProviderException.class,
            error -> {
              assertThat(error.errorCode()).isEqualTo("GITHUB_REPOSITORY_ACCESS_DENIED");
              assertThat(error.getStatusCode().value()).isEqualTo(403);
              assertThat(error.upstreamStatus()).isEqualTo(403);
              assertThat(error.retryable()).isFalse();
            });
  }

  @Test
  void preservesNotFoundRateLimitAndUpstreamOutageCategories() {
    assertFailure(404, "GITHUB_REPOSITORY_NOT_FOUND_OR_INACCESSIBLE", false);
    assertFailure(429, "GITHUB_RATE_LIMITED", true);
    assertFailure(503, "GITHUB_UNAVAILABLE", true);
  }

  private void assertFailure(int status, String code, boolean retryable) {
    String fullName = "acme/repo-" + status;
    when(github.getRemoteRepoResult(fullName, "secret"))
        .thenReturn(GitHubRepoService.remoteRepoFailure(status));

    assertThatThrownBy(() -> client.inspect(fullName, credential))
        .isInstanceOfSatisfying(
            ScmProviderException.class,
            error -> {
              assertThat(error.errorCode()).isEqualTo(code);
              assertThat(error.upstreamStatus()).isEqualTo(status);
              assertThat(error.retryable()).isEqualTo(retryable);
            });
  }
}
