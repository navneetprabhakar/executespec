package com.navneet.executespec.security.guardrail;

import com.navneet.executespec.config.featureflag.FeatureFlagService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Resolves the current {@link GuardrailLevel} from the {@code
 * security.guardrail_level} feature flag. Consumers constructor-inject this
 * service and call {@link #current()} once per request path.
 *
 * <p>The underlying {@link FeatureFlagService} already handles Redis pub/sub
 * cache invalidation plus a 60s scheduled reload, so callers don't need their
 * own caching layer.
 *
 * <p>Fail-open: any exception reading the flag (Redis hiccup, missing row,
 * unparseable value) resolves to {@link GuardrailLevel#LOW} so a runtime
 * error can never lock users out. See open question #3 in the execution plan.
 *
 * <p>Exposes {@link #recordBlock(String)} so every guardrail-driven rejection
 * across the codebase can be counted in a single Prometheus series keyed by
 * layer (input / code_abuse / shell / file) and resolved level.
 */
@Service
@Slf4j
public class GuardrailLevelResolver {

    /** Feature-flag key; matches the V116 seed row. */
    public static final String FLAG_KEY = "security.guardrail_level";

    private final FeatureFlagService featureFlagService;
    private final MeterRegistry meterRegistry;

    public GuardrailLevelResolver(FeatureFlagService featureFlagService, MeterRegistry meterRegistry) {
        this.featureFlagService = featureFlagService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Current guardrail level. Fail-open on any resolution error.
     */
    public GuardrailLevel current() {
        try {
            String value = featureFlagService.getString(FLAG_KEY, "LOW");
            return GuardrailLevel.parse(value);
        } catch (Exception e) {
            log.debug("[GuardrailLevelResolver] falling back to LOW — flag read failed: {}", e.getMessage());
            return GuardrailLevel.LOW;
        }
    }

    /**
     * Record a block emitted by one of the guardrail-aware policies. Single
     * counter series {@code executespec.security.guardrail.blocked} tagged by
     * {@code layer} (input / code_abuse / shell / file / ratelimit) and
     * {@code level} (LOW / MEDIUM / HIGH). Callers should invoke this from
     * the rejection path before throwing so operators can see the flag-flip
     * impact in Grafana without reading logs.
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
