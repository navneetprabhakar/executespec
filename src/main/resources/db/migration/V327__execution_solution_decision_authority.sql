-- V327 — RR-530: solution decisions carry no legal-state, one-shot or actor evidence.
--
-- `processDecision` accepted SKIP / RETRY / FIX / ATTEMPT_ANYWAY for a solution in ANY state and
-- `signalManualFixDone` marked any matching solution DONE without proving a prior FIX decision, so a
-- stale, repeated or crafted call could skip QUEUED/RUNNING work, retry an already-completed
-- solution, or resume the parent launch without the human decision the gate represents. Two
-- concurrent RETRY calls could each launch a paid child run.
--
-- The transition is now an atomic conditional UPDATE from one exact prior state (see
-- ExecutionSolutionRepository.claimDecision / claimManualFixDone), which needs somewhere to record
-- who decided what. FIX also gains its own explicit waiting state, AWAITING_MANUAL_FIX, so
-- manual-ready cannot be replayed against a solution that was never put into manual-fix mode.

ALTER TABLE execution_solution
  ADD COLUMN IF NOT EXISTS last_decision_action VARCHAR(30),
  ADD COLUMN IF NOT EXISTS last_decision_by_user_id VARCHAR(36),
  ADD COLUMN IF NOT EXISTS last_decision_at TIMESTAMP;

COMMENT ON COLUMN execution_solution.last_decision_action IS
  'RR-530: last human execution decision applied to this solution (SKIP/RETRY/FIX/ATTEMPT_ANYWAY/MANUAL_READY). Written only by the caller that won the conditional state transition.';
COMMENT ON COLUMN execution_solution.last_decision_by_user_id IS
  'RR-530: app_user.id of the actor whose decision won the transition. NULL on legacy rows decided before this migration.';
COMMENT ON COLUMN execution_solution.last_decision_at IS
  'RR-530: when the winning decision transition committed.';
