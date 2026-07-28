package com.navneet.executespec.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.entity.ExecutionSolution;
import com.navneet.executespec.execution.entity.ExecutionSolutionRun;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRunRepository;
import com.navneet.executespec.integration.RunLifecycleService;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SolutionExecutionTerminalReconciliationTest {

  @Mock ExecutionPipelineSubmissionService pipelineSubmissionService;
  @Mock CrossRepoContextService contextService;
  @Mock ExecutionBranchService branchService;
  @Mock ExecutionJiraSync jiraSync;
  @Mock ExecutionRunRepository runRepository;
  @Mock ExecutionSolutionRepository solutionRepository;
  @Mock ExecutionSolutionRunRepository attemptRepository;
  @Mock ProjectRepoRepository projectRepoRepository;
  @Mock PipelineRunRepository pipelineRunRepository;
  @Mock AgentTaskRepository agentTaskRepository;
  @Mock AppUserRepository appUserRepository;
  @Mock SseBroadcaster broadcaster;
  @Mock GitHubRepoService gitHubRepoService;
  @Mock ExecutionRunCasUpdater runCasUpdater;
  @Mock ExecutionCostAggregator costAggregator;
  @Mock RunLifecycleService runLifecycleService;

  @InjectMocks SolutionExecutionService service;

  @Test
  void terminalProjectionHasOneConditionalWinner() {
    Fixture f = fixture();
    when(pipelineRunRepository.findById("child-1")).thenReturn(Optional.of(f.child));
    when(solutionRepository.claimTerminalReconciliation(
            eq("solution-1"), eq("exec-1"), eq("child-1"), any(Instant.class)))
        .thenReturn(1);
    when(solutionRepository.findByIdAndExecutionRun_Id("solution-1", "exec-1"))
        .thenReturn(Optional.of(f.solution));
    when(solutionRepository.findWithDependenciesByExecutionRun_IdOrderBySequenceNumberAsc("exec-1"))
        .thenReturn(List.of());

    service.observeCanonicalAttempt(f.solution, f.run, f.attempt, "child-1", false);

    assertThat(f.solution.getStatus()).isEqualTo("DONE");
    assertThat(f.attempt.getTerminalStatus()).isEqualTo("COMPLETE");
    verify(attemptRepository).save(f.attempt);
    verify(costAggregator).recomputeAndPersist("exec-1");
  }

  @Test
  void duplicateObserverThatLosesClaimDoesNotRepeatTerminalSideEffects() {
    Fixture f = fixture();
    when(pipelineRunRepository.findById("child-1")).thenReturn(Optional.of(f.child));
    when(solutionRepository.claimTerminalReconciliation(
            eq("solution-1"), eq("exec-1"), eq("child-1"), any(Instant.class)))
        .thenReturn(0);

    service.observeCanonicalAttempt(f.solution, f.run, f.attempt, "child-1", false);

    verify(attemptRepository, never()).save(any());
    verifyNoInteractions(branchService, contextService, jiraSync, costAggregator);
  }

  @Test
  void recoveryCanTakeOverOnlyAStaleReconciliationLease() {
    Fixture f = fixture();
    f.solution.setStatus("RECONCILING");
    when(pipelineRunRepository.findById("child-1")).thenReturn(Optional.of(f.child));
    when(solutionRepository.claimTerminalReconciliation(
            eq("solution-1"), eq("exec-1"), eq("child-1"), any(Instant.class)))
        .thenReturn(0);
    when(solutionRepository.claimStaleTerminalReconciliation(
            eq("solution-1"), eq("exec-1"), eq("child-1"), any(Instant.class), any(Instant.class)))
        .thenReturn(1);
    when(solutionRepository.findByIdAndExecutionRun_Id("solution-1", "exec-1"))
        .thenReturn(Optional.of(f.solution));
    when(solutionRepository.findWithDependenciesByExecutionRun_IdOrderBySequenceNumberAsc("exec-1"))
        .thenReturn(List.of());

    service.observeCanonicalAttempt(f.solution, f.run, f.attempt, "child-1", true);

    assertThat(f.solution.getStatus()).isEqualTo("DONE");
    verify(solutionRepository)
        .claimStaleTerminalReconciliation(
            eq("solution-1"), eq("exec-1"), eq("child-1"), any(Instant.class), any(Instant.class));
  }

  private static Fixture fixture() {
    ExecutionRun run = ExecutionRun.builder().id("exec-1").status("EXECUTING").build();
    ExecutionSolution solution =
        ExecutionSolution.builder()
            .id("solution-1")
            .executionRun(run)
            .status("RUNNING")
            .pipelineRunId("child-1")
            .build();
    ExecutionSolutionRun attempt =
        ExecutionSolutionRun.builder()
            .id("attempt-1")
            .solutionId("solution-1")
            .pipelineRunId("child-1")
            .attemptNumber(1)
            .build();
    PipelineRun child =
        PipelineRun.builder()
            .runId("child-1")
            .status("COMPLETE")
            .scmPrUrl("https://github.com/acme/repo/pull/1")
            .totalInputTokens(10)
            .totalOutputTokens(5)
            .actualCostUsd(0.01)
            .build();
    return new Fixture(run, solution, attempt, child);
  }

  private record Fixture(
      ExecutionRun run,
      ExecutionSolution solution,
      ExecutionSolutionRun attempt,
      PipelineRun child) {}
}
