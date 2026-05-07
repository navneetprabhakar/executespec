package com.navneet.executespec.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.entity.ExecutionSolution;
import com.navneet.executespec.execution.model.ExecutionPlan;
import com.navneet.executespec.execution.model.SolutionSpec;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * 2026-04-26 hotfix coverage — proves {@link ExecutionPlannerService#persistPlan(ExecutionRun,
 * ExecutionPlan)} now survives @{@link jakarta.persistence.Version} races on the run save by
 * routing through {@link ExecutionRunCasUpdater}.
 *
 * <p>Reproduces the failure observed in the 2026-04-26 project eval: the planner LLM call straddled
 * a concurrent SSE / abort write to the same execution_run row; the original {@code
 * runRepo.save(run)} on the stale in-memory reference threw {@link
 * ObjectOptimisticLockingFailureException} and the @Async exception bubbled to the uncaught
 * handler, leaving the run wedged at PLANNING.
 *
 * <p>Uses reflection to invoke the private persistPlan directly so we don't have to stand up the
 * full LLM-router mock chain that {@code generatePlan} would otherwise need.
 */
@ExtendWith(MockitoExtension.class)
class ExecutionPlannerServicePersistPlanCasTest {

  @Mock com.navneet.executespec.agent.FallbackLlmRouter fallbackLlmRouter;
  @Mock com.navneet.executespec.router.ModelResolver modelResolver;
  @Mock ExecutionRunRepository runRepo;
  @Mock ExecutionSolutionRepository solutionRepo;
  @Mock com.navneet.executespec.codeintel.repository.ProjectCodeMapRepository codeMapRepo;
  @Mock com.navneet.executespec.project.repository.ProjectRepoRepository projectRepoRepo;
  @Mock com.navneet.executespec.memory.ProjectMemoryProvider memoryProvider;
  @Mock FileConflictDetector conflictDetector;
  @Mock com.navneet.executespec.sse.SseBroadcaster broadcaster;

  ExecutionPlannerService planner;
  ExecutionRunCasUpdater runCas;

  @BeforeEach
  void wirePlanner() {
    runCas = new ExecutionRunCasUpdater(runRepo);
    planner =
        new ExecutionPlannerService(
            fallbackLlmRouter,
            modelResolver,
            new ObjectMapper(),
            runRepo,
            solutionRepo,
            codeMapRepo,
            projectRepoRepo,
            memoryProvider,
            conflictDetector,
            broadcaster,
            runCas);
  }

  @Test
  void happyPath_persistPlan_savesRunOnceAndCascadesSolutions() throws Exception {
    ExecutionRun run = withStatus("PLANNING");
    when(runRepo.findById("exec-1")).thenReturn(Optional.of(run));
    when(runRepo.save(any(ExecutionRun.class))).thenAnswer(inv -> inv.getArgument(0));
    when(solutionRepo.save(any(ExecutionSolution.class))).thenAnswer(inv -> inv.getArgument(0));

    invokePersistPlan(run, oneSolutionPlan());

    // CAS writes the run once + refetches it to use as the FK reference.
    verify(runRepo, times(2)).findById("exec-1");
    verify(runRepo, times(1)).save(any());
    // Two solutionRepo.save calls per spec (creation + repoMappings cascade).
    verify(solutionRepo, times(2)).save(any());
    assertThat(run.getExecutionPlan()).isNotBlank();
  }

  @Test
  void runSave_conflictThenSuccess_succeedsAfterRetry() throws Exception {
    // Each findById returns a fresh row so the version-bump retry loop
    // exercises real refetch semantics.
    when(runRepo.findById("exec-1")).thenAnswer(inv -> Optional.of(withStatus("PLANNING")));
    when(runRepo.save(any(ExecutionRun.class)))
        .thenThrow(new ObjectOptimisticLockingFailureException(ExecutionRun.class, "exec-1"))
        .thenAnswer(inv -> inv.getArgument(0));
    when(solutionRepo.save(any(ExecutionSolution.class))).thenAnswer(inv -> inv.getArgument(0));

    invokePersistPlan(withStatus("PLANNING"), oneSolutionPlan());

    // 3 findByIds (1 conflict + 1 success + 1 fresh-FK lookup) and 2 saves.
    verify(runRepo, times(3)).findById("exec-1");
    verify(runRepo, times(2)).save(any());
    verify(solutionRepo, times(2)).save(any());
  }

  @Test
  void allRetriesExhaust_throwsIllegalState_doesNotWriteSolutions() throws Exception {
    when(runRepo.findById("exec-1")).thenAnswer(inv -> Optional.of(withStatus("PLANNING")));
    when(runRepo.save(any(ExecutionRun.class)))
        .thenThrow(new ObjectOptimisticLockingFailureException(ExecutionRun.class, "exec-1"));

    // The reflection helper unwraps InvocationTargetException; the
    // underlying IllegalStateException is a RuntimeException so it
    // propagates as-is (not via cause).
    assertThatThrownBy(() -> invokePersistPlan(withStatus("PLANNING"), oneSolutionPlan()))
        .isInstanceOf(IllegalStateException.class);

    verify(runRepo, times(3)).findById("exec-1");
    verify(runRepo, times(3)).save(any());
    verify(solutionRepo, never()).save(any());
  }

  private void invokePersistPlan(ExecutionRun run, ExecutionPlan plan) throws Exception {
    Method m =
        ExecutionPlannerService.class.getDeclaredMethod(
            "persistPlan", ExecutionRun.class, ExecutionPlan.class);
    m.setAccessible(true);
    try {
      m.invoke(planner, run, plan);
    } catch (java.lang.reflect.InvocationTargetException ite) {
      // Preserve the underlying cause so assertThatThrownBy can match it.
      Throwable cause = ite.getCause();
      if (cause instanceof RuntimeException re) throw re;
      if (cause instanceof Error er) throw er;
      throw new RuntimeException(cause);
    }
  }

  private ExecutionPlan oneSolutionPlan() {
    SolutionSpec spec =
        new SolutionSpec(
            1, "Solution 1", "do thing", "", "", List.of(), List.of(), 1000L, "MEDIUM");
    return new ExecutionPlan(List.of(spec), List.of(), List.of(), 1000L, new BigDecimal("0.01"));
  }

  private ExecutionRun withStatus(String status) {
    ExecutionRun r = new ExecutionRun();
    r.setId("exec-1");
    r.setStatus(status);
    return r;
  }
}
