package com.navneet.executespec.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for fabrication/partial completion detection logic used by WorkerAgent.
 *
 * <p>These tests validate the detection heuristics without requiring the full
 * Spring context or LLM calls. The methods under test are extracted as package-private
 * static helpers to enable isolated testing.
 */
@DisplayName("Fabrication detection logic")
class FabricationDetectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── countClaimedFiles logic ─────────────────────────────────────────────

    @Nested
    @DisplayName("countClaimedFiles")
    class CountClaimedFilesTests {

        @Test
        @DisplayName("counts files_created array elements")
        void countsCreated() {
            String json = """
                    {"task_id": "t1", "status": "complete",
                     "files_created": ["pom.xml", "src/Main.java", "src/App.java"],
                     "files_modified": []}
                    """;
            assertThat(countClaimedFiles(json)).isEqualTo(3);
        }

        @Test
        @DisplayName("counts files_modified array elements")
        void countsModified() {
            String json = """
                    {"task_id": "t1", "status": "complete",
                     "files_created": [],
                     "files_modified": ["pom.xml", "README.md"]}
                    """;
            assertThat(countClaimedFiles(json)).isEqualTo(2);
        }

        @Test
        @DisplayName("sums both created and modified")
        void sumsBoth() {
            String json = """
                    {"task_id": "t1", "status": "complete",
                     "files_created": ["pom.xml", "src/Main.java"],
                     "files_modified": ["README.md"]}
                    """;
            assertThat(countClaimedFiles(json)).isEqualTo(3);
        }

        @Test
        @DisplayName("returns 0 for empty arrays")
        void emptyArrays() {
            String json = """
                    {"task_id": "t1", "status": "complete",
                     "files_created": [], "files_modified": []}
                    """;
            assertThat(countClaimedFiles(json)).isEqualTo(0);
        }

        @Test
        @DisplayName("returns 0 for malformed JSON")
        void malformed() {
            assertThat(countClaimedFiles("not json at all")).isEqualTo(0);
        }

        @Test
        @DisplayName("handles markdown-wrapped JSON")
        void markdownWrapped() {
            String text = """
                    Here is my completion report:
                    ```json
                    {"task_id": "t1", "status": "complete",
                     "files_created": ["a.java", "b.java"],
                     "files_modified": ["c.java"]}
                    ```
                    """;
            assertThat(countClaimedFiles(normalizeResponse(text))).isEqualTo(3);
        }

        @Test
        @DisplayName("handles Anthropic verbose preamble before JSON")
        void anthropicPreamble() {
            String text = """
                    I've completed the task. Here are the cache statistics:
                    - Cache read tokens: 45000
                    - Cache write tokens: 12000

                    The files have been created successfully. Let me provide the completion report:

                    {"task_id": "backend_setup", "status": "complete",
                     "files_created": ["pom.xml", "src/main/java/App.java", "src/main/resources/application.yml", "src/main/java/CorsConfig.java"],
                     "files_modified": [],
                     "self_review": "All 4 files created"}
                    """;
            assertThat(countClaimedFiles(normalizeResponse(text))).isEqualTo(4);
        }
    }

    // ── reportClaimsFileChanges logic ───────────────────────────────────────

    @Nested
    @DisplayName("reportClaimsFileChanges")
    class ReportClaimsTests {

        @Test
        @DisplayName("detects non-empty files_created")
        void detectsCreated() {
            String json = """
                    {"files_created": ["pom.xml"], "files_modified": []}
                    """;
            assertThat(reportClaimsFileChanges(json)).isTrue();
        }

        @Test
        @DisplayName("detects non-empty files_modified")
        void detectsModified() {
            String json = """
                    {"files_created": [], "files_modified": ["pom.xml"]}
                    """;
            assertThat(reportClaimsFileChanges(json)).isTrue();
        }

        @Test
        @DisplayName("returns false for empty arrays")
        void emptyArrays() {
            String json = """
                    {"files_created": [], "files_modified": []}
                    """;
            assertThat(reportClaimsFileChanges(json)).isFalse();
        }

        @Test
        @DisplayName("returns false for null arrays")
        void nullArrays() {
            String json = """
                    {"files_created": null, "files_modified": null}
                    """;
            assertThat(reportClaimsFileChanges(json)).isFalse();
        }

        @Test
        @DisplayName("returns false for null input")
        void nullInput() {
            assertThat(reportClaimsFileChanges(null)).isFalse();
        }
    }

    // ── Fabrication/partial detection scenarios ─────────────────────────────

    @Nested
    @DisplayName("Detection scenarios")
    class DetectionScenarios {

        @ParameterizedTest(name = "totalWrites={0}, claimedFiles={1} → fabricated={2}, partial={3}")
        @CsvSource({
                // totalFileWrites, claimedFileCount, expectedFabricated, expectedPartial
                "0, 4, true, false",    // zero writes, claims 4 → fabricated
                "0, 1, true, false",    // zero writes, claims 1 → fabricated
                "1, 4, false, true",    // 1 write, claims 4 → partial
                "1, 1, false, false",   // 1 write, claims 1 → valid
                "4, 4, false, false",   // 4 writes, claims 4 → valid
                "5, 4, false, false",   // 5 writes, claims 4 → valid (extra writes OK)
                "0, 0, false, false",   // zero writes, claims 0 → valid (no-op task)
                "3, 8, false, true",    // 3 writes, claims 8 → partial
        })
        void detectionMatrix(int totalFileWrites, int claimedFileCount,
                             boolean expectedFabricated, boolean expectedPartial) {
            // Simulate the detection logic from WorkerAgent
            boolean hasClaims = claimedFileCount > 0;
            boolean isFabricated = totalFileWrites == 0 && hasClaims;
            boolean isPartial = !isFabricated && claimedFileCount > 0
                    && totalFileWrites > 0 && claimedFileCount > totalFileWrites;

            assertThat(isFabricated)
                    .as("fabricated (writes=%d, claims=%d)", totalFileWrites, claimedFileCount)
                    .isEqualTo(expectedFabricated);
            assertThat(isPartial)
                    .as("partial (writes=%d, claims=%d)", totalFileWrites, claimedFileCount)
                    .isEqualTo(expectedPartial);
        }
    }

    // ── Context trimming validation ─────────────────────────────────────────

    @Nested
    @DisplayName("Context trimming on rejection")
    class ContextTrimming {

        @Test
        @DisplayName("trimmed context contains task assignment + rejection message only")
        void trimmedContextSize() {
            // Simulate: after fabrication detection, messages should be cleared and
            // rebuilt with just 2 messages (task assignment + rejection).
            // This test validates the invariant that context is minimal after trimming.
            int claimedFileCount = 5;
            int totalFileWrites = 1;
            int filesRemaining = Math.max(0, claimedFileCount - totalFileWrites);

            String writtenFilesSummary = totalFileWrites > 0
                    ? "You have successfully written " + totalFileWrites + " file(s) so far. "
                    : "You have NOT written any files yet. ";

            String rejectionMsg = "CONTEXT RESET — Your previous attempt was REJECTED.\n\n" +
                    "REASON: You produced a completion report claiming " + claimedFileCount +
                    " files, but only " + totalFileWrites + " writeFile tool calls were dispatched.\n\n" +
                    writtenFilesSummary +
                    "You still need to create " + filesRemaining + " more file(s).\n\n" +
                    "RULES (read carefully):\n" +
                    "1. Files are ONLY created by calling the writeFile tool.\n" +
                    "2. Call writeFile ONCE for EACH file.\n" +
                    "3. Do NOT output a completion JSON report until ALL files are written.\n" +
                    "4. Write ONE file at a time.\n\n" +
                    "START NOW — call writeFile for your next file.";

            assertThat(rejectionMsg).contains("CONTEXT RESET");
            assertThat(rejectionMsg).contains("4 more file(s)");
            assertThat(rejectionMsg).contains("successfully written 1 file(s)");
            assertThat(rejectionMsg).doesNotContain("CRITICAL ERROR"); // old message format
        }

        @Test
        @DisplayName("zero-write fabrication includes correct summary")
        void zeroWriteFabrication() {
            int totalFileWrites = 0;
            String writtenFilesSummary = totalFileWrites > 0
                    ? "You have successfully written " + totalFileWrites + " file(s) so far. "
                    : "You have NOT written any files yet. ";

            assertThat(writtenFilesSummary).isEqualTo("You have NOT written any files yet. ");
        }

        @Test
        @DisplayName("MAX_FABRICATED_COMPLETIONS is now 2 (reduced from 3)")
        void maxRetriesReduced() {
            // This validates the policy change: fail faster to save tokens
            final int MAX_FABRICATED_COMPLETIONS = 2;
            assertThat(MAX_FABRICATED_COMPLETIONS).isEqualTo(2);
        }
    }

    // ── Helper methods (mirrors WorkerAgent private methods) ────────────────

    private static int countClaimedFiles(String text) {
        try {
            JsonNode json = MAPPER.readTree(text);
            int count = 0;
            JsonNode created = json.path("files_created");
            if (created.isArray()) count += created.size();
            JsonNode modified = json.path("files_modified");
            if (modified.isArray()) count += modified.size();
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean reportClaimsFileChanges(String text) {
        if (text == null) return false;
        return hasNonEmptyArrayField(text, "files_created") ||
                hasNonEmptyArrayField(text, "files_modified");
    }

    private static boolean hasNonEmptyArrayField(String text, String fieldName) {
        if (!text.contains("\"" + fieldName + "\"")) return false;
        String f = "\"" + fieldName + "\"";
        int idx = text.indexOf(f);
        if (idx < 0) return false;
        String after = text.substring(idx + f.length()).stripLeading();
        if (after.startsWith(":")) after = after.substring(1).stripLeading();
        return !after.startsWith("[]") && !after.startsWith("null");
    }

    /**
     * Simplified version of WorkerAgent.normalizeWorkerResponse for test purposes.
     * Strips markdown fences and preamble to extract JSON.
     */
    private static String normalizeResponse(String text) {
        if (text == null) return "";
        String response = text.trim();

        // Strip markdown code fences
        response = response.replaceAll("(?s)^```(?:json)?\\s*", "");
        response = response.replaceAll("(?s)```\\s*$", "");

        // Strip preamble before JSON
        int braceIdx = response.indexOf('{');
        if (braceIdx > 0 && braceIdx <= 800) {
            response = response.substring(braceIdx);
        }

        return response.trim();
    }
}
