package com.navneet.executespec.api.controller;

import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.LlmRouter.FallbackMode;
import com.navneet.executespec.api.dto.ConversationMessage;
import com.navneet.executespec.api.dto.ConversationRequest;
import com.navneet.executespec.api.dto.ConversationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/conversation")
@RequiredArgsConstructor
@Slf4j
public class ConversationController {

    private static final int MAX_MESSAGES = 10;

    private static final String SYSTEM_PROMPT = """
            You are a requirements analyst helping a software engineer clarify their project idea \
            before launching an automated multi-agent coding pipeline.

            Your job:
            - Ask one or two focused clarifying questions per reply (tech stack, scope, auth, data, integrations).
            - Keep responses short and conversational — no walls of text.
            - Once the requirement is clear enough to build, produce a tidy summary prefixed with \
            "REQUIREMENT SUMMARY:" on its own line, then the consolidated spec in plain English.

            Rules:
            - Do NOT write any code.
            - Do NOT start the implementation.
            - Do NOT ask more than two questions at once.
            """;

    private final LlmRouter llmRouter;

    @PostMapping
    public ResponseEntity<ConversationResponse> chat(@RequestBody ConversationRequest req) {
        List<ConversationMessage> incoming = req.messages() == null ? List.of() : req.messages();

        // Server-side guard: honour the same 10-message cap enforced in the UI
        int allowed = Math.min(incoming.size(), MAX_MESSAGES);
        List<ConversationMessage> messages = incoming.subList(0, allowed);

        log.info("POST /api/conversation — {} message(s)", messages.size());

        List<Message> springMessages = new ArrayList<>();
        springMessages.add(new SystemMessage(SYSTEM_PROMPT));

        for (ConversationMessage msg : messages) {
            if ("user".equalsIgnoreCase(msg.role())) {
                springMessages.add(new UserMessage(msg.content()));
            } else if ("assistant".equalsIgnoreCase(msg.role())) {
                springMessages.add(new AssistantMessage(msg.content()));
            }
        }

        var options = AnthropicChatOptions.builder()
                .model("claude-haiku-4-5-20251001")
                .maxTokens(512)
                .build();

        var response = llmRouter.callWithFallback(
                "anthropic", "claude-haiku-4-5-20251001",
                new Prompt(springMessages, options),
                "conversation", "conversation-api",
                FallbackMode.IMMEDIATE, 0, false);

        String reply = response.getResult().getOutput().getText();
        log.info("Conversation reply: {} chars", reply != null ? reply.length() : 0);

        return ResponseEntity.ok(new ConversationResponse(reply));
    }
}
