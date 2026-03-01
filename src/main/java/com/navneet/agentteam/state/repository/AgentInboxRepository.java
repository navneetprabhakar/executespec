package com.navneet.agentteam.state.repository;

import com.navneet.agentteam.state.entity.AgentInbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AgentInboxRepository extends JpaRepository<AgentInbox, String> {

    List<AgentInbox> findByToAgentAndRunIdAndDeliveredFalse(String toAgent, String runId);

    List<AgentInbox> findByRunId(String runId);

    List<AgentInbox> findByRunIdAndMessageType(String runId, String messageType);

    @Modifying
    @Query("UPDATE AgentInbox m SET m.delivered = true, m.deliveredAt = :now WHERE m.messageId = :id")
    void markDelivered(@Param("id") String messageId, @Param("now") Instant now);
}
