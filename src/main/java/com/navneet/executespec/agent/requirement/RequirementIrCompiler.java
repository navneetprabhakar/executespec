package com.navneet.executespec.agent.requirement;

import static com.navneet.executespec.agent.requirement.RequirementIrDocument.AcceptanceKind.AUTHORIZATION;
import static com.navneet.executespec.agent.requirement.RequirementIrDocument.AcceptanceKind.ERROR_SHAPE;
import static com.navneet.executespec.agent.requirement.RequirementIrDocument.AcceptanceKind.HTTP_OPERATION;
import static com.navneet.executespec.agent.requirement.RequirementIrDocument.AcceptanceKind.RESPONSE_SHAPE;
import static com.navneet.executespec.agent.requirement.RequirementIrDocument.AcceptanceKind.STATUS_CODE;
import static com.navneet.executespec.agent.requirement.RequirementIrDocument.AcceptanceKind.TEST_EXECUTION;
import static com.navneet.executespec.agent.requirement.RequirementIrDocument.AcceptanceKind.UI_INTERACTION;
import static com.navneet.executespec.agent.requirement.RequirementIrDocument.Completeness.BLOCKED;
import static com.navneet.executespec.agent.requirement.RequirementIrDocument.Completeness.READY;
import static com.navneet.executespec.agent.requirement.RequirementIrDocument.EvidenceType.CONTRACT_TEST;
import static com.navneet.executespec.agent.requirement.RequirementIrDocument.EvidenceType.FUNCTIONAL_TEST;
import static com.navneet.executespec.agent.requirement.RequirementIrDocument.EvidenceType.SECURITY_TEST;
import static com.navneet.executespec.agent.requirement.RequirementIrDocument.EvidenceType.UI_TEST;
import static com.navneet.executespec.agent.requirement.RequirementIrDocument.IssueCode.AMBIGUOUS_SOURCE;
import static com.navneet.executespec.agent.requirement.RequirementIrDocument.IssueCode.CONFLICTING_CONTRACT;
import static com.navneet.executespec.agent.requirement.RequirementIrDocument.IssueCode.DUPLICATE_REQUIREMENT_ID;
import static com.navneet.executespec.agent.requirement.RequirementIrDocument.IssueCode.MISSING_ACCEPTANCE;
import static com.navneet.executespec.agent.requirement.RequirementIrDocument.IssueCode.MISSING_TASK;
import static com.navneet.executespec.agent.requirement.RequirementIrDocument.IssueCode.OWNERLESS_TASK;
import static com.navneet.executespec.agent.requirement.RequirementIrDocument.IssueCode.SOURCE_PROVENANCE_MISSING;
import static com.navneet.executespec.agent.requirement.RequirementIrDocument.IssueCode.UNCOVERED_SOURCE_CLAUSE;
import static com.navneet.executespec.agent.requirement.RequirementIrDocument.Origin.INFERRED;
import static com.navneet.executespec.agent.requirement.RequirementIrDocument.Origin.USER_STATED;
import static com.navneet.executespec.agent.requirement.RequirementIrDocument.RequirementState.EXPLICIT;
import static com.navneet.executespec.agent.requirement.RequirementIrDocument.RequirementState.NEEDS_CLARIFICATION;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.agent.RequirementSpec;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.AcceptanceCriterion;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.AcceptanceKind;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.EvidenceType;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.IssueCode;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.Origin;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.RequirementIssue;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.RequirementKind;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.RequirementNode;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.RequirementState;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.SourceSpan;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.TaskBinding;

/**
 * Deterministically compiles exact source and a final legacy plan into versioned requirement IR.
 */
public final class RequirementIrCompiler {
  private static final String ALGORITHM_ID =
      "requirement-ir-compiler:v1.2:exact-source-clauses:legacy-plan-adapter:atomic-acceptance:strict-approval:occurrence-binding";
  public static final String COMPILER_FINGERPRINT = RequirementIrValidator.sha256(ALGORITHM_ID);

