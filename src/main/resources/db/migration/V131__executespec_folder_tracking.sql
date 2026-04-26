-- E1.1 — Enterprise readiness, Phase E1 baseline
--
-- Adds .executespec/ folder tracking columns to project_repo. The folder
-- itself lives in the customer's git repo; this table just caches the
-- bootstrap status + last-known SHA so the dashboard can report progress
-- and the bootstrap flow stays idempotent on re-link.
--
-- All columns nullable. Existing project_repo rows are treated as
-- NOT_BOOTSTRAPPED on first read (handled in the entity getter).
--
-- Reverse migration:
--   ALTER TABLE project_repo
--     DROP COLUMN IF EXISTS executespec_folder_status,
--     DROP COLUMN IF EXISTS executespec_folder_sha,
--     DROP COLUMN IF EXISTS executespec_bootstrap_pr_url,
--     DROP COLUMN IF EXISTS executespec_last_synced_at;
--   DROP INDEX IF EXISTS idx_project_repo_executespec_status;

ALTER TABLE project_repo
  ADD COLUMN IF NOT EXISTS executespec_folder_status     VARCHAR(32),
    -- NULL | NOT_BOOTSTRAPPED | BOOTSTRAP_PR_OPEN | BOOTSTRAPPED | BOOTSTRAP_FAILED
  ADD COLUMN IF NOT EXISTS executespec_folder_sha        VARCHAR(64),
    -- last known SHA of .executespec/ folder content on the default branch
  ADD COLUMN IF NOT EXISTS executespec_bootstrap_pr_url  TEXT,
    -- when status=BOOTSTRAP_PR_OPEN, the open PR url so users can find it
  ADD COLUMN IF NOT EXISTS executespec_last_synced_at    TIMESTAMP;
    -- when we last reconciled the cache against the actual repo

-- Partial index — only project_repo rows with a non-null bootstrap status
-- get indexed. Lets the dashboard query "show me all repos still pending
-- bootstrap" without scanning the whole table.
CREATE INDEX IF NOT EXISTS idx_project_repo_executespec_status
  ON project_repo(executespec_folder_status)
  WHERE executespec_folder_status IS NOT NULL;
