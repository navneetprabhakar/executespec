-- M-038 (Backlog) — surface the activity_feed retention window as a
-- SUPER_ADMIN-tunable feature flag.
--
-- ActivityFeedRetentionJob reads `activity.retention.days` first,
-- falls back to `activity.feed.retention.days` from application.yml
-- (default 365). Flag value is clamped to [30, 3650] in code to
-- prevent typos from accidentally dropping everything or hoarding
-- forever.
--
-- Compliance use case: ENT customers under SOC 2 / ISO 27001 audit
-- requirements often need 365+ days of activity-feed retention.
-- Operators can flip the flag without a deploy when a new contract
-- requires a different window.
--
-- Idempotent — does nothing if the row already exists.

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description) VALUES
    ('activity.retention.days', '365', 'INTEGER', 'PLATFORM',
     'M-038: monthly partitions older than this many days are dropped by ActivityFeedRetentionJob. Clamped [30, 3650] in code. Compliance: SOC 2 / ISO 27001 customers may require 365+.')
ON CONFLICT (flag_key) DO NOTHING;
