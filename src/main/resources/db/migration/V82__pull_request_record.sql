-- V82 — Pilot R5. Track every GitHub PR created by ExecuteSpec so the UI can
-- display multi-repo PR status and a webhook can flip status on merge/close
-- without re-querying the GitHub API.

CREATE TABLE IF NOT EXISTS pull_request_record (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id VARCHAR(255) NOT NULL,
    repo_id UUID REFERENCES project_repo(id),
    repo_full_name VARCHAR(255) NOT NULL,
    pr_number INT NOT NULL,
    pr_url VARCHAR(500) NOT NULL,
    head_branch VARCHAR(255),
    base_branch VARCHAR(255),
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    merged_at TIMESTAMP,
    closed_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_pr_status CHECK (status IN ('OPEN', 'MERGED', 'CLOSED'))
);

CREATE INDEX IF NOT EXISTS idx_pr_record_run_id ON pull_request_record(run_id);
CREATE INDEX IF NOT EXISTS idx_pr_record_repo_id ON pull_request_record(repo_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_pr_record_repo_number
    ON pull_request_record(repo_full_name, pr_number);
