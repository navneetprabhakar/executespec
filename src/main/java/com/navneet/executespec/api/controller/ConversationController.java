package com.navneet.executespec.api.controller;

import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.PostConstruct;

import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.api.dto.ConversationMessage;
import com.navneet.executespec.api.dto.ConversationRequest;
import com.navneet.executespec.api.dto.ConversationResponse;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.router.ModelRegistry;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
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
   * RR-960 / RR-599 — the only prior bound was a message COUNT, so a caller could still submit
   * arbitrarily large turns that consume heap and provider context/spend on this platform-key
   * pinned endpoint. Bound the content bytes too. This is a defensive transport cap; the deeper
   * entitlement/managed-credit migration for this flow is tracked separately (RR-959 / RR-960).
   */
  static final int MAX_MESSAGE_CHARS = 24_000;

  static final int MAX_TOTAL_CONTENT_CHARS = 120_000;

  /**
   * System prompt loaded from {@code prompts/conversation-system.md} at boot. Externalized per
   * CLAUDE.md MUST-pattern + audit 2026-05-12 review item E #32.
   */
  private final com.navneet.executespec.agent.PromptAssembler promptAssembler;

  private String systemPrompt;

  @PostConstruct
  void loadSystemPrompt() {
    systemPrompt = promptAssembler.assemble("conversation-system.md");
  }

  private final LlmRouter llmRouter;
  private final ModelRegistry modelRegistry;

  @PostMapping
  public ResponseEntity<ConversationResponse> chat(
      @RequestBody ConversationRequest req, @AuthenticationPrincipal AppUserDetails userDetails) {

    // Pre-flight requirement refinement is available to ALL users (including FREE)
    // to help them craft better run goals. Post-run chat remains PRO+ gated.
    List<ConversationMessage> incoming = req.messages() == null ? List.of() : req.messages();

    // RR-960 / RR-599 — reject oversize turns before building the provider request.
    long totalChars = 0;
    for (ConversationMessage m : incoming) {
      String content = m == null ? null : m.content();
      int len = content == null ? 0 : content.length();
      if (len > MAX_MESSAGE_CHARS) {
        throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
            "A conversation message exceeds the " + MAX_MESSAGE_CHARS + "-character limit.");
      }
      totalChars += len;
    }
    if (totalChars > MAX_TOTAL_CONTENT_CHARS) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
          "Conversation content exceeds the " + MAX_TOTAL_CONTENT_CHARS + "-character limit.");
    }

    // RR-961 — keep the NEWEST `allowed` messages, not the oldest. subList(0, allowed) dropped
    // every turn after the 50th — including the latest user request and the finalize instruction —
    // so once the history crossed the cap the model only ever saw stale context. Take the tail so
    // the newest turn is always present.
    int allowed = Math.min(incoming.size(), MAX_MESSAGES);
    List<ConversationMessage> messages =
        incoming.subList(incoming.size() - allowed, incoming.size());

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

    // Pin the pre-flight chat to Anthropic Haiku (LIGHTWEIGHT) through the canonical
    // text seam. Supplying the provider and resolved model explicitly keeps this
    // short-lived conversation from inheriting a different account/run route.
    String model = modelRegistry.lightweightModelFor("anthropic");
    // 4096 leaves room for a full consolidated spec on the "Use as spec" turn;
    // clarifying questions stop early so the higher cap costs nothing there.
    var response =
        llmRouter.callText(
            "anthropic",
            model,
            springMessages,
            "conversation",
            "ConversationController",
            "conversation.preflight",
            4096);

    String reply = response.getResult().getOutput().getText();
    log.info("Conversation reply: {} chars", reply != null ? reply.length() : 0);

    return ResponseEntity.ok(new ConversationResponse(reply));
  }
}
