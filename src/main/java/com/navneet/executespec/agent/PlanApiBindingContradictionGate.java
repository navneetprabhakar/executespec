package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanApiContractEvidence.normalizedPath;
import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rejects alternate call-site paths that contradict a task's explicit exact-path binding. */
final class PlanApiBindingContradictionGate {
  private static final Pattern OPERATION =
      Pattern.compile(
          "(?i)\\b(GET|POST|PUT|PATCH|DELETE)\\s+`?(/(?:api(?:/|\\b))?" + "[a-z0-9_{}?=&./:-]*)");
  private static final Pattern BACKTICK_PATH = Pattern.compile("`(/[^`\\s]+)`");

  private PlanApiBindingContradictionGate() {}

  static void addViolations(Plan plan, List<String> violations) {
    if (plan == null || plan.tasks() == null) return;
    for (PlannedTask task : plan.tasks()) {
      if (task == null || task.hardConstraints() == null) continue;
      Set<BoundOperation> bound = exactBindings(task.hardConstraints());
      if (bound.isEmpty()) continue;
      String description = safe(task.description());
      Matcher literal = BACKTICK_PATH.matcher(description);
      while (literal.find()) {
        String rawPath = trimPunctuation(literal.group(1));
        String path = normalizedPath(rawPath);
        if (bound.stream().anyMatch(candidate -> candidate.path().equals(path))) continue;
        List<BoundOperation> sameAction =
            bound.stream()
                .filter(candidate -> lastSegment(candidate.path()).equals(lastSegment(path)))
                .toList();
        if (sameAction.isEmpty() || isNegated(description, literal.start())) continue;
        violations.add(
            task.id()
                + ": call-site path "
                + rawPath
                + " contradicts exact API binding "
                + sameAction.stream()
                    .map(candidate -> candidate.method() + " " + display(candidate.path()))
                    .distinct()
                    .toList());
      }
    }
  }

  private static Set<BoundOperation> exactBindings(List<String> constraints) {
    LinkedHashSet<BoundOperation> operations = new LinkedHashSet<>();
    for (String constraint : constraints) {
      String normalized = safe(constraint).toLowerCase(Locale.ROOT);
      if (!normalized.contains("exact")
          || (!normalized.contains("path") && !normalized.contains("endpoint"))) continue;
      Matcher operation = OPERATION.matcher(constraint);
      while (operation.find()) {
        operations.add(
            new BoundOperation(
                operation.group(1).toUpperCase(Locale.ROOT),
                normalizedPath(trimPunctuation(operation.group(2)))));
      }
    }
    return Set.copyOf(operations);
  }

  private static boolean isNegated(String description, int pathStart) {
    String prefix =
        description.substring(Math.max(0, pathStart - 100), pathStart).toLowerCase(Locale.ROOT);
    return prefix.matches("(?s).*\\b(?:do not|don't|never|forbid(?:den)?|avoid)\\b[^.;\\n]*$");
  }

  private static String trimPunctuation(String path) {
    return safe(path).replaceAll("[.,;:]+$", "");
  }

  private static String lastSegment(String path) {
    String normalized = normalizedPath(path);
    int slash = normalized.lastIndexOf('/');
    return slash < 0 ? normalized : normalized.substring(slash + 1);
  }

  private static String display(String path) {
    return path.equals("/") ? "/api" : "/api" + path;
  }

  private record BoundOperation(String method, String path) {}
}
