package com.navneet.agentteam.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.agentteam.agent.LlmRouter.FallbackMode;
import com.navneet.agentteam.agent.LlmRouter.MidTaskProviderFailureException;
import com.navneet.agentteam.agent.TokenBudgetMonitor.BudgetStatus;
import com.navneet.agentteam.agent.model.TaskDefinition;
import com.navneet.agentteam.agent.model.WorkerReport;
import com.navneet.agentteam.config.AgentProperties;
import com.navneet.agentteam.state.InboxService;
import com.navneet.agentteam.state.StateStore;
import com.navneet.agentteam.state.entity.AgentTask;
import com.navneet.agentteam.tool.FileSystemTool;
import com.navneet.agentteam.tool.GitTool;
import com.navneet.agentteam.tool.ShellTool;
import com.navneet.agentteam.tool.ToolResult;
import com.navneet.agentteam.tool.WorkspaceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkerAgent {

    @Qualifier("workerModel")
    private final ChatModel chatModel; // retained for Spring wiring — LlmRouter resolves models dynamically
    private final FileSystemTool fsTool;
    private final ShellTool shellTool;
    private final GitTool gitTool;
    private final StateStore stateStore;
    private final ObjectMapper mapper;
    private final AgentProperties props;
    private final InboxService inboxService;
    private final TokenBudgetMonitor budgetMonitor;
    private final LlmRouter llmRouter;
    private final ResourceLoader resourceLoader;
    private final LlmOutputParser outputParser;

    @Value("classpath:prompts/worker.txt")
    private Resource workerPromptFallback;

    /**
     * Parsed representation of a single <tool_call> extracted from assistant text.
     */
    private record ParsedToolCall(String name, String argsJson) {
    }

    private static final Pattern TOOL_CALL_PATTERN = Pattern.compile("<tool_call>(.*?)</tool_call>", Pattern.DOTALL);

    public WorkerReport execute(AgentTask task, String runId) {
        String taskId = task.getTaskId();
        String model = task.getAssignedModel() != null ? task.getAssignedModel() : "claude-haiku-4-5-20251001";
        String provider = resolveProvider(model);
        String agentId = "worker-" + taskId;
        String complexity = task.getComplexity() != null ? task.getComplexity() : "MEDIUM";
        String branchName = "agent/" + runId.substring(0, 8) + "/" + taskId;

        log.info("Worker starting task [{}] on branch {} ({}:{})", taskId, branchName, provider, model);

        stateStore.logMessage(runId, "harness", agentId,
                "TASK_ASSIGNMENT", "Starting: " + task.getTitle(), 0);

        // Load task definition
        TaskDefinition taskDef = parseTask(task.getTaskJson());

        // Build the list of dep branches so the worktree can merge them in
        List<String> depBranches = new ArrayList<>();
        if (taskDef.getDependsOn() != null) {
            taskDef.getDependsOn().forEach(depId ->
                depBranches.add("agent/" + runId.substring(0, 8) + "/" + depId));
        }

        // Create an isolated git worktree for this task.  Each task now has its
        // own working directory so concurrent tasks can no longer clobber each
        // other via git checkout / working-tree state.
        String worktreePath = gitTool.createWorktree(runId, taskId, branchName, depBranches);
        WorkspaceContext.set(runId, worktreePath);
        WorkspaceContext.setTaskId(taskId);
        log.info("Worker starting task [{}] on branch {} in worktree {} ({}:{})",
                taskId, branchName, worktreePath, provider, model);

        // Per-task conversation history (WorkerAgent is a Spring singleton — no
        // instance fields)
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(buildInitialUserMessage(taskDef, branchName)));

        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        int totalCacheReadTokens = 0; // Anthropic cache_read_input_tokens (billed at 0.10x)
        int totalCacheWriteTokens = 0; // Anthropic cache_creation_input_tokens (billed at 1.25x)
        int iteration = 0;
        int lastCallInputTokens = 0;
        boolean justCompacted = false;

        // ── AGENTIC LOOP ──────────────────────────────────────────────────────
        try { // outer try: always clear WorkspaceContext on exit
        try {
            int maxIterations = props.getConcurrency().getMaxIterations();
            while (iteration < maxIterations) {

                // Drain inbox at start of each iteration; inject as user turn if non-null
                String inboxContent = inboxService.drainInbox(agentId, runId);
                if (inboxContent != null) {
                    messages.add(new UserMessage(inboxContent));
                    log.debug("[Worker {}] Injected inbox messages into context", taskId);
                }

                // Tier 5: record activity before LLM call (for stuck detection)
                stateStore.updateLastActivity(taskId);
                stateStore.appendTaskLog(taskId, "LLM_REQUEST_SENT");

                LlmRouter.LlmResponse lr = callModel(messages, provider, model,
                        runId, agentId, iteration, justCompacted, complexity);
                provider = lr.provider(); // track the provider that actually succeeded

                iteration++;

                // getResult() can return null when the provider sends an empty completion
                // (e.g. rate-limit response, content-filter rejection) that LlmRouter
                // passed through without throwing. Guard here so the loop can retry.
                if (lr.response().getResult() == null) {
                    log.warn("[Worker {}] Null ChatResponse.getResult() on iteration {} — skipping, will retry",
                            taskId, iteration);
                    continue;
                }
                AssistantMessage assistantMsg = (AssistantMessage) lr.response().getResult().getOutput();
                String assistantText = assistantMsg.getText();
                messages.add(assistantMsg);

                int inputTok = extractInputTokens(lr.response());
                int outputTok = extractOutputTokens(lr.response());
                int cacheReadTok = extractCacheReadTokens(lr.response());
                int cacheWriteTok = extractCacheWriteTokens(lr.response());

                lastCallInputTokens = inputTok;
                totalInputTokens += inputTok;
                totalOutputTokens += outputTok;
                totalCacheReadTokens += cacheReadTok;
                totalCacheWriteTokens += cacheWriteTok;
                justCompacted = false;

                String cacheNote = buildCacheNote(lr.response());
                stateStore.logMessage(runId, agentId, "state",
                        "TOOL_CALL", "Iteration " + iteration +
                                " (" + inputTok + "/" + outputTok + " tokens)" + cacheNote,
                        inputTok + outputTok);

                // Running cache effectiveness summary every 5 iterations
                if (iteration % 5 == 0 && totalCacheReadTokens > 0) {
                    double cacheSavingPct = totalCacheReadTokens * 100.0
                            / Math.max(1, totalInputTokens + totalCacheReadTokens);
                    log.info("[Cache][{}] After {} iter — cacheRead={} tok, cacheWrite={} tok, " +
                            "normalIn={} tok, out={} tok | ~{}% of input served from cache",
                            taskId, iteration,
                            totalCacheReadTokens, totalCacheWriteTokens,
                            totalInputTokens, totalOutputTokens,
                            String.format("%.1f", cacheSavingPct));
                }

                // ── Execute native tool calls or final output ─────────────────────────
                // Spring AI 1.1.0 with internalToolExecutionEnabled=true will actually loop
                // internally
                // until it returns the final text response. However, we want to maintain the
                // outer observable loop in WorkerAgent to log iterations, budget monitors, etc.
                // Since we are returning the raw Tool response to the LLM, we just need to
                // detect
                // if it's a completion report vs an intermediate thought.

                // When using Spring AI's automatic internal tool execution, the final output
                // returned here implies the LLM has finished its chain of thought/tool-usage
                // for this single call.

                if (assistantText != null && !assistantText.isBlank()) {
                    log.info("[Worker {}] Iteration {} — output ({} chars)",
                            taskId, iteration, assistantText.length());

                    // 1. Parse and dispatch XML <tool_call> blocks
                    List<ParsedToolCall> toolCalls = parseXmlToolCalls(assistantText);
                    if (!toolCalls.isEmpty()) {
                        StringBuilder results = new StringBuilder();
                        for (ParsedToolCall tc : toolCalls) {
                            stateStore.appendTaskLog(taskId, "TOOL_DISPATCHED:" + tc.name());
                            String result = dispatchTool(tc, runId, taskId);
                            results.append("[").append(tc.name()).append(" result]\n<output>\n")
                                   .append(result).append("\n</output>\n\n");
                        }
                        messages.add(new UserMessage(results.toString().trim()));
                        continue; // next iteration — do NOT add the "continue" prompt below
                    }

                    // 2. Strict completion detection: JSON must be the entire response
                    if (looksLikeCompletionReport(assistantText)) {
                        return parseCompletionReport(assistantText, taskId,
                                totalInputTokens, totalOutputTokens,
                                totalCacheReadTokens, totalCacheWriteTokens);
                    }
                }

                // No tool calls and no completion report — prompt to continue
                messages.add(new UserMessage(
                        "If you have finished all your work, output ONLY your completion JSON report now. " +
                        "Otherwise, use <tool_call>{\"name\": \"...\", \"args\": {...}}</tool_call> XML to call the next tool."));

                // Budget monitoring (compaction / hard-stop)
                BudgetStatus budget = budgetMonitor.check(model, complexity, lastCallInputTokens);
                switch (budget.status()) {
                    case HARD_STOP -> {
                        log.warn("Hard stop threshold reached for task {} ({}% context)",
                                taskId, (int) (budget.usagePct() * 100));
                        stateStore.recordCompaction(taskId, "hard-stop");
                        return WorkerReport.partial(taskId,
                                "Hard stopped at " + (int) (budget.usagePct() * 100)
                                        + "% context usage. Resume required.",
                                totalInputTokens, totalOutputTokens);
                    }
                    case COMPACT -> {
                        log.info("Compacting context for task {} at {}% usage", taskId,
                                (int) (budget.usagePct() * 100));
                        String[] compactProvider = { provider };
                        messages = compact(messages, taskDef, taskId, runId,
                                compactProvider, model, agentId, complexity);
                        provider = compactProvider[0];
                        lastCallInputTokens = 0;
                        justCompacted = true;
                    }
                    case WARN ->
                        log.warn("Context at {}% for task {} — compaction approaching",
                                (int) (budget.usagePct() * 100), taskId);
                    case OK -> {
                        /* nothing */ }
                }
            }
        } catch (MidTaskProviderFailureException e) {
            log.error("Mid-task provider failure for task {}: {}", taskId, e.getMessage());
            return WorkerReport.blocked(taskId, e.getMessage(), totalInputTokens, totalOutputTokens);
        } catch (LlmRouter.ProviderUnavailableException e) {
            log.error("All providers exhausted for task {}: {}", taskId, e.getMessage());
            return WorkerReport.blocked(taskId, "All providers exhausted: " + e.getMessage(),
                    totalInputTokens, totalOutputTokens);
        }
        // ── END AGENTIC LOOP ──────────────────────────────────────────────────

        log.warn("Worker for task {} hit iteration cap without completing", taskId);
        return WorkerReport.partial(taskId,
                "Hit " + props.getConcurrency().getMaxIterations() + "-iteration cap without producing completion report.",
                totalInputTokens, totalOutputTokens);
        } finally {
            // Always clear the thread-local worktree context, even on exception.
            WorkspaceContext.clear();
        }
    }

    // ── XML Tool-Call Parsing & Dispatch ──────────────────────────────────────

    /** Lenient mapper that accepts raw control chars (newlines, tabs) inside JSON strings.
     *  LLMs frequently emit unescaped newlines in file content — standard Jackson rejects these. */
    private static final com.fasterxml.jackson.databind.ObjectMapper LENIENT_MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true);

    private List<ParsedToolCall> parseXmlToolCalls(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<ParsedToolCall> calls = new ArrayList<>();
        Matcher m = TOOL_CALL_PATTERN.matcher(text);
        while (m.find()) {
            String json = m.group(1).trim();
            try {
                JsonNode node = LENIENT_MAPPER.readTree(json);
                String name = node.has("name") ? node.get("name").asText() : null;
                String argsJson = node.has("args") ? node.get("args").toString() : "{}";
                if (name != null && !name.isBlank()) {
                    calls.add(new ParsedToolCall(name, argsJson));
                }
            } catch (Exception e) {
                log.warn("[Worker] Could not parse tool_call JSON: {} — {}", json, e.getMessage());
            }
        }
        return calls;
    }

    private String dispatchTool(ParsedToolCall tc, String runId, String taskId) {
        try {
            JsonNode args = mapper.readTree(tc.argsJson());
            String result = switch (tc.name()) {
                case "readFile", "read_file" -> {
                    String path = args.path("path").asText("");
                    yield fsTool.readFile(runId, path);
                }
                case "writeFile", "write_file" -> {
                    String path = args.path("path").asText("");
                    String content = args.path("content").asText("");
                    yield fsTool.writeFile(runId, path, content);
                }
                case "editFile", "edit_file" -> {
                    String path = args.path("path").asText("");
                    String oldStr = args.path("oldStr").asText(args.path("old_str").asText(""));
                    String newStr = args.path("newStr").asText(args.path("new_str").asText(""));
                    yield fsTool.editFile(runId, path, oldStr, newStr);
                }
                case "listFiles", "list_files" -> {
                    String path = args.path("path").asText(".");
                    yield fsTool.listFiles(runId, path);
                }
                case "searchFiles", "search_files" -> {
                    String pattern = args.path("pattern").asText("");
                    String directory = args.path("directory").asText(".");
                    yield fsTool.searchFiles(runId, pattern, directory);
                }
                case "run", "run_command", "run_build", "run_tests" -> {
                    String command = args.path("command").asText("");
                    int timeout = args.path("timeoutSeconds").asInt(
                            args.path("timeout_seconds").asInt(120));
                    yield shellTool.run(runId, command, timeout);
                }
                case "status", "git_status" -> gitTool.status(runId);
                case "diff", "git_diff" -> gitTool.diff(runId);
                case "commit", "git_commit" -> {
                    String message = args.path("message").asText("");
                    yield gitTool.commit(runId, message);
                }
                default -> "Unknown tool: " + tc.name();
            };
            String truncated = truncateResult(tc.name(), result);
            log.debug("[Worker {}] Tool '{}' → {} chars", taskId, tc.name(),
                    truncated != null ? truncated.length() : 0);
            return truncated != null ? truncated : "(no output)";
        } catch (Exception e) {
            log.warn("[Worker {}] Tool dispatch error for '{}': {}", taskId, tc.name(), e.getMessage());
            return "Tool error: " + e.getMessage();
        }
    }

    /**
     * Strict completion check: the response must start with '{' (after stripping markdown
     * fences). This prevents false positives when the model discusses field names in prose.
     */
    private boolean looksLikeCompletionReport(String text) {
        if (text == null || text.isBlank()) return false;
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```json\\s*", "").replaceFirst("^```\\s*", "").trim();
        }
        return trimmed.startsWith("{") &&
               trimmed.contains("\"task_id\"") &&
               trimmed.contains("\"status\"");
    }

    // ── Compaction ────────────────────────────────────────────────────────────

    private List<Message> compact(List<Message> messages,
            TaskDefinition task, String taskId, String runId,
            String[] effectiveProvider, String model, String agentId,
            String complexity) {
        String provider = effectiveProvider[0];
        String summaryPrompt = """
                Summarize this coding session precisely for resumption. Be specific — vague summaries
                cause the worker to repeat work or miss context.

                Include exactly:

                1. ACCOMPLISHED
                   List every file written or edited with its path and a one-sentence description.

                2. BUILD AND TEST STATUS
                   Did the build pass? Did tests pass? If either failed, what was the last error message?

                3. REMAINING WORK
                   What still needs to be done to satisfy the acceptance criteria?

                4. DECISIONS MADE
                   Any choices made that aren't obvious from the code.

                5. EXACTLY WHERE I LEFT OFF
                   The last tool call made and what I was about to do next.

                Respond in plain text — not JSON.
                """;

        List<Message> summaryMessages = new ArrayList<>(messages);
        summaryMessages.add(new UserMessage(summaryPrompt));

        LlmRouter.LlmResponse summaryLr = callModel(summaryMessages, provider, model,
                runId, agentId, 0, false, complexity);
        effectiveProvider[0] = summaryLr.provider();
        String summary = summaryLr.response().getResult().getOutput().getText();

        String checkpointPath = ".agent-context/worker-" + taskId + "-checkpoint.md";
        String checkpointContent = """
                # Checkpoint: %s
                Generated: %s

                ## Task
                %s

                ## Session Summary
                %s
                """.formatted(taskId, Instant.now(), safeJson(task), summary);

        fsTool.writeFile(runId, checkpointPath, checkpointContent);
        stateStore.recordCompaction(taskId, checkpointPath);

        String fileContext = rebuildFileContext(runId);

        List<Message> fresh = new ArrayList<>();
        fresh.add(new UserMessage("""
                You are resuming a task after a context compaction.

                Original task:
                %s

                What happened so far (session summary):
                %s

                Current state of files you have written:
                %s

                Continue from where you left off. Use <tool_call> XML calls to complete the remaining work.
                """.formatted(safeJson(task), summary, fileContext)));

        log.info("Context compacted for task {}. Checkpoint: {}", taskId, checkpointPath);
        return fresh;
    }

    private String rebuildFileContext(String runId) {
        return "Current src/ structure:\n" + fsTool.searchFiles(runId, "", "src");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildInitialUserMessage(TaskDefinition task, String branchName) {
        // REWORK mode: apply ONLY the specific reviewer fixes — do not redo the whole task.
        // The harness injects reworkFeedback when a reviewer returned REWORK so the worker
        // knows exactly which files and lines to touch without re-generating everything.
        if (task.getReworkFeedback() != null && !task.getReworkFeedback().isBlank()) {
            return """
                    You are in REWORK mode for task [%s].
                    Your ONLY job is to apply the specific fixes listed below.
                    DO NOT rewrite or regenerate existing code from scratch.
                    DO NOT redo work that is already correct.
                    DO NOT modify files that are not mentioned in the issues.

                    SPECIFIC FIXES TO APPLY:
                    %s

                    Instructions:
                    1. Use readFile to examine the specific files mentioned in the issues above.
                    2. Apply only the minimal targeted changes needed to fix each issue.
                    3. Commit your changes with message: "fix(rework): address reviewer feedback"
                    4. Report DONE with a WorkerReport JSON summarising only the changes made.

                    You are on branch: %s
                    """.formatted(task.getTaskId(), task.getReworkFeedback(), branchName);
        }

        // Normal first-attempt task assignment
        String deps = (task.getDependsOn() == null || task.getDependsOn().isEmpty())
                ? "none"
                : String.join(", ", task.getDependsOn());

        return """
                Your task assignment:

                TASK ID: %s
                TITLE: %s
                OBJECTIVE: %s
                BOUNDARIES: %s
                OUTPUT FORMAT: %s
                ACCEPTANCE CRITERIA: %s
                DEPENDS ON: %s
                COMPLEXITY: %s

                You are on branch: %s
                Other workers are running in parallel. Check .agent-context/file-locks.json before writing any file.
                """.formatted(task.getTaskId(), task.getTitle(), task.getObjective(),
                task.getBoundaries(), task.getOutputFormat(), task.getAcceptanceCriteria(),
                deps, task.getComplexity(), branchName);
    }

    /**
     * Calls the LLM via LlmRouter with plain options (no tool callbacks).
     * Tools are described in the system prompt; the model outputs XML tool calls
     * which the agentic loop parses and executes manually.
     */
    private LlmRouter.LlmResponse callModel(List<Message> messages,
            String provider, String model,
            String runId, String agentId,
            int iteration, boolean justCompacted,
            String complexity) {
        String systemPrompt = loadPrompt(provider);
        int maxOut = budgetMonitor.maxOutputTokens(complexity);

        log.info("[Worker callModel] provider={} model={} complexity={} maxOut={}",
                provider, model, complexity, maxOut);

        // Enable Anthropic prompt caching on the system prompt.
        AnthropicCacheOptions cacheOpts = AnthropicCacheOptions.builder()
                .strategy(AnthropicCacheStrategy.SYSTEM_ONLY)
                .build();

        AnthropicChatOptions opts = AnthropicChatOptions.builder()
                .maxTokens(maxOut)
                .cacheOptions(cacheOpts)
                .build();

        // Sliding window: always send the first 2 messages (task assignment + initial
        // context)
        // and the most-recent HISTORY_WINDOW messages. This bounds input tokens per
        // call
        // regardless of iteration count, while compaction handles summarising the full
        // history.
        // The full `messages` list is preserved in memory for the compact() summariser.
        final int HISTORY_WINDOW = 18;
        List<Message> windowed;
        if (messages.size() <= HISTORY_WINDOW + 2) {
            windowed = messages;
        } else {
            windowed = new ArrayList<>(messages.subList(0, 2));
            windowed.addAll(messages.subList(messages.size() - HISTORY_WINDOW, messages.size()));
            log.debug("[Worker callModel] Sliding window: kept first 2 + last {} of {} messages",
                    HISTORY_WINDOW, messages.size());
        }

        List<Message> allMessages = new ArrayList<>();
        allMessages.add(new SystemMessage(systemPrompt));
        allMessages.addAll(windowed);

        Prompt prompt = new Prompt(allMessages, opts);
        return llmRouter.callWithFallbackTracked(provider, model, prompt, runId, agentId,
                FallbackMode.TASK_BOUNDARY, iteration, justCompacted);
    }

    private WorkerReport parseCompletionReport(String raw, String taskId,
            int inputTokens, int outputTokens,
            int cacheReadTokens, int cacheWriteTokens) {
        try {
            WorkerReport report = outputParser.parse(raw, WorkerReport.class);
            String status = report.getStatus();
            if (status == null || status.isBlank()) {
                status = "partial";
            }
            return WorkerReport.builder()
                    .taskId(report.getTaskId() != null ? report.getTaskId() : taskId)
                    .status(status)
                    .filesCreated(report.getFilesCreated())
                    .filesModified(report.getFilesModified())
                    .filesRead(report.getFilesRead())
                    .selfReview(report.getSelfReview())
                    .blockers(report.getBlockers())
                    .notesForReviewer(report.getNotesForReviewer())
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .cacheReadTokens(cacheReadTokens)
                    .cacheWriteTokens(cacheWriteTokens)
                    .build();
        } catch (JsonProcessingException e) {
            log.error("Failed to parse completion report: {}", e.getMessage());
            return WorkerReport.builder()
                    .taskId(taskId).status("partial")
                    .selfReview("Could not parse completion report: " + e.getMessage())
                    .inputTokens(inputTokens).outputTokens(outputTokens)
                    .cacheReadTokens(cacheReadTokens).cacheWriteTokens(cacheWriteTokens)
                    .build();
        }
    }

    private TaskDefinition parseTask(String json) {
        try {
            return mapper.readValue(json, TaskDefinition.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Cannot parse task definition", e);
        }
    }

    private int extractInputTokens(ChatResponse response) {
        try {
            return response.getMetadata().getUsage().getPromptTokens().intValue();
        } catch (Exception e) {
            log.warn("Could not extract input token count: {}", e.getMessage());
            return 0;
        }
    }

    private int extractOutputTokens(ChatResponse response) {
        try {
            return response.getMetadata().getUsage().getCompletionTokens().intValue();
        } catch (Exception e) {
            log.warn("Could not extract output token count: {}", e.getMessage());
            return 0;
        }
    }

    /** Anthropic cache_creation_input_tokens — billed at 1.25× input rate. */
    private int extractCacheWriteTokens(ChatResponse response) {
        try {
            Object val = response.getMetadata().get("cache_creation_input_tokens");
            return val instanceof Number n ? n.intValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Anthropic cache_read_input_tokens — billed at 0.10× input rate. */
    private int extractCacheReadTokens(ChatResponse response) {
        try {
            Object val = response.getMetadata().get("cache_read_input_tokens");
            return val instanceof Number n ? n.intValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private String safeJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return obj.toString();
        }
    }

    /**
     * Caps tool result text to avoid ballooning message history.
     * read_file and shell commands can produce 50k+ char outputs; keeping them
     * full in the message list wastes tokens on every subsequent API call.
     * The model can always re-read the file if it needs the full content.
     */
    private static final int MAX_RESULT_CHARS_FILE = 8_000; // read_file / list_files
    private static final int MAX_RESULT_CHARS_SHELL = 6_000; // run_command / run_build / run_tests
    private static final int MAX_RESULT_CHARS_DIFF = 5_000; // git_diff

    private String truncateResult(String toolName, String result) {
        if (result == null)
            return result;
        int limit = switch (toolName) {
            case "read_file", "list_files", "search_files" -> MAX_RESULT_CHARS_FILE;
            case "run_command", "run_build", "run_tests" -> MAX_RESULT_CHARS_SHELL;
            case "git_diff" -> MAX_RESULT_CHARS_DIFF;
            default -> Integer.MAX_VALUE; // write_file, git_commit etc. are always short
        };
        if (result.length() <= limit)
            return result;
        int tail = Math.min(1_500, limit / 4); // keep a tail snippet for context
        String head = result.substring(0, limit - tail);
        String end = result.substring(result.length() - tail);
        log.debug("[Worker] Truncated {} result: {} → {} chars (head={}+tail={})",
                toolName, result.length(), limit, limit - tail, tail);
        return head + "\n...[" + (result.length() - limit) +
                " chars omitted — use read_file for full content]...\n" + end;
    }

    /** Provider-aware prompt loading via ResourceLoader; fallback to worker.txt */
    private String loadPrompt(String provider) {
        String resourcePath = "classpath:prompts/worker-" + provider.toLowerCase() + ".txt";
        Resource providerPrompt = resourceLoader.getResource(resourcePath);
        try {
            if (providerPrompt.exists()) {
                return providerPrompt.getContentAsString(StandardCharsets.UTF_8);
            }
            return workerPromptFallback.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Cannot load worker prompt for provider: " + provider, e);
        }
    }

    /** Derive provider from model name; fall back to configured role provider. */
    private String resolveProvider(String model) {
        if (model == null)
            return props.getRoles().getWorker();
        String m = model.toLowerCase();
        if (m.contains("claude"))
            return "anthropic";
        if (m.contains("gpt"))
            return "openai";
        return props.getRoles().getWorker();
    }

    /**
     * Extracts Anthropic prompt-cache statistics from response metadata and returns
     * a human-readable note appended to the iteration log line.
     */
    private String buildCacheNote(ChatResponse response) {
        try {
            var meta = response.getMetadata();
            if (meta == null)
                return "";

            Object writeObj = meta.get("cache_creation_input_tokens");
            Object readObj = meta.get("cache_read_input_tokens");

            long cacheWrite = writeObj instanceof Number n ? n.longValue() : 0L;
            long cacheRead = readObj instanceof Number n ? n.longValue() : 0L;

            if (cacheWrite == 0 && cacheRead == 0)
                return "";

            if (cacheRead > 0 && cacheWrite > 0) {
                log.info("[Cache] HIT+WRITE — read={} tok (0.10x) write={} tok (1.25x)", cacheRead, cacheWrite);
                return " [⚡ cache hit: " + cacheRead + " + write: " + cacheWrite + " tok]";
            } else if (cacheRead > 0) {
                log.info("[Cache] HIT — {} tokens read from cache (0.10x billing)", cacheRead);
                return " [⚡ cache hit: " + cacheRead + " tok]";
            } else {
                log.info("[Cache] WRITE — {} tokens stored in cache (1.25x billing, reads will be 0.10x)", cacheWrite);
                return " [cache write: " + cacheWrite + " tok]";
            }
        } catch (Exception e) {
            return "";
        }
    }
}
