package com.navneet.executespec.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.navneet.executespec.auth.service.MailService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * RR-368 — the secret-gated public reminder relay must bound the accepted message size so a
 * leaked/replayed scheduler token cannot drive arbitrary oversized-body allocation and mail spend.
 */
class AdminReminderControllerTest {

  private MailService mailService;
  private AdminReminderController controller;

  @BeforeEach
  void setUp() {
    mailService = mock(MailService.class);
    controller = new AdminReminderController(mailService);
    ReflectionTestUtils.setField(controller, "expectedToken", "secret-token");
  }

  @Test
  void rejectsOversizedBodyWithoutDispatching() {
    String hugeBody = "x".repeat(AdminReminderController.MAX_BODY_CHARS + 1);
    assertThatThrownBy(
            () ->
                controller.send(
                    "secret-token",
                    new AdminReminderController.ReminderRequest("subject", hugeBody)))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    verify(mailService, never()).sendAdminReminderEmail(anyString(), anyString(), anyString());
  }

  @Test
  void acceptsInBoundsRequest() {
    org.mockito.Mockito.when(mailService.getAdminAddress()).thenReturn("admin@executespec.ai");
    var resp =
        controller.send(
            "secret-token", new AdminReminderController.ReminderRequest("subject", "body"));
    assertThat(resp.getStatusCode().value()).isEqualTo(200);
    verify(mailService).sendAdminReminderEmail("admin@executespec.ai", "subject", "body");
  }
}
