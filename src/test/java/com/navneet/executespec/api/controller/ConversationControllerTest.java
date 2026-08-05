package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.PromptAssembler;
import com.navneet.executespec.api.dto.ConversationMessage;
import com.navneet.executespec.api.dto.ConversationRequest;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.router.ModelRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.web.server.ResponseStatusException;

/** RR-961 / RR-960 / RR-599 — conversation window selection and content-size bounds. */
class ConversationControllerTest {

  private final PromptAssembler promptAssembler = mock(PromptAssembler.class);
  private final LlmRouter llmRouter = mock(LlmRouter.class);
  private final ModelRegistry modelRegistry = mock(ModelRegistry.class);
  private final LlmExecutionPolicyFactory executionPolicyFactory =
      mock(LlmExecutionPolicyFactory.class);
  private ConversationController controller;

  private static final LlmExecutionReference REFERENCE =
      new LlmExecutionReference("llmctx-conversation-test", LlmExecutionScope.platformInternal());

  private static AppUserDetails principal() {
    return new AppUserDetails(
        AppUser.builder().id("user-1").email("u@example.com").orgId("org-1").build(), null);
  }

  @BeforeEach
  void setUp() {
    controller =
        new ConversationController(
            promptAssembler, llmRouter, modelRegistry, executionPolicyFactory);
    when(promptAssembler.assemble("conversation-system.md")).thenReturn("SYSTEM");
    controller.loadSystemPrompt();
    when(modelRegistry.lightweightModelFor("anthropic")).thenReturn("haiku");
    when(executionPolicyFactory.forChatTurn(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(REFERENCE);
    when(llmRouter.callText(
            eq("anthropic"),
            eq("haiku"),
            any(),
            any(String.class),
            any(),
            any(),
            anyInt(),
            any(),
            any(LlmExecutionReference.class)))
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("reply")))));
  }

  private static ConversationMessage user(String content) {
    return new ConversationMessage("user", content);
  }

  @Test
  @SuppressWarnings("unchecked")
  void keepsTheNewestTurnsNotTheOldestWhenOverTheCap() {
    List<ConversationMessage> incoming = new ArrayList<>();
    for (int i = 0; i < 55; i++) {
      incoming.add(user("msg-" + i));
    }

    controller.chat(new ConversationRequest(incoming), principal());

    ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
    org.mockito.Mockito.verify(llmRouter)
        .callText(
            eq("anthropic"),
            eq("haiku"),
            captor.capture(),
            any(String.class),
            any(),
            any(),
            anyInt(),
            any(),
            any(LlmExecutionReference.class));
    List<Message> sent = captor.getValue();

    // 1 system + 50 user messages (the cap).
    assertThat(sent).hasSize(51);
    List<String> texts = sent.stream().map(Message::getText).toList();
    // The newest turn is present; the oldest dropped turns are not.
    assertThat(texts).contains("msg-54");
    assertThat(texts).doesNotContain("msg-0", "msg-4");
    assertThat(texts).contains("msg-5"); // first surviving turn (55 - 50)
  }

  @Test
  void rejectsAnOversizeSingleMessage() {
    String big = "x".repeat(ConversationController.MAX_MESSAGE_CHARS + 1);
    assertThatThrownBy(
            () -> controller.chat(new ConversationRequest(List.of(user(big))), principal()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("413");
  }

  @Test
  void rejectsOversizeTotalContent() {
    List<ConversationMessage> incoming = new ArrayList<>();
    String chunk = "y".repeat(ConversationController.MAX_MESSAGE_CHARS);
    // 6 * 24_000 = 144_000 > MAX_TOTAL_CONTENT_CHARS (120_000), none individually over the cap.
    for (int i = 0; i < 6; i++) {
      incoming.add(user(chunk));
    }
    assertThatThrownBy(() -> controller.chat(new ConversationRequest(incoming), principal()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("413");
  }

  /**
   * A1 (2026-08-01): this endpoint used the legacy context-free text seam, so the V282 dispatch
   * guard refused every call and "Improve request" returned HTTP 500 for every user on dev
   * (LlmDispatchPolicyException: "Provider dispatch requires an explicit durable customer or
   * PLATFORM_INTERNAL context"). Pin the actor-attributed execution reference AND the deliberate
   * 4096-token cap, so a future refactor cannot silently drop either.
   */
  @Test
  void dispatchesWithAnExplicitActorAttributedExecutionContext() {
    controller.chat(new ConversationRequest(List.of(user("make it better"))), principal());

    org.mockito.Mockito.verify(executionPolicyFactory)
        .forChatTurn(
            eq("user-1"),
            eq("org-1"),
            org.mockito.ArgumentMatchers.isNull(),
            eq("conversation.preflight"),
            any(),
            any(),
            any(),
            eq("anthropic"),
            eq("haiku"),
            org.mockito.ArgumentMatchers.isNull());

    ArgumentCaptor<LlmExecutionReference> reference =
        ArgumentCaptor.forClass(LlmExecutionReference.class);
    org.mockito.Mockito.verify(llmRouter)
        .callText(
            eq("anthropic"),
            eq("haiku"),
            any(),
            any(String.class),
            any(),
            eq("conversation.preflight"),
            eq(4096),
            any(),
            reference.capture());
    assertThat(reference.getValue().contextId()).isEqualTo("llmctx-conversation-test");
  }
}
