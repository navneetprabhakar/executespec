package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.navneet.executespec.billing.service.ManagedAiUsageService;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.cost.RunStepUsageRecorder;
import com.navneet.executespec.executioncontext.LlmDispatchPolicyException;
import com.navneet.executespec.executioncontext.LlmExecutionContext;
import com.navneet.executespec.executioncontext.LlmExecutionDispatchGuard;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.router.RunRoutePolicyService;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.ToolRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("LlmRouter dispatch / retry / failover")
class LlmRouterTest {

  private OpenAiNativeToolClient openai;
  private GeminiNativeToolClient gemini;
  private RunKeyContextStore keyStore;
  private RunKeySourceModeResolver keySourceModeResolver;
  private LlmExecutionDispatchGuard executionDispatchGuard;
  private LlmExecutionPolicyFactory executionPolicyFactory;
  private ModelRegistry registry;
  private FeatureFlagService flags;
  private Metrics metrics;
  private RunCostTracker costTracker;
  private RunStepUsageRecorder stepUsageRecorder;
  private AnthropicForcedToolClient anthropicForcedToolClient;
  private ManagedAiUsageService managedAiUsageService;
  private RunRoutePolicyService routePolicyService;
  private ObjectProvider<AnthropicChatModel> anthropicModelProvider;
  private SseBroadcaster broadcaster;
  private LlmRouter router; // a spy with backoffSleep no-op'd

  private LlmRouter build() {
    return build(new ProviderDispatchAuthorization(true, false, ""));
  }

  private LlmRouter build(ProviderDispatchAuthorization dispatchAuthorization) {
    ToolRegistry toolRegistry = mock(ToolRegistry.class);
    lenient().when(toolRegistry.bind(any())).thenReturn(List.of());
    openai = mock(OpenAiNativeToolClient.class);
    gemini = mock(GeminiNativeToolClient.class);
    metrics = mock(Metrics.class);
    costTracker = mock(RunCostTracker.class);
    stepUsageRecorder = mock(RunStepUsageRecorder.class);
    lenient()
        .when(
            stepUsageRecorder.prepareAttempt(
                any(LlmRequest.class),
                anyString(),
                anyString(),
                anyString(),
                anyInt(),
                anyString(),
                nullable(String.class)))
        .thenAnswer(
            invocation -> {
              LlmRequest request = invocation.getArgument(0);
              String provider = invocation.getArgument(1);
              String model = invocation.getArgument(2);
              String keySource = invocation.getArgument(3);
              int ordinal = invocation.getArgument(4);
              String kind = invocation.getArgument(5);
              String parent = invocation.getArgument(6);
              return new RunStepUsageRecorder.AttemptHandle(
                  request.usageLogicalCallId() + ":" + provider + ":" + ordinal,
                  request.usageLogicalCallId(),
                  parent,
                  kind,
                  ordinal,
                  provider,
                  model,
                  "WORKHORSE",
                  keySource,
                  ordinal,
                  null);
            });
    lenient()
        .when(costTracker.record(anyString(), anyString(), anyString(), anyString(), any()))
        .thenReturn(new RunCostTracker.CallUsage("openai", "gpt-x", 1, 2, 0, 0, 0.01, 1, 0));
    lenient()
        .when(costTracker.snapshot(anyString()))
        .thenReturn(new RunCostTracker.Snapshot(0, 0, 0, 0, 0.0, 0));
    broadcaster = mock(SseBroadcaster.class);
    keyStore = mock(RunKeyContextStore.class);
    executionDispatchGuard = mock(LlmExecutionDispatchGuard.class);
    executionPolicyFactory = mock(LlmExecutionPolicyFactory.class);
    lenient().when(keyStore.get(anyString())).thenReturn(null); // platform keys by default
    keySourceModeResolver = mock(RunKeySourceModeResolver.class);
    // Default: NOT a pure-BYOK run, so platform fallback is permitted (existing behavior).
    lenient().when(keySourceModeResolver.isOwnKeysRun(anyString())).thenReturn(false);
    anthropicForcedToolClient = mock(AnthropicForcedToolClient.class);
    registry = mock(ModelRegistry.class);
    flags = mock(FeatureFlagService.class); // isEnabled defaults to false
    managedAiUsageService = mock(ManagedAiUsageService.class);
    routePolicyService = mock(RunRoutePolicyService.class);
    lenient()
        .when(routePolicyService.forRun(anyString()))
        .thenReturn(new RunRoutePolicyService.RoutePolicy(null, null, "PROVIDER_PREFERRED", false));
    anthropicModelProvider = mock(ObjectProvider.class);
    lenient().when(anthropicModelProvider.getIfAvailable()).thenReturn(null);
    lenient()
        .when(
            managedAiUsageService.authorizeCall(
                anyString(), anyString(), anyString(), any(), any(), any()))
        .thenAnswer(
            inv ->
                ManagedAiUsageService.CallAuthorization.allowed(
                    inv.getArgument(1), inv.getArgument(2), ManagedAiUsageService.FULL));
    LlmRouter real =
        new LlmRouter(
            toolRegistry,
            openai,
            gemini,
            metrics,
            costTracker,
            stepUsageRecorder,
            broadcaster,
            keyStore,
            executionDispatchGuard,
            executionPolicyFactory,
            keySourceModeResolver,
            anthropicForcedToolClient,
            registry,
            flags,
            managedAiUsageService,
            routePolicyService,
            dispatchAuthorization,
            new com.navneet.executespec.security.ContextSanitizer(),
            anthropicModelProvider);
    router = spy(real);
    ReflectionTestUtils.setField(router, "executionContextEnforcementEnabled", false);
    doNothing().when(router).backoffSleep(anyLong()); // keep retry tests fast
    return router;
  }

  private static ChatResponse ok() {
    return new ChatResponse(
        List.of(new Generation(AssistantMessage.builder().content("ok").build())));
  }

  private static LlmRequest req(String provider, String model) {
    return LlmRequest.builder()
        .provider(provider)
        .model(model)
        .messages(List.of(new UserMessage("hi")))
        .context(new ExecutionContext("run-1", "T1", Path.of("/tmp/router-test")))
        .build();
  }

