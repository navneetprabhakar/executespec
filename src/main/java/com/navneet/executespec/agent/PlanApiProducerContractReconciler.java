package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanApiContractEvidence.derivedRouterPrefix;
import static com.navneet.executespec.agent.PlanApiContractEvidence.displayPath;
import static com.navneet.executespec.agent.PlanApiContractEvidence.extract;
import static com.navneet.executespec.agent.PlanApiContractEvidence.mergeEvidence;
import static com.navneet.executespec.agent.PlanApiContractEvidence.normalizedPath;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.isFrontendOnlyTask;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.isTestOnlyTask;
import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.navneet.executespec.agent.PlanApiContractEvidence.ApiEvidence;

/** Adds consumer-required operations to the unique backend task that owns their route prefix. */
final class PlanApiProducerContractReconciler {
  private PlanApiProducerContractReconciler() {}

  static Plan reconcileMissingOperations(Plan plan) {
    if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) return plan;
    List<PlannedTask> backendTasks =
        plan.tasks().stream()
            .filter(java.util.Objects::nonNull)
            .filter(task -> !isFrontendOnlyTask(task) && !isTestOnlyTask(task))
            .filter(PlanApiContractEvidence::isImplementationProducerTask)
            .toList();
    List<ApiEvidence> producers =
        mergeEvidence(backendTasks.stream().flatMap(task -> extract(task).stream()).toList());
    java.util.Map<String, LinkedHashSet<String>> bindingsByOwner = new java.util.LinkedHashMap<>();
    for (PlannedTask consumerTask : plan.tasks()) {
      if (consumerTask == null
          || (!isFrontendOnlyTask(consumerTask) && !isTestOnlyTask(consumerTask))) continue;
      for (ApiEvidence consumer : extractConsumerEvidence(consumerTask)) {
        if (hasExactProducer(producers, consumer)) continue;
        List<PlannedTask> owners =
            backendTasks.stream()
                .filter(candidate -> ownsRoutePrefix(candidate, consumer))
                .toList();
        if (owners.size() != 1) continue;
        bindingsByOwner
            .computeIfAbsent(owners.getFirst().id(), ignored -> new LinkedHashSet<>())
            .add(consumer.method() + " " + displayPath(consumer.path()));
      }
    }
    if (bindingsByOwner.isEmpty()) return plan;
    List<PlannedTask> tasks = new ArrayList<>(plan.tasks().size());
    for (PlannedTask task : plan.tasks()) {
      LinkedHashSet<String> operations = task == null ? null : bindingsByOwner.get(task.id());
      if (task == null || operations == null || operations.isEmpty()) {
        tasks.add(task);
        continue;
      }
      String binding =
          "API PRODUCER CONTRACTS (binding; implement every operation): "
              + String.join(", ", operations)
              + ".";
      String description =
          TaskExpansionTextNormalizer.boundTextPreservingFinalContract(
              safe(task.description()) + "\n\n" + binding, TaskExpanderAgent.MAX_DESCRIPTION_CHARS);
      tasks.add(
          copyTask(
              task,
              description,
              bindProducerContract(task.hardConstraints(), operations, binding)));
    }
    return copyPlan(plan, tasks);
  }

  private static boolean hasExactProducer(List<ApiEvidence> producers, ApiEvidence consumer) {
    return producers.stream()
        .anyMatch(
            producer ->
                producer.method().equals(consumer.method())
                    && producer.path().equals(consumer.path()));
  }

  private static boolean ownsRoutePrefix(PlannedTask task, ApiEvidence consumer) {
    String normalizedConsumerPath = consumer.path();
    String prefix = derivedRouterPrefix(task);
    if (prefix != null) {
      String normalizedPrefix = normalizedPath(prefix);
      if (normalizedConsumerPath.equals(normalizedPrefix)
          || normalizedConsumerPath.startsWith(normalizedPrefix + "/")) return true;
    }
    String routeFamily = routeFamily(normalizedConsumerPath);
    if (consumer.method().equals("GET")
        && extract(task).stream()
            .map(ApiEvidence::path)
            .map(PlanApiProducerContractReconciler::routeFamily)
            .anyMatch(routeFamily::equals)) return true;
    // A generated backend task may carry the authoritative public path without restating the
    // method (for example "exact paths: /api/adjustments, /api/transfers"). Bind the consumer's
    // method only when that exact path is present and this is the unique implementation owner.
    String evidence =
        safe(task.title())
            + "\n"
            + safe(task.description())
            + "\n"
            + String.join(
                "\n", task.hardConstraints() == null ? List.of() : task.hardConstraints());
    if (PlanSafetyEvidenceSupport.extractApiEndpoints(evidence).stream()
        .map(PlanApiContractEvidence::normalizedPath)
        .anyMatch(normalizedConsumerPath::equals)) return true;
    // Service-oriented plans sometimes allocate a resource implementation without a router file.
    // The resource noun in the title/file is a stronger ownership signal than an incidental route
    // mention in another task. The outer caller requires exactly one match before binding.
    Set<String> ownerTokens =
        PlanApiContractEvidence.domainTokens(
            safe(task.title())
                + " "
                + String.join(" ", task.touchesFiles() == null ? List.of() : task.touchesFiles()));
    Set<String> familyTokens = PlanApiContractEvidence.domainTokens(routeFamily);
    return consumer.method().equals("GET")
        && !familyTokens.isEmpty()
        && PlanApiContractEvidence.overlap(ownerTokens, familyTokens) == familyTokens.size();
  }

  private static String routeFamily(String path) {
    String normalized = normalizedPath(path);
    int parameter = normalized.indexOf("/{");
    return parameter < 0 ? normalized : normalized.substring(0, parameter);
  }

  private static List<ApiEvidence> extractConsumerEvidence(PlannedTask task) {
    List<String> explicitConstraints =
        (task.hardConstraints() == null ? List.<String>of() : task.hardConstraints())
            .stream().filter(value -> !safe(value).startsWith("API contract binding:")).toList();
    return extract(copyTask(task, safe(task.description()), explicitConstraints));
  }

  private static List<String> bindProducerContract(
      List<String> existing, Set<String> operations, String binding) {
    List<String> constraints = new ArrayList<>(existing == null ? List.<String>of() : existing);
    List<String> paths =
        operations.stream()
            .map(operation -> operation.substring(operation.indexOf(' ') + 1))
            .toList();
    for (int index = 0; index < constraints.size(); index++) {
      String constraint = safe(constraints.get(index));
      if (!paths.stream().allMatch(constraint::contains)) continue;
      if (!constraint.contains(binding)) constraints.set(index, constraint + " " + binding);
      return constraints;
    }
    if (!constraints.contains(binding)) constraints.add(binding);
    return constraints;
  }

  private static PlannedTask copyTask(
      PlannedTask source, String description, List<String> constraints) {
    return PlannedTask.builder()
        .id(source.id())
        .title(source.title())
        .description(description)
        .complexity(source.complexity())
        .touchesFiles(source.touchesFiles())
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
