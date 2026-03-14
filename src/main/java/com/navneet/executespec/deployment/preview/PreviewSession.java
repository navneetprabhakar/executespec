package com.navneet.executespec.deployment.preview;

import java.time.Instant;

public record PreviewSession(
        String runId,
        String composeProjectName,
        PreviewProjectType projectType,
        DatabaseType databaseType,
        PreviewStatus status,
        int hostPort,
        String url,
        String errorMessage,
        Instant startedAt,
        Instant expiresAt
) {
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
