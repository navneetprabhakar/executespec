package com.navneet.executespec.v2.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.v2.observability.V2Metrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * V2 planner. Produces a {@link PlanV2} from a user spec + classification.
 *
 * <p>Implementation strategy: the planner is an LLM call restricted to a single tool —
 * {@code submitPlan}. The tool's input schema IS the plan schema, so the LLM emits
 * structured output directly via native tool calling rather than asking for JSON in
 * text. The planner extracts the tool call arguments, parses them into
 * {@link PlanV2}, and returns.
 *
 * <p>{@link Classification#SINGLE_SHOT} bypasses the LLM entirely — a single synthetic
 * task is generated from the spec. This matches V1's SINGLE_SHOT fast path and avoids
 * a pointless LLM call for trivial inputs like "write fibonacci".
 *
 * <p>The submitPlan tool is built inline here — it is NOT a registered
 * {@link com.navneet.executespec.v2.tool.Tool} bean because (a) it's only called during
 * planning, and (b) its "dispatch" is a no-op from the tool's perspective — the value
 * lives in the tool-call arguments, not in the tool's output.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PlannerAgentV2 {

    private final LlmRouterV2 router;
    private final ObjectMapper objectMapper;
    private final V2Metrics metrics;

    public PlanV2 plan(PlannerRequestV2 req) {
        long start = System.currentTimeMillis();
        PlanV2 plan = (req.classification() == Classification.SINGLE_SHOT)
                ? synthesizeSingleShotPlan(req)
                : plannerViaLlm(req);
        metrics.recordPlannerLatency(req.classification().name(), req.provider(), req.model(),
                java.time.Duration.ofMillis(System.currentTimeMillis() - start));
        metrics.recordPlannerTaskCount(req.classification().name(), plan.tasks().size());
        return plan;
    }

    // ─── SINGLE_SHOT fast path ──────────────────────────────────────────────

    private PlanV2 synthesizeSingleShotPlan(PlannerRequestV2 req) {
        log.info("PlannerAgentV2 SINGLE_SHOT synthesize (no LLM call): spec={}chars", req.spec().length());
        PlannedTaskV2 task = PlannedTaskV2.builder()
                .id("T1")
                .title(truncate(req.spec(), 80))
                .description(req.spec())
                .complexity(PlannedTaskV2.Complexity.LOW)
                .build();
        return PlanV2.builder()
                .planTitle("Single-shot: " + truncate(req.spec(), 60))
                .planSummary(req.spec())
                .projectType("unknown")
                .smokeTestStrategy(PlanV2.SmokeTestStrategy.BUILD)
                .runtimeSurface(PlanV2.RuntimeSurface.HTTP)
                .classification(Classification.SINGLE_SHOT)
                .tasks(List.of(task))
                .build();
    }

    // ─── LLM-driven planner ─────────────────────────────────────────────────

    private PlanV2 plannerViaLlm(PlannerRequestV2 req) {
        String systemPrompt = loadPrompt()
                .replace("{{SPEC}}", req.spec())
                .replace("{{CLASSIFICATION}}", req.classification().name())
                .replace("{{MAX_TASKS}}",
                        req.classification().maxTasks() == Integer.MAX_VALUE
                                ? "unlimited" : String.valueOf(req.classification().maxTasks()))
                .replace("{{WORKSPACE_SUMMARY}}",
                        req.workspaceSummary() == null ? "(no summary)" : req.workspaceSummary())
                .replace("{{PROJECT_MEMORY}}", renderProjectMemoryBlock(req.projectMemory()));

        List<Message> messages = List.of(
                new SystemMessage(systemPrompt),
                new UserMessage("Produce the plan now."));

        ToolCallback submitPlan = buildSubmitPlanCallback();
        ChatResponse resp = router.call(LlmRequestV2.builder()
                .provider(req.provider())
                .model(req.model())
                .messages(messages)
                .context(req.context())
                .temperature(0.0)
                .maxTokens(4096)
                .toolsOverride(List.of(submitPlan))
                .build());

        // Find the submitPlan call across all generations.
        AssistantMessage.ToolCall planCall = null;
        for (var gen : resp.getResults()) {
            if (gen.getOutput().getToolCalls() != null) {
                for (var tc : gen.getOutput().getToolCalls()) {
                    if ("submitPlan".equals(tc.name())) {
                        planCall = tc;
                        break;
                    }
                }
            }
            if (planCall != null) break;
        }
        if (planCall == null) {
            String textPreview = resp.getResult().getOutput().getText();
            throw new IllegalStateException(
                    "Planner LLM did not call submitPlan. Finish reason: "
                            + resp.getResult().getMetadata().getFinishReason()
                            + "; text preview: " + (textPreview == null ? "" : truncate(textPreview, 200)));
        }
        PlanV2 plan = parsePlanFromArgs(planCall.arguments(), req.classification());
        warnIfConstraintsUnderpropagated(plan, req.spec());
        return plan;
    }

    /**
     * Observability check for Phase 4 constraint-propagation quality. Looks for obvious
     * constraint signals in the spec (do NOT / use X / throughout / etc.). If the spec has
     * such signals but a majority of tasks have empty hardConstraints, logs a WARN so we
     * can catch regressions in planner prompt adherence without failing the run.
     *
     * <p>Not a hard validation — weaker models (Gemini 2.5-flash observed) underpropagate
     * intermittently. Auto-backfill is a candidate for Phase 4.x if this warning fires often.
     */
    private void warnIfConstraintsUnderpropagated(PlanV2 plan, String spec) {
        if (plan.tasks().isEmpty()) return;
        String specLower = spec.toLowerCase();
        boolean specHasSignals =
                specLower.contains("do not") || specLower.contains("don't")
                        || specLower.contains(" not ") || specLower.contains("only ")
                        || specLower.contains(" throughout")
                        || specLower.contains("must use") || specLower.contains("avoid ");
        if (!specHasSignals) return;
        long emptyCount = plan.tasks().stream()
                .filter(t -> t.hardConstraints() == null || t.hardConstraints().isEmpty())
                .count();
        double emptyRatio = (double) emptyCount / plan.tasks().size();
        if (emptyRatio >= 0.6) {
            log.warn("PlannerAgentV2 constraint-propagation QUALITY WARNING: spec contains "
                    + "constraint signals but {}/{} tasks ({}%) have empty hardConstraints. "
                    + "Prompt-adherence may have degraded. Consider prompt strengthening or "
                    + "auto-backfill. planTitle='{}'",
                    emptyCount, plan.tasks().size(),
                    Math.round(emptyRatio * 100), plan.planTitle());
        }
    }

    private PlanV2 parsePlanFromArgs(String argsJson, Classification classification) {
        JsonNode root;
        try {
            root = objectMapper.readTree(argsJson);
        } catch (Exception e) {
            throw new IllegalStateException("submitPlan args are not valid JSON: " + e.getMessage()
                    + "; args=" + truncate(argsJson, 400), e);
        }

        List<PlannedTaskV2> tasks = new ArrayList<>();
        JsonNode tasksNode = root.path("tasks");

        // Salvage path: Anthropic occasionally emits its internal
        // <parameter name="..."> XML inline instead of a clean tool-call payload,
        // which shoves the tasks array INSIDE the smokeTestStrategy string value
        // and leaves the real "tasks" field missing. Detect and recover so the
        // run doesn't fail outright.
        if (!tasksNode.isArray()) {
            String leakedBlob = root.path("smokeTestStrategy").asText("");
            JsonNode salvaged = salvageTasksFromBlob(leakedBlob);
            if (salvaged != null) {
                log.warn("Planner: recovered tasks array from malformed smokeTestStrategy blob "
                        + "({} tasks). Anthropic model went off-protocol.", salvaged.size());
                tasksNode = salvaged;
            }
        }

        if (tasksNode.isArray()) {
            int idx = 0;
            for (JsonNode t : tasksNode) {
                if (idx >= classification.maxTasks()) {
                    log.warn("Planner exceeded maxTasks={} for classification={}, truncating",
                            classification.maxTasks(), classification);
                    break;
                }
                tasks.add(buildTask(t, idx + 1));
                idx++;
            }
        }

        String smokeStrategyStr = root.path("smokeTestStrategy").asText("BUILD");
        PlanV2.SmokeTestStrategy smokeStrategy;
        try {
            smokeStrategy = PlanV2.SmokeTestStrategy.valueOf(smokeStrategyStr.toUpperCase());
        } catch (IllegalArgumentException iae) {
            log.warn("Unknown smokeTestStrategy '{}', falling back to BUILD", smokeStrategyStr);
            smokeStrategy = PlanV2.SmokeTestStrategy.BUILD;
        }

        // Parse runtimeSurface — default HTTP so legacy plans keep working.
        PlanV2.RuntimeSurface runtimeSurface = PlanV2.RuntimeSurface.HTTP;
        String surfaceStr = root.path("runtimeSurface").asText("HTTP");
        try {
            runtimeSurface = PlanV2.RuntimeSurface.valueOf(surfaceStr.trim().toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException ignored) {
            log.warn("Unknown runtimeSurface '{}', falling back to HTTP", surfaceStr);
        }

        return PlanV2.builder()
                .planTitle(root.path("planTitle").asText(null))
                .planSummary(root.path("planSummary").asText(null))
                .projectType(root.path("projectType").asText("unknown"))
                .buildCommand(root.path("buildCommand").isNull()
                        ? null : root.path("buildCommand").asText(null))
                .smokeTestStrategy(smokeStrategy)
                .runtimeSurface(runtimeSurface)
                .tasks(tasks)
                .classification(classification)
                .build();
    }

    /**
     * Extracts a JSON array of tasks from a malformed Anthropic tool-call blob
     * that shoved `<parameter name="tasks">[...]` XML inline inside another
     * string field. Returns the parsed array node, or null if no recovery is possible.
     */
    private JsonNode salvageTasksFromBlob(String blob) {
        if (blob == null || blob.isEmpty()) return null;
        int marker = blob.indexOf("name=\"tasks\"");
        if (marker < 0) return null;
        int start = blob.indexOf('[', marker);
        if (start < 0) return null;
        int depth = 0;
        int end = -1;
        for (int i = start; i < blob.length(); i++) {
            char c = blob.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) { end = i; break; }
            }
        }
        if (end < 0) return null;
        String arrayJson = blob.substring(start, end + 1);
        try {
            JsonNode node = objectMapper.readTree(arrayJson);
            return node.isArray() ? node : null;
        } catch (Exception e) {
            log.warn("Planner: salvage parse failed: {}", e.getMessage());
            return null;
        }
    }

    private PlannedTaskV2 buildTask(JsonNode t, int seq) {
        String id = t.path("id").asText(null);
        if (id == null || id.isBlank()) id = "T" + seq;
        PlannedTaskV2.Complexity cx;
        try {
            cx = PlannedTaskV2.Complexity.valueOf(t.path("complexity").asText("MEDIUM").toUpperCase());
        } catch (IllegalArgumentException iae) {
            cx = PlannedTaskV2.Complexity.MEDIUM;
        }
        return PlannedTaskV2.builder()
                .id(id)
                .title(t.path("title").asText("(untitled)"))
                .description(t.path("description").asText("(no description)"))
                .complexity(cx)
                .touchesFiles(stringList(t.path("touchesFiles")))
                .dependsOn(stringList(t.path("dependsOn")))
                .hardConstraints(stringList(t.path("hardConstraints")))
                .build();
    }

    private static List<String> stringList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr.isArray()) {
            arr.forEach(n -> {
                if (n.isTextual() && !n.asText().isBlank()) out.add(n.asText());
            });
        }
        return out;
    }

    // ─── submitPlan tool definition ─────────────────────────────────────────

    private ToolCallback buildSubmitPlanCallback() {
        ToolDefinition def = DefaultToolDefinition.builder()
                .name("submitPlan")
                .description("Submit the final execution plan. Call exactly once with the complete plan. "
                        + "After this call the planning phase ends.")
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "planTitle": {"type": "string"},
                            "planSummary": {"type": "string"},
                            "projectType": {
                              "type": "string",
                              "description": "One of: java-spring-boot, node-backend, node-frontend, python, go, rust, cli, unknown"
                            },
                            "buildCommand": {
                              "type": "string",
                              "description": "Explicit build command, or omit to fall back to framework inference"
                            },
                            "smokeTestStrategy": {
                              "type": "string",
                              "description": "One of: BUILD, PREVIEW_HEALTH, TEST_SUITE"
                            },
                            "runtimeSurface": {
                              "type": "string",
                              "description": "How the code runs: HTTP (web server), CLI (console program), LIBRARY (importable package, no entry point), STATIC_WEB (frontend only). Default HTTP."
                            },
                            "tasks": {
                              "type": "array",
                              "items": {
                                "type": "object",
                                "properties": {
                                  "id": {"type": "string"},
                                  "title": {"type": "string"},
                                  "description": {"type": "string"},
                                  "complexity": {"type": "string", "description": "LOW | MEDIUM | HIGH"},
                                  "touchesFiles": {"type": "array", "items": {"type": "string"}},
                                  "dependsOn": {"type": "array", "items": {"type": "string"}},
                                  "hardConstraints": {"type": "array", "items": {"type": "string"}}
                                },
                                "required": ["id", "title", "description"]
                              }
                            }
                          },
                          "required": ["planTitle", "planSummary", "tasks"]
                        }
                        """)
                .build();
        final String callbackId = "planner-" + UUID.randomUUID();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return def; }
            @Override public String call(String toolInput) {
                // The planner never actually dispatches this — it reads the args from the
                // tool call directly. If we ever get here (internalToolExecutionEnabled
                // mis-set), echo back a benign ack.
                log.debug("submitPlan callback invoked unexpectedly ({}); args={}",
                        callbackId, truncate(toolInput, 200));
                return "{\"ok\":true,\"note\":\"plan captured\"}";
            }
        };
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private String renderProjectMemoryBlock(String projectMemory) {
        if (projectMemory == null || projectMemory.isBlank()) return "";
        return """
                # Project Memory

                This run is part of an ongoing project. Below is accumulated context from
                previous runs — architecture decisions, patterns, and known debt. USE THIS to:
                - avoid re-implementing existing features
                - follow established patterns and conventions
                - build on existing code, not alongside it
                - address known debt when relevant

                """ + projectMemory;
    }

    private String loadPrompt() {
        try {
            return new ClassPathResource("prompts/v2/planner.md").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load prompts/v2/planner.md", e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
