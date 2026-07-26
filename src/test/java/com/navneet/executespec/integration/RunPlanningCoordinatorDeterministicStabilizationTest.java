package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.security.ContextSanitizer;

import org.junit.jupiter.api.Test;

class RunPlanningCoordinatorDeterministicStabilizationTest {

  @Test
  void skipsAdditionalNormalizationWhenTheFirstFinalValidationIsClean() {
    Plan plan = plan("Implement the selected SQLite persistence stack.");

    RunPlanningCoordinator.DeterministicFinalPlanPass result =
        RunPlanningCoordinator.stabilizeFinalPlanBeforeProviderRepair(plan, List.of());

    assertThat(result.stabilizationAttempted()).isFalse();
    assertThat(result.plan()).isSameAs(plan);
    assertThat(result.violations()).isEmpty();
  }

  @Test
  void clearsAStaleFindingWithOneDeterministicPassBeforeProviderRepair() {
    Plan plan = plan("Implement the selected SQLite persistence stack.");

    RunPlanningCoordinator.DeterministicFinalPlanPass result =
        RunPlanningCoordinator.stabilizeFinalPlanBeforeProviderRepair(
            plan, List.of("stale first-pass finding"));

    assertThat(result.stabilizationAttempted()).isTrue();
    assertThat(result.violations()).isEmpty();
  }

  @Test
  void retainsARealViolationForTheBoundedProviderRepair() {
    Plan plan = plan("Choose either PostgreSQL or SQLite before implementing persistence.");

    RunPlanningCoordinator.DeterministicFinalPlanPass result =
        RunPlanningCoordinator.stabilizeFinalPlanBeforeProviderRepair(
            plan, List.of("unresolved implementation choice"));

    assertThat(result.stabilizationAttempted()).isTrue();
    assertThat(result.violations())
        .anyMatch(value -> value.contains("implementation choice unresolved"));
  }

  @Test
  void repairTriggerSummaryIsSecretFreeSingleLineAndBounded() {
    String secret = "sk-proj-123456789012345678901234567890";
    List<String> violations =
        java.util.stream.IntStream.range(0, 20)
            .mapToObj(index -> "T" + index + ": bad contract\n" + secret + " " + "x".repeat(400))
            .toList();

    String summary =
        RunPlanningCoordinator.boundedRepairTriggerSummary(violations, new ContextSanitizer());

    assertThat(summary)
        .doesNotContain(secret, "\n", "\r")
        .contains("[REDACTED]", "+12 more")
        .hasSizeLessThanOrEqualTo(1_601);
  }

  private static Plan plan(String description) {
    return Plan.builder()
        .planTitle("Persistence")
        .tasks(
            List.of(
                PlannedTask.builder()
                    .id("T1")
                    .title("Implement persistence")
                    .description(description)
                    .touchesFiles(List.of("src/persistence.rs"))
                    .build()))
        .build();
  }
}