  private static final Pattern HTTP_OPERATION_PATTERN =
      Pattern.compile("(?i)\\b(GET|POST|PUT|PATCH|DELETE)\\s+(/[A-Za-z0-9_./{}-]+)");
  private static final Pattern STATUS_CODE_PATTERN = Pattern.compile("(?<!\\d)[1-5]\\d{2}(?!\\d)");
  private static final Pattern ERROR_SIGNAL =
      Pattern.compile(
          "(?i)\\b(?:error(?:s| shape| response)?|invalid|unavailable|not found|failure)\\b");
  private static final Pattern AUTHORIZATION_SIGNAL =
      Pattern.compile(
          "(?i)\\b(?:authoriz(?:e|ed|ation)|permission|role|authenticated|forbidden)\\b");
  private static final Pattern UI_SURFACE =
      Pattern.compile(
          "(?i)\\b(?:screen|page|form|button|modal|dialog|dashboard|user interface|ui)\\b");
  private static final Pattern UI_INTERACTION_SIGNAL =
      Pattern.compile("(?i)\\b(?:click|submit|select|type|tap|keyboard|navigate|drag|upload)\\b");
  private static final Pattern TEST_SIGNAL =
      Pattern.compile(
          "(?i)\\b(?:acceptance|integration|functional|contract|unit|end[- ]to[-"
              + " ]end|e2e)?\\s*tests?\\b");
  private static final Pattern RESPONSE_SHAPE_SIGNAL =
      Pattern.compile(
          "(?i)\\b(?:response (?:field|fields|shape|body)|returns?|preserve|include)\\b");
  private static final Pattern VAGUE_SIGNAL =
      Pattern.compile(
          "(?i)\\b(?:when appropriate|as appropriate|as needed|normal"
              + " fallback|reasonable|etc\\.)\\b");

