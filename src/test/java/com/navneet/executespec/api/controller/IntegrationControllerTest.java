package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.integration.IntegrationManager;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class IntegrationControllerTest {

  private final IntegrationManager manager = mock(IntegrationManager.class);
  private final RunAccessGuard guard = mock(RunAccessGuard.class);
  private final IntegrationController controller = new IntegrationController(manager, guard);

  @Test
  void webhookDoesNotExposeInternalExceptionMessage() {
    Logger logger = (Logger) LoggerFactory.getLogger(IntegrationController.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    when(manager.handleWebhook(anyString(), anyMap(), anyString()))
        .thenThrow(new IllegalStateException("jdbc password=secret"));

    try {
      var response = controller.webhook("jira", Map.of(), "{}");

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
      assertThat(response.getBody())
          .containsEntry("error", "Integration request could not be completed.");
      assertThat(response.getBody()).doesNotContainValue("jdbc password=secret");
      assertThat(appender.list)
          .extracting(ILoggingEvent::getFormattedMessage)
          .allSatisfy(message -> assertThat(message).doesNotContain("password=secret"));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void unknownWebhookSourceUsesStableClientError() {
    when(manager.handleWebhook(anyString(), anyMap(), anyString()))
        .thenThrow(new IllegalArgumentException("internal adapter registry details"));

    var response = controller.webhook("unknown", Map.of(), "{}");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("error", "Invalid integration request.");
    assertThat(response.getBody()).doesNotContainValue("internal adapter registry details");
  }
}
