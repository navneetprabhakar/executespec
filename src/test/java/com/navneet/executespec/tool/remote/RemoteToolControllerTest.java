package com.navneet.executespec.tool.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

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
  }
}