  public RequirementIrDocument compile(String exactSource, Plan plan) {
    String source = exactSource == null ? "" : exactSource;
    List<RequirementSpec> legacyRequirements =
        plan == null || plan.requirements() == null ? List.of() : plan.requirements();
    List<PlannedTask> plannedTasks =
        plan == null || plan.tasks() == null ? List.of() : plan.tasks();

    List<RequirementIssue> issues = new ArrayList<>();
    if (!hasText(source)) {
      issues.add(
          new RequirementIssue(
              SOURCE_PROVENANCE_MISSING,
              true,
              "requirement source is empty",
              List.of(),
              List.of()));
    }
    if (plan == null) {
      issues.add(
          new RequirementIssue(
              SOURCE_PROVENANCE_MISSING,
              true,
              "final executable plan is missing",
              List.of(),
              List.of()));
    }
    List<TaskBinding> taskBindings = taskBindings(plannedTasks, issues);
    Map<String, List<String>> ownersByRequirement = ownersByRequirement(taskBindings);
    List<Clause> clauses = sourceClauses(source);
    if (clauses.isEmpty()) {
      issues.add(
          new RequirementIssue(
              UNCOVERED_SOURCE_CLAUSE,
              true,
              "requirement source contains no semantic clauses",
              List.of(),
              List.of()));
    }
    Set<Integer> representedClauses = new HashSet<>();
    Set<Integer> usedMatchStarts = new HashSet<>();
    Set<String> ids = new HashSet<>();
    List<NodeDraft> drafts = new ArrayList<>();

    for (RequirementSpec legacy : legacyRequirements) {
      if (legacy == null || !hasText(legacy.id()) || !hasText(legacy.text())) continue;
      if (!ids.add(legacy.id())) {
        issues.add(issue(DUPLICATE_REQUIREMENT_ID, legacy.id(), "duplicate requirement id"));
        continue;
      }
      Match match = exactMatch(source, clauses, legacy.text(), usedMatchStarts);
      Origin origin = match == null ? INFERRED : USER_STATED;
      SourceSpan span = match == null ? null : span(source, match.start(), match.end());
      if (match != null && match.clauseIndex() >= 0 && match.coversWholeClause()) {
        representedClauses.add(match.clauseIndex());
      }
      if (match != null) usedMatchStarts.add(match.start());
      List<String> taskIds = ownersByRequirement.getOrDefault(legacy.id(), List.of());
      RequirementKind kind = classify(legacy.text(), legacy.kind());
      RequirementState state =
          origin == INFERRED || taskIds.isEmpty() || VAGUE_SIGNAL.matcher(legacy.text()).find()
              ? NEEDS_CLARIFICATION
              : EXPLICIT;
      if (origin == INFERRED) {
        issues.add(
            issue(
                SOURCE_PROVENANCE_MISSING,
                legacy.id(),
                "planner requirement is not an exact source span"));
      }
      if (taskIds.isEmpty()) {
        issues.add(issue(MISSING_TASK, legacy.id(), "requirement has no task owner"));
      }
      if (VAGUE_SIGNAL.matcher(legacy.text()).find()) {
        issues.add(
            issue(AMBIGUOUS_SOURCE, legacy.id(), "requirement contains unresolved vague language"));
      }
      drafts.add(
          new NodeDraft(
              legacy.id(),
              legacy.text(),
              kind,
              origin,
              state,
              span,
              origin == INFERRED ? "Planner output has no exact source span" : null,
              taskIds));
    }

    for (Clause clause : clauses) {
      if (representedClauses.contains(clause.index())) continue;
      String id = uniqueSourceId(clause.text(), ids);
      ids.add(id);
      SourceSpan span = span(source, clause.start(), clause.end());
      issues.add(
          new RequirementIssue(
              UNCOVERED_SOURCE_CLAUSE,
              true,
              "source clause is not represented by the legacy plan requirement ledger",
              List.of(id),
              List.of(span)));
      drafts.add(
          new NodeDraft(
              id,
              clause.text(),
              classify(clause.text(), null),
              USER_STATED,
              NEEDS_CLARIFICATION,
              span,
              null,
              List.of()));
    }

    addSourceLevelIssues(source, drafts, issues);

    List<AcceptanceCriterion> acceptance = new ArrayList<>();
    List<RequirementNode> requirements = new ArrayList<>();
    for (NodeDraft draft : drafts) {
      List<AcceptanceCriterion> compiledAcceptance = compileAcceptance(draft);
      acceptance.addAll(compiledAcceptance);
      List<String> acceptanceIds =
          compiledAcceptance.stream().map(AcceptanceCriterion::id).toList();
      if (requiresAcceptance(draft.kind()) && acceptanceIds.isEmpty()) {
        issues.add(
            issue(MISSING_ACCEPTANCE, draft.id(), "requirement lacks observable acceptance"));
      }
      requirements.add(
          new RequirementNode(
              draft.id(),
              draft.text(),
              draft.kind(),
              draft.origin(),
              draft.state(),
              draft.sourceSpan(),
              draft.inferenceRationale(),
              null,
              acceptanceIds,
              draft.taskIds()));
    }

    boolean blocked = issues.stream().anyMatch(RequirementIssue::blocking);
    RequirementIrDocument document =
        new RequirementIrDocument(
            RequirementIrDocument.CURRENT_SCHEMA_VERSION,
            COMPILER_FINGERPRINT,
            RequirementIrValidator.sha256(source),
            requirements,
            acceptance,
            taskBindings,
            issues,
            blocked ? BLOCKED : READY);
    if (!RequirementIrValidator.validateStructure(document, source).valid()) {
      return new RequirementIrDocument(
          document.schemaVersion(),
          document.compilerFingerprint(),
          document.sourceSha256(),
          document.requirements(),
          document.acceptanceCriteria(),
          document.taskBindings(),
          document.issues(),
          BLOCKED);
    }
    return document;
  }

