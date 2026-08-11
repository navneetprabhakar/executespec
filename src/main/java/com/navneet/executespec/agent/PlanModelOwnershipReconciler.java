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

/** Closes model-definition ownership without discarding schema details from migration consumers. */
final class PlanModelOwnershipReconciler {
  private static final Pattern MODEL_ARTIFACT =
      Pattern.compile(
          "(?i)(?:^|/)(?:models?|entities)/([a-z0-9_-]+)\\.(?:py|java|kt|ts|tsx|js|jsx|cs|go|rb|php)$");
  private static final Pattern BOLD_MODEL_DEFINITION =
      Pattern.compile("\\*\\*([A-Z][A-Za-z0-9_]*)\\*\\*\\s*(—|-|:)");
  private static final Set<String> NON_DOMAIN_ARTIFACTS =
      Set.of("__init__", "base", "index", "model", "models", "entity", "entities");

  private PlanModelOwnershipReconciler() {}

  static Plan reconcile(Plan plan) {
    if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) return plan;
    Map<String, List<ModelOwner>> ownersByModel = ownersByModel(plan.tasks());
    if (ownersByModel.isEmpty()) return plan;

    List<PlannedTask> tasks = new ArrayList<>(plan.tasks().size());
    for (PlannedTask task : plan.tasks()) {
      if (task == null) {
        tasks.add(null);
        continue;
      }
      String description = safe(task.description());
      Matcher definition = BOLD_MODEL_DEFINITION.matcher(description);
      StringBuffer rewritten = new StringBuffer(description.length());
      LinkedHashSet<ModelOwner> foreignOwners = new LinkedHashSet<>();
      int definitionCount = 0;
      int foreignDefinitionCount = 0;
      while (definition.find()) {
        definitionCount++;
        String modelKey = modelKey(definition.group(1));
        List<ModelOwner> owners = ownersByModel.getOrDefault(modelKey, List.of());
        if (owners.size() != 1 || owners.getFirst().taskId().equals(task.id())) continue;
        if (!definitionVerbGoverns(description, definition.start())) continue;
        ModelOwner owner = owners.getFirst();
        foreignOwners.add(owner);
        foreignDefinitionCount++;
        String replacement =
            "canonical "
                + definition.group(1)
                + " schema (owned by "
                + owner.taskId()
                + " in `"
                + owner.path()
                + "`) "
                + definition.group(2);
        definition.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
      }
      if (foreignOwners.isEmpty()) {
        tasks.add(task);
        continue;
      }
      definition.appendTail(rewritten);
      String correctedDescription = rewritten.toString();
      if (definitionCount == foreignDefinitionCount) {
        correctedDescription =
            correctedDescription.replaceAll(
                "(?i)\\b(?:define|create|implement)\\s+(?:these|the following)\\s+models?"
                    + "(?:\\s+for\\s+the\\s+migration)?\\s*:",
                "Consume these canonical model schemas:");
      }
      correctedDescription =
          correctedDescription.replaceAll(
              "(?i)\\b(?:define|create|implement)\\s+(?:the\\s+)?(?=canonical\\s+[A-Z])",
              "Consume ");
      List<String> constraints =
          new ArrayList<>(task.hardConstraints() == null ? List.of() : task.hardConstraints());
      for (ModelOwner owner : foreignOwners) {
        String binding =
            "MODEL OWNER CONTRACT (binding): "
                + owner.taskId()
                + " exclusively defines and implements "
                + owner.displayName()
                + " in `"
                + owner.path()
                + "`; this task consumes that canonical schema and must not create or redefine"
                + " its model artifact.";
        if (!constraints.contains(binding)) constraints.add(binding);
      }
      tasks.add(copyTask(task, correctedDescription, constraints));
    }
    return copyPlan(plan, tasks);
  }

  private static Map<String, List<ModelOwner>> ownersByModel(List<PlannedTask> tasks) {
    Map<String, List<ModelOwner>> owners = new LinkedHashMap<>();
    for (PlannedTask task : tasks) {
      if (task == null) continue;
      for (String rawPath : task.touchesFiles() == null ? List.<String>of() : task.touchesFiles()) {
        String path = safe(rawPath).replace('\\', '/');
        Matcher artifact = MODEL_ARTIFACT.matcher(path);
        if (!artifact.find()) continue;
        String key = modelKey(artifact.group(1));
        if (NON_DOMAIN_ARTIFACTS.contains(key)) continue;
        owners
            .computeIfAbsent(key, ignored -> new ArrayList<>())
            .add(new ModelOwner(task.id(), path, displayName(artifact.group(1))));
      }
    }
    return owners;
  }

  private static boolean definitionVerbGoverns(String description, int definitionStart) {
    String lead =
        description
            .substring(Math.max(0, definitionStart - 240), definitionStart)
            .toLowerCase(Locale.ROOT);
    return lead.matches("(?s).*\\b(?:define|create|implement|model)s?\\b.*");
  }

  private static String modelKey(String value) {
    return value
        .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
        .replace('-', '_')
        .toLowerCase(Locale.ROOT);
  }

  private static String displayName(String artifactStem) {
    StringBuilder result = new StringBuilder();
    for (String part : artifactStem.split("[_-]+")) {
      if (part.isBlank()) continue;
      if (!result.isEmpty()) result.append(' ');
      result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
    }
    return result.toString();
  }

  private static PlannedTask copyTask(
      PlannedTask source, String description, List<String> constraints) {
    return PlannedTask.builder()
        .id(source.id())
        .title(source.title())
        .description(description)
        .complexity(source.complexity())
        .touchesFiles(source.touchesFiles())
        .requiredArtifacts(source.requiredArtifacts())
        .dependsOn(source.dependsOn())
        .hardConstraints(constraints)
        .meetsRequirements(source.meetsRequirements())
        .taskType(source.taskType())
        .build();
  }

  private static Plan copyPlan(Plan source, List<PlannedTask> tasks) {
    return Plan.builder()
        .planTitle(source.planTitle())
        .planSummary(source.planSummary())
        .projectType(source.projectType())
        .buildCommand(source.buildCommand())
        .smokeTestStrategy(source.smokeTestStrategy())
        .runtimeSurface(source.runtimeSurface())
        .tasks(tasks)
        .classification(source.classification())
        .requirements(source.requirements())
        .build();
  }

  private record ModelOwner(String taskId, String path, String displayName) {}
}
