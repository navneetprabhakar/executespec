package com.navneet.executespec.executioncontext;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.junit.jupiter.api.Test;

class LlmExecutionOperationMigrationContractTest {

  @Test
  void v351CreatesDormantPerOperationFenceAndVersionedAttemptLinkage() throws Exception {
    String sql =
        Files.readString(
                Path.of(
                    "src/main/resources/db/migration/"
                        + "V351__llm_execution_operation_fencing_foundation.sql"))
            .toLowerCase(Locale.ROOT);

    assertThat(sql)
        .contains("create table llm_execution_operation")
        .contains("operation_key", "invocation", "operation_fingerprint")
        .contains("lease_owner", "lease_token", "lease_expires_at", "not_reclaim_before")
        .contains("uq_llm_execution_operation_nonterminal")
        .contains("enforce_llm_execution_operation_identity")
        .contains("illegal running operation reclaim")
        .contains("operation_fence_version smallint not null default 0")
        .contains("llm_execution_operation_id", "llm_execution_operation_attempt")
        .contains("operation_not_reclaim_before", "transport_deadline_at")
        .contains("validate_llm_usage_operation_fence")
        .contains("operation context mismatch", "operation claim mismatch")
        .contains("operation reclaim horizon mismatch")
        .contains("operation_fence_version = 0")
        .doesNotContain("operation_fence_version smallint not null default 1");
  }
}
