package com.navneet.executespec.reviewer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmOutputParser;
import com.navneet.executespec.agent.LlmResponseCleaner;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.compiler.CompileResult;
import com.navneet.executespec.agent.LlmRouter.FallbackMode;
import com.navneet.executespec.agent.WorkerAgent;
import com.navneet.executespec.agent.model.TaskDefinition;
import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.reviewer.model.*;
import com.navneet.executespec.reviewer.AccuracyAssessmentService;
import com.navneet.executespec.router.*;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.tool.FileSystemTool;
import com.navneet.executespec.tool.GitTool;
import com.navneet.executespec.validator.CodeValidator;
import com.navneet.executespec.validator.model.*;
import com.navneet.executespec.validator.prompt.RetryPromptBuilder;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Agentic code reviewer with two phases:
 *
 * <p><b>Phase 1 (Inspect):</b> Reads spec + file tree + selected files via tool-use loop.
 * Produces a list of ReviewIssues with CRITICAL and MINOR classifications.
 *
 * <p><b>Phase 2 (Fix Loop):</b> For each CRITICAL issue (max 5), sends a fix instruction
 * to WorkerAgent, then validates the fix.
 *
 * <p>Model: Always CAPABLE tier (one tier above typical workers).
 *
 * <p>Guardrails:
 * <ul>
 *   <li>Max 10 read_file/list_files calls during inspection</li>
 *   <li>Max 5 critical issues trigger auto-fix</li>
 *   <li>Max 1 fix attempt per critical issue</li>
 *   <li>Total phase timeout: 3 minutes</li>
 *   <li>README check is a hard gate (unchanged from v1)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewerAgentV2 {

    private final LlmRouter llmRouter;
    private final ModelResolver modelResolver;
    private final FileSystemTool fileSystemTool;
    private final WorkerAgent workerAgent;
    private final CodeValidator codeValidator;
    private final StateStore stateStore;
    private final SseBroadcaster sseBroadcaster;
    private final GitTool gitTool;
    private final ObjectMapper mapper;
    private final LlmOutputParser outputParser;
    private final ObservationRegistry observationRegistry;
    private final com.navneet.executespec.agent.PromptAssembler promptAssembler;
    private final AccuracyAssessmentService accuracyAssessmentService;

    @Value("classpath:prompts/reviewer-v2.txt")
    private Resource reviewerV2Prompt;

    private static final int MAX_TOOL_CALLS = 50;  // generous limit for inspect phase — reviewer must read many files
    private static final Duration REVIEW_TIMEOUT = Duration.ofMinutes(10); // reviewer runs once, fixes everything
    private static final int MAX_FIX_ITERATIONS = 100;  // total fix iterations across ALL issues

    /**
     * Run the full review: inspect → fix loop → report.
     *
     * @param run                the pipeline run
     * @param tasks              completed tasks (for context)
     * @param originalGoal       the user's original spec/goal
     * @param availableProviders providers the org has keys for
     * @return the final review report
     */
    public ReviewReportV2 review(PipelineRun run, List<AgentTask> tasks,
                                  String originalGoal, CompileResult compileResult,
                                  Set<LlmProvider> availableProviders) {
        Observation obs = Observation.createNotStarted("executespec.run.phase.review", observationRegistry)
            .lowCardinalityKeyValue("run.id", run.getRunId())
            .start();

        try {
            return doReview(run, tasks, originalGoal, compileResult, availableProviders, obs);
        } catch (Exception e) {
            obs.error(e);
            // Return a partial report rather than crashing the entire background validation thread.
            // Issues collected before the crash are preserved; the report clearly marks the partial state.
            log.error("[ReviewerV2][{}] Review crashed — returning partial report: {}", run.getRunId(), e.getMessage(), e);
            return ReviewReportV2.builder()
                    .verdict(ReviewVerdict.NEEDS_FIXES)
                    .issues(List.of())
                    .criticalCount(0)
                    .minorCount(0)
                    .autoFixAttempted(0)
                    .autoFixSucceeded(0)
                    .summary("Review crashed before completing: " + e.getMessage())
                    .reviewDuration(Duration.ZERO)
                    .reviewerModel("unknown")
                    .readmeUpdated(false)
                    .overallConfidence("LOW")
                    .mergeBlockers(List.of())
                    .postMergeSuggestions(List.of())
                    .accuracyReport(null)
                    .build();
        } finally {
            obs.stop();
        }
    }

    private ReviewReportV2 doReview(PipelineRun run, List<AgentTask> tasks,
                                     String originalGoal, CompileResult compileResult,
                                     Set<LlmProvider> availableProviders,
                                     Observation obs) {
        Instant start = Instant.now();
        String runId = run.getRunId();
        Path workspacePath = gitTool.getWorkspacePath(runId);

        // Select model via ModelResolver for review (PLANNING tier)
        ResolvedModel reviewModel = modelResolver.resolve("ReviewerAgentV2", run.getAiProviderEnum());
        log.info("Review using model: {} [{}]", reviewModel.getModel(), reviewModel.getAgentTier());

        sseBroadcaster.emit(runId, RunEvent.log(runId, null,
                "Review started using " + reviewModel.getModel()));

        // ============================
        // PHASE 1: INSPECT
        // ============================

        String fileTree = getFileTree(runId);
        String taskSummary = buildTaskSummary(tasks);
        String reviewPrompt = buildReviewPrompt(originalGoal, taskSummary, fileTree, compileResult);

        List<ReviewIssue> issues = executeInspection(run, workspacePath, reviewPrompt, reviewModel);

        // Check README gate — softened for small runs (≤5 tasks → MINOR, not CRITICAL)
        boolean readmeUpdated = checkReadmeUpdated(workspacePath);
        if (!readmeUpdated) {
            int taskCount = tasks != null ? tasks.size() : 0;
            IssueSeverity readmeSeverity = taskCount <= 5 ? IssueSeverity.MINOR : IssueSeverity.CRITICAL;
            issues.add(ReviewIssue.builder()
                    .id("REV-README")
                    .severity(readmeSeverity)
                    .category(IssueCategory.README_INCOMPLETE)
                    .file("README.md")
                    .description("README.md was not updated to reflect the generated code")
                    .fixInstruction("Update README.md with: project description, setup instructions, API endpoints if applicable, and usage examples")
                    .build());
        }

        int criticalCount = (int) issues.stream().filter(i -> i.getSeverity() == IssueSeverity.CRITICAL).count();
        int minorCount = (int) issues.stream().filter(i -> i.getSeverity() == IssueSeverity.MINOR).count();

        log.info("Phase 1 complete: {} critical, {} minor issues found", criticalCount, minorCount);
        sseBroadcaster.emit(runId, RunEvent.log(runId, null,
                String.format("Review found %d critical, %d minor issues", criticalCount, minorCount)));

        // ============================
        // PHASE 2: FIX LOOP (critical issues only)
        // ============================

        int autoFixAttempted = 0;
        int autoFixSucceeded = 0;
        int totalFixIterations = 0;
        int totalFixable = issues.size();

        {
            // Fix ALL issues (CRITICAL first, then MINOR) within time + iteration budget
            log.info("Auto-fixing {} issues for run {} (budget: {}min, {} iterations)",
                    totalFixable, runId, REVIEW_TIMEOUT.toMinutes(), MAX_FIX_ITERATIONS);
            List<ReviewIssue> fixableIssues = issues.stream()
                    .sorted((a, b) -> {
                        if (a.getSeverity() == IssueSeverity.CRITICAL && b.getSeverity() != IssueSeverity.CRITICAL) return -1;
                        if (a.getSeverity() != IssueSeverity.CRITICAL && b.getSeverity() == IssueSeverity.CRITICAL) return 1;
                        return 0;
                    })
                    .toList();

            for (ReviewIssue issue : fixableIssues) {
                // Check both time and iteration limits
                boolean timedOut = Duration.between(start, Instant.now()).compareTo(REVIEW_TIMEOUT) > 0;
                if (timedOut) {
                    log.warn("Review timeout ({}min) reached — skipping remaining issues", REVIEW_TIMEOUT.toMinutes());
                    break;
                }
                if (totalFixIterations >= MAX_FIX_ITERATIONS) {
                    log.warn("Fix iteration limit ({}) reached — skipping remaining issues", MAX_FIX_ITERATIONS);
                    break;
                }

                autoFixAttempted++;
                sseBroadcaster.emit(runId, RunEvent.log(runId, null,
                        String.format("Auto-fixing issue %s: %s", issue.getId(), issue.getDescription())));

                try {
                    FixResult result = attemptAutoFix(run, workspacePath, issue, availableProviders);
                    totalFixIterations += result.iterationsUsed();

                    issue.setAutoFixed(true);
                    issue.setFixSucceeded(result.succeeded());
                    if (result.succeeded()) {
                        autoFixSucceeded++;
                        log.info("[ReviewerV2][{}] Auto-fix succeeded for issue {} ({} iterations)", runId, issue.getId(), result.iterationsUsed());
                    } else {
                        log.warn("[ReviewerV2][{}] Auto-fix failed for issue {} ({} iterations)", runId, issue.getId(), result.iterationsUsed());
                    }
                } catch (Exception fixEx) {
                    log.error("[ReviewerV2][{}] Auto-fix threw unexpectedly for issue {} — skipping: {}",
                            runId, issue.getId(), fixEx.getMessage());
                    issue.setAutoFixed(true);
                    issue.setFixSucceeded(false);
                }
            }
        }

        // ============================
        // PHASE 3: ACCURACY ASSESSMENT
        // ============================

        sseBroadcaster.emit(runId, RunEvent.log(runId, null, "Running accuracy assessment…"));
        AccuracyReport accuracyReport;
        try {
            accuracyReport = accuracyAssessmentService.assess(
                    runId, reviewModel.providerName(), reviewModel, originalGoal, issues);
            sseBroadcaster.emit(runId, RunEvent.log(runId, null, accuracyReport.toOneLiner()));
        } catch (Exception e) {
            log.warn("[ReviewerV2] Accuracy assessment failed for run {}: {}", runId, e.getMessage());
            accuracyReport = accuracyAssessmentService.defaultReport(issues);
        }

        // ============================
        // BUILD FINAL REPORT
        // ============================

        Duration reviewDuration = Duration.between(start, Instant.now());
        ReviewVerdict verdict = determineVerdict(issues, readmeUpdated, criticalCount, autoFixSucceeded);

        // Build merge blockers from unfixed CRITICAL issues
        List<String> mergeBlockers = issues.stream()
                .filter(i -> i.getSeverity() == IssueSeverity.CRITICAL)
                .filter(i -> !i.isAutoFixed() || !i.isFixSucceeded())
                .map(i -> i.getId() + ": " + i.getDescription())
                .toList();

        // Post-merge suggestions from unfixed MINOR issues
        List<String> postMergeSuggestions = issues.stream()
                .filter(i -> i.getSeverity() == IssueSeverity.MINOR)
                .filter(i -> !i.isAutoFixed() || !i.isFixSucceeded())
                .map(i -> i.getDescription())
                .toList();

        // Derive confidence from verdict
        String confidence = switch (verdict) {
            case APPROVED -> "HIGH";
            case APPROVED_WITH_NOTES -> "HIGH";
            case NEEDS_FIXES -> "MEDIUM";
            case REJECTED -> "LOW";
        };

        ReviewReportV2 report = ReviewReportV2.builder()
                .verdict(verdict)
                .issues(issues)
                .criticalCount(criticalCount)
                .minorCount(minorCount)
                .autoFixAttempted(autoFixAttempted)
                .autoFixSucceeded(autoFixSucceeded)
                .summary(buildSummary(verdict, criticalCount, minorCount, autoFixSucceeded))
                .reviewDuration(reviewDuration)
                .reviewerModel(reviewModel.getModel())
                .readmeUpdated(readmeUpdated)
                .overallConfidence(confidence)
                .mergeBlockers(mergeBlockers)
                .postMergeSuggestions(postMergeSuggestions)
                .accuracyReport(accuracyReport)
                .build();

        sseBroadcaster.emit(runId, RunEvent.log(runId, null,
                String.format("Review complete: %s (%d critical, %d minor, %d/%d auto-fixed) in %ds",
                        verdict, criticalCount, minorCount, autoFixSucceeded, autoFixAttempted,
                        reviewDuration.toSeconds())));

        obs.lowCardinalityKeyValue("verdict", verdict.name());
        obs.highCardinalityKeyValue("issues.critical", String.valueOf(criticalCount));
        obs.highCardinalityKeyValue("issues.minor", String.valueOf(minorCount));
        obs.highCardinalityKeyValue("autofix.attempted", String.valueOf(autoFixAttempted));
        obs.highCardinalityKeyValue("autofix.succeeded", String.valueOf(autoFixSucceeded));
        obs.highCardinalityKeyValue("duration.seconds", String.valueOf(reviewDuration.toSeconds()));
        if (accuracyReport != null) {
            obs.highCardinalityKeyValue("accuracy.score", String.valueOf(accuracyReport.getSpecAdherenceScore()));
            obs.lowCardinalityKeyValue("accuracy.grade", accuracyReport.getOverallAccuracyGrade() != null
                    ? accuracyReport.getOverallAccuracyGrade() : "?");
        }

        return report;
    }

    // ============================
    // PHASE 1: INSPECTION LOOP
    // ============================

    /**
     * Execute the inspection agentic loop.
     * Similar to WorkerAgent's loop but restricted to read-only tools (read_file, list_files)
     * with max 10 tool calls. Expects JSON array output matching ReviewIssue structure.
     */
    private List<ReviewIssue> executeInspection(PipelineRun run, Path workspacePath,
                                                  String reviewPrompt, ResolvedModel model) {
        String runId = run.getRunId();
        log.info("Executing review inspection with model {}", model.getModel());

        String provider = model.providerName();
        String systemPrompt = loadPrompt();

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(reviewPrompt));

        int toolCallCount = 0;

        AnthropicChatOptions reviewOpts = "ANTHROPIC".equalsIgnoreCase(provider)
                ? AnthropicChatOptions.builder()
                        .temperature(0.0)
                        .cacheOptions(AnthropicCacheOptions.builder()
                                .strategy(AnthropicCacheStrategy.SYSTEM_ONLY)
                                .build())
                        .build()
                : null;

        for (int iteration = 0; iteration < MAX_TOOL_CALLS + 5; iteration++) {
            Prompt prompt = reviewOpts != null ? new Prompt(messages, reviewOpts) : new Prompt(messages);

            LlmRouter.LlmResponse lr;
            try {
                lr = llmRouter.callWithFallbackTracked(provider, model.getModel(), prompt,
                        runId, "reviewer-v2", FallbackMode.IMMEDIATE, iteration, false);
            } catch (Exception e) {
                log.error("[ReviewerV2] LLM call failed on iteration {}", iteration, e);
                break;
            }
            ChatResponse response = lr.response();

            // Track tokens
            try {
                int inTok = response.getMetadata().getUsage().getPromptTokens().intValue();
                int outTok = response.getMetadata().getUsage().getCompletionTokens().intValue();
                stateStore.addTokensToRun(runId, inTok, outTok);
                stateStore.setAgentTokens(runId, "reviewer", inTok, outTok, lr.provider(), lr.model());
            } catch (Exception ex) {
                log.warn("[ReviewerV2] Could not extract token counts: {}", ex.getMessage());
            }

            String assistantText = response.getResult().getOutput().getText();
            if (assistantText == null || assistantText.isBlank()) break;

            messages.add(new AssistantMessage(assistantText));

            // Check for tool calls in response (XML format: <tool_call>{"name":"...","args":{...}}</tool_call>)
            List<ToolCallRequest> toolCalls = parseToolCalls(assistantText);

            if (!toolCalls.isEmpty() && toolCallCount < MAX_TOOL_CALLS) {
                toolCallCount = processToolCalls(toolCalls, toolCallCount, runId, messages);
                continue;
            }

            // No tool calls — try to parse JSON output
            List<ReviewIssue> parsed = parseReviewResponse(assistantText, iteration, messages);
            if (parsed != null) {
                return parsed;
            }
        }

        log.warn("[ReviewerV2] Inspection loop ended without producing JSON output");
        return new ArrayList<>();
    }

    /**
     * Process tool calls from the assistant response, dispatching each read-only tool
     * and appending results to the message list.
     *
     * @return the updated tool call count
     */
    private int processToolCalls(List<ToolCallRequest> toolCalls, int toolCallCount,
                                  String runId, List<Message> messages) {
        StringBuilder results = new StringBuilder();
        for (ToolCallRequest tc : toolCalls) {
            if (toolCallCount >= MAX_TOOL_CALLS) {
                results.append("[").append(tc.name).append(" result]\n<output>\n")
                       .append("Tool call limit reached (max ").append(MAX_TOOL_CALLS).append(")")
                       .append("\n</output>\n\n");
                continue;
            }
            toolCallCount++;
            try {
                var args = mapper.readTree(tc.argsJson);
                String path = args.path("path").asText("");
                log.info("[ReviewerV2][{}] tool_call {}/{}: {} path={}", runId, toolCallCount, MAX_TOOL_CALLS, tc.name, path);
            } catch (Exception ignored) {
                log.info("[ReviewerV2][{}] tool_call {}/{}: {}", runId, toolCallCount, MAX_TOOL_CALLS, tc.name);
            }
            String result = dispatchReadOnlyTool(tc, runId);
            results.append("[").append(tc.name).append(" result]\n<output>\n")
                   .append(result).append("\n</output>\n\n");
        }
        messages.add(new UserMessage(results.toString().trim()));
        return toolCallCount;
    }

    /**
     * Parse the assistant's response as a JSON review output.
     * Strips markdown code fences, handles single-object and array formats,
     * and prompts the model to retry on parse failure.
     *
     * @return the parsed list of issues, or null if the response was not valid JSON
     */
    private List<ReviewIssue> parseReviewResponse(String assistantText, int iteration,
                                                    List<Message> messages) {
        String trimmed = LlmResponseCleaner.stripMarkdownFences(assistantText);

        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            try {
                List<ReviewIssue> issues;
                if (trimmed.startsWith("[")) {
                    issues = mapper.readValue(trimmed, new TypeReference<List<ReviewIssue>>() {});
                } else {
                    // Single object — wrap in list
                    ReviewIssue single = mapper.readValue(trimmed, ReviewIssue.class);
                    issues = new ArrayList<>();
                    issues.add(single);
                }
                log.info("[ReviewerV2] Parsed {} issues from inspection", issues.size());
                return issues;
            } catch (Exception e) {
                log.warn("[ReviewerV2] Failed to parse JSON on iteration {}: {}", iteration, e.getMessage());
                messages.add(new UserMessage(
                        "Your response was not valid JSON. Return ONLY a JSON array of review issues."));
            }
        } else {
            // Not JSON — ask model to produce JSON
            messages.add(new UserMessage(
                    "Please produce your final review as a JSON array of issues. " +
                    "Each issue: { id, severity, category, file, description, fixInstruction }. " +
                    "If no issues found, return: []"));
        }
        return null;
    }

    // ============================
    // PHASE 2: AUTO-FIX
    // ============================

    /**
     * Attempt to auto-fix a critical issue by sending a fix instruction to a worker.
     * Uses CAPABLE tier, escalates to POWER on retry.
     * Validates the fix result.
     *
     * @return true if fix succeeded (validation passed), false otherwise
     */
    private static final int MAX_FIX_ATTEMPTS = 2;

    /** Result of an auto-fix attempt, tracking both success and iterations consumed. */
    private record FixResult(boolean succeeded, int iterationsUsed) {}

    private FixResult attemptAutoFix(PipelineRun run, Path workspacePath,
                                      ReviewIssue issue, Set<LlmProvider> availableProviders) {
        String runId = run.getRunId();
        int iterationsUsed = 0;

        // Use WORKHORSE tier (Sonnet) for fix workers — single-file fixes don't need Opus.
        // Review/inspection stays on PLANNING tier; only the fix execution is downgraded.
        ResolvedModel fixModel = modelResolver.resolve("WorkerAgent", run.getAiProviderEnum());

        for (int attempt = 0; attempt < MAX_FIX_ATTEMPTS; attempt++) {
            iterationsUsed++;
            try {
                AgentTask fixTask = createFixTask(run, issue, fixModel);

                log.info("Executing auto-fix for {} using {} (attempt {}/{})",
                        issue.getId(), fixModel.getModel(), attempt + 1, MAX_FIX_ATTEMPTS);

                // Execute using WorkerAgent's standard tool-use loop
                WorkerReport fixReport = workerAgent.execute(fixTask, runId);

                // The fix worker runs in a worktree on branch agent/<prefix>/<fixTaskId>.
                // Merge the fix branch back to main so the fix is visible in the workspace.
                String fixBranchName = "agent/" + runId.substring(0, 8) + "/" + fixTask.getTaskId();
                String mergeResult = gitTool.mergeBranchToMain(runId, fixBranchName);
                if (mergeResult != null && mergeResult.startsWith("ERROR")) {
                    log.warn("Fix branch merge failed for {}: {}", issue.getId(), mergeResult);
                    if (attempt < MAX_FIX_ATTEMPTS - 1) continue;
                    return new FixResult(false, iterationsUsed);
                }
                log.info("Merged fix branch {} to main for issue {}", fixBranchName, issue.getId());

                // Validate the fix (now on main after merge)
                Language lang = detectLanguageForFile(issue.getFile());
                if (lang != null) {
                    ValidationResult validationResult = codeValidator.validateFile(
                            ValidateFileRequest.builder()
                                    .projectRoot(workspacePath)
                                    .filePath(Path.of(issue.getFile()))
                                    .language(lang)
                                    .level(ValidationLevel.TYPE)
                                    .build());

                    if (validationResult.getVerdict() == ValidationVerdict.FAIL) {
                        log.warn("Fix for {} failed validation (attempt {}): {}", issue.getId(),
                                attempt + 1, RetryPromptBuilder.buildSummary(validationResult));
                        if (attempt < MAX_FIX_ATTEMPTS - 1) {
                            log.info("Retrying fix for {} with escalated tier", issue.getId());
                            continue;
                        }
                        return new FixResult(false, iterationsUsed);
                    }
                }

                // Commit any remaining dirty state (e.g. merge artifacts)
                gitTool.commitIfDirty(runId, "Auto-fix: " + issue.getId() + " — " + issue.getDescription());
                return new FixResult(true, iterationsUsed);

            } catch (Exception e) {
                log.error("Auto-fix attempt {} failed for issue {}: {}", attempt + 1, issue.getId(), e.getMessage());
                if (attempt >= MAX_FIX_ATTEMPTS - 1) {
                    return new FixResult(false, iterationsUsed);
                }
            }
        }
        return new FixResult(false, iterationsUsed);
    }

    /**
     * Create a transient AgentTask for the fix worker.
     * Sets up proper TaskDefinition JSON so WorkerAgent.execute() can process it.
     */
    private AgentTask createFixTask(PipelineRun run, ReviewIssue issue, ResolvedModel fixModel) {
        String fixPrompt = buildFixPrompt(issue);

        // Build a TaskDefinition JSON for the worker
        TaskDefinition fixDef = TaskDefinition.builder()
                .taskId("fix-" + issue.getId().toLowerCase())
                .title("Fix: " + issue.getDescription())
                .objective(fixPrompt)
                .build();

        AgentTask task = new AgentTask();
        task.setRunId(run.getRunId());
        task.setTaskId("fix-" + issue.getId().toLowerCase());
        task.setTitle("Fix: " + issue.getDescription());
        task.setStatus("WORKING");
        task.setAssignedModel(fixModel.getModel());

        try {
            task.setTaskJson(mapper.writeValueAsString(fixDef));
        } catch (Exception e) {
            log.warn("Failed to serialize fix TaskDefinition JSON", e);
        }

        // Don't persist — this is a transient fix task
        return task;
    }

    // ============================
    // TOOL CALL PARSING (read-only subset)
    // ============================

    private record ToolCallRequest(String name, String argsJson) {}

    /**
     * Parse XML tool calls from assistant text.
     * Pattern: {@code <tool_call>{"name":"...","args":{...}}</tool_call>}
     */
    private List<ToolCallRequest> parseToolCalls(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<ToolCallRequest> calls = new ArrayList<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "<tool_call>(.*?)</tool_call>", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            try {
                String json = matcher.group(1).trim();
                var node = mapper.readTree(json);
                String name = node.path("name").asText("");
                String args = node.has("args") ? node.get("args").toString() : "{}";
                calls.add(new ToolCallRequest(name, args));
            } catch (Exception e) {
                log.warn("[ReviewerV2] Failed to parse tool call: {}", e.getMessage());
            }
        }
        return calls;
    }

    /**
     * Dispatch read-only tool calls. Only read_file and list_files are allowed.
     */
    private String dispatchReadOnlyTool(ToolCallRequest tc, String runId) {
        try {
            var args = mapper.readTree(tc.argsJson);
            return switch (tc.name) {
                case "readFile", "read_file" -> {
                    String path = args.path("path").asText("");
                    yield fileSystemTool.readFile(runId, path);
                }
                case "listFiles", "list_files" -> {
                    String path = args.path("path").asText(".");
                    yield fileSystemTool.listFiles(runId, path);
                }
                default -> "Tool '" + tc.name + "' is not available. Only read_file and list_files are supported.";
            };
        } catch (Exception e) {
            return "Error executing " + tc.name + ": " + e.getMessage();
        }
    }

    // ============================
    // PROMPT BUILDERS
    // ============================

    private String buildReviewPrompt(String originalGoal, String taskSummary, String fileTree,
                                      CompileResult compileResult) {
        String compileStatus;
        if (compileResult == null || compileResult.skipped()) {
            compileStatus = "Skipped (no build tool detected)";
        } else if (compileResult.passed()) {
            compileStatus = "Passed" + (compileResult.attempts() > 1
                    ? " after " + compileResult.attempts() + " attempts (compiler agent fixed errors)"
                    : " on first attempt");
        } else {
            String summary = compileResult.errorSummary() != null
                    ? compileResult.errorSummary().substring(0, Math.min(2000, compileResult.errorSummary().length()))
                    : "Unknown errors";
            compileStatus = "FAILED after " + compileResult.attempts() + " attempts. Errors:\n" + summary;
        }

        return String.format("""
            === ORIGINAL SPECIFICATION ===
            %s

            === TASK PLAN ===
            %s

            === COMPILATION STATUS ===
            %s

            === FILE TREE ===
            %s

            === TOOLS AVAILABLE ===
            You can use these tools to inspect the code:
            - read_file(path): read the contents of a specific file
            - list_files(path): list files in a directory

            Call tools using XML format:
            <tool_call>{"name":"read_file","args":{"path":"src/main/java/..."}}</tool_call>

            You have a maximum of 10 tool calls. Be strategic:
            1. Start by reading the main entry points (controllers, routes, main class)
            2. Read service/business logic files
            3. Spot-check tests if they exist
            4. Check README.md

            After inspecting, produce your final output as a JSON array of issues.
            If no issues found, return: []
            """, originalGoal, taskSummary, compileStatus, fileTree);
    }

    private String buildFixPrompt(ReviewIssue issue) {
        return String.format("""
            === REVIEW FIX REQUEST ===

            A code review found an issue that needs to be fixed.

            Issue ID: %s
            File: %s
            Category: %s
            Problem: %s
            Expected behavior: %s

            Fix ONLY this specific issue. Do NOT modify any other files or make unrelated changes.
            Read the file first, then make the targeted fix.

            === END FIX REQUEST ===
            """,
                issue.getId(),
                issue.getFile(),
                issue.getCategory(),
                issue.getDescription(),
                issue.getFixInstruction());
    }

    // ============================
    // HELPERS
    // ============================

    private String getFileTree(String runId) {
        try {
            return fileSystemTool.listFiles(runId, ".");
        } catch (Exception e) {
            log.warn("Failed to get file tree", e);
            return "(file tree unavailable)";
        }
    }

    private String buildTaskSummary(List<AgentTask> tasks) {
        return tasks.stream()
                .map(t -> String.format("- [%s] %s (complexity: %s, status: %s)",
                        t.getTaskId(),
                        t.getTitle() != null ? t.getTitle() : t.getTaskId(),
                        t.getComplexity() != null ? t.getComplexity() : "MEDIUM",
                        t.getStatus()))
                .collect(Collectors.joining("\n"));
    }

    private boolean checkReadmeUpdated(Path workspacePath) {
        try {
            Path readme = workspacePath.resolve("README.md");
            return readme.toFile().exists() && readme.toFile().length() > 50;
        } catch (Exception e) {
            return false;
        }
    }

    private Language detectLanguageForFile(String filePath) {
        if (filePath == null) return null;
        return Language.fromPath(Path.of(filePath));
    }

    private String loadPrompt() {
        return promptAssembler.assemble("reviewer-v2.txt");
    }

    // ============================
    // VERDICT LOGIC
    // ============================

    private ReviewVerdict determineVerdict(List<ReviewIssue> issues, boolean readmeUpdated,
                                            int criticalCount, int autoFixSucceeded) {
        // README missing is tracked as a CRITICAL issue (REV-README) and goes through
        // auto-fix like any other issue. No longer a hard rejection gate — working code
        // with a missing README should not be REJECTED.

        // Check for unfixed critical issues
        long unfixedCritical = issues.stream()
                .filter(i -> i.getSeverity() == IssueSeverity.CRITICAL)
                .filter(i -> !i.isAutoFixed() || !i.isFixSucceeded())
                .count();
        if (unfixedCritical > 0) return ReviewVerdict.NEEDS_FIXES;

        // Check for unfixed minor issues
        long unfixedMinor = issues.stream()
                .filter(i -> i.getSeverity() == IssueSeverity.MINOR)
                .filter(i -> !i.isAutoFixed() || !i.isFixSucceeded())
                .count();
        if (unfixedMinor > 0) return ReviewVerdict.APPROVED_WITH_NOTES;

        // All issues were auto-fixed, or no issues at all
        if (!issues.isEmpty()) return ReviewVerdict.APPROVED_WITH_NOTES;

        return ReviewVerdict.APPROVED;
    }

    private String buildSummary(ReviewVerdict verdict, int critical, int minor, int autoFixed) {
        return switch (verdict) {
            case APPROVED -> "Code review passed. No issues found.";
            case APPROVED_WITH_NOTES ->
                    String.format("Code review passed with %d minor note(s). No critical issues.", minor);
            case NEEDS_FIXES ->
                    String.format("Code review found %d critical issue(s). %d auto-fixed. Remaining need manual attention.", critical, autoFixed);
            case REJECTED ->
                    String.format("Code review rejected. %d critical issue(s) could not be resolved.", critical);
        };
    }
}
