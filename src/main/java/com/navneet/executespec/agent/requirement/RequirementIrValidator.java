package com.navneet.executespec.agent.requirement;

import static com.navneet.executespec.agent.requirement.RequirementIrDocument.Origin.USER_STATED;
import static com.navneet.executespec.agent.requirement.RequirementIrDocument.RequirementState.DEFERRED;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.navneet.executespec.agent.requirement.RequirementIrDocument.AcceptanceCriterion;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.AcceptanceKind;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.Completeness;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.IssueCode;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.RequirementKind;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.RequirementNode;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.SourceSpan;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.TaskBinding;

/** Structural and approval-completeness checks for a versioned requirement contract. */
public final class RequirementIrValidator {
  private static final String SHA_256 = "[0-9a-f]{64}";
  private static final Set<RequirementKind> ACCEPTANCE_REQUIRED =
      Set.of(
          RequirementKind.CONTRACT,
          RequirementKind.ERROR,
          RequirementKind.SECURITY,
          RequirementKind.UI);

  private RequirementIrValidator() {}

  public static ValidationResult validateStructure(
      RequirementIrDocument document, String exactSource) {
    return validateStructure(
        document,
        exactSource,
        RequirementIrDocument.CURRENT_SCHEMA_VERSION,
        RequirementIrCompiler.COMPILER_FINGERPRINT);
  }

  static ValidationResult validateStructure(
      RequirementIrDocument document,
      String exactSource,
      int expectedSchemaVersion,
      String expectedCompilerFingerprint) {
    List<Violation> violations = new ArrayList<>();
    if (document == null) {
      return new ValidationResult(
          false, List.of(new Violation(ViolationCode.MISSING_DOCUMENT, "document is required")));
    }
    if (document.schemaVersion() != expectedSchemaVersion) {
      violations.add(
          new Violation(
              ViolationCode.UNSUPPORTED_SCHEMA,
              "unsupported requirement IR schema " + document.schemaVersion()));
    }
    if (!hasText(document.compilerFingerprint())
        || !document.compilerFingerprint().matches(SHA_256)
        || !document.compilerFingerprint().equals(expectedCompilerFingerprint)) {
      violations.add(
          new Violation(
              ViolationCode.INVALID_COMPILER_FINGERPRINT, "invalid compiler fingerprint"));
    }
    String safeSource = exactSource == null ? "" : exactSource;
    if (!hasText(document.sourceSha256()) || !document.sourceSha256().equals(sha256(safeSource))) {
      violations.add(new Violation(ViolationCode.SOURCE_HASH_MISMATCH, "source hash mismatch"));
    }

    Map<String, RequirementNode> requirements = requirementIndex(document, violations);
    Map<String, AcceptanceCriterion> acceptance = acceptanceIndex(document, violations);
    Map<String, TaskBinding> tasks = taskIndex(document, violations);
    Map<String, String> claimedSourceSpans = new HashMap<>();

    for (RequirementNode requirement : document.requirements()) {
      if (requirement == null) continue;
      validateRequirement(requirement, safeSource, acceptance, tasks, violations);
      if (requirement.origin() == USER_STATED && requirement.sourceSpan() != null) {
        String spanKey =
            requirement.sourceSpan().startOffset() + ":" + requirement.sourceSpan().endOffset();
        String previous = claimedSourceSpans.putIfAbsent(spanKey, requirement.id());
        if (previous != null) {
          violations.add(
              new Violation(
                  ViolationCode.DUPLICATE_SOURCE_SPAN,
                  requirement.id() + " duplicates source span claimed by " + previous));
        }
      }
    }
    for (AcceptanceCriterion criterion : document.acceptanceCriteria()) {
      if (criterion == null) continue;
      validateAcceptance(criterion, safeSource, requirements, violations);
    }
    for (TaskBinding task : document.taskBindings()) {
      if (task == null) continue;
      for (String requirementId : task.requirementIds()) {
        RequirementNode requirement = requirements.get(requirementId);
        if (requirement == null) {
          violations.add(
              new Violation(
                  ViolationCode.UNKNOWN_REQUIREMENT_REFERENCE,
                  "task " + task.taskId() + " references " + requirementId));
        } else if (!requirement.taskIds().contains(task.taskId())) {
          violations.add(
              new Violation(
                  ViolationCode.NON_RECIPROCAL_TASK_BINDING,
                  "task binding is not reciprocal for " + task.taskId() + "/" + requirementId));
        }
      }
    }

    return new ValidationResult(violations.isEmpty(), List.copyOf(violations));
  }

  /** The only approval-boundary validator: structure and completeness are inseparable. */
  public static CompletenessDecision validateForApproval(
      RequirementIrDocument document, String exactSource) {
    return validateForApproval(
        document,
        exactSource,
        RequirementIrDocument.CURRENT_SCHEMA_VERSION,
        RequirementIrCompiler.COMPILER_FINGERPRINT);
  }

