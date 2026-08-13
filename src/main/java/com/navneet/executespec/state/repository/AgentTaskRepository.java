package com.navneet.executespec.state.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.state.entity.AgentTask;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentTaskRepository extends JpaRepository<AgentTask, String> {

  List<AgentTask> findByRunId(String runId);

  List<AgentTask> findByRunIdIn(List<String> runIds);

  List<AgentTask> findByRunIdAndStatusIn(String runId, List<String> statuses);

  List<AgentTask> findByRunIdOrderByStartedAtAsc(String runId);

  List<AgentTask> findByStatus(String status);

  /**
   * Converges every nonterminal task after the parent run has durably won cancellation. The bulk
   * CAS is deliberately independent of worker callbacks: a crashed worker or a recovery pass that
   * finishes immediately before cancellation must not leave PENDING/LOCKED/WORKING rows beneath a
   * CANCELLED parent. Terminal task evidence is immutable and therefore excluded.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @org.springframework.transaction.annotation.Transactional
  @Query(
      "UPDATE AgentTask t SET t.status = 'CANCELLED', "
          + "t.completedAt = COALESCE(t.completedAt, :now), t.lastActivityAt = :now "
          + "WHERE t.runId = :runId AND t.status IN ('PENDING', 'LOCKED', 'WORKING')")
  int cancelNonterminalForRun(@Param("runId") String runId, @Param("now") Instant now);

  /**
   * 2026-05-04 — used by {@link
   * com.navneet.executespec.harness.SequentialExecutor#resolveResumeAction} to inspect a specific
   * task's persisted status during boot-time replay. Returns Optional because resume may run for
   * tasks that were never persisted (worker died before the first {@code persistTaskStatus} call).
   */
  Optional<AgentTask> findByRunIdAndTaskId(String runId, String taskId);

  /**
   * Serializes task persistence with the rolling-restart recovery CAS. A stale worker that already
   * holds this row lock finishes its write before takeover; a worker arriving after takeover sees
   * the incremented recovery generation and is fenced before saving.
   */
  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT t FROM AgentTask t WHERE t.taskId = :taskId")
  Optional<AgentTask> findByIdForUpdate(@Param("taskId") String taskId);

  /**
   * Claims one exact continuation proposal before queueing. A concurrent/same-proposal caller
   * updates zero rows and must interpret the durable receipt status: CLAIMED conflicts while
   * QUEUED/COMPLETED replays. A different proposal may replace only a terminal prior receipt.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @org.springframework.transaction.annotation.Transactional
  @Query(
      "UPDATE AgentTask t SET t.continuationApprovalId = :approvalId, "
          + "t.continuationApprovalProposalDigest = :proposalDigest, "
          + "t.continuationApprovalStatus = 'CLAIMED', "
          + "t.continuationApprovalProvider = :provider, "
          + "t.continuationApprovalModel = :model, "
          + "t.continuationApprovalAction = :action, "
          + "t.continuationApprovalPostReview = :postReview, "
          + "t.continuationApprovalUpdatedAt = CURRENT_TIMESTAMP "
          + "WHERE t.taskId = :taskId AND t.runId = :runId "
          + "AND t.continuationDecisionJson = :proposalJson "
          + "AND (t.continuationApprovalProposalDigest IS NULL "
          + "OR (t.continuationApprovalProposalDigest <> :proposalDigest "
          + "AND t.continuationApprovalStatus IN ('COMPLETED', 'REJECTED', 'FAILED')))")
  int claimContinuationApproval(
      @Param("taskId") String taskId,
      @Param("runId") String runId,
      @Param("proposalJson") String proposalJson,
      @Param("proposalDigest") String proposalDigest,
      @Param("approvalId") String approvalId,
      @Param("provider") String provider,
      @Param("model") String model,
      @Param("action") String action,
      @Param("postReview") boolean postReview);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @org.springframework.transaction.annotation.Transactional
  @Query(
      "UPDATE AgentTask t SET t.continuationApprovalStatus = :status, "
          + "t.continuationApprovalUpdatedAt = CURRENT_TIMESTAMP "
          + "WHERE t.taskId = :taskId AND t.continuationApprovalId = :approvalId "
          + "AND t.continuationApprovalProposalDigest = :proposalDigest "
          + "AND ((:status = 'QUEUED' AND t.continuationApprovalStatus = 'CLAIMED') "
          + "OR (:status <> 'QUEUED' "
          + "AND t.continuationApprovalStatus IN ('CLAIMED', 'QUEUED')))")
  int updateContinuationApprovalStatus(
      @Param("taskId") String taskId,
      @Param("approvalId") String approvalId,
      @Param("proposalDigest") String proposalDigest,
      @Param("status") String status);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @org.springframework.transaction.annotation.Transactional
  @Query(
      "UPDATE AgentTask t SET t.continuationApprovalId = NULL, "
          + "t.continuationApprovalProposalDigest = NULL, "
          + "t.continuationApprovalStatus = NULL, "
          + "t.continuationApprovalProvider = NULL, "
          + "t.continuationApprovalModel = NULL, "
          + "t.continuationApprovalAction = NULL, "
          + "t.continuationApprovalPostReview = NULL, "
          + "t.continuationApprovalUpdatedAt = NULL "
          + "WHERE t.taskId = :taskId AND t.continuationApprovalId = :approvalId "
          + "AND t.continuationApprovalProposalDigest = :proposalDigest "
          + "AND t.continuationApprovalStatus IN ('CLAIMED', 'QUEUED')")
  int releaseContinuationApprovalClaim(
      @Param("taskId") String taskId,
      @Param("approvalId") String approvalId,
      @Param("proposalDigest") String proposalDigest);

  List<AgentTask> findByContinuationApprovalStatusAndContinuationApprovalUpdatedAtBefore(
      String status, Instant staleBefore);

  /**
   * A CLAIMED receipt has not reached the durable QUEUED boundary, so no worker can have started. A
   * stale claim is therefore safe to release for an exact idempotent retry.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @org.springframework.transaction.annotation.Transactional
  @Query(
      "UPDATE AgentTask t SET t.continuationApprovalId = NULL, "
          + "t.continuationApprovalProposalDigest = NULL, "
          + "t.continuationApprovalStatus = NULL, "
          + "t.continuationApprovalProvider = NULL, "
          + "t.continuationApprovalModel = NULL, "
          + "t.continuationApprovalAction = NULL, "
          + "t.continuationApprovalPostReview = NULL, "
          + "t.continuationApprovalUpdatedAt = NULL "
          + "WHERE t.taskId = :taskId AND t.continuationApprovalId = :approvalId "
          + "AND t.continuationApprovalProposalDigest = :proposalDigest "
          + "AND t.continuationApprovalStatus = 'CLAIMED' "
          + "AND t.continuationApprovalUpdatedAt < :staleBefore")
  int releaseStaleClaimedContinuationApproval(
      @Param("taskId") String taskId,
      @Param("approvalId") String approvalId,
      @Param("proposalDigest") String proposalDigest,
      @Param("staleBefore") Instant staleBefore);

  /**
   * A stale QUEUED receipt may have entered a mutating worker before the JVM died. It cannot be
   * replayed safely without a durable workspace checkpoint, so terminalize it fail-closed.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @org.springframework.transaction.annotation.Transactional
  @Query(
      "UPDATE AgentTask t SET t.continuationApprovalStatus = 'FAILED', "
          + "t.continuationApprovalUpdatedAt = :now, t.status = 'FAILED', "
          + "t.completedAt = :now "
          + "WHERE t.taskId = :taskId AND t.continuationApprovalId = :approvalId "
          + "AND t.continuationApprovalProposalDigest = :proposalDigest "
          + "AND t.continuationApprovalStatus = 'QUEUED' "
          + "AND t.continuationApprovalUpdatedAt < :staleBefore")
  int failStaleQueuedContinuationApproval(
      @Param("taskId") String taskId,
      @Param("approvalId") String approvalId,
      @Param("proposalDigest") String proposalDigest,
      @Param("staleBefore") Instant staleBefore,
      @Param("now") Instant now);

  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE AgentTask t SET t.status = 'LOCKED', t.startedAt = :now "
          + "WHERE t.taskId = :taskId AND t.status = 'PENDING'")
  int lockIfPending(@Param("taskId") String taskId, @Param("now") Instant now);

  List<AgentTask> findByParentTaskIdOrderBySubtaskIndexAsc(String parentTaskId);

  /** Atomic lock: PENDING → WORKING in a single UPDATE (no intermediate LOCKED state). */
  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE AgentTask t SET t.status = 'WORKING', t.assignedModel = :model, "
          + "t.branchName = :branch, t.startedAt = :now "
          + "WHERE t.taskId = :taskId AND t.status = 'PENDING'")
  int lockAndStart(
      @Param("taskId") String taskId,
      @Param("model") String model,
      @Param("branch") String branch,
      @Param("now") Instant now);

  /**
   * Atomically marks a stale in-flight task as owned by a recovery attempt. The task remains
   * WORKING so the resume executor can reuse its persisted snapshot, while {@code recoveryAttempt}
   * and {@code lastActivityAt} provide durable evidence that takeover happened. A fresh task (or a
   * task already made terminal by another worker) cannot be claimed.
   */
  @Modifying(clearAutomatically = true)
  @org.springframework.transaction.annotation.Transactional
  @Query(
      "UPDATE AgentTask t SET t.status = 'WORKING', "
          + "t.recoveryAttempt = COALESCE(t.recoveryAttempt, 0) + 1, "
          + "t.lastActivityAt = :now "
          + "WHERE t.taskId = :taskId AND t.status IN ('WORKING', 'LOCKED') "
          + "AND (t.lastActivityAt IS NULL OR t.lastActivityAt < :staleBefore)")
  int claimStaleForRecovery(
      @Param("taskId") String taskId,
      @Param("now") Instant now,
      @Param("staleBefore") Instant staleBefore);

  /**
   * N2 (audit 2026-05-29) — model-comparison feed filtered in the DB, replacing {@code
   * StateStore.computeModelComparison}'s {@code findAll().stream().filter(...)} which loaded the
   * whole agent_task table into heap. Split into all / since-windowed variants (the caller branches
   * on a null window) so no {@code :param IS NULL} predicate is needed. {@code inputTokens IS NOT
   * NULL} preserves the prior filter; the provider/model grouping + null-defaulting stays in Java.
   * {@code startedAt > :since} matches the prior strict {@code isAfter} (also drops null startedAt,
   * exactly as the prior {@code startedAt != null && isAfter}).
   */
  @Query("SELECT t FROM AgentTask t WHERE t.inputTokens IS NOT NULL")
  List<AgentTask> findWithTokens();

  @Query("SELECT t FROM AgentTask t WHERE t.inputTokens IS NOT NULL AND t.startedAt > :since")
  List<AgentTask> findWithTokensSince(@Param("since") Instant since);

  /** N2 — org-scoped variants: restrict to tasks whose run is in the given org's run-id set. */
  @Query("SELECT t FROM AgentTask t WHERE t.inputTokens IS NOT NULL AND t.runId IN :runIds")
  List<AgentTask> findWithTokensByRunIds(@Param("runIds") List<String> runIds);

  @Query(
      "SELECT t FROM AgentTask t WHERE t.inputTokens IS NOT NULL "
          + "AND t.runId IN :runIds AND t.startedAt > :since")
  List<AgentTask> findWithTokensByRunIdsSince(
      @Param("runIds") List<String> runIds, @Param("since") Instant since);
}
