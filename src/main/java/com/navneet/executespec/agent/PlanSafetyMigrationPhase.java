package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanSafetyContractEnricher.*;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.*;
import static com.navneet.executespec.agent.PlanSafetyExecutionTextSupport.*;
import static com.navneet.executespec.agent.PlanSafetyStructureSupport.*;
import static com.navneet.executespec.agent.PlanSafetyTextSupport.*;
import static com.navneet.executespec.agent.TaskExpansionPlanContract.taskContractEvidence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Keeps migration and Python model-layout normalization out of the general task safety phase. */
final class PlanSafetyMigrationPhase {
  private PlanSafetyMigrationPhase() {}

  static void apply(PlanSafetyPolicy policy, PlanSafetyTaskPatch patch) {
    reconcileConsolidatedMigration(policy, patch);
    reconcileIdempotencyModel(policy, patch);
    reconcilePythonModelLayout(policy, patch);
  }

  private static void reconcileConsolidatedMigration(
      PlanSafetyPolicy policy, PlanSafetyTaskPatch patch) {
    if (!policy.migrationsRequired()
        || !PlanSafetyMigrationSupport.requiresConsolidatedMigration(policy.plan())) return;

    boolean migrationOwner =
        PlanSafetyMigrationSupport.isConsolidatedMigrationOwner(patch.task, policy.plan());
    String migrationOwnerReference =
        PlanSafetyMigrationSupport.consolidatedMigrationOwnerReference(policy.plan());
    boolean hadVersionMigration =
        patch.touchesFiles.removeIf(PlanSafetyMigrationSupport::isVersionMigrationPath);
    if (migrationOwner) {
      String migrationPath = PlanSafetyMigrationSupport.consolidatedMigrationPath(policy.plan());
      String normalizedDescription =
          PlanSafetyMigrationSupport.normalizeOwnershipDescription(
              patch.description, true, migrationPath, migrationOwnerReference);
      if (!Objects.equals(patch.description, normalizedDescription)) {
        patch.description = normalizedDescription;
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " normalized consolidated migration ownership instructions");
      }
      if (!patch.touchesFiles.contains(migrationPath)) {
        patch.touchesFiles.add(migrationPath);
        patch.applied++;
      }
      patch.applied +=
          addConstraint(
              patch.constraints,
              "Own the single consolidated initial migration after all prerequisite domain"
                  + " model contracts are established. Import every model into Alembic"
                  + " metadata, create each table and constraint exactly once, and implement a"
                  + " reverse-order downgrade.",
              patch.task.id(),
              "schema-established migration ownership",
              patch.summaries);
    } else if (hadVersionMigration
        || containsAny(
            taskIdentityEvidence(patch.task),
            "initial migration",
            "alembic migration",
            "migration file")
        || taskContractEvidence(patch.task)
            .toLowerCase(Locale.ROOT)
            .contains("migration ownership correction (binding)")) {
      String migrationBoundary =
          "MIGRATION OWNERSHIP CORRECTION (binding): this task may configure the migration"
              + " runtime but must not create a versioned migration or any future domain table. "
              + migrationOwnerReference
              + " owns the consolidated migration after all domain model contracts are"
              + " established.";
      if (!patch.description.contains(migrationBoundary)) {
        patch.description =
            patch.description.isBlank()
                ? migrationBoundary
                : migrationBoundary + "\n\n" + patch.description;
        patch.applied++;
      }
      patch.applied +=
          addConstraint(
              patch.constraints,
              migrationBoundary,
              patch.task.id(),
              "defer migration until schemas are established",
              patch.summaries);
      patch.applied +=
          removeConstraints(
              patch.constraints,
              constraint -> {
                String normalized = constraint.toLowerCase(Locale.ROOT);
                return TaskExpansionSemanticContract.hasPositiveInstruction(
                        normalized,
                        "alembic revision",
                        "create the migration",
                        "generate the migration",
                        "forward-only")
                    && containsAny(normalized, "migration", "alembic");
              },
              patch.task.id(),
              "non-owner migration generation/direction",
              patch.summaries);
      String normalizedDescription =
          PlanSafetyMigrationSupport.normalizeOwnershipDescription(
              patch.description, false, null, migrationOwnerReference);
      if (!Objects.equals(patch.description, normalizedDescription)) {
        patch.description = normalizedDescription;
        patch.applied++;
        patch.summaries.add(patch.task.id() + " removed stale non-owner migration instructions");
      }
    }
    if (!migrationOwner
        && PlanSafetyMigrationSupport.isInfrastructureScaffold(taskContractEvidence(patch.task))) {
      boolean removedDomainModels =
          patch.touchesFiles.removeIf(
              path -> isDomainModelOwnedByLaterTask(path, patch.task, policy.plan()));
      if (removedDomainModels) {
        patch.applied++;
        patch.summaries.add(
            patch.task.id() + " removed domain model files owned by later implementation tasks");
      }
    }
  }

  private static void reconcileIdempotencyModel(
      PlanSafetyPolicy policy, PlanSafetyTaskPatch patch) {
    if (!policy.exactIdempotentReplayRequired()) return;
    String canonicalModelPath = canonicalIdempotencyModelPath(policy.plan());
    String normalizedModelPathDescription =
        safe(patch.description).replace("backend/app/models/idempotency.py", canonicalModelPath);
    if (!Objects.equals(patch.description, normalizedModelPathDescription)) {
      patch.description = normalizedModelPathDescription;
      patch.applied++;
      patch.summaries.add(patch.task.id() + " uses the canonical IdempotencyRecord model path");
    }
    boolean removedLegacyModel =
        patch.touchesFiles.removeIf(
            path ->
                path != null
                    && path.replace('\\', '/')
                        .toLowerCase(Locale.ROOT)
                        .endsWith("/models/idempotency.py")
                    && !path.replace('\\', '/').equals(canonicalModelPath));
    if (removedLegacyModel) {
      patch.applied++;
      patch.summaries.add(
          patch.task.id() + " removed duplicate legacy IdempotencyRecord model file");
    }
    patch.applied +=
        removeConstraints(
            patch.constraints,
            constraint -> {
              String normalized = constraint.toLowerCase(Locale.ROOT);
              return normalized.contains("order")
                  && normalized.contains("idempotency_key")
                  && !containsAny(
                      normalized, "must not own", "must not store", "do not store", "forbidden");
            },
            patch.task.id(),
            "business-aggregate replay ownership",
            patch.summaries);
  }

  private static void reconcilePythonModelLayout(
      PlanSafetyPolicy policy, PlanSafetyTaskPatch patch) {
    for (int index = 0; index < patch.touchesFiles.size(); index++) {
      String path = patch.touchesFiles.get(index);
      if (path == null || !path.replace('\\', '/').toLowerCase(Locale.ROOT).endsWith("/models.py"))
        continue;
      String normalizedPath = path.replace('\\', '/');
      String packagePrefix = normalizedPath.substring(0, normalizedPath.length() - 3) + "/";
      String originalEvidence =
          (safe(patch.task.title()) + "\n" + safe(patch.task.description()))
              .toLowerCase(Locale.ROOT);
      List<String> concreteModules =
          concreteModelModules(policy.plan(), packagePrefix, originalEvidence);
      if (concreteModules.isEmpty()) continue;

      patch.touchesFiles.set(index, packagePrefix + "__init__.py");
      concreteModules.stream()
          .filter(candidate -> !patch.touchesFiles.contains(candidate))
          .forEach(patch.touchesFiles::add);
      patch.description =
          safe(patch.task.description()).replace(normalizedPath, concreteModules.getFirst());
      patch.applied++;
      patch.summaries.add(
          patch.task.id() + " reused the canonical Python models package instead of shadowing it");
    }
  }

  private static List<String> concreteModelModules(
      Plan plan, String packagePrefix, String taskEvidence) {
    LinkedHashSet<String> concrete = new LinkedHashSet<>();
    for (PlannedTask task : plan.tasks()) {
      if (task == null || task.touchesFiles() == null) continue;
      for (String candidate : task.touchesFiles()) {
        String path = safe(candidate).replace('\\', '/');
        if (!path.startsWith(packagePrefix)
            || path.equals(packagePrefix + "__init__.py")
            || path.equals(packagePrefix + "base.py")
            || path.endsWith("/")) continue;
        String basename = path.substring(path.lastIndexOf('/') + 1);
        int extension = basename.lastIndexOf('.');
        String stem = extension < 0 ? basename : basename.substring(0, extension);
        String compactStem = stem.replaceAll("[^a-z0-9]", "");
        String compactEvidence = taskEvidence.replaceAll("[^a-z0-9]", "");
        if (!compactStem.isBlank() && compactEvidence.contains(compactStem)) concrete.add(path);
      }
    }
    return List.copyOf(new ArrayList<>(concrete));
  }

  private static boolean isDomainModelOwnedByLaterTask(
      String path, PlannedTask current, Plan plan) {
    String normalized = safe(path).replace('\\', '/').toLowerCase(Locale.ROOT);
    if (!normalized.contains("/models/")
        || normalized.endsWith("/models/__init__.py")
        || normalized.endsWith("/models/base.py")) return false;
    boolean duplicated =
        plan.tasks().stream()
            .filter(Objects::nonNull)
            .filter(candidate -> !Objects.equals(candidate.id(), current.id()))
            .filter(
                candidate ->
                    !PlanSafetyMigrationSupport.isInfrastructureScaffold(
                        taskContractEvidence(candidate)))
            .flatMap(candidate -> candidate.touchesFiles().stream())
            .filter(Objects::nonNull)
            .map(candidate -> candidate.replace('\\', '/').toLowerCase(Locale.ROOT))
            .anyMatch(normalized::equals);
    if (duplicated) return true;
    if (!normalized.endsWith("/idempotency.py") && !normalized.endsWith("/idempotency_record.py"))
      return false;
    return plan.tasks().stream()
        .filter(Objects::nonNull)
        .filter(candidate -> !Objects.equals(candidate.id(), current.id()))
        .map(TaskExpansionPlanContract::taskContractEvidence)
        .map(value -> value.toLowerCase(Locale.ROOT))
        .anyMatch(
            evidence ->
                containsAny(
                    evidence,
                    "idempotency service",
                    "idempotent reserve",
                    "idempotency-key",
                    "/services/idempotency.py"));
  }
}
