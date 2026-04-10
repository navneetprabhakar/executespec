-- V73: Plan effectiveness tracking
-- Records planned vs actual outcomes per task for failure pattern analysis.
-- Used by FailurePatternService to inject adaptive warnings into the planner.

CREATE TABLE IF NOT EXISTS plan_effectiveness (
    id                  BIGSERIAL PRIMARY KEY,
    run_id              VARCHAR(255) REFERENCES pipeline_run(run_id) ON DELETE CASCADE,
    task_id             VARCHAR(100),
    planned_complexity  VARCHAR(10),
    planned_file_count  INT,
    actual_iterations   INT,
    actual_status       VARCHAR(20),
    failure_reason      VARCHAR(100),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_plan_eff_run_id ON plan_effectiveness(run_id);
CREATE INDEX IF NOT EXISTS idx_plan_eff_status ON plan_effectiveness(actual_status);
