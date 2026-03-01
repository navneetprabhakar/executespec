package com.navneet.agentteam.state;

import com.navneet.agentteam.state.entity.AgentInbox;
import com.navneet.agentteam.state.repository.AgentInboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InboxServiceTest {

    @Mock
    private AgentInboxRepository repo;

    @InjectMocks
    private InboxService inboxService;

    @Test
    void drainInbox_returnsFormattedMessages_andMarksDelivered() {
        String runId = "run-1";
        String agentId = "worker-task-1";

        AgentInbox m1 = AgentInbox.builder()
            .messageId("msg-1").runId(runId).toAgent(agentId)
            .fromAgent("harness").messageType("COORDINATION")
            .content("Use shared-contracts.json for shared output")
            .delivered(false).createdAt(Instant.now()).build();

        AgentInbox m2 = AgentInbox.builder()
            .messageId("msg-2").runId(runId).toAgent(agentId)
            .fromAgent("planner").messageType("FYI")
            .content("Task priority changed")
            .delivered(false).createdAt(Instant.now()).build();

        when(repo.findByToAgentAndRunIdAndDeliveredFalse(agentId, runId))
            .thenReturn(List.of(m1, m2));

        String result = inboxService.drainInbox(agentId, runId);

        assertThat(result).isNotNull();
        assertThat(result).contains("INBOX MESSAGES");
        assertThat(result).contains("COORDINATION");
        assertThat(result).contains("shared-contracts.json");
        assertThat(result).contains("FYI");
        assertThat(result).contains("Task priority changed");

        // markDelivered called for each message
        verify(repo).markDelivered(eq("msg-1"), any(Instant.class));
        verify(repo).markDelivered(eq("msg-2"), any(Instant.class));
    }

    @Test
    void drainInbox_returnsNull_whenEmpty() {
        when(repo.findByToAgentAndRunIdAndDeliveredFalse(any(), any()))
            .thenReturn(List.of());

        String result = inboxService.drainInbox("worker-task-1", "run-99");

        assertThat(result).isNull();
        verify(repo, never()).markDelivered(any(), any());
    }

    @Test
    void sendProviderFallback_savesWithCorrectFields() {
        ArgumentCaptor<AgentInbox> captor = ArgumentCaptor.forClass(AgentInbox.class);

        inboxService.sendProviderFallback("run-1", "worker-task-1",
            "anthropic", "openai", "rate limited");

        verify(repo).save(captor.capture());
        AgentInbox saved = captor.getValue();
        assertThat(saved.getMessageType()).isEqualTo("PROVIDER_FALLBACK");
        assertThat(saved.getFromProvider()).isEqualTo("anthropic");
        assertThat(saved.getToProvider()).isEqualTo("openai");
        assertThat(saved.getToAgent()).isEqualTo("reviewer");
        assertThat(saved.getContent()).contains("anthropic").contains("openai");
    }

    @Test
    void sendFileLockConflict_sendsCoordinationMessage() {
        ArgumentCaptor<AgentInbox> captor = ArgumentCaptor.forClass(AgentInbox.class);

        inboxService.sendFileLockConflict("run-1", "worker-task-1", "worker-task-2", "src/Foo.java");

        verify(repo).save(captor.capture());
        AgentInbox saved = captor.getValue();
        assertThat(saved.getMessageType()).isEqualTo("COORDINATION");
        assertThat(saved.getContent()).contains("src/Foo.java");
        assertThat(saved.getContent()).contains("shared-contracts.json");
    }
}
