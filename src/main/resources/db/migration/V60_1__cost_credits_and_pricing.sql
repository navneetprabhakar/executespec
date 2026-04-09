-- V60: Cost-proportional run credits, cached input pricing, run cost audit log
-- Adds credits_charged and hard_cap to pipeline_run for cost-proportional billing.
-- Adds cached_input_cost_per_1m to model_pricing for prompt-cache-aware cost estimation.
-- Creates run_cost_log table for pricing analytics and margin tracking.

-- 1. Pipeline run: track credits charged and hard cap
ALTER TABLE pipeline_run ADD COLUMN credits_charged INTEGER NOT NULL DEFAULT 1;
ALTER TABLE pipeline_run ADD COLUMN hard_cap_usd DOUBLE PRECISION;

-- 2. Model pricing: cached input pricing (prompt cache / context caching)
ALTER TABLE model_pricing ADD COLUMN cached_input_cost_per_1m DOUBLE PRECISION;

-- 3. Run cost audit log for pricing analytics
CREATE TABLE run_cost_log (
    id              BIGSERIAL PRIMARY KEY,
    run_id          VARCHAR(50) NOT NULL,
    user_id         VARCHAR(50) NOT NULL,
    org_id          VARCHAR(50),
    provider        VARCHAR(20) NOT NULL,
    estimated_cost  DOUBLE PRECISION NOT NULL,
    actual_cost     DOUBLE PRECISION,
    hard_cap        DOUBLE PRECISION NOT NULL,
    credits_charged INTEGER NOT NULL,
    status          VARCHAR(30) NOT NULL,
    task_count      INTEGER NOT NULL,
    tasks_completed INTEGER,
    budget_hit      BOOLEAN NOT NULL DEFAULT FALSE,
    margin_usd      DOUBLE PRECISION,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_run_cost_log_user ON run_cost_log(user_id);
CREATE INDEX idx_run_cost_log_created ON run_cost_log(created_at);
CREATE INDEX idx_run_cost_log_run ON run_cost_log(run_id);
