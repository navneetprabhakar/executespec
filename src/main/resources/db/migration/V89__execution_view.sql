-- Execution View (v2.3.0): multi-repo, multi-solution execution wrapped around V2 runs.
-- An ExecutionRun decomposes a user requirement into one or more Solutions. Each
-- Solution targets exactly one repo and executes as a single V2 PipelineRun. A
-- DAG of dependency edges orders solutions; solutions in the same topological
-- layer that don't overlap files may run in parallel.

CREATE TABLE execution_run (
    id                      VARCHAR(36)   PRIMARY KEY,
    project_id              VARCHAR(36)   NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    org_id                  VARCHAR(36)   NOT NULL,
    created_by              VARCHAR(36)   NOT NULL,
    jira_enabled            BOOLEAN       NOT NULL DEFAULT FALSE,
    status                  VARCHAR(30)   NOT NULL DEFAULT 'SETUP',
        -- SETUP, ANALYSING, ARCH_REVIEW, PLANNING, EXECUTING, PAUSED, COMPLETE, ABORTED
    requirement             TEXT          NOT NULL,
    unified_arch_doc        TEXT,
    arch_approved_at        TIMESTAMP,
    execution_plan          TEXT,                               -- JSON: solutions + edges + conflicts + estimates
    plan_approved_at        TIMESTAMP,
    total_estimated_tokens  BIGINT,
    total_actual_tokens     BIGINT        NOT NULL DEFAULT 0,
    total_cost_usd          NUMERIC(10,4) NOT NULL DEFAULT 0,
    created_at              TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP     NOT NULL DEFAULT NOW(),
    deleted_at              TIMESTAMP
);

CREATE TABLE execution_solution (
    id                 VARCHAR(36)   PRIMARY KEY,
    execution_run_id   VARCHAR(36)   NOT NULL REFERENCES execution_run(id) ON DELETE CASCADE,
    sequence_number    INT           NOT NULL,
    title              VARCHAR(500)  NOT NULL,
    description        TEXT,
    status             VARCHAR(30)   NOT NULL DEFAULT 'QUEUED',
        -- QUEUED, RUNNING, VALIDATING, PR_RAISED, DONE, FAILED, SKIPPED
    failure_phase      VARCHAR(20),                             -- RUNNING | VALIDATING | PR
    failure_reason     TEXT,
    retry_count        INT           NOT NULL DEFAULT 0,
    max_retries        INT           NOT NULL DEFAULT 3,
    complexity         VARCHAR(10),                             -- LOW | MEDIUM | HIGH
    estimated_tokens   BIGINT,
    actual_tokens      BIGINT        NOT NULL DEFAULT 0,
    cost_usd           NUMERIC(10,4) NOT NULL DEFAULT 0,
    git_tag_start      VARCHAR(255),
    git_tag_end        VARCHAR(255),
    pipeline_run_id    VARCHAR(36),                             -- FK to pipeline_run.run_id (soft — lifecycle-coupled)
    pr_number          INT,
    pr_url             VARCHAR(500),
    pr_status          VARCHAR(20),                             -- OPEN | APPROVED | MERGED | CLOSED
    jira_ticket_id     VARCHAR(50),
    jira_enabled       BOOLEAN       NOT NULL DEFAULT FALSE,
    started_at         TIMESTAMP,
    completed_at       TIMESTAMP,
    created_at         TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE TABLE execution_solution_dependency (
    solution_id     VARCHAR(36) NOT NULL REFERENCES execution_solution(id) ON DELETE CASCADE,
    depends_on_id   VARCHAR(36) NOT NULL REFERENCES execution_solution(id) ON DELETE CASCADE,
    PRIMARY KEY (solution_id, depends_on_id)
);

CREATE TABLE execution_solution_repo (
    id                VARCHAR(36)  PRIMARY KEY,
    solution_id       VARCHAR(36)  NOT NULL REFERENCES execution_solution(id) ON DELETE CASCADE,
    repo_id           UUID         NOT NULL REFERENCES project_repo(id) ON DELETE CASCADE,
    execution_branch  VARCHAR(255),
    target_files      TEXT,                                    -- JSON array
    actual_files      TEXT,                                    -- JSON array
    codemap_version   INT          NOT NULL DEFAULT 0
);

CREATE TABLE execution_context_handover (
    id                 VARCHAR(36) PRIMARY KEY,
    from_solution_id   VARCHAR(36) NOT NULL REFERENCES execution_solution(id) ON DELETE CASCADE,
    to_solution_id     VARCHAR(36) NOT NULL REFERENCES execution_solution(id) ON DELETE CASCADE,
    contract_type      VARCHAR(30) NOT NULL,                    -- INTERFACE | DIFF_CODEMAP | TEST_ANCHOR
    contract_json      TEXT        NOT NULL,
    created_at         TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_exec_run_project         ON execution_run(project_id);
CREATE INDEX idx_exec_run_status          ON execution_run(status);
CREATE INDEX idx_exec_solution_run        ON execution_solution(execution_run_id);
CREATE INDEX idx_exec_solution_status     ON execution_solution(status);
CREATE INDEX idx_exec_solution_repo_sol   ON execution_solution_repo(solution_id);
CREATE INDEX idx_exec_solution_repo_repo  ON execution_solution_repo(repo_id);
CREATE INDEX idx_exec_handover_to         ON execution_context_handover(to_solution_id);

-- RLS (match V67 permissive pattern)
ALTER TABLE execution_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution_run FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON execution_run
    USING (
        current_setting('app.current_org_id', true) = ''
        OR current_setting('app.current_org_id', true) IS NULL
        OR org_id IS NULL
        OR org_id = current_setting('app.current_org_id', true)
    );

ALTER TABLE execution_solution ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution_solution FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON execution_solution
    USING (
        current_setting('app.current_org_id', true) = ''
        OR current_setting('app.current_org_id', true) IS NULL
        OR execution_run_id IN (
            SELECT id FROM execution_run
            WHERE org_id IS NULL
               OR org_id = current_setting('app.current_org_id', true)
        )
    );

-- Feature flag (default off) + tier gating.
INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description) VALUES
    ('execution.view.enabled', 'false', 'BOOLEAN', 'PLATFORM',
     'Enable multi-repo Execution View (v2.3.0). Off by default, flip on per rollout.');

ALTER TABLE tier_config
    ADD COLUMN IF NOT EXISTS execution_view_enabled BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE tier_config SET execution_view_enabled = TRUE WHERE tier = 'ENTERPRISE';

-- Enterprise-level JIRA project key used by the Execution View when jira_enabled
-- is set on an ExecutionRun. Orgs without this column populated simply skip the
-- JIRA sync path with a warning.
ALTER TABLE org ADD COLUMN IF NOT EXISTS jira_project_key VARCHAR(64);
