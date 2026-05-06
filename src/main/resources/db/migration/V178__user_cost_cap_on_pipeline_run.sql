-- GA-readiness P3.2 (2026-05-06) — customer-supplied "Stop at $X" cost ceiling.
--
-- The platform-derived `hard_cap_usd` (credits × $5 × 1.5) is a SILENT safety
-- net that fires only on runaway behaviour. The new `user_cost_cap_usd` column
-- carries an OPTIONAL tighter ceiling supplied by the customer at submission
-- time via `RunRequest.userCostCap`.
--
-- `RunOrchestrator.persistEstimate` reads this column and clamps
-- `hard_cap_usd = min(platformCap, userCostCap)` so the WorkerAgent budget
-- guard fires at the customer's chosen ceiling, not the platform's.
--
-- Validation (DTO-side): 0.10 ≤ userCostCap ≤ 1000.00. Unbounded values are
-- rejected before reaching the DB so a typo can't accidentally authorise a
-- multi-thousand-dollar run.
--
-- Idempotent: IF NOT EXISTS guard.

ALTER TABLE pipeline_run
    ADD COLUMN IF NOT EXISTS user_cost_cap_usd DOUBLE PRECISION;

COMMENT ON COLUMN pipeline_run.user_cost_cap_usd IS
    'GA-readiness P3.2 — optional customer-supplied tighter cost ceiling in USD. NULL = use platform-derived hard_cap_usd unchanged. Set at submission time via RunRequest.userCostCap; persistEstimate clamps hard_cap_usd to min(platformCap, this).';
