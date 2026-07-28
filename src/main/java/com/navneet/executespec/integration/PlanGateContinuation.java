package com.navneet.executespec.integration;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Versioned, fail-closed execution policy persisted with an approved plan-gate handoff. */
public record PlanGateContinuation(
    int schemaVersion,
    boolean localRun,
    boolean requireContinuationConfirmation,
    boolean requireContinuationBenchmarkPrior,
    String pinnedModel,
    String lowComplexityWorkerStartTierOverride) {

  public static final int SCHEMA_VERSION = 1;
  private static final ObjectMapper DECODER = new ObjectMapper();
  private static final Set<String> REQUIRED_PROPERTIES =
      Set.of(
          "schemaVersion",
          "localRun",
          "requireContinuationConfirmation",
          "requireContinuationBenchmarkPrior",
          "pinnedModel",
          "lowComplexityWorkerStartTierOverride");

  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static PlanGateContinuation fromJson(JsonNode json) {
    if (json == null
        || !json.isObject()
        || json.size() != REQUIRED_PROPERTIES.size()
        || !REQUIRED_PROPERTIES.stream().allMatch(json::has)) {
      throw new IllegalArgumentException(
          "Plan-gate continuation must contain exactly the versioned properties");
    }
    JsonNode schema = json.get("schemaVersion");
    JsonNode local = json.get("localRun");
    JsonNode confirmation = json.get("requireContinuationConfirmation");
    JsonNode benchmark = json.get("requireContinuationBenchmarkPrior");
    if (!schema.isIntegralNumber()
        || !schema.canConvertToInt()
        || !local.isBoolean()
        || !confirmation.isBoolean()
        || !benchmark.isBoolean()) {
      throw new IllegalArgumentException(
          "Plan-gate continuation has invalid schema or required boolean properties");
    }
    return new PlanGateContinuation(
        schema.intValue(),
        local.booleanValue(),
        confirmation.booleanValue(),
        benchmark.booleanValue(),
        nullableText(json.get("pinnedModel"), "pinnedModel"),
        nullableText(
            json.get("lowComplexityWorkerStartTierOverride"),
            "lowComplexityWorkerStartTierOverride"));
  }

  public static PlanGateContinuation parse(String json) {
    try {
      PlanGateContinuation decoded = DECODER.readValue(json, PlanGateContinuation.class);
      if (decoded == null) {
        throw new IllegalArgumentException("Plan-gate continuation must be a JSON object");
      }
      return decoded;
    } catch (Exception invalid) {
      if (invalid instanceof IllegalArgumentException argument) throw argument;
      throw new IllegalArgumentException("Invalid durable plan-gate continuation", invalid);
    }
  }

  private static String nullableText(JsonNode value, String property) {
    if (value.isNull()) return null;
    if (value.isTextual()) return value.textValue();
    throw new IllegalArgumentException(
        "Plan-gate continuation property " + property + " must be a string or null");
  }
}
