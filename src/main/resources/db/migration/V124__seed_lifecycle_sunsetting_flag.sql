-- Wave 1 Day 3 (2026-04-22) — feature flag for tier-lifecycle SUNSETTING
-- side-effects.
--
-- When OFF (default), a SUNSETTING state transition writes the column +
-- audit row but does NOT call gateway.cancelAtPeriodEnd() or fire notice
-- emails. This lets the tooling ship behind a flag while Day 6 admin UI +
-- Day 7 dev smoke verify the safety of the cascade.
--
-- When ON, TierLifecycleService.executeSunsettingSideEffects() iterates the
-- active BillingSubscription rows on the sunsetted tier and queues
-- gateway-side cancel-at-period-end + fires `tier-sunsetting.html` email.
--
-- Ops flips this via the FeatureFlagController admin UI after dev smoke.

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description) VALUES
    ('billing.lifecycle.sunsetting.enabled', 'false', 'BOOLEAN', 'PLATFORM',
     'Gate for SUNSETTING gateway side-effects. When OFF, SUNSETTING transitions only flip the DB flag + write audit — no Stripe/Razorpay cancel-at-period-end call, no notice email. Flip ON after Day 6 admin UI + Day 7 dev smoke verifies the cascade.')
ON CONFLICT (flag_key) DO NOTHING;
