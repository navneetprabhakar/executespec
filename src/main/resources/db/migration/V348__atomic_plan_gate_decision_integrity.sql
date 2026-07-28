-- V348: make a plan-gate decision one immutable, cross-row database fact.
--
-- V347 made gate opening atomic. This migration closes the matching decision-side integrity gap:
-- a versioned decision names the decision and actor, binds the exact request bytes, verifies the
-- contract-report bytes, and can commit only with a coherent pipeline-run/gate/outbox tuple.
-- Historical rows whose admission_contract_version is NULL remain explicitly outside this
-- contract; the migration never invents a decision receipt for them.

ALTER TABLE gate_record
    ADD COLUMN decision_kind VARCHAR(16),
    ADD COLUMN resolved_actor_type VARCHAR(16),
    ADD COLUMN decision_request_fingerprint VARCHAR(64),
    ADD COLUMN resolved_authorization_source VARCHAR(64),
    ADD COLUMN resolved_authorization_org_id VARCHAR(64);

ALTER TABLE gate_record
    ADD CONSTRAINT ck_gate_decision_kind
        CHECK (decision_kind IS NULL OR decision_kind IN ('APPROVE', 'REJECT', 'CANCEL')),
    ADD CONSTRAINT ck_gate_resolved_actor_type
        CHECK (resolved_actor_type IS NULL OR resolved_actor_type IN ('HUMAN', 'SYSTEM')),
    ADD CONSTRAINT ck_plan_gate_admission_contract_version
        CHECK (
            gate_type <> 'PLAN'
            OR admission_contract_version IS NULL
            OR admission_contract_version = 1
        ) NOT VALID,
    ADD CONSTRAINT ck_versioned_plan_gate_resolution_source
        CHECK (
            gate_type <> 'PLAN'
            OR admission_contract_version IS NULL
            OR resolution_source IN (
                'PLAN_APPROVAL_API',
                'EXECUTION_VIEW_API',
                'PROJECT_CHAT_ACTION',
                'RUN_CANCEL_API',
                'SYSTEM_CANCEL'
            )
        ) NOT VALID,
    ADD CONSTRAINT ck_versioned_plan_gate_resolution_mapping
        CHECK (
            gate_type <> 'PLAN'
            OR admission_contract_version IS NULL
            OR (
                decision_kind IN ('APPROVE', 'REJECT')
                AND resolution_source IN (
                    'PLAN_APPROVAL_API', 'EXECUTION_VIEW_API', 'PROJECT_CHAT_ACTION')
                AND resolved_actor_type = 'HUMAN'
            )
            OR (
                decision_kind = 'CANCEL'
                AND resolution_source IN (
                    'RUN_CANCEL_API', 'PROJECT_CHAT_ACTION', 'SYSTEM_CANCEL')
                AND ((resolution_source = 'SYSTEM_CANCEL' AND resolved_actor_type = 'SYSTEM')
                    OR (resolution_source <> 'SYSTEM_CANCEL'
                        AND resolved_actor_type = 'HUMAN'))
            )
        ) NOT VALID,
    ADD CONSTRAINT ck_gate_decision_request_sha
        CHECK (decision_request_fingerprint IS NULL
            OR decision_request_fingerprint ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_versioned_plan_gate_decision_receipt
        CHECK (
            gate_type <> 'PLAN'
            OR admission_contract_version IS NULL
            OR (
                status IN ('APPROVED', 'REJECTED', 'CANCELLED')
                AND resolved_at IS NOT NULL
                AND decision_kind IS NOT NULL
                AND resolved_actor_type IS NOT NULL
                AND decision_request_fingerprint IS NOT NULL
                AND resolved_authorization_source IS NOT NULL
                AND approval_evidence_json IS NOT NULL
                AND approval_evidence_digest IS NOT NULL
                AND resolution_source IS NOT NULL
                AND admitted_at IS NOT NULL
                AND (resolved_actor_type <> 'HUMAN'
                    OR (resolved_by_user_id IS NOT NULL AND resolved_by_role IS NOT NULL))
                AND (decision_kind <> 'APPROVE' OR billing_owner_user_id IS NOT NULL)
                AND (decision_kind <> 'REJECT' OR resolution_reason IS NOT NULL)
                AND (decision_kind <> 'CANCEL' OR resolution_reason IS NOT NULL)
                AND (
                    (decision_kind = 'APPROVE' AND status = 'APPROVED')
                    OR (decision_kind = 'REJECT' AND status = 'REJECTED')
                    OR (decision_kind = 'CANCEL' AND status = 'CANCELLED')
                )
            )
        ) NOT VALID;

ALTER TABLE gate_record
    ADD CONSTRAINT ck_gate_resolved_authorization_source
        CHECK (resolved_authorization_source IS NULL OR resolved_authorization_source IN (
            'RUN_OWNER', 'ORG_ADMIN', 'GLOBAL_SUPER_ADMIN', 'SYSTEM_INTERNAL')),
    ADD CONSTRAINT ck_gate_resolved_authorization_shape
        CHECK (
            admission_contract_version IS NULL
            OR (
                (resolved_authorization_source IN ('RUN_OWNER', 'GLOBAL_SUPER_ADMIN')
                    AND resolved_authorization_org_id IS NULL)
                OR (resolved_authorization_source = 'ORG_ADMIN'
                    AND resolved_authorization_org_id IS NOT NULL)
                OR resolved_authorization_source = 'SYSTEM_INTERNAL'
            )
        ) NOT VALID,
    ADD CONSTRAINT ck_gate_actor_authorization_type
        CHECK (
            admission_contract_version IS NULL
            OR (resolved_actor_type = 'SYSTEM'
                AND resolved_authorization_source = 'SYSTEM_INTERNAL')
            OR (resolved_actor_type = 'HUMAN'
                AND resolved_authorization_source IN (
                    'RUN_OWNER', 'ORG_ADMIN', 'GLOBAL_SUPER_ADMIN'))
        ) NOT VALID,
    ADD CONSTRAINT ck_gate_actor_authorization_role
        CHECK (
            admission_contract_version IS NULL
            OR resolved_authorization_source NOT IN ('ORG_ADMIN', 'GLOBAL_SUPER_ADMIN')
            OR (resolved_authorization_source = 'ORG_ADMIN' AND resolved_by_role = 'ORG_ADMIN')
            OR (resolved_authorization_source = 'GLOBAL_SUPER_ADMIN'
                AND resolved_by_role = 'SUPER_ADMIN')
        ) NOT VALID;

-- The platform has exactly one PLAN gate per run. Without this partial key a second unbound PLAN
-- row could sit outside the outbox FK and make decision/recovery reads ambiguous.
CREATE UNIQUE INDEX uq_gate_record_plan_run
    ON gate_record (run_id)
    WHERE gate_type = 'PLAN';

-- Do not let a valid digest wrap semantically unrelated decision evidence. These checks mirror the
-- stable DecisionEvidenceV1 contract while retaining TEXT byte authority in the columns above.
CREATE OR REPLACE FUNCTION plan_decision_evidence_has_exact_v1_shape(evidence TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS $$
DECLARE
    parsed JSONB := evidence::jsonb;
    required_keys TEXT[] := ARRAY[
        'schemaVersion', 'policyContract', 'decision', 'decidedAt', 'runId',
        'gateKey', 'resolutionSource', 'resolutionReason', 'actorType', 'actorUserId',
        'globalRole', 'orgRole', 'effectiveRole', 'authorizationBasis',
        'authorizationOrgId', 'reviewedPlanFingerprint', 'suppliedPlanFingerprint',
        'planFingerprintMatched', 'decisionRequestFingerprint', 'continuationSchemaVersion',
        'continuationSha256', 'bindingMode', 'bindingStatus', 'requirementContractId',
        'bindingSchemaVersion', 'bindingCompilerFingerprint', 'bindingSourceSha256',
        'bindingExactPlanFingerprint', 'bindingArtifactDigest', 'bindingBoundDigest',
        'bindingReason', 'requirementCompleteness', 'requirementCount',
        'acceptanceCriterionCount', 'taskBindingCount', 'issueCount', 'blockingIssueCount',
        'executionMode', 'estimateHighUsd', 'hardCapUsd', 'expectedCredits',
        'creditsConfirmed', 'creditsMatched', 'billingOwnerUserId', 'billingOrgId', 'provider',
        'keySourceMode', 'credentialOrigin', 'credentialAuthorizationSource',
        'credentialAuthorizationOrgId', 'ownKeyExempt', 'managedAiOutcome', 'managedAiMode',
        'managedAiGrantCredits', 'managedAiCreditsRemaining', 'managedAiEstimatedCredits',
        'managedAiHardRawUsd', 'managedAiRemainingRawUsd', 'governanceOrgOutcome',
        'governanceOrgThresholdUsd', 'governanceOrgEstimateUsd', 'governanceOrgReason',
        'governanceProjectOutcome', 'governanceProjectCapUsd',
        'governanceProjectMonthToDateUsd', 'governanceProjectProjectedUsd',
        'governanceProjectReason', 'contractGateReportDigest', 'contractRequiresTypedAck',
        'contractAcknowledgedAt', 'contractAcknowledgedByUserId',
        'contractAcknowledgementText', 'contractAcknowledgementOutcome'
    ]::text[];
BEGIN
    RETURN jsonb_typeof(parsed) = 'object'
        AND parsed ?& required_keys
        AND (parsed - required_keys) = '{}'::jsonb;
END;
$$;

ALTER TABLE gate_record
    ADD CONSTRAINT ck_plan_gate_decision_evidence_shape
        CHECK (
            gate_type <> 'PLAN'
            OR admission_contract_version IS NULL
            OR plan_decision_evidence_has_exact_v1_shape(approval_evidence_json)
        ) NOT VALID,
    ADD CONSTRAINT ck_plan_gate_decision_evidence_identity
        CHECK (
            gate_type <> 'PLAN'
            OR admission_contract_version IS NULL
            OR (
                (approval_evidence_json::jsonb ->> 'schemaVersion')
                    IS NOT DISTINCT FROM admission_contract_version::text
                AND (approval_evidence_json::jsonb ->> 'policyContract')
                    IS NOT DISTINCT FROM 'PLAN_GATE_DECISION_V1'
                AND (approval_evidence_json::jsonb ->> 'decision')
                    IS NOT DISTINCT FROM decision_kind
                AND (approval_evidence_json::jsonb ->> 'actorType')
                    IS NOT DISTINCT FROM resolved_actor_type
                AND (approval_evidence_json::jsonb ->> 'actorUserId')
                    IS NOT DISTINCT FROM resolved_by_user_id
                AND (approval_evidence_json::jsonb ->> 'effectiveRole')
                    IS NOT DISTINCT FROM resolved_by_role
                AND (approval_evidence_json::jsonb ->> 'resolutionSource')
                    IS NOT DISTINCT FROM resolution_source
                AND (approval_evidence_json::jsonb ->> 'resolutionReason')
                    IS NOT DISTINCT FROM resolution_reason
                AND (approval_evidence_json::jsonb ->> 'decidedAt')::timestamptz
                    IS NOT DISTINCT FROM resolved_at
                AND (approval_evidence_json::jsonb ->> 'runId') IS NOT DISTINCT FROM run_id
                AND (approval_evidence_json::jsonb ->> 'gateKey') IS NOT DISTINCT FROM gate_key
                AND (approval_evidence_json::jsonb ->> 'decisionRequestFingerprint')
                    IS NOT DISTINCT FROM decision_request_fingerprint
                AND (approval_evidence_json::jsonb ->> 'authorizationBasis')
                    IS NOT DISTINCT FROM resolved_authorization_source
                AND (approval_evidence_json::jsonb ->> 'authorizationOrgId')
                    IS NOT DISTINCT FROM resolved_authorization_org_id
                AND (approval_evidence_json::jsonb ->> 'billingOwnerUserId')
                    IS NOT DISTINCT FROM billing_owner_user_id
                AND (approval_evidence_json::jsonb ->> 'billingOrgId')
                    IS NOT DISTINCT FROM billing_org_id
                AND (approval_evidence_json::jsonb ->> 'continuationSchemaVersion')
                    IS NOT DISTINCT FROM continuation_schema_version::text
                AND (approval_evidence_json::jsonb ->> 'continuationSha256')
                    IS NOT DISTINCT FROM continuation_sha256
                AND (approval_evidence_json::jsonb ->> 'bindingMode')
                    IS NOT DISTINCT FROM binding_mode
                AND (approval_evidence_json::jsonb ->> 'bindingStatus')
                    IS NOT DISTINCT FROM binding_status
                AND (approval_evidence_json::jsonb ->> 'requirementContractId')
                    IS NOT DISTINCT FROM requirement_contract_id::text
                AND (approval_evidence_json::jsonb ->> 'bindingSchemaVersion')
                    IS NOT DISTINCT FROM binding_schema_version::text
                AND (approval_evidence_json::jsonb ->> 'bindingCompilerFingerprint')
                    IS NOT DISTINCT FROM binding_compiler_fingerprint
                AND (approval_evidence_json::jsonb ->> 'bindingSourceSha256')
                    IS NOT DISTINCT FROM binding_source_sha256
                AND (approval_evidence_json::jsonb ->> 'bindingExactPlanFingerprint')
                    IS NOT DISTINCT FROM binding_exact_plan_fingerprint
                AND (approval_evidence_json::jsonb ->> 'bindingArtifactDigest')
                    IS NOT DISTINCT FROM binding_artifact_digest
                AND (approval_evidence_json::jsonb ->> 'bindingBoundDigest')
                    IS NOT DISTINCT FROM binding_bound_digest
                AND (approval_evidence_json::jsonb ->> 'bindingReason')
                    IS NOT DISTINCT FROM binding_reason
                AND (approval_evidence_json::jsonb ->> 'contractGateReportDigest')
                    IS NOT DISTINCT FROM contract_gate_report_digest
            )
        ) NOT VALID,
    ADD CONSTRAINT ck_plan_gate_decision_policy_outcomes
        CHECK (
            gate_type <> 'PLAN'
            OR admission_contract_version IS NULL
            OR (
                decision_kind = 'APPROVE'
                AND (approval_evidence_json::jsonb ->> 'managedAiOutcome')
                    IS NOT DISTINCT FROM 'ALLOWED'
                AND (approval_evidence_json::jsonb ->> 'governanceOrgOutcome')
                    IS NOT DISTINCT FROM 'ALLOWED'
                AND (approval_evidence_json::jsonb ->> 'governanceProjectOutcome')
                    IS NOT DISTINCT FROM 'ALLOWED'
                AND approval_evidence_json::jsonb ->> 'contractAcknowledgementOutcome' IS NOT NULL
                AND approval_evidence_json::jsonb ->> 'contractAcknowledgementOutcome'
                    IN ('NOT_REQUIRED', 'ACKNOWLEDGED')
                AND approval_evidence_json::jsonb ->> 'credentialAuthorizationSource' IS NOT NULL
                AND approval_evidence_json::jsonb ->> 'credentialAuthorizationSource'
                    IN ('SYSTEM_PLATFORM_KEY', 'USER_CUSTOMER_KEY', 'ORG_CUSTOMER_KEY')
                AND approval_evidence_json::jsonb ->> 'provider' IS NOT NULL
                AND approval_evidence_json::jsonb ->> 'keySourceMode' IS NOT NULL
                AND approval_evidence_json::jsonb ->> 'credentialOrigin' IS NOT NULL
                AND approval_evidence_json::jsonb ->> 'ownKeyExempt' IS NOT NULL
                AND approval_evidence_json::jsonb ->> 'managedAiMode' IS NOT NULL
                AND (approval_evidence_json::jsonb ->> 'credentialAuthorizationOrgId')
                    IS NOT DISTINCT FROM billing_org_id
                AND (
                    (
                        approval_evidence_json::jsonb ->> 'suppliedPlanFingerprint' IS NULL
                        AND approval_evidence_json::jsonb ->> 'planFingerprintMatched' IS NULL
                        AND binding_mode <> 'ENFORCED'
                    )
                    OR (
                        approval_evidence_json::jsonb ->> 'suppliedPlanFingerprint'
                            ~ '^[0-9a-f]{64}$'
                        AND (approval_evidence_json::jsonb ->> 'planFingerprintMatched')::boolean
                            IS TRUE
                    )
                )
                AND (approval_evidence_json::jsonb ->> 'expectedCredits') ~ '^[1-9][0-9]*$'
                AND (
                    (
                        approval_evidence_json::jsonb ->> 'creditsConfirmed' IS NULL
                        AND approval_evidence_json::jsonb ->> 'creditsMatched' IS NULL
                    )
                    OR (
                        approval_evidence_json::jsonb ->> 'creditsConfirmed'
                            IS NOT DISTINCT FROM
                            approval_evidence_json::jsonb ->> 'expectedCredits'
                        AND (approval_evidence_json::jsonb ->> 'creditsConfirmed')
                            ~ '^[1-9][0-9]*$'
                        AND (approval_evidence_json::jsonb ->> 'creditsMatched')::boolean IS TRUE
                    )
                )
            )
            OR (
                decision_kind IN ('REJECT', 'CANCEL')
                AND (approval_evidence_json::jsonb ->> 'managedAiOutcome')
                    IS NOT DISTINCT FROM 'NOT_EVALUATED'
                AND (approval_evidence_json::jsonb ->> 'governanceOrgOutcome')
                    IS NOT DISTINCT FROM 'NOT_EVALUATED'
                AND (approval_evidence_json::jsonb ->> 'governanceProjectOutcome')
                    IS NOT DISTINCT FROM 'NOT_EVALUATED'
                AND (approval_evidence_json::jsonb ->> 'contractAcknowledgementOutcome')
                    IS NOT DISTINCT FROM 'NOT_EVALUATED'
                AND approval_evidence_json::jsonb ->> 'credentialAuthorizationSource' IS NULL
                AND approval_evidence_json::jsonb ->> 'credentialAuthorizationOrgId' IS NULL
            )
        ) NOT VALID,
    ADD CONSTRAINT ck_plan_gate_decision_fixed_scale_money
        CHECK (
            gate_type <> 'PLAN'
            OR admission_contract_version IS NULL
            OR (
                (approval_evidence_json::jsonb ->> 'estimateHighUsd')
                    ~ '^[0-9]+\.[0-9]{6}$'
                AND COALESCE(
                    (approval_evidence_json::jsonb ->> 'hardCapUsd')
                        ~ '^[0-9]+\.[0-9]{6}$', TRUE)
                AND COALESCE(
                    (approval_evidence_json::jsonb ->> 'managedAiHardRawUsd')
                        ~ '^[0-9]+\.[0-9]{6}$', TRUE)
                AND COALESCE(
                    (approval_evidence_json::jsonb ->> 'managedAiRemainingRawUsd')
                        ~ '^[0-9]+\.[0-9]{6}$', TRUE)
                AND COALESCE(
                    (approval_evidence_json::jsonb ->> 'governanceOrgThresholdUsd')
                        ~ '^[0-9]+\.[0-9]{6}$', TRUE)
                AND COALESCE(
                    (approval_evidence_json::jsonb ->> 'governanceOrgEstimateUsd')
                        ~ '^[0-9]+\.[0-9]{6}$', TRUE)
                AND COALESCE(
                    (approval_evidence_json::jsonb ->> 'governanceProjectCapUsd')
                        ~ '^[0-9]+\.[0-9]{6}$', TRUE)
                AND COALESCE(
                    (approval_evidence_json::jsonb ->> 'governanceProjectMonthToDateUsd')
                        ~ '^[0-9]+\.[0-9]{6}$', TRUE)
                AND COALESCE(
                    (approval_evidence_json::jsonb ->> 'governanceProjectProjectedUsd')
                        ~ '^[0-9]+\.[0-9]{6}$', TRUE)
            )
        ) NOT VALID;

-- PLAN cancellation is a separate user decision, not an alias for rejection. Rebuild the V347
-- rolling-safe checks to admit CANCELLED and to keep the new receipt fields off pending gates.
ALTER TABLE gate_record
    DROP CONSTRAINT ck_plan_gate_resolution_consistency,
    DROP CONSTRAINT ck_pending_plan_gate_has_no_admission_receipt;

ALTER TABLE gate_record
    ADD CONSTRAINT ck_plan_gate_resolution_consistency
        CHECK (
            gate_type <> 'PLAN'
            OR (status = 'PENDING' AND resolved_at IS NULL)
            OR (status IN ('APPROVED', 'REJECTED', 'CANCELLED') AND resolved_at IS NOT NULL)
        ) NOT VALID,
    ADD CONSTRAINT ck_pending_plan_gate_has_no_admission_receipt
        CHECK (
            gate_type <> 'PLAN'
            OR status <> 'PENDING'
            OR (
                admission_contract_version IS NULL
                AND approval_evidence_json IS NULL
                AND approval_evidence_digest IS NULL
                AND decision_kind IS NULL
                AND resolved_actor_type IS NULL
                AND decision_request_fingerprint IS NULL
                AND resolved_authorization_source IS NULL
                AND resolved_authorization_org_id IS NULL
                AND resolved_by_user_id IS NULL
                AND resolved_by_role IS NULL
                AND resolution_source IS NULL
                AND resolution_reason IS NULL
                AND billing_owner_user_id IS NULL
                AND billing_org_id IS NULL
                AND admitted_at IS NULL
            )
        ) NOT VALID;

-- report_json is exact evidence. A SHA-shaped value alone is not proof that report_digest names
-- those bytes. Existing rows are validated during migration; a mismatch aborts deployment rather
-- than silently blessing evidence that the server cannot reproduce.
ALTER TABLE gate_record
    DROP CONSTRAINT fk_gate_contract_report_exact;

ALTER TABLE gate_record
    ADD CONSTRAINT fk_gate_contract_report_exact
    FOREIGN KEY (run_id, contract_gate_report_digest)
    REFERENCES plan_contract_gate_report (run_id, report_digest)
    ON DELETE NO ACTION
    DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE plan_contract_gate_report
    ADD CONSTRAINT fk_plan_contract_gate_report_run
    FOREIGN KEY (run_id) REFERENCES pipeline_run (run_id)
    ON DELETE CASCADE
    NOT VALID;

ALTER TABLE plan_contract_gate_report
    ADD CONSTRAINT ck_plan_contract_gate_report_digest_exact
        CHECK (encode(sha256(convert_to(report_json, 'UTF8')), 'hex') = report_digest);

CREATE OR REPLACE FUNCTION enforce_plan_contract_gate_report_integrity()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    -- Once acknowledgement exists, reject every attempted rewrite as an immutability violation
    -- before validating the proposed replacement values. This keeps the durable fact authoritative:
    -- callers cannot turn a rewrite into a different validation path by choosing malformed bytes.
    IF TG_OP = 'UPDATE' AND OLD.acknowledged_at IS NOT NULL AND (
        NEW.report_json IS DISTINCT FROM OLD.report_json
        OR NEW.report_digest IS DISTINCT FROM OLD.report_digest
        OR NEW.requires_typed_ack IS DISTINCT FROM OLD.requires_typed_ack
        OR NEW.contract_count IS DISTINCT FROM OLD.contract_count
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NEW.acknowledged_at IS DISTINCT FROM OLD.acknowledged_at
        OR NEW.acknowledged_by_user_id IS DISTINCT FROM OLD.acknowledged_by_user_id
        OR NEW.acknowledgement_text IS DISTINCT FROM OLD.acknowledgement_text
    ) THEN
        RAISE EXCEPTION 'plan contract gate acknowledgement is immutable';
    END IF;

    IF encode(sha256(convert_to(NEW.report_json, 'UTF8')), 'hex') <> NEW.report_digest THEN
        RAISE EXCEPTION 'plan contract gate report digest does not match exact bytes';
    END IF;

    IF NEW.acknowledged_at IS NOT NULL
       AND NEW.requires_typed_ack
       AND upper(btrim(NEW.acknowledgement_text)) IS DISTINCT FROM 'BREAKING' THEN
        RAISE EXCEPTION 'plan contract gate acknowledgement text is invalid';
    END IF;

    -- Once a gate binds this digest, only the all-or-nothing acknowledgement triple may be
    -- appended. In particular, requires_typed_ack cannot be downgraded after the user sees it.
    IF TG_OP = 'UPDATE'
       AND EXISTS (
           SELECT 1 FROM gate_record g
            WHERE g.run_id = OLD.run_id
              AND g.contract_gate_report_digest = OLD.report_digest
       ) AND (
        NEW.project_id IS DISTINCT FROM OLD.project_id
        OR NEW.report_json IS DISTINCT FROM OLD.report_json
        OR NEW.report_digest IS DISTINCT FROM OLD.report_digest
        OR NEW.requires_typed_ack IS DISTINCT FROM OLD.requires_typed_ack
        OR NEW.contract_count IS DISTINCT FROM OLD.contract_count
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
    ) THEN
        RAISE EXCEPTION 'referenced plan contract gate report is immutable';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_plan_contract_gate_report_integrity
BEFORE INSERT OR UPDATE ON plan_contract_gate_report
FOR EACH ROW EXECUTE FUNCTION enforce_plan_contract_gate_report_integrity();

-- Versioned decision evidence may be removed only by deleting its parent run through the existing
-- retention/GDPR cascade. Direct child deletion would erase an immutable receipt or its only
-- restart-safe continuation while leaving the run behind.
CREATE OR REPLACE FUNCTION prevent_versioned_plan_gate_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM pipeline_run WHERE run_id = OLD.run_id)
       AND OLD.gate_type = 'PLAN'
       AND OLD.admission_contract_version IS NOT NULL THEN
        RAISE EXCEPTION 'versioned plan decision gate cannot be deleted directly';
    END IF;
    RETURN OLD;
END;
$$;

CREATE OR REPLACE FUNCTION prevent_versioned_plan_outbox_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM pipeline_run WHERE run_id = OLD.run_id)
       AND EXISTS (
           SELECT 1 FROM gate_record g
            WHERE g.run_id = OLD.run_id
              AND g.gate_type = 'PLAN'
              AND g.admission_contract_version IS NOT NULL
       ) THEN
        RAISE EXCEPTION 'versioned plan continuation cannot be deleted directly';
    END IF;
    RETURN OLD;
