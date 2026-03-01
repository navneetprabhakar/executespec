package com.navneet.agentteam.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.agentteam.agent.LlmRouter.FallbackMode;
import com.navneet.agentteam.agent.model.ReviewReport;
import com.navneet.agentteam.agent.model.WorkerReport;
import com.navneet.agentteam.config.AgentProperties;
import com.navneet.agentteam.state.InboxService;
import com.navneet.agentteam.state.StateStore;
import com.navneet.agentteam.state.entity.AgentInbox;
import com.navneet.agentteam.state.entity.AgentTask;
import com.navneet.agentteam.state.entity.PipelineRun;
import com.navneet.agentteam.tool.GitTool;
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
public class ReviewerAgent {

    @Qualifier("reviewerModel")
    private final ChatModel chatModel; // retained for Spring wiring
    private final GitTool gitTool;
    private final StateStore stateStore;
    private final ObjectMapper mapper;
    private final AgentProperties props;
    private final LlmRouter llmRouter;
    private final InboxService inboxService;
    private final LlmOutputParser outputParser;

    @Value("classpath:prompts/reviewer.txt")
    private Resource reviewerPrompt;

    public ReviewReport review(PipelineRun run, List<WorkerReport> workerReports) {
        log.info("Reviewer starting for run {}", run.getRunId());

        String provider = props.getRoles().getReviewer();
        String model    = resolveModelName(provider);

        // Gather all diffs from all worker branches
        List<AgentTask> tasks = stateStore.getAllTasks(run.getRunId());
        StringBuilder allDiffs = new StringBuilder();
        for (AgentTask task : tasks) {
            if (task.getBranchName() != null) {
                String diff = gitTool.getFullDiff(run.getRunId(), "main", task.getBranchName());
                allDiffs.append("=== Branch: ").append(task.getBranchName()).append(" ===\n")
                        .append(diff).append("\n\n");
            }
        }

        // Step 8: Inject provider fallback context if any occurred during this run
        String fallbackNote = buildFallbackNote(run.getRunId());

        String userMessage = """
            Original plan:
            %s

            Worker completion reports:
            %s

            Combined git diff (all branches vs main):
            %s
            %s""".formatted(run.getPlanJson(),
                safeJson(workerReports),
                allDiffs.toString(),
                fallbackNote);

        stateStore.logMessage(run.getRunId(), "harness", "reviewer",
            "TASK_ASSIGNMENT", "Review requested for run " + run.getRunId(), 0);

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String msg = attempt == 1 ? userMessage :
                    userMessage + "\n\nIMPORTANT: Return ONLY valid JSON.";

                // Step 7: Use LlmRouter with IMMEDIATE mode (reviewer — single call)
                Prompt prompt = new Prompt(List.of(
                    new SystemMessage(loadPrompt()),
                    new UserMessage(msg)));
                ChatResponse response = llmRouter.callWithFallback(provider, model, prompt,
                    run.getRunId(), "reviewer", FallbackMode.IMMEDIATE, 0, false);

                String responseContent = response.getResult().getOutput().getText();

                ReviewReport review = outputParser.parse(responseContent, ReviewReport.class);

                stateStore.logMessage(run.getRunId(), "reviewer", "harness",
                    "REVIEW", "Review complete: " + review.getRecommendation() +
                    " (" + review.getOverallConfidence() + ")", 0);

                log.info("Review: {} -- {} (confidence: {})",
                    review.getRecommendation(), review.getSummary(), review.getOverallConfidence());

                return review;

            } catch (JsonProcessingException e) {
                log.warn("Reviewer returned invalid JSON on attempt {}", attempt);
                if (attempt == 3) throw new RuntimeException("Reviewer failed 3 times", e);
            }
        }
        throw new RuntimeException("Unreachable");
    }

    /**
     * Step 8: Build fallback context note from PROVIDER_FALLBACK inbox events.
     * Returns empty string if no fallbacks occurred.
     */
    private String buildFallbackNote(String runId) {
        List<AgentInbox> fallbacks = inboxService.getFallbackEvents(runId);
        if (fallbacks.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("""

            ═══ PROVIDER FALLBACK EVENTS ═══
            One or more workers experienced a provider switch during this run.
            Check for consistency issues at the boundaries where providers switched.
            A provider switch mid-task was blocked; check BLOCKED tasks carefully.

            Fallback events:
            """);
        for (AgentInbox e : fallbacks) {
            sb.append("  - ").append(e.getFromAgent())
              .append(": ").append(e.getFromProvider())
              .append(" -> ").append(e.getToProvider())
              .append(" | ").append(e.getContent()).append("\n");
        }
        return sb.toString();
    }

    private String safeJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return obj.toString();
        }
    }

    private String loadPrompt() {
        try {
            return reviewerPrompt.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Cannot load reviewer prompt", e);
        }
    }

    /** Map provider name to model string for IMMEDIATE (reviewer) calls.
     *  The Reviewer uses the UTILITY chain (Haiku) — verification is fast and cheap. */
    private String resolveModelName(String provider) {
        return switch (provider.toLowerCase()) {
            case "openai"  -> "gpt-4o-mini";
            case "gemini"  -> "gemini-2.0-flash";
            default        -> "claude-haiku-4-5-20251022"; // anthropic — UTILITY chain
        };
    }
}
