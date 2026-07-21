package com.navneet.executespec.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class PatScopePolicyTest {

  @Test
  void fullRetainsLegacyCompatibility() {
    assertThat(PatScopePolicy.allows("full", "DELETE", "/api/admin/tiers/free")).isTrue();
  }

  @Test
  void readCapabilityDoesNotAuthorizeMutation() {
    assertThat(PatScopePolicy.allows("api:read", "GET", "/api/runs")).isTrue();
    assertThat(PatScopePolicy.allows("api:read", "GET", "/api/admin/tiers")).isFalse();
    assertThat(PatScopePolicy.allows("api:read", "POST", "/api/runs")).isFalse();
  }

  @Test
  void clientDefaultsCoverNormalClientsButExcludePrivilegeSurfaces() {
    String scopes = PatScopePolicy.normalizeRequested(null);
    assertThat(PatScopePolicy.allows(scopes, "POST", "/api/runs")).isTrue();
    assertThat(PatScopePolicy.allows(scopes, "POST", "/api/agent/sessions/x/turns/stream"))
        .isTrue();
    assertThat(PatScopePolicy.allows(scopes, "POST", "/api/projects/p/architecture/generate"))
        .isTrue();
    assertThat(PatScopePolicy.allows(scopes, "PUT", "/api/auth/me/provider-preference")).isTrue();
    assertThat(PatScopePolicy.allows(scopes, "PUT", "/api/auth/me/settings/ai-keys")).isFalse();
    assertThat(PatScopePolicy.allows(scopes, "DELETE", "/api/auth/me/settings/ai-keys/deepseek"))
        .isFalse();
    assertThat(PatScopePolicy.allows(scopes, "PUT", "/api/auth/me/settings/github")).isFalse();
    assertThat(PatScopePolicy.allows(scopes, "PUT", "/api/org/me/settings/ai-keys")).isFalse();
    assertThat(PatScopePolicy.allows(scopes, "PUT", "/api/org/me/settings/key-source")).isFalse();
    assertThat(PatScopePolicy.allows(scopes, "POST", "/api/auth/me/tokens")).isFalse();
    assertThat(PatScopePolicy.allows(scopes, "POST", "/api/billing/subscribe")).isFalse();
    assertThat(PatScopePolicy.allows(scopes, "POST", "/api/admin/swebench/run")).isFalse();
    assertThat(PatScopePolicy.allows(scopes, "POST", "/api/v2/test/benchmark/runs")).isFalse();
    assertThat(PatScopePolicy.allows(scopes, "POST", "/api/unknown-write")).isFalse();
  }

  @Test
  void explicitCredentialCapabilityDoesNotGrantUnrelatedSettingsOrAdmin() {
    String scopes = PatScopePolicy.normalizeRequested(List.of("credentials:manage"));
    assertThat(PatScopePolicy.allows(scopes, "PUT", "/api/auth/me/settings/ai-keys")).isTrue();
    assertThat(PatScopePolicy.allows(scopes, "POST", "/api/auth/me/settings/jira/validate"))
        .isTrue();
    assertThat(PatScopePolicy.allows(scopes, "PUT", "/api/auth/me/provider-preference")).isFalse();
    assertThat(PatScopePolicy.allows(scopes, "PUT", "/api/admin/tiers/free")).isFalse();
  }

  @Test
  void agentWriteAloneCannotCreateARunOrWriteLocalFiles() {
    String chatOnly = PatScopePolicy.normalizeRequested(List.of("api:read", "agent:write"));
    assertThat(PatScopePolicy.allows(chatOnly, "POST", "/api/orchestrator/turn")).isTrue();
    assertThat(PatScopePolicy.allows(chatOnly, "POST", "/api/orchestrator/turn/confirm")).isFalse();
    assertThat(PatScopePolicy.allows(chatOnly, "POST", "/api/agent/sessions/s1/writer/reserve"))
        .isFalse();
    assertThat(PatScopePolicy.allows(chatOnly, "POST", "/api/agent/sessions/s1/writer/mutate"))
        .isFalse();
    assertThat(PatScopePolicy.allows(chatOnly, "POST", "/api/agent/sessions/s1/writer/execute"))
        .isFalse();
    assertThat(PatScopePolicy.allows(chatOnly, "POST", "/api/agent/sessions/s1/writer/release"))
        .isFalse();
  }

  @Test
  void boundaryRoutesRequireTheUnionOfBothCapabilities() {
    String scopes = PatScopePolicy.normalizeRequested(null);
    assertThat(scopes).contains("agent:write").contains("runs:write");
    assertThat(PatScopePolicy.allows(scopes, "POST", "/api/orchestrator/turn/confirm")).isTrue();
    // runs:write without agent:write is equally insufficient for the agent confirmation route.
    assertThat(
            PatScopePolicy.allows(
                PatScopePolicy.normalizeRequested(List.of("runs:write")),
                "POST",
                "/api/orchestrator/turn/confirm"))
        .isFalse();
    String writer = PatScopePolicy.normalizeRequested(List.of("agent:write", "local:write"));
    assertThat(PatScopePolicy.allows(writer, "POST", "/api/agent/sessions/s1/writer/mutate"))
        .isTrue();
    assertThat(PatScopePolicy.allows("full", "POST", "/api/agent/sessions/s1/writer/mutate"))
        .isTrue();
    assertThat(PatScopePolicy.allows("full", "POST", "/api/orchestrator/turn/confirm")).isTrue();
  }

  @Test
  void localWriteIsNotInTheClientDefaultSet() {
    assertThat(PatScopePolicy.CLIENT_DEFAULT_SCOPES).doesNotContain(PatScopePolicy.LOCAL_WRITE);
  }

  @Test
  void normalizationRejectsUnknownAndAmbiguousFull() {
    assertThatThrownBy(() -> PatScopePolicy.normalizeRequested(List.of("root")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PatScopePolicy.normalizeRequested(List.of("full", "api:read")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
