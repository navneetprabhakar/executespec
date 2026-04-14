package com.navneet.executespec.v2.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.v2.context.ContextStoreV2;
import com.navneet.executespec.v2.observability.V2Metrics;
import com.navneet.executespec.v2.tool.CompleteTaskTool;
import com.navneet.executespec.v2.tool.Tool;
import com.navneet.executespec.v2.tool.ToolRegistryV2;
import com.navneet.executespec.v2.tool.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * V2 sequential worker agent.
 *
 * <p>One agent, one task, one execution context. Multi-turn loop:
 * <ol>
 *   <li>Send the current message history to the LLM via {@link LlmRouterV2}</li>
 *   <li>If the response contains tool calls, dispatch each through {@link ToolRegistryV2}
 *       and append the results as a {@link ToolResponseMessage}</li>
 *   <li>If the response contains a {@link CompleteTaskTool} call, terminate with
 *       {@link WorkerResult.Outcome#COMPLETED}</li>
 *   <li>If the response has no tool calls, terminate with
 *       {@link WorkerResult.Outcome#STOPPED_WITHOUT_COMPLETE}</li>
 *   <li>If the iteration cap is reached, terminate with
 *       {@link WorkerResult.Outcome#ITERATION_LIMIT_EXCEEDED}</li>
 * </ol>
 *
 * <p>Phase 3 baseline scope:
 * <ul>
 *   <li>Multi-turn message threading — full history kept in memory for the duration of
 *       the task (no compaction yet — Phase 8 owns context persistence + crash recovery)</li>
 *   <li>Native completeTask signal (no JSON-prefix heuristic)</li>
 *   <li>40-iteration default cap (overridable via WorkerTaskRequest.maxIterations)</li>
 * </ul>
 *
 * <p>Deferred to later phases (per execution plan):
 * <ul>
 *   <li>Hybrid context (upfront file fill from planner-declared dependencyFiles) — Phase 4</li>
 *   <li>Sliding-window compaction at 75% context limit — Phase 8</li>
 *   <li>Redis context persistence + crash recovery — Phase 8</li>
 *   <li>Stall detection / model escalation — Phase 2.x</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WorkerAgentV2 {

    /** Default iteration cap (per V2 plan P3.1 — was 28 in V1 MEDIUM, raised to compensate
     *  for SubtaskDecomposer removal). */
    public static final int DEFAULT_MAX_ITER = 40;

    /** Persist context every N iterations (Phase 8 — plan P8.1 says "every 5"). */
    public static final int CONTEXT_PERSIST_EVERY_N_ITERATIONS = 5;

    private final LlmRouterV2 router;
    private final ToolRegistryV2 toolRegistry;
    private final ObjectMapper objectMapper;
    private final ContextStoreV2 contextStore;
    private final V2Metrics metrics;
    private final com.navneet.executespec.v2.cost.V2RunCostTracker costTracker;
    private final com.navneet.executespec.state.repository.PipelineRunRepository pipelineRunRepository;

    /** Bug #2 fix — check budget every N iterations, not just at task boundaries. */
    static final int BUDGET_CHECK_EVERY_N_ITERATIONS = 3;

    public WorkerResult run(WorkerTaskRequest req) {
        long start = System.currentTimeMillis();
        int maxIter = (req.maxIterations() == null || req.maxIterations() <= 0)
                ? DEFAULT_MAX_ITER : req.maxIterations();
        List<Message> history = new ArrayList<>();
        if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
            history.add(new SystemMessage(req.systemPrompt()));
        }
        history.add(new UserMessage(req.userPrompt()));

        List<String> toolLog = new ArrayList<>();
        int toolsDispatched = 0;
        String lastText = "";

        log.info("WorkerAgentV2 START run={} task={} provider={} model={} maxIter={}",
                req.context().runId(), req.context().taskId(),
                req.provider(), req.model(), maxIter);

        // Bug #2 fix — load the run's hard cap once so the loop can stop
        // itself if cost blows past it mid-task. Null cap = no enforcement.
        final String runId = req.context().runId();
        final Double hardCapUsd = pipelineRunRepository.findById(runId)
                .map(r -> r.getHardCapUsd())
                .orElse(null);

        for (int iter = 1; iter <= maxIter; iter++) {
            if (hardCapUsd != null && hardCapUsd > 0
                    && (iter == 1 || iter % BUDGET_CHECK_EVERY_N_ITERATIONS == 0)) {
                double spent = costTracker.snapshot(runId).costUsd();
                if (spent >= hardCapUsd) {
                    log.warn("WorkerAgentV2 BUDGET_EXCEEDED run={} task={} iter={} spent=${} cap=${}",
                            runId, req.context().taskId(), iter,
                            String.format("%.4f", spent), String.format("%.4f", hardCapUsd));
                    recordTerminal(WorkerResult.Outcome.BUDGET_EXCEEDED, req, iter);
                    return errorResult(iter, toolsDispatched, toolLog, start,
                            "hard cap $" + String.format("%.2f", hardCapUsd) + " exceeded mid-task (spent $"
                                    + String.format("%.2f", spent) + ")");
                }
            }

            ChatResponse resp;
            try {
                resp = router.call(LlmRequestV2.builder()
                        .provider(req.provider())
                        .model(req.model())
                        .messages(history)
                        .context(req.context())
                        .temperature(req.temperature())
                        .maxTokens(req.maxTokensPerCall())
                        .build());
            } catch (Exception e) {
                log.error("WorkerAgentV2 router failure at iter={}", iter, e);
                recordTerminal(WorkerResult.Outcome.ERROR, req, iter);
                return errorResult(iter, toolsDispatched, toolLog, start,
                        "router error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            // Collect tool calls + assistant text across all generations (Anthropic
            // returns multi-gen responses; OpenAI/Gemini single-gen — same code path works).
            StringBuilder textBuf = new StringBuilder();
            List<AssistantMessage.ToolCall> calls = new ArrayList<>();
            for (var gen : resp.getResults()) {
                String t = gen.getOutput().getText();
                if (t != null && !t.isEmpty()) {
                    if (textBuf.length() > 0) textBuf.append("\n");
                    textBuf.append(t);
                }
                if (gen.getOutput().getToolCalls() != null) {
                    calls.addAll(gen.getOutput().getToolCalls());
                }
            }
            String assistantText = textBuf.toString();
            if (!assistantText.isEmpty()) lastText = assistantText;

            log.info("WorkerAgentV2 iter={} text={}chars toolCalls={}",
                    iter, assistantText.length(), calls.size());

            // No tool calls → model is done (either text-only stop or stopped early).
            if (calls.isEmpty()) {
                history.add(new AssistantMessage(assistantText));
                recordTerminal(WorkerResult.Outcome.STOPPED_WITHOUT_COMPLETE, req, iter);
                return WorkerResult.builder()
                        .outcome(WorkerResult.Outcome.STOPPED_WITHOUT_COMPLETE)
                        .iterations(iter)
                        .toolCallsDispatched(toolsDispatched)
                        .finalAssistantText(lastText)
                        .toolCallLog(toolLog)
                        .totalLatencyMs(System.currentTimeMillis() - start)
                        .build();
            }

            // Append the assistant turn (text + tool_calls) to history.
            history.add(AssistantMessage.builder()
                    .content(assistantText)
                    .toolCalls(calls)
                    .build());

            // Dispatch every tool call. completeTask short-circuits the loop.
            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            String completionSummary = null;
            for (AssistantMessage.ToolCall tc : calls) {
                toolsDispatched++;
                if (CompleteTaskTool.NAME.equals(tc.name())) {
                    completionSummary = extractSummary(tc.arguments());
                    String envelope = okEnvelope("task marked complete: " + completionSummary);
                    toolResponses.add(new ToolResponseMessage.ToolResponse(
                            safeId(tc.id()), tc.name(), envelope));
                    toolLog.add(tc.name() + " → COMPLETED");
                    log.info("WorkerAgentV2 completeTask received: {}", completionSummary);
                    // Append the tool response so the conversation is well-formed
                    // (matters for any post-hoc inspection / persistence).
                    history.add(ToolResponseMessage.builder().responses(toolResponses).build());
                    // Successful completion — context is no longer needed for recovery.
                    contextStore.delete(req.context());
                    recordTerminal(WorkerResult.Outcome.COMPLETED, req, iter);
                    return WorkerResult.builder()
                            .outcome(WorkerResult.Outcome.COMPLETED)
                            .iterations(iter)
                            .toolCallsDispatched(toolsDispatched)
                            .completionSummary(completionSummary)
                            .finalAssistantText(lastText)
                            .toolCallLog(toolLog)
                            .totalLatencyMs(System.currentTimeMillis() - start)
                            .build();
                }
                metrics.recordToolCall(tc.name());
                String envelope = dispatchOne(tc, req);
                toolResponses.add(new ToolResponseMessage.ToolResponse(
                        safeId(tc.id()), tc.name(), envelope));
                toolLog.add(tc.name() + "(" + previewArgs(tc.arguments()) + ")");
            }
            history.add(ToolResponseMessage.builder().responses(toolResponses).build());

            // Periodic context persistence for crash recovery / retry-with-context. Best-effort;
            // failures inside ContextStoreV2 don't propagate. Save on the iteration boundary
            // so a saved snapshot always ends on a well-formed tool-response turn.
            if (iter % CONTEXT_PERSIST_EVERY_N_ITERATIONS == 0) {
                contextStore.save(req.context(), history, iter);
            }
        }

        log.warn("WorkerAgentV2 ITERATION LIMIT run={} task={} iters={}",
                req.context().runId(), req.context().taskId(), maxIter);
        recordTerminal(WorkerResult.Outcome.ITERATION_LIMIT_EXCEEDED, req, maxIter);
        return WorkerResult.builder()
                .outcome(WorkerResult.Outcome.ITERATION_LIMIT_EXCEEDED)
                .iterations(maxIter)
                .toolCallsDispatched(toolsDispatched)
                .finalAssistantText(lastText)
                .toolCallLog(toolLog)
                .totalLatencyMs(System.currentTimeMillis() - start)
                .build();
    }

    /** Emit worker outcome + iterations metrics at every terminal path. */
    private void recordTerminal(WorkerResult.Outcome outcome, WorkerTaskRequest req, int iterations) {
        metrics.recordWorkerOutcome(outcome.name(), req.provider(), req.model());
        metrics.recordWorkerIterations(outcome.name(), req.provider(), req.model(), iterations);
    }

    private String dispatchOne(AssistantMessage.ToolCall tc, WorkerTaskRequest req) {
        Tool tool = toolRegistry.find(tc.name());
        if (tool == null) {
            return errEnvelope("INVALID_ARGS", "unknown tool: " + tc.name());
        }
        JsonNode args;
        try {
            args = (tc.arguments() == null || tc.arguments().isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(tc.arguments());
        } catch (Exception e) {
            return errEnvelope("INVALID_ARGS", "tool args not valid JSON: " + e.getMessage());
        }
        try {
            ToolResult result = tool.execute(args, req.context());
            return switch (result) {
                case ToolResult.Ok ok -> okEnvelope(ok.content());
                case ToolResult.Err err -> errEnvelope(err.kind().name(), err.message());
            };
        } catch (Exception unexpected) {
            log.error("Tool '{}' threw unexpectedly", tc.name(), unexpected);
            return errEnvelope("INTERNAL",
                    unexpected.getClass().getSimpleName() + ": " + unexpected.getMessage());
        }
    }

    private String okEnvelope(String content) {
        ObjectNode env = objectMapper.createObjectNode();
        env.put("ok", true);
        env.put("content", content);
        return env.toString();
    }

    private String errEnvelope(String kind, String message) {
        ObjectNode env = objectMapper.createObjectNode();
        env.put("ok", false);
        env.put("kind", kind);
        env.put("message", message);
        return env.toString();
    }

    private String extractSummary(String argsJson) {
        try {
            JsonNode n = objectMapper.readTree(argsJson == null ? "{}" : argsJson);
            return n.path("summary").asText("(no summary)");
        } catch (Exception e) {
            return "(unparseable summary args: " + e.getMessage() + ")";
        }
    }

    private static String safeId(String id) {
        return (id == null || id.isBlank()) ? "call_" + Long.toHexString(System.nanoTime()) : id;
    }

    private static String previewArgs(String args) {
        if (args == null) return "";
        return args.length() > 80 ? args.substring(0, 80) + "..." : args;
    }

    private WorkerResult errorResult(int iter, int toolsDispatched, List<String> toolLog,
                                      long start, String message) {
        return WorkerResult.builder()
                .outcome(WorkerResult.Outcome.ERROR)
                .iterations(iter)
                .toolCallsDispatched(toolsDispatched)
                .finalAssistantText(message)
                .toolCallLog(toolLog)
                .totalLatencyMs(System.currentTimeMillis() - start)
                .build();
    }
}
