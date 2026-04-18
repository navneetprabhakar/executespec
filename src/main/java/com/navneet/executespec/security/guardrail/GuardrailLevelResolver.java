package com.navneet.executespec.security.guardrail;

import com.navneet.executespec.config.featureflag.FeatureFlagService;
import lombok.RequiredArgsConstructor;
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
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GuardrailLevelResolver {

    /** Feature-flag key; matches the V116 seed row. */
    public static final String FLAG_KEY = "security.guardrail_level";

    private final FeatureFlagService featureFlagService;

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
}
