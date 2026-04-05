package com.navneet.executespec.api.controller;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.LlmRouter.FallbackMode;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.api.dto.ConversationMessage;
import com.navneet.executespec.api.dto.ConversationRequest;
import com.navneet.executespec.api.dto.ConversationResponse;
import com.navneet.executespec.auth.security.AppUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/conversation")
@RequiredArgsConstructor
@Slf4j
public class ConversationController {

    private static final int MAX_MESSAGES = 50;

    private static final String SYSTEM_PROMPT = """
            You are a requirements analyst helping a software engineer clarify their project idea \
            before launching an automated multi-agent coding pipeline.

            Your job:
            - Ask one or two focused clarifying questions per reply (tech stack, scope, auth, data, integrations).
            - Keep responses short and conversational — no walls of text.
            - If the project has a frontend/UI component, ask: \
            "Visual style: Since this has a UI, which appeals to you most? Or name a website you like and we'll match it." \
            1) Minimal Light — clean, SaaS default (think Notion, Linear) \
            2) Minimal Dark — developer-facing (think Vercel, GitHub) \
            3) Corporate Clean — enterprise, formal (think Salesforce, Workday) \
            4) Startup Bold — vibrant, modern (think Figma, Loom) \
            5) Neutral Warm — editorial, content-focused (think Medium, Substack) \
            6) Or tell me a website you like and I'll match it! \
            Include their choice in the REQUIREMENT SUMMARY as "Visual theme: <choice>".
            - Once the requirement is clear enough to build, produce a tidy summary prefixed with \
            "REQUIREMENT SUMMARY:" on its own line, then the consolidated spec in plain English.

            Rules:
            - Do NOT write any code.
            - Do NOT start the implementation.
            - Do NOT ask more than two questions at once.
            """;

    private final LlmRouter llmRouter;
    private final ModelResolver modelResolver;

    @PostMapping
    public ResponseEntity<ConversationResponse> chat(
            @RequestBody ConversationRequest req,
            @AuthenticationPrincipal AppUserDetails userDetails) {

        // Pre-flight requirement refinement is available to ALL users (including FREE)
        // to help them craft better run goals. Post-run chat remains PRO+ gated.
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

        // Pre-flight conversation has no project context — use default provider
        ResolvedModel resolved = modelResolver.resolve("ConversationAgent", LlmProvider.ANTHROPIC);
        var options = AnthropicChatOptions.builder()
                .model(resolved.getModel())
                .maxTokens(512)
                .build();

        var response = llmRouter.callWithFallback(
                resolved.providerName(), resolved.getModel(),
                new Prompt(springMessages, options),
                "conversation", "conversation-api",
                FallbackMode.IMMEDIATE, 0, false);

        String reply = response.getResult().getOutput().getText();
        log.info("Conversation reply: {} chars", reply != null ? reply.length() : 0);

        return ResponseEntity.ok(new ConversationResponse(reply));
    }
}
