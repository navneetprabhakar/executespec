package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rejects executable model definitions placed outside the task owning the concrete model file. */
final class PlanModelDefinitionOwnershipGate {
  private static final Pattern MODEL_PATH =
      Pattern.compile(
          "(?i)(?:^|/)(?:models?|entities)/([a-z0-9_-]+)\\."
              + "(?:py|java|kt|ts|tsx|js|jsx|cs|go|rb|php)$");
  private static final Pattern MARKDOWN_DEFINITION =
      Pattern.compile(
          "(?is)(?:^|\\s+-\\s+)\\*\\*([A-Z][A-Za-z0-9_]*)\\*\\*"
              + "(?:\\s*\\(`[^`]+`\\))?\\s*(?:—|-)\\s*(.*?)"
              + "(?=\\s+-\\s+\\*\\*[A-Z][A-Za-z0-9_]*\\*\\*|\\z)");
  private static final Pattern FIELD = Pattern.compile("`([a-z_][a-z0-9_]*)`");

  private PlanModelDefinitionOwnershipGate() {}

  static void addViolations(Plan plan, List<String> violations) {
    if (plan == null || plan.tasks() == null) return;
    Map<String, List<Owner>> ownersByStem = ownersByStem(plan.tasks());
    for (PlannedTask task : plan.tasks()) {
      if (task == null) continue;
      Matcher definition = MARKDOWN_DEFINITION.matcher(safe(task.description()));
      while (definition.find()) {
        if (fieldCount(definition.group(2)) < 2) continue;
        String declaredStem = snakeCase(definition.group(1));
        Owner owner = uniqueOwner(declaredStem, ownersByStem);
        if (owner == null || owner.taskId().equals(task.id())) continue;
        violations.add(
            task.id()
                + ": description defines model "
                + definition.group(1)
                + " even though "
                + owner.taskId()
                + " uniquely owns "
                + owner.path());
      }
    }
  }

  private static Map<String, List<Owner>> ownersByStem(List<PlannedTask> tasks) {
    Map<String, List<Owner>> owners = new LinkedHashMap<>();
    for (PlannedTask task : tasks) {
      if (task == null || task.touchesFiles() == null) continue;
      for (String rawPath : task.touchesFiles()) {
        String path = safe(rawPath).replace('\\', '/');
        Matcher model = MODEL_PATH.matcher(path);
        if (!model.find() || PlanArtifactOwnershipSupport.isPackageOrRegistryMetadata(path)) {
          continue;
        }
        owners
            .computeIfAbsent(model.group(1).toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
            .add(new Owner(task.id(), path));
      }
    }
    return owners;
  }

  private static Owner uniqueOwner(String declaredStem, Map<String, List<Owner>> ownersByStem) {
    List<Owner> exact = ownersByStem.getOrDefault(declaredStem, List.of());
    if (exact.size() == 1) return exact.getFirst();
    if (!exact.isEmpty()) return null;

    Set<Owner> suffixMatches = new LinkedHashSet<>();
    ownersByStem.forEach(
        (artifactStem, owners) -> {
          if (declaredStem.endsWith("_" + artifactStem)) suffixMatches.addAll(owners);
        });
    return suffixMatches.size() == 1 ? suffixMatches.iterator().next() : null;
  }

  private static int fieldCount(String body) {
    Set<String> fields = new LinkedHashSet<>();
    Matcher field = FIELD.matcher(safe(body));
    while (field.find()) fields.add(field.group(1).toLowerCase(Locale.ROOT));
    return fields.size();
  }

  private static String snakeCase(String value) {
    return safe(value)
        .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
        .replace('-', '_')
        .toLowerCase(Locale.ROOT);
  }

  private record Owner(String taskId, String path) {}
}
