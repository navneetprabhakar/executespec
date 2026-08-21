package com.navneet.executespec.workspacegeneration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Append-only persistence for environment materialization and ignored-root cleanup receipts. */
@Component
@RequiredArgsConstructor
class BuildEnvironmentReceiptStore {

  private final JdbcTemplate jdbc;

  void recordMaterialization(
      UUID attemptId,
      String phase,
      BuildEnvironmentPlan plan,
      String disposition,
      String evidenceJson,
      String evidenceDigest) {
    int inserted =
        jdbc.update(
            """
            INSERT INTO workspace_generation_environment_receipt
              (id,attempt_id,phase,receipt_kind,plan_digest,runtime_image,network_policy,
               manifest_digest,disposition,evidence_json,evidence_digest)
            VALUES (?,?,?,'MATERIALIZATION',?,?,?,?,?,CAST(? AS jsonb),?)
            ON CONFLICT (attempt_id,phase) DO NOTHING
            """,
            UUID.randomUUID(),
            attemptId,
            phase,
            plan.planDigest(),
            plan.runtimeImage(),
            plan.networkPolicy(),
            plan.manifestDigest(),
            disposition,
            evidenceJson,
            evidenceDigest);
    if (inserted != 1) {
      throw new IllegalStateException("BUILD_ENVIRONMENT_RECEIPT_ALREADY_EXISTS");
    }
  }

  void recordCleanup(
      UUID attemptId,
      String phase,
      String removedRootsJson,
      String removedRootsDigest,
      String evidenceJson,
      String evidenceDigest) {
    int inserted =
        jdbc.update(
            """
            INSERT INTO workspace_generation_environment_receipt
              (id,attempt_id,phase,receipt_kind,removed_roots_json,removed_roots_digest,
               disposition,evidence_json,evidence_digest)
            VALUES (?,?,?,'CLEANUP',CAST(? AS jsonb),?,'PASSED',CAST(? AS jsonb),?)
            ON CONFLICT (attempt_id,phase) DO NOTHING
            """,
            UUID.randomUUID(),
            attemptId,
            phase,
            removedRootsJson,
            removedRootsDigest,
            evidenceJson,
            evidenceDigest);
    if (inserted != 1) {
      throw new IllegalStateException("BUILD_ENVIRONMENT_RECEIPT_ALREADY_EXISTS");
    }
  }

  void requirePassed(UUID attemptId, String... phases) {
    if (attemptId == null || phases == null || phases.length == 0) {
      throw new IllegalArgumentException("BUILD_ENVIRONMENT_RECEIPT_REQUIREMENT_INVALID");
    }
    List<String> required = java.util.Arrays.stream(phases).distinct().sorted().toList();
    String placeholders =
        required.stream().map(ignored -> "?").collect(java.util.stream.Collectors.joining(","));
    java.util.List<Object> arguments = new java.util.ArrayList<>();
    arguments.add(attemptId);
    arguments.addAll(required);
    List<String> passed =
        jdbc.query(
            "SELECT phase FROM workspace_generation_environment_receipt "
                + "WHERE attempt_id = ? AND receipt_kind = 'MATERIALIZATION' "
                + "AND disposition = 'PASSED' AND phase IN ("
                + placeholders
                + ") ORDER BY phase",
            (result, row) -> result.getString("phase"),
            arguments.toArray());
    if (!passed.equals(required)) {
      throw new IllegalStateException("BUILD_ENVIRONMENT_PASSED_RECEIPT_REQUIRED");
    }
  }

  List<ReceiptEvidence> readAll(UUID attemptId) {
    if (attemptId == null) throw new IllegalArgumentException("BUILD_ENVIRONMENT_ATTEMPT_REQUIRED");
    return jdbc.query(
        """
        SELECT id,phase,receipt_kind,plan_digest,runtime_image,network_policy,manifest_digest,
               disposition,removed_roots_json::text,removed_roots_digest,evidence_json::text,
               evidence_digest,created_at
          FROM workspace_generation_environment_receipt
         WHERE attempt_id = ?
         ORDER BY created_at,id
        """,
        (result, row) ->
            new ReceiptEvidence(
                result.getObject("id", UUID.class),
                result.getString("phase"),
                result.getString("receipt_kind"),
                result.getString("plan_digest"),
                result.getString("runtime_image"),
                result.getString("network_policy"),
                result.getString("manifest_digest"),
                result.getString("disposition"),
                result.getString("removed_roots_json"),
                result.getString("removed_roots_digest"),
                result.getString("evidence_json"),
                result.getString("evidence_digest"),
                result.getObject("created_at", java.time.OffsetDateTime.class).toInstant()),
        attemptId);
  }

  record ReceiptEvidence(
      UUID receiptId,
      String phase,
      String receiptKind,
      String planDigest,
      String runtimeImage,
      String networkPolicy,
      String manifestDigest,
      String disposition,
      String removedRootsJson,
      String removedRootsDigest,
      String evidenceJson,
      String evidenceDigest,
      Instant createdAt) {}
}
