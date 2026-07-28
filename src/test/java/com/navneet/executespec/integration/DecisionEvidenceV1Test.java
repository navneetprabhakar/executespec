package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class DecisionEvidenceV1Test {

  @Test
  void canonicalBytesRoundTripWithExplicitNullsAndFixedMoneyScale() {
    DecisionEvidenceV1 evidence = evidence();

    String json = evidence.canonicalJson();

    assertThat(DecisionEvidenceV1.parse(json)).isEqualTo(evidence);
    assertThat(json)
        .startsWith(
            "{\"schemaVersion\":1,\"policyContract\":\"PLAN_GATE_DECISION_V1\","
                + "\"decision\":\"APPROVE\"")
        .contains("\"orgRole\":null")
        .contains(
            "\"executionMode\":\"REALTIME\",\"estimateHighUsd\":\"4.500000\","
                + "\"hardCapUsd\":null,\"expectedCredits\":3")
        .endsWith("\"contractAcknowledgementOutcome\":\"NOT_REQUIRED\"}");
    assertThat(evidence.sha256()).isEqualTo(DecisionEvidenceV1.sha256(json));
  }

  @Test
  void parserRejectsUnknownFieldsWrongTypesAndNonCanonicalOrder() {
    String canonical = evidence().canonicalJson();

    assertThatThrownBy(
            () ->
                DecisionEvidenceV1.parse(
                    canonical.substring(0, canonical.length() - 1) + ",\"unknown\":true}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                DecisionEvidenceV1.parse(
                    canonical.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\"")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DecisionEvidenceV1.parse("[]"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                DecisionEvidenceV1.parse(
                    canonical.replace(
                        "\"resolutionSource\":\"PLAN_APPROVAL_API\"",
                        "\"resolutionSource\":\"UNTRUSTED_SURFACE\"")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                DecisionEvidenceV1.parse(
                    canonical.replace(
                        "\"managedAiOutcome\":\"ALLOWED\"",
                        "\"managedAiOutcome\":\"NOT_EVALUATED\"")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                DecisionEvidenceV1.parse(
                    canonical.replace(
                        "\"estimateHighUsd\":\"4.500000\"", "\"estimateHighUsd\":\"4.5\"")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                DecisionEvidenceV1.parse(
                    canonical.replace("\"creditsMatched\":true", "\"creditsMatched\":false")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                DecisionEvidenceV1.parse(
                    canonical.replace("\"decision\":\"APPROVE\"", "\"decision\":\"CANCEL\"")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void parserRequiresEveryExplicitNullFieldAndRejectsAdditionalTopLevelFields() {
    String canonical = evidence().canonicalJson();

    assertThatThrownBy(() -> DecisionEvidenceV1.parse(canonical.replace("\"orgRole\":null,", "")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasRootCauseMessage("Plan-decision evidence must contain the exact V1 field set");
    assertThatThrownBy(
            () ->
                DecisionEvidenceV1.parse(
                    canonical.substring(0, canonical.length() - 1) + ",\"additional\":null}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasRootCauseMessage("Plan-decision evidence must contain the exact V1 field set");
  }

  @Test
  void parserRejectsNegativeOrNonSixDecimalMoneyEvidence() {
    String canonical =
        replaceField(evidence().canonicalJson(), "hardCapUsd", "null", "\"6.750000\"");
    Map<String, String> monetaryFields =
        Map.of(
            "estimateHighUsd", "\"4.500000\"",
            "hardCapUsd", "\"6.750000\"",
            "managedAiHardRawUsd", "\"25.000000\"",
            "managedAiRemainingRawUsd", "\"23.500000\"",
            "governanceOrgThresholdUsd", "null",
            "governanceOrgEstimateUsd", "\"4.500000\"",
            "governanceProjectCapUsd", "null",
            "governanceProjectMonthToDateUsd", "\"0.000000\"",
            "governanceProjectProjectedUsd", "\"4.500000\"");

    monetaryFields.forEach(
        (field, original) -> {
          assertThatThrownBy(
                  () ->
                      DecisionEvidenceV1.parse(
                          replaceField(canonical, field, original, "\"-0.000001\"")))
              .as("negative %s", field)
              .isInstanceOf(IllegalArgumentException.class);
          assertThatThrownBy(
                  () ->
                      DecisionEvidenceV1.parse(replaceField(canonical, field, original, "\"1.0\"")))
              .as("non-six-decimal %s", field)
              .isInstanceOf(IllegalArgumentException.class);
        });
  }

  @Test
  void parserRequiresPositiveExpectedAndConfirmedCreditsWhenPresent() {
    String canonical = evidence().canonicalJson();

    assertThatThrownBy(
            () -> DecisionEvidenceV1.parse(replaceField(canonical, "expectedCredits", "3", "0")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> DecisionEvidenceV1.parse(replaceField(canonical, "expectedCredits", "3", "-1")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> DecisionEvidenceV1.parse(replaceField(canonical, "creditsConfirmed", "3", "0")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> DecisionEvidenceV1.parse(replaceField(canonical, "creditsConfirmed", "3", "-1")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void parserRequiresNotRequiredAndNoAcknowledgementWhenReportIsAbsent() {
    String canonical = evidence().canonicalJson();

    assertThatThrownBy(
            () ->
                DecisionEvidenceV1.parse(
                    replaceField(
                        canonical,
                        "contractAcknowledgementOutcome",
                        "\"NOT_REQUIRED\"",
                        "\"ACKNOWLEDGED\"")))
        .isInstanceOf(IllegalArgumentException.class);
    Map.of(
            "contractRequiresTypedAck", "true",
            "contractAcknowledgedAt", "\"2026-07-28T00:00:00Z\"",
            "contractAcknowledgedByUserId", "\"user-1\"",
            "contractAcknowledgementText", "\"BREAKING\"")
        .forEach(
            (field, replacement) ->
                assertThatThrownBy(
                        () ->
                            DecisionEvidenceV1.parse(
                                replaceField(canonical, field, "null", replacement)))
                    .as("absent report field %s", field)
                    .isInstanceOf(IllegalArgumentException.class));
  }

  @Test
  void parserRequiresNoAcknowledgementForNonBreakingContractReport() {
    String canonical = withContractReport(evidence().canonicalJson(), false, false);

    assertThat(DecisionEvidenceV1.parse(canonical).contractAcknowledgementOutcome())
        .isEqualTo("NOT_REQUIRED");
    Map.of(
            "contractAcknowledgedAt", "\"2026-07-28T00:00:00Z\"",
            "contractAcknowledgedByUserId", "\"user-1\"",
            "contractAcknowledgementText", "\"BREAKING\"")
        .forEach(
            (field, replacement) ->
                assertThatThrownBy(
                        () ->
                            DecisionEvidenceV1.parse(
                                replaceField(canonical, field, "null", replacement)))
                    .as("non-breaking report field %s", field)
                    .isInstanceOf(IllegalArgumentException.class));
    assertThatThrownBy(
            () ->
                DecisionEvidenceV1.parse(
                    replaceField(
                        canonical,
                        "contractAcknowledgementOutcome",
                        "\"NOT_REQUIRED\"",
                        "\"ACKNOWLEDGED\"")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void parserRequiresCompleteBreakingContractAcknowledgementAndAcceptsLegacyCase() {
    String canonical = withContractReport(evidence().canonicalJson(), true, true);

    assertThat(DecisionEvidenceV1.parse(canonical).contractAcknowledgementOutcome())
        .isEqualTo("ACKNOWLEDGED");
    assertThatThrownBy(
            () ->
                DecisionEvidenceV1.parse(
                    replaceField(
                        canonical,
                        "contractAcknowledgedByUserId",
                        "\"user-1\"",
                        "\"another-user\"")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                DecisionEvidenceV1.parse(
                    replaceField(
                        canonical,
                        "contractAcknowledgedAt",
                        "\"2026-07-28T00:00:00Z\"",
                        "\"not-an-instant\"")))
        .isInstanceOf(IllegalArgumentException.class);
    String legacyLowercase =
        replaceField(canonical, "contractAcknowledgementText", "\"BREAKING\"", "\"breaking\"");
    assertThat(DecisionEvidenceV1.parse(legacyLowercase).canonicalJson())
        .isEqualTo(legacyLowercase);
    assertThatThrownBy(
            () ->
                DecisionEvidenceV1.parse(
                    replaceField(
                        canonical,
                        "contractAcknowledgementOutcome",
                        "\"ACKNOWLEDGED\"",
                        "\"NOT_REQUIRED\"")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                DecisionEvidenceV1.parse(
                    replaceField(
                        canonical, "contractAcknowledgedAt", "\"2026-07-28T00:00:00Z\"", "null")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                DecisionEvidenceV1.parse(
                    replaceField(canonical, "contractAcknowledgedByUserId", "\"user-1\"", "null")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void parserPreservesLegacyPreDecisionAcknowledgementOnTerminalNonApproval() {
    String rejection = terminalNonApprovalWithLegacyAcknowledgement();

    DecisionEvidenceV1 parsed = DecisionEvidenceV1.parse(rejection);

    assertThat(parsed.decision()).isEqualTo("REJECT");
    assertThat(parsed.contractAcknowledgementOutcome()).isEqualTo("NOT_EVALUATED");
    assertThat(parsed.contractRequiresTypedAck()).isTrue();
    assertThat(parsed.contractAcknowledgementText()).isEqualTo(" breaking ");
    assertThat(parsed.canonicalJson()).isEqualTo(rejection);
    assertThatThrownBy(
            () ->
                DecisionEvidenceV1.parse(
                    replaceField(rejection, "contractAcknowledgedByUserId", "\"user-1\"", "null")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                DecisionEvidenceV1.parse(
                    replaceField(
                        rejection,
                        "contractAcknowledgementText",
                        "\" breaking \"",
                        "\"accepted\"")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static String withContractReport(
      String canonical, boolean requiresTypedAck, boolean acknowledged) {
    String result =
        replaceField(canonical, "contractGateReportDigest", "null", "\"" + "d".repeat(64) + "\"");
    result =
        replaceField(
            result, "contractRequiresTypedAck", "null", Boolean.toString(requiresTypedAck));
    if (!acknowledged) {
      return result;
    }
    result = replaceField(result, "contractAcknowledgedAt", "null", "\"2026-07-28T00:00:00Z\"");
    result = replaceField(result, "contractAcknowledgedByUserId", "null", "\"user-1\"");
    result = replaceField(result, "contractAcknowledgementText", "null", "\"BREAKING\"");
    return replaceField(
        result, "contractAcknowledgementOutcome", "\"NOT_REQUIRED\"", "\"ACKNOWLEDGED\"");
  }

  private static String terminalNonApprovalWithLegacyAcknowledgement() {
    String result = withContractReport(evidence().canonicalJson(), true, true);
    result = replaceField(result, "decision", "\"APPROVE\"", "\"REJECT\"");
    result = replaceField(result, "resolutionReason", "null", "\"user rejected\"");
    result = replaceField(result, "managedAiOutcome", "\"ALLOWED\"", "\"NOT_EVALUATED\"");
    result = replaceField(result, "governanceOrgOutcome", "\"ALLOWED\"", "\"NOT_EVALUATED\"");
    result = replaceField(result, "governanceProjectOutcome", "\"ALLOWED\"", "\"NOT_EVALUATED\"");
    result =
        replaceField(
            result, "contractAcknowledgementOutcome", "\"ACKNOWLEDGED\"", "\"NOT_EVALUATED\"");
    return replaceField(result, "contractAcknowledgementText", "\"BREAKING\"", "\" breaking \"");
  }

  private static String replaceField(
      String canonical, String field, String originalJson, String replacementJson) {
    String original = "\"" + field + "\":" + originalJson;
    String replacement = "\"" + field + "\":" + replacementJson;
    assertThat(canonical).contains(original);
    return canonical.replace(original, replacement);
  }

  private static DecisionEvidenceV1 evidence() {
    String sha = "a".repeat(64);
    return new DecisionEvidenceV1(
        1,
        DecisionEvidenceV1.POLICY_CONTRACT,
        "APPROVE",
        "2026-07-28T00:00:00Z",
        "run-1",
        "gate:plan:run-1",
        "PLAN_APPROVAL_API",
        null,
        "HUMAN",
        "user-1",
        "USER",
        null,
        "USER",
        "RUN_OWNER",
        null,
        sha,
        null,
        null,
        "b".repeat(64),
        1,
        "c".repeat(64),
        "LEGACY",
        "UNBOUND",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "HISTORICAL",
        null,
        null,
        null,
        null,
        null,
        "REALTIME",
        "4.500000",
        null,
        3,
        3,
        true,
        "owner-1",
        "org-1",
        "anthropic",
        "SYSTEM",
        "SYSTEM",
        "SYSTEM_PLATFORM_KEY",
        "org-1",
        false,
        "ALLOWED",
        "MANAGED",
        2000L,
        1900L,
        100L,
        "25.000000",
        "23.500000",
        "ALLOWED",
        null,
        "4.500000",
        "NO_THRESHOLD",
        "ALLOWED",
        null,
        "0.000000",
        "4.500000",
        "NO_CAP",
        null,
        null,
        null,
        null,
        null,
        "NOT_REQUIRED");
  }
}
