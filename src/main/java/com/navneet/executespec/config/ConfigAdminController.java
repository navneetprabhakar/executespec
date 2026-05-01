package com.navneet.executespec.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.cloud.secretmanager.v1.*;
import com.google.protobuf.ByteString;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Admin API for managing runtime configuration and secrets.
 *
 * <p><b>Non-secret config</b> is stored in a GCS JSON file and can be viewed/edited freely.
 * <b>Secrets</b> are stored in GCP Secret Manager — only names are listed (never values); values
 * can be created/updated.
 *
 * <p>All endpoints require SUPER_ADMIN role.
 *
 * <h3>Endpoints:</h3>
 *
 * <table>
 *   <tr><td>GET    /api/admin/config</td><td>List non-secret config entries</td></tr>
 *   <tr><td>PUT    /api/admin/config/{key}</td><td>Update a non-secret config value</td></tr>
 *   <tr><td>DELETE /api/admin/config/{key}</td><td>Remove a non-secret config override</td></tr>
 *   <tr><td>GET    /api/admin/config/secrets</td><td>List secret names (no values)</td></tr>
 *   <tr><td>PUT    /api/admin/config/secrets/{name}</td><td>Create/update a secret</td></tr>
 *   <tr><td>POST   /api/admin/config/refresh</td><td>Reload config without restart</td></tr>
 * </table>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/config")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class ConfigAdminController {

  private final GcsRuntimeConfig gcsRuntimeConfig;
  private final ConfigRefreshService configRefreshService;

  @Value("${GCP_PROJECT:}")
  private String gcpProject;

  // ── Non-secret config (GCS JSON) ────────────────────────────────────────

  @GetMapping
  public ResponseEntity<Map<String, Object>> listConfig() {
    Map<String, String> config = gcsRuntimeConfig.getAll();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("source", "GCS runtime-config.json");
    result.put("bucket", gcsRuntimeConfig.getBucket());
    result.put("entries", config);
    result.put("count", config.size());
    return ResponseEntity.ok(result);
  }

  @PutMapping("/{key}")
  public ResponseEntity<Map<String, String>> putConfig(
      @PathVariable String key, @RequestBody Map<String, String> body) {
    String value = body.get("value");
    if (value == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing 'value' in request body");
    }
    gcsRuntimeConfig.put(key, value);
    log.info("[ConfigAdmin] Updated config key '{}' (persisted to GCS)", key);
    return ResponseEntity.ok(Map.of("key", key, "status", "updated"));
  }

  @DeleteMapping("/{key}")
  public ResponseEntity<Map<String, String>> deleteConfig(@PathVariable String key) {
    gcsRuntimeConfig.remove(key);
    log.info("[ConfigAdmin] Removed config key '{}' (persisted to GCS)", key);
    return ResponseEntity.ok(Map.of("key", key, "status", "removed"));
  }

  // ── Secrets (GCP Secret Manager) ────────────────────────────────────────

  @GetMapping("/secrets")
  public ResponseEntity<Map<String, Object>> listSecrets() {
    if (gcpProject == null || gcpProject.isBlank()) {
      return ResponseEntity.ok(
          Map.of(
              "source", "GCP Secret Manager",
              "status", "unavailable",
              "message", "GCP_PROJECT not configured"));
    }

    List<Map<String, String>> secretList = new ArrayList<>();
    try (SecretManagerServiceClient client = SecretManagerServiceClient.create()) {
      ListSecretsRequest request =
          ListSecretsRequest.newBuilder()
              .setParent(ProjectName.of(gcpProject).toString())
              .setFilter("labels.app=executespec")
              .build();

      for (Secret secret : client.listSecrets(request).iterateAll()) {
        String secretId = extractSecretId(secret.getName());
        String envVar = SecretManagerConfigLoader.SECRET_TO_ENV.getOrDefault(secretId, "");
        secretList.add(
            Map.of(
                "name", secretId,
                "envVar", envVar,
                "createTime", secret.getCreateTime().toString()));
      }
    } catch (Exception e) {
      log.warn("[ConfigAdmin] Failed to list secrets: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(Map.of("error", "Failed to connect to Secret Manager: " + e.getMessage()));
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("source", "GCP Secret Manager");
    result.put("project", gcpProject);
    result.put("secrets", secretList);
    result.put("count", secretList.size());
    return ResponseEntity.ok(result);
  }

  @PutMapping("/secrets/{name}")
  public ResponseEntity<Map<String, String>> putSecret(
      @PathVariable String name, @RequestBody Map<String, String> body) {
    if (gcpProject == null || gcpProject.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "GCP_PROJECT not configured");
    }

    String value = body.get("value");
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing 'value' in request body");
    }

    // Ensure name has prefix
    String secretId = name.startsWith("executespec-") ? name : "executespec-" + name;

    try (SecretManagerServiceClient client = SecretManagerServiceClient.create()) {
      SecretName secretName = SecretName.of(gcpProject, secretId);

      // Try to get existing secret; create if not found
      try {
        client.getSecret(secretName);
      } catch (com.google.api.gax.rpc.NotFoundException notFound) {
        // Create the secret
        Secret secret =
            Secret.newBuilder()
                .setReplication(
                    Replication.newBuilder()
                        .setAutomatic(Replication.Automatic.getDefaultInstance())
                        .build())
                .putLabels("app", "executespec")
                .build();
        client.createSecret(
            CreateSecretRequest.newBuilder()
                .setParent(ProjectName.of(gcpProject).toString())
                .setSecretId(secretId)
                .setSecret(secret)
                .build());
        log.info("[ConfigAdmin] Created new secret '{}'", secretId);
      }

      // Add new version with the value
      SecretPayload payload =
          SecretPayload.newBuilder().setData(ByteString.copyFromUtf8(value)).build();
      client.addSecretVersion(
          AddSecretVersionRequest.newBuilder()
              .setParent(secretName.toString())
              .setPayload(payload)
              .build());

      log.info("[ConfigAdmin] Updated secret '{}' with new version", secretId);
      return ResponseEntity.ok(Map.of("name", secretId, "status", "updated"));

    } catch (com.google.api.gax.rpc.NotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Secret not found: " + secretId);
    } catch (Exception e) {
      log.error("[ConfigAdmin] Failed to update secret '{}': {}", secretId, e.getMessage());
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update secret: " + e.getMessage());
    }
  }

  // ── Refresh ─────────────────────────────────────────────────────────────

  @PostMapping("/refresh")
  public ResponseEntity<Map<String, Object>> refresh() {
    ConfigRefreshService.RefreshResult result = configRefreshService.refresh();
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("changedKeys", result.changedKeys());
    response.put("refreshedAt", result.refreshedAt().toString());
    response.put("totalChanged", result.changedKeys().size());
    return ResponseEntity.ok(response);
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private static String extractSecretId(String fullName) {
    int lastSlash = fullName.lastIndexOf('/');
    return lastSlash >= 0 ? fullName.substring(lastSlash + 1) : fullName;
  }
}
