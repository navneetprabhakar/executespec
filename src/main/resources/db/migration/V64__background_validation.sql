-- V64: background_validation — durable tracking for post-COMPLETE validation thread
-- Replaces in-memory ConcurrentHashSet with DB-backed state so restarts don't lose track.

CREATE TABLE background_validation (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id        VARCHAR(255)  NOT NULL REFERENCES pipeline_run(run_id) ON DELETE CASCADE,
    status        VARCHAR(20)   NOT NULL DEFAULT 'PENDING',   -- PENDING, RUNNING, COMPLETED, FAILED
    current_phase VARCHAR(50),                                -- TEST_GEN, REVIEW, RE_VALIDATE, REPORT
    phase_results JSONB         NOT NULL DEFAULT '{}',
    attempt       INT           NOT NULL DEFAULT 1,
    max_attempts  INT           NOT NULL DEFAULT 3,
    started_at    TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ,
    last_error    TEXT,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_background_validation_run_id ON background_validation (run_id);
CREATE INDEX idx_background_validation_status ON background_validation (status);
CREATE INDEX idx_background_validation_started_at ON background_validation (started_at) WHERE status = 'RUNNING';
