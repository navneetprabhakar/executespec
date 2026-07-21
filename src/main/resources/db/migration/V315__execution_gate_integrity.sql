-- V315 — execution-gate integrity (RR-905, RR-1217).
--
-- 1. execution_run boot-recovery fencing (RR-905).
--    Before this, ExecutionOrchestrator.onApplicationReady selected every EXECUTING launch on
--    EVERY instance's ApplicationReadyEvent and re-dispatched all of them, because the candidate
--    query carried no owner and no lease. A rolling deploy or a scale-out therefore ran the same
--    LLM, Git, pull-request and cost side effects concurrently for one launch. These columns are
--    the durable lease: recovery_attempts is the compare-and-set counter (exactly one winner per
--    attempt), recovering_instance records the owner, and recovery_heartbeat bounds takeover.
--    Mirrors the V2 pipeline_run resume-lease contract used by claimCrashResume.
ALTER TABLE execution_run
    ADD COLUMN IF NOT EXISTS recovery_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS recovering_instance VARCHAR(128),
    ADD COLUMN IF NOT EXISTS recovery_heartbeat TIMESTAMPTZ;

-- Boot recovery scans non-terminal rows by status + quiet period; this keeps that scan cheap.
CREATE INDEX IF NOT EXISTS idx_execution_run_recovery_scan
    ON execution_run (status, updated_at)
    WHERE status NOT IN ('COMPLETE', 'FAILED', 'CANCELLED', 'ABORTED');

-- 2. pipeline_run.tdd_mode (RR-1217).
--    TDD mode was a pipeline-thread parameter only and was never persisted, so nothing that
--    recreates a run from its durable row could inherit it. POST /api/runs/{id}/fork therefore
--    hardcoded false and produced a run that behaved differently from the one it claimed to copy.
ALTER TABLE pipeline_run
    ADD COLUMN IF NOT EXISTS tdd_mode BOOLEAN NOT NULL DEFAULT FALSE;
