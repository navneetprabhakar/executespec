package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL proof for byte-bound adjudication, immutable receipts, and quarantine retention. */
@Tag("integration")
@Testcontainers
class WorkspaceGenerationHistoricalAdjudicationPostgresTest {

  private static final String REASON =
      "Operator reviewed the incomplete historical BUILD evidence and recorded this decision.";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("workspace_generation_adjudication")
          .withUsername("test")
          .withPassword("test");

  private static JdbcTemplate jdbc;

  @BeforeAll
  static void migrateDatabase() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
  }

  @Test
  void adjudicationNeverRewritesSourceAndDeletionRequiresAnExactReleaseReceipt() {
    String runId = "legacy-adjudication";
    jdbc.update(
        "INSERT INTO pipeline_run(run_id,status,run_mode,user_request)"
            + " VALUES (?,'FAILED','BUILD','original historical request')",
        runId);
    WorkspaceGenerationHistoricalAdjudicationService service =
        new WorkspaceGenerationHistoricalAdjudicationService(jdbc, new ObjectMapper());

    var closeDryRun =
        service.dryRun(
            WorkspaceGenerationHistoricalAdjudicationService.RecordKind.RUN,
            runId,
            WorkspaceGenerationHistoricalAdjudicationService.Action.CLOSE_AS_NON_EXECUTABLE,
            REASON,
            "operator-1");
    assertThat(closeDryRun.stage())
        .isEqualTo(WorkspaceGenerationHistoricalAdjudicationService.Stage.DRY_RUN);
    assertThat(closeDryRun.sourceDisposition()).isEqualTo("MISSING_EVIDENCE");
    assertThat(closeDryRun.effectiveDisposition()).isEqualTo("OPERATOR_RESOLVED");
    assertThat(closeDryRun.retentionHold()).isTrue();
    assertThat(closeDryRun.publicationBlocked()).isTrue();
    assertThat(closeDryRun.proposedChangeBytes())
        .contains("\"sourceRowsMutated\":0", "\"publicationBlocked\":true");
    assertThat(closeDryRun.beforeStateBytes())
        .doesNotContain("original historical request", "workspace_path", "gcs_workspace_key");
    assertThat(
            jdbc.queryForObject(
                "SELECT build_protocol_disposition FROM pipeline_run WHERE run_id=?",
                String.class,
                runId))
        .isEqualTo("MISSING_EVIDENCE");

    var appliedClose = service.apply(closeDryRun.id(), REASON, "operator-1");
    assertThat(appliedClose.stage())
        .isEqualTo(WorkspaceGenerationHistoricalAdjudicationService.Stage.APPLIED);
    assertThat(appliedClose.dryRunId()).isEqualTo(closeDryRun.id());
    assertThat(appliedClose.beforeStateBytes()).isEqualTo(closeDryRun.beforeStateBytes());
    assertThat(appliedClose.proposedChangeBytes()).isEqualTo(closeDryRun.proposedChangeBytes());

    var inventory = new WorkspaceGenerationHistoricalInventoryService(jdbc).capture();
    assertThat(inventory.records().stream().filter(record -> runId.equals(record.runId())))
        .singleElement()
        .satisfies(
            record -> {
              assertThat(record.sourceDisposition()).isEqualTo("MISSING_EVIDENCE");
              assertThat(record.disposition()).isEqualTo("OPERATOR_RESOLVED");
              assertThat(record.adjudicated()).isTrue();
              assertThat(record.retentionHold()).isTrue();
            });
    assertThatThrownBy(() -> jdbc.update("DELETE FROM pipeline_run WHERE run_id=?", runId))
        .rootCause()
        .hasMessageContaining("under retention hold");
    assertThatThrownBy(() -> service.apply(closeDryRun.id(), REASON, "operator-1"))
        .rootCause()
        .hasMessageContaining("uq_workspace_generation_historical_adjudication_apply");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_historical_adjudication"
                        + " SET operator_reason=? WHERE id=?",
                    REASON + " changed",
                    closeDryRun.id()))
        .rootCause()
        .hasMessageContaining("append-only");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "DELETE FROM workspace_generation_historical_adjudication WHERE id=?",
                    closeDryRun.id()))
        .rootCause()
        .hasMessageContaining("append-only");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO workspace_generation_historical_adjudication("
                        + "id,stage,record_kind,record_id,run_id,action,source_disposition,"
                        + "effective_disposition,retention_hold,before_state_bytes,"
                        + "before_state_sha256,proposed_change_bytes,proposed_change_sha256,"
                        + "dry_run_id,actor_user_id,operator_reason) SELECT ?, 'APPLIED',"
                        + "record_kind,record_id,run_id,action,source_disposition,"
                        + "effective_disposition,retention_hold,before_state_bytes,"
                        + "before_state_sha256,proposed_change_bytes || 'forged',"
                        + "proposed_change_sha256,id,'operator-2',?"
                        + " FROM workspace_generation_historical_adjudication WHERE id=?",
                    UUID.randomUUID(),
                    REASON,
                    closeDryRun.id()))
        .rootCause()
        .hasMessageContaining("does not match dry-run receipt");

    var releaseDryRun =
        service.dryRun(
            WorkspaceGenerationHistoricalAdjudicationService.RecordKind.RUN,
            runId,
            WorkspaceGenerationHistoricalAdjudicationService.Action.RELEASE_FOR_DELETION,
            REASON,
            "operator-2");
    var appliedRelease = service.apply(releaseDryRun.id(), REASON, "operator-2");
    assertThat(appliedRelease.retentionHold()).isFalse();
    assertThat(appliedRelease.effectiveDisposition()).isEqualTo("MISSING_EVIDENCE");
    assertThat(appliedRelease.receiptSequence()).isGreaterThan(appliedClose.receiptSequence());

    jdbc.update("UPDATE pipeline_run SET status_reason='LEGACY_UNKNOWN' WHERE run_id=?", runId);
    assertThatThrownBy(() -> jdbc.update("DELETE FROM pipeline_run WHERE run_id=?", runId))
        .rootCause()
        .hasMessageContaining("changed after deletion release");
    var refreshedRelease =
        service.dryRun(
            WorkspaceGenerationHistoricalAdjudicationService.RecordKind.RUN,
            runId,
            WorkspaceGenerationHistoricalAdjudicationService.Action.RELEASE_FOR_DELETION,
            REASON,
            "operator-2");
    service.apply(refreshedRelease.id(), REASON, "operator-2");
    assertThat(jdbc.update("DELETE FROM pipeline_run WHERE run_id=?", runId)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM workspace_generation_historical_adjudication WHERE run_id=?",
                Long.class,
                runId))
        .isEqualTo(6L);
  }

  @Test
  void applyFailsClosedWhenSourceChangedAfterDryRun() {
    String runId = "legacy-source-drift";
    jdbc.update(
        "INSERT INTO pipeline_run(run_id,status,run_mode,user_request)"
            + " VALUES (?,'FAILED','BUILD','before dry run')",
        runId);
    WorkspaceGenerationHistoricalAdjudicationService service =
        new WorkspaceGenerationHistoricalAdjudicationService(jdbc, new ObjectMapper());
    var dryRun =
        service.dryRun(
            WorkspaceGenerationHistoricalAdjudicationService.RecordKind.RUN,
            runId,
            WorkspaceGenerationHistoricalAdjudicationService.Action.KEEP_QUARANTINED,
            REASON,
            "operator-1");
    jdbc.update("UPDATE pipeline_run SET status_reason='FINAL_BUILD_FAILED' WHERE run_id=?", runId);

    assertThatThrownBy(() -> service.apply(dryRun.id(), REASON, "operator-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SOURCE_CHANGED");
  }
}
