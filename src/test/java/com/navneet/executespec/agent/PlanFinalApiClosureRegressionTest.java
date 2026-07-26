package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Regression coverage for API drift restored at the final normalization boundary. */
class PlanFinalApiClosureRegressionTest {

  @Test
  void clippedPartialSchemaStillFailsClosedAgainstRicherSharedContract() {
    PlannedTask partialOwner =
        task(
            "T2",
            "Define location persistence",
            "`Location`: id, code, e, product, inventory, allocation, transfer.",
            "server/models/location.ts");
    PlannedTask richerConsumer =
        task(
            "T9",
            "Use location response",
            "The Location fields: id, code, name, created_at, product, inventory, allocation,"
                + " transfer.",
            "server/api/location-response.ts");

    List<String> violations =
        PlanContractQualityGate.violations(
            Plan.builder()
                .requirements(List.of())
                .tasks(List.of(partialOwner, richerConsumer))
                .build());

    assertThat(violations)
        .anySatisfy(
            value ->
                assertThat(value)
                    .contains(
                        "shared location field contract differs",
                        "first-only=[e]",
                        "name",
                        "created_at"));
  }

  @Test
  void ambiguousCancelReleaseOwnersRemainFailClosed() {
    PlannedTask firstOwner =
        task(
            "T5",
            "Implement lease commands",
            "Expose POST /api/leases/{id}/cancel.",
            "server/routes/leases.ts");
    PlannedTask secondOwner =
        task(
            "T6",
            "Implement lease administration",
            "Also expose POST /api/leases/{id}/cancel.",
            "server/admin/leases.ts");
    PlannedTask consumer =
        task(
            "T10",
            "Build lease client",
            "Call POST /api/leases/{id}/release.",
            "frontend/src/api/leases.ts");

    Plan normalized =
        TaskExpansionPlanNormalizer.normalize(
            Plan.builder()
                .requirements(List.of())
                .tasks(List.of(firstOwner, secondOwner, consumer))
                .build());

    assertThat(normalized.tasks().get(2).description()).contains("/release");
    assertThat(PlanContractQualityGate.violations(normalized))
        .anyMatch(
            value ->
                value.contains("POST /api/leases/{id}/release")
                    && value.contains("no backend task declares that operation"));
  }

  @Test
  void closesLateActionAndQueryDriftAgainstUniqueBackendOwners() {
    PlannedTask jobProducer =
        task(
            "T4",
            "Implement job routes",
            "Expose GET /api/jobs with ?site_id=<uuid> and GET /api/jobs/{job_id}.",
            "server/routes/jobs.ts");
    PlannedTask leaseProducer =
        task(
            "T7",
            "Implement lease lifecycle routes",
            "Expose POST /api/leases/{id}/cancel and restore the leased capacity exactly once.",
            "server/routes/leases.ts");
    PlannedTask client =
        taskWithConstraints(
            "T11",
            "Build typed client",
            "Load GET /api/jobs?owner_id=<uuid>. One binding says POST"
                + " /api/leases/{id}/cancel; a later stale binding says POST"
                + " /api/leases/{id}/release.",
            "frontend/src/api/client.ts",
            List.of(
                "Use these exact public API paths: POST /api/leases/{id}/cancel.",
                "Use these exact public API paths: POST /api/leases/{id}/release."));
    PlannedTask journey =
        taskWithConstraints(
            "T14",
            "Build lease journey",
            "Filter with GET /api/jobs?owner_id=<uuid>, then submit POST"
                + " /api/leases/{id}/release even though an earlier binding says POST"
                + " /api/leases/{id}/cancel.",
            "frontend/src/pages/Leases.tsx",
            List.of(
                "Use these exact public API paths: POST /api/leases/{id}/cancel.",
                "Use these exact public API paths: POST /api/leases/{id}/release."));

    Plan normalized =
        TaskExpansionPlanNormalizer.normalize(
            Plan.builder()
                .requirements(List.of())
                .tasks(List.of(jobProducer, leaseProducer, client, journey))
                .build());
    Plan repeated = TaskExpansionPlanNormalizer.normalize(normalized);

    PlannedTask normalizedJobProducer = normalized.tasks().get(0);
    PlannedTask normalizedLeaseProducer = normalized.tasks().get(1);
    PlannedTask normalizedClient = normalized.tasks().get(2);
    PlannedTask normalizedJourney = normalized.tasks().get(3);

    assertThat(normalizedLeaseProducer.description())
        .contains("POST /api/leases/{id}/cancel")
        .doesNotContain("POST /api/leases/{id}/release");
    assertThat(normalizedClient.description())
        .contains("POST /api/leases/{id}/cancel", "GET /api/jobs")
        .doesNotContain("/release", "owner_id");
    assertThat(normalizedClient.hardConstraints())
        .allMatch(value -> !value.contains("/release"))
        .anyMatch(
            value ->
                value.contains("T7 is authoritative for POST /api/leases/{id}/cancel")
                    && value.contains("query parameters []"))
        .anyMatch(
            value ->
                value.contains("T4 is authoritative for GET /api/jobs")
                    && value.contains("query parameters [site_id]"));
    assertThat(normalizedJourney.description())
        .contains("POST /api/leases/{id}/cancel", "GET /api/jobs")
        .doesNotContain("/release", "owner_id");
    assertThat(normalizedJourney.hardConstraints())
        .allMatch(value -> !value.contains("/release"))
        .anyMatch(value -> value.contains("T7 is authoritative for POST /api/leases/{id}/cancel"))
        .anyMatch(
            value ->
                value.contains("T4 is authoritative for GET /api/jobs")
                    && value.contains("query parameters [site_id]"));
    assertThat(normalizedJobProducer.description()).contains("GET /api/jobs", "?site_id=<uuid>");
    assertThat(TaskExpanderAgent.crossTaskContractViolations(normalized)).isEmpty();
    assertThat(repeated).isEqualTo(normalized);
  }

