package com.navneet.executespec.v2.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.v2.agent.RequirementSpec.RequirementKind;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Validation v3 / Pillar 1 planner requirement-graph validation. Migrated from
 * {@code PlannerAgentV2ValidationTest} (which was deleted alongside its agent in v2.6.2 slice 5b)
 * and now exercises the static {@link PlannerOutputUtils#validatePlan(PlanV2)} directly — no Spring
 * collaborators needed.
 */
class PlannerOutputUtilsValidationTest {

  @Test
  void cleanPlanProducesNoViolations() {
    PlanV2 plan =
        buildPlan(
            List.of(
                new RequirementSpec(
                    "R1", "POST /api/shorten accepts a URL", RequirementKind.FUNCTIONAL),
                new RequirementSpec(
                    "R2", "Slugs are 7 chars alphanumeric", RequirementKind.FUNCTIONAL),
                new RequirementSpec(
                    "R3", "Rate limit 10 req/min/IP", RequirementKind.NON_FUNCTIONAL),
                new RequirementSpec(
                    "R4", "Add unit tests for service layer", RequirementKind.TEST)),
            List.of(
                task("T1", "Scaffold service + model", List.of("R1", "R2")),
                task("T2", "Add rate limit middleware", List.of("R3")),
                task("T3", "Write unit tests", List.of("R4"))),
            PlanV2.RuntimeSurface.HTTP,
            Classification.MEDIUM);

    assertThat(PlannerOutputUtils.validatePlan(plan)).isEmpty();
  }

  @Test
  void emptyRequirementsListIsViolation() {
    PlanV2 plan =
        buildPlan(
            List.of(),
            List.of(task("T1", "Do stuff", List.of())),
            PlanV2.RuntimeSurface.HTTP,
            Classification.MEDIUM);

    List<String> violations = PlannerOutputUtils.validatePlan(plan);
    assertThat(violations).hasSize(1);
    assertThat(violations.get(0)).contains("No requirements enumerated");
  }

  @Test
  void duplicateRequirementIdsFlagged() {
    PlanV2 plan =
        buildPlan(
            List.of(
                new RequirementSpec("R1", "First", RequirementKind.FUNCTIONAL),
                new RequirementSpec("R1", "Second (dup)", RequirementKind.FUNCTIONAL)),
            List.of(task("T1", "Do R1", List.of("R1"))),
            PlanV2.RuntimeSurface.LIBRARY,
            Classification.MEDIUM);

    assertThat(PlannerOutputUtils.validatePlan(plan))
        .anyMatch(v -> v.contains("Duplicate requirement ids"));
  }

  @Test
  void uncoveredRequirementFlagged() {
    PlanV2 plan =
        buildPlan(
            List.of(
                new RequirementSpec("R1", "Covered", RequirementKind.FUNCTIONAL),
                new RequirementSpec("R2", "Not covered by any task", RequirementKind.FUNCTIONAL),
                new RequirementSpec("R3", "Also missing", RequirementKind.FUNCTIONAL)),
            List.of(task("T1", "Only does R1", List.of("R1"))),
            PlanV2.RuntimeSurface.HTTP,
            Classification.MEDIUM);

    assertThat(PlannerOutputUtils.validatePlan(plan))
        .anyMatch(
            v -> v.contains("not covered by any task") && v.contains("R2") && v.contains("R3"));
  }

  @Test
  void unknownMeetsRequirementRefFlagged() {
    PlanV2 plan =
        buildPlan(
            List.of(new RequirementSpec("R1", "A req", RequirementKind.FUNCTIONAL)),
            List.of(task("T1", "Do R1", List.of("R1")), task("T2", "Refs a ghost", List.of("R99"))),
            PlanV2.RuntimeSurface.HTTP,
            Classification.MEDIUM);

    assertThat(PlannerOutputUtils.validatePlan(plan))
        .anyMatch(v -> v.contains("unknown ids") && v.contains("R99"));
  }

  @Test
  void httpSurfaceWithoutTestRequirementFlagged() {
    PlanV2 plan =
        buildPlan(
            List.of(
                new RequirementSpec("R1", "POST /api/shorten", RequirementKind.FUNCTIONAL),
                new RequirementSpec("R2", "Rate limit", RequirementKind.NON_FUNCTIONAL)),
            List.of(
                task("T1", "Scaffold service", List.of("R1")),
                task("T2", "Add rate limiter", List.of("R2"))),
            PlanV2.RuntimeSurface.HTTP,
            Classification.MEDIUM);

    assertThat(PlannerOutputUtils.validatePlan(plan))
        .anyMatch(v -> v.contains("No test task") && v.contains("HTTP"));
  }

  @Test
  void httpSurfaceWithTestTitleTaskOk() {
    PlanV2 plan =
        buildPlan(
            List.of(new RequirementSpec("R1", "POST /api/shorten", RequirementKind.FUNCTIONAL)),
            List.of(task("T1", "Scaffold service + unit tests", List.of("R1"))),
            PlanV2.RuntimeSurface.HTTP,
            Classification.MEDIUM);

    assertThat(PlannerOutputUtils.validatePlan(plan)).isEmpty();
  }

  @Test
  void singleShotBypassesTestTaskRule() {
    PlanV2 plan =
        buildPlan(
            List.of(new RequirementSpec("R1", "Write fib.py", RequirementKind.FUNCTIONAL)),
            List.of(task("T1", "Implement", List.of("R1"))),
            PlanV2.RuntimeSurface.HTTP,
            Classification.SINGLE_SHOT);

    assertThat(PlannerOutputUtils.validatePlan(plan)).isEmpty();
  }

  @Test
  void staticWebSurfaceBypassesTestTaskRule() {
    PlanV2 plan =
        buildPlan(
            List.of(new RequirementSpec("R1", "Render hero section", RequirementKind.FUNCTIONAL)),
            List.of(task("T1", "Write HTML", List.of("R1"))),
            PlanV2.RuntimeSurface.STATIC_WEB,
            Classification.MEDIUM);

    assertThat(PlannerOutputUtils.validatePlan(plan)).isEmpty();
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static PlannedTaskV2 task(String id, String title, List<String> meetsRequirements) {
    return PlannedTaskV2.builder()
        .id(id)
        .title(title)
        .description(title)
        .complexity(PlannedTaskV2.Complexity.MEDIUM)
        .meetsRequirements(meetsRequirements)
        .build();
  }

  private static PlanV2 buildPlan(
      List<RequirementSpec> reqs,
      List<PlannedTaskV2> tasks,
      PlanV2.RuntimeSurface surface,
      Classification classification) {
    return PlanV2.builder()
        .planTitle("t")
        .planSummary("s")
        .projectType("unknown")
        .runtimeSurface(surface)
        .classification(classification)
        .tasks(tasks)
        .requirements(reqs)
        .build();
  }
}
