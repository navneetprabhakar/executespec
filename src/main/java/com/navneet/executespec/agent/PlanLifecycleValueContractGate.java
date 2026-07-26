package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Validates entity-scoped status and state vocabularies declared across assembled plan tasks. */
final class PlanLifecycleValueContractGate {
  private static final Pattern EXACT_LIFECYCLE =
      Pattern.compile(
          "(?i:\\b([A-Za-z][A-Za-z0-9_]*)\\.(?:status|state)\\s+lifecycle"
              + "(?:\\s+values)?\\s+(?:are|is)\\s+exactly)\\s+([^.;\\n]+)");
  private static final Pattern CANONICAL_LIFECYCLE_VALUE = Pattern.compile("\\b[A-Z][A-Z0-9_]*\\b");
  private static final Pattern STATUS_VALUE =
      Pattern.compile(
          "(?:\\b(?i:status|state)\\s*(?:=|:|(?i:becomes|changes?\\s+to)|->|→)\\s*`?([A-Z][A-Z_]+)`?"
              + "|`?([A-Z][A-Z_]+)`?\\s+(?i:status|state)\\b)");
  private static final Pattern MARKDOWN_MODEL_SECTION =
      Pattern.compile(
          "(?is)(?:^|\\n)\\s*-\\s+`[^`]+`\\s*(?:—|-)\\s*`([A-Z][A-Za-z0-9_]*)`"
              + "(.*?)(?=\\n\\s*-\\s+`[^`]+`\\s*(?:—|-)|\\z)");
  private static final Pattern LOCAL_LIFECYCLE =
      Pattern.compile("(?i)\\bstatus\\s+lifecycle\\s*:\\s*([^.;\\n]+)");
  private static final Set<String> NON_LIFECYCLE_STATUS_PREFIXES =
      Set.of("WHERE", "SET", "AND", "OR", "RETURNING", "SELECT", "UPDATE", "INSERT", "DELETE");
  private static final Set<String> NON_SUBJECT_WORDS =
      Set.of(
          "a",
          "an",
          "and",
          "assert",
          "check",
          "expect",
          "its",
          "render",
          "return",
          "set",
          "show",
          "test",
          "the",
          "their",
          "then",
          "this",
          "that",
          "transition",
          "verify",
          "whose");

  private PlanLifecycleValueContractGate() {}

  static void addViolations(List<PlannedTask> tasks, List<String> violations) {
    Map<String, Set<String>> byEntity = new LinkedHashMap<>();
    for (PlannedTask task : tasks) {
      if (task == null) continue;
      Matcher lifecycle = EXACT_LIFECYCLE.matcher(taskEvidence(task));
      while (lifecycle.find()) {
        Set<String> values = lifecycleValues(lifecycle.group(2));
        if (values.isEmpty()) continue;
        String entity = lifecycle.group(1).toLowerCase(Locale.ROOT);
        Set<String> prior = byEntity.putIfAbsent(entity, values);
        if (prior != null && !prior.equals(values)) {
          violations.add(
              task.id()
                  + ": "
                  + entity
                  + " declares canonical lifecycle values "
                  + values
                  + " but the assembled plan already defines "
                  + prior);
        }
      }
    }
    if (byEntity.isEmpty()) return;
    Set<String> everyCanonicalState =
        byEntity.values().stream().flatMap(Set::stream).collect(Collectors.toSet());
    for (PlannedTask task : tasks) {
      if (task == null) continue;
      String evidence = taskEvidence(task);
      addMarkdownModelLifecycleViolations(task, evidence, byEntity, violations);
      Matcher state = STATUS_VALUE.matcher(evidence);
      while (state.find()) {
        String value = state.group(1) != null ? state.group(1) : state.group(2);
        // The reverse form intentionally recognizes prose such as "ACTIVE status", but SQL also
        // contains structural phrases such as "WHERE status = ACTIVE". Those keywords are not
        // lifecycle values and must not poison the entity-scoped contract check.
        if (NON_LIFECYCLE_STATUS_PREFIXES.contains(value)) continue;
        String clause = localClause(evidence, state.start(), state.end());
        List<Map.Entry<String, Set<String>>> explicit =
            explicitlyBoundLifecycleEntities(clause, value, byEntity);
        if (explicit.size() == 1 && !explicit.getFirst().getValue().contains(value)) {
          violations.add(
              task.id()
                  + ": "
                  + explicit.getFirst().getKey()
                  + " uses lifecycle value "
                  + value
                  + " outside its canonical values "
                  + explicit.getFirst().getValue());
        } else if (explicit.isEmpty()
            && !everyCanonicalState.contains(value)
            && ((value.contains("_")
                    && clause.matches(
                        "(?is).*\\b(?:test|assert|expect|return|render|show|set|transition).*"))
                || (isNaturalLanguageTransition(state.group())
                    && !hasExplicitOtherStatusSubject(clause, value)))) {
          violations.add(
              task.id()
                  + ": uses undeclared lifecycle value "
                  + value
                  + " while the assembled plan defines explicit lifecycle vocabularies");
        }
      }
    }
  }

