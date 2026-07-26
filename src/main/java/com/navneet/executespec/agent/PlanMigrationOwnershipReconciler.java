package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Assigns each model-owning task an ordered concrete Alembic revision. */
final class PlanMigrationOwnershipReconciler {
  private static final Pattern REVISION_NUMBER = Pattern.compile("^([0-9]{3,})[_-].+\\.py$");
  private static final Pattern REASSIGNED_MODEL_OWNER =
      Pattern.compile("([A-Za-z0-9_-]+) exclusively owns `([^`]+/models/[^`]+\\.py)`");

  private PlanMigrationOwnershipReconciler() {}

  static Plan reconcile(Plan plan) {
    if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) return plan;
    // Migration ownership is derived from concrete model ownership. Close explicit cross-task
    // scope bindings here as well as in the outer normalizer so a reassigned model can never be
    // evaluated against the previous owner's migration merely because this reconciler is invoked
    // directly or the normalization order changes.
    plan = PlanScopeOwnershipReconciler.reconcile(plan);
    String requirements =
        plan.requirements() == null
            ? ""
            : plan.requirements().stream()
                .map(RequirementSpec::text)
                .filter(java.util.Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.joining("\n"));
    boolean requiresMigration =
        requirements.contains("migration")
            || requirements.contains("alembic")
            || requirements.matches("(?s).*\\bmust\\s+migrate\\b.*");
    if (!requiresMigration) return plan;

    PlannedTask consolidatedOwner = PlanSafetyMigrationSupport.consolidatedMigrationOwner(plan);
    boolean explicitlyConsolidated =
        PlanSafetyMigrationSupport.hasExplicitConsolidatedOwnership(consolidatedOwner);
    if (PlanSafetyMigrationSupport.requiresConsolidatedMigration(plan)
        && (explicitlyConsolidated || !hasCrossTaskModelReassignment(plan))) {
      return reconcileConsolidatedMigration(plan);
    }

