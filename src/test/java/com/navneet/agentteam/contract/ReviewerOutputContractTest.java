package com.navneet.agentteam.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.agentteam.agent.model.ReviewReport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for reviewer LLM output fixtures.
 */
@DisplayName("Reviewer output contract")
class ReviewerOutputContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ReviewReport mergeReport;
    private static ReviewReport reworkReport;

    @BeforeAll
    static void loadFixtures() throws IOException {
        mergeReport  = load("fixtures/llm/reviewer-merge.json");
        reworkReport = load("fixtures/llm/reviewer-rework.json");
    }

    @Test
    @DisplayName("MERGE recommendation parsed correctly")
    void mergeRecommendation() {
        assertThat(mergeReport.getRecommendation()).isEqualTo("MERGE");
        assertThat(mergeReport.getMergeBlockers()).isEmpty();
        assertThat(mergeReport.isReadmeUpdated()).isTrue();
    }

    @Test
    @DisplayName("REWORK recommendation parsed correctly with blockers")
    void reworkRecommendation() {
        assertThat(reworkReport.getRecommendation()).isEqualTo("REWORK");
        assertThat(reworkReport.getMergeBlockers()).isNotEmpty();
        assertThat(reworkReport.isReadmeUpdated()).isFalse();
    }

    @Test
    @DisplayName("overall_confidence is one of HIGH/MEDIUM/LOW")
    void confidenceValues() {
        assertThat(mergeReport.getOverallConfidence()).isIn("HIGH", "MEDIUM", "LOW");
        assertThat(reworkReport.getOverallConfidence()).isIn("HIGH", "MEDIUM", "LOW");
    }

    @Test
    @DisplayName("recommendation is one of MERGE/REWORK/PARTIAL_MERGE")
    void recommendationValues() {
        assertThat(mergeReport.getRecommendation()).isIn("MERGE", "REWORK", "PARTIAL_MERGE");
        assertThat(reworkReport.getRecommendation()).isIn("MERGE", "REWORK", "PARTIAL_MERGE");
    }

    @Test
    @DisplayName("summary is present and non-blank")
    void summaryPresent() {
        assertThat(mergeReport.getSummary()).isNotBlank();
        assertThat(reworkReport.getSummary()).isNotBlank();
    }

    @Test
    @DisplayName("task_reviews list is present")
    void taskReviewsPresent() {
        assertThat(mergeReport.getTaskReviews()).isNotNull();
        assertThat(reworkReport.getTaskReviews()).isNotNull();
    }

    @Test
    @DisplayName("REWORK report has HIGH severity issues")
    void reworkHasHighSeverityIssues() {
        boolean hasHigh = reworkReport.getTaskReviews().stream()
            .flatMap(r -> r.getIssues() != null ? r.getIssues().stream() : java.util.stream.Stream.empty())
            .anyMatch(i -> "HIGH".equals(i.getSeverity()));
        assertThat(hasHigh).isTrue();
    }

    @Test
    @DisplayName("readme_updated defaults to false when not specified (fail-safe)")
    void readmeDefaultsFalse() throws IOException {
        // Parse a report without readme_updated field
        String json = """
            {
              "overall_confidence": "MEDIUM",
              "recommendation": "REWORK",
              "summary": "Needs work",
              "task_reviews": [],
              "merge_blockers": ["something wrong"],
              "post_merge_suggestions": []
            }
            """;
        ReviewReport report = MAPPER.readValue(json, ReviewReport.class);
        assertThat(report.isReadmeUpdated()).isFalse();
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private static ReviewReport load(String resourcePath) throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream(resourcePath)) {
            assertThat(in).as("fixture not found: " + resourcePath).isNotNull();
            return MAPPER.readValue(in, ReviewReport.class);
        }
    }
}
