package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class TaskExecutionBriefCompilerTest {

  @Test
  void executableCompilationRejectsAnEmptyPlan() {
    assertThatThrownBy(
            () ->
                TaskExecutionBriefCompiler.compileExecutable(
                    Plan.builder().tasks(List.of()).build()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("empty executable plan");
  }

  @Test
  void executableCompilationAlwaysRejectsOmittedFullPlanRequirementOwnership() {
    Plan omitted =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1", "Implement the service", RequirementSpec.RequirementKind.FUNCTIONAL),
                    new RequirementSpec(
                        "R7", "Preserve the endpoint", RequirementSpec.RequirementKind.FUNCTIONAL),
                    new RequirementSpec(
                        "R9", "Add CI", RequirementSpec.RequirementKind.FUNCTIONAL)))
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("Implement service")
                        .description("Implement the service")
                        .meetsRequirements(List.of("R1"))
                        .build()))
            .build();

    assertThatThrownBy(() -> TaskExecutionBriefCompiler.compileExecutable(omitted))
        .isInstanceOf(TaskExecutionBriefCompiler.ExecutablePlanContractException.class)
        .hasMessageContaining("Requirements not covered by any task")
        .hasMessageContaining("R7")
        .hasMessageContaining("R9");
  }

  @Test
  void executableCompilationRejectsAnApiPrefixAddedToAnExactSourceRoute() {
    Plan mutated =
        Plan.builder()
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("Implement health and game routes")
                        .description("Implement GET /api/system/health and GET /api/casino-game.")
                        .touchesFiles(List.of("backend/routes/system.java"))
                        .build()))
            .build();

    assertThatThrownBy(
            () ->
                TaskExecutionBriefCompiler.compileExecutable(
                    mutated,
                    "Implement GET /system/health and GET /casino-game exactly. Do not add an"
                        + " /api prefix."))
        .isInstanceOf(TaskExecutionBriefCompiler.ExecutablePlanContractException.class)
        .hasMessageContaining("SOURCE_HTTP_ROUTE_NAMESPACE_CHANGED")
        .hasMessageContaining("GET /system/health")
        .hasMessageContaining("GET /api/system/health")
        .hasMessageContaining("GET /casino-game")
        .hasMessageContaining("GET /api/casino-game");
  }

  @Test
  void executableCompilationAcceptsExactRootAndApiNamespaces() {
    Plan exact =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Implement the exact routes",
                        RequirementSpec.RequirementKind.FUNCTIONAL)))
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("Implement exact routes")
                        .description("Implement GET /casino-game and GET /api/system/health.")
                        .meetsRequirements(List.of("R1"))
                        .touchesFiles(List.of("backend/routes/system.java"))
                        .build()))
            .build();

    Plan compiled =
        TaskExecutionBriefCompiler.compileExecutable(
            exact, "Implement GET /casino-game and GET /api/system/health exactly.");

    assertThat(compiled.tasks().getFirst().description())
        .contains("GET /casino-game", "GET /api/system/health")
        .doesNotContain("/api/api/");
  }

  @Test
  void preservesValidatedImplementationGuidanceWithAuthoritativeStructuredFields() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T7")
            .title("Build order creation UI")
            .description(
                "Build the accessible multi-line order form and map canonical API errors into the"
                    + " inline error summary.")
            .meetsRequirements(List.of("R1"))
            .hardConstraints(List.of("Idempotency-Key is required."))
            .touchesFiles(List.of("frontend/src/OrderForm.tsx"))
            .build();
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Create multi-line orders with product_id, warehouse_id, and quantity.",
                        RequirementSpec.RequirementKind.FUNCTIONAL)))
            .tasks(List.of(task))
            .build();

    PlannedTask compiled = TaskExecutionBriefCompiler.compile(plan).tasks().getFirst();

    assertThat(compiled.description())
        .isEqualTo(
            "Build the accessible multi-line order form and map canonical API errors into the"
                + " inline error summary.");
    assertThat(compiled.hardConstraints()).containsExactly("Idempotency-Key is required.");
    assertThat(compiled.touchesFiles()).containsExactly("frontend/src/OrderForm.tsx");
  }

  @Test
  void boundsGuidanceAfterReservingSpaceForAuthoritativeContract() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T1")
            .title("Implement inventory")
            .description("Implementation sequence. ".repeat(500))
            .meetsRequirements(List.of("R1"))
            .hardConstraints(List.of("Use integer quantities."))
            .touchesFiles(List.of("backend/app/inventory.py"))
            .build();
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Adjust stock atomically.",
                        RequirementSpec.RequirementKind.FUNCTIONAL)))
            .tasks(List.of(task))
            .build();

    PlannedTask compiled = TaskExecutionBriefCompiler.compile(plan).tasks().getFirst();

    assertThat(compiled.description()).isNotBlank();
    assertThat(compiled.description().length()).isLessThan(12_000);
  }

  @Test
  void truncationKeepsTheSupersedingCanonicalContractEvenWhenItUsesMostOfTheBudget() {
    String canonicalTail =
        "CONTRACT CORRECTION: IdempotencyRecord uses key, scope, request_body_hash, state,"
            + " nullable response_status, nullable response_body, PROCESSING and COMPLETED states,"
            + " and UniqueConstraint(key, scope).";
    PlannedTask task =
        PlannedTask.builder()
            .id("T5")
            .title("Implement idempotent order creation")
            .description(
                "IdempotencyRecord has only key and response_body. "
                    + "Earlier implementation notes. ".repeat(300)
                    + "\n\n"
                    + canonicalTail)
            .meetsRequirements(List.of("R1"))
            .hardConstraints(List.of("x".repeat(4_900)))
            .touchesFiles(List.of("backend/app/idempotency.py"))
            .build();
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Idempotent order creation.",
                        RequirementSpec.RequirementKind.FUNCTIONAL)))
            .tasks(List.of(task))
            .build();

    PlannedTask compiled = TaskExecutionBriefCompiler.compile(plan).tasks().getFirst();

    assertThat(compiled.description()).endsWith(canonicalTail);
  }

  @Test
  void truncationKeepsPlaywrightExecutionContractBeforeARequirementSignalTail() {
    String executableContract =
        "EXECUTABLE PLAYWRIGHT CONTRACT (binding): use the @playwright/test dependency and a"
            + " package.json test:e2e script that runs `playwright test`. The Playwright webServer"
            + " process lifecycle starts both uvicorn and Vite, polls health/readiness with a"
            + " bounded timeout, uses a fresh isolated DATABASE_URL, and teardown stops both"
            + " processes even when a test fails.";
    PlannedTask task =
        PlannedTask.builder()
            .id("T16")
            .title("Write Playwright browser end-to-end tests")
            .description(
                "Detailed browser journey and implementation guidance. ".repeat(240)
                    + "\n\n"
                    + executableContract
                    + "\n\n"
                    + "REQUIREMENT SIGNALS (binding): preserve oversell and rollback.")
            .hardConstraints(List.of("x".repeat(7_500)))
            .touchesFiles(
                List.of(
                    "frontend/e2e/package.json",
                    "frontend/e2e/playwright.config.ts",
                    "frontend/e2e/tests/full-flow.spec.ts"))
            .build();

    PlannedTask compiled =
        TaskExecutionBriefCompiler.compile(Plan.builder().tasks(List.of(task)).build())
            .tasks()
            .getFirst();

    assertThat(compiled.description())
        .contains(
            "@playwright/test",
            "test:e2e",
            "playwright test",
            "webServer",
            "uvicorn",
            "Vite",
            "health/readiness",
            "isolated DATABASE_URL",
            "teardown")
        .endsWith("REQUIREMENT SIGNALS (binding): preserve oversell and rollback.");
    assertThat(PlanContractQualityGate.violations(Plan.builder().tasks(List.of(compiled)).build()))
        .noneMatch(violation -> violation.contains("lacks executable contract parts"));
  }

  @Test
  void truncationKeepsEveryExplicitlyBindingContractAndProofParagraph() {
    String idempotency =
        "IDEMPOTENCY TRANSACTION (binding): claim the replay record and business mutation in one"
            + " transaction.";
    String persistence =
        "PERSISTENCE PROOF (binding): restart the documented process against the same isolated"
            + " database and verify public reads.";
    String producer =
        "**API PRODUCER CONTRACTS (binding; implement every operation):** GET /api/orders/{id}.";
    PlannedTask task =
        PlannedTask.builder()
            .id("T8")
            .title("Write executable acceptance coverage")
            .description(
                "Verbose implementation guidance. ".repeat(300)
                    + "\n\n"
                    + idempotency
                    + "\n\n"
                    + persistence
                    + "\n\n"
                    + producer
                    + "\n\n"
                    + "REQUIREMENT SIGNALS (binding): preserve atomicity and restart behavior.")
            .hardConstraints(List.of("x".repeat(7_500)))
            .touchesFiles(List.of("backend/tests/test_acceptance.py"))
            .build();

    PlannedTask compiled =
        TaskExecutionBriefCompiler.compile(Plan.builder().tasks(List.of(task)).build())
            .tasks()
            .getFirst();

    assertThat(compiled.description())
        .contains(idempotency, persistence, producer)
        .endsWith("REQUIREMENT SIGNALS (binding): preserve atomicity and restart behavior.");
  }

  @Test
  void acceptanceOwnerRetainsRollbackEvenWhenAnImplementationOwnerAlreadyMentionsIt() {
    RequirementSpec requirement =
        new RequirementSpec(
            "R23",
            "Black-box acceptance must assert transaction rollback.",
            RequirementSpec.RequirementKind.NON_FUNCTIONAL);
    PlannedTask implementation =
        PlannedTask.builder()
            .id("T8")
            .title("Implement transactional mutation")
            .description("Rollback the transaction when the second database write fails.")
            .touchesFiles(List.of("backend/app/service.py"))
            .meetsRequirements(List.of("R23"))
            .build();
    PlannedTask acceptance =
        PlannedTask.builder()
            .id("T16")
            .title("Write black-box acceptance tests")
            .description("Verbose browser journey. ".repeat(500))
            .touchesFiles(List.of("tests/acceptance/test_failure.py"))
            .meetsRequirements(List.of("R23"))
            .build();
    Plan normalized =
        TaskExpanderAgent.normalizeFinalPlan(
            Plan.builder()
                .requirements(List.of(requirement))
                .tasks(List.of(implementation, acceptance))
                .build());

    PlannedTask normalizedAcceptance = normalized.tasks().get(1);
    assertThat(normalizedAcceptance.description())
        .contains(
            "REQUIREMENT SIGNALS (binding; preserve these exact contract terms)",
            "R23 requires rollback");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();

    PlannedTask compiledAcceptance = TaskExecutionBriefCompiler.compile(normalized).tasks().get(1);
    assertThat(compiledAcceptance.description()).contains("R23 requires rollback");
  }

  @Test
  void failsClosedWhenExplicitlyBindingDescriptionContractsCannotFitBriefBudget() {
    String oversizedContract =
        "ATOMICITY CONTRACT (binding): " + "preserve-this-executable-clause ".repeat(30);
    PlannedTask task =
        PlannedTask.builder()
            .id("T8")
            .title("Oversized binding contract")
            .description(
                "Verbose head that requires compaction. ".repeat(20)
                    + "\n\n"
                    + oversizedContract
                    + "\n\n"
                    + "PERSISTENCE PROOF (binding): "
                    + "preserve-this-proof-clause ".repeat(20))
            .hardConstraints(List.of("x".repeat(11_500)))
            .touchesFiles(List.of("backend/tests/test_acceptance.py"))
            .build();

    assertThatThrownBy(
            () -> TaskExecutionBriefCompiler.compile(Plan.builder().tasks(List.of(task)).build()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("explicitly binding description contracts exceed");
  }

  @Test
  void failsClosedWhenAuthoritativeContractCannotFit() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T1")
            .title("Oversized contract")
            .meetsRequirements(List.of("R1"))
            .hardConstraints(List.of("x".repeat(TaskExecutionBriefCompiler.MAX_BRIEF_CHARS)))
            .build();
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1", "Required behavior.", RequirementSpec.RequirementKind.FUNCTIONAL)))
            .tasks(List.of(task))
            .build();

    assertThatThrownBy(() -> TaskExecutionBriefCompiler.compile(plan))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("authoritative task contract exceeds");
  }

  @Test
  void acceptsAComplexTaskWhoseStructuredContractFitsTheWorkerBudget() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T6")
            .title("Reservation lifecycle")
            .meetsRequirements(List.of("R1"))
            .hardConstraints(List.of("x".repeat(4_000)))
            .touchesFiles(List.of("backend/app/services/reservation_service.py"))
            .build();
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Reserve and release atomically.",
                        RequirementSpec.RequirementKind.FUNCTIONAL)))
            .tasks(List.of(task))
            .build();

    assertThat(TaskExecutionBriefCompiler.compile(plan).tasks()).hasSize(1);
  }

  @Test
  void rejectsUnknownRequirementReferences() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T1")
            .title("Unknown requirement")
            .meetsRequirements(List.of("R404"))
            .build();

    assertThatThrownBy(
            () -> TaskExecutionBriefCompiler.compile(Plan.builder().tasks(List.of(task)).build()))
        .hasMessageContaining("unknown requirement R404");
  }

  // --- Heuristic contradiction gates are advisory, not run-killers (2026-08-08 structural fix) ---
  //
  // The ~30-gate PlanContractQualityGate battery false-positives on legitimate plans often enough
  // that a correct plan used to fail to reach the plan gate as a coin flip. Those heuristic
  // findings
  // must now compile through as worker guidance, while genuinely structural / intent violations
  // stay
  // fatal.

  @Test
  void heuristicContradictionCompilesAsAdvisoryGuidanceInsteadOfKillingTheRun() {
    // A task that trips a Class-C heuristic detector — an unresolved implementation-stack choice
    // ("Flask or FastAPI") that the deterministic normalizer cannot auto-resolve — with valid
    // ownership and no source-route drift.
    PlannedTask task =
        PlannedTask.builder()
            .id("T1")
            .title("Build the HTTP service")
            .description(
                "Build the service. Choose either the Flask framework or the FastAPI framework for"
                    + " the server implementation.")
            .complexity(PlannedTask.Complexity.MEDIUM)
            .meetsRequirements(List.of("R1"))
            .touchesFiles(List.of("backend/app/main.py"))
            .build();
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1", "Serve HTTP.", RequirementSpec.RequirementKind.FUNCTIONAL)))
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .tasks(List.of(task))
            .build();

    // The heuristic fires ...
    assertThat(TaskExpanderAgent.advisoryPlanContractViolations(plan))
        .anyMatch(v -> v.contains("implementation choice unresolved"));

    // ... but the run is NOT killed: it compiles, and the finding is surfaced to the worker as a
    // non-blocking advisory hard constraint.
    Plan compiled = TaskExecutionBriefCompiler.compileExecutable(plan, "Serve HTTP.");
    PlannedTask compiledTask = compiled.tasks().getFirst();
    assertThat(compiledTask.hardConstraints())
        .anyMatch(
            c ->
                c.startsWith("ADVISORY (non-blocking):")
                    && c.contains("implementation choice unresolved"));
  }

  @Test
  void structuralAndIntentViolationsStayFatalWhileHeuristicsAreAdvisory() {
    // Requirement ownership (execution authority) is still fatal even though heuristics are not.
    Plan uncovered =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1", "Implement it", RequirementSpec.RequirementKind.FUNCTIONAL),
                    new RequirementSpec(
                        "R2", "Preserve it", RequirementSpec.RequirementKind.FUNCTIONAL)))
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("Implement it")
                        .description("Implement it")
                        .meetsRequirements(List.of("R1"))
                        .build()))
            .build();

    assertThatThrownBy(() -> TaskExecutionBriefCompiler.compileExecutable(uncovered))
        .isInstanceOf(TaskExecutionBriefCompiler.ExecutablePlanContractException.class)
        .hasMessageContaining("R2");
  }
}
