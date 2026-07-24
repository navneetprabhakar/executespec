package com.navneet.executespec.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
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
    boolean authenticatedPrincipalRequired =
        containsAny(
            requirements, "authenticated", "authenticate", "authentication", "api key", "bearer");
    boolean principalScopedIdempotencyRequired =
        authenticatedPrincipalRequired && requirements.contains("idempotency");
    boolean migrationsRequired = requirements.contains("migration");
    boolean reservationArithmetic =
        requirements.contains("reservation")
            && (requirements.contains("stock") || requirements.contains("quantity"));
    boolean sqliteConcurrencyRequired =
        requirements.contains("sqlite")
            && (requirements.contains("atomic") || requirements.contains("concurrent"));
    boolean sqliteStockConcurrencyRequired =
        sqliteConcurrencyRequired
            && (requirements.contains("stock") || requirements.contains("quantity"));
    boolean sqliteOverlapConcurrencyRequired =
        sqliteConcurrencyRequired
            && requirements.contains("reservation")
            && requirements.contains("overlap");
    boolean invalidCancellationRequired =
        requirements.contains("cancel")
            && requirements.contains("invalid state")
            && requirements.contains("double cancellation");
    boolean blackBoxHttpRequired = plan.runtimeSurface() == Plan.RuntimeSurface.HTTP;

    if (!authenticatedPrincipalRequired
        && !migrationsRequired
        && !reservationArithmetic
        && !sqliteConcurrencyRequired
        && !sqliteOverlapConcurrencyRequired
        && !invalidCancellationRequired
        && !blackBoxHttpRequired) {
      return new Result(plan, 0, List.of());
    }

    List<PlannedTask> patchedTasks = new ArrayList<>(plan.tasks().size());
    List<String> summaries = new ArrayList<>();
    int applied = 0;
    for (PlannedTask task : plan.tasks()) {
      List<String> constraints = new ArrayList<>(new LinkedHashSet<>(task.hardConstraints()));
      int duplicateConstraints = task.hardConstraints().size() - constraints.size();
      if (duplicateConstraints > 0) {
        applied += duplicateConstraints;
        summaries.add(
            task.id() + " removed " + duplicateConstraints + " duplicate hard constraint(s)");
      }
      String description = task.description();
      String taskEvidence = taskEvidence(task);
      String taskIdentityEvidence = taskIdentityEvidence(task);
      boolean documentationTask =
          containsAny(
              taskEvidence,
              "openapi specification",
              "openapi.yaml",
              "swagger ui",
              "readme",
              "documentation");
      boolean apiOrServiceTask =
          !documentationTask
              && containsAny(
                  taskEvidence,
                  "post /reservations",
                  "get /reservations",
                  "cancel_reservation",
                  "get_reservation",
                  "reservationservice",
                  "reservation_service",
                  "reservation route",
                  "authentication",
                  "auth.py",
                  "idempotency service",
                  "idempotency middleware",
                  "idempotency.py",
                  "routes.py",
                  "dependencies.py");
      boolean testTask =
          containsAny(taskEvidence, "tests/", "pytest test", "testclient", "test fixture", "test_");
      boolean credentialTask =
          !documentationTask
              && containsAny(
                  taskEvidence, "api_key", "api key", "bearer token", "credential", "token");
      boolean dedicatedAcceptanceTestTask =
          testTask
              && containsAny(
                  taskEvidence,
                  "integration test",
                  "functional test",
                  "acceptance test",
                  "end-to-end",
                  "concurrency test",
                  "test_concurrency",
                  "pytest suite",
                  "pytest tests",
                  "pytest api tests",
                  "test happy paths",
                  "over http");
      boolean authenticationProofTask =
          testTask && (apiOrServiceTask || credentialTask || dedicatedAcceptanceTestTask);
      boolean ownedResourceBehaviorTask =
          !documentationTask
              && containsAny(
                  taskEvidence,
                  "post /reservations",
                  "get /reservations",
                  "cancel_reservation",
                  "get_reservation",
                  "reservationservice",
                  "reservation_service",
                  "reservation route",
                  "routes.py");
      boolean idempotencyBehaviorTask =
          !documentationTask
              && containsAny(
                  taskIdentityEvidence,
                  "idempotency service",
                  "idempotency middleware",
                  "idempotency.py");
      boolean createReservationTask =
          !documentationTask
              && (dedicatedAcceptanceTestTask
                  || containsCreateReservationEndpoint(taskEvidence)
                  || containsAny(
                      taskEvidence,
                      "create_reservation",
                      "stock decrement",
                      "decrement stock",
                      "reservation core logic",
                      "last unit"));
      boolean stockMutationTask =
          !documentationTask
              && (dedicatedAcceptanceTestTask
                  || containsAny(
                      taskEvidence,
                      "create_reservation",
                      "stock decrement",
                      "decrement stock",
                      "last unit")
                  || containsAny(
                      taskIdentityEvidence, "reservation service", "reservation_service"));
      boolean cancellationBehaviorTask =
          !documentationTask
              && (dedicatedAcceptanceTestTask
                  || containsAny(taskEvidence, "cancel_reservation")
                  || containsAny(
                      taskIdentityEvidence, "reservation service", "reservation_service"));

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
      }
      if (authenticatedPrincipalRequired && authenticationProofTask) {
        if (credentialTask || dedicatedAcceptanceTestTask) {
          applied +=
              addConstraint(
                  constraints,
                  "Test that forged credentials and caller-selected identity input are rejected.",
                  task.id(),
                  "adversarial authentication proof",
                  summaries);
        }
        if (ownedResourceBehaviorTask || dedicatedAcceptanceTestTask) {
          applied +=
              addConstraint(
                  constraints,
                  "Test that cross-principal owned-resource access is rejected.",
                  task.id(),
                  "adversarial ownership proof",
                  summaries);
        }
        if (principalScopedIdempotencyRequired
            && (idempotencyBehaviorTask || dedicatedAcceptanceTestTask)) {
          applied +=
              addConstraint(
                  constraints,
                  "Test that two authenticated principals can reuse the same Idempotency-Key"
                      + " without collision or response leakage.",
                  task.id(),
                  "principal-scoped idempotency proof",
                  summaries);
        }
      }
      if (authenticatedPrincipalRequired && credentialTask) {
        applied +=
            addConstraint(
                constraints,
                "Never persist or log raw API keys or bearer tokens. If the service owns credential"
                    + " persistence, store a one-way verifier; an explicitly requested external"
                    + " token map may be compared only in memory and must fail closed.",
                task.id(),
                "credential storage boundary",
                summaries);
        applied +=
            addConstraint(
                constraints,
                "Production/runtime code must not seed, ship, log, document, or accept predictable"
                    + " default credentials or bearer tokens; credential fixtures belong only in"
                    + " isolated test code.",
                task.id(),
                "no runtime credential fixtures",
                summaries);
        applied +=
            addConstraint(
                constraints,
                "Any persisted credential-verifier record must reference a stable"
                    + " principal/customer ID; never use a credential hash as principal identity.",
                task.id(),
                "credential verifier identity boundary",
                summaries);
      }

      if (reservationArithmetic && createReservationTask) {
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
          && (createReservationTask || stockMutationTask || cancellationBehaviorTask)) {
        PlanSafetyInstructionCanonicalizer.RewriteResult sqliteRewrite =
            PlanSafetyInstructionCanonicalizer.rewriteUnsafeSqliteConcurrency(
                description, constraints);
        description = sqliteRewrite.description();
        constraints = sqliteRewrite.constraints();
        if (sqliteRewrite.rewrites() > 0) {
          applied += sqliteRewrite.rewrites();
          summaries.add(
              task.id()
                  + " removed "
                  + sqliteRewrite.rewrites()
                  + " ineffective SQLite row-lock instruction(s) · SQLite concurrency boundary");
        }
        if (sqliteStockConcurrencyRequired && stockMutationTask) {
          applied +=
              addConstraint(
                  constraints,
                  "SQLite does not provide effective SELECT FOR UPDATE row locks; use BEGIN"
                      + " IMMEDIATE or one conditional UPDATE with stock >= quantity and verify"
                      + " the affected-row count using separate-connection race tests.",
                  task.id(),
                  "SQLite-effective atomic stock mutation",
                  summaries);
        }
        if (sqliteOverlapConcurrencyRequired && createReservationTask) {
          applied +=
              addConstraint(
                  constraints,
                  "SQLite overlap protection must serialize the overlap check plus insert with"
                      + " BEGIN IMMEDIATE or an equivalent database-enforced guard; a SELECT then"
                      + " INSERT in a deferred transaction is not concurrency-safe. Prove it with"
                      + " separate connections synchronized before the decision, asserting one"
                      + " 201, one 409, no 5xx, and final persisted state.",
                  task.id(),
                  "SQLite-effective concurrent overlap guard",
                  summaries);
        }
      }

      PlanSafetyInstructionCanonicalizer.RewriteResult executableRewrite =
          PlanSafetyInstructionCanonicalizer.rewriteContradictoryExecutableInstructions(
              description,
              constraints,
              authenticatedPrincipalRequired && apiOrServiceTask,
              authenticatedPrincipalRequired && credentialTask,
              invalidCancellationRequired && cancellationBehaviorTask,
              blackBoxHttpRequired && dedicatedAcceptanceTestTask);
      description = executableRewrite.description();
      constraints = executableRewrite.constraints();
      if (executableRewrite.rewrites() > 0) {
        applied += executableRewrite.rewrites();
        summaries.add(
            task.id()
                + " canonicalized "
                + executableRewrite.rewrites()
                + " contradictory executable instruction(s) · worker prompt consistency");
      }

      if (invalidCancellationRequired && cancellationBehaviorTask) {
        applied +=
            addConstraint(
                constraints,
                "A second cancellation must return the stable invalid-state error, not a success"
                    + " replay; stock must remain restored exactly once.",
                task.id(),
                "double-cancel state transition",
                summaries);
      }

      boolean runtimeOrMigrationTask =
          isRuntimeOrMigrationTask(taskEvidence)
              && (!documentationTask || containsAny(taskEvidence, "readme", "running the server"));
      if (migrationsRequired && runtimeOrMigrationTask) {
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
      if (blackBoxHttpRequired && dedicatedAcceptanceTestTask) {
        applied +=
            addConstraint(
                constraints,
                "Functional tests must launch the documented production server, apply the real"
                    + " migrations to a fresh database, and exercise public behavior over real"
                    + " HTTP; in-process TestClient, dependency overrides, and unit tests are"
                    + " supplemental and cannot be the only acceptance proof.",
                task.id(),
                "black-box HTTP acceptance boundary",
                summaries);
      }

      patchedTasks.add(withDescriptionAndConstraints(task, description, constraints));
    }

    if (applied == 0) return new Result(plan, 0, List.of());
    return new Result(withTasks(plan, patchedTasks), applied, List.copyOf(summaries));
  }

  private static PlannedTask withDescriptionAndConstraints(
      PlannedTask task, String description, List<String> constraints) {
    if (description.equals(task.description()) && constraints.equals(task.hardConstraints())) {
      return task;
    }
    return PlannedTask.builder()
        .id(task.id())
        .title(task.title())
        .description(description)
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

  private static String taskIdentityEvidence(PlannedTask task) {
    return (safe(task.title()) + "\n" + String.join("\n", task.touchesFiles()))
        .toLowerCase(Locale.ROOT);
  }

  private static boolean containsAny(String evidence, String... needles) {
    for (String needle : needles) {
      if (evidence.contains(needle)) return true;
    }
    return false;
  }

  private static boolean containsCreateReservationEndpoint(String evidence) {
    return evidence.matches("(?s).*post\\s+/reservations(?:\\s|,|\\.|$).*");
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
