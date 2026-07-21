package com.navneet.executespec.executioncontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agent.ResolvedKeys;
import com.navneet.executespec.agent.RunKeyContext;
import com.navneet.executespec.cost.RunStepUsageRepository;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.test.util.ReflectionTestUtils;

class LlmExecutionDispatchGuardTest {

  private final LlmExecutionContextService contexts = mock(LlmExecutionContextService.class);
  private final ApiKeyResolver apiKeys = mock(ApiKeyResolver.class);
  private final ModelRegistry models = mock(ModelRegistry.class);
  private final RunStepUsageRepository usage = mock(RunStepUsageRepository.class);
  private LlmExecutionDispatchGuard guard;

  @BeforeEach
  void setUp() {
    guard = new LlmExecutionDispatchGuard(contexts, apiKeys, models, usage);
    ReflectionTestUtils.setField(guard, "platformAnthropicKey", "platform-a");
    ReflectionTestUtils.setField(guard, "platformOpenAiKey", "platform-o");
    ReflectionTestUtils.setField(guard, "platformGeminiKey", "");
    ReflectionTestUtils.setField(guard, "platformOpenRouterKey", "");
    ReflectionTestUtils.setField(guard, "platformDeepSeekKey", "");
    ReflectionTestUtils.setField(guard, "platformFireworksKey", "");
    when(models.canonicalize("gpt-test")).thenReturn("gpt-test");
    when(models.isKnownModel("gpt-test")).thenReturn(true);
    when(models.providerFor("gpt-test")).thenReturn("openai");
  }

