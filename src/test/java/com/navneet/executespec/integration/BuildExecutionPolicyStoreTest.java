package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;

class BuildExecutionPolicyStoreTest {
  private final PipelineRunRepository runs = mock(PipelineRunRepository.class);
  private final BuildExecutionPolicyStore store =
      new BuildExecutionPolicyStore(runs, new ObjectMapper());

  @Test
  void replayKeepsOriginalDesignAndCrashRecoveryKeepsConfirmationPolicy() {
    PipelineRun run = run();
    store.freeze(run.getRunId(), "original design and standards", true, true, "WORKHORSE");
    var replay = store.freeze(run.getRunId(), "changed mutable standards", true, true, "WORKHORSE");
    assertThat(replay.designBlock()).isEqualTo("original design and standards");
    assertThat(store.require(run.getRunId())).isEqualTo(replay);
    assertThat(replay.requireConfirmation()).isTrue();
    assertThat(replay.requireBenchmark()).isTrue();
  }

  @Test
  void recoveryRefusesMissingPolicyChangedPlanOrWeakenedConfirmation() {
    PipelineRun run = run();
    assertThatThrownBy(() -> store.require(run.getRunId())).hasMessageContaining("POLICY_MISSING");
    store.freeze(run.getRunId(), "design", true, true, null);
    assertThatThrownBy(() -> store.freeze(run.getRunId(), "design", false, true, null))
        .hasMessageContaining("POLICY_CHANGED");
    run.setPlanJson("{\"tasks\":[\"changed\"]}");
    assertThatThrownBy(() -> store.require(run.getRunId())).hasMessageContaining("PLAN_MISMATCH");
  }

  private PipelineRun run() {
    PipelineRun run = new PipelineRun();
    run.setRunId("policy-run");
    run.setPlanJson("{\"tasks\":[]}");
    when(runs.findById(run.getRunId())).thenReturn(Optional.of(run));
    when(runs.findByIdForTaskContinuation(run.getRunId())).thenReturn(Optional.of(run));
    return run;
  }
}
