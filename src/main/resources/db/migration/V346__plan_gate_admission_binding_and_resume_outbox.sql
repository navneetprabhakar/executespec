-- V346: durable plan-gate admission binding and recoverable continuation outbox.
--
-- The gate row is the admission receipt. All new columns are nullable so an old application node
-- can continue to open/resolve gates during a rolling deployment. A NULL admission_contract_version
-- is therefore meaningful: it is a historical/unbound decision, never proof that current policy
-- or RequirementIR enforcement ran.

ALTER TABLE gate_record
    ADD COLUMN admission_contract_version INTEGER,
    ADD COLUMN binding_mode VARCHAR(16),
    ADD COLUMN binding_status VARCHAR(16),
    ADD COLUMN requirement_contract_id UUID,
    ADD COLUMN binding_schema_version INTEGER,
    ADD COLUMN binding_compiler_fingerprint VARCHAR(64),
    ADD COLUMN binding_source_sha256 VARCHAR(64),
    ADD COLUMN binding_exact_plan_fingerprint VARCHAR(64),
    ADD COLUMN binding_artifact_digest VARCHAR(64),
    ADD COLUMN binding_bound_digest VARCHAR(64),
    ADD COLUMN binding_bound_at TIMESTAMPTZ,
    ADD COLUMN binding_reason VARCHAR(512),
    ADD COLUMN contract_gate_report_digest VARCHAR(64),
    ADD COLUMN continuation_schema_version INTEGER,
    ADD COLUMN continuation_sha256 VARCHAR(64),
    ADD COLUMN approval_evidence_json TEXT,
    ADD COLUMN approval_evidence_digest VARCHAR(64),
    ADD COLUMN resolved_by_user_id VARCHAR(64),
    ADD COLUMN resolved_by_role VARCHAR(64),
    ADD COLUMN resolution_source VARCHAR(64),
    ADD COLUMN resolution_reason VARCHAR(512),
    ADD COLUMN billing_owner_user_id VARCHAR(64),
    ADD COLUMN billing_org_id VARCHAR(64),
    ADD COLUMN admitted_at TIMESTAMPTZ;

-- The copied gate identity must describe the referenced immutable artifact, not merely point at
-- some artifact UUID. This composite key lets PostgreSQL reject a receipt whose plan/source/digest
-- snapshots disagree with the selected V345 row even if application validation is bypassed.
ALTER TABLE run_requirement_contract
    ADD CONSTRAINT uq_run_requirement_contract_gate_binding UNIQUE (
        id,
        run_id,
        schema_version,
        compiler_fingerprint,
        source_sha256,
        exact_plan_fingerprint,
        artifact_digest,
        bound_digest
    );

-- Historical gate rows may already outlive deleted runs, so this rolling-safe FK is NOT VALID for
-- old data but enforced for every new/changed row. Future parent deletion cascades the gate first,
-- allowing the exact RequirementIR FK below and the V345 artifact cascade to coexist without
-- blocking retention/GDPR cleanup.
ALTER TABLE gate_record
    ADD CONSTRAINT fk_gate_record_pipeline_run
    FOREIGN KEY (run_id) REFERENCES pipeline_run(run_id) ON DELETE CASCADE NOT VALID;

