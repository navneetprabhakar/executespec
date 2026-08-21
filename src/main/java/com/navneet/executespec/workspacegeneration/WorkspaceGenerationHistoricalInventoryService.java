package com.navneet.executespec.workspacegeneration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Read-only, digest-bound inventory of BUILD history whose execution protocol is unprovable. */
@Service
@RequiredArgsConstructor
public class WorkspaceGenerationHistoricalInventoryService {

  private static final String INVENTORY_SQL =
      """
SELECT record_kind, record_id, run_id, task_id, protocol_version, source_disposition, disposition,
       product_state, operation_state, workspace_reference_recorded,
       checkpoint_reference_recorded, transition_pending, retention_hold,
       latest_applied_action, adjudicated, safe_reason
  FROM (
    SELECT 'RUN'::text AS record_kind,
           p.run_id::text AS record_id,
           p.run_id::text AS run_id,
           NULL::text AS task_id,
           COALESCE(p.build_protocol_version, 'LEGACY_UNKNOWN')::text AS protocol_version,
           p.build_protocol_disposition::text AS source_disposition,
           COALESCE(d.effective_disposition, p.build_protocol_disposition)::text AS disposition,
           COALESCE(p.status, 'UNKNOWN')::text AS product_state,
           NULL::text AS operation_state,
           (NULLIF(p.workspace_path, '') IS NOT NULL OR NULLIF(p.gcs_workspace_key, '') IS NOT NULL)
               AS workspace_reference_recorded,
           (NULLIF(p.accepted_checkpoint_key, '') IS NOT NULL
               AND NULLIF(p.accepted_checkpoint_digest, '') IS NOT NULL)
               AS checkpoint_reference_recorded,
           false AS transition_pending,
           COALESCE(h.retention_hold, TRUE) AS retention_hold,
           h.action::text AS latest_applied_action,
           (d.id IS NOT NULL) AS adjudicated,
           CASE
             WHEN p.build_protocol_disposition = 'QUARANTINED_CONFLICT'
               THEN 'HISTORICAL_TERMINAL_CAUSALITY_CONFLICT'
             WHEN EXISTS (SELECT 1 FROM workspace_generation_attempt a WHERE a.run_id = p.run_id)
               THEN 'HISTORICAL_BUILD_PROTOCOL_UNPINNED'
             ELSE 'PRE_PROTOCOL_BUILD_WITHOUT_GENERATION_ATTEMPT'
           END::text AS safe_reason
      FROM pipeline_run p
      LEFT JOIN LATERAL (
          SELECT a.id, a.effective_disposition
            FROM workspace_generation_historical_adjudication a
           WHERE a.stage = 'APPLIED' AND a.record_kind = 'RUN' AND a.record_id = p.run_id
             AND a.action <> 'RELEASE_FOR_DELETION'
           ORDER BY a.receipt_sequence DESC LIMIT 1
      ) d ON TRUE
      LEFT JOIN LATERAL (
          SELECT a.action, a.retention_hold
            FROM workspace_generation_historical_adjudication a
           WHERE a.stage = 'APPLIED' AND a.record_kind = 'RUN' AND a.record_id = p.run_id
           ORDER BY a.receipt_sequence DESC LIMIT 1
      ) h ON TRUE
     WHERE p.run_mode = 'BUILD'
       AND (p.build_protocol_version IS NULL OR p.build_protocol_version = 'LEGACY_UNKNOWN')
    UNION ALL
    SELECT 'ATTEMPT'::text AS record_kind,
           a.id::text AS record_id,
           a.run_id::text AS run_id,
           a.task_id::text AS task_id,
           COALESCE(a.build_protocol_version, 'LEGACY_UNKNOWN')::text AS protocol_version,
           a.historical_disposition::text AS source_disposition,
           COALESCE(d.effective_disposition, a.historical_disposition)::text AS disposition,
           a.state::text AS product_state,
           COALESCE(o.status, 'NO_OPERATION')::text AS operation_state,
           (NULLIF(a.workspace_root, '') IS NOT NULL) AS workspace_reference_recorded,
           ((NULLIF(a.before_checkpoint_key, '') IS NOT NULL
                 AND NULLIF(a.before_checkpoint_digest, '') IS NOT NULL)
              OR (NULLIF(a.checkpoint_key, '') IS NOT NULL
                 AND NULLIF(a.checkpoint_digest, '') IS NOT NULL))
               AS checkpoint_reference_recorded,
           (a.task_transition_state = 'PENDING') AS transition_pending,
           COALESCE(h.retention_hold, TRUE) AS retention_hold,
           h.action::text AS latest_applied_action,
           (d.id IS NOT NULL) AS adjudicated,
           CASE
             WHEN a.historical_disposition = 'QUARANTINED_CONFLICT'
               THEN 'HISTORICAL_TERMINAL_CAUSALITY_CONFLICT'
             WHEN a.llm_operation_id IS NULL THEN 'HISTORICAL_OPERATION_EVIDENCE_MISSING'
             WHEN o.id IS NULL THEN 'HISTORICAL_OPERATION_REFERENCE_MISSING'
             WHEN a.before_checkpoint_key IS NULL THEN 'HISTORICAL_BEFORE_CHECKPOINT_MISSING'
             ELSE 'HISTORICAL_BUILD_PROTOCOL_UNPINNED'
           END::text AS safe_reason
      FROM workspace_generation_attempt a
      LEFT JOIN llm_execution_operation o ON o.id = a.llm_operation_id
      LEFT JOIN LATERAL (
          SELECT x.id, x.effective_disposition
            FROM workspace_generation_historical_adjudication x
           WHERE x.stage = 'APPLIED' AND x.record_kind = 'ATTEMPT'
             AND x.record_id = a.id::text AND x.action <> 'RELEASE_FOR_DELETION'
           ORDER BY x.receipt_sequence DESC LIMIT 1
      ) d ON TRUE
      LEFT JOIN LATERAL (
          SELECT x.action, x.retention_hold
            FROM workspace_generation_historical_adjudication x
           WHERE x.stage = 'APPLIED' AND x.record_kind = 'ATTEMPT'
             AND x.record_id = a.id::text
           ORDER BY x.receipt_sequence DESC LIMIT 1
      ) h ON TRUE
     WHERE a.build_protocol_version IS NULL OR a.build_protocol_version = 'LEGACY_UNKNOWN'
  ) inventory
 ORDER BY run_id, record_kind, record_id
""";

