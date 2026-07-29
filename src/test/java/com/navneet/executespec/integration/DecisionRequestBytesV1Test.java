package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DecisionRequestBytesV1Test {

  @Test
  void typedJsonMakesDelimiterLikeUserTextUnambiguous() {
    DecisionRequestBytesV1 first = request("reason=value\nexecutionMode=REALTIME", "REALTIME");
    DecisionRequestBytesV1 second = request("reason=value", "REALTIME\nexecutionMode=REALTIME");

    assertThat(first.canonicalJson()).contains("\\nexecutionMode=REALTIME");
    assertThat(first.sha256()).hasSize(64).isNotEqualTo(second.sha256());
  }

  @Test
  void integerAndNullRemainTypedInsteadOfSentinelStrings() {
    String json = request(null, "REALTIME").canonicalJson();

    assertThat(json)
        .contains("\"resolutionReason\":null")
        .contains("\"creditsConfirmed\":3")
        .doesNotContain("<null>");
  }

  private static DecisionRequestBytesV1 request(String reason, String mode) {
    return new DecisionRequestBytesV1(
        1,
        "APPROVE",
        "user-1",
        "PLAN_APPROVAL_API",
        reason,
        mode,
        3,
        "a".repeat(64),
        null,
        null,
        null);
  }
}
