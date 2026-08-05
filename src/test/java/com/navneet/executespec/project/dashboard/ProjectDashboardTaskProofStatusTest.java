package com.navneet.executespec.project.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.api.RunMapper;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.ValidationReportValidityService;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;

/**
 * The project dashboard's recent-run rows were the second task-summary mapping site bypassing the
 * 2026-07-24 task-proof contract: they projected the raw persisted run status, so a persisted
 * COMPLETE run with durable non-DONE goal evidence displayed COMPLETE while the run list and run
 * detail said PARTIAL. The dashboard must apply the same fail-closed projection.
 */
class ProjectDashboardTaskProofStatusTest {

  private static final String PROJECT_ID = "project-1";
  private static final String RUN_ID = "run-dashboard-1";

  @Test
  void dashboardRecentRunsApplyTheFailClosedTaskProofStatusProjection() {
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
    ValidationReportRepository reportRepo = mock(ValidationReportRepository.class);
    ValidationReportValidityService validity = mock(ValidationReportValidityService.class);
    var prRepo = mock(com.navneet.executespec.project.pr.PullRequestRecordRepository.class);
    var archService = mock(com.navneet.executespec.codeintel.ProjectArchitectureService.class);
    var staleness = mock(com.navneet.executespec.codeintel.StalenessService.class);
    var workspaceHealth =
        mock(com.navneet.executespec.project.service.WorkspaceHealthService.class);
    var postMerge = mock(com.navneet.executespec.project.pr.PostMergeCascadeService.class);
    var estimation = mock(com.navneet.executespec.project.estimation.EstimationService.class);
    var techDebt = mock(com.navneet.executespec.project.techdebt.TechDebtService.class);
    var techDebtFix = mock(com.navneet.executespec.project.techdebt.TechDebtFixService.class);
    var execRunRepo =
        mock(com.navneet.executespec.execution.repository.ExecutionRunRepository.class);
    var codeReviewRepo =
        mock(com.navneet.executespec.codereview.repository.CodeReviewScanRepository.class);
    var prReviewRepo = mock(com.navneet.executespec.prreview.repository.PrReviewRepository.class);
    var repoArchRepo =
        mock(com.navneet.executespec.codeintel.repository.RepoArchitectureDocRepository.class);
    var projectArchRepo =
        mock(com.navneet.executespec.codeintel.repository.ProjectArchitectureDocRepository.class);
    var generatedDocRepo =
        mock(com.navneet.executespec.codeintel.repository.ProjectGeneratedDocRepository.class);
    var guard = mock(com.navneet.executespec.project.security.ProjectAccessGuard.class);
    var projectRepository = mock(com.navneet.executespec.auth.repository.ProjectRepository.class);
    AgentTaskRepository taskRepo = mock(AgentTaskRepository.class);
    RunMapper runMapper =
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

    PipelineRun run = new PipelineRun();
    run.setRunId(RUN_ID);
    run.setStatus("COMPLETE");
    run.setRunTitle("Ship the endpoint");

    when(runRepo.findByProjectIdOrderByStartedAtDesc(eq(PROJECT_ID), any()))
        .thenReturn(List.of(run));
    when(runRepo.sumTotalTokensByProjectId(PROJECT_ID)).thenReturn(0L);
    when(runRepo.sumActualCostByProjectId(PROJECT_ID)).thenReturn(0.0);
    when(execRunRepo.sumTotalActualTokensByProjectId(PROJECT_ID)).thenReturn(0L);
    when(execRunRepo.sumTotalCostUsdByProjectId(PROJECT_ID)).thenReturn(null);
    when(reportRepo.findAllByProjectId(PROJECT_ID)).thenReturn(List.of());
    when(validity.aggregate(anyList()))
        .thenReturn(new ValidationReportValidityService.Aggregate(List.of(), 0.0, 0));
    when(prRepo.findByRunIdOrderByCreatedAtAsc(anyString())).thenReturn(List.of());
    when(archService.latest(PROJECT_ID)).thenReturn(Optional.empty());
    when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());
    when(taskRepo.findByRunIdIn(List.of(RUN_ID)))
        .thenReturn(
            List.of(
                AgentTask.builder()
                    .taskId("T1")
                    .runId(RUN_ID)
                    .status("COMPLETE")
                    .goalDonenessJson("{\"state\":\"NOT_DONE_BUT_PROGRESSING\"}")
                    .build()));

