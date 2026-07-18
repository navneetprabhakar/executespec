package com.navneet.executespec.cost;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Transaction and fencing boundary for V298 terminal-usage recovery. */
@Component
@RequiredArgsConstructor
public class LlmUsageTerminalOutboxStore {

  static final int MAX_PAYLOAD_BYTES = 64 * 1024;

  public record Lease(
      String physicalAttemptId,
      String payload,
      String payloadSha256,
      long attempt,
      String owner,
      long fencingToken,
      Instant expiresAt) {}

  public enum StageResult {
    STAGED,
    ALREADY_STAGED
  }

  /** Non-retryable evidence conflict retained in the outbox for operator investigation. */
  public static final class TerminalUsageConflictException extends RuntimeException {
    TerminalUsageConflictException(String message) {
      super(message);
    }
  }

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  /** Commits the exact terminal payload independently of canonical-ledger materialization. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public StageResult stage(RunStepUsage row) {
    validateRow(row);
    String payload = serialize(row);
    if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
      throw new IllegalArgumentException("terminal usage payload exceeds 64 KiB");
    }
    String hash = payloadHash(row);
    int inserted =
        jdbc.update(
            """
            INSERT INTO llm_usage_terminal_outbox
              (physical_attempt_id, payload, payload_sha256)
            VALUES (?, CAST(? AS jsonb), ?)
            ON CONFLICT (physical_attempt_id) DO NOTHING
            """,
            row.getPhysicalAttemptId(),
            payload,
            hash);
    if (inserted == 1) return StageResult.STAGED;

    String existingHash =
        jdbc.queryForObject(
            "SELECT payload_sha256 FROM llm_usage_terminal_outbox WHERE physical_attempt_id=?",
            String.class,
            row.getPhysicalAttemptId());
    if (!hash.equals(existingHash)) {
      throw new TerminalUsageConflictException(
          "physical attempt id already has a different terminal usage payload");
    }
    return StageResult.ALREADY_STAGED;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<Lease> claimOne(String physicalAttemptId, String owner, Duration leaseDuration) {
    return claimSql(
            """
            UPDATE llm_usage_terminal_outbox SET
              status='PROCESSING', lease_owner=?, lease_token=lease_token+1,
              lease_expires_at=?, heartbeat_at=NOW(), attempt=attempt+1, last_error=NULL
            WHERE physical_attempt_id=? AND (
              (status='PENDING' AND next_retry_at<=NOW())
              OR (status='PROCESSING' AND lease_expires_at<=NOW())
            )
            RETURNING physical_attempt_id, payload::text, payload_sha256, attempt,
                      lease_owner, lease_token, lease_expires_at
            """,
            requireOwner(owner),
            Timestamp.from(Instant.now().plus(requireLease(leaseDuration))),
            physicalAttemptId)
        .stream()
        .findFirst();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<Lease> claimDue(String owner, int requestedLimit, Duration leaseDuration) {
    int limit = Math.max(1, Math.min(requestedLimit, 100));
    return claimSql(
        """
        WITH candidates AS (
          SELECT physical_attempt_id FROM llm_usage_terminal_outbox
          WHERE (status='PENDING' AND next_retry_at<=NOW())
             OR (status='PROCESSING' AND lease_expires_at<=NOW())
          ORDER BY CASE WHEN status='PROCESSING' THEN 0 ELSE 1 END,
                   COALESCE(lease_expires_at, next_retry_at), created_at
          FOR UPDATE SKIP LOCKED
          LIMIT ?
        )
        UPDATE llm_usage_terminal_outbox outbox SET
          status='PROCESSING', lease_owner=?, lease_token=outbox.lease_token+1,
          lease_expires_at=?, heartbeat_at=NOW(), attempt=outbox.attempt+1, last_error=NULL
        FROM candidates
        WHERE outbox.physical_attempt_id=candidates.physical_attempt_id
        RETURNING outbox.physical_attempt_id, outbox.payload::text, outbox.payload_sha256,
                  outbox.attempt, outbox.lease_owner, outbox.lease_token,
                  outbox.lease_expires_at
        """,
        limit,
        requireOwner(owner),
        Timestamp.from(Instant.now().plus(requireLease(leaseDuration))));
  }

  /** Inserts once and acknowledges the exact fenced lease in the same database transaction. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean materialize(Lease lease, RunStepUsage row) {
    requireMatchingLeasePayload(lease, row);
    int inserted = insertCanonical(row);
    if (inserted == 0) {
      String existingHash =
          jdbc.queryForObject(
              "SELECT terminal_payload_sha256 FROM run_step_usage WHERE physical_attempt_id=?",
              String.class,
              lease.physicalAttemptId());
      // NULL is retained only for pre-V298 ledger rows. Every V298+ replay must prove byte-level
      // semantic identity instead of treating an id collision as success.
      if (existingHash == null || !existingHash.equals(lease.payloadSha256())) {
        throw new TerminalUsageConflictException(
            "canonical terminal usage id conflicts with another fact");
      }
    }
    return jdbc.update(
            """
            UPDATE llm_usage_terminal_outbox SET status='COMPLETED', completed_at=NOW(),
              lease_owner=NULL, lease_expires_at=NULL, last_error=NULL
            WHERE physical_attempt_id=? AND status='PROCESSING'
              AND lease_owner=? AND lease_token=? AND lease_expires_at>NOW()
            """,
            lease.physicalAttemptId(),
            lease.owner(),
            lease.fencingToken())
        == 1;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean retry(Lease lease, Throwable failure, Duration delay) {
    return jdbc.update(
            """
            UPDATE llm_usage_terminal_outbox SET status='PENDING', next_retry_at=?,
              lease_owner=NULL, lease_expires_at=NULL, last_error=?
            WHERE physical_attempt_id=? AND status='PROCESSING'
              AND lease_owner=? AND lease_token=?
            """,
            Timestamp.from(Instant.now().plus(delay == null ? Duration.ofSeconds(30) : delay)),
            boundedError(failure),
            lease.physicalAttemptId(),
            lease.owner(),
            lease.fencingToken())
        == 1;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean deadLetter(Lease lease, Throwable failure) {
    return jdbc.update(
            """
            UPDATE llm_usage_terminal_outbox SET status='DEAD', completed_at=NOW(),
              lease_owner=NULL, lease_expires_at=NULL, last_error=?
            WHERE physical_attempt_id=? AND status='PROCESSING'
              AND lease_owner=? AND lease_token=?
            """,
            boundedError(failure),
            lease.physicalAttemptId(),
            lease.owner(),
            lease.fencingToken())
        == 1;
  }

  public RunStepUsage deserialize(Lease lease) {
    try {
      RunStepUsage row = objectMapper.readValue(lease.payload(), RunStepUsage.class);
      requireMatchingLeasePayload(lease, row);
      return row;
    } catch (JsonProcessingException invalidPayload) {
      throw new IllegalArgumentException("invalid terminal usage outbox payload", invalidPayload);
    }
  }

  private int insertCanonical(RunStepUsage row) {
    String providerUsage =
        row.getProviderUsageRawJson() == null ? null : serialize(row.getProviderUsageRawJson());
    return jdbc.update(
        """
INSERT INTO run_step_usage (
  id, run_id, task_id, agent_role, step_id, retry_of, llm_execution_context_id,
  correlation_id, org_id, user_id, project_id, repo_id, feature, source_type, source_id,
  key_source, attempt_status, error_code, error_redacted, attempt_number, latency_ms,
  provider, model, model_tier, logical_call_id, physical_attempt_id, parent_attempt_id,
  attempt_kind, attempt_ordinal, input_tokens, output_tokens, cache_write_tokens,
  cache_read_tokens, reasoning_tokens, cost_usd, call_index, provider_usage_raw_json,
  created_at, terminal_payload_sha256)
VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,?)
ON CONFLICT (physical_attempt_id) DO NOTHING
""",
        row.getId(),
        row.getRunId(),
        row.getTaskId(),
        row.getAgentRole(),
        row.getStepId(),
        row.getRetryOf(),
        row.getLlmExecutionContextId(),
        row.getCorrelationId(),
        row.getOrgId(),
        row.getUserId(),
        row.getProjectId(),
        row.getRepoId(),
        row.getFeature(),
        row.getSourceType(),
        row.getSourceId(),
        row.getKeySource(),
        row.getAttemptStatus(),
        row.getErrorCode(),
        row.getErrorRedacted(),
        row.getAttemptNumber(),
        row.getLatencyMs(),
        row.getProvider(),
        row.getModel(),
        row.getModelTier(),
        row.getLogicalCallId(),
        row.getPhysicalAttemptId(),
        row.getParentAttemptId(),
        row.getAttemptKind(),
        row.getAttemptOrdinal(),
        row.getInputTokens(),
        row.getOutputTokens(),
        row.getCacheWriteTokens(),
        row.getCacheReadTokens(),
        row.getReasoningTokens(),
        row.getCostUsd(),
        row.getCallIndex(),
        providerUsage,
        Timestamp.from(row.getCreatedAt()),
        payloadHash(row));
  }

  private List<Lease> claimSql(String sql, Object... arguments) {
    return jdbc.query(sql, LlmUsageTerminalOutboxStore::mapLease, arguments);
  }

  private static Lease mapLease(ResultSet rs, int ignored) throws SQLException {
    return new Lease(
        rs.getString("physical_attempt_id"),
        rs.getString("payload"),
        rs.getString("payload_sha256"),
        rs.getLong("attempt"),
        rs.getString("lease_owner"),
        rs.getLong("lease_token"),
        rs.getTimestamp("lease_expires_at").toInstant());
  }

  private void requireMatchingLeasePayload(Lease lease, RunStepUsage row) {
    validateRow(row);
    if (!lease.physicalAttemptId().equals(row.getPhysicalAttemptId())
        || !lease.payloadSha256().equals(payloadHash(row))) {
      throw new IllegalArgumentException("terminal usage outbox identity or checksum mismatch");
    }
  }

  private static void validateRow(RunStepUsage row) {
    if (row == null
        || row.getId() == null
        || row.getId().isBlank()
        || row.getPhysicalAttemptId() == null
        || row.getPhysicalAttemptId().isBlank()
        || !row.getId().equals(row.getPhysicalAttemptId())
        || row.getCreatedAt() == null) {
      throw new IllegalArgumentException("complete terminal usage identity required");
    }
  }

  private String serialize(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException serializationFailure) {
      throw new IllegalArgumentException(
          "terminal usage payload is not serializable", serializationFailure);
    }
  }

  private String payloadHash(RunStepUsage row) {
    try {
      return sha256(objectMapper.writeValueAsString(canonicalize(objectMapper.valueToTree(row))));
    } catch (JsonProcessingException serializationFailure) {
      throw new IllegalArgumentException(
          "terminal usage payload is not canonicalizable", serializationFailure);
    }
  }

  private JsonNode canonicalize(JsonNode node) {
    if (node == null || node.isNull() || node.isValueNode()) return node;
    if (node.isArray()) {
      ArrayNode sorted = objectMapper.createArrayNode();
      node.forEach(child -> sorted.add(canonicalize(child)));
      return sorted;
    }
    ObjectNode sorted = objectMapper.createObjectNode();
    java.util.TreeMap<String, JsonNode> fields = new java.util.TreeMap<>();
    node.fields()
        .forEachRemaining(entry -> fields.put(entry.getKey(), canonicalize(entry.getValue())));
    fields.forEach(sorted::set);
    return sorted;
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static Duration requireLease(Duration duration) {
    if (duration == null || duration.isNegative() || duration.isZero()) {
      throw new IllegalArgumentException("positive lease required");
    }
    return duration;
  }

  private static String requireOwner(String owner) {
    if (owner == null || owner.isBlank()) throw new IllegalArgumentException("owner required");
    return owner.length() <= 160 ? owner : owner.substring(0, 160);
  }

  private static String boundedError(Throwable failure) {
    String value = failure == null ? "UNKNOWN" : failure.getClass().getSimpleName();
    return value.length() <= 1000 ? value : value.substring(0, 1000);
  }
}
