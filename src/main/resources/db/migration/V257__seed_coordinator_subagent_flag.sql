-- =====================================================================
-- V257: Seed the deterministic coordinator-triggered subagent flag.
--
-- Automatic SCOUT/VERIFIER execution is fail-closed and operator opt-in.
-- The endpoint still requires an explicit automatic=true + COORDINATOR trigger
-- and repeats eligibility before reserving a child. Existing installations keep
-- the behavior disabled until an operator enables this flag.
-- =====================================================================
INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'agent.subagents.coordinator-auto.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'Enable deterministic coordinator-triggered read-only SCOUT/VERIFIER execution. Default OFF; requires explicit request opt-in and fresh eligibility.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET description = EXCLUDED.description;
