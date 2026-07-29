package com.navneet.executespec.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.navneet.executespec.execution.model.ExecutionDecision;
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
 * RR-530 — a human execution decision is a one-shot conditional transition out of exactly one legal
 * prior state, stamped with the deciding actor. These tests pin the refusals, because the previous
 * implementation applied every action unconditionally and only logged an unknown one.
 */
@ExtendWith(MockitoExtension.class)
class SolutionExecutionServiceDecisionAuthorityTest {

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

  private ExecutionSolution stub(String status) {
    ExecutionRun run = ExecutionRun.builder().id("run-1").build();
    ExecutionSolution sol =
        ExecutionSolution.builder()
            .id("solution-1")
            .executionRun(run)
            .status(status)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    when(solutionRepo.findById("solution-1")).thenReturn(Optional.of(sol));
    return sol;
  }

  @Test
  void unknownActionIsRefusedRatherThanLoggedAndTreatedAsApplied() {
    ExecutionSolution sol = stub("FAILED");

    assertThat(service.processDecision(sol, "DROP TABLE", "user-1"))
        .isEqualTo(DecisionOutcome.UNKNOWN_ACTION);

    verify(solutionRepo, never())
        .claimDecision(any(), any(), any(), any(), any(), any(), anyInt(), any());
    verifyNoInteractions(jiraSync, broadcaster, runCas);
  }

  @Test
  void nullActionIsRefused() {
    ExecutionSolution sol = ExecutionSolution.builder().id("solution-1").status("FAILED").build();

    assertThat(service.processDecision(sol, null, "user-1"))
        .isEqualTo(DecisionOutcome.UNKNOWN_ACTION);

    verifyNoInteractions(solutionRepo, jiraSync, broadcaster, runCas);
  }

  /**
   * The core RR-530 regression: SKIP against a RUNNING solution used to mark it SKIPPED and resume
   * the launch. The conditional transition (legal only from FAILED) loses.
   */
  @Test
  void skipAgainstRunningSolutionIsRefused() {
    ExecutionSolution sol = stub("RUNNING");
    when(solutionRepo.claimDecision(
            eq("solution-1"),
            eq("run-1"),
            eq("FAILED"),
            eq("SKIPPED"),
            eq("SKIP"),
            eq("user-1"),
            eq(0),
            any()))
        .thenReturn(0);

    assertThat(service.processDecision(sol, ExecutionDecision.SKIP, "user-1"))
        .isEqualTo(DecisionOutcome.ILLEGAL_STATE);

    verifyNoInteractions(jiraSync, broadcaster, runCas);
  }

  /**
   * A second concurrent RETRY loses the claim and must not launch an overlapping paid child run.
   */
  @Test
  void concurrentSecondRetryLosesTheClaimAndDoesNotResume() {
    ExecutionSolution sol = stub("QUEUED");
    when(solutionRepo.claimDecision(
            eq("solution-1"),
            eq("run-1"),
            eq("FAILED"),
            eq("QUEUED"),
            eq("RETRY"),
            eq("user-1"),
            eq(1),
            any()))
        .thenReturn(0);

    assertThat(service.processDecision(sol, ExecutionDecision.RETRY, "user-1"))
        .isEqualTo(DecisionOutcome.ILLEGAL_STATE);

    verifyNoInteractions(runCas);
  }

  @Test
  void skipFromAwaitingDecisionAppliesAndResumes() {
    ExecutionSolution sol = stub("FAILED");
    when(solutionRepo.claimDecision(
            eq("solution-1"),
            eq("run-1"),
            eq("FAILED"),
            eq("SKIPPED"),
            eq("SKIP"),
            eq("user-1"),
            eq(0),
            any()))
        .thenReturn(1);

    assertThat(service.processDecision(sol, ExecutionDecision.SKIP, "user-1"))
        .isEqualTo(DecisionOutcome.APPLIED);

    verify(runCas).casUpdate(eq("run-1"), any());
  }

  /** FIX parks the solution in AWAITING_MANUAL_FIX — the state manual-ready later requires. */
  @Test
  void fixMovesSolutionIntoTheManualFixWaitingStateAndDoesNotResume() {
    ExecutionSolution sol = stub("FAILED");
    when(solutionRepo.claimDecision(
            eq("solution-1"),
            eq("run-1"),
            eq("FAILED"),
            eq(SolutionExecutionService.AWAITING_MANUAL_FIX),
            eq("FIX"),
            eq("user-1"),
            eq(0),
            any()))
        .thenReturn(1);

    assertThat(service.processDecision(sol, ExecutionDecision.FIX, "user-1"))
        .isEqualTo(DecisionOutcome.APPLIED);

    verifyNoInteractions(runCas);
  }

  /** RR-531 — a retry fences the superseded child run before submitting a replacement. */
  @Test
  void retryFencesThePreviousChildRunBeforeClaiming() {
    ExecutionRun run = ExecutionRun.builder().id("run-1").build();
    ExecutionSolution sol =
        ExecutionSolution.builder()
            .id("solution-1")
            .executionRun(run)
            .status("FAILED")
            .pipelineRunId("child-run-9")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    when(solutionRepo.findById("solution-1")).thenReturn(Optional.of(sol));
    when(runLifecycleService.cancel("child-run-9")).thenReturn(true);
    when(pipelineRunRepo.findById("child-run-9"))
        .thenReturn(
            Optional.of(
                com.navneet.executespec.state.entity.PipelineRun.builder()
                    .runId("child-run-9")
                    .status("CANCELLED")
                    .build()));
    when(solutionRepo.claimDecision(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyInt(),
            any()))
        .thenReturn(1);

    assertThat(service.processDecision(sol, ExecutionDecision.RETRY, "user-1"))
        .isEqualTo(DecisionOutcome.APPLIED);

    verify(runLifecycleService).cancel("child-run-9");
  }
}
