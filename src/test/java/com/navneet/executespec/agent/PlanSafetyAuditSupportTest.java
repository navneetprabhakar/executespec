package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PlanSafetyAuditSupportTest {

  @Test
  void normalizesShortAuditEnumAliasesWithoutChangingActionProse() {
    String value =
        "Assert operations ADJUST, TRANSFER, RESERVE, CANCEL, COMMIT. Then adjust stock through"
            + " the public API.";

    assertThat(
            PlanSafetyAuditSupport.normalizeAuditOperations(
                value, List.of("ADJUSTMENT", "TRANSFER", "RESERVE", "CANCEL", "COMMIT")))
        .contains("ADJUSTMENT, TRANSFER, RESERVE, CANCEL, COMMIT")
        .contains("adjust stock through the public API")
        .doesNotContain("operations ADJUST,");
  }
}
