package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class CoherencePlannerDeterministicSafetyContractTest {

  @Test
  void reconcilesForwardOnlyDescriptionWithDowngradeConstraintAcrossTaskFields() {
    Plan plan =
        Plan.builder()
            .planTitle("Migration contract")
            .requirements(List.of(requirement("R1", "Provide executable migrations.")))
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("Create schema")
                        .description("Create the initial forward-only migration.")
                        .touchesFiles(List.of("backend/alembic/versions/001_initial.py"))
                        .dependsOn(List.of())
                        .hardConstraints(
                            List.of("The downgrade drops created tables in reverse order."))
                        .meetsRequirements(List.of("R1"))
                        .build()))
            .build();

    Plan enriched = PlanSafetyContractEnricher.enrich(plan).plan();

    assertThat(enriched.tasks().getFirst().description()).contains("reversible migration");
    assertThat(PlanContractQualityGate.violations(enriched)).isEmpty();
  }

  @Test
  void reservationPlanGetsProviderNeutralTrustDomainAndMigrationContracts() {
    Plan plan =
        Plan.builder()
            .planTitle("Reservation service")
            .planSummary("FastAPI reservation service")
            .projectType("python")
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(
                List.of(
                    requirement("R1", "Products have non-negative stock."),
                    requirement(
                        "R2",
                        "POST /reservations uses an Idempotency-Key scoped to the authenticated"
                            + " customer."),
                    requirement("R3", "Include schema migrations."),
                    requirement("R4", "Use SQLite for persistence."),
                    requirement("R5", "Stock decrement must be atomic under concurrent requests."),
                    requirement("R6", "Return a stable error for invalid state transitions."),
                    requirement("R7", "Test double cancellation.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Models and Alembic",
                        "Set up Alembic, a Customer api_key field, and a model smoke test.",
                        List.of("app/database.py", "alembic/env.py", "tests/test_models.py")),
                    task(
                        "T2",
                        "Reservation service",
                        "Implement API key lookup and create_reservation(customer_id, product_id,"
                            + " quantity) with atomic stock decrement and"
                            + " get_reservation/cancel_reservation.",
                        List.of(
                            "app/services/reservation_service.py",
                            "tests/test_reservation_service.py")),
                    task(
                        "T3",
                        "Routes and app factory",
                        "Implement POST /reservations, GET /reservations/{id}, and cancellation"
                            + " routes.",
                        List.of("app/main.py", "app/routes.py", "tests/test_routes.py")),
                    task(
                        "T4",
                        "Concurrency tests",
                        "Use pytest and TestClient for stock and replay behavior.",
                        List.of("tests/test_concurrency.py")),
                    task("T5", "OpenAPI", "Generate openapi.yaml.", List.of("openapi.yaml")),
                    task("T6", "README", "Document running the server.", List.of("README.md"))))
            .build();

    PlanSafetyContractEnricher.Result result = PlanSafetyContractEnricher.enrich(plan);

    assertThat(result.contractsApplied()).isGreaterThanOrEqualTo(19);
    assertThat(result.summaries()).hasSize(result.contractsApplied());
    assertThat(constraints(result, "T2"))
        .anyMatch(value -> value.contains("verified credential"))
        .anyMatch(value -> value.contains("quantity <= 0"))
        .anyMatch(value -> value.contains("SELECT FOR UPDATE"))
        .anyMatch(value -> value.contains("one-way verifier"))
        .anyMatch(value -> value.contains("must not seed, ship, log, document, or accept"))
        .noneMatch(value -> value.contains("cross-principal owned-resource"))
        .anyMatch(value -> value.contains("Test fixtures must apply the configured migration"));
    assertThat(constraints(result, "T3"))
        .anyMatch(value -> value.contains("verified credential"))
        .anyMatch(value -> value.contains("quantity <= 0"))
        .anyMatch(value -> value.contains("default startup path"))
        .anyMatch(value -> value.contains("Test fixtures must apply the configured migration"))
        .noneMatch(value -> value.contains("SELECT FOR UPDATE"))
        .noneMatch(value -> value.contains("second cancellation"));
    assertThat(constraints(result, "T1"))
        .anyMatch(value -> value.contains("one-way verifier"))
        .anyMatch(value -> value.contains("must not seed, ship, log, document, or accept"))
        .anyMatch(value -> value.contains("default startup path"))
        .anyMatch(value -> value.contains("Test fixtures must apply the configured migration"));
    assertThat(constraints(result, "T4"))
        .anyMatch(value -> value.contains("Test fixtures must apply the configured migration"))
        .anyMatch(value -> value.contains("forged credentials"))
        .anyMatch(value -> value.contains("SELECT FOR UPDATE"))
        .anyMatch(value -> value.contains("second cancellation"))
        .anyMatch(value -> value.contains("production server"));
    assertThat(constraints(result, "T6")).anyMatch(value -> value.contains("default startup path"));
    assertThat(constraints(result, "T5")).isEmpty();
  }

  @Test
  void unrelatedPlanIsUnchanged() {
    Plan plan =
        Plan.builder()
            .planTitle("CLI")
            .requirements(List.of(requirement("R1", "Print the requested value.")))
            .tasks(List.of(task("T1", "CLI", "Print a value.", List.of("main.py"))))
            .build();

    PlanSafetyContractEnricher.Result result = PlanSafetyContractEnricher.enrich(plan);

    assertThat(result.contractsApplied()).isZero();
    assertThat(result.plan()).isSameAs(plan);
  }

  @Test
  void authoritativeSpecRestoresBuildContractOmittedByOutlineRequirements() {
    Plan lossyOutline =
        Plan.builder()
            .planTitle("Full-stack app")
            .requirements(List.of(requirement("R1", "Provide a repeatable test command.")))
            .tasks(
                List.of(
                    task(
                        "T9",
                        "Root verification",
                        "run_tests.sh creates a Python venv, runs pip install and npm ci, then runs"
                            + " the test suites.",
                        List.of("run_tests.sh", "README.md"))))
            .build();

    PlanSafetyContractEnricher.Result result =
        PlanSafetyContractEnricher.enrich(
            lossyOutline,
            "Add root orchestration and choose a root build command for backend and frontend."
                + " Build and test commands must not install dependencies or create"
                + " environments;");

    PlannedTask task = result.plan().tasks().getFirst();
    assertThat(result.plan().buildCommand()).isEqualTo("./build.sh");
    assertThat(task.touchesFiles()).contains("build.sh");
    assertThat(task.hardConstraints())
        .anyMatch(value -> value.contains("Build and test commands must never"));
    assertThat(task.description())
        .doesNotContain("pip install", "npm ci", "creates a Python venv")
        .contains("already-installed");
  }

  @Test
  void scopesRealHttpAcceptanceAndRepairsFrontendAndTechnicalProse() {
    Plan plan =
        Plan.builder()
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(
                List.of(
                    requirement("R1", "Include Alembic migrations."),
                    requirement(
                        "R2", "Use integer minor units; never use floating point for money."),
                    requirement("R3", "Include independent black-box public HTTP tests.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Backend model unit tests",
                        "Use httpx.AsyncClient wired to the FastAPI app for a unit-level fixture.",
                        List.of("backend/tests/test_models.py")),
                    task(
                        "T7",
                        "Frontend product form",
                        "Price uses a decimal input, convert to integer minor units before POST —"
                            + " multiply by 100, round.",
                        List.of("frontend/src/ProductForm.tsx")),
                    task(
                        "T8",
                        "Frontend order card",
                        "Use green for PENDING, yellow for PENDING, red for CANCELLED.",
                        List.of("frontend/src/OrderCard.tsx")),
                    task(
                        "T9",
                        "Independent black-box acceptance",
                        "README. md uses Python 3. 12. Launch the documented server and call it"
                            + " with a real HTTP client from test_acceptance. py.",
                        List.of("backend/tests/test_acceptance.py"))))
            .build();

    Plan result = PlanSafetyContractEnricher.enrich(plan).plan();

    assertThat(result.tasks().get(0).hardConstraints())
        .noneMatch(value -> value.contains("production server"));
    assertThat(result.tasks().get(1).description())
        .contains("decimal string", "without floating-point")
        .doesNotContain("multiply by 100, round");
    assertThat(result.tasks().get(2).description())
        .contains("green for PENDING and red for CANCELLED")
        .doesNotContain("yellow for PENDING");
    assertThat(result.tasks().get(3).description())
        .contains("README.md", "Python 3.12", "test_acceptance.py");
    assertThat(result.tasks().get(3).hardConstraints())
        .anyMatch(value -> value.contains("production server"));
  }

  @Test
  void explicitNoAuthenticationRequirementDoesNotInventIdentityContracts() {
    Plan plan =
        Plan.builder()
            .planTitle("Public inventory API")
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(
                List.of(
                    requirement("R1", "No authentication and no external SaaS dependencies."),
                    requirement("R2", "Order creation uses an Idempotency-Key.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Order API and functional tests",
                        "Implement POST /api/orders and end-to-end tests over HTTP.",
                        List.of("app/routes.py", "tests/test_orders.py"))))
            .build();

    PlannedTask enriched = PlanSafetyContractEnricher.enrich(plan).plan().tasks().getFirst();

    assertThat(enriched.hardConstraints())
        .noneMatch(value -> value.contains("forged credentials"))
        .noneMatch(value -> value.contains("cross-principal"))
        .noneMatch(value -> value.contains("authenticated principals"))
        .noneMatch(value -> value.contains("verified credential"));
  }

  @Test
  void ordinaryCrudTestsDoNotReceiveReservationOrBlackBoxAcceptanceContracts() {
    Plan plan =
        Plan.builder()
            .planTitle("Inventory system")
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(
                List.of(
                    requirement(
                        "R1", "Use SQLite for atomic order reservations with stock quantities."),
                    requirement("R2", "Products have SKU and name.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Product CRUD with tests",
                        "Implement product endpoints and pytest tests for create, list, and"
                            + " update.",
                        List.of("app/products.py", "tests/test_products.py")),
                    task(
                        "T2",
                        "Order reservation integration tests",
                        "Create orders over real HTTP and verify atomic reservation.",
                        List.of("tests/test_order_integration.py"))))
            .build();

    PlanSafetyContractEnricher.Result result = PlanSafetyContractEnricher.enrich(plan);

    assertThat(constraints(result, "T1"))
        .noneMatch(value -> value.contains("quantity <= 0"))
        .noneMatch(value -> value.contains("SELECT FOR UPDATE"))
        .noneMatch(value -> value.contains("production server"));
    assertThat(constraints(result, "T2"))
        .anyMatch(value -> value.contains("quantity <= 0"))
        .anyMatch(value -> value.contains("SELECT FOR UPDATE"))
        .anyMatch(value -> value.contains("production server"));
  }

  @Test
  void frontendOrderFlowDoesNotReceiveBackendReservationOrSqliteContracts() {
    Plan plan =
        Plan.builder()
            .planTitle("Full-stack orders")
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(
                List.of(
                    requirement(
                        "R1",
                        "Use SQLite migrations for atomic order reservations with stock"
                            + " quantities.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Build frontend order creation flow",
                        "Create a multi-line reservation form and validate quantities.",
                        List.of(
                            "frontend/src/OrderCreate.tsx", "frontend/src/OrderCreate.test.tsx"))))
            .build();

    PlannedTask enriched = PlanSafetyContractEnricher.enrich(plan).plan().tasks().getFirst();

    assertThat(enriched.hardConstraints())
        .noneMatch(value -> value.contains("quantity <= 0"))
        .noneMatch(value -> value.contains("SELECT FOR UPDATE"))
        .noneMatch(value -> value.contains("BEGIN IMMEDIATE"))
        .noneMatch(value -> value.contains("migration mechanism"))
        .noneMatch(value -> value.contains("production server"));
  }

  @Test
  void repeatedEnrichmentKeepsSafetyContractsWithTheirOwningTasks() {
    Plan plan =
        Plan.builder()
            .planTitle("Reservation service")
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(
                List.of(
                    requirement("R1", "Authenticated customers create idempotent reservations."),
                    requirement("R2", "Use SQLite migrations."),
                    requirement("R3", "Stock decrement is atomic under concurrent requests."),
                    requirement("R4", "Double cancellation is an invalid state transition.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Models and migrations",
                        "Create SQLAlchemy models and Alembic migrations with a database test.",
                        List.of("app/models.py", "migrations/env.py", "tests/test_db.py")),
                    task(
                        "T2",
                        "Authentication",
                        "Verify API credentials and resolve a stable customer principal.",
                        List.of("app/auth.py", "tests/test_auth.py")),
                    task(
                        "T3",
                        "Create reservation",
                        "Implement create_reservation with atomic stock decrement.",
                        List.of("app/routes.py", "tests/test_reservations.py")),
                    task(
                        "T4",
                        "Concurrency integration tests",
                        "Use separate HTTP clients for last-unit reservation and cancellation.",
                        List.of("tests/test_concurrency.py")),
                    task(
                        "T4B",
                        "FastAPI endpoints and routing",
                        "Integrate the authentication, idempotency, and reservation services.",
                        List.of("app/api/endpoints.py", "app/main.py")),
                    task(
                        "T5",
                        "OpenAPI specification",
                        "Document reservation and cancellation endpoints in openapi.yaml.",
                        List.of("openapi.yaml", "app/main.py")),
                    task(
                        "T6",
                        "README documentation",
                        "Document migrations and running the server.",
                        List.of("README.md"))))
            .build();

    PlanSafetyContractEnricher.Result first = PlanSafetyContractEnricher.enrich(plan);
    PlanSafetyContractEnricher.Result second = PlanSafetyContractEnricher.enrich(first.plan());

    assertThat(constraints(second, "T1"))
        .anyMatch(value -> value.contains("configured migration mechanism"))
        .noneMatch(value -> value.contains("forged identity"))
        .noneMatch(value -> value.contains("two authenticated principals"))
        .noneMatch(value -> value.contains("quantity <= 0"))
        .noneMatch(value -> value.contains("production server"));
    assertThat(constraints(second, "T2"))
        .anyMatch(value -> value.contains("one-way verifier"))
        .anyMatch(value -> value.contains("forged credentials"))
        .noneMatch(value -> value.contains("production server"))
        .noneMatch(value -> value.contains("two authenticated principals"))
        .noneMatch(value -> value.contains("cross-principal owned-resource"));
    assertThat(constraints(second, "T3"))
        .anyMatch(value -> value.contains("quantity <= 0"))
        .anyMatch(value -> value.contains("SELECT FOR UPDATE"));
    assertThat(constraints(second, "T4"))
        .anyMatch(value -> value.contains("forged credentials"))
        .anyMatch(value -> value.contains("quantity <= 0"))
        .anyMatch(value -> value.contains("second cancellation"))
        .anyMatch(value -> value.contains("production server"));
    assertThat(constraints(second, "T4B"))
        .anyMatch(value -> value.contains("verified credential"))
        .noneMatch(value -> value.contains("SELECT FOR UPDATE"))
        .noneMatch(value -> value.contains("second cancellation"));
    assertThat(constraints(second, "T5")).isEmpty();
    assertThat(constraints(second, "T6"))
        .containsExactly(
            "Run the project's configured migration mechanism from the documented default startup"
                + " path; do not fall back to ORM schema auto-creation.");
  }

  @Test
  void canonicalizationRemovesConflictingGeneratedContractsInsteadOfOnlyAppendingCorrections() {
    PlannedTask scaffold =
        PlannedTask.builder()
            .id("T1")
            .title("Backend scaffold and fixtures")
            .description("Create FastAPI and backend/tests/conftest.py.")
            .touchesFiles(List.of("backend/app/main.py", "backend/tests/conftest.py"))
            .hardConstraints(
                List.of(
                    "Functional tests must launch the documented production server, apply the real"
                        + " migrations to a fresh database, and exercise public behavior over real"
                        + " HTTP; in-process TestClient is supplemental."))
            .build();
    PlannedTask models =
        PlannedTask.builder()
            .id("T2")
            .title("Implement Order, Reservation, and ReservationLine models")
            .description(
                "Order status (enum: PENDING, RESERVED, CANCELLED, default PENDING). Reservation"
                    + " owns ReservationLine children. CONTRACT CORRECTION: Reservation is one"
                    + " parent aggregate that owns a persisted one-to-many collection of"
                    + " ReservationLine child rows.")
            .touchesFiles(
                List.of("backend/app/models/order.py", "backend/app/models/reservation.py"))
            .hardConstraints(
                List.of(
                    "Order.status lifecycle values are exactly PENDING, RESERVED, CANCELLED.",
                    "Reservation model has fields product_id and quantity — single"
                        + " product_id/quantity per reservation row, not a separate"
                        + " ReservationLine collection.",
                    "Order.status lifecycle values are exactly PENDING, CANCELLED, ACTIVE."))
            .build();
    PlannedTask idempotency =
        PlannedTask.builder()
            .id("T5")
            .title("Reservation idempotency service")
            .description(
                "IdempotencyRecord model: key, operation, payload_hash, status"
                    + " (PROCESSING/COMPLETED), response_body. Unique constraint on (key,"
                    + " operation). POST /api/reservations requires Idempotency-Key.")
            .touchesFiles(
                List.of(
                    "backend/app/services/idempotency_service.py",
                    "backend/app/models/idempotency_record.py"))
            .hardConstraints(
                List.of(
                    "IdempotencyRecord unique constraint is on (key, operation) and stores"
                        + " payload_hash and status."))
            .build();
    PlannedTask acceptance =
        PlannedTask.builder()
            .id("T12")
            .title("Black-box acceptance tests")
            .description("Exercise reservation replay over real HTTP.")
            .touchesFiles(List.of("acceptance/test_idempotent_replay.py"))
            .hardConstraints(List.of())
            .build();
    Plan plan =
        Plan.builder()
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(
                List.of(
                    requirement("R1", "Model reservation lines and reservation lifecycle."),
                    requirement(
                        "R2",
                        "Require Idempotency-Key for mutating reservation requests; same key"
                            + " replays and conflicting reuse returns 409.")))
            .tasks(List.of(scaffold, models, idempotency, acceptance))
            .build();

    Plan enriched =
        TaskExpanderAgent.normalizeFinalPlan(PlanSafetyContractEnricher.enrich(plan).plan());
    PlannedTask enrichedModels = enriched.tasks().get(1);
    PlannedTask enrichedIdempotency = enriched.tasks().get(2);

    assertThat(enriched.tasks().getFirst().hardConstraints())
        .noneMatch(value -> value.contains("production server"));
    assertThat(enrichedModels.hardConstraints())
        .noneMatch(value -> value.contains("single product_id/quantity"))
        .noneMatch(value -> value.contains("PENDING, CANCELLED, ACTIVE"))
        .anyMatch(value -> value.contains("PENDING, RESERVED, CANCELLED"))
        .anyMatch(value -> value.contains("one persisted parent aggregate"));
    assertThat(enrichedIdempotency.description())
        .contains("scope", "request_body_hash", "state")
        .doesNotContain("(key, operation)", "payload_hash");
    assertThat(enrichedIdempotency.hardConstraints())
        .noneMatch(value -> value.contains("(key, operation)"))
        .anyMatch(value -> value.contains("(key, scope)"))
        .anyMatch(value -> value.contains("mutating reservation endpoints"))
        .noneMatch(value -> value.contains("order creation"));
    assertThat(enriched.tasks().get(3).hardConstraints())
        .anyMatch(value -> value.contains("production server"));
  }

  @Test
  void blackBoxPlanUsesExecutableCommandsDocumentedEndpointsAndCanonicalModelFiles() {
    PlannedTask models =
        task(
            "T2",
            "StockTransfer model",
            "Define StockTransfer in the persistence model.",
            List.of("backend/app/models/transfer.py"));
    PlannedTask transfer =
        task(
            "T4",
            "Transfer service",
            "Use the StockTransfer model.",
            List.of(
                "backend/app/services/transfer_service.py",
                "backend/app/models/stock_transfer.py"));
    PlannedTask acceptance =
        task(
            "T12",
            "Black-box acceptance tests",
            "Launch uvicorn with `cd backend && uvicorn backend.app.main:app --port 8000`."
                + " Start the UI with `cd frontend && npx vite --port 5173`. POST adjustment with"
                + " negative quantity must fail. GET the same audit event by ID after restart.",
            List.of("acceptance/test_persistence.py", "acceptance/test_validation_errors.py"));
    Plan plan =
        Plan.builder()
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(
                List.of(
                    requirement(
                        "R1", "Create positive or negative stock adjustments with validation."),
                    requirement(
                        "R2", "Include exact setup/migration/start commands and black-box tests.")))
            .tasks(List.of(models, transfer, acceptance))
            .build();

    Plan enriched = PlanSafetyContractEnricher.enrich(plan).plan();
    PlannedTask enrichedTransfer = enriched.tasks().get(1);
    PlannedTask enrichedAcceptance = enriched.tasks().get(2);

    assertThat(enrichedTransfer.touchesFiles())
        .contains("backend/app/models/transfer.py")
        .doesNotContain("backend/app/models/stock_transfer.py");
    assertThat(enrichedAcceptance.touchesFiles()).contains("README.md");
    assertThat(enrichedAcceptance.description())
        .contains("cd backend && uvicorn app.main:app")
        .contains("cd frontend && npm run dev -- --port 5173")
        .contains("zero delta")
        .contains("GET /api/audit")
        .contains("Create the root README.md")
        .doesNotContain("uvicorn backend.app.main:app", "GET the same audit event by ID");
    assertThat(enrichedAcceptance.hardConstraints())
        .anyMatch(value -> value.contains("README.md must contain exact"));
  }

  @Test
  void authenticatedNonReservationPlanGetsGenericOwnedResourceContract() {
    Plan plan =
        Plan.builder()
            .planTitle("Blog")
            .requirements(
                List.of(requirement("R1", "Authenticated users can update their own posts.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Post routes",
                        "Implement authenticated post updates.",
                        List.of("app/routes.py", "tests/test_routes.py"))))
            .build();

    PlanSafetyContractEnricher.Result result = PlanSafetyContractEnricher.enrich(plan);

    assertThat(constraints(result, "T1"))
        .anyMatch(value -> value.contains("principal identity from a verified credential"))
        .anyMatch(value -> value.contains("cross-principal owned-resource"))
        .noneMatch(value -> value.toLowerCase().contains("reservation"));
  }

  @Test
  void sqliteReservationPlanRemovesContradictorySelectForUpdateInstructions() {
    Plan plan =
        Plan.builder()
            .planTitle("Reservation service")
            .requirements(
                List.of(
                    requirement("R1", "Use SQLite for persistence."),
                    requirement("R2", "Stock decrement must be atomic under concurrent requests.")))
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("Create reservation")
                        .description(
                            "POST /reservations atomically decrements via row-level lock (SELECT"
                                + " ... FOR UPDATE) and tests concurrent last-unit requests.")
                        .touchesFiles(
                            List.of("app/services/reservation_service.py", "tests/test_race.py"))
                        .hardConstraints(
                            List.of(
                                "Atomic stock decrement via row-level lock: use SELECT ... FOR"
                                    + " UPDATE within a SERIALIZABLE transaction"))
                        .build()))
            .build();

    PlanSafetyContractEnricher.Result result = PlanSafetyContractEnricher.enrich(plan);
    PlannedTask enriched = result.plan().tasks().getFirst();

    assertThat(enriched.description())
        .doesNotContainIgnoringCase("SELECT FOR UPDATE")
        .contains("SQLite-effective atomic mutation");
    assertThat(enriched.hardConstraints())
        .noneMatch(
            CoherencePlannerDeterministicSafetyContractTest::positivelyRequiresSelectForUpdate)
        .anyMatch(value -> value.contains("SQLite does not provide effective SELECT FOR UPDATE"));
    assertThat(result.contractsApplied()).isEqualTo(3);
  }

  @Test
  void sqliteReservationPlanRemovesContradictorySqlAlchemyWithForUpdateInstructions() {
    Plan plan =
        Plan.builder()
            .planTitle("Reservation service")
            .requirements(
                List.of(
                    requirement("R1", "Use SQLite for persistence."),
                    requirement("R2", "Stock decrement must be atomic under concurrent requests.")))
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("Reservation service")
                        .description(
                            "Build ReservationService: atomic stock decrement using SQLAlchemy"
                                + " with_for_update() inside an explicit transaction.")
                        .touchesFiles(List.of("app/service.py", "tests/test_concurrency.py"))
                        .hardConstraints(List.of("Atomic stock decrement via with_for_update()"))
                        .build()))
            .build();

    PlanSafetyContractEnricher.Result result = PlanSafetyContractEnricher.enrich(plan);
    PlannedTask enriched = result.plan().tasks().getFirst();

    assertThat(enriched.description())
        .doesNotContainIgnoringCase("with_for_update")
        .contains("conditional UPDATE with affected-row verification");
    assertThat(enriched.hardConstraints())
        .noneMatch(value -> value.toLowerCase().contains("with_for_update"))
        .anyMatch(value -> value.contains("SQLite does not provide effective SELECT FOR UPDATE"));
    assertThat(result.contractsApplied()).isGreaterThanOrEqualTo(3);
  }

  @Test
  void sqliteTimeOverlapPlanGetsDeterministicCheckInsertRaceContract() {
    Plan plan =
        Plan.builder()
            .planTitle("Room reservation service")
            .requirements(
                List.of(
                    requirement("R1", "Use SQLite for reservation persistence."),
                    requirement(
                        "R2",
                        "Reject overlapping reservations and handle concurrent create requests"
                            + " atomically.")))
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("POST /reservations")
                        .description(
                            "Check ACTIVE time overlap, then insert the reservation and add"
                                + " parallel HTTP tests.")
                        .touchesFiles(
                            List.of("app/services/reservation_service.py", "tests/test_race.py"))
                        .hardConstraints(List.of())
                        .build()))
            .build();

    PlannedTask enriched = PlanSafetyContractEnricher.enrich(plan).plan().tasks().getFirst();

    assertThat(enriched.hardConstraints())
        .anyMatch(
            value ->
                value.contains("a SELECT then INSERT in a deferred transaction is not")
                    && value.contains("one 201, one 409, no 5xx"));
  }

  @Test
  void sqliteReservationPlanRewritesUnicodeEllipsisAndGenericForUpdateLock() {
    Plan plan =
        Plan.builder()
            .planTitle("Reservation service")
            .requirements(
                List.of(
                    requirement("R1", "Use SQLite for persistence."),
                    requirement("R2", "Stock decrement must be atomic under concurrent requests.")))
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("Reservation service")
                        .description(
                            "Load the product with SELECT … FOR UPDATE, then retry using a FOR"
                                + " UPDATE lock.")
                        .touchesFiles(List.of("app/service.py", "tests/test_concurrency.py"))
                        .hardConstraints(List.of("Atomic stock decrement: use FOR UPDATE"))
                        .build()))
            .build();

    PlannedTask enriched = PlanSafetyContractEnricher.enrich(plan).plan().tasks().getFirst();

    assertThat(enriched.description())
        .doesNotContainIgnoringCase("SELECT … FOR UPDATE")
        .doesNotContainIgnoringCase("FOR UPDATE lock")
        .contains("conditional UPDATE with affected-row verification");
    assertThat(enriched.hardConstraints())
        .noneMatch(value -> value.equalsIgnoreCase("Atomic stock decrement: use FOR UPDATE"));
  }

  @Test
  void reservationPlanCanonicalizesAuthDoubleCancelAndInProcessHttpContradictions() {
    Plan plan =
        Plan.builder()
            .planTitle("Reservation API")
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(
                List.of(
                    requirement("R1", "Scope Idempotency-Key to the authenticated customer."),
                    requirement("R2", "Return invalid state for double cancellation."),
                    requirement("R3", "Test double cancellation through the HTTP API.")))
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("Reservation API and tests")
                        .description(
                            "Wire routes that read Idempotency-Key + X-Customer-Id headers; cancel"
                                + " is idempotent on already-cancelled. Write pytest API tests via"
                                + " TestClient and assert the second cancel returns the stored"
                                + " response.")
                        .touchesFiles(List.of("app/routes.py", "tests/test_api.py"))
                        .hardConstraints(
                            List.of(
                                "Idempotency-Key scoped to authenticated customer (X-Customer-Id"
                                    + " header)",
                                "POST /reservations/{id}/cancel is idempotent on repeated cancel"))
                        .build()))
            .build();

    PlanSafetyContractEnricher.Result result = PlanSafetyContractEnricher.enrich(plan);
    PlannedTask enriched = result.plan().tasks().getFirst();

    assertThat(enriched.description())
        .doesNotContain("X-Customer-Id")
        .doesNotContainIgnoringCase("TestClient")
        .doesNotContainIgnoringCase("idempotent on already-cancelled")
        .doesNotContainIgnoringCase("second cancel returns the stored response")
        .contains("verified bearer/API credential")
        .contains("real HTTP client")
        .contains("stable invalid-state error");
    assertThat(enriched.hardConstraints())
        .noneMatch(value -> value.contains("X-Customer-Id"))
        .noneMatch(value -> value.toLowerCase().contains("idempotent on repeated cancel"))
        .noneMatch(value -> value.contains("launch the documented production server"))
        .anyMatch(value -> value.contains("verified credential"))
        .anyMatch(value -> value.contains("second cancellation"));
  }

  @Test
  void authenticatedMigrationPlanRemovesRuntimeSeedsPlaintextKeysAndCreateAll() {
    Plan plan =
        Plan.builder()
            .planTitle("Authenticated API")
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(
                List.of(
                    requirement("R1", "Requests use an authenticated customer."),
                    requirement("R2", "Include Alembic migrations.")))
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("Models, dependencies, and tests")
                        .description(
                            "Create Customer with api_key and get_current_customer validating"
                                + " against Customer table. main.py lifespan creating tables and"
                                + " seeding test products/customers. Seed one test product"
                                + " (stock=10) and one test customer (api_key=test-key). Document"
                                + " seeded test data (customer api_key, product). Startup seeds"
                                + " Product rows (non-negative stock) and a test Customer with a"
                                + " generated API key (hashed).")
                        .touchesFiles(
                            List.of(
                                "app/main.py",
                                "app/dependencies.py",
                                "alembic/env.py",
                                "tests/test_api.py"))
                        .hardConstraints(
                            List.of(
                                "Test fixtures insert Customer.api_key before API tests",
                                "Test credentials must be generated at startup, never hardcoded"))
                        .build()))
            .build();

    PlannedTask enriched = PlanSafetyContractEnricher.enrich(plan).plan().tasks().getFirst();

    assertThat(enriched.description())
        .doesNotContain("api_key=test-key")
        .doesNotContain("lifespan creating tables")
        .doesNotContain("seeded test data")
        .doesNotContain("test Customer with a generated API key")
        .contains("api_key_hash")
        .contains("running Alembic migrations")
        .contains("randomly generated credentials")
        .contains("one-way verifier");
    assertThat(enriched.hardConstraints())
        .noneMatch(value -> value.matches(".*\\bapi_key\\b.*"))
        .noneMatch(value -> value.contains("generated at startup"))
        .anyMatch(value -> value.contains("api_key_hash"))
        .anyMatch(value -> value.contains("must not seed, ship, log, document, or accept"));
  }

  @Test
  void repeatedEnrichmentIsIdempotentAndRewritesAsyncClientBoundToApp() {
    Plan plan =
        Plan.builder()
            .planTitle("HTTP tests")
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(List.of(requirement("R1", "Test authenticated HTTP behavior.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "pytest tests",
                        "Create a test fixture and yield httpx.AsyncClient against the FastAPI"
                            + " app.",
                        List.of("tests/conftest.py"))))
            .build();

    PlanSafetyContractEnricher.Result first = PlanSafetyContractEnricher.enrich(plan);
    PlanSafetyContractEnricher.Result second = PlanSafetyContractEnricher.enrich(first.plan());
    PlannedTask enriched = second.plan().tasks().getFirst();

    assertThat(enriched.description())
        .doesNotContain("against the FastAPI app")
        .contains("uvicorn server as a subprocess")
        .contains("loopback HTTP URL");
    assertThat(
            enriched.hardConstraints().stream()
                .filter(value -> value.startsWith("Functional tests must launch")))
        .hasSize(1);
    assertThat(second.contractsApplied()).isZero();
  }

  @Test
  void preservesNegativeTestClientConstraintWithoutNegatingRealHttp() {
    Plan plan =
        Plan.builder()
            .planTitle("HTTP tests")
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(List.of(requirement("R1", "Test HTTP behavior with pytest.")))
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("pytest acceptance tests")
                        .description(
                            "Launch uvicorn with a real HTTP client, not a TestClient fixture.")
                        .touchesFiles(List.of("tests/test_acceptance.py"))
                        .hardConstraints(
                            List.of(
                                "Launch the server as a subprocess; never use FastAPI TestClient"
                                    + " to simulate HTTP",
                                "Launch the server as a subprocess; never use FastAPI TestClient"
                                    + " to simulate HTTP"))
                        .build()))
            .build();

    PlannedTask enriched = PlanSafetyContractEnricher.enrich(plan).plan().tasks().getFirst();

    assertThat(enriched.description())
        .contains("not an in-process HTTP test client")
        .contains("real HTTP client")
        .doesNotContain("not a real HTTP client");
    assertThat(enriched.hardConstraints())
        .anyMatch(value -> value.contains("do not use an in-process HTTP test client"))
        .noneMatch(value -> value.contains("never use a real HTTP client"));
    assertThat(enriched.hardConstraints()).doesNotHaveDuplicates();
  }

  @Test
  void canonicalizesCredentialIdentityAndSubprocessFixtureContradictions() {
    Plan plan =
        Plan.builder()
            .planTitle("Authenticated HTTP service")
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(
                List.of(
                    requirement("R1", "Authenticate each customer with an API key."),
                    requirement("R2", "Test the HTTP API with pytest.")))
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("API authentication and pytest tests")
                        .description(
                            "Validate against a configurable mapping to customer_id. Use a live"
                                + " server with dependency overrides providing test API keys. Tests"
                                + " run in isolated transactions.")
                        .touchesFiles(List.of("app/auth.py", "tests/test_api.py"))
                        .hardConstraints(
                            List.of(
                                "IdempotencyRecord stores the SHA-256 hash of the API key (not"
                                    + " plaintext) as the customer_id lookup key",
                                "Launch uvicorn, not just TestClient"))
                        .build()))
            .build();

    PlannedTask enriched = PlanSafetyContractEnricher.enrich(plan).plan().tasks().getFirst();

    assertThat(enriched.description())
        .contains("externally configured mapping in memory")
        .contains("without persisting or logging raw credentials")
        .contains("provisioned before server startup")
        .contains("independently migrated database")
        .doesNotContain("dependency overrides")
        .doesNotContain("isolated transactions");
    assertThat(enriched.hardConstraints())
        .anyMatch(value -> value.contains("stable principal/customer ID"))
        .anyMatch(value -> value.contains("IdempotencyRecord references that customer_id"))
        .anyMatch(value -> value.contains("not an in-process HTTP test client"))
        .noneMatch(value -> value.contains("hash of the API key"));
  }

  @Test
  void rejectsCallerSelectedIdentityHeaderAndVagueSqliteLockingAcrossProviderPhrasing() {
    Plan plan =
        Plan.builder()
            .planTitle("Authenticated reservation API")
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(
                List.of(
                    requirement("R1", "Authenticate the customer."),
                    requirement("R2", "Use SQLite for atomic stock updates.")))
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("Authentication and reservation route")
                        .description(
                            "Implement customer authentication dependency (extracting customer"
                                + " identity from a secure header like Authorization or"
                                + " X-Customer-ID). Decrement stock using database-level"
                                + " transactions and optimistic/pessimistic locking.")
                        .touchesFiles(List.of("app/auth.py", "app/reservations.py"))
                        .hardConstraints(List.of("Never trust X-Customer-ID header"))
                        .build()))
            .build();

    PlannedTask enriched = PlanSafetyContractEnricher.enrich(plan).plan().tasks().getFirst();

    assertThat(enriched.description())
        .contains("resolving the stable customer principal server-side")
        .contains("SQLite-effective conditional atomic UPDATE")
        .doesNotContainIgnoringCase("X-Customer-ID")
        .doesNotContain("pessimistic locking");
    assertThat(enriched.hardConstraints())
        .noneMatch(value -> value.toLowerCase().contains("x-customer-id"));
  }

  @Test
  void replacesProcessLocalOrVagueSqliteSerializationWithDatabaseEffectiveInstructions() {
    Plan plan =
        Plan.builder()
            .planTitle("Order reservation")
            .requirements(
                List.of(
                    requirement("R1", "Use SQLite for atomic reservation of stock quantities.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Order creation",
                        "Reserve stock using an explicit transaction with SERIALIZABLE isolation"
                            + " or application-level lock.",
                        List.of("app/orders.py"))))
            .build();

    PlannedTask enriched = PlanSafetyContractEnricher.enrich(plan).plan().tasks().getFirst();

    assertThat(enriched.description())
        .contains("SQLite-effective BEGIN IMMEDIATE")
        .doesNotContainIgnoringCase("SERIALIZABLE isolation")
        .doesNotContainIgnoringCase("application-level lock");
  }

  @Test
  void dedicatedHttpTestTaskGetsCrossPrincipalAndIdempotencyIsolationProof() {
    Plan plan =
        Plan.builder()
            .planTitle("Authenticated idempotent API")
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(
                List.of(
                    requirement("R1", "Authenticate each customer."),
                    requirement("R2", "Scope Idempotency-Key to the authenticated customer.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Comprehensive pytest suite",
                        "Test happy paths and errors over HTTP.",
                        List.of("tests/test_reservations.py", "tests/conftest.py"))))
            .build();

    PlannedTask enriched = PlanSafetyContractEnricher.enrich(plan).plan().tasks().getFirst();

    assertThat(enriched.hardConstraints())
        .anyMatch(value -> value.contains("forged credentials"))
        .noneMatch(value -> value.contains("cross-principal owned-resource"))
        .anyMatch(value -> value.contains("reuse the same Idempotency-Key"))
        .anyMatch(value -> value.contains("without collision or response leakage"));
  }

  @Test
  void removesCrossTaskConstraintPollutionAndKeepsAcceptanceGreen() {
    Plan plan =
        Plan.builder()
            .planTitle("Inventory application")
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .buildCommand("cd backend && python -m compileall app")
            .requirements(
                List.of(
                    requirement("R1", "Use Python 3.12+ for the backend."),
                    requirement("R2", "Apply database migrations at startup."),
                    requirement("R9", "Create orders with atomic stock reservation."),
                    requirement("R12", "Return sanitized error responses.")))
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T2")
                        .title("Product inventory CRUD")
                        .description("Implement product and inventory endpoints.")
                        .touchesFiles(List.of("backend/app/products.py"))
                        .meetsRequirements(List.of("R2"))
                        .hardConstraints(
                            List.of(
                                "Reject reservation quantity <= 0 through the public API.",
                                "SQLite must preserve stock >= quantity."))
                        .build(),
                    PlannedTask.builder()
                        .id("T6")
                        .title("Inventory dashboard")
                        .description("Build the React dashboard.")
                        .touchesFiles(List.of("frontend/src/App.tsx", "frontend/package.json"))
                        .hardConstraints(
                            List.of(
                                "React 18 is required.",
                                "Test fixtures must apply migrations to a fresh database.",
                                "Use SQLite BEGIN IMMEDIATE."))
                        .build(),
                    PlannedTask.builder()
                        .id("T8")
                        .title("Black-box acceptance suite")
                        .description("Exercise the live HTTP service.")
                        .touchesFiles(List.of("backend/tests/test_acceptance.py"))
                        .hardConstraints(
                            List.of(
                                "Do not implement production code; the test may fail until the bug"
                                    + " is fixed."))
                        .build()))
            .build();

    PlanSafetyContractEnricher.Result result = PlanSafetyContractEnricher.enrich(plan);

    assertThat(result.plan().buildCommand()).isNull();
    assertThat(constraints(result, "T2"))
        .noneMatch(value -> value.toLowerCase().contains("reservation quantity"))
        .noneMatch(value -> value.toLowerCase().contains("stock >= quantity"));
    assertThat(constraints(result, "T6"))
        .contains("React 18 is required.")
        .noneMatch(value -> value.toLowerCase().contains("migration"))
        .noneMatch(value -> value.toLowerCase().contains("sqlite"));
    assertThat(constraints(result, "T8"))
        .noneMatch(value -> value.toLowerCase().contains("test may fail until"))
        .anyMatch(value -> value.contains("fix the owning production code"));
  }

  @Test
  void alignsOrderStatusConsumersAndRemovesNegatedSqliteAtomicity() {
    Plan plan =
        Plan.builder()
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(
                List.of(
                    requirement("R1", "Use SQLite for atomic order reservation."),
                    requirement("R2", "Support cancelling an order exactly once.")))
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("Define order models and migration")
                        .description(
                            "Order owns OrderLine rows. Order.status has COMPLETED and CANCELLED"
                                + " values and defaults to COMPLETED.")
                        .touchesFiles(List.of("backend/app/models/order.py"))
                        .hardConstraints(List.of())
                        .build(),
                    PlannedTask.builder()
                        .id("T3")
                        .title("Implement order reservation service")
                        .description(
                            "Create orders with Order.status CONFIRMED or CANCELLED. No `BEGIN"
                                + " IMMEDIATE or a conditional UPDATE with affected-row"
                                + " verification`. Use SQLite's serialized isolation.")
                        .touchesFiles(List.of("backend/app/services/orders.py"))
                        .hardConstraints(List.of())
                        .build()))
            .build();

    PlannedTask consumer = PlanSafetyContractEnricher.enrich(plan).plan().tasks().get(1);

    assertThat(consumer.description())
        .contains("Order.status COMPLETED or CANCELLED")
        .contains("Use BEGIN IMMEDIATE or a conditional UPDATE with affected-row verification")
        .contains("SQLite-effective BEGIN IMMEDIATE serialization")
        .doesNotContain("CONFIRMED")
        .doesNotContain("No `BEGIN IMMEDIATE")
        .doesNotContain("serialized isolation");
  }

  @Test
  void derivesCancelledOrderLifecycleAndKeepsBuildAndTestCommandsPure() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    requirement("R1", "Use Alembic migrations."),
                    requirement("R2", "Support cancelling an order exactly once."),
                    requirement("R3", "Do not install dependencies in build/test commands.")))
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("Scaffold Makefile and backend runtime")
                        .description(
                            "Create a lifespan that creates tables on startup (via"
                                + " Base.metadata.create_all). Makefile targets: install (pip and"
                                + " npm), build (install + frontend build), and test.")
                        .touchesFiles(List.of("Makefile", "backend/app/main.py"))
                        .hardConstraints(List.of())
                        .build(),
                    PlannedTask.builder()
                        .id("T2")
                        .title("Define order models and migration")
                        .description(
                            "Order owns OrderLine rows and Order.status defaults to PENDING.")
                        .touchesFiles(List.of("backend/app/models.py"))
                        .hardConstraints(List.of())
                        .build(),
                    PlannedTask.builder()
                        .id("T3")
                        .title("Build order detail UI")
                        .description(
                            "Show cancel when status is not COMPLETED. After cancellation status"
                                + " becomes COMPLETED.")
                        .touchesFiles(List.of("frontend/src/OrderDetail.tsx"))
                        .hardConstraints(
                            List.of(
                                "Remove this constraint from T3 — backend migrations are"
                                    + " irrelevant."))
                        .build()))
            .build();

    Plan enriched = PlanSafetyContractEnricher.enrich(plan).plan();

    assertThat(enriched.tasks().get(0).description())
        .contains("runs Alembic migrations")
        .contains("without dependency installation")
        .doesNotContain("Base.metadata.create_all")
        .doesNotContain("build (install + frontend build)");
    assertThat(enriched.tasks().get(0).hardConstraints())
        .anyMatch(value -> value.contains("Build and test commands must never"));
    assertThat(enriched.tasks().get(1).hardConstraints())
        .anyMatch(value -> value.contains("exactly PENDING and CANCELLED"));
    assertThat(enriched.tasks().get(2).description())
        .contains("status is not CANCELLED")
        .contains("status becomes CANCELLED")
        .doesNotContain("COMPLETED");
    assertThat(enriched.tasks().get(2).hardConstraints()).isEmpty();
  }

  @Test
  void canonicalizesPythonFullStackMetadataAndRejectsChainedTestBuildCommand() {
    Plan plan =
        Plan.builder()
            .projectType("node-backend")
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .buildCommand(
                "cd backend && python3.12 -m pytest && cd ../frontend && npx playwright test")
            .requirements(List.of(requirement("R1", "Use Python 3.12 FastAPI.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Scaffold FastAPI backend",
                        "Create backend/pyproject.toml with FastAPI and SQLAlchemy.",
                        List.of("backend/pyproject.toml"))))
            .build();

    Plan result = PlanSafetyContractEnricher.enrich(plan).plan();

    assertThat(result.projectType()).isEqualTo("python");
    assertThat(result.buildCommand()).isNull();
  }

  @Test
  void canonicalizesMixedSqlalchemySessionModesToOneExecutableContract() {
    Plan plan =
        Plan.builder()
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(List.of(requirement("R1", "Use SQLAlchemy 2 with SQLite.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Scaffold database",
                        "Create database.py with AsyncEngine, async_sessionmaker, and async"
                            + " get_db.",
                        List.of("backend/app/database.py")),
                    task(
                        "T2",
                        "Implement stock service",
                        "Implement stock operations with a synchronous Session and db: Session.",
                        List.of("backend/app/services/stock.py"))))
            .build();

    Plan result = PlanSafetyContractEnricher.enrich(plan).plan();

    assertThat(result.tasks().getFirst().description())
        .contains("Engine", "sessionmaker", "synchronous get_db")
        .doesNotContain("AsyncEngine", "AsyncSession", "async_sessionmaker", "async get_db");
    assertThat(result.tasks())
        .allSatisfy(
            task ->
                assertThat(task.hardConstraints())
                    .anyMatch(value -> value.contains("one synchronous SQLAlchemy 2")));
  }

  @Test
  void orderStatusAlignmentCannotCorruptIdempotencyLifecycle() {
    String corrupted =
        "Use one exact IdempotencyRecord contract everywhere: key, scope, request_body_hash, state,"
            + " nullable response_status, and nullable response_body; enforce a database unique"
            + " constraint on (key, scope), use PENDING/PENDING states, and keep ownership,"
            + " business mutation, and completed replay in one transaction. idempotency_key and"
            + " scope_hash are forbidden aliases.";
    Plan plan =
        Plan.builder()
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(
                List.of(
                    requirement("R1", "Order cancellation uses PENDING and CANCELLED states."),
                    requirement(
                        "R2",
                        "Idempotency-Key with the same key replays exactly; conflicting reuse"
                            + " returns 409.")))
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("Define order models and migration")
                        .description("Order.status has PENDING and CANCELLED values.")
                        .touchesFiles(List.of("backend/app/models/order.py"))
                        .hardConstraints(List.of())
                        .build(),
                    PlannedTask.builder()
                        .id("T2")
                        .title("Implement idempotency service and order cancellation")
                        .description(
                            "Use Order.status ACTIVE before cancellation and IdempotencyRecord for"
                                + " replay.")
                        .touchesFiles(List.of("backend/app/services/idempotency.py"))
                        .hardConstraints(List.of(corrupted))
                        .build()))
            .build();

    PlannedTask result = PlanSafetyContractEnricher.enrich(plan).plan().tasks().get(1);

    assertThat(result.hardConstraints())
        .noneMatch(value -> value.contains("PENDING/PENDING"))
        .anyMatch(value -> value.contains("PROCESSING/COMPLETED"));
  }

  @Test
  void canonicalizesImplicitBuildInstallHeaderTransportAndFinalRealHttpVerification() {
    Plan plan =
        Plan.builder()
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(
                List.of(
                    requirement(
                        "R1",
                        "The order creation endpoint accepts an Idempotency-Key header and replays"
                            + " the same request.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Scaffold root orchestration",
                        "Create Makefile targets: `build` (install backend deps via pip, install"
                            + " frontend deps via npm ci), test, and run.",
                        List.of("Makefile", "backend/app/main.py")),
                    task(
                        "T4",
                        "Implement order API",
                        "`OrderCreate`: idempotency_key (str, required), lines"
                            + " (list[OrderLineCreate]). POST /orders also reads the"
                            + " Idempotency-Key header and uses affected-row verificationor a"
                            + " transaction.",
                        List.of("backend/app/routers/orders.py")),
                    task(
                        "T8",
                        "Real HTTP acceptance tests",
                        "Launch the production server and verify the API over real HTTP.",
                        List.of("backend/tests/test_orders.py")),
                    task(
                        "T10",
                        "README and final verification",
                        "Document that `make build` runs type-checking, linting, and test."
                            + " Configure pytest with the FastAPI TestClient.",
                        List.of("README.md", "backend/tests/conftest.py"))))
            .build();

    Plan enriched = PlanSafetyContractEnricher.enrich(plan).plan();

    assertThat(enriched.tasks().get(0).description())
        .contains("`install`")
        .contains("without dependency installation")
        .doesNotContain("`build` (install backend deps");
    assertThat(enriched.tasks().get(0).hardConstraints())
        .anyMatch(value -> value.contains("Build and test commands must never"));
    assertThat(enriched.tasks().get(1).description())
        .contains("`OrderCreate`: lines")
        .contains("affected-row verification or")
        .doesNotContain("OrderCreate`: idempotency_key");
    assertThat(enriched.tasks().get(1).hardConstraints())
        .anyMatch(value -> value.contains("required HTTP header"));
    assertThat(enriched.tasks().get(3).description())
        .contains("without installing dependencies")
        .contains("real HTTP client")
        .doesNotContain("TestClient");
  }

  @Test
  void replacesStaleDomainProseInsteadOfOnlyAppendingContradictoryConstraints() {
    Plan plan =
        Plan.builder()
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(
                List.of(
                    requirement(
                        "R1", "Inventory available = on_hand - reserved and orders reserve stock."),
                    requirement(
                        "R2",
                        "The order endpoint requires Idempotency-Key; same key replays and a"
                            + " conflicting body returns 409."),
                    requirement("R3", "Use Alembic migrations.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Define order models and migration",
                        "Order owns OrderLine rows and status values PROCESSING, COMPLETED,"
                            + " CANCELLED. `IdempotencyRecord`: id (int PK), key (str, unique,"
                            + " indexed), response_body (str, nullable while PROCESSING),"
                            + " status_code (int, nullable), created_at (datetime). The lifespan"
                            + " that creates tables on startup (call Base.metadata.create_all as a"
                            + " fallback; the migration runner is the primary path).",
                        List.of("backend/app/models.py", "backend/alembic/versions/001.py")),
                    task(
                        "T3",
                        "Implement order creation",
                        "POST /orders accepts an Idempotency-Key header (optional)."
                            + " create_order(db, order_data, idempotency_key=None) decrements"
                            + " on_hand and increments reserved by quantity. After commit: update"
                            + " the IdempotencyRecord with the response.",
                        List.of("backend/app/services/order_service.py")),
                    task(
                        "T4",
                        "Implement order cancellation",
                        "Cancel the order by incrementing on_hand and decrementing reserved by the"
                            + " line's quantity.",
                        List.of("backend/app/services/order_service.py")),
                    task(
                        "T9",
                        "Root build and real HTTP verification",
                        "build.sh installs backend dependencies with pip install and frontend"
                            + " dependencies with npm ci. The real HTTP client uses an in-memory"
                            + " SQLite database in the server subprocess and expects the new order"
                            + " to return PENDING status.",
                        List.of("build.sh", "backend/tests/test_integration.py"))))
            .build();

    Plan enriched = PlanSafetyContractEnricher.enrich(plan).plan();

    assertThat(enriched.tasks().get(0).description())
        .contains("UniqueConstraint(key, scope)")
        .contains("runs `alembic upgrade head`")
        .doesNotContain("Base.metadata.create_all");
    assertThat(enriched.tasks().get(1).description())
        .contains("header (required)")
        .contains("idempotency_key)")
        .contains("leave `on_hand` unchanged and increment `reserved`")
        .contains("Before the single transaction commits")
        .doesNotContain("After commit");
    assertThat(enriched.tasks().get(2).description())
        .contains("leave `on_hand` unchanged and decrement `reserved`");
    assertThat(enriched.tasks().get(3).description())
        .contains("temporary file-backed SQLite database")
        .contains("COMPLETED status")
        .doesNotContain("pip install", "npm ci");
    assertThat(enriched.tasks().get(3).hardConstraints())
        .anyMatch(value -> value.contains("Build and test commands must never"));
  }

  @Test
  void closesGreenfieldApiLifecycleMigrationAndBlackBoxContractGaps() {
    Plan plan =
        Plan.builder()
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(
                List.of(
                    requirement("R1", "Create an order with multiple SKU quantities."),
                    requirement("R2", "Reserve an order atomically from one warehouse."),
                    requirement("R3", "Release/cancel a reservation exactly once."),
                    requirement("R4", "Model reservation lines and reservation lifecycle."),
                    requirement("R5", "Include Alembic migrations."),
                    requirement("R6", "Allow a positive or negative stock adjustment."),
                    requirement("R7", "Include independent black-box public HTTP tests.")))
            .tasks(
                List.of(
                    task(
                        "T2",
                        "Implement order and reservation models",
                        "Order.status values are PENDING, COMPLETED, PROCESSING. Reservation.status"
                            + " is PENDING or CANCELLED and owns ReservationLine rows.",
                        List.of(
                            "backend/app/models/order.py",
                            "backend/app/models/reservation.py",
                            "backend/alembic/versions/001_initial.py")),
                    task(
                        "T3",
                        "Implement inventory and reservation API routes",
                        "POST /api/warehouses/{warehouse_id}/adjust changes stock. POST"
                            + " /api/orders/{order_id}/reserve reserves. POST"
                            + " /api/reservations/{reservation_id}/release releases.",
                        List.of("backend/app/api/inventory.py", "backend/app/api/reservation.py")),
                    task(
                        "T4",
                        "Implement IdempotencyRecord model and service",
                        "Add the canonical IdempotencyRecord model.",
                        List.of("backend/app/models/idempotency.py")),
                    task(
                        "T8",
                        "Build frontend inventory and reservation flows",
                        "POST /api/inventory/adjustments, POST /api/orders/{order_id}/reserve, and"
                            + " POST /api/orders/{order_id}/release.",
                        List.of("frontend/src/api/client.ts")),
                    task(
                        "T11",
                        "Playwright browser E2E tests",
                        "Submit adjustment with negative quantity and assert a validation error.",
                        List.of("frontend/e2e/inventory.spec.ts")),
                    task(
                        "T12",
                        "Independent black-box acceptance suite",
                        "Create a warehouse and product via API, note their IDs. Restart the"
                            + " backend. GET the same warehouse/product and assert persistence.",
                        List.of("acceptance/test_persistence.py"))))
            .build();

    Plan enriched = PlanSafetyContractEnricher.enrich(plan).plan();

    PlannedTask schema = enriched.tasks().get(0);
    assertThat(schema.hardConstraints())
        .anyMatch(value -> value.contains("exactly PENDING and RESERVED"))
        .anyMatch(value -> value.contains("Reservation.status lifecycle is exactly ACTIVE"));
    assertThat(schema.description()).doesNotContain("COMPLETED", "PROCESSING", "CANCELLED");
    PlannedTask idempotency = enriched.tasks().get(2);
    assertThat(idempotency.touchesFiles())
        .contains("backend/alembic/versions/002_add_idempotency_record.py");
    assertThat(idempotency.hardConstraints())
        .anyMatch(value -> value.contains("model file without a migration is incomplete"));
    assertThat(enriched.tasks().get(3).description())
        .contains("/api/warehouses/{warehouse_id}/adjust")
        .contains("/api/reservations/{reservation_id}/release")
        .doesNotContain("/api/inventory/adjustments", "/api/orders/{order_id}/release");
    assertThat(enriched.tasks().get(3).dependsOn()).contains("T3");
    assertThat(enriched.tasks().get(4).description())
        .contains("zero delta")
        .contains("negative delta that would make stock negative");
    assertThat(enriched.tasks().get(5).description())
        .contains("deterministic seeded warehouse/product IDs")
        .doesNotContain("Create a warehouse and product via API");
  }

  @Test
  void lifecycleCanonicalizationIsIdempotentAndPreservesReleaseTransitionSemantics() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    requirement("R1", "Create an order and reserve an order atomically."),
                    requirement("R2", "Release/cancel a reservation exactly once."),
                    requirement("R3", "Persist reservation lines and reservation lifecycle.")))
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T2")
                        .title("Order and reservation models")
                        .description(
                            "Order.status lifecycle values are PENDING, RESERVED, CANCELLED. New"
                                + " and released orders are PENDING. Reservation.status allowed"
                                + " values are RESERVED, RELEASED, CANCELLED; default RESERVED.")
                        .touchesFiles(List.of("backend/app/models.py"))
                        .hardConstraints(
                            List.of(
                                "Reservation.status allowed values are exactly RESERVED,"
                                    + " RELEASED, CANCELLED. Default is RESERVED."))
                        .build(),
                    task(
                        "T7",
                        "Release reservation",
                        "Atomically transition the Reservation status from RESERVED to RELEASED"
                            + " using UPDATE reservations SET status='RELEASED' WHERE id=:rid AND"
                            + " status='RESERVED'. Reservation status must be RESERVED before"
                            + " release.",
                        List.of("backend/app/services/reservation_service.py"))))
            .build();

    Plan once = PlanSafetyContractEnricher.enrich(plan).plan();
    Plan twice = PlanSafetyContractEnricher.enrich(once).plan();
    PlannedTask model = twice.tasks().get(0);
    PlannedTask release = twice.tasks().get(1);

    assertThat(model.description()).containsOnlyOnce("Order.status lifecycle values are exactly");
    assertThat(model.description())
        .contains(
            "Order persists status (string: PENDING | RESERVED, default PENDING)",
            "Reservation persists order_id, status (default ACTIVE)")
        .contains("Each ReservationLine persists reservation_id, order_line_id, inventory_id, sku")
        .doesNotContain("Reservation.idempotency_key");
    assertThat(model.hardConstraints())
        .containsOnlyOnce(
            "Reservation.status lifecycle is exactly ACTIVE and RELEASED. A successful"
                + " reservation is ACTIVE; release/cancel atomically claims ACTIVE→RELEASED"
                + " before restoring stock, and any second release/cancel returns INVALID_STATE.")
        .noneMatch(value -> value.contains("RESERVED, RELEASED, CANCELLED"));
    assertThat(release.description())
        .contains("from ACTIVE to RELEASED")
        .contains("SET status='RELEASED'")
        .contains("status='ACTIVE'")
        .contains("status must be ACTIVE")
        .doesNotContain("from RELEASED to RELEASED");
    assertThat(twice.tasks().stream().map(PlannedTask::description).toList())
        .containsExactlyElementsOf(once.tasks().stream().map(PlannedTask::description).toList());
  }

  @Test
  void repairsLegacyIdempotencyModelAndReadModifyWriteStockMutation() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    requirement("R1", "Exact idempotency replay must detect a conflicting body."),
                    requirement("R2", "Stock must remain non-negative under concurrent writes."),
                    requirement("R3", "Include immutable stock adjustment and transfer audits.")))
            .tasks(
                List.of(
                    task(
                        "T2",
                        "Implement IdempotencyKey model",
                        "- **IdempotencyKey**: key, scope, response_status, response_body, status"
                            + " (PENDING or PENDING).\n\n**Migration:** create the table."
                            + " complete_record updates the record to PENDING.",
                        List.of("backend/app/models.py", "backend/alembic/versions/001.py")),
                    task(
                        "T3",
                        "Implement stock adjustment and transfer services",
                        "Load Stock, compute new_quantity, assign stock.quantity, then commit."
                            + " Transfer loads source and destination before assigning both.",
                        List.of("backend/app/services/inventory_service.py"))))
            .build();

    Plan enriched = PlanSafetyContractEnricher.enrich(plan).plan();

    assertThat(enriched.tasks().get(0).description())
        .contains("**IdempotencyRecord**")
        .contains("request_body_hash")
        .contains("PROCESSING` or `COMPLETED")
        .contains("complete_record updates the record to COMPLETED")
        .doesNotContain("IdempotencyKey", "PENDING or PENDING");
    assertThat(enriched.tasks().get(1).description())
        .contains("never implement stock mutation as a read-compute-write assignment")
        .contains("on_hand + :delta >= reserved")
        .contains("on_hand - :quantity >= reserved");
    assertThat(enriched.tasks().get(1).hardConstraints())
        .anyMatch(value -> value.contains("read-compute-write ORM assignment is forbidden"));
  }

  @Test
  void replacesRealHttpAppFactoryAndDoubleCancelSuccessContradictions() {
    Plan plan =
        Plan.builder()
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(
                List.of(
                    requirement("R1", "Verify the complete product over real HTTP."),
                    requirement(
                        "R2",
                        "Concurrent cancel must release reserved stock exactly once and a second"
                            + " cancel is an invalid state.")))
            .tasks(
                List.of(
                    task(
                        "T8",
                        "Final HTTP acceptance tests",
                        "Use FastAPI TestClient against the app factory, not a subprocess server,"
                            + " with sqlite:///:memory:. Assert the second cancel returns HTTP 200"
                            + " as an idempotent response.",
                        List.of("backend/tests/test_acceptance.py"))))
            .build();

    PlannedTask task = PlanSafetyContractEnricher.enrich(plan).plan().tasks().getFirst();

    assertThat(task.description())
        .contains("server as a subprocess")
        .contains("temporary file-backed SQLite")
        .contains("invalid-state 409")
        .doesNotContainIgnoringCase("TestClient")
        .doesNotContain(":memory:")
        .doesNotContain("returns HTTP 200");
    assertThat(task.hardConstraints())
        .anyMatch(value -> value.contains("second cancellation must return"));
  }

  @Test
  void bundledApiTestsDoNotTurnImplementationTaskIntoBlackBoxAcceptanceOwner() {
    Plan plan =
        Plan.builder()
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(
                List.of(
                    requirement("R1", "Provide independent black-box public HTTP tests."),
                    requirement("R2", "Prove persistence across restart.")))
            .tasks(
                List.of(
                    task(
                        "T5",
                        "Implement API routes and tests",
                        "Implement the routes and use TestClient for focused route tests.",
                        List.of("backend/app/api/routes.py", "backend/tests/test_routes.py")),
                    task(
                        "T12",
                        "Independent black-box acceptance suite",
                        "Launch the documented production server and exercise it over real HTTP.",
                        List.of("acceptance/test_http.py"))))
            .build();

    Plan enriched = PlanSafetyContractEnricher.enrich(plan).plan();
    PlannedTask implementation = enriched.tasks().get(0);
    PlannedTask acceptance = enriched.tasks().get(1);

    assertThat(implementation.description()).doesNotContain("PERSISTENCE PROOF (binding)");
    assertThat(implementation.hardConstraints())
        .noneMatch(value -> value.contains("Functional tests must launch"));
    assertThat(acceptance.description()).contains("PERSISTENCE PROOF (binding)");
    assertThat(acceptance.hardConstraints())
        .anyMatch(value -> value.contains("Functional tests must launch"));
  }

  @Test
  void alignsReservationServiceAndApiDtosToOneParentAggregate() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    requirement("R1", "Create one order with multiple order lines."),
                    requirement("R2", "Persist reservation lines and reservation lifecycle."),
                    requirement("R3", "Release a reservation exactly once.")))
            .tasks(
                List.of(
                    task(
                        "T2",
                        "Order and reservation models",
                        "Reservation owns ReservationLine rows.",
                        List.of(
                            "backend/app/models/order.py", "backend/app/models/reservation.py")),
                    task(
                        "T4",
                        "Reservation service",
                        "Create Reservation rows (one per order line), then release them.",
                        List.of("backend/app/services/reservation_service.py")),
                    task(
                        "T5",
                        "Reservation API routes",
                        "Return ReservationResponse(id, order_line_id, warehouse_id,"
                            + " reserved_quantity, status); errors use IDEMPOTENCY_CONFLICT.",
                        List.of("backend/app/api/reservations.py")),
                    task(
                        "T7",
                        "Frontend reservation client",
                        "Call POST `/api/reservations/reserve`.",
                        List.of("frontend/src/api/client.ts"))))
            .build();

    Plan enriched = PlanSafetyContractEnricher.enrich(plan).plan();

    assertThat(enriched.tasks().get(1).description())
        .contains("Create one Reservation parent and ReservationLine children");
    assertThat(enriched.tasks().get(2).description())
        .contains("ReservationResponse(id, order_id, status, lines)")
        .contains("IDEMPOTENCY_KEY_CONFLICT")
        .doesNotContain("IDEMPOTENCY_CONFLICT");
    assertThat(enriched.tasks().get(3).description())
        .contains("POST `/api/reservations`")
        .doesNotContain("/api/reservations/reserve");
  }

  @Test
  void repairsRootBuildWorkingDirectoryAndTechnicalIdentifierSpacing() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    requirement(
                        "R1",
                        "Provide root orchestration for the Python backend and React frontend.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Root orchestration",
                        "Use SQLAlchemy>=2. 0 and `sqlite:///. /app. db` in `backend. app."
                            + " database. Base`; build.sh runs cd backend && python -m compileall"
                            + " app then npm --prefix frontend run build.",
                        List.of("build.sh", "backend/app/database.py", "frontend/vite.config.ts"))))
            .build();

    PlannedTask task = PlanSafetyContractEnricher.enrich(plan).plan().tasks().getFirst();

    assertThat(task.description())
        .contains("SQLAlchemy>=2.0")
        .contains("`sqlite:///./app.db`")
        .contains("`backend.app.database.Base`")
        .contains("repository root")
        .contains("npm --prefix frontend run build")
        .doesNotContain("cd backend");
  }

  @Test
  void rootBuildTestScriptRequirementOverridesBackendOnlyPlannerCommand() {
    Plan plan =
        Plan.builder()
            .buildCommand("python3.12 -m compileall -q backend/")
            .requirements(
                List.of(
                    requirement(
                        "R1",
                        "Add a small root build/test script for the Python backend and React"
                            + " frontend.")))
            .tasks(
                List.of(
                    task(
                        "T9",
                        "Create root build/test script",
                        "Document the repeatable build and test entrypoints.",
                        List.of("test.sh", "README.md"))))
            .build();

    Plan enriched = PlanSafetyContractEnricher.enrich(plan).plan();

    assertThat(enriched.buildCommand()).isEqualTo("./build.sh");
    assertThat(enriched.tasks().getFirst().touchesFiles())
        .contains("build.sh", "run_tests.sh")
        .doesNotContain("test.sh");
    assertThat(enriched.tasks().getFirst().hardConstraints())
        .anyMatch(value -> value.contains("npm --prefix frontend run build"));
  }

  @Test
  void sqliteIdempotencyUsesCommittedWriterSerializationInsteadOfInvisiblePolling() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    requirement(
                        "X5",
                        "On SQLite, concurrent use of the same Idempotency-Key must create one"
                            + " order and replay the exact response. Use Alembic migrations.")))
            .tasks(
                List.of(
                    task(
                        "T4",
                        "Implement idempotency store",
                        "Insert PROCESSING, then let the losing transaction poll the uncommitted"
                            + " row until it becomes COMPLETED.",
                        List.of(
                            "backend/app/services/idempotency_store.py",
                            "backend/app/models.py",
                            "backend/alembic/versions/001.py"))))
            .build();

    PlannedTask task = PlanSafetyContractEnricher.enrich(plan).plan().tasks().getFirst();

    assertThat(task.description())
        .contains("SQLITE IDEMPOTENCY ALGORITHM (binding)")
        .contains("BEGIN IMMEDIATE")
        .contains("execute the guarded business mutation")
        .contains(
            "never poll for another transaction's uncommitted PROCESSING record",
            "IDEMPOTENCY PERSISTENCE CONTRACT (binding)")
        .doesNotContain("poll the uncommitted row", "create the Order");
    assertThat(task.touchesFiles())
        .contains("backend/app/models.py", "backend/alembic/versions/001.py")
        .doesNotContain("backend/alembic/versions/002_add_idempotency_record.py");
    assertThat(task.hardConstraints()).anyMatch(value -> value.contains("competing writer blocks"));
  }

  @Test
  void idempotencyBehaviorTestsDoNotBecomePersistenceOrMigrationOwners() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    requirement(
                        "R1",
                        "Idempotency-Key repeats replay the exact response using SQLite and"
                            + " Alembic.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Implement Order and IdempotencyRecord schema",
                        "Create both tables in the aggregate migration.",
                        List.of(
                            "backend/app/models/order.py",
                            "backend/app/models/idempotency_record.py",
                            "backend/alembic/versions/003_orders.py")),
                    task(
                        "T2",
                        "Integration tests for idempotent replay",
                        "Test exact replay and conflicting key reuse.",
                        List.of("backend/tests/test_idempotency.py"))))
            .build();

    Plan enriched = PlanSafetyContractEnricher.enrich(plan).plan();

    assertThat(enriched.tasks().getFirst().touchesFiles())
        .contains("backend/alembic/versions/003_orders.py")
        .doesNotContain("backend/alembic/versions/002_add_idempotency_record.py");
    assertThat(enriched.tasks().get(1).touchesFiles())
        .containsExactly("backend/tests/test_idempotency.py");
  }

  @Test
  void canonicalReplayContractRemovesStaleBusinessAggregateOwnership() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    requirement(
                        "R1",
                        "Same principal and Idempotency-Key must replay the exact response; use"
                            + " SQLite and Alembic migrations."),
                    requirement(
                        "R2",
                        "An Order owns lines and a Reservation owns reservation lines; the"
                            + " reservation lifecycle supports release/cancel a reservation.")))
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("Create all database models")
                        .description(
                            "Define **Order** in backend/app/models.py. 6. **Reservation**: id,"
                                + " order_id, status. 7. **ReservationLine**: id, reservation_id,"
                                + " warehouse_id, sku, quantity.")
                        .touchesFiles(
                            List.of(
                                "backend/app/models.py", "backend/alembic/versions/001_initial.py"))
                        .hardConstraints(List.of())
                        .meetsRequirements(List.of("R2"))
                        .build(),
                    PlannedTask.builder()
                        .id("T4")
                        .title("Implement order idempotency")
                        .description("Create and replay orders atomically.")
                        .touchesFiles(List.of("backend/app/services/idempotency_store.py"))
                        .hardConstraints(
                            List.of(
                                "Use the T1 models exactly. Order: id, status, idempotency_key,"
                                    + " created_at."))
                        .meetsRequirements(List.of("R1"))
                        .build()))
            .build();

    Plan enriched =
        TaskExpanderAgent.normalizeFinalPlan(PlanSafetyContractEnricher.enrich(plan).plan());
    PlannedTask schema = enriched.tasks().getFirst();
    PlannedTask behavior = enriched.tasks().get(1);

    assertThat(schema.description()).contains("CONTRACT CORRECTION (binding schema)");
    assertThat(schema.description())
        .contains("order_line_id (FK→order_lines.id)", "inventory_id (FK→inventory.id)")
        .doesNotContain("**ReservationLine**: id, reservation_id, warehouse_id, sku, quantity.");
    assertThat(behavior.hardConstraints())
        .noneMatch(
            value -> value.contains("Order:") && value.toLowerCase().contains("idempotency_key"))
        .anyMatch(value -> value.contains("Do not store replay ownership on Order"));
    assertThat(TaskExpanderAgent.finalPlanContractViolations(enriched)).isEmpty();
  }

  @Test
  void packageMarkerAloneDoesNotRewriteMonolithicModelsPath() {
    Plan plan =
        Plan.builder()
            .requirements(List.of(requirement("R13", "Persist inventory and transfer audits.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Create database models",
                        "Create the canonical models package.",
                        List.of("backend/app/models/__init__.py")),
                    task(
                        "T3",
                        "Implement stock audit and Transfer models",
                        "Extend backend/app/models.py with StockAudit and Transfer.",
                        List.of("backend/app/models.py"))))
            .build();

    PlannedTask task = PlanSafetyContractEnricher.enrich(plan).plan().tasks().get(1);

    assertThat(task.touchesFiles()).containsExactly("backend/app/models.py");
    assertThat(task.description()).contains("backend/app/models.py");
  }

  @Test
  void packageRewriteRequiresAndUsesConcreteModelModulePaths() {
    Plan plan =
        Plan.builder()
            .requirements(List.of(requirement("R13", "Persist inventory and transfer audits.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Create database models",
                        "Create the canonical models package.",
                        List.of(
                            "backend/app/models/__init__.py",
                            "backend/app/models/stock_audit.py",
                            "backend/app/models/transfer.py")),
                    task(
                        "T3",
                        "Implement stock audit and Transfer models",
                        "Extend backend/app/models.py with StockAudit and Transfer.",
                        List.of("backend/app/models.py"))))
            .build();

    PlannedTask task = PlanSafetyContractEnricher.enrich(plan).plan().tasks().get(1);

    assertThat(task.touchesFiles())
        .containsExactly(
            "backend/app/models/__init__.py",
            "backend/app/models/stock_audit.py",
            "backend/app/models/transfer.py");
    assertThat(task.description())
        .contains("backend/app/models/stock_audit.py")
        .doesNotContain("backend/app/models.py", "modules (exported");
  }

  @Test
  void backendRoutesUseTheFrontendClientsDeclaredApiBasePath() {
    Plan plan =
        Plan.builder()
            .requirements(List.of(requirement("R1", "Frontend and backend paths must match.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Implement backend routers",
                        "Expose `GET /warehouses`, `POST /orders`, and use prefix `/inventory`.",
                        List.of("backend/app/routers/orders.py")),
                    task(
                        "T2",
                        "Create frontend API client",
                        "The Vite proxy forwards `/api`; the client base URL defaults to `/api`.",
                        List.of("frontend/src/api/client.ts"))))
            .build();

    PlannedTask backend = PlanSafetyContractEnricher.enrich(plan).plan().tasks().getFirst();

    assertThat(backend.description())
        .contains("GET /api/warehouses", "POST /api/orders", "prefix `/api/inventory`")
        .doesNotContain("GET /warehouses", "POST /orders", "prefix `/inventory`");
  }

  @Test
  void removesBackendCancellationStateConstraintFromFrontendOnlyTask() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    requirement(
                        "R1",
                        "A second cancellation is invalid and must leave stock released exactly"
                            + " once.")))
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T8")
                        .title("Build frontend cancellation flow")
                        .description("Render order cancellation controls and errors.")
                        .complexity(PlannedTask.Complexity.MEDIUM)
                        .touchesFiles(List.of("frontend/src/OrderDetail.tsx"))
                        .meetsRequirements(List.of("R1"))
                        .hardConstraints(
                            List.of(
                                "A second cancellation must return the stable invalid-state error;"
                                    + " stock must remain restored exactly once."))
                        .build()))
            .build();

    PlannedTask task = PlanSafetyContractEnricher.enrich(plan).plan().tasks().getFirst();

    assertThat(task.hardConstraints())
        .noneMatch(
            value ->
                value.contains("second cancellation must return")
                    || value.contains("stock must remain restored"));
  }

  @Test
  void authoritativeSpecRestoresPrincipalSyncDatabaseAndSingleStartupContracts() {
    Plan lossyOutline =
        Plan.builder()
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(List.of(requirement("R1", "Build inventory and order APIs.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Scaffold API persistence",
                        "Use create_async_engine, AsyncSession, and sqlite+aiosqlite for the API.",
                        List.of("backend/app/database.py", "backend/app/routes.py")),
                    task(
                        "T2",
                        "Finalize README",
                        "Document separate backend and frontend startup commands. Limitations: no"
                            + " authentication layer.",
                        List.of("README.md"))))
            .build();

    Plan enriched =
        PlanSafetyContractEnricher.enrich(
                lossyOutline,
                "Use SQLAlchemy 2 synchronous sessions. Use an authenticated-principal"
                    + " abstraction and never accept caller-selected identity. Provide one"
                    + " documented command for development startup plus exact setup, migration,"
                    + " and start commands.")
            .plan();

    PlannedTask persistence = enriched.tasks().getFirst();
    PlannedTask docs = enriched.tasks().get(1);
    assertThat(persistence.description())
        .contains("create_engine", "Session", "sqlite+pysqlite")
        .doesNotContain("create_async_engine", "AsyncSession", "aiosqlite");
    assertThat(persistence.hardConstraints())
        .anyMatch(value -> value.contains("verified credential"))
        .anyMatch(value -> value.contains("one synchronous SQLAlchemy 2 database contract"));
    assertThat(docs.touchesFiles()).contains("dev.sh");
    assertThat(docs.description())
        .contains("one root `./dev.sh` command")
        .doesNotContain("no authentication layer");
    assertThat(docs.hardConstraints())
        .anyMatch(value -> value.contains("single root `./dev.sh` command"));
  }

  @Test
  void compilesOneExecutableContractFromContradictoryExpandedTasks() {
    Plan plan =
        Plan.builder()
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(
                List.of(
                    requirement("R1", "Use SQLAlchemy 2, Alembic, and SQLite."),
                    requirement("R2", "Model reservation lines and reservation lifecycle."),
                    requirement("R3", "Release/cancel a reservation exactly once."),
                    requirement("R4", "Provide API endpoints for adjustment and reservation."),
                    requirement("R5", "Provide an independent black-box acceptance suite."),
                    requirement("R6", "Prove persistence across restart.")))
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("Scaffold async database")
                        .description(
                            "Create an async engine, AsyncSession, async_sessionmaker, and async"
                                + " get_db with sqlite+aiosqlite.")
                        .touchesFiles(List.of("backend/app/database.py"))
                        .hardConstraints(
                            List.of(
                                "The engine must be async (aiosqlite driver).",
                                "Export AsyncSession and async get_db."))
                        .meetsRequirements(List.of("R1"))
                        .build(),
                    PlannedTask.builder()
                        .id("T4")
                        .title("Order and reservation models")
                        .description(
                            "Reservation is one row per order line with order_line_id unique and"
                                + " status ACTIVE. Check status IN ('PENDING','RELEASED').")
                        .touchesFiles(
                            List.of(
                                "backend/app/models/order.py",
                                "backend/app/models/reservation.py",
                                "backend/alembic/versions/003_orders.py"))
                        .hardConstraints(
                            List.of(
                                "Reservation is a single persisted record per order line; there is"
                                    + " no ReservationLine child table."))
                        .meetsRequirements(List.of("R2", "R3"))
                        .build(),
                    PlannedTask.builder()
                        .id("T5")
                        .title("Implement API routes")
                        .description(
                            "POST/api/stock/adjust and POST/api/orders/{order_id}/reserve are the"
                                + " public routes.")
                        .touchesFiles(List.of("backend/app/api/routes.py"))
                        .hardConstraints(List.of())
                        .meetsRequirements(List.of("R4"))
                        .build(),
                    PlannedTask.builder()
                        .id("T6")
                        .title("Seed database")
                        .description("Use db: Session from the synchronous sessionmaker factory.")
                        .touchesFiles(List.of("backend/app/seed.py"))
                        .hardConstraints(List.of())
                        .meetsRequirements(List.of("R1"))
                        .build(),
                    PlannedTask.builder()
                        .id("T12")
                        .title("Playwright black-box acceptance suite")
                        .description(
                            "Playwright webServer starts the backend. POST /api/adjustments, then"
                                + " stop and restart that backend and POST /api/reservations.")
                        .touchesFiles(List.of("e2e/playwright.config.ts", "README.md"))
                        .hardConstraints(List.of())
                        .meetsRequirements(List.of("R4", "R5", "R6"))
                        .build()))
            .build();

    Plan compiled =
        TaskExecutionBriefCompiler.compile(PlanSafetyContractEnricher.enrich(plan).plan());
    PlannedTask database = compiled.tasks().get(0);
    PlannedTask reservation = compiled.tasks().get(1);
    PlannedTask api = compiled.tasks().get(2);
    PlannedTask acceptance = compiled.tasks().get(4);

    assertThat(database.hardConstraints())
        .anyMatch(value -> value.contains("one synchronous SQLAlchemy 2 database contract"))
        .noneMatch(value -> value.toLowerCase().contains("async"));
    assertThat(reservation.hardConstraints())
        .anyMatch(value -> value.contains("one-to-many collection of ReservationLine"))
        .noneMatch(value -> value.contains("single persisted record per order line"));
    assertThat(api.hardConstraints())
        .anyMatch(value -> value.contains("/api/stock/adjust"))
        .anyMatch(value -> value.contains("/api/orders/{order_id}/reserve"));
    assertThat(acceptance.hardConstraints())
        .anyMatch(value -> value.contains("/api/stock/adjust"))
        .anyMatch(value -> value.contains("/api/orders/{order_id}/reserve"))
        .anyMatch(value -> value.contains("separate black-box process-control test/harness"));
  }

  @Test
  void browserE2eJourneysCannotBeReplacedByDirectApiSetup() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    requirement(
                        "R1",
                        "End-to-end tests launch the real backend and frontend and exercise the"
                            + " complete customer workflow.")))
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T9")
                        .title("Playwright end-to-end customer journeys")
                        .description("Create data with POST requests, then open the dashboard.")
                        .touchesFiles(
                            List.of("e2e/playwright.config.ts", "e2e/full-journey.spec.ts"))
                        .hardConstraints(List.of())
                        .meetsRequirements(List.of("R1"))
                        .build()))
            .build();

    PlannedTask e2e = PlanSafetyContractEnricher.enrich(plan).plan().tasks().getFirst();

    assertThat(e2e.description())
        .contains("BROWSER JOURNEY CONTRACT")
        .contains("do not seed business data");
    assertThat(e2e.hardConstraints())
        .anyMatch(
            value ->
                value.contains("drive every required customer journey")
                    && value.contains("must not seed business data"));
  }

  @Test
  void frontendScaffoldAndRootOrchestrationOwnExecutableTestScriptsExactlyOnce() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    requirement("R1", "Frontend tests use Vitest and browser E2E uses Playwright."),
                    requirement(
                        "R2",
                        "Root build/test orchestration compiles the backend and frontend through"
                            + " root ./build.sh and runs black-box acceptance."),
                    requirement(
                        "R3", "Include exact setup/migration/start commands in README.md.")))
            .tasks(
                List.of(
                    task(
                        "T9",
                        "Frontend scaffold",
                        "Create package.json with dev and build scripts.",
                        List.of("frontend/package.json", "frontend/src/App.tsx")),
                    task(
                        "T13",
                        "Black-box acceptance",
                        "Run real HTTP acceptance against the documented server.",
                        List.of("backend/tests/test_acceptance.py")),
                    task(
                        "T15",
                        "Root full-stack build/test orchestration",
                        "Create README.md, build.sh, and run_tests.sh.",
                        List.of("README.md", "build.sh", "run_tests.sh"))))
            .build();

    Plan enriched = PlanSafetyContractEnricher.enrich(plan).plan();
    PlannedTask frontend = enriched.tasks().getFirst();
    PlannedTask root = enriched.tasks().get(2);

    assertThat(frontend.description())
        .contains("`test` (Vitest)", "`test:e2e` (Playwright")
        .contains("matching devDependencies");
    assertThat(root.description())
        .contains("exact dependency setup", "Alembic migration", "browser E2E")
        .contains("must not run a black-box acceptance module")
        .contains("declared package.json scripts");
    assertThat(root.hardConstraints())
        .anyMatch(value -> value.contains("required browser E2E suites exactly once"))
        .anyMatch(value -> value.contains("RUN TESTS ONCE"));
  }

  @Test
  void derivesCanonicalAuditOperationsAndOneEventTransferFromRequirements() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    requirement(
                        "R1",
                        "Every stock adjustment, transfer, reserve, cancel, and commit writes"
                            + " immutable audit history. A transfer writes one audit event.")))
            .tasks(
                List.of(
                    task(
                        "T3",
                        "Implement stock audit model and service",
                        "AuditOperation values are ADJUSTMENT, TRANSFER_OUT, TRANSFER_IN, RESERVE,"
                            + " COMMIT, CANCELLED.",
                        List.of("backend/app/models/audit.py"))))
            .build();

    PlannedTask audit = PlanSafetyContractEnricher.enrich(plan).plan().tasks().getFirst();

    assertThat(audit.description())
        .contains("ADJUSTMENT, TRANSFER, RESERVE, CANCEL, COMMIT")
        .doesNotContain("TRANSFER_OUT", "TRANSFER_IN", "CANCELLED");
    assertThat(audit.hardConstraints())
        .anyMatch(
            value ->
                value.contains(
                        "operation values are exactly ADJUSTMENT, TRANSFER, RESERVE, CANCEL,"
                            + " COMMIT")
                    && value.contains("exactly one TRANSFER audit event")
                    && value.contains("TRANSFER_OUT/TRANSFER_IN pairs are forbidden"));
  }

  @Test
  void bindsDownstreamSessionAlternativeToExplicitAsyncScaffold() {
    Plan plan =
        Plan.builder()
            .requirements(List.of(requirement("R1", "Use SQLAlchemy 2 with SQLite.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Scaffold async database",
                        "Create database.py with create_async_engine, async_sessionmaker, and"
                            + " AsyncSession.",
                        List.of("backend/app/database.py")),
                    task(
                        "T4",
                        "Implement order service",
                        "OrderService depends on AsyncSession (or sync Session matching T1).",
                        List.of("backend/app/services/orders.py"))))
            .build();

    Plan enriched = PlanSafetyContractEnricher.enrich(plan).plan();

    assertThat(enriched.tasks().get(1).description())
        .contains("depends on AsyncSession")
        .doesNotContain("or sync Session");
  }

  @Test
  void doesNotInventAConsolidatedOwnerWhenNoExplicitFullSchemaOwnerExists() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    requirement(
                        "R1", "Alembic migration must target the exact application database.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Scaffold backend and initial migration",
                        "Configure Alembic and create every future domain table.",
                        List.of(
                            "backend/alembic/env.py",
                            "backend/alembic/versions/001_initial_schema.py")),
                    task(
                        "T2",
                        "Implement Warehouse and Product models",
                        "Define persisted Warehouse and Product models.",
                        List.of("backend/app/models/catalog.py")),
                    task(
                        "T3",
                        "Implement Inventory model",
                        "Define the persisted Inventory model.",
                        List.of("backend/app/models/inventory.py")),
                    task(
                        "T4",
                        "Implement Audit model",
                        "Define the final persisted Audit model.",
                        List.of("backend/app/models/audit.py"))))
            .build();

    Plan enriched = PlanSafetyContractEnricher.enrich(plan).plan();

    assertThat(enriched.tasks().getFirst().touchesFiles())
        .containsExactly(
            "backend/alembic/env.py", "backend/alembic/versions/001_initial_schema.py");
    assertThat(enriched.tasks())
        .allMatch(
            task ->
                !task.description().contains("schema-finalization task")
                    && task.hardConstraints().stream()
                        .noneMatch(
                            value ->
                                value.contains("after all prerequisite domain model contracts")));

    Plan enrichedTwice = PlanSafetyContractEnricher.enrich(enriched).plan();
    assertThat(enrichedTwice).usingRecursiveComparison().isEqualTo(enriched);
  }

  @Test
  void orderLifecycleNormalizationDoesNotUppercaseReservedInventoryColumn() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    requirement(
                        "R1",
                        "Orders use DRAFT, RESERVED, COMMITTED, and CANCELLED lifecycle states.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Implement order model",
                        "Order.status values are DRAFT, RESERVED, COMMITTED, CANCELLED.",
                        List.of("backend/app/models/order.py")),
                    task(
                        "T2",
                        "Create database schema",
                        "Create inventory(id, on_hand, reserved, created_at) and orders(id, status,"
                            + " created_at).",
                        List.of("backend/alembic/versions/001_schema.py"))))
            .build();

    String migrationDescription =
        PlanSafetyContractEnricher.enrich(plan).plan().tasks().get(1).description();

    assertThat(migrationDescription)
        .contains("on_hand, reserved, created_at")
        .doesNotContain("on_hand, RESERVED, created_at");
  }

  @Test
  void orderSchemaProducerNormalizesPersistedStateFieldToStatus() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    requirement(
                        "R1",
                        "Orders use DRAFT, RESERVED, COMMITTED, and CANCELLED status values.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Implement Order model",
                        "Add Order model: id, state default DRAFT, created_at. States are DRAFT,"
                            + " RESERVED, COMMITTED, CANCELLED.",
                        List.of("backend/app/models/order.py"))))
            .build();

    String description =
        PlanSafetyContractEnricher.enrich(plan).plan().tasks().getFirst().description();

    assertThat(description)
        .contains("Order model: id, status default DRAFT")
        .doesNotContain("state default");
  }

  @Test
  void explicitFullSchemaMigrationOwnerIsStableAndContradictionFree() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    requirement(
                        "R1",
                        "Persist catalog and orders using Alembic migrations with a reversible"
                            + " downgrade.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Scaffold database setup",
                        "Configure Alembic and generate the initial Alembic migration in"
                            + " `backend/alembic/versions/001_initial_schema.py`.",
                        List.of(
                            "backend/alembic/env.py",
                            "backend/alembic/versions/001_initial_schema.py")),
                    task(
                        "T2",
                        "Define all models and initial migration",
                        "Define all SQLAlchemy domain models and the complete schema."
                            + " **Migration:** Generate a reversible Alembic migration at"
                            + " `backend/alembic/versions/001_initial_schema.py` that creates all"
                            + " required tables.",
                        List.of(
                            "backend/app/models.py",
                            "backend/alembic/versions/001_initial_schema.py")),
                    task(
                        "T3",
                        "Implement order lifecycle",
                        "Use the T2 models. **Migration:** Generate a reversible Alembic migration"
                            + " at `backend/alembic/versions/002_add_orders.py`. **Services:** Add"
                            + " the order service.",
                        List.of(
                            "backend/app/services/orders.py",
                            "backend/alembic/versions/002_add_orders.py"))))
            .build();

    Plan enriched =
        TaskExpanderAgent.normalizeFinalPlan(PlanSafetyContractEnricher.enrich(plan).plan());

    assertThat(enriched.tasks().get(0).description())
        .contains("must not create, generate, or own a versioned migration")
        .contains("task T2")
        .doesNotContain("schema-finalization");
    assertThat(enriched.tasks().get(1).touchesFiles())
        .contains("backend/alembic/versions/001_initial_schema.py");
    assertThat(enriched.tasks().get(1).description())
        .contains("single consolidated migration")
        .contains("backend/alembic/versions/001_initial_schema.py")
        .doesNotContain("schema-finalization");
    assertThat(enriched.tasks().get(2).touchesFiles())
        .doesNotContain("backend/alembic/versions/002_add_orders.py");
    assertThat(enriched.tasks().get(2).description())
        .contains("task T2")
        .doesNotContain(
            "schema-finalization", "Generate a reversible Alembic migration", "002_add_orders.py");
    assertThat(enriched.tasks().get(2).hardConstraints())
        .noneMatch(
            value ->
                value.contains("002_add_orders.py")
                    || value.toLowerCase().contains("forward-only"));

    Plan normalizedAgain = TaskExpanderAgent.normalizeFinalPlan(enriched);
    assertThat(normalizedAgain).usingRecursiveComparison().isEqualTo(enriched);
    for (PlannedTask task : enriched.tasks()) {
      String combined =
          task.description()
              + "\n"
              + String.join("\n", task.hardConstraints())
              + "\n"
              + String.join("\n", task.touchesFiles());
      assertThat(combined)
          .doesNotContain(
              "schema-finalization task",
              "modules (exported",
              "must not create a versioned migration\nGenerate a reversible");
    }
  }

  @Test
  void flywayMigrationOwnershipIsNotRewrittenByAlembicPolicy() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(requirement("R1", "Use Flyway migrations for the complete schema.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Scaffold Spring application",
                        "Configure Flyway.",
                        List.of("backend/src/main/resources/db/migration/V001__initial.sql")),
                    task(
                        "T2",
                        "Define all database models and initial migration",
                        "Define all database models and complete schema in the initial migration.",
                        List.of(
                            "backend/src/main/java/example/Order.java",
                            "backend/src/main/resources/db/migration/V002__orders.sql"))))
            .build();

    Plan enriched = PlanSafetyContractEnricher.enrich(plan).plan();

    assertThat(enriched.tasks())
        .extracting(PlannedTask::touchesFiles)
        .containsExactly(plan.tasks().get(0).touchesFiles(), plan.tasks().get(1).touchesFiles());
    assertThat(enriched.tasks())
        .extracting(PlannedTask::description)
        .containsExactly(plan.tasks().get(0).description(), plan.tasks().get(1).description());
    assertThat(enriched.tasks())
        .allMatch(
            task ->
                task.description().contains("schema-finalization") == false
                    && task.hardConstraints().stream()
                        .noneMatch(value -> value.contains("consolidated migration")));
  }

  @Test
  void frontendAuditDisplayDoesNotBecomeIdempotencyTransactionOwner() {
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    requirement(
                        "R1",
                        "Reservation requests use Idempotency-Key and exact response replay.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Implement reservation endpoint",
                        "POST /api/orders/{id}/reserve requires Idempotency-Key and persists an"
                            + " IdempotencyRecord.",
                        List.of(
                            "backend/app/routers/orders.py",
                            "backend/app/services/order_service.py")),
                    task(
                        "T2",
                        "Build audit history screen",
                        "Render the persisted idempotency_key reference in GET /api/audit rows.",
                        List.of("frontend/src/pages/AuditHistory.tsx"))))
            .build();

    String frontend = PlanSafetyContractEnricher.enrich(plan).plan().tasks().get(1).description();

    assertThat(frontend)
        .doesNotContain(
            "SQLITE IDEMPOTENCY ALGORITHM",
            "IDEMPOTENCY TRANSACTION",
            "BEGIN IMMEDIATE",
            "business mutation");
  }

  private static boolean positivelyRequiresSelectForUpdate(String value) {
    String normalized = value.toLowerCase();
    return normalized.contains("select for update")
        && !normalized.contains("does not provide effective");
  }

  private static RequirementSpec requirement(String id, String text) {
    return new RequirementSpec(id, text, RequirementSpec.RequirementKind.FUNCTIONAL);
  }

  private static PlannedTask task(
      String id, String title, String description, List<String> touchesFiles) {
    return PlannedTask.builder()
        .id(id)
        .title(title)
        .description(description)
        .touchesFiles(touchesFiles)
        .hardConstraints(List.of())
        .build();
  }

  private static List<String> constraints(PlanSafetyContractEnricher.Result result, String taskId) {
    return result.plan().tasks().stream()
        .filter(task -> task.id().equals(taskId))
        .findFirst()
        .orElseThrow()
        .hardConstraints();
  }
}
