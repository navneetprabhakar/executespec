package com.navneet.executespec.harness;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Tracks actual tool-call iterations per completed task, segmented by provider + complexity.
 *
 * <p>Records a rolling window of iteration counts in Redis (last 100 samples per
 * provider+complexity bucket). {@link #getAdaptiveCap(String, String, int, int)} computes
 * the P95 of historical iterations, adds a 20% buffer, and clamps it between the floor
 * and the hard maximum.
 *
 * <p>Provider segmentation is critical because Gemini models typically consume 2-3x more
 * iterations than Anthropic or OpenAI for the same complexity due to batch tool-call
 * capping and higher truncation rates.
 *
 * <p>Cold-start (fewer than {@link #MIN_SAMPLES} samples) returns per-complexity defaults
 * instead of the hard maximum, preventing over-allocation on fresh deploys.
 *
 * <h3>Redis key design</h3>
 * <pre>
 *   adaptive:iterations:{ANTHROPIC|OPENAI|GOOGLE|UNKNOWN}:{LOW|MEDIUM|HIGH}
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdaptiveIterationService {

    private static final String KEY_PREFIX      = "adaptive:iterations:";
    private static final int    MAX_SAMPLES     = 100;
    private static final int    MIN_SAMPLES     = 10;   // min samples before trusting P95
    private static final double P95_BUFFER      = 1.20; // 20% buffer above P95
    private static final Duration LIST_TTL      = Duration.ofDays(30);

    /**
     * Per-complexity cold-start defaults used when fewer than MIN_SAMPLES exist for a
     * provider+complexity bucket. More conservative than hardMax to avoid over-allocation
     * on fresh deploys; generous enough that real tasks don't hit the cap prematurely.
     */
    private static int coldStartDefault(String complexity) {
        return switch (complexity != null ? complexity.toUpperCase() : "MEDIUM") {
            case "LOW"  -> 20;
            case "HIGH" -> 32;
            default     -> 28; // MEDIUM
        };
    }

    private final StringRedisTemplate redis;

    /**
     * Records the actual iteration count for a completed task, segmented by provider+complexity.
     *
     * @param provider       LLM provider (ANTHROPIC / OPENAI / GOOGLE)
     * @param complexity     task complexity (LOW / MEDIUM / HIGH)
     * @param iterationCount actual tool-call iterations used
     */
    public void recordTaskCompletion(String provider, String complexity, int iterationCount) {
        if (iterationCount <= 0) return;
        String key = key(provider, complexity);
        try {
            ListOperations<String, String> ops = redis.opsForList();
            ops.rightPush(key, String.valueOf(iterationCount));
            // Trim to last MAX_SAMPLES, discard oldest
            ops.trim(key, -MAX_SAMPLES, -1);
            redis.expire(key, LIST_TTL);
            log.debug("[AdaptiveIter] Recorded {} iterations for provider={} complexity={}", iterationCount, provider, complexity);
        } catch (Exception e) {
            log.debug("[AdaptiveIter] Redis error recording iteration data — skipping", e);
        }
    }

    /**
     * Returns an adaptive iteration cap based on historical P95 for the given provider+complexity.
     *
     * <p>Falls back to provider-agnostic complexity bucket if the provider-specific bucket
     * has insufficient data. Uses per-complexity cold-start defaults (not hardMax) when
     * no data exists at all, to avoid over-allocating on fresh deploys.
     *
     * @param provider   LLM provider (ANTHROPIC / OPENAI / GOOGLE)
     * @param complexity task complexity label (LOW / MEDIUM / HIGH)
     * @param floor      minimum cap to return
     * @param hardMax    maximum cap to return (ceiling)
     */
    public int getAdaptiveCap(String provider, String complexity, int floor, int hardMax) {
        // Try provider-specific bucket first, fall back to provider-agnostic
        String specificKey = key(provider, complexity);
        String genericKey  = key(null, complexity);
        int cold = Math.min(hardMax, coldStartDefault(complexity));

        try {
            List<String> values = redis.opsForList().range(specificKey, 0, -1);
            if (values == null || values.size() < MIN_SAMPLES) {
                // Try generic (provider-agnostic) bucket as fallback
                values = redis.opsForList().range(genericKey, 0, -1);
            }
            if (values == null || values.size() < MIN_SAMPLES) {
                log.debug("[AdaptiveIter] Insufficient data for provider={} complexity={} ({} samples) — using cold default={}",
                        provider, complexity, values == null ? 0 : values.size(), cold);
                return Math.max(floor, cold);
            }

            int[] sorted = values.stream()
                    .mapToInt(v -> { try { return Integer.parseInt(v); } catch (NumberFormatException e) { return 0; } })
                    .filter(v -> v > 0)
                    .sorted()
                    .toArray();

            if (sorted.length < MIN_SAMPLES) return Math.max(floor, cold);

            int p95Index  = (int) Math.ceil(sorted.length * 0.95) - 1;
            int p95Value  = sorted[Math.min(p95Index, sorted.length - 1)];
            int adaptive  = (int) Math.ceil(p95Value * P95_BUFFER);
            int clamped   = Math.max(floor, Math.min(hardMax, adaptive));

            log.debug("[AdaptiveIter] provider={} complexity={} samples={} P95={} adaptive={} clamped={}",
                    provider, complexity, sorted.length, p95Value, adaptive, clamped);
            return clamped;
        } catch (Exception e) {
            log.debug("[AdaptiveIter] Redis error computing adaptive cap — using cold default={}", cold, e);
            return Math.max(floor, cold);
        }
    }

    private static String key(String provider, String complexity) {
        String prov = provider != null ? provider.toUpperCase() : "UNKNOWN";
        if (!prov.equals("ANTHROPIC") && !prov.equals("OPENAI") && !prov.equals("GOOGLE")) prov = "UNKNOWN";
        String comp = complexity != null ? complexity.toUpperCase() : "MEDIUM";
        if (!comp.equals("LOW") && !comp.equals("HIGH")) comp = "MEDIUM";
        return KEY_PREFIX + prov + ":" + comp;
    }
}
