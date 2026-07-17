package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agent.ResolvedKeys;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.router.ProviderReadinessService.State;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ProviderReadinessServiceTest {

  private final ApiKeyResolver keyResolver = mock(ApiKeyResolver.class);
  private final AppUserRepository users = mock(AppUserRepository.class);
  private final OrgRepository orgs = mock(OrgRepository.class);
  private final ProviderHealthTracker health = mock(ProviderHealthTracker.class);
  private ProviderReadinessService service;

  @BeforeEach
  void setUp() {
    service = new ProviderReadinessService(keyResolver, users, orgs, health);
    ReflectionTestUtils.setField(service, "systemAnthropicKey", "managed-anthropic");
    ReflectionTestUtils.setField(service, "systemOpenAiKey", "");
    ReflectionTestUtils.setField(service, "systemGeminiKey", "");
    ReflectionTestUtils.setField(service, "systemOpenRouterKey", "");
    ReflectionTestUtils.setField(service, "systemDeepSeekKey", "managed-deepseek");
    ReflectionTestUtils.setField(service, "systemFireworksKey", "");
    for (LlmProvider provider : LlmProvider.values())
      when(health.isHealthy(provider)).thenReturn(true);
  }

  @Test
  void distinguishesUserOrgAndManagedCredentials() {
    AppUser user = AppUser.builder().id("u1").orgId("o1").openAiApiKey("user-openai").build();
    Org org = Org.builder().id("o1").geminiApiKey("org-gemini").keySourceMode("HYBRID").build();
    when(users.findById("u1")).thenReturn(Optional.of(user));
    when(orgs.findById("o1")).thenReturn(Optional.of(org));
    when(keyResolver.resolve("u1"))
        .thenReturn(
            new ResolvedKeys(null, "user-openai", "org-gemini", null, null, null, "HYBRID", null));

    var readiness = service.resolve("u1");

    assertThat(state(readiness, LlmProvider.ANTHROPIC)).isEqualTo(State.AVAILABLE_MANAGED);
    assertThat(state(readiness, LlmProvider.OPENAI)).isEqualTo(State.AVAILABLE_USER_KEY);
    assertThat(state(readiness, LlmProvider.GEMINI)).isEqualTo(State.AVAILABLE_ORG_KEY);
    assertThat(state(readiness, LlmProvider.FIREWORKS)).isEqualTo(State.UNAVAILABLE);
  }

  @Test
  void explicitProviderPolicyWinsOverCredentialPresence() {
    AppUser user = AppUser.builder().id("u1").orgId("o1").openAiApiKey("user-openai").build();
    Org org = Org.builder().id("o1").keySourceMode("HYBRID").build();
    when(users.findById("u1")).thenReturn(Optional.of(user));
    when(orgs.findById("o1")).thenReturn(Optional.of(org));
    when(keyResolver.resolve("u1"))
        .thenReturn(
            new ResolvedKeys(
                null, "user-openai", null, null, null, null, "HYBRID", Set.of("deepseek")));

    var readiness = service.resolve("u1");

    assertThat(state(readiness, LlmProvider.OPENAI)).isEqualTo(State.POLICY_BLOCKED);
    assertThat(state(readiness, LlmProvider.DEEPSEEK)).isEqualTo(State.AVAILABLE_MANAGED);
  }

  @Test
  void ownKeysModeNeverReportsManagedCredentialAsAvailable() {
    AppUser user = AppUser.builder().id("u1").orgId("o1").build();
    Org org = Org.builder().id("o1").keySourceMode("OWN_KEYS").build();
    when(users.findById("u1")).thenReturn(Optional.of(user));
    when(orgs.findById("o1")).thenReturn(Optional.of(org));
    when(keyResolver.resolve("u1"))
        .thenReturn(new ResolvedKeys(null, null, null, null, null, null, "OWN_KEYS", null));

    assertThat(state(service.resolve("u1"), LlmProvider.ANTHROPIC)).isEqualTo(State.UNAVAILABLE);
  }

  @Test
  void circuitOpenWinsAfterEffectiveCredentialResolution() {
    AppUser user = AppUser.builder().id("u1").build();
    when(users.findById("u1")).thenReturn(Optional.of(user));
    when(keyResolver.resolve("u1"))
        .thenReturn(new ResolvedKeys(null, null, null, null, null, null, "SYSTEM", null));
    when(health.isHealthy(LlmProvider.DEEPSEEK)).thenReturn(false);

    var item =
        service.resolve("u1").stream()
            .filter(value -> value.provider().equals("DEEPSEEK"))
            .findFirst()
            .orElseThrow();

    assertThat(item.state()).isEqualTo(State.CIRCUIT_OPEN);
    assertThat(item.dispatchable()).isFalse();
  }

  @Test
  void malformedKeySourceModeFailsClosedAsPolicyBlocked() {
    AppUser user = AppUser.builder().id("u1").openAiApiKey("user-openai").build();
    when(users.findById("u1")).thenReturn(Optional.of(user));
    when(keyResolver.resolve("u1"))
        .thenReturn(
            new ResolvedKeys(null, "user-openai", null, null, null, null, "UNKNOWN_MODE", null));

    assertThat(state(service.resolve("u1"), LlmProvider.OPENAI)).isEqualTo(State.POLICY_BLOCKED);
  }

  private static State state(
      java.util.List<ProviderReadinessService.ProviderReadiness> readiness, LlmProvider provider) {
    return readiness.stream()
        .filter(value -> value.provider().equals(provider.name()))
        .map(ProviderReadinessService.ProviderReadiness::state)
        .findFirst()
        .orElseThrow();
  }
}