  static CompletenessDecision validateForApproval(
      RequirementIrDocument document,
      String exactSource,
      int expectedSchemaVersion,
      String expectedCompilerFingerprint) {
    ValidationResult structure =
        validateStructure(
            document, exactSource, expectedSchemaVersion, expectedCompilerFingerprint);
    if (document == null) {
      return new CompletenessDecision(
          false, Set.of(IssueCode.SOURCE_PROVENANCE_MISSING), structure.violations());
    }
    LinkedHashSet<IssueCode> blocking = new LinkedHashSet<>();
    document.issues().stream()
        .filter(java.util.Objects::nonNull)
        .filter(RequirementIrDocument.RequirementIssue::blocking)
        .map(RequirementIrDocument.RequirementIssue::code)
        .forEach(blocking::add);
    document.requirements().stream()
        .filter(java.util.Objects::nonNull)
        .forEach(
            requirement -> {
              if (requirement.origin() != USER_STATED) {
                blocking.add(IssueCode.SOURCE_PROVENANCE_MISSING);
              }
              if (requirement.state()
                  == RequirementIrDocument.RequirementState.NEEDS_CLARIFICATION) {
                blocking.add(IssueCode.AMBIGUOUS_SOURCE);
              }
              if (requirement.state() != DEFERRED && requirement.taskIds().isEmpty()) {
                blocking.add(IssueCode.MISSING_TASK);
              }
              if (ACCEPTANCE_REQUIRED.contains(requirement.kind())
                  && requirement.acceptanceIds().isEmpty()) {
                blocking.add(IssueCode.MISSING_ACCEPTANCE);
              }
            });
    if (document.taskBindings().stream()
        .filter(java.util.Objects::nonNull)
        .anyMatch(task -> task.requirementIds().isEmpty())) {
      blocking.add(IssueCode.OWNERLESS_TASK);
    }
    if (document.requirements().isEmpty()) {
      blocking.add(IssueCode.UNCOVERED_SOURCE_CLAUSE);
    }
    boolean approvable =
        structure.valid() && document.completeness() == Completeness.READY && blocking.isEmpty();
    return new CompletenessDecision(approvable, Set.copyOf(blocking), structure.violations());
  }

  private static Map<String, RequirementNode> requirementIndex(
      RequirementIrDocument document, List<Violation> violations) {
    Map<String, RequirementNode> indexed = new HashMap<>();
    for (RequirementNode requirement : document.requirements()) {
      if (requirement == null || !hasText(requirement.id())) {
        violations.add(new Violation(ViolationCode.BLANK_REQUIREMENT_ID, "blank requirement id"));
        continue;
      }
      if (indexed.putIfAbsent(requirement.id(), requirement) != null) {
        violations.add(
            new Violation(
                ViolationCode.DUPLICATE_REQUIREMENT_ID,
                "duplicate requirement id " + requirement.id()));
      }
    }
    return indexed;
  }

  private static Map<String, AcceptanceCriterion> acceptanceIndex(
      RequirementIrDocument document, List<Violation> violations) {
    Map<String, AcceptanceCriterion> indexed = new HashMap<>();
    for (AcceptanceCriterion criterion : document.acceptanceCriteria()) {
      if (criterion == null || !hasText(criterion.id())) {
        violations.add(new Violation(ViolationCode.BLANK_ACCEPTANCE_ID, "blank acceptance id"));
        continue;
      }
      if (indexed.putIfAbsent(criterion.id(), criterion) != null) {
        violations.add(
            new Violation(
                ViolationCode.DUPLICATE_ACCEPTANCE_ID,
                "duplicate acceptance id " + criterion.id()));
      }
    }
    return indexed;
  }

  private static Map<String, TaskBinding> taskIndex(
      RequirementIrDocument document, List<Violation> violations) {
    Map<String, TaskBinding> indexed = new HashMap<>();
    for (TaskBinding task : document.taskBindings()) {
      if (task == null || !hasText(task.taskId())) {
        violations.add(new Violation(ViolationCode.BLANK_TASK_ID, "blank task id"));
        continue;
      }
      if (indexed.putIfAbsent(task.taskId(), task) != null) {
        violations.add(
            new Violation(ViolationCode.DUPLICATE_TASK_ID, "duplicate task " + task.taskId()));
      }
    }
    return indexed;
  }

