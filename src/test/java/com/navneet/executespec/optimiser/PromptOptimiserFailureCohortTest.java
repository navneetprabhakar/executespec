package com.navneet.executespec.optimiser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;

/**
 * RR-1299 — the optimiser's failure-rate cohort must actually contain the failures it monitors. A
 * fully-failed run terminalizes as FAILED/PARTIAL (never COMPLETE), so a run's own terminal status,
 * not just its task rows, decides whether it is a failure signal.
 */
class PromptOptimiserFailureCohortTest {

  private final StateStore stateStore = mock(StateStore.class);
  private final PromptOptimiserAgent agent =
      new PromptOptimiserAgent(
          mock(PromptPatternAnalyser.class),
          mock(PromptAuditService.class),
          mock(LlmRouter.class),
          mock(ModelResolver.class),
          new PromptOptimiserProperties(),
          stateStore);

  @Test
  void aTerminallyFailedRunCountsAsFailureWithoutInspectingTasks() {
    PipelineRun failed = PipelineRun.builder().runId("r-failed").status("FAILED").build();
    assertThat(agent.runCountedAsFailure(failed)).isTrue();
  }

  @Test
  void aPartialRunCountsAsFailure() {
    PipelineRun partial = PipelineRun.builder().runId("r-partial").status("PARTIAL").build();
    assertThat(agent.runCountedAsFailure(partial)).isTrue();
  }

  @Test
  void aCompleteRunWithAFailedTaskCountsAsFailure() {
    PipelineRun complete = PipelineRun.builder().runId("r-c1").status("COMPLETE").build();
    when(stateStore.getTasksForRun("r-c1"))
        .thenReturn(List.of(AgentTask.builder().taskId("t1").status("FAILED").build()));
    assertThat(agent.runCountedAsFailure(complete)).isTrue();
  }

  @Test
  void aCleanCompleteRunIsNotAFailure() {
    PipelineRun complete = PipelineRun.builder().runId("r-c2").status("COMPLETE").build();
    lenient()
        .when(stateStore.getTasksForRun("r-c2"))
        .thenReturn(List.of(AgentTask.builder().taskId("t1").status("COMPLETE").build()));
    assertThat(agent.runCountedAsFailure(complete)).isFalse();
  }
}
