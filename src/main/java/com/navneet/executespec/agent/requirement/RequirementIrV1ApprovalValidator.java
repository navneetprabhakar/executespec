package com.navneet.executespec.agent.requirement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Frozen approval semantics for persisted RequirementIR V1 wire documents. */
final class RequirementIrV1ApprovalValidator {
  private static final String SHA_256 = "[0-9a-f]{64}";
  private static final Set<RequirementIrV1WireDocument.RequirementKind> ACCEPTANCE_REQUIRED =
      Set.of(
          RequirementIrV1WireDocument.RequirementKind.CONTRACT,
          RequirementIrV1WireDocument.RequirementKind.ERROR,
          RequirementIrV1WireDocument.RequirementKind.SECURITY,
          RequirementIrV1WireDocument.RequirementKind.UI);

  private RequirementIrV1ApprovalValidator() {}

  static ApprovalDecision validate(RequirementIrV1WireDocument document, String exactSource) {
    List<String> violations = new ArrayList<>();
    if (document == null) {
      return new ApprovalDecision(
          false, Set.of("SOURCE_PROVENANCE_MISSING"), List.of("MISSING_DOCUMENT"));
    }
    if (document.schemaVersion() != RequirementIrV1WireDocument.SCHEMA_VERSION) {
      violations.add("UNSUPPORTED_SCHEMA");
    }
    if (!hasText(document.compilerFingerprint())
        || !document.compilerFingerprint().matches(SHA_256)
        || !document
            .compilerFingerprint()
            .equals(RequirementIrV1WireDocument.COMPILER_FINGERPRINT)) {
      violations.add("INVALID_COMPILER_FINGERPRINT");
    }
    String source = exactSource == null ? "" : exactSource;
    if (!hasText(document.sourceSha256()) || !document.sourceSha256().equals(sha256(source))) {
      violations.add("SOURCE_HASH_MISMATCH");
    }

    Map<String, RequirementIrV1WireDocument.RequirementNode> requirements =
        requirementIndex(document, violations);
    Map<String, RequirementIrV1WireDocument.AcceptanceCriterion> acceptance =
        acceptanceIndex(document, violations);
    Map<String, RequirementIrV1WireDocument.TaskBinding> tasks = taskIndex(document, violations);
    Map<String, String> claimedSourceSpans = new HashMap<>();

    for (var requirement : document.requirements()) {
      validateRequirement(requirement, source, acceptance, tasks, violations);
      if (requirement.origin() == RequirementIrV1WireDocument.Origin.USER_STATED
          && requirement.sourceSpan() != null) {
        String spanKey =
            requirement.sourceSpan().startOffset() + ":" + requirement.sourceSpan().endOffset();
        if (claimedSourceSpans.putIfAbsent(spanKey, requirement.id()) != null) {
          violations.add("DUPLICATE_SOURCE_SPAN");
        }
      }
    }
    for (var criterion : document.acceptanceCriteria()) {
      validateAcceptance(criterion, source, requirements, violations);
    }
    for (var task : document.taskBindings()) {
      for (String requirementId : task.requirementIds()) {
        var requirement = requirements.get(requirementId);
        if (requirement == null) {
          violations.add("UNKNOWN_REQUIREMENT_REFERENCE");
        } else if (!requirement.taskIds().contains(task.taskId())) {
          violations.add("NON_RECIPROCAL_TASK_BINDING");
        }
      }
    }

    LinkedHashSet<String> blocking = new LinkedHashSet<>();
    document.issues().stream()
        .filter(RequirementIrV1WireDocument.RequirementIssue::blocking)
        .map(issue -> issue.code() == null ? null : issue.code().name())
        .forEach(
            code -> {
              if (code == null) violations.add("INVALID_ISSUE");
              else blocking.add(code);
            });
    for (var requirement : document.requirements()) {
      if (requirement.origin() != RequirementIrV1WireDocument.Origin.USER_STATED) {
        blocking.add("SOURCE_PROVENANCE_MISSING");
      }
      if (requirement.state() == RequirementIrV1WireDocument.RequirementState.NEEDS_CLARIFICATION) {
        blocking.add("AMBIGUOUS_SOURCE");
      }
      if (requirement.state() != RequirementIrV1WireDocument.RequirementState.DEFERRED
          && requirement.taskIds().isEmpty()) {
        blocking.add("MISSING_TASK");
      }
      if (ACCEPTANCE_REQUIRED.contains(requirement.kind())
          && requirement.acceptanceIds().isEmpty()) {
        blocking.add("MISSING_ACCEPTANCE");
      }
    }
    if (document.taskBindings().stream().anyMatch(task -> task.requirementIds().isEmpty())) {
      blocking.add("OWNERLESS_TASK");
    }
    if (document.requirements().isEmpty()) blocking.add("UNCOVERED_SOURCE_CLAUSE");

    boolean approvable =
        violations.isEmpty()
            && document.completeness() == RequirementIrV1WireDocument.Completeness.READY
            && blocking.isEmpty();
    return new ApprovalDecision(approvable, Set.copyOf(blocking), List.copyOf(violations));
  }

  private static Map<String, RequirementIrV1WireDocument.RequirementNode> requirementIndex(
      RequirementIrV1WireDocument document, List<String> violations) {
    Map<String, RequirementIrV1WireDocument.RequirementNode> indexed = new HashMap<>();
    for (var requirement : document.requirements()) {
      if (!hasText(requirement.id())) {
        violations.add("BLANK_REQUIREMENT_ID");
      } else if (indexed.putIfAbsent(requirement.id(), requirement) != null) {
        violations.add("DUPLICATE_REQUIREMENT_ID");
      }
    }
    return indexed;
  }