END;
$$;

CREATE OR REPLACE FUNCTION prevent_versioned_plan_report_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM pipeline_run WHERE run_id = OLD.run_id)
       AND (OLD.acknowledged_at IS NOT NULL OR EXISTS (
           SELECT 1 FROM gate_record g
            WHERE g.run_id = OLD.run_id
              AND g.contract_gate_report_digest = OLD.report_digest
       )) THEN
        RAISE EXCEPTION 'referenced plan contract gate report cannot be deleted directly';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_versioned_plan_gate_no_direct_delete
BEFORE DELETE ON gate_record
FOR EACH ROW EXECUTE FUNCTION prevent_versioned_plan_gate_delete();

CREATE TRIGGER trg_versioned_plan_outbox_no_direct_delete
BEFORE DELETE ON plan_gate_resume_outbox
FOR EACH ROW EXECUTE FUNCTION prevent_versioned_plan_outbox_delete();

CREATE TRIGGER trg_versioned_plan_report_no_direct_delete
BEFORE DELETE ON plan_contract_gate_report
FOR EACH ROW EXECUTE FUNCTION prevent_versioned_plan_report_delete();

-- Extend V347's gate trigger with the new receipt fields and with the stronger ENFORCED invariant:
-- the exact RequirementIR row must be READY and have zero blockers before it can be described as
-- an enforced binding, including while the gate is still pending.
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

    -- A resolved decision is an audit fact. Replays may read it but cannot rewrite its meaning.
    IF TG_OP = 'UPDATE' AND (OLD.resolved_at IS NOT NULL
            OR OLD.status IN ('APPROVED', 'REJECTED', 'CANCELLED')) AND (
        NEW.status IS DISTINCT FROM OLD.status
        OR NEW.resolved_at IS DISTINCT FROM OLD.resolved_at
    ) THEN
        RAISE EXCEPTION 'resolved gate decision is immutable';
    END IF;

    -- Gate-open evidence is frozen independently from the later admission receipt.
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

    -- Once admission is written, its decision/policy/actor/payer evidence is immutable.
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

        IF NOT FOUND
           OR requirement_completeness IS DISTINCT FROM 'READY'
           OR requirement_blockers IS DISTINCT FROM 0 THEN
            RAISE EXCEPTION 'ENFORCED plan gate requires an exact READY zero-blocker RequirementIR';
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

