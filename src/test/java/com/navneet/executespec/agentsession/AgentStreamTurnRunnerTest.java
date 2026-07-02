package com.navneet.executespec.agentsession;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navneet.executespec.agentsession.AgentSessionService.StreamTurnContext;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorDecision;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorPolicy;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.StreamTurnRequest;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.specchat.SpecChatService;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AgentStreamTurnRunnerTest {

  @Test
  void failedStreamCompletesEmitterWithoutRethrowingIntoSpringErrorHandling() throws Exception {
    AgentSessionService sessionService = mock(AgentSessionService.class);
    SpecChatService specChatService = mock(SpecChatService.class);
    FeatureFlagService featureFlags = mock(FeatureFlagService.class);
    AgentAttachmentService attachmentService = mock(AgentAttachmentService.class);
    SseEmitter emitter = mock(SseEmitter.class);
    AgentStreamTurnRunner runner =
        new AgentStreamTurnRunner(sessionService, specChatService, featureFlags, attachmentService);
    StreamTurnContext ctx =
        new StreamTurnContext(
            "session-1",
            "turn-1",
            1,
            null,
            null,
            "org-1",
            "user-1",
            "anthropic",
            null,
            new OrchestratorDecision(
                "ANSWER",
                0.9,
                "chat",
                false,
                "",
                null,
                new OrchestratorPolicy(true, false, "allowed"),
                "trace-1"));

    when(featureFlags.isEnabled("spec.chat.enabled")).thenReturn(true);
    when(attachmentService.injectionBlock(anyString(), any())).thenReturn("");
    doThrow(new IllegalStateException("provider unavailable"))
        .when(specChatService)
        .runTurn(any(), any(), anyString(), any(), any(), anyString(), any(), any(), any());

    runner.run(emitter, ctx, null, new StreamTurnRequest("hello", null, null, null, null, null));

    verify(sessionService).failStreamTurn(ctx, "provider unavailable");
    verify(emitter).complete();
    verify(emitter, never()).completeWithError(any());
  }

  @Test
  void providerCredentialFailureUsesRecoveryMessageInsteadOfRawProviderJson() throws Exception {
    AgentSessionService sessionService = mock(AgentSessionService.class);
    SpecChatService specChatService = mock(SpecChatService.class);
    FeatureFlagService featureFlags = mock(FeatureFlagService.class);
    AgentAttachmentService attachmentService = mock(AgentAttachmentService.class);
    SseEmitter emitter = mock(SseEmitter.class);
    AgentStreamTurnRunner runner =
        new AgentStreamTurnRunner(sessionService, specChatService, featureFlags, attachmentService);
    StreamTurnContext ctx =
        new StreamTurnContext(
            "session-1",
            "turn-1",
            1,
            null,
            null,
            "org-1",
            "user-1",
            "anthropic",
            null,
            new OrchestratorDecision(
                "ANSWER",
                0.9,
                "chat",
                false,
                "",
                null,
                new OrchestratorPolicy(true, false, "allowed"),
                "trace-1"));

    when(featureFlags.isEnabled("spec.chat.enabled")).thenReturn(true);
    when(attachmentService.injectionBlock(anyString(), any())).thenReturn("");
    doThrow(
            new IllegalStateException(
                "{\"type\":\"error\",\"error\":{\"message\":\"invalid"
                    + " x-api-key\"},\"request_id\":\"req_123\"}"))
        .when(specChatService)
        .runTurn(any(), any(), anyString(), any(), any(), anyString(), any(), any(), any());

    runner.run(emitter, ctx, null, new StreamTurnRequest("hello", null, null, null, null, null));

    verify(sessionService)
        .failStreamTurn(
            ctx,
            "anthropic authentication failed. Check the provider key in Settings > Provider keys,"
                + " switch to a configured provider, or use managed AI credits before retrying.");
    verify(emitter).complete();
    verify(emitter, never()).completeWithError(any());
  }
}
