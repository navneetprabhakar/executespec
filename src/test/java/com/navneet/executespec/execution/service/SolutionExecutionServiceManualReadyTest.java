package com.navneet.executespec.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.entity.ExecutionSolution;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRunRepository;
import com.navneet.executespec.execution.service.SolutionExecutionService.DecisionOutcome;
import com.navneet.executespec.integration.RunLifecycleService;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * RR-530 — manual-ready must prove a prior FIX decision and win a one-shot conditional transition,
 * not simply overwrite whatever state the solution happened to be in.
 */
@ExtendWith(MockitoExtension.class)
class SolutionExecutionServiceManualReadyTest {

  @Mock ExecutionPipelineSubmissionService v2Orchestrator;
  @Mock CrossRepoContextService contextService;
  @Mock ExecutionBranchService branchService;
  @Mock ExecutionJiraSync jiraSync;
  @Mock ExecutionRunRepository runRepo;
  @Mock ExecutionSolutionRepository solutionRepo;
  @Mock ExecutionSolutionRunRepository solutionRunRepo;
  @Mock ProjectRepoRepository projectRepoRepo;
  @Mock PipelineRunRepository pipelineRunRepo;
  @Mock AgentTaskRepository agentTaskRepo;
  @Mock AppUserRepository appUserRepo;
  @Mock SseBroadcaster broadcaster;
  @Mock GitHubRepoService gitHubRepoService;
  @Mock ExecutionRunCasUpdater runCas;
  @Mock ExecutionCostAggregator costAggregator;
  @Mock RunLifecycleService runLifecycleService;

  @InjectMocks SolutionExecutionService service;

  private static ExecutionSolution solution(ExecutionRun run, String status) {
    return ExecutionSolution.builder()
        .id("solution-1")
        .executionRun(run)
        .status(status)
        .createdAt(Instant.now())
        .updatedAt(Instant.now())
        .build();
  }

  @Test
  void foreignRunSolutionIsDeniedBeforeAnySideEffect() {
    when(solutionRepo.findByIdAndExecutionRun_Id("solution-2", "run-1"))
        .thenReturn(Optional.empty());

    assertThat(service.signalManualFixDone("run-1", "solution-2", "user-1"))
        .isEqualTo(DecisionOutcome.NOT_FOUND);

    verify(solutionRepo, never()).claimManualFixDone(any(), any(), any(), any());
    verifyNoInteractions(jiraSync, broadcaster, runCas);
  }

  @Test
  void awaitingManualFixSolutionCompletesAndResumesItsBoundRun() {
    ExecutionRun run = ExecutionRun.builder().id("run-1").build();
    ExecutionSolution sol = solution(run, SolutionExecutionService.AWAITING_MANUAL_FIX);
    when(solutionRepo.findByIdAndExecutionRun_Id("solution-1", "run-1"))
        .thenReturn(Optional.of(sol));
    when(solutionRepo.claimManualFixDone(eq("solution-1"), eq("run-1"), eq("user-1"), any()))
        .thenReturn(1);
    when(solutionRepo.findById("solution-1")).thenReturn(Optional.of(sol));

    assertThat(service.signalManualFixDone("run-1", "solution-1", "user-1"))
        .isEqualTo(DecisionOutcome.APPLIED);

    verify(jiraSync).updateTicket(run, sol, "Manual fix signalled as complete.", "Done");
    verify(runCas).casUpdate(eq("run-1"), any());
  }

  /**
   * The core RR-530 regression: a solution that is still RUNNING (or QUEUED, or already DONE) was
   * previously marked DONE by this call and the parent launch resumed. The conditional transition
   * loses, so nothing happens.
   */
  @Test
  void runningSolutionCannotBeMarkedDoneByManualReady() {
    ExecutionRun run = ExecutionRun.builder().id("run-1").build();
    ExecutionSolution sol = solution(run, "RUNNING");
    when(solutionRepo.findByIdAndExecutionRun_Id("solution-1", "run-1"))
        .thenReturn(Optional.of(sol));
    when(solutionRepo.claimManualFixDone(eq("solution-1"), eq("run-1"), eq("user-1"), any()))
        .thenReturn(0);

    assertThat(service.signalManualFixDone("run-1", "solution-1", "user-1"))
        .isEqualTo(DecisionOutcome.ILLEGAL_STATE);

    verifyNoInteractions(jiraSync, broadcaster, runCas);
  }

  /** A replayed manual-ready loses the transition the second time and does not resume again. */
  @Test
  void replayedManualReadyIsRefused() {
    ExecutionRun run = ExecutionRun.builder().id("run-1").build();
    ExecutionSolution sol = solution(run, "DONE");
    when(solutionRepo.findByIdAndExecutionRun_Id("solution-1", "run-1"))
        .thenReturn(Optional.of(sol));
    when(solutionRepo.claimManualFixDone(eq("solution-1"), eq("run-1"), eq("user-1"), any()))
        .thenReturn(0);

    assertThat(service.signalManualFixDone("run-1", "solution-1", "user-1"))
        .isEqualTo(DecisionOutcome.ILLEGAL_STATE);

    verifyNoInteractions(runCas);
  }
}
