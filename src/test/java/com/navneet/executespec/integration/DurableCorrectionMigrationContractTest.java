package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class DurableCorrectionMigrationContractTest {

  @Test
  void v369PersistsRecoveryAuthorityAndSingleActiveRunFence() throws Exception {
    String sql =
        Files.readString(
            Path.of("src/main/resources/db/migration/V369__durable_correction_attempt.sql"));

    assertThat(sql)
        .contains("CREATE TABLE correction_attempt")
        .contains("run_id VARCHAR(255) NOT NULL REFERENCES pipeline_run(run_id)")
        .contains("repositories_json JSONB NOT NULL")
        .contains("before_generation CHAR(64) NOT NULL")
        .contains("evidence_snapshot_json JSONB")
        .contains("snapshot_schema_version INTEGER NOT NULL DEFAULT 1")
        .contains("evidence_snapshot_digest CHAR(64) NOT NULL")
        .contains("approval_id VARCHAR(36)")
        .contains("proposal_digest CHAR(64)")
        .contains("lease_token UUID NOT NULL")
        .contains("task_snapshot_json JSONB NOT NULL")
        .contains("CREATE TABLE run_workspace_authority")
        .contains("owner_token UUID NOT NULL")
        .contains("lease_expires_at TIMESTAMPTZ NOT NULL")
        .contains("authority_type IN ('CORRECTION', 'RECOVERY', 'PUBLICATION')")
        .contains("CREATE UNIQUE INDEX uq_correction_attempt_active_run")
        .contains("'RECOVERY_FAILED'");
  }
}
