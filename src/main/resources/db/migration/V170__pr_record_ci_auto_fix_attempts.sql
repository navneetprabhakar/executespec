-- 2026-05-02 — track how many auto-fix re-runs have been triggered against each PR's head branch
-- in response to CI failure webhooks. CiFailureAutoFixService bails when attempts >= cap so a
-- runaway CI ↔ planner loop can't burn credits indefinitely. Default 0 for legacy rows; any
-- existing PR starts the auto-fix budget fresh.
ALTER TABLE pull_request_record
  ADD COLUMN IF NOT EXISTS ci_auto_fix_attempts INT NOT NULL DEFAULT 0;
