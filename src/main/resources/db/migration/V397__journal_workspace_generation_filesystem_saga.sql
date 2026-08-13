-- Journal every non-transactional workspace-generation side effect before it is attempted.
-- PostgreSQL cannot commit a Git tree, archive object, or directory rename atomically; this
-- append/monotonic record is the durable bridge used to resume those physical actions.
-- V394 accidentally made the legitimate ADMITTED/AUTHORITY_ACQUIRED -> ROLLING_BACK recovery
-- claim impossible when no predecessor checkpoint had been recorded yet. Preserve the strict
-- shape while admitting that one transient, recovery-leased pre-dispatch state.
ALTER TABLE workspace_generation_attempt
    DROP CONSTRAINT workspace_generation_llm_lease_chk,
    DROP CONSTRAINT workspace_generation_checkpoint_chk,
    ADD CONSTRAINT workspace_generation_llm_lease_chk CHECK (
        (state = 'ADMITTED' AND llm_operation_id IS NULL
            AND llm_attempt_number IS NULL AND llm_lease_token IS NULL)
        OR (state IN ('ROLLING_BACK', 'ROLLED_BACK') AND before_generation IS NULL
            AND llm_operation_id IS NULL AND llm_attempt_number IS NULL
            AND llm_lease_token IS NULL)
        OR (state NOT IN ('ADMITTED', 'ROLLING_BACK', 'ROLLED_BACK')
            AND llm_operation_id IS NOT NULL AND llm_attempt_number > 0
            AND llm_lease_token IS NOT NULL)
        OR (state IN ('ROLLING_BACK', 'ROLLED_BACK') AND before_generation IS NOT NULL
            AND llm_operation_id IS NOT NULL AND llm_attempt_number > 0
            AND llm_lease_token IS NOT NULL)
    ),
    ADD CONSTRAINT workspace_generation_checkpoint_chk CHECK (
        (state IN ('ADMITTED', 'OPERATION_CLAIMED', 'AUTHORITY_ACQUIRED')
            AND before_checkpoint_kind IS NULL AND before_checkpoint_key IS NULL
            AND before_checkpoint_digest IS NULL AND before_checkpoint_completed_at IS NULL)
        OR (state IN ('ROLLING_BACK', 'ROLLED_BACK') AND before_generation IS NULL
            AND before_checkpoint_kind IS NULL AND before_checkpoint_key IS NULL
            AND before_checkpoint_digest IS NULL AND before_checkpoint_completed_at IS NULL)
        OR (state NOT IN ('ADMITTED', 'OPERATION_CLAIMED', 'AUTHORITY_ACQUIRED')
            AND before_generation ~ '^[0-9a-f]{64}$'
            AND repositories_json IS NOT NULL
            AND ignored_artifact_baseline_json IS NOT NULL
            AND ignored_artifact_baseline_digest ~ '^[0-9a-f]{64}$'
            AND before_checkpoint_kind IN ('GCS', 'LOCAL_SAME_HOST', 'LOCAL_ARCHIVE')
            AND before_checkpoint_key IS NOT NULL
            AND before_checkpoint_digest ~ '^[0-9a-f]{64}$'
            AND before_checkpoint_completed_at IS NOT NULL
            AND (
                (checkpoint_kind IS NULL AND checkpoint_key IS NULL
                    AND checkpoint_generation IS NULL AND checkpoint_digest IS NULL
                    AND checkpoint_completed_at IS NULL)
                OR (checkpoint_kind IN ('GCS', 'LOCAL_SAME_HOST', 'LOCAL_ARCHIVE')
                    AND checkpoint_key IS NOT NULL
                    AND checkpoint_generation ~ '^[0-9a-f]{64}$'
                    AND checkpoint_digest ~ '^[0-9a-f]{64}$'
                    AND checkpoint_completed_at IS NOT NULL)
            ))
    );

