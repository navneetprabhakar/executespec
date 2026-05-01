package com.navneet.executespec.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit coverage for {@link ExecutionRunCasUpdater} — the shared CAS helper promoted out of {@code
 * ExecutionOrchestrator} on 2026-04-26 so every long- running async write site that holds an {@link
 * ExecutionRun} reference can survive {@link ObjectOptimisticLockingFailureException}.
 *
 * <p>Mirrors the style of {@link ExecutionOrchestratorTriggerAnalysisRetryTest} — Mockito + the
 * same fresh-instance findById pattern (each retry refetches a fresh row, never the same in-memory
 * reference).
 */
@ExtendWith(MockitoExtension.class)
class ExecutionRunCasUpdaterTest {

  @Mock ExecutionRunRepository runRepo;
  @InjectMocks ExecutionRunCasUpdater cas;

  @Test
  void happyPath_singleAttempt_returnsTrueAndAppliesMutator() {
    ExecutionRun fresh = withStatus("EXECUTING");
    when(runRepo.findById("exec-1")).thenReturn(Optional.of(fresh));
    when(runRepo.save(any(ExecutionRun.class))).thenAnswer(inv -> inv.getArgument(0));

    AtomicInteger callCount = new AtomicInteger();
    boolean ok =
        cas.casUpdate(
            "exec-1",
            r -> {
              callCount.incrementAndGet();
              r.setStatus("ANALYSING");
            });

    assertThat(ok).isTrue();
    assertThat(callCount.get()).isEqualTo(1);
    assertThat(fresh.getStatus()).isEqualTo("ANALYSING");
    verify(runRepo, times(1)).findById("exec-1");
    verify(runRepo, times(1)).save(any());
  }

  @Test
  void conflictThenSuccess_returnsTrueAfterRetry_mutatorRunsOnEveryFetch() {
    // Each findById returns a *fresh* EXECUTING row so the mutator's
    // setStatus on a stale reference can't smuggle the change forward.
    when(runRepo.findById("exec-1")).thenAnswer(inv -> Optional.of(withStatus("EXECUTING")));
    when(runRepo.save(any(ExecutionRun.class)))
        .thenThrow(new ObjectOptimisticLockingFailureException(ExecutionRun.class, "exec-1"))
        .thenAnswer(inv -> inv.getArgument(0));

    AtomicInteger callCount = new AtomicInteger();
    boolean ok =
        cas.casUpdate(
            "exec-1",
            r -> {
              callCount.incrementAndGet();
              r.setStatus("ANALYSING");
            });

    assertThat(ok).isTrue();
    // Mutator must run once per fetched row — the conflict retry refetches.
    assertThat(callCount.get()).isEqualTo(2);
    verify(runRepo, times(2)).findById("exec-1");
    verify(runRepo, times(2)).save(any());
  }

  @Test
  void allThreeAttemptsConflict_returnsFalse() {
    when(runRepo.findById("exec-1")).thenAnswer(inv -> Optional.of(withStatus("EXECUTING")));
    when(runRepo.save(any(ExecutionRun.class)))
        .thenThrow(new ObjectOptimisticLockingFailureException(ExecutionRun.class, "exec-1"));

    AtomicInteger callCount = new AtomicInteger();
    boolean ok =
        cas.casUpdate(
            "exec-1",
            r -> {
              callCount.incrementAndGet();
              r.setStatus("ANALYSING");
            });

    assertThat(ok).isFalse();
    assertThat(callCount.get()).isEqualTo(3);
    verify(runRepo, times(3)).findById("exec-1");
    verify(runRepo, times(3)).save(any());
  }

  @Test
  void rowMissing_returnsFalse_doesNotInvokeMutator() {
    when(runRepo.findById("exec-1")).thenReturn(Optional.empty());

    AtomicInteger callCount = new AtomicInteger();
    boolean ok = cas.casUpdate("exec-1", r -> callCount.incrementAndGet());

    assertThat(ok).isFalse();
    assertThat(callCount.get()).isZero();
    verify(runRepo, times(1)).findById("exec-1");
    verify(runRepo, never()).save(any());
  }

  private ExecutionRun withStatus(String status) {
    ExecutionRun run = new ExecutionRun();
    run.setId("exec-1");
    run.setStatus(status);
    return run;
  }
}
