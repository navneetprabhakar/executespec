-- V19: Per-agent token tracking on pipeline_run for accurate cost/model breakdown.
-- Previously planner/reviewer/compiler tokens were lumped into totalInputTokens/totalOutputTokens
-- with no way to attribute them to specific models in the UI.

ALTER TABLE pipeline_run ADD COLUMN planner_input_tokens   INTEGER DEFAULT 0;
ALTER TABLE pipeline_run ADD COLUMN planner_output_tokens  INTEGER DEFAULT 0;
ALTER TABLE pipeline_run ADD COLUMN planner_provider       VARCHAR(255);
ALTER TABLE pipeline_run ADD COLUMN planner_actual_model   VARCHAR(255);

ALTER TABLE pipeline_run ADD COLUMN reviewer_input_tokens  INTEGER DEFAULT 0;
ALTER TABLE pipeline_run ADD COLUMN reviewer_output_tokens INTEGER DEFAULT 0;
ALTER TABLE pipeline_run ADD COLUMN reviewer_provider      VARCHAR(255);
ALTER TABLE pipeline_run ADD COLUMN reviewer_actual_model  VARCHAR(255);

ALTER TABLE pipeline_run ADD COLUMN compiler_input_tokens  INTEGER DEFAULT 0;
ALTER TABLE pipeline_run ADD COLUMN compiler_output_tokens INTEGER DEFAULT 0;
ALTER TABLE pipeline_run ADD COLUMN compiler_provider      VARCHAR(255);
ALTER TABLE pipeline_run ADD COLUMN compiler_actual_model  VARCHAR(255);
