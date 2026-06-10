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

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RunConversationControllerGuardTest {

  @Test
  void applySuggestionRequiresRunWriteAccessBeforeValidation() {
    RunAccessGuard guard = mock(RunAccessGuard.class);
    RunConversationController controller =
        new RunConversationController(
            guard, mock(ChatService.class), mock(ApplyService.class), mock(ApplyResultStore.class));
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
}