CREATE TABLE workspace_generation_step (
    id UUID PRIMARY KEY,
    attempt_id UUID NOT NULL REFERENCES workspace_generation_attempt(id) ON DELETE CASCADE,
    step_kind VARCHAR(40) NOT NULL CHECK (step_kind IN (
        'BEFORE_ARCHIVE_UPLOAD', 'CANDIDATE_ARCHIVE_UPLOAD',
        'ROLLBACK_RESTORE', 'ACCEPTED_REPLACEMENT'
    )),
    step_key VARCHAR(512) NOT NULL,
    idempotency_key CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('INTENT', 'APPLIED', 'VERIFIED', 'FAILED')),
    expected_generation CHAR(64) NOT NULL,
    expected_tree CHAR(40),
    object_key TEXT,
    artifact_digest CHAR(64),
    storage_generation VARCHAR(128),
    size_bytes BIGINT,
    crc32c VARCHAR(64),
    etag VARCHAR(512),
    evidence_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    evidence_digest CHAR(64) NOT NULL,
    failure_code VARCHAR(64),
    failure_reason VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    applied_at TIMESTAMPTZ,
    verified_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    CONSTRAINT uq_workspace_generation_step_identity UNIQUE (attempt_id, step_kind, step_key),
    CONSTRAINT uq_workspace_generation_step_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_workspace_generation_step_hashes CHECK (
        idempotency_key ~ '^[0-9a-f]{64}$'
        AND expected_generation ~ '^[0-9a-f]{64}$'
        AND (expected_tree IS NULL OR expected_tree ~ '^[0-9a-f]{40}$')
        AND (artifact_digest IS NULL OR artifact_digest ~ '^[0-9a-f]{64}$')
        AND evidence_digest ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_workspace_generation_step_receipt CHECK (
        (state = 'INTENT' AND applied_at IS NULL AND verified_at IS NULL AND failed_at IS NULL)
        OR (state = 'APPLIED' AND applied_at IS NOT NULL AND verified_at IS NULL AND failed_at IS NULL)
        OR (state = 'VERIFIED' AND applied_at IS NOT NULL AND verified_at IS NOT NULL AND failed_at IS NULL)
        OR (state = 'FAILED' AND failed_at IS NOT NULL AND failure_code IS NOT NULL
            AND failure_reason IS NOT NULL)
    ),
    CONSTRAINT ck_workspace_generation_archive_receipt CHECK (
        (step_kind NOT IN ('BEFORE_ARCHIVE_UPLOAD', 'CANDIDATE_ARCHIVE_UPLOAD'))
        OR (state = 'INTENT')
        OR (object_key IS NOT NULL AND artifact_digest IS NOT NULL
            AND storage_generation IS NOT NULL AND size_bytes > 0
            AND crc32c IS NOT NULL AND etag IS NOT NULL)
    )
);

CREATE INDEX idx_workspace_generation_step_recovery
    ON workspace_generation_step(state, created_at, id)
    WHERE state IN ('INTENT', 'APPLIED');

CREATE INDEX idx_workspace_generation_step_attempt
    ON workspace_generation_step(attempt_id, step_kind, step_key);

CREATE OR REPLACE FUNCTION fence_workspace_generation_step_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.attempt_id IS DISTINCT FROM OLD.attempt_id
       OR NEW.step_kind IS DISTINCT FROM OLD.step_kind
       OR NEW.step_key IS DISTINCT FROM OLD.step_key
       OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
       OR NEW.expected_generation IS DISTINCT FROM OLD.expected_generation
       OR NEW.expected_tree IS DISTINCT FROM OLD.expected_tree
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'workspace generation step identity is immutable';
    END IF;
    IF OLD.state = 'VERIFIED' THEN
        RAISE EXCEPTION 'verified workspace generation step is immutable';
    END IF;
    IF OLD.state = 'FAILED' AND NEW.state <> 'FAILED' THEN
        RAISE EXCEPTION 'failed workspace generation step cannot be reopened';
    END IF;
    IF OLD.state = 'APPLIED' AND NEW.state NOT IN ('APPLIED', 'VERIFIED', 'FAILED') THEN
        RAISE EXCEPTION 'workspace generation step transition regressed';
    END IF;
    IF OLD.state = 'INTENT' AND NEW.state NOT IN ('INTENT', 'APPLIED', 'FAILED') THEN
        RAISE EXCEPTION 'workspace generation step skipped physical application';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_workspace_generation_step_fence
BEFORE UPDATE ON workspace_generation_step
FOR EACH ROW EXECUTE FUNCTION fence_workspace_generation_step_mutation();

CREATE OR REPLACE FUNCTION reject_workspace_generation_step_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'workspace generation steps are retained with their attempt';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_workspace_generation_step_no_delete
BEFORE DELETE ON workspace_generation_step
FOR EACH ROW
WHEN (pg_trigger_depth() = 0)
EXECUTE FUNCTION reject_workspace_generation_step_delete();

COMMENT ON TABLE workspace_generation_step IS
    'Durable intent, physical receipt, and verification journal for filesystem/object-store BUILD saga steps.';

CREATE TABLE workspace_generation_archive_cleanup (
    id UUID PRIMARY KEY,
    step_id UUID NOT NULL UNIQUE REFERENCES workspace_generation_step(id) ON DELETE CASCADE,
    attempt_id UUID NOT NULL REFERENCES workspace_generation_attempt(id) ON DELETE CASCADE,
    checkpoint_kind VARCHAR(24) NOT NULL CHECK (checkpoint_kind IN ('GCS', 'LOCAL_ARCHIVE')),
    object_key TEXT NOT NULL,
    artifact_digest CHAR(64) NOT NULL CHECK (artifact_digest ~ '^[0-9a-f]{64}$'),
    storage_generation VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    crc32c VARCHAR(64) NOT NULL,
    etag VARCHAR(512) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'PROTECTED')),
    claimed_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_workspace_generation_archive_cleanup_claim
    ON workspace_generation_archive_cleanup(status, claimed_at, created_at, id)
    WHERE status IN ('PENDING', 'PROCESSING');

COMMENT ON TABLE workspace_generation_archive_cleanup IS
    'Deletion queue for terminal orphan generation archives; accepted and unresolved references are rechecked before physical deletion.';
