-- V105: Support "continue on PR" follow-up executions.
-- When an execution addresses review comments on an existing PR, these columns
-- link back to the original execution and identify the target PR so the system
-- pushes follow-up commits to the same branch instead of opening a new PR.
ALTER TABLE execution_run ADD COLUMN source_execution_id VARCHAR(255);
ALTER TABLE execution_run ADD COLUMN continue_pr_number INTEGER;
ALTER TABLE execution_run ADD COLUMN continue_pr_repo VARCHAR(255);
