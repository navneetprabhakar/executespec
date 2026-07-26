package com.navneet.executespec.agent.requirement;

import java.util.List;

/**
 * Versioned, provider-independent requirement and acceptance contract compiled beside a legacy
 * executable plan.
 *
 * <p>This document is deliberately not embedded in {@code Plan}: plan JSON is a rolling-deploy,
 * crash-recovery, and approval-fingerprint contract. Requirement IR evolves independently and is
 * joined to a run through a separate immutable artifact.
 */
public record RequirementIrDocument(
    int schemaVersion,
    String compilerFingerprint,
    String sourceSha256,
    List<RequirementNode> requirements,
    List<AcceptanceCriterion> acceptanceCriteria,
    List<TaskBinding> taskBindings,
    List<RequirementIssue> issues,
    Completeness completeness) {

  public static final int CURRENT_SCHEMA_VERSION = 1;

  public RequirementIrDocument {
    requirements = immutable(requirements);
    acceptanceCriteria = immutable(acceptanceCriteria);
    taskBindings = immutable(taskBindings);
    issues = immutable(issues);
  }

  private static <T> List<T> immutable(List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  public record RequirementNode(
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

    public RequirementNode {
      acceptanceIds = immutable(acceptanceIds);
      taskIds = immutable(taskIds);
    }
  }

  public record AcceptanceCriterion(
      String id,
      List<String> requirementIds,
      AcceptanceKind kind,
      EvidenceType evidenceType,
      String expected,
      SourceSpan sourceSpan) {

    public AcceptanceCriterion {
      requirementIds = immutable(requirementIds);
    }
  }

  public record TaskBinding(String taskId, List<String> requirementIds) {
    public TaskBinding {
      requirementIds = immutable(requirementIds);
    }
  }

  public record RequirementIssue(
      IssueCode code,
      boolean blocking,
      String message,
      List<String> requirementIds,
      List<SourceSpan> sourceSpans) {

    public RequirementIssue {
      requirementIds = immutable(requirementIds);
      sourceSpans = immutable(sourceSpans);
    }
  }

  /**
   * Java UTF-16 code-unit offsets are zero-based/end-exclusive; line and column values are
   * one-based and columns are also measured in UTF-16 code units.
   */
  public record SourceSpan(
      int startOffset, int endOffset, int startLine, int startColumn, int endLine, int endColumn) {}

  public enum RequirementKind {
    FUNCTIONAL,
    NON_FUNCTIONAL,
    TEST,
    CONSTRAINT,
    CONTRACT,
    ERROR,
    SECURITY,
    UI
  }

  public enum Origin {
    USER_STATED,
    INFERRED
  }

  public enum RequirementState {
    EXPLICIT,
    NEEDS_CLARIFICATION,
    DEFERRED
  }

  public enum AcceptanceKind {
    HTTP_OPERATION,
    STATUS_CODE,
    RESPONSE_SHAPE,
    ERROR_SHAPE,
    UI_INTERACTION,
    AUTHORIZATION,
    TEST_EXECUTION,
    ARTIFACT
  }

  public enum EvidenceType {
    FUNCTIONAL_TEST,
    CONTRACT_TEST,
    UI_TEST,
    SECURITY_TEST,
    DETERMINISTIC_CHECK
  }

  public enum IssueCode {
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

  public enum Completeness {
    READY,
    BLOCKED
  }
}
