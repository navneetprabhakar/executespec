package com.navneet.executespec.workspacegeneration;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Short, independent transactions around non-transactional workspace filesystem side effects. */
@Component
public class WorkspaceGenerationStepStore {

  public enum Kind {
    BEFORE_ARCHIVE_UPLOAD,
    CANDIDATE_ARCHIVE_UPLOAD,
    ROLLBACK_RESTORE,
    ACCEPTED_REPLACEMENT
  }

  public enum State {
    INTENT,
    APPLIED,
    VERIFIED,
    FAILED
  }

  public record Step(
      UUID id,
      UUID attemptId,
      Kind kind,
      String stepKey,
      String idempotencyKey,
      State state,
      String expectedGeneration,
      String expectedTree,
      String objectKey,
      String artifactDigest,
      String storageGeneration,
      Long sizeBytes,
      String crc32c,
      String etag,
      String evidenceJson) {}

  public record ArchiveReceipt(
      String objectKey,
      String artifactDigest,
      String storageGeneration,
      long sizeBytes,
      String crc32c,
      String etag) {}

  private final JdbcTemplate jdbc;
  private final ObjectWriter canonicalWriter;
  private final TransactionTemplate independent;

  public WorkspaceGenerationStepStore(
      JdbcTemplate jdbc, ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.canonicalWriter =
        objectMapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    this.independent = new TransactionTemplate(transactionManager);
    this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public Step recordIntent(
      UUID attemptId,
      Kind kind,
      String stepKey,
      String expectedGeneration,
      String expectedTree,
      Map<String, ?> evidence) {
    requireHash(expectedGeneration, 64, "WORKSPACE_GENERATION_STEP_GENERATION_INVALID");
    if (expectedTree != null) {
      requireHash(expectedTree, 40, "WORKSPACE_GENERATION_STEP_TREE_INVALID");
    }
    String key = requireText(stepKey, "WORKSPACE_GENERATION_STEP_KEY_INVALID");
    String evidenceJson = canonical(evidence == null ? Map.of() : evidence);
    String evidenceDigest = sha256(evidenceJson);
    String idempotency =
        DigestUtils.sha256Hex(
            (attemptId
                    + "\n"
                    + kind
                    + "\n"
                    + key
                    + "\n"
                    + expectedGeneration
                    + "\n"
                    + evidenceDigest)
                .getBytes(StandardCharsets.UTF_8));
    independent.executeWithoutResult(
        ignored ->
            jdbc.update(
                "INSERT INTO workspace_generation_step(id,attempt_id,step_kind,step_key,"
                    + "idempotency_key,state,expected_generation,expected_tree,evidence_json,"
                    + "evidence_digest,created_at) VALUES (?,?,?,?,?,'INTENT',?,?,?::jsonb,?,?) "
                    + "ON CONFLICT (attempt_id,step_kind,step_key) DO NOTHING",
                UUID.randomUUID(),
                attemptId,
                kind.name(),
                key,
                idempotency,
                expectedGeneration,
                expectedTree,
                evidenceJson,
                evidenceDigest,
                timestamp()));
    Step stored =
        find(attemptId, kind, key)
            .orElseThrow(
                () -> new IllegalStateException("WORKSPACE_GENERATION_STEP_INTENT_MISSING"));
    if (!idempotency.equals(stored.idempotencyKey())
        || !expectedGeneration.equals(stored.expectedGeneration())
        || !java.util.Objects.equals(expectedTree, stored.expectedTree())) {
      throw new IllegalStateException("WORKSPACE_GENERATION_STEP_INTENT_CONFLICT");
    }
    return stored;
  }

  public Step markArchiveApplied(
      UUID attemptId, Kind kind, String stepKey, ArchiveReceipt receipt) {
    if (kind != Kind.BEFORE_ARCHIVE_UPLOAD && kind != Kind.CANDIDATE_ARCHIVE_UPLOAD) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_ARCHIVE_STEP_KIND_INVALID");
    }
    requireHash(receipt.artifactDigest(), 64, "WORKSPACE_GENERATION_STEP_DIGEST_INVALID");
    if (receipt.sizeBytes() <= 0) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_STEP_SIZE_INVALID");
    }
    int updated =
        independent.execute(
            ignored ->
                jdbc.update(
                    "UPDATE workspace_generation_step SET state='APPLIED',object_key=?,"
                        + "artifact_digest=?,storage_generation=?,size_bytes=?,crc32c=?,etag=?,"
                        + "applied_at=? WHERE attempt_id=? AND step_kind=? AND step_key=? "
                        + "AND state='INTENT'",
                    requireText(
                        receipt.objectKey(), "WORKSPACE_GENERATION_STEP_OBJECT_KEY_INVALID"),
                    receipt.artifactDigest(),
                    requireText(
                        receipt.storageGeneration(),
                        "WORKSPACE_GENERATION_STEP_STORAGE_GENERATION_INVALID"),
                    receipt.sizeBytes(),
                    requireText(receipt.crc32c(), "WORKSPACE_GENERATION_STEP_CRC_INVALID"),
                    requireText(receipt.etag(), "WORKSPACE_GENERATION_STEP_ETAG_INVALID"),
                    timestamp(),
                    attemptId,
                    kind.name(),
                    stepKey));
    Step stored = require(attemptId, kind, stepKey);
    if (updated != 1 && !same(receipt, stored)) {
      throw new IllegalStateException("WORKSPACE_GENERATION_STEP_ARCHIVE_RECEIPT_CONFLICT");
    }
    return stored;
  }

  public Step markApplied(UUID attemptId, Kind kind, String stepKey, Map<String, ?> evidence) {
    String evidenceJson = canonical(evidence == null ? Map.of() : evidence);
    int updated =
        independent.execute(
            ignored ->
                jdbc.update(
                    "UPDATE workspace_generation_step SET state='APPLIED',evidence_json=?::jsonb,"
                        + "evidence_digest=?,applied_at=? WHERE attempt_id=? AND step_kind=? "
                        + "AND step_key=? AND state='INTENT'",
                    evidenceJson,
                    sha256(evidenceJson),
                    timestamp(),
                    attemptId,
                    kind.name(),
                    stepKey));
    Step stored = require(attemptId, kind, stepKey);
    if (updated != 1 && stored.state() != State.APPLIED && stored.state() != State.VERIFIED) {
      throw new IllegalStateException("WORKSPACE_GENERATION_STEP_APPLY_CAS_FAILED");
    }
    return stored;
  }

  public void markVerified(UUID attemptId, Kind kind, String stepKey, Map<String, ?> evidence) {
    String evidenceJson = canonical(evidence == null ? Map.of() : evidence);
    int updated =
        independent.execute(
            ignored ->
                jdbc.update(
                    "UPDATE workspace_generation_step SET state='VERIFIED',evidence_json=?::jsonb,"
                        + "evidence_digest=?,verified_at=? WHERE attempt_id=? AND step_kind=? "
                        + "AND step_key=? AND state='APPLIED'",
                    evidenceJson,
                    sha256(evidenceJson),
                    timestamp(),
                    attemptId,
                    kind.name(),
                    stepKey));
    if (updated != 1 && require(attemptId, kind, stepKey).state() != State.VERIFIED) {
      throw new IllegalStateException("WORKSPACE_GENERATION_STEP_VERIFY_CAS_FAILED");
    }
  }

  public void markFailed(UUID attemptId, Kind kind, String stepKey, String code, String reason) {
    independent.executeWithoutResult(
        ignored ->
            jdbc.update(
                "UPDATE workspace_generation_step SET state='FAILED',failure_code=?,"
                    + "failure_reason=?,failed_at=? WHERE attempt_id=? AND step_kind=? "
                    + "AND step_key=? AND state IN ('INTENT','APPLIED')",
                requireText(code, "WORKSPACE_GENERATION_STEP_FAILURE_CODE_INVALID"),
                bounded(reason),
                timestamp(),
                attemptId,
                kind.name(),
                stepKey));
  }

  public Optional<Step> find(UUID attemptId, Kind kind, String stepKey) {
    List<Step> rows =
        jdbc.query(
            "SELECT id,attempt_id,step_kind,step_key,idempotency_key,state,expected_generation,"
                + "expected_tree,object_key,artifact_digest,storage_generation,size_bytes,crc32c,"
                + "etag,evidence_json::text FROM workspace_generation_step WHERE attempt_id=? "
                + "AND step_kind=? AND step_key=?",
            (rs, ignored) ->
                new Step(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    Kind.valueOf(rs.getString(3)),
                    rs.getString(4),
                    rs.getString(5),
                    State.valueOf(rs.getString(6)),
                    rs.getString(7),
                    rs.getString(8),
                    rs.getString(9),
                    rs.getString(10),
                    rs.getString(11),
                    (Long) rs.getObject(12),
                    rs.getString(13),
                    rs.getString(14),
                    rs.getString(15)),
            attemptId,
            kind.name(),
            stepKey);
    return rows.stream().findFirst();
  }

  public List<Step> incomplete(UUID attemptId, Kind kind) {
    return jdbc.query(
        "SELECT id,attempt_id,step_kind,step_key,idempotency_key,state,expected_generation,"
            + "expected_tree,object_key,artifact_digest,storage_generation,size_bytes,crc32c,etag,"
            + "evidence_json::text FROM workspace_generation_step WHERE attempt_id=? "
            + "AND step_kind=? AND state IN ('INTENT','APPLIED') ORDER BY step_key",
        (rs, ignored) ->
            new Step(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                Kind.valueOf(rs.getString(3)),
                rs.getString(4),
                rs.getString(5),
                State.valueOf(rs.getString(6)),
                rs.getString(7),
                rs.getString(8),
                rs.getString(9),
                rs.getString(10),
                rs.getString(11),
                (Long) rs.getObject(12),
                rs.getString(13),
                rs.getString(14),
                rs.getString(15)),
        attemptId,
        kind.name());
  }

  public List<Step> readAll(UUID attemptId) {
    return jdbc.query(
        "SELECT id,attempt_id,step_kind,step_key,idempotency_key,state,expected_generation,"
            + "expected_tree,object_key,artifact_digest,storage_generation,size_bytes,crc32c,etag,"
            + "evidence_json::text FROM workspace_generation_step WHERE attempt_id=? "
            + "ORDER BY created_at,id",
        (rs, ignored) ->
            new Step(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                Kind.valueOf(rs.getString(3)),
                rs.getString(4),
                rs.getString(5),
                State.valueOf(rs.getString(6)),
                rs.getString(7),
                rs.getString(8),
                rs.getString(9),
                rs.getString(10),
                rs.getString(11),
                (Long) rs.getObject(12),
                rs.getString(13),
                rs.getString(14),
                rs.getString(15)),
        attemptId);
  }

  private Step require(UUID attemptId, Kind kind, String stepKey) {
    return find(attemptId, kind, stepKey)
        .orElseThrow(() -> new IllegalStateException("WORKSPACE_GENERATION_STEP_MISSING"));
  }

  private String canonical(Object value) {
    try {
      return canonicalWriter.writeValueAsString(value);
    } catch (Exception failure) {
      throw new IllegalStateException("WORKSPACE_GENERATION_STEP_EVIDENCE_INVALID", failure);
    }
  }

  private static boolean same(ArchiveReceipt receipt, Step step) {
    return step.state() != State.INTENT
        && receipt.objectKey().equals(step.objectKey())
        && receipt.artifactDigest().equals(step.artifactDigest())
        && receipt.storageGeneration().equals(step.storageGeneration())
        && receipt.sizeBytes() == step.sizeBytes()
        && receipt.crc32c().equals(step.crc32c())
        && receipt.etag().equals(step.etag());
  }

  private static String sha256(String value) {
    return DigestUtils.sha256Hex(value.getBytes(StandardCharsets.UTF_8));
  }

  private static java.sql.Timestamp timestamp() {
    return java.sql.Timestamp.from(Instant.now());
  }

  private static void requireHash(String value, int length, String code) {
    if (value == null || !value.matches("[0-9a-f]{" + length + "}")) {
      throw new IllegalArgumentException(code);
    }
  }

  private static String requireText(String value, String code) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(code);
    return value;
  }

  private static String bounded(String value) {
    if (value == null || value.isBlank()) return "unspecified";
    return value.length() <= 1000 ? value : value.substring(0, 1000);
  }
}
