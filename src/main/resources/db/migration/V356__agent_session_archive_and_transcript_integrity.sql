-- Durable full-response integrity plus an explicit, non-destructive agent-session lifecycle.
-- assistant_summary remains PostgreSQL TEXT and is the authoritative complete response.

ALTER TABLE agent_turn
  ADD COLUMN IF NOT EXISTS assistant_char_count INTEGER,
  ADD COLUMN IF NOT EXISTS assistant_byte_count INTEGER,
  ADD COLUMN IF NOT EXISTS assistant_line_count INTEGER,
  ADD COLUMN IF NOT EXISTS assistant_sha256 VARCHAR(64);

ALTER TABLE agent_session
  ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS archived_by_user_id VARCHAR(64),
  ADD COLUMN IF NOT EXISTS archive_reason VARCHAR(240);

CREATE INDEX IF NOT EXISTS idx_agent_session_user_lifecycle_updated
  ON agent_session(user_id, status, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_turn_session_status
  ON agent_turn(session_id, status);
