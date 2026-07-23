-- Enterprise hostile-upload admission and restart-safe project-document indexing.
-- Applied migrations remain immutable; V95 data stays readable.

ALTER TABLE project_document
    ADD COLUMN IF NOT EXISTS error_code VARCHAR(64),
    ADD COLUMN IF NOT EXISTS page_count INTEGER,
    ADD COLUMN IF NOT EXISTS extracted_chars INTEGER,
    ADD COLUMN IF NOT EXISTS estimated_tokens INTEGER,
    ADD COLUMN IF NOT EXISTS processing_attempt INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS processing_started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS processing_completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS cancellation_requested BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS processing_lease_owner VARCHAR(64),
    ADD COLUMN IF NOT EXISTS processing_lease_token BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS processing_lease_expires_at TIMESTAMPTZ;

ALTER TABLE project_document
    ADD CONSTRAINT ck_project_document_lease_shape CHECK (
        (processing_lease_owner IS NULL AND processing_lease_expires_at IS NULL)
        OR (processing_lease_owner IS NOT NULL AND processing_lease_expires_at IS NOT NULL)
    );

CREATE TABLE IF NOT EXISTS project_document_blob (
    document_id VARCHAR(36) PRIMARY KEY
        REFERENCES project_document(id) ON DELETE CASCADE,
    content_bytes BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_project_document_recovery
    ON project_document (status, processing_lease_expires_at, updated_at)
    WHERE deleted_at IS NULL;
