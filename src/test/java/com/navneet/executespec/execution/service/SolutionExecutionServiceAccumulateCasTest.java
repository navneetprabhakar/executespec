package com.navneet.executespec.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.Optional;

import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.entity.ExecutionSolution;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * 2026-04-26 hotfix coverage — proves the run-totals aggregator on {@link SolutionExecutionService}
 * now retries via {@link ExecutionRunCasUpdater} instead of silently dropping increments
 * when @{@link jakarta.persistence.Version} moves on between findById and save (the previous
 * implementation caught the exception + warned, losing the token + cost contribution from the
 * completing solution).
 */
@ExtendWith(MockitoExtension.class)
class SolutionExecutionServiceAccumulateCasTest {

  @Mock com.navneet.executespec.integration.RunOrchestrator v2Orchestrator;
  @Mock CrossRepoContextService contextService;
  @Mock ExecutionBranchService branchService;
  @Mock ExecutionJiraSync jiraSync;
  @Mock ExecutionRunRepository runRepo;
  @Mock com.navneet.executespec.execution.repository.ExecutionSolutionRepository solutionRepo;
  @Mock com.navneet.executespec.execution.repository.ExecutionSolutionRunRepository solutionRunRepo;
  @Mock com.navneet.executespec.project.repository.ProjectRepoRepository projectRepoRepo;
  @Mock com.navneet.executespec.state.repository.PipelineRunRepository pipelineRunRepo;
  @Mock com.navneet.executespec.auth.repository.AppUserRepository appUserRepo;
  @Mock com.navneet.executespec.sse.SseBroadcaster broadcaster;
  @Mock com.navneet.executespec.repo.GitHubRepoService gitHubRepoService;

  SolutionExecutionService svc;

  @BeforeEach
  void wire() {
    ExecutionRunCasUpdater runCas = new ExecutionRunCasUpdater(runRepo);
    svc =
        new SolutionExecutionService(
            v2Orchestrator,
            contextService,
            branchService,
            jiraSync,
            runRepo,
            solutionRepo,
            solutionRunRepo,
            projectRepoRepo,
            pipelineRunRepo,
            appUserRepo,
            broadcaster,
            gitHubRepoService,
            runCas);
  }

  @Test
  void happyPath_singleAttempt_appliesIncrement() {
    ExecutionRun fresh = withTotals(100L, new BigDecimal("0.50"));
    when(runRepo.findById("exec-1")).thenReturn(Optional.of(fresh));
    when(runRepo.save(any(ExecutionRun.class))).thenAnswer(inv -> inv.getArgument(0));

    ExecutionSolution sol = solutionWith(50L, new BigDecimal("0.25"));
    svc.accumulateExecutionRunTotals(stubRun(), sol);

    assertThat(fresh.getTotalActualTokens()).isEqualTo(150L);
    assertThat(fresh.getTotalCostUsd()).isEqualByComparingTo("0.75");
    verify(runRepo, times(1)).findById("exec-1");
    verify(runRepo, times(1)).save(any());
  }

  @Test
  void conflictThenSuccess_retainsIncrement_finalRowIsCorrect() {
    // Two distinct fresh rows so the retry's increment lands on a row
    // whose @Version has moved on (the version itself isn't asserted,
    // only that the increment math compounds correctly across retries).
    ExecutionRun first = withTotals(100L, new BigDecimal("0.50"));
    ExecutionRun second =
        withTotals(120L, new BigDecimal("0.60")); // sibling layer landed +20 / +0.10
    when(runRepo.findById("exec-1")).thenReturn(Optional.of(first)).thenReturn(Optional.of(second));
    when(runRepo.save(any(ExecutionRun.class)))
        .thenThrow(new ObjectOptimisticLockingFailureException(ExecutionRun.class, "exec-1"))
        .thenAnswer(inv -> inv.getArgument(0));

    ExecutionSolution sol = solutionWith(50L, new BigDecimal("0.25"));
    svc.accumulateExecutionRunTotals(stubRun(), sol);

    // The second (winning) attempt must apply the increment to the
    // *fresh* totals (120 + 50 = 170, 0.60 + 0.25 = 0.85), not blindly
    // re-use the stale 100 / 0.50 read.
    assertThat(second.getTotalActualTokens()).isEqualTo(170L);
    assertThat(second.getTotalCostUsd()).isEqualByComparingTo("0.85");
    verify(runRepo, times(2)).findById("exec-1");
    verify(runRepo, times(2)).save(any());
  }

  @Test
  void zeroIncrement_shortCircuits_noFindById() {
    ExecutionSolution sol = solutionWith(0L, BigDecimal.ZERO);
    svc.accumulateExecutionRunTotals(stubRun(), sol);
    verify(runRepo, never()).findById(any());
    verify(runRepo, never()).save(any());
  }

  private ExecutionRun stubRun() {
    ExecutionRun r = new ExecutionRun();
    r.setId("exec-1");
    return r;
  }

  private ExecutionRun withTotals(long tokens, BigDecimal cost) {
    ExecutionRun r = new ExecutionRun();
    r.setId("exec-1");
    r.setTotalActualTokens(tokens);
    r.setTotalCostUsd(cost);
    return r;
  }

  private ExecutionSolution solutionWith(long tokens, BigDecimal cost) {
    ExecutionSolution s = new ExecutionSolution();
    s.setId("sol-1");
    s.setActualTokens(tokens);
    s.setCostUsd(cost);
    return s;
  }
}
