package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.agent.RequirementSpec.RequirementKind;

import org.junit.jupiter.api.Test;

/**
 * Pins the deterministic requirement-ownership backfill (fix #3 of the 2026-08-08 BUILD-pipeline
 * pass). When the outline model omits {@code meetsRequirements} (which Anthropic does
 * intermittently because it treats nested {@code required} as a hint), a correct plan must not be
 * thrown away — the repair assigns ownership by keyword overlap so every task owns a requirement
 * and every requirement has an owner.
 */
class PlannerOwnershipRepairTest {

  @Test
  void backfillsOwnershipByKeywordOverlapWhenModelOmitsIt() {
    Plan plan =
        buildPlan(
            List.of(
                new RequirementSpec(
                    "R1", "Create a short link from a target url", RequirementKind.FUNCTIONAL),
                new RequirementSpec(
                    "R2", "Redirect a code to its target url", RequirementKind.FUNCTIONAL),
                new RequirementSpec("R3", "Delete a link, owner only", RequirementKind.FUNCTIONAL)),
            List.of(
                task(
                    "T1",
                    "Link creation endpoint",
                    "Create a short link from a target url",
                    List.of()),
                task("T2", "Redirect endpoint", "Redirect a code to its target url", List.of()),
                task("T3", "Delete endpoint", "Delete a link restricted to the owner", List.of())));

    Plan repaired = PlannerOwnershipRepair.assignMissingOwnership(plan);

    // No scope-integrity violations remain — every task owns a requirement, every requirement
    // owned.
    assertThat(PlannerOutputUtils.validateScopeIntegrity(repaired)).isEmpty();
    // And the assignment is sensible (keyword overlap put each requirement on its matching task).
    assertThat(ownership(repaired, "T1")).contains("R1");
    assertThat(ownership(repaired, "T2")).contains("R2");
    assertThat(ownership(repaired, "T3")).contains("R3");
  }

  @Test
  void preservesOwnershipTheModelDidProvide() {
    Plan plan =
        buildPlan(
            List.of(
                new RequirementSpec("R1", "Create link", RequirementKind.FUNCTIONAL),
                new RequirementSpec("R2", "Redirect link", RequirementKind.FUNCTIONAL)),
            List.of(
                task("T1", "Creation", "Create link", List.of("R1", "R2")),
                task("T2", "Redirect", "Redirect link", List.of())));

    Plan repaired = PlannerOwnershipRepair.assignMissingOwnership(plan);

    assertThat(ownership(repaired, "T1")).containsExactly("R1", "R2");
    // T2 was empty and must be given an owner so no task is ownerless.
    assertThat(ownership(repaired, "T2")).isNotEmpty();
    assertThat(PlannerOutputUtils.validateScopeIntegrity(repaired)).isEmpty();
  }

  private static List<String> ownership(Plan plan, String taskId) {
    return plan.tasks().stream()
        .filter(t -> taskId.equals(t.id()))
        .findFirst()
        .orElseThrow()
        .meetsRequirements();
  }

  private static PlannedTask task(
      String id, String title, String description, List<String> meetsRequirements) {
    return PlannedTask.builder()
        .id(id)
        .title(title)
        .description(description)
        .complexity(PlannedTask.Complexity.MEDIUM)
        .meetsRequirements(meetsRequirements)
        .build();
  }

  private static Plan buildPlan(List<RequirementSpec> reqs, List<PlannedTask> tasks) {
    return Plan.builder()
        .planTitle("t")
        .planSummary("s")
        .projectType("unknown")
        .runtimeSurface(Plan.RuntimeSurface.HTTP)
        .classification(Classification.MEDIUM)
        .tasks(tasks)
        .requirements(reqs)
        .build();
  }
}
