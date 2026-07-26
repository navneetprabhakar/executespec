package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PlanApiProducerAuthorityReconciliationTest {

  @Test
  void rewritesBareLifecycleAliasInConstraintWhenDescriptionAlreadyConsumesUniqueProducer() {
    PlannedTask producer =
        task(
            "T6",
            "Implement order lifecycle router",
            "Expose POST /api/orders/{order_id}/cancel.",
            List.of("backend/app/routers/orders.py"),
            List.of());
    PlannedTask tests =
        task(
            "T13",
            "Write HTTP integration tests",
            "Cancel through POST /api/orders/{id}/cancel and verify stock is restored once.",
            List.of("backend/tests/test_orders.py"),
            List.of(
                "Use these exact public API paths: /api/orders/{id}/release; do not invent"
                    + " aliases."));

    Plan normalized = TaskExpansionPlanNormalizer.normalize(plan(producer, tests));

    assertThat(normalized.tasks().get(1).description())
        .contains("POST /api/orders/{order_id}/cancel")
        .doesNotContain("/release");
    assertThat(normalized.tasks().get(1).hardConstraints())
        .anyMatch(value -> value.contains("/api/orders/{order_id}/cancel"))
        .noneMatch(value -> value.contains("/release"));
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void preservesBareLifecycleAliasWhenProducerOwnershipIsAmbiguous() {
    PlannedTask first =
        task(
            "T5",
            "Implement lifecycle router",
            "Expose POST /api/orders/{id}/cancel.",
            List.of("backend/app/routers/orders.py"),
            List.of());
    PlannedTask second =
        task(
            "T6",
            "Implement admin lifecycle router",
            "Expose POST /api/orders/{id}/cancel.",
            List.of("backend/app/routers/admin_orders.py"),
            List.of());
    PlannedTask tests =
        task(
            "T13",
            "Write integration tests",
            "Call POST /api/orders/{id}/cancel.",
            List.of("backend/tests/test_orders.py"),
            List.of("Use /api/orders/{id}/release."));

    Plan normalized = TaskExpansionPlanNormalizer.normalize(plan(first, second, tests));

    assertThat(normalized.tasks().get(2).hardConstraints())
        .anyMatch(value -> value.contains("/api/orders/{id}/release"));
  }

  @Test
  void releaseOnlyConsumerDoesNotCollapseOntoDistinctCancelAction() {
    PlannedTask producer =
        task(
            "T6",
            "Implement cancel action",
            "Expose POST /api/orders/{id}/cancel.",
            List.of("backend/app/routers/orders.py"),
            List.of());
    PlannedTask consumer =
        task(
            "T11",
            "Build release client",
            "Call POST /api/orders/{id}/release.",
            List.of("frontend/src/api/orders.ts"),
            List.of("Use POST /api/orders/{id}/release."));

    Plan normalized = TaskExpansionPlanNormalizer.normalize(plan(producer, consumer));

    assertThat(normalized.tasks().get(1).description()).contains("/release");
    assertThat(PlanContractQualityGate.violations(normalized))
        .anyMatch(
            value ->
                value.contains("POST /api/orders/{id}/release")
                    && value.contains("no backend task declares that operation"));
  }

  @Test
  void producerNamedQueryListReplacesStaleGeneratedBindingAndConsumerAlias() {
    PlannedTask producer =
        task(
            "T3",
            "Implement product routes",
            "Expose GET /api/products. Query parameters: q, page, and page_size.",
            List.of("backend/app/routers/products.py"),
            List.of());
    PlannedTask client =
        task(
            "T11",
            "Build product client",
            "getProducts(params?: { search?, page?, page_size? }) -> GET"
                + " /api/products?search=x&page=1&page_size=20.",
            List.of("frontend/src/api/products.ts"),
            List.of(
                "API contract binding: T3 is authoritative for GET /api/products; use query"
                    + " parameters [search]; do not invent aliases."));

    Plan normalized = TaskExpansionPlanNormalizer.normalize(plan(producer, client));
    PlannedTask normalizedClient = normalized.tasks().get(1);

    assertThat(normalizedClient.description())
        .contains("q?: string", "page?: number", "page_size?: number")
        .contains("GET /api/products?page=1&page_size=20")
        .doesNotContain("search?");
    assertThat(normalizedClient.hardConstraints())
        .anyMatch(
            value ->
                value.contains("T3 is authoritative for GET /api/products")
                    && value.contains("q")
                    && value.contains("page")
                    && value.contains("page_size"))
        .noneMatch(value -> value.contains("query parameters [search]"));
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  private static PlannedTask task(
      String id, String title, String description, List<String> files, List<String> constraints) {
    return PlannedTask.builder()
        .id(id)
        .title(title)
        .description(description)
        .touchesFiles(files)
        .hardConstraints(constraints)
        .dependsOn(List.of())
        .meetsRequirements(List.of())
        .build();
  }

  private static Plan plan(PlannedTask... tasks) {
    return Plan.builder().requirements(List.of()).tasks(List.of(tasks)).build();
  }
}
