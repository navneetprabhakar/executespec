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
  }

  @Test
  void missing_credentials_report_no_configuration_without_fake_success() {
    var result =
        ScmPushService.PushResult.noConfiguration("GITHUB", "GitHub PAT is not configured");

    assertThat(result.status()).isEqualTo("NO_CONFIGURATION");
    assertThat(result.prUrl()).isEmpty();
    assertThat(result.attempted()).isZero();
    assertThat(result.message()).contains("PAT");
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
  }

  @Test
  void no_changes_is_distinct_from_pushed() {
    var result = ScmPushService.PushResult.noChanges("GITHUB", "No repository changes to push", 1);

    assertThat(result.status()).isEqualTo("NO_CHANGES");
    assertThat(result.status()).isNotEqualTo("PUSHED");
  }
}
