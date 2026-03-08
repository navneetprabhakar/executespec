package com.navneet.agentteam.router;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks LLM provider health based on recent call outcomes.
 * A provider is marked "unhealthy" after UNHEALTHY_THRESHOLD consecutive failures.
 * Auto-recovers after RECOVERY_WINDOW to allow retrying.
 */
@Slf4j
@Component
public class ProviderHealthTracker {

    private static final int UNHEALTHY_THRESHOLD = 3;
    private static final Duration RECOVERY_WINDOW = Duration.ofMinutes(5);

    private final Map<LlmProvider, ProviderStatus> statusMap = new ConcurrentHashMap<>();

    public boolean isHealthy(LlmProvider provider) {
        ProviderStatus status = statusMap.get(provider);
        if (status == null) return true; // assume healthy if no data

        // Auto-recover after window
        if (!status.isHealthy() && Duration.between(status.getLastUpdate(), Instant.now()).compareTo(RECOVERY_WINDOW) > 0) {
            log.info("Provider {} auto-recovered after {}s cooldown", provider, RECOVERY_WINDOW.toSeconds());
            statusMap.put(provider, new ProviderStatus(true, 0, Instant.now()));
            return true;
        }

        return status.isHealthy();
    }

    public void recordSuccess(LlmProvider provider) {
        statusMap.put(provider, new ProviderStatus(true, 0, Instant.now()));
    }

    public void recordFailure(LlmProvider provider) {
        ProviderStatus current = statusMap.getOrDefault(provider, new ProviderStatus(true, 0, Instant.now()));
        int newCount = current.getConsecutiveFailures() + 1;
        boolean stillHealthy = newCount < UNHEALTHY_THRESHOLD;
        if (!stillHealthy) {
            log.warn("Provider {} marked UNHEALTHY after {} consecutive failures", provider, newCount);
        }
        statusMap.put(provider, new ProviderStatus(stillHealthy, newCount, Instant.now()));
    }

    @Data
    @AllArgsConstructor
    private static class ProviderStatus {
        private boolean healthy;
        private int consecutiveFailures;
        private Instant lastUpdate;
    }
}
