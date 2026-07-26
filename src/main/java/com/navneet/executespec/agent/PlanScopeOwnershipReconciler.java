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
    Map<String, LinkedHashSet<String>> scopedOwnersByPath = new LinkedHashMap<>();
    for (PlannedTask task : plan.tasks()) {
      if (task == null) continue;
      for (String rawPath : task.touchesFiles() == null ? List.<String>of() : task.touchesFiles()) {
        String path = safe(rawPath).replace('\\', '/');
        if (declaresModelArtifactAsServiceLayer(task, path)) {
          violations.add("Task classifies model artifact " + path + " as a service-layer file");
        }
        String file = path.substring(path.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
        byFile
            .computeIfAbsent(file, ignored -> new ArrayList<>())
            .add(new ArtifactOwner(task.id(), path));
        if (isExclusivelyOwnedArtifact(path)) {
          scopedOwnersByPath
              .computeIfAbsent(
                  path.toLowerCase(java.util.Locale.ROOT), ignored -> new LinkedHashSet<>())
              .add(task.id());
        }
      }
    }
    scopedOwnersByPath.forEach(
        (path, owners) -> {
          if (owners.size() > 1 && !isExplicitSequentialPageComposition(plan, path, owners)) {
            violations.add(
                "Exclusive implementation artifact "
                    + path
                    + " is assigned to multiple tasks "
                    + owners);
          }
        });
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

  private static boolean isExclusivelyOwnedArtifact(String rawPath) {
    String path = safe(rawPath).replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
    return (path.matches(
                "(?s).*/(?:models?|entities)/[^/]+\\.(?:py|java|kt|ts|tsx|js|jsx|cs|go|rb|php)$")
            && !PlanArtifactOwnershipSupport.isPackageOrRegistryMetadata(path))
        || path.matches("(?s).*/pages/[^/]+\\.(?:tsx?|jsx?)$")
        || PlanSafetyMigrationSupport.isVersionMigrationPath(path);
  }

  /**
   * A later frontend task may deliberately mount a component into a page created by an earlier
   * task. That is a sequential integration hand-off, not competing page ownership, but only when
   * the generated plan says so explicitly: the later task must depend on the prior page owner and
   * name that exact owner in an {@code owned by Tn} clause. Ambiguous duplicate page edits remain
   * fail-closed, and this exception never applies to models/entities or migrations.
   */
  private static boolean isExplicitSequentialPageComposition(
      Plan plan, String path, LinkedHashSet<String> ownerIds) {
    if (!path.matches("(?s).*/pages/[^/]+\\.(?:tsx?|jsx?)$") || ownerIds.size() < 2) {
      return false;
    }
    List<String> orderedOwners = new ArrayList<>(ownerIds);
    for (int index = 1; index < orderedOwners.size(); index++) {
      String priorOwner = orderedOwners.get(index - 1);
      PlannedTask integratingTask = taskById(plan, orderedOwners.get(index));
      if (integratingTask == null || !integratingTask.dependsOn().contains(priorOwner))
        return false;
      String evidence =
          (safe(integratingTask.description())
                  + "\n"
                  + String.join("\n", integratingTask.hardConstraints()))
              .toLowerCase(java.util.Locale.ROOT);
      String explicitOwner = "owned by " + priorOwner.toLowerCase(java.util.Locale.ROOT);
      if (!evidence.contains(explicitOwner)) return false;
    }
    return true;
  }

  /**
   * Compacted task descriptions may put the whole task on one physical line. A mutation verb
   * elsewhere on that line must not turn "use the session from database.py" or "env.py already
   * configures" into cross-owner writes. Require a mutation verb to directly govern the mentioned
   * filename, or a passive mutation immediately after it.
   */
  private static boolean assignsChangesToMention(String description, Matcher mention) {
    int beforeStart = Math.max(0, mention.start() - 240);
    int afterEnd = Math.min(description.length(), mention.end() + 80);
    String before =
        description.substring(beforeStart, mention.start()).toLowerCase(java.util.Locale.ROOT);
    String after =
        description.substring(mention.end(), afterEnd).toLowerCase(java.util.Locale.ROOT);
    String mutation =
        "(?:update|modify|integrate|wrap|refine|handle|show|add|create|rewrite|replace)";
    boolean ownedDefinitionSection =
        before.matches(
            "(?s).*\\b(?:models?|entities|artifacts?)\\s+to\\s+"
                + "(?:create|define|implement)\\b.*");
    boolean directedBefore = before.matches("(?s).*\\b" + mutation + "\\b[^.;:\\n]{0,80}$");
    boolean passiveAfter =
        after.matches(
            "(?s)^\\s*(?:`|\\*|\\)|:|-)*\\s*(?:must|should|will|is|are|to be)?\\s*"
                + "(?:be\\s+)?(?:updated|modified|integrated|wrapped|refined|handled|shown|added|"
                + "created|rewritten|replaced)\\b.*");
    return ownedDefinitionSection || directedBefore || passiveAfter;
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
        PlannedTask target = taskById(plan, matcher.group(1));
        String path = matcher.group(2).replace('\\', '/');
        PlannedTask focusedModelOwner =
            path.toLowerCase(java.util.Locale.ROOT).contains("/models/")
                ? uniqueModelFocusedOwnerForArtifact(plan, path)
                : null;
        if (focusedModelOwner != null && !focusedModelOwner.id().equals(matcher.group(1))) {
          continue;
        }
        if (target == null || declaresModelArtifactAsServiceLayer(target, path)) continue;
        ownedPaths.computeIfAbsent(matcher.group(1), ignored -> new LinkedHashSet<>()).add(path);
      }
      for (String rawPath : task.touchesFiles() == null ? List.<String>of() : task.touchesFiles()) {
        String path = safe(rawPath).replace('\\', '/');
        String domain = scopedArtifactDomain(path.toLowerCase(java.util.Locale.ROOT));
        if (domain == null) continue;
        PlannedTask focusedModelOwner =
            path.toLowerCase(java.util.Locale.ROOT).contains("/models/")
                ? uniqueModelFocusedOwnerForArtifact(plan, path)
                : null;
        if (focusedModelOwner != null) {
          ownedPaths
              .computeIfAbsent(focusedModelOwner.id(), ignored -> new LinkedHashSet<>())
              .add(path);
          continue;
        }
        PlannedTask dependencyArtifactOwner =
            path.toLowerCase(java.util.Locale.ROOT).contains("/models/")
                ? uniqueModelFocusedDependencyOwner(plan, task, path)
                : null;
        if (dependencyArtifactOwner != null) {
          ownedPaths
              .computeIfAbsent(dependencyArtifactOwner.id(), ignored -> new LinkedHashSet<>())
              .add(path);
          continue;
        }
        if (titleOwnsDomain(safe(task.title()).toLowerCase(java.util.Locale.ROOT), domain))
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
                .filter(candidate -> !declaresModelArtifactAsServiceLayer(candidate, path))
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
      constraints.removeIf(
          constraint ->
              reassigned.stream()
                  .filter(path -> !newlyOwned.contains(path))
                  .anyMatch(
                      path ->
                          safe(constraint).startsWith("SCOPE OWNER CONTRACT:")
                              && safe(constraint).contains("`" + path + "`")));
      constraints.removeIf(
          constraint ->
              newlyOwned.stream()
                  .anyMatch(
                      path ->
                          (safe(constraint).startsWith("SCOPE OWNERSHIP CORRECTION")
                                  || safe(constraint).startsWith("MODEL OWNER CONTRACT"))
                              && safe(constraint).contains("`" + path + "`")));
      for (String path : newlyOwned) {
        String binding =
            "SCOPE OWNER CONTRACT: this task creates, tests, and maintains `"
                + path
                + "`; no sibling task owns that artifact.";
        if (!constraints.contains(binding)) constraints.add(binding);
      }
      tasks.add(
          copyTask(
              task,
              removeStaleGeneratedOwnershipText(task, newlyOwned, reassigned),
              List.copyOf(files),
              constraints));
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

  /**
   * A model-focused prerequisite wins over a downstream service/API task when it explicitly names
   * the concrete model file. This prevents a generic resource noun in a service title from stealing
   * persistence ownership after expansion. Ambiguous or unrelated predecessors remain untouched.
   */
  private static PlannedTask uniqueModelFocusedDependencyOwner(
      Plan plan, PlannedTask current, String path) {
    String filename = path.substring(path.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
    List<PlannedTask> candidates =
        plan.tasks().stream()
            .filter(java.util.Objects::nonNull)
            .filter(candidate -> !java.util.Objects.equals(candidate.id(), current.id()))
            .filter(candidate -> dependsTransitivelyOn(plan, current, candidate.id()))
            .filter(
                candidate ->
                    safe(candidate.title())
                        .toLowerCase(java.util.Locale.ROOT)
                        .matches(
                            "(?s).*\\b(?:models?|entities|persistence|database\\s+schema|"
                                + "domain\\s+schema)\\b.*"))
            .filter(
                candidate ->
                    safe(candidate.description())
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains(filename))
            .filter(candidate -> !declaresModelArtifactAsServiceLayer(candidate, path))
            .toList();
    return candidates.size() == 1 ? candidates.getFirst() : null;
  }

  /**
   * Resolves a concrete model artifact to one schema/persistence-focused task before considering
   * generic resource nouns in downstream service/API titles. The owner may already have lost the
   * path during an earlier semantic fallback, so the filename in its model-definition prose is also
   * evidence. This global choice prevents the two-pass cycle where T2 assigns a model to T3 while
   * T3 simultaneously assigns the same model back to its prerequisite T2.
   */
  static PlannedTask uniqueModelFocusedOwnerForArtifact(Plan plan, String path) {
    String normalizedPath = path.toLowerCase(java.util.Locale.ROOT);
    String filename = normalizedPath.substring(normalizedPath.lastIndexOf('/') + 1);
    List<PlannedTask> currentOwners =
        plan.tasks().stream()
            .filter(java.util.Objects::nonNull)
            .filter(
                candidate ->
                    (candidate.touchesFiles() == null
                            ? List.<String>of()
                            : candidate.touchesFiles())
                        .stream()
                            .map(
                                value ->
                                    safe(value)
                                        .replace('\\', '/')
                                        .toLowerCase(java.util.Locale.ROOT))
                            .anyMatch(normalizedPath::equals))
            .toList();
    List<PlannedTask> candidates =
        plan.tasks().stream()
            .filter(java.util.Objects::nonNull)
            .filter(
                candidate ->
                    safe(candidate.title())
                        .toLowerCase(java.util.Locale.ROOT)
                        .matches(
                            "(?s).*\\b(?:models?|entities|persistence|database(?:\\s+schema)?|"
                                + "domain\\s+schema)\\b.*"))
            .filter(candidate -> !declaresModelArtifactAsServiceLayer(candidate, path))
            .filter(
                candidate ->
                    currentOwners.contains(candidate)
                        || (!currentOwners.isEmpty()
                            && currentOwners.stream()
                                .allMatch(
                                    owner ->
                                        owner.id().equals(candidate.id())
                                            || dependsTransitivelyOn(plan, owner, candidate.id()))))
            .filter(
                candidate -> {
                  String evidence =
                      (safe(candidate.description())
                              + "\n"
                              + String.join(
                                  "\n",
                                  candidate.hardConstraints() == null
                                      ? List.of()
                                      : candidate.hardConstraints()))
                          .replace('\\', '/')
                          .toLowerCase(java.util.Locale.ROOT);
                  return evidence.contains(normalizedPath) || evidence.contains(filename);
                })
            .toList();
    return candidates.size() == 1 ? candidates.getFirst() : null;
  }

  static boolean isResolvedModelOwner(Plan plan, PlannedTask task, String path) {
    PlannedTask owner = uniqueModelFocusedOwnerForArtifact(plan, path);
    return owner != null && java.util.Objects.equals(owner.id(), task.id());
  }

  private static String removeStaleGeneratedOwnershipText(
      PlannedTask task, LinkedHashSet<String> newlyOwned, LinkedHashSet<String> reassigned) {
    String description = safe(task.description());
    for (String path : reassigned) {
      if (newlyOwned.contains(path)) continue;
      description =
          description.replaceAll(
              "(?is)\\s*This task owns[^.]*`" + Pattern.quote(path) + "`[^.]*\\.", "");
    }
    for (String path : newlyOwned) {
      String quotedPath = Pattern.quote(path);
      description =
          description.replaceAll(
              "(?is)\\s*SCOPE OWNERSHIP CORRECTION\\s*\\([^)]*\\)\\s*:\\s*"
                  + "[A-Za-z0-9_-]+\\s+exclusively owns\\s+`"
                  + quotedPath
                  + "`[^.]*\\.\\s*This task must not create or modify that artifact\\.",
              "");
      description =
          description.replaceAll(
              "(?i)canonical\\s+([A-Z][A-Za-z0-9_]*)\\s+schema\\s*\\("
                  + "(?:owned by\\s+[A-Za-z0-9_-]+\\s+in|consumed from\\s+"
                  + "[A-Za-z0-9_-]+'s)\\s+`"
                  + quotedPath
                  + "`\\)",
              "**$1** (`" + java.util.regex.Matcher.quoteReplacement(path) + "`)");
      description =
          description.replaceAll(
              "(?i)\\(`"
                  + quotedPath
                  + "`\\)\\s*(?:—|-)\\s*imported from\\s+`"
                  + quotedPath
                  + "`\\s*\\(owned by\\s+[A-Za-z0-9_-]+\\)",
              "(`"
                  + java.util.regex.Matcher.quoteReplacement(path)
                  + "`) — define in this canonical model artifact");
    }
    description =
        description.replaceAll(
            "(?i)\\(single file, importing from sibling-owned model files\\)",
            "(package registry importing the concrete model files)");
    description =
        description.replaceAll(
            "(?i)Models to create in (`[^`]+/(?:__init__|index)\\.[a-z0-9]+`)"
                + "\\s*\\(single file, all models\\)\\s*:",
            "Models to define and export through $1 (concrete model files follow):");
    return description.trim();
  }

  private static boolean dependsTransitivelyOn(Plan plan, PlannedTask task, String candidateId) {
    LinkedHashSet<String> pending =
        new LinkedHashSet<>(task.dependsOn() == null ? List.<String>of() : task.dependsOn());
    LinkedHashSet<String> visited = new LinkedHashSet<>();
    while (!pending.isEmpty()) {
      String dependency = pending.removeFirst();
      if (!visited.add(dependency)) continue;
      if (java.util.Objects.equals(dependency, candidateId)) return true;
      PlannedTask dependencyTask = taskById(plan, dependency);
      if (dependencyTask != null && dependencyTask.dependsOn() != null) {
        pending.addAll(dependencyTask.dependsOn());
      }
    }
    return false;
  }

  private static PlannedTask taskById(Plan plan, String taskId) {
    return plan.tasks().stream()
        .filter(java.util.Objects::nonNull)
        .filter(task -> java.util.Objects.equals(task.id(), taskId))
        .findFirst()
        .orElse(null);
  }

  private static boolean declaresModelArtifactAsServiceLayer(PlannedTask task, String rawPath) {
    String path = safe(rawPath).replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
    if (!path.matches("(?s).*/models/[^/]+\\.(?:py|java|kt|ts|tsx|js|jsx|cs|go|rb|php)$")) {
      return false;
    }
    String file = path.substring(path.lastIndexOf('/') + 1);
    String evidence =
        safe(task.description()).toLowerCase(java.util.Locale.ROOT).replace('\\', '/');
    int mention = Math.max(evidence.indexOf(path), evidence.indexOf(file));
    if (mention < 0) {
      int slash = path.lastIndexOf('/') + 1;
      int dot = path.lastIndexOf('.');
      if (slash > 0 && dot > slash) {
        String prefix = path.substring(0, slash);
        String stem = path.substring(slash, dot);
        String extension = path.substring(dot);
        Matcher groupedPath =
            Pattern.compile(
                    Pattern.quote(prefix)
                        + "\\{[^}]*\\b"
                        + Pattern.quote(stem)
                        + "\\b[^}]*}"
                        + Pattern.quote(extension))
                .matcher(evidence);
        if (groupedPath.find()) mention = groupedPath.start();
      }
    }
    if (mention < 0) return false;
    int clauseEnd = Math.min(evidence.length(), mention + 320);
    String clause = evidence.substring(mention, clauseEnd);
    return clause.matches(
        "(?s).*\\b(?:is|are)\\s+(?:an?\\s+|the\\s+)?service[- ]layer\\s+files?\\b.*");
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
      PlannedTask source, String description, List<String> files, List<String> constraints) {
    return PlannedTask.builder()
        .id(source.id())
        .title(source.title())
        .description(description)
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
