package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.isFrontendOnlyTask;
import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Orders hierarchical expansion so consumers receive established producer contracts.
 *
 * <p>An outline contains only skeleton metadata. A frontend consumer expanded before its backend
 * producer cannot copy routes or DTOs and is forced to invent them; coherence is too late because
 * both descriptions already exist. This pass adds only dependency edges. It never creates tasks or
 * domain contracts.
 */
public final class PlanExpansionDependencyEnricher {
  private PlanExpansionDependencyEnricher() {}

  public record Result(Plan plan, int dependenciesAdded, List<String> summaries) {}

  public static Result enrich(Plan plan) {
    if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) {
      return new Result(plan, 0, List.of());
    }
    DependencyRepair cycleRepair = removeForwardCycleEdges(plan.tasks());
    List<PlannedTask> original = cycleRepair.tasks();
    Map<String, PlannedTask> byId =
        original.stream()
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toMap(PlannedTask::id, task -> task, (left, right) -> left));
    List<PlannedTask> backendProducers =
        original.stream()
            .filter(java.util.Objects::nonNull)
            .filter(Predicate.not(PlanSafetyEvidenceSupport::isFrontendOnlyTask))
            .filter(Predicate.not(PlanExpansionDependencyEnricher::isExpansionTestTask))
            .filter(PlanExpansionDependencyEnricher::isBackendImplementation)
            .toList();
    int added = cycleRepair.edgesRemoved();
    List<String> summaries = new ArrayList<>(cycleRepair.summaries());
    List<PlannedTask> enriched = new ArrayList<>(original.size());
    for (PlannedTask task : original) {
      if (task == null) {
        enriched.add(null);
        continue;
      }
      List<PlannedTask> requiredProducers =
          isExpansionTestTask(task)
              ? precedingImplementationTasks(original, task)
              : isFunctionalFrontendConsumer(task)
                  ? relevantBackendProducers(backendProducers, task)
                  : List.of();
      LinkedHashSet<String> dependencies =
          new LinkedHashSet<>(task.dependsOn() == null ? List.of() : task.dependsOn());
      for (PlannedTask producer : requiredProducers) {
        if (producer.id().equals(task.id())
            || dependencies.contains(producer.id())
            || createsCycle(task.id(), producer.id(), byId)) continue;
        dependencies.add(producer.id());
        added++;
        summaries.add(task.id() + " expands after producer " + producer.id());
      }
      PlannedTask enrichedTask =
          dependencies.equals(
                  new LinkedHashSet<>(task.dependsOn() == null ? List.of() : task.dependsOn()))
              ? task
              : withDependencies(task, List.copyOf(dependencies));
      enriched.add(enrichedTask);
      byId.put(enrichedTask.id(), enrichedTask);
    }
    DependencyRepair transitiveReduction = removeTransitivelyRedundantEdges(enriched);
    added += transitiveReduction.edgesRemoved();
    summaries.addAll(transitiveReduction.summaries());
    return added == 0
        ? new Result(plan, 0, List.of())
        : new Result(copyPlan(plan, transitiveReduction.tasks()), added, List.copyOf(summaries));
  }

  private record DependencyRepair(
      List<PlannedTask> tasks, int edgesRemoved, List<String> summaries) {}

  private static DependencyRepair removeForwardCycleEdges(List<PlannedTask> tasks) {
    Map<String, PlannedTask> byId =
        tasks.stream()
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toMap(PlannedTask::id, task -> task, (left, right) -> left));
    Map<String, Integer> order = new java.util.LinkedHashMap<>();
    for (int index = 0; index < tasks.size(); index++) {
      PlannedTask task = tasks.get(index);
      if (task != null) order.put(task.id(), index);
    }
    List<PlannedTask> repaired = new ArrayList<>(tasks.size());
    List<String> summaries = new ArrayList<>();
    int removed = 0;
    for (PlannedTask task : tasks) {
      if (task == null || task.dependsOn() == null || task.dependsOn().isEmpty()) {
        repaired.add(task);
        continue;
      }
      List<String> dependencies = new ArrayList<>();
      for (String dependency : task.dependsOn()) {
        boolean forwardEdge =
            order.getOrDefault(dependency, -1) > order.getOrDefault(task.id(), Integer.MAX_VALUE);
        if (forwardEdge && reaches(dependency, task.id(), byId, new LinkedHashSet<>())) {
          removed++;
          summaries.add(
              task.id()
                  + " removed cyclic forward dependency on "
                  + dependency
                  + "; preserve outline execution order");
        } else {
          dependencies.add(dependency);
        }
      }
      repaired.add(
          dependencies.equals(task.dependsOn()) ? task : withDependencies(task, dependencies));
    }
    return new DependencyRepair(List.copyOf(repaired), removed, List.copyOf(summaries));
  }

  /**
   * Keeps only dependency-frontier contracts in each expansion prompt.
   *
   * <p>If T9 depends on T7 and T7 already depends on T6→T3→T2, repeating every ancestor as a direct
   * T9 dependency does not add execution ordering. It does make the expander serialize the same
   * prerequisite contracts again until the shared 8k evidence cap truncates the useful API/UI
   * boundary. Preserve the nearest dependencies and remove only edges already reachable through a
   * sibling dependency.
   */
  private static DependencyRepair removeTransitivelyRedundantEdges(List<PlannedTask> tasks) {
    Map<String, PlannedTask> byId =
        tasks.stream()
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toMap(PlannedTask::id, task -> task, (left, right) -> left));
    List<PlannedTask> reduced = new ArrayList<>(tasks.size());
    List<String> summaries = new ArrayList<>();
    int removed = 0;
    for (PlannedTask task : tasks) {
      if (task == null || task.dependsOn() == null || task.dependsOn().size() < 2) {
        reduced.add(task);
        continue;
      }
      List<String> dependencies = new ArrayList<>();
      for (String candidate : task.dependsOn()) {
        boolean redundant =
            task.dependsOn().stream()
                .filter(other -> !other.equals(candidate))
                .anyMatch(other -> reaches(other, candidate, byId, new LinkedHashSet<>()));
        if (redundant) {
          removed++;
          summaries.add(
              task.id()
                  + " removed transitively redundant dependency on "
                  + candidate
                  + "; nearest producer contract remains direct");
        } else {
          dependencies.add(candidate);
        }
      }
      reduced.add(
          dependencies.equals(task.dependsOn()) ? task : withDependencies(task, dependencies));
    }
    return new DependencyRepair(List.copyOf(reduced), removed, List.copyOf(summaries));
  }

  private static List<PlannedTask> precedingImplementationTasks(
      List<PlannedTask> tasks, PlannedTask testTask) {
    List<PlannedTask> preceding = new ArrayList<>();
    for (PlannedTask candidate : tasks) {
      if (candidate == testTask || (candidate != null && candidate.id().equals(testTask.id())))
        break;
      if (candidate != null && !isExpansionTestTask(candidate)) preceding.add(candidate);
    }
    return List.copyOf(preceding);
  }

  private static List<PlannedTask> relevantBackendProducers(
      List<PlannedTask> backendProducers, PlannedTask consumer) {
    Set<String> consumerTokens = contractTokens(consumer);
    return backendProducers.stream()
        .filter(
            producer -> !java.util.Collections.disjoint(contractTokens(producer), consumerTokens))
        .toList();
  }

  private static Set<String> contractTokens(PlannedTask task) {
    Set<String> stopWords =
        Set.of(
            "add",
            "api",
            "app",
            "backend",
            "build",
            "component",
            "create",
            "crud",
            "endpoint",
            "frontend",
            "implement",
            "management",
            "model",
            "page",
            "router",
            "schema",
            "screen",
            "service",
            "shared",
            "test",
            "tests",
            "with");
    LinkedHashSet<String> tokens = new LinkedHashSet<>();
    String source =
        safe(task.title())
            + " "
            + String.join(" ", task.touchesFiles() == null ? List.of() : task.touchesFiles());
    for (String token : source.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
      if (token.length() >= 4 && !stopWords.contains(token)) {
        tokens.add(
            token.endsWith("s") && token.length() > 4
                ? token.substring(0, token.length() - 1)
                : token);
      }
    }
    return Set.copyOf(tokens);
  }

  private static boolean isFunctionalFrontendConsumer(PlannedTask task) {
    if (!isFrontendOnlyTask(task) || isExpansionTestTask(task)) return false;
    String evidence = evidence(task);
    boolean scaffoldOnly =
        evidence.matches("(?s).*\\b(?:scaffold|shell|shared ui|api client)\\b.*")
            && !evidence.matches(
                "(?s).*\\b(?:dashboard|screen|page|management|form|workflow|flow|warehouse|"
                    + "product|inventory|order|audit)\\b.*");
    return !scaffoldOnly;
  }

  private static boolean isBackendImplementation(PlannedTask task) {
    String evidence = evidence(task);
    return evidence.matches(
        "(?s).*(?:\\bbackend\\b|/api/|\\bapi\\b|endpoint|router|controller|service|"
            + "migration|alembic|src/main|app/models|app/schemas|app/routers).*");
  }

  private static boolean isExpansionTestTask(PlannedTask task) {
    List<String> files = task.touchesFiles() == null ? List.of() : task.touchesFiles();
    if (files.isEmpty()) return false;
    return files.stream()
        .map(value -> safe(value).toLowerCase(Locale.ROOT))
        .allMatch(
            value ->
                value.matches(".*(?:^|/)(?:tests?|__tests__|e2e|acceptance)(?:/|\\.|$).*")
                    || value.matches(".*(?:\\.spec|\\.test)\\.[a-z0-9]+$"));
  }

  private static String evidence(PlannedTask task) {
    return (safe(task.title())
            + "\n"
            + safe(task.description())
            + "\n"
            + String.join("\n", task.touchesFiles() == null ? List.of() : task.touchesFiles()))
        .toLowerCase(Locale.ROOT);
  }

  private static boolean createsCycle(
      String consumerId, String producerId, Map<String, PlannedTask> byId) {
    return reaches(producerId, consumerId, byId, new LinkedHashSet<>());
  }

  private static boolean reaches(
      String current, String target, Map<String, PlannedTask> byId, Set<String> visited) {
    if (current.equals(target)) return true;
    if (!visited.add(current)) return false;
    PlannedTask task = byId.get(current);
    if (task == null || task.dependsOn() == null) return false;
    for (String dependency : task.dependsOn()) {
      if (reaches(dependency, target, byId, visited)) return true;
    }
    return false;
  }

  private static PlannedTask withDependencies(PlannedTask task, List<String> dependencies) {
    return PlannedTask.builder()
        .id(task.id())
        .title(task.title())
        .description(task.description())
        .complexity(task.complexity())
        .touchesFiles(task.touchesFiles())
        .requiredArtifacts(task.requiredArtifacts())
        .dependsOn(dependencies)
        .hardConstraints(task.hardConstraints())
        .meetsRequirements(task.meetsRequirements())
        .taskType(task.taskType())
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
