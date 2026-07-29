package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class AtomicPlanGateOpenMigrationContractTest {
  private static final Path MIGRATION =
      Path.of("src/main/resources/db/migration/V347__atomic_plan_gate_open_contract.sql");

  @Test
  void exactContinuationAndReportIdentityAreDatabaseBound() throws Exception {
    String sql = Files.readString(MIGRATION);

    assertThat(sql)
        .contains("'requirement.ir.gate.mode'")
        .contains("'LEGACY'")
        .contains("o.payload = g.comment")
        .contains("uq_gate_plan_continuation_identity")
        .contains("fk_plan_gate_resume_exact_gate")
        .contains("fk_gate_contract_report_exact")
        .contains("ck_pending_plan_gate_has_no_admission_receipt")
        .contains("pending plan gate cannot carry resolution or admission evidence")
        .contains("plan gate continuation digest does not match exact bytes")
        .contains("plan gate outbox payload digest does not match exact bytes")
        .contains("plan gate approval evidence digest does not match exact bytes");
  }

  @Test
  void openAndOutboxIdentityAreImmutableAndTransitionsAreFenced() throws Exception {
    String sql = Files.readString(MIGRATION);

    assertThat(sql)
        .contains("plan gate open binding is immutable")
        .contains("plan gate outbox identity is immutable")
        .contains("illegal plan gate outbox status transition")
        .contains("ck_plan_gate_resume_executable_payload")
        .contains("ck_plan_gate_resume_processing_heartbeat")
        .contains("LEGACY_GATE_MISSING_CONTINUATION_PAYLOAD");
  }
}
