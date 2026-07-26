package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PlanScopeMigrationOwnershipClosureTest {

  @Test
  void r14InventoryReassignmentMovesConcreteModelAndAddsOwnerMigration() {
    PlannedTask scaffold =
        task(
            "T1",
            "Scaffold project and Alembic configuration",
            "Configure the Alembic runtime and database setup.",
            List.of("backend/alembic/env.py"),
            List.of(
                "MIGRATION OWNERSHIP CORRECTION (binding): domain tasks own versioned"
                    + " migrations."));
    PlannedTask catalog =
        task(
            "T2",
            "Create warehouse and product domain models with Alembic migration",
            "Create Warehouse, Product, and Inventory models. `inventory.py` defines Inventory."
                + " Migration `backend/alembic/versions/001_warehouses_products_inventory.py`"
                + " creates all three tables.",
            List.of(
                "backend/app/models/warehouse.py",
                "backend/app/models/product.py",
                "backend/alembic/versions/001_warehouses_products_inventory.py"),
            List.of(
                "SCOPE OWNERSHIP CORRECTION (binding and superseding earlier"
                    + " placeholder/create instructions): T5 exclusively owns"
                    + " `backend/app/models/inventory.py` and the inventory implementation. This"
                    + " task must not create or modify that artifact."));
    PlannedTask inventory =
        task(
            "T5",
            "Implement inventory, stock adjustment, and atomic transfer endpoints",
            "Implement the Inventory service and API. Migrations run through Alembic.",
            List.of(
                "backend/app/schemas/inventory.py",
                "backend/app/routers/inventory.py",
                "backend/app/services/inventory_service.py"),
            List.of(),
            List.of("T2"));
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R20",
                        "Alembic must migrate the exact database used by default startup.",
                        RequirementSpec.RequirementKind.FUNCTIONAL)))
            .tasks(List.of(scaffold, catalog, inventory))
            .build();

    Plan reconciled = PlanMigrationOwnershipReconciler.reconcile(plan);
    PlannedTask reconciledCatalog = reconciled.tasks().get(1);
    PlannedTask reconciledInventory = reconciled.tasks().get(2);

    assertThat(reconciledCatalog.touchesFiles()).doesNotContain("backend/app/models/inventory.py");
    assertThat(reconciledInventory.touchesFiles())
        .contains("backend/app/models/inventory.py")
        .anyMatch(path -> path.matches("backend/alembic/versions/\\d{3,}_inventory_schema\\.py"));
    assertThat(reconciledInventory.hardConstraints())
        .anyMatch(
            value ->
                value.contains("SCOPE OWNER CONTRACT")
                    && value.contains("backend/app/models/inventory.py"))
        .anyMatch(
            value ->
                value.contains("MIGRATION SCOPE CONTRACT")
                    && value.contains("backend/app/models/inventory.py"));
    assertThat(reconciledInventory.dependsOn()).contains("T2");
  }

  private static PlannedTask task(
      String id,
      String title,
      String description,
      List<String> touchesFiles,
      List<String> constraints) {
    return task(id, title, description, touchesFiles, constraints, List.of());
  }

  private static PlannedTask task(
      String id,
      String title,
      String description,
      List<String> touchesFiles,
      List<String> constraints,
      List<String> dependsOn) {
    return PlannedTask.builder()
        .id(id)
        .title(title)
        .description(description)
        .touchesFiles(touchesFiles)
        .hardConstraints(constraints)
        .dependsOn(dependsOn)
        .meetsRequirements(List.of())
        .build();
  }
}