  private static RunKeyContext deepSeekFailoverKeys() {
    return new RunKeyContext(
        null,
        null,
        null,
        "openrouter-key",
        "deepseek-key",
        "fireworks-key",
        RunKeyContext.Source.PLATFORM,
        RunKeyContext.Source.USER,
        RunKeyContext.Source.PLATFORM,
        RunKeyContext.Source.USER,
        RunKeyContext.Source.USER,
        RunKeyContext.Source.USER,
        "u1",
        "o1");
  }

  @Test
  void exactRouteForcesStrictProviderAndRejectsAnyPairSubstitution() {
    LlmRouter subject = build();
    when(registry.canonicalize(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
    var policy =
        new RunRoutePolicyService.RoutePolicy(
            "DEEPSEEK", "deepseek-v4-pro-native", "EXACT_LOCK", true);

    LlmRequest locked =
        subject.enforceDurableRoutePolicy(req("deepseek", "deepseek-v4-pro-native"), policy);
    assertThat(locked.strictProvider()).isTrue();
    assertThatThrownBy(
            () ->
                subject.enforceDurableRoutePolicy(
                    req("openrouter", "deepseek-v4-pro-native"), policy))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Exact run route violation");
  }

  @Test
  void testTransportKillSwitchRejectsBeforeAnyProviderOrUsageCall() {
    // RR-392 — the kill switch now lives in the shared ProviderDispatchAuthorization component, so
    // it is injected rather than reflected onto a router field.
    LlmRouter subject = build(new ProviderDispatchAuthorization(false, false, ""));

    assertThatThrownBy(() -> subject.call(req("openai", "gpt-x")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Live LLM transport is disabled");
    verify(openai, never()).call(any());
    verify(managedAiUsageService, never())
        .authorizeCall(anyString(), anyString(), anyString(), any(), any(), any());
  }

  @Test
  void goalLiveTestModeRequiresAProviderBudgetReservation() {
    LlmRouter subject = build(new ProviderDispatchAuthorization(true, true, ""));

    assertThatThrownBy(() -> subject.call(req("openai", "gpt-x")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("requires executespec.live-llm.reservation-id");
    verify(openai, never()).call(any());
  }

  private static LlmRequest attributedReq(String provider, String model) {
    return req(provider, model).toBuilder()
        .agentRole("WorkerAgent")
        .stepId("worker.iteration-1")
        .build();
  }

  @Test
  void success_returns_response() {
    build();
    when(openai.call(any())).thenReturn(ok());
    assertThat(router.call(req("openai", "gpt-x"))).isNotNull();
    verify(openai, times(1)).call(any());
  }

  @Test
  void ownKeysRunWithNoCustomerKeyFailsClosedInsteadOfPlatformSpend() {
    LlmRouter subject = build();
    // Pure-BYOK run, but the resolved-key context is missing (cache eviction / cross-JVM) → the
    // router would otherwise dispatch on the platform key. It must refuse.
    when(keySourceModeResolver.isOwnKeysRun(anyString())).thenReturn(true);
    when(keyStore.get(anyString())).thenReturn(null);

    assertThatThrownBy(() -> subject.call(req("openai", "gpt-x")))
        .isInstanceOf(ByokContextUnavailableException.class)
        .hasMessageContaining("BYOK_CONTEXT_UNAVAILABLE");
    verify(openai, never()).call(any());
    verify(gemini, never()).call(any());
  }

  @Test
  void ownKeysRunWithACustomerKeyDispatchesNormally() {
    LlmRouter subject = build();
    when(keySourceModeResolver.isOwnKeysRun(anyString())).thenReturn(true);
    // Customer keyed OpenAI (USER source) → source != PLATFORM → the guard does not fire.
    when(keyStore.get(anyString()))
        .thenReturn(
            new RunKeyContext(
                null,
                "customer-openai-key",
                null,
                null,
                RunKeyContext.Source.PLATFORM,
                RunKeyContext.Source.USER,
                RunKeyContext.Source.PLATFORM,
                RunKeyContext.Source.PLATFORM,
                "u1",
                "o1"));
    when(openai.call(any())).thenReturn(ok());

    assertThat(subject.call(req("openai", "gpt-x"))).isNotNull();
    verify(openai, times(1)).call(any());
  }

  @Test
  void hybridRunWithNoKeyKeepsIntentionalPlatformFallback() {
    LlmRouter subject = build();
    // Default resolver stub = not OWN_KEYS (HYBRID/SYSTEM). Platform fallback stays allowed.
    when(keyStore.get(anyString())).thenReturn(null);
    when(openai.call(any())).thenReturn(ok());

    assertThat(subject.call(req("openai", "gpt-x"))).isNotNull();
    verify(openai, times(1)).call(any());
  }

  @Test
  void textSeamPreservesAnthropicSystemOnlyCacheIntent() {
    build();
    AnthropicChatModel anthropic = mock(AnthropicChatModel.class);
    when(anthropicModelProvider.getIfAvailable()).thenReturn(anthropic);
    when(anthropic.call(any(Prompt.class))).thenReturn(ok());

    router.callText(
        "anthropic",
        "claude-sonnet-4-6",
        List.of(new UserMessage("stable system context")),
        "run-cache",
        "ConversationAgent",
        "suggestion-plan",
        128,
        LlmRequest.PromptCacheStrategy.SYSTEM_ONLY);

    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    verify(anthropic).call(captor.capture());
    AnthropicChatOptions options = (AnthropicChatOptions) captor.getValue().getOptions();
    assertThat(options.getCacheOptions().getStrategy())
        .isEqualTo(org.springframework.ai.anthropic.api.AnthropicCacheStrategy.SYSTEM_ONLY);
    assertThat(options.getTemperature()).isEqualTo(0.0);
  }

  @Test
  void textSeamCanDisableAnthropicPromptCaching() {
    build();
    AnthropicChatModel anthropic = mock(AnthropicChatModel.class);
    when(anthropicModelProvider.getIfAvailable()).thenReturn(anthropic);
    when(anthropic.call(any(Prompt.class))).thenReturn(ok());

    router.callText(
        "anthropic",
        "claude-sonnet-4-6",
        List.of(new UserMessage("no cache")),
        "run-cache-none",
        "ConversationAgent",
        "test",
        128,
        LlmRequest.PromptCacheStrategy.NONE);

    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    verify(anthropic).call(captor.capture());
    AnthropicChatOptions options = (AnthropicChatOptions) captor.getValue().getOptions();
    assertThat(options.getCacheOptions().getStrategy())
        .isEqualTo(org.springframework.ai.anthropic.api.AnthropicCacheStrategy.NONE);
  }

  @Test
  void anthropicForcedNullFallsBackWithOrderedPhysicalAttemptsAndOneCostProjection() {
    build();
    AnthropicChatModel anthropic = mock(AnthropicChatModel.class);
    when(anthropicModelProvider.getIfAvailable()).thenReturn(anthropic);
    when(anthropicForcedToolClient.canAttempt(any(), any(), any())).thenReturn(true);
    when(anthropicForcedToolClient.forcedToolCallOutcome(anyString(), any(), any(), any(), any()))
        .thenReturn(
            new AnthropicForcedToolClient.ForcedToolCallOutcome(
                null, false, new RuntimeException("forced transport failed"), true));
    when(anthropic.call(any(Prompt.class))).thenReturn(ok());
    LlmRequest request =
        req("anthropic", "claude-forced-audit-test").toBuilder()
            .toolChoice("required")
            .agentRole("WorkerAgent")
            .stepId("worker.salvage")
            .build();

    assertThat(router.call(request)).isNotNull();

    ArgumentCaptor<Integer> ordinal = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<String> kind = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> parent = ArgumentCaptor.forClass(String.class);
    verify(stepUsageRecorder, times(2))
        .prepareAttempt(
            eq(request),
            eq("anthropic"),
            eq("claude-forced-audit-test"),
            eq("PLATFORM"),
            ordinal.capture(),
            kind.capture(),
            parent.capture());
    assertThat(ordinal.getAllValues()).containsExactly(1, 2);
    assertThat(kind.getAllValues()).containsExactly("PRIMARY", "RETRY");
    assertThat(parent.getAllValues())
        .containsExactly(null, request.usageLogicalCallId() + ":anthropic:1");
    verify(stepUsageRecorder)
        .recordFailure(
            argThat(handle -> handle.attemptOrdinal() == 1),
            eq(request),
            anyLong(),
            any(RuntimeException.class));
    verify(stepUsageRecorder)
        .recordSuccess(
            argThat(handle -> handle.attemptOrdinal() == 2),
            eq(request),
            anyLong(),
            any(RunCostTracker.CallUsage.class),
            any(ChatResponse.class));
    verify(costTracker, times(1))
        .record(eq("run-1"), eq("T1"), eq("anthropic"), eq("claude-forced-audit-test"), any());
    verify(managedAiUsageService, times(1))
        .recordSuccessfulCall(
            any(),
            eq("run-1"),
            eq("T1"),
            eq("WorkerAgent"),
            eq("worker.salvage"),
            eq("anthropic"),
            eq("claude-forced-audit-test"),
            any(),
            any(),
            eq(RunKeyContext.Source.PLATFORM));
    verify(stepUsageRecorder, never())
        .recordSuccess(
            any(LlmRequest.class),
            eq("anthropic"),
            anyString(),
            anyString(),
            anyInt(),
            anyLong(),
            any(),
            any());
  }

  @Test
  void anthropicForcedHttpSuccessWithoutToolAccountsSpendBeforeFallback() {
    build();
    AnthropicChatModel anthropic = mock(AnthropicChatModel.class);
    when(anthropicModelProvider.getIfAvailable()).thenReturn(anthropic);
    when(anthropicForcedToolClient.canAttempt(any(), any(), any())).thenReturn(true);
    when(anthropicForcedToolClient.forcedToolCallOutcome(anyString(), any(), any(), any(), any()))
        .thenReturn(new AnthropicForcedToolClient.ForcedToolCallOutcome(ok(), false, null, true));
    when(anthropic.call(any(Prompt.class))).thenReturn(ok());
    LlmRequest request =
        req("anthropic", "claude-forced-audit-test").toBuilder()
            .toolChoice("required")
            .agentRole("WorkerAgent")
            .stepId("worker.salvage")
            .build();

    assertThat(router.call(request)).isNotNull();

    verify(costTracker, times(2))
        .record(eq("run-1"), eq("T1"), eq("anthropic"), eq("claude-forced-audit-test"), any());
    verify(stepUsageRecorder, times(2))
        .recordSuccess(
            any(RunStepUsageRecorder.AttemptHandle.class),
            eq(request),
            anyLong(),
            any(RunCostTracker.CallUsage.class),
            any(ChatResponse.class));
    verify(stepUsageRecorder, never())
        .recordFailure(
            any(RunStepUsageRecorder.AttemptHandle.class), eq(request), anyLong(), any());
    verify(managedAiUsageService, times(2))
        .recordSuccessfulCall(
            any(),
            eq("run-1"),
            eq("T1"),
            eq("WorkerAgent"),
            eq("worker.salvage"),
            eq("anthropic"),
            eq("claude-forced-audit-test"),
            any(),
            any(),
            eq(RunKeyContext.Source.PLATFORM));
  }

  @Test
  void anthropicTemperatureDeprecationRetryHasOneChainAndOneFinalCostProjection() {
    build();
    String model = "claude-temperature-audit-test";
    AnthropicChatModel anthropic = mock(AnthropicChatModel.class);
    when(anthropicModelProvider.getIfAvailable()).thenReturn(anthropic);
    when(anthropic.call(any(Prompt.class)))
        .thenThrow(
            new RuntimeException(
                "400 - {\"error\":{\"message\":\"`temperature` is deprecated for this model.\"}}"))
        .thenReturn(ok());
    LlmRequest request =
        req("anthropic", model).toBuilder()
            .agentRole("ReviewerAgent")
            .stepId("review.general")
            .build();

    assertThat(router.call(request)).isNotNull();

    ArgumentCaptor<Integer> ordinal = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<String> kind = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> parent = ArgumentCaptor.forClass(String.class);
    verify(stepUsageRecorder, times(2))
        .prepareAttempt(
            eq(request),
            eq("anthropic"),
            eq(model),
            eq("PLATFORM"),
            ordinal.capture(),
            kind.capture(),
            parent.capture());
    assertThat(ordinal.getAllValues()).containsExactly(1, 2);
    assertThat(kind.getAllValues()).containsExactly("PRIMARY", "RETRY");
    assertThat(parent.getAllValues())
        .containsExactly(null, request.usageLogicalCallId() + ":anthropic:1");
    verify(stepUsageRecorder)
        .recordFailure(
            argThat(handle -> handle.attemptOrdinal() == 1),
            eq(request),
            anyLong(),
            any(RuntimeException.class));
    verify(stepUsageRecorder)
        .recordSuccess(
            argThat(handle -> handle.attemptOrdinal() == 2),
            eq(request),
            anyLong(),
            any(RunCostTracker.CallUsage.class),
            any(ChatResponse.class));
    verify(costTracker, times(1)).record(eq("run-1"), eq("T1"), eq("anthropic"), eq(model), any());
    verify(managedAiUsageService, times(1))
        .recordSuccessfulCall(
            any(),
            eq("run-1"),
            eq("T1"),
            eq("ReviewerAgent"),
            eq("review.general"),
            eq("anthropic"),
            eq(model),
            any(),
            any(),
            eq(RunKeyContext.Source.PLATFORM));

    ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
    verify(anthropic, times(2)).call(prompts.capture());
    AnthropicChatOptions first = (AnthropicChatOptions) prompts.getAllValues().get(0).getOptions();
    AnthropicChatOptions second = (AnthropicChatOptions) prompts.getAllValues().get(1).getOptions();
    assertThat(first.getTemperature()).isEqualTo(0.0);
    assertThat(second.getTemperature()).isNull();
  }

  @Test
  void textSeamUsesYamlOutputCapWhenCallerOmitsMaxTokens() {
    build();
    when(registry.maxOutputForModel("claude-sonnet-4-6")).thenReturn(4096);
    AnthropicChatModel anthropic = mock(AnthropicChatModel.class);
    when(anthropicModelProvider.getIfAvailable()).thenReturn(anthropic);
    when(anthropic.call(any(Prompt.class))).thenReturn(ok());

    router.callText(
        "anthropic",
        "claude-sonnet-4-6",
        List.of(new UserMessage("doc generation")),
        "run-doc",
        "ProjectDocGeneratorService",
        "generate");

    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    verify(anthropic).call(captor.capture());
    AnthropicChatOptions options = (AnthropicChatOptions) captor.getValue().getOptions();
    assertThat(options.getMaxTokens()).isEqualTo(4096);
  }

  @Test
  void managedAiAuthorizationCanStepDownModelBeforeDispatchAndDebitAdjustedModel() {
    build();
    when(managedAiUsageService.authorizeCall(
            anyString(), anyString(), anyString(), any(), any(), any()))
        .thenReturn(
            new ManagedAiUsageService.CallAuthorization(
                "openai", "gpt-mini", ManagedAiUsageService.CHEAP_ONLY, true, "step down"));
    when(costTracker.record(eq("run-1"), eq("T1"), eq("openai"), eq("gpt-mini"), any()))
        .thenReturn(new RunCostTracker.CallUsage("openai", "gpt-mini", 1, 2, 0, 0, 0.01, 1, 0));
    when(openai.call(any())).thenReturn(ok());

    router.call(attributedReq("openai", "gpt-x"));

    ArgumentCaptor<OpenAiNativeToolClient.OpenAiToolCallRequest> captor =
        ArgumentCaptor.forClass(OpenAiNativeToolClient.OpenAiToolCallRequest.class);
    verify(openai).call(captor.capture());
    assertThat(captor.getValue().model()).isEqualTo("gpt-mini");
    verify(costTracker).record(eq("run-1"), eq("T1"), eq("openai"), eq("gpt-mini"), any());
    verify(stepUsageRecorder)
        .recordSuccess(
            argThat(
                request ->
                    "WorkerAgent".equals(request.agentRole())
                        && "worker.iteration-1".equals(request.stepId())),
            eq("openai"),
            eq("gpt-mini"),
            eq("PLATFORM"),
            eq(1),
            anyLong(),
            any(),
            any());
    verify(managedAiUsageService)
        .recordSuccessfulCall(
            any(),
            eq("run-1"),
            eq("T1"),
            eq("WorkerAgent"),
            eq("worker.iteration-1"),
            eq("openai"),
            eq("gpt-mini"),
            any(),
            any(),
            eq(RunKeyContext.Source.PLATFORM));
  }

  @Test
  void managedAiProjectionFailureAfterProviderSuccessDoesNotRetryOrRecordProviderFailure() {
    build();
    when(openai.call(any())).thenReturn(ok());
    org.mockito.Mockito.doThrow(new RuntimeException("503 managed projection unavailable"))
        .when(managedAiUsageService)
        .recordSuccessfulCall(
            any(),
            anyString(),
            anyString(),
            any(),
            any(),
            anyString(),
            anyString(),
            any(),
            any(),
            any());

    assertThat(router.call(attributedReq("openai", "gpt-x"))).isNotNull();

    verify(openai, times(1)).call(any());
    verify(stepUsageRecorder, times(1))
        .recordSuccess(
            any(), eq("openai"), eq("gpt-x"), eq("PLATFORM"), eq(1), anyLong(), any(), any());
    verify(stepUsageRecorder, never())
        .recordFailure(any(), anyString(), anyString(), anyString(), anyInt(), anyLong(), any());
    verify(metrics).recordLlmPostSuccessProjection("managed_ai", "failed");
  }

  @Test
  void usageIntentPersistenceFailureBlocksProviderTransport() {
    build();
    org.mockito.Mockito.doThrow(
            new RunStepUsageRecorder.LlmUsagePersistenceException(
                "Provider call blocked", new IllegalStateException("database unavailable")))
        .when(stepUsageRecorder)
        .prepareAttempt(any(), eq("openai"), eq("gpt-x"), eq("PLATFORM"), eq(1));

    assertThatThrownBy(() -> router.call(attributedReq("openai", "gpt-x")))
        .isInstanceOf(RunStepUsageRecorder.LlmUsagePersistenceException.class)
        .hasMessageContaining("Provider call blocked");

    verify(openai, never()).call(any());
    verify(stepUsageRecorder, never())
        .recordFailure(any(), anyString(), anyString(), anyString(), anyInt(), anyLong(), any());
  }

  @Test
  void costProjectionFailureAfterProviderSuccessRecordsUnknownCostSuccessWithoutRetry() {
    build();
    when(openai.call(any())).thenReturn(ok());
    when(costTracker.record(eq("run-1"), eq("T1"), eq("openai"), eq("gpt-x"), any()))
        .thenThrow(new RuntimeException("503 cost projection unavailable"));

    assertThat(router.call(attributedReq("openai", "gpt-x"))).isNotNull();

    verify(openai, times(1)).call(any());
    verify(stepUsageRecorder, times(1))
        .recordSuccess(
            any(),
            eq("openai"),
            eq("gpt-x"),
            eq("PLATFORM"),
            eq(1),
            anyLong(),
            argThat(usage -> usage.costUsd() == -1.0 && usage.callIndex() == 0),
            any());
    verify(stepUsageRecorder, never())
        .recordFailure(any(), anyString(), anyString(), anyString(), anyInt(), anyLong(), any());
    verify(managedAiUsageService, never())
        .recordSuccessfulCall(
            any(),
            anyString(),
            anyString(),
            any(),
            any(),
            anyString(),
            anyString(),
            any(),
            any(),
            any());
    verify(metrics).recordLlmPostSuccessProjection("run_cost", "failed");
    verify(metrics).recordLlmPostSuccessProjection("managed_ai", "skipped_unknown_cost");
  }

  @Test
  void tokenEventFailureAfterProviderSuccessDoesNotRetryOrRecordProviderFailure() {
    build();
    when(openai.call(any())).thenReturn(ok());
    org.mockito.Mockito.doThrow(new RuntimeException("503 SSE projection unavailable"))
        .when(broadcaster)
        .emit(anyString(), any());

    assertThat(router.call(attributedReq("openai", "gpt-x"))).isNotNull();

    verify(openai, times(1)).call(any());
    verify(stepUsageRecorder, times(1))
        .recordSuccess(
            any(), eq("openai"), eq("gpt-x"), eq("PLATFORM"), eq(1), anyLong(), any(), any());
    verify(stepUsageRecorder, never())
        .recordFailure(any(), anyString(), anyString(), anyString(), anyInt(), anyLong(), any());
    verify(metrics).recordLlmPostSuccessProjection("token_event", "failed");
  }

  @Test
  void transient_error_retries_same_provider_then_succeeds() {
    build();
    when(openai.call(any()))
        .thenThrow(new RuntimeException("503 service unavailable"))
        .thenReturn(ok());
    assertThat(router.call(attributedReq("openai", "gpt-x"))).isNotNull();
    verify(openai, times(2)).call(any());
    verify(stepUsageRecorder)
        .recordFailure(
            argThat(request -> "WorkerAgent".equals(request.agentRole())),
            eq("openai"),
            eq("gpt-x"),
            eq("PLATFORM"),
            eq(1),
            anyLong(),
            any());
    verify(stepUsageRecorder)
        .recordSuccess(
            argThat(request -> "worker.iteration-1".equals(request.stepId())),
            eq("openai"),
            eq("gpt-x"),
            eq("PLATFORM"),
            eq(2),
            anyLong(),
            any(),
            any());
  }

  @Test
  void permanent_error_is_not_retried() {
    build();
    when(openai.call(any())).thenThrow(new RuntimeException("400 bad request"));
    assertThatThrownBy(() -> router.call(req("openai", "gpt-x")))
        .isInstanceOf(RuntimeException.class);
    verify(openai, times(1)).call(any()); // no retry on a permanent 4xx
  }

  @Test
  void context_length_error_is_not_retried_or_failed_over() {
    // A context-window-exceeded error is permanent (the next call would be identical) — must fail
    // fast, not burn 4 retries. The real fix for the cause is history compaction (WS3).
    build();
    when(flags.isEnabled(LlmRouter.FAILOVER_FLAG)).thenReturn(true);
    when(openai.call(any())).thenThrow(new RuntimeException("400 maximum context length exceeded"));
    assertThatThrownBy(() -> router.call(req("openai", "gpt-x")))
        .isInstanceOf(RuntimeException.class);
    verify(openai, times(1)).call(any());
    verify(gemini, never()).call(any()); // not transient -> no failover either
  }

  @Test
  void failover_disabled_does_not_cross_provider() {
    build();
    when(flags.isEnabled(LlmRouter.FAILOVER_FLAG)).thenReturn(false);
    when(openai.call(any())).thenThrow(new RuntimeException("503 service unavailable"));
    assertThatThrownBy(() -> router.call(req("openai", "gpt-x")))
        .isInstanceOf(RuntimeException.class);
    verify(gemini, never()).call(any());
  }

  /**
   * C1 Step 1 — a controlled benchmark/SWE-bench worker carries a durable context whose policy sets
   * {@code allowProviderFailover=false}. The dispatch guard therefore rejects every alternate route
   * and the primary error must propagate instead of a different physical provider silently serving
   * the task under the requested provider's name.
   */
  @Test
  void controlledWorkerDurablePolicyBlocksCrossProviderFailover() {
    build();
    when(flags.isEnabled(LlmRouter.FAILOVER_FLAG)).thenReturn(true);
    when(registry.fallbackChainFor(anyString()))
        .thenReturn(List.of(new String[] {"openai", "gpt-x"}, new String[] {"gemini", "gem-x"}));
    when(openai.call(any())).thenThrow(new RuntimeException("503 service unavailable"));
    LlmExecutionContext benchmarkContext = mock(LlmExecutionContext.class);
    when(benchmarkContext.getRequestedProvider()).thenReturn("openai");
    lenient().when(benchmarkContext.getUserId()).thenReturn("u1");
    lenient().when(benchmarkContext.getOrgId()).thenReturn("org-1");
    // The guard authorizes the primary route but rejects any provider the policy did not allow.
    when(executionDispatchGuard.authorize(any()))
        .thenAnswer(
            invocation -> {
              LlmRequest attempted = invocation.getArgument(0);
              if (!"openai".equals(attempted.provider())) {
                throw new LlmDispatchPolicyException(
                    LlmDispatchPolicyException.Reason.PROVIDER_NOT_ALLOWED,
                    "Provider failover is disabled by the durable execution policy");
              }
              return new LlmExecutionDispatchGuard.ResolvedDispatch(
                  "primary-key", RunKeyContext.Source.PLATFORM, benchmarkContext);
            });

    LlmRequest req =
        req("openai", "gpt-x").toBuilder()
            .llmExecutionContextId("llmctx-benchmark")
            .llmExecutionScope(LlmExecutionScope.customer("org-1"))
            .build();

    assertThatThrownBy(() -> router.call(req))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("503 service unavailable");
    verify(gemini, never()).call(any());
    verify(gemini, never()).callAudited(any(), any());
  }

  @Test
  void failover_enabled_crosses_to_next_provider_in_chain() {
    build();
    when(flags.isEnabled(LlmRouter.FAILOVER_FLAG)).thenReturn(true);
    when(registry.fallbackChainFor(anyString()))
        .thenReturn(List.of(new String[] {"openai", "gpt-x"}, new String[] {"gemini", "gem-x"}));
    when(openai.call(any())).thenThrow(new RuntimeException("503 service unavailable"));
    RunCostTracker.CallUsage geminiUsage =
        new RunCostTracker.CallUsage("gemini", "gem-x", 1, 2, 0, 0, 0.01, 1, 0);
    when(gemini.callAudited(any(), any()))
        .thenReturn(
            new GeminiNativeToolClient.AuditedCallResult(
                ok(), geminiUsage, "gemini-usage-1", true));

    LlmRequest req =
        req("openai", "gpt-x").toBuilder()
            .agentRole("OutlinePlannerAgent")
            .stepId("planner.outline")
            .build();
    assertThat(router.call(req)).isNotNull();
    verify(gemini, times(1)).callAudited(any(), any());
    verify(stepUsageRecorder, times(4))
        .recordFailure(
            argThat(request -> "OutlinePlannerAgent".equals(request.agentRole())),
            eq("openai"),
            eq("gpt-x"),
            eq("PLATFORM"),
            any(Integer.class),
            anyLong(),
            any());
    verify(stepUsageRecorder, never())
        .recordSuccess(
            any(LlmRequest.class),
            eq("gemini"),
            eq("gem-x"),
            eq("PLATFORM"),
            anyInt(),
            anyLong(),
            any(),
            any());
  }

  @Test
  void managedAiAuthorizationCanStepDownFailoverTargetBeforeDebit() {
    build();
    when(flags.isEnabled(LlmRouter.FAILOVER_FLAG)).thenReturn(true);
    when(registry.fallbackChainFor(anyString()))
        .thenReturn(List.of(new String[] {"openai", "gpt-x"}, new String[] {"gemini", "gem-pro"}));
    when(openai.call(any())).thenThrow(new RuntimeException("503 service unavailable"));
    RunCostTracker.CallUsage geminiUsage =
        new RunCostTracker.CallUsage("gemini", "gem-flash", 1, 2, 0, 0, 0.01, 1, 0);
    when(gemini.callAudited(any(), any()))
        .thenReturn(
            new GeminiNativeToolClient.AuditedCallResult(
                ok(), geminiUsage, "gemini-usage-2", true));
    when(managedAiUsageService.authorizeCall(
            anyString(), eq("gemini"), eq("gem-pro"), any(), any(), any()))
        .thenReturn(
            new ManagedAiUsageService.CallAuthorization(
                "gemini", "gem-flash", ManagedAiUsageService.CHEAP_ONLY, true, "step down"));
    when(costTracker.record(eq("run-1"), eq("T1"), eq("gemini"), eq("gem-flash"), any()))
        .thenReturn(new RunCostTracker.CallUsage("gemini", "gem-flash", 1, 2, 0, 0, 0.01, 1, 0));

    LlmRequest request =
        req("openai", "gpt-x").toBuilder()
            .agentRole("OutlinePlannerAgent")
            .stepId("planner.outline")
            .build();

    assertThat(router.call(request)).isNotNull();

    ArgumentCaptor<GeminiNativeToolClient.GeminiToolCallRequest> captor =
        ArgumentCaptor.forClass(GeminiNativeToolClient.GeminiToolCallRequest.class);
    verify(gemini).callAudited(captor.capture(), any());
    assertThat(captor.getValue().model()).isEqualTo("gem-flash");
    verify(stepUsageRecorder, never())
        .recordSuccess(
            any(LlmRequest.class),
            eq("gemini"),
            eq("gem-flash"),
            eq("PLATFORM"),
            anyInt(),
            anyLong(),
            any(),
            any());
    verify(managedAiUsageService)
        .recordSuccessfulCall(
            any(),
            eq("run-1"),
            eq("T1"),
            eq("OutlinePlannerAgent"),
            eq("planner.outline"),
            eq("gemini"),
            eq("gem-flash"),
            any(),
            any(),
            eq(RunKeyContext.Source.PLATFORM));
  }

  @Test
  void deepSeekFailureFallsBackToOpenRouterBeforeFireworks() {
    build();
    configureWireProviderUrls();
    when(keyStore.get("run-1")).thenReturn(deepSeekFailoverKeys());
    when(flags.isEnabled(LlmRouter.FAILOVER_FLAG)).thenReturn(true);
    when(registry.fallbackChainFor(eq("deepseek-v4-pro-native"), eq("deepseek")))
        .thenReturn(
            List.of(
                new String[] {"openrouter", "deepseek-v4-pro"},
                new String[] {"fireworks", "deepseek-v4-pro-fw"}));
    AtomicInteger nativeFailures = new AtomicInteger();
    when(openai.call(any()))
        .thenAnswer(
            invocation -> {
              OpenAiNativeToolClient.OpenAiToolCallRequest request = invocation.getArgument(0);
              if (request.baseUrl() != null && request.baseUrl().contains("deepseek")) {
                nativeFailures.incrementAndGet();
                throw new RuntimeException("503 native unavailable");
              }
              return ok();
            });

    assertThat(router.call(req("deepseek", "deepseek-v4-pro-native"))).isNotNull();
    assertThat(nativeFailures).hasValue(4);
    verify(openai, times(4))
        .call(
            argThat(
                request ->
                    request.readTimeoutSeconds() != null
                        && request.readTimeoutSeconds() == 300
                        && request.baseUrl().contains("deepseek")));
    verify(openai, times(1))
        .call(
            argThat(
                request ->
                    request.model().equals("deepseek/deepseek-v4-pro")
                        && request.baseUrl().contains("openrouter")));
    verify(openai, never())
        .call(
            argThat(
                request ->
                    request.model().equals("accounts/fireworks/models/deepseek-v4-pro")
                        && request.baseUrl().contains("fireworks")));
  }

  @Test
  void deepSeekFailureReachesFireworksOnlyAfterOpenRouterFails() {
    build();
    configureWireProviderUrls();
    when(keyStore.get("run-1")).thenReturn(deepSeekFailoverKeys());
    when(flags.isEnabled(LlmRouter.FAILOVER_FLAG)).thenReturn(true);
    when(registry.fallbackChainFor(eq("deepseek-v4-pro-native"), eq("deepseek")))
        .thenReturn(
            List.of(
                new String[] {"openrouter", "deepseek-v4-pro"},
                new String[] {"fireworks", "deepseek-v4-pro-fw"}));
    when(openai.call(any()))
        .thenAnswer(
            invocation -> {
              OpenAiNativeToolClient.OpenAiToolCallRequest request = invocation.getArgument(0);
              if (request.baseUrl() != null
                  && (request.baseUrl().contains("deepseek")
                      || request.baseUrl().contains("openrouter"))) {
                throw new RuntimeException("503 provider unavailable");
              }
              return ok();
            });

    assertThat(router.call(req("deepseek", "deepseek-v4-pro-native"))).isNotNull();
    verify(openai, times(1))
        .call(
            argThat(
                request ->
                    request.model().equals("accounts/fireworks/models/deepseek-v4-pro")
                        && request.baseUrl().contains("fireworks")));
  }

  private void configureWireProviderUrls() {
    ReflectionTestUtils.setField(router, "deepSeekBaseUrl", "https://api.deepseek.com");
    ReflectionTestUtils.setField(router, "openRouterBaseUrl", "https://openrouter.ai/api/v1");
    ReflectionTestUtils.setField(
        router, "fireworksBaseUrl", "https://api.fireworks.ai/inference/v1");
  }

  @Test
  void byok_run_does_not_fail_over_to_an_unkeyed_provider() {
    build();
    when(flags.isEnabled(LlmRouter.FAILOVER_FLAG)).thenReturn(true);
    // Customer keyed OpenAI only (USER source); no Gemini key.
    RunKeyContext keys =
        new RunKeyContext(
            null,
            "sk-user-openai",
            null,
            null,
            null,
            RunKeyContext.Source.USER,
            null,
            null,
            "u1",
            "o1");
    when(keyStore.get(anyString())).thenReturn(keys);
    when(registry.fallbackChainFor(anyString()))
        .thenReturn(List.of(new String[] {"openai", "gpt-x"}, new String[] {"gemini", "gem-x"}));
    when(openai.call(any())).thenThrow(new RuntimeException("503 service unavailable"));

    assertThatThrownBy(() -> router.call(req("openai", "gpt-x")))
        .isInstanceOf(RuntimeException.class);
    verify(gemini, never())
        .call(any()); // BYOK run never crosses to an unkeyed provider's platform key
  }

  @Test
  @SuppressWarnings("unchecked")
  void openRouterRequestsLatencySortedProviderRouting() {
    build();
    when(openai.call(any())).thenReturn(ok());
    RunKeyContext keys =
        new RunKeyContext(
            null, null, null, "sk-or", null, null, null, RunKeyContext.Source.ORG, "u1", "o1");
    when(keyStore.get(anyString())).thenReturn(keys);

    assertThat(router.call(req("openrouter", "gpt-5.4"))).isNotNull();

    ArgumentCaptor<OpenAiNativeToolClient.OpenAiToolCallRequest> captor =
        ArgumentCaptor.forClass(OpenAiNativeToolClient.OpenAiToolCallRequest.class);
    verify(openai).call(captor.capture());
    Map<String, Object> extraBody = captor.getValue().extraBody();
    assertThat(extraBody).containsKey("provider");
    assertThat((Map<String, Object>) extraBody.get("provider")).containsEntry("sort", "latency");
  }

  // ---------------------------------------------------------------------------------------------
  // C1 Step 6 (gap G7) — containment regression pins.
  //
  // These assert the C1 contract precisely: a route change is either recorded with a
  // distinguishable kind/reason, or REJECTED. They deliberately do NOT assert "the provider always
  // equals the requested provider" — a managed-AI tier lock changing provider is legitimate policy
  // substitution, and the pin below proves it is still permitted.
  // ---------------------------------------------------------------------------------------------

  /**
   * The legacy branch: when {@code llmExecutionContextId} is null, {@code maybeFailover} skips the
   * durable guard entirely and falls back to a {@code keys.keyFor(alt) == null} heuristic. That
   * branch is defended only by the {@code executionContextEnforcementEnabled} config default, so it
   * is pinned here rather than assumed unreachable. An OWN_KEYS run whose alternate provider has no
   * customer key must not cross onto a platform key.
   */
  @Test
  void ownKeysRunWithNullDurableContextCannotFailOverOntoAPlatformKey() {
    LlmRouter subject = build();
    when(flags.isEnabled(LlmRouter.FAILOVER_FLAG)).thenReturn(true);
    when(keySourceModeResolver.isOwnKeysRun(anyString())).thenReturn(true);
    // Customer keyed DeepSeek only. Nothing is keyed for the alternates in the chain.
    when(keyStore.get(anyString()))
        .thenReturn(
            new RunKeyContext(
                null,
                null,
                null,
                null,
                "customer-deepseek-key",
                null,
                RunKeyContext.Source.PLATFORM,
                RunKeyContext.Source.PLATFORM,
                RunKeyContext.Source.PLATFORM,
                RunKeyContext.Source.PLATFORM,
                RunKeyContext.Source.USER,
                RunKeyContext.Source.PLATFORM,
                "u1",
                "o1"));
    when(registry.fallbackChainFor(anyString(), anyString()))
        .thenReturn(
            List.of(
                new String[] {"openrouter", "glm-5.2"}, new String[] {"fireworks", "glm-5.2-fw"}));
    when(registry.fallbackChainFor(anyString()))
        .thenReturn(List.<String[]>of(new String[] {"openrouter", "glm-5.2"}));
    when(openai.call(any())).thenThrow(new RuntimeException("503 service unavailable"));

    LlmRequest byokRequest = req("deepseek", "deepseek-v4-pro-native");
    assertThat(byokRequest.llmExecutionContextId()).isNull();

    assertThatThrownBy(() -> subject.call(byokRequest))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("503");
    // Gemini is never reached; every dispatch stayed on the customer-keyed DeepSeek route.
    verify(gemini, never()).callAudited(any(), any());
    verify(gemini, never()).call(any());
    ArgumentCaptor<OpenAiNativeToolClient.OpenAiToolCallRequest> dispatched =
        ArgumentCaptor.forClass(OpenAiNativeToolClient.OpenAiToolCallRequest.class);
    verify(openai, org.mockito.Mockito.atLeastOnce()).call(dispatched.capture());
    // The containment claim: EVERY physical dispatch used the customer's own DeepSeek credential.
    // No attempt crossed onto a platform key or onto an unkeyed alternate provider.
    assertThat(dispatched.getAllValues())
        .isNotEmpty()
        .allSatisfy(
            call -> {
              assertThat(call.apiKey()).isEqualTo("customer-deepseek-key");
              assertThat(call.model()).startsWith("deepseek");
            });
  }

  /**
   * An EXACT_LOCK run cannot be re-pointed by managed-AI policy. The substitution is rejected, not
   * silently applied — the customer pinned this route.
   */
  @Test
  void exactLockRejectsManagedAiModelSubstitutionInsteadOfApplyingIt() {
    LlmRouter subject = build();
    when(registry.canonicalize(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
    when(routePolicyService.forRun(anyString()))
        .thenReturn(
            new RunRoutePolicyService.RoutePolicy(
                "DEEPSEEK", "deepseek-v4-pro-native", "EXACT_LOCK", true));
    when(managedAiUsageService.authorizeCall(
            anyString(), anyString(), anyString(), any(), any(), any()))
        .thenReturn(
            new ManagedAiUsageService.CallAuthorization(
                "deepseek",
                "deepseek-v4-flash-native",
                ManagedAiUsageService.FULL,
                true,
                "Managed AI tier lock: FREE routes to deepseek-v4-flash-native"));

    assertThatThrownBy(() -> subject.call(req("deepseek", "deepseek-v4-pro-native")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Exact run route cannot be changed by managed-AI policy");
    verify(openai, never()).call(any());
  }

  /**
   * The counterpart pin. A managed-AI tier lock IS allowed to change provider AND model on an
   * unpinned run — a FREE-tier lock routing to native DeepSeek Flash is policy substitution, not
   * failover. The change must be applied and surfaced with its reason, not rejected.
   */
  @Test
  void managedAiTierLockMayStillChangeProviderOnAnUnpinnedRunAndSurfacesTheReason() {
    LlmRouter subject = build();
    when(managedAiUsageService.authorizeCall(
            anyString(), anyString(), anyString(), any(), any(), any()))
        .thenReturn(
            new ManagedAiUsageService.CallAuthorization(
                "deepseek",
                "deepseek-v4-flash-native",
                ManagedAiUsageService.FULL,
                true,
                "Managed AI tier lock: FREE routes to deepseek-v4-flash-native"));
    when(keyStore.get(anyString()))
        .thenReturn(
            new RunKeyContext(
                null,
                null,
                null,
                null,
                "platform-deepseek-key",
                null,
                RunKeyContext.Source.PLATFORM,
                RunKeyContext.Source.PLATFORM,
                RunKeyContext.Source.PLATFORM,
                RunKeyContext.Source.PLATFORM,
                RunKeyContext.Source.PLATFORM,
                RunKeyContext.Source.PLATFORM,
                "u1",
                "o1"));
    when(openai.call(any())).thenReturn(ok());

    assertThat(subject.call(req("anthropic", "claude-sonnet-5"))).isNotNull();

    ArgumentCaptor<OpenAiNativeToolClient.OpenAiToolCallRequest> dispatched =
        ArgumentCaptor.forClass(OpenAiNativeToolClient.OpenAiToolCallRequest.class);
    verify(openai, times(1)).call(dispatched.capture());
    // Provider AND model changed — and that is correct here. The request left as anthropic/
    // claude-sonnet-5 and physically dispatched on the DeepSeek wire client.
    assertThat(dispatched.getValue().model()).startsWith("deepseek-v4-flash");
    assertThat(dispatched.getValue().apiKey()).isEqualTo("platform-deepseek-key");
    // The change is recorded with its reason rather than being silent.
    verify(broadcaster, org.mockito.Mockito.atLeastOnce())
        .emit(
            anyString(),
            argThat(
                event ->
                    event != null
                        && String.valueOf(event.payload()).contains("Managed AI tier lock")));
  }

  /** Managed-AI exhaustion with no step-down target must fail closed, never dispatch. */
  @Test
  void managedAiExhaustedWithNoStepDownTargetThrowsBeforeAnyProviderSpend() {
    LlmRouter subject = build();
    when(managedAiUsageService.authorizeCall(
            anyString(), anyString(), anyString(), any(), any(), any()))
        .thenThrow(
            new IllegalStateException("MANAGED_AI_EXHAUSTED: no step-down target available"));

    assertThatThrownBy(() -> subject.call(req("openai", "gpt-x")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MANAGED_AI_EXHAUSTED");
    verify(openai, never()).call(any());
    verify(gemini, never()).call(any());
  }
}
