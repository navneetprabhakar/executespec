package com.navneet.executespec.router;

import java.util.EnumSet;
import java.util.Set;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.executioncontext.LlmExecutionContextService;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmPolicySnapshot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves which LLM providers are available for a given run from the same durable V282 policy
 * snapshot enforced by the transport router. It never reads the retired advisory RunKeyStore.
 *
 * <p>Resolution rules:
 *
 * <ul>
 *   <li><b>SYSTEM mode</b>: providers with system-level keys configured
 *   <li><b>OWN_KEYS mode</b>: only providers with user/org keys resolved (no system fallback)
 *   <li><b>HYBRID mode</b>: union of system + user/org keys
 * </ul>
 *
 * <p>Also respects org-level {@code enabledProviders} policy — if set, only those providers are
 * considered even if keys exist for others.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvailableProviderResolver {

  private final LlmExecutionPolicyFactory executionPolicyFactory;
  private final LlmExecutionContextService executionContextService;
  private final ApiKeyResolver apiKeyResolver;

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

  /**
   * Determine which providers are available for a given run. Missing/stale tenant policy fails
   * closed to an empty set so continuation cannot invent a platform candidate.
   *
   * @param runId the pipeline run ID
   * @return set of available providers; empty means no provider is configured or allowed
   */
  public Set<LlmProvider> resolve(String runId) {
    try {
      var reference = executionPolicyFactory.forRun(runId);
      var context = executionContextService.require(reference.scope(), reference.contextId());
      var policy = context.policySnapshot();
      var current = apiKeyResolver.resolveWithProvenance(context.getKeySubjectUserId());
      if (!context.getOrgId().equals(current.orgId())
          || !policy.keySourceMode().name().equals(current.keys().keySourceMode())) {
        log.warn("Provider availability rejected stale tenant/key policy for run {}", runId);
        return Set.of();
      }
      Set<LlmProvider> available = EnumSet.noneOf(LlmProvider.class);
      for (String provider : policy.allowedProviders()) {
        LlmPolicySnapshot.CredentialSource source = policy.providerKeySources().get(provider);
        boolean present =
            source == LlmPolicySnapshot.CredentialSource.PLATFORM
                ? systemProviderPresent(provider)
                : current.originFor(provider) != null
                    && current.originFor(provider).name().equals(source.name());
        if (!present || !current.keys().isProviderAllowed(provider)) continue;
        try {
          LlmProvider parsed =
              LlmProvider.valueOf("google".equals(provider) ? "GEMINI" : provider.toUpperCase());
          if (!ModelAccessService.isDisabledProvider(provider)) available.add(parsed);
        } catch (IllegalArgumentException ignored) {
          // Unknown persisted provider stays unavailable.
        }
      }
      return Set.copyOf(available);
    } catch (RuntimeException invalidContext) {
      log.warn(
          "Provider availability failed closed for run {}: {}", runId, invalidContext.getMessage());
      return Set.of();
    }
  }

  /** Resolve providers from system-level keys only (used when no run context is available). */
  private boolean systemProviderPresent(String provider) {
    return switch (provider) {
      case "anthropic" -> isPresent(systemAnthropicKey);
      case "openai" -> isPresent(systemOpenAiKey);
      case "gemini", "google" -> isPresent(systemGeminiKey);
      case "openrouter" -> isPresent(systemOpenRouterKey);
      case "deepseek" -> isPresent(systemDeepSeekKey);
      case "fireworks" -> isPresent(systemFireworksKey);
      default -> false;
    };
  }

  private static boolean isPresent(String key) {
    return key != null && !key.isBlank();
  }
}
