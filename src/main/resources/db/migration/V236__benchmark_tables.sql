-- V236: closed-loop in-app benchmark (Terminal-Bench) — RL-2026-06.
-- ExecuteSpec triggers + tracks TB runs in-app; Harbor is orchestrated under the hood (it owns the
-- task containers, the pass/fail verifier, and the official `harbor upload`). Each task is also a
-- pipeline_run (benchmark_task_result.es_run_id links it). Operator-only surface
-- (SUPER_ADMIN + @Profile {local,dev,benchmark}). See benchmark/ package + the design doc
-- docs/plans/benchmark-closed-loop-2026-06-09.md.

CREATE TABLE benchmark_run (
    id                 VARCHAR(64)   PRIMARY KEY,
    suite              VARCHAR(40)   NOT NULL,            -- TERMINAL_BENCH
    dataset            VARCHAR(120)  NOT NULL,
    provider           VARCHAR(40)   NOT NULL,
    model              VARCHAR(120)  NOT NULL,
    k                  INTEGER       NOT NULL DEFAULT 1,  -- attempts per task
    total_tasks        INTEGER       NOT NULL DEFAULT 0,
    resolved_tasks     INTEGER       NOT NULL DEFAULT 0,
    status             VARCHAR(20)   NOT NULL DEFAULT 'PENDING',  -- PENDING/RUNNING/DONE/FAILED/CANCELLED
    cost_usd           NUMERIC(12,6) NOT NULL DEFAULT 0,
    harbor_job_name    VARCHAR(120),
    created_by_user_id VARCHAR(64),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    started_at         TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ,
    error              TEXT
);

CREATE TABLE benchmark_task_result (
    id               VARCHAR(64)   PRIMARY KEY,
    benchmark_run_id VARCHAR(64)   NOT NULL REFERENCES benchmark_run(id) ON DELETE CASCADE,
    task_name        VARCHAR(200)  NOT NULL,
    attempt          INTEGER       NOT NULL DEFAULT 1,
    reward           INTEGER       NOT NULL DEFAULT 0,    -- 1=pass, 0=fail
    es_run_id        VARCHAR(64),                         -- the pipeline_run that executed this task
    cost_usd         NUMERIC(12,6) NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_benchmark_task UNIQUE (benchmark_run_id, task_name, attempt)
);

CREATE INDEX idx_benchmark_task_run ON benchmark_task_result (benchmark_run_id);
CREATE INDEX idx_benchmark_run_status ON benchmark_run (status, created_at DESC);
