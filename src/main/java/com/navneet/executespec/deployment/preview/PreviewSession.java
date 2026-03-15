package com.navneet.executespec.deployment.preview;

import java.time.Duration;
import java.time.Instant;

public record PreviewSession(
        String runId,
        String cloudRunServiceName,
        PreviewProjectType projectType,
        DatabaseType databaseType,
        PreviewStatus status,
        String url,                  // Cloud Run *.run.app URL
        String imageUri,             // AR image URI (for cleanup)
        String errorMessage,
        Instant startedAt,
        Instant expiresAt
) {
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /** Seconds remaining until auto-shutdown. Returns 0 if expired. */
    public long ttlRemainingSeconds() {
        if (expiresAt == null) return 0;
        long remaining = Duration.between(Instant.now(), expiresAt).toSeconds();
        return Math.max(0, remaining);
    }
}
