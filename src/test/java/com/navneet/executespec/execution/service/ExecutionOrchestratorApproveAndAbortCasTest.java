package com.navneet.executespec.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.codeintel.ProjectArchitectureService;
import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.v2.integration.RunOrchestrator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * 2026-04-26 hotfix coverage — approvePlanAndExecute + abortExecution + executeApprovedPlan
 * (EXECUTING + cyclic-deps ABORTED saves) now route through {@link ExecutionRunCasUpdater}.
 *
 * <p>Each test is intentionally narrow: only the @Version race shape that the patch addresses, not
 * a full pipeline replay. Same Mockito wiring style as {@link
 * ExecutionOrchestratorTriggerAnalysisRetryTest}.
 */
@ExtendWith(MockitoExtension.class)
class ExecutionOrchestratorApproveAndAbortCasTest {

  @Mock ExecutionRunRepository runRepo;
  @Mock com.navneet.executespec.execution.repository.ExecutionSolutionRepository solutionRepo;
  @Mock ProjectArchitectureService archService;
  @Mock com.navneet.executespec.project.repository.ProjectRepoRepository projectRepoRepo;
  @Mock com.navneet.executespec.project.service.ProjectRepoService projectRepoService;
  @Mock AppUserRepository appUserRepo;
  @Mock SolutionDependencyResolver depResolver;
  @Mock SolutionExecutionService solutionExecService;
  @Mock ExecutionBranchService branchService;
  @Mock SseBroadcaster broadcaster;
  @Mock GitHubRepoService gitHub;
  @Mock RunOrchestrator v2Orchestrator;
  @Mock com.navneet.executespec.project.pr.PullRequestRecordRepository prRecordRepo;

  ExecutionOrchestrator orchestrator;
  ExecutionRunCasUpdater runCas;

  @BeforeEach
  void wire() {
    runCas = new ExecutionRunCasUpdater(runRepo);
    orchestrator =
        new ExecutionOrchestrator(
            runRepo,
            solutionRepo,
            archService,
            projectRepoRepo,
            projectRepoService,
            appUserRepo,
            depResolver,
            solutionExecService,
            branchService,
            broadcaster,
            gitHub,
            v2Orchestrator,
            prRecordRepo,
            runCas);
  }

  @Test
  void approvePlanAndExecute_recordsApprovalViaCas_thenInvokesExecute() {
    // approvePlanAndExecute: 1 findById for the CAS planApprovedAt save.
    // executeApprovedPlan that follows: 1 findById for the snapshot, 1 for
    // the CAS EXECUTING save, 1 for the no-layer "latest" recheck (we pass
    // an empty layer set so the for-loop is a no-op), and finally
    // applyTerminalStatus(COMPLETE) does its own findById + save.
    when(runRepo.findById("exec-1"))
        .thenAnswer(inv -> Optional.of(withStatus("AWAITING_PLAN_APPROVAL")));
    when(runRepo.save(any(ExecutionRun.class))).thenAnswer(inv -> inv.getArgument(0));
    when(solutionRepo.findWithDependenciesByExecutionRun_IdOrderBySequenceNumberAsc("exec-1"))
        .thenReturn(List.of());
    when(depResolver.resolveExecutionOrder(any())).thenReturn(List.of());
    when(solutionRepo.findWithRepoMappingsByExecutionRun_IdOrderBySequenceNumberAsc("exec-1"))
        .thenReturn(List.of());
    when(appUserRepo.findById(any())).thenReturn(Optional.empty());

    orchestrator.approvePlanAndExecute("exec-1");

    // Verify the CAS plan-approval save fired (planApprovedAt set on the
    // refetched row + saved). The exact total save count depends on the
    // executeApprovedPlan tail — what we care about is that the approval
    // path didn't OOM and reached the executeApprovedPlan fork.
    verify(runRepo, atLeast(2)).save(any());
    verify(runRepo, atLeast(2)).findById("exec-1");
  }

  @Test
  void approvePlanAndExecute_runDeleted_skipsApprovalButStillExecutes() {
    // CAS findById returns empty → returns false (logged), then control
    // falls through to executeApprovedPlan which itself bails on missing
    // row. No save calls anywhere.
    when(runRepo.findById("exec-1")).thenReturn(Optional.empty());

    orchestrator.approvePlanAndExecute("exec-1");

    verify(runRepo, never()).save(any());
  }

  @Test
  void abortExecution_marksAbortedViaCas_singleAttempt() {
    // Distinct in-memory references for the caller's stale read vs. the
    // CAS refetch — proves the mutator landed on the refetched row, not
    // the caller's stale copy.
    ExecutionRun caller = withStatus("EXECUTING");
    ExecutionRun fresh = withStatus("EXECUTING");
    when(runRepo.findById("exec-1")).thenReturn(Optional.of(fresh));
    when(runRepo.save(any(ExecutionRun.class))).thenAnswer(inv -> inv.getArgument(0));
    when(solutionRepo.findByExecutionRun_IdOrderBySequenceNumberAsc("exec-1"))
        .thenReturn(List.of());

    orchestrator.abortExecution(caller);

    // CAS refetched + saved exactly once.
    verify(runRepo, times(1)).findById("exec-1");
    verify(runRepo, times(1)).save(any());
    assertThat(fresh.getStatus()).isEqualTo("ABORTED");
    assertThat(caller.getStatus()).isEqualTo("EXECUTING"); // stale caller untouched
  }

  @Test
  void abortExecution_conflictThenSuccess_succeedsAfterRetry() {
    when(runRepo.findById("exec-1")).thenAnswer(inv -> Optional.of(withStatus("EXECUTING")));
    when(runRepo.save(any(ExecutionRun.class)))
        .thenThrow(new ObjectOptimisticLockingFailureException(ExecutionRun.class, "exec-1"))
        .thenAnswer(inv -> inv.getArgument(0));
    when(solutionRepo.findByExecutionRun_IdOrderBySequenceNumberAsc("exec-1"))
        .thenReturn(List.of());

    orchestrator.abortExecution(withStatus("EXECUTING"));

    verify(runRepo, times(2)).findById("exec-1");
    verify(runRepo, times(2)).save(any());
  }

  private ExecutionRun withStatus(String status) {
    ExecutionRun r = new ExecutionRun();
    r.setId("exec-1");
    r.setStatus(status);
    return r;
  }
}
