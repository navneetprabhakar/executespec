package com.navneet.executespec.state.repository;

import java.util.List;

import com.navneet.executespec.state.entity.AgentMessage;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentMessageRepository extends JpaRepository<AgentMessage, String> {

  List<AgentMessage> findByRunIdOrderBySentAt(String runId);

  List<AgentMessage> findTop20ByTaskIdOrderBySentAtDesc(String taskId);
}