    List<PlannedTask> withIdempotencyOwner = new ArrayList<>(plan.tasks());
    if (requirements.contains("idempotency")) {
      for (int index = 0; index < withIdempotencyOwner.size(); index++) {
        PlannedTask task = withIdempotencyOwner.get(index);
        if (task == null || !TaskExpansionPlanContract.isIdempotencySchemaOwner(task, plan))
          continue;
        LinkedHashSet<String> files = new LinkedHashSet<>(safeFiles(task));
        files.add(PlanSafetyExecutionTextSupport.canonicalIdempotencyModelPath(plan));
        withIdempotencyOwner.set(
            index, copyTask(task, task.description(), List.copyOf(files), task.hardConstraints()));
        break;
      }
    }
    Plan ownerPlan = copyPlan(plan, withIdempotencyOwner);
    String versionsPrefix = versionsPrefix(ownerPlan);
    int revision = maximumRevision(ownerPlan);
    List<PlannedTask> tasks = new ArrayList<>(ownerPlan.tasks().size());
    for (PlannedTask task : ownerPlan.tasks()) {
      if (task == null) {
        tasks.add(null);
        continue;
      }
      LinkedHashSet<String> files = new LinkedHashSet<>(safeFiles(task));
      files.removeIf(PlanMigrationOwnershipReconciler::isPlaceholderMigration);
      List<String> models =
          files.stream()
              .map(path -> safe(path).replace('\\', '/'))
              .filter(PlanMigrationOwnershipReconciler::isConcreteModelPath)
              .toList();
      List<String> migrations =
          files.stream().filter(PlanSafetyMigrationSupport::isVersionMigrationPath).toList();
      if (!models.isEmpty() && migrations.isEmpty()) {
        revision++;
        String slug =
            models.stream()
                .map(PlanMigrationOwnershipReconciler::basename)
                .map(value -> value.replace("_record", ""))
                .distinct()
                .limit(3)
                .collect(java.util.stream.Collectors.joining("_"));
        files.add(versionsPrefix + String.format("%03d_%s_schema.py", revision, slug));
        migrations =
            files.stream().filter(PlanSafetyMigrationSupport::isVersionMigrationPath).toList();
      }
      List<String> constraints =
          new ArrayList<>(
              task.hardConstraints() == null ? List.<String>of() : task.hardConstraints());
      String description = task.description();
      if (!models.isEmpty() && !migrations.isEmpty()) {
        constraints.removeIf(PlanMigrationOwnershipReconciler::isRuntimeOnlyMigrationBoundary);
        description = removeRuntimeOnlyMigrationBoundary(description);
        if (!safe(description).contains("MIGRATION OWNERSHIP (binding")) {
          String binding =
              "MIGRATION SCOPE CONTRACT (binding and superseding broad initial-schema wording): "
                  + String.join(", ", migrations)
                  + " creates and downgrades only the tables/constraints for this task's owned"
                  + " models "
                  + models
                  + "; sibling-owned models are forbidden from this revision.";
          if (!constraints.contains(binding)) constraints.add(binding);
        }
      }
      tasks.add(copyTask(task, description, List.copyOf(files), constraints));
    }
    return copyPlan(ownerPlan, tasks);
  }

  private static Plan reconcileConsolidatedMigration(Plan plan) {
    PlannedTask owner = PlanSafetyMigrationSupport.consolidatedMigrationOwner(plan);
    if (owner == null) return plan;
    String migrationPath = PlanSafetyMigrationSupport.consolidatedMigrationPath(plan);
    String ownerReference = PlanSafetyMigrationSupport.consolidatedMigrationOwnerReference(plan);
    List<PlannedTask> tasks = new ArrayList<>(plan.tasks().size());
    for (PlannedTask task : plan.tasks()) {
      if (task == null) {
        tasks.add(null);
        continue;
      }
      boolean migrationOwner = java.util.Objects.equals(owner.id(), task.id());
      LinkedHashSet<String> files = new LinkedHashSet<>(safeFiles(task));
      files.removeIf(
          path ->
              PlanSafetyMigrationSupport.isVersionMigrationPath(path)
                  || PlanSafetyMigrationSupport.isMigrationDirectoryPlaceholder(path));
      if (migrationOwner) files.add(migrationPath);

      boolean mentionsMigration =
          PlanSafetyMigrationSupport.hasVersionMigration(task)
              || safe(task.description()).toLowerCase(Locale.ROOT).contains("migration")
              || safe(task.title()).toLowerCase(Locale.ROOT).contains("migration");
      String description = task.description();
      List<String> constraints =
          new ArrayList<>(
              task.hardConstraints() == null ? List.<String>of() : task.hardConstraints());
      constraints.removeIf(PlanMigrationOwnershipReconciler::isMigrationDirectionConstraint);
      if (migrationOwner) {
        description =
            PlanSafetyMigrationSupport.normalizeOwnershipDescription(
                description, true, migrationPath, ownerReference);
        String binding =
            "Own the single consolidated initial migration at "
                + migrationPath
                + "; its upgrade creates every required table and constraint exactly once and its"
                + " downgrade removes them in reverse dependency order.";
        constraints.add(binding);
      } else if (mentionsMigration) {
        description =
            PlanSafetyMigrationSupport.normalizeOwnershipDescription(
                description, false, null, ownerReference);
        constraints.add(
            "Migration ownership is assigned to "
                + ownerReference
                + "; this task must not create, generate, or own a versioned migration.");
      }
      tasks.add(
          copyTask(
              task,
              description,
              List.copyOf(files),
              List.copyOf(new LinkedHashSet<>(constraints))));
    }
    return copyPlan(plan, tasks);
  }

  private static List<String> safeFiles(PlannedTask task) {
    return task.touchesFiles() == null ? List.of() : task.touchesFiles();
  }

  private static boolean hasCrossTaskModelReassignment(Plan plan) {
    for (PlannedTask source : plan.tasks()) {
      if (source == null || !PlanSafetyMigrationSupport.hasVersionMigration(source)) continue;
      String evidence =
          safe(source.description())
              + "\n"
              + String.join(
                  "\n", source.hardConstraints() == null ? List.of() : source.hardConstraints());
      Matcher owner = REASSIGNED_MODEL_OWNER.matcher(evidence);
      while (owner.find()) {
        if (!owner.group(1).equals(source.id())
            && plan.tasks().stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(task -> owner.group(1).equals(task.id()))) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean isPlaceholderMigration(String path) {
    String normalized = safe(path).replace('\\', '/').toLowerCase(Locale.ROOT);
    return PlanSafetyMigrationSupport.isVersionMigrationPath(normalized)
        && normalized.matches(".*(?:^|[/_.-])(?:xxxx|todo|tbd)(?:[/_.-]).*");
  }

  private static boolean isConcreteModelPath(String rawPath) {
    String path = safe(rawPath).replace('\\', '/').toLowerCase(Locale.ROOT);
    if (path.endsWith("/models.py")) return true;
    return path.contains("/models/")
        && !path.endsWith("/models/__init__.py")
        && !path.endsWith("/models/base.py");
  }

  private static boolean isMigrationDirectionConstraint(String value) {
    String normalized = safe(value).toLowerCase(Locale.ROOT);
    if (!normalized.contains("migration") && !normalized.contains("alembic")) return false;
    return normalized.contains("own")
        || normalized.contains("create")
        || normalized.contains("generate")
        || normalized.contains("revision")
        || normalized.contains("forward-only")
        || normalized.contains("downgrade");
  }

  private static String versionsPrefix(Plan plan) {
    for (PlannedTask task : plan.tasks()) {
      if (task == null) continue;
      for (String rawPath : safeFiles(task)) {
        String path = safe(rawPath).replace('\\', '/');
        int marker = path.toLowerCase(Locale.ROOT).indexOf("/alembic/versions/");
        if (marker >= 0) return path.substring(0, marker + "/alembic/versions/".length());
      }
    }
    return "backend/alembic/versions/";
  }

  private static int maximumRevision(Plan plan) {
    int maximum = 0;
    for (PlannedTask task : plan.tasks()) {
      if (task == null) continue;
      for (String path : safeFiles(task)) {
        Matcher matcher = REVISION_NUMBER.matcher(path.substring(path.lastIndexOf('/') + 1));
        if (matcher.matches()) maximum = Math.max(maximum, Integer.parseInt(matcher.group(1)));
      }
    }
    return maximum;
  }

  private static String basename(String path) {
    String name = path.substring(path.lastIndexOf('/') + 1);
    int dot = name.lastIndexOf('.');
    return dot < 0 ? name : name.substring(0, dot);
  }

  private static boolean isRuntimeOnlyMigrationBoundary(String value) {
    String normalized = safe(value).toLowerCase(Locale.ROOT);
    return normalized.contains("must not create a versioned migration")
        || normalized.contains("must not create, generate, or own a versioned migration");
  }

  private static String removeRuntimeOnlyMigrationBoundary(String value) {
    return safe(value)
        .replaceAll(
            "(?is)MIGRATION OWNERSHIP CORRECTION \\(binding and superseding every earlier"
                + " migration instruction\\):.*?sole migration owner\\.\\s*",
            "")
        .replaceAll(
            "(?i)\\b(?:this task )?(?:must not|do not) create(?:, generate, or own)? a versioned"
                + " migration(?: or any future domain table)?\\.?",
            "")
        .strip();
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
        .hardConstraints(constraints == null ? List.of() : constraints)
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
