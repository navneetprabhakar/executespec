package com.navneet.executespec.execution.service;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.codeintel.ProjectArchitectureService;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.v2.integration.V2RunOrchestrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 2026-04-26 hotfix coverage — proves
 * {@link ExecutionOrchestrator#triggerAnalysis(String)} survives
 * @{@link jakarta.persistence.Version} races at all 3 save points
 * (status=ANALYSING, status=ARCH_REVIEW + arch doc, status=ABORTED on
 * exception).
 *
 * <p>Reproduces the failure observed in the 2026-04-26 project eval:
 * 3 parallel /api/executions/{id}/analyse calls hit
 * {@link ObjectOptimisticLockingFailureException} on the second save
 * because {@link ProjectArchitectureService#generate} ran long enough
 * for concurrent writers to bump the row's version. The fix routes
 * every save through casUpdateRun which refetches + retries.
 */
@ExtendWith(MockitoExtension.class)
class ExecutionOrchestratorTriggerAnalysisRetryTest {

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
    @Mock V2RunOrchestrator v2Orchestrator;
    @Mock com.navneet.executespec.project.pr.PullRequestRecordRepository prRecordRepo;
    @Mock ObjectProvider<com.navneet.executespec.folder.BootstrapService> bootstrapServiceProvider;

    @InjectMocks ExecutionOrchestrator orchestrator;

    @Test
    void happyPath_savesAnalysingThenArchReview_withSingleAttemptEach() {
        ExecutionRun snapshot = withStatus("EXECUTING");
        snapshot.setProjectId("proj-1");
        snapshot.setCreatedBy("user-1");
        // First findById is the snapshot; subsequent findByIds inside
        // casUpdateRun return a fresh row each time.
        when(runRepo.findById("exec-1"))
                .thenReturn(Optional.of(snapshot))
                .thenAnswer(inv -> Optional.of(withStatus("EXECUTING")))
                .thenAnswer(inv -> Optional.of(withStatus("ANALYSING")));
        when(appUserRepo.findById("user-1")).thenReturn(Optional.of(userWithPat("ghp_test")));
        ProjectArchitectureDoc doc = new ProjectArchitectureDoc();
        doc.setContent("# arch");
        when(archService.generate(eq("proj-1"), any(), eq("ghp_test"))).thenReturn(doc);
        when(runRepo.save(any(ExecutionRun.class))).thenAnswer(inv -> inv.getArgument(0));

        orchestrator.triggerAnalysis("exec-1");

        // 3 findByIds (snapshot + 2 casUpdateRun fetches), 2 saves (ANALYSING, ARCH_REVIEW).
        verify(runRepo, times(3)).findById("exec-1");
        verify(runRepo, times(2)).save(any());
    }

    @Test
    void archReviewSave_conflictThenSuccess_succeedsAfterRetry() {
        ExecutionRun snapshot = withStatus("EXECUTING");
        snapshot.setProjectId("proj-1");
        snapshot.setCreatedBy("user-1");
        when(runRepo.findById("exec-1"))
                .thenReturn(Optional.of(snapshot))                                // snapshot fetch
                .thenAnswer(inv -> Optional.of(withStatus("EXECUTING")))           // ANALYSING save fetch
                .thenAnswer(inv -> Optional.of(withStatus("ANALYSING")))           // ARCH_REVIEW save attempt 1 fetch
                .thenAnswer(inv -> Optional.of(withStatus("ANALYSING")));          // ARCH_REVIEW save attempt 2 fetch
        when(appUserRepo.findById("user-1")).thenReturn(Optional.empty());          // no PAT
        ProjectArchitectureDoc doc = new ProjectArchitectureDoc();
        doc.setContent("# arch");
        when(archService.generate(eq("proj-1"), any(), any())).thenReturn(doc);
        // 1st save = ANALYSING ok; 2nd save = ARCH_REVIEW conflict; 3rd save = ARCH_REVIEW ok
        when(runRepo.save(any(ExecutionRun.class)))
                .thenAnswer(inv -> inv.getArgument(0))                              // ANALYSING ok
                .thenThrow(new ObjectOptimisticLockingFailureException(ExecutionRun.class, "exec-1"))
                .thenAnswer(inv -> inv.getArgument(0));                             // ARCH_REVIEW retry ok

        orchestrator.triggerAnalysis("exec-1");

        // 4 findByIds, 3 saves total.
        verify(runRepo, times(4)).findById("exec-1");
        verify(runRepo, times(3)).save(any());
    }

    @Test
    void analysisException_routesToAbortedViaCas() {
        ExecutionRun snapshot = withStatus("EXECUTING");
        snapshot.setProjectId("proj-1");
        snapshot.setCreatedBy("user-1");
        when(runRepo.findById("exec-1"))
                .thenReturn(Optional.of(snapshot))                                  // snapshot
                .thenAnswer(inv -> Optional.of(withStatus("EXECUTING")))             // ANALYSING save fetch
                .thenAnswer(inv -> Optional.of(withStatus("ANALYSING")));            // ABORTED save fetch
        when(appUserRepo.findById("user-1")).thenReturn(Optional.empty());
        when(archService.generate(eq("proj-1"), any(), any()))
                .thenThrow(new RuntimeException("clone failed"));
        when(runRepo.save(any(ExecutionRun.class))).thenAnswer(inv -> inv.getArgument(0));

        orchestrator.triggerAnalysis("exec-1");

        // 3 findByIds (snapshot + ANALYSING fetch + ABORTED fetch), 2 saves.
        verify(runRepo, times(3)).findById("exec-1");
        verify(runRepo, times(2)).save(any());
    }

    @Test
    void allThreeAnalysingSaveAttemptsConflict_bailsBeforeArch() {
        ExecutionRun snapshot = withStatus("EXECUTING");
        snapshot.setProjectId("proj-1");
        snapshot.setCreatedBy("user-1");
        when(runRepo.findById("exec-1"))
                .thenReturn(Optional.of(snapshot))
                .thenAnswer(inv -> Optional.of(withStatus("EXECUTING")));
        when(runRepo.save(any(ExecutionRun.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(ExecutionRun.class, "exec-1"));

        orchestrator.triggerAnalysis("exec-1");

        // 1 snapshot fetch + 3 retry fetches = 4 findByIds, 3 saves attempted.
        verify(runRepo, times(4)).findById("exec-1");
        verify(runRepo, times(3)).save(any());
        // archService.generate must NOT be called when we couldn't even mark ANALYSING.
        verify(archService, never()).generate(any(), any(), any());
    }

    @Test
    void snapshotMissing_returnsImmediately() {
        when(runRepo.findById("exec-1")).thenReturn(Optional.empty());
        orchestrator.triggerAnalysis("exec-1");
        verify(runRepo, times(1)).findById("exec-1");
        verify(runRepo, never()).save(any());
        verify(archService, never()).generate(any(), any(), any());
    }

    private ExecutionRun withStatus(String status) {
        ExecutionRun run = new ExecutionRun();
        run.setId("exec-1");
        run.setStatus(status);
        return run;
    }

    private AppUser userWithPat(String pat) {
        AppUser u = new AppUser();
        u.setId("user-1");
        u.setGithubPat(pat);
        return u;
    }
}
