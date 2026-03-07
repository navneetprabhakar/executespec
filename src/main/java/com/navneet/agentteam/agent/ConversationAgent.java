package com.navneet.agentteam.agent;

import com.navneet.agentteam.deployment.WorkspaceFileService;
import com.navneet.agentteam.state.entity.PipelineRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Post-run conversational AI agent.
 *
 * <p>After a run reaches COMPLETE the user can ask questions about the generated code or
 * request incremental changes. This agent holds a conversational context window that
 * includes:
 * <ul>
 *   <li>A system prompt describing the completed project.</li>
 *   <li>The full conversation history (user / assistant turns) accumulated so far.</li>
 *   <li>The new user message.</li>
 * </ul>
 *
 * <p>Uses the UTILITY_CHAIN (haiku) for cost-efficiency.
 * The conversation is capped at {@link #MAX_MESSAGES} user messages (configurable).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationAgent {

    /** Maximum number of USER messages allowed per run conversation. */
    public static final int MAX_MESSAGES = 10;

    private static final String PRIMARY_PROVIDER = "anthropic";
    private static final String PRIMARY_MODEL    = "claude-haiku-4-5-20251001";
    private static final int    MAX_OUTPUT_TOKENS = 2048;
    private static final int    MAX_CONTEXT_CHARS = 16_000; // ~4000 tokens of workspace context

    private final LlmRouter llmRouter;
    private final WorkspaceFileService workspaceFileService;

    /**
     * Sends a user message in the context of a completed run and returns the assistant reply.
     *
     * @param run        the completed PipelineRun — used to build the system prompt context
     * @param history    previous conversation messages in {role, content} form
     * @param newMessage the current user message to respond to
     * @return the assistant's reply text
     */
    public String chat(PipelineRun run, List<ChatMessageRecord> history, String newMessage) {
        log.info("[ConversationAgent] run={} historySize={}", run.getRunId(), history.size());

        String systemPrompt = buildSystemPrompt(run);

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));

        // Replay conversation history
        for (ChatMessageRecord m : history) {
            if ("user".equalsIgnoreCase(m.role())) {
                messages.add(new UserMessage(m.content()));
            } else {
                messages.add(new AssistantMessage(m.content()));
            }
        }

        // Add the current user message
        messages.add(new UserMessage(newMessage));

        // Build prompt with haiku + max tokens
        var opts = org.springframework.ai.anthropic.AnthropicChatOptions.builder()
            .model(PRIMARY_MODEL)
            .maxTokens(MAX_OUTPUT_TOKENS)
            .build();
        Prompt prompt = new Prompt(messages, opts);

        var response = llmRouter.callWithFallback(
            PRIMARY_PROVIDER, PRIMARY_MODEL, prompt,
            run.getRunId(), "conversation",
            LlmRouter.FallbackMode.IMMEDIATE, 0, false
        );

        String reply = response.getResult().getOutput().getText();
        log.debug("[ConversationAgent] run={} reply length={}", run.getRunId(), reply != null ? reply.length() : 0);
        return reply != null ? reply.trim() : "I'm sorry, I couldn't generate a response. Please try again.";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildSystemPrompt(PipelineRun run) {
        String status  = run.getStatus();
        String request = run.getUserRequest() != null ? run.getUserRequest() : "(unknown)";
        String model   = run.getPlannerModel() != null ? run.getPlannerModel() : "unknown model";

        String workspaceContext = buildWorkspaceContext(run.getRunId());

        String planSection = "";
        if (run.getPlanJson() != null && !run.getPlanJson().isBlank()) {
            planSection = "PLAN SUMMARY:\n" + truncate(run.getPlanJson(), 2000) + "\n";
        }
        String reviewSection = "";
        if (run.getReviewJson() != null && !run.getReviewJson().isBlank()) {
            reviewSection = "REVIEW SUMMARY:\n" + truncate(run.getReviewJson(), 1000) + "\n";
        }

        return """
            You are a helpful assistant discussing a completed software engineering run.

            THE PROJECT THAT WAS BUILT:
            ─────────────────────────────
            User's original request: %s

            Run ID: %s
            Planned by: %s
            Status: %s

            %s
            %s

            PROJECT FILES AND CODE:
            ─────────────────────────────
            %s

            YOUR ROLE:
            ─────────────────────────────
            • Answer questions about what was implemented, the architecture, and code choices.
            • You have access to the project's file listing and key files shown above.
              Use this context to give specific, accurate answers about the generated code.
            • If the user asks for changes or new features, describe what would need to be done
              and suggest they click "Implement as New Run" to apply the changes automatically.
            • Be concise and practical. Assume the user is a developer.
            • If the user asks about a file not shown above, let them know you can see the file
              listing but not all file contents, and suggest they paste the relevant code.

            CONVERSATION LIMIT:
            ─────────────────────────────
            This conversation is limited to %d messages. Encourage the user to start a new run
            for any substantial changes once they have decided what they want.
            """.formatted(request, run.getRunId(), model, status,
                    planSection, reviewSection, workspaceContext, MAX_MESSAGES);
    }

    private String buildWorkspaceContext(String runId) {
        StringBuilder sb = new StringBuilder();
        try {
            List<String> files = workspaceFileService.listFiles(runId);
            if (!files.isEmpty()) {
                sb.append("File tree:\n");
                for (String f : files) {
                    sb.append("  ").append(f).append('\n');
                }
                sb.append('\n');
            }
            tryAppendFile(sb, runId, "README.md", "README.md", 4000);
            tryAppendFile(sb, runId, ".agent-context/CODEBASE_CONTEXT.md", "Codebase Context", 3000);
        } catch (Exception e) {
            log.warn("[ConversationAgent] Could not read workspace for run {}: {}", runId, e.getMessage());
            sb.append("(workspace files not available)\n");
        }
        return truncate(sb.toString(), MAX_CONTEXT_CHARS);
    }

    private void tryAppendFile(StringBuilder sb, String runId, String path, String label, int maxChars) {
        try {
            String content = workspaceFileService.readFile(runId, path);
            if (content != null && !content.isBlank()) {
                sb.append("── ").append(label).append(" ──\n");
                sb.append(truncate(content, maxChars)).append("\n\n");
            }
        } catch (IOException e) {
            // File doesn't exist — skip
        }
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text != null ? text : "";
        return text.substring(0, maxChars) + "\n...[truncated]";
    }

    /**
     * Simple record representing a single conversation message.
     *
     * @param role    "user" or "assistant"
     * @param content the message text
     */
    public record ChatMessageRecord(String role, String content) {}
}
