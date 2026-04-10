package com.navneet.executespec.harness;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Tracks actual tool-call iterations per completed task, segmented by complexity.
 *
 * <p>Records a rolling window of iteration counts in Redis (last 100 samples per
 * complexity level). {@link #getAdaptiveCap(String, int, int)} computes the P95
 * of historical iterations, adds a 20% buffer, and clamps it between the subtask
 * minimum and the hard maximum. Returns the hard maximum when data is insufficient.
 *
 * <h3>Redis key design</h3>
 * <pre>
 *   adaptive:iterations:{LOW|MEDIUM|HIGH}  → list of iteration counts (capped at 100)
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

    private final StringRedisTemplate redis;

    /**
     * Records the actual iteration count for a completed task.
     *
     * @param complexity    task complexity (LOW / MEDIUM / HIGH)
     * @param iterationCount actual tool-call iterations used
     */
    public void recordTaskCompletion(String complexity, int iterationCount) {
        if (iterationCount <= 0) return;
        String key = key(complexity);
        try {
            ListOperations<String, String> ops = redis.opsForList();
            ops.rightPush(key, String.valueOf(iterationCount));
            // Trim to last MAX_SAMPLES, discard oldest
            ops.trim(key, -MAX_SAMPLES, -1);
            redis.expire(key, LIST_TTL);
            log.debug("[AdaptiveIter] Recorded {} iterations for complexity={}", iterationCount, complexity);
        } catch (Exception e) {
            log.debug("[AdaptiveIter] Redis error recording iteration data — skipping", e);
        }
    }

    /**
     * Returns an adaptive iteration cap based on historical P95 for the given complexity.
     *
     * <p>Returns {@code hardMax} when fewer than {@link #MIN_SAMPLES} data points exist
     * so the system falls back to the static constant.
     *
     * @param complexity task complexity label (LOW / MEDIUM / HIGH)
     * @param subtaskMin minimum cap to return (floor)
     * @param hardMax    maximum cap to return (ceiling, also used as fallback)
     */
    public int getAdaptiveCap(String complexity, int subtaskMin, int hardMax) {
        String key = key(complexity);
        try {
            List<String> values = redis.opsForList().range(key, 0, -1);
            if (values == null || values.size() < MIN_SAMPLES) {
                log.debug("[AdaptiveIter] Insufficient data for complexity={} ({} samples) — using hardMax={}",
                        complexity, values == null ? 0 : values.size(), hardMax);
                return hardMax;
            }

            int[] sorted = values.stream()
                    .mapToInt(v -> { try { return Integer.parseInt(v); } catch (NumberFormatException e) { return 0; } })
                    .filter(v -> v > 0)
                    .sorted()
                    .toArray();

            if (sorted.length < MIN_SAMPLES) return hardMax;

            int p95Index  = (int) Math.ceil(sorted.length * 0.95) - 1;
            int p95Value  = sorted[Math.min(p95Index, sorted.length - 1)];
            int adaptive  = (int) Math.ceil(p95Value * P95_BUFFER);
            int clamped   = Math.max(subtaskMin, Math.min(hardMax, adaptive));

            log.debug("[AdaptiveIter] complexity={} samples={} P95={} adaptive={} clamped={}",
                    complexity, sorted.length, p95Value, adaptive, clamped);
            return clamped;
        } catch (Exception e) {
            log.debug("[AdaptiveIter] Redis error computing adaptive cap — using hardMax={}", hardMax, e);
            return hardMax;
        }
    }

    private static String key(String complexity) {
        String normalized = complexity != null ? complexity.toUpperCase() : "MEDIUM";
        if (!normalized.equals("LOW") && !normalized.equals("HIGH")) normalized = "MEDIUM";
        return KEY_PREFIX + normalized;
    }
}
