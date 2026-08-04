package com.navneet.executespec.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.api.dto.TaskSummary;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;

class RunMapperTaskProofStatusTest {

  private final RunMapper mapper =
      new RunMapper(
          null, null, null, null, null, null, null, null, null, null, new ObjectMapper(), null);

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
  void taskSummarySeparatesRequestedAndEffectiveRouteWithPersistedExplanation() {
    AgentTask task =
        AgentTask.builder()
            .taskId("T1")
            .status("COMPLETE")
            .complexity("HIGH")
            .provider("ANTHROPIC")
            .model("claude-sonnet-5")
            .effectiveProvider("OPENAI")
            .effectiveModel("gpt-5.4")
            .effectiveRouteAttribution("LEDGER")
            .routingTier("PLANNING")
            .routingTaskCategory("BUG_FIX")
            .routingSource("ORG_OVERRIDE")
            .build();

    TaskSummary summary = mapper.toTaskSummary(task);

    assertThat(summary.provider()).isEqualTo("ANTHROPIC");
    assertThat(summary.model()).isEqualTo("claude-sonnet-5");
    assertThat(summary.effectiveProvider()).isEqualTo("OPENAI");
    assertThat(summary.effectiveModel()).isEqualTo("gpt-5.4");
    assertThat(summary.routeDiverged()).isTrue();
    assertThat(summary.routingTier()).isEqualTo("PLANNING");
    assertThat(summary.routingTaskCategory()).isEqualTo("BUG_FIX");
    assertThat(summary.routingSource()).isEqualTo("ORG_OVERRIDE");
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

  @Test
  void serverWorkspacePathsAreRedactedFromProjectedTaskDiagnostics() {
    RunMapper redactingMapper =
        new RunMapper(
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
            new ObjectMapper(),
            new com.navneet.executespec.security.WorkspacePathRedaction(
                new com.navneet.executespec.config.AgentProperties()));
    AgentTask task =
        AgentTask.builder()
            .taskId("T1")
            .status("FAILED")
            .lastLlmFailure("build failed under /opt/app/workspace/x/y")
            .goalDonenessJson(
                "{\"state\":\"NOT_DONE\",\"reason\":\"missing tests\","
                    + "\"incompleteItems\":[\"tests absent at /opt/app/workspace/x/y/tests\"],"
                    + "\"risks\":[\"artifacts left in /opt/app/workspace/x/y/tmp\"]}")
            .build();

    TaskSummary summary = redactingMapper.toTaskSummary(task);

    assertThat(summary.failureReason())
        .doesNotContain("/opt/app/workspace/x/y")
        .contains("[workspace path redacted]");
    assertThat(summary.goalDoneness().toString())
        .doesNotContain("/opt/app/workspace/x/y")
        .contains("[workspace path redacted]");
  }

  @Test
  void failedRowWithoutStatusReasonFallsBackToTheStableFailureCodePrefixOnly() {
    assertThat(
            RunMapper.displayRunStatusReason(
                null,
                "WORKSPACE_PREFLIGHT_FAILED:CLONE_MISSING: clone missing for acme/api",
                "FAILED",
                "FAILED"))
        .isEqualTo("WORKSPACE_PREFLIGHT_FAILED");
    // Free prose never becomes a fake status code and detail after the colon never leaks.
    assertThat(
            RunMapper.displayRunStatusReason(
                null, "provider stack api_key=secret", "FAILED", "FAILED"))
        .isNull();
    // Non-failed rows keep their existing null semantics.
    assertThat(RunMapper.displayRunStatusReason(null, "ANY:detail", "COMPLETE", "COMPLETE"))
        .isNull();
    // A persisted reason always wins.
    assertThat(
            RunMapper.displayRunStatusReason(
                "CLEAN_COMPLETION", "IGNORED:detail", "COMPLETE", "COMPLETE"))
        .isEqualTo("CLEAN_COMPLETION");
  }
}
