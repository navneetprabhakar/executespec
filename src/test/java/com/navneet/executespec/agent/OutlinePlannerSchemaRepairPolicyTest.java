package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonParseException;

import org.junit.jupiter.api.Test;

class OutlinePlannerSchemaRepairPolicyTest {

  @Test
  void malformedToolArgumentJsonIsRetryable() {
    var parseFailure = new JsonParseException(null, "expected comma");
    var wrapped =
        new IllegalStateException(
            "submitPlan/submitOutline args are not valid JSON: expected comma", parseFailure);

    assertThat(OutlinePlannerSchemaRepairPolicy.isMalformedToolPayload(wrapped)).isTrue();
  }

  @Test
  void providerAndAuthenticationFailuresAreNotSchemaRetried() {
    assertThat(
            OutlinePlannerSchemaRepairPolicy.isMalformedToolPayload(
                new IllegalStateException("HTTP 401")))
        .isFalse();
    assertThat(
            OutlinePlannerSchemaRepairPolicy.isMalformedToolPayload(
                new IllegalStateException("timeout")))
        .isFalse();
  }
}
