package com.navneet.executespec.prreview.inbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PrCommentInboxRepository extends JpaRepository<PrCommentInbox, Long> {

  /** Idempotent webhook ingestion — a retry of the same {@code commentId} on the same PR no-ops. */
  Optional<PrCommentInbox> findByRepoFullNameAndPrNumberAndCommentId(
      String repoFullName, Integer prNumber, Long commentId);

  /** v3 RunDetail page chip + side-panel ChatPane — newest comments first. */
  List<PrCommentInbox> findByRunIdOrderByCreatedAtDesc(String runId);

  /** Hot path for the action-row chip — count only what the user can act on. */
  long countByRunIdAndStatus(String runId, String status);

  /**
   * RR-195 — atomically claim a PENDING comment for apply. Exactly one concurrent request can move
   * PENDING -> APPLY_QUEUED, so repeated clicks/requests cannot each enqueue a duplicate workspace
   * mutation. Returns the number of rows changed (0 = already claimed, addressed, dismissed, or not
   * this run's comment).
   */
  @Modifying
  @Query(
      "UPDATE PrCommentInbox c SET c.status = 'APPLY_QUEUED', c.updatedAt = :now "
          + "WHERE c.id = :id AND c.runId = :runId AND c.status = 'PENDING'")
  int claimForApply(@Param("id") Long id, @Param("runId") String runId, @Param("now") Instant now);

  /**
   * RR-195 — release a claim back to PENDING when the apply could not be enqueued (bounded queue
   * saturated) or failed, so the user can retry. Only a still-claimed row is reverted.
   */
  @Modifying
  @Query(
      "UPDATE PrCommentInbox c SET c.status = 'PENDING', c.updatedAt = :now "
          + "WHERE c.id = :id AND c.runId = :runId AND c.status = 'APPLY_QUEUED'")
  int releaseApplyClaim(
      @Param("id") Long id, @Param("runId") String runId, @Param("now") Instant now);
}