    ProjectDashboardController controller =
        new ProjectDashboardController(
            runRepo,
            reportRepo,
            validity,
            prRepo,
            mock(com.navneet.executespec.project.pr.PullRequestRecordService.class),
            archService,
            staleness,
            workspaceHealth,
            postMerge,
            estimation,
            techDebt,
            techDebtFix,
            execRunRepo,
            codeReviewRepo,
            prReviewRepo,
            repoArchRepo,
            projectArchRepo,
            generatedDocRepo,
            guard,
            projectRepository,
            runMapper,
            taskRepo);

    var dto = controller.dashboard(PROJECT_ID, null).getBody();

    assertThat(dto).isNotNull();
    assertThat(dto.recentTasks()).hasSize(1);
    assertThat(dto.recentTasks().get(0).status())
        .as("persisted COMPLETE beside durable non-DONE goal evidence must display PARTIAL")
        .isEqualTo("PARTIAL");
  }

  @Test
  void dashboardKeepsCompleteWhenEveryTaskCarriesProvenOrLegacyGoalEvidence() {
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
    ValidationReportRepository reportRepo = mock(ValidationReportRepository.class);
    ValidationReportValidityService validity = mock(ValidationReportValidityService.class);
    var prRepo = mock(com.navneet.executespec.project.pr.PullRequestRecordRepository.class);
    var archService = mock(com.navneet.executespec.codeintel.ProjectArchitectureService.class);
    AgentTaskRepository taskRepo = mock(AgentTaskRepository.class);
    var execRunRepo =
        mock(com.navneet.executespec.execution.repository.ExecutionRunRepository.class);
    var projectRepository = mock(com.navneet.executespec.auth.repository.ProjectRepository.class);
    RunMapper runMapper =
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

    PipelineRun run = new PipelineRun();
    run.setRunId(RUN_ID);
    run.setStatus("COMPLETE");

    when(runRepo.findByProjectIdOrderByStartedAtDesc(eq(PROJECT_ID), any()))
        .thenReturn(List.of(run));
    when(runRepo.sumTotalTokensByProjectId(PROJECT_ID)).thenReturn(0L);
    when(runRepo.sumActualCostByProjectId(PROJECT_ID)).thenReturn(0.0);
    when(execRunRepo.sumTotalActualTokensByProjectId(PROJECT_ID)).thenReturn(0L);
    when(reportRepo.findAllByProjectId(PROJECT_ID)).thenReturn(List.of());
    when(validity.aggregate(anyList()))
        .thenReturn(new ValidationReportValidityService.Aggregate(List.of(), 0.0, 0));
    when(prRepo.findByRunIdOrderByCreatedAtAsc(anyString())).thenReturn(List.of());
    when(archService.latest(PROJECT_ID)).thenReturn(Optional.empty());
    when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());
    when(taskRepo.findByRunIdIn(List.of(RUN_ID)))
        .thenReturn(
            List.of(
                AgentTask.builder()
                    .taskId("T1")
                    .runId(RUN_ID)
                    .status("COMPLETE")
                    .goalDonenessJson("{\"state\":\"DONE\"}")
                    .build()));

    ProjectDashboardController controller =
        new ProjectDashboardController(
            runRepo,
            reportRepo,
            validity,
            prRepo,
            mock(com.navneet.executespec.project.pr.PullRequestRecordService.class),
            archService,
            mock(com.navneet.executespec.codeintel.StalenessService.class),
            mock(com.navneet.executespec.project.service.WorkspaceHealthService.class),
            mock(com.navneet.executespec.project.pr.PostMergeCascadeService.class),
            mock(com.navneet.executespec.project.estimation.EstimationService.class),
            mock(com.navneet.executespec.project.techdebt.TechDebtService.class),
            mock(com.navneet.executespec.project.techdebt.TechDebtFixService.class),
            execRunRepo,
            mock(com.navneet.executespec.codereview.repository.CodeReviewScanRepository.class),
            mock(com.navneet.executespec.prreview.repository.PrReviewRepository.class),
            mock(com.navneet.executespec.codeintel.repository.RepoArchitectureDocRepository.class),
            mock(
                com.navneet.executespec.codeintel.repository.ProjectArchitectureDocRepository
                    .class),
            mock(com.navneet.executespec.codeintel.repository.ProjectGeneratedDocRepository.class),
            mock(com.navneet.executespec.project.security.ProjectAccessGuard.class),
            projectRepository,
            runMapper,
            taskRepo);

    var dto = controller.dashboard(PROJECT_ID, null).getBody();

    assertThat(dto).isNotNull();
    assertThat(dto.recentTasks().get(0).status()).isEqualTo("COMPLETE");
  }
}
