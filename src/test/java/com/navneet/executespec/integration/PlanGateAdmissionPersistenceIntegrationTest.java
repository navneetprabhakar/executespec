package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import com.navneet.executespec.harness.entity.GateRecord;
import com.navneet.executespec.harness.entity.PlanGateResumeOutbox;
import com.navneet.executespec.harness.repository.GateRepository;
import com.navneet.executespec.harness.repository.PlanGateResumeOutboxRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Real PostgreSQL proof for the V346 gate-receipt and continuation-outbox persistence contract. */
class PlanGateAdmissionPersistenceIntegrationTest extends PostgresIntegrationBase {
  private static final String PLAN_JSON = "{\"tasks\":[]}";

  @Autowired GateRepository gates;
  @Autowired PlanGateResumeOutboxRepository outbox;
  @Autowired PlanGateResumeStore resumeStore;
  @Autowired JdbcTemplate jdbc;
  @Autowired EntityManager entityManager;

  @Test
  @Transactional
  void repositoriesRoundTripCompleteEnforcedReceiptAndExactContinuationBytes() {
    String runId = "gate-binding-" + UUID.randomUUID();
    String gateKey = "gate:plan:" + runId;
    UUID requirementContractId = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-28T00:00:00Z");
    String payload = "{\"runId\":\"" + runId + "\"}";
    String payloadDigest = PlanGateOpenService.sha256(payload);
    String approvalEvidence =
        "{\"schemaVersion\":1,\"policyContract\":\"PLAN_GATE_DECISION_V1\","
            + "\"decision\":\"APPROVE\",\"decidedAt\":\"2026-07-28T00:00:00Z\","
            + "\"resolutionSource\":\"PLAN_APPROVAL_API\",\"resolutionReason\":null,"
            + "\"actorType\":\"HUMAN\",\"actorUserId\":\"owner-1\","
            + "\"globalRole\":null,\"orgRole\":null,"
            + "\"effectiveRole\":\"DEVELOPER\","
            + "\"authorizationBasis\":\"RUN_OWNER\",\"authorizationOrgId\":null,"
            + "\"runId\":\""
            + runId
            + "\",\"gateKey\":\""
            + gateKey
            + "\",\"reviewedPlanFingerprint\":\""
            + PlanGateOpenService.sha256(PLAN_JSON)
            + "\",\"suppliedPlanFingerprint\":\""
            + PlanGateOpenService.sha256(PLAN_JSON)
            + "\",\"decisionRequestFingerprint\":\""
            + "f".repeat(64)
            + "\",\"continuationSchemaVersion\":1,\"continuationSha256\":\""
            + payloadDigest
            + "\",\"bindingMode\":\"ENFORCED\",\"bindingStatus\":\"BOUND\","
            + "\"requirementContractId\":\""
            + requirementContractId
            + "\",\"bindingSchemaVersion\":1,\"bindingCompilerFingerprint\":\""
            + "a".repeat(64)
            + "\",\"bindingSourceSha256\":\""
            + "b".repeat(64)
            + "\",\"bindingExactPlanFingerprint\":\""
            + PlanGateOpenService.sha256(PLAN_JSON)
            + "\",\"bindingArtifactDigest\":\""
            + "d".repeat(64)
            + "\",\"bindingBoundDigest\":\""
            + "e".repeat(64)
            + "\",\"bindingReason\":null"
            + ",\"requirementCompleteness\":\"READY\",\"requirementCount\":0,"
            + "\"acceptanceCriterionCount\":0,\"taskBindingCount\":0,\"issueCount\":0,"
            + "\"blockingIssueCount\":0,\"billingOwnerUserId\":\"owner-1\","
            + "\"executionMode\":\"REALTIME\",\"estimateHighUsd\":\"0.000000\","
            + "\"hardCapUsd\":null,"
            + "\"expectedCredits\":1,\"creditsConfirmed\":null,\"creditsMatched\":null,"
            + "\"billingOrgId\":\"org-1\",\"provider\":\"anthropic\","
            + "\"keySourceMode\":\"SYSTEM\",\"credentialOrigin\":\"SYSTEM\","
            + "\"ownKeyExempt\":false,\"managedAiMode\":\"MANAGED\","
            + "\"credentialAuthorizationSource\":"
            + "\"SYSTEM_PLATFORM_KEY\",\"credentialAuthorizationOrgId\":\"org-1\","
            + "\"planFingerprintMatched\":true,\"managedAiOutcome\":\"ALLOWED\","
            + "\"managedAiGrantCredits\":null,\"managedAiCreditsRemaining\":null,"
            + "\"managedAiEstimatedCredits\":null,\"managedAiHardRawUsd\":null,"
            + "\"managedAiRemainingRawUsd\":null,"
            + "\"governanceOrgOutcome\":\"ALLOWED\",\"governanceOrgThresholdUsd\":null,"
            + "\"governanceOrgEstimateUsd\":null,\"governanceOrgReason\":null,"
            + "\"governanceProjectOutcome\":\"ALLOWED\",\"governanceProjectCapUsd\":null,"
            + "\"governanceProjectMonthToDateUsd\":null,"
            + "\"governanceProjectProjectedUsd\":null,\"governanceProjectReason\":null,"
            + "\"contractGateReportDigest\":null,\"contractRequiresTypedAck\":null,"
            + "\"contractAcknowledgedAt\":null,\"contractAcknowledgedByUserId\":null,"
            + "\"contractAcknowledgementText\":null,"
            + "\"contractAcknowledgementOutcome\":\"NOT_REQUIRED\"}";
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
    gate.setBindingExactPlanFingerprint(PlanGateOpenService.sha256(PLAN_JSON));
    gate.setBindingArtifactDigest("d".repeat(64));
    gate.setBindingBoundDigest("e".repeat(64));
    gate.setBindingBoundAt(now);
    gate.setComment(payload);
    gate.setContinuationSchemaVersion(1);
    gate.setContinuationSha256(payloadDigest);
    gate.setApprovalEvidenceJson(approvalEvidence);
    gate.setApprovalEvidenceDigest(PlanGateOpenService.sha256(approvalEvidence));
    gate.setDecisionKind("APPROVE");
    gate.setResolvedActorType("HUMAN");
    gate.setResolvedAuthorizationSource("RUN_OWNER");
    gate.setDecisionRequestFingerprint("f".repeat(64));
    gate.setResolvedByUserId("owner-1");
    gate.setResolvedByRole("DEVELOPER");
    gate.setResolutionSource("PLAN_APPROVAL_API");
    gate.setBillingOwnerUserId("owner-1");
    gate.setBillingOrgId("org-1");
    gate.setAdmittedAt(now);
    gates.saveAndFlush(gate);

    PlanGateResumeOutbox staged =
        PlanGateResumeOutbox.builder()
            .runId(runId)
            .gateKey(gateKey)
            .payload(payload)
            .payloadSha256(payloadDigest)
            .handlerContractVersion(1)
            .status("READY")
            .attemptCount(0)
            .maxAttempts(8)
            .nextRetryAt(now)
            .build();
    outbox.saveAndFlush(staged);
    jdbc.update("UPDATE pipeline_run SET status='APPROVAL_RESUMING' WHERE run_id=?", runId);
    jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
    entityManager.clear();

    GateRecord reloaded = gates.findFirstByRunIdAndGateType(runId, "PLAN").orElseThrow();
    assertThat(reloaded.getBindingExactPlanFingerprint())
        .isEqualTo(PlanGateOpenService.sha256(PLAN_JSON));
    assertThat(reloaded.getApprovalEvidenceJson()).isEqualTo(approvalEvidence);
    PlanGateResumeOutbox reloadedOutbox = outbox.findByGateKey(gateKey).orElseThrow();
    assertThat(reloadedOutbox.getPayload()).isEqualTo(staged.getPayload());
    assertThat(reloadedOutbox.getCreatedAt()).isNotNull();
    assertThat(reloadedOutbox.getUpdatedAt()).isNotNull();

    jdbc.update("DELETE FROM pipeline_run WHERE run_id=?", runId);
    entityManager.clear();
    assertThat(gates.findById(gateKey)).isEmpty();
    assertThat(outbox.findById(runId)).isEmpty();
  }

