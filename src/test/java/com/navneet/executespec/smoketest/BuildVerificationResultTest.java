package com.navneet.executespec.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildVerificationResultTest {

  @Test
  void noOpExitZeroIsUnavailableNotPassed() {
    var result =
        BuildVerificationResult.parse(
            "command=true\nsource=fallback:no-op\nsmokeSignal=UNAVAILABLE\nnoOp=true\nexit=0");

    assertThat(result.status()).isEqualTo(BuildVerificationResult.Status.UNAVAILABLE);
    assertThat(result.passed()).isFalse();
  }

  @Test
  void parsesExecutedBuildInRawAndJsonEscapedEnvelopes() {
    String evidence = "smokeSignal=EXECUTED\nnoOp=false\nexit=0\noutput:\nok";

    assertThat(BuildVerificationResult.parse(evidence).passed()).isTrue();
    assertThat(
            BuildVerificationResult.parse(
                    "{\"ok\":true,\"content\":\"" + evidence.replace("\n", "\\n") + "\"}")
                .passed())
        .isTrue();
  }

  @Test
  void missingExitIsMalformedRatherThanImplicitSuccess() {
    assertThat(BuildVerificationResult.parse("smokeSignal=EXECUTED").status())
        .isEqualTo(BuildVerificationResult.Status.MALFORMED);
  }
}
