CREATE TABLE workspace_generation_recovery_cleanup (
    id UUID PRIMARY KEY,
    run_id VARCHAR(128) NOT NULL REFERENCES pipeline_run(run_id) ON DELETE CASCADE,
    artifact_path TEXT NOT NULL,
    artifact_path_digest CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    claimed_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_workspace_generation_recovery_cleanup_path UNIQUE (run_id, artifact_path_digest),
    CONSTRAINT ck_workspace_generation_recovery_cleanup_digest
        CHECK (artifact_path_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_workspace_generation_recovery_cleanup_status
        CHECK (status IN ('PENDING','PROCESSING','COMPLETED')),
    CONSTRAINT ck_workspace_generation_recovery_cleanup_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_workspace_generation_recovery_cleanup_completion CHECK (
        (status='COMPLETED' AND completed_at IS NOT NULL)
        OR (status<>'COMPLETED' AND completed_at IS NULL)
    )
);

CREATE INDEX idx_workspace_generation_recovery_cleanup_claim
    ON workspace_generation_recovery_cleanup(status, claimed_at, created_at, id)
    WHERE status IN ('PENDING','PROCESSING');

COMMENT ON TABLE workspace_generation_recovery_cleanup IS
    'Crash-recoverable deletion of accepted-replacement scratch artifacts. Cleanup is never authoritative over an already-proven workspace restore.';
