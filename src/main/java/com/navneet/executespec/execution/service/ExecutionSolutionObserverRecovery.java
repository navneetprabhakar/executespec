package com.navneet.executespec.execution.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.entity.ExecutionSolution;
import com.navneet.executespec.execution.entity.ExecutionSolutionRun;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRunRepository;
import com.navneet.executespec.integration.PipelineExecutorPool;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.micrometer.context.ContextSnapshotFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Restart-safe observer recovery for legacy Execution View approved-solution children.
 *
 * <p>A process may die after reserving an {@code execution_solution_run}, after inserting its
 * canonical {@code pipeline_run}, or while the in-memory terminal poll is active. Normal launch
 * recovery cannot call {@code executeApprovedPlan}: that method intentionally skips RUNNING
 * solutions and would derive a false terminal result. This service therefore owns those rows,
 * claims the existing launch recovery lease, and either:
 *
 * <ul>
 *   <li>re-observes the already-created canonical child without submitting anything, or
 *   <li>when the crash preceded child insertion, resumes submission with the already-reserved UUID.
 * </ul>
 *
 * <p>The solution-level RUNNING -> RECONCILING CAS in {@link SolutionExecutionService} prevents a
 * live predecessor and the recovery observer from projecting terminal side effects twice.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionSolutionObserverRecovery {

  static final Duration QUIET_PERIOD = Duration.ofMinutes(5);
  static final Duration LEASE_TIMEOUT = Duration.ofMinutes(30);
  static final int MAX_RECOVERY_ATTEMPTS = 3;

  private static final String INSTANCE_ID =
      (System.getenv("HOSTNAME") == null ? "local" : System.getenv("HOSTNAME"))
          + ":solution-observer:"
          + java.util.UUID.randomUUID().toString().substring(0, 8);

  private final ExecutionRunRepository runRepository;
  private final ExecutionSolutionRepository solutionRepository;
  private final ExecutionSolutionRunRepository attemptRepository;
  private final PipelineRunRepository pipelineRunRepository;
  private final SolutionExecutionService solutionExecutionService;
  private final ExecutionOrchestrator executionOrchestrator;
  private final PipelineExecutorPool executorPool;

  private final Set<String> activeRecoveries = ConcurrentHashMap.newKeySet();

  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    try {
      scanAndRecover();
    } catch (RuntimeException recoveryFailure) {
      log.error("Approved-solution observer startup recovery scan failed", recoveryFailure);
    }
  }

  @Scheduled(
      fixedDelayString = "${execution.solution-observer-recovery.scan-interval-ms:60000}",
      initialDelayString = "${execution.solution-observer-recovery.scan-initial-delay-ms:60000}")
  @SchedulerLock(
      name = "executionSolutionObserverRecovery",
      lockAtLeastFor = "PT5S",
      lockAtMostFor = "PT1M")
  public void scheduledRecoverySweep() {
    try {
      scanAndRecover();
    } catch (RuntimeException recoveryFailure) {
      log.error("Approved-solution observer scheduled recovery scan failed", recoveryFailure);
    }
  }

  void scanAndRecover() {
    Instant now = Instant.now();
    List<ExecutionRun> candidates =
        runRepository.findStaleApprovedSolutionExecutions(now.minus(QUIET_PERIOD));
    for (ExecutionRun candidate : candidates) {
      int observed = candidate.getRecoveryAttempts() == null ? 0 : candidate.getRecoveryAttempts();
      if (observed >= MAX_RECOVERY_ATTEMPTS) {
        log.warn(
            "Approved-solution observer recovery exhausted for exec={} ({}/{})",
            candidate.getId(),
            observed,
            MAX_RECOVERY_ATTEMPTS);
        for (ExecutionSolution solution :
            solutionRepository.findObserverRecoveryCandidates(candidate.getId())) {
          solutionExecutionService.handleFailure(
              candidate,
              solution,
              "RUNNING",
              "Approved-solution observer recovery exhausted after "
                  + MAX_RECOVERY_ATTEMPTS
                  + " attempts; operator decision required");
        }
        continue;
      }
      int claimed =
          runRepository.claimBootRecovery(
              candidate.getId(),
              observed,
              observed + 1,
              now,
              now.minus(LEASE_TIMEOUT),
              INSTANCE_ID);
      if (claimed != 1) continue;
      activeRecoveries.add(candidate.getId());
      try {
        recoverClaimedExecution(candidate.getId());
      } catch (RuntimeException dispatchFailure) {
        activeRecoveries.remove(candidate.getId());
        log.error(
            "Approved-solution observer dispatch failed for exec={}",
            candidate.getId(),
            dispatchFailure);
      }
    }
  }

  private void recoverClaimedExecution(String executionRunId) {
    ExecutionRun run = runRepository.findById(executionRunId).orElse(null);
    if (run == null || !"EXECUTING".equals(run.getStatus())) {
      activeRecoveries.remove(executionRunId);
      return;
    }
    List<ExecutionSolution> candidates =
        solutionRepository.findObserverRecoveryCandidates(executionRunId);
    var snapshot = ContextSnapshotFactory.builder().build().captureAll();
    List<CompletableFuture<Void>> observers =
        candidates.stream()
            .map(
                solution ->
                    CompletableFuture.runAsync(
                        snapshot.wrap(() -> recoverSolution(run, solution)),
                        executorPool.executor()))
            .toList();
    CompletableFuture.allOf(observers.toArray(new CompletableFuture[0]))
        .whenComplete(
            (ignored, error) -> {
              if (error != null) {
                log.error(
                    "Approved-solution observer recovery failed for exec={}",
                    executionRunId,
                    error);
                // Stop refreshing the durable lease. A replacement instance may take over after
                // LEASE_TIMEOUT rather than leaving this execution owned forever by a dead job.
                activeRecoveries.remove(executionRunId);
                return;
              }
              // All formerly in-flight solutions now have a durable outcome. Re-enter normal
              // layer execution so downstream QUEUED dependencies and terminal launch projection
              // continue through the canonical path.
              runRepository
                  .findById(executionRunId)
                  .filter(current -> "EXECUTING".equals(current.getStatus()))
                  .ifPresent(current -> executionOrchestrator.executeApprovedPlan(executionRunId));
            });
  }

  void recoverSolution(ExecutionRun run, ExecutionSolution solution) {
    int generation = SolutionExecutionService.attemptGeneration(solution.getRetryCount());
    ExecutionSolutionRun attempt =
        attemptRepository
            .findBySolutionIdAndAttemptNumber(solution.getId(), generation)
            .orElse(null);
    if (attempt == null) {
      solutionExecutionService.handleFailure(
          run,
          solution,
          "RUNNING",
          "Recovery found a RUNNING solution without its canonical attempt reservation");
      return;
    }

    PipelineRun child = pipelineRunRepository.findById(attempt.getPipelineRunId()).orElse(null);
    if (child == null) {
      // Crash after reservation and before child insertion. executeSolution reaches the same
      // generation reservation and RunOrchestrator.withReservedRunId, so it cannot mint a second
      // child identity.
      solutionExecutionService.executeSolution(solution, run);
      return;
    }

    String pointer = solution.getPipelineRunId();
    if (pointer == null) {
      solutionRepository.bindCanonicalPipelineRunIfMissing(
          solution.getId(), run.getId(), attempt.getPipelineRunId(), Instant.now());
      solution =
          solutionRepository
              .findByIdAndExecutionRun_Id(solution.getId(), run.getId())
              .orElse(solution);
      pointer = solution.getPipelineRunId();
    }
    if (!attempt.getPipelineRunId().equals(pointer)) {
      log.warn(
          "Skipping superseded approved-solution attempt exec={} sol={} reserved={} current={}",
          run.getId(),
          solution.getId(),
          attempt.getPipelineRunId(),
          pointer);
      return;
    }
    solutionExecutionService.observeCanonicalAttempt(
        solution, run, attempt, attempt.getPipelineRunId(), true);
  }

  @Scheduled(fixedDelayString = "${execution.solution-observer-recovery.heartbeat-ms:30000}")
  void heartbeatActiveRecoveries() {
    Instant now = Instant.now();
    for (String executionRunId : Set.copyOf(activeRecoveries)) {
      int updated = runRepository.heartbeatBootRecovery(executionRunId, INSTANCE_ID, now);
      if (updated == 0) activeRecoveries.remove(executionRunId);
    }
  }
}
