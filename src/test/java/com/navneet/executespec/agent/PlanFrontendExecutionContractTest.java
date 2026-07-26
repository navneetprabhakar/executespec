package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PlanFrontendExecutionContractTest {

  @Test
  void rejectsR13StyleMixedPythonAndNodePlaywrightRunner() {
    PlannedTask e2e =
        task(
            "T16",
            "End-to-end tests",
            "Create pytest Playwright tests in test_full_flow.py. Configure the Playwright"
                + " webServer array from conftest.py for backend and frontend processes.",
            List.of(
                "e2e/tests/test_full_flow.py",
                "e2e/conftest.py",
                "e2e/package.json",
                "e2e/playwright.config.ts"));

    assertThat(PlanContractQualityGate.violations(plan(e2e)))
        .contains(
            "T16: Playwright E2E task mixes Python pytest/.py runner artifacts with Node"
                + " package/config artifacts; select one runner with matching dependencies and"
                + " process lifecycle",
            "T16: Python conftest.py cannot own Playwright's Node webServer configuration; use a"
                + " Python subprocess fixture or a Node Playwright config, not both");
  }

  @Test
  void rejectsNodeWebServerConfigurationAssignedToPythonConftest() {
    PlannedTask e2e =
        task(
            "T16",
            "Python browser tests",
            "Use pytest-playwright. In conftest.py configure the Playwright webServer process for"
                + " uvicorn and tear it down after the suite.",
            List.of("e2e/tests/test_full_flow.py", "e2e/conftest.py"));

    assertThat(PlanContractQualityGate.violations(plan(e2e)))
        .contains(
            "T16: Python conftest.py cannot own Playwright's Node webServer configuration; use a"
                + " Python subprocess fixture or a Node Playwright config, not both");
  }

  @Test
  void acceptsOneExecutablePythonPlaywrightContract() {
    PlannedTask e2e =
        task(
            "T16",
            "Python browser tests",
            "Declare pytest-playwright and run `python -m pytest e2e/tests`. Python session"
                + " fixtures launch uvicorn and Vite with subprocess.Popen, wait for readiness"
                + " with a bounded 15-second timeout against a fresh isolated DATABASE_URL, and"
                + " terminate both processes after pytest.",
            List.of("e2e/tests/test_full_flow.py", "e2e/conftest.py", "backend/pyproject.toml"));

    assertThat(PlanContractQualityGate.violations(plan(e2e))).isEmpty();
  }

  @Test
  void acceptsOneExecutableNodePlaywrightContract() {
    PlannedTask e2e =
        task(
            "T16",
            "Node browser tests",
            "Declare @playwright/test in package.json and a test:e2e script that runs playwright"
                + " test. playwright.config.ts owns webServer commands that launch uvicorn and"
                + " Vite, wait for health readiness against a fresh isolated DATABASE_URL, and"
                + " teardown both processes.",
            List.of("e2e/package.json", "e2e/playwright.config.ts", "e2e/tests/full-flow.spec.ts"));

    assertThat(PlanContractQualityGate.violations(plan(e2e))).isEmpty();
  }

  @Test
  void doesNotTreatBackendPytestSetupOwnedByNodeE2eScaffoldAsPythonPlaywright() {
    PlannedTask scaffold =
        task(
            "T1",
            "Scaffold project dependencies and test harnesses",
            "Configure backend pytest fixtures in backend/tests/conftest.py. Separately declare"
                + " @playwright/test in e2e/package.json and a test:e2e script that runs"
                + " playwright test. playwright.config.ts owns webServer commands that launch"
                + " uvicorn and Vite, wait for health readiness against a fresh isolated"
                + " DATABASE_URL, and teardown both processes.",
            List.of(
                "backend/tests/conftest.py",
                "backend/tests/test_health.py",
                "e2e/package.json",
                "e2e/playwright.config.ts",
                "e2e/tests/full-flow.spec.ts"));

    assertThat(PlanContractQualityGate.violations(plan(scaffold))).isEmpty();
  }

  @Test
  void rejectsPlaywrightE2eWithoutDependencyAndProcessLifecycleContract() {
    PlannedTask e2e =
        task(
            "T16",
            "Browser end-to-end tests",
            "Use Playwright page interactions for the full customer journey.",
            List.of("e2e/tests/full-flow.spec.ts"));

    assertThat(PlanContractQualityGate.violations(plan(e2e)))
        .containsExactly(
            "T16: Playwright E2E task lacks executable contract parts [runner, readiness probe,"
                + " isolated test state, teardown]; declare one runner, dependency, script,"
                + " isolated environment, readiness, process lifecycle, and teardown");
  }

  @Test
  void rejectsR14PlaceholderExecutionTasksEvenAfterSafetyInjection() {
    PlannedTask integration =
        task(
            "T9",
            "Backend integration tests",
            "(no description)",
            List.of("backend/tests/test_concurrency.py"));
    PlannedTask orders =
        task(
            "T14",
            "Build order screens",
            "(no description)",
            List.of("frontend/src/pages/Orders.tsx"));
    PlannedTask e2e =
        task(
            "T16",
            "End-to-end tests",
            "BROWSER JOURNEY CONTRACT (binding): use Playwright page interactions.\n\n"
                + "(no description)",
            List.of("e2e/package.json", "e2e/playwright.config.ts", "e2e/tests/full-flow.spec.ts"));

    assertThat(PlanContractQualityGate.violations(plan(integration, orders, e2e)))
        .contains(
            "T9: executable task has no expanded description; title, requirement IDs, file names,"
                + " and injected safety constraints are not sufficient worker instructions",
            "T14: executable task has no expanded description; title, requirement IDs, file names,"
                + " and injected safety constraints are not sufficient worker instructions",
            "T16: executable task has no expanded description; title, requirement IDs, file names,"
                + " and injected safety constraints are not sufficient worker instructions");
  }

  @Test
  void rejectsR14NodeE2eWithoutDependencyScriptLifecycleReadinessAndTeardown() {
    PlannedTask e2e =
        PlannedTask.builder()
            .id("T16")
            .title("End-to-end tests, seed data script, and final README")
            .description(
                "BROWSER JOURNEY CONTRACT (binding): drive journeys through Playwright.\n\n"
                    + "(no description)")
            .touchesFiles(
                List.of(
                    "e2e/package.json", "e2e/playwright.config.ts", "e2e/tests/full-flow.spec.ts"))
            .hardConstraints(
                List.of(
                    "The Playwright backend webServer command must run alembic upgrade head"
                        + " against the isolated test DATABASE_URL before starting uvicorn."))
            .build();

    assertThat(PlanContractQualityGate.violations(plan(e2e)))
        .contains(
            "T16: Playwright E2E task lacks executable contract parts [dependency, runner script,"
                + " backend/frontend process lifecycle, readiness probe, isolated test state,"
                + " teardown]; declare one"
                + " runner, dependency, script, isolated environment, readiness, process"
                + " lifecycle, and teardown");
  }

  @Test
  void makesFrontendCallSitesRelativeToSharedApiBaseWithoutChangingProducerContract() {
    PlannedTask producer =
        task(
            "T1",
            "Implement orders API",
            "Backend producer exposes GET /api/orders and POST /api/orders.",
            List.of("backend/app/routers/orders.py"));
    PlannedTask shell =
        task(
            "T2",
            "Build frontend API client",
            "Create Axios with baseURL: '/api'.",
            List.of("frontend/src/api/client.ts"));
    PlannedTask consumer =
        PlannedTask.builder()
            .id("T3")
            .title("Build orders screen")
            .description(
                "Call GET /api/orders and POST /api/orders through the shared client."
                    + " Implement apiClient.get('/api/orders').")
            .touchesFiles(List.of("frontend/src/api/orders.ts"))
            .hardConstraints(
                List.of(
                    "The list call uses GET /api/orders.",
                    "Public producer documentation remains at /api/orders."))
            .build();

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan(producer, shell, consumer));
    Plan normalizedAgain = TaskExpanderAgent.normalizeFinalPlan(normalized);

    assertThat(normalized.tasks().get(0).description())
        .contains("GET /api/orders", "POST /api/orders");
    assertThat(normalized.tasks().get(2).description())
        .contains("GET /orders", "POST /orders", "apiClient.get('/orders')")
        .doesNotContain("GET /api/orders", "POST /api/orders", "get('/api/orders')", "/api/api/");
    assertThat(normalized.tasks().get(2).hardConstraints())
        .anyMatch(value -> value.contains("GET /orders"))
        .anyMatch(value -> value.contains("Public producer documentation remains at /api/orders"));
    assertThat(normalizedAgain).isEqualTo(normalized);
  }

  @Test
  void doesNotRewriteFrontendPathsWithoutApiBaseUrlAuthority() {
    PlannedTask consumer =
        task(
            "T1",
            "Build orders screen",
            "Call GET /api/orders through the generated client.",
            List.of("frontend/src/api/orders.ts"));

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan(consumer));

    assertThat(normalized.tasks().getFirst().description()).contains("GET /api/orders");
  }

  @Test
  void doesNotRewriteNonFrontendApiOperations() {
    PlannedTask backend =
        task(
            "T1",
            "Implement API client proxy",
            "Configure baseURL: '/api' and proxy GET /api/orders.",
            List.of("backend/app/proxy.py"));

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan(backend));

    assertThat(normalized.tasks().getFirst().description()).contains("GET /api/orders");
  }

  @Test
  void rejectsR14DashboardEndpointsThatHaveNoBackendProducer() {
    PlannedTask warehouse =
        task(
            "T4",
            "Implement warehouse API",
            "Backend exposes GET /api/warehouses.",
            List.of("backend/app/routers/warehouses.py"));
    PlannedTask inventory =
        task(
            "T5",
            "Implement inventory API",
            "Backend exposes GET /api/inventory.",
            List.of("backend/app/routers/inventory.py"));
    PlannedTask audit =
        task(
            "T7",
            "Implement audit API",
            "Backend exposes GET /api/audit.",
            List.of("backend/app/routers/audit.py"));
    PlannedTask dashboard =
        task(
            "T11",
            "Build dashboard",
            "Call GET /dashboard/summary, GET /dashboard/low-stock?limit=5, and GET"
                + " /dashboard/recent-audit?limit=10 through the shared client.",
            List.of("frontend/src/api/dashboard.ts", "frontend/src/pages/Dashboard.tsx"));

    assertThat(PlanContractQualityGate.violations(plan(warehouse, inventory, audit, dashboard)))
        .contains(
            "T11: API consumer uses GET /api/dashboard/summary but no backend task declares that"
                + " operation",
            "T11: API consumer uses GET /api/dashboard/low-stock but no backend task declares that"
                + " operation",
            "T11: API consumer uses GET /api/dashboard/recent-audit but producer T7 owns GET"
                + " /api/audit");
  }

  @Test
  void rejectsR14FinalPagesThatCannotReplaceScaffoldRoutePlaceholders() {
    PlannedTask scaffold =
        task(
            "T10",
            "Build frontend shell",
            "Define placeholder route stubs for `/`, `/warehouses`, `/products`, `/inventory`,"
                + " `/orders`, `/orders/:id`, and `/audit`; later tasks replace the internals.",
            List.of(
                "frontend/src/App.tsx",
                "frontend/src/pages/DashboardPage.tsx",
                "frontend/src/pages/WarehousesPage.tsx",
                "frontend/src/pages/ProductsPage.tsx",
                "frontend/src/pages/InventoryPage.tsx",
                "frontend/src/pages/OrdersPage.tsx",
                "frontend/src/pages/OrderDetailPage.tsx",
                "frontend/src/pages/AuditPage.tsx"));
    PlannedTask inventory =
        task(
            "T13",
            "Build inventory screen",
            "Implement the final inventory screen.",
            List.of("frontend/src/pages/Inventory.tsx"));
    PlannedTask orders =
        task(
            "T14",
            "Build order screens",
            "Implement the final order list, detail, and create screens.",
            List.of(
                "frontend/src/pages/Orders.tsx",
                "frontend/src/pages/OrderDetail.tsx",
                "frontend/src/pages/OrderCreate.tsx"));
    PlannedTask audit =
        task(
            "T15",
            "Build audit history",
            "Implement the final audit history screen.",
            List.of("frontend/src/pages/AuditHistory.tsx"));

    assertThat(PlanContractQualityGate.violations(plan(scaffold, inventory, orders, audit)))
        .contains(
            "T13: final page routes [Inventory -> /inventory] remain bound to scaffold"
                + " placeholders because the task does not update frontend/src/App.tsx",
            "T14: final page routes [Orders -> /orders, OrderDetail -> /orders/:id, OrderCreate ->"
                + " /orders/new] remain bound to scaffold placeholders because the task does not"
                + " update frontend/src/App.tsx",
            "T15: final page routes [AuditHistory -> /audit] remain bound to scaffold placeholders"
                + " because the task does not update frontend/src/App.tsx");
  }

  @Test
  void acceptsFinalPageTaskThatOwnsTheScaffoldRouterUpdate() {
    PlannedTask scaffold =
        task(
            "T10",
            "Build frontend shell",
            "Define placeholder route stubs for `/inventory`; later tasks replace the internals.",
            List.of("frontend/src/App.tsx", "frontend/src/pages/InventoryPage.tsx"));
    PlannedTask inventory =
        task(
            "T13",
            "Build inventory screen",
            "Implement the final inventory screen and replace its route.",
            List.of("frontend/src/pages/Inventory.tsx", "frontend/src/App.tsx"));

    assertThat(PlanContractQualityGate.violations(plan(scaffold, inventory)))
        .noneMatch(value -> value.contains("remain bound to scaffold placeholders"));
  }

  @Test
  void acceptsDedicatedRouterOwnerThatExplicitlyBindsSeparateFinalPageTasks() {
    PlannedTask scaffold =
        task(
            "T10",
            "Build frontend shell",
            "Define placeholder route stubs for `/inventory`, `/orders`, and `/orders/:id`;"
                + " later tasks replace the internals.",
            List.of(
                "frontend/src/App.tsx",
                "frontend/src/pages/InventoryPage.tsx",
                "frontend/src/pages/OrdersPage.tsx",
                "frontend/src/pages/OrderDetailPage.tsx"));
    PlannedTask inventory =
        task(
            "T11",
            "Build inventory page",
            "Implement the final Inventory component.",
            List.of("frontend/src/pages/Inventory.tsx"));
    PlannedTask orders =
        task(
            "T12",
            "Build order pages",
            "Implement the final Orders and OrderDetail components.",
            List.of("frontend/src/pages/Orders.tsx", "frontend/src/pages/OrderDetail.tsx"));
    PlannedTask router =
        task(
            "T13",
            "Integrate final frontend routes",
            "Replace the stubs and bind Inventory to `/inventory`, Orders to `/orders`, and"
                + " OrderDetail to `/orders/:id` in the router.",
            List.of("frontend/src/App.tsx"));

    assertThat(PlanContractQualityGate.violations(plan(scaffold, inventory, orders, router)))
        .noneMatch(value -> value.contains("remain bound to scaffold placeholders"));
  }

  private static Plan plan(PlannedTask... tasks) {
    return Plan.builder()
        .planTitle("contract test")
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
