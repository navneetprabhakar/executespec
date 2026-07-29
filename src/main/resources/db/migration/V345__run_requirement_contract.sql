-- Persist the versioned RequirementIR beside, never inside, the legacy executable plan.
-- The exact canonical JSON bytes and all binding hashes cannot be updated once inserted. Deletion
-- remains available for explicit lifecycle cleanup and pipeline_run retention/GDPR cascade.

CREATE TABLE run_requirement_contract (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id                      VARCHAR(255) NOT NULL,
    schema_version              INTEGER NOT NULL,
    compiler_fingerprint        VARCHAR(64) NOT NULL,
    source_sha256               VARCHAR(64) NOT NULL,
    exact_plan_fingerprint      VARCHAR(64) NOT NULL,
    canonical_artifact_json     TEXT NOT NULL,
    artifact_digest             VARCHAR(64) NOT NULL,
    bound_digest                VARCHAR(64) NOT NULL,
    completeness                VARCHAR(16) NOT NULL,
    requirement_count           INTEGER NOT NULL,
    acceptance_criterion_count  INTEGER NOT NULL,
    task_binding_count          INTEGER NOT NULL,
    issue_count                 INTEGER NOT NULL,
    blocking_issue_count        INTEGER NOT NULL,
    compiled_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_run_requirement_contract_run
        FOREIGN KEY (run_id) REFERENCES pipeline_run(run_id) ON DELETE CASCADE,
    CONSTRAINT uq_run_requirement_contract_identity
        UNIQUE (run_id, exact_plan_fingerprint, schema_version, compiler_fingerprint),
    CONSTRAINT ck_run_requirement_contract_schema_version CHECK (schema_version > 0),
    CONSTRAINT ck_run_requirement_contract_compiler_sha
        CHECK (compiler_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_run_requirement_contract_source_sha
        CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_run_requirement_contract_plan_sha
        CHECK (exact_plan_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_run_requirement_contract_artifact_sha
        CHECK (artifact_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_run_requirement_contract_bound_sha
        CHECK (bound_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_run_requirement_contract_json_object
        CHECK (jsonb_typeof(canonical_artifact_json::jsonb) = 'object'),
    CONSTRAINT ck_run_requirement_contract_completeness
        CHECK (completeness IN ('READY', 'BLOCKED')),
    CONSTRAINT ck_run_requirement_contract_counts
        CHECK (
            requirement_count >= 0
            AND acceptance_criterion_count >= 0
            AND task_binding_count >= 0
            AND issue_count >= 0
            AND blocking_issue_count >= 0
            AND blocking_issue_count <= issue_count
        )
);

CREATE INDEX idx_run_requirement_contract_run_compiled
    ON run_requirement_contract (run_id, compiled_at DESC);

CREATE OR REPLACE FUNCTION reject_run_requirement_contract_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'run_requirement_contract is immutable';
END;
$$;

CREATE TRIGGER trg_run_requirement_contract_immutable
BEFORE UPDATE ON run_requirement_contract
FOR EACH ROW EXECUTE FUNCTION reject_run_requirement_contract_update();

COMMENT ON TABLE run_requirement_contract IS
    'Update-immutable, versioned RequirementIR artifact bound to the exact legacy plan bytes and source; deletion remains available for explicit lifecycle cleanup and parent-run retention.';
COMMENT ON COLUMN run_requirement_contract.canonical_artifact_json IS
    'Exact canonical JSON text whose UTF-8 SHA-256 is artifact_digest; stored as TEXT so PostgreSQL cannot reorder bytes.';
COMMENT ON COLUMN run_requirement_contract.bound_digest IS
    'Versioned domain-separated digest binding exact plan fingerprint, source SHA, and artifact digest.';

-- Shadow compilation is deliberately dark until restart/replay evidence and the later approval-gate
-- binding slice are complete. The legacy plan remains the only approval/execution authority.
INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description) VALUES
    ('requirement.ir.shadow.enabled', 'false', 'BOOLEAN', 'PLATFORM',
     'Persist immutable RequirementIR shadow artifacts beside legacy plans. Keep OFF until the shadow checkpoint is explicitly activated; this flag does not enable approval-gate enforcement.')
ON CONFLICT (flag_key) DO NOTHING;
