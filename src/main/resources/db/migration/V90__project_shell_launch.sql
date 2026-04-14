-- V90 — Project Shell + Launch restructure (v2.4.0)
--
-- 1. Premium tier expansion: MAX joins ENTERPRISE as a tier that unlocks the
--    Execution View / Project Shell. Execution View is a PREMIUM feature.
-- 2. Multi-run solutions: an ExecutionSolution now groups 1..N V2 pipeline runs
--    (initial + retries + manual-fix runs). The legacy single `pipeline_run_id`
--    column on execution_solution becomes the "latest run" pointer; full
--    history lives in execution_solution_run (this table).

UPDATE tier_config
   SET execution_view_enabled = TRUE
 WHERE tier IN ('ENTERPRISE', 'MAX');

CREATE TABLE execution_solution_run (
    id                  VARCHAR(36)  PRIMARY KEY,
    solution_id         VARCHAR(36)  NOT NULL REFERENCES execution_solution(id) ON DELETE CASCADE,
    pipeline_run_id     VARCHAR(36)  NOT NULL,
    attempt_number      INT          NOT NULL DEFAULT 1,
    terminal_status     VARCHAR(30),           -- COMPLETE | PARTIAL | FAILED | CANCELLED | ABORTED | null (still running)
    input_tokens        BIGINT,
    output_tokens       BIGINT,
    cost_usd            NUMERIC(10,4),
    started_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMP,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_exec_sol_run_solution ON execution_solution_run(solution_id);
CREATE INDEX idx_exec_sol_run_pipeline ON execution_solution_run(pipeline_run_id);
CREATE UNIQUE INDEX uq_exec_sol_run_attempt
    ON execution_solution_run(solution_id, attempt_number);

-- RLS via parent ExecutionSolution / ExecutionRun.
ALTER TABLE execution_solution_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution_solution_run FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON execution_solution_run
    USING (
        current_setting('app.current_org_id', true) = ''
        OR current_setting('app.current_org_id', true) IS NULL
        OR solution_id IN (
            SELECT s.id FROM execution_solution s
            JOIN execution_run r ON s.execution_run_id = r.id
            WHERE r.org_id IS NULL
               OR r.org_id = current_setting('app.current_org_id', true)
        )
    );
