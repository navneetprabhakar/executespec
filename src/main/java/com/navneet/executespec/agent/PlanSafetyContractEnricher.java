package com.navneet.executespec.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Deterministic safety enrichment for mechanically discoverable planning contracts.
 *
 * <p>The provider-backed coherence pass still owns project-specific schema and interface matching.
 * This class owns invariants that must not disappear because a lightweight model overlooked them.
 */
public final class PlanSafetyContractEnricher {

  private PlanSafetyContractEnricher() {}

  public record Result(Plan plan, int contractsApplied, List<String> summaries) {}

  public static Result enrich(Plan plan) {
    String requirements =
        plan.requirements() == null
            ? ""
            : plan.requirements().stream()
                .map(RequirementSpec::text)
                .filter(Objects::nonNull)
                .map(text -> text.toLowerCase(Locale.ROOT))
                .reduce("", (left, right) -> left + "\n" + right);
    boolean authenticatedPrincipalRequired = requirements.contains("authenticated");
    boolean migrationsRequired = requirements.contains("migration");
    boolean reservationArithmetic =
        requirements.contains("reservation")
            && (requirements.contains("stock") || requirements.contains("quantity"));
    boolean sqliteConcurrencyRequired =
        requirements.contains("sqlite")
            && requirements.contains("stock")
            && (requirements.contains("atomic") || requirements.contains("concurrent"));
    boolean invalidCancellationRequired =
        requirements.contains("cancel")
            && requirements.contains("invalid state")
            && requirements.contains("double cancellation");

    if (!authenticatedPrincipalRequired
        && !migrationsRequired
        && !reservationArithmetic
        && !sqliteConcurrencyRequired
        && !invalidCancellationRequired) {
      return new Result(plan, 0, List.of());
    }

    List<PlannedTask> patchedTasks = new ArrayList<>(plan.tasks().size());
    List<String> summaries = new ArrayList<>();
    int applied = 0;
    for (PlannedTask task : plan.tasks()) {
      List<String> constraints = new ArrayList<>(task.hardConstraints());
      String taskEvidence = taskEvidence(task);
      boolean apiOrServiceTask =
          containsAny(
              taskEvidence,
              "post /reservations",
              "get /reservations",
              "cancel_reservation",
              "get_reservation",
              "reservationservice",
              "reservation_service",
              "routes.py",
              "dependencies.py");
      boolean testTask =
          containsAny(taskEvidence, "tests/", "pytest", "testclient", "test fixture", "test_");
      boolean credentialTask =
          containsAny(taskEvidence, "api_key", "api key", "bearer token", "credential", "token");

      if (authenticatedPrincipalRequired && apiOrServiceTask) {
        applied +=
            addConstraint(
                constraints,
                "Derive principal identity from a verified credential; never trust caller-selected"
                    + " identity input, and enforce read/update/delete access only for resources"
                    + " owned by that principal.",
                task.id(),
                "verified principal and owned-resource boundary",
                summaries);
        if (testTask) {
          applied +=
              addConstraint(
                  constraints,
                  "Test that forged identity input and cross-principal owned-resource access are"
                      + " rejected.",
                  task.id(),
                  "adversarial ownership proof",
                  summaries);
        }
      }
      if (authenticatedPrincipalRequired && credentialTask) {
        applied +=
            addConstraint(
                constraints,
                "Never store or compare API keys or bearer tokens in plaintext; store a one-way"
                    + " verifier and never expose credentials in logs or responses.",
                task.id(),
                "credential storage boundary",
                summaries);
      }

      if (reservationArithmetic
          && containsAny(
              taskEvidence,
              "post /reservations",
              "create_reservation",
              "quantity",
              "stock decrement",
              "reservation_service")) {
        applied +=
            addConstraint(
                constraints,
                "Reject reservation quantity <= 0 before stock arithmetic; test zero and negative"
                    + " quantities through the public API.",
                task.id(),
                "positive reservation domain",
                summaries);
      }

      if (sqliteConcurrencyRequired
          && containsAny(
              taskEvidence,
              "post /reservations",
              "create_reservation",
              "stock decrement",
              "reservation_service",
              "concurrent",
              "thread")) {
        applied +=
            addConstraint(
                constraints,
                "SQLite does not provide effective SELECT FOR UPDATE row locks; use BEGIN"
                    + " IMMEDIATE or one conditional UPDATE with stock >= quantity and verify the"
                    + " affected-row count using separate-connection race tests.",
                task.id(),
                "SQLite-effective atomic stock mutation",
                summaries);
      }

      if (invalidCancellationRequired && containsAny(taskEvidence, "cancel", "cancellation")) {
        applied +=
            addConstraint(
                constraints,
                "A second cancellation must return the stable invalid-state error, not a success"
                    + " replay; stock must remain restored exactly once.",
                task.id(),
                "double-cancel state transition",
                summaries);
      }

      if (migrationsRequired && isRuntimeOrMigrationTask(taskEvidence)) {
        applied +=
            addConstraint(
                constraints,
                "Run the project's configured migration mechanism from the documented default"
                    + " startup path; do not fall back to ORM schema auto-creation.",
                task.id(),
                "default runtime migration boundary",
                summaries);
      }
      if (migrationsRequired && testTask) {
        applied +=
            addConstraint(
                constraints,
                "Test fixtures must apply the configured migration mechanism and must not call ORM"
                    + " create_all or manually create the schema.",
                task.id(),
                "migration-backed test fixture",
                summaries);
      }

      patchedTasks.add(withConstraints(task, constraints));
    }

    if (applied == 0) return new Result(plan, 0, List.of());
    return new Result(withTasks(plan, patchedTasks), applied, List.copyOf(summaries));
  }

