-- RL-2026-06-013 — backend protocol for CLI/VS Code agent sessions.
-- Open-ended agent conversation is deliberately separate from pipeline_run so
-- run billing, plan approval, verified score, PR/report, and refund semantics stay intact.

CREATE TABLE IF NOT EXISTS agent_session (
  id VARCHAR(64) PRIMARY KEY,
  org_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  project_id VARCHAR(64),
  repo_remote TEXT,
  repo_root_hash VARCHAR(128),
  client_surface VARCHAR(24) NOT NULL DEFAULT 'CLI',
  title VARCHAR(240) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  default_provider VARCHAR(32),
  default_model VARCHAR(128),
  default_execution_mode VARCHAR(24) NOT NULL DEFAULT 'LOCAL',
  approval_policy VARCHAR(24) NOT NULL DEFAULT 'PLAN',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_session_user_updated
  ON agent_session(user_id, status, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_session_project_updated
  ON agent_session(project_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS agent_turn (
  id VARCHAR(64) PRIMARY KEY,
  session_id VARCHAR(64) NOT NULL REFERENCES agent_session(id) ON DELETE CASCADE,
  org_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  sequence INTEGER NOT NULL,
  user_message TEXT,
  assistant_summary TEXT,
  intent_type VARCHAR(48) NOT NULL,
  intent_confidence DOUBLE PRECISION NOT NULL DEFAULT 0,
  action_type VARCHAR(64),
  proposed_action_json JSONB,
  linked_run_id VARCHAR(64),
  input_tokens BIGINT NOT NULL DEFAULT 0,
  output_tokens BIGINT NOT NULL DEFAULT 0,
  reasoning_tokens BIGINT NOT NULL DEFAULT 0,
  cost_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'OK',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_agent_turn_session_sequence UNIQUE(session_id, sequence)
);

CREATE INDEX IF NOT EXISTS idx_agent_turn_session_sequence
  ON agent_turn(session_id, sequence);

CREATE INDEX IF NOT EXISTS idx_agent_turn_linked_run
  ON agent_turn(linked_run_id);

CREATE TABLE IF NOT EXISTS agent_action_event (
  id VARCHAR(64) PRIMARY KEY,
  session_id VARCHAR(64) NOT NULL REFERENCES agent_session(id) ON DELETE CASCADE,
  turn_id VARCHAR(64) REFERENCES agent_turn(id) ON DELETE CASCADE,
  type VARCHAR(48) NOT NULL,
  payload_json JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_action_event_session_created
  ON agent_action_event(session_id, created_at);

CREATE TABLE IF NOT EXISTS agent_usage_ledger (
  id VARCHAR(64) PRIMARY KEY,
  org_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  source_id VARCHAR(64) NOT NULL,
  provider VARCHAR(32),
  model VARCHAR(128),
  input_tokens BIGINT NOT NULL DEFAULT 0,
  output_tokens BIGINT NOT NULL DEFAULT 0,
  reasoning_tokens BIGINT NOT NULL DEFAULT 0,
  cache_read_tokens BIGINT NOT NULL DEFAULT 0,
  cost_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
  billable_units BIGINT NOT NULL DEFAULT 0,
  billing_policy VARCHAR(32) NOT NULL DEFAULT 'INCLUDED',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_usage_org_created
  ON agent_usage_ledger(org_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_usage_source
  ON agent_usage_ledger(source_type, source_id);
