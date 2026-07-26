package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.agent.RequirementSpec;
import com.navneet.executespec.agent.TaskExpanderAgent;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;

class RunPlanningCoordinatorExpansionWavesTest {

  @Test
  void expandsDependenciesBeforeConsumersWhileKeepingIndependentTasksParallel() {
    List<List<PlannedTask>> waves =
        RunPlanningCoordinator.expansionWaves(
            List.of(
                task("T1"),
                task("T2", "T1"),
                task("T3", "T1"),
                task("T4", "T2", "T3"),
                task("T5", "UNKNOWN")));

    assertThat(ids(waves)).containsExactly(List.of("T1", "T5"), List.of("T2", "T3"), List.of("T4"));
  }

  @Test
  void rejectsACycleBeforeExpandingContradictoryConsumers() {
    assertThatThrownBy(
            () ->
                RunPlanningCoordinator.expansionWaves(
                    List.of(task("T1"), task("T2", "T3"), task("T3", "T2"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cyclic task dependencies")
        .hasMessageContaining("T2")
        .hasMessageContaining("T3");
  }

  @Test
  void cancellingOutstandingExpansionInterruptsRunningProviderWork() throws Exception {
    var executor = Executors.newSingleThreadExecutor();
    var started = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    try {
      var future =
          executor.submit(
              () -> {
                started.countDown();
                try {
                  new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                  interrupted.countDown();
                  Thread.currentThread().interrupt();
                }
              });
      assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

      RunPlanningCoordinator.cancelOutstandingExpansionFutures(List.of(future));

      assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(future.isCancelled()).isTrue();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void semanticFailureIsNotMisreportedAsTwentyMinuteTimeout() {
    IllegalStateException failure =
        RunPlanningCoordinator.expansionPhaseFailure(
            new ExecutionException(
                new java.util.concurrent.CompletionException(
                    new IllegalStateException("reservation line contract missing"))));

    assertThat(failure)
        .hasMessage("LARGE expansion phase failed: reservation line contract missing")
        .hasCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  void expansionPhaseFailurePreservesTypedContractRejection() {
    TaskExpanderAgent.ExpansionContractException rejection =
        new TaskExpanderAgent.ExpansionContractException(
            "T9", List.of("required header became optional"));

    assertThat(
            RunPlanningCoordinator.expansionPhaseFailure(
                new ExecutionException(new java.util.concurrent.CompletionException(rejection))))
        .isSameAs(rejection);
  }

  @Test
  void anyExpansionFailureFailsClosedInsteadOfFabricatingAWorkerTask() {
    var expanded = new java.util.concurrent.ConcurrentHashMap<String, List<PlannedTask>>();
    var failures = new java.util.concurrent.ConcurrentHashMap<String, Throwable>();
    PlannedTask task = task("T1");
    IllegalStateException providerFailure = new IllegalStateException("provider payload invalid");

    assertThatThrownBy(
            () ->
                RunPlanningCoordinator.recordExpansionFailure(
                    "run-1", task, providerFailure, expanded, failures))
        .isInstanceOf(java.util.concurrent.CompletionException.class)
        .hasCause(providerFailure);
    assertThat(expanded).isEmpty();
    assertThat(failures).containsEntry("T1", providerFailure);
  }

  @Test
  void actualTimeoutKeepsDeadlineMessage() {
    assertThat(RunPlanningCoordinator.expansionPhaseFailure(new TimeoutException()))
        .hasMessage("LARGE expansion phase exceeded its 20-minute deadline");
  }

  @Test
  void rejectedCandidateIsRetainedAsDiagnosticWithoutPublishingAnExecutablePlan() throws Exception {
    PipelineRunRepository repository = mock(PipelineRunRepository.class);
    PipelineRun run = PipelineRun.builder().build();
    when(repository.findById("run-1")).thenReturn(Optional.of(run));
    ObjectMapper mapper = new ObjectMapper();
    RunPlanningCoordinator coordinator =
        new RunPlanningCoordinator(
            repository,
            mapper,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    Plan candidate =
        Plan.builder()
            .planTitle("Rejected normalized candidate")
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("Contradictory task")
                        .description("diagnostic evidence")
                        .build()))
            .build();

    coordinator.persistRejectedPlanDiagnostic("run-1", candidate);

    assertThat(mapper.readTree(run.getPlanOutlineJson()).path("planTitle").asText())
        .isEqualTo("Rejected normalized candidate");
    assertThat(run.getPlanJson()).isNull();
    verify(repository).save(run);
  }

  @Test
  void expansionDiagnosticIncludesTheRejectedProviderTaskBody() {
    PlannedTask skeletonTask = task("T15");
    Plan skeleton =
        Plan.builder().planTitle("Warehouse").tasks(List.of(skeletonTask, task("T16"))).build();
    PlannedTask rejected =
        PlannedTask.builder()
            .id("T15")
            .title("Acceptance suite")
            .description("IdempotencyRecord.idempotency_key contradicted the schema")
            .build();
    PlannedTask successful =
        PlannedTask.builder()
            .id("T16")
            .title("Packaging")
            .description("Complete packaging contract")
            .build();

    Plan diagnostic =
        RunPlanningCoordinator.diagnosticExpansionPlan(
            skeleton,
            java.util.Map.of("T16", List.of(successful)),
            java.util.Map.of(
                "T15",
                new TaskExpanderAgent.ExpansionContractException(
                    "T15", rejected, List.of("canonical key/scope"))));

    assertThat(diagnostic.tasks())
        .extracting(PlannedTask::description)
        .containsExactly(
            "IdempotencyRecord.idempotency_key contradicted the schema",
            "Complete packaging contract");
  }

  @Test
  void epicSubSpecCarriesExactOwnedParentRequirements() {
    PlannedTask epic =
        PlannedTask.builder()
            .id("T4")
            .title("Implement inventory lifecycle")
            .meetsRequirements(List.of("R7", "R9"))
            .taskType(PlannedTask.TaskType.EPIC)
            .build();
    Plan parent =
        Plan.builder()
            .planSummary("Warehouse system")
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R7",
                        "Transfers reject inactive warehouses",
                        RequirementSpec.RequirementKind.FUNCTIONAL),
                    new RequirementSpec(
                        "R8",
                        "Render an accessible dashboard",
                        RequirementSpec.RequirementKind.NON_FUNCTIONAL),
                    new RequirementSpec(
                        "R9",
                        "Adjustments require a reason",
                        RequirementSpec.RequirementKind.FUNCTIONAL)))
            .build();

    assertThat(RunPlanningCoordinator.buildEpicSubSpec(epic, parent))
        .contains("R7: Transfers reject inactive warehouses")
        .contains("R9: Adjustments require a reason")
        .doesNotContain("R8: Render an accessible dashboard")
        .contains("do not renumber");
  }

  @Test
  void epicChildrenMustPreserveAndCoverParentRequirementIds() {
    PlannedTask epic =
        PlannedTask.builder()
            .id("T4")
            .title("Implement inventory lifecycle")
            .meetsRequirements(List.of("R7", "R9"))
            .taskType(PlannedTask.TaskType.EPIC)
            .build();

    assertThat(
            RunPlanningCoordinator.validateEpicChildRequirementOwnership(
                epic, List.of(taskWithRequirements("C1", "R7"), taskWithRequirements("C2", "R9"))))
        .isEmpty();
    assertThat(
            RunPlanningCoordinator.validateEpicChildRequirementOwnership(
                epic, List.of(taskWithRequirements("C1", "R1"), taskWithRequirements("C2", "R7"))))
        .anyMatch(violation -> violation.contains("R1"))
        .anyMatch(violation -> violation.contains("R9"));
  }

  private static List<List<String>> ids(List<List<PlannedTask>> waves) {
    return waves.stream().map(wave -> wave.stream().map(PlannedTask::id).toList()).toList();
  }

  private static PlannedTask task(String id, String... dependsOn) {
    return PlannedTask.builder()
        .id(id)
        .title(id)
        .dependsOn(List.of(dependsOn))
        .taskType(PlannedTask.TaskType.LEAF)
        .complexity(PlannedTask.Complexity.MEDIUM)
        .build();
  }

  private static PlannedTask taskWithRequirements(String id, String... requirementIds) {
    return PlannedTask.builder()
        .id(id)
        .title(id)
        .meetsRequirements(List.of(requirementIds))
        .taskType(PlannedTask.TaskType.LEAF)
        .complexity(PlannedTask.Complexity.MEDIUM)
        .build();
  }
}
