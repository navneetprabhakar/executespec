package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanApiContractEvidence.displayPath;
import static com.navneet.executespec.agent.PlanApiContractEvidence.domainTokens;
import static com.navneet.executespec.agent.PlanApiContractEvidence.extract;
import static com.navneet.executespec.agent.PlanApiContractEvidence.mergeEvidence;
import static com.navneet.executespec.agent.PlanApiContractEvidence.overlap;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.isFrontendOnlyTask;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.isTestOnlyTask;
import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.navneet.executespec.agent.PlanApiContractEvidence.ApiEvidence;

/** Promotes only requirement-owned query parameters onto query-capable collection operations. */
final class PlanApiRequirementQueryPromoter {
  private PlanApiRequirementQueryPromoter() {}

  static Plan reconcile(Plan plan) {
    if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) return plan;
    Map<String, String> requirementTextById = requirementTextById(plan);
    if (requirementTextById.isEmpty()) return plan;
    Map<String, PlannedTask> taskById =
        plan.tasks().stream()
            .filter(java.util.Objects::nonNull)
            .collect(
                Collectors.toMap(
                    PlannedTask::id,
                    Function.identity(),
                    (left, right) -> left,
                    java.util.LinkedHashMap::new));
    List<ApiEvidence> producers =
        mergeEvidence(
            plan.tasks().stream()
                .filter(java.util.Objects::nonNull)
                .filter(task -> !isFrontendOnlyTask(task) && !isTestOnlyTask(task))
                .flatMap(task -> extract(task).stream())
                .toList());
    Map<String, LinkedHashSet<String>> additions = new java.util.LinkedHashMap<>();
    for (PlannedTask task : plan.tasks()) {
      if (task == null || (!isFrontendOnlyTask(task) && !isTestOnlyTask(task))) continue;
      for (ApiEvidence consumer : extractConsumerEvidence(task)) {
        ApiEvidence producer = exactProducer(producers, consumer);
        if (producer == null || !supportsPromotion(producer)) continue;
        List<String> requirements =
            scopedRequirements(requirementTextById, taskById.get(producer.taskId()), task);
        if (requirements.isEmpty()) continue;
        LinkedHashSet<String> required = new LinkedHashSet<>(consumer.declaredQueryParams());
        required.removeAll(producer.declaredQueryParams());
        required.removeIf(
            parameter -> !requirementNamesQueryParameter(requirements, producer, parameter));
        if (required.isEmpty()) continue;
        additions
            .computeIfAbsent(operationKey(producer), ignored -> new LinkedHashSet<>())
            .addAll(required);
      }
    }
    return additions.isEmpty() ? plan : apply(plan, additions);
  }

  private static Map<String, String> requirementTextById(Plan plan) {
    if (plan.requirements() == null) return Map.of();
    return plan.requirements().stream()
        .filter(java.util.Objects::nonNull)
        .filter(requirement -> requirement.id() != null && requirement.text() != null)
        .collect(
            Collectors.toMap(
                RequirementSpec::id,
                requirement -> requirement.text().toLowerCase(Locale.ROOT),
                (left, right) -> left,
                java.util.LinkedHashMap::new));
  }

  private static ApiEvidence exactProducer(List<ApiEvidence> producers, ApiEvidence consumer) {
    return producers.stream()
        .filter(candidate -> candidate.method().equals(consumer.method()))
        .filter(candidate -> candidate.path().equals(consumer.path()))
        .findFirst()
        .orElse(null);
  }

  private static boolean supportsPromotion(ApiEvidence producer) {
    return producer.method().equals("GET") && !producer.path().contains("{id}");
  }

  private static List<String> scopedRequirements(
      Map<String, String> requirementTextById, PlannedTask producerTask, PlannedTask consumerTask) {
    LinkedHashSet<String> ownedIds = new LinkedHashSet<>();
    if (producerTask != null && producerTask.meetsRequirements() != null) {
      ownedIds.addAll(producerTask.meetsRequirements());
    }
    if (consumerTask != null && consumerTask.meetsRequirements() != null) {
      ownedIds.addAll(consumerTask.meetsRequirements());
    }
    return ownedIds.stream()
        .map(requirementTextById::get)
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  private static boolean requirementNamesQueryParameter(
      List<String> requirements, ApiEvidence producer, String parameter) {
    String normalized = parameter.toLowerCase(Locale.ROOT);
    Pattern literal =
        Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(normalized) + "(?![a-z0-9_])");
    Set<String> routeTokens = domainTokens(producer.path());
    for (String requirement : requirements) {
      boolean resourceScoped =
          overlap(routeTokens, domainTokens(requirement)) > 0
              || requirement.matches("(?s).*\\blist\\s+endpoints?\\b.*");
      if (!resourceScoped) continue;
      if (literal.matcher(requirement).find()) return true;
      if (normalized.endsWith("_id")) {
        String resource = normalized.substring(0, normalized.length() - 3).replace('_', ' ');
        if (requirement.matches(
            "(?s).*\\b" + Pattern.quote(resource) + "\\b.{0,40}\\bfilters?\\b.*")) {
          return true;
        }
      }
    }
    return false;
  }

  private static Plan apply(Plan plan, Map<String, LinkedHashSet<String>> additions) {
    List<PlannedTask> tasks = new ArrayList<>(plan.tasks().size());
    for (PlannedTask task : plan.tasks()) {
      if (task == null || isFrontendOnlyTask(task) || isTestOnlyTask(task)) {
        tasks.add(task);
        continue;
      }
      String description = safe(task.description());
      List<String> constraints =
          new ArrayList<>(
              task.hardConstraints() == null ? List.<String>of() : task.hardConstraints());
      for (ApiEvidence producer : extract(task)) {
        Set<String> extra = additions.get(operationKey(producer));
        if (extra == null || extra.isEmpty()) continue;
        LinkedHashSet<String> all = new LinkedHashSet<>(producer.declaredQueryParams());
        all.addAll(extra);
        String query =
            all.stream()
                .map(parameter -> parameter + "=")
                .collect(Collectors.joining("&", "?", ""));
        String contract =
            "API producer contract binding: "
                + producer.method()
                + " "
                + displayPath(producer.path())
                + query
                + " accepts the requirement-backed query parameters "
                + all
                + ".";
        if (!description.contains(contract)) description += "\n\n" + contract;
        if (!constraints.contains(contract)) constraints.add(contract);
      }
      tasks.add(copyTask(task, description, constraints));
    }
    return copyPlan(plan, tasks);
  }

  private static String operationKey(ApiEvidence evidence) {
    return evidence.taskId() + "\u0000" + evidence.method() + "\u0000" + evidence.path();
  }

  private static List<ApiEvidence> extractConsumerEvidence(PlannedTask task) {
    List<String> constraints =
        (task.hardConstraints() == null ? List.<String>of() : task.hardConstraints())
            .stream().filter(value -> !safe(value).startsWith("API contract binding:")).toList();
    return extract(copyTask(task, safe(task.description()), constraints));
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
}
