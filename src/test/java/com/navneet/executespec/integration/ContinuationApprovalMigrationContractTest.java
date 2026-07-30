package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ContinuationApprovalMigrationContractTest {

  @Test
  void migrationsFenceIdentityAndBoundCrashRecovery() throws Exception {
    String fence =
        Files.readString(
            Path.of("src/main/resources/db/migration/V364__fence_continuation_approval.sql"));
    String recovery =
        Files.readString(
            Path.of(
                "src/main/resources/db/migration/V365__recover_stale_continuation_approvals.sql"));

    assertThat(fence)
        .contains("chk_agent_task_continuation_approval_identity")
        .contains("'CLAIMED'")
        .contains("'QUEUED'")
        .contains("continuation_approval_proposal_digest");
    assertThat(recovery)
        .contains("continuation_approval_updated_at TIMESTAMPTZ")
        .contains("chk_agent_task_continuation_approval_timestamp")
        .contains("idx_agent_task_continuation_approval_recovery")
        .contains("WHERE continuation_approval_status IN ('CLAIMED', 'QUEUED')");
  }
}
