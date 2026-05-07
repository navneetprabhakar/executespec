package com.navneet.executespec.prreview.inbox;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
