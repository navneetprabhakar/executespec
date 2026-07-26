package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class TaskExpanderSchemaRepairTest {

  @Test
  void retriesOnlyAbsentOrMalformedToolPayloads() {
    assertThat(
            TaskExpanderAgent.isSchemaRepairable(
                new IllegalStateException("TaskExpander did not call submitTaskDetail for T3")))
        .isTrue();
    assertThat(
            TaskExpanderAgent.isSchemaRepairable(
                new IllegalStateException("submitTaskDetail args are not valid JSON for T3")))
        .isTrue();
    assertThat(
            TaskExpanderAgent.isSchemaRepairable(
                new IllegalStateException("submitTaskDetail args exceed schema limits for T3")))
        .isTrue();
    assertThat(
            TaskExpanderAgent.isSchemaRepairable(
                new IllegalStateException("provider authentication failed")))
        .isFalse();
  }

  @Test
  void enforcesProviderIgnoredExpansionEnvelopeLimits() {
    assertThatThrownBy(
            () ->
                TaskExpanderAgent.validateExpansionEnvelope(
                    "T3", "x".repeat(6_001), List.of(), List.of(), List.of("R1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exceed schema limits");

    PlannedTask oversized =
        PlannedTask.builder().id("T3").title("Oversized").description("x".repeat(6_001)).build();
    Plan assembled = Plan.builder().tasks(List.of(oversized)).requirements(List.of()).build();
    assertThat(TaskExpanderAgent.finalPlanContractViolations(assembled))
        .containsExactly("T3: description exceeds 4000 characters after coherence");
  }

  @Test
  void providerIgnoredArrayLimitsCanBeBoundedWithoutAnotherLlmCall() {
    List<String> touchesFiles =
        TaskExpanderAgent.boundValues(
            java.util.stream.IntStream.range(0, 30)
                .mapToObj(i -> "src/feature_" + i + "/" + "x".repeat(300))
                .toList(),
            24,
            160);
    List<String> hardConstraints =
        TaskExpanderAgent.boundValues(
            java.util.stream.IntStream.range(0, 18)
                .mapToObj(i -> "constraint-" + i + "-" + "x".repeat(1_300))
                .toList(),
            12,
            360);
    List<String> requirements =
        TaskExpanderAgent.boundValues(
            java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> "REQ-" + i + "-" + "x".repeat(60))
                .toList(),
            16,
            40);

    TaskExpanderAgent.validateExpansionEnvelope(
        "T10", "Complete integration-test contract", touchesFiles, hardConstraints, requirements);

    assertThat(touchesFiles).hasSize(24).allMatch(value -> value.length() <= 160);
    assertThat(hardConstraints).hasSize(12).allMatch(value -> value.length() <= 360);
    assertThat(requirements).hasSize(16).allMatch(value -> value.length() <= 40);
  }

  @Test
  void oversizedDescriptionCompactionPreservesExecutableContractsFromTheTail() {
    String oversized =
        "Implement the service.\n"
            + "Detailed internal notes ".repeat(400)
            + "\nPOST /api/jobs/{id}/cancel returns 409 for an invalid transition."
            + "\nAcceptance tests run pytest against a migrated database transaction.";

    String compacted =
        TaskExpansionTextNormalizer.boundTextPreservingExecutableContracts(oversized, 4_000);

    assertThat(compacted)
        .hasSizeLessThanOrEqualTo(4_000)
        .contains("Implement the service")
        .contains("POST /api/jobs/{id}/cancel")
        .contains("Acceptance tests run pytest")
        .contains("Contract-preserving compaction");
  }

  @Test
  void finalNormalizationBoundsDescriptionWithoutInventingDomainContracts() {
    PlannedTask task =
        contractTask(
            "T4",
            "Implement the order flow. "
                + "x".repeat(4_850)
                + " POST /orders accepts Idempotency-Key as an optional header.",
            "Idempotency-Key is a required HTTP header for order creation.");
    Plan plan = Plan.builder().tasks(List.of(task)).requirements(List.of()).build();

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan);

    assertThat(TaskExpanderAgent.finalPlanContractViolations(normalized)).isEmpty();
    assertThat(normalized.tasks().getFirst().description()).hasSizeLessThanOrEqualTo(4_000);
    assertThat(normalized.tasks().getFirst().hardConstraints())
        .contains("Idempotency-Key is a required HTTP header for order creation.");
  }

  @Test
  void finalNormalizationUsesTheDeclaredLimitInsteadOfLegacy2400Compaction() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T12")
            .title("Acceptance suite")
            .description("Public HTTP acceptance details. " + "x".repeat(6_400))
            .touchesFiles(List.of("acceptance/test_full_flow.py"))
            .hardConstraints(List.of())
            .meetsRequirements(List.of())
            .dependsOn(List.of())
            .build();

    Plan normalized =
        TaskExpanderAgent.normalizeFinalPlan(
            Plan.builder().tasks(List.of(task)).requirements(List.of()).build());

    assertThat(normalized.tasks().getFirst().description())
        .hasSizeGreaterThan(2_400)
        .hasSizeLessThanOrEqualTo(4_000);
  }

  @Test
  void finalNormalizationKeepsCoherenceContractTailInsideEnvelope() {
    String bindingTail =
        "CONTRACT CORRECTION: The frontend order payload uses product_id and warehouse_id only.";
    PlannedTask task =
        PlannedTask.builder()
            .id("T5")
            .title("Typed frontend contract")
            .description(
                "Detailed generated API prose. " + "x".repeat(4_780) + "\n\n" + bindingTail)
            .hardConstraints(List.of())
            .build();

    Plan normalized =
        TaskExpanderAgent.normalizeFinalPlan(Plan.builder().tasks(List.of(task)).build());

    assertThat(normalized.tasks().getFirst().description())
        .hasSizeLessThanOrEqualTo(4_000)
        .endsWith(bindingTail);
    assertThat(TaskExpanderAgent.finalPlanContractViolations(normalized)).isEmpty();
  }

  @Test
  void safelyBoundsProviderOutputWhilePreservingAuthoritativeValuesFirst() {
    assertThat(TaskExpanderAgent.boundText("sentence. " + "x".repeat(2_500), 2_400))
        .hasSizeLessThanOrEqualTo(2_400)
        .startsWith("sentence.");
    assertThat(
            TaskExpanderAgent.boundValues(
                TaskExpanderAgent.mergeAuthoritativeValues(
                    List.of("canonical-a", "canonical-b"),
                    java.util.stream.IntStream.range(0, 30)
                        .mapToObj(i -> "generated-" + i)
                        .toList()),
                12,
                500))
        .hasSize(12)
        .startsWith("canonical-a", "canonical-b");
  }

  @Test
  void technicalSpacingDoesNotCorruptStandaloneDotInNpmPrefixCommands() {
    assertThat(
            TaskExpanderAgent.normalizeTechnicalSpacing(
                "`cd frontend && npm --prefix . run build`"))
        .contains("npm --prefix frontend run build")
        .doesNotContain("--prefix.run");
  }

  @Test
  void normalizesProviderSpacingInsideTechnicalVersionsAndFileNames() {
    assertThat(
            TaskExpanderAgent.normalizeTechnicalSpacing(
                "Use Python 3. 12 with app/models. py, pyproject. toml, and result. . ."))
        .isEqualTo("Use Python 3.12 with app/models.py, pyproject.toml, and result...");
  }

  @Test
  void preservesAuthoritativeSkeletonContractsAheadOfGeneratedValues() {
    assertThat(
            TaskExpanderAgent.mergeAuthoritativeValues(
                List.of("canonical", "shared"), List.of("shared", "task-specific")))
        .containsExactly("canonical", "shared", "task-specific");
  }

  @Test
  void rejectsDescriptionsThatContradictAuthoritativeSkeletonContracts() {
    PlannedTask build =
        contractTask(
            "T1",
            "Create build.sh that runs pip install and npm ci before compiling.",
            "Dependency installation must be a separate explicit setup command. Build and test"
                + " commands must never create an environment or run pip, npm install, npm ci, or"
                + " another package installer; they only compile using dependencies already"
                + " present.");
    PlannedTask migrations =
        contractTask(
            "T2",
            "Use Base.metadata.create_all for the test schema.",
            "Run the configured migration mechanism; do not fall back to ORM schema"
                + " auto-creation.");
    PlannedTask realHttp =
        contractTask(
            "T3",
            "Use a real HTTP client against the server with an in-memory SQLite database.",
            "Functional tests must exercise public behavior over real HTTP.",
            "Use a real HTTP client against the documented server.");
    PlannedTask header =
        contractTask(
            "T4",
            "POST /orders accepts an Idempotency-Key header (optional).",
            "Idempotency-Key is a required HTTP header for order creation.");
    PlannedTask schema =
        contractTask(
            "T5",
            "IdempotencyRecord has key_hash and request_body_hash fields.",
            "Use one exact IdempotencyRecord contract everywhere: key, scope, request_body_hash,"
                + " state, response_status, response_body.");
    PlannedTask ledger =
        contractTask(
            "T6",
            "Reserve by decrementing on_hand and incrementing reserved.",
            "Reservation accounting leaves Inventory.on_hand unchanged: reserve increments"
                + " reserved only.");
    PlannedTask incompleteSchema =
        contractTask(
            "T7",
            "IdempotencyRecord model fields are id, key (unique), response_body, status_code, and"
                + " created_at.",
            "Use one exact IdempotencyRecord contract everywhere: key, scope, request_body_hash,"
                + " state, nullable response_status, nullable response_body; enforce a database"
                + " unique constraint on (key, scope) with PROCESSING and COMPLETED states.");
    PlannedTask runTests =
        contractTask(
            "T8",
            "run_tests.sh creates a venv, runs pip install, npm ci, and then executes tests.",
            "Dependency installation must be a separate explicit setup command. Build and test"
                + " commands must never create an environment or run pip, npm install, npm ci, or"
                + " another package installer.");

    assertThat(violations(build)).anyMatch(value -> value.contains("installs dependencies"));
    assertThat(violations(migrations)).anyMatch(value -> value.contains("migration path"));
    assertThat(violations(realHttp)).anyMatch(value -> value.contains("file database"));
    assertThat(violations(header)).anyMatch(value -> value.contains("header optional"));
    assertThat(violations(schema)).anyMatch(value -> value.contains("canonical key/scope"));
    assertThat(violations(ledger)).anyMatch(value -> value.contains("mutates on_hand"));
    assertThat(violations(incompleteSchema))
        .contains("Task description defines an incomplete canonical IdempotencyRecord schema");
    assertThat(violations(runTests)).anyMatch(value -> value.contains("installs dependencies"));

    for (PlannedTask contradictory :
        List.of(build, migrations, realHttp, header, schema, ledger, incompleteSchema, runTests)) {
      PlannedTask repaired =
          TaskExpanderAgent.applyDeterministicSemanticFallback(
              contradictory, violations(contradictory));
      assertThat(violations(repaired))
          .as(repaired.id() + " must replace the contradictory clause, not append over it")
          .isEmpty();
    }
    PlannedTask repairedSchema =
        TaskExpanderAgent.applyDeterministicSemanticFallback(
            incompleteSchema, violations(incompleteSchema));
    assertThat(repairedSchema.description())
        .contains("request_body_hash", "PROCESSING|COMPLETED", "UniqueConstraint(key, scope)")
        .contains("CONTRACT CORRECTION:")
        .contains("status_code")
        .doesNotContain("key (unique)");
  }

  @Test
  void acceptanceTestAliasIsRewrittenDeterministicallyWithoutAnotherProviderCall() {
    PlannedTask acceptance =
        contractTask(
            "T15",
            "Start the real server, then query IdempotencyRecord fields idempotency_key and"
                + " scope_hash to assert that one durable replay row was created. Keep the"
                + " remaining black-box HTTP assertions intact.",
            "Use one exact IdempotencyRecord contract everywhere: key, scope, request_body_hash,"
                + " state, response_status, response_body.");

    List<String> violations = violations(acceptance);
    PlannedTask repaired =
        TaskExpanderAgent.applyDeterministicSemanticFallback(acceptance, violations);

    assertThat(violations).anyMatch(value -> value.contains("canonical key/scope"));
    assertThat(repaired.description())
        .contains(
            "IdempotencyRecord uses only key, scope, request_body_hash",
            "Keep the remaining black-box HTTP assertions intact")
        .doesNotContain("fields idempotency_key", "scope_hash to assert");
    assertThat(violations(repaired)).isEmpty();
  }

  @Test
  void acceptsDescriptionsThatStateTheCanonicalContracts() {
    PlannedTask task =
        contractTask(
            "T1",
            "build.sh compiles with preinstalled dependencies. Tests apply Alembic migrations to"
                + " a temporary file-backed SQLite database and call the server over real HTTP.",
            "Dependency installation must be a separate explicit setup command. Build and test"
                + " commands must never create an environment or run pip, npm install, npm ci, or"
                + " another package installer; they only compile using dependencies already"
                + " present.",
            "Run the configured migration mechanism; do not fall back to ORM schema"
                + " auto-creation.",
            "Functional tests must exercise public behavior over real HTTP.");

    assertThat(violations(task)).isEmpty();
  }

  @Test
  void quantityGuardValidationIsDerivedFromTheCitedRequirementAndRepairsQualifiedExpressions() {
    RequirementSpec requirement =
        new RequirementSpec(
            "R1",
            "Stock adjustments must reject a correction that would lower on-hand below reserved.",
            RequirementSpec.RequirementKind.FUNCTIONAL);
    PlannedTask task =
        PlannedTask.builder()
            .id("T5")
            .title("Implement stock adjustment service")
            .description(
                "Reject only when `on_hand + adjustment.quantity >= 0`; otherwise persist.")
            .meetsRequirements(List.of("R1"))
            .hardConstraints(List.of())
            .build();
    Plan citedPlan = Plan.builder().requirements(List.of(requirement)).tasks(List.of(task)).build();

    List<String> citedViolations = TaskExpanderAgent.semanticViolations(task, citedPlan);
    PlannedTask repaired =
        TaskExpanderAgent.applyDeterministicSemanticFallback(task, citedViolations);

    assertThat(citedViolations)
        .contains("Stock adjustment guard ignores reserved inventory availability");
    assertThat(repaired.description()).contains("on_hand + adjustment.quantity >= reserved");
    assertThat(TaskExpanderAgent.semanticViolations(repaired, citedPlan)).isEmpty();
    assertThat(
            TaskExpanderAgent.semanticViolations(
                task, Plan.builder().requirements(List.of()).tasks(List.of(task)).build()))
        .as("no hidden inventory policy may activate without a cited user requirement")
        .isEmpty();
  }

  @Test
  void allowsDependencyInstallationInReadmeSetupButNotInBuildOrTestScripts() {
    PlannedTask task =
        contractTask(
            "T16",
            "Create build.sh and run_tests.sh using already-installed dependencies. README.md"
                + " Setup documents `python -m pip install -r backend/requirements.txt` and `npm"
                + " --prefix frontend install` as explicit prerequisite commands. README Build"
                + " documents `./build.sh`; README Test documents `./run_tests.sh`.",
            "Dependency installation must be a separate explicit setup command. Build and test"
                + " commands must never create an environment or run pip, npm install, npm ci, or"
                + " another package installer.");

    assertThat(violations(task)).isEmpty();
  }

  @Test
  void schemaProducerCannotCollapseExplicitLineCollectionIntoParentColumns() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Model orders, reservation lines, and reservation lifecycle.",
                        RequirementSpec.RequirementKind.FUNCTIONAL)))
            .build();
    PlannedTask collapsed =
        PlannedTask.builder()
            .id("T2")
            .title("Implement data models and migration")
            .description("Reservation has product_id and quantity columns.")
            .meetsRequirements(List.of("R1"))
            .build();
    PlannedTask preserved =
        PlannedTask.builder()
            .id("T2")
            .title("Implement data models and migration")
            .description("Reservation owns a collection of ReservationLine rows.")
            .meetsRequirements(List.of("R1"))
            .build();
    PlannedTask equivalentCollection =
        PlannedTask.builder()
            .id("T2")
            .title("Implement data models and migration")
            .description("Reservation owns a one-to-many collection of ReservationItem rows.")
            .meetsRequirements(List.of("R1"))
            .build();

    assertThat(TaskExpanderAgent.semanticViolations(collapsed, plan))
        .containsExactly("R1 requires explicit 'reservation lines' collection cardinality");
    assertThat(TaskExpanderAgent.semanticViolations(preserved, plan)).isEmpty();
    assertThat(TaskExpanderAgent.semanticViolations(equivalentCollection, plan)).isEmpty();
  }

  @Test
  void explicitCollectionViolationHasDeterministicSchemaFallback() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Model reservation lines and lifecycle.",
                        RequirementSpec.RequirementKind.FUNCTIONAL)))
            .build();
    PlannedTask collapsed =
        PlannedTask.builder()
            .id("T2")
            .title("Implement models")
            .description("Reservation has product_id and quantity columns.")
            .meetsRequirements(List.of("R1"))
            .build();
    List<String> violations = TaskExpanderAgent.semanticViolations(collapsed, plan);

    PlannedTask repaired =
        TaskExpanderAgent.applyDeterministicSemanticFallback(collapsed, violations);

    assertThat(repaired.hardConstraints())
        .anyMatch(constraint -> constraint.contains("ReservationLine child rows"));
    assertThat(repaired.description())
        .contains("CONTRACT CORRECTION", "one Reservation parent per member");
    assertThat(TaskExpanderAgent.semanticViolations(repaired, plan)).isEmpty();
  }

  @Test
  void reservationLifecycleRequiresAParentAggregateRatherThanBareLines() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Model orders, reservation lines, and reservation lifecycle; release/cancel"
                            + " a reservation exactly once.",
                        RequirementSpec.RequirementKind.FUNCTIONAL)))
            .build();
    PlannedTask bareLines =
        PlannedTask.builder()
            .id("T2")
            .title("Implement data models and migration")
            .description("Order owns ReservationLine rows with a status on each line.")
            .meetsRequirements(List.of("R1"))
            .build();

    List<String> violations = TaskExpanderAgent.semanticViolations(bareLines, plan);
    PlannedTask repaired =
        TaskExpanderAgent.applyDeterministicSemanticFallback(bareLines, violations);

    assertThat(violations)
        .contains(
            "Plan requires a persisted Reservation aggregate owning ReservationLine children");
    assertThat(repaired.description())
        .contains(
            "Reservation is a persisted parent aggregate", "release/cancel targets Reservation.id");
    assertThat(TaskExpanderAgent.semanticViolations(repaired, plan)).isEmpty();
  }

  @Test
  void adjectivesBeforeOrderLinesDoNotBecomeFakeAggregates() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Reject duplicate order lines and accept multiple order lines.",
                        RequirementSpec.RequirementKind.FUNCTIONAL)))
            .build();
    PlannedTask task =
        PlannedTask.builder()
            .id("T3")
            .title("Implement order service schema validation")
            .description("Reject duplicate product IDs while accepting an OrderLine collection.")
            .meetsRequirements(List.of("R1"))
            .build();

    assertThat(TaskExpanderAgent.semanticViolations(task, plan)).isEmpty();
  }

  @Test
  void skuLineItemsDoNotInventSkuAggregateOrSkuLineModel() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T4")
            .title("Implement order and reservation service")
            .description("Create orders with multiple SKU line items and ReservationLine rows.")
            .meetsRequirements(List.of("R1"))
            .build();
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Create an order with multiple SKU line items.",
                        RequirementSpec.RequirementKind.FUNCTIONAL)))
            .tasks(List.of(task))
            .build();

    assertThat(TaskExpanderAgent.semanticViolations(task, plan)).isEmpty();
  }

  @Test
  void frontendSchemasAreNotTreatedAsPersistenceSchemaProducers() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Model reservation lines and reservation lifecycle.",
                        RequirementSpec.RequirementKind.FUNCTIONAL)))
            .build();
    PlannedTask frontend =
        PlannedTask.builder()
            .id("T7")
            .title("Scaffold React frontend and API schemas")
            .description("Define TypeScript response schemas and routes for the inventory UI.")
            .touchesFiles(List.of("frontend/src/types/api.ts", "frontend/src/App.tsx"))
            .meetsRequirements(List.of("R1"))
            .build();

    assertThat(TaskExpanderAgent.isPersistenceSchemaProducer(frontend, frontend.description()))
        .isFalse();
    assertThat(TaskExpanderAgent.semanticViolations(frontend, plan)).isEmpty();
  }

  @Test
  void taskCannotNegateGuardedWriteRequiredByItsCitedRequirement() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R8",
                        "Prevent overselling using database invariants and guarded writes.",
                        RequirementSpec.RequirementKind.NON_FUNCTIONAL)))
            .build();
    PlannedTask contradictory =
        PlannedTask.builder()
            .id("T6")
            .title("Implement reservation service")
            .description("Implement this without a conditional UPDATE with row-count verification.")
            .meetsRequirements(List.of("R8"))
            .build();

    assertThat(TaskExpanderAgent.semanticViolations(contradictory, plan))
        .containsExactly("R8 requires guarded writes but the task description negates them");
  }

  @Test
  void consumerCannotInventAliasesForEarlierIdempotencySchema() {
    PlannedTask schema =
        PlannedTask.builder()
            .id("T2")
            .title("Implement IdempotencyRecord model and migration")
            .description(
                "IdempotencyRecord has key, scope, request_body_hash and a unique constraint on"
                    + " (key, scope).")
            .build();
    PlannedTask consumer =
        PlannedTask.builder()
            .id("T6")
            .title("Implement reservation idempotency service")
            .description(
                "Use IdempotencyRecord with unique constraint on (idempotency_key) and compare"
                    + " scope_hash.")
            .build();
    Plan plan = Plan.builder().requirements(List.of()).tasks(List.of(schema, consumer)).build();

    assertThat(TaskExpanderAgent.semanticViolations(consumer, plan)).isEmpty();
    assertThat(TaskExpanderAgent.crossTaskContractViolations(plan))
        .containsExactly(
            "T6: IdempotencyRecord consumer invents idempotency_key but schema task T2 defines key"
                + " scoped by scope",
            "T6: IdempotencyRecord consumer invents scope_hash but schema task T2 defines"
                + " request_body_hash");
  }

  @Test
  void consumerMatchingEarlierIdempotencySchemaPasses() {
    PlannedTask schema =
        PlannedTask.builder()
            .id("T2")
            .title("Implement IdempotencyRecord model and migration")
            .description(
                "IdempotencyRecord has key, scope, request_body_hash and a unique constraint on"
                    + " (key, scope).")
            .build();
    PlannedTask consumer =
        PlannedTask.builder()
            .id("T6")
            .title("Implement reservation idempotency service")
            .description(
                "Insert IdempotencyRecord using key, scope, and request_body_hash; uniqueness is"
                    + " (key, scope).")
            .build();
    Plan plan = Plan.builder().requirements(List.of()).tasks(List.of(schema, consumer)).build();

    assertThat(TaskExpanderAgent.semanticViolations(consumer, plan)).isEmpty();
    assertThat(TaskExpanderAgent.crossTaskContractViolations(plan)).isEmpty();
  }

  @Test
  void orderFieldAndHttpHeaderAreNotIdempotencyRecordAliases() {
    PlannedTask schema =
        PlannedTask.builder()
            .id("T2")
            .title("Implement IdempotencyRecord model and migration")
            .description(
                "IdempotencyRecord has key, scope, request_body_hash and a unique constraint on"
                    + " (key, scope).")
            .build();
    PlannedTask consumer =
        PlannedTask.builder()
            .id("T4")
            .title("Implement order API and idempotency")
            .description(
                "Order.idempotency_key stores the accepted HTTP Idempotency-Key. Insert"
                    + " IdempotencyRecord using key, scope, and request_body_hash.")
            .hardConstraints(
                List.of(
                    "OrderCreate and the JSON body must not declare idempotency_key; the HTTP"
                        + " Idempotency-Key header is required."))
            .build();
    Plan plan = Plan.builder().requirements(List.of()).tasks(List.of(schema, consumer)).build();

    assertThat(TaskExpanderAgent.crossTaskContractViolations(plan)).isEmpty();
  }

  @Test
  void genericLineItemsDoesNotRequireLiteralLineItemModel() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Create an order with multiple line items.",
                        RequirementSpec.RequirementKind.FUNCTIONAL)))
            .build();
    PlannedTask task =
        PlannedTask.builder()
            .id("T2")
            .title("Implement order models and migration")
            .description("Order has a collection of OrderLine rows.")
            .meetsRequirements(List.of("R1"))
            .build();

    assertThat(TaskExpanderAgent.semanticViolations(task, plan)).isEmpty();
  }

  @Test
  void idempotencySchemaMustMakeConflictDetectionAndExactReplayFeasible() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R8",
                        "Idempotency-Key: same key replays exactly; conflicting payload returns"
                            + " 409.",
                        RequirementSpec.RequirementKind.NON_FUNCTIONAL)))
            .build();
    PlannedTask broken =
        PlannedTask.builder()
            .id("T2")
            .title("Implement reservation models and migration")
            .description("Reservation has idempotency_key and original_response fields.")
            .meetsRequirements(List.of("R8"))
            .build();

    assertThat(TaskExpanderAgent.semanticViolations(broken, plan))
        .contains(
            "Plan requires a separate durable IdempotencyRecord ownership/replay model",
            "Plan requires durable request hash plus exact response status/body fields");

    PlannedTask repaired =
        TaskExpanderAgent.applyDeterministicSemanticFallback(
            broken, TaskExpanderAgent.semanticViolations(broken, plan));
    assertThat(TaskExpanderAgent.semanticViolations(repaired, plan)).isEmpty();
  }

  @Test
  void idempotencyServiceMustClaimBeforeMutationInSameTransaction() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R8",
                        "Idempotency-Key: same key replays exactly; conflicting payload returns"
                            + " 409.",
                        RequirementSpec.RequirementKind.NON_FUNCTIONAL)))
            .build();
    PlannedTask broken =
        PlannedTask.builder()
            .id("T6")
            .title("Implement idempotency service")
            .description("check_or_claim commits a placeholder, then caller mutates business data.")
            .meetsRequirements(List.of("R8"))
            .build();

    assertThat(TaskExpanderAgent.semanticViolations(broken, plan))
        .contains("Plan requires idempotency ownership before mutation in the same transaction");

    PlannedTask repaired =
        TaskExpanderAgent.applyDeterministicSemanticFallback(
            broken, TaskExpanderAgent.semanticViolations(broken, plan));
    assertThat(TaskExpanderAgent.semanticViolations(repaired, plan)).isEmpty();
  }

  @Test
  void rejectsSeparatelyCommittedMiddlewareClaimAndIncompleteReplaySchema() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R8",
                        "Idempotency-Key: same key and payload replays exactly; conflicting reuse"
                            + " returns 409 under concurrent first use.",
                        RequirementSpec.RequirementKind.NON_FUNCTIONAL)))
            .build();
    PlannedTask broken =
        PlannedTask.builder()
            .id("T4")
            .title("Implement IdempotencyRecord model and idempotency middleware")
            .description(
                "IdempotencyRecord has idempotency_key unique, scope, request_payload_hash,"
                    + " response_status non-null, and response_body non-null. Commit after storing"
                    + " the record in a separate transaction, let the request proceed, then update"
                    + " it after the response is generated.")
            .meetsRequirements(List.of("R8"))
            .build();

    assertThat(TaskExpanderAgent.semanticViolations(broken, plan))
        .contains(
            "IdempotencyRecord uniqueness must cover both the key and its mutation scope",
            "Concurrent idempotency ownership needs PROCESSING/COMPLETED state and nullable"
                + " response fields until the winning transaction completes",
            "Idempotency ownership, business mutation, and completed replay record must share one"
                + " transaction; a separately committed middleware claim is not atomic");
  }

  @Test
  void deterministicFallbackCompletesPartialIdempotencyRecordSchema() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R8",
                        "Idempotency-Key: same key and payload replays exactly; conflicting reuse"
                            + " returns 409.",
                        RequirementSpec.RequirementKind.NON_FUNCTIONAL)))
            .build();
    PlannedTask partial =
        PlannedTask.builder()
            .id("T1")
            .title("Scaffold models and migration")
            .description(
                "Define IdempotencyRecord with key, scope, request_body_hash, response_status, and"
                    + " response_body.")
            .meetsRequirements(List.of("R8"))
            .hardConstraints(List.of())
            .build();

    List<String> violations = TaskExpanderAgent.semanticViolations(partial, plan);
    PlannedTask repaired =
        TaskExpanderAgent.applyDeterministicSemanticFallback(partial, violations);

    assertThat(violations)
        .contains(
            "IdempotencyRecord uniqueness must cover both the key and its mutation scope",
            "Concurrent idempotency ownership needs PROCESSING/COMPLETED state and nullable"
                + " response fields until the winning transaction completes");
    assertThat(TaskExpanderAgent.semanticViolations(repaired, plan)).isEmpty();
  }

  @Test
  void genericDomainSchemaDoesNotStealIdempotencyOwnershipFromDedicatedTask() {
    PlannedTask domainSchema =
        PlannedTask.builder()
            .id("T2")
            .title("Implement core domain models and migration")
            .description("Reservation stores the incoming idempotency_key for API correlation.")
            .meetsRequirements(List.of("R1"))
            .build();
    PlannedTask idempotencyOwner =
        PlannedTask.builder()
            .id("T7")
            .title("Implement reservation API and idempotency service")
            .description("Own the durable replay protocol.")
            .meetsRequirements(List.of("R1"))
            .build();
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Idempotency-Key with the same key replays exactly; conflicting reuse"
                            + " returns 409.",
                        RequirementSpec.RequirementKind.NON_FUNCTIONAL)))
            .tasks(List.of(domainSchema, idempotencyOwner))
            .build();

    assertThat(TaskExpanderAgent.isIdempotencySchemaOwner(domainSchema, plan)).isFalse();
    assertThat(TaskExpanderAgent.semanticViolations(domainSchema, plan)).isEmpty();
    assertThat(TaskExpanderAgent.isIdempotencySchemaOwner(idempotencyOwner, plan)).isTrue();
  }

  @Test
  void rejectsUnavailableSqliteServerUuidFunction() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Persist data in SQLite with real migrations.",
                        RequirementSpec.RequirementKind.NON_FUNCTIONAL)))
            .build();
    PlannedTask broken =
        PlannedTask.builder()
            .id("T2")
            .title("Implement models and migration")
            .description("Every UUID primary key uses server_default=func.uuid4().")
            .meetsRequirements(List.of("R1"))
            .build();

    assertThat(TaskExpanderAgent.semanticViolations(broken, plan))
        .containsExactly(
            "SQLite does not provide the proposed server-side UUID function; use a portable"
                + " Python-side UUID default and persist a SQLite-compatible UUID value");
  }

  @Test
  void deterministicallyRepairsUnavailableSqliteServerUuidFunction() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Persist data in SQLite with real migrations.",
                        RequirementSpec.RequirementKind.NON_FUNCTIONAL)))
            .build();
    PlannedTask broken =
        PlannedTask.builder()
            .id("T2")
            .title("Implement models and migration")
            .description("Every UUID primary key uses server_default=func.uuid4().")
            .hardConstraints(List.of())
            .meetsRequirements(List.of("R1"))
            .build();

    PlannedTask repaired =
        TaskExpanderAgent.applyDeterministicSemanticFallback(
            broken, TaskExpanderAgent.semanticViolations(broken, plan));

    assertThat(repaired.description())
        .contains("default=lambda: str(uuid.uuid4())")
        .doesNotContain("server_default=func.uuid4()");
    assertThat(repaired.hardConstraints())
        .anyMatch(value -> value.contains("never use an unavailable database-side UUID"));
    assertThat(TaskExpanderAgent.semanticViolations(repaired, plan)).isEmpty();
  }

  @Test
  void concurrentReleaseRequiresAtomicStateClaimBeforeStockRestore() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R7",
                        "Concurrent release must restore stock exactly once.",
                        RequirementSpec.RequirementKind.NON_FUNCTIONAL)))
            .build();
    PlannedTask broken =
        PlannedTask.builder()
            .id("T6")
            .title("Implement release reservation")
            .description(
                "release_reservation reads status, restores stock, then sets status released.")
            .meetsRequirements(List.of("R7"))
            .build();

    assertThat(TaskExpanderAgent.semanticViolations(broken, plan))
        .containsExactly(
            "Plan requires release to claim the state transition with a conditional status UPDATE"
                + " and rowcount check before restoring stock");

    PlannedTask repaired =
        TaskExpanderAgent.applyDeterministicSemanticFallback(
            broken, TaskExpanderAgent.semanticViolations(broken, plan));
    assertThat(TaskExpanderAgent.semanticViolations(repaired, plan)).isEmpty();
  }

  @Test
  void crossTaskValidationOwnsOrderStatusMismatchAfterExpansion() {
    PlannedTask producer =
        PlannedTask.builder()
            .id("T2")
            .title("Implement order models and migration")
            .description(
                "`Order`: id and created_at. `OrderLine`: order_id, product_id, quantity."
                    + " `Reservation`: id and status.")
            .build();
    PlannedTask consumer =
        PlannedTask.builder()
            .id("T6")
            .title("Implement reservation service")
            .description("Validate the order is in pending status before reserving.")
            .build();
    Plan plan = Plan.builder().requirements(List.of()).tasks(List.of(producer, consumer)).build();

    assertThat(TaskExpanderAgent.semanticViolations(consumer, plan)).isEmpty();
    assertThat(TaskExpanderAgent.crossTaskContractViolations(plan))
        .containsExactly(
            "T6: Order consumer requires status but schema task T2 does not define Order.status");
  }

  @Test
  void apiSchemaTaskIsNotMistakenForPersistentOrderSchemaProducer() {
    PlannedTask producer =
        PlannedTask.builder()
            .id("T2")
            .title("Implement core domain models and Alembic migration")
            .description("Order model has id and created_at. OrderLine has order_id and quantity.")
            .touchesFiles(List.of("backend/app/models.py", "backend/alembic/versions/001.py"))
            .build();
    PlannedTask apiSchemas =
        PlannedTask.builder()
            .id("T3")
            .title("Implement inventory API and Pydantic schemas")
            .description("OrderLineResponse schema exposes order status for clients.")
            .touchesFiles(List.of("backend/app/schemas.py", "backend/app/api.py"))
            .build();
    PlannedTask consumer =
        PlannedTask.builder()
            .id("T4")
            .title("Implement reservation service")
            .description("Require Order.status pending before reservation.")
            .touchesFiles(List.of("backend/app/services/reservations.py"))
            .build();
    Plan plan =
        Plan.builder()
            .requirements(List.of())
            .tasks(List.of(producer, apiSchemas, consumer))
            .build();

    assertThat(TaskExpanderAgent.crossTaskContractViolations(plan))
        .containsExactly(
            "T3: Order consumer requires status but schema task T2 does not define Order.status",
            "T4: Order consumer requires status but schema task T2 does not define Order.status");
  }

  @Test
  void deterministicSafetyEnrichmentRepairsProducerOwnedOrderStatusContract() {
    PlannedTask producer =
        PlannedTask.builder()
            .id("T2")
            .title("Implement order models and migration")
            .description(
                "`Order`: id and created_at. `OrderLine`: order_id, product_id, quantity."
                    + " `Reservation`: id and status.")
            .build();
    PlannedTask consumer =
        PlannedTask.builder()
            .id("T6")
            .title("Implement reservation service")
            .description("Validate Order.status is pending before reserving.")
            .build();
    Plan plan =
        Plan.builder()
            .requirements(List.of())
            .tasks(List.of(producer, consumer))
            .runtimeSurface(Plan.RuntimeSurface.CLI)
            .build();

    PlanSafetyContractEnricher.Result result = PlanSafetyContractEnricher.enrich(plan);

    assertThat(result.contractsApplied()).isEqualTo(1);
    assertThat(result.plan().tasks().getFirst().hardConstraints())
        .anyMatch(constraint -> constraint.contains("persisted Order.status"));
    assertThat(TaskExpanderAgent.crossTaskContractViolations(result.plan())).isEmpty();
  }

  @Test
  void propagatedReservationConstraintsDoNotTurnEveryModelTaskIntoAggregateOwner() {
    RequirementSpec requirement =
        new RequirementSpec(
            "R1",
            "Persist a Reservation aggregate with reservation lines and release/cancel a"
                + " reservation exactly once.",
            RequirementSpec.RequirementKind.FUNCTIONAL);
    String propagated =
        "Reservation is a persisted parent aggregate that owns ReservationLine children.";
    PlannedTask inventory =
        PlannedTask.builder()
            .id("T2")
            .title("Implement warehouse and inventory models and migration")
            .description("Persist Warehouse and Inventory tables.")
            .touchesFiles(
                List.of(
                    "backend/app/models/inventory.py", "backend/alembic/versions/001_inventory.py"))
            .hardConstraints(List.of(propagated))
            .build();
    PlannedTask order =
        PlannedTask.builder()
            .id("T4")
            .title("Implement order model and migration")
            .description("Persist Order and OrderLine with Order.status PENDING or RESERVED.")
            .touchesFiles(
                List.of("backend/app/models/order.py", "backend/alembic/versions/002_order.py"))
            .hardConstraints(List.of(propagated))
            .build();
    PlannedTask reservation =
        PlannedTask.builder()
            .id("T5")
            .title("Implement reservation model and service")
            .description(
                "Persist Reservation parent with lifecycle status and one-to-many"
                    + " ReservationLine children.")
            .touchesFiles(List.of("backend/app/models/reservation.py"))
            .hardConstraints(List.of(propagated))
            .meetsRequirements(List.of("R1"))
            .build();
    Plan plan =
        Plan.builder()
            .requirements(List.of(requirement))
            .tasks(List.of(inventory, order, reservation))
            .build();

    assertThat(TaskExpanderAgent.finalPlanContractViolations(plan))
        .noneMatch(
            value ->
                value.startsWith("T2: Plan requires a persisted Reservation")
                    || value.startsWith("T4: Plan requires a persisted Reservation"));
  }

  @Test
  void canonicalOrderLifecycleConstraintRemainsExecutableAfterCoherenceRewrite() {
    PlannedTask producer =
        PlannedTask.builder()
            .id("T4")
            .title("Implement order models and Alembic migration")
            .description(
                "`Order`: id and created_at. `OrderLine`: order_id, product_id, quantity."
                    + " `Reservation`: id and status.")
            .hardConstraints(
                List.of(
                    "Order.status lifecycle values are exactly PENDING and RESERVED. New and"
                        + " released orders are PENDING; successful reservation transitions"
                        + " PENDING→RESERVED."))
            .touchesFiles(List.of("backend/app/models.py", "backend/alembic/versions/001.py"))
            .build();
    PlannedTask consumer =
        PlannedTask.builder()
            .id("T5")
            .title("Implement reservation service")
            .description("Require Order.status PENDING before reservation.")
            .touchesFiles(List.of("backend/app/services/reservations.py"))
            .build();
    Plan plan =
        Plan.builder()
            .requirements(List.of())
            .tasks(List.of(producer, consumer))
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .build();

    assertThat(TaskExpanderAgent.crossTaskContractViolations(plan)).isEmpty();

    PlanSafetyContractEnricher.Result enriched = PlanSafetyContractEnricher.enrich(plan);
    assertThat(enriched.plan().tasks().getFirst().hardConstraints())
        .noneMatch(
            constraint -> constraint.contains("Define and migrate a persisted Order.status"));
    assertThat(TaskExpanderAgent.finalPlanContractViolations(enriched.plan())).isEmpty();
  }

  @Test
  void constraintPrioritizationIsBoundedAndRelevanceDrivenWithoutProductAllowLists() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T4")
            .title("Implement idempotent order creation")
            .description("Create Order and IdempotencyRecord in one transaction.")
            .touchesFiles(
                List.of("backend/app/services/order_service.py", "backend/app/models/order.py"))
            .hardConstraints(
                List.of(
                    "Reservation accounting leaves Inventory.on_hand unchanged.",
                    "Reservation.status lifecycle is exactly ACTIVE and RELEASED.",
                    "Reservation is one persisted parent aggregate with ReservationLine children.",
                    "Cancellation atomically claims ACTIVE to RELEASED.",
                    "Use synchronous SQLAlchemy sessions.",
                    "Use canonical JSON errors.",
                    "Use the authenticated principal.",
                    "Apply Alembic migrations.",
                    "Do not create duplicate model files.",
                    "Order.status lifecycle is PENDING and RESERVED.",
                    "Tests use migrated schemas.",
                    "Use one exact IdempotencyRecord contract everywhere: key, scope,"
                        + " request_body_hash, state, nullable response_status, nullable"
                        + " response_body; use PROCESSING/COMPLETED and unique (key, scope).",
                    "Idempotency ownership and business mutation share one transaction."))
            .build();

    List<String> prioritized = TaskExpansionPlanContract.prioritizeHardConstraints(task);

    assertThat(prioritized)
        .hasSizeLessThanOrEqualTo(12)
        .anyMatch(value -> value.contains("one exact IdempotencyRecord contract everywhere"));
  }

  @Test
  void canonicalIdempotencyStateMachineRejectsAndRepairsPendingState() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T5")
            .title("Implement idempotent order creation")
            .description(
                "IdempotencyRecord fields are key, scope, request_body_hash, state"
                    + " (PROCESSING|COMPLETED), nullable response_status, nullable response_body,"
                    + " and UniqueConstraint(key, scope). If record state==PENDING, replay its"
                    + " response.")
            .hardConstraints(
                List.of(
                    "Use one exact IdempotencyRecord contract everywhere: key, scope,"
                        + " request_body_hash, state, nullable response_status, and nullable"
                        + " response_body; use PROCESSING/COMPLETED and unique (key, scope)."))
            .build();
    Plan plan = Plan.builder().requirements(List.of()).tasks(List.of(task)).build();

    assertThat(TaskExpanderAgent.semanticViolations(task, plan))
        .contains("IdempotencyRecord state machine uses PENDING outside PROCESSING/COMPLETED");

    PlannedTask repaired =
        TaskExpanderAgent.applyDeterministicSemanticFallback(
            task, TaskExpanderAgent.semanticViolations(task, plan));
    assertThat(repaired.description())
        .contains("state==COMPLETED")
        .doesNotContain("state==PENDING");
    assertThat(TaskExpanderAgent.semanticViolations(repaired, plan)).isEmpty();
  }

  @Test
  void canonicalIdempotencyStateMachineRejectsAndRepairsBusinessAggregateStates() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T6")
            .title("Complete idempotent reservation")
            .description(
                "IdempotencyRecord fields are key, scope, request_body_hash, state"
                    + " (PROCESSING|COMPLETED), nullable response_status populated when DRAFT,"
                    + " nullable response_body, and UniqueConstraint(key, scope). The complete"
                    + " operation updates the IdempotencyRecord to DRAFT after storing the"
                    + " response.")
            .hardConstraints(
                List.of(
                    "Use one exact IdempotencyRecord contract everywhere: key, scope,"
                        + " request_body_hash, state, nullable response_status, and nullable"
                        + " response_body; use PROCESSING/COMPLETED and unique (key, scope)."))
            .build();
    Plan plan = Plan.builder().requirements(List.of()).tasks(List.of(task)).build();

    assertThat(TaskExpanderAgent.semanticViolations(task, plan))
        .contains(
            "IdempotencyRecord state machine uses a business state outside"
                + " PROCESSING/COMPLETED");

    PlannedTask repaired =
        TaskExpanderAgent.applyDeterministicSemanticFallback(
            task, TaskExpanderAgent.semanticViolations(task, plan));
    assertThat(repaired.description())
        .contains("response_status populated when COMPLETED")
        .contains("updates the IdempotencyRecord to COMPLETED")
        .doesNotContain("when DRAFT", "IdempotencyRecord to DRAFT");
    assertThat(TaskExpanderAgent.semanticViolations(repaired, plan)).isEmpty();
  }

  @Test
  void canonicalIdempotencyStateMachineDoesNotConfuseOrderBusinessState() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T5")
            .title("Implement idempotent order creation")
            .description(
                "Define IdempotencyRecord with key, scope, request_body_hash, state"
                    + " (PROCESSING|COMPLETED), nullable response_status, nullable response_body,"
                    + " and UniqueConstraint(key, scope). Create the Order record with RESERVED"
                    + " status after inventory is claimed.")
            .hardConstraints(
                List.of(
                    "Use one exact IdempotencyRecord contract everywhere: key, scope,"
                        + " request_body_hash, state, nullable response_status, and nullable"
                        + " response_body; use PROCESSING/COMPLETED and unique (key, scope)."))
            .build();
    Plan plan = Plan.builder().requirements(List.of()).tasks(List.of(task)).build();

    assertThat(TaskExpanderAgent.semanticViolations(task, plan))
        .doesNotContain(
            "IdempotencyRecord state machine uses a business state outside"
                + " PROCESSING/COMPLETED");
  }

  @Test
  void canonicalAliasCorrectionDoesNotRewriteItsOwnForbiddenAliasNames() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T4")
            .title("Implement IdempotencyRecord")
            .description(
                "IdempotencyRecord uses idempotency_key and scope_hash."
                    + " On IdempotencyRecord, idempotency_key and scope_hash are forbidden"
                    + " aliases.")
            .hardConstraints(
                List.of(
                    "Use one exact IdempotencyRecord contract everywhere: key, scope,"
                        + " request_body_hash, state, nullable response_status, and nullable"
                        + " response_body; use PROCESSING/COMPLETED and unique (key, scope)."))
            .build();
    Plan plan = Plan.builder().requirements(List.of()).tasks(List.of(task)).build();

    PlannedTask repaired =
        TaskExpanderAgent.applyDeterministicSemanticFallback(
            task, TaskExpanderAgent.semanticViolations(task, plan));

    assertThat(repaired.description())
        .contains("IdempotencyRecord uses only key, scope")
        .contains("idempotency_key and scope_hash are forbidden aliases")
        .doesNotContain("key and scope are forbidden aliases");
  }

  @Test
  void compactionPreservesExactOperationsWithoutNeighboringProseCorruption() {
    String longDescription =
        "Build order screens. ".repeat(500)
            + "\n- reserveOrder(id) calls POST /api/orders${id}/reserve with a header.";

    String normalized =
        TaskExpansionTextNormalizer.boundTextPreservingExecutableContracts(
            TaskExpansionTextNormalizer.normalizeTechnicalSpacing(longDescription), 2_000);

    assertThat(normalized)
        .contains("POST /api/orders/{id}/reserve")
        .doesNotContain("POST /api/orders/build", "/reserveBuild order", "CANCELLEDLED");
  }

  @Test
  void normalizationRepairsDuplicatedLifecycleSuffixes() {
    assertThat(
            TaskExpansionTextNormalizer.normalizeTechnicalSpacing(
                "Statuses: DRAFT, RESERVEDVED, COMMITTEDTED, CANCELLEDLED."))
        .isEqualTo("Statuses: DRAFT, RESERVED, COMMITTED, CANCELLED.");
  }

  @Test
  void sqliteIdempotencyNormalizationRemovesBusinessStatePollingAndOrderAlias() {
    String normalized =
        PlanSafetyTextSupport.normalizeSqliteIdempotencyAlgorithm(
            "Insert a DRAFT record with state='DRAFT', order_id, and key. If COMPLETED with"
                + " matching order_id, replay. If PROCESSING, poll up to 200ms.");

    assertThat(normalized)
        .contains("PROCESSING record", "state='PROCESSING'", "same request_body_hash")
        .contains("BEGIN IMMEDIATE")
        .doesNotContain("DRAFT record", "state='DRAFT'", "matching order_id", "poll up to");
  }

  @Test
  void browserE2eDirectApiSetupIsRejectedAndRewrittenToUiInteractions() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T14")
            .title("Playwright end-to-end customer journeys")
            .description(
                "Setup via API: use httpx to create warehouses and products. Open the frontend"
                    + " and verify via API that inventory changed.")
            .touchesFiles(List.of("e2e/tests/full-workflow.spec.ts"))
            .hardConstraints(List.of())
            .build();
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "End-to-end tests launch the real backend and frontend.",
                        RequirementSpec.RequirementKind.FUNCTIONAL)))
            .tasks(List.of(task))
            .build();

    assertThat(TaskExpanderAgent.semanticViolations(task, plan))
        .contains("Browser E2E task replaces required UI workflow with direct API operations");

    PlannedTask repaired =
        TaskExpanderAgent.applyDeterministicSemanticFallback(
            task, TaskExpanderAgent.semanticViolations(task, plan));
    assertThat(repaired.description())
        .contains("BROWSER JOURNEY CONTRACT")
        .contains("through Playwright page interactions")
        .doesNotContain("Setup via API", "use httpx", "verify via API");
    assertThat(TaskExpanderAgent.semanticViolations(repaired, plan)).isEmpty();
  }

  @Test
  void idempotencyConsumerAssertionDoesNotPretendToDefineTheSchema() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T11")
            .title("Implement black-box acceptance tests")
            .description(
                "Assert concurrent identical requests create one IdempotencyRecord and replay the"
                    + " committed response without duplicate Order rows.")
            .hardConstraints(
                List.of(
                    "Use one exact IdempotencyRecord contract everywhere: key, scope,"
                        + " request_body_hash, state, nullable response_status, and nullable"
                        + " response_body; use PROCESSING/COMPLETED and unique (key, scope)."))
            .touchesFiles(List.of("tests/acceptance/test_orders.py"))
            .build();
    Plan plan = Plan.builder().requirements(List.of()).tasks(List.of(task)).build();

    assertThat(TaskExpanderAgent.semanticViolations(task, plan))
        .doesNotContain(
            "Task description defines an incomplete canonical IdempotencyRecord schema");
  }

  @Test
  void idempotencyFieldVerificationDoesNotPretendToDefineTheSchema() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T11")
            .title("Implement black-box idempotency tests")
            .description(
                "Verify the IdempotencyRecord fields: key, scope, request_body_hash, state,"
                    + " response_status, and response_body are populated after the replay.")
            .hardConstraints(
                List.of(
                    "Use one exact IdempotencyRecord contract everywhere: key, scope,"
                        + " request_body_hash, state, nullable response_status, and nullable"
                        + " response_body; use PROCESSING/COMPLETED and unique (key, scope)."))
            .touchesFiles(List.of("tests/acceptance/test_idempotency.py"))
            .build();
    Plan plan = Plan.builder().requirements(List.of()).tasks(List.of(task)).build();

    assertThat(TaskExpanderAgent.semanticViolations(task, plan))
        .doesNotContain(
            "Task description defines an incomplete canonical IdempotencyRecord schema");
  }

  @Test
  void repeatedItemRouteRemainsUnambiguousWhenCollectionAndItemBulletsAreCompacted() {
    String description =
        "Router: - GET /api/orders — list orders - POST /api/orders — create order - GET"
            + " /api/orders/{order_id} — single order with lines. Preserved endpoints: GET"
            + " /api/orders GET /api/orders/{order_id}.";

    assertThat(TaskExpansionRouteContract.ambiguousDuplicateReadRoutes(description)).isEmpty();
  }

  @Test
  void rejectsLiteralParameterizedRouteWithBothCollectionAndItemSemantics() {
    String description =
        "GET /api/orders/{order_id} — list all orders. GET /api/orders/{order_id} — single item"
            + " detail.";

    assertThat(TaskExpansionRouteContract.ambiguousDuplicateReadRoutes(description))
        .containsExactly("/api/orders/{order_id}");
  }

  @Test
  void deterministicSafetyEnrichmentCanonicalizesIdempotencyAliasesAcrossTasks() {
    PlannedTask producer =
        PlannedTask.builder()
            .id("T2")
            .title("Implement IdempotencyRecord model and migration")
            .description(
                "IdempotencyRecord has key, scope, request_body_hash, state, nullable"
                    + " response_status, nullable response_body, and a unique constraint on (key,"
                    + " scope).")
            .touchesFiles(List.of("backend/app/models.py", "backend/alembic/versions/001.py"))
            .build();
    PlannedTask consumer =
        PlannedTask.builder()
            .id("T3")
            .title("Implement idempotency service")
            .description("Read IdempotencyRecord.idempotency_key and compare scope_hash.")
            .touchesFiles(List.of("backend/app/idempotency.py"))
            .build();
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Idempotency-Key with the same key replays exactly; conflicting reuse"
                            + " returns 409.",
                        RequirementSpec.RequirementKind.NON_FUNCTIONAL)))
            .tasks(List.of(producer, consumer))
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .build();

    PlanSafetyContractEnricher.Result result = PlanSafetyContractEnricher.enrich(plan);

    assertThat(result.plan().tasks().get(1).description())
        .contains("CONTRACT CORRECTION", "idempotency_key and scope_hash are forbidden aliases");
    assertThat(TaskExpanderAgent.crossTaskContractViolations(result.plan())).isEmpty();
  }

  @Test
  void deterministicSafetyEnrichmentUnifiesSanitizedErrorEnvelope() {
    PlannedTask api =
        PlannedTask.builder()
            .id("T5")
            .title("Implement HTTP error handling")
            .description("Return a flat {error, detail} response from exception handlers.")
            .build();
    PlannedTask acceptance =
        PlannedTask.builder()
            .id("T12")
            .title("Black-box acceptance tests for sanitized errors")
            .description("Assert nested error.code and error.message through HTTP.")
            .build();
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Return stable sanitized JSON errors.",
                        RequirementSpec.RequirementKind.NON_FUNCTIONAL)))
            .tasks(List.of(api, acceptance))
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .build();

    PlanSafetyContractEnricher.Result result = PlanSafetyContractEnricher.enrich(plan);

    assertThat(result.plan().tasks())
        .allSatisfy(
            task -> {
              assertThat(task.description()).contains("binding contract supersedes");
              assertThat(task.hardConstraints())
                  .anyMatch(constraint -> constraint.contains("STABLE_CODE"));
            });
  }

  @Test
  void blackBoxVerificationDoesNotBecomeProductionBehaviorOwner() {
    PlannedTask acceptance =
        PlannedTask.builder()
            .id("T12")
            .title("Independent black-box acceptance suite")
            .description(
                "Use Playwright and public HTTP to exercise the idempotency endpoint and issue"
                    + " concurrent release reservation requests.")
            .touchesFiles(
                List.of(
                    "acceptance/tests/test_reservation_http.py",
                    "frontend/e2e/reservation.spec.ts"))
            .build();
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Idempotency-Key same-key requests replay exactly and conflicting reuse"
                            + " returns 409.",
                        RequirementSpec.RequirementKind.NON_FUNCTIONAL),
                    new RequirementSpec(
                        "R2",
                        "Concurrent release/cancel restores stock exactly once.",
                        RequirementSpec.RequirementKind.NON_FUNCTIONAL)))
            .tasks(List.of(acceptance))
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .build();

    assertThat(TaskExpanderAgent.isImplementationTask(acceptance)).isFalse();
    assertThat(TaskExpanderAgent.semanticViolations(acceptance, plan)).isEmpty();
  }

  @Test
  void finalNormalizationRepairsTechnicalSpacingBeforeContractValidation() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T1")
            .title("Scaffold")
            .description(
                "Use SQLAlchemy 2. 0, `backend. app. database. Base`, `vite. config. ts`, and"
                    + " `sqlite:///. /app. db`.")
            .build();
    Plan plan = Plan.builder().tasks(List.of(task)).build();

    String normalized = TaskExpanderAgent.normalizeFinalPlan(plan).tasks().getFirst().description();

    assertThat(normalized)
        .contains("SQLAlchemy 2.0")
        .contains("`backend.app.database.Base`")
        .contains("`vite.config.ts`")
        .contains("`sqlite:///./app.db`");
  }

  @Test
  void finalNormalizationCapsConstraintsAndDropsRepeatedGlobalCopiesFirst() {
    List<String> constraints = new java.util.ArrayList<>();
    constraints.add("Backend: Python 3.12 and FastAPI.");
    constraints.add("Frontend: React 18 and TypeScript.");
    constraints.add("No authentication and no external SaaS dependencies.");
    constraints.add("Use integer minor units for prices and integer quantities.");
    for (int index = 1; index <= 12; index++) {
      constraints.add("Task-specific contract " + index);
    }
    PlannedTask task =
        PlannedTask.builder()
            .id("T1")
            .title("Contract-heavy task")
            .description("Implement the bounded task.")
            .hardConstraints(constraints)
            .build();

    PlannedTask normalized =
        TaskExpanderAgent.normalizeFinalPlan(Plan.builder().tasks(List.of(task)).build())
            .tasks()
            .getFirst();

    assertThat(normalized.hardConstraints())
        .hasSize(12)
        .allMatch(value -> value.startsWith("Task-specific contract"));
    assertThat(
            TaskExpanderAgent.finalPlanContractViolations(
                Plan.builder().tasks(List.of(normalized)).build()))
        .isEmpty();
  }

  @Test
  void rootBuildFallbackPreservesNumberedAcceptanceSuite() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T8")
            .title("Root build and acceptance")
            .description(
                "Root build.sh runs cd backend && python -m compileall app, then cd ../frontend"
                    + " && npm run build. 1. Atomic rollback over real HTTP. 2. Idempotent replay."
                    + " 3. Idempotency conflict. 4. Double cancellation restores stock once.")
            .hardConstraints(
                List.of(
                    "The root ./build.sh must compile/import-check the Python 3.12 backend and run"
                        + " the React production build via npm --prefix frontend run build."))
            .build();

    PlannedTask normalized =
        TaskExpanderAgent.normalizeFinalPlan(Plan.builder().tasks(List.of(task)).build())
            .tasks()
            .getFirst();

    assertThat(normalized.description())
        .contains("python3.12 -m compileall -q backend/app")
        .contains("npm --prefix frontend run build")
        .contains("Atomic rollback over real HTTP")
        .contains("Idempotent replay")
        .contains("Idempotency conflict")
        .contains("Double cancellation restores stock once")
        .doesNotContain("cd backend", "cd ../frontend");
  }

  @Test
  void rootBuildFallbackNormalizesCompileallTargetVariants() {
    for (String command :
        List.of(
            "cd backend && python -m compileall -q . && cd .. && npm --prefix frontend run build",
            "cd backend && (python3.12 -m compileall app) && cd frontend && npm run build",
            "cd backend && python -m compileall backend && npm --prefix frontend run build")) {
      PlannedTask task =
          PlannedTask.builder()
              .id("T10")
              .title("Root build")
              .description("Root build.sh runs " + command + ". Preserve integration coverage.")
              .hardConstraints(
                  List.of(
                      "The root ./build.sh must compile/import-check the Python 3.12 backend and"
                          + " run the React production build via npm --prefix frontend run build."))
              .build();

      PlannedTask normalized =
          TaskExpanderAgent.normalizeFinalPlan(Plan.builder().tasks(List.of(task)).build())
              .tasks()
              .getFirst();

      assertThat(normalized.description())
          .contains("python3.12 -m compileall -q backend/app")
          .contains("npm --prefix frontend run build")
          .contains("Preserve integration coverage")
          .doesNotContain("cd backend");
      assertThat(
              TaskExpanderAgent.finalPlanContractViolations(
                  Plan.builder().tasks(List.of(normalized)).build()))
          .isEmpty();
    }
  }

  @Test
  void rootBuildFallbackNormalizesMultilineWorkingDirectoryChanges() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T16")
            .title("Finalize build.sh and run_tests.sh")
            .description(
                """
                Root build.sh:
                - cd backend
                - python -m compileall -q app
                - cd ..
                - npm --prefix frontend run build
                """)
            .hardConstraints(
                List.of(
                    "The root ./build.sh must compile/import-check the Python 3.12 backend and run"
                        + " the React production build via npm --prefix frontend run build."))
            .build();

    Plan normalized =
        TaskExpanderAgent.normalizeFinalPlan(Plan.builder().tasks(List.of(task)).build());

    assertThat(normalized.tasks().getFirst().description())
        .contains("python3.12 -m compileall -q backend/app")
        .contains("npm --prefix frontend run build")
        .doesNotContain("cd backend", "cd ..");
    assertThat(TaskExpanderAgent.finalPlanContractViolations(normalized)).isEmpty();
  }

  @Test
  void buildTargetCannotHideDependencyInstallationBehindSeparateSetupTarget() {
    PlannedTask task =
        contractTask(
            "T1",
            "Makefile targets:\n- setup (pip install -e . and npm ci)\n- build (pip install -e .;"
                + " npm run build)",
            "Build and test commands must never create an environment or run pip, npm install,"
                + " npm ci, or another package installer.");

    PlannedTask normalized =
        TaskExpanderAgent.normalizeFinalPlan(Plan.builder().tasks(List.of(task)).build())
            .tasks()
            .getFirst();

    assertThat(violations(normalized)).isEmpty();
    assertThat(normalized.description())
        .contains("already-installed dependencies")
        .doesNotContain("build (pip install");
  }

  @Test
  void realHttpAcceptanceRejectsHttpxAppBindingAndDependencyOverride() {
    PlannedTask task =
        contractTask(
            "T4",
            "Create httpx.AsyncClient with app=app and a get_db override against a test database.",
            "Functional tests must exercise public behavior over real HTTP against the documented"
                + " server.");

    PlannedTask normalized =
        TaskExpanderAgent.normalizeFinalPlan(Plan.builder().tasks(List.of(task)).build())
            .tasks()
            .getFirst();

    assertThat(violations(normalized)).isEmpty();
    assertThat(normalized.description())
        .contains("Launch the documented server as a subprocess")
        .doesNotContain("app=app", "get_db override");
  }

  @Test
  void frontendOrderConsumerUsesProducerIdsAndNoInventedCustomerField() {
    PlannedTask task =
        contractTask(
            "T7",
            "POST /api/orders body: {customer_name: string, items: [{product_sku: string,"
                + " warehouse_code: string, quantity: int}]}. Return 400 on insufficient stock.",
            "OrderCreate items use product_id and warehouse_id (integer FK values), not product_sku"
                + " or warehouse_code strings.");

    PlannedTask normalized =
        TaskExpanderAgent.normalizeFinalPlan(Plan.builder().tasks(List.of(task)).build())
            .tasks()
            .getFirst();

    assertThat(violations(normalized)).isEmpty();
    assertThat(normalized.description())
        .contains("product_id", "warehouse_id", "422 on insufficient stock")
        .doesNotContain("customer_name: string", "product_sku: string", "warehouse_code: string");
  }

  @Test
  void globalIdempotencyRequirementRejectsOptionalHeaderWithoutTaskConstraint() {
    PlannedTask consumer =
        PlannedTask.builder()
            .id("T5")
            .title("Typed order API client")
            .description(
                "POST /api/orders accepts OrderCreate plus optional Idempotency-Key header.")
            .hardConstraints(List.of())
            .meetsRequirements(List.of("R1"))
            .build();
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Idempotency-Key: same key and request replays; conflicting reuse returns"
                            + " 409.",
                        RequirementSpec.RequirementKind.FUNCTIONAL)))
            .tasks(List.of(consumer))
            .build();

    assertThat(TaskExpanderAgent.semanticViolations(consumer, plan))
        .contains("Task description makes the required Idempotency-Key header optional");
  }

  @Test
  void crossTaskContractRejectsInventedCustomerField() {
    PlannedTask producer =
        PlannedTask.builder()
            .id("T3")
            .title("Implement order models")
            .description(
                "Order model has id, status, and OrderItem children. OrderCreate has items with"
                    + " product_id, warehouse_id, and quantity.")
            .touchesFiles(List.of("backend/app/models.py"))
            .hardConstraints(List.of())
            .build();
    PlannedTask consumer =
        PlannedTask.builder()
            .id("T7")
            .title("Build frontend order creation")
            .description(
                "POST /api/orders with OrderCreate body {customer_name, items: [{product_id,"
                    + " warehouse_id, quantity}]}.")
            .touchesFiles(List.of("frontend/src/OrderForm.tsx"))
            .hardConstraints(List.of())
            .build();
    Plan plan = Plan.builder().tasks(List.of(producer, consumer)).build();

    assertThat(TaskExpanderAgent.crossTaskContractViolations(plan))
        .contains(
            "T7: Frontend order consumer contradicts the canonical backend payload with alternate"
                + " identity fields");
  }

  @Test
  void rootVerificationNormalizesMalformedCommandsAndKeepsBuildPure() {
    PlannedTask task =
        contractTask(
            "T9",
            "build.sh:\n- python -m alembic upgrade head\n- python -c \"import app.main\"\n- npm"
                + " --prefix frontend run build\nrun_tests.sh:\n- python -m pytest tests/-v\n- npx"
                + " --prefix frontend vitest run",
            "The root ./build.sh must compile/import-check the Python 3.12 backend and run the"
                + " React production build via npm --prefix frontend run build, using only"
                + " already-installed dependencies; ./run_tests.sh separately runs backend pytest,"
                + " real-HTTP acceptance, and frontend Vitest.");

    PlannedTask normalized =
        TaskExpanderAgent.normalizeFinalPlan(Plan.builder().tasks(List.of(task)).build())
            .tasks()
            .getFirst();

    assertThat(violations(normalized)).isEmpty();
    assertThat(normalized.description())
        .contains("python3.12 -m compileall -q backend/app")
        .contains("npm --prefix frontend test -- --run")
        .doesNotContain("tests/-v", "python -m alembic upgrade head", "npx --prefix");
  }

  @Test
  void rootVerificationDoesNotConfuseReadmeMigrationSetupWithBuildScriptContents() {
    String description =
        """
        Root build.sh runs `python -c "import app.main"` and then
        `npm --prefix frontend run build`.

        README setup runs `python -m alembic upgrade head` before starting the server.
        """;

    String normalized = TaskExpansionTextNormalizer.rewriteRootVerificationCommands(description);

    assertThat(normalized)
        .contains("python3.12 -m compileall -q backend/app")
        .contains("python -m alembic upgrade head")
        .doesNotContain("python -c");
  }

  @Test
  void rootVerificationNormalizesCompactDeepSeekBuildClausesWithoutDroppingReadmeMigration() {
    String description =
        """
        Create root build.sh which runs python3 -c 'from backend.app.main import app' and npm
        --prefix frontend run build. README documents python -m alembic upgrade head.
        If build.sh includes python3 -m alembic upgrade head, remove that migration side effect.
        run_tests.sh runs python3 -m pytest tests/-v, then python3.12 -m pytest tests/-k smoke,
        and python -m pytest tests/acceptance/--tb=short.
        """;

    String normalized = TaskExpansionTextNormalizer.rewriteRootVerificationCommands(description);
    PlannedTask task =
        contractTask(
            "T16",
            normalized,
            "The root ./build.sh must compile/import-check Python and run the frontend build.");

    assertThat(normalized)
        .contains("python3.12 -m compileall -q backend/app")
        .contains("README documents python -m alembic upgrade head")
        .doesNotContain("python3 -c", "tests/-v", "tests/-k", "tests/acceptance/--");
    assertThat(violations(task))
        .noneMatch(value -> value.contains("non-canonical build or test commands"));
  }

  @Test
  void rootVerificationDoesNotConfuseReadmeServerCwdWithBuildScriptContents() {
    PlannedTask task =
        contractTask(
            "T15",
            """
**build.sh**: run python3.12 -m compileall -q backend/app and npm --prefix frontend run build.
**run_tests.sh**: run the declared backend and frontend suites.
**README setup/start commands**: cd backend && uvicorn app.main:app.
""",
            "The root ./build.sh must compile/import-check Python and run the frontend build.");

    assertThat(violations(task)).noneMatch(value -> value.contains("changes working directory"));
  }

  @Test
  void rootVerificationStillRejectsCwdChangeInsideBuildScriptSection() {
    PlannedTask task =
        contractTask(
            "T15",
            """
            **build.sh**:
            - cd backend
            - python3.12 -m compileall -q app
            - npm --prefix frontend run build
            **README**: document setup.
            """,
            "The root ./build.sh must compile/import-check Python and run the frontend build.");

    assertThat(violations(task))
        .contains("Root build execution changes working directory before root-relative commands");
  }

  @Test
  void incompleteInlineSchemaRepairPreservesNumberedImplementationProcedure() {
    PlannedTask task =
        contractTask(
            "T4",
            "Order service: 1. Claim the IdempotencyRecord. 2. Create the Order and lines. 3."
                + " Persist the response. IdempotencyRecord model fields are key and"
                + " response_body.",
            "Use one exact IdempotencyRecord contract everywhere: key, scope, request_body_hash,"
                + " state, nullable response_status, nullable response_body; enforce a database"
                + " unique constraint on (key, scope) with PROCESSING and COMPLETED states.");

    PlannedTask repaired =
        TaskExpanderAgent.applyDeterministicSemanticFallback(task, violations(task));

    assertThat(repaired.description())
        .contains("1. Claim", "2. Create", "3. Persist", "CONTRACT CORRECTION:")
        .doesNotContain("1. 2.", "2. 3.");
    assertThat(violations(repaired)).isEmpty();
  }

  @Test
  void emptyNumberedProcedureFailsTheFinalSemanticGate() {
    PlannedTask task =
        contractTask(
            "T4", "Order service procedure: 1. 2. Return the response.", "Keep it atomic.");

    assertThat(violations(task))
        .contains("Task description contains empty numbered implementation steps");
  }

  @Test
  void emptyNumberedProcedureIsRemovedByDeterministicFallback() {
    PlannedTask task =
        contractTask(
            "T4",
            "Scaffold procedure:\n1. 2. Create the Vite entry point.\n3. Add the router.",
            "Keep it bounded.");

    PlannedTask repaired =
        TaskExpanderAgent.applyDeterministicSemanticFallback(task, violations(task));

    assertThat(repaired.description())
        .contains("2. Create the Vite entry point", "3. Add the router")
        .doesNotContain("1. 2.");
    assertThat(violations(repaired)).isEmpty();
  }

  @Test
  void technicalSpacingCanonicalizesAllMalformedFrontendPrefixForms() {
    String normalized =
        TaskExpansionTextNormalizer.normalizeTechnicalSpacing(
            "Run npm --prefix . run build and cd frontend && npm --prefix frontend run test.");

    assertThat(normalized)
        .contains("npm --prefix frontend run build", "npm --prefix frontend run test")
        .doesNotContain("npm --prefix . run", "cd frontend && npm --prefix frontend");
  }

  @Test
  void finalNormalizerDoesNotRewriteProviderSchemaUsingAHiddenProductTemplate() {
    PlannedTask schema =
        PlannedTask.builder()
            .id("T3")
            .title("Implement order and reservation models with Alembic migration")
            .description(
                "`backend/app/models/order.py` — `Order`: id, status,"
                    + " idempotency_key (str, unique), idempotency_payload_hash (str)."
                    + " `backend/app/models/reservation.py` — `Reservation`: id, status"
                    + " (default ACTIVE, exactly PENDING or RELEASED). Canceling a reservation"
                    + " performs the RELEASED transition.")
            .touchesFiles(
                List.of(
                    "backend/app/models/order.py",
                    "backend/app/models/reservation.py",
                    "backend/alembic/versions/002_orders.py"))
            .hardConstraints(List.of())
            .meetsRequirements(List.of("R1", "R2"))
            .build();
    PlannedTask service =
        PlannedTask.builder()
            .id("T8")
            .title("Implement idempotent order creation endpoint")
            .description(
                "POST /api/orders uses IdempotencyRecord. If record exists and status == PENDING:"
                    + " - If request_body_hash != request_body_hash return 409. If record exists"
                    + " and status == PENDING → wait briefly, re-reading until PENDING or timeout.")
            .touchesFiles(List.of("backend/app/services/order_service.py"))
            .hardConstraints(List.of())
            .meetsRequirements(List.of("R1"))
            .build();
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Same Idempotency-Key and payload replays the exact response; conflicting"
                            + " reuse returns 409.",
                        RequirementSpec.RequirementKind.FUNCTIONAL),
                    new RequirementSpec(
                        "R2",
                        "Release/cancel a reservation aggregate with persisted lines from ACTIVE to"
                            + " RELEASED.",
                        RequirementSpec.RequirementKind.FUNCTIONAL)))
            .tasks(List.of(schema, service))
            .build();

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan);
    PlannedTask normalizedSchema = normalized.tasks().getFirst();
    PlannedTask normalizedService = normalized.tasks().get(1);

    assertThat(normalizedSchema.description())
        .contains("idempotency_key (str, unique)")
        .contains("PENDING or RELEASED")
        .doesNotContain("IdempotencyRecord is a separate persisted model");
    assertThat(normalizedSchema.touchesFiles())
        .doesNotContain("backend/app/models/idempotency_record.py");
    assertThat(normalizedService.description())
        .contains("status == PENDING", "If request_body_hash != request_body_hash");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void normalizesAdditionalMalformedDeepSeekPytestPathOptions() {
    String normalized =
        TaskExpansionTextNormalizer.rewriteRootVerificationCommands(
            "run_tests.sh runs python -m pytest tests/--ignore=tests/acceptance -x and then"
                + " python -m pytest tests/acceptance/-x.");

    assertThat(normalized)
        .contains(
            "backend/tests --ignore=backend/tests/acceptance -x", "backend/tests/acceptance -x")
        .doesNotContain("tests/--", "tests/acceptance/-");
  }

  @Test
  void rootVerificationFallbackCompilesOneAuthoritativeCommandProgram() {
    PlannedTask task =
        contractTask(
            "T15",
            "build.sh runs python -m alembic upgrade head and python -c \"import app\"."
                + " run_tests.sh runs tests/--ignore=tests/acceptance and skips E2E when missing.",
            "The root ./build.sh must compile/import-check Python and run the frontend build.");

    PlannedTask repaired =
        TaskExpanderAgent.applyDeterministicSemanticFallback(task, violations(task));

    assertThat(repaired.description())
        .contains(
            "python3.12 -m compileall -q backend/app",
            "python3.12 -m pytest backend/tests --ignore=backend/tests/acceptance --tb=short",
            "python3.12 -m pytest backend/tests/acceptance --tb=short",
            "npm --prefix frontend test -- --run",
            "npm --prefix frontend run test:e2e")
        .doesNotContain(
            "python -m alembic upgrade head", "python -c", "tests/--", "skips E2E when missing");
    assertThat(violations(repaired)).isEmpty();
  }

  @Test
  void migrationRuntimeFallbackRemovesProviderIgnoredVersionedMigration() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T1")
            .title("Configure migration runtime")
            .description(
                "Generate the initial migration at"
                    + " `backend/alembic/versions/001_initial_schema.py`.")
            .touchesFiles(
                List.of("backend/alembic/env.py", "backend/alembic/versions/001_initial_schema.py"))
            .hardConstraints(
                List.of(
                    "This task may configure the migration runtime but must not create a"
                        + " versioned migration."))
            .meetsRequirements(List.of())
            .build();

    PlannedTask repaired =
        TaskExpanderAgent.applyDeterministicSemanticFallback(task, violations(task));

    assertThat(repaired.touchesFiles())
        .contains("backend/alembic/env.py")
        .noneMatch(PlanSafetyMigrationSupport::isVersionMigrationPath);
    assertThat(repaired.description())
        .contains("must not create, generate, or own a versioned migration")
        .doesNotContain("Generate the initial migration");
    assertThat(violations(repaired)).isEmpty();
  }

  @Test
  void finalNormalizerLetsModelOwnersOwnMigrationsAndPrefersDedicatedIdempotencySchemaTask() {
    String runtimeOnly =
        "This task may configure the migration runtime but must not create a versioned migration.";
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Use Alembic migrations.",
                        RequirementSpec.RequirementKind.CONSTRAINT),
                    new RequirementSpec(
                        "R2",
                        "Persist idempotency ownership and replay records.",
                        RequirementSpec.RequirementKind.FUNCTIONAL)))
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T2")
                        .title("Implement warehouse and product models")
                        .description("Create Warehouse and Product models.")
                        .touchesFiles(
                            List.of(
                                "backend/app/models/warehouse.py",
                                "backend/app/models/product.py",
                                "backend/alembic/versions/001_warehouse_product.py"))
                        .hardConstraints(List.of(runtimeOnly))
                        .meetsRequirements(List.of("R1"))
                        .build(),
                    PlannedTask.builder()
                        .id("T4")
                        .title("Implement order models")
                        .description("Create Order and OrderLine models.")
                        .touchesFiles(
                            List.of(
                                "backend/app/models/order.py",
                                "backend/app/models/idempotency_record.py",
                                "backend/alembic/versions/002_orders.py"))
                        .hardConstraints(List.of(runtimeOnly))
                        .build(),
                    PlannedTask.builder()
                        .id("T5")
                        .title("Implement idempotency persistence")
                        .description("Persist IdempotencyRecord ownership and exact replay.")
                        .touchesFiles(List.of("backend/app/services/idempotency.py"))
                        .hardConstraints(List.of(runtimeOnly))
                        .meetsRequirements(List.of("R2"))
                        .build()))
            .build();

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan);
    PlannedTask order = normalized.tasks().get(1);
    PlannedTask idempotency = normalized.tasks().get(2);

    assertThat(order.touchesFiles()).doesNotContain("backend/app/models/idempotency_record.py");
    assertThat(idempotency.touchesFiles())
        .contains("backend/app/models/idempotency_record.py")
        .anyMatch(PlanSafetyMigrationSupport::isVersionMigrationPath);
    assertThat(normalized.tasks())
        .allSatisfy(
            task -> {
              boolean ownsModel =
                  task.touchesFiles().stream()
                      .anyMatch(path -> path.contains("/models/") && !path.endsWith("__init__.py"));
              if (ownsModel) {
                assertThat(task.hardConstraints())
                    .noneMatch(value -> value.contains("must not create a versioned migration"));
              }
            });
    assertThat(TaskExpanderAgent.finalPlanContractViolations(normalized)).isEmpty();
  }

  @Test
  void expansionFallbackRemovesDomainModelsAndPagesOwnedBySpecificSiblingTasks() {
    PlannedTask broad =
        PlannedTask.builder()
            .id("T2")
            .title("Implement warehouse and product domain models")
            .description("Create warehouse, product, inventory, order, and audit models.")
            .touchesFiles(
                List.of(
                    "backend/app/models/warehouse.py",
                    "backend/app/models/product.py",
                    "backend/app/models/inventory.py",
                    "backend/app/models/order.py",
                    "backend/app/models/audit.py"))
            .hardConstraints(List.of())
            .meetsRequirements(List.of())
            .build();
    Plan plan =
        Plan.builder()
            .requirements(List.of())
            .tasks(
                List.of(
                    broad,
                    PlannedTask.builder().id("T5").title("Implement inventory with audit").build(),
                    PlannedTask.builder().id("T6").title("Implement order lifecycle").build()))
            .build();

    PlannedTask repaired =
        TaskExpanderAgent.applyDeterministicSemanticFallback(
            broad, TaskExpanderAgent.semanticViolations(broad, plan));

    assertThat(repaired.touchesFiles())
        .containsExactly("backend/app/models/warehouse.py", "backend/app/models/product.py")
        .doesNotContain(
            "backend/app/models/inventory.py",
            "backend/app/models/order.py",
            "backend/app/models/audit.py");
    assertThat(repaired.hardConstraints())
        .anyMatch(value -> value.contains("T5 exclusively owns `backend/app/models/inventory.py`"))
        .anyMatch(value -> value.contains("T6 exclusively owns `backend/app/models/order.py`"));
    assertThat(TaskExpanderAgent.semanticViolations(repaired, plan)).isEmpty();

    Plan reconciled =
        PlanScopeOwnershipReconciler.reconcile(
            Plan.builder()
                .requirements(List.of())
                .tasks(List.of(repaired, plan.tasks().get(1), plan.tasks().get(2)))
                .build());
    assertThat(reconciled.tasks().get(1).touchesFiles())
        .contains("backend/app/models/inventory.py", "backend/app/models/audit.py");
    assertThat(reconciled.tasks().get(2).touchesFiles()).contains("backend/app/models/order.py");
  }

  @Test
  void expansionFallbackDropsPlaceholderMigrationRevisionPath() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T6")
            .title("Implement order lifecycle")
            .description("Add the order models and a concrete migration.")
            .touchesFiles(
                List.of(
                    "backend/app/models/order.py",
                    "backend/alembic/versions/XXXX_create_orders.py"))
            .hardConstraints(List.of())
            .meetsRequirements(List.of())
            .build();
    Plan plan = Plan.builder().requirements(List.of()).tasks(List.of(task)).build();

    PlannedTask repaired =
        TaskExpanderAgent.applyDeterministicSemanticFallback(
            task, TaskExpanderAgent.semanticViolations(task, plan));

    assertThat(repaired.touchesFiles())
        .contains("backend/app/models/order.py")
        .noneMatch(path -> path.contains("XXXX"));
    assertThat(TaskExpanderAgent.semanticViolations(repaired, plan)).isEmpty();
  }

  private static PlannedTask contractTask(
      String id, String description, String... hardConstraints) {
    return PlannedTask.builder()
        .id(id)
        .title("Contract task " + id)
        .description(description)
        .hardConstraints(List.of(hardConstraints))
        .meetsRequirements(List.of())
        .build();
  }

  private static List<String> violations(PlannedTask task) {
    Plan plan = Plan.builder().requirements(List.of()).tasks(List.of(task)).build();
    return TaskExpanderAgent.semanticViolations(task, plan);
  }
}
