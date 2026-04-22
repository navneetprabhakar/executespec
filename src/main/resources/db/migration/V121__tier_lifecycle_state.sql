-- Wave 1 Day 1 (2026-04-22) — tier lifecycle state columns.
--
-- Adds a 3-state (LISTED / HIDDEN / SUNSETTING) lifecycle machine on both
-- tier_config and billing_plan so any tier or plan cycle can be hidden from
-- new buyers without forcing churn on existing subscribers. See
-- docs/plans/tier-lifecycle-plan.md for the full design + state-transition
-- rules; docs/plans/team-tier-plan.md for the shipping context.
--
-- Both columns default to 'LISTED' so existing rows behave identically to
-- pre-v2.6.0. The TEAM tier launch migration (V122) is where PRO flips
-- to 'HIDDEN'; this migration stays data-neutral.

ALTER TABLE tier_config
    ADD COLUMN lifecycle_state       VARCHAR(16) NOT NULL DEFAULT 'LISTED',
    ADD COLUMN lifecycle_changed_at  TIMESTAMPTZ,
    ADD COLUMN lifecycle_changed_by  VARCHAR(64),
    ADD COLUMN lifecycle_reason      TEXT;

ALTER TABLE tier_config
    ADD CONSTRAINT tier_config_lifecycle_state_check
        CHECK (lifecycle_state IN ('LISTED', 'HIDDEN', 'SUNSETTING'));

ALTER TABLE billing_plan
    ADD COLUMN lifecycle_state       VARCHAR(16) NOT NULL DEFAULT 'LISTED',
    ADD COLUMN lifecycle_changed_at  TIMESTAMPTZ,
    ADD COLUMN lifecycle_changed_by  VARCHAR(64),
    ADD COLUMN lifecycle_reason      TEXT;

ALTER TABLE billing_plan
    ADD CONSTRAINT billing_plan_lifecycle_state_check
        CHECK (lifecycle_state IN ('LISTED', 'HIDDEN', 'SUNSETTING'));

CREATE INDEX idx_tier_config_lifecycle   ON tier_config(lifecycle_state);
CREATE INDEX idx_billing_plan_lifecycle  ON billing_plan(lifecycle_state);
