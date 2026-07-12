package com.navneet.executespec.recovery;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Durable, workspace-local ownership fence for crash recovery.
 *
 * <p>The database CAS is the primary recovery lease. This sidecar is the checkpoint-transfer
 * contract: it binds a recovered workspace to the run, persisted plan, and recovery instance. A
 * peer with a fresh sidecar or incompatible plan is refused even if a stale database heartbeat
 * would otherwise permit takeover. It deliberately does not claim to migrate an in-flight provider
 * future or conversation.
 */
public final class RecoveryCheckpointLease {

  static final int SCHEMA_VERSION = 1;
  static final Duration FRESH_OWNER_WINDOW = Duration.ofMinutes(2);
  static final String FILE_NAME = "recovery-lease.json";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private RecoveryCheckpointLease() {}

  public static AcquireResult acquire(
      Path workspace, String runId, String instanceId, String planJson, Instant now) {
    if (workspace == null
        || runId == null
        || runId.isBlank()
        || instanceId == null
        || instanceId.isBlank()) {
      return AcquireResult.refused("INVALID_ARGUMENT");
    }
    Path dir = workspace.resolve(".agent-context");
    Path target = dir.resolve(FILE_NAME);
    String planSha = sha256(planJson == null ? "" : planJson);
    try {
      Files.createDirectories(dir);
      if (Files.isRegularFile(target)) {
        JsonNode current;
        try {
          current = MAPPER.readTree(Files.readString(target));
        } catch (Exception malformed) {
          return AcquireResult.refused("MALFORMED_LEASE");
        }
        if (current == null || current.path("schemaVersion").asInt(-1) != SCHEMA_VERSION) {
          return AcquireResult.refused("MALFORMED_LEASE");
        }
        if (!runId.equals(current.path("runId").asText())
            || !planSha.equals(current.path("planSha256").asText())) {
          return AcquireResult.refused("INCOMPATIBLE_CHECKPOINT");
        }
        String owner = current.path("instanceId").asText("");
        Instant claimedAt = parseInstant(current.path("claimedAt").asText(null));
        if (!owner.isBlank()
            && !owner.equals(instanceId)
            && claimedAt != null
            && claimedAt.isAfter(now.minus(FRESH_OWNER_WINDOW))) {
          return AcquireResult.refused("FRESH_CHECKPOINT_OWNER");
        }
      }
      ObjectNode lease = MAPPER.createObjectNode();
      lease.put("schemaVersion", SCHEMA_VERSION);
      lease.put("runId", runId);
      lease.put("instanceId", instanceId);
      lease.put("planSha256", planSha);
      lease.put("claimedAt", now.toString());
      Path temp = dir.resolve(FILE_NAME + ".tmp-" + instanceId.replaceAll("[^A-Za-z0-9_.-]", "_"));
      Files.writeString(
          temp,
          MAPPER.writeValueAsString(lease) + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE);
      try {
        Files.move(
            temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (java.nio.file.AtomicMoveNotSupportedException e) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      }
      return AcquireResult.accepted();
    } catch (Exception e) {
      return AcquireResult.refused("LEASE_IO_FAILURE");
    }
  }

  private static Instant parseInstant(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Instant.parse(value);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  static String sha256(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  public record AcquireResult(boolean acquired, String reason) {
    static AcquireResult accepted() {
      return new AcquireResult(true, "ACQUIRED");
    }

    static AcquireResult refused(String reason) {
      return new AcquireResult(false, reason);
    }
  }
}
