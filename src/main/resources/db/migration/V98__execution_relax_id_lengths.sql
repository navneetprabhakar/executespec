-- V98 — relax VARCHAR(36) id + fk columns on execution_* tables to VARCHAR(64).
-- Same class as V94 (project_chat_message): admin user IDs can exceed 36 chars,
-- so every table that stores a user/org/project reference needs the wider column.
--
-- Postgres blocks ALTER TYPE on columns referenced by RLS policies — drop the
-- tenant_isolation policies first, alter, then recreate with identical bodies.

DROP POLICY IF EXISTS tenant_isolation ON execution_run;
DROP POLICY IF EXISTS tenant_isolation ON execution_solution;
DROP POLICY IF EXISTS tenant_isolation ON execution_solution_run;

ALTER TABLE execution_run                ALTER COLUMN id           TYPE VARCHAR(64);
ALTER TABLE execution_run                ALTER COLUMN project_id   TYPE VARCHAR(64);
ALTER TABLE execution_run                ALTER COLUMN org_id       TYPE VARCHAR(64);
ALTER TABLE execution_run                ALTER COLUMN created_by   TYPE VARCHAR(64);

ALTER TABLE execution_solution           ALTER COLUMN id               TYPE VARCHAR(64);
ALTER TABLE execution_solution           ALTER COLUMN execution_run_id TYPE VARCHAR(64);
ALTER TABLE execution_solution           ALTER COLUMN pipeline_run_id  TYPE VARCHAR(64);

ALTER TABLE execution_solution_repo      ALTER COLUMN id          TYPE VARCHAR(64);
ALTER TABLE execution_solution_repo      ALTER COLUMN solution_id TYPE VARCHAR(64);

ALTER TABLE execution_solution_run       ALTER COLUMN id              TYPE VARCHAR(64);
ALTER TABLE execution_solution_run       ALTER COLUMN solution_id     TYPE VARCHAR(64);
ALTER TABLE execution_solution_run       ALTER COLUMN pipeline_run_id TYPE VARCHAR(64);

ALTER TABLE execution_context_handover   ALTER COLUMN id              TYPE VARCHAR(64);
ALTER TABLE execution_context_handover   ALTER COLUMN from_solution_id TYPE VARCHAR(64);
ALTER TABLE execution_context_handover   ALTER COLUMN to_solution_id   TYPE VARCHAR(64);

-- Recreate the RLS policies with their original bodies.
CREATE POLICY tenant_isolation ON execution_run
    USING (
        current_setting('app.current_org_id', true) = ''
        OR current_setting('app.current_org_id', true) IS NULL
        OR org_id IS NULL
        OR org_id::text = current_setting('app.current_org_id', true)
    );

CREATE POLICY tenant_isolation ON execution_solution
    USING (
        current_setting('app.current_org_id', true) = ''
        OR current_setting('app.current_org_id', true) IS NULL
        OR execution_run_id::text IN (
            SELECT execution_run.id
              FROM execution_run
             WHERE execution_run.org_id IS NULL
                OR execution_run.org_id::text = current_setting('app.current_org_id', true)
        )
    );

CREATE POLICY tenant_isolation ON execution_solution_run
    USING (
        current_setting('app.current_org_id', true) = ''
        OR current_setting('app.current_org_id', true) IS NULL
        OR solution_id::text IN (
            SELECT s.id
              FROM execution_solution s
              JOIN execution_run r ON s.execution_run_id::text = r.id::text
             WHERE r.org_id IS NULL
                OR r.org_id::text = current_setting('app.current_org_id', true)
        )
    );
