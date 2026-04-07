package com.navneet.executespec.agent;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.router.ResolvedModel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRouter.FallbackMode;
import com.navneet.executespec.agent.LlmRouter.MidTaskProviderFailureException;
import com.navneet.executespec.agent.TokenBudgetMonitor.BudgetStatus;
import com.navneet.executespec.agent.model.TaskDefinition;
import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.state.InboxService;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.tool.FileSystemTool;
import com.navneet.executespec.tool.GitTool;
import com.navneet.executespec.tool.ShellTool;
import com.navneet.executespec.tool.ToolResult;
import com.navneet.executespec.tool.WorkspaceContext;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatOptions;
// AnthropicApi import removed — Anthropic uses PATH B (XML prompt-based tool calling)
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final ObservationRegistry observationRegistry;
    private final SseBroadcaster sseBroadcaster;
    private final com.navneet.executespec.harness.UiStyleResolver uiStyleResolver;
    private final com.navneet.executespec.router.ModelResolver modelResolver;
    private final com.navneet.executespec.harness.RunBudgetTracker runBudgetTracker;
    private final com.navneet.executespec.harness.RunCostBudgetService runCostBudgetService;
    private final ModelRegistry modelRegistry;

    @Value("classpath:prompts/worker.txt")
    private Resource workerPromptFallback;


    /**
     * Parsed representation of a single tool call extracted from assistant text.
     */
    private record ParsedToolCall(String name, String argsJson) {
    }

    private static final Pattern TOOL_CALL_PATTERN = Pattern.compile("<tool_call>(.*?)</tool_call>", Pattern.DOTALL);
    /** Matches fabricated <tool_result> blocks that models may self-generate (self-play). */
    private static final Pattern FABRICATED_TOOL_RESULT_PATTERN = Pattern.compile(
            "<tool_result[^>]*>.*?</tool_result>", Pattern.DOTALL);
    /** Inject a progress checkpoint every this many minutes. */
    private static final int CHECKPOINT_INTERVAL_MINUTES = 5;
    /** Inject completion urgency prompt when this many minutes remain. */
    private static final int URGENCY_THRESHOLD_MINUTES = 2;
    /** Check for stall (no file writes) and escalate model after this many minutes. */
    private static final int STALL_CHECK_MINUTES = 5;
    /** Hard cap on tool-call iterations — circuit breaker when prompt-level limit (20) is ignored.
     *  Set high enough that truncation recovery (2 continues + 2 skeleton recoveries) doesn't
     *  exhaust the budget. Each recovery burns ~2-3 tool calls, so 35 gives ~10 recovery slots. */
    private static final int HARD_ITERATION_LIMIT = 35;
    /** Reduced iteration limit for subtasks — a subtask writes ONE file and needs at most
     *  a few reads + 1 writeFile. 10 iterations is generous for single-file tasks. */
    private static final int SUBTASK_ITERATION_LIMIT = 10;
    /** Max auto-continue attempts when LLM response is truncated (hit maxTokens). */
    private static final int MAX_CONTINUATIONS = 2;
    /** Max skeleton recovery attempts before escalating model anyway. */
    private static final int MAX_SKELETON_RECOVERIES = 2;
    /** Recovery prompt injected when truncation persists after MAX_CONTINUATIONS exhausted. */
    private static final String SKELETON_RECOVERY_PROMPT =
            "STOP — your output keeps getting truncated because it exceeds the token limit. " +
            "You MUST change strategy immediately:\n\n" +
            "1. Write a MINIMAL SKELETON of the file first using writeFile — include the structure " +
            "(imports, class/function signatures, key comments like `// TODO: implement`) but leave " +
            "method bodies as stubs or single-line placeholders.\n\n" +
            "2. Then use editFile to replace each placeholder with real content, ONE SECTION AT A TIME. " +
            "Keep each editFile call under 150 lines.\n\n" +
            "3. Do NOT attempt to write the entire file in one writeFile call again — that is what " +
            "caused the truncation.\n\n" +
            "Start now: call writeFile with the skeleton version of the file you were working on.";

    public WorkerReport execute(AgentTask task, String runId) {
        String taskId = task.getTaskId();
        String provider = props.getRoles().getWorker(); // default provider from config
        String model = task.getAssignedModel() != null
                ? task.getAssignedModel()
                : modelRegistry.workhouseModelFor(provider);
        provider = resolveProvider(model);
        String agentId = "worker-" + taskId;
        String complexity = task.getComplexity() != null ? task.getComplexity() : "MEDIUM";
        String branchName = "agent/" + runId.substring(0, 8) + "/" + taskId;

        int taskDurationMinutes = props.getConcurrency().getMaxTaskDurationMinutes();
        log.info("Worker starting task [{}] on branch {} ({}:{}) complexity={} timeLimit={}min",
                taskId, branchName, provider, model, complexity, taskDurationMinutes);

        stateStore.logMessage(runId, "harness", agentId,
                "TASK_ASSIGNMENT", "Starting: " + task.getTitle(), 0);

        // Load task definition
        TaskDefinition taskDef = parseTask(task.getTaskJson());

        // ── Workspace setup: worktree (normal) or main branch (single-shot) ──
        String workDir;
        if (SKIP_WORKTREE.get()) {
            // Skip worktree creation: either subtask mode (OVERRIDE_WORK_DIR) or single-shot mode
            String overrideDir = OVERRIDE_WORK_DIR.get();
            workDir = overrideDir != null ? overrideDir : props.getWorkspace() + "/" + runId;
            WorkspaceContext.set(runId, workDir);
            WorkspaceContext.setTaskId(taskId);
            if (overrideDir == null) branchName = "main";
            log.info("[Worker-{}] Task [{}] operating in {}", overrideDir != null ? "Subtask" : "MainBranch",
                    taskId, workDir);
        } else {
            // Normal mode: create isolated git worktree with dependency branches
            List<String> depBranches = new ArrayList<>();
            if (taskDef.getDependsOn() != null) {
                taskDef.getDependsOn().forEach(depId ->
                    depBranches.add("agent/" + runId.substring(0, 8) + "/" + depId));
            }
            workDir = gitTool.createWorktree(runId, taskId, branchName, depBranches);
            WorkspaceContext.set(runId, workDir);
            WorkspaceContext.setTaskId(taskId);
        }

        // Apply complexity override (last line of defense against planner over-assignment)
        String projectType = readProjectType(workDir);
        int ownedFilesCount = taskDef.getOwnedFiles() != null ? taskDef.getOwnedFiles().size() : 0;
        complexity = budgetMonitor.resolveEffectiveComplexity(complexity, model, projectType, ownedFilesCount, taskId);

        log.info("Worker starting task [{}] on branch {} in {} ({}:{}) complexity={}",
                taskId, branchName, workDir, provider, model, complexity);

        // Per-task conversation history (WorkerAgent is a Spring singleton — no
        // instance fields)
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(buildInitialUserMessage(taskDef, branchName, runId)));

        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        int totalCacheReadTokens = 0; // Anthropic cache_read_input_tokens (billed at 0.10x)
        int totalCacheWriteTokens = 0; // Anthropic cache_creation_input_tokens (billed at 1.25x)
        int iteration = 0;
        int lastCallInputTokens = 0;
        boolean justCompacted = false;
        int lastCompactedIteration = -999;
        int fileWritesSinceCheck = 0;  // tracks file writes for adaptive extension
        int totalFileWrites = 0;      // total writeFile/editFile dispatches for completion validation
        int consecutiveNullResponses = 0;
        final int MAX_CONSECUTIVE_NULL_RESPONSES = 3;
        int toolCallCount = 0;  // total tool calls dispatched — for hard iteration limit
        boolean isSubtask = task.getParentTaskId() != null;
        int effectiveIterationLimit = isSubtask ? SUBTASK_ITERATION_LIMIT : HARD_ITERATION_LIMIT;
        int continuations = 0;  // truncation auto-continue count
        int consecutiveNonJsonOutputs = 0; // consecutive non-tool, non-JSON outputs
        int skeletonRecoveries = 0; // skeleton recovery injection count
        int fabricatedCompletionCount = 0; // fabricated completion rejections — max 2 before FAILED
        final int MAX_FABRICATED_COMPLETIONS = 2;
        int configuredMaxOutput = budgetMonitor.maxOutputTokens(model, complexity); // for calibration tracking
        boolean midTaskRecoveryAttempted = false; // limit to 1 recovery attempt per task

        // ── AGENTIC LOOP (time-based) ────────────────────────────────────────
        Instant deadline = Instant.now().plus(Duration.ofMinutes(taskDurationMinutes));
        int safetyIterationCap = props.getConcurrency().getSafetyIterationCap();
        Instant lastCheckpointTime = Instant.now();
        Instant lastStallCheckTime = Instant.now();
        boolean urgencyInjected = false;
        try { // outer try: always clear WorkspaceContext on exit
        try {
            while (Instant.now().isBefore(deadline) && iteration < safetyIterationCap) {

                Observation iterObs = Observation.createNotStarted("executespec.task.iteration", observationRegistry)
                        .lowCardinalityKeyValue("task.id", taskId)
                        .lowCardinalityKeyValue("run.id", runId)
                        .lowCardinalityKeyValue("iteration.number", String.valueOf(iteration))
                        .lowCardinalityKeyValue("iteration.model", model)
                        .lowCardinalityKeyValue("iteration.provider", provider)
                        .start();
                try {

                // Drain inbox at start of each iteration; inject as user turn if non-null
                String inboxContent = inboxService.drainInbox(agentId, runId);
                if (inboxContent != null) {
                    messages.add(new UserMessage(inboxContent));
                    log.debug("[Worker {}] Injected inbox messages into context", taskId);
                }

                long elapsedMinutes = Duration.between(deadline.minus(Duration.ofMinutes(taskDurationMinutes)), Instant.now()).toMinutes();
                long remainingMinutes = Duration.between(Instant.now(), deadline).toMinutes();

                // ── Progress checkpoint: re-inject task context every N minutes ──
                if (iteration > 0 && Duration.between(lastCheckpointTime, Instant.now()).toMinutes() >= CHECKPOINT_INTERVAL_MINUTES) {
                    messages.add(new UserMessage(buildProgressCheckpoint(taskDef, elapsedMinutes, taskDurationMinutes)));
                    log.info("[Worker {}] Injected progress checkpoint at {}min elapsed (iteration {})", taskId, elapsedMinutes, iteration);
                    lastCheckpointTime = Instant.now();
                }

                // ── Stall detection: if no file writes in N minutes, escalate model ──
                // Truncation-aware: if recent stall is caused by truncation (continuations > 0),
                // inject recovery instruction instead of escalating — a bigger model won't fix
                // a token-limit problem, it just costs more.
                if (iteration > 0 && Duration.between(lastStallCheckTime, Instant.now()).toMinutes() >= STALL_CHECK_MINUTES) {
                    if (fileWritesSinceCheck == 0) {
                        if (continuations > 0 && skeletonRecoveries < MAX_SKELETON_RECOVERIES) {
                            // Stall is likely truncation-caused — try skeleton recovery before escalating
                            skeletonRecoveries++;
                            log.info("[Worker {}] Stalled but {} truncation(s) detected — injecting skeleton recovery " +
                                    "({}/{}) instead of model escalation", taskId, continuations,
                                    skeletonRecoveries, MAX_SKELETON_RECOVERIES);
                            stateStore.appendTaskLog(taskId, "STALL_TRUNCATION_RECOVERY:" + skeletonRecoveries);
                            messages.add(new UserMessage(SKELETON_RECOVERY_PROMPT));
                        } else {
                            String escalatedModel = escalateModel(model, runId);
                            if (!escalatedModel.equals(model)) {
                                log.warn("[Worker {}] Stalled (no file writes in {}min) — escalating {} → {}",
                                        taskId, STALL_CHECK_MINUTES, model, escalatedModel);
                                stateStore.logMessage(runId, agentId, "state", "MODEL_ESCALATED",
                                        "Escalating model to " + escalatedModel + " (stalled — no file writes in " + STALL_CHECK_MINUTES + " min)", 0);
                                model = escalatedModel;
                                provider = resolveProvider(model);
                            }
                        }
                    } else {
                        log.info("[Worker {}] Progress check: {} file writes in last {}min", taskId, fileWritesSinceCheck, STALL_CHECK_MINUTES);
                    }
                    fileWritesSinceCheck = 0;
                    lastStallCheckTime = Instant.now();
                }

                // ── Forced completion: approaching time limit ──
                if (!urgencyInjected && remainingMinutes <= URGENCY_THRESHOLD_MINUTES) {
                    messages.add(new UserMessage(buildCompletionUrgencyPrompt(taskDef, elapsedMinutes, taskDurationMinutes)));
                    log.warn("[Worker {}] Injected completion urgency prompt at {}min elapsed (~{}min remaining)",
                            taskId, elapsedMinutes, remainingMinutes);
                    urgencyInjected = true;
                }

                // ── Pre-call token estimation: compact early if projected to exceed threshold ──
                if (!justCompacted && messages.size() > 30) {
                    int estimatedTokens = estimateInputTokens(messages);
                    int limit = budgetMonitor.contextLimitFor(model);
                    double estimatedPct = (double) estimatedTokens / limit;
                    double compactPct = props.getCompaction().getCompactPct()
                            + ("HIGH".equalsIgnoreCase(complexity) ? 0.05 : 0.0);
                    if (estimatedPct >= compactPct) {
                        log.info("[Worker {}] Pre-call estimate {}% >= compact threshold — compacting before LLM call",
                                taskId, (int) (estimatedPct * 100));
                        String[] compactProvider = { provider };
                        String[] compactModel   = { model };
                        messages = compact(messages, taskDef, taskId, runId,
                                compactProvider, compactModel, agentId, complexity);
                        provider = compactProvider[0];
                        model    = compactModel[0];
                        lastCallInputTokens = 0;
                        justCompacted = true;
                        lastCompactedIteration = iteration;
                    }
                }

                // Tier 5: record activity before LLM call (for stuck detection)
                stateStore.updateLastActivity(taskId);

                // Degenerate loop breaker: after 5+ consecutive text-only responses,
                // the model is stuck in a "I'll call X" / "NO DO IT" cycle.
                // Trim the degenerate (text + escalation) pairs to break the pattern.
                // tool_choice is already set to required/any, so the API will enforce
                // tool use — but the accumulated bad history can confuse the model.
                if (consecutiveNonJsonOutputs >= 5) {
                    int pairsToTrim = Math.min(consecutiveNonJsonOutputs - 2, 10);
                    int msgsToRemove = pairsToTrim * 2; // each pair = assistant text + user escalation
                    if (msgsToRemove > 0 && messages.size() > msgsToRemove + 4) {
                        int trimStart = messages.size() - msgsToRemove;
                        messages.subList(trimStart, messages.size()).clear();
                        log.info("[Worker {}] Trimmed {} degenerate message pairs ({} msgs) to break text-only loop",
                                taskId, pairsToTrim, msgsToRemove);
                        stateStore.appendTaskLog(taskId, "DEGENERATE_LOOP_TRIM:" + pairsToTrim + "_pairs");
                        messages.add(new UserMessage(
                                "Call a tool NOW. Do not respond with text."));
                    }
                }

                LlmRouter.LlmResponse lr = callModel(messages, provider, model,
                        runId, taskId, agentId, iteration, justCompacted, complexity);
                provider = lr.provider(); // track the provider that actually succeeded
                model    = lr.model();    // track the model that actually served the call

                // Record activity AFTER LLM call returns so stuck detection has accurate timing
                stateStore.updateLastActivity(taskId);

                iteration++;

                // Mid-task cost enforcement: check every 5 LLM calls
                if (iteration % 5 == 0 && runCostBudgetService.isOverBudget(runId)) {
                    String tier = runCostBudgetService.getRunTier(runId);
                    Double budget = runCostBudgetService.getBudget(runId);
                    log.warn("[Worker {}] Mid-task cost budget exceeded — stopping (tier={}, budget=${})",
                            taskId, tier, budget != null ? String.format("%.2f", budget) : "?");
                    stateStore.logMessage(runId, agentId, "state", "BUDGET_EXCEEDED",
                            "Task stopped mid-execution: run cost exceeded $" +
                            (budget != null ? String.format("%.2f", budget) : "?") + " " + tier + " budget", 0);
                    sseBroadcaster.emit(runId, RunEvent.budgetExceeded(runId, tier, budget != null ? budget : 3.0));
                    break;
                }

                // getResult() can return null when the provider sends an empty completion
                // (e.g. rate-limit response, content-filter rejection) that LlmRouter
                // passed through without throwing. Guard here so the loop can retry.
                if (lr.response().getResult() == null) {
                    consecutiveNullResponses++;
                    log.warn("[Worker {}] Null ChatResponse.getResult() on iteration {} ({}/{} consecutive nulls)",
                            taskId, iteration, consecutiveNullResponses, MAX_CONSECUTIVE_NULL_RESPONSES);
                    if (consecutiveNullResponses >= MAX_CONSECUTIVE_NULL_RESPONSES) {
                        log.error("[Worker {}] {} consecutive null responses — aborting task (provider may be down)",
                                taskId, consecutiveNullResponses);
                        stateStore.logMessage(runId, agentId, "state", "WORKER_ABORT",
                                "Task aborted: " + consecutiveNullResponses + " consecutive null LLM responses", 0);
                        break;
                    }
                    continue;
                }
                consecutiveNullResponses = 0; // reset on successful response
                runBudgetTracker.recordCall(runId);
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

                // Record token usage on the current iteration observation
                iterObs.highCardinalityKeyValue("input_tokens", String.valueOf(inputTok));
                iterObs.highCardinalityKeyValue("output_tokens", String.valueOf(outputTok));
                iterObs.highCardinalityKeyValue("cache_read_tokens", String.valueOf(cacheReadTok));
                iterObs.highCardinalityKeyValue("cache_write_tokens", String.valueOf(cacheWriteTok));

                String cacheNote = buildCacheNote(lr.response());
                log.debug("[Worker {}] Iteration {} ({}/{} tokens){}",
                        taskId, iteration, inputTok, outputTok, cacheNote);

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

                // ── TRUNCATION AUTO-CONTINUE ─────────────────────────────────────────
                // If the LLM hit maxTokens, the response is truncated mid-output.
                // Instead of letting broken code flow to compiler, auto-continue up to
                // MAX_CONTINUATIONS times.
                String finishReason = null;
                try {
                    finishReason = lr.response().getResult().getMetadata().getFinishReason();
                } catch (Exception ignored) { /* metadata may be null for some providers */ }
                if (isTruncatedResponse(finishReason)) {
                    // Salvage: dispatch any COMPLETE tool calls before the truncation point.
                    // Without this, the model thinks it wrote files but the harness never dispatched them.
                    int salvaged = 0;
                    if (assistantText != null && !assistantText.isBlank()) {
                        String salvageText = FABRICATED_TOOL_RESULT_PATTERN.matcher(assistantText).replaceAll("");
                        List<ParsedToolCall> salvageCalls = parseXmlToolCalls(salvageText);
                        if (salvageCalls.isEmpty()) salvageCalls = parseJsonToolCalls(salvageText);
                        for (ParsedToolCall tc : salvageCalls) {
                            try {
                                dispatchTool(tc, runId, taskId);
                                if (isFileWriteTool(tc.name())) { fileWritesSinceCheck++; totalFileWrites++; }
                                toolCallCount++;
                                salvaged++;
                            } catch (Exception salvageEx) {
                                log.debug("[Worker {}] Salvage dispatch failed for {}: {}", taskId, tc.name(), salvageEx.getMessage());
                            }
                        }
                        if (salvaged > 0) {
                            log.info("[Worker {}] Salvaged {} complete tool call(s) from truncated response " +
                                    "(totalFileWrites={})", taskId, salvaged, totalFileWrites);
                        }
                    }

                    continuations++;
                    if (continuations <= MAX_CONTINUATIONS) {
                        log.warn("[Worker {}] Iteration {} — response truncated (finishReason={}). " +
                                "Auto-continuing ({}/{})", taskId, iteration, finishReason,
                                continuations, MAX_CONTINUATIONS);
                        stateStore.appendTaskLog(taskId, "TRUNCATION_CONTINUE:" + continuations);
                        // Critical: tell the model which tool calls executed and which didn't.
                        String salvageNote = salvaged > 0
                                ? salvaged + " tool call(s) from your response WERE executed. "
                                : "NONE of your tool calls were executed — they were all cut off by truncation. ";
                        messages.add(new UserMessage(
                                "Your previous response was TRUNCATED (hit output token limit). " +
                                salvageNote +
                                "Any writeFile/editFile that was cut off mid-content was NOT executed.\n\n" +
                                "IMPORTANT: Write ONE file per response to avoid truncation. " +
                                "Call writeFile for the next file now."));
                        continue; // next iteration — send continuation request
                    } else {
                        // Skeleton recovery: continuations exhausted, response still truncating.
                        // Instead of letting broken concatenated output flow to compiler,
                        // instruct the model to use a skeleton-then-fill strategy.
                        skeletonRecoveries++;
                        log.warn("[Worker {}] Iteration {} — truncation persists after {} continuations. " +
                                "Injecting skeleton recovery ({}/{}).", taskId, iteration, MAX_CONTINUATIONS,
                                skeletonRecoveries, MAX_SKELETON_RECOVERIES);
                        stateStore.appendTaskLog(taskId, "SKELETON_RECOVERY_INJECTED:" + skeletonRecoveries);
                        messages.add(new UserMessage(SKELETON_RECOVERY_PROMPT));
                        continue; // next iteration — model should switch to skeleton strategy
                    }
                }

                // ── PATH A: Native tool calls (OpenAI + Anthropic) ──────────────────
                // When using native function calling, tool calls are returned as structured
                // objects in the response (not in text). We dispatch them manually and send
                // results back as ToolResponseMessage (required by the API).
                if (assistantMsg.hasToolCalls() && !assistantMsg.getToolCalls().isEmpty()) {
                    log.info("[Worker {}] Iteration {} — {} native tool call(s)",
                            taskId, iteration, assistantMsg.getToolCalls().size());

                    // ── Dispatch non-taskComplete tools FIRST ──────────────────────
                    // This ensures totalFileWrites is up-to-date before taskComplete
                    // validation runs (fixes false "fabricated completion" when writeFile
                    // and taskComplete arrive in the same tool-call batch).
                    AssistantMessage.ToolCall pendingTaskComplete = null;
                    List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                    for (AssistantMessage.ToolCall tc : assistantMsg.getToolCalls()) {
                        if ("taskComplete".equals(tc.name())) {
                            pendingTaskComplete = tc; // defer until after other tools
                            continue;
                        }
                        Observation toolObs = Observation.createNotStarted("executespec.tool.dispatch", observationRegistry)
                                .lowCardinalityKeyValue("tool.name", tc.name())
                                .lowCardinalityKeyValue("task.id", taskId)
                                .lowCardinalityKeyValue("run.id", runId)
                                .start();
                        try {
                            String result = dispatchTool(new ParsedToolCall(tc.name(), tc.arguments()), runId, taskId);
                            if (isFileWriteTool(tc.name())) { fileWritesSinceCheck++; totalFileWrites++; }
                            responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), result));
                        } finally {
                            toolObs.stop();
                        }
                    }
                    toolCallCount += assistantMsg.getToolCalls().size();

                    // ── Subtask write nudge: if 5+ tool calls with zero writes, force it ──
                    if (isSubtask && totalFileWrites == 0 && toolCallCount >= 5) {
                        String nudge = buildSubtaskWriteNudge(taskDef, toolCallCount);
                        responses.add(new ToolResponseMessage.ToolResponse(
                                assistantMsg.getToolCalls().getLast().id(),
                                assistantMsg.getToolCalls().getLast().name(),
                                nudge));
                        log.warn("[Worker {}] Subtask write nudge injected at {} tool calls (0 writes)", taskId, toolCallCount);
                        stateStore.appendTaskLog(taskId, "SUBTASK_WRITE_NUDGE:" + toolCallCount);
                    }

                    // ── Now handle taskComplete with accurate totalFileWrites ────────
                    if (pendingTaskComplete != null) {
                        AssistantMessage.ToolCall tc = pendingTaskComplete;
                        log.info("[Worker {}] taskComplete tool called — building completion report", taskId);
                        stateStore.appendTaskLog(taskId, "TASK_COMPLETE_TOOL_CALLED");
                        String completionJson = buildCompletionJsonFromToolCall(tc.arguments(), taskId);
                        if (looksLikeCompletionReport(completionJson, taskId)) {
                            int claimedFileCount = countClaimedFiles(completionJson);
                            boolean isFabricated = totalFileWrites == 0 && reportClaimsFileChanges(completionJson);
                            boolean isPartial = !isFabricated && claimedFileCount > 0
                                    && totalFileWrites > 0 && claimedFileCount > totalFileWrites;
                            if (isFabricated || isPartial) {
                                fabricatedCompletionCount++;
                                String reason = isFabricated
                                        ? "taskComplete claims files but 0 writeFile calls dispatched"
                                        : "taskComplete claims " + claimedFileCount + " files but only "
                                                + totalFileWrites + " writeFile dispatched";
                                log.warn("[Worker {}] REJECTED {} taskComplete ({}/{}) — {}",
                                        taskId, isFabricated ? "fabricated" : "partial",
                                        fabricatedCompletionCount, MAX_FABRICATED_COMPLETIONS, reason);
                                stateStore.appendTaskLog(taskId, "FABRICATED_TASK_COMPLETE:" + reason);
                                if (fabricatedCompletionCount >= MAX_FABRICATED_COMPLETIONS) {
                                    WorkerReport failReport = new WorkerReport();
                                    failReport.setTaskId(taskId);
                                    failReport.setStatus("failed");
                                    failReport.setFilesCreated(List.of());
                                    failReport.setFilesModified(List.of());
                                    failReport.setBlockers("Fabricated completion " + fabricatedCompletionCount + " times");
                                    return withProviderModel(failReport, provider, model,
                                            continuations > 0, configuredMaxOutput, totalOutputTokens, iteration);
                                }
                                // Send rejection back as tool response and continue
                                responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(),
                                        "REJECTED: " + reason + ". You must call writeFile for each file first."));
                            } else {
                                return withProviderModel(
                                        parseCompletionReport(completionJson, taskId,
                                                totalInputTokens, totalOutputTokens,
                                                totalCacheReadTokens, totalCacheWriteTokens),
                                        provider, model,
                                        continuations > 0, configuredMaxOutput, totalOutputTokens, iteration);
                            }
                        } else {
                            log.warn("[Worker {}] taskComplete args didn't parse as valid completion — continuing", taskId);
                        }
                    }
                    if (!responses.isEmpty()) {
                        messages.add(ToolResponseMessage.builder().responses(responses).build());
                    }
                    if (toolCallCount >= effectiveIterationLimit) {
                        log.warn("[Worker {}] Hit {} iteration limit ({} tool calls) — forcing partial completion",
                                taskId, isSubtask ? "subtask" : "hard", toolCallCount);
                        stateStore.appendTaskLog(taskId, "ITERATION_LIMIT:" + toolCallCount +
                                (isSubtask ? " (subtask cap=" + SUBTASK_ITERATION_LIMIT + ")" : ""));
                        return withProviderModel(WorkerReport.partial(taskId,
                                "Terminated: exceeded " + effectiveIterationLimit + " tool-call iterations",
                                totalInputTokens, totalOutputTokens), provider, model,
                                continuations > 0, configuredMaxOutput, totalOutputTokens, iteration);
                    }
                    continue; // next iteration
                }

                // ── PATH B: Prompt-based tool calls (Anthropic/Google) ───────────────
                if (assistantText != null && !assistantText.isBlank()) {
                    // 1. Strip fabricated <tool_result> blocks (self-play prevention).
                    //    Some models (Anthropic Sonnet) simulate entire tool conversations
                    //    in one response — strip the fabricated results before parsing.
                    String cleanedText = FABRICATED_TOOL_RESULT_PATTERN.matcher(assistantText).replaceAll("");
                    int strippedResults = (assistantText.length() - cleanedText.length());
                    if (strippedResults > 0) {
                        log.warn("[Worker {}] Stripped {} chars of fabricated <tool_result> blocks from model response (self-play detected)",
                                taskId, strippedResults);
                    }

                    // 2. Parse tool calls — XML first for PATH B (expected format), JSON fallback
                    List<ParsedToolCall> toolCalls = parseXmlToolCalls(cleanedText);
                    if (toolCalls.isEmpty()) {
                        toolCalls = parseJsonToolCalls(cleanedText);
                    }
                    if (!toolCalls.isEmpty()) {
                        consecutiveNonJsonOutputs = 0; // reset — model is cooperating
                        StringBuilder results = new StringBuilder();
                        for (ParsedToolCall tc : toolCalls) {
                            Observation toolObs = Observation.createNotStarted("executespec.tool.dispatch", observationRegistry)
                                    .lowCardinalityKeyValue("tool.name", tc.name())
                                    .lowCardinalityKeyValue("task.id", taskId)
                                    .lowCardinalityKeyValue("run.id", runId)
                                    .start();
                            try {
                            String result = dispatchTool(tc, runId, taskId);
                            if (isFileWriteTool(tc.name())) { fileWritesSinceCheck++; totalFileWrites++; }
                            results.append("<tool_result tool=\"").append(tc.name()).append("\" untrusted=\"true\">\n")
                                   .append(result).append("\n</tool_result>\n\n");
                            } finally {
                                toolObs.stop();
                            }
                        }
                        toolCallCount += toolCalls.size();

                        // ── Subtask write nudge (PATH B): if 5+ tool calls with zero writes, force it ──
                        if (isSubtask && totalFileWrites == 0 && toolCallCount >= 5) {
                            String nudge = buildSubtaskWriteNudge(taskDef, toolCallCount);
                            results.append("\n\n").append(nudge);
                            log.warn("[Worker {}] Subtask write nudge injected at {} tool calls (0 writes) [PATH B]",
                                    taskId, toolCallCount);
                            stateStore.appendTaskLog(taskId, "SUBTASK_WRITE_NUDGE:" + toolCallCount);
                        }

                        messages.add(new UserMessage(results.toString().trim()));
                        if (toolCallCount >= effectiveIterationLimit) {
                            log.warn("[Worker {}] Hit {} iteration limit ({} tool calls) — forcing partial completion",
                                    taskId, isSubtask ? "subtask" : "hard", toolCallCount);
                            stateStore.appendTaskLog(taskId, "ITERATION_LIMIT:" + toolCallCount +
                                    (isSubtask ? " (subtask cap=" + SUBTASK_ITERATION_LIMIT + ")" : ""));
                            return withProviderModel(WorkerReport.partial(taskId,
                                    "Terminated: exceeded " + effectiveIterationLimit + " tool-call iterations",
                                    totalInputTokens, totalOutputTokens), provider, model,
                                    continuations > 0, configuredMaxOutput, totalOutputTokens, iteration);
                        }
                        continue; // next iteration — do NOT add the "continue" prompt below
                    }

                    // 2. Strict completion detection: JSON must be the entire response
                    if (looksLikeCompletionReport(assistantText, taskId)) {
                        // Validate: if the report claims files were created or modified but no
                        // writeFile/editFile was dispatched, the model fabricated the report.
                        int claimedFileCount = countClaimedFiles(assistantText);
                        boolean isFabricated = totalFileWrites == 0 && reportClaimsFileChanges(assistantText);
                        // Partial completion: report claims more files than were actually written.
                        // E.g. task says "create 4 files", model writes 1 then sends completion claiming 4.
                        boolean isPartial = !isFabricated && claimedFileCount > 0
                                && totalFileWrites > 0 && claimedFileCount > totalFileWrites;
                        if (isFabricated || isPartial) {
                            fabricatedCompletionCount++;
                            String reason = isFabricated
                                    ? "claims files_created but 0 writeFile calls were dispatched"
                                    : "claims " + claimedFileCount + " files but only " + totalFileWrites
                                            + " writeFile calls were dispatched";
                            log.warn("[Worker {}] REJECTED {} completion report ({}/{}) — {}. Forcing model to use tools.",
                                    taskId, isFabricated ? "fabricated" : "partial",
                                    fabricatedCompletionCount, MAX_FABRICATED_COMPLETIONS, reason);
                            stateStore.appendTaskLog(taskId, (isFabricated ? "FABRICATED" : "PARTIAL")
                                    + "_REPORT_REJECTED (" + fabricatedCompletionCount + "/"
                                    + MAX_FABRICATED_COMPLETIONS + "): " + reason);

                            // After MAX_FABRICATED_COMPLETIONS retries, give up — model cannot use tools correctly
                            if (fabricatedCompletionCount >= MAX_FABRICATED_COMPLETIONS) {
                                log.error("[Worker {}] Fabricated/partial completion detected {} times — marking task FAILED. " +
                                        "Model persistently claims file creation without using writeFile tool.",
                                        taskId, fabricatedCompletionCount);
                                stateStore.appendTaskLog(taskId, "FAILED: REPEATED_FABRICATED_COMPLETION");
                                WorkerReport failReport = new WorkerReport();
                                failReport.setTaskId(taskId);
                                failReport.setStatus("failed");
                                failReport.setFilesCreated(List.of());
                                failReport.setFilesModified(List.of());
                                failReport.setBlockers("Fabricated/partial completion detected " +
                                        fabricatedCompletionCount + " times — model claims " + claimedFileCount +
                                        " files but only " + totalFileWrites + " writeFile calls dispatched.");
                                return withProviderModel(failReport, provider, model,
                                        continuations > 0, configuredMaxOutput, totalOutputTokens, iteration);
                            }

                            // CONTEXT-TRIMMED RETRY: instead of keeping full bloated history
                            // (which burns 100k+ tokens per retry), reset to task assignment +
                            // summary of actual work done + explicit file-by-file instructions.
                            // This cuts retry cost from ~100k tokens to ~5k tokens.
                            int filesRemaining = Math.max(0, claimedFileCount - totalFileWrites);
                            String writtenFilesSummary = totalFileWrites > 0
                                    ? "You have successfully written " + totalFileWrites + " file(s) so far. "
                                    : "You have NOT written any files yet. ";

                            boolean nativeFabricationMode = "openai".equalsIgnoreCase(provider) || "anthropic".equalsIgnoreCase(provider);
                            messages.clear();
                            messages.add(new UserMessage(buildInitialUserMessage(taskDef, branchName, runId)));
                            String fabricationExample = nativeFabricationMode
                                    ? "Call the writeFile tool now for your next file."
                                    : "Example:\n" +
                                      "<tool_call>{\"name\": \"writeFile\", \"args\": {\"path\": \"src/Main.java\", " +
                                      "\"content\": \"package com.example;\\n...\"}}</tool_call>\n\n" +
                                      "START NOW — call writeFile for your next file.";
                            messages.add(new UserMessage(
                                    "CONTEXT RESET — Your previous attempt was REJECTED.\n\n" +
                                    "REASON: You produced a completion report claiming " + claimedFileCount +
                                    " files, but only " + totalFileWrites + " writeFile tool calls were dispatched.\n\n" +
                                    writtenFilesSummary +
                                    (filesRemaining > 0
                                            ? "You still need to create " + filesRemaining + " more file(s).\n\n"
                                            : "\n") +
                                    "RULES (read carefully):\n" +
                                    "1. Files are ONLY created by calling the writeFile tool — describing them in text does NOTHING.\n" +
                                    "2. Call writeFile ONCE for EACH file you need to create.\n" +
                                    "3. Do NOT call taskComplete until ALL files are written.\n" +
                                    "4. Write ONE file at a time. After each writeFile call, write the next file.\n\n" +
                                    fabricationExample));
                            continue; // retry with trimmed context
                        }
                        return withProviderModel(
                                parseCompletionReport(assistantText, taskId,
                                        totalInputTokens, totalOutputTokens,
                                        totalCacheReadTokens, totalCacheWriteTokens),
                                provider, model,
                                continuations > 0, configuredMaxOutput, totalOutputTokens, iteration);
                    }
                }

                // No tool calls and no completion report — prompt to continue
                // Log the unparsed output for diagnostics
                if (assistantText != null && !assistantText.isBlank()) {
                    log.warn("[Worker {}] Iteration {} — output NOT parsed as tool call or completion ({} chars). " +
                            "First 300 chars: {}", taskId, iteration, assistantText.length(),
                            assistantText.length() <= 300 ? assistantText : assistantText.substring(0, 300) + "...");
                }

                consecutiveNonJsonOutputs++;
                boolean nativeToolMode = "openai".equalsIgnoreCase(provider);
                String continuePrompt;
                long minsLeft = Duration.between(Instant.now(), deadline).toMinutes();
                if (minsLeft <= 3) {
                    continuePrompt = nativeToolMode
                            ? "You have approximately " + minsLeft + " minute(s) remaining. " +
                              "If you have finished all your work, call the taskComplete tool now. " +
                              "Otherwise, call the most important remaining tool."
                            : "You have approximately " + minsLeft + " minute(s) remaining. " +
                              "If you have finished all your work, output ONLY your completion JSON report now. " +
                              "Otherwise, call the MOST IMPORTANT remaining tool using <tool_call> XML tags.";
                } else if (consecutiveNonJsonOutputs >= 2) {
                    // Escalated prompt after 2+ consecutive non-JSON outputs
                    log.warn("[Worker {}] {} consecutive non-JSON outputs — injecting strict completion demand",
                            taskId, consecutiveNonJsonOutputs);
                    stateStore.appendTaskLog(taskId, "NON_JSON_ESCALATION:" + consecutiveNonJsonOutputs);
                    continuePrompt = nativeToolMode
                            ? "STOP. You have produced " + consecutiveNonJsonOutputs +
                              " consecutive text responses without calling any tool. " +
                              "Do NOT respond with plain text.\n\n" +
                              "If your task is done, call the taskComplete tool with your status and file lists.\n\n" +
                              "If you still need to do work, call writeFile, editFile, readFile, or run.\n\n" +
                              "You MUST call a tool in your next response. No prose, no explanation."
                            : "STOP. You have produced " + consecutiveNonJsonOutputs +
                              " consecutive responses that are neither tool calls nor a valid completion report. " +
                              "This is wasting your iteration budget.\n\n" +
                              "OUTPUT ONLY THE JSON COMPLETION REPORT NOW. No prose, no explanation, no markdown — " +
                              "ONLY the raw JSON object starting with { and ending with }.\n\n" +
                              "If your task is done:\n" +
                              "{\"task_id\": \"YOUR_TASK_ID\", \"status\": \"complete\", " +
                              "\"files_created\": [...], \"files_modified\": [...], " +
                              "\"files_read\": [...], \"self_review\": \"...\", " +
                              "\"blockers\": null, \"notes_for_reviewer\": \"...\"}\n\n" +
                              "If you still need to call a tool, use EXACTLY this format:\n" +
                              "<tool_call>{\"name\": \"TOOL_NAME\", \"args\": {\"KEY\": \"VALUE\"}}</tool_call>\n\n" +
                              "NOTHING ELSE. Your next response must start with either { or <tool_call>.";
                } else {
                    continuePrompt = nativeToolMode
                            ? "Your response was text only — no tool was called. " +
                              "You MUST call a tool. If you are done, call the taskComplete tool. " +
                              "If you need to do more work, call writeFile, readFile, or another tool."
                            : "Your response was not recognized as a tool call. To call a tool, " +
                              "you MUST use <tool_call> XML tags. Example:\n" +
                              "<tool_call>{\"name\": \"writeFile\", \"args\": {\"path\": \"file.js\", " +
                              "\"content\": \"console.log('hello');\"}}</tool_call>\n\n" +
                              "If you have finished all your work, output ONLY your completion JSON report. " +
                              "Otherwise, call the next tool using <tool_call> tags now.";
                }
                messages.add(new UserMessage(continuePrompt));

                // Budget monitoring (compaction / hard-stop)
                BudgetStatus budget = budgetMonitor.check(model, complexity, lastCallInputTokens);
                switch (budget.status()) {
                    case HARD_STOP -> {
                        if (iteration - lastCompactedIteration > 2) {
                            log.warn("Hard stop threshold reached for task {} ({}% context) — " +
                                     "attempting emergency compaction", taskId,
                                    (int) (budget.usagePct() * 100));
                            stateStore.recordCompaction(taskId, "emergency-compact-at-hard-stop");
                            sseBroadcaster.emit(runId, RunEvent.compactionTriggered(runId, taskId));
                            String[] compactProvider = { provider };
                            String[] compactModel   = { model };
                            messages = compact(messages, taskDef, taskId, runId,
                                    compactProvider, compactModel, agentId, complexity);
                            provider = compactProvider[0];
                            model    = compactModel[0];
                            lastCallInputTokens = 0;
                            justCompacted = true;
                            lastCompactedIteration = iteration;
                        } else {
                            log.warn("Hard stop threshold reached for task {} ({}% context) — " +
                                     "already compacted at iteration {}, cannot recover",
                                    taskId, (int) (budget.usagePct() * 100), lastCompactedIteration);
                            stateStore.recordCompaction(taskId, "hard-stop");
                            sseBroadcaster.emit(runId, RunEvent.compactionTriggered(runId, taskId));
                            return withProviderModel(WorkerReport.partial(taskId,
                                    "Hard stopped at " + (int) (budget.usagePct() * 100)
                                            + "% context usage after emergency compaction failed. Resume required.",
                                    totalInputTokens, totalOutputTokens), provider, model,
                                    continuations > 0, configuredMaxOutput, totalOutputTokens, iteration);
                        }
                    }
                    case COMPACT -> {
                        log.info("Compacting context for task {} at {}% usage", taskId,
                                (int) (budget.usagePct() * 100));
                        String[] compactProvider = { provider };
                        String[] compactModel   = { model };
                        messages = compact(messages, taskDef, taskId, runId,
                                compactProvider, compactModel, agentId, complexity);
                        provider = compactProvider[0];
                        model    = compactModel[0];
                        lastCallInputTokens = 0;
                        justCompacted = true;
                        lastCompactedIteration = iteration;
                    }
                    case WARN ->
                        log.warn("Context at {}% for task {} — compaction approaching",
                                (int) (budget.usagePct() * 100), taskId);
                    case OK -> {
                        /* nothing */ }
                }

                } finally {
                    iterObs.stop();
                }
            }
        } catch (MidTaskProviderFailureException e) {
            // ── Recovery via checkpoint compaction + fallback provider ────────────
            // Compact conversation to produce provider-agnostic messages, switch to a
            // fallback provider, and re-enter the agentic loop for one more attempt.
            if (!midTaskRecoveryAttempted) {
                midTaskRecoveryAttempted = true;
                String failedProvider = provider;
                try {
                    var aiProvider = stateStore.findRun(runId)
                            .map(r -> r.getAiProviderEnum())
                            .orElse(com.navneet.executespec.router.LlmProvider.ANTHROPIC);

                    // Find a different provider via failover chain
                    ResolvedModel fallbackResolved = modelResolver.resolveFallbackExcluding(
                            "WorkerAgent", aiProvider, failedProvider);

                    // Compact conversation — produces provider-agnostic checkpoint
                    String[] compactProvider = { fallbackResolved.providerName() };
                    String[] compactModel   = { fallbackResolved.getModel() };
                    messages = compact(messages, taskDef, taskId, runId,
                            compactProvider, compactModel, agentId, complexity);
                    provider = compactProvider[0];
                    model    = compactModel[0];
                    lastCallInputTokens = 0;

                    log.info("Mid-task recovery: compacted and switched {} → {} for task {}",
                            failedProvider, provider, taskId);
                    io.micrometer.core.instrument.Metrics.counter("executespec.worker.mid_task_recovery",
                            "from_provider", failedProvider, "to_provider", provider).increment();
                    sseBroadcaster.emit(runId, RunEvent.log(runId, taskId,
                            "⚡ Provider failure — recovered via checkpoint (now using " + model + ")"));

                    // Re-enter the agentic loop — goto not available in Java, so we use
                    // a labeled continue on an outer recovery loop. Since the current code
                    // structure doesn't have one, we return partial with enough context
                    // for the harness retry mechanism to pick up where we left off.
                    // The checkpoint file is already written by compact(), so a retry
                    // will resume from the compacted state with the new provider.
                } catch (Exception recoveryEx) {
                    log.error("Mid-task recovery failed for task {}: {}", taskId, recoveryEx.getMessage());
                }
            }
            return withProviderModel(WorkerReport.blocked(taskId,
                    "Provider failure mid-task (" + e.getMessage() + ")",
                    totalInputTokens, totalOutputTokens), provider, model,
                    continuations > 0, configuredMaxOutput, totalOutputTokens, iteration);
        } catch (LlmRouter.ProviderUnavailableException e) {
            log.error("All providers exhausted for task {}: {}", taskId, e.getMessage());
            return withProviderModel(WorkerReport.blocked(taskId, "All providers exhausted: " + e.getMessage(),
                    totalInputTokens, totalOutputTokens), provider, model,
                    continuations > 0, configuredMaxOutput, totalOutputTokens, iteration);
        }
        // ── END AGENTIC LOOP ──────────────────────────────────────────────────

        boolean hitTimeLimitFlag = !Instant.now().isBefore(deadline);
        String reason = hitTimeLimitFlag
                ? "Hit " + taskDurationMinutes + "-minute time limit after " + iteration + " iterations without producing completion report."
                : "Hit safety iteration cap (" + safetyIterationCap + ") without producing completion report.";
        log.warn("Worker for task {} stopped: {} (complexity={})", taskId, reason, complexity);
        return withProviderModel(WorkerReport.partial(taskId, reason,
                totalInputTokens, totalOutputTokens), provider, model,
                continuations > 0, configuredMaxOutput, totalOutputTokens, iteration);
        } finally {
            // Always clear the thread-local worktree context, even on exception.
            WorkspaceContext.clear();
        }
    }

    /**
     * Thread-local flag that suppresses worktree creation in {@link #execute}.
     * When set, the worker operates directly on the main workspace directory.
     * Set by {@link #executeOnMainBranch} and cleared in its finally block.
     */
    private static final ThreadLocal<Boolean> SKIP_WORKTREE = ThreadLocal.withInitial(() -> false);

    /**
     * Executes a task directly on the main branch workspace (no git worktree).
     *
     * <p>Used by {@link com.navneet.executespec.harness.SingleShotExecutor} for
     * SINGLE_SHOT requests that bypass the planner and worktree system.
     *
     * <p>Identical to {@link #execute(AgentTask, String)} except:
     * <ul>
     *   <li>No git worktree creation — operates on {@code {agent.workspace}/{runId}/}</li>
     *   <li>No branch creation — all changes go to the main branch</li>
     *   <li>No dependency branch merging</li>
     * </ul>
     */
    public WorkerReport executeOnMainBranch(AgentTask task, String runId) {
        SKIP_WORKTREE.set(true);
        try {
            return execute(task, runId);
        } finally {
            SKIP_WORKTREE.remove();
        }
    }

    /**
     * Thread-local override for the workspace directory.
     * When set, {@link #execute(AgentTask, String)} uses this directory instead
     * of creating a new worktree. Used by subtask decomposition so all subtasks
     * share the parent task's worktree.
     */
    private static final ThreadLocal<String> OVERRIDE_WORK_DIR = new ThreadLocal<>();

    /**
     * Executes a task in a pre-existing workspace directory (no worktree creation).
     *
     * <p>Used by subtask decomposition: the parent task's worktree is created once,
     * and all subtasks execute sequentially in the same directory. This allows
     * subtask N+1 to see files written by subtask N.
     *
     * @param task   the subtask to execute
     * @param runId  the pipeline run ID
     * @param workDir the existing workspace directory to operate in
     * @return worker report for this subtask
     */
    public WorkerReport executeInWorkDir(AgentTask task, String runId, String workDir) {
        SKIP_WORKTREE.set(true);
        OVERRIDE_WORK_DIR.set(workDir);
        try {
            return execute(task, runId);
        } finally {
            SKIP_WORKTREE.remove();
            OVERRIDE_WORK_DIR.remove();
        }
    }

    /** Stamps the final provider, model, and calibration data onto the report. */
    private static WorkerReport withProviderModel(WorkerReport report, String provider, String model,
                                                    boolean wasTruncated, int maxOutputTokens,
                                                    int totalOutputTokens, int iterationCount) {
        report.setProvider(provider);
        report.setModel(model);
        report.setWasTruncated(wasTruncated);
        report.setMaxOutputTokens(maxOutputTokens);
        report.setActualOutputTokens(totalOutputTokens);
        report.setIterationCount(iterationCount);
        return report;
    }

    /** Overload for backward compat — no calibration data. */
    private static WorkerReport withProviderModel(WorkerReport report, String provider, String model) {
        report.setProvider(provider);
        report.setModel(model);
        return report;
    }

    // ── Model Router integration ────────────────────────────────────────────

    /**
     * Execute with a specific resolved model.
     * Overrides task.assignedModel before calling execute().
     *
     * @param task   the task to execute
     * @param runId  the run ID
     * @param model  the ResolvedModel from ModelResolver
     * @return the WorkerReport from execution
     */
    public WorkerReport executeWithModel(AgentTask task, String runId,
                                          com.navneet.executespec.router.ResolvedModel model) {
        log.info("Running task {} with model {} [{}]",
                task.getTaskId(), model.getModel(), model.getAgentTier());
        task.setAssignedModel(model.getModel());
        return execute(task, runId);
    }

    // ── XML Tool-Call Parsing & Dispatch ──────────────────────────────────────

    private List<ParsedToolCall> parseXmlToolCalls(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<ParsedToolCall> calls = new ArrayList<>();
        Matcher m = TOOL_CALL_PATTERN.matcher(text);
        while (m.find()) {
            String rawContent = m.group(1).trim();
            String json = escapeControlCharsInJsonStrings(rawContent);
            try {
                JsonNode node = mapper.readTree(json);
                String name = node.has("name") ? node.get("name").asText() : null;
                String argsJson = node.has("args") ? node.get("args").toString() : "{}";
                if (name != null && !name.isBlank()) {
                    calls.add(new ParsedToolCall(name, argsJson));
                }
            } catch (Exception e) {
                // Standard JSON parsing failed — likely unescaped content in writeFile/editFile.
                // Use structural fallback parser that handles raw file content.
                log.info("[Worker] Standard JSON parse failed for tool_call ({}chars), error: {}. Trying structural fallback. Raw: {}",
                        rawContent.length(), e.getMessage(),
                        rawContent.length() > 1000 ? rawContent.substring(0, 1000) + "..." : rawContent);
                ParsedToolCall fallback = parseToolCallStructural(rawContent);
                if (fallback != null) {
                    calls.add(fallback);
                    log.info("[Worker] Structural fallback parsed tool call: {}", fallback.name());
                } else {
                    log.warn("[Worker] Could not parse tool_call (both JSON and structural failed). Full raw content ({}chars): {}",
                            rawContent.length(), rawContent);
                }
            }
        }
        return calls;
    }

    /**
     * Structural fallback parser for tool calls whose JSON contains unescaped file content.
     * LLMs often emit writeFile/editFile calls where the "content" or "newStr" value has
     * unescaped double-quotes and newlines that break standard JSON parsing.
     *
     * <p>This parser extracts "name" and known arg fields by scanning for structural markers
     * rather than relying on strict JSON parsing.
     *
     * @param raw the raw text between {@code <tool_call>} and {@code </tool_call>}
     * @return a ParsedToolCall if extraction succeeds, null otherwise
     */
    private ParsedToolCall parseToolCallStructural(String raw) {
        if (raw == null || raw.isBlank()) return null;

        // Extract the "name" field — must be near the start and is always a simple string
        java.util.regex.Matcher nameMatcher = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(raw);
        if (!nameMatcher.find()) return null;
        String name = nameMatcher.group(1);

        try {
            switch (name) {
                case "writeFile", "write_file" -> {
                    // Extract "path" — simple string, always before "content"
                    String path = extractSimpleStringField(raw, "path");
                    if (path == null) return null;
                    // Extract "content" — everything between the content value start and the closing of args
                    String content = extractContentField(raw);
                    if (content == null) return null;
                    // Build proper JSON for args
                    String argsJson = mapper.writeValueAsString(
                            mapper.createObjectNode().put("path", path).put("content", content));
                    return new ParsedToolCall(name, argsJson);
                }
                case "editFile", "edit_file" -> {
                    String path = extractSimpleStringField(raw, "path");
                    if (path == null) return null;
                    String oldStr = extractFieldBetween(raw, "oldStr", new String[]{"newStr", "old_str"});
                    if (oldStr == null) oldStr = extractFieldBetween(raw, "old_str", new String[]{"newStr", "new_str"});
                    String newStr = extractLastStringField(raw, "newStr");
                    if (newStr == null) newStr = extractLastStringField(raw, "new_str");
                    if (oldStr == null || newStr == null) return null;
                    String argsJson = mapper.writeValueAsString(
                            mapper.createObjectNode().put("path", path).put("oldStr", oldStr).put("newStr", newStr));
                    return new ParsedToolCall(name, argsJson);
                }
                default -> {
                    // For non-content tools (readFile, listFiles, etc.), the JSON should have parsed fine.
                    // If we're here, the JSON was truly malformed — give up.
                    return null;
                }
            }
        } catch (Exception e) {
            log.debug("[Worker] Structural parse failed for '{}': {}", name, e.getMessage());
            return null;
        }
    }

    /** Extract a simple string field value from JSON-like text (no embedded quotes). */
    private String extractSimpleStringField(String text, String fieldName) {
        java.util.regex.Matcher m = Pattern.compile(
                "\"" + fieldName + "\"\\s*:\\s*\"([^\"]+)\"").matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Extract the "content" field value from a writeFile tool call.
     * The content is everything between the opening quote after "content": and the last
     * proper closing boundary ("}}" or "} }") of the tool call JSON.
     */
    private String extractContentField(String text) {
        // Find "content" : "  (the start of the content value)
        java.util.regex.Matcher m = Pattern.compile("\"content\"\\s*:\\s*\"").matcher(text);
        if (!m.find()) return null;
        int contentStart = m.end();

        // The content ends at the last `"}` or `"  }` (closing of args object then outer object).
        // Work backwards from the end of the raw text to find the content boundary.
        String remainder = text.substring(contentStart);

        // Find the closing pattern: quote followed by optional whitespace, }, optional whitespace, }
        // We scan from the END because the content itself may contain `"}` patterns.
        int lastClose = -1;
        for (int i = remainder.length() - 1; i >= 0; i--) {
            if (remainder.charAt(i) == '}') {
                // Found a }, look for another } before it
                for (int j = i - 1; j >= 0; j--) {
                    char c = remainder.charAt(j);
                    if (c == '}') {
                        // Found }}, now look for the quote before it
                        for (int k = j - 1; k >= 0; k--) {
                            char q = remainder.charAt(k);
                            if (q == '"') {
                                lastClose = k;
                                break;
                            } else if (!Character.isWhitespace(q)) {
                                break;
                            }
                        }
                        break;
                    } else if (!Character.isWhitespace(c)) {
                        break;
                    }
                }
                break;
            }
        }

        if (lastClose < 0) return null;
        return remainder.substring(0, lastClose);
    }

    /**
     * Extract a field value that appears between the named field and the next known field.
     * Used for oldStr in editFile where the content may contain unescaped quotes.
     */
    private String extractFieldBetween(String text, String fieldName, String[] nextFields) {
        java.util.regex.Matcher m = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*\"").matcher(text);
        if (!m.find()) return null;
        int start = m.end();

        // Find the earliest next field
        int end = text.length();
        for (String next : nextFields) {
            int idx = text.indexOf("\"" + next + "\"", start);
            if (idx > 0 && idx < end) {
                // Back up past the comma and whitespace
                int boundary = idx;
                while (boundary > start && (text.charAt(boundary - 1) == ',' ||
                        text.charAt(boundary - 1) == '"' || Character.isWhitespace(text.charAt(boundary - 1)))) {
                    boundary--;
                }
                end = boundary;
            }
        }
        if (end <= start) return null;
        String value = text.substring(start, end);
        // Trim trailing quote if present
        if (value.endsWith("\"")) value = value.substring(0, value.length() - 1);
        return value;
    }

    /** Extract the last string field (like newStr) — content runs to the closing `"}}`. */
    private String extractLastStringField(String text, String fieldName) {
        java.util.regex.Matcher m = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*\"").matcher(text);
        if (!m.find()) return null;
        int start = m.end();
        String remainder = text.substring(start);
        // Same logic as extractContentField — find closing `"}}` from end
        for (int i = remainder.length() - 1; i >= 0; i--) {
            if (remainder.charAt(i) == '}') {
                for (int j = i - 1; j >= 0; j--) {
                    if (remainder.charAt(j) == '}') {
                        for (int k = j - 1; k >= 0; k--) {
                            if (remainder.charAt(k) == '"') return remainder.substring(0, k);
                            if (!Character.isWhitespace(remainder.charAt(k))) break;
                        }
                        break;
                    }
                    if (!Character.isWhitespace(remainder.charAt(j))) break;
                }
                break;
            }
        }
        return null;
    }

    /**
     * Fallback parser for non-Anthropic models that emit tool calls as bare JSON
     * (without XML wrapping). Handles single objects and arrays:
     *   {"name": "readFile", "args": {"path": "..."}}
     *   [{"name": "readFile", "args": {"path": "..."}}, ...]
     *
     * Rejects objects that look like completion reports ("task_id" + "status" present).
     */
    private List<ParsedToolCall> parseJsonToolCalls(String text) {
        if (text == null || text.isBlank()) return List.of();

        List<ParsedToolCall> calls = new ArrayList<>();

        // Strategy: scan the text for JSON objects that look like tool calls.
        // Models may wrap them in prose, markdown fences, or output them bare.

        // 1. Try extracting from markdown code fences first (```json ... ```)
        java.util.regex.Matcher fenceMatcher = java.util.regex.Pattern
                .compile("```(?:json)?\\s*([\\s\\S]*?)```")
                .matcher(text);
        while (fenceMatcher.find()) {
            List<ParsedToolCall> fenced = tryParseJsonBlock(fenceMatcher.group(1).trim());
            if (!fenced.isEmpty()) {
                calls.addAll(fenced);
                return calls; // fenced JSON is authoritative — don't double-parse
            }
        }

        // 2. Scan for bare JSON objects by finding { at potential tool-call boundaries.
        //    Use brace-depth tracking to extract complete JSON objects.
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '{') {
                String candidate = extractJsonObject(text, i);
                if (candidate != null) {
                    List<ParsedToolCall> parsed = tryParseJsonBlock(candidate);
                    calls.addAll(parsed);
                    if (!calls.isEmpty()) return calls; // first valid tool call wins
                    // Skip past this object even if it wasn't a tool call
                    i += candidate.length() - 1;
                }
            }
        }

        return calls;
    }

    /** Try to parse a JSON string as one or more tool calls. */
    private List<ParsedToolCall> tryParseJsonBlock(String json) {
        if (json == null || json.isBlank()) return List.of();
        String sanitized = escapeControlCharsInJsonStrings(json);
        List<ParsedToolCall> calls = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(sanitized);
            if (root.isArray()) {
                for (JsonNode node : root) {
                    ParsedToolCall tc = extractToolCallFromNode(node);
                    if (tc != null) calls.add(tc);
                }
            } else if (root.isObject()) {
                ParsedToolCall tc = extractToolCallFromNode(root);
                if (tc != null) calls.add(tc);
            }
        } catch (Exception e) {
            log.debug("[Worker] JSON tool-call parse attempt failed: {}", e.getMessage());
        }
        return calls;
    }

    /** Extract a balanced JSON object starting at position i, or null if not valid. */
    private String extractJsonObject(String text, int start) {
        if (start >= text.length() || text.charAt(start) != '{') return null;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        return null; // unbalanced
    }

    /** Extract a ParsedToolCall from a JSON node, or null if it's not a tool call. */
    private ParsedToolCall extractToolCallFromNode(JsonNode node) {
        if (!node.isObject()) return null;
        // Reject completion reports — they have "task_id" + "status"
        if (node.has("task_id") && node.has("status")) return null;
        String name = node.has("name") ? node.get("name").asText() : null;
        if (name == null || name.isBlank()) return null;
        // "args" is optional — some models may omit it for no-arg tools like git_status
        String argsJson = node.has("args") ? node.get("args").toString() : "{}";
        return new ParsedToolCall(name, argsJson);
    }

    /**
     * LLMs frequently emit raw control characters (newlines, tabs, carriage returns)
     * inside JSON string values. Standard JSON requires these to be escaped as \\n, \\t, \\r.
     * This method walks the JSON text and escapes any raw control chars found inside
     * quoted strings, leaving structural whitespace (between keys/values) untouched.
     */
    static String escapeControlCharsInJsonStrings(String json) {
        StringBuilder sb = new StringBuilder(json.length() + 64);
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                sb.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                sb.append(c);
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                sb.append(c);
                continue;
            }
            if (inString && c < 0x20) {
                // Escape raw control characters inside JSON string values
                switch (c) {
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default   -> sb.append(String.format("\\u%04x", (int) c));
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
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
                default -> {
                    String suggestion = suggestTool(tc.name());
                    yield "Unknown tool: '" + tc.name() + "'. "
                        + "Available tools: readFile, writeFile, editFile, listFiles, searchFiles, run, status, diff, commit. "
                        + (suggestion != null ? "Did you mean: '" + suggestion + "'?" : "Use ONLY these exact tool names.");
                }
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

    /** Returns true if the tool call is a file-writing operation (for progress tracking). */
    private boolean isFileWriteTool(String toolName) {
        return switch (toolName) {
            case "writeFile", "write_file", "editFile", "edit_file" -> true;
            default -> false;
        };
    }

    /** Suggests the correct tool name for commonly hallucinated tool names. */
    private String suggestTool(String name) {
        if (name == null) return null;
        return switch (name.toLowerCase()) {
            case "gitcommit", "git_commit_all", "git_add", "gitadd", "stage" -> "commit";
            case "runbuild", "build", "run_build", "make" -> "run";
            case "runtests", "test", "run_tests", "runtestcases" -> "run";
            case "runcommand", "execute", "exec", "shell", "bash", "terminal" -> "run";
            case "createfile", "create_file", "touch", "newfile" -> "writeFile";
            case "deletefile", "delete_file", "remove", "rm" -> "run";
            case "updatefile", "update_file", "modifyfile", "modify_file" -> "editFile";
            case "cat", "viewfile", "view_file", "getfile", "get_file" -> "readFile";
            case "ls", "dir", "listdir", "list_dir", "list_directory" -> "listFiles";
            case "grep", "find", "search", "findfiles", "find_files" -> "searchFiles";
            case "gitstatus" -> "status";
            case "gitdiff" -> "diff";
            default -> null;
        };
    }

    /**
     * Escalate to the next higher tier model within the run's selected provider.
     * WORKHORSE → PLANNING (the highest tier). Used when the worker stalls.
     */
    private String escalateModel(String currentModel, String runId) {
        try {
            var aiProvider = stateStore.findRun(runId)
                    .map(r -> r.getAiProviderEnum())
                    .orElse(com.navneet.executespec.router.LlmProvider.ANTHROPIC);
            var resolved = modelResolver.resolve("WorkerAgent", aiProvider,
                    com.navneet.executespec.router.AgentTier.PLANNING);
            if (!resolved.getModel().equals(currentModel)) {
                return resolved.getModel();
            }
        } catch (Exception e) {
            // Best-effort — fall through to current model
        }
        return currentModel;
    }

    private static final Set<String> VALID_COMPLETION_STATUSES =
            Set.of("complete", "partial", "blocked");

    /**
     * Strict completion check: the response must start with '{' (after normalization),
     * contain the expected task_id, and have a valid status value.
     * This prevents false positives from tool results or code snippets containing
     * similar field names.
     */
    /**
     * Build a completion report JSON string from taskComplete tool call arguments.
     * The tool args have the report fields directly; we inject task_id to match
     * the expected completion report format.
     */
    private String buildCompletionJsonFromToolCall(String argsJson, String taskId) {
        try {
            JsonNode args = mapper.readTree(argsJson);
            var report = mapper.createObjectNode();
            report.put("task_id", taskId);
            report.put("status", args.path("status").asText("complete"));
            report.set("files_created", args.has("files_created") ? args.get("files_created") : mapper.createArrayNode());
            report.set("files_modified", args.has("files_modified") ? args.get("files_modified") : mapper.createArrayNode());
            report.set("files_read", args.has("files_read") ? args.get("files_read") : mapper.createArrayNode());
            report.put("self_review", args.path("self_review").asText(""));
            if (args.has("blockers") && !args.get("blockers").isNull()) {
                report.put("blockers", args.get("blockers").asText());
            } else {
                report.putNull("blockers");
            }
            report.put("notes_for_reviewer", args.path("notes_for_reviewer").asText(""));
            return mapper.writeValueAsString(report);
        } catch (Exception e) {
            log.warn("[Worker {}] Failed to build completion from taskComplete args: {}", taskId, e.getMessage());
            return argsJson; // fallback to raw args
        }
    }

    private boolean looksLikeCompletionReport(String text, String expectedTaskId) {
        if (text == null || text.isBlank()) return false;
        String normalized = normalizeWorkerResponse(text);
        if (!normalized.startsWith("{")) return false;
        try {
            JsonNode json = mapper.readTree(normalized);
            String reportTaskId = json.path("task_id").asText("");
            String status = json.path("status").asText("");
            return reportTaskId.equals(expectedTaskId)
                    && VALID_COMPLETION_STATUSES.contains(status);
        } catch (Exception e) {
            // Not valid JSON — not a completion report
            return false;
        }
    }

    /**
     * Normalize raw LLM response to extract the JSON/tool_call payload.
     * Handles provider-specific quirks: markdown fences, preamble text,
     * and XML tool_call wrappers (Gemini often wraps JSON in these).
     */
    static String normalizeWorkerResponse(String raw) {
        if (raw == null) return "";
        String response = raw.trim();

        // Strip markdown code fences
        if (response.startsWith("```json")) {
            response = response.substring(7);
        } else if (response.startsWith("```")) {
            response = response.substring(3);
        }
        if (response.endsWith("```")) {
            response = response.substring(0, response.length() - 3);
        }
        response = response.trim();

        // Extract tool_call XML content if present (Gemini format)
        if (response.contains("<tool_call>")) {
            int start = response.indexOf("<tool_call>") + "<tool_call>".length();
            int end = response.indexOf("</tool_call>");
            if (end > start) {
                response = response.substring(start, end).trim();
            }
        }

        // Extract first JSON object if preceded by conversational preamble.
        // Anthropic can produce verbose cache stats / explanations before JSON — allow up to 800 chars.
        if (!response.startsWith("{")) {
            int jsonStart = response.indexOf('{');
            if (jsonStart > 0 && jsonStart < 800) {
                response = response.substring(jsonStart);
            }
        }

        return response.trim();
    }

    /**
     * Checks if a completion report JSON claims files were created or modified.
     * Used to detect fabricated reports where the model says files_created/files_modified
     * are non-empty but never actually called writeFile/editFile.
     */
    private boolean reportClaimsFileChanges(String text) {
        if (text == null) return false;
        return hasNonEmptyArrayField(text, "files_created") ||
               hasNonEmptyArrayField(text, "files_modified");
    }

    private boolean hasNonEmptyArrayField(String text, String fieldName) {
        if (!text.contains("\"" + fieldName + "\"")) return false;
        // Non-empty = NOT [], NOT null
        String f = "\"" + fieldName + "\"";
        int idx = text.indexOf(f);
        if (idx < 0) return false;
        // Look at the value after the field name
        String after = text.substring(idx + f.length()).stripLeading();
        if (after.startsWith(":")) after = after.substring(1).stripLeading();
        return !after.startsWith("[]") && !after.startsWith("null");
    }

    /**
     * Count total files claimed in completion report (files_created + files_modified).
     * Uses JSON parsing for accuracy. Returns 0 if parsing fails.
     */
    private int countClaimedFiles(String text) {
        try {
            String normalized = normalizeWorkerResponse(text);
            JsonNode json = mapper.readTree(normalized);
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

    // ── Compaction ────────────────────────────────────────────────────────────

    private List<Message> compact(List<Message> messages,
            TaskDefinition task, String taskId, String runId,
            String[] effectiveProvider, String[] effectiveModel, String agentId,
            String complexity) {
        String provider = effectiveProvider[0];
        String model    = effectiveModel[0];
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
                runId, taskId, agentId, 0, false, complexity);
        effectiveProvider[0] = summaryLr.provider();
        effectiveModel[0]    = summaryLr.model();
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
        sseBroadcaster.emit(runId, RunEvent.compactionTriggered(runId, taskId));

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

                Continue from where you left off. Call the next tool using the native tool calling schema.
                """.formatted(safeJson(task), summary, fileContext)));

        log.info("Context compacted for task {}. Checkpoint: {}", taskId, checkpointPath);
        return fresh;
    }

    private String rebuildFileContext(String runId) {
        return "Current src/ structure:\n" + fsTool.searchFiles(runId, "", "src");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildInitialUserMessage(TaskDefinition task, String branchName, String runId) {
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

        String baseAssignment = """
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

        // Inject frontend design system constraints if this is a frontend task
        String frontendBlock = buildFrontendBlock(task, runId);
        if (frontendBlock != null) {
            return baseAssignment + "\n" + frontendBlock;
        }
        return baseAssignment;
    }

    /**
     * Detects if a task is frontend-related and returns styling guidance.
     * Combines the CSS styling prompt (visual craftsmanship rules) with the
     * run-specific design system constraint block from ui_style config.
     * Returns null for non-frontend tasks.
     */
    private String buildFrontendBlock(TaskDefinition task, String runId) {
        // Check owned files for frontend extensions
        boolean hasUiFiles = task.getOwnedFiles() != null && task.getOwnedFiles().stream()
                .anyMatch(f -> f.endsWith(".html") || f.endsWith(".css") || f.endsWith(".jsx")
                        || f.endsWith(".tsx") || f.endsWith(".vue") || f.endsWith(".svelte"));

        // Heuristic: check title and objective for frontend keywords
        String probe = ((task.getTitle() != null ? task.getTitle() : "")
                + " " + (task.getObjective() != null ? task.getObjective() : "")).toLowerCase();
        boolean isFrontendKeyword = probe.contains("frontend") || probe.contains("ui component")
                || probe.contains("react") || probe.contains("vue") || probe.contains("angular")
                || probe.contains("page") || probe.contains("form") || probe.contains("layout")
                || probe.contains("dashboard") || probe.contains("login") || probe.contains("register")
                || probe.contains("navigation") || probe.contains("sidebar") || probe.contains("header")
                || probe.contains("component") || probe.contains("css") || probe.contains("style")
                || probe.contains("tailwind") || probe.contains("shadcn") || probe.contains("html")
                || probe.contains("landing") || probe.contains("modal") || probe.contains("table view")
                || probe.contains("next.js") || probe.contains("nextjs");

        if (!hasUiFiles && !isFrontendKeyword) return null;

        var sb = new StringBuilder();

        // Load design system content and build constraint block
        try {
            var run = stateStore.findRun(runId).orElse(null);
            if (run != null) {
                String uiStyleJson = run.getUiStyle();

                // Extract design_name from ui_style JSON and load DESIGN.md
                String designContent = null;
                if (uiStyleJson != null && !uiStyleJson.isBlank()) {
                    try {
                        var styleMap = mapper.readValue(uiStyleJson,
                                new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
                        String designName = styleMap.get("design_name");
                        if (designName != null && !designName.isBlank()) {
                            designContent = uiStyleResolver.loadDesignContent(designName);
                        }
                    } catch (Exception je) {
                        log.debug("Could not parse ui_style JSON for run {}: {}", runId, je.getMessage());
                    }
                }

                // Build constraint block (design content or legacy fallback)
                String constraintBlock = uiStyleResolver.buildFrontendConstraintBlock(uiStyleJson, designContent);
                if (constraintBlock != null) {
                    sb.append("\n").append(constraintBlock);
                }
            }
        } catch (Exception e) {
            log.debug("Could not load design system for run {}: {}", runId, e.getMessage());
        }

        return sb.isEmpty() ? null : sb.toString();
    }

    private String buildProgressCheckpoint(TaskDefinition task, long elapsedMinutes, int totalMinutes) {
        long remainingMinutes = totalMinutes - elapsedMinutes;
        return """
                ═══ PROGRESS CHECKPOINT (%d of %d minutes elapsed) ═══

                REMINDER — Your task:
                TASK ID: %s
                OBJECTIVE: %s
                ACCEPTANCE CRITERIA: %s

                You have been working for %d minutes. You have approximately %d minutes remaining.
                Assess your progress:
                • What have you accomplished so far?
                • What remains to satisfy the acceptance criteria?
                • If all work is done, output your completion JSON report NOW.

                Continue working or report completion.
                """.formatted(elapsedMinutes, totalMinutes,
                task.getTaskId(), task.getObjective(), task.getAcceptanceCriteria(),
                elapsedMinutes, remainingMinutes);
    }

    private String buildCompletionUrgencyPrompt(TaskDefinition task, long elapsedMinutes, int totalMinutes) {
        long remainingMinutes = totalMinutes - elapsedMinutes;
        return """
                ═══ URGENT: APPROACHING TIME LIMIT ═══

                You have been working for %d of %d minutes. You have less than %d minutes remaining.

                TASK ID: %s
                ACCEPTANCE CRITERIA: %s

                ACTION REQUIRED:
                1. If your work satisfies the acceptance criteria: output your completion JSON NOW.
                2. If your work is partially done: commit what you have, then output a completion JSON
                   with status "partial" and describe what remains in self_review.
                3. Do NOT start new work that you cannot finish in the remaining time.

                Output your completion JSON report as your next response.
                """.formatted(elapsedMinutes, totalMinutes, remainingMinutes,
                task.getTaskId(), task.getAcceptanceCriteria());
    }

    /**
     * Builds a write nudge message for subtasks that have consumed 5+ tool calls
     * without producing any writeFile output. Injected into the conversation to
     * break the read-loop pattern common with Gemini on simple subtasks.
     */
    private String buildSubtaskWriteNudge(TaskDefinition taskDef, int toolCallCount) {
        String ownedFile = (taskDef.getOwnedFiles() != null && !taskDef.getOwnedFiles().isEmpty())
                ? taskDef.getOwnedFiles().getFirst() : "your assigned file";
        return """
                ═══ SUBTASK WRITE NUDGE ═══

                You are on a SUBTASK. You own exactly ONE file: %s
                You have used %d of 10 allowed tool calls and have written ZERO files.

                STOP READING. You have enough context.
                Your NEXT tool call MUST be writeFile to create %s.
                If you call readFile, listFiles, or searchFiles again, this task WILL be terminated.

                Write the complete file NOW.
                """.formatted(ownedFile, toolCallCount, ownedFile);
    }

    /**
     * Rough token estimate for a message list. Uses 4 chars per token heuristic.
     * Not precise, but sufficient for pre-call compaction decisions.
     */
    private int estimateInputTokens(List<Message> messages) {
        int totalChars = 0;
        for (Message m : messages) {
            if (m instanceof UserMessage um) {
                totalChars += um.getText() != null ? um.getText().length() : 0;
            } else if (m instanceof AssistantMessage am) {
                totalChars += am.getText() != null ? am.getText().length() : 0;
            } else if (m instanceof SystemMessage sm) {
                totalChars += sm.getText() != null ? sm.getText().length() : 0;
            } else if (m instanceof ToolResponseMessage trm) {
                for (var resp : trm.getResponses()) {
                    totalChars += resp.responseData() != null ? resp.responseData().length() : 0;
                }
            }
        }
        return totalChars / 4;
    }

    /**
     * Calls the LLM via LlmRouter.
     *
     * <p><b>Anthropic/Google:</b> tools described in the system prompt; model outputs XML/JSON
     * tool calls which the agentic loop parses and executes manually.
     *
     * <p><b>OpenAI:</b> tools provided as native function-calling schemas via
     * {@link ToolCallback}. Model returns structured {@code tool_calls} in the response
     * (not in text). {@code internalToolExecutionEnabled = false} keeps the outer loop
     * in control.
     */
    private LlmRouter.LlmResponse callModel(List<Message> messages,
            String provider, String model,
            String runId, String taskId, String agentId,
            int iteration, boolean justCompacted,
            String complexity) {
        String systemPrompt = loadPrompt(provider);
        int maxOut = budgetMonitor.maxOutputTokens(model, complexity);
        boolean isOpenAi = "openai".equalsIgnoreCase(provider);
        boolean useNativeTools = isOpenAi;
        // Anthropic uses PATH B (XML prompt-based) — Spring AI 1.1.0 drops tool_use
        // blocks from the parsed response (finishReason='tool_use' but getToolCalls()=empty).

        log.info("[Worker callModel] provider={} model={} complexity={} maxOut={} nativeTools={}",
                provider, model, complexity, maxOut, useNativeTools);

        // Sliding window: always send the first 2 messages (task assignment + initial
        // context) and the most-recent HISTORY_WINDOW messages. This bounds input tokens
        // per call regardless of iteration count, while compaction handles summarising
        // the full history. The full `messages` list is preserved in memory for compact().
        final int HISTORY_WINDOW = 18;
        List<Message> windowed;
        if (useNativeTools || messages.size() <= HISTORY_WINDOW + 2) {
            // Native tool calling (OpenAI): skip sliding window entirely.
            // AssistantMessage(tool_calls) and ToolResponseMessage pairs MUST stay together;
            // the window boundary would split them causing 400 errors. Rely on compaction instead.
            windowed = messages;
        } else {
            int windowStart = messages.size() - HISTORY_WINDOW;
            // Ensure the window boundary does not split an AssistantMessage(tool_calls)
            // from its ToolResponseMessage.
            while (windowStart > 2 && windowStart < messages.size()
                    && messages.get(windowStart) instanceof ToolResponseMessage) {
                windowStart--;
            }
            windowed = new ArrayList<>(messages.subList(0, 2));
            windowed.addAll(messages.subList(windowStart, messages.size()));
            log.debug("[Worker callModel] Sliding window: kept first 2 + last {} of {} messages",
                    messages.size() - windowStart, messages.size());
        }

        List<Message> allMessages = new ArrayList<>();
        allMessages.add(new SystemMessage(systemPrompt));
        allMessages.addAll(windowed);

        Prompt prompt;
        List<ToolCallback> callbacks = WorkerToolCallbackFactory.create(
                runId, taskId,
                (name, argsJson, rid, tid) -> dispatchTool(new ParsedToolCall(name, argsJson), rid, tid));

        FallbackMode fallbackMode;
        if (isOpenAi) {
            // OpenAI: native function calling — tools defined as JSON schemas.
            // OpenAI models reliably call tools in auto mode, so no explicit
            // tool_choice override needed (Spring AI's extra_body serialization
            // of toolChoice causes 400 errors on some OpenAI API versions).
            OpenAiChatOptions opts = OpenAiChatOptions.builder()
                    .temperature(0.0)
                    .maxCompletionTokens(maxOut)
                    .toolCallbacks(callbacks)
                    .internalToolExecutionEnabled(false) // keep outer loop in control
                    .build();
            prompt = new Prompt(allMessages, opts);
            // NO_FALLBACK: message history contains ToolResponseMessages
            // that are incompatible across providers if we failover mid-task
            fallbackMode = FallbackMode.NO_FALLBACK;
        } else {
            // Anthropic / Google / others: prompt-based tool calling (XML <tool_call> format).
            // No toolCallbacks — tools described in prompt, parsed as XML in PATH B.
            // Spring AI 1.1.0 drops Anthropic tool_use blocks (finishReason='tool_use'
            // but getToolCalls()=empty), so native tool calling is not viable.
            AnthropicCacheOptions cacheOpts = AnthropicCacheOptions.builder()
                    .strategy(AnthropicCacheStrategy.SYSTEM_ONLY)
                    .build();
            AnthropicChatOptions opts = AnthropicChatOptions.builder()
                    .temperature(0.0)
                    .maxTokens(maxOut)
                    .cacheOptions(cacheOpts)
                    .build();
            prompt = new Prompt(allMessages, opts);
            // No ToolResponseMessages in history — safe to failover across providers.
            fallbackMode = FallbackMode.IMMEDIATE;
        }
        return llmRouter.callWithFallbackTracked(provider, model, prompt, runId, agentId,
                fallbackMode, iteration, justCompacted);
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

    /**
     * Reads project_type from .agent-context/project.json in the workspace.
     * Returns null if file doesn't exist or can't be parsed.
     */
    private String readProjectType(String worktreePath) {
        try {
            java.nio.file.Path projectJson = java.nio.file.Path.of(worktreePath, ".agent-context", "project.json");
            if (java.nio.file.Files.exists(projectJson)) {
                String content = java.nio.file.Files.readString(projectJson);
                var node = mapper.readTree(content);
                if (node.has("project_type")) {
                    return node.get("project_type").asText();
                }
            }
        } catch (Exception e) {
            log.debug("Could not read project type from .agent-context/project.json: {}", e.getMessage());
        }
        return null;
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
            case "read_file", "readFile", "list_files", "listFiles",
                 "search_files", "searchFiles" -> MAX_RESULT_CHARS_FILE;
            case "run_command", "run_build", "run_tests", "run" -> MAX_RESULT_CHARS_SHELL;
            case "git_diff", "diff" -> MAX_RESULT_CHARS_DIFF;
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

    /** Derive provider from model name via ModelRegistry; fall back to configured role provider. */
    private String resolveProvider(String model) {
        if (model == null) return props.getRoles().getWorker();
        String provider = modelRegistry.providerFor(model);
        return provider != null ? provider : props.getRoles().getWorker();
    }

    /**
     * Checks whether the LLM response was truncated because it hit the output token limit.
     * Finish reason strings vary by provider:
     *   Anthropic: "end_turn" (normal) vs "max_tokens" (truncated)
     *   OpenAI:    "stop" (normal) vs "length" (truncated)
     *   Gemini:    "STOP" (normal) vs "MAX_TOKENS" (truncated)
     */
    private static boolean isTruncatedResponse(String finishReason) {
        if (finishReason == null) return false;
        return "max_tokens".equalsIgnoreCase(finishReason)
                || "length".equalsIgnoreCase(finishReason)
                || "MAX_TOKENS".equals(finishReason);
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
