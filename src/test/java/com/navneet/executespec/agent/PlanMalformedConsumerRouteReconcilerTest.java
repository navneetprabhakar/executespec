package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PlanMalformedConsumerRouteReconcilerTest {

  @Test
  void canonicalizesRejectedAuditRouteToItsUniqueProducerAndNamedQueryString() {
    Plan normalized =
        TaskExpanderAgent.normalizeFinalPlan(
            plan(
                backend("T1", "GET /api/audit accepts page and page_size query parameters."),
                frontend(
                    "T11",
                    "Call `GET /audit<int>&page=<int>&page_size=<int>` and render the result.")));

    assertThat(normalized.tasks().get(1).description())
        .contains("GET /api/audit?page=<int>&page_size=<int>")
        .doesNotContain("/audit<int>");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void appliesTheSameRepairToAnyUniquelyOwnedResourceRoute() {
    Plan normalized =
        TaskExpanderAgent.normalizeFinalPlan(
            plan(
                backend("T4", "GET /api/ledger-events accepts cursor and limit query parameters."),
                frontend(
                    "T8",
                    "loadLedger calls GET" + " /ledger-events<row>&cursor=<string>&limit=<int>.")));

    assertThat(normalized.tasks().get(1).description())
        .contains("GET /api/ledger-events?cursor=<string>&limit=<int>")
        .doesNotContain("ledger-events<row>");
    assertThat(PlanContractQualityGate.violations(normalized)).isEmpty();
  }

  @Test
  void leavesMalformedRouteVisibleWhenProducerOwnershipIsAmbiguous() {
    Plan input =
        plan(
            backend("T1", "GET /api/audit accepts page query parameter."),
            backend("T2", "GET /api/audit accepts page query parameter."),
            frontend("T3", "Call GET /audit<int>&page=<int>."));

    Plan normalized = PlanApiContractReconciler.normalizeMalformedConsumerRoutes(input);

    assertThat(normalized.tasks().get(2).description()).contains("GET /audit<int>&page=<int>");
    assertThat(PlanContractQualityGate.violations(normalized))
        .anyMatch(value -> value.contains("malformed HTTP route"));
  }

  private static Plan plan(PlannedTask... tasks) {
    return Plan.builder().tasks(List.of(tasks)).requirements(List.of()).build();
  }

  private static PlannedTask backend(String id, String description) {
    return task(id, description, "backend/app/routers/" + id.toLowerCase() + ".py");
  }

  private static PlannedTask frontend(String id, String description) {
    return task(id, description, "frontend/src/api/" + id.toLowerCase() + ".ts");
  }

  private static PlannedTask task(String id, String description, String file) {
    return PlannedTask.builder()
        .id(id)
        .title("Task " + id)
        .description(description)
        .complexity(PlannedTask.Complexity.MEDIUM)
        .touchesFiles(List.of(file))
        .dependsOn(List.of())
        .hardConstraints(List.of())
        .meetsRequirements(List.of())
        .taskType(PlannedTask.TaskType.LEAF)
        .build();
  }
}
