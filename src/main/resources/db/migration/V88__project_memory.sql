-- Project Memory: per-run compressed ledger + compacted snapshot
-- Every completed project-scoped run appends one row to project_run_ledger.
-- Every 10 runs, older entries are compacted into project_memory_snapshot.

CREATE TABLE project_run_ledger (
    id                VARCHAR(36)  PRIMARY KEY,
    project_id        VARCHAR(36)  NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    run_id            VARCHAR(36)  NOT NULL,
    run_number        INT          NOT NULL,
    entry             TEXT         NOT NULL,
    token_count       INT,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    compacted_into    VARCHAR(36),
    org_id            VARCHAR(36)  NOT NULL,
    CONSTRAINT uq_project_run_ledger_run UNIQUE (project_id, run_id)
);

CREATE INDEX idx_prl_project_uncompacted
    ON project_run_ledger (project_id, run_number DESC)
    WHERE compacted_into IS NULL;

CREATE INDEX idx_prl_project_run_number
    ON project_run_ledger (project_id, run_number DESC);

CREATE TABLE project_memory_snapshot (
    id                VARCHAR(36)  PRIMARY KEY,
    project_id        VARCHAR(36)  NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    version           INT          NOT NULL DEFAULT 1,
    content           TEXT         NOT NULL,
    covers_runs_from  INT          NOT NULL,
    covers_runs_to    INT          NOT NULL,
    token_count       INT,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    org_id            VARCHAR(36)  NOT NULL
);

CREATE INDEX idx_pms_project_version
    ON project_memory_snapshot (project_id, version DESC);

-- RLS: match V67 pattern — permissive when no org context (system jobs, Flyway).
ALTER TABLE project_run_ledger ENABLE ROW LEVEL SECURITY;
ALTER TABLE project_run_ledger FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON project_run_ledger
    USING (
        current_setting('app.current_org_id', true) = ''
        OR current_setting('app.current_org_id', true) IS NULL
        OR org_id IS NULL
        OR org_id = current_setting('app.current_org_id', true)
    );

ALTER TABLE project_memory_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE project_memory_snapshot FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON project_memory_snapshot
    USING (
        current_setting('app.current_org_id', true) = ''
        OR current_setting('app.current_org_id', true) IS NULL
        OR org_id IS NULL
        OR org_id = current_setting('app.current_org_id', true)
    );
