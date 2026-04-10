-- V71: Triage outcome tracking for single-shot → multi-task escalation feedback loop.
-- Records whether the initial triage classification was correct, and which threshold
-- (if any) was exceeded, enabling data-driven triage model improvements.

CREATE TABLE IF NOT EXISTS triage_outcome (
    id                      UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id                  UUID          NOT NULL REFERENCES pipeline_run(id) ON DELETE CASCADE,
    original_classification VARCHAR(20)   NOT NULL,  -- SINGLE_SHOT or MULTI_TASK
    escalated               BOOLEAN       NOT NULL DEFAULT false,
    escalation_reason       TEXT,                     -- which threshold(s) triggered
    iterations              INT,                      -- total tool calls across all tasks
    files_written           INT,                      -- distinct files written
    compile_errors          INT,                      -- compile error count
    duration_seconds        INT,                      -- wall-clock execution time
    outcome_status          VARCHAR(20),              -- COMPLETE, PARTIAL, FAILED
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_triage_outcome_run_id   ON triage_outcome(run_id);
CREATE INDEX IF NOT EXISTS idx_triage_outcome_escalated ON triage_outcome(escalated);
CREATE INDEX IF NOT EXISTS idx_triage_outcome_created  ON triage_outcome(created_at DESC);
