package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PlanContractQualityGateTest {

  @Test
  void rejectsConsumerRouteThatContradictsRelatedProducer() {
    Plan plan =
        plan(
            backend(
                "T1",
                "Implement audit history endpoint",
                "Router prefix `/api/audit`: GET /api/audit with ?operation, ?offset and ?limit."),
            frontend(
                "T2",
                "Build audit history screen",
                "getAuditHistory(params?: { operation?, page?, page_size? }) ->"
                    + " GET/audit-history"));

    assertThat(PlanContractQualityGate.violations(plan))
        .anyMatch(
            value ->
                value.contains("T2")
                    && value.contains("GET /api/audit-history")
                    && value.contains("T1")
                    && value.contains("GET /api/audit"));
  }

  @Test
  void normalizerReconcilesConsumerPathAndQueryNamesFromProducerEvidence() {
    Plan plan =
        plan(
            backend(
                "T1",
                "Implement activity feed endpoint",
                "GET /api/activity with query params ?kind, ?offset and ?limit.\n"
                    + "`ActivityResponse`: id, timestamp, kind, actor_id."),
            frontend(
                "T2",
                "Build activity feed screen",
                "getActivity(params?: { kind?, page?, page_size? }) -> GET/activity-history"));

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan);
    PlannedTask consumer = normalized.tasks().get(1);

    assertThat(consumer.description())
        .contains("GET /api/activity")
        .contains("kind?: string")
        .contains("offset?: number")
        .contains("limit?: number")
        .doesNotContain("page_size");
    assertThat(consumer.hardConstraints())
        .anyMatch(
            value ->
                value.contains("T1 is authoritative")
                    && value.contains("GET /api/activity")
                    && value.contains("response fields")
                    && value.contains("timestamp"));
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void unrelatedConsumerRoutesAreRejectedButNotSilentlyRewritten() {
    Plan plan =
        plan(
            backend("T1", "Implement user profile endpoint", "GET /api/profile."),
            frontend("T2", "Build billing ledger screen", "GET /billing/ledger."));

    assertThat(PlanContractQualityGate.violations(plan))
        .containsExactly(
            "T2: API consumer uses GET /api/billing/ledger but no backend task declares that"
                + " operation");
    assertThat(TaskExpanderAgent.normalizeFinalPlan(plan).tasks().get(1).description())
        .contains("GET /billing/ledger");
  }

  @Test
  void apiBaseAndRelativeConsumerPathsAreEquivalent() {
    Plan plan =
        plan(
            backend("T1", "Implement notification list", "GET /api/notifications with ?offset."),
            frontend(
                "T2",
                "Build notification list",
                "getNotifications(params?: { offset? }) -> GET /notifications"));

    assertThat(PlanContractQualityGate.violations(plan)).isEmpty();
  }

  @Test
  void routerLocalPathsComposeWithTheirNearestDeclaredPrefix() {
    Plan plan =
        plan(
            backend(
                "T2",
                "Implement warehouse and product APIs",
                "Warehouse router prefix `/api/warehouses`: POST /{id}/deactivate.\n"
                    + "Product router prefix `/api/products`: POST /{id}/deactivate."),
            frontend(
                "T11",
                "Build warehouse management screen",
                "deactivateWarehouse(id) -> POST /api/warehouses/{id}/deactivate."));

    assertThat(PlanContractQualityGate.violations(plan)).isEmpty();
    assertThat(TaskExpanderAgent.finalPlanContractViolations(plan)).isEmpty();
  }

  @Test
  void analogousRouterContractExpandsSameEndpointsForSecondDeclaredPrefix() {
    Plan plan =
        plan(
            backend(
                "T1",
                "Implement account and team APIs",
                "Account router prefix `/api/accounts`: POST /, GET /, GET /{id}, PATCH /{id},"
                    + " DELETE /{id}. Team router prefix `/api/teams`: same five endpoints."),
            frontend(
                "T2",
                "Build team management",
                "GET /api/teams, GET /api/teams/{id}, PATCH /api/teams/{id}, DELETE"
                    + " /api/teams/{id}."));

    assertThat(PlanContractQualityGate.violations(plan)).isEmpty();
  }

  @Test
  void derivesRelativeRouterPrefixFromSingleOwnedRouterArtifact() {
    PlannedTask producer =
        PlannedTask.builder()
            .id("T1")
            .title("Implement shipment API routes")
            .description("GET /, GET /{shipment_id}, POST /dispatch.")
            .touchesFiles(List.of("backend/app/routers/shipments.py"))
            .build();
    Plan plan =
        plan(
            producer,
            frontend(
                "T2",
                "Build shipment screen",
                "GET /api/shipments, GET /api/shipments/{id}, POST" + " /api/shipments/dispatch."));

    assertThat(PlanContractQualityGate.violations(plan)).isEmpty();
  }

  @Test
  void derivesRelativeRouterPrefixFromFastApiApiModule() {
    PlannedTask producer =
        PlannedTask.builder()
            .id("T1")
            .title("Implement order API")
            .description("GET/, GET/{order_id}, POST/{order_id}/reserve.")
            .touchesFiles(List.of("backend/app/api/orders.py"))
            .build();
    Plan plan =
        plan(
            producer,
            frontend(
                "T2",
                "Build order screen",
                "GET /api/orders, GET /api/orders/{id}, POST /api/orders/{id}/reserve."));

    assertThat(PlanContractQualityGate.violations(plan)).isEmpty();
  }

  @Test
  void compactEndpointHeadingsScopeRelativeRoutesToTheNearestApiPrefix() {
    PlannedTask producer =
        backend(
            "T6",
            "Implement resource APIs",
            "**Warehouse endpoints** (`/api/warehouses`): - `POST/` create. - `GET/` list. -"
                + " `GET/{warehouse_id}` detail. - `PATCH/{warehouse_id}` update. **Product"
                + " endpoints** (`/api/products`): - `POST/` create. - `GET/` list. -"
                + " `GET/{product_id}` detail. - `PATCH/{product_id}` update. **Inventory"
                + " routes** (`/api/inventory`): - `GET/` list. - `GET/{inventory_id}` detail.");

    List<PlanApiContractEvidence.ApiEvidence> evidence = PlanApiContractEvidence.extract(producer);

    assertThat(evidence)
        .extracting(
            PlanApiContractEvidence.ApiEvidence::method, PlanApiContractEvidence.ApiEvidence::path)
        .contains(
            org.assertj.core.groups.Tuple.tuple("POST", "/warehouses"),
            org.assertj.core.groups.Tuple.tuple("PATCH", "/warehouses/{id}"),
            org.assertj.core.groups.Tuple.tuple("POST", "/products"),
            org.assertj.core.groups.Tuple.tuple("PATCH", "/products/{id}"),
            org.assertj.core.groups.Tuple.tuple("GET", "/inventory"),
            org.assertj.core.groups.Tuple.tuple("GET", "/inventory/{id}"))
        .doesNotContain(
            org.assertj.core.groups.Tuple.tuple("POST", "/api"),
            org.assertj.core.groups.Tuple.tuple("PATCH", "/products/warehouses/{id}"));
  }

  @Test
  void r11CompactMultiRouterSectionsDeclareCanonicalBackendProducers() {
    PlannedTask resources =
        backend(
            "T6",
            "Implement Warehouse, Product, and Inventory REST API endpoints",
            "**Warehouse endpoints** (`/api/warehouses`): - `POST/` create. - `GET/` list. -"
                + " `GET/{warehouse_id}` detail. - `PATCH/{warehouse_id}` update. -"
                + " `DELETE/{warehouse_id}` deactivate. **Product endpoints**"
                + " (`/api/products`): - `POST/` create. - `GET/` list. - `GET/{product_id}`"
                + " detail. - `PATCH/{product_id}` update. - `DELETE/{product_id}` deactivate."
                + " **Inventory endpoints** (`/api/inventory`): - `GET/` list. -"
                + " `GET/{inventory_id}` detail. **Transfer endpoints** (`/api/transfers`): -"
                + " `POST/` transfer.");
    PlannedTask orders =
        backend(
            "T7",
            "Implement Order and Audit REST API endpoints",
            "**Order endpoints** (`/api/orders`): - `POST/` create. - `GET/` list. -"
                + " `GET/{order_id}` detail. - `POST/{order_id}/reserve` reserve. **Audit"
                + " endpoints** (`/api/audit`): - `GET/` list with `?operation=`.");
    PlannedTask consumer =
        frontend(
            "T11",
            "Build management screens",
            "POST /api/warehouses. GET /api/warehouses/{id}. PUT /api/warehouses/{id}."
                + " DELETE /api/warehouses/{id}. POST /api/products. GET /api/products/{id}."
                + " PUT /api/products/{id}. DELETE /api/products/{id}. GET /api/inventory."
                + " POST /api/transfers. POST /api/orders. GET /api/orders/{id}."
                + " POST /api/orders/{id}/reserve. GET /api/audit?operation=RESERVE.");

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan(resources, orders, consumer));

    assertThat(normalized.tasks().get(2).description())
        .contains("PATCH /api/warehouses/{warehouse_id}")
        .contains("PATCH /api/products/{product_id}")
        .contains("GET /api/audit?operation=RESERVE");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void clientAndServerPathParameterNotationsAreEquivalent() {
    Plan plan =
        plan(
            backend("T8", "Implement order transitions", "POST /api/orders/{id}/reserve."),
            frontend(
                "T14", "Build order actions", "reserveOrder(id) -> POST /api/orders/:id/reserve."));

    assertThat(PlanContractQualityGate.violations(plan)).isEmpty();
    assertThat(TaskExpanderAgent.finalPlanContractViolations(plan)).isEmpty();
  }

  @Test
  void descriptiveAndGenericPathParameterNamesAreEquivalent() {
    Plan plan =
        plan(
            backend(
                "T3",
                "Implement warehouse API",
                "Router prefix `/api/warehouses`: POST /{warehouse_id}/deactivate and PATCH"
                    + " /{warehouse_id}."),
            frontend(
                "T11",
                "Build warehouse management",
                "deactivate(id) -> POST /api/warehouses/{id}/deactivate; update(id) -> PATCH"
                    + " /api/warehouses/{id}."));

    assertThat(PlanContractQualityGate.violations(plan)).isEmpty();
    assertThat(TaskExpanderAgent.finalPlanContractViolations(plan)).isEmpty();
  }

  @Test
  void rejectsConsumerOperationWhenNoBackendTaskDeclaresIt() {
    Plan plan =
        plan(
            backend("T1", "Implement audit API", "GET /api/audit."),
            frontend("T2", "Build inventory screen", "Load rows with GET /api/inventory."));

    assertThat(PlanContractQualityGate.violations(plan))
        .containsExactly(
            "T2: API consumer uses GET /api/inventory but no backend task declares that operation");
  }

  @Test
  void normalizerComposesUnownedAggregateReadFromDeclaredCollections() {
    Plan plan =
        plan(
            backend("T1", "Implement accounts", "GET /api/accounts."),
            backend("T2", "Implement events", "GET /api/events."),
            frontend(
                "T3",
                "Build dashboard overview",
                "getDashboardStats() -> GET /api/dashboard/stats and render the counts."));

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan);

    assertThat(normalized.tasks().get(2).description())
        .contains("compose client-side from GET /api/accounts, GET /api/events")
        .doesNotContain("GET /api/dashboard/stats");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void aggregateCompositionDoesNotLeakSyntheticDashboardParamsIntoChildReads() {
    Plan plan =
        plan(
            backend(
                "T1",
                "Implement audit API",
                "GET /api/audit with query params ?operation and ?warehouse_id."),
            backend(
                "T2",
                "Implement inventory API",
                "GET /api/inventory with query params ?warehouse_id, ?product_id, ?skip and"
                    + " ?limit."),
            backend("T3", "Expose API documentation", "GET /api/openapi.json."),
            frontend(
                "T4",
                "Build dashboard overview",
                "getDashboard(params?: { low_stock_threshold?, limit? }) -> GET"
                    + " /api/dashboard/stats and render inventory and audit counts."));

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan);
    PlannedTask dashboard = normalized.tasks().get(3);

    assertThat(dashboard.description())
        .contains("params?: {}")
        .contains("compose client-side from GET /api/audit, GET /api/inventory")
        .doesNotContain("GET /api/openapi.json");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void repairsDifferentMethodOnlyWhenProducerOwnsTheExactPath() {
    Plan plan =
        plan(
            backend("T1", "Implement resource API", "PATCH /api/resources/{resource_id}."),
            frontend(
                "T2", "Build resource editor", "updateResource(id) -> PUT /api/resources/{id}."));

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan);

    assertThat(normalized.tasks().get(1).description())
        .contains("PATCH /api/resources/{resource_id}")
        .doesNotContain("PUT /api/resources/{id}");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void repairsReadIntentEvenWhenTheSameCollectionAlsoHasPostCreate() {
    Plan plan =
        plan(
            backend(
                "T5",
                "Implement order API",
                "GET /api/orders. POST /api/orders. GET /api/orders/{order_id}."),
            frontend(
                "T12",
                "Build order screens",
                "POST /api/orders — list with filters.\n"
                    + "POST /api/orders/{order_id} — single order detail.\n"
                    + "POST /api/orders — create with request body."));

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan);

    assertThat(normalized.tasks().get(1).description())
        .contains("GET /api/orders — list")
        .contains("GET /api/orders/{order_id} — single order detail")
        .contains("POST /api/orders — create with request body");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void repairsActionRouteFromUniqueDeclaredActionSuffix() {
    Plan plan =
        plan(
            backend("T5", "Implement order API", "POST /api/orders/{order_id}/reserve."),
            backend("T6", "Implement audit API", "GET /api/audit."),
            frontend(
                "T12",
                "Build order actions",
                "POST /api/audit?operation=RESERVE — reserve with Idempotency-Key."));

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan);

    assertThat(normalized.tasks().get(2).description())
        .contains("POST /api/orders/{order_id}/reserve")
        .doesNotContain("POST /api/audit");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void auditGetOperationFilterValuesDoNotBecomeActionInvocations() {
    Plan plan =
        plan(
            backend("T5", "Implement order API", "POST /api/orders/{order_id}/reserve."),
            backend(
                "T6",
                "Implement audit API",
                "GET /api/audit?operation=&warehouse_id=&product_id=&order_id=&page=&page_size=."),
            frontend(
                "T13",
                "Build audit history filters",
                "Fetch GET /api/audit?operation=ADJUSTMENT|TRANSFER|RESERVE|CANCEL|COMMIT"
                    + "&warehouse_id=&product_id=&order_id=&page=&page_size=. Include an operation"
                    + " dropdown with all five filter values."));

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan);

    assertThat(normalized.tasks().get(2).description())
        .contains("GET /api/audit")
        .doesNotContain("POST /api/orders/{order_id}/reserve");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void acceptsExplicitFastApiOpenApiRouteWithoutInventedProducerWork() {
    PlannedTask backend =
        task(
            "T1",
            "Scaffold FastAPI application",
            "Create the FastAPI app.",
            List.of("backend/app/main.py"));
    PlannedTask test =
        task(
            "T7",
            "Add backend integration tests",
            "Verify GET /openapi.json returns the generated contract.",
            List.of("backend/tests/test_integration.py"));
    Plan plan =
        Plan.builder()
            .planTitle("FastAPI service")
            .planSummary("FastAPI backend")
            .projectType("python")
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Expose health and OpenAPI endpoints.",
                        RequirementSpec.RequirementKind.FUNCTIONAL)))
            .tasks(List.of(withRequirements(backend, "R1"), test))
            .build();

    assertThat(PlanContractQualityGate.violations(plan)).isEmpty();
  }

  @Test
  void canonicalizesAuthorizedPrefixedFastApiFrameworkRoutes() {
    Plan docs =
        fastApiFrameworkPlan(
            "Expose the interactive API docs endpoint.", "Create the FastAPI app.", "/api/docs");
    Plan openApi =
        fastApiFrameworkPlan(
            "Expose the OpenAPI endpoint.", "Create the FastAPI app.", "/api/openapi.json");

    Plan normalizedDocs = TaskExpanderAgent.normalizeFinalPlan(docs);
    Plan normalizedOpenApi = TaskExpanderAgent.normalizeFinalPlan(openApi);

    assertThat(normalizedDocs.tasks().get(1).description())
        .contains("GET /docs")
        .doesNotContain("GET /api/docs");
    assertThat(normalizedOpenApi.tasks().get(1).description())
        .contains("GET /openapi.json")
        .doesNotContain("GET /api/openapi.json");
    assertThat(PlanContractQualityGate.violations(normalizedDocs)).isEmpty();
    assertThat(PlanContractQualityGate.violations(normalizedOpenApi)).isEmpty();
  }

  @Test
  void doesNotCanonicalizePrefixedFrameworkRouteWithoutFastApiAuthority() {
    Plan plan =
        frameworkPlan(
            "Expose the interactive API docs endpoint.",
            "Create the application.",
            "/api/docs",
            "Python service");

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan);

    assertThat(normalized.tasks().get(1).description()).contains("GET /api/docs");
    assertThat(PlanContractQualityGate.violations(normalized))
        .anyMatch(value -> value.contains("GET /api/docs"));
  }

  @Test
  void doesNotCanonicalizePrefixedRouteWhenFastApiDocsUrlIsCustomized() {
    Plan plan =
        fastApiFrameworkPlan(
            "Expose the interactive API docs endpoint.",
            "Create FastAPI(docs_url=\"/reference\").",
            "/api/docs");

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan);

    assertThat(normalized.tasks().get(1).description()).contains("GET /api/docs");
    assertThat(PlanContractQualityGate.violations(normalized))
        .anyMatch(value -> value.contains("GET /api/docs"));
  }

  @Test
  void rejectsNegatedFastApiOpenApiRouteAuthority() {
    Plan plan =
        fastApiFrameworkPlan(
            "Do not expose OpenAPI or documentation endpoints.",
            "Create the FastAPI app with documentation disabled.",
            "/openapi.json");

    assertThat(PlanContractQualityGate.violations(plan))
        .anyMatch(
            value ->
                value.contains("GET /api/openapi.json")
                    && value.contains("no backend task declares"));
  }

  @Test
  void openApiAuthorityDoesNotAuthorizeFastApiDocsRoute() {
    Plan plan =
        fastApiFrameworkPlan(
            "Expose the OpenAPI endpoint for machine-readable schema discovery.",
            "Create the FastAPI app.",
            "/docs");

    assertThat(PlanContractQualityGate.violations(plan))
        .anyMatch(
            value -> value.contains("GET /api/docs") && value.contains("no backend task declares"));
  }

  @Test
  void rejectsDisabledOrCustomizedFastApiDefaultRoutes() {
    Plan disabled =
        fastApiFrameworkPlan(
            "Expose the OpenAPI endpoint.", "Create FastAPI(openapi_url=None).", "/openapi.json");
    Plan customized =
        fastApiFrameworkPlan(
            "Expose the interactive API docs endpoint.",
            "Create FastAPI(docs_url=\"/reference\").",
            "/docs");

    assertThat(PlanContractQualityGate.violations(disabled))
        .anyMatch(value -> value.contains("GET /api/openapi.json"));
    assertThat(PlanContractQualityGate.violations(customized))
        .anyMatch(value -> value.contains("GET /api/docs"));
  }

  @Test
  void openApiFileRequirementDoesNotAuthorizeRuntimeRoute() {
    Plan plan =
        fastApiFrameworkPlan(
            "Generate an openapi.yaml file for offline client generation.",
            "Create the FastAPI app.",
            "/openapi.json");

    assertThat(PlanContractQualityGate.violations(plan))
        .anyMatch(
            value ->
                value.contains("GET /api/openapi.json")
                    && value.contains("no backend task declares"));
  }

  @Test
  void reconcilesCrudActionsInDescriptionsAndHardConstraintsFromProducerContracts() {
    PlannedTask consumer =
        PlannedTask.builder()
            .id("T11")
            .title("Build resource management screen")
            .description(
                "Edit mode: Save via GET /api/resources/{id}. Deactivate via POST"
                    + " /api/resources/{id}/deactivate.")
            .touchesFiles(List.of("frontend/src/pages/Resources.tsx"))
            .hardConstraints(
                List.of(
                    "Resource CRUD uses GET/POST/PUT /api/resources and POST"
                        + " /api/resources/{id}/deactivate."))
            .build();
    Plan plan =
        plan(
            backend(
                "T1",
                "Implement resource API",
                "POST /api/resources. GET /api/resources. GET /api/resources/{resource_id}."
                    + " PATCH /api/resources/{resource_id}. DELETE"
                    + " /api/resources/{resource_id}."),
            consumer);

    Plan firstPass = TaskExpanderAgent.normalizeFinalPlan(plan);
    PlannedTask normalized = TaskExpanderAgent.normalizeFinalPlan(firstPass).tasks().get(1);

    assertThat(normalized.description())
        .contains("PATCH /api/resources/{resource_id}")
        .contains("DELETE /api/resources/{resource_id}")
        .doesNotContain("Save via GET", "/deactivate");
    assertThat(normalized.hardConstraints())
        .anyMatch(
            value ->
                value.contains("PATCH /api/resources/{resource_id}")
                    && value.contains("DELETE /api/resources/{resource_id}"));
    assertThat(PlanContractQualityGate.violations(plan(plan.tasks().getFirst(), normalized)))
        .isEmpty();
  }

  @Test
  void responseBindingsDoNotLeakFieldsAcrossResourcesInOneProducerTask() {
    Plan plan =
        plan(
            backend(
                "T1",
                "Implement warehouse and product APIs",
                "GET /api/warehouses/{id}.\n"
                    + "`WarehouseResponse`: id, code, address.\n"
                    + "GET /api/products/{id}.\n"
                    + "`ProductResponse`: id, sku, description."),
            frontend(
                "T2", "Build product management", "Load product with GET /api/products/{id}."));

    Plan firstPass = TaskExpanderAgent.normalizeFinalPlan(plan);
    PlannedTask normalized = TaskExpanderAgent.normalizeFinalPlan(firstPass).tasks().get(1);

    assertThat(normalized.hardConstraints())
        .anyMatch(
            value ->
                value.contains("GET /api/products/{id}")
                    && value.contains("sku")
                    && value.contains("description")
                    && !value.contains("address")
                    && !value.contains("code"));
  }

  @Test
  void slashSeparatedMethodListDoesNotBecomeAnApiPath() {
    Plan plan =
        plan(
            backend("T1", "Implement resource API", "PATCH /api/resources/{id}."),
            frontend(
                "T2",
                "Build resource editor",
                "Use POST/PUT /api/resources/{id} for the edit form."));

    assertThat(PlanContractQualityGate.violations(plan))
        .noneMatch(value -> value.contains("/api/put"));
  }

  @Test
  void rejectsUnresolvedStackChoiceBeforeWorkerExecution() {
    Plan plan =
        plan(
            backend(
                "T1",
                "Scaffold persistence",
                "Use the async driver or the sync driver — pick one and stay consistent."));

    assertThat(PlanContractQualityGate.violations(plan))
        .containsExactly(
            "T1: executable task leaves an implementation choice unresolved; select one concrete"
                + " stack or strategy before worker execution");
  }

  @Test
  void acceptsAnAlternativeThatIsImmediatelyResolvedToOneConcreteStack() {
    Plan plan =
        plan(
            backend(
                "T1",
                "Scaffold browser tests",
                "Use Node Playwright or Python Playwright. Choose one consistent stack. Given the"
                    + " existing Python test harness, use pytest-playwright for this task."));

    assertThat(PlanContractQualityGate.violations(plan))
        .noneMatch(value -> value.contains("implementation choice unresolved"));
  }

  @Test
  void businessFlowSelectingOneRecordIsNotAnUnresolvedImplementationChoice() {
    Plan plan =
        plan(
            frontend(
                "T14",
                "Exercise the order journey",
                "Select one product from the inventory table, enter a quantity, and reserve it."));

    assertThat(PlanContractQualityGate.violations(plan))
        .noneMatch(value -> value.contains("implementation choice unresolved"));
  }

  @Test
  void uiTextInputOrSelectIsNotAnUnresolvedImplementationStrategy() {
    Plan plan =
        plan(
            frontend(
                "T12",
                "Build audit filters",
                "Use a warehouse filter with a text input or select and send its value as a query"
                    + " parameter."));

    assertThat(PlanContractQualityGate.violations(plan))
        .noneMatch(value -> value.contains("implementation choice unresolved"));
  }

  @Test
  void sqlSelectForUpdateIsNotAnUnresolvedImplementationChoice() {
    Plan plan =
        plan(
            backend(
                "T5",
                "Implement concurrent reservation",
                "SQLite does not provide effective SELECT FOR UPDATE locks; use BEGIN IMMEDIATE"
                    + " or one conditional UPDATE with affected-row verification."));

    assertThat(PlanContractQualityGate.violations(plan))
        .noneMatch(value -> value.contains("implementation choice unresolved"));
  }

  @Test
  void sqlSelectQueriesWithBusinessAlternativesAreNotImplementationChoices() {
    Plan plan =
        plan(
            backend(
                "T3",
                "Implement warehouse queries",
                "Use SQLAlchemy select statements to filter by warehouse code or name and return"
                    + " the matching rows."),
            backend(
                "T4",
                "Implement inventory mutations",
                "Execute SELECT queries for the source or target inventory row before applying the"
                    + " database-enforced conditional update."),
            backend(
                "T5",
                "Read inventory rows",
                "Select the database row for the source or return the stable not-found error."),
            backend(
                "T6",
                "Lock inventory rows",
                "Select either the source or target inventory row before applying the conditional"
                    + " update."));

    assertThat(PlanContractQualityGate.violations(plan))
        .noneMatch(value -> value.contains("implementation choice unresolved"));
  }

  @Test
  void stillRejectsAnExplicitSelectStackChoice() {
    Plan plan =
        plan(
            backend(
                "T1",
                "Scaffold persistence",
                "Select a database or ORM implementation and keep the chosen approach"
                    + " consistent."));

    assertThat(PlanContractQualityGate.violations(plan))
        .containsExactly(
            "T1: executable task leaves an implementation choice unresolved; select one concrete"
                + " stack or strategy before worker execution");
  }

  @Test
  void rejectsConflictingLifecycleForTheSameSharedArtifact() {
    Plan plan =
        plan(
            backend(
                "T1",
                "Create database migrations",
                "Add `migrations/002_add_replay.py` as a reversible migration with upgrade and"
                    + " downgrade."),
            backend(
                "T2",
                "Implement replay service",
                "Reuse `migrations/002_add_replay.py` as a forward-only migration."));

    assertThat(PlanContractQualityGate.violations(plan))
        .containsExactly(
            "T2: shared artifact 002_add_replay.py is called forward-only but T1 requires it to be"
                + " reversible");
  }

  @Test
  void doesNotApplyAnUnrelatedMigrationLifecycleToEveryArtifactInTheTask() {
    Plan plan =
        plan(
            backend(
                "T1",
                "Create models and migration",
                "Create `models/audit.py`. Add `migrations/001_schema.py` as a reversible"
                    + " migration."),
            backend(
                "T2",
                "Add another migration and reuse audit model",
                "Reuse `models/audit.py`. Add a new forward-only migration."));

    assertThat(PlanContractQualityGate.violations(plan))
        .noneMatch(value -> value.contains("audit.py"));
  }

  @Test
  void rejectsUnmappedStateStatusAliasInsideOneEntityContract() {
    Plan plan =
        plan(
            backend(
                "T1",
                "Implement jobs",
                "**Job model** has `state` (QUEUED|DONE). "
                    + "`JobResponse(id: UUID, status: str)` is returned by the API."));

    assertThat(PlanContractQualityGate.violations(plan))
        .containsExactly(
            "T1: Job persistence and response schemas alternate between state and status without"
                + " an explicit mapping");
  }

  @Test
  void responseSchemaBeforeAnotherModelDeclarationDoesNotCrashOrCrossCorrelate() {
    Plan plan =
        plan(
            backend(
                "T1",
                "Implement jobs and orders",
                "`JobResponse(id: UUID, status: str)` is returned by the API. "
                    + "**Order model** has `state` (DRAFT|RESERVED)."));

    assertThat(PlanContractQualityGate.violations(plan)).isEmpty();
  }

  @Test
  void rejectsSharedModelFieldDriftAcrossProducerTasks() {
    Plan plan =
        plan(
            backend(
                "T1",
                "Implement event model",
                "`EventLog`: id (UUID), operation (String), timestamp (DateTime),"
                    + " sequence_after (Integer)."),
            backend(
                "T2",
                "Extend event lifecycle",
                "The EventLog fields: id, operation, created_at, sequence_delta."));

    assertThat(PlanContractQualityGate.violations(plan))
        .anyMatch(
            value ->
                value.contains("T2: shared eventlog field contract differs from T1")
                    && value.contains("timestamp")
                    && value.contains("created_at"));
  }

  @Test
  void acceptsIdenticalSharedModelFieldContracts() {
    Plan plan =
        plan(
            backend(
                "T1",
                "Implement event model",
                "`EventLog`: id (UUID), operation (String), timestamp (DateTime),"
                    + " sequence_after (Integer)."),
            backend(
                "T2",
                "Extend event lifecycle",
                "The EventLog fields: id, operation, timestamp, sequence_after."));

    assertThat(PlanContractQualityGate.violations(plan)).isEmpty();
  }

  @Test
  void acceptsIdenticalBracedAndUnbracedSharedModelFieldContracts() {
    Plan plan =
        plan(
            backend(
                "T4",
                "Define inventory responses",
                "`InventoryResponse`: `{id (UUID), sku (String), quantity (Integer), status"
                    + " (String), updated_at (DateTime)}`."),
            backend(
                "T11",
                "Use inventory responses",
                "The InventoryResponse fields: id, sku, quantity, status, updated_at."));

    assertThat(PlanContractQualityGate.violations(plan)).isEmpty();
  }

  @Test
  void rejectsTrueStrictSubsetOfBracedSharedModelContract() {
    Plan plan =
        plan(
            backend(
                "T4",
                "Define inventory responses",
                "`InventoryResponse`: {id (UUID), sku (String), quantity (Integer), status"
                    + " (String), updated_at (DateTime)}."),
            backend(
                "T11",
                "Use inventory responses",
                "The InventoryResponse fields: {sku, quantity, status}."));

    assertThat(PlanContractQualityGate.violations(plan))
        .anyMatch(
            value ->
                value.contains("T11: shared inventoryresponse field contract differs from T4")
                    && value.contains("id")
                    && value.contains("updated_at"));
  }

  @Test
  void rejectsDivergentBracedSharedSchemaAliases() {
    Plan plan =
        plan(
            backend(
                "T1",
                "Implement event model",
                "`EventLog`: {id (UUID), operation (String), timestamp (DateTime),"
                    + " sequence_after (Integer)}."),
            backend(
                "T2",
                "Extend event lifecycle",
                "The EventLog fields: {id, operation, created_at, sequence_delta}."));

    assertThat(PlanContractQualityGate.violations(plan))
        .anyMatch(
            value ->
                value.contains("shared eventlog field contract differs")
                    && value.contains("timestamp")
                    && value.contains("created_at"));
  }

  @Test
  void rejectsBulletStyleSharedModelAliasesAcrossProducerAndConsumer() {
    Plan plan =
        plan(
            backend(
                "T2",
                "Define audit contract",
                "- **AuditEntry**:\n"
                    + "  - `id` (UUID)\n"
                    + "  - `operation` (String)\n"
                    + "  - `product_id` (UUID)\n"
                    + "  - `idempotency_key` (String)\n"),
            backend(
                "T6",
                "Consume audit contract",
                "- **AuditEntry**:\n"
                    + "  - `id` (UUID)\n"
                    + "  - `operation` (String)\n"
                    + "  - `sku` (String)\n"
                    + "  - `idempotency_reference` (String)\n"));

    assertThat(PlanContractQualityGate.violations(plan))
        .anyMatch(
            value ->
                value.contains("T6: shared auditentry field contract differs from T2")
                    && value.contains("product_id")
                    && value.contains("sku")
                    && value.contains("idempotency_key")
                    && value.contains("idempotency_reference"));
  }

  @Test
  void rejectsCollapsedReferenceWhenProducerDeclaresSeparateReferenceFields() {
    Plan plan =
        plan(
            backend(
                "T4",
                "Define movement DTO",
                "`MovementRecord`: id, operation, source_reference_id, target_reference_id."),
            backend(
                "T8",
                "Render movement DTO",
                "- `MovementRecord`:\n"
                    + "  - `id` (UUID)\n"
                    + "  - `operation` (String)\n"
                    + "  - `reference_id` (UUID)\n"));

    assertThat(PlanContractQualityGate.violations(plan))
        .anyMatch(
            value ->
                value.contains("T8: shared movementrecord field contract differs from T4")
                    && value.contains("source_reference_id")
                    && value.contains("target_reference_id")
                    && value.contains("reference_id"));
  }

  @Test
  void acceptsEquivalentInlineAndBulletStyleSharedContracts() {
    Plan plan =
        plan(
            backend(
                "T2",
                "Define audit contract",
                "`AuditEntry`: id (UUID), operation (String), subject_id (UUID), reference_key"
                    + " (String)."),
            backend(
                "T6",
                "Consume audit contract",
                "- **AuditEntry**:\n"
                    + "  - `id` (UUID)\n"
                    + "  - `operation` (String)\n"
                    + "  - `subject_id` (UUID)\n"
                    + "  - `reference_key` (String)\n"));

    assertThat(PlanContractQualityGate.violations(plan))
        .noneMatch(value -> value.contains("shared auditentry field contract differs"));
  }

  @Test
  void rejectsInstallCommandForManifestThatNoTaskOwns() {
    PlannedTask scaffold =
        PlannedTask.builder()
            .id("T1")
            .title("Scaffold Python service")
            .description("Create backend/pyproject.toml with the application dependencies.")
            .touchesFiles(List.of("backend/pyproject.toml"))
            .build();
    PlannedTask docs =
        PlannedTask.builder()
            .id("T2")
            .title("Document setup")
            .description("Install with `pip install -r backend/requirements.txt`.")
            .touchesFiles(List.of("README.md", "Makefile"))
            .build();

    assertThat(PlanContractQualityGate.violations(plan(scaffold, docs)))
        .containsExactly(
            "T2: setup installs requirements.txt, but the plan owns pyproject.toml and no"
                + " requirements.txt artifact");
  }

  @Test
  void normalizerRewritesSetupToTheDependencyManifestThePlanOwns() {
    PlannedTask scaffold =
        PlannedTask.builder()
            .id("T1")
            .title("Scaffold Python service")
            .description("Create backend/pyproject.toml with application dependencies.")
            .touchesFiles(List.of("backend/pyproject.toml"))
            .build();
    PlannedTask docs =
        PlannedTask.builder()
            .id("T2")
            .title("Document setup")
            .description("Run `pip install -r backend/requirements.txt` before startup.")
            .hardConstraints(
                List.of("Clean checkout setup uses pip install -r backend/requirements.txt."))
            .touchesFiles(List.of("README.md"))
            .build();

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan(scaffold, docs));

    assertThat(normalized.tasks().get(1).description())
        .contains("pip install -e backend")
        .doesNotContain("requirements.txt");
    assertThat(normalized.tasks().get(1).hardConstraints())
        .containsExactly("Clean checkout setup uses pip install -e backend.");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void normalizerRepairsEditableInstallRelativeToChangedWorkingDirectory() {
    PlannedTask scaffold =
        task(
            "T1",
            "Scaffold Python service",
            "Create backend/pyproject.toml with application dependencies.",
            List.of("backend/pyproject.toml"));
    PlannedTask docs =
        task(
            "T2",
            "Document setup",
            "Run `cd backend && pip install -e backend && pytest` from repository root.",
            List.of("README.md"));

    assertThat(PlanContractQualityGate.violations(plan(scaffold, docs)))
        .containsExactly(
            "T2: editable install repeats backend after changing into that directory; use `pip"
                + " install -e .` there");

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan(scaffold, docs));

    assertThat(normalized.tasks().get(1).description())
        .contains("cd backend && pip install -e . && pytest")
        .doesNotContain("pip install -e backend");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void rejectsAsyncEngineWithSynchronousSqliteUrl() {
    Plan plan =
        plan(
            backend(
                "T1",
                "Scaffold persistence",
                "Use create_async_engine with DATABASE_URL default sqlite:///./app.db."));

    assertThat(PlanContractQualityGate.violations(plan))
        .containsExactly(
            "T1: async SQLAlchemy engine is paired with a synchronous sqlite:/// URL; use an"
                + " async driver URL consistently or select the synchronous SQLAlchemy stack");
  }

  @Test
  void implementationDoesNotEmbedPreviouslyTestedProductContracts() throws Exception {
    String source =
        Files.readString(
            Path.of("src/main/java/com/navneet/executespec/agent/PlanContractQualityGate.java"));

    assertThat(source)
        .doesNotContain("/api/audit")
        .doesNotContain("warehouse_id")
        .doesNotContain("reservation_id")
        .doesNotContain("quantity_delta")
        .doesNotContain("\"sku\"");
  }

  @Test
  void regressionReconcilesAuditConsumerFromFailedLocalHierarchicalRun() {
    Plan raw =
        plan(
            backend(
                "T7",
                "Implement audit-history list endpoint",
                "Prefix `/api/audit`: GET /api/audit with query params ?operation,"
                    + " ?warehouse_id, ?product_id, ?order_id, ?search, ?offset, ?limit.\n"
                    + "`AuditLogResponse`: id, timestamp, operation, warehouse_id, product_id,"
                    + " order_id, quantity_delta, reason, idempotency_key, details."),
            frontend(
                "T13",
                "Build Audit History screen",
                "getAuditHistory(params?: { operation?, warehouse_id?, sku?, order_id?, page?,"
                    + " page_size? }) -> GET/audit-history"));

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(raw);
    PlannedTask consumer = normalized.tasks().get(1);

    assertThat(consumer.description())
        .contains("GET /api/audit")
        .contains("product_id?: string")
        .contains("offset?: number")
        .contains("limit?: number")
        .doesNotContain("sku?", "page?", "page_size?");
    assertThat(consumer.hardConstraints())
        .anyMatch(
            value ->
                value.contains("T7 is authoritative")
                    && value.contains("quantity_delta")
                    && value.contains("idempotency_key"));
    assertThat(TaskExpanderAgent.finalPlanContractViolations(normalized)).isEmpty();
  }

  @Test
  void reconciliationPrefersTheTaskThatOwnsTheMatchingRouterArtifact() {
    PlannedTask incidental =
        task(
            "T4",
            "Implement inventory mutations",
            "GET /api/inventory accepts ?sku and ?low_stock. Verify persisted rows later through"
                + " GET /api/audit.",
            List.of("backend/app/routers/inventory.py"));
    PlannedTask owner =
        task(
            "T7",
            "Implement audit history",
            "GET /api/audit accepts ?operation, ?warehouse_id, ?search, ?page, and ?page_size.",
            List.of("backend/app/routers/audit.py"));
    PlannedTask consumer =
        task(
            "T13",
            "Build audit screen",
            "Fetch GET /api/audit with ?operation and ?page.",
            List.of("frontend/src/pages/Audit.tsx"));

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan(incidental, owner, consumer));

    assertThat(normalized.tasks().get(2).hardConstraints())
        .anyMatch(
            value ->
                value.contains("T7 is authoritative")
                    && value.contains("operation")
                    && value.contains("page"))
        .noneMatch(value -> value.contains("T4 is authoritative"));
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void endpointLocalQueryRepairDoesNotRewriteTheFirstUnrelatedClientParamBlock() {
    PlannedTask producer =
        backend(
            "T3",
            "Implement product API",
            "GET /api/products accepts ?search, ?page, and ?page_size.");
    PlannedTask warehouseProducer =
        backend("T2", "Implement warehouse API", "GET /api/warehouses accepts ?search.");
    PlannedTask consumer =
        frontend(
            "T8",
            "Build API client",
            "getWarehouses(params?: { search? }) -> GET /api/warehouses.\n"
                + "getProducts(params?: { search?, limit? }) -> GET /api/products?limit=10.");

    Plan normalized =
        TaskExpanderAgent.normalizeFinalPlan(plan(warehouseProducer, producer, consumer));

    assertThat(normalized.tasks().get(2).description())
        .startsWith("getWarehouses(params?: { search? })")
        .contains("getProducts(params?: {")
        .contains("page_size?: number")
        .contains("GET /api/products?page_size=10")
        .doesNotContain("GET /api/products?limit=10");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void endpointBindingsDoNotCopyCollectionQueryParamsOntoMutationRoutes() {
    PlannedTask producer =
        task(
            "T5",
            "Implement order API",
            "GET /api/orders accepts ?status, ?page, and ?page_size."
                + " POST /api/orders/{order_id}/reserve requires Idempotency-Key.",
            List.of("backend/app/routers/orders.py"));
    PlannedTask consumer =
        task(
            "T12",
            "Build order UI",
            "List with GET /api/orders?status=DRAFT&page=1."
                + " Reserve with POST /api/orders/{id}/reserve.",
            List.of("frontend/src/pages/Orders.tsx"));

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan(producer, consumer));

    assertThat(normalized.tasks().get(1).hardConstraints())
        .anyMatch(
            value ->
                value.contains("POST /api/orders/{id}/reserve")
                    && value.contains("query parameters []"));
  }

  @Test
  void impossibleGetMutationIsCorrectedWithoutBeingRevertedToCollectionGet() {
    Plan plan =
        plan(
            backend("T5", "Implement order API", "GET /api/orders."),
            frontend(
                "T14",
                "Build order UI",
                "Create with GET/orders returning 201 and a response body. Commit with"
                    + " GET/orders/{id}/commit."));

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan);
    String description = normalized.tasks().get(1).description();

    assertThat(description)
        .contains("POST /api/orders")
        .contains("POST /api/orders/{id}/commit")
        .doesNotContain("GET/orders");
    assertThat(PlanContractQualityGate.violations(normalized))
        .contains(
            "T14: API consumer uses POST /api/orders/{id}/commit but no backend task declares that"
                + " operation");
  }

  @Test
  void missingConsumerOperationsAreBackfilledIntoTheUniqueBackendRouteOwner() {
    PlannedTask producer =
        task(
            "T5",
            "Implement order API",
            "GET /api/orders.",
            List.of("backend/app/routers/orders.py"));
    PlannedTask consumer =
        task(
            "T14",
            "Build order UI",
            "GET /api/orders/{id}. POST /api/orders. POST /api/orders/{id}/commit. POST"
                + " /api/orders/{id}/cancel.",
            List.of("frontend/src/pages/Orders.tsx"));

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan(producer, consumer));

    assertThat(normalized.tasks().getFirst().description())
        .contains("API PRODUCER CONTRACTS")
        .contains("GET /api/orders/{id}")
        .contains("POST /api/orders")
        .contains("POST /api/orders/{id}/commit")
        .contains("POST /api/orders/{id}/cancel");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void unsupportedProseQuerySuggestionIsRemovedFromConsumerContract() {
    Plan plan =
        plan(
            backend(
                "T3",
                "Implement inventory API",
                "GET /api/inventory with ?warehouse_id and ?search."),
            frontend(
                "T11",
                "Build dashboard",
                "Use GET /api/inventory with `?low_stock=true` or equivalent filter. Derive the"
                    + " count client-side."));

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan);

    assertThat(normalized.tasks().get(1).description()).doesNotContain("?low_stock");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void typedClientHelperInventoryIsNotParsedAsAnApiOperation() {
    Plan plan =
        plan(
            backend("T1", "Implement warehouse API", "PATCH /api/warehouses/{id}."),
            frontend(
                "T2",
                "Build warehouse screen",
                "Use the shared typed API client with get/post/patch/del helpers. Call PATCH"
                    + " /api/warehouses/{id} when saving."));

    assertThat(PlanContractQualityGate.violations(plan))
        .noneMatch(value -> value.contains("/api/del"));
  }

  @Test
  void optionalItemRouteShorthandPreservesDeleteResourceIdentity() {
    Plan plan =
        plan(
            backend(
                "T1",
                "Implement warehouse API",
                "POST /api/warehouses. DELETE /api/warehouses/{warehouse_id}."),
            frontend(
                "T2",
                "Build warehouse client",
                "Map create/deactivate to POST/DELETE /api/warehouses[/{warehouse_id}]."));

    assertThat(PlanContractQualityGate.violations(plan))
        .noneMatch(value -> value.contains("DELETE /api/warehouses"));
  }

  @Test
  void endpointClauseKeepsAllAmpersandQueryParamsWithoutLeakingToSiblingRoute() {
    PlannedTask producer =
        backend(
            "T4",
            "Implement inventory API",
            "GET /api/inventory with ?warehouse_id=1, ?product_id=1, ?search=x,"
                + " ?low_stock=true, ?page=1&page_size=20. GET /api/inventory/{id}.");
    PlannedTask consumer =
        frontend(
            "T10",
            "Build inventory screen",
            "GET /api/inventory?warehouse_id=1&product_id=1&search=x&low_stock=true&page=1"
                + "&page_size=20. GET /api/inventory/{id}.");

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan(producer, consumer));

    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
    assertThat(normalized.tasks().get(1).hardConstraints())
        .anyMatch(
            value ->
                value.contains("T4 is authoritative for GET /api/inventory")
                    && value.contains("page_size"));
  }

  @Test
  void negatedPollingInstructionIsAcceptedButPersistedOrderStateIsRejected() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T5")
            .title("Implement idempotent orders")
            .description("Never poll for another transaction. Add Order model with state DRAFT.")
            .complexity(PlannedTask.Complexity.MEDIUM)
            .touchesFiles(List.of("backend/app/models.py"))
            .hardConstraints(
                List.of(
                    "Use one exact IdempotencyRecord contract everywhere: key, scope,"
                        + " request_body_hash, state, nullable response_status, and nullable"
                        + " response_body; it must not poll.",
                    "Order.status lifecycle values are exactly DRAFT, RESERVED, COMMITTED,"
                        + " CANCELLED."))
            .dependsOn(List.of())
            .meetsRequirements(List.of())
            .taskType(PlannedTask.TaskType.LEAF)
            .build();

    assertThat(PlanContractQualityGate.violations(plan(task)))
        .contains("Order producer persists state instead of the canonical status field")
        .noneMatch(value -> value.contains("Idempotency task polls"));

    PlannedTask repaired =
        TaskExpanderAgent.applyDeterministicSemanticFallback(
            task, TaskExpanderAgent.semanticViolations(task, plan(task)));
    assertThat(repaired.description())
        .contains("Order model with status DRAFT")
        .doesNotContain("Order model with state DRAFT");
    assertThat(repaired.hardConstraints())
        .anyMatch(value -> value.contains("canonical Order.status field"));
    assertThat(TaskExpanderAgent.semanticViolations(repaired, plan(repaired))).isEmpty();
  }

  @Test
  void readinessPollingDoesNotContradictIdempotencyWriterSerialization() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T7")
            .title("Run browser acceptance tests")
            .description(
                "Start the real backend and poll its health endpoint with a bounded timeout."
                    + " Exercise exact replay after readiness succeeds.")
            .complexity(PlannedTask.Complexity.MEDIUM)
            .touchesFiles(List.of("e2e/tests/full-flow.spec.ts"))
            .hardConstraints(
                List.of(
                    "Use one exact IdempotencyRecord contract everywhere: key, scope,"
                        + " request_body_hash, state, nullable response_status, and nullable"
                        + " response_body.",
                    "SQLite idempotency ownership must start BEGIN IMMEDIATE before lookup; it"
                        + " must not poll for another transaction's uncommitted PROCESSING row."))
            .dependsOn(List.of())
            .meetsRequirements(List.of())
            .taskType(PlannedTask.TaskType.LEAF)
            .build();

    assertThat(TaskExpanderAgent.finalPlanContractViolations(plan(task)))
        .noneMatch(value -> value.contains("Idempotency task polls"));
  }

  @Test
  void sameSentenceReadinessPollingDoesNotContaminateIdempotencyReplayVerification() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T7")
            .title("Run browser acceptance tests")
            .description(
                "Poll the health endpoint until ready, then test the idempotency replay transaction"
                    + " with the exact original request.")
            .complexity(PlannedTask.Complexity.MEDIUM)
            .touchesFiles(List.of("e2e/tests/full-flow.spec.ts"))
            .hardConstraints(
                List.of(
                    "Use one exact IdempotencyRecord contract everywhere: key, scope,"
                        + " request_body_hash, state, nullable response_status, and nullable"
                        + " response_body; it must not poll."))
            .dependsOn(List.of())
            .meetsRequirements(List.of())
            .taskType(PlannedTask.TaskType.LEAF)
            .build();

    assertThat(TaskExpanderAgent.finalPlanContractViolations(plan(task)))
        .noneMatch(value -> value.contains("Idempotency task polls"));
  }

  @Test
  void finalValidationReportsAnIdempotencyPollingContradictionOnceWithTaskAttribution() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T5")
            .title("Implement idempotent reservation")
            .description(
                "When IdempotencyRecord is PROCESSING, poll it in a retry loop until the other"
                    + " transaction commits.")
            .complexity(PlannedTask.Complexity.HIGH)
            .touchesFiles(List.of("backend/app/services/orders.py"))
            .hardConstraints(
                List.of(
                    "Use one exact IdempotencyRecord contract everywhere: key, scope,"
                        + " request_body_hash, state, nullable response_status, and nullable"
                        + " response_body; it must not poll."))
            .dependsOn(List.of())
            .meetsRequirements(List.of())
            .taskType(PlannedTask.TaskType.LEAF)
            .build();

    assertThat(TaskExpanderAgent.finalPlanContractViolations(plan(task)))
        .containsExactly(
            "T5: Idempotency task polls despite the binding writer-serialization contract");
  }

  @Test
  void negatedMigrationOwnershipBoundaryDoesNotTriggerPositiveInstructionViolation() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T1")
            .title("Configure migration runtime")
            .description(
                "This task must not create, generate, or own a versioned migration. Do not create"
                    + " the initial migration here.")
            .complexity(PlannedTask.Complexity.MEDIUM)
            .touchesFiles(List.of("backend/alembic/env.py"))
            .hardConstraints(
                List.of(
                    "This task may configure the migration runtime but must not create a"
                        + " versioned migration."))
            .dependsOn(List.of())
            .meetsRequirements(List.of())
            .taskType(PlannedTask.TaskType.LEAF)
            .build();

    assertThat(PlanContractQualityGate.violations(plan(task)))
        .noneMatch(value -> value.contains("migration-runtime-only"));
  }

  @Test
  void negatedSeparateCommitRuleDoesNotBecomeSplitIdempotencyInstruction() {
    assertThat(
            PlanSafetyIdempotencySupport.hasSplitTransactionInstruction(
                "Persist the result in the same transaction and never commit a claim separately."
                    + "\nIDEMPOTENCY TRANSACTION: ownership and business mutation share one"
                    + " transaction."))
        .isFalse();
    assertThat(
            PlanSafetyIdempotencySupport.hasSplitTransactionInstruction(
                "Use a short-lived transaction, then let the business handler proceed."))
        .isTrue();
  }

  @Test
  void normalizerRepairsCrudMethodAndPromotesRequirementBackedSearchToProducer() {
    PlannedTask producer =
        backend(
            "T6",
            "Implement warehouse APIs",
            "GET /api/warehouses?limit=20&skip=0. PATCH /api/warehouses/{warehouse_id}.");
    PlannedTask consumer =
        frontend(
            "T10",
            "Build warehouse management",
            "List with GET /api/warehouses?search=blr&limit=20&skip=0. In edit mode submit"
                + " changes with POST /api/warehouses/{id}.");
    producer = withRequirements(producer, "R1");
    Plan plan =
        Plan.builder()
            .planTitle("contract test")
            .projectType("full_stack")
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "List endpoints support useful search and pagination.",
                        RequirementSpec.RequirementKind.FUNCTIONAL)))
            .tasks(List.of(producer, consumer))
            .build();

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan);

    assertThat(normalized.tasks().getFirst().description())
        .contains("API producer contract binding: GET /api/warehouses")
        .contains("search=");
    assertThat(normalized.tasks().get(1).description())
        .contains("PATCH /api/warehouses/{warehouse_id}")
        .doesNotContain("POST /api/warehouses/{id}");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void routeRepairPrecedesRequirementBackedQueryPromotion() {
    PlannedTask producer =
        withRequirements(
            backend(
                "T6",
                "Implement order and audit APIs",
                "POST /api/orders/{order_id}/reserve requires Idempotency-Key."
                    + " GET /api/audit accepts ?operation, ?warehouse_id, ?product_id, ?order_id,"
                    + " ?page, and ?page_size."),
            "R1");
    PlannedTask consumer =
        frontend(
            "T13",
            "Build audit history page",
            "Fetch audit history from POST /api/orders/{order_id}/reserve with query params"
                + " ?operation, ?warehouse_id, ?product_id, ?order_id, ?page, and ?page_size.");
    consumer =
        PlannedTask.builder()
            .id(consumer.id())
            .title(consumer.title())
            .description(consumer.description())
            .complexity(consumer.complexity())
            .touchesFiles(consumer.touchesFiles())
            .dependsOn(consumer.dependsOn())
            .hardConstraints(
                List.of(
                    "Use this exact public API path for this task: /api/audit. Consumers must not"
                        + " invent alternate endpoint shapes."))
            .meetsRequirements(consumer.meetsRequirements())
            .taskType(consumer.taskType())
            .build();
    Plan plan =
        Plan.builder()
            .planTitle("audit contract")
            .projectType("full_stack")
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Audit history supports operation, warehouse, product, and order filters"
                            + " with pagination.",
                        RequirementSpec.RequirementKind.FUNCTIONAL)))
            .tasks(List.of(producer, consumer))
            .build();

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan);

    assertThat(normalized.tasks().get(1).description())
        .contains("GET /api/audit")
        .doesNotContain("POST /api/orders/{order_id}/reserve");
    assertThat(normalized.tasks().getFirst().description())
        .doesNotContain(
            "API producer contract binding: POST /api/orders/{id}/reserve?warehouse_id=");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void mutationTitleDoesNotUseReadRouteFromAnIncidentalConstraint() {
    PlannedTask producer =
        backend(
            "T6",
            "Implement order and audit APIs",
            "POST /api/orders/{order_id}/reserve. GET /api/audit.");
    PlannedTask consumer =
        frontend(
            "T13",
            "Reserve order",
            "Submit the reservation with POST /api/orders/{order_id}/reserve.");
    consumer =
        PlannedTask.builder()
            .id(consumer.id())
            .title(consumer.title())
            .description(consumer.description())
            .complexity(consumer.complexity())
            .touchesFiles(consumer.touchesFiles())
            .dependsOn(consumer.dependsOn())
            .hardConstraints(List.of("Audit links use this exact public API path: /api/audit."))
            .meetsRequirements(consumer.meetsRequirements())
            .taskType(consumer.taskType())
            .build();

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan(producer, consumer));

    assertThat(normalized.tasks().get(1).description())
        .contains("POST /api/orders/{order_id}/reserve")
        .doesNotContain("GET /api/audit");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void readOrientedPagePreservesItsExactMutationProducer() {
    PlannedTask producer =
        backend(
            "T6", "Implement order API", "GET /api/orders. POST /api/orders/{order_id}/cancel.");
    PlannedTask consumer =
        frontend(
            "T13",
            "Build order history dashboard",
            "Load history with GET /api/orders.\n"
                + "Cancel a selected order with POST /api/orders/{order_id}/cancel.");
    consumer =
        PlannedTask.builder()
            .id(consumer.id())
            .title(consumer.title())
            .description(consumer.description())
            .complexity(consumer.complexity())
            .touchesFiles(consumer.touchesFiles())
            .dependsOn(consumer.dependsOn())
            .hardConstraints(
                List.of(
                    "Use these exact public API paths: /api/orders,"
                        + " /api/orders/{order_id}/cancel."))
            .meetsRequirements(consumer.meetsRequirements())
            .taskType(consumer.taskType())
            .build();

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan(producer, consumer));

    assertThat(normalized.tasks().get(1).description())
        .contains("GET /api/orders")
        .contains("POST /api/orders/{order_id}/cancel");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void reconcilesTypedClientMethodsAndActionNamesToDeclaredProducers() {
    PlannedTask inventoryProducer =
        backend(
            "T6",
            "Implement resource APIs",
            "PATCH /api/warehouses/{warehouse_id}."
                + " PATCH /api/products/{product_id}."
                + " GET /api/inventory/{inventory_id}."
                + " POST /api/adjustments.");
    PlannedTask orderProducer =
        backend(
            "T7",
            "Implement order actions",
            "POST /api/orders/{order_id}/reserve."
                + " POST /api/orders/{order_id}/cancel."
                + " POST /api/orders/{order_id}/commit.");
    PlannedTask client =
        frontend(
            "T9",
            "Build typed API client",
            "updateWarehouse(id) -> PUT /api/warehouses/{id}.\n"
                + "updateProduct(id) -> PUT /api/products/{id}.\n"
                + "getInventory(warehouseId, sku) -> GET"
                + " /api/inventory/{warehouse_id}/{sku}.\n"
                + "adjustStock(data) -> POST /api/inventory/adjust.\n"
                + "commitOrder(id) -> POST /api/orders/{id}/reserve.");

    Plan normalized =
        TaskExpanderAgent.normalizeFinalPlan(plan(inventoryProducer, orderProducer, client));

    assertThat(normalized.tasks().get(2).description())
        .contains("PATCH /api/warehouses/{warehouse_id}")
        .contains("PATCH /api/products/{product_id}")
        .contains("GET /api/inventory/{inventory_id}")
        .contains("POST /api/adjustments")
        .contains("POST /api/orders/{order_id}/commit")
        .doesNotContain("PUT /api/warehouses/{id}")
        .doesNotContain("PUT /api/products/{id}")
        .doesNotContain("/api/inventory/{warehouse_id}/{sku}")
        .doesNotContain("/api/inventory/adjust")
        .doesNotContain("commitOrder(id) -> POST /api/orders/{id}/reserve");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void ambiguousTypedActionNounAliasesRemainFailClosed() {
    PlannedTask producers =
        backend("T6", "Implement adjustment APIs", "POST /api/adjust. POST /api/adjustments.");
    PlannedTask client =
        frontend(
            "T9", "Build typed API client", "adjustStock(data) -> POST /api/inventory/change.");

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan(producers, client));

    assertThat(normalized.tasks().get(1).description())
        .contains("POST /api/inventory/change")
        .doesNotContain("POST /api/adjustments");
    assertThat(PlanContractQualityGate.violations(normalized))
        .contains(
            "T9: API consumer uses POST /api/inventory/change but no backend task declares that"
                + " operation");
  }

  @Test
  void itemUpdateUsesTheSoleSamePathUpdateProducerEvenWhenReadAndDeleteAlsoExist() {
    PlannedTask producer =
        backend(
            "T3",
            "Implement warehouse and product APIs",
            "POST /api/warehouses. GET /api/warehouses/{warehouse_id}."
                + " PATCH /api/warehouses/{warehouse_id}."
                + " DELETE /api/warehouses/{warehouse_id}. GET /api/products/{product_id}."
                + " POST /api/products. PATCH /api/products/{product_id}."
                + " DELETE /api/products/{product_id}.");
    PlannedTask consumer =
        frontend(
            "T10",
            "Build warehouse and product forms",
            "On create: POST /api/warehouses. On update: PUT /api/warehouses/{id}.\n"
                + "On create: POST /api/products. On update: PUT /api/products/{id}.");

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan(producer, consumer));

    assertThat(normalized.tasks().get(1).description())
        .contains("PATCH /api/warehouses/{warehouse_id}")
        .contains("PATCH /api/products/{product_id}")
        .doesNotContain("PUT /api/warehouses/{id}", "PUT /api/products/{id}");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void missingActionProducerContractsSurviveDescriptionCompactionAtTheRouteOwner() {
    String nearCeilingDescription =
        "Implement inventory tracking and atomic stock operations. "
            + "x".repeat(TaskExpanderAgent.MAX_DESCRIPTION_CHARS - 180);
    PlannedTask producer =
        task(
            "T4",
            "Implement inventory adjustments and transfers",
            nearCeilingDescription,
            List.of("backend/app/routers/inventory.py"));
    List<String> constraints = new ArrayList<>();
    constraints.add(
        "Use these exact public API paths for the operations in this task:"
            + " /api/inventory/adjust, /api/inventory/transfer.");
    for (int index = 0; index < 11; index++) {
      constraints.add("Inventory safety constraint " + index + " must remain enforced.");
    }
    producer =
        PlannedTask.builder()
            .id(producer.id())
            .title(producer.title())
            .description(producer.description())
            .complexity(producer.complexity())
            .touchesFiles(producer.touchesFiles())
            .dependsOn(producer.dependsOn())
            .hardConstraints(constraints)
            .meetsRequirements(producer.meetsRequirements())
            .taskType(producer.taskType())
            .build();
    PlannedTask consumer =
        frontend(
            "T11",
            "Build inventory adjustment and transfer flows",
            "Submit adjustments with POST /api/inventory/adjust. Submit transfers with POST"
                + " /api/inventory/transfer.");

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan(producer, consumer));

    assertThat(normalized.tasks().getFirst().description())
        .contains("API PRODUCER CONTRACTS")
        .contains("POST /api/inventory/adjust")
        .contains("POST /api/inventory/transfer")
        .hasSizeLessThanOrEqualTo(TaskExpanderAgent.MAX_DESCRIPTION_CHARS);
    assertThat(normalized.tasks().getFirst().hardConstraints())
        .hasSizeLessThanOrEqualTo(12)
        .anyMatch(
            value ->
                value.contains("API PRODUCER CONTRACTS")
                    && value.contains("POST /api/inventory/adjust")
                    && value.contains("POST /api/inventory/transfer"));
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void crossCuttingApiMetadataDoesNotStealResourceProducerOwnership() {
    PlannedTask products =
        task(
            "T3",
            "Implement product catalog API",
            "Create products with POST /api/products and persist product records.",
            List.of("server/src/main/java/example/catalog/ProductService.java"));
    PlannedTask inventory =
        task(
            "T4",
            "Implement inventory adjustments and transfers",
            "Apply both stock mutations atomically and persist their audit records.",
            List.of("server/src/main/java/example/inventory/InventoryService.java"));
    inventory =
        PlannedTask.builder()
            .id(inventory.id())
            .title(inventory.title())
            .description(inventory.description())
            .complexity(inventory.complexity())
            .touchesFiles(inventory.touchesFiles())
            .dependsOn(inventory.dependsOn())
            .hardConstraints(List.of("Exact public paths: /api/adjustments and /api/transfers."))
            .meetsRequirements(inventory.meetsRequirements())
            .taskType(inventory.taskType())
            .build();
    PlannedTask metadata =
        task(
            "T8",
            "Configure CORS, global error handling, and OpenAPI metadata",
            "Document GET/POST /api/products, POST /api/adjustments, and POST /api/transfers.",
            List.of(
                "server/src/main/java/example/config/ApiConfig.java",
                "server/src/main/java/example/error/GlobalErrorHandler.java"));
    PlannedTask typedClient =
        frontend("T9", "Build typed API client", "List products with GET /api/products.");
    PlannedTask stockFlows =
        frontend(
            "T10",
            "Build stock mutation flows",
            "Submit POST /api/adjustments and POST /api/transfers.");
    PlannedTask details =
        frontend(
            "T13",
            "Build product details",
            "Load GET /api/products/{id}; submit POST /api/adjustments and POST /api/transfers.");
    PlannedTask acceptance =
        task(
            "T16",
            "Implement end-to-end tests",
            "Verify POST /api/adjustments and POST /api/transfers.",
            List.of("e2e/tests/inventory.spec.ts"));

    Plan normalized =
        TaskExpanderAgent.normalizeFinalPlan(
            plan(products, inventory, metadata, typedClient, stockFlows, details, acceptance));

    assertThat(normalized.tasks().get(0).description())
        .contains("GET /api/products", "GET /api/products/{id}");
    assertThat(normalized.tasks().get(1).description())
        .contains("POST /api/adjustments", "POST /api/transfers");
    assertThat(normalized.tasks().get(2).description()).doesNotContain("API PRODUCER CONTRACTS");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void compactMethodListsDeclareEveryOperationOnTheSharedPath() {
    PlannedTask producer =
        task(
            "T3",
            "Implement product controller",
            "Expose GET/POST/PATCH/DELETE /api/products/{id}.",
            List.of("server/src/main/java/example/catalog/ProductController.java"));
    PlannedTask consumer =
        frontend(
            "T9",
            "Build product client",
            "Use GET /api/products/{id}, POST /api/products/{id}, PATCH /api/products/{id}, and"
                + " DELETE /api/products/{id}.");

    assertThat(PlanContractQualityGate.violations(plan(producer, consumer))).isEmpty();
  }

  @Test
  void rejectsRequirementThatNoTaskCarriesIntoWorkerExecution() {
    RequirementSpec requirement =
        new RequirementSpec(
            "R17",
            "The management screen supports loading, empty, error, and success states.",
            RequirementSpec.RequirementKind.FUNCTIONAL);
    Plan plan =
        Plan.builder()
            .planTitle("coverage")
            .requirements(List.of(requirement))
            .tasks(
                List.of(
                    frontend(
                        "T1",
                        "Build management screen",
                        "Render the persisted records in a responsive table.")))
            .build();

    assertThat(PlanContractQualityGate.violations(plan))
        .containsExactly(
            "R17: requirement is not cited by any task's meetsRequirements; it would be absent"
                + " from the worker execution contract");
  }

  @Test
  void rejectsRequirementCitationThatDropsHighSignalBehavior() {
    RequirementSpec requirement =
        new RequirementSpec(
            "R1",
            "Resources: create, list, edit, and deactivate with unique code validation.",
            RequirementSpec.RequirementKind.FUNCTIONAL);
    PlannedTask task =
        withRequirements(
            backend(
                "T1",
                "Implement resource CRUD",
                "Create, list, edit, and hard-delete resources. Names are unique and validated."),
            "R1");
    Plan plan =
        Plan.builder()
            .planTitle("coverage")
            .requirements(List.of(requirement))
            .tasks(List.of(task))
            .build();

    assertThat(PlanContractQualityGate.violations(plan))
        .contains(
            "R1: tasks [T1] cite the requirement but omit high-signal contract terms [code,"
                + " deactivate]");
  }

  @Test
  void normalizationReinforcesExactlyOnceAsExecutablePhraseInsteadOfInternalMarkerKey() {
    RequirementSpec requirement =
        new RequirementSpec(
            "R9",
            "Cancelling a RESERVED order atomically releases stock exactly once.",
            RequirementSpec.RequirementKind.FUNCTIONAL);
    PlannedTask task =
        withRequirements(
            backend(
                "T8",
                "Implement order cancellation",
                "POST /api/orders/{order_id}/cancel releases reserved stock atomically."),
            "R9");
    Plan plan =
        Plan.builder()
            .planTitle("coverage")
            .requirements(List.of(requirement))
            .tasks(List.of(task))
            .build();

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan);

    assertThat(normalized.tasks().getFirst().description())
        .contains("R9 requires exactly once")
        .doesNotContain("exactly_once");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void normalizationAddsNewRequirementSignalBesideAnExistingSignalParagraph() {
    RequirementSpec requirement =
        new RequirementSpec(
            "R8",
            "Concurrent reservations for scarce stock must never oversell.",
            RequirementSpec.RequirementKind.NON_FUNCTIONAL);
    PlannedTask task =
        withRequirements(
            backend(
                "T5",
                "Implement reservation",
                "Use a guarded stock update.\n\n"
                    + "REQUIREMENT SIGNALS (binding; preserve these exact contract terms):"
                    + " R8 requires oversell."),
            "R8");
    Plan plan =
        Plan.builder()
            .planTitle("coverage")
            .requirements(List.of(requirement))
            .tasks(List.of(task))
            .build();

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan);

    assertThat(normalized.tasks().getFirst().description()).contains("R8 requires concurrent");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void acceptsRequirementWhoseContractIsSplitAcrossItsOwningTasks() {
    RequirementSpec requirement =
        new RequirementSpec(
            "R4",
            "Adjustments accept positive and negative quantities with a reason and are atomic.",
            RequirementSpec.RequirementKind.FUNCTIONAL);
    PlannedTask schema =
        withRequirements(
            backend(
                "T1",
                "Define adjustment input",
                "AdjustmentInput has quantity and required reason fields; quantity may be positive"
                    + " or negative."),
            "R4");
    PlannedTask service =
        withRequirements(
            backend(
                "T2",
                "Apply adjustments",
                "Apply each adjustment atomically in one database transaction."),
            "R4");
    Plan plan =
        Plan.builder()
            .planTitle("coverage")
            .requirements(List.of(requirement))
            .tasks(List.of(schema, service))
            .build();

    assertThat(PlanContractQualityGate.violations(plan)).isEmpty();
  }

  @Test
  void rejectsFrontendRequestBodyThatDropsNestedProducerIdentityFields() {
    PlannedTask producer =
        task(
            "T5",
            "Implement order API",
            "- `OrderCreate`: `lines` (list of `OrderLineCreate`, min_length=1)\n"
                + "- `OrderLineCreate`: `product_id` (UUID), `warehouse_id` (UUID), `quantity`"
                + " (int)\n"
                + "POST /api/orders accepts OrderCreate.",
            List.of("backend/app/api/orders.py"));
    PlannedTask consumer =
        task(
            "T11",
            "Build order creation screen",
            "Submit POST /api/orders. Request body: `{lines: [{sku: string, quantity: int}]}`.",
            List.of("frontend/src/pages/OrderCreate.tsx"));

    assertThat(PlanContractQualityGate.violations(plan(producer, consumer)))
        .containsExactly(
            "T11: request body for POST /api/orders omits producer fields [product_id,"
                + " warehouse_id] declared by T5");
  }

  @Test
  void acceptsFrontendRequestBodyThatMatchesNestedProducerSchema() {
    PlannedTask producer =
        task(
            "T5",
            "Implement order API",
            "- `OrderCreate`: `lines` (list of `OrderLineCreate`, min_length=1)\n"
                + "- `OrderLineCreate`: `product_id` (UUID), `warehouse_id` (UUID), `quantity`"
                + " (int)\n"
                + "POST /api/orders accepts OrderCreate.",
            List.of("backend/app/api/orders.py"));
    PlannedTask consumer =
        task(
            "T11",
            "Build order creation screen",
            "Submit POST /api/orders. Request body: `{lines: [{product_id: string,"
                + " warehouse_id: string, quantity: int}]}`.",
            List.of("frontend/src/pages/OrderCreate.tsx"));

    assertThat(PlanContractQualityGate.violations(plan(producer, consumer))).isEmpty();
  }

  @Test
  void rejectsDescriptionWorkThatContradictsForeignScopeOwnershipBinding() {
    PlannedTask owner =
        task(
            "T8",
            "Build dashboard",
            "Create the dashboard page.",
            List.of("frontend/src/pages/Dashboard.tsx"));
    PlannedTask task =
        frontend(
            "T13",
            "Add shared UI states",
            "Page integration — update Dashboard.tsx with loading and error states.\n"
                + "SCOPE OWNERSHIP CORRECTION: T8 exclusively owns"
                + " `frontend/src/pages/Dashboard.tsx`. This task must not create or modify that"
                + " artifact.");

    assertThat(PlanContractQualityGate.violations(plan(owner, task)))
        .containsExactly(
            "Task description assigns changes to frontend/src/pages/Dashboard.tsx even though T8"
                + " owns that artifact");
  }

  @Test
  void acceptsReadOnlyReferenceToForeignDatabaseArtifactInCompactedTask() {
    PlannedTask owner =
        task(
            "T1",
            "Configure database",
            "Create the database session.",
            List.of("backend/app/database.py"));
    PlannedTask consumer =
        task(
            "T15",
            "Add seed script",
            "Create seed.py. The function must use the async SQLAlchemy session from"
                + " `backend/app/database.py` and must not redefine the existing models.",
            List.of("backend/app/seed.py"));

    assertThat(PlanContractQualityGate.violations(plan(owner, consumer))).isEmpty();
  }

  @Test
  void acceptsReferenceToExistingAlembicConfigurationInCompactedTask() {
    PlannedTask owner =
        task(
            "T1",
            "Configure Alembic",
            "Create the Alembic environment.",
            List.of("backend/alembic/env.py"));
    PlannedTask consumer =
        task(
            "T2",
            "Add domain migration",
            "Write the migration manually. Use the synchronous SQLite-compatible engine that"
                + " Alembic's `env.py` already configures. Add model constraint tests.",
            List.of("backend/alembic/versions/001_initial_schema.py"));

    assertThat(PlanContractQualityGate.violations(plan(owner, consumer))).isEmpty();
  }

  @Test
  void rejectsJourneyThatSkipsDeclaredLifecycleTransitionAfterCreate() {
    PlannedTask producer =
        backend(
            "T5",
            "Implement order lifecycle",
            "Order has status default DRAFT. Order.status lifecycle values are exactly DRAFT,"
                + " RESERVED, COMMITTED, CANCELLED.");
    PlannedTask journey =
        task(
            "T14",
            "Implement end-to-end order journey",
            "Create an Order through the UI; verify the Order appears with status RESERVED; then"
                + " commit it.",
            List.of("e2e/tests/full-flow.spec.ts"));

    assertThat(PlanContractQualityGate.violations(plan(producer, journey)))
        .containsExactly(
            "T14: journey expects Order status RESERVED immediately after create, but the"
                + " canonical default is DRAFT and no explicit reserve transition is exercised");
  }

  @Test
  void acceptsJourneyThatExercisesDeclaredLifecycleTransition() {
    PlannedTask producer =
        backend(
            "T5",
            "Implement order lifecycle",
            "Order has status default DRAFT. Order.status lifecycle values are exactly DRAFT,"
                + " RESERVED, COMMITTED, CANCELLED.");
    PlannedTask journey =
        task(
            "T14",
            "Implement end-to-end order journey",
            "Create an Order through the UI; then click Reserve and verify the Order status changes"
                + " to RESERVED; then commit it.",
            List.of("e2e/tests/full-flow.spec.ts"));

    assertThat(PlanContractQualityGate.violations(plan(producer, journey))).isEmpty();
  }

  @Test
  void rejectsJourneyThatRequiresMoreRecordsThanItsSetupCreates() {
    PlannedTask journey =
        task(
            "T14",
            "Implement end-to-end checkout journey",
            "Create a product through the UI. Create an order with at least 2 different products.",
            List.of("e2e/tests/full-flow.spec.ts"));

    assertThat(PlanContractQualityGate.violations(plan(journey)))
        .containsExactly(
            "T14: journey requires at least 2 different products but its preceding setup creates"
                + " only 1");
  }

  @Test
  void acceptsJourneyThatPreparesItsRequiredCardinality() {
    PlannedTask journey =
        task(
            "T14",
            "Implement end-to-end checkout journey",
            "Create a product through the UI. Create a second product through the UI. Create an"
                + " order with at least 2 different products.",
            List.of("e2e/tests/full-flow.spec.ts"));

    assertThat(PlanContractQualityGate.violations(plan(journey))).isEmpty();
  }

  private static Plan plan(PlannedTask... tasks) {
    return Plan.builder()
        .planTitle("contract test")
        .projectType("full_stack")
        .runtimeSurface(Plan.RuntimeSurface.HTTP)
        .tasks(List.of(tasks))
        .build();
  }

  private static Plan fastApiFrameworkPlan(
      String requirementText, String backendDescription, String consumerPath) {
    return frameworkPlan(requirementText, backendDescription, consumerPath, "FastAPI backend");
  }

  private static Plan frameworkPlan(
      String requirementText, String backendDescription, String consumerPath, String planSummary) {
    RequirementSpec requirement =
        new RequirementSpec("R1", requirementText, RequirementSpec.RequirementKind.FUNCTIONAL);
    PlannedTask backend =
        withRequirements(
            task(
                "T1",
                planSummary.toLowerCase().contains("fastapi")
                    ? "Scaffold FastAPI application"
                    : "Scaffold Python application",
                backendDescription,
                List.of("backend/app/main.py")),
            "R1");
    PlannedTask consumer =
        task(
            "T7",
            "Verify framework route",
            "Verify GET " + consumerPath + " returns successfully.",
            List.of("backend/tests/test_integration.py"));
    return Plan.builder()
        .planTitle("service")
        .planSummary(planSummary)
        .projectType("python")
        .runtimeSurface(Plan.RuntimeSurface.HTTP)
        .requirements(List.of(requirement))
        .tasks(List.of(backend, consumer))
        .build();
  }

  private static PlannedTask backend(String id, String title, String description) {
    return task(id, title, description, List.of("backend/app/router.py"));
  }

  private static PlannedTask frontend(String id, String title, String description) {
    return task(id, title, description, List.of("frontend/src/api/client.ts"));
  }

  private static PlannedTask task(
      String id, String title, String description, List<String> touchesFiles) {
    return PlannedTask.builder()
        .id(id)
        .title(title)
        .description(description)
        .complexity(PlannedTask.Complexity.MEDIUM)
        .touchesFiles(touchesFiles)
        .hardConstraints(List.of())
        .dependsOn(List.of())
        .meetsRequirements(List.of())
        .taskType(PlannedTask.TaskType.LEAF)
        .build();
  }

  private static PlannedTask withRequirements(PlannedTask task, String... requirementIds) {
    return PlannedTask.builder()
        .id(task.id())
        .title(task.title())
        .description(task.description())
        .complexity(task.complexity())
        .touchesFiles(task.touchesFiles())
        .hardConstraints(task.hardConstraints())
        .dependsOn(task.dependsOn())
        .meetsRequirements(List.of(requirementIds))
        .taskType(task.taskType())
        .build();
  }
}