  private static PlannedTask withConstraints(PlannedTask task, List<String> constraints) {
    if (constraints.equals(task.hardConstraints())) return task;
    return PlannedTask.builder()
        .id(task.id())
        .title(task.title())
        .description(task.description())
        .complexity(task.complexity())
        .touchesFiles(task.touchesFiles())
        .dependsOn(task.dependsOn())
        .hardConstraints(List.copyOf(constraints))
        .meetsRequirements(task.meetsRequirements())
        .taskType(task.taskType())
        .build();
  }

  private static Plan withTasks(Plan plan, List<PlannedTask> tasks) {
    return Plan.builder()
        .planTitle(plan.planTitle())
        .planSummary(plan.planSummary())
        .projectType(plan.projectType())
        .buildCommand(plan.buildCommand())
        .smokeTestStrategy(plan.smokeTestStrategy())
        .runtimeSurface(plan.runtimeSurface())
        .tasks(List.copyOf(tasks))
        .classification(plan.classification())
        .requirements(plan.requirements())
        .build();
  }

  private static int addConstraint(
      List<String> constraints,
      String constraint,
      String taskId,
      String reason,
      List<String> summaries) {
    if (constraints.contains(constraint)) return 0;
    constraints.add(constraint);
    summaries.add(taskId + " hardConstraint += '" + truncate(constraint, 60) + "' · " + reason);
    return 1;
  }

  private static String taskEvidence(PlannedTask task) {
    return (safe(task.title())
            + "\n"
            + safe(task.description())
            + "\n"
            + String.join("\n", task.touchesFiles()))
        .toLowerCase(Locale.ROOT);
  }

  private static boolean containsAny(String evidence, String... needles) {
    for (String needle : needles) {
      if (evidence.contains(needle)) return true;
    }
    return false;
  }

  private static boolean isRuntimeOrMigrationTask(String evidence) {
    return containsAny(
        evidence,
        "alembic",
        "migration",
        "app/main.py",
        "app factory",
        "database.py",
        "startup",
        "readme.md",
        "running the server");
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }

  private static String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max - 1) + "…";
  }
}
