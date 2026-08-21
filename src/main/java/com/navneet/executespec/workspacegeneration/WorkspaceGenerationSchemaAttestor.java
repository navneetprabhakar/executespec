package com.navneet.executespec.workspacegeneration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Read-only attestation of the PostgreSQL controls required before shared BUILD admission. */
@Service
@RequiredArgsConstructor
public class WorkspaceGenerationSchemaAttestor {

  private static final int REQUIRED_MIGRATION = 400;
  private static final List<ExpectedArtifact> EXPECTED =
      List.of(
          new ExpectedArtifact("INDEX", "uq_workspace_generation_attempt_active_run"),
          new ExpectedArtifact("TRIGGER", "workspace_generation_attempt_admission"),
          new ExpectedArtifact("TRIGGER", "workspace_generation_attempt_predispatch_transition"),
          new ExpectedArtifact("TRIGGER", "workspace_generation_attempt_transition"),
          new ExpectedArtifact("TRIGGER", "build_task_complete_fence"),
          new ExpectedArtifact("TRIGGER", "build_run_terminal_fence"),
          new ExpectedArtifact("TRIGGER", "workspace_generation_rollback_authority_fence"),
          new ExpectedArtifact("TRIGGER", "workspace_generation_task_transition_bind"),
          new ExpectedArtifact("TRIGGER", "workspace_generation_task_transition_receipt"),
          new ExpectedArtifact("TRIGGER", "workspace_generation_attempt_00_operation_intent"),
          new ExpectedArtifact("TRIGGER", "workspace_generation_operation_transition_receipt"),
          new ExpectedArtifact(
              "TRIGGER", "workspace_generation_attempt_operation_transition_admission"),
          new ExpectedArtifact("TRIGGER", "pipeline_run_build_protocol_version_immutable"),
          new ExpectedArtifact("TRIGGER", "workspace_generation_build_protocol_version_binding"),
          new ExpectedArtifact(
              "TRIGGER", "workspace_generation_historical_adjudication_append_only"),
          new ExpectedArtifact(
              "TRIGGER", "workspace_generation_historical_adjudication_apply_binding"),
          new ExpectedArtifact("INDEX", "uq_workspace_generation_historical_adjudication_apply"),
          new ExpectedArtifact("TRIGGER", "pipeline_run_legacy_build_history_retention_hold"),
          new ExpectedArtifact("INDEX", "uq_run_sandbox_active_run_purpose"),
          new ExpectedArtifact("INDEX", "idx_sandbox_command_recovery"),
          new ExpectedArtifact("INDEX", "idx_sandbox_command_unsettled_recovery"),
          new ExpectedArtifact("TRIGGER", "workspace_generation_command_protocol_immutable"),
          new ExpectedArtifact("TRIGGER", "sandbox_command_insert_admitted_guard"),
          new ExpectedArtifact("TRIGGER", "sandbox_command_transition_guard"),
          new ExpectedArtifact("TRIGGER", "sandbox_command_terminal_receipt_guard"),
          new ExpectedArtifact("TRIGGER", "sandbox_command_receipt_identity_guard"),
          new ExpectedArtifact("TRIGGER", "sandbox_command_receipt_no_update"),
          new ExpectedArtifact("TRIGGER", "sandbox_command_receipt_no_delete"),
          new ExpectedArtifact("TRIGGER", "workspace_generation_command_receipts_guard"),
          new ExpectedArtifact("INDEX", "uq_workspace_publication_active_per_run"),
          new ExpectedArtifact("INDEX", "uq_workspace_publication_attempt_active_repo"),
          new ExpectedArtifact("INDEX", "uq_workspace_publication_attempt_sequence"),
          new ExpectedArtifact("INDEX", "idx_workspace_publication_preparation_recovery"),
          new ExpectedArtifact("INDEX", "idx_workspace_publication_attempt_recovery"),
          new ExpectedArtifact("INDEX", "uq_workspace_publication_provision_sequence"),
          new ExpectedArtifact("INDEX", "uq_workspace_publication_provision_active_repo"),
          new ExpectedArtifact("INDEX", "idx_workspace_publication_provision_recovery"),
          new ExpectedArtifact("TRIGGER", "workspace_publication_preparation_insert_guard"),
          new ExpectedArtifact("TRIGGER", "workspace_publication_preparation_transition_guard"),
          new ExpectedArtifact("TRIGGER", "workspace_publication_repository_insert_guard"),
          new ExpectedArtifact("TRIGGER", "workspace_publication_repository_transition_guard"),
          new ExpectedArtifact("TRIGGER", "workspace_publication_attempt_insert_guard"),
          new ExpectedArtifact("TRIGGER", "workspace_publication_attempt_transition_guard"),
          new ExpectedArtifact("TRIGGER", "workspace_publication_provision_insert_guard"),
          new ExpectedArtifact("TRIGGER", "workspace_publication_provision_transition_guard"),
          new ExpectedArtifact("TRIGGER", "workspace_publication_preparation_delete_guard"),
          new ExpectedArtifact("TRIGGER", "workspace_publication_repository_delete_guard"),
          new ExpectedArtifact("TRIGGER", "workspace_publication_attempt_delete_guard"),
          new ExpectedArtifact("TRIGGER", "workspace_publication_provision_delete_guard"),
          new ExpectedArtifact("INDEX", "idx_workspace_generation_recovery_cleanup_claim"),
          new ExpectedArtifact("TRIGGER", "trg_workspace_checkpoint_storage_identity"),
          new ExpectedArtifact("TRIGGER", "trg_workspace_verification_typed_causality"),
          new ExpectedArtifact("INDEX", "idx_workspace_generation_environment_attempt"),
          new ExpectedArtifact("TRIGGER", "trg_workspace_generation_environment_receipt_no_update"),
          new ExpectedArtifact("TRIGGER", "trg_workspace_generation_environment_receipt_gate"),
          new ExpectedArtifact("INDEX", "idx_workspace_generation_step_recovery"),
          new ExpectedArtifact("INDEX", "idx_workspace_generation_step_attempt"),
          new ExpectedArtifact("TRIGGER", "trg_workspace_generation_step_fence"),
          new ExpectedArtifact("TRIGGER", "trg_workspace_generation_step_no_delete"),
          new ExpectedArtifact("INDEX", "idx_workspace_generation_archive_cleanup_claim"),
          new ExpectedArtifact("INDEX", "idx_workspace_generation_reconciliation_open"),
          new ExpectedArtifact("INDEX", "idx_workspace_generation_reconciliation_dead_letter"));

