-- V92 — per-Launch AI provider override. Default is the project's ai_provider;
-- user can override at Launch time via the dropdown in ExecutionSetup.
ALTER TABLE execution_run
    ADD COLUMN IF NOT EXISTS ai_provider VARCHAR(20);
