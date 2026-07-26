-- Durable sandbox teardown intent and retry evidence.
--
-- A failed container removal must not make the lifecycle row terminal: the
-- runtime may still be live. Persist the intended terminal outcome before
-- destruction, then leave RELEASING rows immediately reclaimable until a
-- reconciler verifies that the runtime is absent and publishes that outcome.

ALTER TABLE run_sandbox
    ADD COLUMN cleanup_target VARCHAR(24),
    ADD COLUMN cleanup_failure_class VARCHAR(48),
    ADD COLUMN cleanup_failure_detail VARCHAR(2000),
    ADD COLUMN cleanup_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN cleanup_last_error VARCHAR(2000);

-- All three sandbox flags were seeded false, but preserve an honest recovery
-- path if an operator exercised the dark lifecycle before this migration.
UPDATE run_sandbox
SET cleanup_target = 'FAILED',
    cleanup_failure_class = 'LEGACY_RELEASE_INTENT_UNKNOWN',
    cleanup_failure_detail = 'release intent predated durable cleanup metadata'
WHERE state = 'RELEASING';

ALTER TABLE run_sandbox
    ADD CONSTRAINT ck_run_sandbox_cleanup_target CHECK (
        cleanup_target IS NULL
        OR cleanup_target IN ('TERMINATED', 'CANCELLED', 'EXPIRED', 'FAILED')
    ),
    ADD CONSTRAINT ck_run_sandbox_releasing_has_target CHECK (
        state <> 'RELEASING' OR cleanup_target IS NOT NULL
    ),
    ADD CONSTRAINT ck_run_sandbox_cleanup_failure CHECK (
        (cleanup_target = 'FAILED' AND cleanup_failure_class IS NOT NULL)
        OR (cleanup_target IS DISTINCT FROM 'FAILED' AND cleanup_failure_class IS NULL)
    ),
    ADD CONSTRAINT ck_run_sandbox_cleanup_attempts CHECK (cleanup_attempts >= 0);

COMMENT ON COLUMN run_sandbox.cleanup_target IS
    'Terminal lifecycle state to publish only after runtime destruction is verified.';
COMMENT ON COLUMN run_sandbox.cleanup_attempts IS
    'Count of failed runtime-destruction attempts; RELEASING remains reclaimable for retry.';
COMMENT ON COLUMN run_sandbox.cleanup_last_error IS
    'Bounded, redacted error from the latest failed runtime-destruction attempt.';
