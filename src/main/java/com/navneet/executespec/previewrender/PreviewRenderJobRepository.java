package com.navneet.executespec.previewrender;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Render jobs (V302, single-flight index in V306).
 *
 * <p>The lease transitions are single atomic statements on purpose. A render outlives the request
 * that started it, so ownership has to survive a restart of the instance holding it, and a
 * read-modify-write through the entity would lose that race against a reclaiming instance.
 */
@Repository
public interface PreviewRenderJobRepository extends JpaRepository<PreviewRenderJob, UUID> {

  Optional<PreviewRenderJob> findFirstByRunIdOrderByRequestedAtDesc(String runId);

  @Query(
      "SELECT j FROM PreviewRenderJob j "
          + "WHERE j.runId = :runId AND j.status IN ('QUEUED', 'RENDERING')")
  List<PreviewRenderJob> findActiveForRun(@Param("runId") String runId);

  /**
   * Claims an unstarted job for this instance. Succeeds once: the {@code QUEUED} predicate is what
   * stops two instances from both executing the same row.
   *
   * @return 1 when the lease was taken, 0 when someone else already owns it
   */
  @Modifying
  @Transactional
  @Query(
      nativeQuery = true,
      value =
          """
          UPDATE preview_render_job
             SET status = 'RENDERING', lease_owner = :owner, lease_expires_at = :expiresAt
           WHERE id = :jobId AND status = 'QUEUED'
          """)
  int claimLease(
      @Param("jobId") UUID jobId,
      @Param("owner") String owner,
      @Param("expiresAt") Instant expiresAt);

  /**
   * Extends the lease while the render is still running.
   *
   * @return 0 once the lease has been reclaimed by someone else, which tells the holder to stop
   */
  @Modifying
  @Transactional
  @Query(
      nativeQuery = true,
      value =
          """
          UPDATE preview_render_job
             SET lease_expires_at = :expiresAt
           WHERE id = :jobId AND status = 'RENDERING' AND lease_owner = :owner
          """)
  int renewLease(
      @Param("jobId") UUID jobId,
      @Param("owner") String owner,
      @Param("expiresAt") Instant expiresAt);

  /**
   * Terminalizes a job and drops its lease. Fenced on {@code lease_owner} so an instance that lost
   * its lease mid-render cannot overwrite the outcome recorded by whoever reclaimed it.
   */
  @Modifying
  @Transactional
  @Query(
      nativeQuery = true,
      value =
          """
          UPDATE preview_render_job
             SET status = :status,
                 -- Explicit cast: :reason is null on a clean finish, and PostgreSQL cannot infer
                 -- the parameter type from an untyped null. NOTE: never put an apostrophe in a
                 -- comment inside @Query. The Spring Data query rewriter reads it as an
                 -- unterminated quoted range and fails repository creation at context startup.
                 failure_reason = CAST(:reason AS VARCHAR),
                 surfaces_found = :found, surfaces_rendered = :rendered,
                 completed_at = :completedAt, lease_owner = NULL, lease_expires_at = NULL
           WHERE id = :jobId AND lease_owner = :owner
          """)
  int releaseLease(
      @Param("jobId") UUID jobId,
      @Param("owner") String owner,
      @Param("status") String status,
      @Param("reason") String reason,
      @Param("found") int found,
      @Param("rendered") int rendered,
      @Param("completedAt") Instant completedAt);

  /**
   * Terminalizes renders nobody is executing any more, for one run.
   *
   * <p>Two abandonment shapes: a {@code RENDERING} row whose holder stopped renewing, and a {@code
   * QUEUED} row whose instance died before it ever claimed one. Without this an interrupted render
   * stays active forever, keeps satisfying {@link #findActiveForRun}, and permanently prevents the
   * run from being rendered again.
   */
  @Modifying
  @Transactional
  @Query(
      nativeQuery = true,
      value =
          """
          UPDATE preview_render_job
             SET status = 'FAILED', failure_reason = 'LEASE_EXPIRED', completed_at = :now,
                 lease_owner = NULL, lease_expires_at = NULL
           WHERE run_id = :runId AND status IN ('QUEUED', 'RENDERING')
             AND ((lease_expires_at IS NOT NULL AND lease_expires_at < :now)
               OR (lease_expires_at IS NULL AND requested_at < :queuedCutoff))
          """)
  int reclaimAbandonedForRun(
      @Param("runId") String runId,
      @Param("now") Instant now,
      @Param("queuedCutoff") Instant queuedCutoff);

  /** The same reclaim across every run, for the periodic sweeper. */
  @Modifying
  @Transactional
  @Query(
      nativeQuery = true,
      value =
          """
          UPDATE preview_render_job
             SET status = 'FAILED', failure_reason = 'LEASE_EXPIRED', completed_at = :now,
                 lease_owner = NULL, lease_expires_at = NULL
           WHERE status IN ('QUEUED', 'RENDERING')
             AND ((lease_expires_at IS NOT NULL AND lease_expires_at < :now)
               OR (lease_expires_at IS NULL AND requested_at < :queuedCutoff))
          """)
  int reclaimAbandoned(@Param("now") Instant now, @Param("queuedCutoff") Instant queuedCutoff);
}
