package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.MAX_DESCRIPTION_CHARS;
import static com.navneet.executespec.agent.TaskExpanderAgent.safe;
import static com.navneet.executespec.agent.TaskExpansionSemanticContract.applyDeterministicSemanticFallback;
import static com.navneet.executespec.agent.TaskExpansionSemanticContract.semanticViolations;
import static com.navneet.executespec.agent.TaskExpansionTextNormalizer.boundTextPreservingFinalContract;
import static com.navneet.executespec.agent.TaskExpansionTextNormalizer.normalizeTechnicalSpacing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Domain-neutral final-plan normalization.
 *
 * <p>Normalization is intentionally limited to mechanical cleanup, bounded prompt size, and the
 * shared semantic fallback. Product-specific schemas and routes must come from the user's
 * requirements and producer tasks, never Java constants in the planning pipeline.
 */
final class TaskExpansionPlanNormalizer {

  private TaskExpansionPlanNormalizer() {}

  static Plan normalize(Plan plan) {
    if (plan == null || plan.tasks() == null) return plan;
    Plan dependencyReconciled = PlanExpansionDependencyEnricher.enrich(plan).plan();
    Plan httpSemanticsReconciled =
        PlanApiContractReconciler.normalizeConsumerHttpSemantics(dependencyReconciled);
    Plan operationReconciled =
        PlanApiContractReconciler.reconcileConsumerOperations(
            PlanContractQualityGate.reconcilePackaging(httpSemanticsReconciled));
    Plan requirementReconciled =
        PlanContractQualityGate.reconcileRequirementBackedProducerQueries(operationReconciled);
    Plan consumerReconciled = PlanContractQualityGate.reconcileConsumers(requirementReconciled);
    Plan consumerQueryReconciled =
        PlanContractQualityGate.reconcileRequirementBackedProducerQueries(consumerReconciled);
    Plan producerReconciled =
        PlanApiProducerContractReconciler.reconcileMissingOperations(consumerQueryReconciled);
    Plan reconciled = PlanContractQualityGate.reconcileConsumers(producerReconciled);
    Plan ownershipReconciled = PlanScopeOwnershipReconciler.reconcile(reconciled);
    List<PlannedTask> normalized = new ArrayList<>(ownershipReconciled.tasks().size());
    for (PlannedTask task : ownershipReconciled.tasks()) {
      if (task == null) {
        normalized.add(null);
        continue;
      }
      PlannedTask current =
          copyTask(
              task,
              boundDescription(normalizeTechnicalSpacing(safe(task.description()))),
              prioritizeHardConstraints(task));
      List<String> violations = semanticViolations(current, ownershipReconciled);
      if (!violations.isEmpty()) {
        current = applyDeterministicSemanticFallback(current, violations);
        current =
            copyTask(
                current,
                boundDescription(normalizeTechnicalSpacing(safe(current.description()))),
                prioritizeHardConstraints(current));
      }
      normalized.add(current);
    }
    Plan scopeReconciled =
        PlanScopeOwnershipReconciler.reconcile(copyPlan(ownershipReconciled, normalized));
    List<PlannedTask> secondPass = new ArrayList<>(scopeReconciled.tasks().size());
    for (PlannedTask task : scopeReconciled.tasks()) {
      if (task == null) {
        secondPass.add(null);
        continue;
      }
      List<String> violations = semanticViolations(task, scopeReconciled);
      PlannedTask current =
          violations.isEmpty() ? task : applyDeterministicSemanticFallback(task, violations);
      secondPass.add(
          copyTask(
              current,
              boundDescription(normalizeTechnicalSpacing(safe(current.description()))),
              prioritizeHardConstraints(current)));
    }
    Plan migrationReconciled =
        PlanMigrationOwnershipReconciler.reconcile(copyPlan(scopeReconciled, secondPass));
    List<PlannedTask> bounded = new ArrayList<>(migrationReconciled.tasks().size());
    for (PlannedTask task : migrationReconciled.tasks()) {
      bounded.add(
          task == null
              ? null
              : copyTask(task, safe(task.description()), prioritizeHardConstraints(task)));
    }
    return PlanFrontendApiCallSiteNormalizer.normalize(
        PlanRequirementCoverageGate.reinforceMissingMarkers(
            copyPlan(migrationReconciled, bounded)));
  }

  static List<String> prioritizeHardConstraints(PlannedTask task) {
    LinkedHashSet<String> unique = new LinkedHashSet<>();
    for (String constraint :
        task.hardConstraints() == null ? List.<String>of() : task.hardConstraints()) {
      String normalized = normalizeTechnicalSpacing(safe(constraint)).trim();
      if (normalized.isBlank() || isPlaceholderInstruction(normalized)) continue;
      unique.add(normalized);
    }
    if (unique.size() <= 12) return List.copyOf(unique);

    Set<String> scopeTokens =
        tokens(
            safe(task.title())
                + " "
                + safe(task.description())
                + " "
                + String.join(" ", task.touchesFiles() == null ? List.of() : task.touchesFiles()));
    List<String> values = new ArrayList<>(unique);
    values.sort(
        java.util.Comparator.comparingInt((String value) -> relevance(value, scopeTokens))
            .thenComparingInt(String::length)
            .reversed());
    return List.copyOf(values.subList(0, 12));
  }

  private static boolean isPlaceholderInstruction(String value) {
    String normalized = value.toLowerCase(Locale.ROOT);
    return normalized.contains("{{")
        || normalized.contains("}}")
        || normalized.matches(".*\\b(?:todo|tbd|fill in|placeholder)\\b.*");
  }

  private static int relevance(String value, Set<String> scopeTokens) {
    int score = 0;
    for (String token : tokens(value)) if (scopeTokens.contains(token)) score++;
    String normalized = value.toLowerCase(Locale.ROOT);
    if (normalized.contains("must")
        || normalized.contains("never")
        || normalized.contains("exact")) {
      score += 2;
    }
    return score;
  }

  private static Set<String> tokens(String value) {
    LinkedHashSet<String> tokens = new LinkedHashSet<>();
    for (String token : safe(value).toLowerCase(Locale.ROOT).split("[^a-z0-9_]+")) {
      if (token.length() >= 3) tokens.add(token);
    }
    return tokens;
  }

  private static String boundDescription(String value) {
    if (value.length() <= MAX_DESCRIPTION_CHARS) return value;
    return boundTextPreservingFinalContract(value, MAX_DESCRIPTION_CHARS);
  }

  static PlannedTask copyTask(PlannedTask task, String description, List<String> hardConstraints) {
    return PlannedTask.builder()
        .id(task.id())
        .title(task.title())
        .description(description)
        .complexity(task.complexity())
        .touchesFiles(task.touchesFiles())
        .dependsOn(task.dependsOn())
        .hardConstraints(hardConstraints)
        .meetsRequirements(task.meetsRequirements())
        .taskType(task.taskType())
        .build();
  }

  static Plan copyPlan(Plan source, List<PlannedTask> tasks) {
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
