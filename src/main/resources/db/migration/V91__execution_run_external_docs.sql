-- V91 — optional external context links on the ExecutionRun (Launch flow)
ALTER TABLE execution_run
    ADD COLUMN IF NOT EXISTS confluence_url VARCHAR(500),
    ADD COLUMN IF NOT EXISTS figma_url      VARCHAR(500);
