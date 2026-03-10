package com.navneet.executespec.state;

import com.navneet.executespec.state.entity.AgentInbox;
import com.navneet.executespec.state.repository.AgentInboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:5432/executespec",
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.datasource.username=postgres",
    "spring.datasource.password=postgres",
    "spring.jpa.hibernate.ddl-auto=update"
})
class AgentInboxRepositoryTest {

    @Autowired
    private AgentInboxRepository repo;

    @Autowired
    private TestEntityManager em;

    private String runId;

    @BeforeEach
    void setup() {
        runId = UUID.randomUUID().toString();
    }

    @Test
    void undeliveredMessages_returnedForCorrectAgent() {
        AgentInbox msg = AgentInbox.builder()
            .messageId(UUID.randomUUID().toString())
            .runId(runId)
            .toAgent("worker-task-1")
            .fromAgent("planner")
            .messageType("COORDINATION")
            .content("Please write shared contract to .agent-context/shared-contracts.json")
            .delivered(false)
            .createdAt(Instant.now())
            .build();
        em.persistAndFlush(msg);

        // delivered message for same agent — should NOT appear
        AgentInbox delivered = AgentInbox.builder()
            .messageId(UUID.randomUUID().toString())
            .runId(runId)
            .toAgent("worker-task-1")
            .fromAgent("harness")
            .messageType("FYI")
            .content("Already seen")
            .delivered(true)
            .createdAt(Instant.now())
            .deliveredAt(Instant.now())
            .build();
        em.persistAndFlush(delivered);

        List<AgentInbox> results = repo.findByToAgentAndRunIdAndDeliveredFalse("worker-task-1", runId);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).contains("shared-contracts.json");
    }

    @Test
    void markDelivered_updatesDeliveredFlag() {
        String msgId = UUID.randomUUID().toString();
        AgentInbox msg = AgentInbox.builder()
            .messageId(msgId)
            .runId(runId)
            .toAgent("reviewer")
            .fromAgent("worker-task-1")
            .messageType("PROVIDER_FALLBACK")
            .content("Switched from anthropic to openai")
            .delivered(false)
            .createdAt(Instant.now())
            .fromProvider("anthropic")
            .toProvider("openai")
            .build();
        em.persistAndFlush(msg);

        repo.markDelivered(msgId, Instant.now());
        em.clear();

        AgentInbox updated = repo.findById(msgId).orElseThrow();
        assertThat(updated.isDelivered()).isTrue();
        assertThat(updated.getDeliveredAt()).isNotNull();
        // also verify new provider fields persisted
        assertThat(updated.getFromProvider()).isEqualTo("anthropic");
        assertThat(updated.getToProvider()).isEqualTo("openai");
    }

    @Test
    void findByRunId_returnsAllMessagesForRun() {
        AgentInbox m1 = AgentInbox.builder()
            .messageId(UUID.randomUUID().toString()).runId(runId)
            .toAgent("reviewer").fromAgent("worker-1").messageType("FYI")
            .content("done").delivered(false).createdAt(Instant.now()).build();
        AgentInbox m2 = AgentInbox.builder()
            .messageId(UUID.randomUUID().toString()).runId(runId)
            .toAgent("harness").fromAgent("worker-2").messageType("BLOCKER")
            .content("blocked").delivered(true).createdAt(Instant.now()).deliveredAt(Instant.now()).build();
        em.persistAndFlush(m1);
        em.persistAndFlush(m2);

        List<AgentInbox> results = repo.findByRunId(runId);
        assertThat(results).hasSize(2);
    }

    @Test
    void findByRunIdAndMessageType_filtersCorrectly() {
        AgentInbox fallback = AgentInbox.builder()
            .messageId(UUID.randomUUID().toString()).runId(runId)
            .toAgent("reviewer").fromAgent("worker-1").messageType("PROVIDER_FALLBACK")
            .content("switched").delivered(false).createdAt(Instant.now())
            .fromProvider("anthropic").toProvider("openai").build();
        AgentInbox coord = AgentInbox.builder()
            .messageId(UUID.randomUUID().toString()).runId(runId)
            .toAgent("worker-2").fromAgent("harness").messageType("COORDINATION")
            .content("coordinate").delivered(false).createdAt(Instant.now()).build();
        em.persistAndFlush(fallback);
        em.persistAndFlush(coord);

        List<AgentInbox> fallbacks = repo.findByRunIdAndMessageType(runId, "PROVIDER_FALLBACK");
        assertThat(fallbacks).hasSize(1);
        assertThat(fallbacks.get(0).getFromProvider()).isEqualTo("anthropic");
    }
}
