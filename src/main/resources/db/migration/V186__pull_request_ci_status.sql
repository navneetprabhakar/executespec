-- Surface CI check status on each tracked PR.
--
-- Pre-fix: the dashboard "Refresh" button (PrRefreshService) only polled
-- the PR's open/merged/closed state and never asked GitHub about CI
-- check_runs. The webhook-driven CiFailureAutoFixService DOES handle
-- check_run.failure events, but it requires a webhook to be installed
-- AND `ci.autofix.enabled` to be true; the default-off state combined
-- with the no-webhook common case meant a user clicking Refresh got
-- zero CI signal — the PR could be merge-blocked by a red CI check and
-- the UI would still show "Open" with no badge.
--
-- This migration adds three columns to support polling-side CI status:
--   • ci_status — UNKNOWN | PENDING | SUCCESS | FAILURE | NEUTRAL
--     mirrors GitHub's check-runs `conclusion` rollup. NEUTRAL covers
--     skipped/stale checks. UNKNOWN is the pre-poll default + the
--     fallback when GitHub returns no checks at all.
--   • ci_failing_count — number of failing checks in the latest poll.
--     0 when status != FAILURE. Drives the inbox count.
--   • ci_checked_at — timestamp of the last successful poll. Used by
--     the frontend to show "as of 2 min ago" hover.
--
-- Idempotent: every ALTER uses IF NOT EXISTS / DEFAULT so a partial
-- replay is safe.

ALTER TABLE pull_request_record
    ADD COLUMN IF NOT EXISTS ci_status        VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN IF NOT EXISTS ci_failing_count INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS ci_checked_at    TIMESTAMPTZ;

ALTER TABLE pull_request_record
    DROP CONSTRAINT IF EXISTS pull_request_record_ci_status_check;
ALTER TABLE pull_request_record
    ADD  CONSTRAINT pull_request_record_ci_status_check
         CHECK (ci_status IN ('UNKNOWN','PENDING','SUCCESS','FAILURE','NEUTRAL'));

-- Partial index for the dashboard "PRs with failing CI" rollup query
-- (the hottest path post-feature). FAILURE-only since SUCCESS/UNKNOWN
-- aren't filtered on; keeps the index small.
CREATE INDEX IF NOT EXISTS idx_pull_request_record_ci_failure
    ON pull_request_record(project_id, ci_status)
    WHERE ci_status = 'FAILURE';
