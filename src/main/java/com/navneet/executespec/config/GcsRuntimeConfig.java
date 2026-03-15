package com.navneet.executespec.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads non-secret runtime configuration from a GCS JSON file.
 *
 * <p>Config file location: {@code gs://<DEPLOY_BUCKET>/config/runtime-config.json}
 *
 * <p>JSON format:
 * <pre>{@code
 * {
 *   "CORS_ALLOWED_ORIGINS": "https://app.executespec.ai,...",
 *   "preview.previewTtlMinutes": "10",
 *   "agent.concurrency.max-runs": "10"
 * }
 * }</pre>
 *
 * <p>Values are applied as system properties on startup and can be refreshed
 * at runtime via {@link ConfigRefreshService}.
 */
@Slf4j
@Component
public class GcsRuntimeConfig {

    private static final String CONFIG_OBJECT_KEY = "config/runtime-config.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${DEPLOY_BUCKET:}")
    private String deployBucket;

    private final Map<String, String> currentConfig = new ConcurrentHashMap<>();
    private Storage storage;

    @PostConstruct
    void init() {
        if (deployBucket == null || deployBucket.isBlank()) {
            log.info("[GcsRuntimeConfig] No DEPLOY_BUCKET configured — skipping GCS config (local dev)");
            return;
        }
        try {
            this.storage = StorageOptions.getDefaultInstance().getService();
            load();
        } catch (Exception e) {
            log.warn("[GcsRuntimeConfig] Failed to initialise GCS config: {}", e.getMessage());
        }
    }

    /**
     * Load config from GCS and apply as system properties.
     *
     * @return map of keys that changed (old value → new value)
     */
    public Map<String, String> load() {
        if (storage == null || deployBucket.isBlank()) {
            return Collections.emptyMap();
        }

        try {
            Blob blob = storage.get(BlobId.of(deployBucket, CONFIG_OBJECT_KEY));
            if (blob == null || !blob.exists()) {
                log.debug("[GcsRuntimeConfig] No runtime config file at gs://{}/{}", deployBucket, CONFIG_OBJECT_KEY);
                return Collections.emptyMap();
            }

            String json = new String(blob.getContent(), StandardCharsets.UTF_8);
            Map<String, String> loaded = MAPPER.readValue(json, new TypeReference<>() {});

            Map<String, String> changed = new LinkedHashMap<>();
            for (var entry : loaded.entrySet()) {
                String key = entry.getKey();
                String newValue = entry.getValue();
                String oldValue = currentConfig.get(key);

                if (!newValue.equals(oldValue)) {
                    System.setProperty(key, newValue);
                    currentConfig.put(key, newValue);
                    changed.put(key, newValue);
                }
            }

            log.info("[GcsRuntimeConfig] Loaded {} config entries from GCS ({} changed)",
                    loaded.size(), changed.size());
            return changed;

        } catch (Exception e) {
            log.warn("[GcsRuntimeConfig] Failed to load runtime config from GCS: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Returns a snapshot of all currently loaded config entries.
     */
    public Map<String, String> getAll() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(currentConfig));
    }

    /**
     * Update a single config entry and persist to GCS.
     */
    public void put(String key, String value) {
        currentConfig.put(key, value);
        System.setProperty(key, value);
        persistToGcs();
    }

    /**
     * Remove a config entry and persist to GCS.
     */
    public void remove(String key) {
        currentConfig.remove(key);
        System.clearProperty(key);
        persistToGcs();
    }

    /**
     * Returns the GCS bucket name for config storage.
     */
    public String getBucket() {
        return deployBucket;
    }

    private void persistToGcs() {
        if (storage == null || deployBucket.isBlank()) {
            log.warn("[GcsRuntimeConfig] Cannot persist — no GCS storage available");
            return;
        }

        try {
            // Add metadata
            Map<String, String> toWrite = new LinkedHashMap<>(currentConfig);

            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toWrite);
            BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(deployBucket, CONFIG_OBJECT_KEY))
                    .setContentType("application/json")
                    .setMetadata(Map.of("updated-at", Instant.now().toString()))
                    .build();
            storage.create(blobInfo, json.getBytes(StandardCharsets.UTF_8));
            log.info("[GcsRuntimeConfig] Persisted {} config entries to GCS", toWrite.size());

        } catch (Exception e) {
            log.error("[GcsRuntimeConfig] Failed to persist config to GCS: {}", e.getMessage());
        }
    }
}
