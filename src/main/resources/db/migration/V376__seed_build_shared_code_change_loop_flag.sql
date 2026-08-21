-- M0 rollout control for BUILD/PATCH shared-kernel convergence.
--
-- OFF preserves the deployed V374/V375 BUILD request/prompt policy while the provider-neutral
-- CodeChangeLoop port remains behavior-neutral. M2+ failure-first baseline/contract injection must
-- consult this flag; disabling it must never disable transactional generation acceptance/rollback.
INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES
    ('build.shared-code-change-loop.enabled', 'false', 'BOOLEAN', 'GLOBAL',
     'Use the shared PATCH-style inspect-edit-test-diagnose policy for BUILD tasks. Default OFF until held-out A/B/C qualification; transactional workspace-generation safety remains mandatory regardless of this flag.')
ON CONFLICT (flag_key) DO UPDATE
    SET description = EXCLUDED.description;
