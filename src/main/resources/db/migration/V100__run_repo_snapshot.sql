-- V100 — Per-run repo snapshot: captures the exact commit SHA each linked repo
-- was cloned from at run start. Enables "reset to original state" on terminal
-- runs + makes a run reproducible on demand.
--
-- Why: pre-v2.5.2, RunSyncService.sync(runId) reset repos to current HEAD of
-- the configured branch, not to the state the run actually consumed. If main
-- advanced between clone + sync, the workspace no longer matched what the
-- run executed against.

CREATE TABLE IF NOT EXISTS run_repo_snapshot (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id          VARCHAR(255) NOT NULL,
    repo_id         UUID,
    repo_full_name  VARCHAR(255) NOT NULL,
    branch          VARCHAR(255) NOT NULL,
    commit_sha      VARCHAR(40) NOT NULL,
    cloned_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_run_repo_snapshot_run FOREIGN KEY (run_id)
        REFERENCES pipeline_run(run_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_run_repo_snapshot_run_id ON run_repo_snapshot(run_id);
CREATE INDEX IF NOT EXISTS idx_run_repo_snapshot_repo_id ON run_repo_snapshot(repo_id);
