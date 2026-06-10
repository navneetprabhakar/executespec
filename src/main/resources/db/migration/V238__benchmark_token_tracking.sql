-- V238: Aggregate token tracking on the in-app benchmark layer (RL-2026-06).
--
-- The benchmark dashboard tracked resolved% + cost but not tokens. The Harbor per-trial
-- result.json carries agent_result.n_input_tokens / n_output_tokens (the ExecuteSpec run's
-- usage), so the bridge now posts them per TASK_SCORED and the tracking service sums them per
-- run. Operator-only tracking tables (V236) — additive, default 0.

ALTER TABLE benchmark_task_result
    ADD COLUMN IF NOT EXISTS input_tokens  BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS output_tokens BIGINT NOT NULL DEFAULT 0;

ALTER TABLE benchmark_run
    ADD COLUMN IF NOT EXISTS input_tokens  BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS output_tokens BIGINT NOT NULL DEFAULT 0;
