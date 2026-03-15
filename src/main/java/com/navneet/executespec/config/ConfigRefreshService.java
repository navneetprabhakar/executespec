package com.navneet.executespec.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Orchestrates runtime config refresh from Secret Manager + GCS.
 *
 * <p>Call {@link #refresh()} to:
 * <ol>
 *   <li>Reload secrets from GCP Secret Manager</li>
 *   <li>Reload non-secret config from GCS JSON</li>
 * </ol>
 *
 * <p>Note: System properties set here are picked up by Spring on next
 * {@code @Value} resolution. For {@code @ConfigurationProperties} beans,
 * values are static after startup — a restart is needed for those to change.
 * The runtime config values (via GCS JSON) are read via System.setProperty
 * and are available immediately to any code using {@code System.getProperty()}.
 *
 * <p>Exposed via {@code POST /api/admin/config/refresh} (SUPER_ADMIN only).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigRefreshService {

    private final GcsRuntimeConfig gcsRuntimeConfig;

    @Value("${GCP_PROJECT:}")
    private String gcpProject;

    // Not injected — EnvironmentPostProcessor is not a Spring bean.
    // We create a new instance for refresh operations.
    private final SecretManagerConfigLoader secretLoader = new SecretManagerConfigLoader();

    /**
     * Refresh all config sources and return a summary.
     */
    public RefreshResult refresh() {
        List<String> changedKeys = new ArrayList<>();

        // 1. Refresh secrets from Secret Manager
        if (gcpProject != null && !gcpProject.isBlank()) {
            try {
                Map<String, String> secretChanges = secretLoader.refreshSecrets(gcpProject);
                changedKeys.addAll(secretChanges.keySet());
                log.info("[ConfigRefresh] Refreshed {} secrets from Secret Manager", secretChanges.size());
            } catch (Exception e) {
                log.warn("[ConfigRefresh] Failed to refresh secrets: {}", e.getMessage());
            }
        }

        // 2. Refresh runtime config from GCS
        try {
            Map<String, String> configChanges = gcsRuntimeConfig.load();
            changedKeys.addAll(configChanges.keySet());
            log.info("[ConfigRefresh] Refreshed {} config entries from GCS", configChanges.size());
        } catch (Exception e) {
            log.warn("[ConfigRefresh] Failed to refresh GCS config: {}", e.getMessage());
        }

        log.info("[ConfigRefresh] Total changed keys: {}", changedKeys.size());

        return new RefreshResult(changedKeys, Instant.now());
    }

    /**
     * Result of a config refresh operation.
     */
    public record RefreshResult(
            List<String> changedKeys,
            Instant refreshedAt
    ) {}
}
