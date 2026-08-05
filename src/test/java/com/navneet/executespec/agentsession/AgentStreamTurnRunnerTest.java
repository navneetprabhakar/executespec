package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.agentsession.AgentSessionService.StreamTurnContext;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorDecision;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorPolicy;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.StreamTurnRequest;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.executioncontext.LlmExecutionOperationClaim;
import com.navneet.executespec.executioncontext.LlmExecutionOperationCoordinator;
import com.navneet.executespec.executioncontext.LlmExecutionOperationDescriptor;
import com.navneet.executespec.executioncontext.LlmExecutionOperationService;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.specchat.SpecChatService;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AgentStreamTurnRunnerTest {

  @Test
  void capabilitiesOverviewSkipsProviderAndStreamsDeterministicCopy() throws Exception {
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
                "CHAT_ONLY",
                0.98,
                "capabilities_overview",
                false,
                "I can propose a confirmation-gated remote run.",
                null,
                new OrchestratorPolicy(false, false, "informational"),
                "trace-1"));

    when(featureFlags.isEnabled("spec.chat.enabled")).thenReturn(true);

    runner.run(emitter, ctx, null, new StreamTurnRequest("features", null, null, null, null, null));

    verify(specChatService, never())
        .runTurn(
            any(),
            any(),
            anyString(),
            any(),
            any(),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyString());
    verify(sessionService)
        .completeStreamTurn(
            eq(ctx),
            eq("I can propose a confirmation-gated remote run."),
            any(RunCostTracker.Snapshot.class),
            eq("OK"));
    verify(emitter).complete();
  }

  @Test
  void mapsDurableBudgetFailureToActionableCustomerCopy() {
    AgentSessionService sessionService = mock(AgentSessionService.class);
    SpecChatService specChatService = mock(SpecChatService.class);
    FeatureFlagService featureFlags = mock(FeatureFlagService.class);
    AgentAttachmentService attachmentService = mock(AgentAttachmentService.class);
    AgentEventRecorder eventRecorder = mock(AgentEventRecorder.class);
    SseEmitter emitter = mock(SseEmitter.class);
    AgentStreamTurnRunner runner =
        new AgentStreamTurnRunner(
            sessionService, specChatService, featureFlags, attachmentService, eventRecorder);
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
            new com.navneet.executespec.executioncontext.LlmDispatchPolicyException(
                com.navneet.executespec.executioncontext.LlmDispatchPolicyException.Reason
                    .BUDGET_EXHAUSTED,
                "Durable LLM execution-context budget is exhausted"))
        .when(specChatService)
        .runTurn(
            any(),
            any(),
            anyString(),
            any(),
            any(),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyString());

    runner.run(emitter, ctx, null, new StreamTurnRequest("hello", null, null, null, null, null));

    verify(sessionService)
        .failStreamTurn(
            ctx,
            "This agent turn reached its AI execution limit before a response was produced.",
            (RunCostTracker.Snapshot) null);
    verify(eventRecorder)
        .append(
            eq("session-1"),
            eq("turn-1"),
            eq("TURN_ERROR"),
            argThat(
                payload ->
                    payload instanceof java.util.Map<?, ?> map
                        && "AGENT_TURN_BUDGET_EXHAUSTED".equals(map.get("code"))
                        && "ACTION_REQUIRED".equals(map.get("category"))
                        && Boolean.TRUE.equals(map.get("retryable"))
                        && map.get("next_action").toString().contains("new agent turn")));
  }

  @Test
  void mapsProjectedCallBudgetFailureWithoutDispatchingOrLeakingRawException() {
    AgentSessionService sessionService = mock(AgentSessionService.class);
    SpecChatService specChatService = mock(SpecChatService.class);
    FeatureFlagService featureFlags = mock(FeatureFlagService.class);
    AgentAttachmentService attachmentService = mock(AgentAttachmentService.class);
    AgentEventRecorder eventRecorder = mock(AgentEventRecorder.class);
    SseEmitter emitter = mock(SseEmitter.class);
    AgentStreamTurnRunner runner =
        new AgentStreamTurnRunner(
            sessionService, specChatService, featureFlags, attachmentService, eventRecorder);
    StreamTurnContext ctx =
        new StreamTurnContext(
            "session-1",
            "turn-1",
            1,
            null,
            null,
            "org-1",
            "user-1",
            "deepseek",
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
            new com.navneet.executespec.cost.ProjectedCallBudgetExceededException(
                "run hard cap", 1.0, 0.95, 0.1))
        .when(specChatService)
        .runTurn(
            any(),
            any(),
            anyString(),
            any(),
            any(),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyString());

    runner.run(emitter, ctx, null, new StreamTurnRequest("hello", null, null, null, null, null));

    verify(sessionService)
        .failStreamTurn(
            ctx,
            "The next model call would exceed the configured cost cap, so no provider request was"
                + " sent.",
            (RunCostTracker.Snapshot) null);
    verify(eventRecorder)
        .append(
            eq("session-1"),
            eq("turn-1"),
            eq("TURN_ERROR"),
            argThat(
                payload ->
                    payload instanceof java.util.Map<?, ?> map
                        && "PROJECTED_CALL_BUDGET_EXCEEDED".equals(map.get("code"))
                        && "ACTION_REQUIRED".equals(map.get("category"))
                        && Double.valueOf(0.95).equals(map.get("actual_cost_usd"))
                        && Double.valueOf(1.0).equals(map.get("cap_usd"))
                        && Double.valueOf(0.1).equals(map.get("projected_call_usd"))
                        && map.get("next_action").toString().contains("Raise the run cap")));
  }

  @Test
  void persistsBoundedSemanticFramesForReplay() throws Exception {
    AgentSessionService sessionService = mock(AgentSessionService.class);
    SpecChatService specChatService = mock(SpecChatService.class);
    FeatureFlagService featureFlags = mock(FeatureFlagService.class);
    AgentAttachmentService attachmentService = mock(AgentAttachmentService.class);
    AgentEventRecorder eventRecorder = mock(AgentEventRecorder.class);
    SseEmitter emitter = mock(SseEmitter.class);
    AgentStreamTurnRunner runner =
        new AgentStreamTurnRunner(
            sessionService, specChatService, featureFlags, attachmentService, eventRecorder);
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
    doAnswer(
            invocation -> {
              SpecChatService.Sink sink = invocation.getArgument(0);
              sink.token("hello ");
              sink.toolCall("readFile", "{\"path\":\"README.md\"}", "call-1");
              sink.toolResult("call-1", "{\"ok\":true}");
              sink.done(1, new RunCostTracker.Snapshot(1, 2, 0, 0, 0.01, 0));
              return null;
            })
        .when(specChatService)
        .runTurn(
            any(),
            any(),
            anyString(),
            any(),
            any(),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyString());

    runner.run(emitter, ctx, null, new StreamTurnRequest("hello", null, null, null, null, null));

    verify(eventRecorder).append(eq("session-1"), eq("turn-1"), eq("ROUTE_DECISION"), any());
    verify(eventRecorder).append(eq("session-1"), eq("turn-1"), eq("TOOL_CALL"), any());
    verify(eventRecorder).append(eq("session-1"), eq("turn-1"), eq("TOOL_RESULT"), any());
    verify(eventRecorder).append(eq("session-1"), eq("turn-1"), eq("TURN_TEXT"), any());
    verify(eventRecorder).append(eq("session-1"), eq("turn-1"), eq("TURN_DONE"), any());
  }

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
        .runTurn(
            any(),
            any(),
            anyString(),
            any(),
            any(),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyString());

    runner.run(emitter, ctx, null, new StreamTurnRequest("hello", null, null, null, null, null));

    // RR-1453 — failure settles this turn's usage (null cost when no cost tracker is wired).
    verify(sessionService)
        .failStreamTurn(
            ctx, "IllegalStateException: provider unavailable", (RunCostTracker.Snapshot) null);
    verify(emitter).complete();
    verify(emitter, never()).completeWithError(any());
  }

  @Test
  void clientDisconnectPersistsPartialAssistantTextForReplay() throws Exception {
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
    var sends = new java.util.concurrent.atomic.AtomicInteger();
    doAnswer(
            invocation -> {
              if (sends.incrementAndGet() == 3) throw new java.io.IOException("broken pipe");
              return null;
            })
        .when(emitter)
        .send(any(SseEmitter.SseEventBuilder.class));
    doAnswer(
            invocation -> {
              SpecChatService.Sink sink = invocation.getArgument(0);
              sink.token("partial response");
              return null;
            })
        .when(specChatService)
        .runTurn(
            any(),
            any(),
            anyString(),
            any(),
            any(),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyString());

    runner.run(emitter, ctx, null, new StreamTurnRequest("hello", null, null, null, null, null));

    verify(sessionService).disconnectStreamTurn(ctx, "partial response", null);
    verify(emitter).complete();
  }

  @Test
  void providerFreeQaFixtureAlsoRetainsPartialTextOnDisconnect() throws Exception {
    AgentSessionService sessionService = mock(AgentSessionService.class);
    SseEmitter emitter = mock(SseEmitter.class);
    MockEnvironment environment = webQaEnvironment(false, "web-qa");
    AgentStreamTurnRunner runner =
        new AgentStreamTurnRunner(
            sessionService,
            mock(SpecChatService.class),
            mock(FeatureFlagService.class),
            mock(AgentAttachmentService.class),
            null,
            null,
            environment);
    StreamTurnContext ctx =
        new StreamTurnContext(
            "web-qa-fixture",
            "turn-qa",
            1,
            null,
            null,
            "org-1",
            "user-1",
            "anthropic",
            null,
            new OrchestratorDecision(
                "CHAT_ONLY",
                0.9,
                "fixture",
                false,
                "",
                null,
                new OrchestratorPolicy(false, false, "fixture"),
                "trace-qa"));

    var sends = new java.util.concurrent.atomic.AtomicInteger();
    doAnswer(
            invocation -> {
              if (sends.incrementAndGet() == 3) throw new java.io.IOException("broken pipe");
              return null;
            })
        .when(emitter)
        .send(any(SseEmitter.SseEventBuilder.class));

    runner.run(emitter, ctx, null, new StreamTurnRequest("fixture", null, null, null, null, null));

    verify(sessionService).disconnectStreamTurn(ctx, "Fixture stream started. ", null);
    verify(emitter).complete();
  }

  @Test
  void providerFreeQaFixtureRequiresExactProfileAndDisabledLiveDispatch() {
    assertThat(fixtureRunner(webQaEnvironment(false, "web-qa")).fixtureStreamEnabled("web-qa-1"))
        .isTrue();
  }

  @Test
  void providerFreeQaFixtureRejectsMixedProfiles() {
    assertThat(
            fixtureRunner(webQaEnvironment(false, "web-qa", "local"))
                .fixtureStreamEnabled("web-qa-1"))
        .isFalse();
  }

  @Test
  void providerFreeQaFixtureRejectsOrdinaryLocalAndDevProfiles() {
    assertThat(fixtureRunner(webQaEnvironment(false, "local")).fixtureStreamEnabled("web-qa-1"))
        .isFalse();
    assertThat(fixtureRunner(webQaEnvironment(false, "dev")).fixtureStreamEnabled("web-qa-1"))
        .isFalse();
  }

  @Test
  void providerFreeQaFixtureRejectsLiveLlmDispatch() {
    assertThat(fixtureRunner(webQaEnvironment(true, "web-qa")).fixtureStreamEnabled("web-qa-1"))
        .isFalse();
  }

  @Test
  void providerFreeQaFixtureRejectsWrongSessionPrefix() {
    assertThat(fixtureRunner(webQaEnvironment(false, "web-qa")).fixtureStreamEnabled("session-1"))
        .isFalse();
  }

  @Test
  void ordinarySessionUsesCurrentStreamInsideWebQaProfile() throws Exception {
    AgentSessionService sessionService = mock(AgentSessionService.class);
    SpecChatService specChatService = mock(SpecChatService.class);
    FeatureFlagService featureFlags = mock(FeatureFlagService.class);
    AgentAttachmentService attachmentService = mock(AgentAttachmentService.class);
    SseEmitter emitter = mock(SseEmitter.class);
    AgentStreamTurnRunner runner =
        new AgentStreamTurnRunner(
            sessionService,
            specChatService,
            featureFlags,
            attachmentService,
            null,
            null,
            webQaEnvironment(false, "web-qa"));
    StreamTurnContext ctx =
        new StreamTurnContext(
            "session-current",
            "turn-current",
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
                "trace-current"));

    when(featureFlags.isEnabled("spec.chat.enabled")).thenReturn(true);
    when(attachmentService.injectionBlock(anyString(), any())).thenReturn("");
    doAnswer(
            invocation -> {
              SpecChatService.Sink sink = invocation.getArgument(0);
              sink.token("current response");
              sink.done(1, new RunCostTracker.Snapshot(1, 2, 0, 0, 0.01, 1));
              return null;
            })
        .when(specChatService)
        .runTurn(
            any(),
            any(),
            anyString(),
            any(),
            any(),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyString());

    runner.run(emitter, ctx, null, new StreamTurnRequest("hello", null, null, null, null, null));

    verify(specChatService)
        .runTurn(
            any(),
            any(),
            eq("session-current"),
            any(),
            any(),
            eq("org-1"),
            any(),
            any(),
            any(),
            any(),
            eq("turn-current"));
    verify(sessionService)
        .completeStreamTurn(eq(ctx), eq("current response"), any(RunCostTracker.Snapshot.class));
    verify(emitter).complete();
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
        .runTurn(
            any(),
            any(),
            anyString(),
            any(),
            any(),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyString());

    runner.run(emitter, ctx, null, new StreamTurnRequest("hello", null, null, null, null, null));

    verify(sessionService)
        .failStreamTurn(ctx, "anthropic authentication failed.", (RunCostTracker.Snapshot) null);
    verify(emitter).complete();
    verify(emitter, never()).completeWithError(any());
  }

  @Test
  void durableTurnPersistsCompletionBeforeCompletingOperation() throws Exception {
    DurableFixture fixture = durableFixture();
    doAnswer(
            invocation -> {
              java.util.function.Consumer<SpecChatService.DurableTurnOperation> consumer =
                  invocation.getArgument(11);
              consumer.accept(fixture.operation());
              SpecChatService.Sink sink = invocation.getArgument(0);
              sink.token("durable answer");
              sink.done(1, new RunCostTracker.Snapshot(3, 4, 0, 0, 0.02, 1));
              return null;
            })
        .when(fixture.specChat())
        .runDurableAgentTurn(
            any(),
            any(),
            anyString(),
            any(),
            any(),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyString(),
            any());
    fixture
        .runner()
        .run(
            fixture.emitter(),
            fixture.context(),
            null,
            new StreamTurnRequest("hello", null, null, null, null, null));

    InOrder order =
        org.mockito.Mockito.inOrder(fixture.sessionService(), fixture.operationService());
    order
        .verify(fixture.sessionService())
        .completeStreamTurn(
            eq(fixture.context()), eq("durable answer"), any(RunCostTracker.Snapshot.class));
    order.verify(fixture.operationService()).complete(fixture.claim(), fixture.owner());
  }

  @Test
  void durableTurnPersistsFailureBeforeFailingOperation() {
    DurableFixture fixture = durableFixture();
    doAnswer(
            invocation -> {
              java.util.function.Consumer<SpecChatService.DurableTurnOperation> consumer =
                  invocation.getArgument(11);
              consumer.accept(fixture.operation());
              throw new IllegalStateException("provider failed");
            })
        .when(fixture.specChat())
        .runDurableAgentTurn(
            any(),
            any(),
            anyString(),
            any(),
            any(),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyString(),
            any());

    fixture
        .runner()
        .run(
            fixture.emitter(),
            fixture.context(),
            null,
            new StreamTurnRequest("hello", null, null, null, null, null));

    InOrder order =
        org.mockito.Mockito.inOrder(fixture.sessionService(), fixture.operationService());
    order
        .verify(fixture.sessionService())
        .failStreamTurn(eq(fixture.context()), anyString(), any(RunCostTracker.Snapshot.class));
    order
        .verify(fixture.operationService())
        .fail(
            eq(fixture.claim()),
            eq(fixture.owner()),
            any(LlmExecutionOperationService.Failure.class));
  }

  @Test
  void durableTurnPersistsDisconnectBeforeCancellationAcknowledgement() {
    DurableFixture fixture = durableFixture();
    doAnswer(
            invocation -> {
              java.util.function.Consumer<SpecChatService.DurableTurnOperation> consumer =
                  invocation.getArgument(11);
              consumer.accept(fixture.operation());
              throw new IOException("Broken pipe");
            })
        .when(fixture.specChat())
        .runDurableAgentTurn(
            any(),
            any(),
            anyString(),
            any(),
            any(),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyString(),
            any());

    fixture
        .runner()
        .run(
            fixture.emitter(),
            fixture.context(),
            null,
            new StreamTurnRequest("hello", null, null, null, null, null));

    InOrder order =
        org.mockito.Mockito.inOrder(fixture.sessionService(), fixture.operationService());
    order
        .verify(fixture.sessionService())
        .disconnectStreamTurn(eq(fixture.context()), eq(null), any(RunCostTracker.Snapshot.class));
    order
        .verify(fixture.operationService())
        .requestCancellation(
            fixture.operation().reference(), SpecChatService.AGENT_TURN_OPERATION_KEY);
    order
        .verify(fixture.operationService())
        .acknowledgeCancellation(fixture.claim(), fixture.owner());
  }

  private static DurableFixture durableFixture() {
    AgentSessionService sessionService = mock(AgentSessionService.class);
    SpecChatService specChat = mock(SpecChatService.class);
    FeatureFlagService flags = mock(FeatureFlagService.class);
    AgentAttachmentService attachments = mock(AgentAttachmentService.class);
    RunCostTracker costs = mock(RunCostTracker.class);
    LlmExecutionOperationService operationService = mock(LlmExecutionOperationService.class);
    LlmExecutionOperationCoordinator coordinator =
        new LlmExecutionOperationCoordinator(operationService);
    SseEmitter emitter = mock(SseEmitter.class);
    LlmExecutionReference reference =
        new LlmExecutionReference("llmctx-turn", LlmExecutionScope.customer("org-1"));
    LlmExecutionOperationClaim claim =
        new LlmExecutionOperationClaim(reference, "llmop-turn", 1, 1, UUID.randomUUID());
    String owner = "agent-turn-test-owner";
    when(operationService.claimOrResume(any(), any(), eq(owner), any()))
        .thenReturn(Optional.of(claim));
    when(operationService.complete(claim, owner)).thenReturn(true);
    when(operationService.fail(eq(claim), eq(owner), any())).thenReturn(true);
    when(operationService.acknowledgeCancellation(claim, owner)).thenReturn(true);
    LlmExecutionOperationCoordinator.Lease lease =
        coordinator
            .claimOrResume(
                reference,
                LlmExecutionOperationDescriptor.of(
                    SpecChatService.AGENT_TURN_OPERATION_KEY, "AGENT_TURN", "runner-test", 1),
                owner,
                Duration.ofMinutes(30))
            .orElseThrow();
    SpecChatService.DurableTurnOperation operation =
        new SpecChatService.DurableTurnOperation(reference, lease);
    StreamTurnContext context =
        new StreamTurnContext(
            "session-durable",
            "turn-durable",
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
                "trace-durable"));
    when(flags.isEnabled("spec.chat.enabled")).thenReturn(true);
    when(attachments.injectionBlock(anyString(), any())).thenReturn("");
    when(attachments.withMediaAttachments(any(), anyString(), any()))
        .thenAnswer(i -> i.getArgument(0));
    when(costs.snapshot(anyString()))
        .thenReturn(new RunCostTracker.Snapshot(0, 0, 0, 0, 0.0, 0))
        .thenReturn(new RunCostTracker.Snapshot(3, 4, 0, 0, 0.02, 1));
    AgentStreamTurnRunner runner =
        new AgentStreamTurnRunner(
            sessionService,
            specChat,
            flags,
            attachments,
            null,
            costs,
            new MockEnvironment(),
            coordinator);
    return new DurableFixture(
        runner,
        sessionService,
        specChat,
        operationService,
        costs,
        emitter,
        context,
        operation,
        lease,
        claim,
        owner);
  }

  private record DurableFixture(
      AgentStreamTurnRunner runner,
      AgentSessionService sessionService,
      SpecChatService specChat,
      LlmExecutionOperationService operationService,
      RunCostTracker costs,
      SseEmitter emitter,
      StreamTurnContext context,
      SpecChatService.DurableTurnOperation operation,
      LlmExecutionOperationCoordinator.Lease lease,
      LlmExecutionOperationClaim claim,
      String owner) {}

  private static AgentStreamTurnRunner fixtureRunner(MockEnvironment environment) {
    return new AgentStreamTurnRunner(
        mock(AgentSessionService.class),
        mock(SpecChatService.class),
        mock(FeatureFlagService.class),
        mock(AgentAttachmentService.class),
        null,
        null,
        environment);
  }

  private static MockEnvironment webQaEnvironment(boolean liveLlmEnabled, String... profiles) {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("executespec.live-llm.enabled", Boolean.toString(liveLlmEnabled));
    environment.setActiveProfiles(profiles);
    return environment;
  }
}
