-- V3: Add actual cost tracking and Anthropic prompt-cache token fields
-- actual_cost_usd: filled post-run by CostEstimator.computeCostForRun() with cache-aware multipliers
-- cache_read_tokens / cache_write_tokens: sourced from Anthropic response metadata per iteration

ALTER TABLE pipeline_run
    ADD COLUMN IF NOT EXISTS actual_cost_usd DOUBLE PRECISION;

ALTER TABLE agent_task
    ADD COLUMN IF NOT EXISTS cache_read_tokens  INTEGER DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cache_write_tokens INTEGER DEFAULT 0;
