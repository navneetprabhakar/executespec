package com.navneet.executespec.router;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agent.ResolvedKeys;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Canonical, secret-free provider dispatch readiness for an authenticated account.
 *
 * <p>This is deliberately stricter than deployment key-presence health. It applies the caller's org
 * provider policy and key-source mode, identifies the effective key provenance, and finally checks
 * the shared provider circuit breaker. Web and local clients should use this contract instead of
 * inferring readiness from billing tier or the presence of a user-visible BYOK key.
 */
@Service
@RequiredArgsConstructor
public class ProviderReadinessService {

  public enum State {
    AVAILABLE_MANAGED,
    AVAILABLE_USER_KEY,
    AVAILABLE_ORG_KEY,
    POLICY_BLOCKED,
    CIRCUIT_OPEN,
    UNAVAILABLE
  }

  public record ProviderReadiness(
      String provider, State state, boolean dispatchable, String keySourceMode, String reason) {}

  private enum Source {
    MANAGED,
    USER,
    ORG,
    NONE
  }

  private final ApiKeyResolver apiKeyResolver;
  private final AppUserRepository userRepository;
  private final OrgRepository orgRepository;
  private final ProviderHealthTracker healthTracker;
  private final ModelRegistry modelRegistry;

  @Value("${spring.ai.anthropic.api-key:}")
  private String systemAnthropicKey;

  @Value("${spring.ai.openai.api-key:}")
  private String systemOpenAiKey;

  @Value("${spring.ai.google.genai.api-key:}")
  private String systemGeminiKey;

  @Value("${spring.ai.openrouter.api-key:}")
  private String systemOpenRouterKey;

  @Value("${spring.ai.deepseek.api-key:}")
  private String systemDeepSeekKey;

  @Value("${spring.ai.fireworks.api-key:}")
  private String systemFireworksKey;

  public List<ProviderReadiness> resolve(String userId) {
    AppUser user = userId == null ? null : userRepository.findById(userId).orElse(null);
    Org org =
        user == null || user.getOrgId() == null
            ? null
            : orgRepository.findById(user.getOrgId()).orElse(null);
    ResolvedKeys keys = apiKeyResolver.resolve(userId);
    String mode = normalizeMode(keys.keySourceMode());
    Set<String> enabled = normalizeProviders(keys.enabledProviders());

    return Arrays.stream(LlmProvider.values())
        .map(provider -> readiness(provider, user, org, keys, mode, enabled))
        .toList();
  }

  private ProviderReadiness readiness(
      LlmProvider provider,
      AppUser user,
      Org org,
      ResolvedKeys keys,
      String mode,
      Set<String> enabled) {
    String name = provider.name().toLowerCase(Locale.ROOT);
    if (!Set.of("SYSTEM", "OWN_KEYS", "HYBRID").contains(mode)) {
      return result(
          provider,
          State.POLICY_BLOCKED,
          mode,
          "The organization key-source policy is invalid and fails closed.");
    }
    if (ModelAccessService.isDisabledProvider(name)
        || (!enabled.isEmpty() && !enabled.contains(name))) {
      return result(
          provider,
          State.POLICY_BLOCKED,
          mode,
          "Provider is disabled by the effective organization policy.");
    }
    if (isFreeTier(user, org)
        && !name.equals(modelRegistry.freeTierProvider().trim().toLowerCase(Locale.ROOT))) {
      return result(
          provider, State.POLICY_BLOCKED, mode, "Provider is not available on the free tier.");
    }

    Source source = effectiveSource(provider, user, org, mode);
    if (source == Source.NONE) {
      return result(
          provider,
          State.UNAVAILABLE,
          mode,
          "No permitted user, organization, or managed credential is configured.");
    }
    if (!healthTracker.isHealthy(provider)) {
      return result(
          provider,
          State.CIRCUIT_OPEN,
          mode,
          "A credential is available, but the provider circuit breaker is open.");
    }

    return switch (source) {
      case USER ->
          result(provider, State.AVAILABLE_USER_KEY, mode, "Using the caller's provider key.");
      case ORG ->
          result(provider, State.AVAILABLE_ORG_KEY, mode, "Using the organization provider key.");
      case MANAGED ->
          result(provider, State.AVAILABLE_MANAGED, mode, "Using the managed platform provider.");
      case NONE -> throw new IllegalStateException("unreachable source");
    };
  }

