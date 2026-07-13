package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;

import com.navneet.executespec.agent.ResolvedKeys;
import com.navneet.executespec.agent.RunKeyStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AvailableProviderResolverTest {

  private final RunKeyStore runKeyStore = mock(RunKeyStore.class);
  private AvailableProviderResolver resolver;

  @BeforeEach
  void setUp() {
    resolver = new AvailableProviderResolver(runKeyStore);
    ReflectionTestUtils.setField(resolver, "systemAnthropicKey", "system-anthropic");
    ReflectionTestUtils.setField(resolver, "systemOpenAiKey", "");
    ReflectionTestUtils.setField(resolver, "systemGeminiKey", "");
    ReflectionTestUtils.setField(resolver, "systemOpenRouterKey", "");
    ReflectionTestUtils.setField(resolver, "systemDeepSeekKey", "");
    ReflectionTestUtils.setField(resolver, "systemFireworksKey", "");
  }

  @Test
  void explicitProviderPolicyCannotFallBackToAnUnlistedSystemProvider() {
    when(runKeyStore.get("run-1"))
        .thenReturn(
            new ResolvedKeys(null, null, null, null, null, null, "SYSTEM", Set.of("openai")));

    assertThat(resolver.resolve("run-1")).isEmpty();
  }

  @Test
  void ownKeysModeCannotFallBackToPlatformProviderWhenNoOwnKeyIsResolved() {
    when(runKeyStore.get("run-2"))
        .thenReturn(new ResolvedKeys(null, null, null, null, null, null, "OWN_KEYS", null));

    assertThat(resolver.resolve("run-2")).isEmpty();
  }

  @Test
  void configuredSystemProviderStillResolvesWhenPolicyAllowsIt() {
    when(runKeyStore.get("run-3"))
        .thenReturn(new ResolvedKeys(null, null, null, null, null, null, "SYSTEM", null));

    assertThat(resolver.resolve("run-3")).containsExactly(LlmProvider.ANTHROPIC);
  }

  @Test
  void missingRunContextDoesNotInventAProviderWhenNoSystemKeyExists() {
    ReflectionTestUtils.setField(resolver, "systemAnthropicKey", "");

    when(runKeyStore.get("run-without-context")).thenReturn(null);

    assertThat(resolver.resolve("run-without-context")).isEmpty();
  }
}
