-- W0 distributed execution contract: durable per-call LLM usage.
-- This deliberately has no FK to pipeline_run because the same router path is
-- also used by agent sessions, benchmarks, and local/dev diagnostic endpoints.

CREATE TABLE IF NOT EXISTS run_step_usage (
  id VARCHAR(64) PRIMARY KEY,
  run_id VARCHAR(96) NOT NULL,
  task_id VARCHAR(160),
  agent_role VARCHAR(80),
  step_id VARCHAR(120),
  retry_of VARCHAR(120),
  provider VARCHAR(32),
  model VARCHAR(128),
  input_tokens BIGINT NOT NULL DEFAULT 0,
  output_tokens BIGINT NOT NULL DEFAULT 0,
  cache_write_tokens BIGINT NOT NULL DEFAULT 0,
  cache_read_tokens BIGINT NOT NULL DEFAULT 0,
  reasoning_tokens BIGINT NOT NULL DEFAULT 0,
  cost_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
  call_index INTEGER NOT NULL DEFAULT 0,
  provider_usage_raw_json JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_run_step_usage_run_created
  ON run_step_usage(run_id, created_at);

CREATE INDEX IF NOT EXISTS idx_run_step_usage_task_created
  ON run_step_usage(run_id, task_id, created_at);
