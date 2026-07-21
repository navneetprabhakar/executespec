-- Durable, restart-safe project exports with append-only lifecycle/download audit.

CREATE TABLE project_export_job (
    id UUID PRIMARY KEY,
    project_id VARCHAR(255) NOT NULL,
    org_id VARCHAR(255) NOT NULL,
    requested_by_user_id VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    format VARCHAR(16) NOT NULL DEFAULT 'TAR_GZ',
    file_name VARCHAR(320) NOT NULL,
    content_type VARCHAR(96) NOT NULL DEFAULT 'application/gzip',
    object_key TEXT,
    size_bytes BIGINT,
    sha256 VARCHAR(64),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    lease_owner VARCHAR(160),
    lease_token BIGINT NOT NULL DEFAULT 0,
    lease_expires_at TIMESTAMPTZ,
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    cancel_requested_at TIMESTAMPTZ,
    last_downloaded_at TIMESTAMPTZ,
    download_count BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_project_export_status CHECK (
        status IN (
            'QUEUED', 'RUNNING', 'CANCEL_REQUESTED', 'CANCELLED',
            'SUCCEEDED', 'FAILED', 'EXPIRING', 'EXPIRED'
        )
    ),
    CONSTRAINT ck_project_export_format CHECK (format = 'TAR_GZ'),
    CONSTRAINT ck_project_export_attempt CHECK (attempt_count >= 0),
    CONSTRAINT ck_project_export_download_count CHECK (download_count >= 0),
    CONSTRAINT ck_project_export_artifact CHECK (
        status NOT IN ('SUCCEEDED', 'EXPIRING')
        OR (object_key IS NOT NULL AND size_bytes IS NOT NULL AND sha256 IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_project_export_idempotency
    ON project_export_job (project_id, requested_by_user_id, idempotency_key);

-- One resource-intensive export per project. Different JVMs still race safely through this index.
CREATE UNIQUE INDEX uq_project_export_active
    ON project_export_job (project_id)
    WHERE status IN ('QUEUED', 'RUNNING', 'CANCEL_REQUESTED');

CREATE INDEX idx_project_export_project_created
    ON project_export_job (project_id, created_at DESC);

CREATE INDEX idx_project_export_claim
    ON project_export_job (next_attempt_at, created_at)
    WHERE status = 'QUEUED';

CREATE INDEX idx_project_export_stale_lease
    ON project_export_job (lease_expires_at)
    WHERE status = 'RUNNING';

CREATE INDEX idx_project_export_cancel_recovery
    ON project_export_job (lease_expires_at)
    WHERE status = 'CANCEL_REQUESTED';

CREATE INDEX idx_project_export_expiry
    ON project_export_job (expires_at)
    WHERE status IN ('SUCCEEDED', 'EXPIRING');

CREATE TABLE project_export_audit (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    org_id VARCHAR(255) NOT NULL,
    actor_user_id VARCHAR(255),
    event_type VARCHAR(32) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    details JSONB,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_project_export_audit_job_time
    ON project_export_audit (job_id, occurred_at DESC);

CREATE INDEX idx_project_export_audit_org_time
    ON project_export_audit (org_id, occurred_at DESC);

CREATE OR REPLACE FUNCTION reject_project_export_audit_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'project_export_audit is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_project_export_audit_append_only
    BEFORE UPDATE OR DELETE ON project_export_audit
    FOR EACH ROW EXECUTE FUNCTION reject_project_export_audit_mutation();
