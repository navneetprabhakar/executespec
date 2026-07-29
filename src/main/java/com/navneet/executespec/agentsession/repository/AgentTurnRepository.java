package com.navneet.executespec.agentsession.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import com.navneet.executespec.agentsession.entity.AgentTurn;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentTurnRepository extends JpaRepository<AgentTurn, String> {

  List<AgentTurn> findBySessionIdOrderBySequenceAsc(String sessionId);

  /** Loads only the newest bounded transcript rows, avoiding full-session materialisation. */
  List<AgentTurn> findBySessionIdOrderBySequenceDesc(String sessionId, Pageable pageable);

  /** Loads the bounded page immediately before a turn cursor in descending sequence order. */
  List<AgentTurn> findBySessionIdAndSequenceLessThanOrderBySequenceDesc(
      String sessionId, int sequence, Pageable pageable);

  long countBySessionId(String sessionId);

  void deleteBySessionId(String sessionId);

  boolean existsByIdAndSessionId(String id, String sessionId);

  boolean existsBySessionIdAndStatus(String sessionId, String status);

  /**
   * Oldest-first bounded recovery scan backed by V355's partial {@code (created_at,id) WHERE
   * status='STREAMING'} index.
   */
  @Query(
      """
      SELECT t FROM AgentTurn t
      WHERE t.status = 'STREAMING'
        AND t.createdAt <= :staleBefore
      ORDER BY t.createdAt ASC, t.id ASC
      """)
  List<AgentTurn> findStaleStreaming(@Param("staleBefore") Instant staleBefore, Pageable pageable);

  /** Rechecks terminal state under a row lock before recovery mutates the candidate. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT t FROM AgentTurn t WHERE t.id = :id")
  Optional<AgentTurn> findByIdForUpdate(@Param("id") String id);

  /** One authoritative PostgreSQL clock sample per recovery batch. */
  @Query(value = "SELECT clock_timestamp()", nativeQuery = true)
  Instant databaseNow();

  @Query(
      """
      SELECT t FROM AgentTurn t
      WHERE t.sessionId IN :sessionIds
        AND t.linkedRunId IS NOT NULL
        AND t.sequence = (
          SELECT MAX(t2.sequence)
          FROM AgentTurn t2
          WHERE t2.sessionId = t.sessionId
            AND t2.linkedRunId IS NOT NULL
        )
      """)
  List<AgentTurn> findLatestLinkedRunTurnsBySessionIds(
      @Param("sessionIds") List<String> sessionIds);
}
