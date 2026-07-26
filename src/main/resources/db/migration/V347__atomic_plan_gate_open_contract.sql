-- V347: close the durable identity gaps required before the atomic plan-gate opener is wired.
--
-- This migration does not activate RequirementIR. The authoritative gate mode is seeded LEGACY
-- and must be changed explicitly only after every writer/reader supports the versioned contract.

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description, updated_at)
VALUES (
    'requirement.ir.gate.mode',
    'LEGACY',
    'STRING',
    'PLATFORM',
    'Plan-gate RequirementIR mode: LEGACY, SHADOW, or ENFORCED. Default remains LEGACY.',
    NOW()
)
ON CONFLICT (flag_key) DO NOTHING;

-- A continuation intent must identify the same run, exact bytes and handler version as its gate.
-- V346 copied historical gate comments into the outbox but deliberately left the new gate
-- identity nullable. Reconstruct only that exact copied identity before installing the FK; never
-- fabricate bytes for legacy rows whose comment/payload is absent.
UPDATE gate_record g
SET continuation_schema_version = o.handler_contract_version,
    continuation_sha256 = o.payload_sha256
FROM plan_gate_resume_outbox o
WHERE o.gate_key = g.gate_key
  AND o.run_id = g.run_id
  AND o.payload IS NOT NULL
  AND o.payload = g.comment
  AND g.continuation_schema_version IS NULL
  AND g.continuation_sha256 IS NULL;

ALTER TABLE gate_record
    ADD CONSTRAINT uq_gate_plan_continuation_identity UNIQUE (
        gate_key, run_id, continuation_schema_version, continuation_sha256
    );

ALTER TABLE plan_gate_resume_outbox
    ADD CONSTRAINT fk_plan_gate_resume_exact_gate
    FOREIGN KEY (gate_key, run_id, handler_contract_version, payload_sha256)
    REFERENCES gate_record (
        gate_key, run_id, continuation_schema_version, continuation_sha256
    ) ON DELETE CASCADE;

-- Contract-report evidence is exact-run/exact-digest evidence, not a free-form SHA-shaped claim.
ALTER TABLE plan_contract_gate_report
    ADD CONSTRAINT uq_plan_contract_gate_report_identity UNIQUE (run_id, report_digest);

ALTER TABLE gate_record
    ADD CONSTRAINT fk_gate_contract_report_exact
    FOREIGN KEY (run_id, contract_gate_report_digest)
    REFERENCES plan_contract_gate_report (run_id, report_digest)
    ON DELETE RESTRICT;

-- Historical approvals with no continuation bytes are evidence, not executable work.
UPDATE plan_gate_resume_outbox
SET status = 'DEAD',
    completed_at = COALESCE(completed_at, NOW()),
    last_error = COALESCE(last_error, 'LEGACY_GATE_MISSING_CONTINUATION_PAYLOAD')
WHERE status = 'READY' AND payload IS NULL;

ALTER TABLE gate_record
    ADD CONSTRAINT ck_plan_gate_resolution_consistency
    CHECK (
        gate_type <> 'PLAN'
        OR (status = 'PENDING' AND resolved_at IS NULL)
        OR (status IN ('APPROVED', 'REJECTED') AND resolved_at IS NOT NULL)
    ) NOT VALID;

ALTER TABLE gate_record
    ADD CONSTRAINT ck_pending_plan_gate_has_no_admission_receipt
    CHECK (
        gate_type <> 'PLAN'
        OR status <> 'PENDING'
        OR (
            admission_contract_version IS NULL
            AND approval_evidence_json IS NULL
            AND approval_evidence_digest IS NULL
            AND resolved_by_user_id IS NULL
            AND resolved_by_role IS NULL
            AND resolution_source IS NULL
            AND resolution_reason IS NULL
            AND billing_owner_user_id IS NULL
            AND billing_org_id IS NULL
            AND admitted_at IS NULL
        )
    ) NOT VALID;

ALTER TABLE plan_gate_resume_outbox
    ADD CONSTRAINT ck_plan_gate_resume_executable_payload
        CHECK (status NOT IN ('READY', 'PROCESSING') OR payload IS NOT NULL),
    ADD CONSTRAINT ck_plan_gate_resume_processing_heartbeat
        CHECK (status <> 'PROCESSING' OR heartbeat_at IS NOT NULL);

CREATE OR REPLACE FUNCTION enforce_plan_gate_admission_receipt()
RETURNS trigger
LANGUAGE plpgsql
AS $$
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

    -- A resolved decision is an audit fact. Replays may read it but cannot rewrite its meaning.
    IF TG_OP = 'UPDATE' AND (OLD.resolved_at IS NOT NULL
            OR OLD.status IN ('APPROVED', 'REJECTED')) AND (
        NEW.status IS DISTINCT FROM OLD.status
        OR NEW.resolved_at IS DISTINCT FROM OLD.resolved_at
    ) THEN
        RAISE EXCEPTION 'resolved gate decision is immutable';
    END IF;

    -- Gate-open evidence is frozen independently from the later admission receipt. Approval may
    -- add actor/payer/policy evidence once, but can never swap the reviewed plan or continuation.
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

    -- Once admission is written, its policy/actor/payer evidence is immutable.
    IF TG_OP = 'UPDATE' AND OLD.admission_contract_version IS NOT NULL AND (
        NEW.admission_contract_version IS DISTINCT FROM OLD.admission_contract_version
        OR NEW.approval_evidence_json IS DISTINCT FROM OLD.approval_evidence_json
        OR NEW.approval_evidence_digest IS DISTINCT FROM OLD.approval_evidence_digest
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

CREATE OR REPLACE FUNCTION enforce_plan_gate_resume_outbox()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.payload_sha256 IS NOT NULL AND (
        NEW.payload IS NULL
        OR encode(sha256(convert_to(NEW.payload, 'UTF8')), 'hex') <> NEW.payload_sha256
    ) THEN
        RAISE EXCEPTION 'plan gate outbox payload digest does not match exact bytes';
    END IF;

    IF TG_OP = 'UPDATE' AND (
        NEW.run_id IS DISTINCT FROM OLD.run_id
        OR NEW.gate_key IS DISTINCT FROM OLD.gate_key
        OR NEW.payload IS DISTINCT FROM OLD.payload
        OR NEW.payload_sha256 IS DISTINCT FROM OLD.payload_sha256
        OR NEW.handler_contract_version IS DISTINCT FROM OLD.handler_contract_version
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
    ) THEN
        RAISE EXCEPTION 'plan gate outbox identity is immutable';
    END IF;

    IF TG_OP = 'UPDATE' AND NOT (
        NEW.status = OLD.status
        OR (OLD.status = 'WAITING' AND NEW.status IN ('READY', 'CANCELLED', 'DEAD'))
        OR (OLD.status = 'READY' AND NEW.status IN ('PROCESSING', 'CANCELLED', 'DEAD'))
        OR (OLD.status = 'PROCESSING' AND NEW.status IN ('READY', 'COMPLETED', 'CANCELLED', 'DEAD'))
    ) THEN
        RAISE EXCEPTION 'illegal plan gate outbox status transition: % -> %', OLD.status, NEW.status;
    END IF;

    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

DROP TRIGGER trg_plan_gate_resume_outbox_updated_at ON plan_gate_resume_outbox;
CREATE TRIGGER trg_plan_gate_resume_outbox_guard
BEFORE INSERT OR UPDATE ON plan_gate_resume_outbox
FOR EACH ROW EXECUTE FUNCTION enforce_plan_gate_resume_outbox();