-- This deferred constraint trigger is the commit boundary. Intermediate writes may temporarily be
-- inconsistent inside the decision/handoff transaction, but no versioned tuple can commit in an
-- impossible state. Historical pre-V348 decisions remain readable because they have no version.
CREATE OR REPLACE FUNCTION enforce_plan_gate_decision_coherence()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    target_run_id VARCHAR(255);
    gate_status VARCHAR(30);
    gate_decision VARCHAR(16);
    gate_contract_version INTEGER;
    run_status VARCHAR(64);
    outbox_status VARCHAR(16);
    report_requires_ack BOOLEAN;
    report_acknowledged_at TIMESTAMPTZ;
    report_acknowledged_by VARCHAR(64);
    report_acknowledgement_text VARCHAR(64);
    gate_report_digest VARCHAR(64);
    gate_evidence JSONB;
    gate_continuation_version INTEGER;
    gate_continuation_sha VARCHAR(64);
    gate_billing_owner VARCHAR(64);
    gate_billing_org VARCHAR(64);
    gate_authorization_source VARCHAR(64);
    gate_authorization_org VARCHAR(64);
    gate_actor_user_id VARCHAR(64);
    gate_requirement_id UUID;
    gate_requirement_schema INTEGER;
    gate_requirement_compiler VARCHAR(64);
    gate_requirement_source VARCHAR(64);
    gate_requirement_plan VARCHAR(64);
    gate_requirement_artifact VARCHAR(64);
    gate_requirement_bound VARCHAR(64);
    run_plan_json TEXT;
    run_org_id VARCHAR(64);
    run_owner_user_id VARCHAR(64);
    run_execution_mode VARCHAR(20);
    run_provider VARCHAR(20);
    run_credits INTEGER;
    run_cost_estimate_high DOUBLE PRECISION;
    run_hard_cap_usd DOUBLE PRECISION;
    outbox_handler_version INTEGER;
    outbox_payload_sha VARCHAR(64);
    requirement_completeness_value VARCHAR(16);
    requirement_count_value INTEGER;
    acceptance_count_value INTEGER;
    task_binding_count_value INTEGER;
    issue_count_value INTEGER;
    blocker_count_value INTEGER;
