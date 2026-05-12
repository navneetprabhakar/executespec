package com.navneet.executespec.activity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository over {@link ActivityFeedEntry} (V133 partitioned table). Reads are scoped
 * to {@code project_id} + a {@code occurred_at} window so PostgreSQL can prune the relevant
 * partition; writes go via the standard {@code save(...)} from {@link ActivityEventEmitter}.
 */
@Repository
public interface ActivityFeedEntryRepository
    extends JpaRepository<ActivityFeedEntry, ActivityFeedEntryId> {

  /**
   * Recent feed entries for a project within a time window. The window is mandatory so PostgreSQL
   * prunes partitions; callers default to "last 30 days" or per-tier retention.
   */
  @Query(
      "SELECT a FROM ActivityFeedEntry a "
          + "WHERE a.projectId = :projectId "
          + "AND a.occurredAt >= :from AND a.occurredAt < :to "
          + "ORDER BY a.occurredAt DESC")
  List<ActivityFeedEntry> findRecentForProject(
      @Param("projectId") UUID projectId, @Param("from") Instant from, @Param("to") Instant to);

  /**
   * Limited variant — same partition-pruning query but with a DB-side LIMIT so we don't pull
   * thousands of rows into the JVM heap for a high-volume project (audit 2026-05-12 review item P
   * #80). Callers SHOULD prefer this when the controller has a known max page size; the unbounded
   * {@link #findRecentForProject} is still around for code paths that intentionally want the whole
   * window.
   */
  @Query(
      "SELECT a FROM ActivityFeedEntry a "
          + "WHERE a.projectId = :projectId "
          + "AND a.occurredAt >= :from AND a.occurredAt < :to "
          + "ORDER BY a.occurredAt DESC")
  List<ActivityFeedEntry> findRecentForProjectLimited(
      @Param("projectId") UUID projectId,
      @Param("from") Instant from,
      @Param("to") Instant to,
      Pageable pageable);
}
