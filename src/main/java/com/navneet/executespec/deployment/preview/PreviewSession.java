package com.navneet.executespec.deployment.preview;

import java.time.Duration;
import java.time.Instant;

public record PreviewSession(
        String runId,
        String composeProjectName,   // docker compose --project-name value
        PreviewProjectType projectType,
        PreviewMode previewMode,     // WEB, CLI_TERMINAL, or MCP_INSPECTOR
        DatabaseType databaseType,
        PreviewStatus status,
        String url,                  // https://preview-{short-id}.executespec.ai
        int hostPort,                // allocated host port (for cleanup)
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
