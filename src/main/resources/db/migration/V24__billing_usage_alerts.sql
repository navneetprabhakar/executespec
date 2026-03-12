-- ============================================================
-- V24: Track billing usage alert history (prevents duplicate notifications)
-- ============================================================

CREATE TABLE billing_usage_alert (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       VARCHAR(255) NOT NULL REFERENCES app_user(id),
    metric        VARCHAR(50)  NOT NULL,
    threshold     INT          NOT NULL,       -- e.g., 80 for 80%
    sent_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    period_start  TIMESTAMPTZ  NOT NULL,
    UNIQUE(user_id, metric, threshold, period_start)
);

CREATE INDEX idx_billing_usage_alert_user ON billing_usage_alert(user_id, period_start);
