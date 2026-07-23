package com.navneet.executespec.sharing.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.sharing.entity.ProjectShareLink;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectShareLinkRepository extends JpaRepository<ProjectShareLink, UUID> {

  Optional<ProjectShareLink> findByTokenHash(String tokenHash);

  /** Atomic counter bump avoids lost updates under concurrent public reads. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE ProjectShareLink s SET s.views = COALESCE(s.views, 0) + 1, "
          + "s.lastViewedAt = :viewedAt WHERE s.id = :linkId")
  int recordView(@Param("linkId") UUID linkId, @Param("viewedAt") Instant viewedAt);

  /** All links for a project, newest first — used by the dashboard share-link panel. */
  List<ProjectShareLink> findByProjectIdOrderByCreatedAtDesc(String projectId);

  /**
   * Active (non-revoked, non-expired) link count for a project — used by the tier-gate to enforce
   * per-tier active-link caps. {@code now} is supplied by the caller so the query is timezone-
   * agnostic + testable.
   */
  @Query(
      "SELECT COUNT(s) FROM ProjectShareLink s "
          + "WHERE s.projectId = :projectId "
          + "  AND s.revokedAt IS NULL "
          + "  AND (s.expiresAt IS NULL OR s.expiresAt > :now)")
  long countActiveByProject(@Param("projectId") String projectId, @Param("now") Instant now);
}
