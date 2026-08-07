package com.navneet.executespec.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.api.dto.TaskSummary;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;

class RunMapperTaskProofStatusTest {

  private final RunMapper mapper =
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
          null,
          null);

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
    assertThat(RunDisplayPolicy.displayVerifiedScore(97, "PARTIAL")).isEqualTo(84);
    assertThat(RunDisplayPolicy.displayVerifiedScore(97, "COMPLETE")).isEqualTo(97);
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
                new com.navneet.executespec.config.AgentProperties()),
            null);
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
  void successSummaryProseIsSuppressedFromTerminalSuccessFailureReasonProjection() {
    // Terminal-success display: worker success prose must not surface as a failure reason.
    assertThat(
            RunDisplayPolicy.displayRunFailureReason(
                "Implemented the endpoint and added both tests.", "COMPLETE", "COMPLETE"))
        .isNull();
    // Structured codes survive, including the bounded publication-block marker.
    assertThat(
            RunDisplayPolicy.displayRunFailureReason(
                "PUBLICATION_BLOCKED[PUBLICATION_EVIDENCE_STALE]: gate=EVIDENCE_FRESHNESS",
                "COMPLETE",
                "COMPLETE"))
        .startsWith("PUBLICATION_BLOCKED[PUBLICATION_EVIDENCE_STALE]");
    assertThat(
            RunDisplayPolicy.displayRunFailureReason(
                "VALIDATION_FAILED: detail here", "COMPLETE", "COMPLETE"))
        .isEqualTo("VALIDATION_FAILED: detail here");
    // FAILED/PARTIAL keep the persisted value untouched.
    assertThat(
            RunDisplayPolicy.displayRunFailureReason(
                "free prose about what failed", "FAILED", "FAILED"))
        .isEqualTo("free prose about what failed");
    assertThat(
            RunDisplayPolicy.displayRunFailureReason(
                "free prose about what failed", "PARTIAL", "PARTIAL"))
        .isEqualTo("free prose about what failed");
  }

  @Test
  void terminalSuccessDetailDoesNotPresentSuccessTextAsAFailureReason() {
    RunMapper detailMapper = detailCapableMapper(null, null);
    PipelineRun run = new PipelineRun();
    run.setRunId("run-clean");
    run.setStatus("COMPLETE");
    run.setStatusReason("CLEAN_COMPLETION");
    run.setFailureReason("Implemented the endpoint and added both tests.");

    var detail = detailMapper.toDetail(run, java.util.List.of());

    assertThat(detail.getStatus()).isEqualTo("COMPLETE");
    assertThat(detail.getFailureReason()).isNull();
    // FAILED runs keep the customer-safe terminal message projection.
    PipelineRun failed = new PipelineRun();
    failed.setRunId("run-failed");
    failed.setStatus("FAILED");
    failed.setStatusReason("FINAL_BUILD_FAILED");
    failed.setFailureReason("raw builder stack");
    assertThat(detailMapper.toDetail(failed, java.util.List.of()).getFailureReason())
        .contains("did not compile")
        .doesNotContain("raw builder stack");
  }

  @Test
  void runListPathAppliesTheSameFailClosedTaskProofProjection() {
    var reportRepo = org.mockito.Mockito.mock(ValidationReportRepository.class);
    var taskRepo =
        org.mockito.Mockito.mock(
            com.navneet.executespec.state.repository.AgentTaskRepository.class);
    org.mockito.Mockito.when(reportRepo.findScoresByRunIds(org.mockito.Mockito.anyList()))
        .thenReturn(java.util.List.of());
    org.mockito.Mockito.when(taskRepo.findByRunIdIn(org.mockito.Mockito.anyList()))
        .thenReturn(
            java.util.List.of(
                AgentTask.builder()
                    .taskId("T1")
                    .runId("run-list-1")
                    .status("COMPLETE")
                    .goalDonenessJson("{\"state\":\"NOT_DONE_BUT_PROGRESSING\"}")
                    .build()));
    RunMapper listMapper =
        new RunMapper(
            null,
            null,
            null,
            null,
            null,
            reportRepo,
            null,
            null,
            taskRepo,
            null,
            new ObjectMapper(),
            null,
            null);
    PipelineRun run = new PipelineRun();
    run.setRunId("run-list-1");
    run.setStatus("COMPLETE");

    var summaries = listMapper.toSummaries(java.util.List.of(run));

    assertThat(summaries).hasSize(1);
    assertThat(summaries.get(0).status()).isEqualTo("PARTIAL");
    assertThat(summaries.get(0).statusReason()).isEqualTo("TASK_PROOF_INCOMPLETE");
  }

  @Test
  void singleRunSummaryLoadsTaskProofInsteadOfTrustingPersistedComplete() {
    var taskRepo =
        org.mockito.Mockito.mock(
            com.navneet.executespec.state.repository.AgentTaskRepository.class);
    org.mockito.Mockito.when(taskRepo.findByRunId("run-single-1"))
        .thenReturn(
            java.util.List.of(
                AgentTask.builder()
                    .taskId("T1")
                    .runId("run-single-1")
                    .status("COMPLETE")
                    .goalDonenessJson("{\"state\":\"NOT_DONE_BUT_PROGRESSING\"}")
                    .build()));
    RunMapper singleMapper =
        new RunMapper(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            taskRepo,
            null,
            new ObjectMapper(),
            null,
            null);
    PipelineRun run = new PipelineRun();
    run.setRunId("run-single-1");
    run.setStatus("COMPLETE");

    var summary = singleMapper.toSummary(run);

    assertThat(summary.status()).isEqualTo("PARTIAL");
    assertThat(summary.statusReason()).isEqualTo("TASK_PROOF_INCOMPLETE");
  }

  private static final String CONTINUATION_WITH_POLICY =
      "{\"decision\":\"STEP_UP_MODEL\",\"reasons\":[\"quality gap\"],"
          + "\"policy\":{\"rankingPolicy\":\"quality_desc_then_cost_asc_then_latency_asc\","
          + "\"rankingReasons\":[\"candidate evidence\"],\"topEligibleCandidates\":[]}}";

  @Test
  void operatorContinuationPolicyIsWithheldWithAnExplicitMarkerForNonPrivilegedViewers() {
    AgentTask task =
        AgentTask.builder()
            .taskId("T1")
            .status("COMPLETE")
            .continuationDecisionJson(CONTINUATION_WITH_POLICY)
            .build();

    TaskSummary restricted = mapper.toTaskSummary(task);

    // Customer-relevant fields stay visible.
    assertThat(restricted.continuationDecision().path("decision").asText())
        .isEqualTo("STEP_UP_MODEL");
    // Operator internals are withheld with an explicit marker, never silently removed.
    var policy = restricted.continuationDecision().path("policy");
    assertThat(policy.path("included").asBoolean(true)).isFalse();
    assertThat(policy.path("restrictedReason").asText()).isEqualTo("ORG_BILLING_ROLE_REQUIRED");
    assertThat(policy.has("rankingPolicy")).isFalse();
    assertThat(policy.has("topEligibleCandidates")).isFalse();
  }

  @Test
  void privilegedProjectionKeepsTheFullContinuationPolicyEvidence() {
    AgentTask task =
        AgentTask.builder()
            .taskId("T1")
            .status("COMPLETE")
            .continuationDecisionJson(CONTINUATION_WITH_POLICY)
            .build();

    TaskSummary privileged = mapper.toTaskSummary(task, true);

    assertThat(privileged.continuationDecision().path("policy").path("rankingPolicy").asText())
        .isEqualTo("quality_desc_then_cost_asc_then_latency_asc");
    assertThat(privileged.continuationDecision().path("policy").has("rankingReasons")).isTrue();
  }

  @Test
  void detailViewerAuthorityGatesTheOperatorContinuationPolicy() {
    AgentTask task =
        AgentTask.builder()
            .taskId("T1")
            .runId("run-viewer-1")
            .status("COMPLETE")
            .continuationDecisionJson(CONTINUATION_WITH_POLICY)
            .build();
    PipelineRun run = new PipelineRun();
    run.setRunId("run-viewer-1");
    run.setStatus("COMPLETE");

    com.navneet.executespec.auth.entity.AppUser admin =
        com.navneet.executespec.auth.entity.AppUser.builder().build();
    admin.setGlobalRole("SUPER_ADMIN");
    var adminViewer = new com.navneet.executespec.auth.security.AppUserDetails(admin, null);
    com.navneet.executespec.auth.entity.AppUser developer =
        com.navneet.executespec.auth.entity.AppUser.builder().build();
    developer.setGlobalRole("USER");
    var developerViewer = new com.navneet.executespec.auth.security.AppUserDetails(developer, null);

    RunMapper detailMapper = detailCapableMapper("run-viewer-1", null);

    var adminDetail = detailMapper.toDetail(run, java.util.List.of(task), adminViewer);
    assertThat(
            adminDetail
                .getTasks()
                .get(0)
                .continuationDecision()
                .path("policy")
                .has("rankingPolicy"))
        .isTrue();

    var developerDetail = detailMapper.toDetail(run, java.util.List.of(task), developerViewer);
    var developerPolicy = developerDetail.getTasks().get(0).continuationDecision().path("policy");
    assertThat(developerPolicy.path("included").asBoolean(true)).isFalse();
    assertThat(developerPolicy.path("restrictedReason").asText())
        .isEqualTo("ORG_BILLING_ROLE_REQUIRED");

    // The viewer-less overload (SSE push path) is fail-closed.
    var sseDetail = detailMapper.toDetail(run, java.util.List.of(task));
    assertThat(
            sseDetail
                .getTasks()
                .get(0)
                .continuationDecision()
                .path("policy")
                .path("included")
                .asBoolean(true))
        .isFalse();
  }

  /** Mapper with the repository lookups toDetail requires stubbed to empty. */
  private RunMapper detailCapableMapper(String runId, String unused) {
    var integrationRepo =
        org.mockito.Mockito.mock(
            com.navneet.executespec.state.repository.IntegrationRunRepository.class);
    var codeMapRepo =
        org.mockito.Mockito.mock(
            com.navneet.executespec.codeintel.repository.RunCodeMapRepository.class);
    var reportRepo = org.mockito.Mockito.mock(ValidationReportRepository.class);
    var usageRepo =
        org.mockito.Mockito.mock(com.navneet.executespec.cost.RunStepUsageRepository.class);
    var managedAiRepo =
        org.mockito.Mockito.mock(
            com.navneet.executespec.billing.repository.ManagedAiUsageEventRepository.class);
    var testPlanRepo =
        org.mockito.Mockito.mock(
            com.navneet.executespec.testgen.repository.TestPlanRepository.class);
    org.mockito.Mockito.when(integrationRepo.findByRunId(org.mockito.Mockito.anyString()))
        .thenReturn(java.util.Optional.empty());
    org.mockito.Mockito.when(codeMapRepo.findByRunId(org.mockito.Mockito.anyString()))
        .thenReturn(java.util.Optional.empty());
    org.mockito.Mockito.when(reportRepo.findByRunId(org.mockito.Mockito.anyString()))
        .thenReturn(java.util.Optional.empty());
    return new RunMapper(
        integrationRepo,
        null,
        null,
        testPlanRepo,
        codeMapRepo,
        reportRepo,
        managedAiRepo,
        new com.navneet.executespec.cost.RunEffectiveRouteService(usageRepo),
        null,
        null,
        new ObjectMapper(),
        null,
        null);
  }

  @Test
  void failedRowWithoutStatusReasonFallsBackToTheStableFailureCodePrefixOnly() {
    assertThat(
            RunDisplayPolicy.displayRunStatusReason(
                null,
                "WORKSPACE_PREFLIGHT_FAILED:CLONE_MISSING: clone missing for acme/api",
                "FAILED",
                "FAILED"))
        .isEqualTo("WORKSPACE_PREFLIGHT_FAILED");
    // Free prose never becomes a fake status code and detail after the colon never leaks.
    assertThat(
            RunDisplayPolicy.displayRunStatusReason(
                null, "provider stack api_key=secret", "FAILED", "FAILED"))
        .isNull();
    // Non-failed rows keep their existing null semantics.
    assertThat(RunDisplayPolicy.displayRunStatusReason(null, "ANY:detail", "COMPLETE", "COMPLETE"))
        .isNull();
    // A persisted reason always wins.
    assertThat(
            RunDisplayPolicy.displayRunStatusReason(
                "CLEAN_COMPLETION", "IGNORED:detail", "COMPLETE", "COMPLETE"))
        .isEqualTo("CLEAN_COMPLETION");
  }
}
