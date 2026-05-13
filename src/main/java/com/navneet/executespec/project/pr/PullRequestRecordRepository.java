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
   * V169 (2026-05-02) — every system PR (BOOTSTRAP / ARCHITECTURE / CONTRACT / CHAT_TOOL) persisted
   * for this project, newest first. Used by the project dashboard to surface PRs that don't tie to
   * a single run.
   */
  List<PullRequestRecord> findByProjectIdAndKindNotOrderByCreatedAtDesc(
      String projectId, PullRequestRecord.Kind excludeKind);

  /**
   * 2026-05-02 — used by {@code CiFailureAutoFixService} to find the PR (and originating run) a
   * {@code check_run} failure webhook is targeting. One row is created per (repo, PR); the branch
   * is unique within that pair so {@code findFirst} is unambiguous.
   */
  Optional<PullRequestRecord> findFirstByRepoFullNameAndHeadBranchOrderByCreatedAtDesc(
      String repoFullName, String headBranch);

  /**
   * 2026-05-13 — open-only variants for PrRefreshService. Merged + closed PRs don't change at
   * GitHub once they're terminal, so the refresh sweep shouldn't pull them from the DB just to skip
   * them in-loop. Cuts the DB round-trip + JPA hydration cost on projects with many historical PRs
   * (e.g. pplai-api has 28+ PRs but typically &lt;5 open at a time).
   */
  List<PullRequestRecord> findByRunIdAndStatusOrderByCreatedAtAsc(
      String runId, PullRequestRecord.Status status);

  /** Open system PRs for a project (BOOTSTRAP / ARCHITECTURE / CONTRACT / CHAT_TOOL kinds). */
  List<PullRequestRecord> findByProjectIdAndKindNotAndStatusOrderByCreatedAtDesc(
      String projectId, PullRequestRecord.Kind excludeKind, PullRequestRecord.Status status);
}
