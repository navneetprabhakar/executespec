package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.navneet.executespec.harness.entity.GateRecord;
import com.navneet.executespec.harness.entity.PlanGateResumeOutbox;
import com.navneet.executespec.harness.repository.GateRepository;
import com.navneet.executespec.harness.repository.PlanGateResumeOutboxRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real PostgreSQL proof for the V346 gate-receipt and continuation-outbox persistence contract. */
class PlanGateAdmissionPersistenceIntegrationTest extends PostgresIntegrationBase {

  @Autowired GateRepository gates;
  @Autowired PlanGateResumeOutboxRepository outbox;
  @Autowired JdbcTemplate jdbc;

  @Test
  void repositoriesRoundTripCompleteEnforcedReceiptAndExactContinuationBytes() {
    String runId = "gate-binding-" + UUID.randomUUID();
    String gateKey = "gate:plan:" + runId;
    UUID requirementContractId = UUID.randomUUID();
    Instant now = Instant.now();
    insertRun(runId, "AWAITING_APPROVAL");
    insertRequirementContract(runId, requirementContractId);

    GateRecord gate = GateRecord.pending(gateKey, runId, "PLAN");
    gate.setStatus("APPROVED");
    gate.setResolvedAt(now);
    gate.setAdmissionContractVersion(1);
    gate.setBindingMode("ENFORCED");
    gate.setBindingStatus("BOUND");
    gate.setRequirementContractId(requirementContractId);
    gate.setBindingSchemaVersion(1);
    gate.setBindingCompilerFingerprint("a".repeat(64));
    gate.setBindingSourceSha256("b".repeat(64));
    gate.setBindingExactPlanFingerprint("c".repeat(64));
    gate.setBindingArtifactDigest("d".repeat(64));
    gate.setBindingBoundDigest("e".repeat(64));
    gate.setBindingBoundAt(now);
    gate.setContinuationSchemaVersion(1);
    gate.setContinuationSha256("f".repeat(64));
    gate.setApprovalEvidenceJson("{\"admitted\":true}");
    gate.setApprovalEvidenceDigest("1".repeat(64));
    gate.setResolvedByUserId("user-1");
    gate.setResolvedByRole("DEVELOPER");
    gate.setResolutionSource("RUN_API");
    gate.setBillingOwnerUserId("owner-1");
    gate.setBillingOrgId("org-1");
    gate.setAdmittedAt(now);
    gates.saveAndFlush(gate);

    PlanGateResumeOutbox staged =
        PlanGateResumeOutbox.builder()
            .runId(runId)
            .gateKey(gateKey)
            .payload("{\"runId\":\"" + runId + "\"}")
            .payloadSha256("2".repeat(64))
            .handlerContractVersion(1)
            .status("READY")
            .attemptCount(0)
            .maxAttempts(8)
            .nextRetryAt(now)
            .build();
    outbox.saveAndFlush(staged);

    GateRecord reloaded = gates.findFirstByRunIdAndGateType(runId, "PLAN").orElseThrow();
    assertThat(reloaded.getBindingExactPlanFingerprint()).isEqualTo("c".repeat(64));
    assertThat(reloaded.getApprovalEvidenceJson()).isEqualTo("{\"admitted\":true}");
    PlanGateResumeOutbox reloadedOutbox = outbox.findByGateKey(gateKey).orElseThrow();
    assertThat(reloadedOutbox.getPayload()).isEqualTo(staged.getPayload());
    assertThat(reloadedOutbox.getCreatedAt()).isNotNull();
    assertThat(reloadedOutbox.getUpdatedAt()).isNotNull();

    jdbc.update("DELETE FROM pipeline_run WHERE run_id=?", runId);
    assertThat(gates.findById(gateKey)).isEmpty();
    assertThat(outbox.findById(runId)).isEmpty();
  }

  @Test
  void databaseRejectsIncompleteEnforcementReceiptMutationAndUnfencedProcessing() {
    String runId = "gate-constraints-" + UUID.randomUUID();
    String gateKey = "gate:plan:" + runId;
    insertRun(runId, "AWAITING_APPROVAL");
    jdbc.update(
        "INSERT INTO gate_record (gate_key,run_id,gate_type,status) VALUES (?,?,?,?)",
        gateKey,
        runId,
        "PLAN",
        "PENDING");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE gate_record SET status='APPROVED', resolved_at=NOW(),"
                        + " binding_mode='ENFORCED' WHERE gate_key=?",
                    gateKey))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("complete BOUND admission binding");

    jdbc.update(
        "UPDATE gate_record SET status='REJECTED', resolved_at=NOW() WHERE gate_key=?", gateKey);
    assertThatThrownBy(
            () -> jdbc.update("UPDATE gate_record SET status='APPROVED' WHERE gate_key=?", gateKey))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("resolved gate decision is immutable");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    INSERT INTO plan_gate_resume_outbox
                      (run_id,gate_key,payload,payload_sha256,handler_contract_version,status,
                       attempt_count,max_attempts,next_retry_at)
                    VALUES (?,?,?,?,?,'PROCESSING',0,8,NOW())
                    """,
                    runId,
                    gateKey,
                    "{}",
                    "3".repeat(64),
                    1))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ck_plan_gate_resume_lease");
  }

  @Test
  void databaseRejectsGateSnapshotsThatDisagreeWithReferencedRequirementArtifact() {
    String runId = "gate-exact-fk-" + UUID.randomUUID();
    String gateKey = "gate:plan:" + runId;
    UUID requirementContractId = UUID.randomUUID();
    insertRun(runId, "AWAITING_APPROVAL");
    insertRequirementContract(runId, requirementContractId);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    INSERT INTO gate_record (
                      gate_key,run_id,gate_type,status,admission_contract_version,
                      binding_mode,binding_status,requirement_contract_id,binding_schema_version,
                      binding_compiler_fingerprint,binding_source_sha256,
                      binding_exact_plan_fingerprint,binding_artifact_digest,binding_bound_digest,
                      binding_bound_at)
                    VALUES (?,?,'PLAN','PENDING',1,'SHADOW','BOUND',?,1,?,?,?,?,?,NOW())
                    """,
                    gateKey,
                    runId,
                    requirementContractId,
                    "a".repeat(64),
                    "b".repeat(64),
                    "9".repeat(64),
                    "d".repeat(64),
                    "e".repeat(64)))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("fk_gate_requirement_contract_exact");
  }

  private void insertRun(String runId, String status) {
    jdbc.update(
        "INSERT INTO pipeline_run (run_id,status,started_at) VALUES (?,?,?)",
        runId,
        status,
        Timestamp.from(Instant.now()));
  }

  private void insertRequirementContract(String runId, UUID id) {
    jdbc.update(
        """
        INSERT INTO run_requirement_contract (
          id,run_id,schema_version,compiler_fingerprint,source_sha256,
          exact_plan_fingerprint,canonical_artifact_json,artifact_digest,bound_digest,
          completeness,requirement_count,acceptance_criterion_count,task_binding_count,
          issue_count,blocking_issue_count)
        VALUES (?,?,1,?,?,?,'{}',?,?,'READY',0,0,0,0,0)
        """,
        id,
        runId,
        "a".repeat(64),
        "b".repeat(64),
        "c".repeat(64),
        "d".repeat(64),
        "e".repeat(64));
  }
}
