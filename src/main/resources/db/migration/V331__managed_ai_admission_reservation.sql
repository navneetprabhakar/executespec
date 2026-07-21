-- RR-1664 (2026-07-21) — serialized, reserving managed-credit RUN ADMISSION.
--
-- ManagedAiUsageService.assertCanStartRun is the live pre-async spend gate at POST /api/runs
-- (RR-186's assertWithinLimits has no production caller). It read the usage period WITHOUT a row
-- lock and WITHOUT reserving anything, then threw only if the recomputed routing state was already
-- EXHAUSTED. N concurrent submissions at the last credit therefore each observed the same
-- remaining allowance and were all admitted, after which every one of them started spending.
--
-- reserved_ai_credits is the RR-321 shape applied one layer up: a tighten-only reservation counter
-- committed inside the same pessimistically-locked transaction that evaluates the state. Admission
-- reserves the minimum credit a successful managed call can debit (1), so the last credit admits
-- exactly one run. The counter is reconciled as max(reserved, ai_credits_used) on every subsequent
-- admission, so real usage subsumes the reservation and nothing leaks when a run never spends —
-- the same self-healing property V316 relies on. This is a MINIMUM reservation, not an estimate of
-- the run's cost: a run's total spend is not knowable at submission, and inventing a worst-case
-- estimate here would deny admission far below the real ceiling.
ALTER TABLE managed_ai_usage_period
  ADD COLUMN IF NOT EXISTS reserved_ai_credits BIGINT NOT NULL DEFAULT 0;

-- Existing rows: seed from the credits already debited so a period created before this migration
-- does not receive a second allowance on its first post-migration admission.
UPDATE managed_ai_usage_period
SET reserved_ai_credits = ai_credits_used
WHERE reserved_ai_credits < ai_credits_used;

ALTER TABLE managed_ai_usage_period
  ADD CONSTRAINT managed_ai_usage_period_reserved_credits_nonneg
  CHECK (reserved_ai_credits >= 0);
