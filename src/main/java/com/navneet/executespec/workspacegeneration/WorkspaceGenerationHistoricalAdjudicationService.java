package com.navneet.executespec.workspacegeneration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Append-only, byte-bound operator adjudication of unprovable historical BUILD evidence. */
@Service
@RequiredArgsConstructor
public class WorkspaceGenerationHistoricalAdjudicationService {

  private static final String INSERT_SQL =
      """
INSERT INTO workspace_generation_historical_adjudication (
    id, stage, record_kind, record_id, run_id, action, source_disposition,
    effective_disposition, retention_hold, before_state_bytes, before_state_sha256,
    proposed_change_bytes, proposed_change_sha256, dry_run_id, actor_user_id, operator_reason
) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
""";

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public Receipt dryRun(
      RecordKind recordKind,
      String recordId,
      Action action,
      String operatorReason,
      String actorUserId) {
    if (recordKind == null || action == null) {
      throw new IllegalArgumentException("recordKind and action are required");
    }
    requireReason(operatorReason);
    requireActor(actorUserId);
    Source source = loadSource(recordKind, recordId, true);
    String effectiveDisposition =
        action == Action.CLOSE_AS_NON_EXECUTABLE ? "OPERATOR_RESOLVED" : source.sourceDisposition();
    boolean retentionHold = action != Action.RELEASE_FOR_DELETION;
    String proposed =
        proposedChange(
            action, source.sourceDisposition(), effectiveDisposition, retentionHold, source);
    UUID id = UUID.randomUUID();
    insert(
        id,
        Stage.DRY_RUN,
        recordKind,
        source,
        action,
        effectiveDisposition,
        retentionHold,
        proposed,
        null,
        actorUserId,
        operatorReason);
    return read(id);
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public Receipt apply(UUID dryRunId, String operatorReason, String actorUserId) {
    requireReason(operatorReason);
    requireActor(actorUserId);
    Receipt dryRun = readLocked(dryRunId);
    if (dryRun.stage() != Stage.DRY_RUN) {
      throw new IllegalArgumentException(
          "Historical adjudication apply requires a DRY_RUN receipt");
    }
    Source current = loadSource(dryRun.recordKind(), dryRun.recordId(), true);
    if (!current.beforeStateBytes().equals(dryRun.beforeStateBytes())
        || !sha256(current.beforeStateBytes()).equals(dryRun.beforeStateSha256())) {
      throw new IllegalStateException(
          "HISTORICAL_ADJUDICATION_SOURCE_CHANGED: create a new dry-run receipt");
    }
    UUID appliedId = UUID.randomUUID();
    jdbc.update(
        INSERT_SQL,
        appliedId,
        Stage.APPLIED.name(),
        dryRun.recordKind().name(),
        dryRun.recordId(),
        dryRun.runId(),
        dryRun.action().name(),
        dryRun.sourceDisposition(),
        dryRun.effectiveDisposition(),
        dryRun.retentionHold(),
        dryRun.beforeStateBytes(),
        dryRun.beforeStateSha256(),
        dryRun.proposedChangeBytes(),
        dryRun.proposedChangeSha256(),
        dryRun.id(),
        actorUserId,
        operatorReason);
    return read(appliedId);
  }

  @Transactional(readOnly = true)
  public Receipt read(UUID id) {
    return queryReceipt(id, false);
  }

  private Receipt readLocked(UUID id) {
    return queryReceipt(id, true);
  }

  private Receipt queryReceipt(UUID id, boolean lock) {
    String sql =
        "SELECT * FROM workspace_generation_historical_adjudication WHERE id = ?"
            + (lock ? " FOR UPDATE" : "");
    return jdbc
        .query(
            sql,
            (rs, row) ->
                new Receipt(
                    rs.getObject("id", UUID.class),
                    rs.getLong("receipt_sequence"),
                    Stage.valueOf(rs.getString("stage")),
                    RecordKind.valueOf(rs.getString("record_kind")),
                    rs.getString("record_id"),
                    rs.getString("run_id"),
                    Action.valueOf(rs.getString("action")),
                    rs.getString("source_disposition"),
                    rs.getString("effective_disposition"),
                    rs.getBoolean("retention_hold"),
                    rs.getString("before_state_bytes"),
                    rs.getString("before_state_sha256"),
                    rs.getString("proposed_change_bytes"),
                    rs.getString("proposed_change_sha256"),
                    rs.getObject("dry_run_id", UUID.class),
                    rs.getString("actor_user_id"),
                    rs.getString("operator_reason"),
                    rs.getTimestamp("created_at").toInstant(),
                    true,
                    true),
            id)
        .stream()
        .findFirst()
        .orElseThrow(
            () -> new IllegalArgumentException("Historical adjudication receipt not found"));
  }

  private Source loadSource(RecordKind kind, String recordId, boolean lock) {
    if (recordId == null || recordId.isBlank()) {
      throw new IllegalArgumentException("recordId is required");
    }
    String lockClause = lock ? " FOR SHARE" : "";
    String sql =
        switch (kind) {
          case RUN ->
              """
SELECT p.run_id::text AS run_id, p.build_protocol_disposition::text AS source_disposition,
       jsonb_build_object(
           'recordKind', 'RUN', 'runId', p.run_id, 'runMode', p.run_mode,
           'protocolVersion', COALESCE(p.build_protocol_version, 'LEGACY_UNKNOWN'),
           'sourceDisposition', p.build_protocol_disposition, 'productState', p.status,
           'statusReason', p.status_reason,
           'acceptedGeneration', p.accepted_workspace_generation,
           'acceptedTaskIndex', p.accepted_task_index,
           'acceptedCheckpointKind', p.accepted_checkpoint_kind,
           'acceptedCheckpointDigest', p.accepted_checkpoint_digest,
           'workspaceReferenceRecorded',
               (NULLIF(p.workspace_path, '') IS NOT NULL
                   OR NULLIF(p.gcs_workspace_key, '') IS NOT NULL),
           'checkpointReferenceRecorded',
               (NULLIF(p.accepted_checkpoint_key, '') IS NOT NULL
                   AND NULLIF(p.accepted_checkpoint_digest, '') IS NOT NULL)
       )::text AS before_state_bytes,
       (NULLIF(p.workspace_path, '') IS NOT NULL OR NULLIF(p.gcs_workspace_key, '') IS NOT NULL)
           AS workspace_reference_recorded,
       (NULLIF(p.accepted_checkpoint_key, '') IS NOT NULL
           AND NULLIF(p.accepted_checkpoint_digest, '') IS NOT NULL)
           AS checkpoint_reference_recorded
  FROM pipeline_run p
 WHERE p.run_id = ? AND p.run_mode = 'BUILD'
   AND COALESCE(p.build_protocol_version, 'LEGACY_UNKNOWN') = 'LEGACY_UNKNOWN'
"""
                  + lockClause;
          case ATTEMPT ->
              """
SELECT a.run_id::text AS run_id, a.historical_disposition::text AS source_disposition,
       jsonb_build_object(
           'recordKind', 'ATTEMPT', 'attemptId', a.id, 'runId', a.run_id,
           'taskId', a.task_id, 'taskIndex', a.task_index,
           'attemptNumber', a.attempt_number, 'attemptKind', a.attempt_kind,
           'productState', a.state,
           'protocolVersion', COALESCE(a.build_protocol_version, 'LEGACY_UNKNOWN'),
           'sourceDisposition', a.historical_disposition,
           'llmOperationId', a.llm_operation_id,
           'beforeGeneration', a.before_generation,
           'candidateGeneration', a.candidate_generation,
           'authorityDigest', a.authority_digest,
           'taskContractDigest', a.task_contract_digest,
           'beforeCheckpointKind', a.before_checkpoint_kind,
           'beforeCheckpointDigest', a.before_checkpoint_digest,
           'beforeCheckpointCompletedAt', a.before_checkpoint_completed_at,
           'checkpointKind', a.checkpoint_kind,
           'checkpointGeneration', a.checkpoint_generation,
           'checkpointDigest', a.checkpoint_digest,
           'checkpointCompletedAt', a.checkpoint_completed_at,
           'rejectedDiffDigest', a.rejected_diff_digest,
           'failureCode', a.failure_code,
           'recoveryFailureCode', a.recovery_failure_code,
           'resolvedAt', a.resolved_at,
           'taskTransitionState', a.task_transition_state
       )::text AS before_state_bytes,
       (NULLIF(a.workspace_root, '') IS NOT NULL) AS workspace_reference_recorded,
       ((NULLIF(a.before_checkpoint_key, '') IS NOT NULL
              AND NULLIF(a.before_checkpoint_digest, '') IS NOT NULL)
          OR (NULLIF(a.checkpoint_key, '') IS NOT NULL
              AND NULLIF(a.checkpoint_digest, '') IS NOT NULL))
           AS checkpoint_reference_recorded
  FROM workspace_generation_attempt a
 WHERE a.id::text = ?
   AND COALESCE(a.build_protocol_version, 'LEGACY_UNKNOWN') = 'LEGACY_UNKNOWN'
"""
                  + lockClause;
        };
    return jdbc
        .query(
            sql,
            (rs, row) ->
                new Source(
                    recordId,
                    rs.getString("run_id"),
                    rs.getString("source_disposition"),
                    rs.getString("before_state_bytes"),
                    rs.getBoolean("workspace_reference_recorded"),
                    rs.getBoolean("checkpoint_reference_recorded")),
            recordId)
        .stream()
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Unpinned historical BUILD " + kind.name().toLowerCase() + " not found"));
  }

  private void insert(
      UUID id,
      Stage stage,
      RecordKind kind,
      Source source,
      Action action,
      String effectiveDisposition,
      boolean retentionHold,
      String proposed,
      UUID dryRunId,
      String actorUserId,
      String operatorReason) {
    jdbc.update(
        INSERT_SQL,
        id,
        stage.name(),
        kind.name(),
        source.recordId(),
        source.runId(),
        action.name(),
        source.sourceDisposition(),
        effectiveDisposition,
        retentionHold,
        source.beforeStateBytes(),
        sha256(source.beforeStateBytes()),
        proposed,
        sha256(proposed),
        dryRunId,
        actorUserId,
        operatorReason);
  }

  private String proposedChange(
      Action action,
      String sourceDisposition,
      String effectiveDisposition,
      boolean retentionHold,
      Source source) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schemaVersion", 1);
    value.put("action", action.name());
    value.put("sourceDisposition", sourceDisposition);
    value.put("effectiveDisposition", effectiveDisposition);
    value.put("protocolVersion", BuildProtocolVersion.LEGACY_UNKNOWN.name());
    value.put("sourceRowsMutated", 0);
    value.put("publicationBlocked", true);
    value.put("retentionHold", retentionHold);
    value.put("workspaceReferenceRecorded", source.workspaceReferenceRecorded());
    value.put("checkpointReferenceRecorded", source.checkpointReferenceRecorded());
    value.put("reversibility", "APPEND_ONLY_FORWARD_RECEIPT");
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException impossible) {
      throw new IllegalStateException(
          "Cannot serialize historical adjudication proposal", impossible);
    }
  }

  private static void requireReason(String reason) {
    if (reason == null || reason.length() < 30 || reason.length() > 2000) {
      throw new IllegalArgumentException("operatorReason must contain 30 to 2000 characters");
    }
  }

  private static void requireActor(String actorUserId) {
    if (actorUserId == null || actorUserId.isBlank()) {
      throw new IllegalArgumentException("Authenticated operator identity is required");
    }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public enum Stage {
    DRY_RUN,
    APPLIED
  }

  public enum RecordKind {
    RUN,
    ATTEMPT
  }

  public enum Action {
    KEEP_QUARANTINED,
    CLOSE_AS_NON_EXECUTABLE,
    RELEASE_FOR_DELETION
  }

  private record Source(
      String recordId,
      String runId,
      String sourceDisposition,
      String beforeStateBytes,
      boolean workspaceReferenceRecorded,
      boolean checkpointReferenceRecorded) {}

  public record Receipt(
      UUID id,
      long receiptSequence,
      Stage stage,
      RecordKind recordKind,
      String recordId,
      String runId,
      Action action,
      String sourceDisposition,
      String effectiveDisposition,
      boolean retentionHold,
      String beforeStateBytes,
      String beforeStateSha256,
      String proposedChangeBytes,
      String proposedChangeSha256,
      UUID dryRunId,
      String actorUserId,
      String operatorReason,
      Instant createdAt,
      boolean sourceRowsUnchanged,
      boolean publicationBlocked) {}
}
