package com.navneet.executespec.tool.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RemoteToolControllerTest {

  private final ObjectMapper om = new ObjectMapper();
  private final RemoteToolDispatcher dispatcher = mock(RemoteToolDispatcher.class);
  private final RunAccessGuard guard = mock(RunAccessGuard.class);
  private final RemoteToolController controller = new RemoteToolController(dispatcher, guard, om);

  @Test
  void submitToolResult_normalizesSuccessEnvelopeBeforeDispatch() throws Exception {
    when(guard.requireRunWriteAccess(eq("run-1"), any())).thenReturn(new PipelineRun());

    var body =
        new RemoteToolController.ToolResultBody(
            "call-1",
            om.readTree("{\"ok\":true,\"content\":{\"nested\":true},\"extra\":\"drop\"}"));
    when(dispatcher.complete(eq("run-1"), eq("call-1"), any()))
        .thenReturn(RemoteToolDispatcher.CompletionResult.accepted());

    var response = controller.submitToolResult(null, "run-1", body);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    ArgumentCaptor<String> envelope = ArgumentCaptor.forClass(String.class);
    verify(dispatcher).complete(eq("run-1"), eq("call-1"), envelope.capture());
    assertThat(om.readTree(envelope.getValue()).toString())
        .isEqualTo("{\"ok\":true,\"content\":\"{\\\"nested\\\":true}\"}");
  }

  @Test
  void submitToolResult_turnsMalformedEnvelopeIntoInternalError() throws Exception {
    when(guard.requireRunWriteAccess(eq("run-1"), any())).thenReturn(new PipelineRun());

    var body =
        new RemoteToolController.ToolResultBody("call-2", om.readTree("\"not an envelope\""));
    when(dispatcher.complete(eq("run-1"), eq("call-2"), any()))
        .thenReturn(RemoteToolDispatcher.CompletionResult.accepted());

    var response = controller.submitToolResult(null, "run-1", body);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    ArgumentCaptor<String> envelope = ArgumentCaptor.forClass(String.class);
    verify(dispatcher).complete(eq("run-1"), eq("call-2"), envelope.capture());
    assertThat(om.readTree(envelope.getValue()).toString())
        .isEqualTo("{\"ok\":false,\"kind\":\"INTERNAL\",\"message\":\"malformed tool result\"}");
  }

  @Test
  void submitToolResult_rejectsMissingCallId() {
    var response =
        controller.submitToolResult(
            null,
            "run-1",
            new RemoteToolController.ToolResultBody(" ", om.createObjectNode().put("ok", true)));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    verify(dispatcher, never()).complete(any(), any(), any());
  }

  @Test
  void submitToolResult_rejectsUnknownSession() throws Exception {
    when(guard.requireRunWriteAccess(eq("run-1"), any())).thenReturn(new PipelineRun());
    when(dispatcher.complete(eq("run-1"), eq("call-3"), any()))
        .thenReturn(RemoteToolDispatcher.CompletionResult.unknownSession());

    var body =
        new RemoteToolController.ToolResultBody(
            "call-3", om.readTree("{\"ok\":true,\"content\":\"late\"}"));

    assertThatThrownBy(() -> controller.submitToolResult(null, "run-1", body))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void submitToolResult_rejectsUnknownCallId() throws Exception {
    when(guard.requireRunWriteAccess(eq("run-1"), any())).thenReturn(new PipelineRun());
    when(dispatcher.complete(eq("run-1"), eq("missing-call"), any()))
        .thenReturn(RemoteToolDispatcher.CompletionResult.unknownCall());

    var body =
        new RemoteToolController.ToolResultBody(
            "missing-call", om.readTree("{\"ok\":true,\"content\":\"late\"}"));

    assertThatThrownBy(() -> controller.submitToolResult(null, "run-1", body))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void submitToolResult_capsContentAndErrorMessagesBeforeDispatch() throws Exception {
    when(guard.requireRunWriteAccess(eq("run-1"), any())).thenReturn(new PipelineRun());
    when(dispatcher.complete(eq("run-1"), eq("call-big"), any()))
        .thenReturn(RemoteToolDispatcher.CompletionResult.accepted());

    var body =
        new RemoteToolController.ToolResultBody(
            "call-big",
            om.createObjectNode()
                .put("ok", true)
                .put("content", "x".repeat(RemoteToolController.MAX_RESULT_CONTENT_CHARS + 50)));

    var response = controller.submitToolResult(null, "run-1", body);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    ArgumentCaptor<String> envelope = ArgumentCaptor.forClass(String.class);
    verify(dispatcher).complete(eq("run-1"), eq("call-big"), envelope.capture());
    String content = om.readTree(envelope.getValue()).get("content").asText();
    assertThat(content).endsWith("\n...[truncated by backend]");
    assertThat(content.length()).isGreaterThan(RemoteToolController.MAX_RESULT_CONTENT_CHARS);
  }
}