ALTER TABLE gate_record
    ADD CONSTRAINT ck_gate_admission_contract_version
        CHECK (admission_contract_version IS NULL OR admission_contract_version > 0),
    ADD CONSTRAINT ck_gate_binding_mode
        CHECK (binding_mode IS NULL OR binding_mode IN ('LEGACY', 'SHADOW', 'ENFORCED')),
    ADD CONSTRAINT ck_gate_binding_status
        CHECK (binding_status IS NULL OR binding_status IN (
            'UNBOUND', 'BOUND', 'BLOCKED', 'MISSING', 'INVALID', 'UNAVAILABLE')),
    ADD CONSTRAINT ck_gate_binding_schema_version
        CHECK (binding_schema_version IS NULL OR binding_schema_version > 0),
    ADD CONSTRAINT ck_gate_continuation_schema_version
        CHECK (continuation_schema_version IS NULL OR continuation_schema_version > 0),
    ADD CONSTRAINT ck_gate_binding_compiler_sha
        CHECK (binding_compiler_fingerprint IS NULL OR binding_compiler_fingerprint ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_gate_binding_source_sha
        CHECK (binding_source_sha256 IS NULL OR binding_source_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_gate_binding_plan_sha
        CHECK (binding_exact_plan_fingerprint IS NULL OR binding_exact_plan_fingerprint ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_gate_binding_artifact_sha
        CHECK (binding_artifact_digest IS NULL OR binding_artifact_digest ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_gate_binding_bound_sha
        CHECK (binding_bound_digest IS NULL OR binding_bound_digest ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_gate_contract_report_sha
        CHECK (contract_gate_report_digest IS NULL OR contract_gate_report_digest ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_gate_continuation_sha
        CHECK (continuation_sha256 IS NULL OR continuation_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_gate_approval_evidence_sha
        CHECK (approval_evidence_digest IS NULL OR approval_evidence_digest ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_gate_approval_evidence_object
        CHECK (approval_evidence_json IS NULL OR jsonb_typeof(approval_evidence_json::jsonb) = 'object'),
    ADD CONSTRAINT ck_gate_approval_evidence_pair
        CHECK ((approval_evidence_json IS NULL) = (approval_evidence_digest IS NULL)),
    ADD CONSTRAINT fk_gate_requirement_contract_exact
        FOREIGN KEY (
            requirement_contract_id,
            run_id,
            binding_schema_version,
            binding_compiler_fingerprint,
            binding_source_sha256,
            binding_exact_plan_fingerprint,
            binding_artifact_digest,
            binding_bound_digest
        ) REFERENCES run_requirement_contract (
            id,
            run_id,
            schema_version,
            compiler_fingerprint,
            source_sha256,
            exact_plan_fingerprint,
            artifact_digest,
            bound_digest
        )
        ON DELETE RESTRICT,
    ADD CONSTRAINT ck_gate_binding_identity_all_or_null
        CHECK (num_nonnulls(
            requirement_contract_id,
            binding_schema_version,
            binding_compiler_fingerprint,
            binding_source_sha256,
            binding_exact_plan_fingerprint,
            binding_artifact_digest,
            binding_bound_digest,
            binding_bound_at
        ) IN (0, 8)),
    ADD CONSTRAINT ck_gate_bound_has_identity
        CHECK (binding_status IS DISTINCT FROM 'BOUND' OR num_nonnulls(
            requirement_contract_id,
            binding_schema_version,
            binding_compiler_fingerprint,
            binding_source_sha256,
            binding_exact_plan_fingerprint,
            binding_artifact_digest,
            binding_bound_digest,
            binding_bound_at
        ) = 8),
    ADD CONSTRAINT ck_gate_legacy_has_no_contract_binding
        CHECK (binding_mode IS DISTINCT FROM 'LEGACY' OR num_nonnulls(
            requirement_contract_id,
            binding_schema_version,
            binding_compiler_fingerprint,
            binding_source_sha256,
            binding_exact_plan_fingerprint,
            binding_artifact_digest,
            binding_bound_digest,
            binding_bound_at
        ) = 0);

-- Safely label historical PLAN decisions without inventing a receipt, actor, payer, RequirementIR
-- binding or policy result. New code must re-admit rows whose contract version remains NULL.
UPDATE gate_record
SET binding_mode = 'LEGACY',
    binding_status = 'UNBOUND',
    binding_reason = 'HISTORICAL_DECISION_WITHOUT_REQUIREMENT_BINDING',
    resolution_source = 'LEGACY_PRE_V346',
    resolution_reason = 'HISTORICAL_GATE_WITHOUT_ADMISSION_RECEIPT'
WHERE gate_type = 'PLAN'
  AND status IN ('APPROVED', 'REJECTED')
  AND admission_contract_version IS NULL;

CREATE OR REPLACE FUNCTION enforce_plan_gate_admission_receipt()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    -- A resolved decision is an audit fact. Replays may read it but cannot rewrite its meaning.
    IF TG_OP = 'UPDATE' AND (OLD.resolved_at IS NOT NULL
            OR OLD.status IN ('APPROVED', 'REJECTED')) AND (
        NEW.status IS DISTINCT FROM OLD.status
        OR NEW.resolved_at IS DISTINCT FROM OLD.resolved_at
    ) THEN
        RAISE EXCEPTION 'resolved gate decision is immutable';
    END IF;

    -- Once a versioned receipt exists, its complete identity and policy evidence are immutable.
    IF TG_OP = 'UPDATE' AND OLD.admission_contract_version IS NOT NULL AND (
        NEW.admission_contract_version IS DISTINCT FROM OLD.admission_contract_version
        OR NEW.binding_mode IS DISTINCT FROM OLD.binding_mode
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

    -- Enforcement may be activated only with exact, READY RequirementIR and byte-bound policy and
    -- continuation evidence. SHADOW and LEGACY rows remain descriptive and cannot imply this gate.
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

CREATE TRIGGER trg_gate_record_admission_receipt
BEFORE INSERT OR UPDATE ON gate_record
FOR EACH ROW EXECUTE FUNCTION enforce_plan_gate_admission_receipt();

-- One continuation intent per run. It is staged in the same transaction as the receipt/gate claim
-- and then leased by any healthy JVM after commit. Payload stays TEXT so its SHA binds exact bytes.
CREATE TABLE plan_gate_resume_outbox (
    run_id                    VARCHAR(255) PRIMARY KEY,
    gate_key                  VARCHAR(255) NOT NULL UNIQUE,
    payload                   TEXT,
    payload_sha256            VARCHAR(64),
    handler_contract_version  INTEGER NOT NULL,
    status                    VARCHAR(16) NOT NULL,
    attempt_count             INTEGER NOT NULL DEFAULT 0,
    max_attempts              INTEGER NOT NULL DEFAULT 8,
    next_retry_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error                TEXT,
    lease_owner               VARCHAR(160),
    lease_token               UUID,
    lease_expires_at          TIMESTAMPTZ,
    heartbeat_at              TIMESTAMPTZ,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at              TIMESTAMPTZ,
    CONSTRAINT fk_plan_gate_resume_run
        FOREIGN KEY (run_id) REFERENCES pipeline_run(run_id) ON DELETE CASCADE,
    CONSTRAINT fk_plan_gate_resume_gate
        FOREIGN KEY (gate_key) REFERENCES gate_record(gate_key) ON DELETE CASCADE,
    CONSTRAINT ck_plan_gate_resume_payload_pair
        CHECK ((payload IS NULL) = (payload_sha256 IS NULL)),
    CONSTRAINT ck_plan_gate_resume_payload_sha
        CHECK (payload_sha256 IS NULL OR payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_plan_gate_resume_handler_version
        CHECK (handler_contract_version > 0),
    CONSTRAINT ck_plan_gate_resume_status
        CHECK (status IN ('WAITING', 'READY', 'PROCESSING', 'COMPLETED', 'CANCELLED', 'DEAD')),
    CONSTRAINT ck_plan_gate_resume_attempts
        CHECK (attempt_count >= 0 AND max_attempts > 0 AND attempt_count <= max_attempts),
    CONSTRAINT ck_plan_gate_resume_lease
        CHECK (
            (status = 'PROCESSING' AND lease_owner IS NOT NULL AND lease_token IS NOT NULL
                AND lease_expires_at IS NOT NULL)
            OR (status <> 'PROCESSING' AND lease_owner IS NULL AND lease_token IS NULL
                AND lease_expires_at IS NULL)
        ),
    CONSTRAINT ck_plan_gate_resume_terminal_time
        CHECK (
            (status IN ('COMPLETED', 'CANCELLED', 'DEAD') AND completed_at IS NOT NULL)
            OR (status NOT IN ('COMPLETED', 'CANCELLED', 'DEAD') AND completed_at IS NULL)
        )
);

CREATE INDEX idx_plan_gate_resume_due
    ON plan_gate_resume_outbox (next_retry_at, created_at)
    WHERE status = 'READY';
CREATE INDEX idx_plan_gate_resume_stale_lease
    ON plan_gate_resume_outbox (lease_expires_at)
    WHERE status = 'PROCESSING';

CREATE OR REPLACE FUNCTION touch_plan_gate_resume_outbox()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_plan_gate_resume_outbox_updated_at
BEFORE UPDATE ON plan_gate_resume_outbox
FOR EACH ROW EXECUTE FUNCTION touch_plan_gate_resume_outbox();

-- Preserve the old comment continuation bytes. Missing historical payloads stay explicitly NULL
-- and labelled; no replacement continuation is fabricated. New V346 writers must supply payload.
INSERT INTO plan_gate_resume_outbox (
    run_id, gate_key, payload, payload_sha256, handler_contract_version, status,
    next_retry_at, last_error, completed_at
)
SELECT
    g.run_id,
    g.gate_key,
    g.comment,
    CASE WHEN g.comment IS NULL THEN NULL
         ELSE encode(sha256(convert_to(g.comment, 'UTF8')), 'hex') END,
    1,
    CASE
        WHEN g.status = 'PENDING' THEN 'WAITING'
        WHEN g.status = 'REJECTED' THEN 'CANCELLED'
        WHEN g.status = 'APPROVED'
             AND p.status IN ('AWAITING_APPROVAL', 'APPROVAL_RESUMING') THEN 'READY'
        WHEN g.status = 'APPROVED' THEN 'COMPLETED'
        ELSE 'WAITING'
    END,
    NOW(),
    CASE WHEN g.comment IS NULL THEN 'LEGACY_GATE_MISSING_CONTINUATION_PAYLOAD' ELSE NULL END,
    CASE WHEN g.status = 'REJECTED' OR (g.status = 'APPROVED'
              AND p.status NOT IN ('AWAITING_APPROVAL', 'APPROVAL_RESUMING'))
         THEN COALESCE(g.resolved_at, NOW()) ELSE NULL END
FROM gate_record g
JOIN pipeline_run p ON p.run_id = g.run_id
WHERE g.gate_type = 'PLAN'
  AND g.status IN ('PENDING', 'APPROVED', 'REJECTED')
ON CONFLICT (run_id) DO NOTHING;

COMMENT ON TABLE plan_gate_resume_outbox IS
    'Fenced, restart-recoverable continuation intent staged atomically with a plan-gate admission receipt.';
COMMENT ON COLUMN gate_record.admission_contract_version IS
    'NULL means historical/unbound; a positive value identifies an immutable versioned admission receipt.';
COMMENT ON COLUMN gate_record.approval_evidence_json IS
    'Canonical byte-stable admission-policy evidence; TEXT is intentional and digest-bound.';
