package com.navneet.executespec.integration.slack;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Per-workspace (team_id) rate limiter for Slack's chat.postMessage API. Slack enforces ~1 msg/sec
 * per workspace (Tier 2).
 *
 * <p>Uses simple timestamp tracking — if last send for this workspace was &lt; 1100ms ago, the
 * caller sleeps for the remaining interval before proceeding.
 */
@Component
@Slf4j
public class SlackRateLimiter {

  private static final long MIN_INTERVAL_MS = 1100; // slightly over 1s to stay safe
  private final ConcurrentHashMap<String, Long> lastSendTimestamps = new ConcurrentHashMap<>();

  /**
   * Blocks until it's safe to send a message for the given workspace. Returns immediately if enough
   * time has elapsed since the last send.
   *
   * <p>RR-686 — the reservation is atomic. The old read-then-write let concurrent callers for one
   * team observe the same last-send timestamp and send together, exceeding Slack's per-workspace
   * limit exactly under load. {@link java.util.concurrent.ConcurrentHashMap#merge} now assigns each
   * caller a strictly increasing, non-overlapping send slot in one atomic step; the caller then
   * sleeps until its reserved slot outside the map operation.
   *
   * <p>Interruption ABORTS the send (the reserved slot is already committed, which only pushes
   * later sends slightly later — safe) rather than silently recording a permit and proceeding.
   *
   * <p>Still single-JVM: each application instance keeps an independent map, so multi-instance
   * throttling requires a shared (Redis) limiter — tracked as the remaining half of RR-686.
   */
  public void acquire(String teamId) {
    long now = System.currentTimeMillis();
    long scheduled =
        lastSendTimestamps.merge(
            teamId, now, (prevSlot, n) -> Math.max(n, prevSlot + MIN_INTERVAL_MS));
    long waitMs = scheduled - now;
    if (waitMs > 0) {
      try {
        log.debug("[Slack] Rate limiting for team {}: sleeping {}ms", teamId, waitMs);
        Thread.sleep(waitMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Slack rate-limit wait interrupted for team " + teamId, e);
      }
    }
  }

  /** Non-blocking check — returns true if a message can be sent immediately. */
  public boolean tryAcquire(String teamId) {
    long now = System.currentTimeMillis();
    // RR-686 — atomic claim under ConcurrentHashMap.compute: exactly one concurrent caller for a
    // team can observe an expired slot and advance it. The boolean holder distinguishes "I claimed"
    // from "the stored slot happens to equal now", which a merge-return comparison could not.
    boolean[] claimed = {false};
    lastSendTimestamps.compute(
        teamId,
        (k, prevSlot) -> {
          if (prevSlot == null || (now - prevSlot) >= MIN_INTERVAL_MS) {
            claimed[0] = true;
            return now;
          }
          return prevSlot;
        });
    return claimed[0];
  }
}
