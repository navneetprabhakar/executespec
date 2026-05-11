-- Retire the "Solo" branding introduced by V176 (2026-05-06).
--
-- Background: V176 set `tier_config.PRO.display_name = 'Solo'` and V176+V177
-- inserted `billing_plan` rows named "Solo Monthly USD/INR" + "Solo Yearly
-- USD/INR" as a bridge between FREE and TEAM. After UX review (2026-05-11)
-- the ladder is consolidated back to the canonical names — FREE / PRO /
-- TEAM / ENTERPRISE — so customers see one term per tier across every
-- surface (topbar chip, billing hero, pricing card, settings page).
--
-- Idempotent: every UPDATE is keyed on the OLD value, so re-running this
-- migration on a DB that already shows "Pro" is a no-op (zero rows match
-- the second time).
--
-- Customer impact (verified via /api/admin/tiers on 2026-05-11 dev):
--   - tier_config.PRO.active_subscribers = 0
--   - 0 paying customers on any Solo billing_plan
-- → zero invoices, zero auth claims, zero entitlement payloads need
--   refreshing. Stripe / Razorpay plan IDs (`stripe_price_id`,
--   `razorpay_plan_id`) are NOT touched because (a) those are external
--   identifiers, not user-facing copy, and (b) re-creating them would
--   churn the gateway plan registry for no customer benefit.

-- 1) tier_config: PRO display_name back to "Pro". Stamp the lifecycle
--    audit columns so /admin/tiers reflects the rename in its history.
UPDATE tier_config
SET display_name           = 'Pro',
    lifecycle_changed_at   = NOW(),
    lifecycle_changed_by   = 'V185',
    lifecycle_reason       = 'Display-name rename Solo → Pro (UX consolidation, 2026-05-11)'
WHERE tier = 'PRO' AND display_name = 'Solo';

-- 2) billing_plan: rename the four Solo-* rows back to Pro-*. Done
--    individually rather than via REGEXP_REPLACE so the migration reads
--    clearly to anyone scanning the audit trail.
UPDATE billing_plan SET name = 'Pro Monthly USD' WHERE name = 'Solo Monthly USD';
UPDATE billing_plan SET name = 'Pro Monthly INR' WHERE name = 'Solo Monthly INR';
UPDATE billing_plan SET name = 'Pro Yearly USD'  WHERE name = 'Solo Yearly USD';
UPDATE billing_plan SET name = 'Pro Yearly INR'  WHERE name = 'Solo Yearly INR';
