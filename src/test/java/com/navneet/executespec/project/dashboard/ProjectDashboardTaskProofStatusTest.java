package com.navneet.executespec.project.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Dashboard counters must bucket runs by the customer-facing DISPLAY status — the same fail-closed
 * task-proof projection as the run list ({@link RunMapper#displayRunStatus}) — so a persisted
 * PARTIAL run is visible and a persisted-COMPLETE run with unproven goal evidence lands in the
 * partial bucket rather than being counted as completed.
 */
class ProjectDashboardTaskProofStatusTest {

  private static final String PROJECT_ID = "project-1";

  private PipelineRunRepository runRepo;
  private AgentTaskRepository agentTaskRepo;
  private ValidationReportRepository reportRepo;
  private ValidationReportValidityService reportValidityService;
  private ExecutionRunRepository execRunRepo;
  private ProjectArchitectureService archService;
  private ProjectDashboardController controller;

  @BeforeEach
  void setUp() {
    runRepo = mock(PipelineRunRepository.class);
    agentTaskRepo = mock(AgentTaskRepository.class);
    reportRepo = mock(ValidationReportRepository.class);
    reportValidityService = mock(ValidationReportValidityService.class);
    execRunRepo = mock(ExecutionRunRepository.class);
    archService = mock(ProjectArchitectureService.class);
    // Real RunMapper (nulls for unused collaborators, matching RunMapperTaskProofStatusTest) so
    // the dashboard buckets are proven against the actual display projection, not a mock of it.
    RunMapper runMapper =
        new RunMapper(
            null, null, null, null, null, null, null, null, null, null, new ObjectMapper(), null);
    controller =
        new ProjectDashboardController(
            runRepo,
            agentTaskRepo,
            runMapper,
            reportRepo,
            reportValidityService,
            mock(PullRequestRecordRepository.class),
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
            mock(ProjectRepository.class));

    // Neutral stubs for the non-counter halves of the dashboard payload.
    when(runRepo.findByProjectIdOrderByStartedAtDesc(eq(PROJECT_ID), any())).thenReturn(List.of());
    when(runRepo.sumTotalTokensByProjectId(PROJECT_ID)).thenReturn(0L);
    when(runRepo.sumActualCostByProjectId(PROJECT_ID)).thenReturn(0.0);
    when(execRunRepo.sumTotalActualTokensByProjectId(PROJECT_ID)).thenReturn(0L);
    when(execRunRepo.sumTotalCostUsdByProjectId(PROJECT_ID)).thenReturn(null);
    when(reportRepo.findAllByProjectId(PROJECT_ID)).thenReturn(List.of());
    when(reportValidityService.aggregate(any()))
        .thenReturn(new ValidationReportValidityService.Aggregate(List.of(), 0.0, 0));
    when(archService.latest(PROJECT_ID)).thenReturn(Optional.empty());
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
