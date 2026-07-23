-- V297: restart-safe benchmark progress, trajectory provenance and submission readiness.
-- Active attempts are a separate relation because benchmark runners execute concurrently; a
-- single "current task" column would discard evidence whenever concurrency is greater than one.

ALTER TABLE benchmark_run
    ADD COLUMN IF NOT EXISTS active_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS completed_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_progress_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS eta_seconds BIGINT,
    ADD COLUMN IF NOT EXISTS submission_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS submission_readiness VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUIRED',
    ADD COLUMN IF NOT EXISTS submission_readiness_reason VARCHAR(500);

ALTER TABLE benchmark_task_result
    ADD COLUMN IF NOT EXISTS failure_classification VARCHAR(40) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN IF NOT EXISTS trajectory_location VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS trajectory_sha256 VARCHAR(64);

CREATE TABLE IF NOT EXISTS benchmark_active_attempt (
    id                  VARCHAR(64) PRIMARY KEY,
    benchmark_run_id    VARCHAR(64) NOT NULL REFERENCES benchmark_run(id) ON DELETE CASCADE,
    task_name           VARCHAR(200) NOT NULL,
    attempt             INTEGER NOT NULL,
    started_at          TIMESTAMPTZ NOT NULL,
    last_progress_at    TIMESTAMPTZ NOT NULL,
    stale_after         TIMESTAMPTZ NOT NULL,
    eta_seconds         BIGINT,
    trajectory_location VARCHAR(1000),
    trajectory_sha256  VARCHAR(64),
    CONSTRAINT uq_benchmark_active_attempt UNIQUE (benchmark_run_id, task_name, attempt)
);

CREATE INDEX IF NOT EXISTS idx_benchmark_active_attempt_run
    ON benchmark_active_attempt (benchmark_run_id, started_at);

ALTER TABLE benchmark_run
    ADD CONSTRAINT ck_benchmark_run_progress_counts
        CHECK (active_attempt_count >= 0 AND completed_attempts >= 0 AND (eta_seconds IS NULL OR eta_seconds >= 0)),
    ADD CONSTRAINT ck_benchmark_submission_readiness
        CHECK (submission_readiness IN ('NOT_REQUIRED', 'IN_PROGRESS', 'BLOCKED', 'READY', 'SUBMITTED'));

ALTER TABLE benchmark_task_result
    ADD CONSTRAINT ck_benchmark_failure_classification
        CHECK (failure_classification IN ('NONE', 'TIMEOUT', 'INFRASTRUCTURE', 'VERIFIER', 'AGENT_OR_TEST_FAILURE', 'UNKNOWN')),
    ADD CONSTRAINT ck_benchmark_task_trajectory_pair
        CHECK (
            (trajectory_location IS NULL AND trajectory_sha256 IS NULL)
            OR (trajectory_location IS NOT NULL AND trajectory_location <> ''
                AND trajectory_sha256 ~ '^[0-9a-f]{64}$')
        );

ALTER TABLE benchmark_active_attempt
    ADD CONSTRAINT ck_benchmark_active_eta
        CHECK (eta_seconds IS NULL OR eta_seconds >= 0),
    ADD CONSTRAINT ck_benchmark_active_heartbeat
        CHECK (stale_after >= last_progress_at),
    ADD CONSTRAINT ck_benchmark_active_trajectory_pair
        CHECK (
            (trajectory_location IS NULL AND trajectory_sha256 IS NULL)
            OR (trajectory_location IS NOT NULL AND trajectory_location <> ''
                AND trajectory_sha256 ~ '^[0-9a-f]{64}$')
        );

UPDATE benchmark_run
SET completed_attempts = (
    SELECT COUNT(*) FROM benchmark_task_result r WHERE r.benchmark_run_id = benchmark_run.id
);
