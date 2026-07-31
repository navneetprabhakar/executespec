package com.navneet.executespec.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.integration.scm.ScmPushService;

import org.junit.jupiter.api.Test;

/**
 * Pins the human-readable summary line for {@link ApplyService#summarizePushResult}. The line lands
 * on the apply outcome surface (and inside the polled {@link ApplyResultStore.ApplyState} record),
 * so the wording matters — it's the only feedback the user has when their browser disconnected
 * mid-apply and they re-attached via the polling endpoint.
 *
 * <p>Pure pure-function tests; no Spring context.
 */
class ApplyServicePushSummaryTest {

  @Test
  void skipped_renders_reason() {
    String s =
        ApplyService.summarizePushResult(
            ScmPushService.ApplyPushResult.skipped("no GitHub PAT on run owner"));
    assertThat(s).startsWith("push: skipped").contains("no GitHub PAT on run owner");
  }

  @Test
  void missingCredential_retains_machine_diagnostic_in_compatibility_skipped_mode() {
    var result =
        ScmPushService.ApplyPushResult.blocked(
            "SCM_APPLY_CREDENTIAL_MISSING",
            "AUTHENTICATION",
            "The run owner has no GitHub credential",
            true,
            "Connect GitHub, then retry.");

    assertThat(result.mode()).isEqualTo("SKIPPED");
    assertThat(result.code()).isEqualTo("SCM_APPLY_CREDENTIAL_MISSING");
    assertThat(result.blockingGate()).isEqualTo("AUTHENTICATION");
    assertThat(result.retryable()).isTrue();
    assertThat(result.remediation()).contains("Connect GitHub");
  }

  @Test
  void single_repo_clean_renders_clean_message() {
    var outcome =
        new ScmPushService.ApplyRepoOutcome("o/r", "executespec/abc", true, true, "CLEAN");
    String s = ApplyService.summarizePushResult(ScmPushService.ApplyPushResult.singleRepo(outcome));
    assertThat(s).isEqualTo("push: clean (no diff to push)");
  }

  @Test
  void single_repo_pushed_renders_branch_name() {
    var outcome =
        new ScmPushService.ApplyRepoOutcome(
            "o/r", "executespec/abc1234", true, false, "executespec/abc1234");
    String s = ApplyService.summarizePushResult(ScmPushService.ApplyPushResult.singleRepo(outcome));
    assertThat(s).isEqualTo("push: pushed → executespec/abc1234");
  }

  @Test
  void single_repo_failed_renders_error_message() {
    var outcome =
        new ScmPushService.ApplyRepoOutcome(
            "o/r", "executespec/abc", false, false, "push failed: rejected (non-fast-forward)");
    String s = ApplyService.summarizePushResult(ScmPushService.ApplyPushResult.singleRepo(outcome));
    assertThat(s).startsWith("push: failed").contains("non-fast-forward");
  }

  @Test
  void multi_repo_aggregates_counts() {
    var pushed =
        new ScmPushService.ApplyRepoOutcome("o/api", "executespec/x", true, false, "executespec/x");
    var clean = new ScmPushService.ApplyRepoOutcome("o/web", "executespec/x", true, true, "CLEAN");
    var failed =
        new ScmPushService.ApplyRepoOutcome(
            "o/mobile", "executespec/x", false, false, "push failed: auth");
    ScmPushService.ApplyPushResult result =
        ScmPushService.ApplyPushResult.multiRepo(List.of(pushed, clean, failed));
    String s = ApplyService.summarizePushResult(result);
    assertThat(s).contains("1 pushed").contains("1 clean").contains("1 failed").contains("3 repos");
    assertThat(result.code()).isEqualTo("SCM_APPLY_PUSH_PARTIAL");
    assertThat(result.blockingGate()).isEqualTo("GIT_PUSH");
    assertThat(result.publicationEligible()).isFalse();
    assertThat(result.retryable()).isTrue();
    assertThat(failed.code()).isEqualTo("SCM_APPLY_PUSH_FAILED");
    assertThat(failed.blockingGate()).isEqualTo("GIT_PUSH");
    assertThat(failed.remediation()).isNotBlank();
  }

  @Test
  void all_failed_is_not_reported_as_an_attempted_or_eligible_push() {
    var failed =
        new ScmPushService.ApplyRepoOutcome(
            "o/api", "executespec/x", false, false, "push failed: auth");

    var result = ScmPushService.ApplyPushResult.multiRepo(List.of(failed));

    assertThat(result.code()).isEqualTo("SCM_APPLY_PUSH_FAILED");
    assertThat(result.publicationEligible()).isFalse();
    assertThat(result.retryable()).isTrue();
  }

  @Test
  void aggregateGateReflectsWorkspaceSafetyAndMixedFailureClasses() {
    var workspaceFailure =
        new ScmPushService.ApplyRepoOutcome(
            "o/api",
            "executespec/x",
            false,
            false,
            "secret found",
            "SCM_APPLY_WORKSPACE_UNSAFE",
            "WORKSPACE_SAFETY",
            false,
            "Remove the secret.");
    var pushFailure =
        new ScmPushService.ApplyRepoOutcome(
            "o/web",
            "executespec/x",
            false,
            false,
            "push rejected",
            "SCM_APPLY_GIT_PUSH_FAILED",
            "GIT_PUSH",
            true,
            "Resolve the push failure.");

    assertThat(ScmPushService.ApplyPushResult.multiRepo(List.of(workspaceFailure)).blockingGate())
        .isEqualTo("WORKSPACE_SAFETY");
    assertThat(
            ScmPushService.ApplyPushResult.multiRepo(List.of(workspaceFailure, pushFailure))
                .blockingGate())
        .isEqualTo("MULTIPLE");
  }

  @Test
  void null_result_is_safe() {
    String s = ApplyService.summarizePushResult(null);
    assertThat(s).isEqualTo("push: (unknown)");
  }
}
