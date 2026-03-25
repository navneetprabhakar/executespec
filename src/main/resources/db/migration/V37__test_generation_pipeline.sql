-- Test generation pipeline: parallel test generation from spec

-- Test plan: one per run, tracks the overall test gen pipeline state
CREATE TABLE test_plan (
    id              BIGSERIAL PRIMARY KEY,
    run_id          TEXT NOT NULL REFERENCES pipeline_run(run_id) ON DELETE CASCADE,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    plan_json       JSONB,
    conventions_doc TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_test_plan_run_id ON test_plan(run_id);

-- Individual test tasks (parallel to agent_task)
CREATE TABLE test_task (
    id                  BIGSERIAL PRIMARY KEY,
    test_plan_id        BIGINT NOT NULL REFERENCES test_plan(id) ON DELETE CASCADE,
    task_name           VARCHAR(255) NOT NULL,
    task_description    TEXT NOT NULL,
    target_entity       VARCHAR(100),
    test_category       VARCHAR(50) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    generated_code      TEXT,
    compilation_error   TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_test_task_plan_id ON test_task(test_plan_id);
CREATE INDEX idx_test_task_status ON test_task(status);

-- Test execution results (one row per test method executed)
CREATE TABLE test_result (
    id              BIGSERIAL PRIMARY KEY,
    test_plan_id    BIGINT NOT NULL REFERENCES test_plan(id) ON DELETE CASCADE,
    test_task_id    BIGINT NOT NULL REFERENCES test_task(id) ON DELETE CASCADE,
    test_method     VARCHAR(255) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    failure_message TEXT,
    expected_value  TEXT,
    actual_value    TEXT,
    suggestion      TEXT,
    duration_ms     INTEGER,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_test_result_plan_id ON test_result(test_plan_id);
CREATE INDEX idx_test_result_status ON test_result(status);
