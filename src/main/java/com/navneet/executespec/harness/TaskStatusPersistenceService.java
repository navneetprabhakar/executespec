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
          task.setInputTokens(snapshot.inputTokens());
          task.setOutputTokens(snapshot.outputTokens());
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
