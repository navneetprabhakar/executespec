package com.navneet.executespec.agentsession.repository;

import java.util.List;

import com.navneet.executespec.agentsession.entity.AgentTurn;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentTurnRepository extends JpaRepository<AgentTurn, String> {

  List<AgentTurn> findBySessionIdOrderBySequenceAsc(String sessionId);

  long countBySessionId(String sessionId);
}
