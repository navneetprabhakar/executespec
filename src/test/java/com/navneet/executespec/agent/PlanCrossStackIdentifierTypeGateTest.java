package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PlanCrossStackIdentifierTypeGateTest {

  @Test
  void rejectsR14StyleUuidResponsesAndNumericSharedInterfaceExamples() {
    PlannedTask backend =
        task(
            "T4",
            "Define response contracts",
            "Use WarehouseResponse(id: UUID, code: str) and ProductResponse(id: UUID, sku: str).",
            List.of("backend/app/schemas.py"));
    PlannedTask frontend =
        task(
            "T10",
            "Define frontend contracts",
            "Define shared TypeScript interfaces for `Warehouse`, `Product`, `Inventory`, and"
                + " `Order`. Use exact field names/types from the API response schemas (e.g."
                + " `id: number`, `warehouse_id: number`).",
            List.of("frontend/src/types.ts"));

    assertThat(violations(backend, frontend))
        .contains(
            "T10: frontend Warehouse.id uses numeric identifier type but backend T4 declares UUID"
                + " in WarehouseResponse.id",
            "T10: frontend Product.id uses numeric identifier type but backend T4 declares UUID in"
                + " ProductResponse.id");
  }

  @Test
  void comparesIdentifiersOnlyWithinTheSameEntityAndField() {
    PlannedTask backend =
        task(
            "T1",
            "Warehouse response",
            "class WarehouseResponse(BaseModel):\n    id: UUID\n    code: str",
            List.of("backend/schemas.py"));
    PlannedTask frontend =
        task(
            "T2",
            "Product interface",
            "export interface Product { id: number; name: string }",
            List.of("frontend/types.ts"));

    assertThat(violations(backend, frontend)).isEmpty();
  }

  @Test
  void allowsUuidSerializedAsAFrontendString() {
    PlannedTask backend =
        task(
            "T1",
            "Warehouse response",
            "record WarehouseResponse(UUID id, String code)",
            List.of("backend/WarehouseResponse.java"));
    PlannedTask frontend =
        task(
            "T2",
            "Warehouse interface",
            "export interface Warehouse { id: string; code: string }",
            List.of("frontend/types.ts"));

    assertThat(violations(backend, frontend)).isEmpty();
  }

  @Test
  void supportsJavaRecordsPythonModelsAndTypescriptAliases() {
    PlannedTask javaBackend =
        task(
            "T1",
            "Warehouse response",
            "record WarehouseResponse(UUID id, String code)",
            List.of("backend/WarehouseResponse.java"));
    PlannedTask pythonBackend =
        task(
            "T2",
            "Product response",
            "class ProductResponse(BaseModel):\n    id: UUID\n    warehouse_id: UUID",
            List.of("backend/schemas.py"));
    PlannedTask frontend =
        task(
            "T3",
            "Shared frontend models",
            "export type Warehouse = { id: number; code: string };\n"
                + "export interface Product { id: number; warehouse_id: number }",
            List.of("frontend/types.ts"));

    assertThat(violations(javaBackend, pythonBackend, frontend))
        .contains(
            "T3: frontend Warehouse.id uses numeric identifier type but backend T1 declares UUID"
                + " in WarehouseResponse.id",
            "T3: frontend Product.id uses numeric identifier type but backend T2 declares UUID in"
                + " ProductResponse.id",
            "T3: frontend Product.warehouse_id uses numeric identifier type but backend T2"
                + " declares UUID in ProductResponse.warehouse_id");
  }

  @Test
  void acceptsNumericIdentifiersOnBothSides() {
    PlannedTask backend =
        task(
            "T1",
            "Customer response",
            "record CustomerResponse(Long id, String name)",
            List.of("backend/CustomerResponse.java"));
    PlannedTask frontend =
        task(
            "T2",
            "Customer interface",
            "interface Customer { id: number; name: string }",
            List.of("frontend/types.ts"));

    assertThat(violations(backend, frontend)).isEmpty();
  }

  private static List<String> violations(PlannedTask... tasks) {
    List<String> violations = new ArrayList<>();
    PlanCrossStackIdentifierTypeGate.addViolations(plan(tasks), violations);
    return violations;
  }

  private static Plan plan(PlannedTask... tasks) {
    return Plan.builder()
        .planTitle("identifier contracts")
        .projectType("full_stack")
        .runtimeSurface(Plan.RuntimeSurface.HTTP)
        .tasks(List.of(tasks))
        .build();
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
}
