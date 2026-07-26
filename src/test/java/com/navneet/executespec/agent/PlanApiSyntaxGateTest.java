package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PlanApiSyntaxGateTest {

  @Test
  void rejectsDanglingAssignmentAndAngleBracketRouteParameters() {
    Plan plan =
        plan(
            task(
                "Implement API",
                "Implement GET /api/products=. Consume GET /api/stock<warehouse_id>&search=x."));
    List<String> violations = new ArrayList<>();

    PlanApiSyntaxGate.addViolations(plan, violations);

    assertThat(violations)
        .anyMatch(value -> value.contains("GET /api/products="))
        .anyMatch(value -> value.contains("GET /api/stock<warehouse_id>&search=x"));
    assertThat(PlanContractQualityGate.violations(plan))
        .anyMatch(value -> value.contains("malformed HTTP route"));
  }

  @Test
  void acceptsBraceParametersAndNormalQueryStrings() {
    Plan plan =
        plan(
            task(
                "Implement API",
                "Implement GET /api/products/{product_id} and GET"
                    + " /api/stock?warehouse_id={warehouse_id}&search=x."));
    List<String> violations = new ArrayList<>();

    PlanApiSyntaxGate.addViolations(plan, violations);

    assertThat(violations).isEmpty();
  }

  private static Plan plan(PlannedTask task) {
    return Plan.builder().tasks(List.of(task)).requirements(List.of()).build();
  }

  private static PlannedTask task(String title, String description) {
    return PlannedTask.builder()
        .id("T1")
        .title(title)
        .description(description)
        .complexity(PlannedTask.Complexity.LOW)
        .touchesFiles(List.of("backend/app/routers/items.py"))
        .dependsOn(List.of())
        .hardConstraints(List.of())
        .meetsRequirements(List.of())
        .build();
  }
}
