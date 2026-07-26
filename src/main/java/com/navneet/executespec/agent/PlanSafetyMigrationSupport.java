package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.isFrontendOnlyTask;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.isTestOnlyTask;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.safe;
import static com.navneet.executespec.agent.TaskExpansionPlanContract.taskContractEvidence;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PlanSafetyMigrationSupport {
  private static final Pattern VERSION_MIGRATION_REFERENCE =
      Pattern.compile(
          "(?i)(?:`)?[a-z0-9_./-]*/(?:alembic/versions|db/migration)/" + "[a-z0-9_.-]+(?:`)?");

  private PlanSafetyMigrationSupport() {}

  static boolean requiresConsolidatedMigration(Plan plan) {
    if (plan == null || plan.tasks() == null || !usesAlembic(plan)) return false;
    PlannedTask owner = consolidatedMigrationOwner(plan);
    if (owner == null) return false;
    int ownerIndex = plan.tasks().indexOf(owner);
    for (int index = 0; index < ownerIndex; index++) {
      PlannedTask task = plan.tasks().get(index);
      if (task == null) continue;
      String evidence = taskContractEvidence(task);
      if (isInfrastructureScaffold(evidence)
          && (hasVersionMigration(task)
              || evidence
                  .toLowerCase(Locale.ROOT)
                  .contains("migration ownership correction (binding)"))) return true;
    }
    return false;
  }

  private static boolean usesAlembic(Plan plan) {
    return plan.tasks().stream()
        .filter(Objects::nonNull)
        .map(TaskExpansionPlanContract::taskContractEvidence)
        .map(value -> value.toLowerCase(Locale.ROOT))
        .anyMatch(value -> value.contains("alembic") || value.contains("/alembic/"));
  }

  static boolean isConsolidatedMigrationOwner(PlannedTask task, Plan plan) {
    if (task == null || !requiresConsolidatedMigration(plan)) return false;
    PlannedTask owner = consolidatedMigrationOwner(plan);
    return owner != null && Objects.equals(owner.id(), task.id());
  }

  static PlannedTask consolidatedMigrationOwner(Plan plan) {
    if (plan == null || plan.tasks() == null) return null;
    return plan.tasks().stream()
        .filter(Objects::nonNull)
        .filter(PlanSafetyMigrationSupport::isExplicitFullSchemaMigrationOwner)
        .findFirst()
        .orElse(null);
  }

  static String consolidatedMigrationOwnerReference(Plan plan) {
    PlannedTask owner = consolidatedMigrationOwner(plan);
    if (owner == null) return "the plan's explicit migration owner";
    String title = safe(owner.title()).replaceAll("[\\r\\n\"]+", " ").strip();
    return title.isBlank() ? "task " + owner.id() : "task " + owner.id() + " (“" + title + "”)";
  }

  static String consolidatedMigrationPath(Plan plan) {
    if (plan != null && plan.tasks() != null) {
      for (PlannedTask task : plan.tasks()) {
        if (task == null || task.touchesFiles() == null) continue;
        for (String path : task.touchesFiles()) {
          if (isVersionMigrationPath(path)) return path;
        }
      }
    }
    return "backend/alembic/versions/001_initial_schema.py";
  }

  static boolean hasVersionMigration(PlannedTask task) {
    return task != null
        && task.touchesFiles() != null
        && task.touchesFiles().stream()
            .anyMatch(PlanSafetyMigrationSupport::isVersionMigrationPath);
  }

  static boolean isVersionMigrationPath(String path) {
    String normalized = safe(path).replace('\\', '/').toLowerCase(Locale.ROOT);
    return normalized.contains("/alembic/versions/")
        || normalized.matches("(?s).*/db/migration/v[^/]+\\.(?:sql|java|kt)$");
  }

  static String normalizeOwnershipDescription(
      String value, boolean migrationOwner, String consolidatedPath) {
    return normalizeOwnershipDescription(
        value, migrationOwner, consolidatedPath, "the plan's explicit migration owner");
  }

  static String normalizeOwnershipDescription(
      String value,
      boolean migrationOwner,
      String consolidatedPath,
      String migrationOwnerReference) {
    String description =
        safe(value)
            .replaceAll("(?im)^MIGRATION OWNERSHIP CORRECTION \\(binding[^\\n]*(?:\\R|$)", "")
            .replaceAll(
                "(?im)^MIGRATION\\s+(?:Do not\\s+)+create a versioned migration in this task\\."
                    + "[^\\n]*(?:\\R|$)",
                "")
            .strip();
    String canonicalPath =
        safe(consolidatedPath).isBlank()
            ? "backend/alembic/versions/001_initial_schema.py"
            : consolidatedPath;
    if (migrationOwner) {
      description =
          VERSION_MIGRATION_REFERENCE
              .matcher(description)
              .replaceAll(Matcher.quoteReplacement("`" + canonicalPath + "`"));
      description =
          description
              .replaceAll("(?i)\\bforward-only\\b", "reversible")
              .replaceAll(
                  "(?i)no downgrade needed[^.\\n]*", "a reverse-order downgrade is required")
              .replaceAll(
                  "(?i)advances? the existing schema from [^.\\n]*initial migration",
                  "creates the complete schema after every model contract is established");
      String binding =
          "MIGRATION OWNERSHIP (binding and superseding earlier migration filenames): this task"
              + " owns the single consolidated migration at `"
              + canonicalPath
              + "`; its upgrade creates every required table and constraint exactly once and its"
              + " downgrade removes them in reverse dependency order.";
      return description.contains(binding) ? description : description + "\n\n" + binding;
    }

    String ownerReference =
        safe(migrationOwnerReference).isBlank()
            ? "the plan's explicit migration owner"
            : migrationOwnerReference;
    description =
        description.replaceAll(
            "(?i)(?:generate|create|add|write|own|implement)\\s+(?:a\\s+|the\\s+)?(?:reversible\\s+)?(?:alembic\\s+)?migration[^.\\n"
                + "]*\\.?",
            "Do not create a versioned migration in this task.");
    description =
        description
            .replaceAll(
                "(?i)\\s+and\\s+(?:generate|create|add|write|own|implement)\\s+(?:the\\s+)?"
                    + "(?:initial\\s+)?(?:alembic\\s+)?migration\\s+in\\s+"
                    + "`?[^\\s`]+(?:alembic/versions|db/migration)/[^\\s`]+`?",
                "")
            .replaceAll(
                "(?is)\\*\\*Migration:?\\*\\*.*?(?=\\n\\s*\\*\\*[A-Z][^\\n]*\\*\\*|$)",
                Matcher.quoteReplacement(
                    "**Migration:** Configure/import model metadata only in this task. "
                        + ownerReference
                        + " owns the versioned migration.\n"))
            .replaceAll(
                "(?i)(?<!not\\s)(?:run\\s+)?(?:alembic\\s+revision|generate|create|add|write|own|implement)[^.\\n"
                    + "]{0,180}(?:alembic/versions|versioned migration|initial migration)[^.\\n"
                    + "]*\\.?",
                "Do not create a versioned migration in this task.")
            .replaceAll(
                "(?i)the migration must be [^.\\n]*",
                Matcher.quoteReplacement(
                    ownerReference + " owns migration direction and downgrade behavior."));
    description =
        VERSION_MIGRATION_REFERENCE
            .matcher(description)
            .replaceAll(Matcher.quoteReplacement(ownerReference + "'s migration"));
    String binding =
        "MIGRATION OWNERSHIP CORRECTION (binding and superseding every earlier migration"
            + " instruction): this task may configure the migration runtime or define model"
            + " metadata, but it must not create, generate, or own a versioned migration. "
            + ownerReference
            + " is the sole migration owner.";
    return description.contains(binding) ? description : binding + "\n\n" + description;
  }

  private static boolean isExplicitFullSchemaMigrationOwner(PlannedTask task) {
    if (!hasVersionMigration(task)) return false;
    String evidence = taskContractEvidence(task).toLowerCase(Locale.ROOT);
    if (evidence.contains("migration ownership (binding")) return true;
    if (!isDomainSchemaTask(task)) return false;
    boolean initialMigration =
        evidence.contains("initial migration")
            || evidence.contains("initial alembic migration")
            || evidence.matches("(?s).*[/_-]00*1[_-][^\\s`]*\\.py.*");
    boolean fullSchema =
        evidence.contains("all sqlalchemy")
            || evidence.contains("all database models")
            || evidence.contains("all domain models")
            || evidence.contains("all required tables")
            || evidence.contains("all tables")
            || evidence.contains("complete schema")
            || evidence.contains("full schema")
            || evidence.matches("(?s).*creates? all \\w+ tables.*");
    return initialMigration && fullSchema;
  }

  private static boolean isDomainSchemaTask(PlannedTask task) {
    if (task == null || isFrontendOnlyTask(task) || isTestOnlyTask(task)) return false;
    String evidence = taskContractEvidence(task);
    return TaskExpansionPlanContract.isPersistenceSchemaProducer(task, evidence)
        && !isInfrastructureScaffold(evidence);
  }

  static boolean isInfrastructureScaffold(String evidence) {
    String normalized = safe(evidence).toLowerCase(Locale.ROOT);
    return normalized.contains("scaffold")
        || normalized.contains("project structure")
        || normalized.contains("alembic config")
        || normalized.contains("database setup");
  }
}
