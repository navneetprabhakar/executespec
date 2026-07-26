package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PlanApiQueryScopingRegressionTest {

  @Test
  void repeatedSameRouteHelpersKeepTheirOwnParameterBlocks() {
    PlannedTask producer =
        backend(
            "T1",
            "Implement warehouse list",
            "GET /api/warehouses accepts ?page and ?page_size.",
            List.of("R1"));
    PlannedTask consumer =
        frontend(
            "T2",
            "Build two warehouse readers",
            "getPrimary(params?: { legacy?, page? }) -> GET /api/warehouses.\n"
                + "getSecondary(params?: { legacy?, page_size? }) -> GET /api/warehouses.",
            List.of("R1"));

    Plan normalized =
        TaskExpanderAgent.normalizeFinalPlan(
            plan(
                List.of(producer, consumer),
                new RequirementSpec(
                    "R1",
                    "The warehouse list supports page and page_size pagination.",
                    RequirementSpec.RequirementKind.FUNCTIONAL)));

    String description = normalized.tasks().get(1).description();
    assertThat(description).doesNotContain("legacy");
    assertThat(description)
        .contains("getPrimary(params?: {")
        .contains("getSecondary(params?: {")
        .contains("page?: number", "page_size?: number");
    assertThat(count(description, "page?: number")).isEqualTo(2);
    assertThat(count(description, "page_size?: number")).isEqualTo(2);
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void globalListRequirementCannotPromoteFiltersOntoMutationOrItemDetail() {
    PlannedTask producer =
        backend(
            "T1",
            "Implement inventory and order APIs",
            "GET /api/inventory. POST /api/inventory/transfer. GET /api/orders/{order_id}.",
            List.of("R1"));
    PlannedTask consumer =
        frontend(
            "T2",
            "Build inventory and order clients",
            "getInventory(params?: { search?, warehouse_id? }) -> GET /api/inventory.\n"
                + "transferStock(params?: { search?, warehouse_id? }) -> POST"
                + " /api/inventory/transfer.\n"
                + "getOrder(params?: { search?, status? }) -> GET /api/orders/{id}.",
            List.of("R1"));

    Plan normalized =
        TaskExpanderAgent.normalizeFinalPlan(
            plan(
                List.of(producer, consumer),
                new RequirementSpec(
                    "R1",
                    "List endpoints support search, status and warehouse filters with pagination.",
                    RequirementSpec.RequirementKind.FUNCTIONAL)));

    String producerDescription = normalized.tasks().getFirst().description();
    assertThat(producerDescription)
        .contains("API producer contract binding: GET /api/inventory?")
        .contains("search=", "warehouse_id=")
        .doesNotContain("POST /api/inventory/transfer?")
        .doesNotContain("GET /api/orders/{id}?");
    assertThat(normalized.tasks().get(1).hardConstraints())
        .anyMatch(
            value ->
                value.contains("POST /api/inventory/transfer")
                    && value.contains("query parameters []"))
        .anyMatch(
            value ->
                value.contains("GET /api/orders/{order_id}")
                    && value.contains("query parameters []"));
  }

  @Test
  void healthRouterPrefixStopsBeforeSeparateOpenApiDocsSection() {
    PlannedTask task =
        backend(
            "T7",
            "Add health and documentation endpoints",
            """
            **Health endpoint (backend/app/routers/health.py):**
            Use router prefix `/api`.
            - GET /health returns readiness status.

            **OpenAPI/docs endpoints:**
            - GET /docs serves Swagger UI.
            - GET /openapi.json serves the schema.
            """,
            List.of("R1"));

    List<PlanApiContractEvidence.ApiEvidence> evidence = PlanApiContractEvidence.extract(task);

    assertThat(evidence)
        .extracting(PlanApiContractEvidence.ApiEvidence::rawPath)
        .contains("/health", "/docs", "/openapi.json")
        .doesNotContain("/api/docs", "/api/openapi.json");
  }

  @Test
  void warehouseSectionPrefixDoesNotLeakIntoFollowingHealthSection() {
    PlannedTask task =
        backend(
            "T1",
            "Add warehouse and health routes",
            """
            **Warehouse routes:**
            Use router prefix `/api/warehouses`.
            - GET / lists warehouses.
            - POST / creates a warehouse.

            ## Health checks
            - GET /health returns readiness status.
            """,
            List.of("R1"));

    List<PlanApiContractEvidence.ApiEvidence> evidence = PlanApiContractEvidence.extract(task);

    assertThat(evidence)
        .extracting(PlanApiContractEvidence.ApiEvidence::rawPath)
        .contains("/api/warehouses", "/health")
        .doesNotContain("/api/warehouses/health");
  }

  private static int count(String value, String needle) {
    return (value.length() - value.replace(needle, "").length()) / needle.length();
  }

  private static Plan plan(List<PlannedTask> tasks, RequirementSpec... requirements) {
    return Plan.builder()
        .planTitle("query scoping")
        .projectType("full_stack")
        .runtimeSurface(Plan.RuntimeSurface.HTTP)
        .requirements(List.of(requirements))
        .tasks(tasks)
        .build();
  }

  private static PlannedTask backend(
      String id, String title, String description, List<String> requirements) {
    return task(id, title, description, List.of("backend/app/routers/contracts.py"), requirements);
  }

  private static PlannedTask frontend(
      String id, String title, String description, List<String> requirements) {
    return task(id, title, description, List.of("frontend/src/api/client.ts"), requirements);
  }

  private static PlannedTask task(
      String id,
      String title,
      String description,
      List<String> touchesFiles,
      List<String> requirements) {
    return PlannedTask.builder()
        .id(id)
        .title(title)
        .description(description)
        .complexity(PlannedTask.Complexity.MEDIUM)
        .touchesFiles(touchesFiles)
        .dependsOn(List.of())
        .hardConstraints(List.of())
        .meetsRequirements(requirements)
        .taskType(PlannedTask.TaskType.LEAF)
        .build();
  }
}
