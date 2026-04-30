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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    /** v2.6.2 slice 5b — used by {@link #synthesizeSingleShot} to give the
     *  bypass path a sensible run title (LIGHTWEIGHT-tier call only). */
    private final RunTitleSummarizer titleSummarizer;

    private static final String PROMPT_PATH_FULL = "prompts/v2/outline-planner.md";
    /** v2.6.2 slice 3 — skeleton-mode prompt for LARGE/LEGACY classifications.
     *  Emits id/title/complexity/repos/dependsOn/type only — the expander fills
     *  the bodies in parallel. Smaller output budget needed because no
     *  descriptions / hardConstraints / meetsRequirements per task. */
    private static final String PROMPT_PATH_SKELETON = "prompts/v2/outline-planner-skeleton.md";
    /** v2.6.2 slice 2 — 32k breathing room. The old planner's 8192 cap truncated
     *  on multi-repo specs (the bug that drove the whole hierarchical rewrite).
     *  SMALL/MEDIUM specs are bounded by definition (≤15 tasks, ≤2 repos), so
     *  32k is comfortably enough for full descriptions + hardConstraints +
     *  meetsRequirements per task. */
    private static final int OUTLINE_MAX_TOKENS_FULL = 32_768;
    /** v2.6.2 slice 3 — skeleton outlines are tiny (id/title/complexity only)
     *  even for 50-task LARGE plans. 8k is plenty. */
    private static final int OUTLINE_MAX_TOKENS_SKELETON = 8_192;

    /**
     * [D-02] (coverage Sprint 3, 2026-05-01) — wall-clock timeout
     * for the synchronous planner LLM call. Was: a stalled provider
     * socket would leave the whole pipeline in PLANNING forever
     * (Spring AI's default RestClient retry could compound on top
     * of network hangs). Cap is 8 minutes — well above the
     * observed-good MEDIUM Express run's 4m36s but below any
     * reasonable user expectation for a "the planner has wedged"
     * heuristic. The phase 2 expander pool already enforces a 20-min
     * wall timeout; this closes the matching gap on phase 1.
     *
     * Hits raise an IllegalStateException so callers (V2RunOrchestrator)
     * can fall back to the legacy planner OR mark the run FAILED with a
     * clear reason instead of hanging indefinitely.
     */
    private static final long PLANNER_LLM_WALL_TIMEOUT_SECONDS = 8 * 60;

    /** v2.5.11 — gates strict v3 planner behaviour (requirement enumeration + validation). */
    private static final String FLAG_V3_STRICT = "validation.v3-strict";

    public enum Mode {
        /** SMALL/MEDIUM classifications — emit a complete plan in one tool call.
         *  Slice 2 default. */
        FULL_PLAN,
        /** LARGE/LEGACY classifications — emit task skeletons (id/title/complexity/
         *  repos/dependsOn/type) only; {@code TaskExpanderAgent} fills bodies in
         *  parallel afterwards. Slice 3+. */
        SKELETON_ONLY
    }

    /**
     * Convenience overload — defaults to {@link Mode#FULL_PLAN} (slice 2 contract).
     */
    public PlanV2 plan(PlannerRequestV2 req) {
        return plan(req, Mode.FULL_PLAN);
    }

    /**
     * v2.6.2 slice 5b — synthesise a one-task plan for {@code SINGLE_SHOT}
     * specs. No LLM planner call (the title summarizer is LIGHTWEIGHT and only
     * fires when the synthesised title would otherwise come from a regex
     * fallback). Migrated from {@code PlannerAgentV2#synthesizeSingleShotPlan}
     * so the legacy planner can be deleted.
     */
    public PlanV2 synthesizeSingleShot(PlannerRequestV2 req) {
        log.info("OutlinePlanner SINGLE_SHOT synthesise (LIGHTWEIGHT title call only): spec={}chars",
                req.spec() == null ? 0 : req.spec().length());
        LlmProvider provider = resolveProviderForTitle(req.provider());
        String runId = req.context() != null ? req.context().runId() : null;
        String summarized = titleSummarizer.summarize(req.spec(), provider, runId);
        String title = (summarized != null && !summarized.isBlank())
                ? summarized
                : cleanTitle(req.spec(), 70);
        String description = req.spec()
                + "\n\nAlso include unit tests for any new public function, endpoint, "
                + "or module this task adds. If a test framework already exists in the "
                + "workspace (Jest/Vitest, pytest, Go *_test.go, JUnit), extend it; "
                + "otherwise scaffold a minimal test file with at least one happy-path "
                + "test + one error case.";
        // v2.5.11 Validation v3 / Pillar 1 — synthesise a single requirement so
        // SINGLE_SHOT plans honour the same coverage contract as LLM-driven plans.
        String r1Text = req.spec();
        int nl = r1Text.indexOf('\n');
        if (nl > 0) r1Text = r1Text.substring(0, nl);
        if (r1Text.length() > 120) r1Text = r1Text.substring(0, 120) + "...";
        RequirementSpec r1 = new RequirementSpec("R1", r1Text.trim(),
                RequirementSpec.RequirementKind.FUNCTIONAL);
        PlannedTaskV2 task = PlannedTaskV2.builder()
                .id("T1")
                .title(title)
                .description(description)
                .complexity(PlannedTaskV2.Complexity.LOW)
                .taskType(PlannedTaskV2.TaskType.LEAF)
                .meetsRequirements(List.of("R1"))
                .build();
        return PlanV2.builder()
                .planTitle(title)
                .planSummary(req.spec())
                .projectType("unknown")
                .smokeTestStrategy(PlanV2.SmokeTestStrategy.BUILD)
                .runtimeSurface(PlanV2.RuntimeSurface.HTTP)
                .classification(Classification.SINGLE_SHOT)
                .tasks(List.of(task))
                .requirements(List.of(r1))
                .build();
    }

    /** Deterministic title fallback when the title summarizer doesn't produce
     *  anything usable. Delegates to {@link PlannerOutputUtils#cleanTitle}. */
    private static String cleanTitle(String spec, int max) {
        return PlannerOutputUtils.cleanTitle(spec, max);
    }

    /**
     * Run the outline phase. Synchronous — cost flows through {@link LlmRouterV2} →
     * {@link com.navneet.executespec.v2.cost.V2RunCostTracker} automatically; the
     * orchestrator diffs the per-run snapshot before/after to attribute the phase
     * to {@code pipeline_run.outline_*} columns.
     *
     * <p>{@link Mode#FULL_PLAN} emits descriptions + hardConstraints + meetsRequirements
     * per task. {@link Mode#SKELETON_ONLY} emits id/title/complexity/repos/dependsOn/
     * type — the expander fills the bodies. Skeleton mode bypasses validation-v3
     * coverage checks (those need filled-in meetsRequirements which haven't been
     * written yet); coverage is re-checked post-expansion in the coherence pass.
     */
    public PlanV2 plan(PlannerRequestV2 req, Mode mode) {
        long start = System.currentTimeMillis();
        try {
            PlanV2 plan = planOnce(req, null, mode);

            // v2.5.11 (Validation v3 / Pillar 1) — strict validation + one retry.
            // Gated by feature flag; off → log-only behaviour. SKELETON_ONLY mode
            // skips this — meetsRequirements is intentionally blank pre-expansion;
            // the coherence pass post-expansion runs the same check.
            if (mode == Mode.FULL_PLAN
                    && featureFlagService != null
                    && featureFlagService.isEnabled(FLAG_V3_STRICT)) {
                List<String> violations = PlannerOutputUtils.validatePlan(plan);
                if (!violations.isEmpty()) {
                    String feedback = "Your previous plan had these requirement-graph violations:\n  - "
                            + String.join("\n  - ", violations)
                            + "\n\nRe-emit the plan with every spec requirement enumerated and every "
                            + "task's meetsRequirements referencing real requirement IDs. "
                            + "Every requirement must be cited by at least one task.";
                    log.warn("OutlinePlanner plan invalid on first try ({} violations) — retrying. First violation: {}",
                            violations.size(), violations.get(0));
                    PlanV2 retry = planOnce(req, feedback, mode);
                    List<String> retryViolations = PlannerOutputUtils.validatePlan(retry);
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

    private PlanV2 planOnce(PlannerRequestV2 req, String retryFeedback, Mode mode) {
        String promptPath = (mode == Mode.SKELETON_ONLY) ? PROMPT_PATH_SKELETON : PROMPT_PATH_FULL;
        int maxTokens = (mode == Mode.SKELETON_ONLY)
                ? OUTLINE_MAX_TOKENS_SKELETON : OUTLINE_MAX_TOKENS_FULL;
        String systemPrompt = loadPrompt(promptPath)
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

        ToolCallback submitOutline = buildSubmitOutlineCallback(mode);
        LlmProvider plannerProvider = resolveProviderForTitle(req.provider());
        ResolvedModel resolved = modelResolver.resolve("OutlinePlannerAgent", plannerProvider);
        String plannerModel = resolved.getModel();
        String plannerProviderName = resolved.providerName();

        log.info("OutlinePlanner [{}] -> {} {} (classification={}, maxTokens={}, wallTimeout={}s)",
                mode, plannerProviderName, plannerModel, req.classification(), maxTokens,
                PLANNER_LLM_WALL_TIMEOUT_SECONDS);

        // [D-02] (coverage Sprint 3, 2026-05-01) — wall-clock timeout.
        // CompletableFuture.supplyAsync runs the synchronous router.call
        // on the common ForkJoinPool; .get(timeout) interrupts and
        // propagates a TimeoutException if the LLM provider stalls. Was:
        // a wedged provider socket (network partition, hung TCP keepalive,
        // provider-side queue jam) left the run in PLANNING indefinitely
        // because Spring AI's RestClient retry chain has no overall
        // ceiling. The 8-minute cap matches "Phase 2 expander 20-min /
        // 4 = per-call ceiling" rule of thumb already used in
        // V2RunOrchestrator.runLargePipeline.
        ChatResponse resp;
        try {
            CompletableFuture<ChatResponse> fut = CompletableFuture.supplyAsync(() ->
                router.call(LlmRequestV2.builder()
                        .provider(plannerProviderName)
                        .model(plannerModel)
                        .messages(messages)
                        .context(req.context())
                        .temperature(0.0)
                        .maxTokens(maxTokens)
                        .toolsOverride(List.of(submitOutline))
                        .build())
            );
            resp = fut.get(PLANNER_LLM_WALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            log.error("OutlinePlanner [{}] WALL_TIMEOUT after {}s — provider {} may be stalled",
                    mode, PLANNER_LLM_WALL_TIMEOUT_SECONDS, plannerProviderName);
            throw new IllegalStateException(
                "OutlinePlanner LLM call timed out after " + PLANNER_LLM_WALL_TIMEOUT_SECONDS
                + "s. Provider may be stalled or rate-limited. Run can be retried.", te);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OutlinePlanner interrupted", ie);
        } catch (ExecutionException ee) {
            // Unwrap to surface the original LLM error (401, 5xx, etc.) so
            // callers / fallbacks see the same cause they would have without
            // the timeout wrapper.
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            if (cause instanceof RuntimeException re) throw re;
            throw new IllegalStateException("OutlinePlanner LLM call failed", cause);
        }

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
            String salvaged = PlannerOutputUtils.salvagePlanFromText(textPreview);
            if (salvaged != null) {
                log.warn("OutlinePlanner did not emit a structured submitOutline call (finish={}); salvaged text-form args",
                        finishReason);
                PlanV2 plan = PlannerOutputUtils.parsePlanFromArgs(salvaged, req.classification(), objectMapper);
                PlannerOutputUtils.warnIfConstraintsUnderpropagated(plan, req.spec());
                return plan;
            }
            throw new IllegalStateException(
                    "OutlinePlanner did not call submitOutline. Finish reason: " + finishReason
                            + "; text preview: " + (textPreview == null ? "" : PlannerOutputUtils.truncate(textPreview, 200)));
        }
        PlanV2 plan = PlannerOutputUtils.parsePlanFromArgs(planCall.arguments(), req.classification(), objectMapper);
        PlannerOutputUtils.warnIfConstraintsUnderpropagated(plan, req.spec());
        return plan;
    }

    // ─── tool definition ────────────────────────────────────────────────────

    private ToolCallback buildSubmitOutlineCallback(Mode mode) {
        String description = (mode == Mode.SKELETON_ONLY)
                ? ("Submit a SKELETON outline — task ids/titles/complexity/repos/dependsOn/type only. "
                        + "Descriptions, hardConstraints, and meetsRequirements will be filled in by the "
                        + "task expander afterwards. Call exactly once.")
                : ("Submit the final outline-mode execution plan. Call exactly once with the complete "
                        + "plan. After this call the planning phase ends.");
        ToolDefinition def = DefaultToolDefinition.builder()
                .name("submitOutline")
                .description(description)
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
                                  "description": {"type": "string", "description": "Full task description in FULL_PLAN mode; OMIT in SKELETON_ONLY mode (the expander fills it)."},
                                  "complexity": {"type": "string", "description": "LOW | MEDIUM | HIGH"},
                                  "type": {"type": "string", "description": "LEAF (terminal task) or EPIC (will be expanded into subtasks). Default LEAF."},
                                  "touchesFiles": {"type": "array", "items": {"type": "string"}},
                                  "dependsOn": {"type": "array", "items": {"type": "string"}},
                                  "hardConstraints": {"type": "array", "items": {"type": "string"}},
                                  "meetsRequirements": {
                                    "type": "array",
                                    "description": "Requirement IDs (from the top-level requirements array) this task implements. OMIT in SKELETON_ONLY mode.",
                                    "items": {"type": "string"}
                                  }
                                },
                                "required": ["id", "title"]
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
                        callbackId, PlannerOutputUtils.truncate(toolInput, 200));
                return "{\"ok\":true,\"note\":\"outline captured\"}";
            }
        };
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private String loadPrompt(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("OutlinePlanner prompt missing at " + path, e);
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

    /** Delegates to {@link PlannerOutputUtils#resolveProviderForTitle}. */
    private static LlmProvider resolveProviderForTitle(String provider) {
        return PlannerOutputUtils.resolveProviderForTitle(provider);
    }
}
