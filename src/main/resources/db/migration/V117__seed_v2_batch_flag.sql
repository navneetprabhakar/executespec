-- Harness V2 batch-execution feature flag seed.
--
-- See docs/plans/v2-batch-execution-plan.md. This migration is P1
-- scaffolding only: seeds the feature-flag row. Subsequent PRs wire the
-- v2/batch/* package (coordinator, router, provider clients, poll
-- service, resume service) behind this flag.
--
-- Default FALSE — flipping to true is the cutover switch after the P0
-- probe confirms native tool calling works in each provider's batch API.

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description) VALUES
    ('harness.v2.batch_enabled', 'false', 'BOOLEAN', 'PLATFORM',
     'Enable V2 batch execution path. When true, plan approval at BATCH mode dispatches worker tasks through provider batch APIs (Anthropic / OpenAI / Gemini) for ~50% credit savings. Reviewer/test-gen/preview stay realtime. Default FALSE until P0 probe results are captured.')
ON CONFLICT (flag_key) DO NOTHING;
