package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PlanApiBindingContradictionGateTest {

  @Test
  void rejectsAlternateFrontendActionPathAfterExactBinding() {
    PlannedTask frontend =
        task(
            "Submit with POST /adjustments, but use relative path"
                + " `/warehouses/{warehouseId}/products/{productId}/adjustments`.",
            List.of(
                "Reuse the exact endpoint paths from T5: POST /adjustments and POST /transfers."));
    Plan plan = Plan.builder().tasks(List.of(frontend)).requirements(List.of()).build();
    ArrayList<String> violations = new ArrayList<>();

    PlanApiBindingContradictionGate.addViolations(plan, violations);

    assertThat(violations)
        .containsExactly(
            "T10: call-site path /warehouses/{warehouseId}/products/{productId}/adjustments"
                + " contradicts exact API binding [POST /api/adjustments]");
    assertThat(PlanContractQualityGate.violations(plan))
        .anyMatch(value -> value.contains("contradicts exact API binding"));
  }

  @Test
  void acceptsCanonicalAndExplicitlyForbiddenAlternateExamples() {
    PlannedTask frontend =
        task(
            "Call `/adjustments`; never use"
                + " `/warehouses/{warehouseId}/products/{productId}/adjustments`.",
            List.of("Use the exact endpoint path POST /adjustments."));
    ArrayList<String> violations = new ArrayList<>();

    PlanApiBindingContradictionGate.addViolations(
        Plan.builder().tasks(List.of(frontend)).build(), violations);

    assertThat(violations).isEmpty();
  }

  private static PlannedTask task(String description, List<String> constraints) {
    return PlannedTask.builder()
        .id("T10")
        .title("Build adjustment dialog")
        .description(description)
        .complexity(PlannedTask.Complexity.MEDIUM)
        .touchesFiles(List.of("frontend/src/components/AdjustmentDialog.tsx"))
        .dependsOn(List.of("T5"))
        .hardConstraints(constraints)
        .meetsRequirements(List.of())
        .build();
  }
}
