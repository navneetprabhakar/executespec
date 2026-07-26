package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies scope-owner bindings emitted while individual hierarchical tasks are expanded. */
final class PlanScopeOwnershipReconciler {
  private static final Pattern OWNER_BINDING =
      Pattern.compile("([A-Za-z0-9_-]+) exclusively owns `([^`]+)`");

  private PlanScopeOwnershipReconciler() {}

  static void addViolations(Plan plan, List<String> violations) {
    if (plan == null || plan.tasks() == null) return;
    Map<String, List<ArtifactOwner>> byFile = new LinkedHashMap<>();
    for (PlannedTask task : plan.tasks()) {
      if (task == null) continue;
      for (String rawPath : task.touchesFiles() == null ? List.<String>of() : task.touchesFiles()) {
        String path = safe(rawPath).replace('\\', '/');
        String file = path.substring(path.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
        byFile
            .computeIfAbsent(file, ignored -> new ArrayList<>())
            .add(new ArtifactOwner(task.id(), path));
      }
    }
    Pattern mentionedFile = Pattern.compile("(?i)\\b([A-Za-z0-9_-]+\\.(?:tsx?|jsx?|py|java))\\b");
    for (PlannedTask task : plan.tasks()) {
      if (task == null) continue;
      String description = safe(task.description());
      String lower = description.toLowerCase(java.util.Locale.ROOT);
      boolean globalMutation =
          lower.matches(
              "(?s).*(?:update|modify|integrate|wrap|refine)\\s+(?:all|every)\\s+"
                  + "(?:existing\\s+)?(?:page|screen|component|file)s?\\b.*");
      Matcher mention = mentionedFile.matcher(description);
      while (mention.find()) {
        List<ArtifactOwner> owners =
            byFile.getOrDefault(mention.group(1).toLowerCase(java.util.Locale.ROOT), List.of());
        if (owners.size() != 1 || owners.getFirst().taskId().equals(task.id())) continue;
        boolean localMutation = assignsChangesToMention(description, mention);
        if (!globalMutation && !localMutation) continue;
        ArtifactOwner owner = owners.getFirst();
        violations.add(
            "Task description assigns changes to "
                + owner.path()
                + " even though "
                + owner.taskId()
                + " owns that artifact");
      }
    }
  }

  /**
   * Compacted task descriptions may put the whole task on one physical line. A mutation verb
   * elsewhere on that line must not turn "use the session from database.py" or "env.py already
   * configures" into cross-owner writes. Require a mutation verb to directly govern the mentioned
   * filename, or a passive mutation immediately after it.
   */
  private static boolean assignsChangesToMention(String description, Matcher mention) {
    int beforeStart = Math.max(0, mention.start() - 120);
    int afterEnd = Math.min(description.length(), mention.end() + 80);
    String before =
        description.substring(beforeStart, mention.start()).toLowerCase(java.util.Locale.ROOT);
    String after =
        description.substring(mention.end(), afterEnd).toLowerCase(java.util.Locale.ROOT);
    String mutation =
        "(?:update|modify|integrate|wrap|refine|handle|show|add|create|rewrite|replace)";
    boolean directedBefore = before.matches("(?s).*\\b" + mutation + "\\b[^.;:\\n]{0,80}$");
    boolean passiveAfter =
        after.matches(
            "(?s)^\\s*(?:`|\\*|\\)|:|-)*\\s*(?:must|should|will|is|are|to be)?\\s*"
                + "(?:be\\s+)?(?:updated|modified|integrated|wrapped|refined|handled|shown|added|"
                + "created|rewritten|replaced)\\b.*");
    return directedBefore || passiveAfter;
  }

  static Plan reconcile(Plan plan) {
    if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) return plan;
    Map<String, LinkedHashSet<String>> ownedPaths = new LinkedHashMap<>();
    for (PlannedTask task : plan.tasks()) {
      if (task == null) continue;
      String evidence =
          safe(task.description())
              + "\n"
              + String.join(
                  "\n", task.hardConstraints() == null ? List.of() : task.hardConstraints());
      Matcher matcher = OWNER_BINDING.matcher(evidence);
      while (matcher.find()) {
        ownedPaths
            .computeIfAbsent(matcher.group(1), ignored -> new LinkedHashSet<>())
            .add(matcher.group(2).replace('\\', '/'));
      }
      for (String rawPath : task.touchesFiles() == null ? List.<String>of() : task.touchesFiles()) {
        String path = safe(rawPath).replace('\\', '/');
        String domain = scopedArtifactDomain(path.toLowerCase(java.util.Locale.ROOT));
        if (domain == null
            || titleOwnsDomain(safe(task.title()).toLowerCase(java.util.Locale.ROOT), domain))
          continue;
        PlannedTask sibling =
            plan.tasks().stream()
                .filter(java.util.Objects::nonNull)
                .filter(candidate -> !java.util.Objects.equals(candidate.id(), task.id()))
                .filter(
                    candidate ->
                        path.toLowerCase(java.util.Locale.ROOT).contains("/pages/")
                            ? PlanSafetyEvidenceSupport.isFrontendOnlyTask(candidate)
                            : !PlanSafetyEvidenceSupport.isFrontendOnlyTask(candidate))
                .filter(
                    candidate ->
                        titleOwnsDomain(
                            safe(candidate.title()).toLowerCase(java.util.Locale.ROOT), domain))
                .findFirst()
                .orElse(null);
        if (sibling != null) {
          ownedPaths.computeIfAbsent(sibling.id(), ignored -> new LinkedHashSet<>()).add(path);
        }
      }
    }
    if (ownedPaths.isEmpty()) return plan;

    LinkedHashSet<String> reassigned =
        ownedPaths.values().stream()
            .flatMap(java.util.Collection::stream)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    List<PlannedTask> tasks = new ArrayList<>(plan.tasks().size());
    for (PlannedTask task : plan.tasks()) {
      if (task == null) {
        tasks.add(null);
        continue;
      }
      LinkedHashSet<String> files = new LinkedHashSet<>();
      LinkedHashSet<String> newlyOwned = ownedPaths.getOrDefault(task.id(), new LinkedHashSet<>());
      for (String path : task.touchesFiles() == null ? List.<String>of() : task.touchesFiles()) {
        String normalized = safe(path).replace('\\', '/');
        if (!reassigned.contains(normalized) || newlyOwned.contains(normalized)) files.add(path);
      }
      files.addAll(newlyOwned);
      List<String> constraints =
          new ArrayList<>(
              task.hardConstraints() == null ? List.<String>of() : task.hardConstraints());
      for (String path : newlyOwned) {
        String binding =
            "SCOPE OWNER CONTRACT: this task creates, tests, and maintains `"
                + path
                + "`; no sibling task owns that artifact.";
        if (!constraints.contains(binding)) constraints.add(binding);
      }
      tasks.add(copyTask(task, List.copyOf(files), constraints));
    }
    return copyPlan(plan, tasks);
  }

