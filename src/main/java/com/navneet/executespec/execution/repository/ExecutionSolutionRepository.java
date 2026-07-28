package com.navneet.executespec.execution.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.execution.entity.ExecutionSolution;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExecutionSolutionRepository extends JpaRepository<ExecutionSolution, String> {

  /** Security boundary for lifecycle mutations: a solution id is never sufficient on its own. */
  Optional<ExecutionSolution> findByIdAndExecutionRun_Id(String id, String executionRunId);

  /**
   * Default order-by lookup. Lazy collections (dependencies, repos) are NOT fetched — callers that
   * walk those must use {@link #findByExecutionRunIdWithDependencies} or run inside an open
   * transaction.
   */
  List<ExecutionSolution> findByExecutionRun_IdOrderBySequenceNumberAsc(String executionRunId);

  /**
   * RR-1689 — the solution that currently names this child run, i.e. the still-current attempt. A
   * RETRY overwrites {@code pipelineRunId}, so an empty result means the run has been superseded.
   */
  Optional<ExecutionSolution> findFirstByPipelineRunId(String pipelineRunId);

  /** Canonical in-flight solutions that need their child observer restored after a restart. */
  @Query(
      "SELECT s FROM ExecutionSolution s WHERE s.executionRun.id = :executionRunId "
          + "AND s.status IN ('RUNNING', 'RECONCILING') ORDER BY s.sequenceNumber ASC")
  List<ExecutionSolution> findObserverRecoveryCandidates(
      @Param("executionRunId") String executionRunId);

  /**
   * Atomically bind the pre-reserved child identity when the process died after child insertion but
   * before the convenience pointer was saved on the solution.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @org.springframework.transaction.annotation.Transactional
  @Query(
      "UPDATE ExecutionSolution s SET s.pipelineRunId = :pipelineRunId, s.updatedAt = :updatedAt "
          + "WHERE s.id = :solutionId AND s.executionRun.id = :executionRunId "
          + "AND s.status = 'RUNNING' AND s.pipelineRunId IS NULL")
  int bindCanonicalPipelineRunIfMissing(
      @Param("solutionId") String solutionId,
      @Param("executionRunId") String executionRunId,
      @Param("pipelineRunId") String pipelineRunId,
      @Param("updatedAt") Instant updatedAt);

  /** One durable winner is allowed to project a terminal child onto its RUNNING solution. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @org.springframework.transaction.annotation.Transactional
  @Query(
      "UPDATE ExecutionSolution s SET s.status = 'RECONCILING', s.updatedAt = :claimedAt "
          + "WHERE s.id = :solutionId AND s.executionRun.id = :executionRunId "
          + "AND s.status = 'RUNNING' AND s.pipelineRunId = :pipelineRunId")
  int claimTerminalReconciliation(
      @Param("solutionId") String solutionId,
      @Param("executionRunId") String executionRunId,
      @Param("pipelineRunId") String pipelineRunId,
      @Param("claimedAt") Instant claimedAt);

  /**
   * Take over a reconciliation whose owner died after the durable claim. The timestamp predicate is
   * the lease fence: concurrent replicas cannot both refresh the same stale row.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @org.springframework.transaction.annotation.Transactional
  @Query(
      "UPDATE ExecutionSolution s SET s.updatedAt = :claimedAt "
          + "WHERE s.id = :solutionId AND s.executionRun.id = :executionRunId "
          + "AND s.status = 'RECONCILING' AND s.pipelineRunId = :pipelineRunId "
          + "AND s.updatedAt < :staleBefore")
  int claimStaleTerminalReconciliation(
      @Param("solutionId") String solutionId,
      @Param("executionRunId") String executionRunId,
      @Param("pipelineRunId") String pipelineRunId,
      @Param("claimedAt") Instant claimedAt,
      @Param("staleBefore") Instant staleBefore);

  /**
   * Eager-fetches the {@code dependencies} set so callers outside a transaction (e.g. {@link
   * com.navneet.executespec.execution.service.SolutionDependencyResolver}) can iterate without a
   * {@code LazyInitializationException}.
   */
  @EntityGraph(attributePaths = {"dependencies"})
  List<ExecutionSolution> findWithDependenciesByExecutionRun_IdOrderBySequenceNumberAsc(
      String executionRunId);

  /**
   * v2.5.12 — eager-fetches the {@code repoMappings} list. Needed by {@link
   * com.navneet.executespec.execution.service.ExecutionOrchestrator#executeApprovedPlan} which runs
   * on an {@code @Async} thread with no open Hibernate session and used to throw {@code
   * LazyInitializationException} when walking {@code solution.getRepoMappings()} after all
   * solutions finished. The exception killed the final "setStatus(COMPLETE)" step, leaving the
   * ExecutionRun stuck at EXECUTING forever (observed on launch 9d5fb7d4).
   *
   * <p>Kept as a separate query from {@link
   * #findWithDependenciesByExecutionRun_IdOrderBySequenceNumberAsc} to avoid
   * MultipleBagFetchException on the mixed Set (dependencies) + List (repoMappings) collections.
   */
  @EntityGraph(attributePaths = {"repoMappings"})
  List<ExecutionSolution> findWithRepoMappingsByExecutionRun_IdOrderBySequenceNumberAsc(
      String executionRunId);

  /**
   * RR-530 — atomically claim a human execution decision. The transition applies ONLY when the row
   * is still in {@code expectedStatus}, so an illegal state, a replay of an already-applied
   * decision and a concurrent second decision all return 0 rather than mutating counters or
   * resuming the launch. The actor/action/timestamp are written inside the same statement, so the
   * audit trail can only ever describe the caller that actually won.
   *
   * <p>{@code retryDelta} is 1 for RETRY (which consumes retry budget) and 0 for every other
   * action. Failure phase/reason are cleared because the decision supersedes them.
   *
   * @return 1 when this caller won the transition, 0 when it did not
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update ExecutionSolution s
         set s.status = :nextStatus,
             s.retryCount = s.retryCount + :retryDelta,
             s.failurePhase = null,
             s.failureReason = null,
             s.lastDecisionAction = :action,
             s.lastDecisionByUserId = :actorUserId,
             s.lastDecisionAt = :decidedAt,
             s.updatedAt = :decidedAt
       where s.id = :solutionId
         and s.executionRun.id = :executionRunId
         and s.status = :expectedStatus
      """)
  int claimDecision(
      @Param("solutionId") String solutionId,
      @Param("executionRunId") String executionRunId,
      @Param("expectedStatus") String expectedStatus,
      @Param("nextStatus") String nextStatus,
      @Param("action") String action,
      @Param("actorUserId") String actorUserId,
      @Param("retryDelta") int retryDelta,
      @Param("decidedAt") Instant decidedAt);

  /**
   * RR-530 — atomically claim manual-fix completion. Legal only from {@code AWAITING_MANUAL_FIX},
   * the state a prior FIX decision puts the solution into; a solution that is QUEUED, RUNNING,
   * FAILED or already DONE cannot be marked DONE by this path.
   *
   * @return 1 when this caller won the transition, 0 when it did not
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update ExecutionSolution s
         set s.status = 'DONE',
             s.completedAt = :completedAt,
             s.failurePhase = null,
             s.failureReason = null,
             s.lastDecisionAction = 'MANUAL_READY',
             s.lastDecisionByUserId = :actorUserId,
             s.lastDecisionAt = :completedAt,
             s.updatedAt = :completedAt
       where s.id = :solutionId
         and s.executionRun.id = :executionRunId
         and s.status = 'AWAITING_MANUAL_FIX'
      """)
  int claimManualFixDone(
      @Param("solutionId") String solutionId,
      @Param("executionRunId") String executionRunId,
      @Param("actorUserId") String actorUserId,
      @Param("completedAt") Instant completedAt);
}
