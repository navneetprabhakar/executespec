package com.navneet.executespec.repo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class GitHubRepoServiceWebhookTest {

  @Test
  void missingServerConfigurationIsDistinguishedFromPatPermissionFailure() {
    GitHubRepoService service = new GitHubRepoService();
    ReflectionTestUtils.setField(service, "webhookCallbackUrl", "");
    ReflectionTestUtils.setField(service, "webhookSecret", "");

    var result = service.installWebhookDetailed("acme/widget", "pat-value");

    assertThat(result.installed()).isFalse();
    assertThat(result.eligibilityState()).isEqualTo("BLOCKED");
    assertThat(result.blockingGate()).isEqualTo("SERVER_WEBHOOK_CONFIGURATION");
    assertThat(result.errorCode()).isEqualTo("WEBHOOK_CONFIGURATION_UNAVAILABLE");
    assertThat(result.remediation()).contains("callback URL").contains("signing secret");
    assertThat(result.upstreamStatus()).isNull();
  }

  @Test
  void missingCallerCredentialFailsBeforeAnyGitHubRequestWithSafeRemediation() {
    GitHubRepoService service = new GitHubRepoService();
    ReflectionTestUtils.setField(
        service, "webhookCallbackUrl", "https://dev.executespec.ai/webhooks/github");
    ReflectionTestUtils.setField(service, "webhookSecret", "configured-secret");

    var result = service.installWebhookDetailed("acme/widget", " ");

    assertThat(result.installed()).isFalse();
    assertThat(result.blockingGate()).isEqualTo("CALLER_GITHUB_CREDENTIAL");
    assertThat(result.errorCode()).isEqualTo("GITHUB_PAT_REQUIRED");
    assertThat(result.reason()).doesNotContain("configured-secret");
    assertThat(result.retryable()).isFalse();
  }

  @Test
  void githubPermissionDenialHasAStableGateCodeAndAction() {
    GitHubRepoService.WebhookInstallResult result =
        ReflectionTestUtils.invokeMethod(GitHubRepoService.class, "upstreamWebhookFailure", 403);

    assertThat(result).isNotNull();
    assertThat(result.eligibilityState()).isEqualTo("REJECTED");
    assertThat(result.blockingGate()).isEqualTo("GITHUB_WEBHOOK_PERMISSION");
    assertThat(result.errorCode()).isEqualTo("GITHUB_WEBHOOK_PERMISSION_DENIED");
    assertThat(result.reason()).contains("denied permission");
    assertThat(result.remediation()).contains("admin access").contains("retry");
    assertThat(result.upstreamStatus()).isEqualTo(403);
    assertThat(result.retryable()).isFalse();
  }
}
