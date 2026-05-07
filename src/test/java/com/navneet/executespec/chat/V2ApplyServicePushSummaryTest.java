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
class V2ApplyServicePushSummaryTest {

  @Test
  void skipped_renders_reason() {
    String s =
        ApplyService.summarizePushResult(
            ScmPushService.ApplyPushResult.skipped("no GitHub PAT on run owner"));
    assertThat(s).startsWith("push: skipped").contains("no GitHub PAT on run owner");
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
    String s =
        ApplyService.summarizePushResult(
            ScmPushService.ApplyPushResult.multiRepo(List.of(pushed, clean, failed)));
    assertThat(s).contains("1 pushed").contains("1 clean").contains("1 failed").contains("3 repos");
  }

  @Test
  void null_result_is_safe() {
    String s = ApplyService.summarizePushResult(null);
    assertThat(s).isEqualTo("push: (unknown)");
  }
}
