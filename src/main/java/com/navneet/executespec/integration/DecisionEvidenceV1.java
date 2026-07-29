package com.navneet.executespec.integration;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Version-one, secret-free plan-decision evidence.
 *
 * <p>The JSON writer below is intentionally explicit. Reflection-based record serialization makes
 * field order an incidental mapper setting; an admission receipt needs exact bytes that remain
 * stable across nodes and rolling deployments. Every field is emitted in the declared order and
 * nullable fields are emitted as JSON {@code null}.
 */
public record DecisionEvidenceV1(
    int schemaVersion,
    String policyContract,
    String decision,
    String decidedAt,
    String runId,
    String gateKey,
    String resolutionSource,
    String resolutionReason,
    String actorType,
    String actorUserId,
    String globalRole,
    String orgRole,
    String effectiveRole,
    String authorizationBasis,
    String authorizationOrgId,
    String reviewedPlanFingerprint,
    String suppliedPlanFingerprint,
    Boolean planFingerprintMatched,
    String decisionRequestFingerprint,
    Integer continuationSchemaVersion,
    String continuationSha256,
    String bindingMode,
    String bindingStatus,
    String requirementContractId,
    Integer bindingSchemaVersion,
    String bindingCompilerFingerprint,
    String bindingSourceSha256,
    String bindingExactPlanFingerprint,
    String bindingArtifactDigest,
    String bindingBoundDigest,
    String bindingReason,
    String requirementCompleteness,
    Integer requirementCount,
    Integer acceptanceCriterionCount,
    Integer taskBindingCount,
    Integer issueCount,
    Integer blockingIssueCount,
    String executionMode,
    String estimateHighUsd,
    String hardCapUsd,
    Integer expectedCredits,
    Integer creditsConfirmed,
    Boolean creditsMatched,
    String billingOwnerUserId,
    String billingOrgId,
    String provider,
    String keySourceMode,
    String credentialOrigin,
    String credentialAuthorizationSource,
    String credentialAuthorizationOrgId,
    Boolean ownKeyExempt,
    String managedAiOutcome,
    String managedAiMode,
    Long managedAiGrantCredits,
    Long managedAiCreditsRemaining,
    Long managedAiEstimatedCredits,
    String managedAiHardRawUsd,
    String managedAiRemainingRawUsd,
    String governanceOrgOutcome,
    String governanceOrgThresholdUsd,
    String governanceOrgEstimateUsd,
    String governanceOrgReason,
    String governanceProjectOutcome,
    String governanceProjectCapUsd,
    String governanceProjectMonthToDateUsd,
    String governanceProjectProjectedUsd,
    String governanceProjectReason,
    String contractGateReportDigest,
    Boolean contractRequiresTypedAck,
    String contractAcknowledgedAt,
    String contractAcknowledgedByUserId,
    String contractAcknowledgementText,
    String contractAcknowledgementOutcome) {

  public static final int SCHEMA_VERSION = 1;
  public static final String POLICY_CONTRACT = "PLAN_GATE_DECISION_V1";
  private static final ObjectMapper READER = new ObjectMapper();
  private static final Set<String> CANONICAL_FIELDS =
      Set.of(
          "schemaVersion",
          "policyContract",
          "decision",
          "decidedAt",
          "runId",
          "gateKey",
          "resolutionSource",
          "resolutionReason",
          "actorType",
          "actorUserId",
          "globalRole",
          "orgRole",
          "effectiveRole",
          "authorizationBasis",
          "authorizationOrgId",
          "reviewedPlanFingerprint",
          "suppliedPlanFingerprint",
          "planFingerprintMatched",
          "decisionRequestFingerprint",
          "continuationSchemaVersion",
          "continuationSha256",
          "bindingMode",
          "bindingStatus",
          "requirementContractId",
          "bindingSchemaVersion",
          "bindingCompilerFingerprint",
          "bindingSourceSha256",
          "bindingExactPlanFingerprint",
          "bindingArtifactDigest",
          "bindingBoundDigest",
          "bindingReason",
          "requirementCompleteness",
          "requirementCount",
          "acceptanceCriterionCount",
          "taskBindingCount",
          "issueCount",
          "blockingIssueCount",
          "executionMode",
          "estimateHighUsd",
          "hardCapUsd",
          "expectedCredits",
          "creditsConfirmed",
          "creditsMatched",
          "billingOwnerUserId",
          "billingOrgId",
          "provider",
          "keySourceMode",
          "credentialOrigin",
          "credentialAuthorizationSource",
          "credentialAuthorizationOrgId",
          "ownKeyExempt",
          "managedAiOutcome",
          "managedAiMode",
          "managedAiGrantCredits",
          "managedAiCreditsRemaining",
          "managedAiEstimatedCredits",
          "managedAiHardRawUsd",
          "managedAiRemainingRawUsd",
          "governanceOrgOutcome",
          "governanceOrgThresholdUsd",
          "governanceOrgEstimateUsd",
          "governanceOrgReason",
          "governanceProjectOutcome",
          "governanceProjectCapUsd",
          "governanceProjectMonthToDateUsd",
          "governanceProjectProjectedUsd",
          "governanceProjectReason",
          "contractGateReportDigest",
          "contractRequiresTypedAck",
          "contractAcknowledgedAt",
          "contractAcknowledgedByUserId",
          "contractAcknowledgementText",
          "contractAcknowledgementOutcome");

  public String canonicalJson() {
    try {
      StringWriter out = new StringWriter(2048);
      JsonGenerator json = new JsonFactory().createGenerator(out);
      json.writeStartObject();
      json.writeNumberField("schemaVersion", schemaVersion);
      nullable(json, "policyContract", policyContract);
      nullable(json, "decision", decision);
      nullable(json, "decidedAt", decidedAt);
      nullable(json, "runId", runId);
      nullable(json, "gateKey", gateKey);
      nullable(json, "resolutionSource", resolutionSource);
      nullable(json, "resolutionReason", resolutionReason);
      nullable(json, "actorType", actorType);
      nullable(json, "actorUserId", actorUserId);
      nullable(json, "globalRole", globalRole);
      nullable(json, "orgRole", orgRole);
      nullable(json, "effectiveRole", effectiveRole);
      nullable(json, "authorizationBasis", authorizationBasis);
      nullable(json, "authorizationOrgId", authorizationOrgId);
      nullable(json, "reviewedPlanFingerprint", reviewedPlanFingerprint);
      nullable(json, "suppliedPlanFingerprint", suppliedPlanFingerprint);
      nullable(json, "planFingerprintMatched", planFingerprintMatched);
      nullable(json, "decisionRequestFingerprint", decisionRequestFingerprint);
      nullable(json, "continuationSchemaVersion", continuationSchemaVersion);
      nullable(json, "continuationSha256", continuationSha256);
      nullable(json, "bindingMode", bindingMode);
      nullable(json, "bindingStatus", bindingStatus);
      nullable(json, "requirementContractId", requirementContractId);
      nullable(json, "bindingSchemaVersion", bindingSchemaVersion);
      nullable(json, "bindingCompilerFingerprint", bindingCompilerFingerprint);
      nullable(json, "bindingSourceSha256", bindingSourceSha256);
      nullable(json, "bindingExactPlanFingerprint", bindingExactPlanFingerprint);
      nullable(json, "bindingArtifactDigest", bindingArtifactDigest);
      nullable(json, "bindingBoundDigest", bindingBoundDigest);
      nullable(json, "bindingReason", bindingReason);
      nullable(json, "requirementCompleteness", requirementCompleteness);
      nullable(json, "requirementCount", requirementCount);
      nullable(json, "acceptanceCriterionCount", acceptanceCriterionCount);
      nullable(json, "taskBindingCount", taskBindingCount);
      nullable(json, "issueCount", issueCount);
      nullable(json, "blockingIssueCount", blockingIssueCount);
      nullable(json, "executionMode", executionMode);
      nullable(json, "estimateHighUsd", estimateHighUsd);
      nullable(json, "hardCapUsd", hardCapUsd);
      nullable(json, "expectedCredits", expectedCredits);
      nullable(json, "creditsConfirmed", creditsConfirmed);
      nullable(json, "creditsMatched", creditsMatched);
      nullable(json, "billingOwnerUserId", billingOwnerUserId);
      nullable(json, "billingOrgId", billingOrgId);
      nullable(json, "provider", provider);
      nullable(json, "keySourceMode", keySourceMode);
      nullable(json, "credentialOrigin", credentialOrigin);
      nullable(json, "credentialAuthorizationSource", credentialAuthorizationSource);
      nullable(json, "credentialAuthorizationOrgId", credentialAuthorizationOrgId);
      nullable(json, "ownKeyExempt", ownKeyExempt);
      nullable(json, "managedAiOutcome", managedAiOutcome);
      nullable(json, "managedAiMode", managedAiMode);
      nullable(json, "managedAiGrantCredits", managedAiGrantCredits);
      nullable(json, "managedAiCreditsRemaining", managedAiCreditsRemaining);
      nullable(json, "managedAiEstimatedCredits", managedAiEstimatedCredits);
      nullable(json, "managedAiHardRawUsd", managedAiHardRawUsd);
      nullable(json, "managedAiRemainingRawUsd", managedAiRemainingRawUsd);
      nullable(json, "governanceOrgOutcome", governanceOrgOutcome);
      nullable(json, "governanceOrgThresholdUsd", governanceOrgThresholdUsd);
      nullable(json, "governanceOrgEstimateUsd", governanceOrgEstimateUsd);
      nullable(json, "governanceOrgReason", governanceOrgReason);
      nullable(json, "governanceProjectOutcome", governanceProjectOutcome);
      nullable(json, "governanceProjectCapUsd", governanceProjectCapUsd);
      nullable(json, "governanceProjectMonthToDateUsd", governanceProjectMonthToDateUsd);
      nullable(json, "governanceProjectProjectedUsd", governanceProjectProjectedUsd);
      nullable(json, "governanceProjectReason", governanceProjectReason);
      nullable(json, "contractGateReportDigest", contractGateReportDigest);
      nullable(json, "contractRequiresTypedAck", contractRequiresTypedAck);
      nullable(json, "contractAcknowledgedAt", contractAcknowledgedAt);
      nullable(json, "contractAcknowledgedByUserId", contractAcknowledgedByUserId);
      nullable(json, "contractAcknowledgementText", contractAcknowledgementText);
      nullable(json, "contractAcknowledgementOutcome", contractAcknowledgementOutcome);
      json.writeEndObject();
      json.close();
      return out.toString();
    } catch (Exception e) {
      throw new IllegalStateException("Cannot serialize plan-decision evidence", e);
    }
  }

  public String sha256() {
    return sha256(canonicalJson());
  }

  public static DecisionEvidenceV1 parse(String value) {
    try {
      JsonNode n = READER.readTree(value);
      if (n == null || !n.isObject()) {
        throw new IllegalArgumentException("Plan-decision evidence must be a JSON object");
      }
      Set<String> actualFields = new HashSet<>();
      n.fieldNames().forEachRemaining(actualFields::add);
      if (!actualFields.equals(CANONICAL_FIELDS)) {
        throw new IllegalArgumentException(
            "Plan-decision evidence must contain the exact V1 field set");
      }
      DecisionEvidenceV1 parsed =
          new DecisionEvidenceV1(
              integer(n, "schemaVersion", 0),
              text(n, "policyContract"),
              text(n, "decision"),
              text(n, "decidedAt"),
              text(n, "runId"),
              text(n, "gateKey"),
              text(n, "resolutionSource"),
              text(n, "resolutionReason"),
              text(n, "actorType"),
              text(n, "actorUserId"),
              text(n, "globalRole"),
              text(n, "orgRole"),
              text(n, "effectiveRole"),
              text(n, "authorizationBasis"),
              text(n, "authorizationOrgId"),
              text(n, "reviewedPlanFingerprint"),
              text(n, "suppliedPlanFingerprint"),
              bool(n, "planFingerprintMatched"),
              text(n, "decisionRequestFingerprint"),
              integer(n, "continuationSchemaVersion"),
              text(n, "continuationSha256"),
              text(n, "bindingMode"),
              text(n, "bindingStatus"),
              text(n, "requirementContractId"),
              integer(n, "bindingSchemaVersion"),
              text(n, "bindingCompilerFingerprint"),
              text(n, "bindingSourceSha256"),
              text(n, "bindingExactPlanFingerprint"),
              text(n, "bindingArtifactDigest"),
              text(n, "bindingBoundDigest"),
              text(n, "bindingReason"),
              text(n, "requirementCompleteness"),
              integer(n, "requirementCount"),
              integer(n, "acceptanceCriterionCount"),
              integer(n, "taskBindingCount"),
              integer(n, "issueCount"),
              integer(n, "blockingIssueCount"),
              text(n, "executionMode"),
              text(n, "estimateHighUsd"),
              text(n, "hardCapUsd"),
              integer(n, "expectedCredits"),
              integer(n, "creditsConfirmed"),
              bool(n, "creditsMatched"),
              text(n, "billingOwnerUserId"),
              text(n, "billingOrgId"),
              text(n, "provider"),
              text(n, "keySourceMode"),
              text(n, "credentialOrigin"),
              text(n, "credentialAuthorizationSource"),
              text(n, "credentialAuthorizationOrgId"),
              bool(n, "ownKeyExempt"),
              text(n, "managedAiOutcome"),
              text(n, "managedAiMode"),
              longValue(n, "managedAiGrantCredits"),
              longValue(n, "managedAiCreditsRemaining"),
              longValue(n, "managedAiEstimatedCredits"),
              text(n, "managedAiHardRawUsd"),
              text(n, "managedAiRemainingRawUsd"),
              text(n, "governanceOrgOutcome"),
              text(n, "governanceOrgThresholdUsd"),
              text(n, "governanceOrgEstimateUsd"),
              text(n, "governanceOrgReason"),
              text(n, "governanceProjectOutcome"),
              text(n, "governanceProjectCapUsd"),
              text(n, "governanceProjectMonthToDateUsd"),
              text(n, "governanceProjectProjectedUsd"),
              text(n, "governanceProjectReason"),
              text(n, "contractGateReportDigest"),
              bool(n, "contractRequiresTypedAck"),
              text(n, "contractAcknowledgedAt"),
              text(n, "contractAcknowledgedByUserId"),
              text(n, "contractAcknowledgementText"),
              text(n, "contractAcknowledgementOutcome"));
      if (parsed.schemaVersion() != SCHEMA_VERSION
          || !POLICY_CONTRACT.equals(parsed.policyContract())
          || parsed.decision() == null
          || parsed.decidedAt() == null
          || parsed.runId() == null
          || parsed.gateKey() == null
          || parsed.resolutionSource() == null
          || parsed.actorType() == null
          || parsed.authorizationBasis() == null
          || parsed.reviewedPlanFingerprint() == null
          || parsed.decisionRequestFingerprint() == null
          || parsed.continuationSchemaVersion() == null
          || parsed.continuationSchemaVersion() <= 0
          || parsed.continuationSha256() == null
          || parsed.executionMode() == null) {
        throw new IllegalArgumentException("Plan-decision evidence is missing required identity");
      }
      java.time.Instant.parse(parsed.decidedAt());
      PlanGateDecisionService.DecisionKind decisionKind =
          PlanGateDecisionService.DecisionKind.valueOf(parsed.decision());
      PlanGateDecisionService.ResolutionSource resolutionSource =
          PlanGateDecisionService.ResolutionSource.valueOf(parsed.resolutionSource());
      if (!("HUMAN".equals(parsed.actorType()) || "SYSTEM".equals(parsed.actorType()))
          || !java.util.Set.of("RUN_OWNER", "ORG_ADMIN", "GLOBAL_SUPER_ADMIN", "SYSTEM_INTERNAL")
              .contains(parsed.authorizationBasis())
          || !isSha256(parsed.reviewedPlanFingerprint())
          || !isSha256(parsed.decisionRequestFingerprint())
          || !isSha256(parsed.continuationSha256())) {
        throw new IllegalArgumentException("Plan-decision evidence has invalid typed identity");
      }
      boolean cancellationSource =
          resolutionSource == PlanGateDecisionService.ResolutionSource.RUN_CANCEL_API
              || resolutionSource == PlanGateDecisionService.ResolutionSource.SYSTEM_CANCEL
              || (resolutionSource == PlanGateDecisionService.ResolutionSource.PROJECT_CHAT_ACTION
                  && decisionKind == PlanGateDecisionService.DecisionKind.CANCEL);
      if ((decisionKind == PlanGateDecisionService.DecisionKind.CANCEL) != cancellationSource
          || (resolutionSource == PlanGateDecisionService.ResolutionSource.SYSTEM_CANCEL)
              != "SYSTEM".equals(parsed.actorType())) {
        throw new IllegalArgumentException(
            "Plan-decision kind, source, and actor type are inconsistent");
      }
      if ("HUMAN".equals(parsed.actorType())
          && (parsed.actorUserId() == null || parsed.effectiveRole() == null)) {
        throw new IllegalArgumentException("Human decision evidence is missing actor authority");
      }
      if ("ORG_ADMIN".equals(parsed.authorizationBasis()) && parsed.authorizationOrgId() == null) {
        throw new IllegalArgumentException("Org-admin decision evidence is missing its org scope");
      }
      if ("APPROVE".equals(parsed.decision())) {
        boolean suppliedPlan = parsed.suppliedPlanFingerprint() != null;
        if (("ENFORCED".equals(parsed.bindingMode()) && !suppliedPlan)
            || (suppliedPlan
                && (!isSha256(parsed.suppliedPlanFingerprint())
                    || !Boolean.TRUE.equals(parsed.planFingerprintMatched())))
            || (!suppliedPlan && parsed.planFingerprintMatched() != null)
            || parsed.billingOwnerUserId() == null
            || parsed.expectedCredits() == null
            || (parsed.creditsConfirmed() == null && parsed.creditsMatched() != null)
            || (parsed.creditsConfirmed() != null
                && (!parsed.creditsConfirmed().equals(parsed.expectedCredits())
                    || !Boolean.TRUE.equals(parsed.creditsMatched())))
            || parsed.provider() == null
            || parsed.keySourceMode() == null
            || parsed.credentialOrigin() == null
            || parsed.credentialAuthorizationSource() == null
            || parsed.ownKeyExempt() == null
            || !"ALLOWED".equals(parsed.managedAiOutcome())
            || parsed.managedAiMode() == null
            || !"ALLOWED".equals(parsed.governanceOrgOutcome())
            || !"ALLOWED".equals(parsed.governanceProjectOutcome())
            || !("NOT_REQUIRED".equals(parsed.contractAcknowledgementOutcome())
                || "ACKNOWLEDGED".equals(parsed.contractAcknowledgementOutcome()))) {
          throw new IllegalArgumentException("Approval evidence is missing admitted policy proof");
        }
      } else if (parsed.resolutionReason() == null
          || !"NOT_EVALUATED".equals(parsed.managedAiOutcome())
          || !"NOT_EVALUATED".equals(parsed.governanceOrgOutcome())
          || !"NOT_EVALUATED".equals(parsed.governanceProjectOutcome())
          || !"NOT_EVALUATED".equals(parsed.contractAcknowledgementOutcome())) {
        throw new IllegalArgumentException("Terminal non-approval evidence is incomplete");
      }
      validateCredits(parsed);
      validateMoneyFields(parsed);
      validateContractAcknowledgement(parsed);
      // This proves field order, explicit nulls, scalar JSON types, and absence of unknown or
      // duplicate fields. A receipt is accepted only when it is already in canonical byte form.
      if (!parsed.canonicalJson().equals(value)) {
        throw new IllegalArgumentException("Plan-decision evidence is not canonical");
      }
      return parsed;
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid plan-decision evidence", e);
    }
  }

  public static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static boolean isSha256(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }

  private static void validateMoneyFields(DecisionEvidenceV1 value) {
    java.util.Arrays.asList(
            value.estimateHighUsd(),
            value.hardCapUsd(),
            value.managedAiHardRawUsd(),
            value.managedAiRemainingRawUsd(),
            value.governanceOrgThresholdUsd(),
            value.governanceOrgEstimateUsd(),
            value.governanceProjectCapUsd(),
            value.governanceProjectMonthToDateUsd(),
            value.governanceProjectProjectedUsd())
        .stream()
        .filter(java.util.Objects::nonNull)
        .forEach(
            money -> {
              if (!money.matches("[0-9]+\\.[0-9]{6}")) {
                throw new IllegalArgumentException(
                    "Plan-decision money evidence must use non-negative six-decimal strings");
              }
            });
  }

  private static void validateCredits(DecisionEvidenceV1 value) {
    if ((value.expectedCredits() != null && value.expectedCredits() <= 0)
        || (value.creditsConfirmed() != null && value.creditsConfirmed() <= 0)) {
      throw new IllegalArgumentException("Plan-decision credit evidence must be positive");
    }
  }

  private static void validateContractAcknowledgement(DecisionEvidenceV1 value) {
    boolean approval = "APPROVE".equals(value.decision());
    if (!approval) {
      if (!"NOT_EVALUATED".equals(value.contractAcknowledgementOutcome())
          || (value.contractGateReportDigest() == null && value.contractRequiresTypedAck() != null)
          || (value.contractGateReportDigest() != null
              && (!isSha256(value.contractGateReportDigest())
                  || value.contractRequiresTypedAck() == null))) {
        throw new IllegalArgumentException(
            "Terminal non-approval contract evidence is inconsistent");
      }
      validateStoredAcknowledgementSnapshot(value);
      return;
    }
    if (value.contractGateReportDigest() == null) {
      if (value.contractRequiresTypedAck() != null
          || value.contractAcknowledgedAt() != null
          || value.contractAcknowledgedByUserId() != null
          || value.contractAcknowledgementText() != null
          || !"NOT_REQUIRED".equals(value.contractAcknowledgementOutcome())) {
        throw new IllegalArgumentException("Missing contract report cannot carry acknowledgement");
      }
      return;
    }
    if (!isSha256(value.contractGateReportDigest()) || value.contractRequiresTypedAck() == null) {
      throw new IllegalArgumentException("Contract report evidence is incomplete");
    }
    if (!value.contractRequiresTypedAck()) {
      if (!"NOT_REQUIRED".equals(value.contractAcknowledgementOutcome())
          || value.contractAcknowledgedAt() != null
          || value.contractAcknowledgedByUserId() != null
          || value.contractAcknowledgementText() != null) {
        throw new IllegalArgumentException(
            "Non-breaking contract report cannot carry acknowledgement");
      }
      return;
    }
    if (!"ACKNOWLEDGED".equals(value.contractAcknowledgementOutcome())
        || value.contractAcknowledgedAt() == null
        || value.actorUserId() == null
        || !java.util.Objects.equals(value.actorUserId(), value.contractAcknowledgedByUserId())
        || !"BREAKING".equalsIgnoreCase(value.contractAcknowledgementText().trim())) {
      throw new IllegalArgumentException("Typed contract acknowledgement evidence is incomplete");
    }
    java.time.Instant.parse(value.contractAcknowledgedAt());
  }

  /**
   * A pre-V348 report may already carry the acknowledgement that the old approval path wrote.
   * REJECT/CANCEL snapshots that row without treating it as admission. Preserve its bytes exactly,
   * but require the historical acknowledgement tuple to be complete and semantically valid.
   */
  private static void validateStoredAcknowledgementSnapshot(DecisionEvidenceV1 value) {
    boolean hasAt = value.contractAcknowledgedAt() != null;
    boolean hasActor = value.contractAcknowledgedByUserId() != null;
    boolean hasText = value.contractAcknowledgementText() != null;
    if (!(hasAt || hasActor || hasText)) {
      return;
    }
    if (!(hasAt && hasActor && hasText)
        || !"BREAKING".equalsIgnoreCase(value.contractAcknowledgementText().trim())) {
      throw new IllegalArgumentException("Stored contract acknowledgement snapshot is incomplete");
    }
    java.time.Instant.parse(value.contractAcknowledgedAt());
  }

  private static void nullable(JsonGenerator json, String name, Object value) throws Exception {
    if (value == null) {
      json.writeNullField(name);
    } else if (value instanceof String s) {
      json.writeStringField(name, s);
    } else if (value instanceof Integer i) {
      json.writeNumberField(name, i);
    } else if (value instanceof Long l) {
      json.writeNumberField(name, l);
    } else if (value instanceof Boolean b) {
      json.writeBooleanField(name, b);
    } else {
      throw new IllegalArgumentException("Unsupported evidence value: " + value.getClass());
    }
  }

  private static String text(JsonNode n, String name) {
    JsonNode value = n.get(name);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static Integer integer(JsonNode n, String name) {
    JsonNode value = n.get(name);
    return value == null || value.isNull() ? null : value.intValue();
  }

  private static int integer(JsonNode n, String name, int fallback) {
    Integer value = integer(n, name);
    return value == null ? fallback : value;
  }

  private static Long longValue(JsonNode n, String name) {
    JsonNode value = n.get(name);
    return value == null || value.isNull() ? null : value.longValue();
  }

  private static Boolean bool(JsonNode n, String name) {
    JsonNode value = n.get(name);
    return value == null || value.isNull() ? null : value.booleanValue();
  }
}
