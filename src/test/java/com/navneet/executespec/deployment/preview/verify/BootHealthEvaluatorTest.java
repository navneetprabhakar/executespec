package com.navneet.executespec.deployment.preview.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator.BootSignals;
import com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator.BootVerification;
import com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator.Verdict;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BootHealthEvaluator deterministic boot verdict")
class BootHealthEvaluatorTest {

  private static BootVerification eval(
      boolean build, boolean started, Integer status, long elapsed, long timeout) {
    return BootHealthEvaluator.evaluate(
        new BootSignals(build, started, status, "/health", elapsed, timeout));
  }

  @Test
  void build_failure_short_circuits() {
    var v = eval(false, false, null, 0, 5000);
    assertThat(v.verdict()).isEqualTo(Verdict.BUILD_FAILED);
    assertThat(v.healthy()).isFalse();
  }

  @Test
  void built_but_did_not_start() {
    var v = eval(true, false, null, 100, 5000);
    assertThat(v.verdict()).isEqualTo(Verdict.DID_NOT_START);
    assertThat(v.healthy()).isFalse();
  }

  @Test
  void started_but_no_response_within_timeout_is_timed_out() {
    assertThat(eval(true, true, null, 5000, 5000).verdict()).isEqualTo(Verdict.TIMED_OUT);
    assertThat(eval(true, true, null, 100, 5000).verdict()).isEqualTo(Verdict.TIMED_OUT);
  }

  @Test
  void success_status_is_healthy() {
    assertThat(eval(true, true, 200, 300, 5000).healthy()).isTrue();
    assertThat(eval(true, true, 302, 300, 5000).verdict())
        .isEqualTo(Verdict.HEALTHY); // redirect = up
  }

  @Test
  void error_status_is_unhealthy() {
    assertThat(eval(true, true, 500, 300, 5000).verdict()).isEqualTo(Verdict.UNHEALTHY);
    assertThat(eval(true, true, 404, 300, 5000).healthy()).isFalse();
  }

  @Test
  void null_signals_are_safe() {
    var v = BootHealthEvaluator.evaluate(null);
    assertThat(v.verdict()).isEqualTo(Verdict.DID_NOT_START);
    assertThat(v.healthy()).isFalse();
  }
}
