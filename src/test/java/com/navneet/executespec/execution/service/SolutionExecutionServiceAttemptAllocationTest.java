package com.navneet.executespec.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.execution.entity.ExecutionSolutionRun;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRunRepository;
import com.navneet.executespec.integration.RunLifecycleService;
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
import org.springframework.dao.DataIntegrityViolationException;

/**
 * RR-532 — the attempt row (unique on solution_id, attempt_number per V90) must be reconciled on a
 * concurrent-collision instead of failing the whole submission, because by the time it is allocated
 * the paid V2 child run already exists; failing here orphaned it.
 */
@ExtendWith(MockitoExtension.class)
class SolutionExecutionServiceAttemptAllocationTest {

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
  @Mock RunLifecycleService runLifecycleService;

  @InjectMocks SolutionExecutionService service;

  @Test
  void saveAttempt_recomputesAndRetriesOnUniqueCollision() {
    // First allocation picks attempt 1 and the unique index rejects it (a concurrent starter won);
    // the retry recomputes to attempt 2 and succeeds — the already-submitted child is recorded.
    when(solutionRunRepo.countBySolutionId("sol-1")).thenReturn(0, 1);
    when(solutionRunRepo.saveAndFlush(any(ExecutionSolutionRun.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate key (solution_id, attempt)"))
        .thenAnswer(inv -> inv.getArgument(0));

    ExecutionSolutionRun result = service.saveAttemptWithUniqueRetry("sol-1", "pr-99");

    assertThat(result.getAttemptNumber()).isEqualTo(2);
    assertThat(result.getPipelineRunId()).isEqualTo("pr-99");
    verify(solutionRunRepo, times(2)).saveAndFlush(any(ExecutionSolutionRun.class));
  }

  @Test
  void saveAttempt_firstAllocationSucceedsWithoutRetry() {
    when(solutionRunRepo.countBySolutionId("sol-1")).thenReturn(0);
    when(solutionRunRepo.saveAndFlush(any(ExecutionSolutionRun.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    ExecutionSolutionRun result = service.saveAttemptWithUniqueRetry("sol-1", "pr-1");

    assertThat(result.getAttemptNumber()).isEqualTo(1);
    verify(solutionRunRepo, times(1)).saveAndFlush(any(ExecutionSolutionRun.class));
  }
}
