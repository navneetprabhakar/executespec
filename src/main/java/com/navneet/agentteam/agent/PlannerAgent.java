package com.navneet.agentteam.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.agentteam.agent.LlmRouter.FallbackMode;
import com.navneet.agentteam.agent.model.TaskDefinition;
import com.navneet.agentteam.agent.model.TaskPlan;
import com.navneet.agentteam.config.AgentProperties;
import com.navneet.agentteam.state.StateStore;
import com.navneet.agentteam.tool.FileSystemTool;
import com.navneet.agentteam.tool.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @Value("classpath:prompts/planner.txt")
    private Resource plannerPrompt;

    public TaskPlan plan(String userRequest, String runId) {
        log.info("Planner starting for run {}", runId);

        String systemPrompt   = loadPrompt();
        String contextSummary = buildWorkspaceContext(runId);
        String provider       = props.getRoles().getPlanner();
        String model          = resolveModelName(provider);

        String userMessage = """
            User request: %s

            Current workspace context:
            %s
            """.formatted(userRequest, contextSummary);

        stateStore.logMessage(runId, "harness", "planner",
            "TASK_ASSIGNMENT", "Plan: " + userRequest, 0);

        // Retry loop — planner must return valid JSON
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String msg = attempt == 1 ? userMessage :
                    userMessage + "\n\nIMPORTANT: Your previous response was not valid JSON. " +
                    "Respond ONLY with a valid JSON object. No markdown, no explanation.";

                // Step 7: Use LlmRouter with IMMEDIATE mode
                Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(msg)));
                ChatResponse response = llmRouter.callWithFallback(provider, model, prompt,
                    runId, "planner", FallbackMode.IMMEDIATE, 0, false);

                String responseContent = response.getResult().getOutput().getText();

                log.debug("Planner raw response (attempt {}): {}", attempt,
                    responseContent.length() > 200 ? responseContent.substring(0, 200) + "..." : responseContent);

                TaskPlan plan = outputParser.parse(responseContent, TaskPlan.class);

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
                if (attempt == 3) throw new RuntimeException("Planner failed 3 times to return valid JSON", e);
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

        String basePrompt    = loadPrompt();
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

        String systemPrompt  = basePrompt + feasibilityAppendix;
        String contextSummary = buildWorkspaceContext(null); // feasibility — no per-run workspace
        String provider      = props.getRoles().getPlanner();
        String model         = resolveModelName(provider);

        String userMessage = """
            User request: %s

            Current workspace context:
            %s
            """.formatted(goal, contextSummary);

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String msg = attempt == 1 ? userMessage :
                    userMessage + "\n\nIMPORTANT: Your previous response was not valid JSON. " +
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
                if (attempt == 3) throw new RuntimeException("Feasibility planner failed 3 times", e);
            }
        }
        throw new RuntimeException("Should not reach here");
    }

    /**
     * Summarises the workspace context for the LLM.
     *
     * <p>Each run has its own isolated workspace ({@code workspace/<runId>/}), so the
     * directory is always clean at planning time — no cross-run contamination possible.
     *
     * @param runId the run ID, or {@code null} for feasibility-mode calls (no workspace)
     */
    private String buildWorkspaceContext(String runId) {
        if (runId == null) {
            return "No workspace context (feasibility mode — no run directory exists yet).";
        }

        ToolResult listing = fsTool.listDirectory(runId, ".");
        ToolResult context = fsTool.readFile(runId, ".agent-context/CODEBASE_CONTEXT.md");

        String dirSection = "Directory listing (this run's isolated workspace):\n" + listing.value();
        String ctxSection = context.isOk() ? "\n\nCodebase context:\n" + context.value() : "";

        return dirSection + ctxSection;
    }

    private String loadPrompt() {
        try {
            return plannerPrompt.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Cannot load planner prompt", e);
        }
    }

    /** Map provider name to model string for IMMEDIATE (planner) calls.
     *  The Planner is The Architect — complex task decomposition requires Opus. */
    private String resolveModelName(String provider) {
        return switch (provider.toLowerCase()) {
            case "openai"  -> "gpt-4o";
            case "gemini"  -> "gemini-2.5-pro";
            default        -> "claude-opus-4-5-20251101"; // anthropic — The Architect
        };
    }
}
