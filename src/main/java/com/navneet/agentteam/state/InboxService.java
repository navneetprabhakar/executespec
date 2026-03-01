package com.navneet.agentteam.state;

import com.navneet.agentteam.state.entity.AgentInbox;
import com.navneet.agentteam.state.repository.AgentInboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class InboxService {

    private final AgentInboxRepository repo;

    public void send(String runId, String fromAgent, String toAgent, String messageType, String content) {
        AgentInbox msg = AgentInbox.builder()
            .messageId(UUID.randomUUID().toString())
            .runId(runId)
            .fromAgent(fromAgent)
            .toAgent(toAgent)
            .messageType(messageType)
            .content(content)
            .delivered(false)
            .createdAt(Instant.now())
            .build();
        repo.save(msg);
        log.debug("[Inbox] {} -> {}: [{}]", fromAgent, toAgent, messageType);
    }

    public void sendProviderFallback(String runId, String agentId,
                                     String fromProvider, String toProvider, String reason) {
        AgentInbox msg = AgentInbox.builder()
            .messageId(UUID.randomUUID().toString())
            .runId(runId)
            .fromAgent(agentId)
            .toAgent("reviewer")
            .messageType("PROVIDER_FALLBACK")
            .content("Agent " + agentId + " switched from " + fromProvider
                     + " to " + toProvider + ". Reason: " + reason)
            .delivered(false)
            .createdAt(Instant.now())
            .fromProvider(fromProvider)
            .toProvider(toProvider)
            .build();
        repo.save(msg);
        log.info("[Inbox] PROVIDER_FALLBACK: {} {} -> {}", agentId, fromProvider, toProvider);
    }

    public void sendFileLockConflict(String runId, String fromAgent, String toAgent, String lockedFile) {
        String content = "File lock conflict on '" + lockedFile
            + "'. Write your shared output to .agent-context/shared-contracts.json instead.";
        send(runId, fromAgent, toAgent, "COORDINATION", content);
    }

    @Transactional
    public String drainInbox(String agentId, String runId) {
        List<AgentInbox> msgs = repo.findByToAgentAndRunIdAndDeliveredFalse(agentId, runId);
        if (msgs.isEmpty()) return null;

        StringBuilder sb = new StringBuilder("═══ INBOX MESSAGES ═══\n");
        for (AgentInbox m : msgs) {
            sb.append("[").append(m.getMessageType()).append("] from ")
              .append(m.getFromAgent()).append(":\n")
              .append(m.getContent()).append("\n\n");
            repo.markDelivered(m.getMessageId(), Instant.now());
        }
        log.debug("[Inbox] Drained {} messages for {}", msgs.size(), agentId);
        return sb.toString().trim();
    }

    public List<AgentInbox> getFallbackEvents(String runId) {
        return repo.findByRunIdAndMessageType(runId, "PROVIDER_FALLBACK");
    }

    public List<AgentInbox> getDeliveredMessages(String runId) {
        return repo.findByRunId(runId).stream()
            .filter(AgentInbox::isDelivered)
            .toList();
    }
}
