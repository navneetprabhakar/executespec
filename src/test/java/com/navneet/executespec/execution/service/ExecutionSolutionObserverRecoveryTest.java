package com.navneet.executespec.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.entity.ExecutionSolution;
import com.navneet.executespec.execution.entity.ExecutionSolutionRun;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRunRepository;
import com.navneet.executespec.integration.PipelineExecutorPool;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExecutionSolutionObserverRecoveryTest {

  @Mock ExecutionRunRepository runRepository;
  @Mock ExecutionSolutionRepository solutionRepository;
  @Mock ExecutionSolutionRunRepository attemptRepository;
  @Mock PipelineRunRepository pipelineRunRepository;
  @Mock SolutionExecutionService solutionExecutionService;
  @Mock ExecutionOrchestrator executionOrchestrator;
  @Mock PipelineExecutorPool executorPool;

  private ExecutionSolutionObserverRecovery recovery;

  @BeforeEach
  void setUp() {
    recovery =
        new ExecutionSolutionObserverRecovery(
            runRepository,
            solutionRepository,
            attemptRepository,
            pipelineRunRepository,
            solutionExecutionService,
            executionOrchestrator,
            executorPool);
  }

  @Test
  void existingCanonicalChildOnlyRearmsObservationAndNeverSubmits() {
    ExecutionRun run = execution();
    ExecutionSolution solution = solution("child-1");
    ExecutionSolutionRun attempt = attempt("child-1");
    PipelineRun child = PipelineRun.builder().runId("child-1").status("WORKING").build();
    when(attemptRepository.findBySolutionIdAndAttemptNumber("solution-1", 1))
        .thenReturn(Optional.of(attempt));
    when(pipelineRunRepository.findById("child-1")).thenReturn(Optional.of(child));

    recovery.recoverSolution(run, solution);

    verify(solutionExecutionService)
        .observeCanonicalAttempt(solution, run, attempt, "child-1", true);
    verify(solutionExecutionService, never()).executeSolution(any(), any());
  }

  @Test
  void reservationOnlyCrashResumesTheSameAttemptGeneration() {
    ExecutionRun run = execution();
    ExecutionSolution solution = solution(null);
    ExecutionSolutionRun attempt = attempt("reserved-child");
    when(attemptRepository.findBySolutionIdAndAttemptNumber("solution-1", 1))
        .thenReturn(Optional.of(attempt));
    when(pipelineRunRepository.findById("reserved-child")).thenReturn(Optional.empty());

    recovery.recoverSolution(run, solution);

    verify(solutionExecutionService).executeSolution(solution, run);
    verify(solutionExecutionService, never())
        .observeCanonicalAttempt(any(), any(), any(), any(), anyBoolean());
  }

  @Test
  void childInsertedBeforePointerSaveBindsTheReservedIdentityThenObserves() {
    ExecutionRun run = execution();
    ExecutionSolution detached = solution(null);
    ExecutionSolution rebound = solution("child-1");
    ExecutionSolutionRun attempt = attempt("child-1");
    when(attemptRepository.findBySolutionIdAndAttemptNumber("solution-1", 1))
        .thenReturn(Optional.of(attempt));
    when(pipelineRunRepository.findById("child-1"))
        .thenReturn(Optional.of(PipelineRun.builder().runId("child-1").build()));
    when(solutionRepository.bindCanonicalPipelineRunIfMissing(
            eq("solution-1"), eq("exec-1"), eq("child-1"), any(Instant.class)))
        .thenReturn(1);
    when(solutionRepository.findByIdAndExecutionRun_Id("solution-1", "exec-1"))
        .thenReturn(Optional.of(rebound));

    recovery.recoverSolution(run, detached);

    verify(solutionExecutionService)
        .observeCanonicalAttempt(rebound, run, attempt, "child-1", true);
    verify(solutionExecutionService, never()).executeSolution(any(), any());
  }

  @Test
  void missingDurableAttemptFailsClosedWithoutMintingAChild() {
    ExecutionRun run = execution();
    ExecutionSolution solution = solution(null);
    when(attemptRepository.findBySolutionIdAndAttemptNumber("solution-1", 1))
        .thenReturn(Optional.empty());

    recovery.recoverSolution(run, solution);

    verify(solutionExecutionService)
        .handleFailure(
            run,
            solution,
            "RUNNING",
            "Recovery found a RUNNING solution without its canonical attempt reservation");
    verifyNoInteractions(pipelineRunRepository);
  }

  @Test
  void genericRecoveryQueryAndObserverQueryUseDisjointRepositoryMethods() throws Exception {
    assertThat(
            ExecutionRunRepository.class.getMethod(
                "findStaleApprovedSolutionExecutions", Instant.class))
        .isNotNull();
    assertThat(
            ExecutionSolutionRepository.class.getMethod(
                "findObserverRecoveryCandidates", String.class))
        .isNotNull();
  }

  private static ExecutionRun execution() {
    return ExecutionRun.builder().id("exec-1").status("EXECUTING").build();
  }

  private static ExecutionSolution solution(String pipelineRunId) {
    return ExecutionSolution.builder()
        .id("solution-1")
        .status("RUNNING")
        .retryCount(0)
        .pipelineRunId(pipelineRunId)
        .build();
  }

  private static ExecutionSolutionRun attempt(String pipelineRunId) {
    return ExecutionSolutionRun.builder()
        .id(UUID.randomUUID().toString())
        .solutionId("solution-1")
        .pipelineRunId(pipelineRunId)
        .attemptNumber(1)
        .build();
  }
}
