package com.navneet.executespec.v2.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
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
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Phase 1 of the hierarchical planner — produces a {@link PlanV2} for runs the
 * {@link SmartClassifierAgent} marked {@code SMALL} or {@code MEDIUM}. SINGLE_SHOT
 * still bypasses the LLM via {@link PlannerAgentV2#plan} (synthesised plan);
 * {@code LARGE} and {@code LEGACY} fall back to {@link PlannerAgentV2} until
 * slices 3-4 wire up parallel expansion + recursive outlines.
 *
 * <p>Slice 2 contract: emit the full plan in a single tool call (32k cap — the
 * old planner used 8192 and got truncated on RN+API multi-repo specs). The
 * output shape is the same {@code PlanV2}; only the prompt + tool name differ
 * from {@link PlannerAgentV2}. Slice 3 will evolve this into a "skeleton-only"
 * mode for {@code LARGE}, with {@code TaskExpanderAgent} filling task bodies
 * in parallel.
 *
 * <p>Reuses {@link PlannerAgentV2#parsePlanFromArgs}, {@link PlannerAgentV2#validatePlan},
 * {@link PlannerAgentV2#salvagePlanFromText}, and
 * {@link PlannerAgentV2#warnIfConstraintsUnderpropagated} so both agents
 * produce structurally identical {@code PlanV2} instances. The four are
 * package-private specifically so we can share them without copy-paste.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OutlinePlannerAgent {

    private final LlmRouterV2 router;
    private final ObjectMapper objectMapper;
    private final V2Metrics metrics;
    private final ModelResolver modelResolver;
    private final FeatureFlagService featureFlagService;
    /** For shared parsing + validation — see class-level javadoc. */
    private final PlannerAgentV2 plannerAgent;

    private static final String PROMPT_PATH = "prompts/v2/outline-planner.md";
    /** v2.6.2 slice 2 — 32k breathing room. The old planner's 8192 cap truncated
     *  on multi-repo specs (the bug that drove the whole hierarchical rewrite).
     *  SMALL/MEDIUM specs are bounded by definition (≤15 tasks, ≤2 repos), so
     *  32k is comfortably enough for full descriptions + hardConstraints +
     *  meetsRequirements per task. */
    private static final int OUTLINE_MAX_TOKENS = 32_768;

    /** v2.5.11 — gates strict v3 planner behaviour (requirement enumeration + validation). */
    private static final String FLAG_V3_STRICT = "validation.v3-strict";

    /**
     * Run the outline phase for a SMALL/MEDIUM-classified spec. Synchronous —
     * cost flows through {@link LlmRouterV2} → {@link com.navneet.executespec.v2.cost.V2RunCostTracker}
     * automatically; the orchestrator diffs the per-run snapshot before/after
     * to attribute the phase to {@code pipeline_run.outline_*} columns.
     */
    public PlanV2 plan(PlannerRequestV2 req) {
        long start = System.currentTimeMillis();
        try {
            PlanV2 plan = planOnce(req, null);

            // v2.5.11 (Validation v3 / Pillar 1) — strict validation + one retry.
            // Gated by feature flag; off → log-only behaviour.
            if (featureFlagService != null && featureFlagService.isEnabled(FLAG_V3_STRICT)) {
                List<String> violations = plannerAgent.validatePlan(plan);
                if (!violations.isEmpty()) {
                    String feedback = "Your previous plan had these requirement-graph violations:\n  - "
                            + String.join("\n  - ", violations)
                            + "\n\nRe-emit the plan with every spec requirement enumerated and every "
                            + "task's meetsRequirements referencing real requirement IDs. "
                            + "Every requirement must be cited by at least one task.";
                    log.warn("OutlinePlanner plan invalid on first try ({} violations) — retrying. First violation: {}",
                            violations.size(), violations.get(0));
                    PlanV2 retry = planOnce(req, feedback);
                    List<String> retryViolations = plannerAgent.validatePlan(retry);
                    if (!retryViolations.isEmpty()) {
                        throw new IllegalStateException(
                                "PLAN_COVERAGE_INVALID: outline planner produced an invalid requirement graph "
                                        + "even after one retry. Violations: "
                                        + String.join("; ", retryViolations));
                    }
                    plan = retry;
                }
            }
            return plan;
        } finally {
            metrics.recordPlannerLatency(req.classification().name(),
                    req.provider(), req.model(),
                    Duration.ofMillis(System.currentTimeMillis() - start));
        }
    }

    private PlanV2 planOnce(PlannerRequestV2 req, String retryFeedback) {
        String systemPrompt = loadPrompt()
                .replace("{{SPEC}}", req.spec() == null ? "" : req.spec())
                .replace("{{CLASSIFICATION}}", req.classification().name())
                .replace("{{MAX_TASKS}}",
                        req.classification().maxTasks() == Integer.MAX_VALUE
                                ? "unlimited" : String.valueOf(req.classification().maxTasks()))
                .replace("{{WORKSPACE_SUMMARY}}",
                        req.workspaceSummary() == null ? "(no summary)" : req.workspaceSummary())
                .replace("{{PROJECT_MEMORY}}", renderProjectMemoryBlock(req.projectMemory()))
                .replace("{{PROJECT_BRIEF}}", req.projectBrief() == null ? "" : req.projectBrief())
                .replace("{{REFERENCE_DOCS}}", req.referenceDocs() == null ? "" : req.referenceDocs())
                .replace("{{TDD_MODE_BLOCK}}", renderTddBlock(req.tddMode()))
                .replace("{{MOBILE_PREFERENCE_BLOCK}}", "")
                .replace("{{PR_REVIEW_COMMENTS}}", "");

        String userMsg = (retryFeedback == null || retryFeedback.isBlank())
                ? "Produce the plan now."
                : retryFeedback + "\n\nProduce the corrected plan now.";
        List<Message> messages = List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userMsg));

        ToolCallback submitOutline = buildSubmitOutlineCallback();
        LlmProvider plannerProvider = resolveProviderForTitle(req.provider());
        ResolvedModel resolved = modelResolver.resolve("OutlinePlannerAgent", plannerProvider);
        String plannerModel = resolved.getModel();
        String plannerProviderName = resolved.providerName();

        log.info("OutlinePlanner -> {} {} (classification={}, maxTokens={})",
                plannerProviderName, plannerModel, req.classification(), OUTLINE_MAX_TOKENS);
        ChatResponse resp = router.call(LlmRequestV2.builder()
                .provider(plannerProviderName)
                .model(plannerModel)
                .messages(messages)
                .context(req.context())
                .temperature(0.0)
                .maxTokens(OUTLINE_MAX_TOKENS)
                .toolsOverride(List.of(submitOutline))
                .build());

        AssistantMessage.ToolCall planCall = null;
        for (var gen : resp.getResults()) {
            if (gen.getOutput().getToolCalls() != null) {
                for (var tc : gen.getOutput().getToolCalls()) {
                    if ("submitOutline".equals(tc.name())) {
                        planCall = tc;
                        break;
                    }
                }
            }
            if (planCall != null) break;
        }

        if (planCall == null) {
            String textPreview = resp.getResult().getOutput().getText();
            String finishReason = String.valueOf(resp.getResult().getMetadata().getFinishReason());
            // Same Gemini pseudo-code salvage path as PlannerAgentV2 — text-form
            // tool-call recovery. The salvage helper is tool-name-agnostic; it
            // pulls a JSON-ish payload out of any "default_api.*(...)" call.
            String salvaged = plannerAgent.salvagePlanFromText(textPreview);
            if (salvaged != null) {
                log.warn("OutlinePlanner did not emit a structured submitOutline call (finish={}); salvaged text-form args",
                        finishReason);
                PlanV2 plan = plannerAgent.parsePlanFromArgs(salvaged, req.classification());
                plannerAgent.warnIfConstraintsUnderpropagated(plan, req.spec());
                return plan;
            }
            throw new IllegalStateException(
                    "OutlinePlanner did not call submitOutline. Finish reason: " + finishReason
                            + "; text preview: " + (textPreview == null ? "" : PlannerAgentV2.truncate(textPreview, 200)));
        }
        PlanV2 plan = plannerAgent.parsePlanFromArgs(planCall.arguments(), req.classification());
        plannerAgent.warnIfConstraintsUnderpropagated(plan, req.spec());
        return plan;
    }

    // ─── tool definition ────────────────────────────────────────────────────

    private ToolCallback buildSubmitOutlineCallback() {
        // Slice 2: identical schema to submitPlan. Slice 3 will branch on
        // strategy — emit skeleton-only tasks for LARGE, defer body fields
        // (description / hardConstraints / meetsRequirements) to TaskExpander.
        ToolDefinition def = DefaultToolDefinition.builder()
                .name("submitOutline")
                .description("Submit the final outline-mode execution plan. Call exactly once with the complete "
                        + "plan. After this call the planning phase ends.")
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
                              "description": "How the code runs: HTTP, CLI, LIBRARY, STATIC_WEB. Default HTTP."
                            },
                            "requirements": {
                              "type": "array",
                              "description": "v2.5.11 — enumerate EVERY requirement from the spec.",
                              "items": {
                                "type": "object",
                                "properties": {
                                  "id": {"type": "string", "description": "Short ID like R1, R2, R3"},
                                  "text": {"type": "string", "description": "One-sentence requirement statement"},
                                  "kind": {"type": "string", "description": "FUNCTIONAL | NON_FUNCTIONAL | TEST | CONSTRAINT"}
                                },
                                "required": ["id", "text"]
                              }
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
                                  "hardConstraints": {"type": "array", "items": {"type": "string"}},
                                  "meetsRequirements": {
                                    "type": "array",
                                    "description": "Requirement IDs (from the top-level requirements array) this task implements.",
                                    "items": {"type": "string"}
                                  }
                                },
                                "required": ["id", "title", "description"]
                              }
                            }
                          },
                          "required": ["planTitle", "planSummary", "tasks"]
                        }
                        """)
                .build();
        final String callbackId = "outline-planner-" + UUID.randomUUID();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return def; }
            @Override public String call(String toolInput) {
                // Like submitPlan's callback — never actually dispatched. The router
                // returns the tool call object; we read arguments directly.
                log.debug("submitOutline callback invoked unexpectedly ({}); args={}",
                        callbackId, PlannerAgentV2.truncate(toolInput, 200));
                return "{\"ok\":true,\"note\":\"outline captured\"}";
            }
        };
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private String loadPrompt() {
        try {
            return new ClassPathResource(PROMPT_PATH).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("OutlinePlanner prompt missing at " + PROMPT_PATH, e);
        }
    }

    private static String renderProjectMemoryBlock(String projectMemory) {
        if (projectMemory == null || projectMemory.isBlank()) return "(no project memory yet)";
        return projectMemory;
    }

    private static String renderTddBlock(boolean tddMode) {
        if (!tddMode) return "";
        return """
                ## TDD MODE
                The user requested test-first ordering. Lead with test tasks. Implementation
                tasks must declare `dependsOn` the test task IDs they implement against.
                """;
    }

    /**
     * Resolve the {@link LlmProvider} enum from the request's provider string.
     * Mirrors {@code PlannerAgentV2#resolveProviderForTitle} so adaptive routing
     * picks the right tier model. Falls back to OPENAI on unrecognised input.
     */
    private static LlmProvider resolveProviderForTitle(String provider) {
        if (provider == null || provider.isBlank()) return LlmProvider.OPENAI;
        try {
            return LlmProvider.valueOf(provider.trim().toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return LlmProvider.OPENAI;
        }
    }
}
