package com.navneet.executespec.tool.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.security.JwtAuthenticationFilter;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class RemoteToolControllerTest {

  private final ObjectMapper om = new ObjectMapper();
  private final RemoteToolDispatcher dispatcher = mock(RemoteToolDispatcher.class);
  private final RunAccessGuard guard = mock(RunAccessGuard.class);
  private final RemoteToolController controller = new RemoteToolController(dispatcher, guard, om);

  @Test
  void submitToolResult_normalizesSuccessEnvelopeBeforeDispatch() throws Exception {
    when(guard.requireRunWriteAccess(eq("run-1"), any())).thenReturn(new PipelineRun());
    when(dispatcher.complete(eq("run-1"), eq("call-1"), any()))
        .thenReturn(RemoteToolDispatcher.CompletionOutcome.ACCEPTED);

    var body =
        new RemoteToolController.ToolResultBody(
            "call-1",
            "run-1",
            om.readTree("{\"ok\":true,\"content\":{\"nested\":true},\"extra\":\"drop\"}"));

    var response = controller.submitToolResult(null, "run-1", body, patRequest());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    ArgumentCaptor<String> envelope = ArgumentCaptor.forClass(String.class);
    verify(dispatcher).complete(eq("run-1"), eq("call-1"), envelope.capture());
    assertThat(om.readTree(envelope.getValue()).toString())
        .isEqualTo("{\"ok\":true,\"content\":\"{\\\"nested\\\":true}\"}");
  }

  @Test
  void submitToolResult_turnsMalformedEnvelopeIntoInternalError() throws Exception {
    when(guard.requireRunWriteAccess(eq("run-1"), any())).thenReturn(new PipelineRun());
    when(dispatcher.complete(eq("run-1"), eq("call-2"), any()))
        .thenReturn(RemoteToolDispatcher.CompletionOutcome.ACCEPTED);

    var body =
        new RemoteToolController.ToolResultBody("call-2", om.readTree("\"not an envelope\""));

    var response = controller.submitToolResult(null, "run-1", body, patRequest());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    ArgumentCaptor<String> envelope = ArgumentCaptor.forClass(String.class);
    verify(dispatcher).complete(eq("run-1"), eq("call-2"), envelope.capture());
    assertThat(om.readTree(envelope.getValue()).toString())
        .isEqualTo("{\"ok\":false,\"kind\":\"INTERNAL\",\"message\":\"malformed tool result\"}");
  }

  @Test
  void submitToolResult_rejectsMissingCallId() {
    when(guard.requireRunWriteAccess(eq("run-1"), any())).thenReturn(new PipelineRun());

    var response =
        controller.submitToolResult(
            null,
            "run-1",
            new RemoteToolController.ToolResultBody(" ", om.createObjectNode().put("ok", true)),
            patRequest());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    verifyNoInteractions(dispatcher);
  }

  @Test
  void submitToolResult_rejectsBrowserJwtSession() {
    when(guard.requireRunWriteAccess(eq("run-1"), any())).thenReturn(new PipelineRun());

    var response =
        org.assertj.core.api.Assertions.catchThrowableOfType(
            () ->
                controller.submitToolResult(
                    null,
                    "run-1",
                    new RemoteToolController.ToolResultBody(
                        "call-1", om.createObjectNode().put("ok", true)),
                    jwtRequest()),
            org.springframework.web.server.ResponseStatusException.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    verifyNoInteractions(dispatcher);
  }

  @Test
  void submitToolResult_rejectsBodyRunIdMismatch() {
    when(guard.requireRunWriteAccess(eq("run-1"), any())).thenReturn(new PipelineRun());

    var response =
        controller.submitToolResult(
            null,
            "run-1",
            new RemoteToolController.ToolResultBody(
                "call-1", "other-run", om.createObjectNode().put("ok", true)),
            patRequest());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    verifyNoInteractions(dispatcher);
  }

  @Test
  void submitToolResult_rejectsTerminalRunResult() {
    PipelineRun run = new PipelineRun();
    run.setStatus("CANCELLED");
    when(guard.requireRunWriteAccess(eq("run-1"), any())).thenReturn(run);

    var response =
        controller.submitToolResult(
            null,
            "run-1",
            new RemoteToolController.ToolResultBody(
                "call-1", "run-1", om.createObjectNode().put("ok", true)),
            patRequest());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    verifyNoInteractions(dispatcher);
  }

  @Test
  void submitToolResult_mapsUnknownAndDuplicateDispatcherOutcomes() {
    when(guard.requireRunWriteAccess(eq("run-1"), any())).thenReturn(new PipelineRun());
    when(dispatcher.complete(eq("run-1"), eq("missing"), any()))
        .thenReturn(RemoteToolDispatcher.CompletionOutcome.UNKNOWN_CALL);
    when(dispatcher.complete(eq("run-1"), eq("dup"), any()))
        .thenReturn(RemoteToolDispatcher.CompletionOutcome.DUPLICATE);

    var missing =
        controller.submitToolResult(
            null,
            "run-1",
            new RemoteToolController.ToolResultBody(
                "missing", "run-1", om.createObjectNode().put("ok", true)),
            patRequest());
    var duplicate =
        controller.submitToolResult(
            null,
            "run-1",
            new RemoteToolController.ToolResultBody(
                "dup", "run-1", om.createObjectNode().put("ok", true)),
            patRequest());

    assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void submitToolResult_redactsAndTruncatesClientOutput() throws Exception {
    when(guard.requireRunWriteAccess(eq("run-1"), any())).thenReturn(new PipelineRun());
    when(dispatcher.complete(eq("run-1"), eq("call-secret"), any()))
        .thenReturn(RemoteToolDispatcher.CompletionOutcome.ACCEPTED);
    String secret = "before es_pat_ABCDEF1234567890 after ";
    String huge = secret + "x".repeat(70_000);

    controller.submitToolResult(
        null,
        "run-1",
        new RemoteToolController.ToolResultBody(
            "call-secret", "run-1", om.createObjectNode().put("ok", true).put("content", huge)),
        patRequest());

    ArgumentCaptor<String> envelope = ArgumentCaptor.forClass(String.class);
    verify(dispatcher).complete(eq("run-1"), eq("call-secret"), envelope.capture());
    String content = om.readTree(envelope.getValue()).get("content").asText();
    assertThat(content).contains("[REDACTED_SECRET]");
    assertThat(content).contains("[truncated:");
    assertThat(content).doesNotContain("es_pat_ABCDEF1234567890");
  }

  private static MockHttpServletRequest patRequest() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(
        JwtAuthenticationFilter.AUTH_SOURCE_ATTRIBUTE, JwtAuthenticationFilter.AUTH_SOURCE_PAT);
    return request;
  }

  private static MockHttpServletRequest jwtRequest() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(
        JwtAuthenticationFilter.AUTH_SOURCE_ATTRIBUTE, JwtAuthenticationFilter.AUTH_SOURCE_JWT);
    return request;
  }
}
