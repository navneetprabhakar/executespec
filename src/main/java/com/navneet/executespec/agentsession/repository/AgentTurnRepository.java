package com.navneet.executespec.agentsession.repository;

import java.util.List;

import com.navneet.executespec.agentsession.entity.AgentTurn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentTurnRepository extends JpaRepository<AgentTurn, String> {

  List<AgentTurn> findBySessionIdOrderBySequenceAsc(String sessionId);

  long countBySessionId(String sessionId);

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
