package com.navneet.executespec.saga;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SideEffectOutboxRepository extends JpaRepository<SideEffectOutboxEntry, UUID> {

  /** Returns PENDING entries whose next_retry_at has passed (up to batchSize). */
  @Query(
      "SELECT e FROM SideEffectOutboxEntry e WHERE e.status IN ('PENDING', 'PROCESSING') "
          + "AND e.nextRetryAt <= :now ORDER BY e.nextRetryAt ASC LIMIT :limit")
  List<SideEffectOutboxEntry> findDue(@Param("now") Instant now, @Param("limit") int limit);

  /** Mark entry as PROCESSING atomically. Returns count updated (0 if already taken). */
  @Modifying
  @Query(
      "UPDATE SideEffectOutboxEntry e SET e.status = 'PROCESSING' "
          + "WHERE e.id = :id AND e.status = 'PENDING'")
  int markProcessing(@Param("id") UUID id);

  List<SideEffectOutboxEntry> findByRunId(String runId);
}
