-- =====================================================================
-- V227: Seed the verifyBoot flags (BOTH DEFAULT OFF).
--
-- RL-2026-05-066 — preview Phase 1 keystone: the agent authors a
-- .agent-context/run-recipe.json and the platform BUILDS + BOOTS the app
-- in a hardened sandbox + health-checks it, feeding a deterministic verdict
-- (BootHealthEvaluator) into the verifiedScore BUILD slot — replacing the
-- LLM's guess about whether the app actually runs.
--
--   worker.verify-boot.enabled
--     Gates the verifyBoot worker tool (VerifyBootTool) + its prompt
--     directive (SequentialExecutor) + the RunOrchestrator report-time
--     population. When OFF: the tool returns {available:false}, no prompt
--     change, no boot at scoring time → the BUILD slot keeps its
--     compile-only signal. Production is byte-identical until flipped.
--
--   preview.buildpack.enabled
--     Capability gate for the build-and-boot engine (PreviewBootEngine,
--     preview Phase 0 — buildpack adapter + isolated deny-by-default-egress
--     network + dedicated least-privilege service account + CodeAbuseScanner
--     pre-build). Until a PreviewBootEngine bean exists AND this is ON,
--     PreviewVerifier fails open (returns empty), so verifyBoot is inert.
--
-- Both OFF: zero behaviour change. SUPER_ADMIN-toggleable. Default-ON only
-- after the boot-pass-rate x verifiedScore eval + the red-team checklist in
-- the parent preview plan (no auto-flip — founder constraint).
-- =====================================================================

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'worker.verify-boot.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'verifyBoot — agent authors a run-recipe; platform boots + health-checks the app in a hardened sandbox; deterministic verdict feeds the verifiedScore BUILD slot (RL-2026-05-066). Gates the tool + prompt directive + report population. Default OFF.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'preview.buildpack.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'Capability gate for the verifyBoot build-and-boot engine (PreviewBootEngine — buildpack adapter + isolated deny-by-default-egress network + dedicated least-privilege SA + CodeAbuseScanner pre-build, preview Phase 0). PreviewVerifier fails open until a boot engine exists AND this is ON (RL-2026-05-066). Default OFF.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;
