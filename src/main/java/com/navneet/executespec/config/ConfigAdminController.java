package com.navneet.executespec.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.cloud.secretmanager.v1.*;
import com.google.protobuf.ByteString;
import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.ActivityAuditService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
  private final ActivityAuditService auditService;

  @Value("${GCP_PROJECT:}")
  private String gcpProject;

  // Non-secret config (GCS JSON)

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
      @PathVariable String key,
      @RequestBody Map<String, String> body,
      @AuthenticationPrincipal AppUserDetails user) {
    String value = body.get("value");
    if (value == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing 'value' in request body");
    }
    try {
      gcsRuntimeConfig.put(key, value);
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
    } catch (IllegalStateException unavailable) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Runtime configuration could not be persisted");
    }
    recordConfigAudit(user, "RUNTIME_CONFIG_UPDATED", key, true);
    log.info("[ConfigAdmin] Updated config key '{}' (persisted to GCS)", key);
    return ResponseEntity.ok(Map.of("key", key, "status", "updated"));
  }

  @DeleteMapping("/{key}")
  public ResponseEntity<Map<String, String>> deleteConfig(
      @PathVariable String key, @AuthenticationPrincipal AppUserDetails user) {
    try {
      gcsRuntimeConfig.remove(key);
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
    } catch (IllegalStateException unavailable) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Runtime configuration could not be persisted");
    }
    recordConfigAudit(user, "RUNTIME_CONFIG_REMOVED", key, false);
    log.info("[ConfigAdmin] Removed config key '{}' (persisted to GCS)", key);
    return ResponseEntity.ok(Map.of("key", key, "status", "removed"));
  }

  // Secrets (GCP Secret Manager)

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
      log.warn("[ConfigAdmin] Failed to list secrets type={}", e.getClass().getSimpleName());
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(Map.of("error", "Failed to connect to Secret Manager"));
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
      @PathVariable String name,
      @RequestBody Map<String, String> body,
      @AuthenticationPrincipal AppUserDetails user) {
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
      SecretVersion version =
          client.addSecretVersion(
              AddSecretVersionRequest.newBuilder()
                  .setParent(secretName.toString())
                  .setPayload(payload)
                  .build());

      log.info("[ConfigAdmin] Updated secret '{}' with new version", secretId);
      recordSecretAudit(user, secretId, version == null ? "unknown" : version.getName());
      return ResponseEntity.ok(
          Map.of(
              "name",
              secretId,
              "version",
              version == null ? "unknown" : extractSecretId(version.getName()),
              "status",
              "updated",
              "restartRequired",
              "true"));

    } catch (com.google.api.gax.rpc.NotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Secret not found: " + secretId);
    } catch (Exception e) {
      log.error(
          "[ConfigAdmin] Failed to update secret '{}' type={}",
          secretId,
          e.getClass().getSimpleName());
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update secret");
    }
  }

  @PostMapping("/refresh")
  public ResponseEntity<Map<String, Object>> refresh(@AuthenticationPrincipal AppUserDetails user) {
    ConfigRefreshService.RefreshResult result = configRefreshService.refresh();
    // RR-533 — a refresh activates externally-edited routing/concurrency/continuation/tracing
    // policy
    // in the running JVM. Like the PUT/DELETE config and secret-version paths, it MUST leave a
    // durable actor record so incident review can distinguish automatic startup state from a manual
    // live policy change. Secret VALUES are never recorded — only the changed key NAMES.
    recordRefreshAudit(user, result.changedKeys());
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("changedKeys", result.changedKeys());
    response.put("refreshedAt", result.refreshedAt().toString());
    response.put("totalChanged", result.changedKeys().size());
    return ResponseEntity.ok(response);
  }

  private void recordRefreshAudit(AppUserDetails user, List<String> changedKeys) {
    String actorId = user == null || user.getUser() == null ? null : user.getUser().getId();
    String keysJson =
        changedKeys.stream()
            .map(k -> "\"" + k.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    auditService.record(
        ActivityAudit.builder()
            .eventType("RUNTIME_CONFIG_REFRESHED")
            .entityType("RUNTIME_CONFIG")
            .entityId("refresh")
            .actorId(actorId)
            .newValue(
                "{\"changedKeyCount\":" + changedKeys.size() + ",\"changedKeys\":" + keysJson + "}")
            .build());
  }

  private static String extractSecretId(String fullName) {
    int lastSlash = fullName.lastIndexOf('/');
    return lastSlash >= 0 ? fullName.substring(lastSlash + 1) : fullName;
  }

  private void recordConfigAudit(
      AppUserDetails user, String eventType, String key, boolean present) {
    String actorId = user == null || user.getUser() == null ? null : user.getUser().getId();
    auditService.record(
        ActivityAudit.builder()
            .eventType(eventType)
            .entityType("RUNTIME_CONFIG")
            .entityId(key)
            .actorId(actorId)
            .newValue("{\"present\":" + present + "}")
            .build());
  }

  private void recordSecretAudit(AppUserDetails user, String secretId, String versionName) {
    String actorId = user == null || user.getUser() == null ? null : user.getUser().getId();
    auditService.record(
        ActivityAudit.builder()
            .eventType("SECRET_VERSION_ADDED")
            .entityType("SECRET_MANAGER_SECRET")
            .entityId(secretId)
            .actorId(actorId)
            .newValue(
                "{\"version\":\""
                    + extractSecretId(versionName).replace("\"", "")
                    + "\",\"restartRequired\":true}")
            .build());
  }
}
