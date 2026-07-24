package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class CoherencePlannerDeterministicSafetyContractTest {

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
        .anyMatch(value -> value.contains("cross-principal owned-resource"))
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
    assertThat(result.contractsApplied()).isEqualTo(4);
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
        .anyMatch(value -> value.contains("launch the documented production server"))
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
        .anyMatch(value -> value.contains("cross-principal owned-resource"))
        .anyMatch(value -> value.contains("reuse the same Idempotency-Key"))
        .anyMatch(value -> value.contains("without collision or response leakage"));
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
