package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rejects acceptance journeys that skip an explicitly declared persisted lifecycle transition. */
final class PlanLifecycleJourneyGate {
  private static final Pattern LIFECYCLE =
      Pattern.compile(
          "(?i)\\b([A-Z][A-Za-z0-9_]*)\\.status\\s+lifecycle\\s+values\\s+are\\s+exactly"
              + "\\s+([A-Z][A-Z, ]+)");

  private PlanLifecycleJourneyGate() {}

  static void addViolations(Plan plan, List<String> violations) {
    if (plan == null || plan.tasks() == null) return;
    String planEvidence =
        plan.tasks().stream()
            .filter(java.util.Objects::nonNull)
            .map(PlanLifecycleJourneyGate::taskEvidence)
            .collect(java.util.stream.Collectors.joining("\n"));
    Map<String, Lifecycle> lifecycles = lifecycles(planEvidence);
    if (lifecycles.isEmpty()) return;
    for (PlannedTask task : plan.tasks()) {
      if (task == null || !isJourneyTask(task)) continue;
      String evidence = taskEvidence(task);
      for (Lifecycle lifecycle : lifecycles.values()) {
        for (String target : lifecycle.states()) {
          if (target.equals(lifecycle.initialState())) continue;
          Pattern immediateTarget =
              Pattern.compile(
                  "(?is)\\bcreate(?:s|d|ing)?\\s+(?:an?\\s+)?"
                      + Pattern.quote(lifecycle.entity())
                      + "\\b(.{0,320}?)\\bstatus\\s+(?:changes?\\s+to\\s+|becomes\\s+)?`?"
                      + Pattern.quote(target)
                      + "`?\\b");
          Matcher matcher = immediateTarget.matcher(evidence);
          while (matcher.find()) {
            String between = matcher.group(1).toLowerCase(Locale.ROOT);
            String transition = transitionVerb(target);
            if (between.matches(
                "(?s).*\\b" + Pattern.quote(transition) + "(?:e|es|ed|ing|ation)?\\b.*")) {
              continue;
            }
            violations.add(
                task.id()
                    + ": journey expects "
                    + lifecycle.entity()
                    + " status "
                    + target
                    + " immediately after create, but the canonical default is "
                    + lifecycle.initialState()
                    + " and no explicit "
                    + transition
                    + " transition is exercised");
          }
        }
      }
    }
  }

  private static Map<String, Lifecycle> lifecycles(String evidence) {
    Map<String, Lifecycle> result = new LinkedHashMap<>();
    Matcher matcher = LIFECYCLE.matcher(evidence);
    while (matcher.find()) {
      String entity = matcher.group(1);
      List<String> states =
          java.util.Arrays.stream(matcher.group(2).split("\\s*,\\s*"))
              .map(String::trim)
              .filter(value -> value.matches("[A-Z][A-Z_]+"))
              .distinct()
              .toList();
      if (states.size() < 2) continue;
      Pattern defaultState =
          Pattern.compile(
              "(?is)\\b"
                  + Pattern.quote(entity)
                  + "\\b.{0,260}?\\bstatus\\b.{0,120}?\\bdefault\\s*[=:]?\\s*`?([A-Z][A-Z_]+)`?");
      Matcher defaultMatcher = defaultState.matcher(evidence);
      if (!defaultMatcher.find() || !states.contains(defaultMatcher.group(1))) continue;
      result.put(
          entity.toLowerCase(Locale.ROOT), new Lifecycle(entity, defaultMatcher.group(1), states));
    }
    return Map.copyOf(result);
  }

  private static boolean isJourneyTask(PlannedTask task) {
    String identity =
        (safe(task.title())
                + " "
                + String.join(" ", task.touchesFiles() == null ? List.of() : task.touchesFiles()))
            .toLowerCase(Locale.ROOT);
    return identity.matches("(?s).*\\b(?:e2e|end-to-end|acceptance|playwright|journey)\\b.*");
  }

  private static String transitionVerb(String state) {
    String value = state.toLowerCase(Locale.ROOT);
    if (value.endsWith("tted") || value.endsWith("lled") || value.endsWith("rred")) {
      return value.substring(0, value.length() - 3);
    }
    if (value.endsWith("ed") && value.length() > 4) return value.substring(0, value.length() - 1);
    return value;
  }

  private static String taskEvidence(PlannedTask task) {
    return safe(task.title())
        + "\n"
        + safe(task.description())
        + "\n"
        + String.join("\n", task.hardConstraints() == null ? List.of() : task.hardConstraints());
  }

  private record Lifecycle(String entity, String initialState, List<String> states) {}
}
