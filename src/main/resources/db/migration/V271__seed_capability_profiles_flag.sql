-- V271: Seed the evidence gate for model/provider capability profiles.
--
-- A capability profile may add optional tools or alter worker stagnation thresholds. YAML is
-- intentionally not sufficient to activate it: profiles can only take effect when this
-- separately audited flag is enabled after a reproducible, budget-reconciled evaluation.
-- Default OFF preserves the pre-profile worker schema and complexity thresholds.
INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'worker.capability-profiles.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'Evidence gate for model/provider capability profiles (optional tools and stagnation thresholds). Default OFF; enable only after a reproducible k>=3 evaluation against a fixed baseline.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET description = EXCLUDED.description;
