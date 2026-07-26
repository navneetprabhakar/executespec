package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PlanPersistenceFieldUsageGateTest {

  @Test
  void rejectsSqlUpdateFieldsAbsentFromCanonicalCrossTaskStockTable() {
    PlannedTask schema =
        task(
            "T2",
            "Define stock schema",
            "Stock table: id, product_id, warehouse_id, quantity, updated_at.",
            "backend/app/models/stock.py");
    PlannedTask service =
        task(
            "T5",
            "Implement guarded stock adjustment",
            "Run UPDATE stock SET on_hand = on_hand + :delta, reserved = reserved WHERE id ="
                + " :stock_id.",
            "backend/app/services/stock.py");
    ArrayList<String> violations = new ArrayList<>();

    PlanPersistenceFieldUsageGate.addViolations(
        Plan.builder().tasks(List.of(schema, service)).build(), violations);

    assertThat(violations)
        .containsExactly(
            "T5: SQL UPDATE of stock assigns undeclared fields [on_hand, reserved]; canonical"
                + " table contract in T2 declares [id, product_id, warehouse_id, quantity,"
                + " updated_at]");
  }

  @Test
  void acceptsSqlUpdateUsingCanonicalCrossTaskStockFields() {
    PlannedTask schema =
        task(
            "T2",
            "Define stock schema",
            "Stock table: id, product_id, warehouse_id, quantity, updated_at.",
            "backend/app/models/stock.py");
    PlannedTask service =
        task(
            "T5",
            "Implement guarded stock adjustment",
            "Run UPDATE stock SET quantity = quantity + :delta, updated_at = :now WHERE id ="
                + " :stock_id.",
            "backend/app/services/stock.py");
    ArrayList<String> violations = new ArrayList<>();

    PlanPersistenceFieldUsageGate.addViolations(
        Plan.builder().tasks(List.of(schema, service)).build(), violations);

    assertThat(violations).isEmpty();
  }

  @Test
  void compactMarkdownSchemaStillGuardsDownstreamSqlFields() {
    PlannedTask schema =
        task(
            "T2",
            "Define models",
            "**Models:** - **Warehouse** — `id`, `code`, `name`. - **Stock**"
                + " (`server/models/stock.py`) — `id`, `warehouse_id`, `product_id`, `quantity`,"
                + " `updated_at`.",
            "server/models/stock.py");
    PlannedTask service =
        task(
            "T4",
            "Adjust stock",
            "Execute UPDATE stock SET on_hand = on_hand + :delta WHERE id = :stock_id.",
            "server/services/adjustment.py");
    ArrayList<String> violations = new ArrayList<>();

    PlanPersistenceFieldUsageGate.addViolations(
        Plan.builder().tasks(List.of(schema, service)).build(), violations);

    assertThat(violations)
        .containsExactly(
            "T4: SQL UPDATE of stock assigns undeclared fields [on_hand]; canonical table"
                + " contract in T2 declares [id, warehouse_id, product_id, quantity, updated_at]");
  }

  @Test
  void rejectsNearDuplicatePersistenceModelsUnderDifferentNames() {
    PlannedTask canonical =
        task(
            "T2",
            "Define audit persistence",
            "AuditEvent table: id, operation, reference_id, quantity_delta, created_at.",
            "backend/app/models/audit_event.py");
    PlannedTask duplicate =
        task(
            "T5",
            "Implement inventory audit writes",
            "AuditLog model: id, operation, reference_id, quantity_delta, created_at.",
            "backend/app/models/audit_log.py");
    ArrayList<String> violations = new ArrayList<>();

    PlanPersistenceFieldUsageGate.addViolations(
        Plan.builder().tasks(List.of(canonical, duplicate)).build(), violations);

    assertThat(violations)
        .containsExactly(
            "T5: persistence contracts AuditEvent and AuditLog are near-duplicate aliases across"
                + " tasks T2 and T5 with shared fields [id, operation, reference_id,"
                + " quantity_delta, created_at]; choose one canonical aggregate or declare"
                + " distinct table ownership");
  }

  private static PlannedTask task(String id, String title, String description, String file) {
    return PlannedTask.builder()
        .id(id)
        .title(title)
        .description(description)
        .touchesFiles(List.of(file))
        .hardConstraints(List.of())
        .dependsOn(List.of())
        .meetsRequirements(List.of())
        .build();
  }
}
