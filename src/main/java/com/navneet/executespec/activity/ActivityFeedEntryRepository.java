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

  /**
   * Filtered keyset page for the customer-facing feed. All user-selectable filters are applied in
   * the database before the limit, so a dense run-event prefix cannot hide matching rows or make
   * {@code hasMore} lie. The {@code (occurredAt,id)} tuple is a stable descending cursor even when
   * several events share the same timestamp.
   */
  // F5 (2026-07-18): the previous JPQL used `(:param IS NULL OR col = :param)` for the nullable
  // typed filters. When actorUserId / cursorOccurredAt / cursorId are null, Hibernate binds an
  // UNTYPED null, and PostgreSQL rejects it with "could not determine data type of parameter $n" —
  // so the default (unfiltered) activity feed always 500'd. This native form casts only the
  // nullable binds to their column type; the non-null :projectId/:from/:to/:eventTypes keep their
  // type from the column side.
  @Query(
      value =
          "SELECT a.* FROM activity_feed a "
              + "WHERE a.project_id = :projectId "
              + "AND a.occurred_at >= :from AND a.occurred_at < :to "
              + "AND a.event_type IN (:eventTypes) "
              + "AND (CAST(:actorUserId AS uuid) IS NULL OR a.actor_user_id = CAST(:actorUserId AS uuid)) "
              + "AND (CAST(:targetType AS text) IS NULL OR a.target_type = CAST(:targetType AS text)) "
              + "AND (CAST(:targetId AS text) IS NULL OR a.target_id = CAST(:targetId AS text)) "
              + "AND (CAST(:cursorOccurredAt AS timestamp) IS NULL "
              + "OR a.occurred_at < CAST(:cursorOccurredAt AS timestamp) "
              + "OR (a.occurred_at = CAST(:cursorOccurredAt AS timestamp) AND a.id < CAST(:cursorId AS uuid))) "
              + "ORDER BY a.occurred_at DESC, a.id DESC",
      nativeQuery = true)
  List<ActivityFeedEntry> findFilteredPage(
      @Param("projectId") UUID projectId,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("eventTypes") List<String> eventTypes,
      @Param("actorUserId") UUID actorUserId,
      @Param("targetType") String targetType,
      @Param("targetId") String targetId,
      @Param("cursorOccurredAt") Instant cursorOccurredAt,
      @Param("cursorId") UUID cursorId,
      Pageable pageable);

  /** Stable, bounded export page pinned to the job's creation timestamp. */
  @Query(
      "SELECT a FROM ActivityFeedEntry a "
          + "WHERE a.projectId = :projectId "
          + "AND a.occurredAt >= :from AND a.occurredAt < :to "
          + "ORDER BY a.occurredAt DESC, a.id DESC")
  List<ActivityFeedEntry> findProjectExportPage(
      @Param("projectId") UUID projectId,
      @Param("from") Instant from,
      @Param("to") Instant to,
      Pageable pageable);
}
