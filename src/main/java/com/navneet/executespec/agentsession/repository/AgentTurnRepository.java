package com.navneet.executespec.agentsession.repository;

import java.util.List;

import com.navneet.executespec.agentsession.entity.AgentTurn;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
