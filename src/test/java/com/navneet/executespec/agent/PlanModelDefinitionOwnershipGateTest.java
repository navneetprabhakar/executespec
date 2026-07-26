package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PlanModelDefinitionOwnershipGateTest {

  @Test
  void compactMarkdownModelInventoryCannotDefineSiblingOwnedModels() {
    PlannedTask schema =
        task(
            "T2",
            "**Models:** - **Warehouse** (`server/models/warehouse.py`) — `id`, `code`, `name`."
                + " - **StockAdjustment** — `id`, `stock_id`, `delta`, `reason`.",
            "server/models/warehouse.py");
    PlannedTask adjustment =
        task(
            "T4",
            "Create **StockAdjustment** — `id`, `warehouse_id`, `quantity`, `reason`.",
            "server/models/adjustment.py");
    List<String> violations = new ArrayList<>();

    PlanModelDefinitionOwnershipGate.addViolations(plan(schema, adjustment), violations);

    assertThat(violations)
        .containsExactly(
            "T2: description defines model StockAdjustment even though T4 uniquely owns"
                + " server/models/adjustment.py");
  }

  @Test
  void owningTaskMayDefineItsModelAndPlainReferencesAreIgnored() {
    PlannedTask owner =
        task(
            "T4",
            "Create **StockAdjustment** — `id`, `warehouse_id`, `quantity`, `reason`.",
            "server/models/adjustment.py");
    PlannedTask consumer =
        task(
            "T7",
            "Return StockAdjustment records from the service.",
            "server/routes/adjustments.py");
    List<String> violations = new ArrayList<>();

    PlanModelDefinitionOwnershipGate.addViolations(plan(owner, consumer), violations);

    assertThat(violations).isEmpty();
  }

  @Test
  void contradictoryManualSchemaInstructionsFailClosed() {
    PlannedTask task =
        task(
            "T5",
            "Create the real test schema with `Base.metadata.create_all`.",
            "server/tests/test_transfer.py");
    task =
        copyWithConstraints(
            task, "Fixtures must apply migrations and must not call ORM create_all.");
    List<String> violations = new ArrayList<>();

    PlanDatabaseInitializationContractGate.addViolations(plan(task), violations);

    assertThat(violations).singleElement().asString().contains("both requires and forbids");
  }

  @Test
  void modelLocalLifecycleCannotContradictCanonicalEntityLifecycle() {
    PlannedTask schema =
        copyWithConstraints(
            task(
                "T6",
                "Models:\n- `server/models/reservation.py` — `Reservation` with `id` and"
                    + " `status`. Status lifecycle: PENDING, RELEASED.",
                "server/models/reservation.py"),
            "Reservation.status lifecycle is exactly ACTIVE and RELEASED.");
    List<String> violations = new ArrayList<>();

    PlanLifecycleValueContractGate.addViolations(List.of(schema), violations);

    assertThat(violations)
        .containsExactly(
            "T6: reservation model lifecycle values [PENDING, RELEASED] contradict canonical"
                + " values [ACTIVE, RELEASED]");
  }

  @Test
  void explicitCrossTaskSymbolReferenceRequiresTheNamedProducer() {
    PlannedTask producer =
        task("T4", "Create AdjustmentRepository.", "server/repositories/adjustment_repository.py");
    PlannedTask consumer =
        task(
            "T5",
            "Persist the event through AuditRepository (from T4).",
            "server/services/transfer_service.py");
    List<String> violations = new ArrayList<>();

    PlanCrossTaskSymbolReferenceGate.addViolations(plan(producer, consumer), violations);

    assertThat(violations)
        .containsExactly(
            "T5: references AuditRepository from T4, but that task does not declare or own the"
                + " symbol");
  }

  @Test
  void compactedOrDanglingWorkerInstructionsFailClosed() {
    PlannedTask compacted =
        task(
            "T8",
            "Implement tests.\n[Contract-preserving compaction: verbose implementation detail"
                + " omitted.]",
            "tests/service.test.ts");
    PlannedTask dangling =
        task("T9", "1. Start server.\n2. Run request.\n3.", "tests/http.test.ts");
    List<String> violations = new ArrayList<>();

    PlanWorkerBriefIntegrityGate.addViolations(plan(compacted, dangling), violations);

    assertThat(violations)
        .anyMatch(value -> value.startsWith("T8:") && value.contains("omission marker"))
        .anyMatch(value -> value.startsWith("T9:") && value.contains("empty numbered step"));
  }

  private static PlannedTask task(String id, String description, String file) {
    return PlannedTask.builder()
        .id(id)
        .title("Implement " + id)
        .description(description)
        .complexity(PlannedTask.Complexity.MEDIUM)
        .touchesFiles(List.of(file))
        .dependsOn(List.of())
        .hardConstraints(List.of())
        .meetsRequirements(List.of())
        .taskType(PlannedTask.TaskType.LEAF)
        .build();
  }

  private static PlannedTask copyWithConstraints(PlannedTask task, String constraint) {
    return PlannedTask.builder()
        .id(task.id())
        .title(task.title())
        .description(task.description())
        .complexity(task.complexity())
        .touchesFiles(task.touchesFiles())
        .dependsOn(task.dependsOn())
        .hardConstraints(List.of(constraint))
        .meetsRequirements(task.meetsRequirements())
        .taskType(task.taskType())
        .build();
  }

  private static Plan plan(PlannedTask... tasks) {
    return Plan.builder().tasks(List.of(tasks)).requirements(List.of()).build();
  }
}
