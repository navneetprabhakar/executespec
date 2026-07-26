package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.chat.ApplyResultStore;
import com.navneet.executespec.chat.ApplyService;
import com.navneet.executespec.chat.ChatService;
import com.navneet.executespec.run.RunIterationService;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RunConversationControllerGuardTest {

  @Test
  void applySuggestionRequiresRunWriteAccessBeforeValidation() {
    RunAccessGuard guard = mock(RunAccessGuard.class);
    RunConversationController controller =
        new RunConversationController(
            guard,
            mock(ChatService.class),
            mock(ApplyService.class),
            mock(ApplyResultStore.class),
            mock(RunIterationService.class));
    AppUserDetails user = mock(AppUserDetails.class);

    assertThatThrownBy(
            () ->
                controller.applySuggestion(
                    "run-1", new RunConversationController.ApplyBody("", null), user))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("suggestion is required");

    verify(guard).requireRunWriteAccess("run-1", user);
    verify(guard, never()).requireRunAccess("run-1", user);
  }

  // RR-599 — an oversize chat turn is rejected at the boundary before it reaches ChatService.
  @Test
  void sendMessageRejectsOversizeTurn() {
    RunAccessGuard guard = mock(RunAccessGuard.class);
    ChatService chat = mock(ChatService.class);
    RunConversationController controller =
        new RunConversationController(
            guard,
            chat,
            mock(ApplyService.class),
            mock(ApplyResultStore.class),
            mock(RunIterationService.class));
    AppUserDetails user = mock(AppUserDetails.class);
    String big = "x".repeat(RunConversationController.MAX_CHAT_MESSAGE_CHARS + 1);

    assertThatThrownBy(
            () ->
                controller.sendMessage(
                    "run-1", new RunConversationController.ChatMessageBody(big), user))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("413");

    verify(guard).requireRunWriteAccess("run-1", user);
    verify(chat, never())
        .send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void sendMessagePreservesManagedAiAdmissionStatus() {
    RunAccessGuard guard = mock(RunAccessGuard.class);
    ChatService chat = mock(ChatService.class);
    RunConversationController controller =
        new RunConversationController(
            guard,
            chat,
            mock(ApplyService.class),
            mock(ApplyResultStore.class),
            mock(RunIterationService.class));
    AppUserDetails user = mock(AppUserDetails.class);
    org.mockito.Mockito.when(chat.send("run-1", "why"))
        .thenThrow(
            new ResponseStatusException(
                org.springframework.http.HttpStatus.PAYMENT_REQUIRED,
                "managed AI credits unavailable"));

    assertThatThrownBy(
            () ->
                controller.sendMessage(
                    "run-1", new RunConversationController.ChatMessageBody("why"), user))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("402")
        .hasMessageContaining("managed AI credits unavailable");
  }

  @Test
  void iterateRequiresWriteAccessAndDelegatesToIterationService() {
    RunAccessGuard guard = mock(RunAccessGuard.class);
    RunIterationService iterations = mock(RunIterationService.class);
    RunConversationController controller =
        new RunConversationController(
            guard,
            mock(ChatService.class),
            mock(ApplyService.class),
            mock(ApplyResultStore.class),
            iterations);
    AppUserDetails user = mock(AppUserDetails.class);
    var child =
        com.navneet.executespec.state.entity.PipelineRun.builder()
            .runId("child-1")
            .sourceRunId("run-1")
            .status("PLANNING")
            .aiProvider("ANTHROPIC")
            .build();
    org.mockito.Mockito.when(iterations.iterate("run-1", "add edge cases", "key-1", user))
        .thenReturn(new RunIterationService.IterationResult(child, false));

    var response =
        controller.iterateRun(
            "run-1", new RunConversationController.IterateBody("add edge cases"), "key-1", user);

    org.assertj.core.api.Assertions.assertThat(response.getStatusCode().value()).isEqualTo(202);
    org.assertj.core.api.Assertions.assertThat(response.getBody().sourceRunId()).isEqualTo("run-1");
    verify(guard).requireRunWriteAccess("run-1", user);
    verify(iterations).iterate("run-1", "add edge cases", "key-1", user);
  }
}
