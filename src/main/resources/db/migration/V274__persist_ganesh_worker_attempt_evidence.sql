ALTER TABLE pipeline_run ADD COLUMN ganesh_target_files_json TEXT;

CREATE TABLE ganesh_worker_attempt (
    id VARCHAR(256) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    attempt INTEGER NOT NULL,
    provider VARCHAR(32) NOT NULL,
    model VARCHAR(128) NOT NULL,
    verification_status VARCHAR(40) NOT NULL,
    failure_stage VARCHAR(40) NOT NULL,
    evidence_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ganesh_worker_attempt UNIQUE (run_id, task_id, attempt)
);

CREATE INDEX idx_ganesh_worker_attempt_run ON ganesh_worker_attempt(run_id, task_id, attempt);

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'worker.ganesh.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'Allow bounded five-language single-file Ganesh worker routing with mandatory E2. Default OFF.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET description = EXCLUDED.description;
