-- Durable, cross-JVM work ownership for whole-repository Code Review and PR Review (V299).
-- The customer-facing review rows remain the result records; this table owns asynchronous
-- execution, immutable route/input snapshots, retries, cancellation, heartbeat and fencing.

ALTER TABLE code_review_scan DROP CONSTRAINT IF EXISTS chk_code_review_scan_status;
ALTER TABLE code_review_scan ADD CONSTRAINT chk_code_review_scan_status CHECK (
    status IN ('QUEUED', 'CLONING', 'STATIC_RUNNING', 'LLM_RUNNING', 'COMPLETE', 'FAILED', 'CANCELLED')
);

ALTER TABLE pr_review DROP CONSTRAINT IF EXISTS chk_pr_review_status;
ALTER TABLE pr_review ADD CONSTRAINT chk_pr_review_status CHECK (
    status IN ('QUEUED', 'RUNNING', 'COMPLETE', 'FAILED', 'CANCELLED')
);

CREATE TABLE review_background_job (
    id UUID PRIMARY KEY,
    job_type VARCHAR(24) NOT NULL,
    subject_id UUID NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    org_id VARCHAR(255) NOT NULL,
    repo_id UUID NOT NULL,
    requested_by_user_id VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    model VARCHAR(160) NOT NULL,
    execution_context_id VARCHAR(64) NOT NULL,
    execution_scope_mode VARCHAR(32) NOT NULL,
    execution_scope_org_id VARCHAR(255),
    payload JSONB NOT NULL,
    result_payload JSONB,
    result_ready_at TIMESTAMPTZ,
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    lease_owner VARCHAR(160),
    lease_token BIGINT NOT NULL DEFAULT 0,
    lease_expires_at TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ,
    cancel_requested_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    last_error_message VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_review_job_type CHECK (job_type IN ('CODE_REVIEW', 'PR_REVIEW')),
    CONSTRAINT ck_review_job_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'CANCEL_REQUESTED', 'CANCELLED', 'SUCCEEDED', 'FAILED')
    ),
    CONSTRAINT ck_review_job_attempt CHECK (
        attempt_count >= 0 AND max_attempts > 0 AND attempt_count <= max_attempts
    ),
    CONSTRAINT ck_review_job_scope CHECK (
        (execution_scope_mode = 'CUSTOMER' AND execution_scope_org_id = org_id)
        OR (execution_scope_mode = 'PLATFORM_INTERNAL' AND execution_scope_org_id IS NULL)
    ),
    CONSTRAINT ck_review_job_lease CHECK (
        (status IN ('RUNNING', 'CANCEL_REQUESTED')
            AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (status NOT IN ('RUNNING', 'CANCEL_REQUESTED')
            AND lease_owner IS NULL AND lease_expires_at IS NULL)
    )
);

CREATE UNIQUE INDEX uq_review_job_subject
    ON review_background_job (job_type, subject_id);

CREATE UNIQUE INDEX uq_review_job_idempotency
    ON review_background_job (idempotency_key);

CREATE INDEX idx_review_job_claim
    ON review_background_job (next_attempt_at, created_at)
    WHERE status = 'QUEUED';

CREATE INDEX idx_review_job_stale_lease
    ON review_background_job (lease_expires_at, created_at)
    WHERE status IN ('RUNNING', 'CANCEL_REQUESTED');

CREATE INDEX idx_review_job_project_created
    ON review_background_job (project_id, created_at DESC);

COMMENT ON COLUMN review_background_job.payload IS
    'Bounded immutable source snapshot. Credentials/PATs are excluded and re-resolved from requested_by_user_id; repository text remains governed customer data.';
COMMENT ON COLUMN review_background_job.lease_token IS
    'Monotonic fencing token; a stale JVM cannot heartbeat or publish a terminal result.';
COMMENT ON COLUMN review_background_job.execution_context_id IS
    'Pinned V282 LLM policy identity; retries cannot silently rebuild a different provider/key policy.';
COMMENT ON COLUMN review_background_job.result_payload IS
    'Bounded prepared review result governed as customer source data; restart publishes it without repeating provider spend after staging.';
