-- V369 makes every server-side correction a recoverable workspace transaction. The row is
-- written before validation evidence is invalidated or a correction worker may mutate files.
CREATE TABLE correction_attempt (
    id UUID PRIMARY KEY,
    run_id VARCHAR(255) NOT NULL REFERENCES pipeline_run(run_id) ON DELETE CASCADE,
    task_id VARCHAR(255) NOT NULL,
    phase VARCHAR(32) NOT NULL,
    workspace_root TEXT NOT NULL,
    before_generation CHAR(64) NOT NULL,
    after_generation CHAR(64),
    repositories_json JSONB NOT NULL,
    evidence_report_id VARCHAR(255),
    evidence_status VARCHAR(20),
    evidence_workspace_generation CHAR(64),
    evidence_snapshot_json JSONB,
    task_snapshot_json JSONB NOT NULL,
    snapshot_schema_version INTEGER NOT NULL DEFAULT 1,
    evidence_snapshot_digest CHAR(64) NOT NULL,
    approval_id VARCHAR(36),
    proposal_digest CHAR(64),
    lease_token UUID NOT NULL,
    failure_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ,
    CONSTRAINT correction_attempt_phase_chk CHECK (
        phase IN ('PREPARED', 'MUTATING', 'ACCEPTED', 'ROLLED_BACK', 'RECOVERY_FAILED')
    ),
    CONSTRAINT correction_attempt_generation_chk CHECK (
        before_generation ~ '^[0-9a-f]{64}$'
        AND (after_generation IS NULL OR after_generation ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT correction_attempt_snapshot_chk CHECK (
        snapshot_schema_version = 1
        AND evidence_snapshot_digest ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT correction_attempt_approval_binding_chk CHECK (
        (approval_id IS NULL AND proposal_digest IS NULL)
        OR (approval_id IS NOT NULL AND proposal_digest ~ '^[0-9a-f]{64}$')
    )
);

CREATE TABLE run_workspace_authority (
    run_id VARCHAR(255) PRIMARY KEY REFERENCES pipeline_run(run_id) ON DELETE CASCADE,
    authority_type VARCHAR(24) NOT NULL,
    owner_token UUID NOT NULL,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    heartbeat_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT run_workspace_authority_type_chk
        CHECK (authority_type IN ('CORRECTION', 'RECOVERY', 'PUBLICATION'))
);

-- At most one unresolved correction may own a run workspace. This is the cross-JVM admission
-- fence; a later attempt must recover or explicitly fail the existing transaction first.
CREATE UNIQUE INDEX uq_correction_attempt_active_run
    ON correction_attempt(run_id)
    WHERE phase IN ('PREPARED', 'MUTATING', 'RECOVERY_FAILED');

CREATE INDEX idx_correction_attempt_recovery
    ON correction_attempt(phase, updated_at)
    WHERE phase IN ('PREPARED', 'MUTATING', 'RECOVERY_FAILED');
