-- V254: benchmark task outcome/reason visibility.
--
-- Reward remains the source of truth for resolved%; these fields explain whether a reward=0 was a
-- verifier failure, agent failure, timeout, bridge/container infra issue, or plain task failure.

ALTER TABLE benchmark_task_result
    ADD COLUMN IF NOT EXISTS outcome VARCHAR(40),
    ADD COLUMN IF NOT EXISTS outcome_reason VARCHAR(500);
