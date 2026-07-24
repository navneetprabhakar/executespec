package com.navneet.executespec.cost;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RunStepUsageRepository extends JpaRepository<RunStepUsage, String> {
  List<RunStepUsage> findByRunIdOrderByCreatedAtAsc(String runId);

  /**
   * Bounded newest-first page of one run's physical attempts. RR-1558 — the run cost endpoint is
   * polled continuously while a run is live, so it must never materialize the whole ledger.
   */
  Page<RunStepUsage> findByRunIdOrderByCreatedAtDesc(String runId, Pageable pageable);

  long countByRunId(String runId);

  long countByRunIdAndAttemptStatus(String runId, String attemptStatus);

  /**
   * Sum of the attempts whose cost is PROVEN. Deliberately excludes non-SUCCESS attempts (persisted
   * with a hard zero cost) and SUCCESS attempts that consumed tokens yet priced to exactly zero —
   * the signature of a model missing from the pricing matrix. The result is a lower bound, never a
   * reconciled total (RR-1556).
   */
  @Query(
      "select coalesce(sum(u.costUsd), 0.0) from RunStepUsage u "
          + "where u.runId = :runId and upper(coalesce(u.attemptStatus, '')) = 'SUCCESS' "
          + "and not (u.costUsd = 0.0 and (u.inputTokens > 0 or u.outputTokens > 0))")
  Double sumKnownCostByRunId(@Param("runId") String runId);

  /** Count of attempts whose cost the ledger cannot prove; the complement of the sum above. */
  @Query(
      "select count(u) from RunStepUsage u "
          + "where u.runId = :runId and (upper(coalesce(u.attemptStatus, '')) <> 'SUCCESS' "
          + "or (u.costUsd = 0.0 and (u.inputTokens > 0 or u.outputTokens > 0)))")
  Long countUnknownCostAttemptsByRunId(@Param("runId") String runId);

  /**
   * Physical attempts scoped to one plan task. Backs the per-task effective-route projection; the
   * SUCCESS filter lives in {@link EffectiveRouteProjection} so exactly one place decides what
   * "served the task" means.
   */
  List<RunStepUsage> findByRunIdAndTaskIdOrderByCreatedAtAsc(String runId, String taskId);

  long countByLlmExecutionContextId(String llmExecutionContextId);

  @Query(
      "select coalesce(sum(u.inputTokens), 0) from RunStepUsage u "
          + "where u.llmExecutionContextId = :contextId and u.attemptStatus = 'SUCCESS'")
  Long sumSuccessfulInputTokensByContextId(@Param("contextId") String contextId);

  @Query(
      "select coalesce(sum(u.outputTokens), 0) from RunStepUsage u "
          + "where u.llmExecutionContextId = :contextId and u.attemptStatus = 'SUCCESS'")
  Long sumSuccessfulOutputTokensByContextId(@Param("contextId") String contextId);

  @Query(
      "select coalesce(sum(u.inputTokens), 0) from RunStepUsage u "
          + "where u.runId = :runId and u.attemptStatus = 'SUCCESS'")
  Long sumSuccessfulInputTokensByRunId(@Param("runId") String runId);

  @Query(
      "select coalesce(sum(u.outputTokens), 0) from RunStepUsage u "
          + "where u.runId = :runId and u.attemptStatus = 'SUCCESS'")
  Long sumSuccessfulOutputTokensByRunId(@Param("runId") String runId);

  @Query(
      "select coalesce(sum(u.cacheReadTokens), 0) from RunStepUsage u "
          + "where u.runId = :runId and u.attemptStatus = 'SUCCESS'")
  Long sumSuccessfulCacheReadTokensByRunId(@Param("runId") String runId);

  @Query(
      "select coalesce(sum(u.cacheWriteTokens), 0) from RunStepUsage u "
          + "where u.runId = :runId and u.attemptStatus = 'SUCCESS'")
  Long sumSuccessfulCacheWriteTokensByRunId(@Param("runId") String runId);

  @Query(
      "select coalesce(sum(u.reasoningTokens), 0) from RunStepUsage u "
          + "where u.runId = :runId and u.attemptStatus = 'SUCCESS'")
  Long sumSuccessfulReasoningTokensByRunId(@Param("runId") String runId);

  @Query(
      "select coalesce(sum(u.costUsd), 0.0) from RunStepUsage u "
          + "where u.llmExecutionContextId = :contextId and u.attemptStatus = 'SUCCESS'")
  Double sumSuccessfulCostByContextId(@Param("contextId") String contextId);
}
