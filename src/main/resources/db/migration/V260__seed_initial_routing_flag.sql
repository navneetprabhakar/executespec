-- V260: Seed the evidence-gated initial worker-route flag.
--
-- The runtime seam is deliberately opt-in until a comparable provider-quality sample supports
-- changing the first worker attempt. With the default OFF value, existing route and pin behavior
-- remains byte-compatible.

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'execution.initial-routing.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'Select the first worker provider/model from observed candidate evidence. Default OFF.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;
