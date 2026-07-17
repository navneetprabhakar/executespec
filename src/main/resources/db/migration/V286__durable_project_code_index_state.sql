-- V286 — durable, source-bound readiness for project semantic-code indexes.
--
-- The V223 chunk table deliberately remains the vector corpus. This companion row is the
-- authoritative control-plane state: it prevents "one chunk means indexed", makes partial
-- coverage visible, and provides a database lease + monotonically increasing fencing token so
-- two JVMs cannot publish the same project/repository index concurrently.

CREATE TABLE IF NOT EXISTS project_code_index_state (
    project_id        VARCHAR(255) NOT NULL,
    repo_id           UUID         NOT NULL REFERENCES project_repo(id) ON DELETE CASCADE,
    source_sha        VARCHAR(128)  NOT NULL,
    status            VARCHAR(16)   NOT NULL,
    status_reason     VARCHAR(255),
    discovered_files  INTEGER      NOT NULL DEFAULT 0,
    eligible_files    INTEGER      NOT NULL DEFAULT 0,
    indexed_files     INTEGER      NOT NULL DEFAULT 0,
    refreshed_files   INTEGER      NOT NULL DEFAULT 0,
    unchanged_files   INTEGER      NOT NULL DEFAULT 0,
    skipped_files     INTEGER      NOT NULL DEFAULT 0,
    deleted_files     INTEGER      NOT NULL DEFAULT 0,
    failed_files      INTEGER      NOT NULL DEFAULT 0,
    indexed_chunks    INTEGER      NOT NULL DEFAULT 0,
    coverage_percent  NUMERIC(5,2) NOT NULL DEFAULT 0,
    inventory_truncated BOOLEAN    NOT NULL DEFAULT FALSE,
    lease_owner       VARCHAR(160),
    lease_token       BIGINT       NOT NULL DEFAULT 0,
    lease_expires_at  TIMESTAMPTZ,
    started_at        TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (project_id, repo_id),
    CONSTRAINT chk_code_index_status
        CHECK (status IN ('UNINDEXED', 'INDEXING', 'READY', 'PARTIAL', 'STALE', 'FAILED')),
    CONSTRAINT chk_code_index_counts
        CHECK (discovered_files >= 0 AND eligible_files >= 0 AND indexed_files >= 0
            AND refreshed_files >= 0 AND unchanged_files >= 0 AND skipped_files >= 0
            AND deleted_files >= 0 AND failed_files >= 0 AND indexed_chunks >= 0),
    CONSTRAINT chk_code_index_coverage CHECK (coverage_percent >= 0 AND coverage_percent <= 100)
);

CREATE INDEX IF NOT EXISTS idx_code_index_state_project
    ON project_code_index_state(project_id, status);
CREATE INDEX IF NOT EXISTS idx_code_index_state_expired_lease
    ON project_code_index_state(lease_expires_at)
    WHERE status = 'INDEXING';

-- V223's file lookup was repo-only. Project scoping is required when the same registry repo is
-- linked to more than one project/tenant context.
CREATE INDEX IF NOT EXISTS idx_pcc_project_repo_file
    ON project_code_chunk(project_id, repo_id, file_path);
