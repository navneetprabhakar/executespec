package com.navneet.executespec.harness;

import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

/** Owns the transaction boundary for one durable agent-task status transition. */
@Service
@Slf4j
public class TaskStatusPersistenceService {

  private final AgentTaskRepository taskRepo;
  private final RunCostTracker costTracker;
  private final PipelineRunRepository runRepo;

  @Autowired
  public TaskStatusPersistenceService(
      AgentTaskRepository taskRepo, RunCostTracker costTracker, PipelineRunRepository runRepo) {
    this.taskRepo = taskRepo;
    this.costTracker = costTracker;
    this.runRepo = runRepo;
  }

  /** Bounded/test constructor; production injects the run lock used by continuation admission. */
  public TaskStatusPersistenceService(AgentTaskRepository taskRepo, RunCostTracker costTracker) {
    this(taskRepo, costTracker, null);
  }

  /**
   * RR-265 — a self-contained mapper for the continuation-action-history read/modify/write. Jackson
   * {@link ObjectMapper} is thread-safe once configured; this is a bounded internal helper, not an
   * injected collaborator, so the bounded test constructor stays a 2-arg call.
   */
  private final ObjectMapper actionHistoryMapper = new ObjectMapper();

  public enum Result {
    UPDATED,
    ROW_NOT_FOUND,
    STALE_RECOVERY_ATTEMPT,
    TERMINAL_OVERWRITE,
    CANCELLED_WINS
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
   * Publishes a rollback outbox transition only against the exact task status and recovery
   * generation snapshotted when rollback became terminal. Replaying after the task commit but
   * before outbox acknowledgement is idempotent; any other intervening task transition is fenced.
   */
  @Transactional
  @Retryable(
      retryFor = DataAccessException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100, multiplier = 2.0))
  public Result publishWorkspaceGenerationTransition(
      String runId,
      String taskIdShort,
      String targetStatus,
      String expectedStatus,
      Integer expectedRecoveryAttempt,
      String failureReason) {
    if (runId == null
        || runId.length() < 8
        || taskIdShort == null
        || targetStatus == null
        || expectedStatus == null) return Result.ROW_NOT_FOUND;
    if (runRepo != null) {
      var run = runRepo.findByIdForTaskContinuation(runId);
      if (run.isEmpty()) return Result.ROW_NOT_FOUND;
      if (!"WORKING".equals(run.get().getStatus())) return Result.CANCELLED_WINS;
    }
    String key = runId.substring(0, Math.min(8, runId.length())) + "_" + taskIdShort;
    var existing = taskRepo.findByIdForUpdate(key);
    if (existing.isEmpty()) return Result.ROW_NOT_FOUND;
    var task = existing.get();
    if ("CANCELLED".equals(task.getStatus())) return Result.CANCELLED_WINS;
    int expectedRecovery = expectedRecoveryAttempt == null ? 0 : expectedRecoveryAttempt;
    if (!SequentialExecutor.recoveryAttemptMatches(expectedRecovery, task.getRecoveryAttempt())) {
      return Result.STALE_RECOVERY_ATTEMPT;
    }
    if (targetStatus.equals(task.getStatus())) {
      return Result.UPDATED;
    }
    if (!expectedStatus.equals(task.getStatus())) {
      return Result.TERMINAL_OVERWRITE;
    }

    Instant now = Instant.now();
    task.setStatus(targetStatus);
    task.setLastActivityAt(now);
    if ("PENDING".equals(targetStatus)) {
      task.setCompletedAt(null);
      task.setDurationMs(null);
    } else if ("PARTIAL".equals(targetStatus)) {
      if (task.getCompletedAt() == null) task.setCompletedAt(now);
      if (task.getStartedAt() != null && task.getDurationMs() == null) {
        task.setDurationMs(Duration.between(task.getStartedAt(), now).toMillis());
      }
      if (failureReason != null && !failureReason.isBlank()) {
        task.setLastLlmFailure(truncate(failureReason, 4000, "… [truncated]"));
      }
    } else {
      return Result.ROW_NOT_FOUND;
    }
    taskRepo.saveAndFlush(task);
    return Result.UPDATED;
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

  /**
   * RR-265 (P1 audit durability) — atomically append one continuation-action-history entry to the
   * task's {@code continuation_actions_json} (V252) under the same pessimistic row lock and proxied
   * transaction {@link #persist} uses.
   *
   * <p>The previous {@code SequentialExecutor.appendContinuationActionHistory} called {@code
   * findByIdForUpdate} (a {@code PESSIMISTIC_WRITE} query that REQUIRES an active transaction)
   * directly from the non-transactional executor and swallowed every failure, so an executed or
   * skipped provider/model action could be absent from durable evidence while SSE claimed it
   * happened — losing the approval/recovery audit trail on reload. The read-modify-write is now one
   * transaction, fenced by recovery generation exactly like the goal-decision writer, so a stale
   * JVM cannot clobber a newer document.
   *
   * @param actionJson a single pre-built action object (from the executor, which owns the domain
   *     fields); its {@code attempt} index is (re)assigned here from the durable array length.
   */
  @Transactional
  @Retryable(
      retryFor = DataAccessException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100, multiplier = 2.0))
  public Result appendContinuationAction(
      String runId, String taskIdShort, String actionJson, Integer expectedRecoveryAttempt) {
    if (runId == null || runId.length() < 8 || taskIdShort == null || actionJson == null) {
      return Result.ROW_NOT_FOUND;
    }
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
    try {
      ArrayNode actions;
      String existingJson = task.getContinuationActionsJson();
      if (existingJson == null || existingJson.isBlank()) {
        actions = actionHistoryMapper.createArrayNode();
      } else {
        var parsed = actionHistoryMapper.readTree(existingJson);
        actions = parsed.isArray() ? (ArrayNode) parsed : actionHistoryMapper.createArrayNode();
      }
      var parsedAction = actionHistoryMapper.readTree(actionJson);
      if (!parsedAction.isObject()) {
        return Result.ROW_NOT_FOUND;
      }
      ObjectNode action = (ObjectNode) parsedAction;
      action.put("attempt", actions.size());
      actions.add(action);
      task.setContinuationActionsJson(actionHistoryMapper.writeValueAsString(actions));
      taskRepo.saveAndFlush(task);
      return Result.UPDATED;
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      log.warn("appendContinuationAction: malformed JSON for {}: {}", key, ex.getMessage());
      return Result.ROW_NOT_FOUND;
    }
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
