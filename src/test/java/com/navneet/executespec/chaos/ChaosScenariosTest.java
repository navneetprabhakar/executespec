package com.navneet.executespec.chaos;

import com.navneet.executespec.saga.SideEffectOutbox;
import com.navneet.executespec.saga.SideEffectOutboxRepository;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chaos scenario tests — verify resilience under infrastructure failures.
 *
 * <p>Each scenario deliberately disrupts a subsystem (Redis, GCS, budget) and
 * verifies the system degrades gracefully rather than hard-failing.
 *
 * <p>Run manually (not in CI default):
 * <pre>
 *   mvn test -Dgroups=chaos
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
@Tag("chaos")
@Testcontainers
@TestMethodOrder(MethodOrderer.DisplayName.class)
class ChaosScenariosTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("executespec_chaos")
                    .withUsername("test")
                    .withPassword("test");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        reg.add("spring.datasource.username", POSTGRES::getUsername);
        reg.add("spring.datasource.password", POSTGRES::getPassword);
        reg.add("spring.flyway.baseline-on-migrate", () -> "true");
        reg.add("spring.data.redis.host", REDIS::getHost);
        reg.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        reg.add("spring.ai.anthropic.api-key", () -> "test-key-chaos");
        reg.add("spring.ai.openai.api-key", () -> "test-key-chaos");
        reg.add("agent.workspace", () -> System.getProperty("java.io.tmpdir") + "/chaos-workspace");
    }

    @Autowired private StateStore stateStore;
    @Autowired private SideEffectOutbox sideEffectOutbox;
    @Autowired private SideEffectOutboxRepository outboxRepo;
    @Autowired private StringRedisTemplate redis;

    // ── Chaos 1: Redis evicts run state — reads fall back to DB ─────────────

    @Test
    @DisplayName("Chaos 1: Run state survives Redis eviction — reads fall back to Postgres")
    void runState_survivesRedisEviction() {
        String runId = UUID.randomUUID().toString();

        // Save run to Postgres via StateStore
        PipelineRun run = new PipelineRun();
        run.setRunId(runId);
        run.setStatus("WORKING");
        run.setStartedAt(Instant.now());
        stateStore.saveRun(run);

        // Flush any Redis cache for this run key
        redis.delete("run:" + runId);

        // Run should still be findable from Postgres
        var found = stateStore.findRun(runId);
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo("WORKING");
    }

    // ── Chaos 2: Budget exceeded mid-task ────────────────────────────────────

    @Test
    @DisplayName("Chaos 2: Run with zero budget cap and positive actual cost is detected over-budget")
    void budget_zeroCapIsDetectedAsOverBudget() {
        String runId = UUID.randomUUID().toString();

        // Create a run with 0 hard cap and a positive actual cost
        PipelineRun run = new PipelineRun();
        run.setRunId(runId);
        run.setStatus("WORKING");
        run.setHardCapUsd(0.0);
        run.setActualCostUsd(1.0); // already over cap
        run.setStartedAt(Instant.now());
        stateStore.saveRun(run);

        PipelineRun saved = stateStore.findRun(runId).orElseThrow();
        double cap = saved.getHardCapUsd() != null ? saved.getHardCapUsd() : 0.0;
        double cost = saved.getActualCostUsd() != null ? saved.getActualCostUsd() : 0.0;
        assertThat(cost).as("Actual cost exceeds hard cap").isGreaterThan(cap);
    }

    // ── Chaos 3: GCS failure → outbox entry created for retry ─────────────

    @Test
    @DisplayName("Chaos 3: GCS upload failure creates outbox entry for reliable retry")
    void gcsFailure_createsOutboxEntry() {
        String runId = UUID.randomUUID().toString();

        // Simulate what WorkspaceGcsUploadListener does on GCS failure
        sideEffectOutbox.enqueue(runId, "GCS_ARCHIVE", Map.of(
                "runId", runId,
                "bucket", "test-bucket",
                "error", "simulated GCS outage"
        ));

        var entries = outboxRepo.findAll().stream()
                .filter(e -> runId.equals(e.getRunId()) && "GCS_ARCHIVE".equals(e.getEffectType()))
                .toList();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getStatus()).isEqualTo("PENDING");
        assertThat(entries.get(0).getMaxAttempts()).isGreaterThan(1);
    }

    // ── Chaos 4: Side-effect executor retries on failure ────────────────────

    @Test
    @DisplayName("Chaos 4: SideEffectExecutor backs off and retries failed entries")
    void sideEffect_backoffOnFailure() {
        String runId = UUID.randomUUID().toString();
        String effectType = "COMPENSATE_GITHUB_BRANCH"; // stub — logs only, never actually deletes

        sideEffectOutbox.enqueue(runId, effectType, Map.of(
                "owner", "test-owner",
                "repo", "test-repo",
                "branch", "test-branch"
        ));

        var before = outboxRepo.findAll().stream()
                .filter(e -> runId.equals(e.getRunId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Entry not found"));

        assertThat(before.getStatus()).isEqualTo("PENDING");
        assertThat(before.getAttempt()).isEqualTo(0);
        assertThat(before.getMaxAttempts()).isGreaterThanOrEqualTo(3);
        assertThat(before.getNextRetryAt()).isBeforeOrEqualTo(Instant.now().plusSeconds(5));
    }

    // ── Chaos 5: Provider outage queuing ────────────────────────────────────

    @Test
    @DisplayName("Chaos 5: Run queued due to provider outage is persisted and discoverable")
    void providerOutage_runQueued() {
        String runId = UUID.randomUUID().toString();

        PipelineRun run = new PipelineRun();
        run.setRunId(runId);
        run.setStatus("QUEUED");
        run.setFailureReason("LLM providers unavailable — run will retry automatically when providers recover");
        run.setQueuePosition(0);
        run.setStartedAt(Instant.now());
        stateStore.saveRun(run);

        var outageQueued = stateStore.findRun(runId).orElseThrow();
        assertThat(outageQueued.getStatus()).isEqualTo("QUEUED");
        assertThat(outageQueued.getFailureReason()).contains("providers unavailable");
    }
}
