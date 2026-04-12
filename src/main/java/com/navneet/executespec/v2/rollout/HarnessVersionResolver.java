package com.navneet.executespec.v2.rollout;

import com.navneet.executespec.config.featureflag.FeatureFlagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Resolves which harness version a given run should use, based on feature flags.
 *
 * <h2>Feature flags consulted</h2>
 * <ul>
 *   <li><b>{@value #FLAG_HARNESS_VERSION}</b> (string) — one of:
 *       <ul>
 *         <li>{@code "v1"} (default): always V1</li>
 *         <li>{@code "v2"}: always V2</li>
 *         <li>{@code "v2-canary"}: route a percentage of runs to V2, the rest to V1</li>
 *       </ul>
 *   </li>
 *   <li><b>{@value #FLAG_V2_CANARY_PERCENT}</b> (int 0..100) — the canary percentage.
 *       Only consulted when {@value #FLAG_HARNESS_VERSION} is {@code "v2-canary"}.
 *       Out-of-range values are clamped to [0, 100]. Default 0.</li>
 * </ul>
 *
 * <h2>Resolution rules</h2>
 * <ol>
 *   <li>Unknown {@code harness.version} values fall back to V1 (fail-safe).</li>
 *   <li>Canary roll uses {@link ThreadLocalRandom} — per-request independence. Future
 *       enhancement: make it deterministic per-{@code runId} so a retry lands on the
 *       same harness as the original attempt.</li>
 * </ol>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HarnessVersionResolver {

    public static final String FLAG_HARNESS_VERSION = "harness.version";
    public static final String FLAG_V2_CANARY_PERCENT = "harness.v2.canary_percent";

    private final FeatureFlagService featureFlagService;

    /**
     * Decide which harness version to use for a new run.
     *
     * @return {@link HarnessVersion#V1} or {@link HarnessVersion#V2}
     */
    public HarnessVersion resolve() {
        String flag = featureFlagService.getString(FLAG_HARNESS_VERSION, "v1");
        return switch (flag.toLowerCase()) {
            case "v2" -> {
                log.debug("HarnessVersionResolver: flag=v2 → V2");
                yield HarnessVersion.V2;
            }
            case "v2-canary" -> rollCanary();
            case "v1" -> HarnessVersion.V1;
            default -> {
                log.warn("HarnessVersionResolver: unknown flag value '{}', defaulting to V1", flag);
                yield HarnessVersion.V1;
            }
        };
    }

    /**
     * Deterministically resolve based on a caller-supplied seed (runId / specHash / etc.).
     *
     * <p>Useful when a retry must land on the same harness as the original attempt —
     * pass the original runId (or any stable identifier) as the seed and the same roll
     * produces the same outcome.
     */
    public HarnessVersion resolveForSeed(String seed) {
        String flag = featureFlagService.getString(FLAG_HARNESS_VERSION, "v1");
        return switch (flag.toLowerCase()) {
            case "v2" -> HarnessVersion.V2;
            case "v2-canary" -> {
                int percent = clampPercent(featureFlagService.getInt(FLAG_V2_CANARY_PERCENT, 0));
                int bucket = Math.floorMod(seed == null ? 0 : seed.hashCode(), 100);
                HarnessVersion chosen = bucket < percent ? HarnessVersion.V2 : HarnessVersion.V1;
                log.debug("HarnessVersionResolver: canary seed='{}' bucket={} percent={} → {}",
                        seed, bucket, percent, chosen);
                yield chosen;
            }
            case "v1" -> HarnessVersion.V1;
            default -> HarnessVersion.V1;
        };
    }

    /** Read-only view of the currently configured canary percentage (for admin UI / observability). */
    public int currentCanaryPercent() {
        return clampPercent(featureFlagService.getInt(FLAG_V2_CANARY_PERCENT, 0));
    }

    /** Read-only view of the currently configured harness.version flag value. */
    public String currentFlagValue() {
        return featureFlagService.getString(FLAG_HARNESS_VERSION, "v1");
    }

    // ─── internals ──────────────────────────────────────────────────────────

    private HarnessVersion rollCanary() {
        int percent = clampPercent(featureFlagService.getInt(FLAG_V2_CANARY_PERCENT, 0));
        if (percent <= 0) return HarnessVersion.V1;
        if (percent >= 100) return HarnessVersion.V2;
        int roll = ThreadLocalRandom.current().nextInt(100);
        HarnessVersion chosen = roll < percent ? HarnessVersion.V2 : HarnessVersion.V1;
        log.debug("HarnessVersionResolver: canary roll={} percent={} → {}", roll, percent, chosen);
        return chosen;
    }

    private static int clampPercent(int p) {
        if (p < 0) return 0;
        if (p > 100) return 100;
        return p;
    }
}
