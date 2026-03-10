package com.navneet.executespec.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.model.WorkerReport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for worker LLM output fixtures.
 */
@DisplayName("WorkerReport output contract")
class WorkerReportContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static WorkerReport completeReport;
    private static WorkerReport blockedReport;

    @BeforeAll
    static void loadFixtures() throws IOException {
        completeReport = load("fixtures/llm/worker-complete.json");
        blockedReport  = load("fixtures/llm/worker-blocked.json");
    }

    @Test
    @DisplayName("complete report parses with status 'complete'")
    void completeStatus() {
        assertThat(completeReport.getStatus()).isEqualTo("complete");
        assertThat(completeReport.getTaskId()).isEqualTo("task-auth-model");
    }

    @Test
    @DisplayName("blocked report parses with non-null blockers")
    void blockedStatus() {
        assertThat(blockedReport.getStatus()).isEqualTo("blocked");
        assertThat(blockedReport.getBlockers()).isNotBlank();
    }

    @Test
    @DisplayName("status is one of complete/partial/blocked")
    void statusValues() {
        assertThat(completeReport.getStatus()).isIn("complete", "partial", "blocked");
        assertThat(blockedReport.getStatus()).isIn("complete", "partial", "blocked");
    }

    @Test
    @DisplayName("complete report lists files created and modified")
    void filesListed() {
        assertThat(completeReport.getFilesCreated()).isNotEmpty();
        assertThat(completeReport.getFilesModified()).isNotEmpty();
    }

    @Test
    @DisplayName("blocked report has empty file lists")
    void blockedHasNoFiles() {
        assertThat(blockedReport.getFilesCreated()).isEmpty();
        assertThat(blockedReport.getFilesModified()).isEmpty();
    }

    @Test
    @DisplayName("token counts are non-negative")
    void tokenCounts() {
        assertThat(completeReport.getInputTokens()).isGreaterThan(0);
        assertThat(completeReport.getOutputTokens()).isGreaterThan(0);
        assertThat(blockedReport.getInputTokens()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("unknown extra fields are ignored (JsonIgnoreProperties)")
    void extraFieldsIgnored() throws IOException {
        String json = """
            {
              "task_id": "task-x",
              "status": "complete",
              "files_created": ["Foo.java"],
              "files_modified": [],
              "files_read": [],
              "input_tokens": 100,
              "output_tokens": 50,
              "unknown_future_field": "should be ignored"
            }
            """;
        WorkerReport report = MAPPER.readValue(json, WorkerReport.class);
        assertThat(report.getTaskId()).isEqualTo("task-x");
        assertThat(report.getStatus()).isEqualTo("complete");
    }

    @Test
    @DisplayName("partial factory produces correct status and tokens")
    void partialFactory() {
        WorkerReport partial = WorkerReport.partial("task-y", "token limit", 5000, 1000);
        assertThat(partial.getStatus()).isEqualTo("partial");
        assertThat(partial.getInputTokens()).isEqualTo(5000);
        assertThat(partial.getSelfReview()).isEqualTo("token limit");
    }

    @Test
    @DisplayName("blocked factory produces correct status and blockers")
    void blockedFactory() {
        WorkerReport blocked = WorkerReport.blocked("task-z", "dep not ready", 100, 0);
        assertThat(blocked.getStatus()).isEqualTo("blocked");
        assertThat(blocked.getBlockers()).isEqualTo("dep not ready");
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private static WorkerReport load(String resourcePath) throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream(resourcePath)) {
            assertThat(in).as("fixture not found: " + resourcePath).isNotNull();
            return MAPPER.readValue(in, WorkerReport.class);
        }
    }
}
