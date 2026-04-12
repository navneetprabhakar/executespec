package com.navneet.executespec.v2.test;

import com.navneet.executespec.v2.rollout.HarnessVersion;
import com.navneet.executespec.v2.rollout.HarnessVersionResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V2 Phase 9 verification endpoint.
 *
 * <p>Reports the currently-effective {@link HarnessVersionResolver} state + runs a
 * configurable number of {@code resolve()} calls to confirm the canary split matches
 * the configured percentage (within statistical tolerance).
 *
 * <p>Usage: {@code GET /api/v2/test/rollout?samples=1000}
 */
@RestController
@RequestMapping("/api/v2/test")
@RequiredArgsConstructor
public class RolloutTestController {

    private final HarnessVersionResolver resolver;

    @GetMapping("/rollout")
    public Map<String, Object> testRollout(
            @RequestParam(defaultValue = "1000") int samples) {
        // Take a snapshot of the resolver's current view of the feature flags.
        String flag = resolver.currentFlagValue();
        int canaryPercent = resolver.currentCanaryPercent();

        // Monte-Carlo: roll resolve() N times, count V1 vs V2.
        int n = Math.max(1, Math.min(samples, 100_000));
        EnumMap<HarnessVersion, Integer> counts = new EnumMap<>(HarnessVersion.class);
        counts.put(HarnessVersion.V1, 0);
        counts.put(HarnessVersion.V2, 0);
        for (int i = 0; i < n; i++) {
            HarnessVersion v = resolver.resolve();
            counts.merge(v, 1, Integer::sum);
        }
        int v2Count = counts.get(HarnessVersion.V2);
        double observedV2Percent = 100.0 * v2Count / n;

        // Sanity: if flag is "v2-canary", the observed percent should be within ±5pp of
        // the configured canary percent. If flag is "v1" or "v2", observed should be 0 or 100.
        boolean sane;
        switch (flag == null ? "v1" : flag.toLowerCase()) {
            case "v2" -> sane = v2Count == n;
            case "v1" -> sane = v2Count == 0;
            case "v2-canary" -> sane = Math.abs(observedV2Percent - canaryPercent) <= 5.0;
            default -> sane = v2Count == 0;
        }

        // Also verify the deterministic resolveForSeed is stable — same seed produces
        // same result across calls.
        HarnessVersion seed1 = resolver.resolveForSeed("stable-seed");
        HarnessVersion seed2 = resolver.resolveForSeed("stable-seed");
        boolean seedDeterministic = seed1 == seed2;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currentFlagValue", flag);
        out.put("currentCanaryPercent", canaryPercent);
        out.put("samples", n);
        out.put("v1Count", counts.get(HarnessVersion.V1));
        out.put("v2Count", v2Count);
        out.put("observedV2Percent", Math.round(observedV2Percent * 100.0) / 100.0);
        out.put("expectedWithin5pp", sane);
        out.put("seedDeterministic", seedDeterministic);
        out.put("resolveForSeedResult", seed1.name());
        out.put("ok", sane && seedDeterministic);
        out.put("note", "Configure via FeatureFlagService: harness.version = v1|v2|v2-canary, "
                + "harness.v2.canary_percent = 0..100 (only used when flag is v2-canary).");
        return out;
    }
}
