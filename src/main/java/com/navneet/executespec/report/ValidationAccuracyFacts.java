package com.navneet.executespec.report;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/** Parses citation-aware advisory accuracy facts without growing the main ledger builder. */
@Slf4j
record ValidationAccuracyFacts(
    Integer score,
    String grade,
    Integer endpointCoverage,
    String testCoverageLevel,
    int incompleteRequirements,
    int unknownRequirements,
    List<String> specEvidenceLocations,
    List<String> endpointEvidenceLocations,
    List<String> testEvidenceLocations,
    String specEvidenceStatus,
    String endpointEvidenceStatus,
    String testEvidenceStatus) {

  static ValidationAccuracyFacts parse(ObjectMapper objectMapper, String accuracyJson) {
    if (accuracyJson == null || accuracyJson.isBlank()) return unavailable();
    try {
      JsonNode root = objectMapper.readTree(accuracyJson);
      Integer score =
          root.hasNonNull("specAdherenceScore") ? root.get("specAdherenceScore").asInt() : null;
      String grade =
          root.hasNonNull("overallAccuracyGrade")
              ? root.get("overallAccuracyGrade").asText()
              : null;
      JsonNode endpoint = root.path("endpointCoverage");
      Integer endpointCoverage =
          endpoint.hasNonNull("coveragePercent") ? endpoint.get("coveragePercent").asInt() : null;
      String testCoverage =
          root.hasNonNull("testCoverageLevel") ? root.get("testCoverageLevel").asText() : null;
      JsonNode unknown = root.path("unknownRequirements");
      JsonNode missing = root.path("missingRequirements");
      JsonNode partial = root.path("partialRequirements");
      return new ValidationAccuracyFacts(
          score,
          grade,
          endpointCoverage,
          testCoverage,
          (missing.isArray() ? missing.size() : 0) + (partial.isArray() ? partial.size() : 0),
          unknown.isArray() ? unknown.size() : 0,
          locations(root, "specEvidenceLocations"),
          locations(root, "endpointEvidenceLocations"),
          locations(root, "testEvidenceLocations"),
          status(root, "specEvidenceStatus"),
          status(root, "endpointEvidenceStatus"),
          status(root, "testEvidenceStatus"));
    } catch (Exception malformed) {
      log.warn(
          "Failed to parse accuracy facts for verification ledger: {}", malformed.getMessage());
      return unavailable();
    }
  }

  boolean specCited() {
    return cited(specEvidenceStatus, specEvidenceLocations);
  }

  boolean endpointCited() {
    return cited(endpointEvidenceStatus, endpointEvidenceLocations);
  }

  boolean testCited() {
    return cited(testEvidenceStatus, testEvidenceLocations);
  }

  String specArtifact(String persistedField) {
    return artifact(persistedField, specCited(), specEvidenceLocations);
  }

  String endpointArtifact(String persistedField) {
    return artifact(persistedField, endpointCited(), endpointEvidenceLocations);
  }

  String testArtifact(String persistedField) {
    return artifact(persistedField, testCited(), testEvidenceLocations);
  }

  private static boolean cited(String status, List<String> locations) {
    return "CITED".equalsIgnoreCase(status) && !locations.isEmpty();
  }

  private static String artifact(String persistedField, boolean cited, List<String> locations) {
    return cited
        ? persistedField + " · sources=" + String.join(",", locations)
        : "UNAVAILABLE: no deterministically validated source citation for " + persistedField;
  }

  private static List<String> locations(JsonNode root, String field) {
    List<String> locations = new ArrayList<>();
    JsonNode persisted = root.path(field);
    if (persisted.isArray()) {
      persisted.forEach(
          location -> {
            if (location.isTextual() && !location.asText().isBlank()) {
              locations.add(location.asText());
            }
          });
    }
    return List.copyOf(locations);
  }

  private static String status(JsonNode root, String field) {
    return root.hasNonNull(field) ? root.get(field).asText() : "UNAVAILABLE";
  }

  private static ValidationAccuracyFacts unavailable() {
    return new ValidationAccuracyFacts(
        null,
        null,
        null,
        null,
        0,
        0,
        List.of(),
        List.of(),
        List.of(),
        "UNAVAILABLE",
        "UNAVAILABLE",
        "UNAVAILABLE");
  }
}
