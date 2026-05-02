package com.navneet.executespec.project.pr;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PullRequestRecordRepository extends JpaRepository<PullRequestRecord, UUID> {

  List<PullRequestRecord> findByRunIdOrderByCreatedAtAsc(String runId);

  Optional<PullRequestRecord> findByRepoFullNameAndPrNumber(String repoFullName, Integer prNumber);

  /** v2.5.2 — open-PR count for a given repo full name. */
  long countByRepoFullNameAndStatus(String repoFullName, PullRequestRecord.Status status);

  /**
   * V169 (2026-05-02) — every system PR (BOOTSTRAP / ARCHITECTURE / CONTRACT / CHAT_TOOL)
   * persisted for this project, newest first. Used by the project dashboard to surface PRs
   * that don't tie to a single run.
   */
  List<PullRequestRecord> findByProjectIdAndKindNotOrderByCreatedAtDesc(
      String projectId, PullRequestRecord.Kind excludeKind);
}
