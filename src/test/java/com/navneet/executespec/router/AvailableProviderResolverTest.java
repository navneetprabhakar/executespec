package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agent.ResolvedKeys;
import com.navneet.executespec.executioncontext.LlmExecutionContext;
import com.navneet.executespec.executioncontext.LlmExecutionContextService;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.executioncontext.LlmPolicySnapshot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AvailableProviderResolverTest {

  private final LlmExecutionPolicyFactory factory = mock(LlmExecutionPolicyFactory.class);
  private final LlmExecutionContextService contexts = mock(LlmExecutionContextService.class);
  private final ApiKeyResolver apiKeys = mock(ApiKeyResolver.class);
  private AvailableProviderResolver resolver;

  @BeforeEach
  void setUp() {
    resolver = new AvailableProviderResolver(factory, contexts, apiKeys);
    ReflectionTestUtils.setField(resolver, "systemAnthropicKey", "system-anthropic");
    ReflectionTestUtils.setField(resolver, "systemOpenAiKey", "");
    ReflectionTestUtils.setField(resolver, "systemGeminiKey", "");
    ReflectionTestUtils.setField(resolver, "systemOpenRouterKey", "");
    ReflectionTestUtils.setField(resolver, "systemDeepSeekKey", "");
    ReflectionTestUtils.setField(resolver, "systemFireworksKey", "");
  }

  @Test
  void explicitProviderPolicyCannotFallBackToAnUnlistedSystemProvider() {
    stub(
        "run-1",
        policy("SYSTEM", Set.of("openai"), Map.of("openai", "PLATFORM")),
        new ResolvedKeys(null, null, null, null, null, null, "SYSTEM", Set.of("openai")),
        Map.of());

    assertThat(resolver.resolve("run-1")).isEmpty();
  }

  @Test
  void ownKeysModeCannotFallBackToPlatformProviderWhenCredentialWasRemoved() {
    stub(
        "run-2",
        policy("OWN_KEYS", Set.of("anthropic"), Map.of("anthropic", "ORG")),
        new ResolvedKeys(null, null, null, null, null, null, "OWN_KEYS", null),
        Map.of());

    assertThat(resolver.resolve("run-2")).isEmpty();
  }

  @Test
  void configuredSystemProviderStillResolvesWhenDurablePolicyAllowsIt() {
    stub(
        "run-3",
        policy("SYSTEM", Set.of("anthropic"), Map.of("anthropic", "PLATFORM")),
        new ResolvedKeys(null, null, null, null, null, null, "SYSTEM", null),
        Map.of());

    assertThat(resolver.resolve("run-3")).containsExactly(LlmProvider.ANTHROPIC);
  }

  @Test
  void missingDurableContextFailsClosedEvenWhenSystemKeyExists() {
    when(factory.forRun("run-without-context")).thenThrow(new IllegalStateException("missing"));

    assertThat(resolver.resolve("run-without-context")).isEmpty();
  }

  private void stub(
      String runId,
      LlmPolicySnapshot policy,
      ResolvedKeys keys,
      Map<String, ApiKeyResolver.CredentialOrigin> origins) {
    LlmExecutionScope scope = LlmExecutionScope.customer("org-1");
    LlmExecutionReference reference = new LlmExecutionReference("ctx-1", scope);
    LlmExecutionContext context = mock(LlmExecutionContext.class);
    when(factory.forRun(runId)).thenReturn(reference);
    when(contexts.require(scope, "ctx-1")).thenReturn(context);
    when(context.policySnapshot()).thenReturn(policy);
    when(context.getOrgId()).thenReturn("org-1");
    when(context.getKeySubjectUserId()).thenReturn("user-1");
    when(apiKeys.resolveWithProvenance("user-1"))
        .thenReturn(new ApiKeyResolver.CredentialResolution(keys, "org-1", origins));
  }

  private static LlmPolicySnapshot policy(
      String mode, Set<String> providers, Map<String, String> sources) {
    Map<String, LlmPolicySnapshot.CredentialSource> typedSources = new java.util.LinkedHashMap<>();
    sources.forEach(
        (provider, source) ->
            typedSources.put(provider, LlmPolicySnapshot.CredentialSource.valueOf(source)));
    Map<String, List<String>> models = new java.util.LinkedHashMap<>();
    providers.forEach(provider -> models.put(provider, List.of("model")));
    return new LlmPolicySnapshot(
        LlmPolicySnapshot.KeySourceMode.valueOf(mode),
        "user-1",
        providers,
        models,
        typedSources,
        Map.of(),
        providers.iterator().next(),
        null,
        LlmPolicySnapshot.RoutingMode.PROVIDER_PREFERRED,
        false,
        true,
        1);
  }
}
