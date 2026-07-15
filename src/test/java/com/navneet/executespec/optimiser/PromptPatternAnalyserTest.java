package com.navneet.executespec.optimiser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;

class PromptPatternAnalyserTest {

  @Test
  void doesNotEmitRemovedWorkerPromptTarget() {
    StateStore stateStore = mock(StateStore.class);
    PromptOptimiserProperties properties = new PromptOptimiserProperties();
    properties.setAnalysisWindowRuns(2);
    properties.setPatternMinOccurrences(1);

    PipelineRun first = PipelineRun.builder().runId("run-1").build();
    PipelineRun second = PipelineRun.builder().runId("run-2").build();
    AgentTask task = AgentTask.builder().lastLlmFailure("FILE_LOCK").build();
    when(stateStore.getRecentCompletedRuns(2)).thenReturn(List.of(first, second));
    when(stateStore.getTasksForRun("run-1")).thenReturn(List.of(task));
    when(stateStore.getTasksForRun("run-2")).thenReturn(List.of(task));

    PromptPatterns patterns = new PromptPatternAnalyser(stateStore, properties).analyse();

    assertThat(patterns.patternsByPrompt()).doesNotContainKey("worker.txt");
    assertThat(patterns.hasPatterns()).isFalse();
  }
}
