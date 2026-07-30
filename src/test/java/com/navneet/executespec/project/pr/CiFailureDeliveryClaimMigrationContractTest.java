package com.navneet.executespec.project.pr;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class CiFailureDeliveryClaimMigrationContractTest {

  @Test
  void migrationPinsUniqueAdmissionLifecycleAndRunBinding() throws Exception {
    String sql =
        Files.readString(
            Path.of("src/main/resources/db/migration/V359__durable_ci_failure_delivery_claim.sql"));

    assertThat(sql)
        .contains("finding_key VARCHAR(128) PRIMARY KEY")
        .contains("status IN ('CLAIMED','DISPATCHED','FAILED')")
        .contains("lease_expires_at TIMESTAMPTZ")
        .contains("run_id VARCHAR(255)")
        .doesNotContain("run_id VARCHAR(255) REFERENCES pipeline_run")
        .contains("status='DISPATCHED' AND completed_at IS NOT NULL AND run_id IS NOT NULL")
        .contains("status='FAILED' AND error_code IS NOT NULL");
  }
}
