package com.navneet.executespec.project.pr;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PullRequestRecordRepository extends JpaRepository<PullRequestRecord, UUID> {

    List<PullRequestRecord> findByRunIdOrderByCreatedAtAsc(String runId);

    Optional<PullRequestRecord> findByRepoFullNameAndPrNumber(String repoFullName, Integer prNumber);

    /** v2.5.2 — open-PR count for a given repo full name. */
    long countByRepoFullNameAndStatus(String repoFullName, PullRequestRecord.Status status);
}
