package com.navneet.executespec.agentsession.repository;

import java.util.List;

import com.navneet.executespec.agentsession.entity.AgentSession;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentSessionRepository extends JpaRepository<AgentSession, String> {

  List<AgentSession> findByStatus(String status);

  List<AgentSession> findByUserIdAndStatusOrderByUpdatedAtDesc(
      String userId, String status, Pageable pageable);

  List<AgentSession> findByRunIdAndStatusOrderByUpdatedAtDesc(String runId, String status);
}