  private final JdbcTemplate jdbc;

  public Attestation attest() {
    Instant checkedAt = Instant.now();
    try {
      String latest =
          jdbc
              .query(
                  "SELECT version FROM flyway_schema_history "
                      + "WHERE success = true AND version IS NOT NULL "
                      + "ORDER BY installed_rank DESC LIMIT 1",
                  (rs, row) -> rs.getString(1))
              .stream()
              .findFirst()
              .orElse(null);
      List<Artifact> artifacts = new ArrayList<>(EXPECTED.size());
      for (ExpectedArtifact expected : EXPECTED) {
        ArtifactState state = state(expected);
        artifacts.add(
            new Artifact(
                expected.kind(),
                expected.name(),
                state != null,
                state != null && state.active(),
                state == null ? null : sha256(state.definition())));
      }
      boolean migrationReady = parseMajor(latest) >= REQUIRED_MIGRATION;
      boolean ready =
          migrationReady
              && artifacts.stream().allMatch(artifact -> artifact.present() && artifact.active());
      return new Attestation(
          1,
          checkedAt,
          true,
          latest,
          REQUIRED_MIGRATION,
          artifacts,
          ready,
          ready ? null : "SCHEMA_INCOMPLETE");
    } catch (DataAccessException failure) {
      return new Attestation(
          1,
          checkedAt,
          false,
          null,
          REQUIRED_MIGRATION,
          List.of(),
          false,
          "SCHEMA_ATTESTATION_UNAVAILABLE");
    }
  }

  public Attestation requireReady() {
    Attestation attestation = attest();
    if (!attestation.ready()) {
      throw new IllegalStateException(
          "BUILD_PROTOCOL_SCHEMA_ATTESTATION_FAILED:" + attestation.reason());
    }
    return attestation;
  }

  private ArtifactState state(ExpectedArtifact expected) {
    if ("INDEX".equals(expected.kind())) {
      return jdbc
          .query(
              "SELECT pg_get_indexdef(i.indexrelid), (i.indisvalid AND i.indisready) "
                  + "FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
                  + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                  + "WHERE n.nspname = current_schema() AND c.relname = ?",
              (rs, row) -> new ArtifactState(rs.getString(1), rs.getBoolean(2)),
              expected.name())
          .stream()
          .findFirst()
          .orElse(null);
    }
    return jdbc
        .query(
            "SELECT pg_get_triggerdef(t.oid, true), (t.tgenabled IN ('O','A')) FROM pg_trigger t "
                + "JOIN pg_class c ON c.oid = t.tgrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = current_schema() AND t.tgname = ? AND NOT t.tgisinternal",
            (rs, row) -> new ArtifactState(rs.getString(1), rs.getBoolean(2)),
            expected.name())
        .stream()
        .findFirst()
        .orElse(null);
  }

  private static int parseMajor(String version) {
    if (version == null) return -1;
    int dot = version.indexOf('.');
    String major = dot < 0 ? version : version.substring(0, dot);
    try {
      return Integer.parseInt(major);
    } catch (NumberFormatException ignored) {
      return -1;
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

  private record ExpectedArtifact(String kind, String name) {}

  private record ArtifactState(String definition, boolean active) {}

  public record Artifact(
      String kind, String name, boolean present, boolean active, String definitionSha256) {}

  public record Attestation(
      int schemaVersion,
      Instant checkedAt,
      boolean databaseReachable,
      String latestSuccessfulMigration,
      int requiredMigration,
      List<Artifact> artifacts,
      boolean ready,
      String reason) {}
}