  private static List<TaskBinding> taskBindings(
      List<PlannedTask> tasks, List<RequirementIssue> issues) {
    List<TaskBinding> bindings = new ArrayList<>();
    Set<String> taskIds = new HashSet<>();
    for (PlannedTask task : tasks) {
      if (task == null || !hasText(task.id())) continue;
      if (!taskIds.add(task.id())) {
        issues.add(
            new RequirementIssue(
                RequirementIrDocument.IssueCode.DUPLICATE_TASK_ID,
                true,
                "duplicate task id " + task.id(),
                List.of(),
                List.of()));
        continue;
      }
      List<String> requirementIds =
          task.meetsRequirements() == null
              ? List.of()
              : task.meetsRequirements().stream()
                  .filter(RequirementIrCompiler::hasText)
                  .distinct()
                  .toList();
      if (requirementIds.isEmpty()) {
        issues.add(
            new RequirementIssue(
                OWNERLESS_TASK,
                true,
                "task " + task.id() + " is not linked to any requirement",
                List.of(),
                List.of()));
      }
      bindings.add(new TaskBinding(task.id(), requirementIds));
    }
    return List.copyOf(bindings);
  }

  private static Map<String, List<String>> ownersByRequirement(List<TaskBinding> bindings) {
    Map<String, List<String>> owners = new LinkedHashMap<>();
    for (TaskBinding binding : bindings) {
      for (String requirementId : binding.requirementIds()) {
        owners.computeIfAbsent(requirementId, ignored -> new ArrayList<>()).add(binding.taskId());
      }
    }
    owners.replaceAll((ignored, values) -> List.copyOf(values));
    return Map.copyOf(owners);
  }

  private static List<AcceptanceCriterion> compileAcceptance(NodeDraft draft) {
    List<AcceptanceCriterion> criteria = new ArrayList<>();
    addAcceptance(
        criteria,
        draft,
        HTTP_OPERATION,
        CONTRACT_TEST,
        HTTP_OPERATION_PATTERN.matcher(draft.text()));
    addAcceptance(
        criteria, draft, STATUS_CODE, CONTRACT_TEST, STATUS_CODE_PATTERN.matcher(draft.text()));
    addAcceptance(
        criteria,
        draft,
        RESPONSE_SHAPE,
        CONTRACT_TEST,
        RESPONSE_SHAPE_SIGNAL.matcher(draft.text()));
    addAcceptance(
        criteria, draft, ERROR_SHAPE, FUNCTIONAL_TEST, ERROR_SIGNAL.matcher(draft.text()));
    addAcceptance(
        criteria, draft, UI_INTERACTION, UI_TEST, UI_INTERACTION_SIGNAL.matcher(draft.text()));
    addAcceptance(
        criteria, draft, AUTHORIZATION, SECURITY_TEST, AUTHORIZATION_SIGNAL.matcher(draft.text()));
    addAcceptance(
        criteria, draft, TEST_EXECUTION, FUNCTIONAL_TEST, TEST_SIGNAL.matcher(draft.text()));
    return List.copyOf(criteria);
  }

  private static void addAcceptance(
      List<AcceptanceCriterion> criteria,
      NodeDraft draft,
      AcceptanceKind kind,
      EvidenceType evidenceType,
      Matcher matcher) {
    int ordinal = 1;
    while (matcher.find()) {
      if (isNegated(draft.text(), matcher.start())) continue;
      String expected = matcher.group();
      SourceSpan criterionSpan = null;
      if (draft.sourceSpan() != null) {
        int start = draft.sourceSpan().startOffset() + matcher.start();
        int end = draft.sourceSpan().startOffset() + matcher.end();
        criterionSpan = relativeSpan(draft, start, end);
      }
      String id = "A-" + draft.id() + "-" + kind.name() + "-" + ordinal++;
      criteria.add(
          new AcceptanceCriterion(
              id, List.of(draft.id()), kind, evidenceType, expected, criterionSpan));
    }
  }

  private static SourceSpan relativeSpan(NodeDraft draft, int start, int end) {
    SourceSpan parent = draft.sourceSpan();
    String prefix = draft.text().substring(0, start - parent.startOffset());
    String matched =
        draft.text().substring(start - parent.startOffset(), end - parent.startOffset());
    int startLine = parent.startLine();
    int startColumn = parent.startColumn();
    for (int i = 0; i < prefix.length(); i++) {
      if (prefix.charAt(i) == '\n') {
        startLine++;
        startColumn = 1;
      } else {
        startColumn++;
      }
    }
    int endLine = startLine;
    int endColumn = startColumn;
    for (int i = 0; i < matched.length(); i++) {
      if (matched.charAt(i) == '\n') {
        endLine++;
        endColumn = 1;
      } else {
        endColumn++;
      }
    }
    return new SourceSpan(start, end, startLine, startColumn, endLine, endColumn);
  }

