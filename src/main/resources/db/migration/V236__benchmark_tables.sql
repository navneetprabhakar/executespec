-- V236: in-app benchmark tracking (Terminal-Bench) — RL-2026-06.
-- ExecuteSpec TRACKS a TB run + its submission + events. The verifier (Harbor) runs externally and
-- PUBLISHES events; ExecuteSpec ingests → stores → re-broadcasts (SSE) → shows them in-login. It does
-- NOT run or reimplement Harbor (Harbor owns the verifier + the official `harbor upload`).
-- Each scored task can link its pipeline_run (benchmark_task_result.es_run_id). Operator-only surface
-- (SUPER_ADMIN + @Profile {local,dev,benchmark}). Design: docs/plans/benchmark-closed-loop-2026-06-09.md.

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
    submission_status  VARCHAR(20),                       -- NULL/SUBMITTING/SUBMITTED/FAILED
    leaderboard_url    VARCHAR(300),
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

-- The event stream the verifier publishes (run progress + submission); persisted for replay + SSE.
CREATE TABLE benchmark_event (
    id               VARCHAR(64)  PRIMARY KEY,
    benchmark_run_id VARCHAR(64)  NOT NULL REFERENCES benchmark_run(id) ON DELETE CASCADE,
    seq              INTEGER      NOT NULL,               -- per-run ordering
    type             VARCHAR(40)  NOT NULL,               -- RUN_STARTED/TASK_SCORED/RUN_PROGRESS/RUN_DONE/SUBMISSION_STARTED/SUBMITTED/SUBMISSION_FAILED
    message          TEXT,
    payload          TEXT,                                -- free-form JSON
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_benchmark_task_run ON benchmark_task_result (benchmark_run_id);
CREATE INDEX idx_benchmark_run_status ON benchmark_run (status, created_at DESC);
CREATE INDEX idx_benchmark_event_run ON benchmark_event (benchmark_run_id, seq);
