package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class PlanGateAdmissionPersistenceMigrationContractTest {

  private static final Path MIGRATION =
      Path.of(
          "src/main/resources/db/migration/V346__plan_gate_admission_binding_and_resume_outbox.sql");

  @Test
  void gateRowIsTheVersionedByteBoundAdmissionReceipt() throws Exception {
    String sql = Files.readString(MIGRATION);

    assertThat(sql)
        .contains("ADD COLUMN admission_contract_version INTEGER")
        .contains("ADD COLUMN binding_mode VARCHAR(16)")
        .contains("ADD COLUMN requirement_contract_id UUID")
        .contains("ADD COLUMN binding_exact_plan_fingerprint VARCHAR(64)")
        .contains("ADD COLUMN binding_bound_digest VARCHAR(64)")
        .contains("ADD COLUMN continuation_sha256 VARCHAR(64)")
        .contains("ADD COLUMN approval_evidence_json TEXT")
        .contains("ADD COLUMN approval_evidence_digest VARCHAR(64)")
        .contains("ADD CONSTRAINT uq_run_requirement_contract_gate_binding UNIQUE")
        .contains("ADD CONSTRAINT fk_gate_record_pipeline_run")
        .contains("ON DELETE CASCADE NOT VALID")
        .contains("ADD CONSTRAINT fk_gate_requirement_contract_exact")
        .contains("requirement_contract_id,")
        .contains("binding_exact_plan_fingerprint,")
        .contains("ck_gate_binding_identity_all_or_null")
        .contains("ck_gate_legacy_has_no_contract_binding")
        .contains("OLD.admission_contract_version IS NOT NULL")
        .contains("resolved gate decision is immutable")
        .contains("ENFORCED plan approval requires a complete BOUND admission binding")
        .doesNotContain("approval_evidence_json JSONB");
  }

  @Test
  void outboxIsFencedRecoverableAndHistoricalBackfillDoesNotInventEvidence() throws Exception {
    String sql = Files.readString(MIGRATION);

    assertThat(sql)
        .contains("CREATE TABLE plan_gate_resume_outbox")
        .contains("run_id                    VARCHAR(255) PRIMARY KEY")
        .contains("gate_key                  VARCHAR(255) NOT NULL UNIQUE")
        .contains("payload                   TEXT")
        .contains("lease_token               UUID")
        .contains("'WAITING', 'READY', 'PROCESSING', 'COMPLETED', 'CANCELLED', 'DEAD'")
        .contains("WHERE status = 'READY'")
        .contains("WHERE status = 'PROCESSING'")
        .contains("LEGACY_GATE_MISSING_CONTINUATION_PAYLOAD")
        .contains("admission_contract_version IS NULL")
        .doesNotContain("SET admission_contract_version =")
        .doesNotContain("SET binding_mode = 'ENFORCED'");
  }
}
