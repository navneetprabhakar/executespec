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
    private final RunTitleSummarizer titleSummarizer;
    // v2.5.12 — resolve PLANNING-tier model from the matrix instead of using
    // the run-level worker default (was: req.model()). Closes the CLAUDE.md
    // "planner model sourced from run model, not tier" tech debt.
    private final com.navneet.executespec.router.ModelResolver modelResolver;
    /** v2.5.11 — gates strict v3 planner behaviour (requirement enumeration + validation). */
    private final com.navneet.executespec.config.featureflag.FeatureFlagService featureFlagService;

    /** v2.5.11 — feature flag name gating the whole validation v3 rollout. */
    private static final String FLAG_V3_STRICT = "validation.v3-strict";

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
        log.info("PlannerAgentV2 SINGLE_SHOT synthesize (LIGHTWEIGHT title call only): spec={}chars", req.spec().length());
        // LIGHTWEIGHT-tier title summarizer; falls back to deterministic cleanTitle.
        com.navneet.executespec.router.LlmProvider provider = resolveProviderForTitle(req.provider());
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
        // v2.5.11 (Validation v3 / Pillar 1) — synthesize a single requirement
        // so SINGLE_SHOT plans have the same coverage contract as LLM-driven ones.
        // Requirement text = first line / 120 chars of the spec.
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

    // ─── LLM-driven planner ─────────────────────────────────────────────────

    private PlanV2 plannerViaLlm(PlannerRequestV2 req) {
        // First attempt
        PlanV2 plan = plannerViaLlmOnce(req, null);

        // v2.5.11 (Validation v3 / Pillar 1) — strict validation + one retry.
        // Only enforced when the flag is on so legacy runs keep their current
        // behaviour. Flag off = log-only constraint underpropagation warning.
        if (featureFlagService != null && featureFlagService.isEnabled(FLAG_V3_STRICT)) {
            List<String> violations = validatePlan(plan);
            if (!violations.isEmpty()) {
                String feedback = "Your previous plan had these requirement-graph violations:\n  - "
                        + String.join("\n  - ", violations)
                        + "\n\nRe-emit the plan with every spec requirement enumerated and every "
                        + "task's meetsRequirements referencing real requirement IDs. "
                        + "Every requirement must be cited by at least one task.";
                log.warn("Planner plan invalid on first try ({} violations) — retrying. First violation: {}",
                        violations.size(), violations.get(0));
                PlanV2 retryPlan = plannerViaLlmOnce(req, feedback);
                List<String> retryViolations = validatePlan(retryPlan);
                if (!retryViolations.isEmpty()) {
                    throw new IllegalStateException(
                            "PLAN_COVERAGE_INVALID: planner produced an invalid requirement graph "
                                    + "even after one retry. Violations: "
                                    + String.join("; ", retryViolations));
                }
                plan = retryPlan;
            }
        }
        return plan;
    }

    /** One LLM round-trip + parse. Feedback (optional) is injected as an
     *  additional user message when retrying after a validation failure. */
    private PlanV2 plannerViaLlmOnce(PlannerRequestV2 req, String feedback) {
        String systemPrompt = loadPrompt()
                .replace("{{SPEC}}", req.spec())
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
                .replace("{{MOBILE_PREFERENCE_BLOCK}}", renderMobilePreferenceBlock())
                .replace("{{PR_REVIEW_COMMENTS}}", renderPrReviewBlock(req.prReviewComments()));

        String userMsg = (feedback == null || feedback.isBlank())
                ? "Produce the plan now."
                : feedback + "\n\nProduce the corrected plan now.";
        List<Message> messages = List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userMsg));

        ToolCallback submitPlan = buildSubmitPlanCallback();
        // v2.5.12 — resolve PLANNING-tier model from the matrix per provider.
        // Was req.model() (the run-level worker default → Haiku for Anthropic),
        // which made YAML matrix changes for the planner have no effect.
        com.navneet.executespec.router.LlmProvider plannerProvider =
                resolveProviderForTitle(req.provider());
        com.navneet.executespec.router.ResolvedModel resolved =
                modelResolver.resolve("PlannerAgentV2", plannerProvider);
        String plannerModel = resolved.getModel();
        // v2.5.13 — ResolvedModel.providerName() emits the V2-canonical string
        // ("anthropic" / "openai" / "gemini"), mapping enum GOOGLE → "gemini".
        String plannerProviderName = resolved.providerName();
        ChatResponse resp = router.call(LlmRequestV2.builder()
                .provider(plannerProviderName)
                .model(plannerModel)
                .messages(messages)
                .context(req.context())
                .temperature(0.0)
                // v2.5.13 — bumped 4096 → 8192. Validation v3's required
                // requirements-graph emission + the new mobile-preference block
                // can push the planner's tool-call JSON past the 4k ceiling,
                // which truncates the arguments mid-emission and produces
                // "empty plan" failures. Haiku/sonnet/gpt all handle 8k cleanly.
                .maxTokens(8192)
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
            String finishReason = String.valueOf(resp.getResult().getMetadata().getFinishReason());
            // v2.5.9 Bug #3 — Gemini 2.5-flash occasionally emits Python-style
            // pseudo-code like `print(default_api.submitPlan(planTitle="...",
            // planSummary="..."))` and returns MALFORMED_FUNCTION_CALL. Try to
            // salvage by pulling the JSON-ish args out of the text.
            String salvaged = salvagePlanFromText(textPreview);
            if (salvaged != null) {
                log.warn("Planner LLM did not emit a structured tool call (finish={}); salvaged text-form args",
                        finishReason);
                PlanV2 plan = parsePlanFromArgs(salvaged, req.classification());
                warnIfConstraintsUnderpropagated(plan, req.spec());
                return plan;
            }
            throw new IllegalStateException(
                    "Planner LLM did not call submitPlan. Finish reason: " + finishReason
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
    /**
     * v2.5.11 (Validation v3 / Pillar 1) — validate the plan's requirement graph
     * against the hard contract:
     * <ol>
     *   <li>At least one requirement is declared (empty list = planner skipped step 1).</li>
     *   <li>Requirement IDs are unique and non-blank.</li>
     *   <li>Every {@code task.meetsRequirements} entry references a real requirement ID.</li>
     *   <li>Every requirement ID is cited by at least one task.</li>
     *   <li>If runtime surface is HTTP / CLI / LIBRARY and classification is not
     *       SINGLE_SHOT, at least one task or requirement must be test-oriented.</li>
     * </ol>
     *
     * Returns a list of violation strings — empty when the plan is clean.
     * Called from {@link #plannerViaLlm(PlannerRequestV2)} when the
     * {@code validation.v3-strict} flag is on.
     */
    List<String> validatePlan(PlanV2 plan) {
        List<String> out = new ArrayList<>();
        List<RequirementSpec> reqs = plan.requirements();
        if (reqs == null || reqs.isEmpty()) {
            out.add("No requirements enumerated (top-level 'requirements' array is empty).");
            // Without requirements we can't evaluate the rest of the rules.
            return out;
        }

        // Rule 2: unique non-blank IDs
        java.util.Set<String> ids = new java.util.HashSet<>();
        java.util.Set<String> duplicates = new java.util.LinkedHashSet<>();
        for (RequirementSpec r : reqs) {
            if (r.id() == null || r.id().isBlank()) {
                out.add("Requirement has blank id (text=" + truncate(r.text(), 60) + ").");
                continue;
            }
            if (!ids.add(r.id())) duplicates.add(r.id());
        }
        if (!duplicates.isEmpty()) {
            out.add("Duplicate requirement ids: " + duplicates);
        }

        // Rule 3 + 4: coverage
        java.util.Set<String> covered = new java.util.HashSet<>();
        java.util.Set<String> unknownRefs = new java.util.LinkedHashSet<>();
        for (PlannedTaskV2 t : plan.tasks()) {
            if (t.meetsRequirements() == null) continue;
            for (String refId : t.meetsRequirements()) {
                if (!ids.contains(refId)) unknownRefs.add(refId);
                else covered.add(refId);
            }
        }
        if (!unknownRefs.isEmpty()) {
            out.add("Task meetsRequirements references unknown ids: " + unknownRefs);
        }
        java.util.Set<String> uncovered = new java.util.LinkedHashSet<>(ids);
        uncovered.removeAll(covered);
        if (!uncovered.isEmpty()) {
            out.add("Requirements not covered by any task: " + uncovered);
        }

        // Rule 5: test requirement / test task for testable surfaces
        PlanV2.RuntimeSurface surface = plan.runtimeSurface();
        boolean testableSurface = surface == PlanV2.RuntimeSurface.HTTP
                || surface == PlanV2.RuntimeSurface.CLI
                || surface == PlanV2.RuntimeSurface.LIBRARY
                || surface == PlanV2.RuntimeSurface.MOBILE_APP;
        boolean isSingleShot = plan.classification() == Classification.SINGLE_SHOT;
        if (testableSurface && !isSingleShot) {
            boolean hasTestRequirement = reqs.stream()
                    .anyMatch(r -> r.kind() == RequirementSpec.RequirementKind.TEST);
            boolean hasTestTask = plan.tasks().stream().anyMatch(t ->
                    (t.title() != null && t.title().toLowerCase().contains("test"))
                            || (t.description() != null && t.description().toLowerCase().contains("test")));
            if (!hasTestRequirement && !hasTestTask) {
                out.add("No test task or TEST-kind requirement on a testable surface ("
                        + surface + ") — strict v3 requires at least one.");
            }
        }
        return out;
    }

    /** Package-private — also called by {@link OutlinePlannerAgent}. */
    void warnIfConstraintsUnderpropagated(PlanV2 plan, String spec) {
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

    /** Package-private — also called by {@link OutlinePlannerAgent} (slice 2 of
     *  hierarchical-planner rollout) so both agents emit identical PlanV2 shapes. */
    PlanV2 parsePlanFromArgs(String argsJson, Classification classification) {
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

        // v2.5.11 (Validation v3 / Pillar 1) — parse the enumerated requirements
        // list. Empty list on legacy-shaped plans (no "requirements" key) or when
        // the feature flag is off and the prompt didn't ask for them.
        List<RequirementSpec> requirements = parseRequirements(root.path("requirements"));

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
                .requirements(requirements)
                .build();
    }

    /** v2.5.11 — parse the planner's requirement array. Robust to missing fields
     *  and unknown kinds. Drops entries with blank id/text. */
    private List<RequirementSpec> parseRequirements(JsonNode arr) {
        if (arr == null || !arr.isArray()) return List.of();
        List<RequirementSpec> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String id = n.path("id").asText("").trim();
            String text = n.path("text").asText("").trim();
            if (id.isEmpty() || text.isEmpty()) continue;
            RequirementSpec.RequirementKind kind;
            try {
                String kStr = n.path("kind").asText("FUNCTIONAL").trim().toUpperCase();
                kind = RequirementSpec.RequirementKind.valueOf(kStr);
            } catch (IllegalArgumentException e) {
                kind = RequirementSpec.RequirementKind.FUNCTIONAL;
            }
            out.add(new RequirementSpec(id, text, kind));
        }
        return out;
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
        // v2.6.2 slice 3 — `type` field on the tool-call payload (LEAF | EPIC).
        // Default LEAF when missing (backward-compat with slice 1+2 plans that
        // predate the field). EPIC is only legitimate on skeleton-mode outlines.
        PlannedTaskV2.TaskType taskType;
        try {
            taskType = PlannedTaskV2.TaskType.valueOf(t.path("type").asText("LEAF").toUpperCase());
        } catch (IllegalArgumentException iae) {
            taskType = PlannedTaskV2.TaskType.LEAF;
        }
        // Slice 3 — descriptions can legitimately be blank in skeleton mode (the
        // expander fills them later). Slice 1+2 plans always include the field.
        String description = t.path("description").asText("");
        if (description.isBlank() && taskType == PlannedTaskV2.TaskType.LEAF) {
            // Legacy LEAF tasks must have a description for the worker prompt.
            // Stick the placeholder so the worker doesn't get an empty user prompt.
            description = "(no description)";
        }
        return PlannedTaskV2.builder()
                .id(id)
                .title(t.path("title").asText("(untitled)"))
                .description(description)
                .complexity(cx)
                .touchesFiles(stringList(t.path("touchesFiles")))
                .dependsOn(stringList(t.path("dependsOn")))
                .hardConstraints(stringList(t.path("hardConstraints")))
                .meetsRequirements(stringList(t.path("meetsRequirements")))
                .taskType(taskType)
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
                            "requirements": {
                              "type": "array",
                              "description": "v2.5.11 — enumerate EVERY requirement from the spec. Each requirement will be cited by at least one task's meetsRequirements and later verified with evidence by the reviewer.",
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
                                    "description": "v2.5.11 — requirement IDs (from the top-level requirements array) this task implements. Every requirement must be cited by at least one task.",
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

    /**
     * v2.5.2 (Phase 4.3) — when TDD mode is on, prepend an instruction block
     * telling the planner to lead with test tasks. Empty string when off.
     */
    private String renderPrReviewBlock(String prReviewComments) {
        if (prReviewComments == null || prReviewComments.isBlank()) return "";
        return """
                # PR Review Comments — Address These

                A previous iteration of this work was submitted as a pull request and
                received review feedback. Your primary objective is to address ALL of the
                review comments below. Read each comment carefully, understand the file and
                line it refers to, and make the requested changes. Use `readFile` and
                `editFile` (not `writeFile`) to modify existing code.

                """ + prReviewComments;
    }

    /** v2.5.13 — inject a directive biasing the planner toward React Native + Expo
     *  for mobile specs when the codegen.react_native.enabled flag is ON. The flag
     *  alone was a passive gate (accept iff planner happens to pick react-native-expo);
     *  this block makes it an active nudge so the Calorie Tracker / habit-tracker
     *  style mobile specs actually route there instead of falling to native Gradle
     *  (which the smoke container has no SDK for → FINAL_BUILD_FAILED). */
    private String renderMobilePreferenceBlock() {
        if (featureFlagService == null) return "";
        try {
            if (!featureFlagService.isEnabled("codegen.react_native.enabled")) return "";
        } catch (Exception e) {
            return "";
        }
        return """
                # Mobile preference — React Native + Expo (ACTIVE)

                The `codegen.react_native.enabled` feature flag is ON. When you infer that
                the spec is asking for a mobile application (phrases like "Android app",
                "iOS app", "mobile app", "cross-platform", "native or cross-platform"),
                you MUST set:
                - `projectType = "react-native-expo"`
                - `runtimeSurface = "MOBILE_APP"`
                - `buildCommand = "npx expo export --platform android --output-dir dist && npx tsc --noEmit"`
                - `smokeTestStrategy = "BUILD"`

                The ONLY reason to pick native Android (`./gradlew build`) or native iOS is
                if the spec explicitly requires a feature Expo managed workflow cannot provide
                (deep OS integration like Health APIs, NFC, custom native modules). Stylistic
                signals like "Material Design 3", "iOS-style", "native look" are NOT reasons
                to avoid Expo — it fully supports those through libraries. When in doubt,
                choose React Native + Expo: one codebase, both platforms, and the smoke
                container actually has the tooling to build it.

                Tasks must reflect the RN scaffold: package.json + app.json + eas.json +
                tsconfig.json + babel.config.js + index.ts + App.tsx at workspace root; then
                per-screen .tsx files; then Jest + @testing-library/react-native tests. Do
                NOT emit native `android/` or `ios/` directories — managed workflow only.

                """;
    }

    private String renderTddBlock(boolean tddMode) {
        if (!tddMode) return "";
        return """
                # TDD MODE — tests first

                Generate test tasks BEFORE implementation tasks. Specifically:
                - The first task group must be one or more tasks that write FAILING
                  tests for the feature being built (one test task per logical
                  unit — service / endpoint / component).
                - Every subsequent implementation task must declare `dependsOn`
                  the relevant test task ids so the executor runs tests first.
                - Tests must be committed files (not inline strings); the worker
                  uses writeFile to create them.
                - This is slower but materially higher accuracy on logic-heavy
                  specs. Do NOT skip this directive when TDD mode is on.
                """;
    }

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

    private static com.navneet.executespec.router.LlmProvider resolveProviderForTitle(String providerName) {
        // v2.5.13 bug fix — "GEMINI" (v2 provider alias) must map to the enum
        // name "GOOGLE". Previously this returned null for any Gemini run that
        // hit the LLM planner path (non-SINGLE_SHOT), causing the caller's
        // `provider.name()` to throw an NPE in ModelResolver.lookupModel.
        // Default to OPENAI for unknown values instead of null so the caller
        // never dereferences a null provider.
        if (providerName == null || providerName.isBlank()) {
            return com.navneet.executespec.router.LlmProvider.OPENAI;
        }
        String up = providerName.toUpperCase();
        if ("GEMINI".equals(up)) {
            return com.navneet.executespec.router.LlmProvider.GEMINI;
        }
        try {
            return com.navneet.executespec.router.LlmProvider.valueOf(up);
        } catch (IllegalArgumentException e) {
            return com.navneet.executespec.router.LlmProvider.OPENAI;
        }
    }

    // v2.5.13 bug fix (centralised) — the LlmProvider→V2-canonical mapping
    // now lives on {@code ResolvedModel.providerName()} so every call site
    // shares one source of truth. Use {@code resolved.providerName()}, never
    // {@code resolved.getActualProvider().name().toLowerCase()}.

    /**
     * v2.5.9 Bug #3 — Salvage a submitPlan invocation from text when the model
     * emits it as pseudo-code instead of a structured tool call. Observed with
     * Gemini 2.5-flash returning responses like
     * {@code print(default_api.submitPlan(planTitle="...", planSummary="...", tasks=[...]))}.
     *
     * <p>Returns JSON args string (as {@code submitPlan} expects) or null if the
     * text doesn't contain a recoverable call.
     */
    /** Package-private — also called by {@link OutlinePlannerAgent}. */
    String salvagePlanFromText(String text) {
        if (text == null || text.isBlank()) return null;
        // Look for a submitPlan invocation anywhere in the text
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("submitPlan\\s*\\(([\\s\\S]*)\\)\\s*$",
                        java.util.regex.Pattern.MULTILINE)
                .matcher(text);
        if (!m.find()) return null;
        String args = m.group(1).trim();
        // Convert Python kwarg syntax (name=value) to JSON object. Very rough.
        // Only the common case: `planTitle="...", planSummary="...", tasks=[...]`
        StringBuilder json = new StringBuilder("{");
        // Split on top-level commas (naive — assumes args are well-formed)
        int depth = 0; int start = 0;
        java.util.List<String> pairs = new java.util.ArrayList<>();
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                pairs.add(args.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (start < args.length()) pairs.add(args.substring(start).trim());
        for (int i = 0; i < pairs.size(); i++) {
            String pair = pairs.get(i);
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = pair.substring(0, eq).trim();
            String val = pair.substring(eq + 1).trim();
            // Transform Python literals to JSON: True/False/None → true/false/null
            val = val.replaceAll("\\bTrue\\b", "true")
                    .replaceAll("\\bFalse\\b", "false")
                    .replaceAll("\\bNone\\b", "null");
            if (i > 0) json.append(",");
            json.append("\"").append(key).append("\":").append(val);
        }
        json.append("}");
        String candidate = json.toString();
        // Basic sanity check — must contain the top-level keys the planner needs.
        if (!candidate.contains("tasks")) return null;
        return candidate;
    }

    static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * Produces a concise, title-cased run name from a free-form spec. Used by
     * the SINGLE_SHOT path so project-view tables show readable titles instead
     * of raw "Single-shot: ..." echoes.
     */
    private static String cleanTitle(String spec, int max) {
        if (spec == null || spec.isBlank()) return "Untitled run";
        String s = spec.trim().replaceAll("\\s+", " ");
        // First sentence / first line (whichever is shorter) — avoids pulling in
        // bullet lists or multi-paragraph rambles.
        int dot = s.indexOf('.');
        int nl  = s.indexOf('\n');
        int cut = -1;
        if (dot > 0) cut = dot;
        if (nl > 0 && (cut < 0 || nl < cut)) cut = nl;
        if (cut > 0) s = s.substring(0, cut).trim();
        // Capitalise first letter so "add /x endpoint" → "Add /x endpoint".
        if (!s.isEmpty() && Character.isLowerCase(s.charAt(0))) {
            s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
        // Trim trailing punctuation.
        while (!s.isEmpty() && ",;:.".indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(0, s.length() - 1);
        }
        return s.length() <= max ? s : s.substring(0, max - 1).trim() + "…";
    }
}
