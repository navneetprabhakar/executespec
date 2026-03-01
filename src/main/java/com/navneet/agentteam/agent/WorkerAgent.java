package com.navneet.agentteam.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
import com.navneet.agentteam.tool.FileLockTool;
import com.navneet.agentteam.tool.FileSystemTool;
import com.navneet.agentteam.tool.GitTool;
import com.navneet.agentteam.tool.ShellTool;
import com.navneet.agentteam.tool.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
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
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkerAgent {

    @Qualifier("workerModel")
    private final ChatModel chatModel; // retained for Spring wiring — LlmRouter resolves models dynamically
    private final FileSystemTool fsTool;
    private final ShellTool shellTool;
    private final GitTool gitTool;
    private final FileLockTool lockTool;
    private final StateStore stateStore;
    private final ObjectMapper mapper;
    private final AgentProperties props;
    private final InboxService inboxService;
    private final TokenBudgetMonitor budgetMonitor;
    private final LlmRouter llmRouter;
    private final ResourceLoader resourceLoader;
    private final WorkerToolCallbacks toolCallbacksFactory;
    private final LlmOutputParser outputParser;

    @Value("classpath:prompts/worker.txt")
    private Resource workerPromptFallback;

    public WorkerReport execute(AgentTask task, String runId) {
        String taskId     = task.getTaskId();
        String model      = task.getAssignedModel() != null ? task.getAssignedModel() : "claude-sonnet-4-5-20250929";
        String provider   = resolveProvider(model);
        String agentId    = "worker-" + taskId;
        String complexity = task.getComplexity() != null ? task.getComplexity() : "MEDIUM";
        String branchName = "agent/" + runId.substring(0, 8) + "/" + taskId;

        log.info("Worker starting task [{}] on branch {} ({}:{})", taskId, branchName, provider, model);

        // Create branch inside this run's isolated workspace
        ToolResult branchResult = gitTool.createBranch(runId, branchName);
        if (!branchResult.isOk()) {
            log.warn("Branch creation warning for {}: {}", branchName, branchResult.value());
        }
        stateStore.logMessage(runId, "harness", agentId,
            "TASK_ASSIGNMENT", "Starting: " + task.getTitle(), 0);

        // Load task definition
        TaskDefinition taskDef = parseTask(task.getTaskJson());

        // Build tool callbacks for this task (closed over runId + taskId)
        List<ToolCallback> toolCallbacks = toolCallbacksFactory.createCallbacks(
            runId, taskId, fsTool, shellTool, gitTool, lockTool, inboxService, stateStore);

        // Per-task conversation history (WorkerAgent is a Spring singleton — no instance fields)
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(buildInitialUserMessage(taskDef, branchName)));

        int     totalInputTokens    = 0;
        int     totalOutputTokens   = 0;
        int     iteration           = 0;
        int     lastCallInputTokens = 0;
        boolean justCompacted       = false;

        // ── AGENTIC LOOP ──────────────────────────────────────────────────────
        try {
            while (iteration < 50) {  // hard cap on iterations

                // Drain inbox at start of each iteration; inject as user turn if non-null
                String inboxContent = inboxService.drainInbox(agentId, runId);
                if (inboxContent != null) {
                    messages.add(new UserMessage(inboxContent));
                    log.debug("[Worker {}] Injected inbox messages into context", taskId);
                }

                // Tier 5: record activity before LLM call (for stuck detection)
                stateStore.updateLastActivity(taskId);
                stateStore.appendTaskLog(taskId, "LLM_REQUEST_SENT");

                LlmRouter.LlmResponse lr = callModel(messages, toolCallbacks, provider, model,
                    runId, agentId, iteration, justCompacted, complexity);
                // Track the provider that actually succeeded so future iterations
                // start from the working provider instead of retrying a rate-limited one.
                provider = lr.provider();

                iteration++;

                AssistantMessage assistantMsg = (AssistantMessage) lr.response().getResult().getOutput();
                messages.add(assistantMsg);

                int inputTok  = extractInputTokens(lr.response());
                int outputTok = extractOutputTokens(lr.response());

                lastCallInputTokens  = inputTok;
                totalInputTokens    += inputTok;
                totalOutputTokens   += outputTok;
                justCompacted        = false;

                stateStore.logMessage(runId, agentId, "state",
                    "TOOL_CALL", "Iteration " + iteration +
                    " (" + inputTok + "/" + outputTok + " tokens)" +
                    buildCacheNote(lr.response()), inputTok + outputTok);

                // Check for native tool calls
                List<AssistantMessage.ToolCall> toolCalls = assistantMsg.getToolCalls();

                if (toolCalls == null || toolCalls.isEmpty()) {
                    // No tool calls — check if it's a completion report
                    String assistantContent = assistantMsg.getText();
                    if (assistantContent != null &&
                        assistantContent.contains("\"task_id\"") &&
                        assistantContent.contains("\"status\"")) {
                        return parseCompletionReport(assistantContent, taskId,
                            totalInputTokens, totalOutputTokens);
                    }
                    messages.add(new UserMessage(
                        "Continue working on the task. Use tools to make progress, " +
                        "or output your completion JSON report if done."));
                    continue;
                }

                // Execute native tool calls and collect responses
                List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
                for (AssistantMessage.ToolCall tc : toolCalls) {
                    log.debug("  Tool: {}", tc.name());
                    stateStore.updateLastActivity(taskId);
                    stateStore.appendTaskLog(taskId, "TOOL_CALL:" + tc.name());
                    ToolResult result = dispatchNativeTool(tc, taskId, runId);
                    toolResponses.add(new ToolResponseMessage.ToolResponse(
                        tc.id(), tc.name(), result.value()));

                    // Auto-commit after file writes to preserve progress
                    if ("write_file".equals(tc.name()) || "edit_file".equals(tc.name())) {
                        try {
                            Map<String, Object> argMap = mapper.readValue(
                                tc.arguments(), new TypeReference<>() {});
                            String path = argMap.get("path") != null
                                ? argMap.get("path").toString() : "file";
                            gitTool.commit(runId, "wip(" + taskId + "): " + tc.name() + " " + path);
                        } catch (Exception e) {
                            log.debug("Auto-commit skipped: {}", e.getMessage());
                        }
                    }
                }
                messages.add(ToolResponseMessage.builder().responses(toolResponses).build());

                // Budget monitoring (compaction / hard-stop)
                BudgetStatus budget = budgetMonitor.check(model, complexity, lastCallInputTokens);
                switch (budget.status()) {
                    case HARD_STOP -> {
                        log.warn("Hard stop threshold reached for task {} ({}% context)",
                            taskId, (int)(budget.usagePct() * 100));
                        stateStore.recordCompaction(taskId, "hard-stop");
                        return WorkerReport.partial(taskId,
                            "Hard stopped at " + (int)(budget.usagePct() * 100) + "% context usage. Resume required.",
                            totalInputTokens, totalOutputTokens);
                    }
                    case COMPACT -> {
                        log.info("Compacting context for task {} at {}% usage", taskId,
                            (int)(budget.usagePct() * 100));
                        String[] compactProvider = {provider};
                        messages = compact(messages, taskDef, taskId, runId,
                            compactProvider, model, agentId, complexity);
                        provider = compactProvider[0]; // track effective provider from compaction
                        lastCallInputTokens = 0;
                        justCompacted = true;
                    }
                    case WARN ->
                        log.warn("Context at {}% for task {} — compaction approaching",
                            (int)(budget.usagePct() * 100), taskId);
                    case OK -> { /* nothing */ }
                }
            }
        } catch (MidTaskProviderFailureException e) {
            // Kept for safety; with the new guardrail-free design this should not be thrown.
            log.error("Mid-task provider failure for task {}: {}", taskId, e.getMessage());
            return WorkerReport.blocked(taskId, e.getMessage(), totalInputTokens, totalOutputTokens);
        } catch (LlmRouter.ProviderUnavailableException e) {
            // All providers in the chain exhausted (e.g. both anthropic and openai rate-limited
            // even after the 15 s sleep). Block the task so recovery can resubmit it later.
            log.error("All providers exhausted for task {}: {}", taskId, e.getMessage());
            return WorkerReport.blocked(taskId, "All providers exhausted: " + e.getMessage(),
                totalInputTokens, totalOutputTokens);
        }
        // ── END AGENTIC LOOP ──────────────────────────────────────────────────

        log.warn("Worker for task {} hit iteration cap without completing", taskId);
        return WorkerReport.partial(taskId,
            "Hit 50-iteration cap without producing completion report.",
            totalInputTokens, totalOutputTokens);
    }

    // ── Tool Dispatch (native tool call format) ───────────────────────────────

    private ToolResult dispatchNativeTool(AssistantMessage.ToolCall tc, String taskId, String runId) {
        try {
            Map<String, Object> args = (tc.arguments() != null && !tc.arguments().isBlank())
                ? mapper.readValue(tc.arguments(), new TypeReference<>() {})
                : Map.of();

            return switch (tc.name()) {
                case "read_file" -> fsTool.readFile(runId, str(args, "path"));

                case "write_file" -> {
                    String path = str(args, "path");
                    boolean claimed = lockTool.claim(path, runId, taskId);
                    if (!claimed) {
                        String owner = lockTool.getOwner(path).orElse("unknown");
                        inboxService.sendFileLockConflict(runId,
                            "worker-" + taskId, "worker-" + owner, path);
                        stateStore.logMessage(runId, "worker-" + taskId, "worker-" + owner,
                            "COORDINATION", "Conflict on " + path + " -- owner: " + owner, 0);
                        yield ToolResult.err("File " + path + " is locked by worker-" + owner +
                            ". Coordination message sent. Choose a different file or wait.");
                    }
                    yield fsTool.writeFile(runId, path, str(args, "content"));
                }

                case "edit_file" -> {
                    String path = str(args, "path");
                    boolean claimed = lockTool.claim(path, runId, taskId);
                    if (!claimed) yield ToolResult.err("File locked: " + path);
                    yield fsTool.editFile(runId, path, str(args, "old_str"), str(args, "new_str"));
                }

                case "list_files" -> fsTool.listDirectory(runId,
                    args.containsKey("path") ? str(args, "path") : ".");
                case "search_files"   -> fsTool.searchFiles(runId, str(args, "pattern"),
                    args.containsKey("directory") ? str(args, "directory") : ".");
                case "run_command"    -> shellTool.run(runId, str(args, "command"), 120);
                case "run_build"      -> shellTool.run(runId, str(args, "command"), 300);
                case "run_tests"      -> shellTool.run(runId, str(args, "command"), 300);
                case "git_status"     -> gitTool.status(runId);
                case "git_diff"       -> gitTool.diff(runId);
                case "git_commit"     -> gitTool.commit(runId,
                    args.containsKey("message") ? str(args, "message") : "wip");

                default -> ToolResult.err("Unknown tool: " + tc.name());
            };
        } catch (Exception e) {
            return ToolResult.err("Tool dispatch error for " + tc.name() + ": " + e.getMessage());
        }
    }

    private String str(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val != null ? val.toString() : null;
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

        // No tools needed for the compaction summary call — pass empty callbacks
        LlmRouter.LlmResponse summaryLr = callModel(summaryMessages, List.of(), provider, model,
            runId, agentId, 0, false, complexity);
        effectiveProvider[0] = summaryLr.provider(); // propagate effective provider to caller
        String summary = summaryLr.response().getResult().getOutput().getText();

        // Write checkpoint to the run's workspace
        String checkpointPath    = ".agent-context/worker-" + taskId + "-checkpoint.md";
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

            Continue from where you left off. Use tools to complete the remaining work.
            """.formatted(safeJson(task), summary, fileContext)));

        log.info("Context compacted for task {}. Checkpoint: {}", taskId, checkpointPath);
        return fresh;
    }

    private String rebuildFileContext(String runId) {
        ToolResult listing = fsTool.searchFiles(runId, "", "src");
        return "Current src/ structure:\n" + listing.value();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildInitialUserMessage(TaskDefinition task, String branchName) {
        String deps = (task.getDependsOn() == null || task.getDependsOn().isEmpty())
            ? "none" : String.join(", ", task.getDependsOn());
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
     * Calls the LLM via LlmRouter with native tool-use options.
     * Tool callbacks are registered as JSON-schema definitions (internalToolExecutionEnabled=false
     * so the agentic loop receives tool calls and dispatches them manually).
     */
    private LlmRouter.LlmResponse callModel(List<Message> messages, List<ToolCallback> toolCallbacks,
                                             String provider, String model,
                                             String runId, String agentId,
                                             int iteration, boolean justCompacted,
                                             String complexity) {
        String systemPrompt = loadPrompt(provider);
        int maxOut = budgetMonitor.maxOutputTokens(complexity);

        var optBuilder = AnthropicChatOptions.builder()
            .maxTokens(maxOut)
            .internalToolExecutionEnabled(false);
        if (!toolCallbacks.isEmpty()) {
            optBuilder.toolCallbacks(toolCallbacks);
        }

        List<Message> allMessages = new ArrayList<>();
        allMessages.add(new SystemMessage(systemPrompt));
        allMessages.addAll(messages);

        Prompt prompt = new Prompt(allMessages, optBuilder.build());
        return llmRouter.callWithFallbackTracked(provider, model, prompt, runId, agentId,
            FallbackMode.TASK_BOUNDARY, iteration, justCompacted);
    }

    private WorkerReport parseCompletionReport(String raw, String taskId,
                                                int inputTokens, int outputTokens) {
        try {
            WorkerReport report = outputParser.parse(raw, WorkerReport.class);
            return WorkerReport.builder()
                .taskId(report.getTaskId())
                .status(report.getStatus())
                .filesCreated(report.getFilesCreated())
                .filesModified(report.getFilesModified())
                .filesRead(report.getFilesRead())
                .selfReview(report.getSelfReview())
                .blockers(report.getBlockers())
                .notesForReviewer(report.getNotesForReviewer())
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .build();
        } catch (JsonProcessingException e) {
            log.error("Failed to parse completion report: {}", e.getMessage());
            return WorkerReport.builder()
                .taskId(taskId).status("partial")
                .selfReview("Could not parse completion report: " + e.getMessage())
                .inputTokens(inputTokens).outputTokens(outputTokens)
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

    private String safeJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return obj.toString();
        }
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
        if (model == null) return props.getRoles().getWorker();
        String m = model.toLowerCase();
        if (m.contains("claude"))  return "anthropic";
        if (m.contains("gpt"))     return "openai";
        if (m.contains("gemini"))  return "gemini";
        return props.getRoles().getWorker();
    }

    /**
     * Extracts Anthropic prompt-cache statistics from the response metadata and returns
     * a human-readable note appended to the iteration log line.
     *
     * <p>Anthropic returns {@code cache_creation_input_tokens} (first call — cache write)
     * and {@code cache_read_input_tokens} (subsequent calls — cache hit) in the usage block.
     * Spring AI 1.0 surfaces these as entries in the generic {@code ChatResponseMetadata} map.
     * When absent (e.g. provider was OpenAI) the method returns an empty string silently.
     */
    private String buildCacheNote(ChatResponse response) {
        try {
            var meta = response.getMetadata();
            if (meta == null) return "";

            Object writeObj = meta.get("cache_creation_input_tokens");
            Object readObj  = meta.get("cache_read_input_tokens");

            long cacheWrite = writeObj instanceof Number n ? n.longValue() : 0L;
            long cacheRead  = readObj  instanceof Number n ? n.longValue() : 0L;

            if (cacheWrite == 0 && cacheRead == 0) return "";

            if (cacheRead > 0) {
                log.debug("[AnthropicCache] Cache HIT: {} tokens read from cache", cacheRead);
                return " [⚡ cache hit: " + cacheRead + " tok]";
            } else {
                log.debug("[AnthropicCache] Cache WRITE: {} tokens stored in cache", cacheWrite);
                return " [cache write: " + cacheWrite + " tok]";
            }
        } catch (Exception e) {
            // Non-fatal — never disrupt the worker loop for cache telemetry
            return "";
        }
    }
}
