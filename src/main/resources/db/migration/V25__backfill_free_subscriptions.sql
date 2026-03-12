-- ============================================================
-- V25: Backfill FREE billing subscriptions for existing users
-- Users registered before V1.0 billing have no billing_subscription
-- row, which causes UsageTrackingService.assertWithinLimits() to
-- block run creation. This inserts a FREE subscription for every
-- user that doesn't already have one.
-- ============================================================

INSERT INTO billing_subscription (
    id, user_id, billing_plan_id, payment_gateway, status,
    total_seats, extra_seats, cancel_at_period_end, is_founder_pricing,
    created_at, updated_at
)
SELECT
    gen_random_uuid(),
    u.id,
    bp.id,
    'NONE',
    'FREE',
    1,
    0,
    false,
    false,
    NOW(),
    NOW()
FROM app_user u
CROSS JOIN billing_plan bp
WHERE bp.tier = 'FREE' AND bp.active = true
  AND NOT EXISTS (
      SELECT 1 FROM billing_subscription bs
      WHERE bs.user_id = u.id
        AND bs.status IN ('FREE', 'ACTIVE', 'PAST_DUE')
  );