  @Test
  void missingExplicitContextFailsBeforeCredentialResolution() {
    assertThatThrownBy(() -> guard.authorize(request(null, null, "openai", "gpt-test")))
        .isInstanceOf(LlmDispatchPolicyException.class)
        .extracting(error -> ((LlmDispatchPolicyException) error).reason())
        .isEqualTo(LlmDispatchPolicyException.Reason.CONTEXT_REQUIRED);
    verify(apiKeys, never()).resolveWithProvenance(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void crossTenantScopeIsIndistinguishableFromMissingContext() {
    LlmExecutionScope wrong = LlmExecutionScope.customer("org-b");
    when(contexts.load(wrong, "ctx-1")).thenReturn(java.util.Optional.empty());

    assertThatThrownBy(() -> guard.authorize(request("ctx-1", wrong, "openai", "gpt-test")))
        .isInstanceOf(LlmDispatchPolicyException.class)
        .extracting(error -> ((LlmDispatchPolicyException) error).reason())
        .isEqualTo(LlmDispatchPolicyException.Reason.CONTEXT_NOT_FOUND);
  }

  @Test
  void exactOwnKeyRouteReloadsCurrentCredentialWithoutRunKeyCache() {
    LlmExecutionScope scope = LlmExecutionScope.customer("org-a");
    stubContext(scope, exactOwnKeyPolicy(), null);
    when(apiKeys.resolveWithProvenance("user-a"))
        .thenReturn(
            new ApiKeyResolver.CredentialResolution(
                keys("current-user-key", "OWN_KEYS", Set.of("openai")),
                "org-a",
                Map.of("openai", ApiKeyResolver.CredentialOrigin.USER)));

    var result = guard.authorize(request("ctx-1", scope, "openai", "gpt-test"));

    assertThat(result.apiKey()).isEqualTo("current-user-key");
    assertThat(result.keySource()).isEqualTo(RunKeyContext.Source.USER);
  }

  @Test
  void removedOrMovedCredentialFailsBeforeSpend() {
    LlmExecutionScope scope = LlmExecutionScope.customer("org-a");
    stubContext(scope, exactOwnKeyPolicy(), null);
    when(apiKeys.resolveWithProvenance("user-a"))
        .thenReturn(
            new ApiKeyResolver.CredentialResolution(
                keys("org-key", "OWN_KEYS", Set.of("openai")),
                "org-a",
                Map.of("openai", ApiKeyResolver.CredentialOrigin.ORG)));

    assertThatThrownBy(() -> guard.authorize(request("ctx-1", scope, "openai", "gpt-test")))
        .isInstanceOf(LlmDispatchPolicyException.class)
        .extracting(error -> ((LlmDispatchPolicyException) error).reason())
        .isEqualTo(LlmDispatchPolicyException.Reason.CREDENTIAL_SOURCE_CHANGED);
  }

  @Test
  void providerSwitchOutsideSnapshotFailsEvenWhenAnotherPlatformKeyExists() {
    LlmExecutionScope scope = LlmExecutionScope.customer("org-a");
    stubContext(scope, exactOwnKeyPolicy(), null);

    assertThatThrownBy(() -> guard.authorize(request("ctx-1", scope, "anthropic", "gpt-test")))
        .isInstanceOf(LlmDispatchPolicyException.class)
        .extracting(error -> ((LlmDispatchPolicyException) error).reason())
        .isEqualTo(LlmDispatchPolicyException.Reason.PROVIDER_NOT_ALLOWED);
    verify(apiKeys, never()).resolveWithProvenance(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void providerCallBudgetIsCheckedAgainstDurableAttemptLedger() {
    LlmExecutionScope scope = LlmExecutionScope.customer("org-a");
    stubContext(scope, exactOwnKeyPolicy(), 2);
    when(usage.countByLlmExecutionContextId("ctx-1")).thenReturn(2L);

    assertThatThrownBy(() -> guard.authorize(request("ctx-1", scope, "openai", "gpt-test")))
        .isInstanceOf(LlmDispatchPolicyException.class)
        .extracting(error -> ((LlmDispatchPolicyException) error).reason())
        .isEqualTo(LlmDispatchPolicyException.Reason.BUDGET_EXHAUSTED);
    verify(apiKeys, never()).resolveWithProvenance(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void providerCapabilityUsesTheSameOwnKeyTenantAndSourceContract() {
    LlmExecutionScope scope = LlmExecutionScope.customer("org-a");
    stubContext(scope, exactOwnKeyPolicy(), null);
    when(apiKeys.resolveWithProvenance("user-a"))
        .thenReturn(
            new ApiKeyResolver.CredentialResolution(
                keys("current-user-key", "OWN_KEYS", Set.of("openai")),
                "org-a",
                Map.of("openai", ApiKeyResolver.CredentialOrigin.USER)));

    var dispatch =
        guard.authorizeProviderCapability(new LlmExecutionReference("ctx-1", scope), "openai");

    assertThat(dispatch.apiKey()).isEqualTo("current-user-key");
    assertThat(dispatch.keySource()).isEqualTo(RunKeyContext.Source.USER);
  }

  @Test
  void providerCapabilityCannotSwitchAwayFromTheSelectedProvider() {
    LlmExecutionScope scope = LlmExecutionScope.customer("org-a");
    stubContext(scope, exactOwnKeyPolicy(), null);

    assertThatThrownBy(
            () ->
                guard.authorizeProviderCapability(
                    new LlmExecutionReference("ctx-1", scope), "anthropic"))
        .isInstanceOf(LlmDispatchPolicyException.class)
        .extracting(error -> ((LlmDispatchPolicyException) error).reason())
        .isEqualTo(LlmDispatchPolicyException.Reason.PROVIDER_NOT_ALLOWED);
    verify(apiKeys, never()).resolveWithProvenance(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void exactOpenRouterRouteAcceptsARegisteredAggregatorModelOwnedByAnthropic() {
    String model = "claude-sonnet-4-6";
    when(models.canonicalize(model)).thenReturn(model);
    when(models.isKnownModel(model)).thenReturn(true);
    when(models.providerFor(model)).thenReturn("anthropic");
    LlmExecutionScope scope = LlmExecutionScope.customer("org-a");
    LlmPolicySnapshot policy =
        new LlmPolicySnapshot(
            LlmPolicySnapshot.KeySourceMode.OWN_KEYS,
            "user-a",
            Set.of("openrouter"),
            Map.of("openrouter", List.of(model)),
            Map.of("openrouter", LlmPolicySnapshot.CredentialSource.USER),
            Map.of(),
            "openrouter",
            model,
            LlmPolicySnapshot.RoutingMode.EXACT,
            true,
            false,
            1);
    stubContext(scope, policy, null);
    when(apiKeys.resolveWithProvenance("user-a"))
        .thenReturn(
            new ApiKeyResolver.CredentialResolution(
                new ResolvedKeys(
                    null,
                    null,
                    null,
                    "openrouter-key",
                    null,
                    null,
                    "OWN_KEYS",
                    Set.of("openrouter")),
                "org-a",
                Map.of("openrouter", ApiKeyResolver.CredentialOrigin.USER)));

    var result = guard.authorize(request("ctx-1", scope, "openrouter", model));

    assertThat(result.apiKey()).isEqualTo("openrouter-key");
    assertThat(result.keySource()).isEqualTo(RunKeyContext.Source.USER);
  }

  @Test
  void providerCapabilityResolvesTheActualPlatformKeyForSystemPolicy() {
    LlmExecutionScope scope = LlmExecutionScope.customer("org-a");
    stubContext(scope, platformPolicy(LlmPolicySnapshot.KeySourceMode.SYSTEM), null);
    when(apiKeys.resolveWithProvenance("user-a"))
        .thenReturn(
            new ApiKeyResolver.CredentialResolution(
                keys(null, "SYSTEM", Set.of("openai")), "org-a", Map.of()));

    var dispatch =
        guard.authorizeProviderCapability(new LlmExecutionReference("ctx-1", scope), "openai");

    assertThat(dispatch.keySource()).isEqualTo(RunKeyContext.Source.PLATFORM);
    assertThat(dispatch.apiKey()).isEqualTo("platform-o");
  }

  @Test
  void providerCapabilityResolvesTheActualPlatformKeyForHybridPolicy() {
    LlmExecutionScope scope = LlmExecutionScope.customer("org-a");
    stubContext(scope, platformPolicy(LlmPolicySnapshot.KeySourceMode.HYBRID), null);
    when(apiKeys.resolveWithProvenance("user-a"))
        .thenReturn(
            new ApiKeyResolver.CredentialResolution(
                keys(null, "HYBRID", Set.of("openai")), "org-a", Map.of()));

    var dispatch =
        guard.authorizeProviderCapability(new LlmExecutionReference("ctx-1", scope), "openai");

    assertThat(dispatch.keySource()).isEqualTo(RunKeyContext.Source.PLATFORM);
    assertThat(dispatch.apiKey()).isEqualTo("platform-o");
  }

  /**
   * An OWN_KEYS snapshot can never carry a PLATFORM source ({@link LlmPolicySnapshot} refuses to
   * build one), so the reachable OWN_KEYS case is drift: a platform-sourced context whose org has
   * since moved to BYOK must not keep spending the platform key.
   */
  @Test
  void providerCapabilityStillRefusesPlatformCredentialsAfterAnOrgMovesToOwnKeys() {
    LlmExecutionScope scope = LlmExecutionScope.customer("org-a");
    stubContext(scope, platformPolicy(LlmPolicySnapshot.KeySourceMode.HYBRID), null);
    when(apiKeys.resolveWithProvenance("user-a"))
        .thenReturn(
            new ApiKeyResolver.CredentialResolution(
                keys("user-key", "OWN_KEYS", Set.of("openai")),
                "org-a",
                Map.of("openai", ApiKeyResolver.CredentialOrigin.USER)));

    assertThatThrownBy(
            () ->
                guard.authorizeProviderCapability(
                    new LlmExecutionReference("ctx-1", scope), "openai"))
        .isInstanceOf(LlmDispatchPolicyException.class)
        .extracting(error -> ((LlmDispatchPolicyException) error).reason())
        .isEqualTo(LlmDispatchPolicyException.Reason.POLICY_STALE);
  }

  @Test
  void providerCapabilityFailsLoudlyWhenThePlatformKeyIsNotConfigured() {
    LlmExecutionScope scope = LlmExecutionScope.customer("org-a");
    ReflectionTestUtils.setField(guard, "platformOpenAiKey", "");
    stubContext(scope, platformPolicy(LlmPolicySnapshot.KeySourceMode.SYSTEM), null);
    when(apiKeys.resolveWithProvenance("user-a"))
        .thenReturn(
            new ApiKeyResolver.CredentialResolution(
                keys(null, "SYSTEM", Set.of("openai")), "org-a", Map.of()));

    assertThatThrownBy(
            () ->
                guard.authorizeProviderCapability(
                    new LlmExecutionReference("ctx-1", scope), "openai"))
        .isInstanceOf(LlmDispatchPolicyException.class)
        .extracting(error -> ((LlmDispatchPolicyException) error).reason())
        .isEqualTo(LlmDispatchPolicyException.Reason.CREDENTIAL_UNAVAILABLE);
  }

  private void stubContext(
      LlmExecutionScope scope, LlmPolicySnapshot policy, Integer maxProviderCalls) {
    LlmExecutionContext context = mock(LlmExecutionContext.class);
    when(contexts.load(scope, "ctx-1")).thenReturn(java.util.Optional.of(context));
    when(context.getStatus()).thenReturn(LlmExecutionContext.Status.QUEUED);
    when(context.getIsolationKey()).thenReturn(scope.isolationKey());
    when(context.getScopeMode()).thenReturn(scope.mode());
    when(context.getOrgId()).thenReturn("org-a");
    when(context.getUserId()).thenReturn("user-a");
    when(context.getKeySubjectUserId()).thenReturn("user-a");
    when(context.getId()).thenReturn("ctx-1");
    when(context.getMaxProviderCalls()).thenReturn(maxProviderCalls);
    when(context.getMaxInputTokens()).thenReturn(null);
    when(context.getMaxOutputTokens()).thenReturn(null);
    when(context.getMaxTotalTokens()).thenReturn(null);
    when(context.getMaxCostUsd()).thenReturn(null);
    when(context.policySnapshot()).thenReturn(policy);
  }

  private static LlmPolicySnapshot exactOwnKeyPolicy() {
    return new LlmPolicySnapshot(
        LlmPolicySnapshot.KeySourceMode.OWN_KEYS,
        "user-a",
        Set.of("openai"),
        Map.of("openai", List.of("gpt-test")),
        Map.of("openai", LlmPolicySnapshot.CredentialSource.USER),
        Map.of(),
        "openai",
        "gpt-test",
        LlmPolicySnapshot.RoutingMode.EXACT,
        true,
        false,
        1);
  }

  private static LlmPolicySnapshot platformPolicy(LlmPolicySnapshot.KeySourceMode mode) {
    return new LlmPolicySnapshot(
        mode,
        "user-a",
        Set.of("openai"),
        Map.of("openai", List.of("gpt-test")),
        Map.of("openai", LlmPolicySnapshot.CredentialSource.PLATFORM),
        Map.of(),
        "openai",
        "gpt-test",
        LlmPolicySnapshot.RoutingMode.EXACT,
        true,
        false,
        1);
  }

  private static ResolvedKeys keys(String openAi, String mode, Set<String> providers) {
    return new ResolvedKeys(null, openAi, null, null, null, null, mode, providers);
  }

  private static LlmRequest request(
      String contextId, LlmExecutionScope scope, String provider, String model) {
    return LlmRequest.builder()
        .provider(provider)
        .model(model)
        .messages(List.of(new UserMessage("test")))
        .context(new ExecutionContext("run-1", "task-1", Path.of("/tmp")))
        .llmExecutionContextId(contextId)
        .llmExecutionScope(scope)
        .build();
  }
}
