package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Verifies explicit "from Tn" implementation-symbol claims against that producer task. */
final class PlanCrossTaskSymbolReferenceGate {
  private static final Pattern EXPLICIT_PRODUCER_REFERENCE =
      Pattern.compile(
          "\\b([A-Z][A-Za-z0-9_]*(?:Repository|Service|Client|Store|Gateway))"
              + "\\s*\\(\\s*from\\s+([A-Za-z0-9_-]+)\\s*\\)");

  private PlanCrossTaskSymbolReferenceGate() {}

  static void addViolations(Plan plan, List<String> violations) {
    if (plan == null || plan.tasks() == null) return;
    Map<String, PlannedTask> byId =
        plan.tasks().stream()
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toMap(PlannedTask::id, Function.identity(), (left, right) -> left));
    for (PlannedTask consumer : plan.tasks()) {
      if (consumer == null) continue;
      Matcher reference = EXPLICIT_PRODUCER_REFERENCE.matcher(evidence(consumer));
      while (reference.find()) {
        PlannedTask producer = byId.get(reference.group(2));
        if (producer == null || ownsSymbol(producer, reference.group(1))) continue;
        violations.add(
            consumer.id()
                + ": references "
                + reference.group(1)
                + " from "
                + reference.group(2)
                + ", but that task does not declare or own the symbol");
      }
    }
  }

  private static boolean ownsSymbol(PlannedTask task, String symbol) {
    if (Pattern.compile("\\b" + Pattern.quote(symbol) + "\\b").matcher(evidence(task)).find()) {
      return true;
    }
    String expectedStem = snakeCase(symbol);
    return (task.touchesFiles() == null ? List.<String>of() : task.touchesFiles())
        .stream()
            .map(path -> safe(path).replace('\\', '/').toLowerCase(Locale.ROOT))
            .map(path -> path.substring(path.lastIndexOf('/') + 1))
            .map(file -> file.contains(".") ? file.substring(0, file.lastIndexOf('.')) : file)
            .anyMatch(file -> file.equals(expectedStem));
  }

  private static String evidence(PlannedTask task) {
    return safe(task.title())
        + "\n"
        + safe(task.description())
        + "\n"
        + String.join("\n", task.hardConstraints() == null ? List.of() : task.hardConstraints());
  }

  private static String snakeCase(String value) {
    return safe(value)
        .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
        .replace('-', '_')
        .toLowerCase(Locale.ROOT);
  }
}
