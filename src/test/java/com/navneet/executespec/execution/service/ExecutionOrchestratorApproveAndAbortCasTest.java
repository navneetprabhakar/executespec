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
import com.navneet.executespec.integration.RunOrchestrator;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * 2026-04-26 hotfix coverage — projectApprovedPlan + abortExecution + executeApprovedPlan
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
  @Mock com.navneet.executespec.integration.RunLifecycleService runLifecycleService;
  @Mock com.navneet.executespec.project.pr.PullRequestRecordRepository prRecordRepo;
  @Mock com.navneet.executespec.state.repository.PipelineRunRepository pipelineRunRepo;
  @Mock ExecutionPlannerService plannerService;
  @Mock ExecutionCostAggregator costAggregator;

  ExecutionOrchestrator orchestrator;
  ExecutionRunCasUpdater runCas;

  @BeforeEach
  void wire() {
    runCas = new ExecutionRunCasUpdater(runRepo);
    final ExecutionOrchestrator[] selfHolder = new ExecutionOrchestrator[1];
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
            plannerService,
            costAggregator,
            // RR-343 — the production bean routes self-calls through the Spring @Async proxy; in a
            // plain unit test the "proxy" is the instance itself, so the calls stay synchronous and
            // the existing assertions still observe them.
            new org.springframework.beans.factory.ObjectProvider<ExecutionOrchestrator>() {
              @Override
              public ExecutionOrchestrator getObject() {
                return selfHolder[0];
              }
            },
            new com.navneet.executespec.integration.PipelineExecutorPool());
    selfHolder[0] = orchestrator;
  }

  @Test
  void projectApprovedPlan_recordsApprovalForExactCanonicalLink_thenPollsIt() {
    ExecutionRun row = withStatus("AWAITING_PLAN_APPROVAL");
    row.setPipelineRunId("run-1");
    when(runRepo.findById("exec-1")).thenAnswer(inv -> Optional.of(row));
    when(runRepo.save(any(ExecutionRun.class))).thenAnswer(inv -> inv.getArgument(0));
    when(pipelineRunRepo.findById("run-1"))
        .thenReturn(Optional.of(PipelineRun.builder().runId("run-1").status("COMPLETE").build()));

    orchestrator.projectApprovedPlan("exec-1", "run-1");

    verify(runRepo, atLeast(2)).save(row);
    verify(pipelineRunRepo).findById("run-1");
    verify(runLifecycleService, never()).cancel(anyString());
    assertThat(row.getPlanApprovedAt()).isNotNull();
    assertThat(row.getStatus()).isEqualTo("COMPLETE");
  }

  @Test
  void projectApprovedPlan_deletedExecutionFencesPassedApprovedPipeline() {
    when(runRepo.findById("exec-1")).thenReturn(Optional.empty());

    orchestrator.projectApprovedPlan("exec-1", "run-approved");

    verify(runRepo, never()).save(any());
    verify(runLifecycleService).cancel("run-approved");
    verifyNoInteractions(pipelineRunRepo);
  }

  @Test
  void unifiedApprovalProjectsOnlyAfterCanonicalApprovalWasResolvedByController() {
    ExecutionRun row = withStatus("AWAITING_PLAN_APPROVAL");
    row.setPipelineRunId("run-1");
    when(runRepo.findById("exec-1")).thenReturn(Optional.of(row));
    when(runRepo.save(any(ExecutionRun.class))).thenAnswer(inv -> inv.getArgument(0));
    when(pipelineRunRepo.findById("run-1"))
        .thenReturn(Optional.of(PipelineRun.builder().runId("run-1").status("COMPLETE").build()));

    orchestrator.projectApprovedPlan("exec-1", "run-1");

    verify(pipelineRunRepo).findById("run-1");
    assertThat(row.getPlanApprovedAt()).isNotNull();
  }

  @Test
  void duplicateUnifiedApprovalAfterCanonicalProgressDoesNotFailTheLaunch() {
    ExecutionRun row = withStatus("EXECUTING");
    row.setPipelineRunId("run-1");
    row.setPlanApprovedAt(java.time.Instant.now());
    when(runRepo.findById("exec-1")).thenReturn(Optional.of(row));
    when(runRepo.save(any(ExecutionRun.class))).thenAnswer(inv -> inv.getArgument(0));
    orchestrator.projectApprovedPlan("exec-1", "run-1");

    assertThat(row.getStatus()).isEqualTo("EXECUTING");
    assertThat(row.getFailureReason()).isNull();
    verifyNoInteractions(pipelineRunRepo);
  }

  @Test
  void unifiedApprovalLostToAbortFencesCanonicalRunWithoutPolling() {
    ExecutionRun row = withStatus("ABORTED");
    row.setPipelineRunId("run-1");
    when(runRepo.findById("exec-1")).thenReturn(Optional.of(row));
    when(runRepo.save(any(ExecutionRun.class))).thenAnswer(inv -> inv.getArgument(0));
    orchestrator.projectApprovedPlan("exec-1", "run-1");

    verify(runLifecycleService).cancel("run-1");
    verifyNoInteractions(pipelineRunRepo);
    assertThat(row.getStatus()).isEqualTo("ABORTED");
    assertThat(row.getPlanApprovedAt()).isNull();
  }

  @Test
  void optimisticRetryRefetchingAbortDoesNotReuseStaleProjectionDecision() {
    ExecutionRun planning = withStatus("PLANNING");
    planning.setPipelineRunId("run-1");
    ExecutionRun aborted = withStatus("ABORTED");
    aborted.setPipelineRunId("run-1");
    when(runRepo.findById("exec-1"))
        .thenReturn(Optional.of(planning), Optional.of(aborted), Optional.of(aborted));
    when(runRepo.save(any(ExecutionRun.class)))
        .thenThrow(new ObjectOptimisticLockingFailureException(ExecutionRun.class, "exec-1"))
        .thenAnswer(inv -> inv.getArgument(0));

    orchestrator.projectApprovedPlan("exec-1", "run-1");

    verify(runLifecycleService).cancel("run-1");
    verifyNoInteractions(pipelineRunRepo);
    assertThat(aborted.getStatus()).isEqualTo("ABORTED");
    assertThat(aborted.getPlanApprovedAt()).isNull();
  }

  @Test
  void exactLinkMismatchFencesPassedApprovedPipelineWithoutTouchingDifferentLink() {
    ExecutionRun row = withStatus("PLANNING");
    row.setPipelineRunId("run-different");
    when(runRepo.findById("exec-1")).thenReturn(Optional.of(row));
    when(runRepo.save(any(ExecutionRun.class))).thenAnswer(inv -> inv.getArgument(0));

    orchestrator.projectApprovedPlan("exec-1", "run-approved");

    verify(runLifecycleService).cancel("run-approved");
    verify(runLifecycleService, never()).cancel("run-different");
    verifyNoInteractions(pipelineRunRepo);
    assertThat(row.getStatus()).isEqualTo("PLANNING");
    assertThat(row.getPlanApprovedAt()).isNull();
  }

  @Test
  void projectionCasExhaustionPreservesConcurrentAbortAndFencesPassedApprovedPipeline() {
    ExecutionRun attemptOne = withStatus("PLANNING");
    attemptOne.setPipelineRunId("run-approved");
    ExecutionRun attemptTwo = withStatus("PLANNING");
    attemptTwo.setPipelineRunId("run-approved");
    ExecutionRun attemptThree = withStatus("PLANNING");
    attemptThree.setPipelineRunId("run-approved");
    ExecutionRun aborted = withStatus("ABORTED");
    aborted.setPipelineRunId("run-approved");
    when(runRepo.findById("exec-1"))
        .thenReturn(
            Optional.of(attemptOne),
            Optional.of(attemptTwo),
            Optional.of(attemptThree),
            Optional.of(aborted));
    when(runRepo.save(any(ExecutionRun.class)))
        .thenThrow(new ObjectOptimisticLockingFailureException(ExecutionRun.class, "exec-1"))
        .thenThrow(new ObjectOptimisticLockingFailureException(ExecutionRun.class, "exec-1"))
        .thenThrow(new ObjectOptimisticLockingFailureException(ExecutionRun.class, "exec-1"))
        .thenAnswer(inv -> inv.getArgument(0));

    orchestrator.projectApprovedPlan("exec-1", "run-approved");

    verify(runLifecycleService).cancel("run-approved");
    verify(runRepo, times(4)).findById("exec-1");
    verify(runRepo, times(4)).save(any(ExecutionRun.class));
    verifyNoInteractions(pipelineRunRepo);
    assertThat(aborted.getStatus()).isEqualTo("ABORTED");
    assertThat(aborted.getFailureReason()).isNull();
    assertThat(aborted.getPlanApprovedAt()).isNull();
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

    // RR-350 — abort now (a) re-reads to reject an already-terminal launch before deciding, and
    // (b) re-reads the owned row after winning the claim so compensations never run off the
    // caller's stale copy. That is 3 reads: precondition, CAS refetch, owned re-read. The CAS still
    // saves exactly once, and the mutator still lands on the refetched row.
    verify(runRepo, times(3)).findById("exec-1");
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

    // RR-350 — precondition read + two CAS attempts + the post-claim owned re-read.
    verify(runRepo, times(4)).findById("exec-1");
    verify(runRepo, times(2)).save(any());
  }

  @Test
  void abortExecution_alreadyTerminalLaunch_performsNoDestructiveCompensation() {
    // RR-350 — the abort endpoint had no lifecycle precondition, so a late or retried cancel could
    // rewrite an already-COMPLETE launch as ABORTED and close pull requests that had already been
    // delivered to the customer. A terminal launch must now be a total no-op.
    ExecutionRun caller = withStatus("EXECUTING"); // stale controller-loaded copy
    when(runRepo.findById("exec-1")).thenReturn(Optional.of(withStatus("COMPLETE")));

    orchestrator.abortExecution(caller);

    verify(runRepo, never()).save(any());
    verify(solutionRepo, never()).findByExecutionRun_IdOrderBySequenceNumberAsc("exec-1");
  }

  @Test
  void executeApprovedPlan_withoutDurablePlanApproval_refusesToExecute() {
    // RR-351 — POST /api/executions/{id}/resume calls straight into this method. Without the
    // planApprovedAt proof, any writable launch could skip architecture/plan approval and enter
    // solution execution (provider spend + PR publication).
    ExecutionRun unapproved = withStatus("PLANNING"); // planApprovedAt is null
    when(runRepo.findById("exec-1")).thenReturn(Optional.of(unapproved));

    orchestrator.executeApprovedPlan("exec-1");

    verify(runRepo, never()).save(any());
    verify(solutionRepo, never())
        .findWithDependenciesByExecutionRun_IdOrderBySequenceNumberAsc("exec-1");
  }

  @Test
  void executeApprovedPlan_onATerminalLaunch_refusesToExecute() {
    // RR-349/RR-351 — recovery and resume must not restart work on a launch that already ended.
    ExecutionRun terminal = withStatus("COMPLETE");
    terminal.setPlanApprovedAt(java.time.Instant.now());
    when(runRepo.findById("exec-1")).thenReturn(Optional.of(terminal));

    orchestrator.executeApprovedPlan("exec-1");

    verify(runRepo, never()).save(any());
    verify(solutionRepo, never())
        .findWithDependenciesByExecutionRun_IdOrderBySequenceNumberAsc("exec-1");
  }

  private ExecutionRun withStatus(String status) {
    ExecutionRun r = new ExecutionRun();
    r.setId("exec-1");
    r.setStatus(status);
    return r;
  }
}
