package com.navneet.executespec.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Optional;

import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Backlog — extends the triggerAnalysis hotfix coverage to the executeApprovedPlan terminal save.
 * Same shape, same retry contract.
 *
 * <p>Note: this branch (feature/enterprise-readiness-eval) does NOT have the triggerAnalysis
 * hotfix; that lives on feature/fix-execution-orchestrator-stale-state-2026-04-25. The two fixes
 * overlap: when both branches converge, the helpers can be unified. For now, this branch ships its
 * own helper for the executeApprovedPlan path.
 */
@ExtendWith(MockitoExtension.class)
class ExecutionOrchestratorTerminalStatusTest {

  @Mock ExecutionRunRepository runRepo;
  @Mock com.navneet.executespec.execution.repository.ExecutionSolutionRepository solutionRepo;
  @Mock com.navneet.executespec.codeintel.ProjectArchitectureService archService;
  @Mock com.navneet.executespec.project.repository.ProjectRepoRepository projectRepoRepo;
  @Mock com.navneet.executespec.project.service.ProjectRepoService projectRepoService;
  @Mock com.navneet.executespec.auth.repository.AppUserRepository appUserRepo;
  @Mock SolutionDependencyResolver depResolver;
  @Mock SolutionExecutionService solutionExecService;
  @Mock ExecutionBranchService branchService;
  @Mock com.navneet.executespec.sse.SseBroadcaster broadcaster;
  @Mock com.navneet.executespec.repo.GitHubRepoService gitHub;
  @Mock com.navneet.executespec.integration.RunOrchestrator v2Orchestrator;
  @Mock com.navneet.executespec.integration.RunLifecycleService runLifecycleService;
  @Mock com.navneet.executespec.project.pr.PullRequestRecordRepository prRecordRepo;
  @Mock com.navneet.executespec.state.repository.PipelineRunRepository pipelineRunRepo;
  @Mock com.navneet.executespec.harness.GateStore gateStore;
  @Mock ExecutionPlannerService plannerService;
  @Mock ExecutionCostAggregator costAggregator;

  @Mock
  org.springframework.beans.factory.ObjectProvider<com.navneet.executespec.folder.BootstrapService>
      bootstrapServiceProvider;

  // ExecutionRunCasUpdater isn't exercised by applyTerminalStatus (which has
  // its own self-contained retry helper) but is required by the orchestrator
  // constructor.
  ExecutionRunCasUpdater runCas;

  ExecutionOrchestrator orchestrator;

  @org.junit.jupiter.api.BeforeEach
  void wireOrchestrator() {
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
            runLifecycleService,
            prRecordRepo,
            runCas,
            pipelineRunRepo,
            gateStore,
            plannerService,
            costAggregator);
  }

  @Test
  void happyPath_appliesStatusOnFirstAttempt() {
    ExecutionRun run = withStatus("EXECUTING");
    when(runRepo.findById("exec-1")).thenReturn(Optional.of(run));
    when(runRepo.save(any(ExecutionRun.class))).thenAnswer(inv -> inv.getArgument(0));

    boolean ok = orchestrator.applyTerminalStatus("exec-1", "COMPLETE");

    assertThat(ok).isTrue();
    assertThat(run.getStatus()).isEqualTo("COMPLETE");
    verify(runRepo, times(1)).save(any());
  }

  @Test
  void conflictThenSuccess_returnsTrueAfterRetry() {
    // Each findById returns a *fresh* EXECUTING ExecutionRun — mirrors the
    // real refetch contract. Without this, the impl's in-place setStatus
    // on the shared mock object would trip the terminal-state early-return
    // guard on the retry's findById, hiding the second save attempt.
    when(runRepo.findById("exec-1")).thenAnswer(inv -> Optional.of(withStatus("EXECUTING")));
    when(runRepo.save(any(ExecutionRun.class)))
        .thenThrow(new ObjectOptimisticLockingFailureException(ExecutionRun.class, "exec-1"))
        .thenAnswer(inv -> inv.getArgument(0));

    boolean ok = orchestrator.applyTerminalStatus("exec-1", "COMPLETE");

    assertThat(ok).isTrue();
    verify(runRepo, times(2)).findById("exec-1");
    verify(runRepo, times(2)).save(any());
  }

  @Test
  void allThreeAttemptsConflict_returnsFalse() {
    when(runRepo.findById("exec-1")).thenAnswer(inv -> Optional.of(withStatus("EXECUTING")));
    when(runRepo.save(any(ExecutionRun.class)))
        .thenThrow(new ObjectOptimisticLockingFailureException(ExecutionRun.class, "exec-1"));

    boolean ok = orchestrator.applyTerminalStatus("exec-1", "COMPLETE");

    assertThat(ok).isFalse();
    verify(runRepo, times(3)).save(any());
  }

  @Test
  void entityGone_returnsFalse_doesNotSave() {
    when(runRepo.findById("exec-1")).thenReturn(Optional.empty());
    boolean ok = orchestrator.applyTerminalStatus("exec-1", "COMPLETE");
    assertThat(ok).isFalse();
    verify(runRepo, never()).save(any());
  }

  @Test
  void alreadyAborted_skipsTerminalWrite_returnsTrue() {
    // User cancelled mid-run; another thread set ABORTED.
    // applyTerminalStatus("COMPLETE") must NOT trample.
    ExecutionRun run = withStatus("ABORTED");
    when(runRepo.findById("exec-1")).thenReturn(Optional.of(run));

    boolean ok = orchestrator.applyTerminalStatus("exec-1", "COMPLETE");

    assertThat(ok).isTrue();
    assertThat(run.getStatus()).isEqualTo("ABORTED");
    verify(runRepo, never()).save(any());
  }

  private ExecutionRun withStatus(String status) {
    ExecutionRun run = new ExecutionRun();
    run.setId("exec-1");
    run.setStatus(status);
    return run;
  }
}
