package com.navneet.agentteam.agent;

import com.navneet.agentteam.state.entity.PipelineRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

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
    private static final int    MAX_OUTPUT_TOKENS = 1024;

    private final LlmRouter llmRouter;

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
        String status = run.getStatus();
        String request = run.getUserRequest() != null ? run.getUserRequest() : "(unknown)";
        String model   = run.getPlannerModel() != null ? run.getPlannerModel() : "unknown model";

        return """
            You are a helpful assistant discussing a completed software engineering run.

            THE PROJECT THAT WAS BUILT:
            ─────────────────────────────
            User's original request: %s

            Run ID: %s
            Planned by: %s
            Status: %s

            YOUR ROLE:
            ─────────────────────────────
            • Answer questions about what was implemented, the architecture, and code choices.
            • If the user asks for changes or new features, describe what would need to be done
              and suggest they click "Implement as New Run" to apply the changes automatically.
            • Be concise and practical. Assume the user is a developer.
            • If the user asks you to write code directly, provide it but also mention that
              clicking "Implement as New Run" will apply the changes via the full agent pipeline.
            • You do NOT have access to read the workspace files directly. Discuss the project
              based on the original request and any files the user pastes into the conversation.

            CONVERSATION LIMIT:
            ─────────────────────────────
            This conversation is limited to %d messages. Encourage the user to start a new run
            for any substantial changes once they have decided what they want.
            """.formatted(request, run.getRunId(), model, status, MAX_MESSAGES);
    }

    /**
     * Simple record representing a single conversation message.
     *
     * @param role    "user" or "assistant"
     * @param content the message text
     */
    public record ChatMessageRecord(String role, String content) {}
}
