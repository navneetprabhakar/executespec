package com.navneet.agentteam.state.repository;

import com.navneet.agentteam.state.entity.AgentMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentMessageRepository extends JpaRepository<AgentMessage, String> {

    List<AgentMessage> findByRunIdOrderBySentAt(String runId);

    List<AgentMessage> findTop20ByTaskIdOrderBySentAtDesc(String taskId);
}
