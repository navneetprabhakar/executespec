-- E4.5 — Enterprise readiness, Phase E4 (notification preferences)
--
-- Per plan §4.5: per-user opt-in/out per event type per channel.
-- Defaults are sensible (notify for things that need attention,
-- don't notify for routine events) — applied by NotificationPrefService
-- when no row exists for a (user, event, channel) triple.
--
-- Reverse migration:
--   DROP TABLE IF EXISTS user_notification_pref CASCADE;

CREATE TABLE IF NOT EXISTS user_notification_pref (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       VARCHAR(64) NOT NULL,
  event_type    VARCHAR(64) NOT NULL,
  channel       VARCHAR(32) NOT NULL,    -- IN_APP | EMAIL
  enabled       BOOLEAN NOT NULL DEFAULT true,
  created_at    TIMESTAMP NOT NULL DEFAULT now(),
  updated_at    TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (user_id, event_type, channel)
);

CREATE INDEX IF NOT EXISTS idx_user_notification_pref_user
  ON user_notification_pref(user_id);
