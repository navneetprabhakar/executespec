package com.navneet.executespec.agent.requirement;

import java.util.List;

/**
 * Frozen schema-v1 wire DTO retained independently from the rolling RequirementIR domain model.
 *
 * <p>Pending plan gates can outlive a deployment. Never add, remove, or rename a component here;
 * introduce a new wire DTO and approval adapter for a later schema/compiler pair instead.
 */
record RequirementIrV1WireDocument(
    int schemaVersion,
    String compilerFingerprint,
    String sourceSha256,
    List<RequirementNode> requirements,
    List<AcceptanceCriterion> acceptanceCriteria,
    List<TaskBinding> taskBindings,
    List<RequirementIssue> issues,
    Completeness completeness) {

  static final int SCHEMA_VERSION = 1;
  static final String COMPILER_FINGERPRINT =
      "8730dfa7ae62e27368afcd1a91192270950c82707b8845816b67d6c687a5371d";

  RequirementIrV1WireDocument {
    requirements = immutable(requirements);
    acceptanceCriteria = immutable(acceptanceCriteria);
    taskBindings = immutable(taskBindings);
    issues = immutable(issues);
  }

  private static <T> List<T> immutable(List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  record RequirementNode(
      String id,
      String text,
      RequirementKind kind,
      Origin origin,
      RequirementState state,
      SourceSpan sourceSpan,
      String inferenceRationale,
      String deferredReason,
      List<String> acceptanceIds,
      List<String> taskIds) {

    RequirementNode {
      acceptanceIds = immutable(acceptanceIds);
      taskIds = immutable(taskIds);
    }
  }

  record AcceptanceCriterion(
      String id,
      List<String> requirementIds,
      AcceptanceKind kind,
      EvidenceType evidenceType,
      String expected,
      SourceSpan sourceSpan) {

    AcceptanceCriterion {
      requirementIds = immutable(requirementIds);
    }
  }

  record TaskBinding(String taskId, List<String> requirementIds) {
    TaskBinding {
      requirementIds = immutable(requirementIds);
    }
  }

  record RequirementIssue(
      IssueCode code,
      boolean blocking,
      String message,
      List<String> requirementIds,
      List<SourceSpan> sourceSpans) {

    RequirementIssue {
      requirementIds = immutable(requirementIds);
      sourceSpans = immutable(sourceSpans);
    }
  }

  record SourceSpan(
      int startOffset, int endOffset, int startLine, int startColumn, int endLine, int endColumn) {}

  enum RequirementKind {
    FUNCTIONAL,
    NON_FUNCTIONAL,
    TEST,
    CONSTRAINT,
    CONTRACT,
    ERROR,
    SECURITY,
    UI
  }

  enum Origin {
    USER_STATED,
    INFERRED
  }

  enum RequirementState {
    EXPLICIT,
    NEEDS_CLARIFICATION,
    DEFERRED
  }

  enum AcceptanceKind {
    HTTP_OPERATION,
    STATUS_CODE,
    RESPONSE_SHAPE,
    ERROR_SHAPE,
    UI_INTERACTION,
    AUTHORIZATION,
    TEST_EXECUTION,
    ARTIFACT
  }

  enum EvidenceType {
    FUNCTIONAL_TEST,
    CONTRACT_TEST,
    UI_TEST,
    SECURITY_TEST,
    DETERMINISTIC_CHECK
  }

  enum IssueCode {
    UNCOVERED_SOURCE_CLAUSE,
    SOURCE_PROVENANCE_MISSING,
    MISSING_TASK,
    OWNERLESS_TASK,
    UNKNOWN_REQUIREMENT_REFERENCE,
    MISSING_ACCEPTANCE,
    AMBIGUOUS_SOURCE,
    CONFLICTING_CONTRACT,
    DUPLICATE_REQUIREMENT_ID,
    DUPLICATE_ACCEPTANCE_ID,
    DUPLICATE_TASK_ID
  }

  enum Completeness {
    READY,
    BLOCKED
  }
}
