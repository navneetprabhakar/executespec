-- Durable operator evidence for poison terminal records. This never bypasses terminal fences.
CREATE TABLE workspace_generation_reconciliation_failure (
    id UUID PRIMARY KEY,
    identity_kind VARCHAR(16) NOT NULL
        CHECK (identity_kind IN ('ATTEMPT','RUN')),
    identity_key VARCHAR(255) NOT NULL,
    attempt_id UUID REFERENCES workspace_generation_attempt(id) ON DELETE CASCADE,
    run_id VARCHAR(100) REFERENCES pipeline_run(run_id) ON DELETE CASCADE,
    failure_count INTEGER NOT NULL CHECK (failure_count > 0),
    last_error_code VARCHAR(96) NOT NULL
        CHECK (last_error_code ~ '^[A-Z0-9_]{3,96}$'),
    first_failed_at TIMESTAMPTZ NOT NULL,
    last_failed_at TIMESTAMPTZ NOT NULL,
    dead_letter_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    CHECK (last_failed_at >= first_failed_at),
    CHECK (dead_letter_at IS NULL OR dead_letter_at >= first_failed_at),
    CHECK (resolved_at IS NULL OR resolved_at >= first_failed_at),
    CHECK ((identity_kind='ATTEMPT' AND attempt_id IS NOT NULL AND run_id IS NULL
            AND identity_key=attempt_id::text)
        OR (identity_kind='RUN' AND run_id IS NOT NULL AND attempt_id IS NULL
            AND identity_key=run_id)),
    UNIQUE (identity_kind, identity_key)
);

CREATE INDEX idx_workspace_generation_reconciliation_open
    ON workspace_generation_reconciliation_failure(last_failed_at, identity_kind, identity_key)
    WHERE resolved_at IS NULL;

CREATE INDEX idx_workspace_generation_reconciliation_dead_letter
    ON workspace_generation_reconciliation_failure(dead_letter_at, identity_kind, identity_key)
    WHERE resolved_at IS NULL AND dead_letter_at IS NOT NULL;

COMMENT ON TABLE workspace_generation_reconciliation_failure IS
    'Safe, durable operator evidence for poison terminal reconciliation identities; never execution authority.';
