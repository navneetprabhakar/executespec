package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class AtomicPlanGateDecisionMigrationContractTest {

  @Test
  void v348PinsDecisionIdentityEvidenceAndDeferredCrossRowCoherence() throws Exception {
    String sql = normalizeWhitespace(readMigration());

    assertThat(sql)
        .contains("ADD COLUMN decision_kind VARCHAR(16)")
        .contains("ADD COLUMN resolved_actor_type VARCHAR(16)")
        .contains("ADD COLUMN decision_request_fingerprint VARCHAR(64)")
        .contains("ADD CONSTRAINT ck_plan_gate_admission_contract_version")
        .contains(
            "gate_type <> 'PLAN' OR admission_contract_version IS NULL OR"
                + " admission_contract_version = 1")
        .contains("ck_versioned_plan_gate_decision_receipt")
        .contains("uq_gate_record_plan_run")
        .contains("plan_decision_evidence_has_exact_v1_shape")
        .contains("ck_plan_gate_decision_evidence_shape")
        .contains("AND parsed ?& required_keys")
        .contains("AND (parsed - required_keys) = '{}'::jsonb")
        .contains("ck_plan_gate_decision_evidence_identity")
        .contains("ck_plan_gate_decision_policy_outcomes")
        .contains("ck_plan_contract_gate_report_digest_exact")
        .contains("plan contract gate acknowledgement is immutable")
        .contains("referenced plan contract gate report is immutable")
        .contains("ENFORCED plan gate requires an exact READY zero-blocker RequirementIR")
        .contains("CREATE CONSTRAINT TRIGGER trg_gate_record_decision_coherence")
        .contains("CREATE CONSTRAINT TRIGGER trg_plan_gate_outbox_decision_coherence")
        .contains("CREATE CONSTRAINT TRIGGER trg_pipeline_run_plan_decision_coherence")
        .contains("CREATE CONSTRAINT TRIGGER trg_plan_contract_report_decision_coherence")
        .contains("plan decision evidence continuation identity is inconsistent")
        .contains(
            "gate_contract_version IS DISTINCT FROM 1 OR (gate_evidence ->> 'schemaVersion') IS"
                + " DISTINCT FROM gate_contract_version::text")
        .contains("plan decision receipt schema is unsupported")
        .contains("plan decision evidence reviewed-plan fingerprint is inconsistent")
        .contains("plan decision evidence billing identity is inconsistent")
        .contains("plan decision actor authorization identity is inconsistent")
        .contains("plan decision RequirementIR result evidence is inconsistent")
        .contains("DEFERRABLE INITIALLY DEFERRED");
  }

  @Test
  void v348PreservesVersionedDecisionChildrenExceptThroughParentRunCascade() throws Exception {
    String sql = normalizeWhitespace(readMigration());

    assertThat(sql)
        .contains(
            "CREATE TRIGGER trg_versioned_plan_gate_no_direct_delete BEFORE DELETE ON gate_record")
        .contains(
            "CREATE TRIGGER trg_versioned_plan_outbox_no_direct_delete BEFORE DELETE ON"
                + " plan_gate_resume_outbox")
        .contains(
            "CREATE TRIGGER trg_versioned_plan_report_no_direct_delete BEFORE DELETE ON"
                + " plan_contract_gate_report")
        .contains("OLD.gate_type = 'PLAN' AND OLD.admission_contract_version IS NOT NULL")
        .contains("versioned plan decision gate cannot be deleted directly")
        .contains("versioned plan continuation cannot be deleted directly")
        .contains("referenced plan contract gate report cannot be deleted directly")
        .contains(
            "ADD CONSTRAINT fk_plan_contract_gate_report_run FOREIGN KEY (run_id) REFERENCES"
                + " pipeline_run (run_id) ON DELETE CASCADE")
        .contains(
            "ADD CONSTRAINT fk_gate_contract_report_exact FOREIGN KEY (run_id,"
                + " contract_gate_report_digest) REFERENCES plan_contract_gate_report (run_id,"
                + " report_digest) ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED");
  }

  @Test
  void v348CrossBindsProviderExecutionModeCostAndCreditsToRunState() throws Exception {
    String sql = normalizeWhitespace(readMigration());

    assertThat(sql)
        .contains(
            "SELECT status, plan_json, org_id, created_by_user_id, approved_execution_mode,"
                + " ai_provider, credits_charged, cost_estimate_high, hard_cap_usd")
        .contains(
            "(gate_evidence ->> 'executionMode') IS DISTINCT FROM COALESCE(run_execution_mode,"
                + " 'REALTIME')")
        .contains("lower(COALESCE(run_provider, 'openai')) = 'google' THEN 'gemini'")
        .contains("ELSE lower(COALESCE(run_provider, 'openai')) END")
        .contains(
            "(gate_evidence ->> 'estimateHighUsd') IS DISTINCT FROM to_char("
                + " round(COALESCE(run_cost_estimate_high, run_hard_cap_usd, 0)::numeric, 6),"
                + " 'FM999999999999999990.000000')")
        .contains(
            "(gate_evidence ->> 'hardCapUsd') IS DISTINCT FROM (CASE WHEN run_hard_cap_usd IS"
                + " NULL THEN NULL ELSE to_char( round(run_hard_cap_usd::numeric, 6),"
                + " 'FM999999999999999990.000000') END)")
        .contains(
            "(gate_evidence ->> 'expectedCredits') IS DISTINCT FROM COALESCE(run_credits, 1)::text")
        .contains(
            "(gate_evidence ->> 'creditsConfirmed') IS DISTINCT FROM COALESCE(run_credits,"
                + " 1)::text")
        .contains("(gate_evidence ->> 'creditsMatched')::boolean IS DISTINCT FROM TRUE")
        .contains("plan decision evidence cost or credit identity is inconsistent")
        .contains(
            "AFTER INSERT OR UPDATE OF status, plan_json, org_id, created_by_user_id,"
                + " approved_execution_mode, ai_provider, credits_charged, cost_estimate_high,"
                + " hard_cap_usd OR DELETE ON pipeline_run");
  }

  @Test
  void v348RequiresFixedScaleNonNegativeMoneyAndPositiveCreditEvidence() throws Exception {
    String sql = normalizeWhitespace(readMigration());

    assertThat(sql)
        .contains("ADD CONSTRAINT ck_plan_gate_decision_fixed_scale_money")
        .contains("(approval_evidence_json::jsonb ->> 'estimateHighUsd')")
        .contains("(approval_evidence_json::jsonb ->> 'hardCapUsd')")
        .contains("(approval_evidence_json::jsonb ->> 'managedAiHardRawUsd')")
        .contains("(approval_evidence_json::jsonb ->> 'managedAiRemainingRawUsd')")
        .contains("(approval_evidence_json::jsonb ->> 'governanceOrgThresholdUsd')")
        .contains("(approval_evidence_json::jsonb ->> 'governanceOrgEstimateUsd')")
        .contains("(approval_evidence_json::jsonb ->> 'governanceProjectCapUsd')")
        .contains("(approval_evidence_json::jsonb ->> 'governanceProjectMonthToDateUsd')")
        .contains("(approval_evidence_json::jsonb ->> 'governanceProjectProjectedUsd')")
        .contains("~ '^[0-9]+\\.[0-9]{6}$'")
        .contains("(approval_evidence_json::jsonb ->> 'expectedCredits') ~ '^[1-9][0-9]*$'")
        .contains("(approval_evidence_json::jsonb ->> 'creditsConfirmed') ~ '^[1-9][0-9]*$'");
  }

  @Test
  void v348PinsDecisionSourceAndActorCompatibility() throws Exception {
    String sql = normalizeWhitespace(readMigration());

    assertThat(sql)
        .contains("ADD CONSTRAINT ck_versioned_plan_gate_resolution_mapping")
        .contains(
            "decision_kind IN ('APPROVE', 'REJECT') AND resolution_source IN ( 'PLAN_APPROVAL_API',"
                + " 'EXECUTION_VIEW_API', 'PROJECT_CHAT_ACTION') AND resolved_actor_type = 'HUMAN'")
        .contains(
            "decision_kind = 'CANCEL' AND resolution_source IN ( 'RUN_CANCEL_API',"
                + " 'PROJECT_CHAT_ACTION', 'SYSTEM_CANCEL')")
        .contains("resolution_source = 'SYSTEM_CANCEL' AND resolved_actor_type = 'SYSTEM'")
        .contains("resolution_source <> 'SYSTEM_CANCEL' AND resolved_actor_type = 'HUMAN'")
        .contains("ADD CONSTRAINT ck_gate_actor_authorization_type")
        .contains(
            "resolved_actor_type = 'SYSTEM' AND resolved_authorization_source = 'SYSTEM_INTERNAL'")
        .contains(
            "resolved_actor_type = 'HUMAN' AND resolved_authorization_source IN ( 'RUN_OWNER',"
                + " 'ORG_ADMIN', 'GLOBAL_SUPER_ADMIN')");
  }

  @Test
  void v348SnapshotsExactContractAcknowledgementInDecisionEvidence() throws Exception {
    String sql = normalizeWhitespace(readMigration());

    assertThat(sql)
        .contains(
            "SELECT requires_typed_ack, acknowledged_at, acknowledged_by_user_id,"
                + " acknowledgement_text")
        .contains(
            "(gate_evidence ->> 'contractRequiresTypedAck')::boolean IS DISTINCT FROM"
                + " report_requires_ack")
        .contains(
            "(gate_evidence ->> 'contractAcknowledgedAt')::timestamptz IS DISTINCT FROM"
                + " report_acknowledged_at")
        .contains(
            "(gate_evidence ->> 'contractAcknowledgedByUserId') IS DISTINCT FROM"
                + " report_acknowledged_by")
        .contains(
            "(gate_evidence ->> 'contractAcknowledgementText') IS DISTINCT FROM"
                + " report_acknowledgement_text")
        .contains("report_acknowledged_by IS DISTINCT FROM gate_actor_user_id")
        .contains("upper(btrim(report_acknowledgement_text)) IS DISTINCT FROM 'BREAKING'")
        .contains("plan decision contract acknowledgement evidence is inconsistent")
        .contains("plan decision typed acknowledgement evidence is inconsistent")
        .contains("plan decision non-breaking contract evidence is inconsistent");
  }

  @Test
  void v348PreservesLegacyPreDecisionAcknowledgementForRejectAndCancelReceipts() throws Exception {
    String sql = normalizeWhitespace(readMigration());

    assertThat(sql)
        .contains(
            "decision_kind IN ('REJECT', 'CANCEL') AND"
                + " (approval_evidence_json::jsonb ->> 'managedAiOutcome') IS NOT DISTINCT FROM"
                + " 'NOT_EVALUATED'")
        .contains(
            "(approval_evidence_json::jsonb ->> 'contractAcknowledgementOutcome') IS NOT"
                + " DISTINCT FROM 'NOT_EVALUATED'")
        .contains(
            "IF gate_decision <> 'APPROVE' THEN IF (gate_evidence ->>"
                + " 'contractAcknowledgementOutcome') IS DISTINCT FROM 'NOT_EVALUATED' THEN")
        .contains("upper(btrim(report_acknowledgement_text)) IS DISTINCT FROM 'BREAKING'")
        .doesNotContain("gate_decision <> 'APPROVE' AND report_acknowledged_at IS NOT NULL");
  }

  private static String readMigration() throws Exception {
    return Files.readString(
        Path.of(
            "src/main/resources/db/migration/" + "V348__atomic_plan_gate_decision_integrity.sql"));
  }

  private static String normalizeWhitespace(String value) {
    return value.replaceAll("\\s+", " ").trim();
  }
}
