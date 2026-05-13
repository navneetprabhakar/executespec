package com.navneet.executespec.codeintel.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.codeintel.entity.FilePurposeSummary;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * PR-N2 (V196, 2026-05-14) — JPA repository for the per-file purpose summary cache.
 *
 * <p>Cache reads go through {@link #findByRepoFullNameAndFilePathAndFileSha} — that's the composite
 * key {@code FilePurposeSummaryService} uses for the SHA-keyed short-circuit.
 *
 * <p>{@link #findByGeneratedAtBefore} powers a future eviction sweeper (PR-H sibling) that ages out
 * rows older than ~90 days regardless of churn. Not wired into a scheduled job yet — defer the
 * sweeper until cost telemetry shows enough churn to justify the runtime overhead (the original
 * deferred-handoff doc was deleted once PR-N2 shipped; this comment is the surviving rationale).
 */
public interface FilePurposeSummaryRepository extends JpaRepository<FilePurposeSummary, UUID> {

  /**
   * The hot-path lookup. Returns {@link Optional#empty()} on cache miss → caller calls the LLM and
   * persists the new row. Spring Data derives the query from the method name (no @Query).
   */
  Optional<FilePurposeSummary> findByRepoFullNameAndFilePathAndFileSha(
      String repoFullName, String filePath, String fileSha);

  /**
   * Sweeper hook — find rows older than {@code cutoff} for eviction. Pre-PR-H this method has no
   * live caller; staged here so the sweeper PR doesn't need a back-edit on this file.
   */
  List<FilePurposeSummary> findByGeneratedAtBefore(Instant cutoff);
}
