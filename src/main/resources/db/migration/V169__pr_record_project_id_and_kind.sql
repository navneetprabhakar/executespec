-- =====================================================================
-- V169: Make pull_request_record visible at the project level for
-- system PRs (bootstrap + architecture + chat tools), not only for
-- per-run launches.
--
-- Background: V82 introduced pull_request_record with run_id NOT NULL.
-- That covers the run/launch path (ScmPushService + ExecutionBranchService
-- both set run_id), but PRs opened by BootstrapService (initial
-- .executespec/ scaffold) and ExecutespecFolderWriter (architecture
-- + contract artefact writes) bypass that table entirely. They're
-- created via gitHub.createPullRequest(...) and the URL is stored on
-- project_repo, but the project dashboard's Pull requests tab queries
-- pull_request_record — so those PRs were invisible.
--
-- 2026-05-02 user report: "PR not visible in dashboard" after a fresh
-- project setup that created 6 system PRs (3 bootstrap + 3 architecture
-- writes across 3 repos).
--
-- Schema changes:
--
--   1. run_id is now nullable. System PRs aren't tied to a specific
--      run; making the column nullable lets us persist them with the
--      same row shape as run-scoped PRs without a synthetic runId.
--      Existing rows are unaffected (they all already have run_id set
--      by the run/launch code paths).
--
--   2. project_id (VARCHAR(36), nullable) — when set, identifies the
--      project the PR belongs to. Run-scoped PRs leave this null and
--      keep being looked up via run_id → pipeline_run.project_id;
--      system PRs set it directly. The query in
--      ProjectLaunchController#projectPrs uses this column to surface
--      system PRs alongside run/launch PRs.
--
--   3. kind (VARCHAR(32) NOT NULL DEFAULT 'RUN', CHECK in
--      ('RUN', 'BOOTSTRAP', 'ARCHITECTURE', 'CONTRACT', 'CHAT_TOOL'))
--      — origin classifier. Lets the dashboard group / filter system
--      PRs separately from run PRs and lets us add new categories
--      (e.g. CHAT_TOOL for PRs opened by the project-chat
--      regenerate_architecture tool, V169-companion) without
--      reshuffling.
--
-- Index:
--   idx_pr_record_project_id — partial index on rows where
--   project_id IS NOT NULL. Keeps the index tiny (most rows are
--   run-scoped with project_id NULL). Hot path is the project
--   dashboard's "show me every system PR for this project" query.
--
-- Idempotent: every column / index uses IF [NOT] EXISTS so re-running
-- the migration on a partially-applied DB is a no-op.
-- =====================================================================

ALTER TABLE pull_request_record
  ALTER COLUMN run_id DROP NOT NULL;

ALTER TABLE pull_request_record
  ADD COLUMN IF NOT EXISTS project_id VARCHAR(36),
  ADD COLUMN IF NOT EXISTS kind VARCHAR(32) NOT NULL DEFAULT 'RUN';

-- Defensive: re-create the CHECK constraint each run. Postgres doesn't
-- support `ADD CONSTRAINT IF NOT EXISTS` so we drop-then-create.
ALTER TABLE pull_request_record
  DROP CONSTRAINT IF EXISTS chk_pr_record_kind;

ALTER TABLE pull_request_record
  ADD CONSTRAINT chk_pr_record_kind
  CHECK (kind IN ('RUN', 'BOOTSTRAP', 'ARCHITECTURE', 'CONTRACT', 'CHAT_TOOL'));

CREATE INDEX IF NOT EXISTS idx_pr_record_project_id
  ON pull_request_record(project_id)
  WHERE project_id IS NOT NULL;
