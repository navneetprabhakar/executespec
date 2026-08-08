package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.navneet.executespec.agent.RequirementSpec.RequirementKind;

import org.junit.jupiter.api.Test;

/**
 * Pins the requirement-ownership repair behaviour (fix #1 of the 2026-08-08 BUILD-pipeline
 * failure-surface pass). A plan whose tasks are perfectly reasonable but simply omit their {@code
 * meetsRequirements} field must NOT be told it "added work the customer did not request" — that
 * phrasing pushed the model to delete legitimate tasks instead of filling in ownership, which is
 * exactly why a FastAPI link-shortener spec failed to plan three times on Sonnet 5. The repair
 * prompt must instead direct the model to keep the same tasks and populate {@code
 * meetsRequirements}.
 */
class PlannerScopeIntegrityPolicyTest {

  @Test
  void unownedTasksRepairPromptDirectsPopulatingOwnershipNotDeletingTasks() {
    Plan unowned =
        buildPlan(
            List.of(
                new RequirementSpec("R1", "Create short links", RequirementKind.FUNCTIONAL),
                new RequirementSpec("R2", "Redirect by code", RequirementKind.FUNCTIONAL)),
            List.of(
                taskWithOwnership("T1", "Implement link creation", List.of()),
                taskWithOwnership("T2", "Implement redirect endpoint", List.of())));

    AtomicReference<String> capturedFeedback = new AtomicReference<>();
    Plan repaired =
        PlannerScopeIntegrityPolicy.enforce(
            unowned,
            feedback -> {
              capturedFeedback.set(feedback);
              // Simulate the model doing the RIGHT thing: same tasks, now with ownership.
              return buildPlan(
                  unowned.requirements(),
                  List.of(
                      taskWithOwnership("T1", "Implement link creation", List.of("R1")),
                      taskWithOwnership("T2", "Implement redirect endpoint", List.of("R2"))));
            });

    // The corrected plan is accepted.
    assertThat(PlannerOutputUtils.validateScopeIntegrity(repaired)).isEmpty();

    String feedback = capturedFeedback.get();
    assertThat(feedback)
        .as("repair prompt must name the actual fix: populate meetsRequirements, keep the tasks")
        .contains("meetsRequirements")
        .containsIgnoringCase("do NOT remove");
    // The raw violation label still reads "(likely invented deliverables)"; what must be gone is
    // the
    // old DIRECTIVE that told the model its tasks were unrequested work it should re-emit without.
    assertThat(feedback)
        .as("repair prompt must NOT tell the model its tasks were unrequested/invented work")
        .doesNotContain("did not request")
        .doesNotContain("without invented deliverables");
  }

  @Test
  void unownedPlanIsDeterministicallyRepairedInsteadOfFailing() {
    // Even when the model retry STILL omits ownership, a correct plan must not die — the ownership
    // is deterministically backfilled so the run proceeds. This is the FastAPI coin-flip fix.
    Plan unowned =
        buildPlan(
            List.of(new RequirementSpec("R1", "Create short links", RequirementKind.FUNCTIONAL)),
            List.of(taskWithOwnership("T1", "Implement link creation", List.of())));

    Plan result = PlannerScopeIntegrityPolicy.enforce(unowned, feedback -> unowned);

    assertThat(PlannerOutputUtils.validateScopeIntegrity(result)).isEmpty();
    assertThat(result.tasks().getFirst().meetsRequirements()).contains("R1");
  }

  @Test
  void genuineUnrequestedApiSpecStillFailsClosed() {
    // Ownership backfill must NOT paper over a real unauthorized deliverable: a task shipping a
    // standalone openapi.yaml the requirements never asked for still fails closed.
    Plan withApiSpec =
        buildPlan(
            List.of(new RequirementSpec("R1", "Create short links", RequirementKind.FUNCTIONAL)),
            List.of(
                PlannedTask.builder()
                    .id("T1")
                    .title("Implement link creation")
                    .description("Implement link creation")
                    .complexity(PlannedTask.Complexity.MEDIUM)
                    .touchesFiles(List.of("src/app.py", "openapi.yaml"))
                    .meetsRequirements(List.of("R1"))
                    .build()));

    assertThatThrownBy(
            () -> PlannerScopeIntegrityPolicy.enforce(withApiSpec, feedback -> withApiSpec))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unauthorized deliverables");
  }

  private static PlannedTask taskWithOwnership(
      String id, String title, List<String> meetsRequirements) {
    return PlannedTask.builder()
        .id(id)
        .title(title)
        .description(title)
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
