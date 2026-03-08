-- Complexity column already exists on agent_task, but ensure it's present
ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS complexity VARCHAR(20) DEFAULT 'MEDIUM';

-- Create model task metrics table for tracking model performance per task type
CREATE TABLE IF NOT EXISTS model_task_metric (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(100),
    task_id VARCHAR(100),
    model_name VARCHAR(100) NOT NULL,
    provider VARCHAR(50) NOT NULL,
    tier VARCHAR(20) NOT NULL,
    complexity VARCHAR(20) NOT NULL,
    language VARCHAR(20),
    attempt INT NOT NULL DEFAULT 1,
    validation_passed BOOLEAN NOT NULL DEFAULT FALSE,
    error_count INT DEFAULT 0,
    first_error_gate VARCHAR(50),
    duration_ms BIGINT DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_model_task_metric_model ON model_task_metric (model_name, complexity);
CREATE INDEX IF NOT EXISTS idx_model_task_metric_run ON model_task_metric (run_id);
