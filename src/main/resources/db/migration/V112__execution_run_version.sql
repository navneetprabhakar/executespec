-- V112 — Bug #3 root-cause fix via optimistic locking on ExecutionRun.
--
-- Scenario:
--   Planner /plan endpoint is async; the orchestrator loads the run, generates
--   the plan, then writes it back. If the user's /plan/approve request lands
--   BETWEEN load and save, a last-write-wins PUT can clobber the plan or the
--   planApprovedAt timestamp — depending on which thread saves last.
--
-- Fix:
--   Add a JPA @Version column so concurrent saves race-condition each other via
--   Hibernate's optimistic lock. The loser gets ObjectOptimisticLockingFailureException,
--   which we translate to 409 Conflict at the controller boundary (done in code).
--
-- Safety:
--   - Default 0 is fine for backfill.
--   - NOT NULL so the JPA side never has to think about the null case.
--   - Migration is additive; no data rewrites.

ALTER TABLE execution_run
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN execution_run.version IS
    'JPA @Version for optimistic locking. Incremented on every row update.';
