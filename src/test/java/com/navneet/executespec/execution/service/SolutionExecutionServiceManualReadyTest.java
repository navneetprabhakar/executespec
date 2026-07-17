package com.navneet.executespec.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.entity.ExecutionSolution;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRunRepository;
import com.navneet.executespec.integration.RunOrchestrator;
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

@ExtendWith(MockitoExtension.class)
class SolutionExecutionServiceManualReadyTest {

  @Mock RunOrchestrator v2Orchestrator;
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

  @InjectMocks SolutionExecutionService service;

  @Test
  void foreignRunSolutionIsDeniedBeforeAnySideEffect() {
    when(solutionRepo.findByIdAndExecutionRun_Id("solution-2", "run-1"))
        .thenReturn(Optional.empty());

    assertThat(service.signalManualFixDone("run-1", "solution-2")).isFalse();

    verify(solutionRepo, never()).save(org.mockito.ArgumentMatchers.any());
    verifyNoInteractions(jiraSync, broadcaster, runCas);
  }

  @Test
  void sameRunSolutionCompletesAndResumesItsBoundRun() {
    ExecutionRun run = ExecutionRun.builder().id("run-1").build();
    ExecutionSolution solution =
        ExecutionSolution.builder()
            .id("solution-1")
            .executionRun(run)
            .status("AWAITING_FIX")
            .build();
    when(solutionRepo.findByIdAndExecutionRun_Id("solution-1", "run-1"))
        .thenReturn(Optional.of(solution));

    assertThat(service.signalManualFixDone("run-1", "solution-1")).isTrue();

    assertThat(solution.getStatus()).isEqualTo("DONE");
    assertThat(solution.getCompletedAt()).isNotNull();
    verify(solutionRepo).save(solution);
    verify(jiraSync).updateTicket(run, solution, "Manual fix signalled as complete.", "Done");
    verify(runCas)
        .casUpdate(org.mockito.ArgumentMatchers.eq("run-1"), org.mockito.ArgumentMatchers.any());
  }
}
