package com.navneet.executespec.agent;
import com.navneet.executespec.config.ModelConstants;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRouter.FallbackMode;
import com.navneet.executespec.agent.model.TaskDefinition;
import com.navneet.executespec.agent.model.TaskPlan;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.harness.merge.MergeConflictAnalyser;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.tool.FileSystemTool;
import com.navneet.executespec.tool.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlannerAgent {

    @Qualifier("plannerModel")
    private final ChatModel chatModel; // retained for Spring wiring
    private final FileSystemTool fsTool;
    private final StateStore stateStore;
    private final ObjectMapper mapper;
    private final AgentProperties props;
    private final LlmRouter llmRouter;
    private final LlmOutputParser outputParser;
    private final MergeConflictAnalyser conflictAnalyser;

    @Value("classpath:prompts/planner.txt")
    private Resource plannerPrompt;

    @Value("classpath:prompts/planner_edit.txt")
    private Resource plannerEditPrompt;

    public TaskPlan plan(String userRequest, String runId, String modelOverride) {
        log.info("Planner starting for run {}", runId);

        String systemPrompt = loadPrompt() + buildConflictHistoryContext();
        String contextSummary = buildWorkspaceContext(runId);
        String model = (modelOverride != null && !modelOverride.isBlank())
                ? modelOverride
                : resolveModelName(props.getRoles().getPlanner());
        String provider = resolveProviderFromModel(model);
        log.info("[Planner] Using model={} provider={} (override={})", model, provider, modelOverride);

        // Prepend existing-codebase context when the workspace was cloned from a real repo
        String existingCodebaseHeader = buildExistingCodebaseHeader(runId);
        String userMessage = existingCodebaseHeader + """
                User request: %s

                Current workspace context:
                %s
                """.formatted(userRequest, contextSummary);

        stateStore.logMessage(runId, "harness", "planner",
                "TASK_ASSIGNMENT", "Plan: " + userRequest, 0);

        // Retry loop — planner must return valid JSON
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String msg = attempt == 1 ? userMessage
                        : userMessage + "\n\nIMPORTANT: Your previous response was not valid JSON. " +
                                "Respond ONLY with a valid JSON object. No markdown, no explanation.";

                // Step 7: Use LlmRouter with IMMEDIATE mode.
                // Enable SYSTEM_ONLY caching — system prompt is identical on every retry
                // attempt.
                AnthropicChatOptions plannerOpts = AnthropicChatOptions.builder()
                        .cacheOptions(AnthropicCacheOptions.builder()
                                .strategy(AnthropicCacheStrategy.SYSTEM_ONLY)
                                .build())
                        .build();
                Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(msg)),
                        plannerOpts);
                LlmRouter.LlmResponse lr = llmRouter.callWithFallbackTracked(provider, model, prompt,
                        runId, "planner", FallbackMode.IMMEDIATE, 0, false);
                ChatResponse response = lr.response();

                String responseContent = response.getResult().getOutput().getText();

                // Track actual token usage for cost accounting
                try {
                    int inTok = response.getMetadata().getUsage().getPromptTokens().intValue();
                    int outTok = response.getMetadata().getUsage().getCompletionTokens().intValue();
                    stateStore.addTokensToRun(runId, inTok, outTok);
                    stateStore.setAgentTokens(runId, "planner", inTok, outTok, lr.provider(), lr.model());
                } catch (Exception ex) {
                    log.warn("[Planner] Could not extract token counts: {}", ex.getMessage());
                }

                log.debug("Planner raw response (attempt {}): {}", attempt,
                        responseContent.length() > 200 ? responseContent.substring(0, 200) + "..." : responseContent);

                TaskPlan plan = outputParser.parse(responseContent, TaskPlan.class);

                if (plan.getTasks() == null || plan.getTasks().isEmpty()) {
                    log.warn("Planner returned a plan with no tasks on attempt {} — retrying", attempt);
                    if (attempt == 3) {
                        throw new RuntimeException("Planner failed to produce any tasks after 3 attempts");
                    }
                    continue;
                }

                if (plan.getTasks().size() > 35) {
                    log.warn("Plan has {} tasks — truncating to 35", plan.getTasks().size());
                    plan.setTasks(plan.getTasks().subList(0, 35));
                }

                stateStore.logMessage(runId, "planner", "harness",
                        "COMPLETION_REPORT",
                        "Plan ready: " + plan.getPlanSummary() + " (" + plan.getTasks().size() + " tasks)",
                        0);

                log.info("Planner produced {} tasks: {}",
                        plan.getTasks().size(),
                        plan.getTasks().stream().map(TaskDefinition::getTaskId).toList());

                return plan;

            } catch (JsonProcessingException e) {
                log.warn("Planner returned invalid JSON on attempt {}: {}", attempt, e.getMessage());
                if (attempt == 3)
                    throw new RuntimeException("Planner failed 3 times to return valid JSON", e);
            }
        }
        throw new RuntimeException("Should not reach here");
    }

    /**
     * Feasibility mode: runs planner with iteration-estimate instructions appended.
     * Returns a TaskPlan where each task has estimatedIterations and
     * estimatedTokensPerIteration populated.
     */
    public TaskPlan planWithEstimates(String goal) {
        log.info("Planner starting feasibility mode for goal: {}", goal);

        String basePrompt = loadPrompt();
        String feasibilityAppendix = """

                ══════════════════════════════════════════════════════
                FEASIBILITY MODE — ADDITIONAL REQUIREMENTS
                ══════════════════════════════════════════════════════

                You are running in FEASIBILITY mode. In addition to the normal task plan JSON,
                each task object MUST include two extra fields:

                  "estimated_iterations": {"min": <int>, "max": <int>}
                  "estimated_tokens_per_iteration": <int>

                Guidelines:
                - LOW complexity  → iterations: {min:1, max:2},  tokens: ~10000
                - MEDIUM complexity → iterations: {min:2, max:4},  tokens: ~25000
                - HIGH complexity → iterations: {min:3, max:6},  tokens: ~60000

                Be conservative — users rely on these estimates to make a budget decision.
                Output ONLY the JSON plan. No text before or after.
                """;

        String systemPrompt = basePrompt + feasibilityAppendix;
        String contextSummary = buildWorkspaceContext(null); // feasibility — no per-run workspace
        String provider = props.getRoles().getPlanner();
        String model = resolveModelName(provider);

        String userMessage = """
                User request: %s

                Current workspace context:
                %s
                """.formatted(goal, contextSummary);

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String msg = attempt == 1 ? userMessage
                        : userMessage + "\n\nIMPORTANT: Your previous response was not valid JSON. " +
                                "Respond ONLY with a valid JSON object including estimated_iterations fields.";

                Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(msg)));
                ChatResponse response = llmRouter.callWithFallback(provider, model, prompt,
                        "feasibility", "planner", FallbackMode.IMMEDIATE, 0, false);

                String raw = response.getResult().getOutput().getText();
                TaskPlan plan = outputParser.parse(raw, TaskPlan.class);
                log.info("Feasibility planner produced {} tasks", plan.getTasks().size());
                return plan;

            } catch (JsonProcessingException e) {
                log.warn("Feasibility planner invalid JSON (attempt {}): {}", attempt, e.getMessage());
                if (attempt == 3)
                    throw new RuntimeException("Feasibility planner failed 3 times", e);
            }
        }
        throw new RuntimeException("Should not reach here");
    }

    /**
     * Re-plans with user feedback — used when the user edits the plan at the approval gate.
     */
    public TaskPlan replanWithFeedback(String originalGoal, String existingPlanJson, String feedback, String runId) {
        log.info("Planner re-planning with feedback for run {}", runId);
        String editPromptText;
        try {
            editPromptText = plannerEditPrompt.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Cannot load planner_edit prompt", e);
        }

        String systemPrompt = editPromptText
            .replace("{goal}", originalGoal)
            .replace("{existingPlan}", existingPlanJson)
            .replace("{feedback}", feedback);

        String model = resolveModelName(props.getRoles().getPlanner());
        String provider = resolveProviderFromModel(model);

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String msg = attempt == 1
                    ? "Revise the plan based on the feedback provided."
                    : "IMPORTANT: Your previous response was not valid JSON. Respond ONLY with a valid JSON object.";

                Prompt prompt = new Prompt(
                    List.of(new SystemMessage(systemPrompt), new UserMessage(msg)));
                LlmRouter.LlmResponse lr = llmRouter.callWithFallbackTracked(provider, model, prompt,
                    runId, "planner", FallbackMode.IMMEDIATE, 0, false);
                ChatResponse response = lr.response();

                String responseContent = response.getResult().getOutput().getText();

                // Track tokens
                try {
                    int inTok = response.getMetadata().getUsage().getPromptTokens().intValue();
                    int outTok = response.getMetadata().getUsage().getCompletionTokens().intValue();
                    stateStore.addTokensToRun(runId, inTok, outTok);
                    stateStore.setAgentTokens(runId, "planner", inTok, outTok, lr.provider(), lr.model());
                } catch (Exception ex) {
                    log.warn("[Planner] Could not extract token counts: {}", ex.getMessage());
                }

                TaskPlan plan = outputParser.parse(responseContent, TaskPlan.class);

                if (plan.getTasks() != null && plan.getTasks().size() > 35) {
                    log.warn("Edited plan has {} tasks — truncating to 35", plan.getTasks().size());
                    plan.setTasks(plan.getTasks().subList(0, 35));
                }

                log.info("Planner re-plan produced {} tasks", plan.getTasks().size());
                return plan;

            } catch (JsonProcessingException e) {
                log.warn("Planner re-plan returned invalid JSON on attempt {}: {}", attempt, e.getMessage());
                if (attempt == 3)
                    throw new RuntimeException("Planner re-plan failed 3 times to return valid JSON", e);
            }
        }
        throw new RuntimeException("Should not reach here");
    }

    /**
     * Summarises the workspace context for the LLM.
     *
     * <p>
     * Each run has its own isolated workspace ({@code workspace/<runId>/}), so the
     * directory is always clean at planning time — no cross-run contamination
     * possible.
     *
     * @param runId the run ID, or {@code null} for feasibility-mode calls (no
     *              workspace)
     */
    private String buildWorkspaceContext(String runId) {
        if (runId == null) {
            return "No workspace context (feasibility mode — no run directory exists yet).";
        }

        String listing = fsTool.listFiles(runId, ".");
        String context = fsTool.readFile(runId, ".agent-context/CODEBASE_CONTEXT.md");

        String dirSection = "Directory listing (this run's isolated workspace):\n" + listing;
        String ctxSection = !context.startsWith("ERROR") ? "\n\nCodebase context:\n" + context : "";

        return dirSection + ctxSection;
    }

    private String loadPrompt() {
        try {
            return plannerPrompt.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Cannot load planner prompt", e);
        }
    }

    /**
     * Appends historical merge conflict data to the system prompt so the planner
     * can avoid patterns that frequently cause conflicts.
     */
    private String buildConflictHistoryContext() {
        try {
            return conflictAnalyser.buildPlannerContext(5, 20);
        } catch (Exception e) {
            log.debug("Could not build conflict history context: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Maps provider name → default model for IMMEDIATE (planner) calls.
     * Used only when the UI sends no model override.
     */
    private String resolveModelName(String provider) {
        return switch (provider.toLowerCase()) {
            case "openai" -> ModelConstants.GPT4O;
            default -> ModelConstants.SONNET; // anthropic — strong reasoning at lower cost
        };
    }

    /**
     * Returns a context header block when the run starts from an existing GitHub repo.
     * Empty string otherwise so normal runs are unaffected.
     */
    private String buildExistingCodebaseHeader(String runId) {
        if (runId == null) return "";
        return stateStore.findRun(runId)
                .filter(r -> "EXISTING".equals(r.getRepoMode()) && r.getRepoUrl() != null)
                .map(r -> """
                        [EXISTING CODEBASE MODE]
                        This run starts from an existing repository: %s
                        The workspace already contains the full cloned codebase.
                        Your FIRST task MUST analyze the existing project structure and key files.
                        Plan changes and extensions ON TOP OF the existing code — do not recreate files that already exist.
                        When the run is complete, all changes will be pushed back to the source repository.

                        """.formatted(r.getRepoUrl()))
                .orElse("");
    }

    /**
     * Infers the provider from a concrete model name so the LlmRouter chain is
     * correct
     * when a UI model-override replaces the config default.
     */
    private String resolveProviderFromModel(String model) {
        if (model == null)
            return props.getRoles().getPlanner();
        if (model.startsWith("claude-"))
            return "anthropic";
        if (model.startsWith("gpt-"))
            return "openai";
        if (model.startsWith("gemini-"))
            return "google";
        return props.getRoles().getPlanner(); // safe fallback
    }
}
