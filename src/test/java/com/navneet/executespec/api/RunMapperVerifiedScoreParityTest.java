package com.navneet.executespec.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.api.dto.RunDetail;
import com.navneet.executespec.api.dto.RunSummary;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.billing.repository.ManagedAiUsageEventRepository;
import com.navneet.executespec.codeintel.repository.RunCodeMapRepository;
import com.navneet.executespec.cost.RunEffectiveRouteService;
import com.navneet.executespec.cost.RunStepUsageRepository;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.ValidationReportScoreProjection;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.IntegrationRunRepository;
import com.navneet.executespec.testgen.repository.TestPlanRepository;

import org.junit.jupiter.api.Test;

/** Regression for run 20fbd314: list/detail exposed 100 while the report correctly exposed 84. */
class RunMapperVerifiedScoreParityTest {

  private static final String RUN_ID = "run-score-parity";
  private static final String ACCURACY_WITH_UNVERIFIED_REQUIREMENT =
      """
      {
        "specAdherenceScore": 100,
        "missingRequirements": [],
        "partialRequirements": [],
        "unknownRequirements": ["Test execution was not independently verified"],
        "specEvidenceStatus": "CITED",
        "specEvidenceLocations": ["src/main/java/example/Todo.java:1"]
      }
      """;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final IntegrationRunRepository integrationRuns = mock(IntegrationRunRepository.class);
  private final RunCodeMapRepository codeMaps = mock(RunCodeMapRepository.class);
  private final ValidationReportRepository reports = mock(ValidationReportRepository.class);
  private final ManagedAiUsageEventRepository managedUsage =
      mock(ManagedAiUsageEventRepository.class);
  private final AgentTaskRepository tasks = mock(AgentTaskRepository.class);

  @Test
  void listDetailAndReportProjectionExposeTheSameEvidenceCappedScore() {
    PipelineRun run = new PipelineRun();
    run.setRunId(RUN_ID);
    run.setStatus("COMPLETE");
    ValidationReport report =
        ValidationReport.builder()
            .runId(RUN_ID)
            .verifiedScore(100)
            .accuracyJson(ACCURACY_WITH_UNVERIFIED_REQUIREMENT)
            .build();

    when(reports.findScoresByRunIds(List.of(RUN_ID)))
        .thenReturn(
            List.<Object[]>of(new Object[] {RUN_ID, 100, ACCURACY_WITH_UNVERIFIED_REQUIREMENT}));
    when(tasks.findByRunIdIn(List.of(RUN_ID))).thenReturn(List.of());
    when(reports.findByRunId(RUN_ID)).thenReturn(Optional.of(report));
    when(integrationRuns.findByRunId(RUN_ID)).thenReturn(Optional.empty());
    when(codeMaps.findByRunId(RUN_ID)).thenReturn(Optional.empty());

    RunMapper mapper = mapper();
    RunSummary summary = mapper.toSummaries(List.of(run)).getFirst();
    RunDetail detail = mapper.toDetail(run, List.of());
    Integer reportScore =
        ValidationReportScoreProjection.effectiveVerifiedScore(report, objectMapper);

    assertThat(summary.verifiedScore()).isEqualTo(84);
    assertThat(detail.getVerifiedScore()).isEqualTo(84);
    assertThat(reportScore).isEqualTo(84);
  }

  @Test
  void scoreWithoutAdmittedEvidenceGapRemainsUnchangedAcrossListAndDetail() {
    PipelineRun run = new PipelineRun();
    run.setRunId(RUN_ID);
    run.setStatus("COMPLETE");
    String completeAccuracy =
        "{\"specAdherenceScore\":100,\"missingRequirements\":[],"
            + "\"partialRequirements\":[],\"unknownRequirements\":[]}";
    ValidationReport report =
        ValidationReport.builder()
            .runId(RUN_ID)
            .verifiedScore(100)
            .accuracyJson(completeAccuracy)
            .build();

    when(reports.findScoresByRunIds(List.of(RUN_ID)))
        .thenReturn(List.<Object[]>of(new Object[] {RUN_ID, 100, completeAccuracy}));
    when(tasks.findByRunIdIn(List.of(RUN_ID))).thenReturn(List.of());
    when(reports.findByRunId(RUN_ID)).thenReturn(Optional.of(report));
    when(integrationRuns.findByRunId(RUN_ID)).thenReturn(Optional.empty());
    when(codeMaps.findByRunId(RUN_ID)).thenReturn(Optional.empty());

    RunMapper mapper = mapper();

    assertThat(mapper.toSummaries(List.of(run)).getFirst().verifiedScore()).isEqualTo(100);
    assertThat(mapper.toDetail(run, List.of()).getVerifiedScore()).isEqualTo(100);
  }

  @Test
  void summaryAndDetailExposeTheSameLeakSafeTerminalOutcome() {
    PipelineRun run = new PipelineRun();
    run.setRunId(RUN_ID);
    run.setStatus("FAILED");
    run.setStatusReason("WORKER_BUDGET_EXCEEDED");
    run.setFailureReason("provider stack api_key=secret");
    run.setActualCostUsd(0.42);
    run.setHardCapUsd(0.50);

    when(reports.findScoresByRunIds(List.of(RUN_ID))).thenReturn(List.of());
    when(tasks.findByRunIdIn(List.of(RUN_ID))).thenReturn(List.of());
    when(reports.findByRunId(RUN_ID)).thenReturn(Optional.empty());
    when(integrationRuns.findByRunId(RUN_ID)).thenReturn(Optional.empty());
    when(codeMaps.findByRunId(RUN_ID)).thenReturn(Optional.empty());

    RunMapper mapper = mapper();
    RunSummary summary = mapper.toSummaries(List.of(run)).getFirst();
    RunDetail detail = mapper.toDetail(run, List.of());

    assertThat(summary.terminalOutcome()).isEqualTo(detail.getTerminalOutcome());
    assertThat(summary.terminalOutcome().code()).isEqualTo("WORKER_BUDGET_EXCEEDED");
    assertThat(summary.terminalOutcome().category())
        .isEqualTo(com.navneet.executespec.run.outcome.RunTerminalOutcome.Category.ACTION_REQUIRED);
    assertThat(summary.terminalOutcome().impact().actualCostUsd()).isEqualTo(0.42);
    assertThat(summary.terminalOutcome().impact().capUsd()).isEqualTo(0.50);
    assertThat(detail.getFailureReason())
        .isEqualTo(summary.terminalOutcome().customerMessage())
        .doesNotContain("provider stack", "api_key", "secret");
  }

  private RunMapper mapper() {
    return new RunMapper(
        integrationRuns,
        mock(ProjectRepository.class),
        mock(AppUserRepository.class),
        mock(TestPlanRepository.class),
        codeMaps,
        reports,
        managedUsage,
        new RunEffectiveRouteService(mock(RunStepUsageRepository.class)),
        tasks,
        null,
        objectMapper,
        null,
        null);
  }
}
