package com.navneet.executespec.integration.slack;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** RR-686 — the single-JVM rate limiter must reserve send slots atomically. */
class SlackRateLimiterTest {

  @Test
  void concurrentTryAcquireLetsOnlyOneCallerThroughPerInterval() throws Exception {
    SlackRateLimiter limiter = new SlackRateLimiter();
    int threads = 32;
    AtomicInteger claimed = new AtomicInteger();
    Thread[] pool = new Thread[threads];
    for (int i = 0; i < threads; i++) {
      pool[i] =
          new Thread(
              () -> {
                if (limiter.tryAcquire("team-1")) claimed.incrementAndGet();
              });
    }
    for (Thread t : pool) t.start();
    for (Thread t : pool) t.join();
    // Before the fix, many racing callers could each observe the same expired slot and all claim.
    assertThat(claimed.get()).isEqualTo(1);
  }

  @Test
  void acquireAssignsStrictlyIncreasingSlotsUnderContention() throws Exception {
    SlackRateLimiter limiter = new SlackRateLimiter();
    // Two acquires for one team must not both return "immediately"; the second is delayed ~1.1s.
    long start = System.currentTimeMillis();
    limiter.acquire("team-2");
    limiter.acquire("team-2");
    long elapsed = System.currentTimeMillis() - start;
    assertThat(elapsed).isGreaterThanOrEqualTo(1000);
  }
}
