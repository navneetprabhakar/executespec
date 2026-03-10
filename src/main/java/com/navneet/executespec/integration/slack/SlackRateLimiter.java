package com.navneet.executespec.integration.slack;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-workspace (team_id) rate limiter for Slack's chat.postMessage API.
 * Slack enforces ~1 msg/sec per workspace (Tier 2).
 * <p>
 * Uses simple timestamp tracking — if last send for this workspace was &lt; 1100ms ago,
 * the caller sleeps for the remaining interval before proceeding.
 */
@Component
@Slf4j
public class SlackRateLimiter {

    private static final long MIN_INTERVAL_MS = 1100; // slightly over 1s to stay safe
    private final ConcurrentHashMap<String, Long> lastSendTimestamps = new ConcurrentHashMap<>();

    /**
     * Blocks until it's safe to send a message for the given workspace.
     * Returns immediately if enough time has elapsed since the last send.
     */
    public void acquire(String teamId) {
        long now = System.currentTimeMillis();
        Long lastSend = lastSendTimestamps.get(teamId);
        if (lastSend != null) {
            long elapsed = now - lastSend;
            if (elapsed < MIN_INTERVAL_MS) {
                try {
                    long sleepMs = MIN_INTERVAL_MS - elapsed;
                    log.debug("[Slack] Rate limiting for team {}: sleeping {}ms", teamId, sleepMs);
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        lastSendTimestamps.put(teamId, System.currentTimeMillis());
    }

    /**
     * Non-blocking check — returns true if a message can be sent immediately.
     */
    public boolean tryAcquire(String teamId) {
        long now = System.currentTimeMillis();
        Long lastSend = lastSendTimestamps.get(teamId);
        if (lastSend == null || (now - lastSend) >= MIN_INTERVAL_MS) {
            lastSendTimestamps.put(teamId, now);
            return true;
        }
        return false;
    }
}
