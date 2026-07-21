package com.navneet.executespec.config;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.storage.*;
import com.navneet.executespec.security.GeneratedSecretScanner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads non-secret runtime configuration from a GCS JSON file.
 *
 * <p>Config file location: {@code gs://<DEPLOY_BUCKET>/config/runtime-config.json}
 *
 * <p>JSON format:
 *
 * <pre>{@code
 * {
 *   "CORS_ALLOWED_ORIGINS": "https://app.executespec.ai,...",
 *   "preview.previewTtlMinutes": "10",
 *   "agent.concurrency.max-runs": "10"
 * }
 * }</pre>
 *
 * <p>Values are applied as system properties on startup and can be refreshed at runtime via {@link
 * ConfigRefreshService}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GcsRuntimeConfig {

  private static final String CONFIG_OBJECT_KEY = "config/runtime-config.json";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Set<String> ALLOWED_EXACT_KEYS =
      Set.of("CORS_ALLOWED_ORIGINS", "app.cors.allowed-origins");
  private static final List<String> ALLOWED_PREFIXES =
      List.of(
          "preview.",
          "agent.concurrency.",
          "execution.continuation.",
          "management.tracing.",
          "management.otlp.");
  private static final List<String> SECRET_KEY_MARKERS =
      List.of("secret", "password", "token", "api-key", "api_key", "credential", "private-key");

  private final GeneratedSecretScanner secretScanner;

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
        log.debug(
            "[GcsRuntimeConfig] No runtime config file at gs://{}/{}",
            deployBucket,
            CONFIG_OBJECT_KEY);
        return Collections.emptyMap();
      }

      String json = new String(blob.getContent(), StandardCharsets.UTF_8);
      Map<String, String> loaded = MAPPER.readValue(json, new TypeReference<>() {});

      Map<String, String> changed = new LinkedHashMap<>();
      for (var entry : loaded.entrySet()) {
        String key = entry.getKey();
        String newValue = entry.getValue();
        try {
          validateNonSecretEntry(key, newValue);
        } catch (IllegalArgumentException rejected) {
          log.error("[GcsRuntimeConfig] Ignoring disallowed runtime-config key '{}'", key);
          continue;
        }
        String oldValue = currentConfig.get(key);

        if (!newValue.equals(oldValue)) {
          System.setProperty(key, newValue);
          currentConfig.put(key, newValue);
          changed.put(key, newValue);
        }
      }

      log.info(
          "[GcsRuntimeConfig] Loaded {} config entries from GCS ({} changed)",
          loaded.size(),
          changed.size());
      return changed;

    } catch (Exception e) {
      log.warn("[GcsRuntimeConfig] Failed to load runtime config from GCS: {}", e.getMessage());
      return Collections.emptyMap();
    }
  }

  /** Returns a snapshot of all currently loaded config entries. */
  public Map<String, String> getAll() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(currentConfig));
  }

  /** Update a single config entry and persist to GCS. */
  public synchronized void put(String key, String value) {
    validateNonSecretEntry(key, value);
    Map<String, String> candidate = new LinkedHashMap<>(currentConfig);
    candidate.put(key, value);
    persistToGcs(candidate);
    currentConfig.put(key, value);
    System.setProperty(key, value);
  }

  /** Remove a config entry and persist to GCS. */
  public synchronized void remove(String key) {
    validateAllowedKey(key);
    Map<String, String> candidate = new LinkedHashMap<>(currentConfig);
    candidate.remove(key);
    persistToGcs(candidate);
    currentConfig.remove(key);
    System.clearProperty(key);
  }

  /** Returns the GCS bucket name for config storage. */
  public String getBucket() {
    return deployBucket;
  }

  private void persistToGcs(Map<String, String> toWrite) {
    if (storage == null || deployBucket.isBlank()) {
      throw new IllegalStateException("GCS runtime-config storage is unavailable");
    }

    try {
      String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toWrite);
      BlobId blobId = BlobId.of(deployBucket, CONFIG_OBJECT_KEY);
      Blob existing = storage.get(blobId);
      BlobInfo blobInfo =
          BlobInfo.newBuilder(blobId)
              .setContentType("application/json")
              .setMetadata(Map.of("updated-at", Instant.now().toString()))
              .build();
      Storage.BlobTargetOption generationGuard =
          existing == null
              ? Storage.BlobTargetOption.doesNotExist()
              : Storage.BlobTargetOption.generationMatch(existing.getGeneration());
      storage.create(blobInfo, json.getBytes(StandardCharsets.UTF_8), generationGuard);
      log.info("[GcsRuntimeConfig] Persisted {} config entries to GCS", toWrite.size());

    } catch (Exception e) {
      log.error(
          "[GcsRuntimeConfig] Failed to persist config to GCS type={}",
          e.getClass().getSimpleName());
      throw new IllegalStateException("GCS runtime-config persistence failed", e);
    }
  }

  private void validateNonSecretEntry(String key, String value) {
    validateAllowedKey(key);
    if (value == null) throw new IllegalArgumentException("Runtime-config value is required");
    if (value.length() > 4096)
      throw new IllegalArgumentException("Runtime-config value is too large");
    if (secretScanner.scan(value).isPresent()) {
      throw new IllegalArgumentException("Secrets are not allowed in runtime config");
    }
  }

  private static void validateAllowedKey(String key) {
    if (key == null || key.isBlank())
      throw new IllegalArgumentException("Runtime-config key is required");
    String normalized = key.strip();
    String lower = normalized.toLowerCase(Locale.ROOT);
    if (SECRET_KEY_MARKERS.stream().anyMatch(lower::contains)) {
      throw new IllegalArgumentException("Secret-like keys are not allowed in runtime config");
    }
    boolean allowed =
        ALLOWED_EXACT_KEYS.contains(normalized)
            || ALLOWED_PREFIXES.stream().anyMatch(normalized::startsWith);
    if (!allowed)
      throw new IllegalArgumentException("Unsupported runtime-config key: " + normalized);
  }
}