  private static void validateRequirement(
      RequirementNode requirement,
      String source,
      Map<String, AcceptanceCriterion> acceptance,
      Map<String, TaskBinding> tasks,
      List<Violation> violations) {
    if (!hasText(requirement.text())
        || requirement.kind() == null
        || requirement.origin() == null) {
      violations.add(
          new Violation(
              ViolationCode.INVALID_REQUIREMENT,
              "requirement " + requirement.id() + " lacks text, kind, or origin"));
    }
    if (requirement.state() == null) {
      violations.add(
          new Violation(ViolationCode.INVALID_REQUIREMENT, requirement.id() + " lacks state"));
    }
    if (requirement.origin() == USER_STATED) {
      if (requirement.sourceSpan() == null
          || !spanMatches(source, requirement.sourceSpan(), requirement.text())) {
        violations.add(
            new Violation(
                ViolationCode.SOURCE_SPAN_MISMATCH,
                requirement.id() + " does not match its exact source span"));
      }
      if (hasText(requirement.inferenceRationale())) {
        violations.add(
            new Violation(
                ViolationCode.INVALID_PROVENANCE,
                requirement.id() + " is user-stated but has inference rationale"));
      }
    } else {
      if (!hasText(requirement.inferenceRationale())) {
        violations.add(
            new Violation(
                ViolationCode.INVALID_PROVENANCE,
                requirement.id() + " is inferred without rationale"));
      }
      if (requirement.sourceSpan() != null) {
        violations.add(
            new Violation(
                ViolationCode.INVALID_PROVENANCE,
                requirement.id() + " is inferred but claims an exact source span"));
      }
    }
    if (requirement.state() == DEFERRED && !hasText(requirement.deferredReason())) {
      violations.add(
          new Violation(
              ViolationCode.INVALID_DEFERMENT, requirement.id() + " is deferred without a reason"));
    }
    for (String taskId : requirement.taskIds()) {
      TaskBinding task = tasks.get(taskId);
      if (task == null || !task.requirementIds().contains(requirement.id())) {
        violations.add(
            new Violation(
                ViolationCode.NON_RECIPROCAL_TASK_BINDING,
                "requirement binding is not reciprocal for " + requirement.id() + "/" + taskId));
      }
    }
    for (String acceptanceId : requirement.acceptanceIds()) {
      AcceptanceCriterion criterion = acceptance.get(acceptanceId);
      if (criterion == null || !criterion.requirementIds().contains(requirement.id())) {
        violations.add(
            new Violation(
                ViolationCode.NON_RECIPROCAL_ACCEPTANCE_BINDING,
                "acceptance binding is not reciprocal for "
                    + requirement.id()
                    + "/"
                    + acceptanceId));
      }
    }
  }

  private static void validateAcceptance(
      AcceptanceCriterion criterion,
      String source,
      Map<String, RequirementNode> requirements,
      List<Violation> violations) {
    if (criterion.kind() == null
        || criterion.evidenceType() == null
        || !hasText(criterion.expected())
        || criterion.requirementIds().isEmpty()) {
      violations.add(
          new Violation(
              ViolationCode.INVALID_ACCEPTANCE,
              "acceptance " + criterion.id() + " is not observable or is unbound"));
    }
    if (criterion.sourceSpan() != null
        && !spanMatches(source, criterion.sourceSpan(), criterion.expected())) {
      violations.add(
          new Violation(
              ViolationCode.SOURCE_SPAN_MISMATCH,
              criterion.id() + " does not match its source span"));
    }
    for (String requirementId : criterion.requirementIds()) {
      RequirementNode requirement = requirements.get(requirementId);
      if (requirement == null || !requirement.acceptanceIds().contains(criterion.id())) {
        violations.add(
            new Violation(
                ViolationCode.NON_RECIPROCAL_ACCEPTANCE_BINDING,
                "acceptance binding is not reciprocal for "
                    + criterion.id()
                    + "/"
                    + requirementId));
      }
    }
    if (criterion.kind() == AcceptanceKind.AUTHORIZATION
        && criterion.evidenceType() != RequirementIrDocument.EvidenceType.SECURITY_TEST) {
      violations.add(
          new Violation(
              ViolationCode.INVALID_ACCEPTANCE,
              criterion.id() + " authorization evidence must be a security test"));
    }
  }

  static boolean spanMatches(String source, SourceSpan span, String expected) {
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
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  public record ValidationResult(boolean valid, List<Violation> violations) {
    public ValidationResult {
      violations = violations == null ? List.of() : List.copyOf(violations);
    }
  }

  public record Violation(ViolationCode code, String message) {}

  public record CompletenessDecision(
      boolean approvable, Set<IssueCode> blockingCodes, List<Violation> structuralViolations) {
    public CompletenessDecision {
      blockingCodes = blockingCodes == null ? Set.of() : Set.copyOf(blockingCodes);
      structuralViolations =
          structuralViolations == null ? List.of() : List.copyOf(structuralViolations);
    }
  }

  public enum ViolationCode {
    MISSING_DOCUMENT,
    UNSUPPORTED_SCHEMA,
    INVALID_COMPILER_FINGERPRINT,
    SOURCE_HASH_MISMATCH,
    BLANK_REQUIREMENT_ID,
    DUPLICATE_REQUIREMENT_ID,
    BLANK_ACCEPTANCE_ID,
    DUPLICATE_ACCEPTANCE_ID,
    BLANK_TASK_ID,
    DUPLICATE_TASK_ID,
    INVALID_REQUIREMENT,
    INVALID_PROVENANCE,
    INVALID_DEFERMENT,
    SOURCE_SPAN_MISMATCH,
    DUPLICATE_SOURCE_SPAN,
    MISSING_TASK,
    OWNERLESS_TASK,
    UNKNOWN_REQUIREMENT_REFERENCE,
    NON_RECIPROCAL_TASK_BINDING,
    MISSING_ACCEPTANCE,
    INVALID_ACCEPTANCE,
    NON_RECIPROCAL_ACCEPTANCE_BINDING
  }

  private record Position(int line, int column) {}
}
