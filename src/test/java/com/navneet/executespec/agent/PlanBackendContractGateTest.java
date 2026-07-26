package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PlanBackendContractGateTest {

  @Test
  void rejectsR13LifecycleAndPythonModelNamespaceConflicts() {
    PlannedTask models =
        task(
            "T2",
            "Define SQLAlchemy models",
            "Define persisted Order.state with values DRAFT, RESERVED, COMMITTED, CANCELLED."
                + " Order.status lifecycle values are exactly DRAFT, RESERVED, COMMITTED,"
                + " CANCELLED.",
            List.of("backend/app/models/__init__.py"));
    PlannedTask orderService =
        task(
            "T5",
            "Implement order lifecycle",
            "Order.status starts DRAFT.",
            List.of("backend/app/models.py", "backend/app/models/__init__.py"));

    assertThat(PlanContractQualityGate.violations(plan(models, orderService)))
        .anyMatch(value -> value.contains("lifecycle alternates") && value.contains("status"))
        .anyMatch(
            value ->
                value.contains("backend/app/models.py")
                    && value.contains("backend/app/models/")
                    && value.contains("collides"));
  }

  @Test
  void rejectsR13AuditFieldTypeShapeAndEnumAliasDrift() {
    PlannedTask writer =
        task(
            "T4",
            "Write audit events",
            "warehouse_id (UUID). write_audit_event(operation, warehouse_id, sku,"
                + " quantity_delta, reference_type, reference_id). Audit operation values are"
                + " exactly ADJUSTMENT, TRANSFER, RESERVE, CANCEL, COMMIT.",
            List.of("backend/app/services/audit_service.py"));
    PlannedTask reader =
        task(
            "T6",
            "Read audit events",
            "Audit columns: warehouse_id (int), quantity_delta_on_hand,"
                + " quantity_delta_reserved, idempotency_key.",
            List.of("backend/app/routers/audit.py"));
    PlannedTask acceptance =
        task(
            "T8",
            "Verify audit invariants",
            "Verify the operation strings (ADJUST, TRANSFER, RESERVE, CANCEL, COMMIT).",
            List.of("backend/tests/test_integration.py"));

    assertThat(PlanContractQualityGate.violations(plan(writer, reader, acceptance)))
        .anyMatch(
            value ->
                value.contains("warehouse_id")
                    && value.contains("conflicting declared types")
                    && value.contains("uuid")
                    && value.contains("int"))
        .anyMatch(
            value ->
                value.contains("quantity_delta")
                    && value.contains("quantity_delta_on_hand")
                    && value.contains("quantity_delta_reserved"))
        .anyMatch(value -> value.contains("aliases [ADJUST]") && value.contains("ADJUSTMENT"));
  }

  @Test
  void rejectsR13AsyncFixtureWithoutPluginOrReadinessProbe() {
    PlannedTask scaffold =
        task(
            "T1",
            "Scaffold Python dependencies",
            "Declare pytest and httpx as development dependencies.",
            List.of("backend/pyproject.toml", "backend/requirements.txt"));
    PlannedTask fixtures =
        task(
            "T2",
            "Create database fixtures",
            "Provide an async fixture for AsyncSession.",
            List.of("backend/tests/conftest.py"));
    PlannedTask integration =
        task(
            "T8",
            "Run functional tests",
            "Use subprocess to launch uvicorn app.main:app on a random port and then create an"
                + " HTTP client.",
            List.of("backend/tests/test_integration.py"));

    assertThat(PlanContractQualityGate.violations(plan(scaffold, fixtures, integration)))
        .contains(
            "plan: async pytest fixtures are required but dependency-owning tasks do not declare"
                + " pytest-asyncio",
            "T8: real-server functional fixture launches uvicorn without a bounded readiness"
                + " probe");
  }

  @Test
  void acceptsAsyncFixtureWithPluginAndBoundedReadinessProbe() {
    PlannedTask scaffold =
        task(
            "T1",
            "Scaffold Python dependencies",
            "Declare pytest, pytest-asyncio, and httpx as development dependencies.",
            List.of("backend/pyproject.toml"));
    PlannedTask integration =
        task(
            "T8",
            "Run functional tests",
            "Use subprocess to launch uvicorn app.main:app, poll GET /api/health until ready with a"
                + " 10 second startup timeout, then create the HTTP client.",
            List.of("backend/tests/test_integration.py"));
    PlannedTask health =
        task(
            "T7",
            "Implement health endpoint",
            "GET /api/health returns readiness.",
            List.of("backend/app/routers/health.py"));

    assertThat(PlanContractQualityGate.violations(plan(scaffold, health, integration))).isEmpty();
  }

  @Test
  void rejectsAsyncSqliteUrlWhenDependencyListOmitsAiosqlite() {
    PlannedTask scaffold =
        task(
            "T1",
            "Scaffold Python dependencies",
            "Dependencies: fastapi, uvicorn, sqlalchemy, alembic, pydantic. Configure"
                + " DATABASE_URL default sqlite+aiosqlite:///./app.db and use aiosqlite for the"
                + " async engine.",
            List.of("backend/pyproject.toml", "backend/requirements.txt"));

    assertThat(PlanContractQualityGate.violations(plan(scaffold)))
        .contains(
            "plan: runtime database URL sqlite+aiosqlite requires dependency-owning tasks to"
                + " declare aiosqlite");
  }

  @Test
  void acceptsAsyncSqliteUrlWhenDependencyListDeclaresAiosqlite() {
    PlannedTask scaffold =
        task(
            "T1",
            "Scaffold Python dependencies",
            "Dependencies: fastapi, uvicorn, sqlalchemy, alembic, pydantic, aiosqlite. Configure"
                + " DATABASE_URL default sqlite+aiosqlite:///./app.db.",
            List.of("backend/pyproject.toml", "backend/requirements.txt"));

    assertThat(PlanContractQualityGate.violations(plan(scaffold))).isEmpty();
  }

  @Test
  void rejectsR13ExactPathBindingThatOmitsCommitAction() {
    PlannedTask orders =
        task(
            "T5",
            "Implement order lifecycle",
            "POST /api/orders/{order_id}/reserve. POST /api/orders/{order_id}/commit. POST"
                + " /api/orders/{order_id}/cancel.",
            List.of("backend/app/routers/orders.py"),
            List.of(
                "Use these exact public API paths for the operations in this task:"
                    + " /api/orders, /api/orders/{order_id}/reserve,"
                    + " /api/orders/{order_id}/cancel."));

    assertThat(PlanContractQualityGate.violations(plan(orders)))
        .contains(
            "T5: exact public API path binding omits declared action path"
                + " /api/orders/{order_id}/commit");
  }

  private static Plan plan(PlannedTask... tasks) {
    return Plan.builder()
        .planTitle("r13 contract regression")
        .projectType("full_stack")
        .runtimeSurface(Plan.RuntimeSurface.HTTP)
        .tasks(List.of(tasks))
        .build();
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
