package com.navneet.executespec.integration.scm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Contract tests for the explicit result returned by the canonical push-scm flow. */
class ScmPushResultTest {

  @Test
  void configured_push_reports_pushed_with_pr() {
    var result = ScmPushService.PushResult.pushed("GITHUB", "https://github.com/o/r/pull/1", "ok");

    assertThat(result.status()).isEqualTo("PUSHED");
    assertThat(result.scmProvider()).isEqualTo("GITHUB");
    assertThat(result.prUrl()).endsWith("/pull/1");
    assertThat(result.succeeded()).isOne();
    assertThat(result.failed()).isZero();
    assertThat(result.code()).isEqualTo("SCM_PUSHED");
    assertThat(result.publicationEligible()).isTrue();
    assertThat(result.retryable()).isFalse();
  }

  @Test
  void missing_credentials_report_no_configuration_without_fake_success() {
    var result =
        ScmPushService.PushResult.noConfiguration("GITHUB", "GitHub PAT is not configured");

    assertThat(result.status()).isEqualTo("NO_CONFIGURATION");
    assertThat(result.prUrl()).isEmpty();
    assertThat(result.attempted()).isZero();
    assertThat(result.message()).contains("PAT");
    assertThat(result.code()).isEqualTo("SCM_CONFIGURATION_REQUIRED");
    assertThat(result.publicationEligible()).isFalse();
    assertThat(result.retryable()).isTrue();
    assertThat(result.retryGuidance()).contains("credentials");
  }

  @Test
  void multi_repo_partial_result_preserves_counts_and_representative_pr() {
    var result =
        ScmPushService.PushResult.partial(
            "GITHUB", "https://github.com/o/r/pull/1", "one repository failed", 3, 2, 1);

    assertThat(result.status()).isEqualTo("PARTIAL");
    assertThat(result.attempted()).isEqualTo(3);
    assertThat(result.succeeded()).isEqualTo(2);
    assertThat(result.failed()).isOne();
    assertThat(result.code()).isEqualTo("SCM_PUSH_PARTIAL");
    assertThat(result.retryable()).isTrue();
  }

  @Test
  void no_changes_is_distinct_from_pushed() {
    var result = ScmPushService.PushResult.noChanges("GITHUB", "No repository changes to push", 1);

    assertThat(result.status()).isEqualTo("NO_CHANGES");
    assertThat(result.status()).isNotEqualTo("PUSHED");
    assertThat(result.code()).isEqualTo("SCM_NO_CHANGES");
    assertThat(result.publicationEligible()).isTrue();
    assertThat(result.retryable()).isFalse();
  }

  @Test
  void policy_block_reports_stable_code_and_non_retryable_remediation() {
    var result =
        ScmPushService.PushResult.blocked(
            "BROWNFIELD_HTTP_CONTRACT_REMOVED",
            "PROTECTED_CONTRACTS",
            "POST /orders was removed",
            "GITHUB",
            false,
            "Restore the route or obtain explicit approval.");

    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.attempted()).isZero();
    assertThat(result.code()).isEqualTo("BROWNFIELD_HTTP_CONTRACT_REMOVED");
    assertThat(result.blockingGate()).isEqualTo("PROTECTED_CONTRACTS");
    assertThat(result.publicationEligible()).isFalse();
    assertThat(result.retryable()).isFalse();
    assertThat(result.retryGuidance()).contains("explicit approval");
    assertThat(result.remediation()).contains("explicit approval");
  }

  @Test
  void git_and_pull_request_failures_keep_distinct_blocking_gates() {
    var git =
        ScmPushService.PushResult.failed(
            "SCM_GIT_PUSH_FAILED", "GIT_PUSH", "push rejected", "GITHUB", "Fix Git access.");
    var pr =
        ScmPushService.PushResult.failed(
            "SCM_PR_CREATION_FAILED", "PR_CREATION", "PR rejected", "GITHUB", "Fix PR policy.");

    assertThat(git.blockingGate()).isEqualTo("GIT_PUSH");
    assertThat(git.remediation()).isEqualTo("Fix Git access.");
    assertThat(pr.blockingGate()).isEqualTo("PR_CREATION");
    assertThat(pr.remediation()).isEqualTo("Fix PR policy.");
  }
}