BEGIN
    target_run_id := COALESCE(NEW.run_id, OLD.run_id);

    SELECT status, decision_kind, admission_contract_version, contract_gate_report_digest,
           approval_evidence_json::jsonb, continuation_schema_version, continuation_sha256,
           billing_owner_user_id, billing_org_id, resolved_authorization_source,
           resolved_authorization_org_id, resolved_by_user_id, requirement_contract_id,
           binding_schema_version,
           binding_compiler_fingerprint, binding_source_sha256, binding_exact_plan_fingerprint,
           binding_artifact_digest, binding_bound_digest
      INTO gate_status, gate_decision, gate_contract_version, gate_report_digest,
           gate_evidence, gate_continuation_version, gate_continuation_sha,
           gate_billing_owner, gate_billing_org, gate_authorization_source,
           gate_authorization_org, gate_actor_user_id, gate_requirement_id, gate_requirement_schema,
           gate_requirement_compiler, gate_requirement_source, gate_requirement_plan,
           gate_requirement_artifact, gate_requirement_bound
      FROM gate_record
     WHERE run_id = target_run_id AND gate_type = 'PLAN';

    IF NOT FOUND OR gate_contract_version IS NULL THEN
        RETURN NULL;
    END IF;

    SELECT status, plan_json, org_id, created_by_user_id, approved_execution_mode, ai_provider,
           credits_charged, cost_estimate_high, hard_cap_usd
      INTO run_status, run_plan_json, run_org_id, run_owner_user_id, run_execution_mode,
           run_provider, run_credits, run_cost_estimate_high, run_hard_cap_usd
      FROM pipeline_run WHERE run_id = target_run_id;
    IF NOT FOUND THEN
        RETURN NULL; -- parent deletion/cascade
    END IF;

    SELECT status, handler_contract_version, payload_sha256
      INTO outbox_status, outbox_handler_version, outbox_payload_sha
      FROM plan_gate_resume_outbox WHERE run_id = target_run_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'versioned plan decision requires a continuation outbox row';
    END IF;

    IF gate_contract_version IS DISTINCT FROM 1
       OR (gate_evidence ->> 'schemaVersion')
            IS DISTINCT FROM gate_contract_version::text THEN
        RAISE EXCEPTION 'plan decision receipt schema is unsupported';
    END IF;

    IF (gate_evidence ->> 'continuationSchemaVersion')
           IS DISTINCT FROM gate_continuation_version::text
       OR gate_continuation_version IS DISTINCT FROM outbox_handler_version
       OR (gate_evidence ->> 'continuationSha256') IS DISTINCT FROM gate_continuation_sha
       OR gate_continuation_sha IS DISTINCT FROM outbox_payload_sha THEN
        RAISE EXCEPTION 'plan decision evidence continuation identity is inconsistent';
    END IF;

    IF run_plan_json IS NULL
       OR (gate_evidence ->> 'reviewedPlanFingerprint') IS DISTINCT FROM
          encode(sha256(convert_to(run_plan_json, 'UTF8')), 'hex') THEN
        RAISE EXCEPTION 'plan decision evidence reviewed-plan fingerprint is inconsistent';
    END IF;

    IF (gate_evidence ->> 'executionMode')
           IS DISTINCT FROM COALESCE(run_execution_mode, 'REALTIME') THEN
        RAISE EXCEPTION 'plan decision evidence execution mode is inconsistent';
    END IF;

    IF (gate_evidence ->> 'provider') IS DISTINCT FROM
          (CASE WHEN lower(COALESCE(run_provider, 'openai')) = 'google' THEN 'gemini'
                ELSE lower(COALESCE(run_provider, 'openai')) END) THEN
        RAISE EXCEPTION 'plan decision evidence provider is inconsistent';
    END IF;

    IF (gate_evidence ->> 'estimateHighUsd') IS DISTINCT FROM
          to_char(
              round(COALESCE(run_cost_estimate_high, run_hard_cap_usd, 0)::numeric, 6),
              'FM999999999999999990.000000')
       OR (gate_evidence ->> 'hardCapUsd') IS DISTINCT FROM
          (CASE WHEN run_hard_cap_usd IS NULL THEN NULL
                ELSE to_char(
                    round(run_hard_cap_usd::numeric, 6),
                    'FM999999999999999990.000000') END)
       OR (gate_evidence ->> 'expectedCredits')
            IS DISTINCT FROM COALESCE(run_credits, 1)::text
       OR (
            gate_evidence ->> 'creditsConfirmed' IS NOT NULL
            AND (
                (gate_evidence ->> 'creditsConfirmed')
                    IS DISTINCT FROM COALESCE(run_credits, 1)::text
                OR (gate_evidence ->> 'creditsMatched')::boolean IS DISTINCT FROM TRUE
            )
       )
       OR (
            gate_evidence ->> 'creditsConfirmed' IS NULL
            AND gate_evidence ->> 'creditsMatched' IS NOT NULL
       ) THEN
        RAISE EXCEPTION 'plan decision evidence cost or credit identity is inconsistent';
    END IF;

    IF (gate_evidence ->> 'billingOwnerUserId') IS DISTINCT FROM gate_billing_owner
       OR gate_billing_owner IS DISTINCT FROM run_owner_user_id
       OR (gate_evidence ->> 'billingOrgId') IS DISTINCT FROM gate_billing_org
       OR gate_billing_org IS DISTINCT FROM run_org_id THEN
        RAISE EXCEPTION 'plan decision evidence billing identity is inconsistent';
    END IF;

    IF (gate_evidence ->> 'authorizationBasis') IS DISTINCT FROM gate_authorization_source
       OR (gate_evidence ->> 'authorizationOrgId') IS DISTINCT FROM gate_authorization_org
       OR (gate_authorization_source IN ('ORG_ADMIN', 'SYSTEM_INTERNAL')
           AND gate_authorization_org IS DISTINCT FROM run_org_id)
       OR (gate_authorization_source IN ('RUN_OWNER', 'GLOBAL_SUPER_ADMIN')
           AND gate_authorization_org IS NOT NULL)
       OR (gate_authorization_source = 'RUN_OWNER'
           AND gate_actor_user_id IS DISTINCT FROM run_owner_user_id) THEN
        RAISE EXCEPTION 'plan decision actor authorization identity is inconsistent';
    END IF;

    IF (gate_evidence ->> 'contractGateReportDigest') IS DISTINCT FROM gate_report_digest THEN
        RAISE EXCEPTION 'plan decision evidence contract-report identity is inconsistent';
    END IF;

    IF gate_report_digest IS NULL THEN
        IF (gate_evidence ->> 'contractAcknowledgementOutcome')
                IS DISTINCT FROM
                (CASE WHEN gate_decision = 'APPROVE' THEN 'NOT_REQUIRED'
                      ELSE 'NOT_EVALUATED' END)
           OR gate_evidence ->> 'contractRequiresTypedAck' IS NOT NULL
           OR gate_evidence ->> 'contractAcknowledgedAt' IS NOT NULL
           OR gate_evidence ->> 'contractAcknowledgedByUserId' IS NOT NULL
           OR gate_evidence ->> 'contractAcknowledgementText' IS NOT NULL THEN
            RAISE EXCEPTION 'plan decision evidence claims a missing contract report';
        END IF;
    ELSE
        SELECT requires_typed_ack, acknowledged_at, acknowledged_by_user_id,
               acknowledgement_text
          INTO report_requires_ack, report_acknowledged_at, report_acknowledged_by,
               report_acknowledgement_text
          FROM plan_contract_gate_report
         WHERE run_id = target_run_id AND report_digest = gate_report_digest;
        IF NOT FOUND
           OR (gate_evidence ->> 'contractRequiresTypedAck')::boolean
                IS DISTINCT FROM report_requires_ack
           OR (gate_evidence ->> 'contractAcknowledgedAt')::timestamptz
                IS DISTINCT FROM report_acknowledged_at
           OR (gate_evidence ->> 'contractAcknowledgedByUserId')
                IS DISTINCT FROM report_acknowledged_by
           OR (gate_evidence ->> 'contractAcknowledgementText')
                IS DISTINCT FROM report_acknowledgement_text THEN
            RAISE EXCEPTION 'plan decision contract acknowledgement evidence is inconsistent';
        END IF;
        IF gate_decision <> 'APPROVE' THEN
            IF (gate_evidence ->> 'contractAcknowledgementOutcome')
                    IS DISTINCT FROM 'NOT_EVALUATED' THEN
                RAISE EXCEPTION 'terminal non-approval contract evidence is inconsistent';
            END IF;
        ELSIF report_requires_ack THEN
            IF (gate_evidence ->> 'contractAcknowledgementOutcome')
                    IS DISTINCT FROM 'ACKNOWLEDGED'
               OR report_acknowledged_at IS NULL
               OR report_acknowledged_by IS NULL
               OR report_acknowledged_by IS DISTINCT FROM gate_actor_user_id
               OR upper(btrim(report_acknowledgement_text)) IS DISTINCT FROM 'BREAKING' THEN
                RAISE EXCEPTION 'plan decision typed acknowledgement evidence is inconsistent';
            END IF;
        ELSIF (gate_evidence ->> 'contractAcknowledgementOutcome')
                    IS DISTINCT FROM 'NOT_REQUIRED'
              OR report_acknowledged_at IS NOT NULL
              OR report_acknowledged_by IS NOT NULL
              OR report_acknowledgement_text IS NOT NULL THEN
            RAISE EXCEPTION 'plan decision non-breaking contract evidence is inconsistent';
        END IF;
    END IF;

    IF gate_requirement_id IS NULL THEN
        IF gate_evidence ->> 'requirementCompleteness' IS NOT NULL
           OR gate_evidence ->> 'requirementCount' IS NOT NULL
           OR gate_evidence ->> 'acceptanceCriterionCount' IS NOT NULL
           OR gate_evidence ->> 'taskBindingCount' IS NOT NULL
           OR gate_evidence ->> 'issueCount' IS NOT NULL
           OR gate_evidence ->> 'blockingIssueCount' IS NOT NULL THEN
            RAISE EXCEPTION 'unbound plan decision cannot claim RequirementIR result evidence';
        END IF;
    ELSE
        SELECT completeness, requirement_count, acceptance_criterion_count, task_binding_count,
               issue_count, blocking_issue_count
          INTO requirement_completeness_value, requirement_count_value, acceptance_count_value,
               task_binding_count_value, issue_count_value, blocker_count_value
          FROM run_requirement_contract
         WHERE id = gate_requirement_id
           AND run_id = target_run_id
           AND schema_version = gate_requirement_schema
           AND compiler_fingerprint = gate_requirement_compiler
           AND source_sha256 = gate_requirement_source
           AND exact_plan_fingerprint = gate_requirement_plan
           AND artifact_digest = gate_requirement_artifact
           AND bound_digest = gate_requirement_bound;
        IF NOT FOUND
           OR (gate_evidence ->> 'requirementCompleteness')
                IS DISTINCT FROM requirement_completeness_value
           OR (gate_evidence ->> 'requirementCount')
                IS DISTINCT FROM requirement_count_value::text
           OR (gate_evidence ->> 'acceptanceCriterionCount')
                IS DISTINCT FROM acceptance_count_value::text
           OR (gate_evidence ->> 'taskBindingCount')
                IS DISTINCT FROM task_binding_count_value::text
           OR (gate_evidence ->> 'issueCount') IS DISTINCT FROM issue_count_value::text
           OR (gate_evidence ->> 'blockingIssueCount') IS DISTINCT FROM blocker_count_value::text THEN
            RAISE EXCEPTION 'plan decision RequirementIR result evidence is inconsistent';
        END IF;
    END IF;

    IF gate_decision = 'APPROVE' THEN
        IF gate_status IS DISTINCT FROM 'APPROVED' THEN
            RAISE EXCEPTION 'APPROVE decision requires APPROVED gate';
        END IF;

        IF outbox_status IN ('READY', 'PROCESSING')
           AND run_status IS DISTINCT FROM 'APPROVAL_RESUMING' THEN
            RAISE EXCEPTION '% continuation requires APPROVAL_RESUMING run', outbox_status;
        ELSIF outbox_status = 'COMPLETED'
           AND run_status IN ('QUEUED', 'PLANNING', 'AWAITING_APPROVAL', 'APPROVAL_RESUMING') THEN
            RAISE EXCEPTION 'COMPLETED continuation requires durable worker handoff';
        ELSIF outbox_status = 'CANCELLED' AND run_status IS DISTINCT FROM 'CANCELLED' THEN
            RAISE EXCEPTION 'cancelled approved continuation requires CANCELLED run';
        ELSIF outbox_status = 'DEAD' AND run_status NOT IN ('FAILED', 'CANCELLED') THEN
            RAISE EXCEPTION 'dead approved continuation requires FAILED or CANCELLED run';
        ELSIF outbox_status = 'WAITING' THEN
            RAISE EXCEPTION 'approved plan decision cannot retain WAITING continuation';
        END IF;

        IF EXISTS (SELECT 1 FROM plan_contract_gate_report WHERE run_id = target_run_id)
           AND gate_report_digest IS NULL THEN
            RAISE EXCEPTION 'approved plan decision must bind the persisted contract gate report';
        END IF;

        IF gate_report_digest IS NOT NULL THEN
            IF report_requires_ack
               AND (report_acknowledged_at IS NULL OR report_acknowledged_by IS NULL) THEN
                RAISE EXCEPTION 'approved plan decision requires immutable contract acknowledgement';
            END IF;
        END IF;
    ELSIF gate_decision IN ('REJECT', 'CANCEL') THEN
        IF (gate_decision = 'REJECT' AND gate_status IS DISTINCT FROM 'REJECTED')
           OR (gate_decision = 'CANCEL' AND gate_status IS DISTINCT FROM 'CANCELLED') THEN
            RAISE EXCEPTION '% decision has inconsistent gate status', gate_decision;
        END IF;
        IF run_status IS DISTINCT FROM 'CANCELLED'
           OR outbox_status IS DISTINCT FROM 'CANCELLED' THEN
            RAISE EXCEPTION '% decision requires CANCELLED run and outbox', gate_decision;
        END IF;
    ELSE
        RAISE EXCEPTION 'versioned plan decision has unsupported decision_kind';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_gate_record_decision_coherence
