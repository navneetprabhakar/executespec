package com.navneet.executespec.executioncontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class LlmPolicySnapshotTest {

  @Test
  void ownKeysRejectsAnyPlatformCredentialSource() {
    assertThatThrownBy(
            () ->
                new LlmPolicySnapshot(
                    LlmPolicySnapshot.KeySourceMode.OWN_KEYS,
                    "user-1",
                    Set.of("deepseek"),
                    Map.of("deepseek", List.of("deepseek-v4-pro-native")),
                    Map.of("deepseek", LlmPolicySnapshot.CredentialSource.PLATFORM),
                    Map.of(),
                    "deepseek",
                    "deepseek-v4-pro-native",
                    LlmPolicySnapshot.RoutingMode.EXACT,
                    true,
                    false,
                    1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("OWN_KEYS");
  }

  @Test
  void exactPinnedRouteCannotEnableFailover() {
    assertThatThrownBy(
            () ->
                new LlmPolicySnapshot(
                    LlmPolicySnapshot.KeySourceMode.HYBRID,
                    "user-1",
                    Set.of("anthropic"),
                    Map.of("anthropic", List.of("claude-sonnet-4-6")),
                    Map.of("anthropic", LlmPolicySnapshot.CredentialSource.ORG),
                    Map.of(),
                    "anthropic",
                    "claude-sonnet-4-6",
                    LlmPolicySnapshot.RoutingMode.EXACT,
                    true,
                    true,
                    1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot allow provider failover");
  }

  @Test
  void normalizesProviderAliasesAndProducesStablePolicyCollections() {
    LlmPolicySnapshot snapshot =
        new LlmPolicySnapshot(
            LlmPolicySnapshot.KeySourceMode.OWN_KEYS,
            "user-1",
            Set.of("Google", "DEEPSEEK"),
            Map.of(
                "google", List.of("gemini-3.5-flash"),
                "deepseek", List.of("deepseek-v4-pro-native")),
            Map.of(
                "gemini", LlmPolicySnapshot.CredentialSource.USER,
                "deepseek", LlmPolicySnapshot.CredentialSource.ORG),
            Map.of("google", "gemini-3.5-flash"),
            "GOOGLE",
            "gemini-3.5-flash",
            LlmPolicySnapshot.RoutingMode.PROVIDER_PREFERRED,
            false,
            true,
            3);

    assertThat(snapshot.allowedProviders()).containsExactly("deepseek", "gemini");
    assertThat(snapshot.requestedProvider()).isEqualTo("gemini");
    assertThat(snapshot.modelOverrides()).containsEntry("gemini", "gemini-3.5-flash");
    assertThat(snapshot.canonicalForm()).doesNotContain("apiKey", "secret", "Bearer");
  }
}
