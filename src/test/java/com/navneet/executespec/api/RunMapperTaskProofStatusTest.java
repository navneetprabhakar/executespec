package com.navneet.executespec.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.api.dto.TaskSummary;
import com.navneet.executespec.state.entity.AgentTask;

import org.junit.jupiter.api.Test;

class RunMapperTaskProofStatusTest {

  private final RunMapper mapper =
      new RunMapper(null, null, null, null, null, null, null, null, new ObjectMapper());

  @Test
  void completeWorkerBoundaryWithUnprovenGoalIsExposedAsPartial() {
    AgentTask task =
        AgentTask.builder()
            .taskId("T1")
            .status("COMPLETE")
            .goalDonenessJson(
                "{\"state\":\"NOT_DONE_BUT_PROGRESSING\","
                    + "\"reason\":\"validation failed, but meaningful progress exists\"}")
            .build();

    TaskSummary summary = mapper.toTaskSummary(task);

    assertThat(summary.status()).isEqualTo("PARTIAL");
    assertThat(summary.failureReason())
        .isEqualTo("validation failed, but meaningful progress exists");
  }

  @Test
  void provenAndLegacyCompleteTasksRemainComplete() {
    AgentTask proven =
        AgentTask.builder()
            .taskId("T1")
            .status("COMPLETE")
            .goalDonenessJson("{\"state\":\"DONE\"}")
            .build();
    AgentTask legacy = AgentTask.builder().taskId("T2").status("COMPLETE").build();

    assertThat(mapper.toTaskSummary(proven).status()).isEqualTo("COMPLETE");
    assertThat(mapper.toTaskSummary(legacy).status()).isEqualTo("COMPLETE");
  }
}
