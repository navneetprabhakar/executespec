package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Closes marker-only Python package/module namespace collisions without weakening validation. */
final class PlanPythonNamespaceLayoutReconciler {
  private PlanPythonNamespaceLayoutReconciler() {}

  static Plan reconcile(Plan plan) {
    if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) return plan;
    Map<String, String> canonicalPaths = new LinkedHashMap<>();
    Map<String, List<PlannedTask>> pathOwners = new LinkedHashMap<>();
    for (PlannedTask task : plan.tasks()) {
      if (task == null || task.touchesFiles() == null) continue;
      for (String path : task.touchesFiles()) {
        String normalizedPath = normalized(path);
        canonicalPaths.putIfAbsent(normalizedPath, canonical(path));
        pathOwners.computeIfAbsent(normalizedPath, ignored -> new ArrayList<>()).add(task);
      }
    }

    Map<String, NamespaceLayout> markerOnlyPackages = new LinkedHashMap<>();
    for (String module : canonicalPaths.keySet()) {
      if (!module.endsWith(".py") || module.endsWith("/__init__.py")) continue;
      String packagePrefix = module.substring(0, module.length() - 3) + "/";
      List<String> packagePaths =
          canonicalPaths.keySet().stream().filter(path -> path.startsWith(packagePrefix)).toList();
      if (packagePaths.isEmpty()
          || packagePaths.stream()
              .anyMatch(path -> !isProvenMarker(path, packagePrefix, pathOwners.get(path)))) {
        continue;
      }
      String canonicalModule = canonicalPaths.get(module);
      markerOnlyPackages.put(
          module,
          new NamespaceLayout(
              canonicalModule,
              canonicalModule.substring(0, canonicalModule.length() - 3) + "/",
              Set.copyOf(packagePaths)));
    }
    if (markerOnlyPackages.isEmpty()) return plan;

    List<PlannedTask> tasks = new ArrayList<>(plan.tasks().size());
    for (PlannedTask task : plan.tasks()) {
      if (task == null) {
        tasks.add(null);
        continue;
      }
      LinkedHashSet<String> files =
          new LinkedHashSet<>(task.touchesFiles() == null ? List.of() : task.touchesFiles());
      List<String> constraints =
          new ArrayList<>(task.hardConstraints() == null ? List.of() : task.hardConstraints());
      String description = safe(task.description());
      for (Map.Entry<String, NamespaceLayout> entry : markerOnlyPackages.entrySet()) {
        String normalizedModule = entry.getKey();
        NamespaceLayout layout = entry.getValue();
        String module = layout.canonicalModule();
        boolean ownsModule =
            files.stream()
                .map(PlanPythonNamespaceLayoutReconciler::normalized)
                .anyMatch(normalizedModule::equals);
        boolean ownedMarker =
            files.removeIf(path -> layout.normalizedMarkerPaths().contains(normalized(path)));
        if (!ownsModule && !ownedMarker) continue;
        if (ownedMarker) files.add(parentPackageMarker(module));
        description =
            replaceOwnedMarkers(description, layout, canonicalPaths, parentPackageMarker(module));
        String binding =
            "PYTHON NAMESPACE CONTRACT (binding): use `"
                + module
                + "` as the canonical module; do not create the shadow package `"
                + layout.canonicalPackagePrefix()
                + "`.";
        if (!constraints.contains(binding)) constraints.add(binding);
      }
      tasks.add(copyTask(task, description, List.copyOf(files), List.copyOf(constraints)));
    }
    return copyPlan(plan, tasks);
  }

  private static boolean isProvenMarker(String path, String prefix, List<PlannedTask> pathOwners) {
    if (path.equals(prefix + "__init__.py")) return true;
    if (!path.equals(prefix + "base.py") || pathOwners == null || pathOwners.isEmpty())
      return false;
    return pathOwners.stream().allMatch(task -> explicitlyDefinesMarkerOnlyBase(path, task));
  }

  private static boolean explicitlyDefinesMarkerOnlyBase(String normalizedPath, PlannedTask task) {
    String evidence =
        (safe(task.title())
                + "\n"
                + safe(task.description())
                + "\n"
                + String.join(
                    "\n", task.hardConstraints() == null ? List.of() : task.hardConstraints()))
            .toLowerCase(Locale.ROOT)
            .replace('\\', '/');
    String fileName = normalizedPath.substring(normalizedPath.lastIndexOf('/') + 1);
    String markerMeaning =
        "(?:marker-only|marker only|empty package marker|package marker|compatibility marker|"
            + "contains no classes|no runtime definitions|no executable code)";
    String quotedFile = java.util.regex.Pattern.quote(fileName);
    return evidence.matches("(?s).*" + quotedFile + ".{0,120}" + markerMeaning + ".*")
        || evidence.matches(
            "(?s).*(?:marker-only|marker only|empty package marker|compatibility marker)"
                + ".{0,40}"
                + quotedFile
                + ".*");
  }

  private static String replaceOwnedMarkers(
      String description,
      NamespaceLayout layout,
      Map<String, String> canonicalPaths,
      String replacement) {
    String reconciled = description;
    for (String marker : layout.normalizedMarkerPaths()) {
      String canonicalMarker = canonicalPaths.get(marker);
      if (canonicalMarker != null) reconciled = reconciled.replace(canonicalMarker, replacement);
    }
    return reconciled;
  }

  private static String parentPackageMarker(String module) {
    int slash = module.lastIndexOf('/');
    return slash < 0 ? "__init__.py" : module.substring(0, slash + 1) + "__init__.py";
  }

  private static String normalized(String path) {
    return canonical(path).toLowerCase(Locale.ROOT);
  }

  private static String canonical(String path) {
    return safe(path).replace('\\', '/');
  }

  private record NamespaceLayout(
      String canonicalModule, String canonicalPackagePrefix, Set<String> normalizedMarkerPaths) {}

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
}
