package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PlanExecutableContractReconcilerTest {

  @Test
  void closesR15RuntimeE2eAndPlaceholderContractsWithoutProviderRepair() {
    PlannedTask scaffold =
        task(
            "T1",
            "Scaffold backend",
            "Dependencies: fastapi, uvicorn, sqlalchemy. Configure"
                + " sqlite+aiosqlite:///./app.db.",
            List.of("backend/pyproject.toml"));
    PlannedTask frontendScaffold =
        task(
            "T2",
            "Scaffold React frontend",
            "Define placeholder route stubs for `/`, `/inventory`, `/orders`, `/orders/:id`, and"
                + " `/orders/new`.",
            List.of("frontend/package.json", "frontend/src/App.tsx"));
    PlannedTask serverTests =
        task(
            "T6",
            "Backend functional tests",
            "Use subprocess to launch uvicorn app.main:app and then issue real HTTP requests.",
            List.of("backend/tests/test_functional.py"));
    PlannedTask inventory =
        task(
            "T9",
            "Build inventory page",
            "Implement the final inventory page.",
            List.of("frontend/src/pages/Inventory.tsx"));
    PlannedTask firstE2e =
        task(
            "T13",
            "Browser end-to-end inventory journey",
            "Use Playwright for the browser journey.",
            List.of("frontend/e2e/inventory.spec.ts"));
    PlannedTask orders =
        task(
            "T12",
            "Build order detail and create screens",
            "Implement final OrderDetail and OrderCreate pages.",
            List.of("frontend/src/pages/OrderDetail.tsx", "frontend/src/pages/OrderCreate.tsx"));
    PlannedTask secondE2e =
        task(
            "T14",
            "Browser end-to-end order journey",
            "Run Playwright end-to-end tests.",
            List.of("frontend/e2e/orders.spec.ts"));

    PlanExecutableContractReconciler.Result result =
        PlanExecutableContractReconciler.reconcile(
            plan(scaffold, frontendScaffold, serverTests, inventory, orders, firstE2e, secondE2e));

    assertThat(PlanContractQualityGate.violations(result.plan())).isEmpty();
    assertThat(result.repairsApplied()).isEqualTo(6);
    assertThat(task(result.plan(), "T1").description()).contains("aiosqlite");
    assertThat(task(result.plan(), "T6").description())
        .contains("bounded 15-second startup timeout");
    assertThat(task(result.plan(), "T13").description())
        .contains(
            "@playwright/test", "test:e2e", "documented backend and frontend commands", "teardown");
    assertThat(task(result.plan(), "T14").description())
        .contains(
            "@playwright/test", "test:e2e", "documented backend and frontend commands", "teardown");
    assertThat(task(result.plan(), "T9").touchesFiles()).contains("frontend/src/App.tsx");
    assertThat(task(result.plan(), "T12").touchesFiles()).contains("frontend/src/App.tsx");
  }

  @Test
  void keepsAlreadyExecutableContractsIdempotent() {
    PlannedTask scaffold =
        task(
            "T1",
            "Scaffold",
            "Dependencies include aiosqlite. Runtime uses sqlite+aiosqlite:///./app.db.",
            List.of("backend/pyproject.toml"));
    PlannedTask tests =
        task(
            "T2",
            "Functional tests",
            "Launch uvicorn as a subprocess, poll the health endpoint until ready with a bounded"
                + " startup timeout, and issue HTTP requests.",
            List.of("backend/tests/test_functional.py"));
    Plan plan = plan(scaffold, tests);

    PlanExecutableContractReconciler.Result first =
        PlanExecutableContractReconciler.reconcile(plan);
    PlanExecutableContractReconciler.Result second =
        PlanExecutableContractReconciler.reconcile(first.plan());

    assertThat(first.repairsApplied()).isZero();
    assertThat(second.repairsApplied()).isZero();
    assertThat(second.plan()).isSameAs(plan);
  }

  @Test
  void springReactE2eClosureUsesDocumentedCommandsWithoutPythonOrViteAssumptions() {
    PlannedTask backend =
        task(
            "T1",
            "Build Spring Boot backend",
            "Run the backend with `./mvnw spring-boot:run`.",
            List.of("server/pom.xml"));
    PlannedTask frontend =
        task(
            "T2",
            "Build React frontend",
            "Run the frontend with `npm start`.",
            List.of("web/package.json", "web/src/App.tsx"));
    PlannedTask e2e =
        task(
            "T9",
            "Browser end-to-end tests",
            "Use Playwright for the browser journey.",
            List.of("e2e/package.json", "e2e/playwright.config.ts", "e2e/full-flow.spec.ts"));

    Plan normalizedPlan =
        PlanExecutableContractReconciler.reconcile(plan(backend, frontend, e2e)).plan();
    PlannedTask normalized = task(normalizedPlan, "T9");

    assertThat(normalized.description())
        .contains("@playwright/test", "documented backend and frontend commands")
        .contains("fresh isolated test state")
        .doesNotContain("uvicorn", "Vite", "DATABASE_URL", "Alembic");
    assertThat(PlanContractQualityGate.violations(normalizedPlan)).isEmpty();
  }

  @Test
  void expressNextE2eClosureDoesNotInventPythonDatabaseTooling() {
    PlannedTask backend =
        task(
            "T1",
            "Build Express API",
            "Run the backend with `npm run server`.",
            List.of("server/package.json", "server/src/index.ts"));
    PlannedTask frontend =
        task(
            "T2",
            "Build Next.js frontend",
            "Run the frontend with `npm run dev`.",
            List.of("web/package.json", "web/next.config.js"));
    PlannedTask e2e =
        task(
            "T9",
            "Browser end-to-end tests",
            "Use Playwright for the browser journey.",
            List.of("e2e/package.json", "e2e/playwright.config.ts", "e2e/full-flow.spec.ts"));

    Plan normalizedPlan =
        PlanExecutableContractReconciler.reconcile(plan(backend, frontend, e2e)).plan();
    PlannedTask normalized = task(normalizedPlan, "T9");

    assertThat(normalized.description())
        .contains("documented backend and frontend commands", "fresh isolated test state")
        .doesNotContain("uvicorn", "Vite", "DATABASE_URL", "Alembic");
    assertThat(PlanContractQualityGate.violations(normalizedPlan)).isEmpty();
  }

  @Test
  void fastApiViteE2eClosureRetainsConcreteRuntimeContract() {
    PlannedTask backend =
        task(
            "T1",
            "Build FastAPI backend",
            "Use FastAPI and uvicorn with Alembic migrations.",
            List.of("backend/pyproject.toml", "backend/alembic/env.py"));
    PlannedTask frontend =
        task(
            "T2",
            "Build Vite frontend",
            "Run Vite from the frontend package.",
            List.of("frontend/package.json", "frontend/vite.config.ts"));
    PlannedTask e2e =
        task(
            "T9",
            "Browser end-to-end tests",
            "Use Playwright for the browser journey.",
            List.of("e2e/package.json", "e2e/playwright.config.ts", "e2e/full-flow.spec.ts"));

    Plan normalizedPlan =
        PlanExecutableContractReconciler.reconcile(plan(backend, frontend, e2e)).plan();
    PlannedTask normalized = task(normalizedPlan, "T9");

    assertThat(normalized.description()).contains("uvicorn", "Vite", "DATABASE_URL");
    assertThat(PlanContractQualityGate.violations(normalizedPlan)).isEmpty();
  }

  private static PlannedTask task(Plan plan, String id) {
    return plan.tasks().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
  }

  private static Plan plan(PlannedTask... tasks) {
    return Plan.builder()
        .planTitle("r15 executable closure")
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
        .complexity(PlannedTask.Complexity.MEDIUM)
        .touchesFiles(touchesFiles)
        .dependsOn(List.of())
        .hardConstraints(List.of())
        .meetsRequirements(List.of())
        .taskType(PlannedTask.TaskType.LEAF)
        .build();
  }
}
