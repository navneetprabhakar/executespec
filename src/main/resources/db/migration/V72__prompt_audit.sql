-- V72: Prompt version tracking
-- Adds prompt_hash to agent_task for per-task prompt traceability,
-- and a prompt_audit table for per-run prompt analytics.

ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS prompt_hash VARCHAR(12);

CREATE TABLE IF NOT EXISTS prompt_audit (
    id          BIGSERIAL PRIMARY KEY,
    run_id      UUID NOT NULL,
    agent_type  VARCHAR(30) NOT NULL,
    prompt_hash VARCHAR(12) NOT NULL,
    char_count  INT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_prompt_audit_hash   ON prompt_audit(prompt_hash);
CREATE INDEX IF NOT EXISTS idx_prompt_audit_run_id ON prompt_audit(run_id);
