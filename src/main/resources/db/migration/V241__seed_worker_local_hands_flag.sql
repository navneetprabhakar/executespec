-- =====================================================================
-- V241: Seed worker.local-hands.enabled = false (DEFAULT OFF).
--
-- RL-2026-06-015 follow-up — exposes the CLI local-hands execution switch to
-- the feature-flag console. When ON, POST /api/runs with localExecution=true
-- accepts LOCAL mode and the worker proxies tool calls back to the CLI. When
-- OFF, the submit endpoint rejects localExecution=true before quota/billing so
-- a local run cannot silently fall back to remote execution.
-- =====================================================================
INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description) VALUES
    ('worker.local-hands.enabled', 'false', 'BOOLEAN', 'PLATFORM',
     'RL-2026-06-015 — enable CLI local-hands execution: server brain with worker tool calls proxied to the client working tree. Default OFF; when OFF localExecution=true submit requests are rejected fail-closed.')
ON CONFLICT (flag_key) DO NOTHING;
