package com.navneet.executespec.reviewer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmOutputParser;
import com.navneet.executespec.agent.LlmResponseCleaner;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.reviewer.model.AccuracyReport;
import com.navneet.executespec.reviewer.model.ReviewIssue;
import com.navneet.executespec.reviewer.model.IssueSeverity;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.tool.FileSystemTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Phase 3 of the review pipeline: Accuracy Assessment.
 *
 * <p>After code inspection (Phase 1) and auto-fix (Phase 2), this service makes a focused
 * LLM call that scores the generated code against the original user specification.
 *
 * <p>Accuracy dimensions measured:
 * <ul>
 *   <li><b>Spec adherence (0-100)</b> — what % of explicitly stated requirements were implemented</li>
 *   <li><b>Endpoint coverage</b> — for API specs, which HTTP endpoints are present vs missing</li>
 *   <li><b>Schema accuracy</b> — do request/response payloads match the spec (HIGH/MEDIUM/LOW)</li>
 *   <li><b>Test coverage</b> — NONE/PARTIAL/GOOD/COMPREHENSIVE relative to what spec implied</li>
 *   <li><b>Non-functional coverage</b> — auth, pagination, rate limiting, error handling</li>
 * </ul>
 *
 * <p>The assessment uses PLANNING tier (same as the reviewer) and runs with a short timeout —
 * it should complete in under 60 seconds. On any failure it returns a default conservative report.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccuracyAssessmentService {

    private final LlmRouter llmRouter;
    private final ModelResolver modelResolver;
    private final FileSystemTool fileSystemTool;
    private final StateStore stateStore;
    private final ObjectMapper mapper;
    private final LlmOutputParser outputParser;

    private static final String SYSTEM_PROMPT = """
            You are a specification adherence auditor. Your ONLY job is to compare the ORIGINAL USER SPECIFICATION
            against the FILE TREE, KEY SOURCE FILES, and REVIEW ISSUES to determine how accurately the generated
            code implements what was asked for.

            You are NOT re-reviewing the code quality — that was done already. You are measuring COMPLETENESS
            and ACCURACY of the implementation relative to the specification.

            OUTPUT FORMAT (strict JSON, no markdown fences):
            {
              "specAdherenceScore": <0-100 integer>,
              "overallAccuracyGrade": "<A|B|C|D|F>",
              "gradingNotes": "<1-3 sentences explaining the grade>",
              "implementedRequirements": ["<requirement text>", ...],
              "missingRequirements": ["<requirement text>", ...],
              "partialRequirements": ["<requirement text that exists but is incomplete>", ...],
              "endpointCoverage": {
                "required": ["<METHOD /path>", ...],
                "implemented": ["<METHOD /path>", ...],
                "missing": ["<METHOD /path>", ...],
                "extra": ["<METHOD /path>", ...],
                "coveragePercent": <0-100>
              },
              "schemaAccuracy": "<HIGH|MEDIUM|LOW>",
              "testCoverageLevel": "<NONE|PARTIAL|GOOD|COMPREHENSIVE>",
              "nonFunctionalCoverage": "<COMPLETE|PARTIAL|MISSING>"
            }

            GRADING SCALE:
            A = 90-100: All or nearly all requirements implemented correctly
            B = 80-89: Most requirements implemented, minor gaps
            C = 70-79: Core requirements met, some features missing
            D = 60-69: Basic skeleton present, significant features absent
            F = 0-59: Major portions of the spec are not implemented

            RULES:
            - Base your score on the SPECIFICATION, not on what is "good enough"
            - A requirement is IMPLEMENTED if it appears in KEY SOURCE FILES content OR the file tree shows a file
              that clearly implements it — even if the file is named differently (e.g., all routes in src/index.js)
            - A requirement is MISSING only if you cannot find any evidence of it in the source files
            - A requirement is PARTIAL if an issue flags it as incomplete (e.g., endpoint exists but validation missing)
            - For endpointCoverage: extract ALL endpoints from the spec (e.g., "POST /api/users"), then check
              KEY SOURCE FILES content for the route handler — not just file names
            - README and documentation gaps count at most 5 points total toward specAdherenceScore
            - The specAdherenceScore must primarily reflect FUNCTIONAL compliance (endpoints, features, business logic)
            - If spec has no explicit endpoints (non-API project), set endpointCoverage to null
            - schemaAccuracy = HIGH if spec defines schemas and code matches them; MEDIUM if minor deviations; LOW if major mismatches
            - testCoverageLevel = based on what the spec REQUIRED (if spec says "add unit tests", assess against that)
            - Return valid JSON. Do not wrap in markdown fences.
            """;

    /**
     * Run the accuracy assessment for a completed run.
     *
     * @param runId         run identifier (for token tracking and workspace access)
     * @param provider      LLM provider name (e.g., "ANTHROPIC")
     * @param model         resolved model for PLANNING tier
     * @param originalGoal  the user's original specification text
     * @param issues        review issues found in Phase 1 (already with fix results)
     * @return accuracy report, or a safe default on failure
     */
    public AccuracyReport assess(String runId, String provider, ResolvedModel model,
                                  String originalGoal, List<ReviewIssue> issues) {
        log.info("[AccuracyAssessment] Starting assessment for run {} using {}", runId, model.getModel());

        try {
            String fileTree = getFileTree(runId);
            String keyFileSnippets = buildKeyFileSnippets(runId, fileTree);
            String issuesSummary = buildIssuesSummary(issues);
            String userMessage = buildAssessmentPrompt(originalGoal, fileTree, keyFileSnippets, issuesSummary);

            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(SYSTEM_PROMPT));
            messages.add(new UserMessage(userMessage));

            AnthropicChatOptions opts = "ANTHROPIC".equalsIgnoreCase(provider)
                    ? AnthropicChatOptions.builder()
                            .temperature(0.0)
                            .cacheOptions(AnthropicCacheOptions.builder()
                                    .strategy(AnthropicCacheStrategy.SYSTEM_ONLY)
                                    .build())
                            .build()
                    : null;
            Prompt prompt = opts != null ? new Prompt(messages, opts) : new Prompt(messages);

            LlmRouter.LlmResponse lr = llmRouter.callWithFallbackTracked(
                    provider, model.getModel(), prompt,
                    runId, "accuracy-assessment", LlmRouter.FallbackMode.IMMEDIATE, 0, false);

            ChatResponse response = lr.response();

            // Track tokens
            try {
                int inTok = response.getMetadata().getUsage().getPromptTokens().intValue();
                int outTok = response.getMetadata().getUsage().getCompletionTokens().intValue();
                stateStore.addTokensToRun(runId, inTok, outTok);
            } catch (Exception ex) {
                log.warn("[AccuracyAssessment] Could not extract token counts: {}", ex.getMessage());
            }

            String text = response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) {
                log.warn("[AccuracyAssessment] Empty response from LLM — using default");
                return defaultReport(issues);
            }

            // Try to parse the accuracy report
            String cleaned = LlmResponseCleaner.stripMarkdownFences(text).trim();
            if (!cleaned.startsWith("{")) {
                // Try to extract JSON object
                int start = cleaned.indexOf('{');
                int end = cleaned.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    cleaned = cleaned.substring(start, end + 1);
                }
            }

            // First attempt: try parsing directly
            AccuracyReport report = null;
            try {
                report = mapper.readValue(cleaned, AccuracyReport.class);
            } catch (Exception e1) {
                log.warn("[AccuracyAssessment] First parse failed, attempting retry: {}", e1.getMessage());
                // Retry: ask model to fix its JSON
                messages.add(new AssistantMessage(text));
                messages.add(new UserMessage(
                        "Your response was not valid JSON. Return ONLY a valid JSON object matching the required format. No markdown, no explanation."));
                try {
                    LlmRouter.LlmResponse retryLr = llmRouter.callWithFallbackTracked(
                            provider, model.getModel(),
                            opts != null ? new Prompt(messages, opts) : new Prompt(messages),
                            runId, "accuracy-assessment-retry", LlmRouter.FallbackMode.IMMEDIATE, 1, false);
                    String retryText = retryLr.response().getResult().getOutput().getText();
                    String retryCleaned = LlmResponseCleaner.stripMarkdownFences(retryText).trim();
                    report = mapper.readValue(retryCleaned, AccuracyReport.class);
                } catch (Exception e2) {
                    log.warn("[AccuracyAssessment] Retry parse also failed: {}", e2.getMessage());
                    return defaultReport(issues);
                }
            }

            // Clamp score to valid range
            if (report.getSpecAdherenceScore() < 0 || report.getSpecAdherenceScore() > 100) {
                report.setSpecAdherenceScore(Math.max(0, Math.min(100, report.getSpecAdherenceScore())));
            }

            // Ensure grade is consistent with score
            if (report.getOverallAccuracyGrade() == null || report.getOverallAccuracyGrade().isBlank()) {
                report.setOverallAccuracyGrade(gradeFromScore(report.getSpecAdherenceScore()));
            }

            log.info("[AccuracyAssessment] Complete for run {}: {} ({}/100) — {} missing, {} partial",
                    runId, report.getOverallAccuracyGrade(), report.getSpecAdherenceScore(),
                    report.getMissingRequirements() != null ? report.getMissingRequirements().size() : 0,
                    report.getPartialRequirements() != null ? report.getPartialRequirements().size() : 0);

            return report;

        } catch (Exception e) {
            log.error("[AccuracyAssessment] Unexpected failure for run {}: {}", runId, e.getMessage(), e);
            return defaultReport(issues);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Prompt builder
    // ──────────────────────────────────────────────────────────────────────────

    private String buildAssessmentPrompt(String originalGoal, String fileTree,
                                          String keyFileSnippets, String issuesSummary) {
        String filesSection = keyFileSnippets.isBlank()
                ? "(no key source files available — rely on file tree)"
                : keyFileSnippets;
        return String.format("""
                === ORIGINAL SPECIFICATION (this is what the user asked for) ===
                %s

                === GENERATED FILE TREE (files that were created) ===
                %s

                === KEY SOURCE FILES (actual code — use this to verify endpoint/feature existence) ===
                %s

                === REVIEW ISSUES FOUND (critical and minor issues identified in code review) ===
                %s

                === TASK ===
                Based on the specification, file tree, source files, and review issues above:
                1. Extract every explicit requirement from the specification
                2. Check KEY SOURCE FILES content to verify each requirement is actually implemented
                3. Mark as IMPLEMENTED only if you find the implementation in source files or file tree
                4. Mark as MISSING only if you find NO evidence in any source file
                5. Mark as PARTIAL if a review issue flags it incomplete, or if code exists but is clearly incomplete
                6. For API specs: check KEY SOURCE FILES for route handlers matching each required endpoint
                7. Assign a specAdherenceScore (0-100) — README/doc gaps max 5 pts penalty

                Return your assessment as a JSON object. No markdown fences. No explanation outside the JSON.
                """, originalGoal, fileTree, filesSection, issuesSummary);
    }

    private String buildIssuesSummary(List<ReviewIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return "No issues found during code review — code passed review.";
        }

        StringBuilder sb = new StringBuilder();
        long critical = issues.stream().filter(i -> i.getSeverity() == IssueSeverity.CRITICAL).count();
        long minor = issues.stream().filter(i -> i.getSeverity() == IssueSeverity.MINOR).count();
        sb.append(String.format("Total: %d critical, %d minor issues\n\n", critical, minor));

        issues.stream()
                .filter(i -> i.getSeverity() == IssueSeverity.CRITICAL)
                .limit(10)
                .forEach(i -> sb.append(String.format("[CRITICAL/%s] %s — %s (fixed=%s)\n",
                        i.getCategory(), i.getId(), i.getDescription(),
                        i.isAutoFixed() && i.isFixSucceeded() ? "YES" : "NO")));

        issues.stream()
                .filter(i -> i.getSeverity() == IssueSeverity.MINOR)
                .limit(5)
                .forEach(i -> sb.append(String.format("[MINOR/%s] %s — %s\n",
                        i.getCategory(), i.getId(), i.getDescription())));

        return sb.toString().trim();
    }

    private String getFileTree(String runId) {
        try {
            return fileSystemTool.listFiles(runId, ".");
        } catch (Exception e) {
            log.warn("[AccuracyAssessment] Could not get file tree for run {}: {}", runId, e.getMessage());
            return "(file tree unavailable)";
        }
    }

    /**
     * Reads key source files (routes, controllers, models, entry points) and returns
     * their content as annotated snippets for the accuracy scorer.
     * Capped at 6 files × 2,500 chars each to control token cost (~15K extra tokens max).
     */
    private String buildKeyFileSnippets(String runId, String fileTree) {
        List<String> candidates = inferKeyFilePaths(fileTree);
        if (candidates.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        int filesRead = 0;
        for (String path : candidates) {
            if (filesRead >= 6) break;
            try {
                String content = fileSystemTool.readFile(runId, path);
                if (content == null || content.isBlank()) continue;
                String snip = content.length() > 2_500
                        ? content.substring(0, 2_500) + "\n...(truncated, " + content.length() + " chars total)"
                        : content;
                sb.append("### ").append(path).append("\n```\n").append(snip).append("\n```\n\n");
                filesRead++;
            } catch (Exception e) {
                log.debug("[AccuracyAssessment] Could not read key file {} for run {}: {}", path, runId, e.getMessage());
            }
        }
        return sb.toString();
    }

    /**
     * Heuristically identifies key structural files from the file tree listing.
     * Targets route handlers, controllers, models, and entry points — the files most
     * likely to contain endpoint implementations the spec auditor needs to verify.
     * Excludes test files, compiled output, and node_modules.
     */
    private List<String> inferKeyFilePaths(String fileTree) {
        if (fileTree == null || fileTree.isBlank()) return List.of();
        return fileTree.lines()
                .map(String::trim)
                // Strip leading tree characters (├──, └──, │, spaces)
                .map(l -> l.replaceAll("^[│├└\\s─]+", "").trim())
                // Keep only files (contain a dot extension) that match structural patterns
                .filter(l -> l.matches(".*\\.(java|js|ts|py|go|rb|php|kt|cs)$"))
                .filter(l -> l.matches("(?i).*(route|router|controller|handler|resolver|service|" +
                        "model|schema|index|main|app|server|api).*"))
                // Exclude test files, vendor, compiled output
                .filter(l -> !l.matches("(?i).*(test|spec|__pycache__|node_modules|target|build|dist|\\.min\\.).*"))
                // Prefer shorter paths (closer to root = more likely to be entry points)
                .sorted(java.util.Comparator.comparingInt(l -> l.length()))
                .limit(10)
                .toList();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Defaults and helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Conservative default when LLM call fails.
     * Derives a rough score from review issue counts.
     */
    AccuracyReport defaultReport(List<ReviewIssue> issues) {
        long unfixedCritical = issues == null ? 0 : issues.stream()
                .filter(i -> i.getSeverity() == IssueSeverity.CRITICAL)
                .filter(i -> !i.isAutoFixed() || !i.isFixSucceeded())
                .count();

        // Rough heuristic: each unfixed critical issue = -15 points
        int score = Math.max(0, 75 - (int) (unfixedCritical * 15));
        String grade = gradeFromScore(score);

        return AccuracyReport.builder()
                .specAdherenceScore(score)
                .overallAccuracyGrade(grade)
                .gradingNotes("Accuracy assessment unavailable — estimated from review issue count.")
                .implementedRequirements(List.of())
                .missingRequirements(List.of())
                .partialRequirements(List.of())
                .schemaAccuracy("MEDIUM")
                .testCoverageLevel("PARTIAL")
                .nonFunctionalCoverage("PARTIAL")
                .build();
    }

    private String gradeFromScore(int score) {
        if (score >= 90) return "A";
        if (score >= 80) return "B";
        if (score >= 70) return "C";
        if (score >= 60) return "D";
        return "F";
    }
}
