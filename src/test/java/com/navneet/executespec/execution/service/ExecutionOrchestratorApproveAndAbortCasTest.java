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
  void approvePlanAndExecute_recordsApprovalViaCas_thenInvokesExecute() {
    // approvePlanAndExecute: 1 findById for the CAS planApprovedAt save.
    // executeApprovedPlan that follows: 1 findById for the snapshot, 1 for
    // the CAS EXECUTING save, 1 for the no-layer "latest" recheck (we pass
    // an empty layer set so the for-loop is a no-op), and finally
    // applyTerminalStatus(COMPLETE) does its own findById + save.
    // RR-351 — executeApprovedPlan now requires the durable planApprovedAt stamp this method
    // writes. Return ONE shared row so the later reads observe that stamp, which is what a real
    // database does; a fresh instance per call would model a row that never persisted the approval.
    ExecutionRun row = withStatus("AWAITING_PLAN_APPROVAL");
    when(runRepo.findById("exec-1")).thenAnswer(inv -> Optional.of(row));
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
    // RR-351 — and the approval is now durably stamped, which is what authorises execution.
    assertThat(row.getPlanApprovedAt()).isNotNull();
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
  void unifiedApprovalRoutesThroughCanonicalLifecycleBeforeMirroringExecution() {
    ExecutionRun row = withStatus("AWAITING_PLAN_APPROVAL");
    row.setPipelineRunId("run-1");
    when(runRepo.findById("exec-1")).thenReturn(Optional.of(row));
    when(runRepo.save(any(ExecutionRun.class))).thenAnswer(inv -> inv.getArgument(0));
    when(runLifecycleService.resolvePlanGate("run-1", true))
        .thenReturn(
            com.navneet.executespec.integration.RunLifecycleService.PlanApprovalOutcome
                .RESUME_QUEUED);
    when(pipelineRunRepo.findById("run-1"))
        .thenReturn(Optional.of(PipelineRun.builder().runId("run-1").status("COMPLETE").build()));

    orchestrator.approvePlanAndExecute("exec-1");

    verify(runLifecycleService).resolvePlanGate("run-1", true);
    verify(pipelineRunRepo).findById("run-1");
    assertThat(row.getPlanApprovedAt()).isNotNull();
  }

  @Test
  void duplicateUnifiedApprovalAfterCanonicalProgressDoesNotFailTheLaunch() {
    ExecutionRun row = withStatus("AWAITING_PLAN_APPROVAL");
    row.setPipelineRunId("run-1");
    when(runRepo.findById("exec-1")).thenReturn(Optional.of(row));
    when(runRepo.save(any(ExecutionRun.class))).thenAnswer(inv -> inv.getArgument(0));
    when(runLifecycleService.resolvePlanGate("run-1", true))
        .thenReturn(
            com.navneet.executespec.integration.RunLifecycleService.PlanApprovalOutcome
                .ALREADY_RESOLVED);
    when(pipelineRunRepo.findById("run-1"))
        .thenReturn(Optional.of(PipelineRun.builder().runId("run-1").status("COMPLETE").build()));

    orchestrator.approvePlanAndExecute("exec-1");

    assertThat(row.getStatus()).isEqualTo("COMPLETE");
    assertThat(row.getFailureReason()).isNull();
  }

  @Test
  void unifiedApprovalDoesNotMirrorOrPollWhenLifecycleRejectsTheRunState() {
    ExecutionRun row = withStatus("AWAITING_PLAN_APPROVAL");
    row.setPipelineRunId("run-1");
    when(runRepo.findById("exec-1")).thenReturn(Optional.of(row));
    when(runRepo.save(any(ExecutionRun.class))).thenAnswer(inv -> inv.getArgument(0));
    when(runLifecycleService.resolvePlanGate("run-1", true))
        .thenReturn(
            com.navneet.executespec.integration.RunLifecycleService.PlanApprovalOutcome
                .INVALID_STATE);

    orchestrator.approvePlanAndExecute("exec-1");

    verify(runLifecycleService).resolvePlanGate("run-1", true);
    verifyNoInteractions(pipelineRunRepo);
    assertThat(row.getStatus()).isEqualTo("FAILED");
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
