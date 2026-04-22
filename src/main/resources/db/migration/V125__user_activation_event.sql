-- Wave 2.1 — 6-event activation funnel schema.
--
-- Composite PK (user_id, event_name) enforces the "fire once per user"
-- invariant used by EventTrackingService.isFirstTime(). Repeat actions
-- (second run, third project) are tracked separately in PostHog with an
-- nth property on a generic `repeat_action` event and never hit this table.
--
-- Full design: docs/plans/activation-instrumentation-plan.md §6.3.

CREATE TABLE user_activation_event (
    user_id         VARCHAR(64)  NOT NULL,
    event_name      VARCHAR(32)  NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    properties_json JSONB,
    PRIMARY KEY (user_id, event_name),
    FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE INDEX idx_uae_event_occurred ON user_activation_event(event_name, occurred_at);

-- Signup-source attribution. Captured from ?utm_source= + ?utm_campaign= query
-- params on the signup request (RegisterRequest + OAuth callback URL params).
-- Nullable — pre-existing users + direct-navigation signups have no source.
ALTER TABLE app_user
    ADD COLUMN signup_source        VARCHAR(64),
    ADD COLUMN signup_utm_source    VARCHAR(64),
    ADD COLUMN signup_utm_campaign  VARCHAR(128);

CREATE INDEX idx_app_user_signup_source ON app_user(signup_source) WHERE signup_source IS NOT NULL;