AFTER INSERT OR UPDATE OR DELETE ON gate_record
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION enforce_plan_gate_decision_coherence();

CREATE CONSTRAINT TRIGGER trg_plan_gate_outbox_decision_coherence
AFTER INSERT OR UPDATE OR DELETE ON plan_gate_resume_outbox
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION enforce_plan_gate_decision_coherence();

CREATE CONSTRAINT TRIGGER trg_pipeline_run_plan_decision_coherence
AFTER INSERT OR UPDATE OF status, plan_json, org_id, created_by_user_id, approved_execution_mode,
    ai_provider, credits_charged, cost_estimate_high, hard_cap_usd OR DELETE
ON pipeline_run
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION enforce_plan_gate_decision_coherence();

CREATE CONSTRAINT TRIGGER trg_plan_contract_report_decision_coherence
AFTER INSERT OR UPDATE OR DELETE ON plan_contract_gate_report
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION enforce_plan_gate_decision_coherence();

COMMENT ON COLUMN gate_record.decision_kind IS
    'Versioned immutable PLAN decision: APPROVE, REJECT, or pre-approval CANCEL.';
COMMENT ON COLUMN gate_record.resolved_actor_type IS
    'Actor provenance for the decision receipt: HUMAN or trusted SYSTEM authority.';
COMMENT ON COLUMN gate_record.decision_request_fingerprint IS
    'SHA-256 of the canonical decision request bytes used for exact replay/conflict detection.';
COMMENT ON COLUMN gate_record.resolution_source IS
    'Typed decision surface for versioned PLAN decisions; constrained by V348.';
COMMENT ON COLUMN gate_record.resolved_authorization_source IS
    'Authority actually exercised: RUN_OWNER, ORG_ADMIN, GLOBAL_SUPER_ADMIN, or SYSTEM_INTERNAL.';
COMMENT ON COLUMN gate_record.resolved_authorization_org_id IS
    'Tenant scope of ORG_ADMIN/SYSTEM_INTERNAL authority; null for owner/global authority.';
