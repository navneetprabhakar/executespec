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

  /**
   * V369 shipped the digest columns as CHAR(64) (bpchar), which Hibernate schema-validation rejects
   * against the entity's VARCHAR(64) mapping — the dev deployment crash-looped on boot. Applied
   * migrations are immutable, so V370 must convert every digest column to VARCHAR(64) in place;
   * this pins the repair so the entity mapping and the migrated schema cannot drift apart again on
   * these columns.
   */
  @Test
  void v370ConvertsEveryCharDigestColumnToVarchar() throws Exception {
    String sql =
        Files.readString(
            Path.of(
                "src/main/resources/db/migration/V370__correction_attempt_varchar_digests.sql"));

    assertThat(sql)
        .contains("ALTER TABLE correction_attempt")
        .contains("ALTER COLUMN before_generation TYPE VARCHAR(64)")
        .contains("ALTER COLUMN after_generation TYPE VARCHAR(64)")
        .contains("ALTER COLUMN evidence_workspace_generation TYPE VARCHAR(64)")
        .contains("ALTER COLUMN evidence_snapshot_digest TYPE VARCHAR(64)")
        .contains("ALTER COLUMN proposal_digest TYPE VARCHAR(64)");

    String v369 =
        Files.readString(
            Path.of("src/main/resources/db/migration/V369__durable_correction_attempt.sql"));
    java.util.regex.Matcher charColumns =
        java.util.regex.Pattern.compile("(\\w+) CHAR\\(64\\)").matcher(v369);
    while (charColumns.find()) {
      assertThat(sql)
          .as("every V369 CHAR(64) column must be converted by V370")
          .contains("ALTER COLUMN " + charColumns.group(1) + " TYPE VARCHAR(64)");
    }
  }
}
