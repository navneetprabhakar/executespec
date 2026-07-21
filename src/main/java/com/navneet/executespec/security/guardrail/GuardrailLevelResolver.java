package com.navneet.executespec.security.guardrail;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves the current {@link GuardrailLevel} from the {@code security.guardrail_level} feature
 * flag. Consumers constructor-inject this service and call {@link #current()} once per request
 * path.
 *
 * <p>The underlying {@link FeatureFlagService} already handles Redis pub/sub cache invalidation
 * plus a 60s scheduled reload, so callers don't need their own caching layer.
 *
 * <p>Fail-safe: a missing, unreadable, or invalid value resolves to {@link GuardrailLevel#HIGH} and
 * emits both a warning and a metric. Security posture must not silently degrade during a feature
 * flag/cache outage.
 *
 * <p>Exposes {@link #recordBlock(String)} so every guardrail-driven rejection across the codebase
 * can be counted in a single Prometheus series keyed by layer (input / code_abuse / shell / file)
 * and resolved level.
 */
@Service
@Slf4j
public class GuardrailLevelResolver {

  /** Feature-flag key; matches the V116 seed row. */
  public static final String FLAG_KEY = "security.guardrail_level";

  private final FeatureFlagService featureFlagService;
  private final MeterRegistry meterRegistry;

  public GuardrailLevelResolver(
      FeatureFlagService featureFlagService, MeterRegistry meterRegistry) {
    this.featureFlagService = featureFlagService;
    this.meterRegistry = meterRegistry;
  }

  /** Current guardrail level. Fail-safe to HIGH on any resolution error. */
  public GuardrailLevel current() {
    try {
      String value = featureFlagService.getString(FLAG_KEY, null);
      if (value == null || value.isBlank()) {
        return resolutionFailure("missing flag value", null);
      }
      try {
        return GuardrailLevel.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
      } catch (IllegalArgumentException invalid) {
        return resolutionFailure("invalid flag value", invalid);
      }
    } catch (Exception e) {
      return resolutionFailure("flag read failed", e);
    }
  }

  private GuardrailLevel resolutionFailure(String reason, Exception error) {
    log.warn(
        "[GuardrailLevelResolver] {} — failing safe to HIGH{}",
        reason,
        error == null ? "" : " (" + error.getClass().getSimpleName() + ")");
    Counter.builder("executespec.security.guardrail.resolution_failures")
        .description("Guardrail-level resolution failures that triggered HIGH fail-safe posture")
        .tag("reason", reason.replace(' ', '_'))
        .register(meterRegistry)
        .increment();
    return GuardrailLevel.HIGH;
  }

  /**
   * Record a block emitted by one of the guardrail-aware policies. Single counter series {@code
   * executespec.security.guardrail.blocked} tagged by {@code layer} (input / code_abuse / shell /
   * file / ratelimit) and {@code level} (LOW / MEDIUM / HIGH). Callers should invoke this from the
   * rejection path before throwing so operators can see the flag-flip impact in Grafana without
   * reading logs.
   */
  public void recordBlock(String layer) {
    GuardrailLevel lvl = current();
    Counter.builder("executespec.security.guardrail.blocked")
        .description("Requests / tool calls blocked by a guardrail-aware policy")
        .tag("layer", layer == null ? "unknown" : layer)
        .tag("level", lvl.name())
        .register(meterRegistry)
        .increment();
  }
}