  private static void addSourceLevelIssues(
      String source, List<NodeDraft> drafts, List<RequirementIssue> issues) {
    String lower = source.toLowerCase(Locale.ROOT);
    for (NodeDraft draft : drafts) {
      if (draft.kind() != RequirementKind.UI) continue;
      addMissingDimension(
          issues, draft, hasAffirmed(HTTP_OPERATION_PATTERN, draft.text()), "API route");
      addMissingDimension(
          issues, draft, hasAffirmed(RESPONSE_SHAPE_SIGNAL, draft.text()), "response fields");
      addMissingDimension(issues, draft, hasAffirmed(ERROR_SIGNAL, draft.text()), "error behavior");
      addMissingDimension(
          issues, draft, hasAffirmed(TEST_SIGNAL, draft.text()), "executable tests");
      addMissingDimension(
          issues, draft, hasAffirmed(UI_INTERACTION_SIGNAL, draft.text()), "UI interaction");
    }
    if (VAGUE_SIGNAL.matcher(source).find()) {
      issues.add(
          new RequirementIssue(
              AMBIGUOUS_SOURCE,
              true,
              "source contains unresolved fallback/retry language",
              drafts.stream().map(NodeDraft::id).toList(),
              drafts.stream()
                  .map(NodeDraft::sourceSpan)
                  .filter(java.util.Objects::nonNull)
                  .toList()));
    }
    List<String> operations = new ArrayList<>();
    Matcher operation = HTTP_OPERATION_PATTERN.matcher(source);
    while (operation.find()) {
      if (!isNegated(source, operation.start())) {
        operations.add(operation.group().toUpperCase(Locale.ROOT));
      }
    }
    boolean sameSubjectConflict =
        operations.stream().distinct().count() > 1
            && lower.contains("same")
            && (lower.contains("both") || lower.contains("authoritative"));
    if (sameSubjectConflict) {
      issues.add(
          new RequirementIssue(
              CONFLICTING_CONTRACT,
              true,
              "the same operation has conflicting HTTP contracts " + operations,
              drafts.stream().map(NodeDraft::id).toList(),
              List.of()));
    }
  }

  private static void addMissingDimension(
      List<RequirementIssue> issues, NodeDraft draft, boolean present, String dimension) {
    if (present) return;
    issues.add(
        new RequirementIssue(
            MISSING_ACCEPTANCE,
            true,
            "UI requirement does not define " + dimension,
            List.of(draft.id()),
            List.of()));
  }

  private static RequirementIssue issue(IssueCode code, String requirementId, String message) {
    return new RequirementIssue(code, true, message, List.of(requirementId), List.of());
  }

  private static boolean requiresAcceptance(RequirementKind kind) {
    return kind == RequirementKind.CONTRACT
        || kind == RequirementKind.ERROR
        || kind == RequirementKind.SECURITY
        || kind == RequirementKind.UI;
  }

  private static RequirementKind classify(String text, RequirementSpec.RequirementKind legacyKind) {
    if (hasAffirmed(AUTHORIZATION_SIGNAL, text)) return RequirementKind.SECURITY;
    if (hasAffirmed(ERROR_SIGNAL, text)) return RequirementKind.ERROR;
    if (hasAffirmed(HTTP_OPERATION_PATTERN, text) || hasAffirmed(RESPONSE_SHAPE_SIGNAL, text)) {
      return RequirementKind.CONTRACT;
    }
    if (hasAffirmed(TEST_SIGNAL, text)) return RequirementKind.TEST;
    if (UI_SURFACE.matcher(text).find()) return RequirementKind.UI;
    if (legacyKind == null) return RequirementKind.FUNCTIONAL;
    return switch (legacyKind) {
      case FUNCTIONAL -> RequirementKind.FUNCTIONAL;
      case NON_FUNCTIONAL -> RequirementKind.NON_FUNCTIONAL;
      case TEST -> RequirementKind.TEST;
      case CONSTRAINT -> RequirementKind.CONSTRAINT;
    };
  }

