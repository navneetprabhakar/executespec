package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PlanRouteNamespacePreservationTest {

  @Test
  void repeatedNormalizationPreservesExplicitNonApiProducerRoute() {
    Plan once = TaskExpanderAgent.normalizeFinalPlan(plan(task("T1", "GET /casino-game")));
    Plan twice = TaskExpanderAgent.normalizeFinalPlan(once);

    assertThat(routeEvidence(once)).contains("GET /casino-game").doesNotContain("/api/casino-game");
    assertThat(twice).isEqualTo(once);
  }

  @Test
  void keepsRootAndApiNamespacesAsDistinctProducerIdentities() {
    Plan normalized =
        TaskExpanderAgent.normalizeFinalPlan(
            plan(task("T1", "GET /casino-game"), task("T2", "GET /api/casino-game")));

    assertThat(routeEvidence(normalized))
        .contains("GET /casino-game")
        .contains("GET /api/casino-game");
  }

  @Test
  void canonicalizesParameterSpellingWithoutDuplicatingApiNamespace() {
    PlannedTask producer = task("T1", "Implement GET /api/games/{game_id}.");
    PlannedTask consumer =
        PlannedTask.builder()
            .id("T2")
            .title("Build game details UI")
            .description("Load GET /api/games/{id} from the web client.")
            .touchesFiles(List.of("frontend/src/Game.tsx"))
            .dependsOn(List.of("T1"))
            .build();

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan(producer, consumer));

    assertThat(routeEvidence(normalized))
        .contains("GET /api/games/{game_id}")
        .doesNotContain("/api/api/");
  }

  private static PlannedTask task(String id, String description) {
    return PlannedTask.builder()
        .id(id)
        .title("Implement game HTTP route")
        .description(description)
        .touchesFiles(List.of("backend/routes/game-" + id.toLowerCase() + ".java"))
        .build();
  }

  private static Plan plan(PlannedTask... tasks) {
    return Plan.builder().tasks(List.of(tasks)).requirements(List.of()).build();
  }

  private static String routeEvidence(Plan plan) {
    return plan.tasks().stream()
        .map(
            task ->
                task.description()
                    + "\n"
                    + String.join(
                        "\n", task.hardConstraints() == null ? List.of() : task.hardConstraints()))
        .collect(java.util.stream.Collectors.joining("\n"));
  }
}
