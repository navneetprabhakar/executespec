package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.RequirementSpec.RequirementKind;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Validation v3 / Pillar 1 planner requirement-graph validation. Migrated from
 * {@code PlannerAgentV2ValidationTest} (which was deleted alongside its agent in v2.6.2 slice 5b)
 * and now exercises the static {@link PlannerOutputUtils#validatePlan(Plan)} directly — no Spring
 * collaborators needed.
 */
class PlannerOutputUtilsValidationTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  void cleanPlanProducesNoViolations() {
    Plan plan =
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
            Plan.RuntimeSurface.HTTP,
            Classification.MEDIUM);

    assertThat(PlannerOutputUtils.validatePlan(plan)).isEmpty();
  }

  @Test
  void emptyRequirementsListIsViolation() {
    Plan plan =
        buildPlan(
            List.of(),
            List.of(task("T1", "Do stuff", List.of())),
            Plan.RuntimeSurface.HTTP,
            Classification.MEDIUM);

    List<String> violations = PlannerOutputUtils.validatePlan(plan);
    assertThat(violations).hasSize(1);
    assertThat(violations.get(0)).contains("No requirements enumerated");
  }

  @Test
  void duplicateRequirementIdsFlagged() {
    Plan plan =
        buildPlan(
            List.of(
                new RequirementSpec("R1", "First", RequirementKind.FUNCTIONAL),
                new RequirementSpec("R1", "Second (dup)", RequirementKind.FUNCTIONAL)),
            List.of(task("T1", "Do R1", List.of("R1"))),
            Plan.RuntimeSurface.LIBRARY,
            Classification.MEDIUM);

    assertThat(PlannerOutputUtils.validatePlan(plan))
        .anyMatch(v -> v.contains("Duplicate requirement ids"));
  }

  @Test
  void uncoveredRequirementFlagged() {
    Plan plan =
        buildPlan(
            List.of(
                new RequirementSpec("R1", "Covered", RequirementKind.FUNCTIONAL),
                new RequirementSpec("R2", "Not covered by any task", RequirementKind.FUNCTIONAL),
                new RequirementSpec("R3", "Also missing", RequirementKind.FUNCTIONAL)),
            List.of(task("T1", "Only does R1", List.of("R1"))),
            Plan.RuntimeSurface.HTTP,
            Classification.MEDIUM);

    assertThat(PlannerOutputUtils.validatePlan(plan))
        .anyMatch(
            v -> v.contains("not covered by any task") && v.contains("R2") && v.contains("R3"));
  }

  @Test
  void unknownMeetsRequirementRefFlagged() {
    Plan plan =
        buildPlan(
            List.of(new RequirementSpec("R1", "A req", RequirementKind.FUNCTIONAL)),
            List.of(task("T1", "Do R1", List.of("R1")), task("T2", "Refs a ghost", List.of("R99"))),
            Plan.RuntimeSurface.HTTP,
            Classification.MEDIUM);

    assertThat(PlannerOutputUtils.validatePlan(plan))
        .anyMatch(v -> v.contains("unknown ids") && v.contains("R99"));
  }

  @Test
  void httpSurfaceWithoutTestRequirementFlagged() {
    Plan plan =
        buildPlan(
            List.of(
                new RequirementSpec("R1", "POST /api/shorten", RequirementKind.FUNCTIONAL),
                new RequirementSpec("R2", "Rate limit", RequirementKind.NON_FUNCTIONAL)),
            List.of(
                task("T1", "Scaffold service", List.of("R1")),
                task("T2", "Add rate limiter", List.of("R2"))),
            Plan.RuntimeSurface.HTTP,
            Classification.MEDIUM);

    assertThat(PlannerOutputUtils.validatePlan(plan))
        .anyMatch(v -> v.contains("No test task") && v.contains("HTTP"));
  }

  @Test
  void httpSurfaceWithTestTitleTaskOk() {
    Plan plan =
        buildPlan(
            List.of(new RequirementSpec("R1", "POST /api/shorten", RequirementKind.FUNCTIONAL)),
            List.of(task("T1", "Scaffold service + unit tests", List.of("R1"))),
            Plan.RuntimeSurface.HTTP,
            Classification.MEDIUM);

    assertThat(PlannerOutputUtils.validatePlan(plan)).isEmpty();
  }

  @Test
  void singleShotBypassesTestTaskRule() {
    Plan plan =
        buildPlan(
            List.of(new RequirementSpec("R1", "Write fib.py", RequirementKind.FUNCTIONAL)),
            List.of(task("T1", "Implement", List.of("R1"))),
            Plan.RuntimeSurface.HTTP,
            Classification.SINGLE_SHOT);

    assertThat(PlannerOutputUtils.validatePlan(plan)).isEmpty();
  }

  @Test
  void staticWebSurfaceBypassesTestTaskRule() {
    Plan plan =
        buildPlan(
            List.of(new RequirementSpec("R1", "Render hero section", RequirementKind.FUNCTIONAL)),
            List.of(task("T1", "Write HTML", List.of("R1"))),
            Plan.RuntimeSurface.STATIC_WEB,
            Classification.MEDIUM);

    assertThat(PlannerOutputUtils.validatePlan(plan)).isEmpty();
  }

  @Test
  void parsePlanUsesLegacyClassificationCapWhenNoPlanStrategyIsSupplied() {
    Plan plan =
        PlannerOutputUtils.parsePlanFromArgs(
            planJsonWithTasks(8), Classification.MEDIUM, OBJECT_MAPPER);

    assertThat(plan.tasks()).hasSize(4);
  }

  @Test
  void parsePlanUsesPlanStrategyCapWhenStrategyIsSupplied() {
    Plan plan =
        PlannerOutputUtils.parsePlanFromArgs(
            planJsonWithTasks(8), Classification.MEDIUM, PlanStrategy.MEDIUM, OBJECT_MAPPER);

    assertThat(plan.tasks()).hasSize(8);
  }

  private static PlannedTask task(String id, String title, List<String> meetsRequirements) {
    return PlannedTask.builder()
        .id(id)
        .title(title)
        .description(title)
        .complexity(PlannedTask.Complexity.MEDIUM)
        .meetsRequirements(meetsRequirements)
        .build();
  }

  private static Plan buildPlan(
      List<RequirementSpec> reqs,
      List<PlannedTask> tasks,
      Plan.RuntimeSurface surface,
      Classification classification) {
    return Plan.builder()
        .planTitle("t")
        .planSummary("s")
        .projectType("unknown")
        .runtimeSurface(surface)
        .classification(classification)
        .tasks(tasks)
        .requirements(reqs)
        .build();
  }

  private static String planJsonWithTasks(int taskCount) {
    StringBuilder tasks = new StringBuilder("[");
    for (int i = 1; i <= taskCount; i++) {
      if (i > 1) tasks.append(',');
      tasks
          .append("{\"id\":\"T")
          .append(i)
          .append("\",\"title\":\"Task ")
          .append(i)
          .append("\",\"description\":\"Task ")
          .append(i)
          .append("\",\"complexity\":\"LOW\",\"type\":\"LEAF\"}");
    }
    tasks.append(']');
    return "{\"planTitle\":\"t\",\"planSummary\":\"s\",\"projectType\":\"unknown\","
        + "\"runtimeSurface\":\"HTTP\",\"smokeTestStrategy\":\"BUILD\",\"tasks\":"
        + tasks
        + ",\"requirements\":[]}";
  }
}
