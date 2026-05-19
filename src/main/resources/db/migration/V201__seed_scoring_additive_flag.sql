-- =====================================================================
-- V201: Seed the `scoring.additive-v2` feature flag (default ON).
--
-- Ships alongside the 2026-05-19 additive scoring rebuild in
-- ValidationReportService.computeVerifiedScoreAdditive. When this flag is
-- on, the persisted `verifiedScore` comes from the additive formula:
--
--   CODE_DELIVERED (30) + BUILD (25) + TESTS (20) + REVIEWER (20) + CRITICALS (5)
--
-- evaluated only over the signals that fired, with a hard 0-floor when
-- the agent shipped zero files.
--
-- Flip OFF (admin: PUT /api/feature-flags/scoring.additive-v2) to revert
-- to the legacy multiplicative formula for the next batch of runs while
-- we investigate. Existing rows are NOT recomputed — the column is set at
-- generate-and-persist time, so a flag flip only affects new runs.
-- =====================================================================

INSERT INTO feature_flag (flag_key, flag_value, flag_type, scope, description, created_at, updated_at)
VALUES (
    'scoring.additive-v2',
    'true',
    'BOOLEAN',
    'PLATFORM',
    'Additive verifiedScore: CODE_DELIVERED+BUILD+TESTS+REVIEWER+CRITICALS, weighted-avg over measured signals. Replaces the 2026-05-08 multiplicative cliff.',
    NOW(),
    NOW()
)
ON CONFLICT (flag_key) DO UPDATE
SET flag_value  = 'true',
    description = EXCLUDED.description,
    updated_at  = NOW();
