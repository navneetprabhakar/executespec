-- The legacy Apply-to-Run path directly mutates and publishes a terminal run workspace. Keep it
-- unavailable until it uses the same reversible correction transaction, evidence invalidation,
-- deterministic validation, read-only review, and publication admission as automatic
-- continuation. This is a release-safety gate, not a rollout default to flip speculatively.
INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description, updated_at)
VALUES (
    'correction.transactional-apply.enabled',
    'false',
    'BOOLEAN',
    'PLATFORM',
    'Enable Apply to Run only after its transactional correction lifecycle is proven.',
    NOW()
)
ON CONFLICT (flag_key) DO UPDATE
SET flag_value = 'false',
    value_type = 'BOOLEAN',
    scope = 'PLATFORM',
    description = EXCLUDED.description,
    updated_at = NOW();
