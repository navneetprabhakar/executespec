-- A structurally valid BLOCKED RequirementIR is useful gate evidence even though it can never
-- authorize approval. Preserve V348's immutable receipt checks while separating gate visibility
-- from approval compatibility.
CREATE OR REPLACE FUNCTION enforce_plan_gate_admission_receipt()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    requirement_completeness VARCHAR(16);
    requirement_blockers INTEGER;
BEGIN
    IF NEW.continuation_sha256 IS NOT NULL AND (
        NEW.comment IS NULL
        OR encode(sha256(convert_to(NEW.comment, 'UTF8')), 'hex') <> NEW.continuation_sha256
    ) THEN
        RAISE EXCEPTION 'plan gate continuation digest does not match exact bytes';
    END IF;

    IF NEW.approval_evidence_digest IS NOT NULL AND (
        NEW.approval_evidence_json IS NULL
        OR encode(sha256(convert_to(NEW.approval_evidence_json, 'UTF8')), 'hex')
            <> NEW.approval_evidence_digest
    ) THEN
        RAISE EXCEPTION 'plan gate approval evidence digest does not match exact bytes';
    END IF;

    IF NEW.gate_type = 'PLAN' AND NEW.status = 'PENDING' AND (
        NEW.resolved_at IS NOT NULL
        OR NEW.admission_contract_version IS NOT NULL
        OR NEW.approval_evidence_json IS NOT NULL
        OR NEW.approval_evidence_digest IS NOT NULL
        OR NEW.decision_kind IS NOT NULL
        OR NEW.resolved_actor_type IS NOT NULL
        OR NEW.decision_request_fingerprint IS NOT NULL
        OR NEW.resolved_authorization_source IS NOT NULL
        OR NEW.resolved_authorization_org_id IS NOT NULL
        OR NEW.resolved_by_user_id IS NOT NULL
        OR NEW.resolved_by_role IS NOT NULL
        OR NEW.resolution_source IS NOT NULL
        OR NEW.resolution_reason IS NOT NULL
        OR NEW.billing_owner_user_id IS NOT NULL
        OR NEW.billing_org_id IS NOT NULL
        OR NEW.admitted_at IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'pending plan gate cannot carry resolution or admission evidence';
    END IF;

    IF TG_OP = 'UPDATE' AND (OLD.resolved_at IS NOT NULL
            OR OLD.status IN ('APPROVED', 'REJECTED', 'CANCELLED')) AND (
        NEW.status IS DISTINCT FROM OLD.status
        OR NEW.resolved_at IS DISTINCT FROM OLD.resolved_at
    ) THEN
        RAISE EXCEPTION 'resolved gate decision is immutable';
    END IF;

    IF TG_OP = 'UPDATE' AND OLD.binding_mode IS NOT NULL AND (
        NEW.binding_mode IS DISTINCT FROM OLD.binding_mode
        OR NEW.binding_status IS DISTINCT FROM OLD.binding_status
        OR NEW.requirement_contract_id IS DISTINCT FROM OLD.requirement_contract_id
        OR NEW.binding_schema_version IS DISTINCT FROM OLD.binding_schema_version
        OR NEW.binding_compiler_fingerprint IS DISTINCT FROM OLD.binding_compiler_fingerprint
        OR NEW.binding_source_sha256 IS DISTINCT FROM OLD.binding_source_sha256
        OR NEW.binding_exact_plan_fingerprint IS DISTINCT FROM OLD.binding_exact_plan_fingerprint
        OR NEW.binding_artifact_digest IS DISTINCT FROM OLD.binding_artifact_digest
        OR NEW.binding_bound_digest IS DISTINCT FROM OLD.binding_bound_digest
        OR NEW.binding_bound_at IS DISTINCT FROM OLD.binding_bound_at
        OR NEW.binding_reason IS DISTINCT FROM OLD.binding_reason
        OR NEW.contract_gate_report_digest IS DISTINCT FROM OLD.contract_gate_report_digest
        OR NEW.continuation_schema_version IS DISTINCT FROM OLD.continuation_schema_version
        OR NEW.continuation_sha256 IS DISTINCT FROM OLD.continuation_sha256
        OR NEW.comment IS DISTINCT FROM OLD.comment
    ) THEN
        RAISE EXCEPTION 'plan gate open binding is immutable';
    END IF;

    IF TG_OP = 'UPDATE' AND OLD.admission_contract_version IS NOT NULL AND (
        NEW.admission_contract_version IS DISTINCT FROM OLD.admission_contract_version
        OR NEW.approval_evidence_json IS DISTINCT FROM OLD.approval_evidence_json
        OR NEW.approval_evidence_digest IS DISTINCT FROM OLD.approval_evidence_digest
        OR NEW.decision_kind IS DISTINCT FROM OLD.decision_kind
        OR NEW.resolved_actor_type IS DISTINCT FROM OLD.resolved_actor_type
        OR NEW.decision_request_fingerprint IS DISTINCT FROM OLD.decision_request_fingerprint
        OR NEW.resolved_authorization_source IS DISTINCT FROM OLD.resolved_authorization_source
        OR NEW.resolved_authorization_org_id IS DISTINCT FROM OLD.resolved_authorization_org_id
        OR NEW.resolved_by_user_id IS DISTINCT FROM OLD.resolved_by_user_id
        OR NEW.resolved_by_role IS DISTINCT FROM OLD.resolved_by_role
        OR NEW.resolution_source IS DISTINCT FROM OLD.resolution_source
        OR NEW.resolution_reason IS DISTINCT FROM OLD.resolution_reason
        OR NEW.billing_owner_user_id IS DISTINCT FROM OLD.billing_owner_user_id
        OR NEW.billing_org_id IS DISTINCT FROM OLD.billing_org_id
        OR NEW.admitted_at IS DISTINCT FROM OLD.admitted_at
    ) THEN
        RAISE EXCEPTION 'plan gate admission receipt is immutable';
    END IF;

    IF NEW.gate_type = 'PLAN' AND NEW.binding_mode = 'ENFORCED' THEN
        SELECT completeness, blocking_issue_count
          INTO requirement_completeness, requirement_blockers
          FROM run_requirement_contract
         WHERE id = NEW.requirement_contract_id
           AND run_id = NEW.run_id
           AND schema_version = NEW.binding_schema_version
           AND compiler_fingerprint = NEW.binding_compiler_fingerprint
           AND source_sha256 = NEW.binding_source_sha256
           AND exact_plan_fingerprint = NEW.binding_exact_plan_fingerprint
           AND artifact_digest = NEW.binding_artifact_digest
           AND bound_digest = NEW.binding_bound_digest;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'ENFORCED plan gate requires an exact RequirementIR';
        ELSIF NEW.binding_status = 'BOUND' AND (
            requirement_completeness IS DISTINCT FROM 'READY'
            OR requirement_blockers IS DISTINCT FROM 0
        ) THEN
            RAISE EXCEPTION 'BOUND ENFORCED plan gate requires READY zero-blocker RequirementIR';
        ELSIF NEW.binding_status = 'BLOCKED' AND NOT (
            requirement_completeness = 'BLOCKED' AND requirement_blockers > 0
        ) THEN
            RAISE EXCEPTION 'BLOCKED ENFORCED plan gate requires blocked RequirementIR evidence';
        ELSIF NEW.binding_status NOT IN ('BOUND', 'BLOCKED') THEN
            RAISE EXCEPTION 'ENFORCED plan gate requires BOUND or BLOCKED RequirementIR';
        END IF;
    END IF;

    IF NEW.gate_type = 'PLAN' AND NEW.status = 'APPROVED' AND NEW.binding_mode = 'ENFORCED' AND (
        NEW.admission_contract_version IS NULL
        OR NEW.binding_status IS DISTINCT FROM 'BOUND'
        OR NEW.requirement_contract_id IS NULL
        OR NEW.binding_schema_version IS NULL
        OR NEW.binding_compiler_fingerprint IS NULL
        OR NEW.binding_source_sha256 IS NULL
        OR NEW.binding_exact_plan_fingerprint IS NULL
        OR NEW.binding_artifact_digest IS NULL
        OR NEW.binding_bound_digest IS NULL
        OR NEW.binding_bound_at IS NULL
        OR NEW.continuation_schema_version IS NULL
        OR NEW.continuation_sha256 IS NULL
        OR NEW.approval_evidence_json IS NULL
        OR NEW.approval_evidence_digest IS NULL
        OR NEW.resolved_by_user_id IS NULL
        OR NEW.resolution_source IS NULL
        OR NEW.billing_owner_user_id IS NULL
        OR NEW.admitted_at IS NULL
    ) THEN
        RAISE EXCEPTION 'ENFORCED plan approval requires a complete BOUND admission binding';
    END IF;

    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION enforce_plan_gate_admission_receipt() IS
    'V352: permits exact BLOCKED RequirementIR at a pending ENFORCED gate; approval remains BOUND/READY/zero-blocker only';
