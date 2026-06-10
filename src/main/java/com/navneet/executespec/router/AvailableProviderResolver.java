package com.navneet.executespec.router;

import java.util.EnumSet;
import java.util.Set;

import com.navneet.executespec.agent.ResolvedKeys;
import com.navneet.executespec.agent.RunKeyStore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves which LLM providers are available for a given run. Bridges the existing {@link
 * ResolvedKeys} / {@link RunKeyStore} system with the {@link ModelResolver}'s provider-based model
 * resolution.
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

  private final RunKeyStore runKeyStore;

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
   * Determine which providers are available for a given run. Uses the already-resolved keys from
   * {@link RunKeyStore}.
   *
   * @param runId the pipeline run ID
   * @return set of available providers (never empty — falls back to system keys)
   */
  public Set<LlmProvider> resolve(String runId) {
    ResolvedKeys keys = runKeyStore.get(runId);
    if (keys == null) {
      // No resolved keys yet (e.g. system-generated run) — use system keys
      return resolveSystemProviders();
    }

    Set<LlmProvider> available = EnumSet.noneOf(LlmProvider.class);
    String keySourceMode = keys.keySourceMode() != null ? keys.keySourceMode() : "HYBRID";

    if ("SYSTEM".equals(keySourceMode) || "HYBRID".equals(keySourceMode)) {
      // System keys available
      if (isPresent(systemAnthropicKey)) available.add(LlmProvider.ANTHROPIC);
      if (isPresent(systemOpenAiKey)) available.add(LlmProvider.OPENAI);
      if (isPresent(systemGeminiKey)) available.add(LlmProvider.GEMINI);
      if (isPresent(systemOpenRouterKey)) available.add(LlmProvider.OPENROUTER);
      if (isPresent(systemDeepSeekKey)) available.add(LlmProvider.DEEPSEEK);
      if (isPresent(systemFireworksKey)) available.add(LlmProvider.FIREWORKS);
    }

    if ("OWN_KEYS".equals(keySourceMode) || "HYBRID".equals(keySourceMode)) {
      // User/org keys
      if (keys.anthropicKey() != null) available.add(LlmProvider.ANTHROPIC);
      if (keys.openAiKey() != null) available.add(LlmProvider.OPENAI);
      if (keys.geminiKey() != null) available.add(LlmProvider.GEMINI);
      // OpenRouter (Enterprise gateway) — offered whenever the org key is present. Only Enterprise
      // org admins can set the key (gated at OrgController), so key-present implies Enterprise.
      if (keys.openRouterKey() != null) available.add(LlmProvider.OPENROUTER);
      if (keys.deepSeekKey() != null) available.add(LlmProvider.DEEPSEEK);
      if (keys.fireworksKey() != null) available.add(LlmProvider.FIREWORKS);
    }

    // Apply org-level enabled provider policy
    if (keys.enabledProviders() != null && !keys.enabledProviders().isEmpty()) {
      available.removeIf(p -> !keys.isProviderAllowed(providerToString(p)));
    }

    // Fallback: if nothing available, include all system providers
    if (available.isEmpty()) {
      log.warn(
          "No providers available for run {} after policy filtering — falling back to system keys",
          runId);
      return resolveSystemProviders();
    }

    log.debug("Available providers for run {}: {} (mode={})", runId, available, keySourceMode);
    return available;
  }

  /** Resolve providers from system-level keys only (used when no run context is available). */
  private Set<LlmProvider> resolveSystemProviders() {
    Set<LlmProvider> providers = EnumSet.noneOf(LlmProvider.class);
    if (isPresent(systemAnthropicKey)) providers.add(LlmProvider.ANTHROPIC);
    if (isPresent(systemOpenAiKey)) providers.add(LlmProvider.OPENAI);
    if (isPresent(systemGeminiKey)) providers.add(LlmProvider.GEMINI);
    if (isPresent(systemOpenRouterKey)) providers.add(LlmProvider.OPENROUTER);
    if (isPresent(systemDeepSeekKey)) providers.add(LlmProvider.DEEPSEEK);
    if (isPresent(systemFireworksKey)) providers.add(LlmProvider.FIREWORKS);

    if (providers.isEmpty()) {
      // Last resort: assume Anthropic is available (system default)
      providers.add(LlmProvider.ANTHROPIC);
    }
    return providers;
  }

  private static String providerToString(LlmProvider provider) {
    return switch (provider) {
      case ANTHROPIC -> "anthropic";
      case OPENAI -> "openai";
      case GEMINI -> "gemini";
      case OPENROUTER -> "openrouter";
      case DEEPSEEK -> "deepseek";
      case FIREWORKS -> "fireworks";
    };
  }

  private static boolean isPresent(String key) {
    return key != null && !key.isBlank();
  }
}
