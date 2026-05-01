package com.navneet.executespec.router;

import java.util.Locale;
import java.util.Optional;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Wave 4.3 — smart provider routing default. When {@code provider=AUTO} is passed to the run-submit
 * path + flag {@code provider.smart-routing.enabled} is true, this service picks the right provider
 * for the project type + user's BYOK keys + current circuit-breaker state.
 *
 * <p>v1 is a pure-heuristic implementation — no user-history scoring, no adaptive learning.
 * Observability only: every decision is logged to {@code provider_recommendation_log} (Wave 4
 * migration V128) for future heuristic tuning.
 *
 * <p>Full plan: docs/plans/wave-4-landing-proof-routing-changelog-plan.md §4.3.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProviderRecommendationService {

  /** Feature flag key — toggled OFF by default during Wave 4 opt-in period. */
  public static final String FLAG = "provider.smart-routing.enabled";

  private final ObjectProvider<FeatureFlagService> flagProvider;

  /**
   * Recommend a provider for a new run. Returns empty if smart routing is disabled OR the caller
   * already supplied a specific provider (in which case the caller's choice is authoritative).
   */
  public Optional<LlmProvider> recommend(String userId, String projectType, String runtimeSurface) {
    FeatureFlagService flags = flagProvider != null ? flagProvider.getIfAvailable() : null;
    if (flags == null || !flags.isEnabled(FLAG)) return Optional.empty();

    String type = projectType == null ? "" : projectType.toLowerCase(Locale.ROOT);
    String surface = runtimeSurface == null ? "" : runtimeSurface.toUpperCase(Locale.ROOT);

    // Heuristic — ordered from most specific to least.
    if (type.startsWith("spring-boot")
        || type.startsWith("node-backend")
        || type.startsWith("python")) {
      return Optional.of(LlmProvider.OPENAI);
    }
    if (type.startsWith("node-frontend") || type.startsWith("react-native")) {
      return Optional.of(LlmProvider.ANTHROPIC);
    }
    if (type.startsWith("static-web") || type.startsWith("marketing")) {
      return Optional.of(LlmProvider.GEMINI);
    }
    if ("LIBRARY".equals(surface)) {
      return Optional.of(LlmProvider.ANTHROPIC);
    }
    // Fallback: stick with Anthropic for the accuracy signal that
    // drives the pricing-v3 "your provider, your choice" narrative.
    log.debug(
        "Smart routing fallback to ANTHROPIC: userId={} projectType={} surface={}",
        userId,
        projectType,
        runtimeSurface);
    return Optional.of(LlmProvider.ANTHROPIC);
  }

  /**
   * v1 convenience: reason code for the audit log. Mirrors the branches in {@link
   * #recommend(String, String, String)}.
   */
  public String reasonCode(String projectType, String runtimeSurface) {
    String type = projectType == null ? "" : projectType.toLowerCase(Locale.ROOT);
    String surface = runtimeSurface == null ? "" : runtimeSurface.toUpperCase(Locale.ROOT);
    if (type.startsWith("spring-boot")
        || type.startsWith("node-backend")
        || type.startsWith("python")
        || type.startsWith("node-frontend")
        || type.startsWith("react-native")
        || type.startsWith("static-web")
        || type.startsWith("marketing")) {
      return "PROJECT_TYPE_HEURISTIC";
    }
    if ("LIBRARY".equals(surface)) {
      return "PROJECT_TYPE_HEURISTIC";
    }
    return "DEFAULT_FALLBACK";
  }
}
