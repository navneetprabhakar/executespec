package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PlanR16DeterministicClosureTest {

  @Test
  void closesR16ChoicesActionsOwnershipReadinessAndMissingItemProducer() {
    Plan original = r16Plan();

    assertThat(PlanContractQualityGate.violations(original))
        .anyMatch(value -> value.contains("T7") && value.contains("implementation choice"))
        .anyMatch(value -> value.contains("T8") && value.contains("bounded readiness"))
        .anyMatch(value -> value.contains("T6") && value.contains("/commit"))
        .anyMatch(value -> value.contains("T8") && value.contains("/commit"))
        .anyMatch(value -> value.contains("Warehouses.tsx") && value.contains("T10 owns"))
        .anyMatch(value -> value.contains("Products.tsx") && value.contains("T11 owns"))
        .anyMatch(value -> value.contains("Inventory.tsx") && value.contains("T12 owns"))
        .anyMatch(value -> value.contains("Dashboard.tsx") && value.contains("T9 owns"))
        .anyMatch(value -> value.contains("T12") && value.contains("GET /api/orders/{id}"));

    PlanExecutableContractReconciler.Result closed =
        PlanExecutableContractReconciler.reconcile(original);
    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(closed.plan());

    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
    assertThat(task(normalized, "T7").description())
        .contains("Use httpx.AsyncClient against the ASGI application")
        .doesNotContain("pick one");
    assertThat(task(normalized, "T8").description()).contains("bounded 15-second startup timeout");
    assertThat(task(normalized, "T6").hardConstraints().getFirst())
        .contains("/api/orders/{order_id}/commit");
    assertThat(task(normalized, "T8").hardConstraints().getFirst())
        .contains("/api/orders/{order_id}/commit");
    assertThat(task(normalized, "T9").description()).doesNotContain("Create Warehouses.tsx");
    assertThat(task(normalized, "T10").description()).doesNotContain("Create Products.tsx");
    assertThat(task(normalized, "T11").description()).doesNotContain("Create Inventory.tsx");
    assertThat(task(normalized, "T12").description()).doesNotContain("Create Dashboard.tsx");
    assertThat(task(normalized, "T6").description())
        .contains("API PRODUCER CONTRACTS", "GET /api/orders/{id}");
  }

  @Test
  void r16ClosureIsIdempotent() {
    Plan once =
        TaskExpanderAgent.normalizeFinalPlan(
            PlanExecutableContractReconciler.reconcile(r16Plan()).plan());
    PlanExecutableContractReconciler.Result second =
        PlanExecutableContractReconciler.reconcile(once);
    Plan twice = TaskExpanderAgent.normalizeFinalPlan(second.plan());

    assertThat(second.repairsApplied()).isZero();
    assertThat(twice).isEqualTo(once);
  }

  private static Plan r16Plan() {
    PlannedTask scaffold =
        task(
            "T1",
            "Scaffold async backend",
            "Dependencies include aiosqlite. Configure sqlite+aiosqlite:///./app.db with"
                + " AsyncSession.",
            List.of("backend/pyproject.toml"));
    PlannedTask orderBackend =
        task(
            "T6",
            "Implement order API",
            "GET /api/orders. POST /api/orders/{order_id}/reserve. POST"
                + " /api/orders/{order_id}/cancel. POST /api/orders/{order_id}/commit.",
            List.of("backend/app/main.py"),
            List.of(
                "Use these exact public API paths for the operations in this task:"
                    + " /api/orders, /api/orders/{order_id}/reserve,"
                    + " /api/orders/{order_id}/cancel."));
    PlannedTask health =
        task(
            "T7",
            "Test health and error handling",
            "Use httpx.AsyncClient or TestClient for the ASGI integration tests; pick one and stay"
                + " consistent.",
            List.of("backend/tests/test_health.py"));
    PlannedTask functional =
        task(
            "T8",
            "Run real-server functional tests",
            "Use subprocess to launch uvicorn app.main:app, then test POST"
                + " /api/orders/{order_id}/reserve, POST /api/orders/{order_id}/cancel, and POST"
                + " /api/orders/{order_id}/commit over HTTP.",
            List.of("backend/tests/test_functional.py"),
            List.of(
                "Use these exact public API paths for the operations in this task:"
                    + " /api/orders/{order_id}/reserve, /api/orders/{order_id}/cancel."));
    PlannedTask dashboard =
        task(
            "T9",
            "Build dashboard page",
            "Implement Dashboard.tsx.\nCreate Warehouses.tsx with warehouse CRUD.",
            List.of("frontend/src/pages/Dashboard.tsx"));
    PlannedTask warehouses =
        task(
            "T10",
            "Build warehouses page",
            "Implement Warehouses.tsx.\nCreate Products.tsx with product CRUD.",
            List.of("frontend/src/pages/Warehouses.tsx"));
    PlannedTask products =
        task(
            "T11",
            "Build products page",
            "Implement Products.tsx.\nCreate Inventory.tsx with stock operations.",
            List.of("frontend/src/pages/Products.tsx"));
    PlannedTask inventoryOrders =
        task(
            "T12",
            "Build inventory and orders pages",
            "Implement Inventory.tsx and Orders.tsx. Load an order through GET"
                + " /api/orders/{id}.\nCreate Dashboard.tsx with summary cards.",
            List.of(
                "frontend/src/pages/Inventory.tsx",
                "frontend/src/pages/Orders.tsx",
                "frontend/src/pages/OrderDetail.tsx",
                "frontend/src/pages/OrderCreate.tsx"));
    return Plan.builder()
        .planTitle("r16 deterministic closure")
        .projectType("full_stack")
        .runtimeSurface(Plan.RuntimeSurface.HTTP)
        .tasks(
            List.of(
                scaffold,
                orderBackend,
                health,
                functional,
                dashboard,
                warehouses,
                products,
                inventoryOrders))
        .build();
  }

  private static PlannedTask task(Plan plan, String id) {
    return plan.tasks().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
  }

  private static PlannedTask task(
      String id, String title, String description, List<String> touchesFiles) {
    return task(id, title, description, touchesFiles, List.of());
  }

  private static PlannedTask task(
      String id,
      String title,
      String description,
      List<String> touchesFiles,
      List<String> hardConstraints) {
    return PlannedTask.builder()
        .id(id)
        .title(title)
        .description(description)
        .complexity(PlannedTask.Complexity.MEDIUM)
        .touchesFiles(touchesFiles)
        .dependsOn(List.of())
        .hardConstraints(hardConstraints)
        .meetsRequirements(List.of())
        .taskType(PlannedTask.TaskType.LEAF)
        .build();
  }
}
