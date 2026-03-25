-- Add LLM token tracking columns to test_plan for cost breakdown
ALTER TABLE test_plan ADD COLUMN planner_input_tokens   INTEGER DEFAULT 0;
ALTER TABLE test_plan ADD COLUMN planner_output_tokens  INTEGER DEFAULT 0;
ALTER TABLE test_plan ADD COLUMN worker_input_tokens    INTEGER DEFAULT 0;
ALTER TABLE test_plan ADD COLUMN worker_output_tokens   INTEGER DEFAULT 0;
ALTER TABLE test_plan ADD COLUMN report_input_tokens    INTEGER DEFAULT 0;
ALTER TABLE test_plan ADD COLUMN report_output_tokens   INTEGER DEFAULT 0;
ALTER TABLE test_plan ADD COLUMN planner_provider       VARCHAR(50);
ALTER TABLE test_plan ADD COLUMN planner_model          VARCHAR(100);
ALTER TABLE test_plan ADD COLUMN worker_provider        VARCHAR(50);
ALTER TABLE test_plan ADD COLUMN worker_model           VARCHAR(100);
