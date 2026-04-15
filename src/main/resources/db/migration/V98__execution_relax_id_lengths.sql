-- V98 — relax VARCHAR(36) id + fk columns on execution_* tables to VARCHAR(64).
-- Same class as V94 (project_chat_message): admin user IDs can exceed 36 chars,
-- so every table that stores a user/org/project reference needs the wider column.

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
