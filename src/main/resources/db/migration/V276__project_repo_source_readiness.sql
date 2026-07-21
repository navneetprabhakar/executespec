-- Brownfield source readiness is independent from link/bootstrap state.
-- Legacy rows remain UNKNOWN until their next explicit metadata sync.
ALTER TABLE project_repo
    ADD COLUMN IF NOT EXISTS source_readiness_status VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN IF NOT EXISTS source_readiness_reason VARCHAR(96),
    ADD COLUMN IF NOT EXISTS source_file_count BIGINT,
    ADD COLUMN IF NOT EXISTS source_readiness_checked_at TIMESTAMPTZ;

ALTER TABLE project_repo
    ADD CONSTRAINT chk_project_repo_source_readiness_status
    CHECK (source_readiness_status IN
           ('UNKNOWN', 'LINKED', 'AUTH_VALIDATED', 'SOURCE_READABLE',
            'INVENTORIED', 'INDEXING', 'READY', 'BLOCKED'));
