package com.navneet.executespec.v2.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.v2.tool.ExecutionContext;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 3 of the hierarchical planner — final coherence check on the assembled
 * plan after expansion. Detects requirement-coverage gaps, dangling
 * {@code dependsOn} references, conflicting hardConstraints, and multi-repo
 * file-path leaks. Emits a {@code patchPlan} call with minimal deltas; the
 * orchestrator applies the patches and feeds the corrected plan to the
 * sequential executor.
 *
 * <p>LIGHTWEIGHT-tier model — coherence is a structural pattern-match, not a
 * judgement call. Single LLM round-trip, ≤1k output tokens. Failure of the
 * coherence pass is non-fatal: the unpatched plan flows through and the
 * worker / reviewer catch any remaining issues at execution time.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CoherencePlannerAgent {

    private final LlmRouterV2 router;
    private final ObjectMapper objectMapper;
    private final ModelResolver modelResolver;

    private static final String PROMPT_PATH = "prompts/v2/coherence-planner.md";
    private static final int COHERENCE_MAX_TOKENS = 1_500;

    public record PatchResult(PlanV2 patchedPlan, int patchesApplied, List<String> patchSummaries) {}

    /**
     * Run the coherence pass over an assembled plan. Returns a possibly-patched
     * plan along with summary strings for each applied patch. On LLM failure
     * the input plan is returned unchanged with {@code patchesApplied=0}.
     */
    public PatchResult patchIfNeeded(PlanV2 plan, PlannerRequestV2 req, ExecutionContext context) {
        if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) {
            return new PatchResult(plan, 0, List.of());
        }

        String systemPrompt;
        try {
            systemPrompt = loadPrompt()
                    .replace("{{PLAN_SUMMARY}}", safe(plan.planSummary()))
                    .replace("{{PLAN_META}}",
                            "projectType=" + safe(plan.projectType())
                                    + ", runtimeSurface=" + (plan.runtimeSurface() == null ? "HTTP" : plan.runtimeSurface().name())
                                    + ", buildCommand=" + safe(plan.buildCommand()))
                    .replace("{{WORKSPACE_SUMMARY}}", safe(req.workspaceSummary()))
                    .replace("{{REQUIREMENTS}}", renderRequirements(plan))
                    .replace("{{TASKS}}", renderTasks(plan));
        } catch (Exception promptEx) {
            log.warn("CoherencePlanner prompt assembly failed — skipping pass: {}", promptEx.getMessage());
            return new PatchResult(plan, 0, List.of());
        }

        List<Message> messages = List.of(
                new SystemMessage(systemPrompt),
                new UserMessage("Run the coherence pass now."));

        ToolCallback patchPlan = buildPatchPlanCallback();
        LlmProvider provider = resolveProvider(req.provider());
        ResolvedModel resolved;
        try {
            resolved = modelResolver.resolve("CoherencePlannerAgent", provider, AgentTier.LIGHTWEIGHT);
        } catch (Exception e) {
            log.warn("CoherencePlanner model resolve failed — skipping pass: {}", e.getMessage());
            return new PatchResult(plan, 0, List.of());
        }

        log.info("CoherencePlanner -> {} {} (tasks={}, requirements={})",
                resolved.providerName(), resolved.getModel(),
                plan.tasks().size(),
                plan.requirements() == null ? 0 : plan.requirements().size());

        ChatResponse resp;
        try {
            resp = router.call(LlmRequestV2.builder()
                    .provider(resolved.providerName())
                    .model(resolved.getModel())
                    .messages(messages)
                    .context(context)
                    .temperature(0.0)
                    .maxTokens(COHERENCE_MAX_TOKENS)
                    .toolsOverride(List.of(patchPlan))
                    .build());
        } catch (Exception e) {
            log.warn("CoherencePlanner LLM call failed — using unpatched plan: {}", e.getMessage());
            return new PatchResult(plan, 0, List.of());
        }

        AssistantMessage.ToolCall patchCall = null;
        for (var gen : resp.getResults()) {
            if (gen.getOutput().getToolCalls() != null) {
                for (var tc : gen.getOutput().getToolCalls()) {
                    if ("patchPlan".equals(tc.name())) {
                        patchCall = tc;
                        break;
                    }
                }
            }
            if (patchCall != null) break;
        }
        if (patchCall == null) {
            log.warn("CoherencePlanner did not call patchPlan — using unpatched plan");
            return new PatchResult(plan, 0, List.of());
        }

        return applyPatches(plan, patchCall.arguments());
    }

    private PatchResult applyPatches(PlanV2 plan, String argsJson) {
        JsonNode root;
        try {
            root = objectMapper.readTree(argsJson);
        } catch (Exception e) {
            log.warn("patchPlan args are not valid JSON — using unpatched plan: {}", e.getMessage());
            return new PatchResult(plan, 0, List.of());
        }
        JsonNode patches = root.path("patches");
        if (!patches.isArray() || patches.size() == 0) {
            log.info("CoherencePlanner: 0 patches needed — plan is clean");
            return new PatchResult(plan, 0, List.of());
        }

        // Mutable working copy so we can apply patches one at a time.
        Map<String, MutableTask> byId = new LinkedHashMap<>();
        for (PlannedTaskV2 t : plan.tasks()) {
            byId.put(t.id(), MutableTask.from(t));
        }

        List<String> summaries = new ArrayList<>();
        int applied = 0;
        for (JsonNode patch : patches) {
            String kind = patch.path("kind").asText("").trim();
            String taskId = patch.path("taskId").asText("").trim();
            String reason = patch.path("reason").asText("").trim();
            MutableTask task = byId.get(taskId);
            if (task == null) {
                log.warn("CoherencePlanner: patch targets unknown taskId={} (kind={}) — skipping", taskId, kind);
                continue;
            }
            try {
                switch (kind) {
                    case "ADD_MEETS_REQUIREMENT" -> {
                        String reqId = patch.path("requirementId").asText("").trim();
                        if (reqId.isEmpty()) break;
                        if (!task.meetsRequirements.contains(reqId)) {
                            task.meetsRequirements.add(reqId);
                            applied++;
                            summaries.add(taskId + " += meetsRequirement[" + reqId + "] · " + reason);
                        }
                    }
                    case "ADD_DEPENDS_ON" -> {
                        String dep = patch.path("dependsOnTaskId").asText("").trim();
                        if (dep.isEmpty()) break;
                        if (!byId.containsKey(dep)) {
                            log.warn("CoherencePlanner: ADD_DEPENDS_ON targets unknown task {}, skipping", dep);
                            break;
                        }
                        if (!task.dependsOn.contains(dep)) {
                            task.dependsOn.add(dep);
                            applied++;
                            summaries.add(taskId + " += dependsOn[" + dep + "] · " + reason);
                        }
                    }
                    case "REMOVE_DEPENDS_ON" -> {
                        String dep = patch.path("dependsOnTaskId").asText("").trim();
                        if (task.dependsOn.remove(dep)) {
                            applied++;
                            summaries.add(taskId + " -= dependsOn[" + dep + "] · " + reason);
                        }
                    }
                    case "FIX_HARD_CONSTRAINT_CONFLICT" -> {
                        String newConstraint = patch.path("newConstraint").asText("").trim();
                        if (newConstraint.isEmpty()) break;
                        // Simplest semantics: append the corrected constraint. The worker's
                        // system prompt sees both the old + new; the prompt's TDD wording
                        // tells it the newest entry wins on conflict. Heavy-handed; we
                        // can refine in slice 5.
                        task.hardConstraints.add(newConstraint);
                        applied++;
                        summaries.add(taskId + " hardConstraint += '"
                                + truncate(newConstraint, 60) + "' · " + reason);
                    }
                    case "MOVE_FILE_TO_REPO_SUBDIR" -> {
                        String from = patch.path("from").asText("").trim();
                        String to = patch.path("to").asText("").trim();
                        if (from.isEmpty() || to.isEmpty()) break;
                        boolean changed = task.touchesFiles.remove(from);
                        if (!task.touchesFiles.contains(to)) task.touchesFiles.add(to);
                        if (changed) {
                            applied++;
                            summaries.add(taskId + " moved file " + from + " → " + to + " · " + reason);
                        }
                    }
                    default -> log.warn("CoherencePlanner: unknown patch kind '{}' — skipping", kind);
                }
            } catch (Exception applyEx) {
                log.warn("CoherencePlanner: patch apply failed kind={} task={} — {}", kind, taskId, applyEx.getMessage());
            }
        }

        if (applied == 0) {
            log.info("CoherencePlanner: 0 patches applied (LLM emitted patches but none were valid)");
            return new PatchResult(plan, 0, List.of());
        }

        List<PlannedTaskV2> patchedTasks = new ArrayList<>(byId.size());
        for (PlannedTaskV2 original : plan.tasks()) {
            patchedTasks.add(byId.get(original.id()).toImmutable(original));
        }
        PlanV2 patchedPlan = PlanV2.builder()
                .planTitle(plan.planTitle())
                .planSummary(plan.planSummary())
                .projectType(plan.projectType())
                .buildCommand(plan.buildCommand())
                .smokeTestStrategy(plan.smokeTestStrategy())
                .runtimeSurface(plan.runtimeSurface())
                .tasks(patchedTasks)
                .classification(plan.classification())
                .requirements(plan.requirements())
                .build();

        log.info("CoherencePlanner applied {} patches", applied);
        return new PatchResult(patchedPlan, applied, summaries);
    }

    // ─── tool definition ────────────────────────────────────────────────────

    private ToolCallback buildPatchPlanCallback() {
        ToolDefinition def = DefaultToolDefinition.builder()
                .name("patchPlan")
                .description("Submit zero or more minimal patches to fix coherence issues in the assembled plan. "
                        + "Empty patches array = clean plan, no changes needed. Each patch must have kind, "
                        + "taskId, and a reason.")
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "patches": {
                              "type": "array",
                              "items": {
                                "type": "object",
                                "properties": {
                                  "kind": {"type": "string", "description": "ADD_MEETS_REQUIREMENT | ADD_DEPENDS_ON | REMOVE_DEPENDS_ON | FIX_HARD_CONSTRAINT_CONFLICT | MOVE_FILE_TO_REPO_SUBDIR"},
                                  "taskId": {"type": "string"},
                                  "requirementId": {"type": "string", "description": "For ADD_MEETS_REQUIREMENT"},
                                  "dependsOnTaskId": {"type": "string", "description": "For ADD/REMOVE_DEPENDS_ON"},
                                  "newConstraint": {"type": "string", "description": "For FIX_HARD_CONSTRAINT_CONFLICT"},
                                  "from": {"type": "string", "description": "For MOVE_FILE_TO_REPO_SUBDIR"},
                                  "to": {"type": "string", "description": "For MOVE_FILE_TO_REPO_SUBDIR"},
                                  "reason": {"type": "string"}
                                },
                                "required": ["kind", "taskId", "reason"]
                              }
                            }
                          },
                          "required": ["patches"]
                        }
                        """)
                .build();
        final String callbackId = "coherence-" + UUID.randomUUID();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return def; }
            @Override public String call(String toolInput) {
                log.debug("patchPlan callback invoked unexpectedly ({}); args={}",
                        callbackId, truncate(toolInput, 200));
                return "{\"ok\":true}";
            }
        };
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private String loadPrompt() {
        try {
            return new ClassPathResource(PROMPT_PATH).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("CoherencePlanner prompt missing at " + PROMPT_PATH, e);
        }
    }

    private static String renderRequirements(PlanV2 plan) {
        if (plan.requirements() == null || plan.requirements().isEmpty()) {
            return "(no requirements declared)";
        }
        StringBuilder sb = new StringBuilder();
        for (RequirementSpec r : plan.requirements()) {
            sb.append("- ").append(r.id()).append(" [")
              .append(r.kind() == null ? "FUNCTIONAL" : r.kind().name())
              .append("] ").append(r.text()).append("\n");
        }
        return sb.toString();
    }

    private static String renderTasks(PlanV2 plan) {
        StringBuilder sb = new StringBuilder();
        for (PlannedTaskV2 t : plan.tasks()) {
            sb.append("Task ").append(t.id())
              .append(" [").append(t.complexity() == null ? "MEDIUM" : t.complexity().name())
              .append("/").append(t.taskType() == null ? "LEAF" : t.taskType().name()).append("]: ")
              .append(t.title()).append("\n");
            if (t.description() != null && !t.description().isBlank()) {
                sb.append("  description: ").append(truncate(t.description(), 240)).append("\n");
            }
            if (t.dependsOn() != null && !t.dependsOn().isEmpty()) {
                sb.append("  dependsOn: ").append(String.join(", ", t.dependsOn())).append("\n");
            }
            if (t.touchesFiles() != null && !t.touchesFiles().isEmpty()) {
                sb.append("  touchesFiles: ").append(String.join(", ", t.touchesFiles())).append("\n");
            }
            if (t.hardConstraints() != null && !t.hardConstraints().isEmpty()) {
                sb.append("  hardConstraints: ").append(String.join(" | ", t.hardConstraints())).append("\n");
            }
            if (t.meetsRequirements() != null && !t.meetsRequirements().isEmpty()) {
                sb.append("  meetsRequirements: ").append(String.join(", ", t.meetsRequirements())).append("\n");
            }
        }
        return sb.toString();
    }

    private static LlmProvider resolveProvider(String provider) {
        if (provider == null || provider.isBlank()) return LlmProvider.OPENAI;
        try {
            return LlmProvider.valueOf(provider.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return LlmProvider.OPENAI;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static final class MutableTask {
        final List<String> dependsOn;
        final List<String> hardConstraints;
        final List<String> meetsRequirements;
        final List<String> touchesFiles;

        private MutableTask(List<String> dependsOn, List<String> hardConstraints,
                             List<String> meetsRequirements, List<String> touchesFiles) {
            this.dependsOn = dependsOn;
            this.hardConstraints = hardConstraints;
            this.meetsRequirements = meetsRequirements;
            this.touchesFiles = touchesFiles;
        }

        static MutableTask from(PlannedTaskV2 t) {
            return new MutableTask(
                    new ArrayList<>(t.dependsOn() == null ? List.of() : t.dependsOn()),
                    new ArrayList<>(t.hardConstraints() == null ? List.of() : t.hardConstraints()),
                    new ArrayList<>(t.meetsRequirements() == null ? List.of() : t.meetsRequirements()),
                    new ArrayList<>(t.touchesFiles() == null ? List.of() : t.touchesFiles())
            );
        }

        PlannedTaskV2 toImmutable(PlannedTaskV2 base) {
            return PlannedTaskV2.builder()
                    .id(base.id())
                    .title(base.title())
                    .description(base.description())
                    .complexity(base.complexity())
                    .touchesFiles(List.copyOf(touchesFiles))
                    .dependsOn(List.copyOf(dependsOn))
                    .hardConstraints(List.copyOf(hardConstraints))
                    .meetsRequirements(List.copyOf(meetsRequirements))
                    .taskType(base.taskType())
                    .build();
        }
    }
}