  private Source effectiveSource(LlmProvider provider, AppUser user, Org org, String mode) {
    if (!"SYSTEM".equals(mode) && customerKeysEntitled(user, org)) {
      if (hasUserKey(provider, user)) return Source.USER;
      if (hasOrgKey(provider, org)) return Source.ORG;
    }
    if (!"OWN_KEYS".equals(mode) && hasSystemKey(provider)) return Source.MANAGED;

    return Source.NONE;
  }

  private static boolean customerKeysEntitled(AppUser user, Org org) {
    String tier = org != null ? org.getTier() : user == null ? null : user.getTier();
    if (tier == null) return false;
    return Set.of("BYOK", "TEAM", "ENTERPRISE")
        .contains(tier.trim().toUpperCase(java.util.Locale.ROOT));
  }

  private static boolean isFreeTier(AppUser user, Org org) {
    String tier = org != null ? org.getTier() : user == null ? null : user.getTier();
    return tier != null && "FREE".equals(tier.trim().toUpperCase(Locale.ROOT));
  }

  private boolean hasUserKey(LlmProvider provider, AppUser user) {
    if (user == null) return false;
    return switch (provider) {
      case ANTHROPIC -> present(user.getAnthropicApiKey());
      case OPENAI -> present(user.getOpenAiApiKey());
      case GEMINI -> present(user.getGeminiApiKey());
      case OPENROUTER -> false; // OpenRouter is intentionally organization-scoped.
      case DEEPSEEK -> present(user.getDeepSeekApiKey());
      case FIREWORKS -> present(user.getFireworksApiKey());
    };
  }

  private boolean hasOrgKey(LlmProvider provider, Org org) {
    if (org == null) return false;
    return switch (provider) {
      case ANTHROPIC -> present(org.getAnthropicApiKey());
      case OPENAI -> present(org.getOpenAiApiKey());
      case GEMINI -> present(org.getGeminiApiKey());
      case OPENROUTER -> present(org.getOpenRouterApiKey());
      case DEEPSEEK -> present(org.getDeepSeekApiKey());
      case FIREWORKS -> present(org.getFireworksApiKey());
    };
  }

  private boolean hasSystemKey(LlmProvider provider) {
    return switch (provider) {
      case ANTHROPIC -> present(systemAnthropicKey);
      case OPENAI -> present(systemOpenAiKey);
      case GEMINI -> present(systemGeminiKey);
      case OPENROUTER -> present(systemOpenRouterKey);
      case DEEPSEEK -> present(systemDeepSeekKey);
      case FIREWORKS -> present(systemFireworksKey);
    };
  }

  private static ProviderReadiness result(
      LlmProvider provider, State state, String mode, String reason) {
    return new ProviderReadiness(
        provider.name(),
        state,
        state == State.AVAILABLE_MANAGED
            || state == State.AVAILABLE_USER_KEY
            || state == State.AVAILABLE_ORG_KEY,
        mode,
        reason);
  }

  private static String normalizeMode(String mode) {
    if (mode == null || mode.isBlank()) return "HYBRID";
    return mode.trim().toUpperCase(Locale.ROOT);
  }

  private static Set<String> normalizeProviders(Set<String> providers) {
    if (providers == null || providers.isEmpty()) return Set.of();
    return providers.stream()
        .filter(value -> value != null && !value.isBlank())
        .map(value -> value.trim().toLowerCase(Locale.ROOT))
        .map(value -> "google".equals(value) ? "gemini" : value)
        .collect(Collectors.toUnmodifiableSet());
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }
}