  @Test
  void closesMissingItemReadAgainstUniqueCollectionRouteOwner() {
    PlannedTask producer =
        task(
            "T3",
            "Implement shipment routes",
            "Expose POST /api/shipments.",
            "server/routes/shipments.ts");
    PlannedTask consumer =
        task(
            "T8",
            "Build shipment details",
            "Load GET /api/shipments/{shipment_id}.",
            "frontend/src/pages/ShipmentDetails.tsx");

    Plan normalized = TaskExpansionPlanNormalizer.normalize(plan(producer, consumer));

    assertThat(normalized.tasks().getFirst().description())
        .contains("API PRODUCER CONTRACTS")
        .contains("GET /api/shipments/{id}");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void closesMissingMutationAgainstUniqueRouteFamilyOwner() {
    PlannedTask producer =
        taskWithConstraints(
            "T4",
            "Implement deployment routes",
            "Expose GET /api/deployments.",
            "server/controllers/deployments.go",
            List.of(
                "The deployment controller owns the exact public path"
                    + " /api/deployments/{deployment_id}/activate."));
    PlannedTask consumer =
        task(
            "T9",
            "Build deployment controls",
            "Trigger POST /api/deployments/{deployment_id}/activate.",
            "frontend/src/deployments/client.ts");

    Plan initial =
        PlanApiProducerContractReconciler.reconcileMissingReadOperations(plan(producer, consumer));
    assertThat(initial.tasks().getFirst().description())
        .contains("API PRODUCER CONTRACTS")
        .contains("POST /api/deployments/{id}/activate");

    Plan normalized = TaskExpansionPlanNormalizer.normalize(initial);

    assertThat(normalized.tasks().getFirst().description())
        .contains("API PRODUCER CONTRACTS")
        .contains("POST /api/deployments/{id}/activate");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void concreteAggregateFamilyOwnerWinsWithoutMutatingNestedReadOwner() {
    PlannedTask nestedReadOwner =
        task(
            "T2",
            "Implement account event reads",
            "Expose GET /api/accounts/{account_id}/events.",
            "server/routes/account-events.ts");
    PlannedTask aggregateFamilyOwner =
        task(
            "T3",
            "Implement event commands",
            "Expose POST /api/events.",
            "server/routes/events.ts");
    PlannedTask consumer =
        task("T7", "Build event browser", "Load GET /api/events.", "frontend/src/events/client.ts");

    Plan normalized =
        TaskExpansionPlanNormalizer.normalize(
            plan(nestedReadOwner, aggregateFamilyOwner, consumer));

    assertThat(normalized.tasks().get(0).description())
        .contains("GET /api/accounts/{account_id}/events")
        .doesNotContain("API PRODUCER CONTRACTS");
    assertThat(normalized.tasks().get(1).description())
        .contains("API PRODUCER CONTRACTS")
        .contains("GET /api/events");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void nestedReadAloneDoesNotCreateUnrelatedAggregateAlias() {
    PlannedTask nestedReadOwner =
        task(
            "T2",
            "Implement account event reads",
            "Expose GET /api/accounts/{account_id}/events.",
            "server/routes/account-events.ts");
    PlannedTask consumer =
        task("T7", "Build event browser", "Load GET /api/events.", "frontend/src/events/client.ts");

    Plan reconciled =
        PlanApiProducerContractReconciler.reconcileMissingOperations(
            plan(nestedReadOwner, consumer));

    assertThat(reconciled.tasks().getFirst().description())
        .contains("GET /api/accounts/{account_id}/events")
        .doesNotContain("API PRODUCER CONTRACTS", "GET /api/events");
    assertThat(PlanContractQualityGate.violations(reconciled))
        .anyMatch(
            violation ->
                violation.contains("T7: API consumer uses GET /api/events")
                    && violation.contains("producer T2 owns GET /api/accounts/{id}/events"));
  }

  @Test
  void closesMissingCollectionReadAgainstUniqueAllocatedBackendDependency() {
    PlannedTask inventoryProducer =
        task(
            "T3",
            "Implement backend inventory API for warehouses, products, and stock listing",
            "Expose GET /api/warehouses, GET /api/products, and GET /api/stock with filters.",
            "backend/app/api/inventory.py");
    PlannedTask orderProducer =
        task(
            "T5",
            "Implement order commands",
            "Expose POST /api/orders and POST /api/orders/{id}/reserve.",
            "backend/app/api/orders.py");
    PlannedTask consumer =
        PlannedTask.builder()
            .id("T11")
            .title("Build order creation and reservation flows")
            .description("Load GET /api/inventory before creating and reserving an order.")
            .complexity(PlannedTask.Complexity.MEDIUM)
            .touchesFiles(List.of("frontend/src/api/orders.ts"))
            .dependsOn(List.of("T3", "T5"))
            .hardConstraints(List.of())
            .meetsRequirements(List.of())
            .taskType(PlannedTask.TaskType.LEAF)
            .build();

    Plan normalized =
        TaskExpansionPlanNormalizer.normalize(plan(inventoryProducer, orderProducer, consumer));

    assertThat(normalized.tasks().get(0).description())
        .contains("API PRODUCER CONTRACTS")
        .contains("GET /api/inventory");
    assertThat(normalized.tasks().get(1).description()).doesNotContain("GET /api/inventory");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void allocatedNestedOnlyReadStillDoesNotCreateAggregateAlias() {
    PlannedTask nestedReadOwner =
        task(
            "T2",
            "Implement account event reads",
            "Expose GET /api/accounts/{account_id}/events.",
            "server/routes/account-events.ts");
    PlannedTask consumer =
        PlannedTask.builder()
            .id("T7")
            .title("Build event browser")
            .description("Load GET /api/events.")
            .complexity(PlannedTask.Complexity.MEDIUM)
            .touchesFiles(List.of("frontend/src/events/client.ts"))
            .dependsOn(List.of("T2"))
            .hardConstraints(List.of())
            .meetsRequirements(List.of())
            .taskType(PlannedTask.TaskType.LEAF)
            .build();

    Plan normalized =
        PlanApiProducerContractReconciler.reconcileMissingOperations(
            plan(nestedReadOwner, consumer));

    assertThat(normalized.tasks().getFirst().description())
        .doesNotContain("API PRODUCER CONTRACTS", "GET /api/events");
    assertThat(PlanContractQualityGate.violations(normalized))
        .anyMatch(violation -> violation.contains("T7: API consumer uses GET /api/events"));
  }

  @Test
  void canonicalProducerRouteReplacesActionAndParameterAliasesInConsumersAndTests() {
    PlannedTask producer =
        task(
            "T4",
            "Implement reservation lifecycle route",
            "Expose POST /api/reservations/{reservation_id}/release.",
            "backend/app/routes/reservations.py");
    PlannedTask client =
        taskWithConstraints(
            "T7",
            "Build reservation release client",
            "Call POST /api/reservations/release and retry POST"
                + " /api/reservations/{id}/release only as the same operation.",
            "frontend/src/api/reservations.ts",
            List.of("Use POST /api/reservations/{id}/release."));
    PlannedTask tests =
        taskWithConstraints(
            "T8",
            "Reservation release acceptance tests",
            "Verify POST /api/reservations/release and POST" + " /api/reservations/{id}/release.",
            "acceptance/test_reservations.py",
            List.of("Exercise POST /api/reservations/release."));

    Plan normalized = TaskExpansionPlanNormalizer.normalize(plan(producer, client, tests));

    assertThat(normalized.tasks().getFirst().description())
        .contains("POST /api/reservations/{reservation_id}/release");
    for (PlannedTask consumer : normalized.tasks().subList(1, 3)) {
      assertThat(consumer.description())
          .contains("POST /api/reservations/{reservation_id}/release")
          .doesNotContain("POST /api/reservations/release", "/{id}/release");
      assertThat(consumer.hardConstraints())
          .allMatch(value -> !value.contains("POST /api/reservations/release"))
          .allMatch(value -> !value.contains("/{id}/release"));
    }
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void conflictingProducerActionCardinalityFailsClosed() {
    PlannedTask producer =
        task(
            "T4",
            "Implement reservation lifecycle routes",
            "Expose POST /api/reservations/release and POST"
                + " /api/reservations/{reservation_id}/release.",
            "backend/app/routes/reservations.py");

    List<String> violations = PlanContractQualityGate.violations(plan(producer));

    assertThat(violations)
        .anyMatch(
            value ->
                value.contains("API producer route cardinality is ambiguous")
                    && value.contains("POST /api/reservations/release")
                    && value.contains("/api/reservations/{id}/release")
                    && value.contains("choose one authoritative operation path"));
  }

  @Test
  void collectionGetCannotAlsoClaimMissingSingleResourceRoute() {
    PlannedTask producer =
        task(
            "T4",
            "Implement reservation reads",
            "GET /api/reservations lists all reservations. GET /api/reservations returns a"
                + " single-resource reservation detail.",
            "backend/app/routes/reservations.py");

    List<String> violations = PlanContractQualityGate.violations(plan(producer));

    assertThat(violations)
        .anyMatch(
            value ->
                value.contains("GET /api/reservations claims single-resource semantics")
                    && value.contains("has no path parameter")
                    && value.contains("declare a distinct item route"));
  }

  @Test
  void generatedProducerBindingMovesToUniqueConcreteRouteArtifactOwner() {
    String wrongBinding =
        "API PRODUCER CONTRACTS (binding; implement every operation): GET /api/orders, POST"
            + " /api/orders.";
    PlannedTask stockOwner =
        taskWithConstraints(
            "T4",
            "Implement stock adjustment routes",
            "Expose POST /api/adjustments.\n\n" + wrongBinding,
            "backend/app/api/adjustments.py",
            List.of(wrongBinding));
    PlannedTask orderOwner =
        task(
            "T5",
            "Implement order creation",
            "The order router owns the exact public path /api/orders.",
            "backend/app/api/orders.py");
    PlannedTask consumer =
        task("T12", "Build order form", "Submit POST /api/orders.", "frontend/src/api/orders.ts");

    Plan normalized = TaskExpansionPlanNormalizer.normalize(plan(stockOwner, orderOwner, consumer));

    assertThat(normalized.tasks().get(0).description())
        .contains("POST /api/adjustments")
        .doesNotContain("API PRODUCER CONTRACTS", "/api/orders");
    assertThat(normalized.tasks().get(0).hardConstraints()).doesNotContain(wrongBinding);
    assertThat(normalized.tasks().get(1).description())
        .contains("API PRODUCER CONTRACTS", "GET /api/orders", "POST /api/orders");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
    assertThat(TaskExpansionPlanNormalizer.normalize(normalized)).isEqualTo(normalized);
  }

  @Test
  void providerAuthoredOperationOnForeignConcreteRouteArtifactFailsClosed() {
    PlannedTask stockOwner =
        task(
            "T4",
            "Implement stock adjustment routes",
            "Expose POST /api/adjustments and POST /api/orders.",
            "backend/app/api/adjustments.py");
    PlannedTask orderOwner =
        task(
            "T5",
            "Implement order creation",
            "The order router owns /api/orders.",
            "backend/app/api/orders.py");

    assertThat(PlanContractQualityGate.violations(plan(stockOwner, orderOwner)))
        .contains(
            "T4: declares POST /api/orders but concrete route artifact ownership belongs to T5");
  }

  @Test
  void adjacentCollectionAndItemReadsDoNotContaminateEachOthersCardinality() {
    PlannedTask producer =
        task(
            "T5",
            "Implement order routes",
            "Expose GET /api/orders for the order list and GET /api/orders/{id} for one order.",
            "backend/app/routers/orders.py");
    PlannedTask consumer =
        taskWithConstraints(
            "T12",
            "Build frontend order flows",
            "Fetch GET /orders on mount. Show a list/table of orders. Clicking an order row shows"
                + " its lines and reservation details. Then GET /orders/{id} returns a single"
                + " order with lines and GET /reservations/{id} returns reservation detail.",
            "frontend/src/pages/Orders.tsx",
            List.of(
                "API contract binding: T5 is authoritative for GET /orders/{id}; use query"
                    + " parameters []; do not invent aliases or undeclared response fields."));

    Plan normalized = TaskExpansionPlanNormalizer.normalize(plan(producer, consumer));

    assertThat(PlanContractQualityGate.violations(normalized))
        .noneMatch(
            value ->
                value.contains("GET /api/orders claims single-resource semantics")
                    && value.contains("has no path parameter"));
  }

  @Test
  void multiRouterBackendTaskOwnsMissingActionUnderItsConcreteResourceArtifact() {
    PlannedTask producer =
        PlannedTask.builder()
            .id("T5")
            .title("Implement order and reservation APIs")
            .description("Expose POST /api/orders and POST /api/reservations/release.")
            .touchesFiles(
                List.of("backend/app/routers/orders.py", "backend/app/routers/reservations.py"))
            .dependsOn(List.of())
            .hardConstraints(List.of())
            .meetsRequirements(List.of())
            .taskType(PlannedTask.TaskType.LEAF)
            .complexity(PlannedTask.Complexity.HIGH)
            .build();
    PlannedTask consumer =
        task(
            "T12",
            "Build order reservation flow",
            "Submit POST /orders/{id}/reserve from the frontend client.",
            "frontend/src/pages/Orders.tsx");

    Plan normalized = TaskExpansionPlanNormalizer.normalize(plan(producer, consumer));

    assertThat(normalized.tasks().getFirst().description())
        .contains("API PRODUCER CONTRACTS", "POST /api/orders/{id}/reserve");
    assertThat(PlanContractQualityGate.violations(normalized))
        .noneMatch(
            value ->
                value.contains("POST /api/orders/{id}/reserve")
                    && value.contains("no backend task declares that operation"));
    assertThat(TaskExpansionPlanNormalizer.normalize(normalized)).isEqualTo(normalized);
  }

  private static PlannedTask task(String id, String title, String description, String touchedFile) {
    return taskWithConstraints(id, title, description, touchedFile, List.of());
  }

  private static Plan plan(PlannedTask... tasks) {
    return Plan.builder().requirements(List.of()).tasks(List.of(tasks)).build();
  }

  private static PlannedTask taskWithConstraints(
      String id,
      String title,
      String description,
      String touchedFile,
      List<String> hardConstraints) {
    return PlannedTask.builder()
        .id(id)
        .title(title)
        .description(description)
        .complexity(PlannedTask.Complexity.MEDIUM)
        .touchesFiles(List.of(touchedFile))
        .dependsOn(List.of())
        .hardConstraints(hardConstraints)
        .meetsRequirements(List.of())
        .taskType(PlannedTask.TaskType.LEAF)
        .build();
  }
}
