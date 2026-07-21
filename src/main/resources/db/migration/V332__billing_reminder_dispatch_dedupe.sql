-- RR-518 (2026-07-21) — durable delivery identity for billing reminders.
--
-- RenewalReminderScheduler and DunningScheduler both run daily and re-selected the same
-- subscriptions every run: renewal picked every ACTIVE subscription whose period end fell in the
-- next three days, dunning re-sent GENTLE/URGENT/FINAL for every PAST_DUE subscription once its
-- day-threshold was crossed. Neither recorded that a given reminder had already been delivered, so a
-- customer received the same renewal notice (email + a fresh in-app row) every day for three days,
-- and daily dunning mail thereafter — multiplied by every application replica running the schedule.
--
-- This table is the per-(subscription, billing-period, reminder-type, stage, channel) delivery
-- claim. A scheduler inserts the row BEFORE sending; the unique index makes the insert the atomic
-- winner-selection, so each stage on each channel is delivered exactly once per billing period no
-- matter how many times the daily schedule (or how many replicas) revisits the subscription.
--
-- period_key identifies the billing cycle the reminder is about (the subscription's current period
-- end), NOT the mutable updatedAt a webhook can reset. When the subscription renews into a new cycle
-- the key advances and the reminder legitimately becomes eligible again.
CREATE TABLE IF NOT EXISTS billing_reminder_dispatch (
    id VARCHAR(64) PRIMARY KEY,
    subscription_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64),
    period_key VARCHAR(64) NOT NULL,
    reminder_type VARCHAR(32) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    dispatched_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_billing_reminder_dispatch_once
    ON billing_reminder_dispatch(subscription_id, period_key, reminder_type, stage, channel);