  private static void addMarkdownModelLifecycleViolations(
      PlannedTask task,
      String evidence,
      Map<String, Set<String>> byEntity,
      List<String> violations) {
    Matcher section = MARKDOWN_MODEL_SECTION.matcher(evidence);
    while (section.find()) {
      String entity = section.group(1).toLowerCase(Locale.ROOT);
      Set<String> canonical = byEntity.get(entity);
      if (canonical == null) continue;
      Matcher lifecycle = LOCAL_LIFECYCLE.matcher(section.group(2));
      while (lifecycle.find()) {
        Set<String> declared = lifecycleValues(lifecycle.group(1));
        if (declared.isEmpty() || declared.equals(canonical)) continue;
        violations.add(
            task.id()
                + ": "
                + entity
                + " model lifecycle values "
                + declared
                + " contradict canonical values "
                + canonical);
      }
    }
  }

  private static Set<String> lifecycleValues(String value) {
    Set<String> values = new LinkedHashSet<>();
    Matcher canonicalValue = CANONICAL_LIFECYCLE_VALUE.matcher(safe(value));
    while (canonicalValue.find()) values.add(canonicalValue.group());
    return java.util.Collections.unmodifiableSet(values);
  }

  private static boolean isNaturalLanguageTransition(String matchedStatus) {
    return safe(matchedStatus).matches("(?is).*(?:\\bbecomes\\b|\\bchanges?\\s+to\\b|->|→).*");
  }

  private static boolean hasExplicitOtherStatusSubject(String clause, String value) {
    Pattern subjectTransition =
        Pattern.compile(
            "(?is)\\b([A-Za-z][A-Za-z0-9_]*)"
                + "(?:response|dto)?\\s+(?:status|state)\\s*"
                + "(?:becomes|changes?\\s+to|->|→)\\s*`?"
                + Pattern.quote(value)
                + "`?\\b");
    Matcher subject = subjectTransition.matcher(clause);
    while (subject.find()) {
      if (!NON_SUBJECT_WORDS.contains(subject.group(1).toLowerCase(Locale.ROOT))) return true;
    }
    return false;
  }

  private static List<Map.Entry<String, Set<String>>> explicitlyBoundLifecycleEntities(
      String clause, String value, Map<String, Set<String>> byEntity) {
    return byEntity.entrySet().stream()
        .filter(
            entry -> {
              String entity = Pattern.quote(entry.getKey());
              String response = entity + "(?:response|dto)?";
              String forward =
                  "(?is).*\\b"
                      + response
                      + "\\b(?:\\s+(?:with|has|uses|whose))?\\s*(?:\\.|\\s+)"
                      + "(?:status|state)\\s*(?:=|:|becomes|changes?\\s+to|->|→)\\s*`?"
                      + Pattern.quote(value)
                      + "`?\\b.*";
              String reverse =
                  "(?is).*\\b`?"
                      + Pattern.quote(value)
                      + "`?\\s+"
                      + response
                      + "\\s*(?:\\.|\\s+)(?:status|state)\\b.*";
              return clause.matches(forward) || clause.matches(reverse);
            })
        .toList();
  }

  private static String taskEvidence(PlannedTask task) {
    return safe(task.title())
        + "\n"
        + safe(task.description())
        + "\n"
        + String.join("\n", task.hardConstraints() == null ? List.of() : task.hardConstraints());
  }

  private static String localClause(String evidence, int start, int end) {
    int clauseStart =
        Math.max(
            0, Math.max(evidence.lastIndexOf('\n', start), previousSentenceEnd(evidence, start)));
    int newlineEnd = evidence.indexOf('\n', end);
    int sentenceEnd = nextSentenceEnd(evidence, end);
    int clauseEnd = evidence.length();
    if (newlineEnd >= 0) clauseEnd = Math.min(clauseEnd, newlineEnd);
    if (sentenceEnd >= 0) clauseEnd = Math.min(clauseEnd, sentenceEnd + 1);
    return evidence.substring(clauseStart, clauseEnd);
  }

  private static int previousSentenceEnd(String evidence, int before) {
    int candidate = evidence.lastIndexOf('.', Math.max(0, before - 1));
    while (candidate >= 0) {
      if (isSentenceEnd(evidence, candidate)) return candidate;
      candidate = evidence.lastIndexOf('.', candidate - 1);
    }
    return -1;
  }

  private static int nextSentenceEnd(String evidence, int from) {
    int candidate = evidence.indexOf('.', from);
    while (candidate >= 0) {
      if (isSentenceEnd(evidence, candidate)) return candidate;
      candidate = evidence.indexOf('.', candidate + 1);
    }
    return -1;
  }

  private static boolean isSentenceEnd(String evidence, int period) {
    return period + 1 >= evidence.length() || Character.isWhitespace(evidence.charAt(period + 1));
  }
}
