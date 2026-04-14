-- V78: track resume attempts per run so StartupRunResumerV2 can cap retries
-- and avoid infinite resume loops across deploys.
--
-- Bumps on every boot-time resume attempt (Phase 2 replay) + every fail-cleanly
-- path in Phase 1 so we accumulate accounting even before replay ships.
-- Cap: 3 attempts (enforced in code, not the schema).

ALTER TABLE pipeline_run
  ADD COLUMN resume_attempts INTEGER NOT NULL DEFAULT 0;

-- Backfill existing terminal rows as 0 (already the default). No rows need updating.

COMMENT ON COLUMN pipeline_run.resume_attempts IS
  'Incremented by StartupRunResumerV2 on each resume attempt (fail-cleanly or replay). Capped at 3.';
