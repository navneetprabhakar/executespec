package com.navneet.executespec.harness;

import java.time.Duration;
import java.time.Instant;

import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.state.repository.AgentTaskRepository;

import org.springframework.dao.DataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Owns the transaction boundary for one durable agent-task status transition. */
@Service
@Slf4j
@RequiredArgsConstructor
public class TaskStatusPersistenceService {

  private final AgentTaskRepository taskRepo;
  private final RunCostTracker costTracker;

  public enum Result {
    UPDATED,
    ROW_NOT_FOUND,
    STALE_RECOVERY_ATTEMPT,
    TERMINAL_OVERWRITE
  }

  /**
   * Locks, fences, mutates, and flushes the task row in one proxied transaction. Retry wraps the
   * transaction so every retry gets a fresh transaction and row lock.
   */
  @Transactional
  @Retryable(
      retryFor = DataAccessException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100, multiplier = 2.0))
  public Result persist(
      String runId,
      String taskIdShort,
      String status,
      String failureReason,
      String failureDiagnosticJson,
      Integer expectedRecoveryAttempt) {
    String key = runId.substring(0, Math.min(8, runId.length())) + "_" + taskIdShort;
    var existing = taskRepo.findByIdForUpdate(key);
    if (existing.isEmpty()) {
      return Result.ROW_NOT_FOUND;
    }

    var task = existing.get();
    if (expectedRecoveryAttempt != null
        && !SequentialExecutor.recoveryAttemptMatches(
            expectedRecoveryAttempt, task.getRecoveryAttempt())) {
      return Result.STALE_RECOVERY_ATTEMPT;
    }
    if (isTerminalTaskStatus(task.getStatus()) && !status.equals(task.getStatus())) {
      return Result.TERMINAL_OVERWRITE;
    }

    Instant now = Instant.now();
    task.setStatus(status);
    task.setLastActivityAt(now);
    if ("WORKING".equals(status) && task.getStartedAt() == null) {
      task.setStartedAt(now);
    }

    boolean terminal = isPersistedTerminalStatus(status);
    if (terminal && task.getCompletedAt() == null) {
      task.setCompletedAt(now);
      if (task.getStartedAt() != null && task.getDurationMs() == null) {
        task.setDurationMs(Duration.between(task.getStartedAt(), now).toMillis());
      }
    }

    if (terminal && costTracker != null) {
      try {
        var snapshot = costTracker.snapshotTask(runId, taskIdShort);
        if (snapshot != null && (snapshot.inputTokens() > 0 || snapshot.outputTokens() > 0)) {
          task.setInputTokens(RunCostTracker.saturatedInt(snapshot.inputTokens()));
          task.setOutputTokens(RunCostTracker.saturatedInt(snapshot.outputTokens()));
        }
      } catch (Exception exception) {
        log.debug("Task token snapshot failed for {}: {}", key, exception.getMessage());
      }
    }

    if (failureReason != null && !failureReason.isBlank()) {
      task.setLastLlmFailure(truncate(failureReason, 4000, "… [truncated]"));
    }
    if (failureDiagnosticJson != null && !failureDiagnosticJson.isBlank()) {
      task.setFailureDiagnosticJson(truncate(failureDiagnosticJson, 8000, "\"… [truncated]\"}"));
    }

    taskRepo.saveAndFlush(task);
    return Result.UPDATED;
  }

  /**
   * RR-266 (2026-07-21, P1 recovery race) — atomic compare-and-set reopen of a terminal task for a
   * SELECTED continuation rerun (model/provider handoff). The previous {@code
   * SequentialExecutor.prepareTaskForContinuation} did an unlocked {@code findById} → mutate →
   * {@code saveAndFlush} outside any transaction, so a cancellation, a rolling-restart recovery
   * claimant, or a newer terminal writer could race the reset: a stale executor could reopen a
   * COMPLETE/CANCELLED task to {@code PENDING}, erase its completion timing, and spend on a
   * duplicate rerun.
   *
   * <p>The reopen is now taken under the same pessimistic row lock {@link #persist} uses, and only
   * when BOTH invariants hold:
   *
   * <ul>
   *   <li>the row's recovery generation still matches the generation this executor started the
   *       primary attempt on (a rolling-restart takeover increments {@code recovery_attempt}), so a
   *       stale post-takeover executor cannot revive a task another JVM now owns; and
   *   <li>the row is NOT {@code CANCELLED} (a concurrent cancellation) or {@code BLOCKED} (an
   *       approval-blocked proposal). The owning executor legitimately reopens from its own
   *       in-flight state (the pre-smoke-retry escalation path reopens a still-{@code WORKING} row)
   *       or from its own completed state (the post-completion path reopens a {@code FAILED} /
   *       {@code PARTIAL} / soft {@code COMPLETE} row); only another authority's terminal decision
   *       is off-limits.
   * </ul>
   *
   * <p>A {@code false} return means the caller is NOT the authority to rerun: the terminal result
   * must stand and no continuation rerun may be dispatched.
   */
  @Transactional
  @Retryable(
      retryFor = DataAccessException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100, multiplier = 2.0))
  public boolean reopenForContinuation(
      String runId, String taskIdShort, Integer expectedRecoveryAttempt) {
    if (runId == null || runId.length() < 8 || taskIdShort == null) {
      return false;
    }
    String key = runId.substring(0, Math.min(8, runId.length())) + "_" + taskIdShort;
    var existing = taskRepo.findByIdForUpdate(key);
    if (existing.isEmpty()) {
      log.warn("reopenForContinuation: row not found id={}", key);
      return false;
    }
    var task = existing.get();
    if (expectedRecoveryAttempt != null
        && !SequentialExecutor.recoveryAttemptMatches(
            expectedRecoveryAttempt, task.getRecoveryAttempt())) {
      log.warn(
          "reopenForContinuation: fenced stale worker id={} expectedRecoveryAttempt={} status={}",
          key,
          expectedRecoveryAttempt,
          task.getStatus());
      return false;
    }
    if (!isContinuationReopenSource(task.getStatus())) {
      log.warn(
          "reopenForContinuation: refused reopen id={} status={} (cancelled/blocked by another"
              + " authority)",
          key,
          task.getStatus());
      return false;
    }
    Instant now = Instant.now();
    task.setStatus("PENDING");
    task.setCompletedAt(null);
    task.setDurationMs(null);
    task.setLastActivityAt(now);
    taskRepo.saveAndFlush(task);
    log.info("reopenForContinuation: reopened id={} for selected handoff rerun", key);
    return true;
  }

  /**
   * A denylist: the owning executor may reopen from any of its own states, but never from another
   * authority's terminal decision. {@code CANCELLED} (a concurrent cancellation) and {@code
   * BLOCKED} (an approval-blocked proposal) are refused so neither can be reopened into a paid
   * rerun; the stale-JVM race is guarded separately by recovery-generation fencing.
   */
  private static boolean isContinuationReopenSource(String status) {
    return !"CANCELLED".equals(status) && !"BLOCKED".equals(status);
  }

  /**
   * Persists the goal-doneness + continuation-decision evidence (V250) in its own proxied
   * transaction. The row lock ({@code findByIdForUpdate}) requires an active transaction; when the
   * caller reaches this from a fast-fail path with no ambient transaction (e.g. an early worker
   * router/provider error), an inline pessimistic read would throw {@code
   * TransactionRequiredException} and silently drop the goal decision — exactly when an operator
   * most needs to know why the task failed and whether another route might help.
   */
  @Transactional
  @Retryable(
      retryFor = DataAccessException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100, multiplier = 2.0))
  public Result persistGoalDecisionJson(
      String runId, String taskIdShort, String goalDonenessJson, String continuationDecisionJson) {
    if (runId == null || runId.length() < 8 || taskIdShort == null) {
      return Result.ROW_NOT_FOUND;
    }
    String key = runId.substring(0, Math.min(8, runId.length())) + "_" + taskIdShort;
    var existing = taskRepo.findByIdForUpdate(key);
    if (existing.isEmpty()) {
      return Result.ROW_NOT_FOUND;
    }
    var task = existing.get();
    task.setGoalDonenessJson(goalDonenessJson);
    task.setContinuationDecisionJson(continuationDecisionJson);
    taskRepo.saveAndFlush(task);
    return Result.UPDATED;
  }

  private static boolean isPersistedTerminalStatus(String status) {
    return "COMPLETE".equals(status)
        || "PARTIAL".equals(status)
        || "FAILED".equals(status)
        || "BLOCKED".equals(status);
  }

  private static boolean isTerminalTaskStatus(String status) {
    return isPersistedTerminalStatus(status) || "CANCELLED".equals(status);
  }

  private static String truncate(String value, int maxLength, String suffix) {
    return value.length() > maxLength ? value.substring(0, maxLength) + suffix : value;
  }
}
