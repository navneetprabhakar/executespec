package com.navneet.executespec.util;

/**
 * Tiny helper for sizing per-service dispatch pools relative to host CPU count.
 *
 * <p>All our service-level executor pools (CodeReviewService, PrReviewService, RunService,
 * RunConversationController, RunOrchestrator, etc.) handle I/O-bound workloads — LLM API calls
 * dominate, with some git clone + DB writes mixed in. For these the textbook sizing is roughly
 * {@code cores × (1 + waitTime/computeTime)}; in practice we cap to a tidy multiple of cores with a
 * sensible minimum so a 2-core CI VM still has parallelism and a 16-core dev box doesn't spin up 64
 * idle threads.
 *
 * <p>Each pool's call site picks its own {@code multiplier}, {@code min}, and {@code max} based on
 * what's appropriate (a chat-apply pool wants more concurrency than a per-PR review pool because
 * applies are bursty; reviews are queued one-per-PR).
 *
 * <p>The choice is made at JVM startup against {@code Runtime.getRuntime().availableProcessors()};
 * pools are not resized dynamically.
 */
public final class PoolSizing {

  private PoolSizing() {}

  /**
   * Returns {@code clamp(min, max, cores × multiplier)}.
   *
   * <p>Use this at executor construction:
   *
   * <pre>{@code
   * Executors.newFixedThreadPool(PoolSizing.coreMultiple(2, 4, 16), threadFactory)
   * }</pre>
   *
   * @param multiplier how many threads per core. Common values: 1 for moderately parallel I/O-bound
   *     work, 2 for high-concurrency dispatch, 4 for very-bursty apply paths.
   * @param min lower bound — pools below this size lose useful parallelism on small hosts.
   * @param max upper bound — guards against thread explosion on very-large dev/CI boxes.
   */
  public static int coreMultiple(int multiplier, int min, int max) {
    if (multiplier <= 0) throw new IllegalArgumentException("multiplier must be positive");
    if (min < 1) throw new IllegalArgumentException("min must be >= 1");
    if (max < min) throw new IllegalArgumentException("max must be >= min");
    int cores = Runtime.getRuntime().availableProcessors();
    int sized = cores * multiplier;
    if (sized < min) return min;
    if (sized > max) return max;
    return sized;
  }
}