  private static Map<String, RequirementIrV1WireDocument.AcceptanceCriterion> acceptanceIndex(
      RequirementIrV1WireDocument document, List<String> violations) {
    Map<String, RequirementIrV1WireDocument.AcceptanceCriterion> indexed = new HashMap<>();
    for (var criterion : document.acceptanceCriteria()) {
      if (!hasText(criterion.id())) {
        violations.add("BLANK_ACCEPTANCE_ID");
      } else if (indexed.putIfAbsent(criterion.id(), criterion) != null) {
        violations.add("DUPLICATE_ACCEPTANCE_ID");
      }
    }
    return indexed;
  }

  private static Map<String, RequirementIrV1WireDocument.TaskBinding> taskIndex(
      RequirementIrV1WireDocument document, List<String> violations) {
    Map<String, RequirementIrV1WireDocument.TaskBinding> indexed = new HashMap<>();
    for (var task : document.taskBindings()) {
      if (!hasText(task.taskId())) {
        violations.add("BLANK_TASK_ID");
      } else if (indexed.putIfAbsent(task.taskId(), task) != null) {
        violations.add("DUPLICATE_TASK_ID");
      }
    }
    return indexed;
  }

  private static void validateRequirement(
      RequirementIrV1WireDocument.RequirementNode requirement,
      String source,
      Map<String, RequirementIrV1WireDocument.AcceptanceCriterion> acceptance,
      Map<String, RequirementIrV1WireDocument.TaskBinding> tasks,
      List<String> violations) {
    if (!hasText(requirement.text())
        || requirement.kind() == null
        || requirement.origin() == null
        || requirement.state() == null) {
      violations.add("INVALID_REQUIREMENT");
    }
    if (requirement.origin() == RequirementIrV1WireDocument.Origin.USER_STATED) {
      if (requirement.sourceSpan() == null
          || !spanMatches(source, requirement.sourceSpan(), requirement.text())) {
        violations.add("SOURCE_SPAN_MISMATCH");
      }
      if (hasText(requirement.inferenceRationale())) violations.add("INVALID_PROVENANCE");
    } else if (requirement.origin() != null) {
      if (!hasText(requirement.inferenceRationale()) || requirement.sourceSpan() != null) {
        violations.add("INVALID_PROVENANCE");
      }
    }
    if (requirement.state() == RequirementIrV1WireDocument.RequirementState.DEFERRED
        && !hasText(requirement.deferredReason())) {
      violations.add("INVALID_DEFERMENT");
    }
    for (String taskId : requirement.taskIds()) {
      var task = tasks.get(taskId);
      if (task == null || !task.requirementIds().contains(requirement.id())) {
        violations.add("NON_RECIPROCAL_TASK_BINDING");
      }
    }
    for (String acceptanceId : requirement.acceptanceIds()) {
      var criterion = acceptance.get(acceptanceId);
      if (criterion == null || !criterion.requirementIds().contains(requirement.id())) {
        violations.add("NON_RECIPROCAL_ACCEPTANCE_BINDING");
      }
    }
  }

  private static void validateAcceptance(
      RequirementIrV1WireDocument.AcceptanceCriterion criterion,
      String source,
      Map<String, RequirementIrV1WireDocument.RequirementNode> requirements,
      List<String> violations) {
    if (criterion.kind() == null
        || criterion.evidenceType() == null
        || !hasText(criterion.expected())
        || criterion.requirementIds().isEmpty()) {
      violations.add("INVALID_ACCEPTANCE");
    }
    if (criterion.sourceSpan() != null
        && !spanMatches(source, criterion.sourceSpan(), criterion.expected())) {
      violations.add("SOURCE_SPAN_MISMATCH");
    }
    for (String requirementId : criterion.requirementIds()) {
      var requirement = requirements.get(requirementId);
      if (requirement == null || !requirement.acceptanceIds().contains(criterion.id())) {
        violations.add("NON_RECIPROCAL_ACCEPTANCE_BINDING");
      }
    }
    if (criterion.kind() == RequirementIrV1WireDocument.AcceptanceKind.AUTHORIZATION
        && criterion.evidenceType() != RequirementIrV1WireDocument.EvidenceType.SECURITY_TEST) {
      violations.add("INVALID_ACCEPTANCE");
    }
  }

  private static boolean spanMatches(
      String source, RequirementIrV1WireDocument.SourceSpan span, String expected) {
    if (span.startOffset() < 0
        || span.endOffset() < span.startOffset()
        || span.endOffset() > source.length()
        || span.startLine() < 1
        || span.startColumn() < 1
        || span.endLine() < span.startLine()
        || span.endColumn() < 1) return false;
    Position start = position(source, span.startOffset());
    Position end = position(source, span.endOffset());
    return source.substring(span.startOffset(), span.endOffset()).equals(expected)
        && span.startLine() == start.line()
        && span.startColumn() == start.column()
        && span.endLine() == end.line()
        && span.endColumn() == end.column();
  }

  private static Position position(String source, int offset) {
    int line = 1;
    int column = 1;
    for (int i = 0; i < offset && i < source.length(); i++) {
      if (source.charAt(i) == '\n') {
        line++;
        column = 1;
      } else {
        column++;
      }
    }
    return new Position(line, column);
  }

  static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception failure) {
      throw new IllegalStateException("SHA-256 unavailable", failure);
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  record ApprovalDecision(boolean approvable, Set<String> blockingCodes, List<String> violations) {
    ApprovalDecision {
      blockingCodes = blockingCodes == null ? Set.of() : Set.copyOf(blockingCodes);
      violations = violations == null ? List.of() : List.copyOf(violations);
    }
  }

  private record Position(int line, int column) {}
}