  private static String scopedArtifactDomain(String path) {
    Matcher model = Pattern.compile(".*/models/([a-z0-9_]+)\\.py$").matcher(path);
    if (model.matches()) {
      String value = model.group(1);
      return java.util.Set.of("__init__", "base").contains(value) ? null : value;
    }
    Matcher page = Pattern.compile(".*/pages/([a-z0-9_-]+)\\.tsx?$").matcher(path);
    if (page.matches()) {
      String value = page.group(1);
      return java.util.Set.of("notfound", "not_found", "index").contains(value) ? null : value;
    }
    return null;
  }

  private static boolean titleOwnsDomain(String title, String domain) {
    for (String token : domain.split("[_-]+")) {
      String singular =
          token.endsWith("ies")
              ? token.substring(0, token.length() - 3) + "y"
              : token.endsWith("s") && token.length() > 3
                  ? token.substring(0, token.length() - 1)
                  : token;
      if (singular.length() > 2 && title.matches("(?s).*\\b" + Pattern.quote(singular) + "s?\\b.*"))
        return true;
    }
    return false;
  }

  private static PlannedTask copyTask(
      PlannedTask source, List<String> files, List<String> constraints) {
    return PlannedTask.builder()
        .id(source.id())
        .title(source.title())
        .description(source.description())
        .complexity(source.complexity())
        .touchesFiles(files)
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

  private record ArtifactOwner(String taskId, String path) {}
}