  private final JdbcTemplate jdbc;

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Inventory capture() {
    List<Record> records =
        jdbc.query(
            INVENTORY_SQL,
            (rs, row) ->
                new Record(
                    rs.getString("record_kind"),
                    rs.getString("record_id"),
                    rs.getString("run_id"),
                    rs.getString("task_id"),
                    rs.getString("protocol_version"),
                    rs.getString("source_disposition"),
                    rs.getString("disposition"),
                    rs.getString("product_state"),
                    rs.getString("operation_state"),
                    rs.getBoolean("workspace_reference_recorded"),
                    rs.getBoolean("checkpoint_reference_recorded"),
                    rs.getBoolean("transition_pending"),
                    rs.getBoolean("retention_hold"),
                    rs.getString("latest_applied_action"),
                    rs.getBoolean("adjudicated"),
                    rs.getString("safe_reason")));
    Map<String, Long> byDisposition = new LinkedHashMap<>();
    for (String disposition :
        List.of(
            "AUTO_RECOVERABLE",
            "QUARANTINED_CONFLICT",
            "MISSING_EVIDENCE",
            "OPERATOR_RESOLVED",
            "NOT_APPLICABLE")) {
      byDisposition.put(disposition, 0L);
    }
    records.forEach(
        record ->
            byDisposition.compute(
                record.disposition(), (ignored, count) -> count == null ? 1L : count + 1L));
    long runs = records.stream().filter(record -> "RUN".equals(record.recordKind())).count();
    long attempts = records.size() - runs;
    return new Inventory(
        1,
        Instant.now(),
        records.size(),
        runs,
        attempts,
        Map.copyOf(byDisposition),
        snapshotDigest(records),
        List.copyOf(records));
  }

  private static String snapshotDigest(List<Record> records) {
    StringBuilder canonical = new StringBuilder();
    for (Record record : records) {
      append(canonical, record.recordKind());
      append(canonical, record.recordId());
      append(canonical, record.runId());
      append(canonical, record.taskId());
      append(canonical, record.protocolVersion());
      append(canonical, record.sourceDisposition());
      append(canonical, record.disposition());
      append(canonical, record.productState());
      append(canonical, record.operationState());
      append(canonical, Boolean.toString(record.workspaceReferenceRecorded()));
      append(canonical, Boolean.toString(record.checkpointReferenceRecorded()));
      append(canonical, Boolean.toString(record.transitionPending()));
      append(canonical, Boolean.toString(record.retentionHold()));
      append(canonical, record.latestAppliedAction());
      append(canonical, Boolean.toString(record.adjudicated()));
      append(canonical, record.safeReason());
      canonical.append('\n');
    }
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void append(StringBuilder target, String value) {
    String safe = value == null ? "-" : value;
    target.append(safe.length()).append(':').append(safe).append('|');
  }

  public record Record(
      String recordKind,
      String recordId,
      String runId,
      String taskId,
      String protocolVersion,
      String sourceDisposition,
      String disposition,
      String productState,
      String operationState,
      boolean workspaceReferenceRecorded,
      boolean checkpointReferenceRecorded,
      boolean transitionPending,
      boolean retentionHold,
      String latestAppliedAction,
      boolean adjudicated,
      String safeReason) {}

  public record Inventory(
      int schemaVersion,
      Instant capturedAt,
      long totalRecords,
      long runRecords,
      long attemptRecords,
      Map<String, Long> byDisposition,
      String snapshotSha256,
      List<Record> records) {}
}
