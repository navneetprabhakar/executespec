-- RL-2026-06 - durable run-scoped web agent sessions.
-- Keeps /runs/{runId}/agent reloadable without encoding run context only in UI text.

ALTER TABLE agent_session
  ADD COLUMN IF NOT EXISTS run_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_agent_session_run_updated
  ON agent_session(run_id, updated_at DESC);