  private static Match exactMatch(
      String source, List<Clause> clauses, String text, Set<Integer> usedMatchStarts) {
    int start = source.indexOf(text);
    while (start >= 0 && usedMatchStarts.contains(start)) {
      start = source.indexOf(text, start + Math.max(1, text.length()));
    }
    if (start < 0) return null;
    int end = start + text.length();
    for (Clause clause : clauses) {
      if (start >= clause.start() && end <= clause.end()) {
        return new Match(
            start, end, clause.index(), start == clause.start() && end == clause.end());
      }
    }
    return new Match(start, end, -1, false);
  }

  private static List<Clause> sourceClauses(String source) {
    List<Clause> clauses = new ArrayList<>();
    int cursor = 0;
    while (cursor < source.length()) {
      while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) cursor++;
      if (cursor >= source.length()) break;
      int start = cursor;
      int end = source.length();
      for (int i = cursor; i < source.length() - 1; i++) {
        if (source.charAt(i) == '\n' && source.charAt(i + 1) == '\n') {
          end = i;
          cursor = i + 2;
          break;
        }
      }
      if (end == source.length()) cursor = source.length();
      while (end > start && Character.isWhitespace(source.charAt(end - 1))) end--;
      String text = source.substring(start, end);
      if (text.stripLeading().startsWith("#") && text.contains("\n")) {
        int headingEnd = text.indexOf('\n') + 1;
        start += headingEnd;
        while (start < end && Character.isWhitespace(source.charAt(start))) start++;
        text = source.substring(start, end);
      }
      if (!text.isBlank() && !text.stripLeading().startsWith("#")) {
        clauses.add(new Clause(clauses.size(), start, end, text));
      }
    }
    return List.copyOf(clauses);
  }

  private static SourceSpan span(String source, int start, int end) {
    Position from = position(source, start);
    Position to = position(source, end);
    return new SourceSpan(start, end, from.line(), from.column(), to.line(), to.column());
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

  private static String uniqueSourceId(String text, Set<String> ids) {
    String digest = RequirementIrValidator.sha256(text).substring(0, 12).toUpperCase(Locale.ROOT);
    String candidate = "SRC-" + digest;
    int suffix = 2;
    while (ids.contains(candidate)) candidate = "SRC-" + digest + "-" + suffix++;
    return candidate;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static boolean hasAffirmed(Pattern pattern, String source) {
    return nextAffirmed(pattern.matcher(source), source);
  }

  private static boolean nextAffirmed(Matcher matcher, String source) {
    while (matcher.find()) {
      if (!isNegated(source, matcher.start())) return true;
    }
    return false;
  }

  private static boolean isNegated(String source, int matchStart) {
    int boundary = matchStart;
    while (boundary > 0) {
      char previous = source.charAt(boundary - 1);
      if (previous == '.' || previous == '!' || previous == '?' || previous == '\n') break;
      boundary--;
    }
    String prefix = source.substring(boundary, matchStart).strip().toLowerCase(Locale.ROOT);
    return prefix.startsWith("no ")
        || prefix.startsWith("without ")
        || prefix.matches("(?s).*(?:\\bnot|\\bnever|\\bcannot|can't|mustn't|shouldn't)\\s*$")
        || prefix.matches(
            "(?s).*(?:\\bnot|\\bnever|\\bcannot|can't|mustn't|shouldn't)\\s+\\w+\\s*$")
        || prefix.contains(" does not define ")
        || prefix.contains(" is not defined ")
        || prefix.contains(" out of scope ")
        || prefix.contains(" unnecessary ");
  }

  private record Clause(int index, int start, int end, String text) {}

  private record Match(int start, int end, int clauseIndex, boolean coversWholeClause) {}

  private record Position(int line, int column) {}

  private record NodeDraft(
      String id,
      String text,
      RequirementKind kind,
      Origin origin,
      RequirementState state,
      SourceSpan sourceSpan,
      String inferenceRationale,
      List<String> taskIds) {}
}
