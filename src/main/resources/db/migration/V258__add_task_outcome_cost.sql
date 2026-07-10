-- Preserve task-level spend so routing and analytics can use observed cost rather than
-- reconstructing it from wall-clock duration. Existing rows intentionally backfill to zero.
ALTER TABLE model_task_metric
    ADD COLUMN IF NOT EXISTS actual_cost_usd DOUBLE PRECISION NOT NULL DEFAULT 0;

