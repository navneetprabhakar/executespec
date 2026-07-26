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

  @Test
  void explicitConsolidatedOwnerRemovesLaterNumberedMigrationsDespiteModelReassignment() {
    PlannedTask schema =
        task(
            "T2",
            "Define complete persistence schema",
            "Define all domain models and the complete schema in"
                + " `backend/alembic/versions/001_initial_schema.py`.",
            List.of("backend/app/models.py", "backend/alembic/versions/001_initial_schema.py"),
            List.of(
                "MIGRATION OWNERSHIP (binding): this task owns the single consolidated migration;"
                    + " sibling tasks must not own numbered revisions."));
    PlannedTask product =
        task(
            "T4",
            "Implement product domain",
            "Create Product in its exclusive model artifact and generate revision 002.",
            List.of(
                "backend/app/models/product.py", "backend/alembic/versions/002_product_schema.py"),
            List.of(
                "SCOPE OWNERSHIP CORRECTION: T4 exclusively owns"
                    + " `backend/app/models/product.py`."));
    PlannedTask inventory =
        task(
            "T5",
            "Implement inventory domain",
            "Create Inventory and a reversible numbered migration.",
            List.of(
                "backend/app/models/inventory.py",
                "backend/alembic/versions/003_inventory_schema.py"),
            List.of());
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Use Alembic with one consolidated reversible initial migration.",
                        RequirementSpec.RequirementKind.FUNCTIONAL)))
            .tasks(List.of(schema, product, inventory))
            .build();

    Plan reconciled = PlanMigrationOwnershipReconciler.reconcile(plan);

    assertThat(reconciled.tasks().getFirst().touchesFiles())
        .contains("backend/alembic/versions/001_initial_schema.py");
    assertThat(reconciled.tasks().get(1).touchesFiles())
        .doesNotContain("backend/alembic/versions/002_product_schema.py");
    assertThat(reconciled.tasks().get(2).touchesFiles())
        .doesNotContain("backend/alembic/versions/003_inventory_schema.py");
    assertThat(reconciled.tasks().subList(1, 3))
        .allMatch(
            task ->
                task.hardConstraints().stream()
                    .anyMatch(value -> value.contains("must not create, generate, or own")));
  }

  @Test
  void migrationDirectoryMetadataCannotBecomeTheConsolidatedRevision() {
    PlannedTask schema =
        task(
            "T2",
            "Define complete persistence schema",
            "Define all domain models and own the single consolidated Alembic migration.",
            List.of("backend/app/models.py", "backend/alembic/versions/.gitkeep"),
            List.of(
                "MIGRATION OWNERSHIP (binding): this task is the sole migration owner for the"
                    + " complete schema."));
    PlannedTask product =
        task(
            "T4",
            "Implement product domain",
            "Create Product and revision 002.",
            List.of("backend/app/models/product.py", "backend/alembic/versions/002_product.py"),
            List.of());
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Use one consolidated Alembic migration for the complete schema.",
                        RequirementSpec.RequirementKind.FUNCTIONAL)))
            .tasks(List.of(schema, product))
            .build();

    Plan reconciled = PlanMigrationOwnershipReconciler.reconcile(plan);

    assertThat(reconciled.tasks().getFirst().touchesFiles())
        .contains("backend/alembic/versions/001_initial_schema.py")
        .doesNotContain("backend/alembic/versions/.gitkeep");
    assertThat(reconciled.tasks().getFirst().description())
        .contains("backend/alembic/versions/001_initial_schema.py")
        .doesNotContain(".gitkeep", "002_product.py");
    assertThat(reconciled.tasks().get(1).touchesFiles())
        .doesNotContain("backend/alembic/versions/002_product.py");
    assertThat(PlanSafetyMigrationSupport.isVersionMigrationPath("alembic/versions/.gitkeep"))
        .isFalse();
    assertThat(PlanSafetyMigrationSupport.isVersionMigrationPath("alembic/versions/__init__.py"))
        .isFalse();
  }

  @Test
  void serviceLayerDeclarationCannotAcquireGroupedModelPaths() {
    PlannedTask persistence =
        task(
            "T2",
            "Create persistence schema",
            "Define the database model artifacts.",
            List.of(
                "backend/app/models/warehouse.py",
                "backend/app/models/product.py",
                "backend/app/models/stock.py"),
            List.of());
    PlannedTask service =
        task(
            "T4",
            "Implement warehouse product and stock services",
            "Paths backend/app/models/{warehouse,product,stock}.py are service-layer files used"
                + " for orchestration.",
            List.of("backend/app/services/catalog.py"),
            List.of());
    Plan plan = Plan.builder().requirements(List.of()).tasks(List.of(persistence, service)).build();

    Plan reconciled = PlanScopeOwnershipReconciler.reconcile(plan);

    assertThat(reconciled.tasks().getFirst().touchesFiles())
        .containsExactly(
            "backend/app/models/warehouse.py",
            "backend/app/models/product.py",
            "backend/app/models/stock.py");
    assertThat(reconciled.tasks().get(1).touchesFiles())
        .containsExactly("backend/app/services/catalog.py");
    assertThat(reconciled.tasks().get(1).hardConstraints())
        .noneMatch(value -> value.contains("SCOPE OWNER CONTRACT"));
  }

  @Test
  void modelPathClassifiedAsServiceLayerFailsClosedEvenWithOwnerBinding() {
    PlannedTask contradictory =
        task(
            "T4",
            "Implement warehouse product and stock services",
            "Paths backend/app/models/{warehouse,product,stock}.py are service-layer files used"
                + " for orchestration.",
            List.of("backend/app/models/warehouse.py"),
            List.of(
                "SCOPE OWNER CONTRACT: this task creates, tests, and maintains"
                    + " `backend/app/models/warehouse.py`; no sibling task owns that artifact."));
    Plan plan = Plan.builder().requirements(List.of()).tasks(List.of(contradictory)).build();
    java.util.ArrayList<String> violations = new java.util.ArrayList<>();

    PlanScopeOwnershipReconciler.addViolations(plan, violations);

    assertThat(violations)
        .containsExactly(
            "Task classifies model artifact backend/app/models/warehouse.py as a service-layer"
                + " file");
  }

  @Test
  void modelCreationListCannotKeepClaimingArtifactsReassignedToSiblingOwner() {
    PlannedTask staleSchemaTask =
        task(
            "T2",
            "Implement initial domain schema",
            "Models to create (each in its own file): **Warehouse** (`warehouse.py`), **Product**"
                + " (`product.py`), and **Stock** (`stock.py`).",
            List.of("backend/app/models/__init__.py", "backend/alembic/versions/001.py"),
            List.of());
    PlannedTask concreteOwner =
        task(
            "T3",
            "Implement warehouse product and stock persistence",
            "Create the concrete persistence models.",
            List.of(
                "backend/app/models/warehouse.py",
                "backend/app/models/product.py",
                "backend/app/models/stock.py"),
            List.of());
    java.util.ArrayList<String> violations = new java.util.ArrayList<>();

    PlanScopeOwnershipReconciler.addViolations(
        Plan.builder().tasks(List.of(staleSchemaTask, concreteOwner)).build(), violations);

    assertThat(violations)
        .contains(
            "Task description assigns changes to backend/app/models/warehouse.py even though T3"
                + " owns that artifact",
            "Task description assigns changes to backend/app/models/product.py even though T3"
                + " owns that artifact",
            "Task description assigns changes to backend/app/models/stock.py even though T3 owns"
                + " that artifact");
  }

  @Test
  void modelFocusedDependencyRetainsExplicitArtifactFromDownstreamServiceTask() {
    PlannedTask modelTask =
        task(
            "T2",
            "Implement SQLAlchemy domain models and migration",
            "Models to create: Product (`product.py`).",
            List.of("backend/app/models/__init__.py", "backend/alembic/versions/001.py"),
            List.of());
    PlannedTask serviceTask =
        task(
            "T3",
            "Implement product services and API",
            "Create product CRUD services and consume the established Product model.",
            List.of("backend/app/models/product.py", "backend/app/api/products.py"),
            List.of(
                "SCOPE OWNER CONTRACT: this task creates, tests, and maintains"
                    + " `backend/app/models/product.py`; no sibling task owns that artifact."),
            List.of("T2"));

    Plan reconciled =
        PlanScopeOwnershipReconciler.reconcile(
            Plan.builder().tasks(List.of(modelTask, serviceTask)).build());

    assertThat(reconciled.tasks().get(0).touchesFiles()).contains("backend/app/models/product.py");
    assertThat(reconciled.tasks().get(0).hardConstraints())
        .anyMatch(
            value ->
                value.contains("SCOPE OWNER CONTRACT")
                    && value.contains("backend/app/models/product.py"));
    assertThat(reconciled.tasks().get(1).touchesFiles())
        .containsExactly("backend/app/api/products.py");
    assertThat(reconciled.tasks().get(1).hardConstraints())
        .noneMatch(value -> value.contains("backend/app/models/product.py"));
  }

  @Test
  void modelFocusedPrerequisiteWinsGloballyAndRemovesStaleGeneratedSiblingCorrection() {
    PlannedTask modelTask =
        task(
            "T2",
            "Implement database models migrations and seed data",
            "Models to create in `backend/app/models/__init__.py` (single file, all models): "
                + "canonical Warehouse schema (owned by T3 in"
                + " `backend/app/models/warehouse.py`) — id and code.\n\n"
                + "SCOPE OWNERSHIP CORRECTION (binding and superseding earlier"
                + " placeholder/create instructions): T3 exclusively owns"
                + " `backend/app/models/warehouse.py` and the warehouse implementation. This"
                + " task must not create or modify that artifact.",
            List.of(
                "backend/app/models/__init__.py",
                "backend/app/models/warehouse.py",
                "backend/alembic/versions/001_initial.py"),
            List.of(
                "SCOPE OWNERSHIP CORRECTION (binding and superseding earlier"
                    + " placeholder/create instructions): T3 exclusively owns"
                    + " `backend/app/models/warehouse.py` and the warehouse implementation. This"
                    + " task must not create or modify that artifact."));
    PlannedTask apiTask =
        task(
            "T3",
            "Implement warehouse CRUD services and API routes",
            "Reuse the warehouse model from T2; do not redefine it.",
            List.of("backend/app/models/warehouse.py", "backend/app/routers/warehouses.py"),
            List.of(
                "SCOPE OWNER CONTRACT: this task creates, tests, and maintains"
                    + " `backend/app/models/warehouse.py`; no sibling task owns that artifact."),
            List.of("T2"));

    Plan reconciled =
        PlanScopeOwnershipReconciler.reconcile(
            Plan.builder().tasks(List.of(modelTask, apiTask)).build());
    PlannedTask owner = reconciled.tasks().getFirst();
    PlannedTask consumer = reconciled.tasks().get(1);

    assertThat(owner.touchesFiles()).contains("backend/app/models/warehouse.py");
    assertThat(owner.description())
        .contains("**Warehouse** (`backend/app/models/warehouse.py`)")
        .contains("Models to define and export through")
        .doesNotContain("T3 exclusively owns", "single file, all models");
    assertThat(owner.hardConstraints())
        .anyMatch(
            value ->
                value.contains("SCOPE OWNER CONTRACT")
                    && value.contains("backend/app/models/warehouse.py"))
        .noneMatch(value -> value.contains("T3 exclusively owns"));
    assertThat(consumer.touchesFiles()).containsExactly("backend/app/routers/warehouses.py");
    assertThat(consumer.hardConstraints())
        .noneMatch(value -> value.contains("backend/app/models/warehouse.py"));
  }

  @Test
  void modelFocusedPrerequisiteRemovesStaleModelOwnerAndDownstreamWrapperInstructions() {
    String modelPath = "backend/app/models/warehouse.py";
    PlannedTask modelTask =
        task(
            "T2",
            "Implement database models migrations and seed data",
            "The models go in `backend/app/models/__init__.py` (single file, importing from"
                + " sibling-owned model files). **Warehouse** (`"
                + modelPath
                + "`) — imported from `"
                + modelPath
                + "` (owned by T3).",
            List.of("backend/app/models/__init__.py", modelPath),
            List.of(
                "MODEL OWNER CONTRACT (binding): T3 exclusively defines and implements Warehouse"
                    + " in `"
                    + modelPath
                    + "`; this task consumes that canonical schema and must not create or redefine"
                    + " its model artifact."));
    PlannedTask apiTask =
        task(
            "T3",
            "Implement warehouse listing API",
            "This task owns the model file `"
                + modelPath
                + "` — create it as a re-export or thin wrapper. Implement the warehouse router.",
            List.of(modelPath, "backend/app/routers/warehouses.py"),
            List.of(),
            List.of("T2"));

    Plan normalized =
        TaskExpansionPlanNormalizer.normalize(
            Plan.builder().tasks(List.of(modelTask, apiTask)).requirements(List.of()).build());
    PlannedTask owner = normalized.tasks().getFirst();
    PlannedTask consumer = normalized.tasks().get(1);

    assertThat(owner.touchesFiles()).contains(modelPath);
    assertThat(owner.description())
        .contains("define in this canonical model artifact")
        .doesNotContain("owned by T3", "sibling-owned model files");
    assertThat(owner.hardConstraints())
        .noneMatch(value -> value.startsWith("MODEL OWNER CONTRACT") && value.contains("T3"));
    assertThat(consumer.touchesFiles()).doesNotContain(modelPath);
    assertThat(consumer.description()).doesNotContain("This task owns the model file");
    assertThat(PlanContractQualityGate.violations(normalized))
        .noneMatch(value -> value.contains("Task scope overreach") && value.contains(modelPath));
  }

  @Test
  void exactModelAndPageArtifactsCannotHaveMultipleTaskOwners() {
    PlannedTask schema =
        task(
            "T2",
            "Define product schema",
            "Create the Product model.",
            List.of("backend/app/models/product.py"),
            List.of());
    PlannedTask api =
        task(
            "T4",
            "Implement product API",
            "Update the Product model and expose its API.",
            List.of("backend/app/models/product.py", "backend/app/routers/products.py"),
            List.of());
    PlannedTask inventoryPage =
        task(
            "T8",
            "Build inventory page",
            "Create the inventory page.",
            List.of("frontend/src/pages/Inventory.tsx"),
            List.of());
    PlannedTask stockPage =
        task(
            "T9",
            "Add stock behavior to inventory page",
            "Update Inventory.tsx with stock behavior.",
            List.of("frontend/src/pages/Inventory.tsx"),
            List.of());
    java.util.ArrayList<String> violations = new java.util.ArrayList<>();

    PlanScopeOwnershipReconciler.addViolations(
        Plan.builder().tasks(List.of(schema, api, inventoryPage, stockPage)).build(), violations);

    assertThat(violations)
        .contains(
            "Exclusive implementation artifact backend/app/models/product.py is assigned to"
                + " multiple tasks [T2, T4]",
            "Exclusive implementation artifact frontend/src/pages/inventory.tsx is assigned to"
                + " multiple tasks [T8, T9]");
  }

  @Test
  void explicitlyOrderedPageCompositionIsNotCompetingOwnership() {
    PlannedTask page =
        task(
            "T11",
            "Build inventory page",
            "Create InventoryPage and its stock table.",
            List.of("frontend/src/pages/InventoryPage.tsx"),
            List.of());
    PlannedTask dialogs =
        task(
            "T12",
            "Build adjustment and transfer dialogs",
            "Mount dialogs from InventoryPage (owned by T11) and integrate with its selector.",
            List.of(
                "frontend/src/pages/InventoryPage.tsx",
                "frontend/src/components/AdjustmentDialog.tsx"),
            List.of(),
            List.of("T11"));
    java.util.ArrayList<String> violations = new java.util.ArrayList<>();

    PlanScopeOwnershipReconciler.addViolations(
        Plan.builder().tasks(List.of(page, dialogs)).build(), violations);

    assertThat(violations)
        .noneMatch(
            violation ->
                violation.contains("frontend/src/pages/inventorypage.tsx")
                    && violation.contains("multiple tasks"));
  }

  @Test
  void pageCompositionWithoutDependencyOrExactOwnerRemainsFailClosed() {
    PlannedTask page =
        task(
            "T11",
            "Build inventory page",
            "Create InventoryPage and its stock table.",
            List.of("frontend/src/pages/InventoryPage.tsx"),
            List.of());
    PlannedTask missingDependency =
        task(
            "T12",
            "Build dialogs",
            "Mount dialogs from InventoryPage (owned by T11).",
            List.of("frontend/src/pages/InventoryPage.tsx"),
            List.of());
    PlannedTask wrongOwner =
        task(
            "T13",
            "Build order panel",
            "Mount the order panel from InventoryPage (owned by T9).",
            List.of("frontend/src/pages/InventoryPage.tsx"),
            List.of(),
            List.of("T12"));
    java.util.ArrayList<String> violations = new java.util.ArrayList<>();

    PlanScopeOwnershipReconciler.addViolations(
        Plan.builder().tasks(List.of(page, missingDependency, wrongOwner)).build(), violations);

    assertThat(violations)
        .containsExactly(
            "Exclusive implementation artifact frontend/src/pages/inventorypage.tsx is assigned"
                + " to multiple tasks [T11, T12, T13]");
  }

  @Test
  void orderedOwnershipClauseNeverRelaxesConcreteModelOwnership() {
    PlannedTask modelOwner =
        task(
            "T2",
            "Build product model",
            "Create Product.",
            List.of("backend/app/models/product.py"),
            List.of());
    PlannedTask api =
        task(
            "T3",
            "Build product API",
            "Extend Product (owned by T2) while exposing the API.",
            List.of("backend/app/models/product.py"),
            List.of(),
            List.of("T2"));
    java.util.ArrayList<String> violations = new java.util.ArrayList<>();

    PlanScopeOwnershipReconciler.addViolations(
        Plan.builder().tasks(List.of(modelOwner, api)).build(), violations);

    assertThat(violations)
        .containsExactly(
            "Exclusive implementation artifact backend/app/models/product.py is assigned to"
                + " multiple tasks [T2, T3]");
  }

  @Test
  void sharedModelPackageRegistryDoesNotCreateFalseConcreteOwnershipConflict() {
    PlannedTask scaffold =
        task(
            "T1",
            "Scaffold backend persistence",
            "Create the model package registry and SQLAlchemy base.",
            List.of("backend/app/models/__init__.py", "backend/app/models/base.py"),
            List.of());
    PlannedTask catalog =
        task(
            "T2",
            "Implement catalog models",
            "Create Warehouse and Product and export them from the model registry.",
            List.of(
                "backend/app/models/__init__.py",
                "backend/app/models/warehouse.py",
                "backend/app/models/product.py"),
            List.of());
    PlannedTask reservations =
        task(
            "T7",
            "Implement reservation models",
            "Create Reservation and ReservationLine and export them from the model registry.",
            List.of(
                "backend/app/models/__init__.py",
                "backend/app/models/reservation.py",
                "backend/app/models/reservation_line.py"),
            List.of());
    java.util.ArrayList<String> violations = new java.util.ArrayList<>();

    PlanScopeOwnershipReconciler.addViolations(
        Plan.builder().tasks(List.of(scaffold, catalog, reservations)).build(), violations);

    assertThat(violations)
        .noneMatch(
            violation ->
                violation.contains("backend/app/models/__init__.py")
                    && violation.contains("multiple tasks [T1, T2, T7]"));
  }

  @Test
  void sharedCrossStackModelBarrelsAreMetadataButPageIndexRemainsConcrete() {
    PlannedTask first =
        task(
            "T1",
            "Create model registries and application page",
            "Export the first model and create the application index page.",
            List.of(
                "server/models/index.ts",
                "worker/entities/barrel.js",
                "frontend/src/pages/index.tsx"),
            List.of());
    PlannedTask second =
        task(
            "T2",
            "Extend model registries and application page",
            "Export the second model and update the application index page.",
            List.of(
                "server/models/index.ts",
                "worker/entities/barrel.js",
                "frontend/src/pages/index.tsx"),
            List.of());
    java.util.ArrayList<String> violations = new java.util.ArrayList<>();

    PlanScopeOwnershipReconciler.addViolations(
        Plan.builder().tasks(List.of(first, second)).build(), violations);

    assertThat(violations)
        .containsExactly(
            "Exclusive implementation artifact frontend/src/pages/index.tsx is assigned to"
                + " multiple tasks [T1, T2]");
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
