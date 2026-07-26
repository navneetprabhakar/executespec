package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PlanExpansionDependencyEnricherTest {
  @Test
  void frontendConsumersExpandAfterBackendProducersAndTestsAfterImplementations() {
    Plan plan =
        Plan.builder()
            .tasks(
                List.of(
                    task("T1", "Scaffold backend", List.of("backend/app/main.py"), List.of()),
                    task(
                        "T2",
                        "Implement warehouse API endpoints",
                        List.of(
                            "backend/app/routers/warehouses.py",
                            "backend/tests/test_warehouses.py"),
                        List.of("T1")),
                    task(
                        "T3",
                        "Scaffold React frontend",
                        List.of("frontend/src/App.tsx"),
                        List.of()),
                    task(
                        "T4",
                        "Implement warehouse management page",
                        List.of("frontend/src/pages/Warehouses.tsx"),
                        List.of("T3")),
                    task(
                        "T5", "End-to-end tests", List.of("e2e/inventory.spec.ts"), List.of("T4"))))
            .build();

    PlanExpansionDependencyEnricher.Result result = PlanExpansionDependencyEnricher.enrich(plan);

    assertThat(result.plan().tasks().get(2).dependsOn()).isEmpty();
    assertThat(result.plan().tasks().get(3).dependsOn()).contains("T2", "T3");
    assertThat(result.plan().tasks().get(3).dependsOn()).doesNotContain("T1");
    assertThat(result.plan().tasks().get(4).dependsOn()).containsExactly("T4");
    assertThat(result.summaries())
        .anyMatch(value -> value.contains("transitively redundant dependency"));
  }

  @Test
  void skipsAnEdgeThatWouldCreateACycle() {
    Plan plan =
        Plan.builder()
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Implement backend API",
                        List.of("backend/app/router.py"),
                        List.of("T2")),
                    task(
                        "T2",
                        "Implement management page",
                        List.of("frontend/src/Page.tsx"),
                        List.of())))
            .build();

    assertThat(PlanExpansionDependencyEnricher.enrich(plan).plan().tasks().get(1).dependsOn())
        .doesNotContain("T1");
  }

  @Test
  void enrichmentIsIdempotent() {
    Plan plan =
        Plan.builder()
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Implement warehouse API",
                        List.of("backend/app/routers/warehouses.py"),
                        List.of()),
                    task(
                        "T2",
                        "Implement warehouse dashboard page",
                        List.of("frontend/src/Dashboard.tsx"),
                        List.of())))
            .build();

    Plan once = PlanExpansionDependencyEnricher.enrich(plan).plan();
    PlanExpansionDependencyEnricher.Result twice = PlanExpansionDependencyEnricher.enrich(once);

    assertThat(twice.dependenciesAdded()).isZero();
    assertThat(twice.plan().tasks().get(1).dependsOn()).containsExactly("T1");
  }

  @Test
  void endToEndTaskDoesNotDependOnLaterPackagingTasksThatAlreadyDependOnIt() {
    Plan plan =
        Plan.builder()
            .tasks(
                List.of(
                    task(
                        "T1", "Implement backend API", List.of("backend/app/router.py"), List.of()),
                    task(
                        "T2",
                        "Implement management page",
                        List.of("frontend/src/Page.tsx"),
                        List.of("T1")),
                    task(
                        "T3",
                        "End-to-end tests",
                        List.of("e2e/full-workflow.spec.ts"),
                        List.of("T2")),
                    task(
                        "T4",
                        "Finalize README and seed script",
                        List.of("README.md", "backend/app/seed.py"),
                        List.of("T3")),
                    task(
                        "T5",
                        "Wire build scripts",
                        List.of("Makefile", "backend/pyproject.toml"),
                        List.of("T4"))))
            .build();

    Plan enriched = PlanExpansionDependencyEnricher.enrich(plan).plan();

    assertThat(enriched.tasks().get(2).dependsOn()).containsExactly("T2");
    assertThat(enriched.tasks().get(2).dependsOn()).doesNotContain("T4", "T5");
    assertThat(enriched.tasks().get(3).dependsOn()).containsExactly("T3");
    assertThat(enriched.tasks().get(4).dependsOn()).containsExactly("T4");
  }

  @Test
  void frontendFeatureDependsOnlyOnDomainRelevantBackendProducers() {
    Plan plan =
        Plan.builder()
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Implement warehouse and product APIs",
                        List.of("backend/app/routers/warehouses.py"),
                        List.of()),
                    task(
                        "T2",
                        "Implement inventory and transfer APIs",
                        List.of("backend/app/routers/inventory.py"),
                        List.of()),
                    task(
                        "T3",
                        "Implement order lifecycle APIs",
                        List.of("backend/app/routers/orders.py"),
                        List.of()),
                    task(
                        "T4",
                        "Build inventory screen with transfer flow",
                        List.of("frontend/src/pages/Inventory.tsx"),
                        List.of())))
            .build();

    PlannedTask inventory = PlanExpansionDependencyEnricher.enrich(plan).plan().tasks().get(3);

    assertThat(inventory.dependsOn()).containsExactly("T2");
  }

  @Test
  void frontendShellDoesNotReceiveEveryBackendContract() {
    Plan plan =
        Plan.builder()
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Implement warehouse API",
                        List.of("backend/app/routers/warehouses.py"),
                        List.of()),
                    task(
                        "T2",
                        "Build frontend shell, routing, shared UI, and API client",
                        List.of("frontend/src/App.tsx", "frontend/src/api/client.ts"),
                        List.of())))
            .build();

    assertThat(PlanExpansionDependencyEnricher.enrich(plan).plan().tasks().get(1).dependsOn())
        .isEmpty();
  }

  @Test
  void removesCyclicForwardEdgeWhilePreservingOutlineOrder() {
    Plan plan =
        Plan.builder()
            .tasks(
                List.of(
                    task("T1", "Implement backend", List.of("backend/app.py"), List.of()),
                    task(
                        "T2",
                        "End-to-end tests",
                        List.of("e2e/tests/full.spec.ts"),
                        List.of("T1", "T3")),
                    task("T3", "Wire test commands", List.of("Makefile"), List.of("T2"))))
            .build();

    PlanExpansionDependencyEnricher.Result result = PlanExpansionDependencyEnricher.enrich(plan);

    assertThat(result.plan().tasks().get(1).dependsOn()).containsExactly("T1");
    assertThat(result.plan().tasks().get(2).dependsOn()).containsExactly("T2");
    assertThat(result.summaries()).anyMatch(value -> value.contains("cyclic forward dependency"));
  }

  @Test
  void keepsOnlyNearestDependencyFrontierForExpansionEvidence() {
    Plan plan =
        Plan.builder()
            .tasks(
                List.of(
                    task("T1", "Create schema", List.of("server/models/item.py"), List.of()),
                    task("T2", "Create service", List.of("server/services/item.py"), List.of("T1")),
                    task(
                        "T3", "Create API", List.of("server/routes/items.py"), List.of("T1", "T2")),
                    task(
                        "T4",
                        "HTTP integration tests",
                        List.of("tests/items.integration.py"),
                        List.of("T1", "T2", "T3"))))
            .build();

    PlanExpansionDependencyEnricher.Result result = PlanExpansionDependencyEnricher.enrich(plan);

    assertThat(result.plan().tasks().get(2).dependsOn()).containsExactly("T2");
    assertThat(result.plan().tasks().get(3).dependsOn()).containsExactly("T3");
  }

  private static PlannedTask task(
      String id, String title, List<String> files, List<String> dependencies) {
    return PlannedTask.builder()
        .id(id)
        .title(title)
        .touchesFiles(files)
        .dependsOn(dependencies)
        .hardConstraints(List.of())
        .meetsRequirements(List.of())
        .build();
  }
}
