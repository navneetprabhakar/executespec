package com.navneet.executespec.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.api.dto.TaskSummary;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;

class RunMapperTaskProofStatusTest {

  private final RunMapper mapper =
      new RunMapper(null, null, null, null, null, null, null, null, null, new ObjectMapper());

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

  @Test
  void intermediateTaskDeferredToFinalValidationRemainsCompleteWithoutOverclaimingDone() {
    AgentTask deferred =
        AgentTask.builder()
            .taskId("T1")
            .status("COMPLETE")
            .goalDonenessJson(
                "{\"state\":\"NOT_DONE_BUT_PROGRESSING\","
                    + "\"evidence\":[\"downstreamValidationDeferred=true\"]}")
            .build();

    TaskSummary summary = mapper.toTaskSummary(deferred);

    assertThat(summary.status()).isEqualTo("COMPLETE");
    assertThat(summary.goalDoneness().path("state").asText()).isEqualTo("NOT_DONE_BUT_PROGRESSING");
    assertThat(mapper.displayRunStatus("COMPLETE", java.util.List.of(deferred)))
        .isEqualTo("COMPLETE");
  }

  @Test
  void completeRunWithAnyUnprovenTaskIsExposedAsPartial() {
    PipelineRun run = new PipelineRun();
    run.setRunId("run-1");
    run.setStatus("COMPLETE");
    run.setStatusReason("CLEAN_COMPLETION");
    AgentTask uncertain =
        AgentTask.builder()
            .taskId("T1")
            .status("COMPLETE")
            .goalDonenessJson("{\"state\":\"NOT_DONE_BUT_PROGRESSING\"}")
            .build();

    assertThat(mapper.displayRunStatus(run.getStatus(), java.util.List.of(uncertain)))
        .isEqualTo("PARTIAL");
  }

  @Test
  void completeRunRemainsCompleteWhenEveryTaskIsProvenOrLegacyComplete() {
    AgentTask proven =
        AgentTask.builder()
            .taskId("T1")
            .status("COMPLETE")
            .goalDonenessJson("{\"state\":\"DONE\"}")
            .build();
    AgentTask legacy = AgentTask.builder().taskId("T2").status("COMPLETE").build();

    assertThat(mapper.displayRunStatus("COMPLETE", java.util.List.of(proven, legacy)))
        .isEqualTo("COMPLETE");
  }

  @Test
  void unprovenRunCannotExposeAPassingVerifiedScore() {
    assertThat(RunMapper.displayVerifiedScore(97, "PARTIAL")).isEqualTo(84);
    assertThat(RunMapper.displayVerifiedScore(97, "COMPLETE")).isEqualTo(97);
  }
}
