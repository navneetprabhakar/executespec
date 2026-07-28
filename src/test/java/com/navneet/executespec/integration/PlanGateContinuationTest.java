package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

class PlanGateContinuationTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String COMPLETE =
      "{\"schemaVersion\":1,\"localRun\":false,"
          + "\"requireContinuationConfirmation\":false,"
          + "\"requireContinuationBenchmarkPrior\":false,"
          + "\"pinnedModel\":null,\"lowComplexityWorkerStartTierOverride\":null}";

  @Test
  void decoderAcceptsCompleteEnvelopeWithExplicitNullOptionalValues() throws Exception {
    PlanGateContinuation decoded = JSON.readValue(COMPLETE, PlanGateContinuation.class);

    assertThat(decoded.schemaVersion()).isEqualTo(1);
    assertThat(decoded.localRun()).isFalse();
    assertThat(decoded.requireContinuationConfirmation()).isFalse();
    assertThat(decoded.requireContinuationBenchmarkPrior()).isFalse();
    assertThat(decoded.pinnedModel()).isNull();
    assertThat(decoded.lowComplexityWorkerStartTierOverride()).isNull();
  }

  @Test
  void decoderRejectsEveryMissingVersionOneProperty() throws Exception {
    for (String property :
        List.of(
            "schemaVersion",
            "localRun",
            "requireContinuationConfirmation",
            "requireContinuationBenchmarkPrior",
            "pinnedModel",
            "lowComplexityWorkerStartTierOverride")) {
      ObjectNode incomplete = (ObjectNode) JSON.readTree(COMPLETE);
      incomplete.remove(property);

      assertThatThrownBy(
              () -> JSON.readValue(JSON.writeValueAsString(incomplete), PlanGateContinuation.class))
          .as("missing %s", property)
          .isInstanceOf(JsonMappingException.class);
    }
  }

  @Test
  void decoderRejectsNullRequiredBooleans() throws Exception {
    for (String property :
        List.of(
            "localRun", "requireContinuationConfirmation", "requireContinuationBenchmarkPrior")) {
      ObjectNode incomplete = (ObjectNode) JSON.readTree(COMPLETE);
      incomplete.putNull(property);

      assertThatThrownBy(
              () -> JSON.readValue(JSON.writeValueAsString(incomplete), PlanGateContinuation.class))
          .as("null %s", property)
          .isInstanceOf(JsonMappingException.class);
    }
  }

  @Test
  void decoderRejectsNullOrNonIntegralSchemaAndUnknownProperties() throws Exception {
    ObjectNode nullSchema = (ObjectNode) JSON.readTree(COMPLETE);
    nullSchema.putNull("schemaVersion");
    ObjectNode decimalSchema = (ObjectNode) JSON.readTree(COMPLETE);
    decimalSchema.put("schemaVersion", 1.0);
    ObjectNode widened = (ObjectNode) JSON.readTree(COMPLETE);
    widened.put("unversionedPolicy", true);

    assertThatThrownBy(
            () -> JSON.readValue(JSON.writeValueAsString(nullSchema), PlanGateContinuation.class))
        .isInstanceOf(JsonMappingException.class);
    assertThatThrownBy(
            () ->
                JSON.readValue(JSON.writeValueAsString(decimalSchema), PlanGateContinuation.class))
        .isInstanceOf(JsonMappingException.class);
    assertThatThrownBy(
            () -> JSON.readValue(JSON.writeValueAsString(widened), PlanGateContinuation.class))
        .isInstanceOf(JsonMappingException.class);
  }
}
