package com.navneet.executespec.deployment.preview;

import java.time.Instant;

public record PreviewSession(
        String runId,
        String containerId,
        String containerName,
        PreviewProjectType projectType,
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
