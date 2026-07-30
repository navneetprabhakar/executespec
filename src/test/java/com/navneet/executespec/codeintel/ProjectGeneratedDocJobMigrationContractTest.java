package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ProjectGeneratedDocJobMigrationContractTest {

  @Test
  void migrationPinsDurableIdentityExactRouteAndOneActiveJob() throws Exception {
    String sql =
        Files.readString(
            Path.of("src/main/resources/db/migration/V358__durable_project_document_jobs.sql"));

    assertThat(sql)
        .contains("CREATE TABLE project_generated_doc_job")
        .contains("requested_provider VARCHAR(32) NOT NULL")
        .contains("requested_model VARCHAR(160) NOT NULL")
        .contains("uq_project_generated_doc_job_active")
        .contains("WHERE status IN ('QUEUED','RUNNING')")
        .contains("result_doc_id UUID REFERENCES project_generated_doc(id) ON DELETE SET NULL")
        .contains("d.version=NEW.result_doc_version")
        .contains("UPDATE OF project_id, org_id, doc_type, result_doc_id, result_doc_version")
        .contains("project_generated_doc_job project/org mismatch");
  }

  @Test
  void forwardMigrationPinsUncertainOutcomeAndConfirmedRetryReceipt() throws Exception {
    String sql =
        Files.readString(
            Path.of(
                "src/main/resources/db/migration/"
                    + "V363__confirm_uncertain_project_document_retries.sql"));

    assertThat(sql)
        .contains("ALTER COLUMN status TYPE VARCHAR(32)")
        .contains("ADD COLUMN provider_started_at TIMESTAMPTZ")
        .contains("'NEEDS_CONFIRMATION'")
        .contains("WHERE status IN ('QUEUED','RUNNING','NEEDS_CONFIRMATION')")
        .contains("CREATE TABLE project_generated_doc_job_retry_confirmation")
        .contains("confirmed_by_user_id VARCHAR(255) NOT NULL REFERENCES app_user(id)");
  }
}