  @Test
  void databaseRejectsIncompleteEnforcementReceiptMutationAndUnfencedProcessing() {
    String runId = "gate-constraints-" + UUID.randomUUID();
    String gateKey = "gate:plan:" + runId;
    insertRun(runId, "AWAITING_APPROVAL");
    jdbc.update(
        "INSERT INTO gate_record"
            + " (gate_key,run_id,gate_type,status,comment,continuation_schema_version,continuation_sha256)"
            + " VALUES (?,?,?,?,?,1,?)",
        gateKey,
        runId,
        "PLAN",
        "PENDING",
        "{}",
        PlanGateOpenService.sha256("{}"));

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE gate_record SET status='APPROVED', resolved_at=NOW(),"
                        + " binding_mode='ENFORCED' WHERE gate_key=?",
                    gateKey))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("exact READY zero-blocker RequirementIR");

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
                    PlanGateOpenService.sha256("{}"),
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
                      gate_key,run_id,gate_type,status,
                      binding_mode,binding_status,requirement_contract_id,binding_schema_version,
                      binding_compiler_fingerprint,binding_source_sha256,
                      binding_exact_plan_fingerprint,binding_artifact_digest,binding_bound_digest,
                      binding_bound_at)
                    VALUES (?,?,'PLAN','PENDING','SHADOW','BOUND',?,1,?,?,?,?,?,NOW())
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

  @Test
  @Transactional
  void heartbeatIsFencedByTheExactOwnerTokenInPostgres() {
    String runId = "gate-heartbeat-" + UUID.randomUUID();
    String gateKey = "gate:plan:" + runId;
    UUID token = UUID.randomUUID();
    Instant now = Instant.now();
    insertRun(runId, "AWAITING_APPROVAL");
    insertPendingGateWithContinuation(runId, gateKey, "{}");
    jdbc.update(
        """
        INSERT INTO plan_gate_resume_outbox
          (run_id,gate_key,payload,payload_sha256,handler_contract_version,status,
           attempt_count,max_attempts,next_retry_at,lease_owner,lease_token,
           lease_expires_at,heartbeat_at)
        VALUES (?,?,?, ?,1,'PROCESSING',1,8,NOW(),'node-a',?,NOW() + interval '2 minutes',NOW())
        """,
        runId,
        gateKey,
        "{}",
        PlanGateOpenService.sha256("{}"),
        token);

    assertThat(
            outbox.heartbeat(
                runId, "node-a", UUID.randomUUID(), now, now.plus(Duration.ofMinutes(2))))
        .isZero();
    assertThat(outbox.heartbeat(runId, "wrong-owner", token, now, now.plus(Duration.ofMinutes(2))))
        .isZero();
    assertThat(outbox.heartbeat(runId, "node-a", token, now, now.plus(Duration.ofMinutes(2))))
        .isEqualTo(1);
  }

  @Test
  void expiredProcessingLeaseIsReclaimedWithANewTokenAndIncrementedAttempt() {
    String runId = "gate-reclaim-" + UUID.randomUUID();
    String gateKey = "gate:plan:" + runId;
    Instant now = Instant.now();
    insertRun(runId, "AWAITING_APPROVAL");
    insertPendingGateWithContinuation(runId, gateKey, "{}");
    jdbc.update(
        """
        INSERT INTO plan_gate_resume_outbox
          (run_id,gate_key,payload,payload_sha256,handler_contract_version,status,
           attempt_count,max_attempts,next_retry_at)
        VALUES (?,?,?, ?,1,'READY',0,8,?)
        """,
        runId,
        gateKey,
        "{}",
        PlanGateOpenService.sha256("{}"),
        Timestamp.from(now.minusSeconds(1)));

    PlanGateResumeStore.Lease first =
        resumeStore.claimNext("node-a", now, Duration.ofSeconds(30)).orElseThrow();
    jdbc.update(
        "UPDATE plan_gate_resume_outbox SET lease_expires_at=? WHERE run_id=?",
        Timestamp.from(now.minusSeconds(1)),
        runId);
    PlanGateResumeStore.Lease reclaimed =
        resumeStore.claimNext("node-b", now, Duration.ofSeconds(30)).orElseThrow();

    assertThat(first.attempt()).isEqualTo(1);
    assertThat(reclaimed.attempt()).isEqualTo(2);
    assertThat(reclaimed.owner()).isEqualTo("node-b");
    assertThat(reclaimed.token()).isNotEqualTo(first.token());
  }

  private void insertRun(String runId, String status) {
    jdbc.update(
        "INSERT INTO pipeline_run"
            + " (run_id,status,started_at,plan_json,org_id,created_by_user_id)"
            + " VALUES (?,?,?,?,?,?)",
        runId,
        status,
        Timestamp.from(Instant.now()),
        PLAN_JSON,
        "org-1",
        "owner-1");
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
        PlanGateOpenService.sha256(PLAN_JSON),
        "d".repeat(64),
        "e".repeat(64));
  }

  private void insertPendingGateWithContinuation(String runId, String gateKey, String payload) {
    jdbc.update(
        "INSERT INTO gate_record"
            + " (gate_key,run_id,gate_type,status,comment,continuation_schema_version,"
            + " continuation_sha256) VALUES (?,?,'PLAN','PENDING',?,1,?)",
        gateKey,
        runId,
        payload,
        PlanGateOpenService.sha256(payload));
  }
}
