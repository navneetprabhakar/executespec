package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanApiContractEvidence.extract;
import static com.navneet.executespec.agent.PlanApiContractEvidence.normalizedPath;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.isFrontendOnlyTask;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.isTestOnlyTask;
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

import com.navneet.executespec.agent.PlanApiContractEvidence.ApiEvidence;

/** Reconciles an unambiguous cancel/release spelling alias to the producer-owned public route. */
final class PlanApiActionAliasReconciler {
  private static final Set<String> LIFECYCLE_ALIASES = Set.of("cancel", "release");

  private PlanApiActionAliasReconciler() {}

  static Plan reconcile(Plan plan) {
    if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) return plan;
    Map<String, List<ApiEvidence>> producerActions = producerActions(plan);
    if (producerActions.isEmpty()) return plan;

    List<PlannedTask> tasks = new ArrayList<>(plan.tasks().size());
    for (PlannedTask task : plan.tasks()) {
      if (task == null || (!isFrontendOnlyTask(task) && !isTestOnlyTask(task))) {
        tasks.add(task);
        continue;
      }
      Set<ApiEvidence> authoritative = new LinkedHashSet<>();
      for (ApiEvidence consumer : extractConsumerEvidence(task)) {
        if (!LIFECYCLE_ALIASES.contains(lastSegment(consumer.path()))) continue;
        List<ApiEvidence> candidates = producerActions.get(actionFamilyKey(consumer));
        if (candidates != null
            && candidates.size() == 1
            && candidates.getFirst().path().equals(consumer.path())) {
          authoritative.add(candidates.getFirst());
        }
      }
      if (authoritative.isEmpty()) {
        tasks.add(task);
        continue;
      }
      String description = safe(task.description());
      for (ApiEvidence producer : authoritative) description = replaceAlias(description, producer);
      List<String> constraints = new ArrayList<>();
      for (String constraint :
          task.hardConstraints() == null ? List.<String>of() : task.hardConstraints()) {
        String reconciled = safe(constraint);
        for (ApiEvidence producer : authoritative) reconciled = replaceAlias(reconciled, producer);
        constraints.add(reconciled);
      }
      tasks.add(copyTask(task, description, constraints));
    }
    return copyPlan(plan, tasks);
  }

  private static Map<String, List<ApiEvidence>> producerActions(Plan plan) {
    Map<String, List<ApiEvidence>> actions = new LinkedHashMap<>();
    for (PlannedTask task : plan.tasks()) {
      if (task == null
          || isFrontendOnlyTask(task)
          || isTestOnlyTask(task)
          || !PlanApiContractEvidence.isImplementationProducerTask(task)) continue;
      for (ApiEvidence endpoint : extract(task)) {
        if (!LIFECYCLE_ALIASES.contains(lastSegment(endpoint.path()))) continue;
        actions
            .computeIfAbsent(actionFamilyKey(endpoint), ignored -> new ArrayList<>())
            .add(endpoint);
      }
    }
    return actions;
  }

  private static String replaceAlias(String value, ApiEvidence producer) {
    String canonicalAction = lastSegment(producer.path());
    String alias = canonicalAction.equals("cancel") ? "release" : "cancel";
    String canonicalRawPath = producer.rawPath().replaceFirst("[?].*$", "");
    String aliasPath =
        canonicalRawPath.substring(0, canonicalRawPath.length() - canonicalAction.length()) + alias;
    String aliasPattern = pathPattern(aliasPath);
    Pattern literal =
        Pattern.compile("(?i)(?<![a-zA-Z0-9_])" + aliasPattern + "(?![/a-zA-Z0-9_{}:-])");
    return literal.matcher(value).replaceAll(Matcher.quoteReplacement(canonicalRawPath));
  }

  private static String pathPattern(String path) {
    Matcher parameter = Pattern.compile("\\{[^}/]+}").matcher(path);
    StringBuilder pattern = new StringBuilder();
    int offset = 0;
    while (parameter.find()) {
      pattern.append(Pattern.quote(path.substring(offset, parameter.start())));
      pattern.append("\\{[^}/]+}");
      offset = parameter.end();
    }
    pattern.append(Pattern.quote(path.substring(offset)));
    return pattern.toString();
  }

  private static String actionFamilyKey(ApiEvidence endpoint) {
    String path = normalizedPath(endpoint.path());
    int slash = path.lastIndexOf('/');
    String family = slash < 0 ? path : path.substring(0, slash);
    return endpoint.method().toUpperCase(Locale.ROOT) + "\u0000" + family;
  }

  private static String lastSegment(String path) {
    String normalized = normalizedPath(path);
    int slash = normalized.lastIndexOf('/');
    return slash < 0 ? normalized : normalized.substring(slash + 1);
  }

  private static List<ApiEvidence> extractConsumerEvidence(PlannedTask task) {
    List<String> constraints =
        (task.hardConstraints() == null ? List.<String>of() : task.hardConstraints())
            .stream().filter(value -> !safe(value).startsWith("API contract binding:")).toList();
    return extract(copyTask(task, task.description(), constraints));
  }

  private static PlannedTask copyTask(
      PlannedTask source, String description, List<String> constraints) {
    return PlannedTask.builder()
        .id(source.id())
        .title(source.title())
        .description(description)
        .complexity(source.complexity())
        .touchesFiles(source.touchesFiles())
        .hardConstraints(constraints)
        .dependsOn(source.dependsOn())
        .taskType(source.taskType())
        .meetsRequirements(source.meetsRequirements())
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
