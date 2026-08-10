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
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.codeintel.ProjectArchitectureService;
import com.navneet.executespec.codeintel.StalenessService;
import com.navneet.executespec.codeintel.repository.ProjectArchitectureDocRepository;
import com.navneet.executespec.codeintel.repository.ProjectGeneratedDocRepository;
import com.navneet.executespec.codeintel.repository.RepoArchitectureDocRepository;
import com.navneet.executespec.codereview.repository.CodeReviewScanRepository;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.project.pr.PullRequestRecordRepository;
import com.navneet.executespec.prreview.repository.PrReviewRepository;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.ValidationReportValidityService;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The project dashboard must apply the same fail-closed task-proof projection as the run list
 * ({@link RunMapper#displayRunStatus}, the 2026-07-24 contract) on BOTH of its run surfaces:
 *
 * <ul>
 *   <li>recent-run rows — a persisted COMPLETE run with durable non-DONE goal evidence displays
 *       PARTIAL, exactly as it does on the run list and run detail;
 *   <li>run counters — persisted PARTIAL runs are visible (they previously had no bucket at all),
 *       and a persisted-COMPLETE run whose display status flips to PARTIAL lands in the partial
 *       bucket, never in completed. Each run is counted in exactly one bucket.
 * </ul>
 */
class ProjectDashboardTaskProofStatusTest {

  private static final String PROJECT_ID = "project-1";
  private static final String RUN_ID = "run-dashboard-1";

  private PipelineRunRepository runRepo;
  private AgentTaskRepository agentTaskRepo;
  private ValidationReportRepository reportRepo;
  private ValidationReportValidityService reportValidityService;
  private PullRequestRecordRepository prRepo;
  private ExecutionRunRepository execRunRepo;
  private ProjectArchitectureService archService;
  private ProjectRepository projectRepository;
  private ProjectDashboardController controller;

  @BeforeEach
  void setUp() {
    runRepo = mock(PipelineRunRepository.class);
    agentTaskRepo = mock(AgentTaskRepository.class);
    reportRepo = mock(ValidationReportRepository.class);
    reportValidityService = mock(ValidationReportValidityService.class);
    prRepo = mock(PullRequestRecordRepository.class);
    execRunRepo = mock(ExecutionRunRepository.class);
    archService = mock(ProjectArchitectureService.class);
    projectRepository = mock(ProjectRepository.class);
    // Real RunMapper (nulls for unused collaborators, matching RunMapperTaskProofStatusTest) so
    // both dashboard surfaces are proven against the actual display projection, not a mock of it.
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
            null,
            null);
    controller =
        new ProjectDashboardController(
            runRepo,
            agentTaskRepo,
            runMapper,
            reportRepo,
            reportValidityService,
            prRepo,
            mock(com.navneet.executespec.project.pr.PullRequestRecordService.class),
            archService,
            mock(StalenessService.class),
            mock(com.navneet.executespec.project.service.WorkspaceHealthService.class),
            mock(com.navneet.executespec.project.pr.PostMergeCascadeService.class),
            mock(com.navneet.executespec.project.estimation.EstimationService.class),
            mock(com.navneet.executespec.project.techdebt.TechDebtService.class),
            mock(com.navneet.executespec.project.techdebt.TechDebtFixService.class),
            execRunRepo,
            mock(CodeReviewScanRepository.class),
            mock(PrReviewRepository.class),
            mock(RepoArchitectureDocRepository.class),
            mock(ProjectArchitectureDocRepository.class),
            mock(ProjectGeneratedDocRepository.class),
            mock(com.navneet.executespec.project.security.ProjectAccessGuard.class),
            projectRepository);

    // Neutral stubs for the non-counter halves of the dashboard payload.
    when(runRepo.findByProjectIdOrderByStartedAtDesc(eq(PROJECT_ID), any())).thenReturn(List.of());
    when(runRepo.sumTotalTokensByProjectId(PROJECT_ID)).thenReturn(0L);
    when(runRepo.sumActualCostByProjectId(PROJECT_ID)).thenReturn(0.0);
    when(execRunRepo.sumTotalActualTokensByProjectId(PROJECT_ID)).thenReturn(0L);
    when(execRunRepo.sumTotalCostUsdByProjectId(PROJECT_ID)).thenReturn(null);
    when(reportRepo.findAllByProjectId(PROJECT_ID)).thenReturn(List.of());
    when(reportValidityService.aggregate(anyList()))
        .thenReturn(new ValidationReportValidityService.Aggregate(List.of(), 0.0, 0));
    when(prRepo.findByRunIdOrderByCreatedAtAsc(anyString())).thenReturn(List.of());
    when(archService.latest(PROJECT_ID)).thenReturn(Optional.empty());
    when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());
    when(runRepo.findRunIdsByProjectIdAndStatus(anyString(), anyString())).thenReturn(List.of());
  }

  private static AgentTask task(String runId, String status, String goalDonenessJson) {
    return AgentTask.builder()
        .runId(runId)
        .taskId(runId + "-T1")
        .status(status)
        .goalDonenessJson(goalDonenessJson)
        .build();
  }

  // --- recent-run rows -----------------------------------------------------------------------

  @Test
  void dashboardRecentRunsApplyTheFailClosedTaskProofStatusProjection() {
    PipelineRun run = new PipelineRun();
    run.setRunId(RUN_ID);
    run.setStatus("COMPLETE");
    run.setRunTitle("Ship the endpoint");
    when(runRepo.findByProjectIdOrderByStartedAtDesc(eq(PROJECT_ID), any()))
        .thenReturn(List.of(run));
    when(agentTaskRepo.findByRunIdIn(List.of(RUN_ID)))
        .thenReturn(List.of(task(RUN_ID, "COMPLETE", "{\"state\":\"NOT_DONE_BUT_PROGRESSING\"}")));

    var dto = controller.dashboard(PROJECT_ID, null).getBody();

    assertThat(dto).isNotNull();
    assertThat(dto.recentTasks()).hasSize(1);
    assertThat(dto.recentTasks().get(0).status())
        .as("persisted COMPLETE beside durable non-DONE goal evidence must display PARTIAL")
        .isEqualTo("PARTIAL");
  }

  @Test
  void dashboardKeepsCompleteWhenEveryTaskCarriesProvenOrLegacyGoalEvidence() {
    PipelineRun run = new PipelineRun();
    run.setRunId(RUN_ID);
    run.setStatus("COMPLETE");
    when(runRepo.findByProjectIdOrderByStartedAtDesc(eq(PROJECT_ID), any()))
        .thenReturn(List.of(run));
    when(agentTaskRepo.findByRunIdIn(List.of(RUN_ID)))
        .thenReturn(List.of(task(RUN_ID, "COMPLETE", "{\"state\":\"DONE\"}")));

    var dto = controller.dashboard(PROJECT_ID, null).getBody();

    assertThat(dto).isNotNull();
    assertThat(dto.recentTasks().get(0).status()).isEqualTo("COMPLETE");
  }

  // --- run counters --------------------------------------------------------------------------

  @Test
  void countersBucketByDisplayStatusAcrossCompletePartialAndFailedMix() {
    // 3 persisted-COMPLETE runs: one proven DONE, one legacy (no tasks), one with unproven goal
    // evidence — the last one must display as PARTIAL, exactly like the run list.
    when(runRepo.countByProjectIdAndStatus(PROJECT_ID, "COMPLETE")).thenReturn(3L);
    when(runRepo.countByProjectIdAndStatus(PROJECT_ID, "PARTIAL")).thenReturn(2L);
    when(runRepo.countByProjectIdAndStatus(PROJECT_ID, "FAILED")).thenReturn(1L);
    when(runRepo.countByProjectIdAndStatus(PROJECT_ID, "WORKING")).thenReturn(1L);
    when(runRepo.findRunIdsByProjectIdAndStatus(PROJECT_ID, "COMPLETE"))
        .thenReturn(List.of("run-proven", "run-legacy", "run-unproven"));
    when(agentTaskRepo.findByRunIdIn(List.of("run-proven", "run-legacy", "run-unproven")))
        .thenReturn(
            List.of(
                task("run-proven", "COMPLETE", "{\"state\":\"DONE\"}"),
                task("run-unproven", "COMPLETE", "{\"state\":\"NOT_DONE_BUT_PROGRESSING\"}")));

    ProjectDashboardController.DashboardDto dto = controller.dashboard(PROJECT_ID, null).getBody();

    assertThat(dto).isNotNull();
    assertThat(dto.activeRuns()).isEqualTo(1);
    // The unproven COMPLETE run moved from completed to partial — no bucket double-counts it.
    assertThat(dto.completedRuns()).isEqualTo(2);
    assertThat(dto.partialRuns()).isEqualTo(3);
    assertThat(dto.failedRuns()).isEqualTo(1);
  }

  @Test
  void persistedPartialRunsAreNoLongerInvisible() {
    // The observed dev defect: 2 PARTIAL runs existed but the dashboard read "completed 0
    // failed 1" because PARTIAL had no bucket at all.
    when(runRepo.countByProjectIdAndStatus(PROJECT_ID, "PARTIAL")).thenReturn(2L);
    when(runRepo.countByProjectIdAndStatus(PROJECT_ID, "FAILED")).thenReturn(1L);

    ProjectDashboardController.DashboardDto dto = controller.dashboard(PROJECT_ID, null).getBody();

    assertThat(dto).isNotNull();
    assertThat(dto.partialRuns()).isEqualTo(2);
    assertThat(dto.completedRuns()).isZero();
    assertThat(dto.failedRuns()).isEqualTo(1);
  }

  @Test
  void provenAndLegacyCompleteRunsStayInTheCompletedBucket() {
    when(runRepo.countByProjectIdAndStatus(PROJECT_ID, "COMPLETE")).thenReturn(2L);
    when(runRepo.findRunIdsByProjectIdAndStatus(PROJECT_ID, "COMPLETE"))
        .thenReturn(List.of("run-proven", "run-legacy"));
    when(agentTaskRepo.findByRunIdIn(List.of("run-proven", "run-legacy")))
        .thenReturn(List.of(task("run-proven", "COMPLETE", "{\"state\":\"DONE\"}")));

    ProjectDashboardController.DashboardDto dto = controller.dashboard(PROJECT_ID, null).getBody();

    assertThat(dto).isNotNull();
    assertThat(dto.completedRuns()).isEqualTo(2);
    assertThat(dto.partialRuns()).isZero();
  }
}
