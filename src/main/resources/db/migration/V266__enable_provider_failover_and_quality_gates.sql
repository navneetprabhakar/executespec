-- V266: Graduate provider failover and benchmark-confidence continuation gates.
-- These controls are now part of the supported multi-provider execution contract.

UPDATE feature_flag
SET flag_value = 'true',
    description = 'Canonical multi-provider failover after same-provider transient retry; BYOK-safe.'
WHERE flag_key = 'router.failover.enabled';
