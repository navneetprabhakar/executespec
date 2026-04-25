-- v2.6.2 — Hierarchical Planner support (slice 1 of 5)
-- Adds: plan_strategy + per-phase cost columns on pipeline_run,
--       task_type + expansion_status on agent_task.
-- All columns are nullable; existing in-flight runs keep working unchanged.
-- Old single-phase planner stays alive through slices 1-3 as a fallback.

ALTER TABLE pipeline_run
  ADD COLUMN IF NOT EXISTS plan_strategy            VARCHAR(32),
  ADD COLUMN IF NOT EXISTS plan_outline_json        JSONB,
  ADD COLUMN IF NOT EXISTS classifier_reasoning     TEXT,
  ADD COLUMN IF NOT EXISTS classifier_input_tokens  BIGINT,
  ADD COLUMN IF NOT EXISTS classifier_output_tokens BIGINT,
  ADD COLUMN IF NOT EXISTS classifier_cost_usd      DOUBLE PRECISION,
  ADD COLUMN IF NOT EXISTS outline_input_tokens     BIGINT,
  ADD COLUMN IF NOT EXISTS outline_output_tokens    BIGINT,
  ADD COLUMN IF NOT EXISTS outline_cost_usd         DOUBLE PRECISION,
  ADD COLUMN IF NOT EXISTS expansion_input_tokens   BIGINT,
  ADD COLUMN IF NOT EXISTS expansion_output_tokens  BIGINT,
  ADD COLUMN IF NOT EXISTS expansion_cost_usd       DOUBLE PRECISION,
  ADD COLUMN IF NOT EXISTS coherence_input_tokens   BIGINT,
  ADD COLUMN IF NOT EXISTS coherence_output_tokens  BIGINT,
  ADD COLUMN IF NOT EXISTS coherence_cost_usd       DOUBLE PRECISION;

ALTER TABLE agent_task
  ADD COLUMN IF NOT EXISTS task_type        VARCHAR(16),
  ADD COLUMN IF NOT EXISTS expansion_status VARCHAR(16),
  ADD COLUMN IF NOT EXISTS expanded_at      TIMESTAMP;

-- Plan strategy is queried for analytics (classifier accuracy,
-- per-strategy success rates) and for filtering admin dashboards.
CREATE INDEX IF NOT EXISTS idx_pipeline_run_plan_strategy
  ON pipeline_run(plan_strategy)
  WHERE plan_strategy IS NOT NULL;
