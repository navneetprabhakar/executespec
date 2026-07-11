package com.navneet.executespec.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;

class PipelineRunLocalExecutionIntentTest {

  @Test
  void defaultsToRemoteForLegacyAndNonLocalRuns() {
    assertThat(new PipelineRun().isLocalExecutionRequested()).isFalse();
    assertThat(PipelineRun.builder().runId("r1").build().isLocalExecutionRequested()).isFalse();
  }

  @Test
  void builderPersistsLocalIntent() {
    assertThat(
            PipelineRun.builder()
                .runId("r1")
                .localExecutionRequested(true)
                .build()
                .isLocalExecutionRequested())
        .isTrue();
  }
}
