package com.navneet.executespec.agentsession.repository;

import java.util.List;

import com.navneet.executespec.agentsession.entity.AgentActionEvent;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentActionEventRepository extends JpaRepository<AgentActionEvent, String> {

  List<AgentActionEvent> findBySessionIdOrderByCreatedAtAsc(String sessionId);
}
