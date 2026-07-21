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
    // RR-004 — the ACTUAL personal credential mappings live under /api/auth/settings/**.
    assertThat(PatScopePolicy.allows(scopes, "PUT", "/api/auth/settings/ai-keys")).isFalse();
    assertThat(PatScopePolicy.allows(scopes, "DELETE", "/api/auth/settings/ai-keys/deepseek"))
        .isFalse();
    assertThat(PatScopePolicy.allows(scopes, "PUT", "/api/auth/settings/github")).isFalse();
    assertThat(PatScopePolicy.allows(scopes, "PUT", "/api/auth/settings/bitbucket")).isFalse();
    assertThat(PatScopePolicy.allows(scopes, "POST", "/api/auth/settings/jira/validate")).isFalse();
    assertThat(PatScopePolicy.allows(scopes, "POST", "/api/auth/settings/confluence/validate"))
        .isFalse();
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
    // RR-004 — a scoped credentials:manage PAT must reach the real controller mappings.
    assertThat(PatScopePolicy.allows(scopes, "PUT", "/api/auth/settings/ai-keys")).isTrue();
    assertThat(PatScopePolicy.allows(scopes, "DELETE", "/api/auth/settings/ai-keys/deepseek"))
        .isTrue();
    assertThat(PatScopePolicy.allows(scopes, "PUT", "/api/auth/settings/github")).isTrue();
    assertThat(PatScopePolicy.allows(scopes, "PUT", "/api/auth/settings/bitbucket")).isTrue();
    assertThat(PatScopePolicy.allows(scopes, "POST", "/api/auth/settings/jira/validate")).isTrue();
    assertThat(PatScopePolicy.allows(scopes, "POST", "/api/auth/settings/confluence/validate"))
        .isTrue();
    assertThat(PatScopePolicy.allows(scopes, "PUT", "/api/auth/me/provider-preference")).isFalse();
    assertThat(PatScopePolicy.allows(scopes, "PUT", "/api/admin/tiers/free")).isFalse();
  }

  @Test
  void normalizationRejectsUnknownAndAmbiguousFull() {
    assertThatThrownBy(() -> PatScopePolicy.normalizeRequested(List.of("root")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PatScopePolicy.normalizeRequested(List.of("full", "api:read")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
