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
    Plan dependencyReconciled =
        PlanApiRouteIdentityContract.canonicalizeParameterNames(
            PlanExpansionDependencyEnricher.enrich(plan).plan());
    dependencyReconciled = PlanDatabaseStackReconciler.reconcile(dependencyReconciled);
    Plan httpSemanticsReconciled =
        PlanApiContractReconciler.normalizeConsumerHttpSemantics(dependencyReconciled);
    httpSemanticsReconciled =
        PlanApiContractReconciler.normalizeMalformedConsumerRoutes(httpSemanticsReconciled);
    // Preserve explicit consumer operations before any compatibility reconciliation can collapse
    // an aggregate/item read onto a merely related producer route. The producer pass is bounded to
    // a unique implementation owner and therefore establishes the operation contract first.
    // Mutations are included here only when a backend task already owns their exact public path;
    // arbitrary consumer-invented actions remain fail-closed.
    Plan initialProducerReconciled =
        PlanApiProducerContractReconciler.reconcileMissingReadOperations(httpSemanticsReconciled);
    Plan operationReconciled =
        PlanApiContractReconciler.reconcileConsumerOperations(
            PlanContractQualityGate.reconcilePackaging(initialProducerReconciled));
    Plan requirementReconciled =
        PlanContractQualityGate.reconcileRequirementBackedProducerQueries(operationReconciled);
    Plan consumerReconciled = PlanContractQualityGate.reconcileConsumers(requirementReconciled);
    Plan consumerQueryReconciled =
        PlanContractQualityGate.reconcileRequirementBackedProducerQueries(consumerReconciled);
    Plan producerReconciled =
        PlanApiProducerContractReconciler.reconcileMissingOperations(consumerQueryReconciled);
    Plan reconciled = PlanContractQualityGate.reconcileConsumers(producerReconciled);
    Plan ownershipReconciled =
        PlanModelOwnershipReconciler.reconcile(PlanScopeOwnershipReconciler.reconcile(reconciled));
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
        PlanModelOwnershipReconciler.reconcile(
            PlanScopeOwnershipReconciler.reconcile(copyPlan(ownershipReconciled, normalized)));
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
    Plan namespaceReconciled = PlanPythonNamespaceLayoutReconciler.reconcile(migrationReconciled);
    Plan schemaReconciled = reconcileIdempotencyConsumerAliases(namespaceReconciled);
    Plan payloadReconciled = reconcileConsumerPayloadCompatibility(schemaReconciled);
    List<PlannedTask> bounded = new ArrayList<>(payloadReconciled.tasks().size());
    for (PlannedTask task : payloadReconciled.tasks()) {
      bounded.add(
          task == null
              ? null
              : copyTask(task, safe(task.description()), prioritizeHardConstraints(task)));
    }
    Plan finalNormalized =
        PlanFrontendApiCallSiteNormalizer.normalize(
            PlanRequirementCoverageGate.reinforceMissingMarkers(
                copyPlan(payloadReconciled, bounded)));
    // Coherence patches, safety fallbacks, and final call-site canonicalization can introduce or
    // restore a consumer operation after the earlier producer/consumer pass. Rebind once at the
    // actual final boundary: add only operations with one unique backend owner, then align that
    // consumer to the now-established producer contract. Ambiguous ownership still fails closed.
    Plan routeOwnerReconciled =
        PlanApiProducerContractReconciler.reconcileGeneratedBindingsToRouteArtifactOwners(
            PlanApiContractReconciler.normalizeMalformedConsumerRoutes(finalNormalized));
    Plan finalProducerReconciled =
        PlanApiProducerContractReconciler.reconcileMissingOperations(routeOwnerReconciled);
    Plan finalRouteIdentityReconciled =
        PlanApiRouteIdentityContract.canonicalizeParameterNames(finalProducerReconciled);
    Plan finalApiReconciled =
        PlanContractQualityGate.reconcileConsumers(
            PlanApiActionAliasReconciler.reconcile(finalRouteIdentityReconciled));
    // Consumer reconciliation binds frontend operations back to the producer's public `/api`
    // contract. When the plan declares a shared client whose baseURL already owns that prefix,
    // restore relative frontend call sites at the true final boundary. This pass is deliberately
    // frontend-only, so backend producer evidence remains public and unchanged.
    Plan finalFrontendNormalized = PlanFrontendApiCallSiteNormalizer.normalize(finalApiReconciled);
    // Final normalization and compaction can expose or truncate an executable contract after the
    // earlier safety pass. Close those mechanical gaps at the last pre-compile boundary so the
    // provider is never asked to repair deterministic process/toolchain wiring.
    Plan executableReconciled =
        PlanExecutableContractReconciler.reconcile(finalFrontendNormalized).plan();
    return boundFinalEnvelope(executableReconciled);
  }

  /**
   * Applies the provider envelope at the true final boundary.
   *
   * <p>The executable reconciler intentionally runs last because it derives runtime and test
   * contracts from the fully assembled plan. Those deterministic additions can, however, take a
   * task back over the same limits enforced on provider output (for example a thirteenth producer
   * constraint or a readiness contract appended to an already-full description). Bound once more
   * after that pass. The description compactor retains explicit binding paragraphs and constraint
   * prioritization keeps contract-bearing entries ahead of incidental detail.
   */
  private static Plan boundFinalEnvelope(Plan plan) {
    if (plan == null || plan.tasks() == null) return plan;
    List<PlannedTask> tasks = new ArrayList<>(plan.tasks().size());
    for (PlannedTask task : plan.tasks()) {
      tasks.add(
          task == null
              ? null
              : copyTask(
                  task,
                  boundDescription(normalizeTechnicalSpacing(safe(task.description()))),
                  prioritizeHardConstraints(task)));
    }
    return copyPlan(plan, tasks);
  }

  /**
   * Makes consumers follow a schema that the assembled plan itself already established. This is
   * intentionally producer-derived: a plan that deliberately defines another schema is not
   * rewritten to ExecuteSpec's preferred names.
   */
  private static Plan reconcileIdempotencyConsumerAliases(Plan plan) {
    PlannedTask producer = TaskExpansionPlanContract.canonicalIdempotencySchemaProducer(plan);
    if (producer == null) return plan;
    String producerEvidence = TaskExpansionPlanContract.taskContractEvidence(producer);
    String compact = producerEvidence.replaceAll("[^a-z0-9]", "");
    boolean keyScope =
        compact.contains("uniqueconstraintkeyscope")
            || compact.contains("uniqueconstraintonkeyscope");
    boolean requestHash = producerEvidence.contains("request_body_hash");
    if (!keyScope && !requestHash) return plan;

    List<PlannedTask> tasks = new ArrayList<>(plan.tasks().size());
    for (PlannedTask task : plan.tasks()) {
      if (task == null || java.util.Objects.equals(task.id(), producer.id())) {
        tasks.add(task);
        continue;
      }
      String evidence = TaskExpansionPlanContract.taskContractEvidence(task);
      boolean wrongKey =
          keyScope
              && TaskExpansionPlanContract.usesIdempotencyRecordAlias(evidence, "idempotency_key");
      boolean wrongScope =
          requestHash
              && TaskExpansionPlanContract.usesIdempotencyRecordAlias(evidence, "scope_hash");
      if (!wrongKey && !wrongScope) {
        tasks.add(task);
        continue;
      }
      String correction;
      if (wrongKey && wrongScope) {
        correction =
            "IDEMPOTENCY SCHEMA CONTRACT (binding; supersedes consumer aliases): follow schema task"
                + " "
                + producer.id()
                + "; IdempotencyRecord uses key scoped by scope and request_body_hash. On"
                + " IdempotencyRecord, idempotency_key and scope_hash are forbidden aliases.";
      } else if (wrongKey) {
        correction =
            "IDEMPOTENCY SCHEMA CONTRACT (binding; supersedes consumer alias): follow schema task "
                + producer.id()
                + "; IdempotencyRecord uses key scoped by scope. On IdempotencyRecord,"
                + " idempotency_key is a forbidden alias.";
      } else {
        correction =
            "IDEMPOTENCY SCHEMA CONTRACT (binding; supersedes consumer alias): follow schema task "
                + producer.id()
                + "; IdempotencyRecord uses request_body_hash. On IdempotencyRecord, scope_hash"
                + " is a forbidden alias.";
      }
      String description = safe(task.description()).strip();
      if (!description.contains(correction)) {
        description = description.isBlank() ? correction : description + "\n\n" + correction;
      }
      tasks.add(copyTask(task, description, task.hardConstraints()));
    }
    return copyPlan(plan, tasks);
  }

  private static Plan reconcileConsumerPayloadCompatibility(Plan plan) {
    Set<String> conflictingTaskIds =
        TaskExpansionPlanContract.crossTaskContractViolations(plan).stream()
            .filter(
                violation ->
                    violation.contains(
                        ": Frontend order consumer contradicts the canonical backend payload"))
            .map(violation -> violation.substring(0, violation.indexOf(':')))
            .collect(java.util.stream.Collectors.toSet());
    if (conflictingTaskIds.isEmpty()) return plan;

    List<PlannedTask> tasks = new ArrayList<>(plan.tasks().size());
    for (PlannedTask task : plan.tasks()) {
      tasks.add(
          task == null || !conflictingTaskIds.contains(task.id())
              ? task
              : applyDeterministicSemanticFallback(
                  task,
                  List.of(
                      "Frontend order consumer contradicts the canonical backend payload with"
                          + " alternate identity fields")));
    }
    return copyPlan(plan, tasks);
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
    // These entries are deterministic authoritative contracts introduced by the final safety,
    // ownership, and producer-reconciliation passes. They must survive when those passes append
    // one entry to a provider task that was already at its twelve-constraint envelope.
    if (normalized.contains("(binding")
        || normalized.contains("api producer contracts")
        || normalized.contains("contract correction")
        || normalized.contains("use these exact public api paths")) {
      score += 100;
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
