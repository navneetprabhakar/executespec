package com.navneet.executespec.api.controller;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.PostConstruct;

import com.navneet.executespec.agent.FallbackLlmRouter;
import com.navneet.executespec.agent.FallbackLlmRouter.FallbackMode;
import com.navneet.executespec.api.dto.ConversationMessage;
import com.navneet.executespec.api.dto.ConversationRequest;
import com.navneet.executespec.api.dto.ConversationResponse;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/conversation")
@RequiredArgsConstructor
@Slf4j
public class ConversationController {

  private static final int MAX_MESSAGES = 50;

  /**
   * System prompt loaded from {@code prompts/conversation-system.md} at boot. Externalized per
   * CLAUDE.md MUST-pattern + audit 2026-05-12 review item E #32.
   */
  @Value("classpath:prompts/conversation-system.md")
  private Resource systemPromptResource;

  private String systemPrompt;

  @PostConstruct
  void loadSystemPrompt() {
    try {
      systemPrompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
    } catch (Exception e) {
      log.error(
          "[Conversation] Failed to load system prompt from classpath:prompts/"
              + "conversation-system.md — falling back to a stub",
          e);
      systemPrompt = "You are a requirements analyst. Be concise.";
    }
  }

  private final FallbackLlmRouter fallbackLlmRouter;
  private final ModelResolver modelResolver;

  @PostMapping
  public ResponseEntity<ConversationResponse> chat(
      @RequestBody ConversationRequest req, @AuthenticationPrincipal AppUserDetails userDetails) {

    // Pre-flight requirement refinement is available to ALL users (including FREE)
    // to help them craft better run goals. Post-run chat remains PRO+ gated.
    List<ConversationMessage> incoming = req.messages() == null ? List.of() : req.messages();

    // Server-side guard: honour the same 10-message cap enforced in the UI
    int allowed = Math.min(incoming.size(), MAX_MESSAGES);
    List<ConversationMessage> messages = incoming.subList(0, allowed);

    log.info("POST /api/conversation — {} message(s)", messages.size());

    List<Message> springMessages = new ArrayList<>();
    springMessages.add(new SystemMessage(systemPrompt));

    for (ConversationMessage msg : messages) {
      if ("user".equalsIgnoreCase(msg.role())) {
        springMessages.add(new UserMessage(msg.content()));
      } else if ("assistant".equalsIgnoreCase(msg.role())) {
        springMessages.add(new AssistantMessage(msg.content()));
      }
    }

    // Pre-flight conversation has no project context — use default provider
    ResolvedModel resolved = modelResolver.resolve("ConversationAgent", LlmProvider.ANTHROPIC);
    // 4096 (not 512): the LIGHTWEIGHT tier resolves to a thinking model
    // (gemini-3-flash-preview), whose reasoning tokens count against this cap. At
    // 512 the "Use as spec" finalize turn — which must emit a full consolidated
    // spec — was truncated mid-sentence after ~20 visible tokens. Clarifying
    // questions are short and stop early, so the higher cap costs nothing there.
    var options = AnthropicChatOptions.builder().model(resolved.getModel()).maxTokens(4096).build();

    var response =
        fallbackLlmRouter.callWithFallback(
            resolved.providerName(),
            resolved.getModel(),
            new Prompt(springMessages, options),
            "conversation",
            "conversation-api",
            FallbackMode.IMMEDIATE,
            0,
            false);

    String reply = response.getResult().getOutput().getText();
    log.info("Conversation reply: {} chars", reply != null ? reply.length() : 0);

    return ResponseEntity.ok(new ConversationResponse(reply));
  }
}
