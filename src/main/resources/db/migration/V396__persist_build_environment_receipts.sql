-- Keep ignored dependency/cache bytes outside product generation while proving how each task
-- reconstructed the environment that consumed accepted manifests.
CREATE TABLE workspace_generation_environment_receipt (
    id UUID PRIMARY KEY,
    attempt_id UUID NOT NULL REFERENCES workspace_generation_attempt(id) ON DELETE CASCADE,
    phase VARCHAR(48) NOT NULL,
    receipt_kind VARCHAR(24) NOT NULL CHECK (receipt_kind IN ('MATERIALIZATION', 'CLEANUP')),
    plan_digest VARCHAR(64),
    runtime_image TEXT,
    network_policy VARCHAR(32),
    manifest_digest VARCHAR(64),
    disposition VARCHAR(32) NOT NULL CHECK (disposition IN (
        'PASSED', 'COMMAND_FAILED', 'UNAVAILABLE', 'POLICY_DENIED', 'TIMED_OUT',
        'PRODUCT_MUTATION_REFUSED', 'INTERNAL_PROTOCOL'
    )),
    removed_roots_json JSONB,
    removed_roots_digest VARCHAR(64),
    evidence_json JSONB NOT NULL,
    evidence_digest VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_workspace_generation_environment_phase UNIQUE (attempt_id, phase),
    CONSTRAINT workspace_generation_environment_digest_chk CHECK (
        evidence_digest ~ '^[0-9a-f]{64}$'
        AND (plan_digest IS NULL OR plan_digest ~ '^[0-9a-f]{64}$')
        AND (manifest_digest IS NULL OR manifest_digest ~ '^[0-9a-f]{64}$')
        AND (removed_roots_digest IS NULL OR removed_roots_digest ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT workspace_generation_environment_shape_chk CHECK (
        (receipt_kind = 'MATERIALIZATION'
         AND plan_digest IS NOT NULL AND runtime_image IS NOT NULL
         AND network_policy = 'DENY_ALL' AND manifest_digest IS NOT NULL
         AND removed_roots_json IS NULL AND removed_roots_digest IS NULL)
        OR
        (receipt_kind = 'CLEANUP'
         AND disposition = 'PASSED' AND removed_roots_json IS NOT NULL
         AND removed_roots_digest IS NOT NULL AND plan_digest IS NULL
         AND runtime_image IS NULL AND network_policy IS NULL AND manifest_digest IS NULL)
    )
);

CREATE INDEX idx_workspace_generation_environment_attempt
    ON workspace_generation_environment_receipt(attempt_id, created_at, id);

CREATE OR REPLACE FUNCTION reject_workspace_generation_environment_receipt_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'workspace generation environment receipts are append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_workspace_generation_environment_receipt_no_update
BEFORE UPDATE OR DELETE ON workspace_generation_environment_receipt
FOR EACH ROW EXECUTE FUNCTION reject_workspace_generation_environment_receipt_mutation();

CREATE OR REPLACE FUNCTION enforce_workspace_generation_environment_receipts()
RETURNS TRIGGER AS $$
DECLARE
    passed_count INTEGER;
BEGIN
    IF NEW.build_protocol_version = 'SHARED_KERNEL_V2'
       AND NEW.state = 'VERIFYING'
       AND OLD.state IS DISTINCT FROM NEW.state THEN
        SELECT COUNT(*)
          INTO passed_count
          FROM workspace_generation_environment_receipt r
         WHERE r.attempt_id = NEW.id
           AND r.receipt_kind = 'MATERIALIZATION'
           AND r.disposition = 'PASSED'
           AND r.phase IN ('INITIAL_PROBE', 'WORKER');
        IF passed_count <> 2 THEN
            RAISE EXCEPTION 'shared BUILD verification requires passed probe and worker environment receipts';
        END IF;
    END IF;
    IF NEW.build_protocol_version = 'SHARED_KERNEL_V2'
       AND NEW.state = 'VERIFIED'
       AND OLD.state IS DISTINCT FROM NEW.state THEN
        SELECT COUNT(*)
          INTO passed_count
          FROM workspace_generation_environment_receipt r
         WHERE r.attempt_id = NEW.id
           AND r.receipt_kind = 'MATERIALIZATION'
           AND r.disposition = 'PASSED'
           AND r.phase = 'FINAL_VERIFICATION';
        IF passed_count <> 1 THEN
            RAISE EXCEPTION 'shared BUILD acceptance requires a passed final environment receipt';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_workspace_generation_environment_receipt_gate
BEFORE UPDATE OF state ON workspace_generation_attempt
FOR EACH ROW EXECUTE FUNCTION enforce_workspace_generation_environment_receipts();

COMMENT ON TABLE workspace_generation_environment_receipt IS
    'Append-only deterministic environment materialization and ignored-root cleanup evidence; dependency bytes are never product generation identity.';
