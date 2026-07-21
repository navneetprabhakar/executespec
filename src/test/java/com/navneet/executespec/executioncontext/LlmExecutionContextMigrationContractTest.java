package com.navneet.executespec.executioncontext;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.junit.jupiter.api.Test;

class LlmExecutionContextMigrationContractTest {

  @Test
  void v282CreatesTenantLeaseIdempotencyAndSecretFreePolicyContracts() throws Exception {
    String sql =
        Files.readString(
                Path.of("src/main/resources/db/migration/V282__durable_llm_execution_context.sql"))
            .toLowerCase(Locale.ROOT);

    assertThat(sql)
        .contains("create table if not exists llm_execution_context")
        .contains("scope_mode", "isolation_key", "org_id", "user_id", "project_id")
        .contains("allowed_providers_json", "provider_key_sources_json", "route_snapshot_version")
        .contains("lease_owner", "lease_expires_at", "heartbeat_at")
        .contains("cancellation_requested_at", "attempt", "max_attempts")
        .contains("request_fingerprint", "version bigint not null")
        .contains("uq_llm_execution_context_idempotency")
        .contains("isolation_key = ('org:' || org_id)")
        .contains("scope_mode = 'platform_internal'")
        .doesNotContain("api_key", "encrypted_key", "credential_envelope", "plaintext_secret");
  }
}
