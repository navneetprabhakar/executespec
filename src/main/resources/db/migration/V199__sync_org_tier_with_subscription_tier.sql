-- V199 (2026-05-16, SDET bug-bash) — close BUG-009 / Org.tier vs User.tier drift.
--
-- Root cause (BillingService.upgradeTier prior to this PR):
--   billing upgrade set `app_user.tier` but never set `org.tier`. Existing orgs
--   whose admin user was upgraded therefore kept `org.tier='FREE'` (initial value)
--   while members showed PRO/TEAM/ENTERPRISE in their permission snapshot. The
--   org-tier surface (Plans page "Currently on FREE", billing surface) read the
--   wrong source-of-truth and showed downgrade messaging to paid customers.
--
-- This migration is a one-time backfill that resolves the drift on existing rows.
-- The BillingService change in the same PR now writes both sides on every future
-- upgrade/downgrade, so this script only matters for legacy data.
--
-- Tier-bump priority (high → low):
--   1. ENTERPRISE  (highest commitment)
--   2. TEAM
--   3. PRO
--   4. FREE        (default)
--
-- For each org we resolve the *highest* paid tier across:
--   (a) any ACTIVE BillingSubscription rows whose `org_id` references this org
--   (b) any AppUser rows whose `org_id` references this org (their auth tier)
-- and write that to `org.tier`. Orgs with no paid signal stay on whatever they
-- already had (defaults to 'FREE' from V162's seeded value).
--
-- Idempotency: running this migration twice is a no-op because the second run
-- resolves to the same max-tier and writes the same value. Flyway already
-- prevents re-application; this note is for the reader.

WITH org_tier_signals AS (
  SELECT
    o.id AS org_id,
    GREATEST(
      -- subscription-driven tier (active subscription wins)
      COALESCE(MAX(CASE
        WHEN bs.status = 'ACTIVE' THEN
          CASE bp.tier
            WHEN 'ENTERPRISE' THEN 4
            WHEN 'MAX'        THEN 3   -- legacy MAX → TEAM
            WHEN 'TEAM'       THEN 3
            WHEN 'PRO'        THEN 2
            WHEN 'STARTER'    THEN 1   -- legacy STARTER → FREE
            ELSE 1
          END
        ELSE 0
      END), 0),
      -- user-tier-driven (max of any member's tier)
      COALESCE(MAX(
        CASE u.tier
          WHEN 'ENTERPRISE' THEN 4
          WHEN 'TEAM'       THEN 3
          WHEN 'PRO'        THEN 2
          WHEN 'FREE'       THEN 1
          ELSE 1
        END
      ), 0)
    ) AS rank
  FROM org o
  LEFT JOIN billing_subscription bs ON bs.org_id = o.id
  LEFT JOIN billing_plan bp ON bp.id = bs.billing_plan_id
  LEFT JOIN app_user u ON u.org_id = o.id
  GROUP BY o.id
)
UPDATE org o
SET tier = CASE ots.rank
             WHEN 4 THEN 'ENTERPRISE'
             WHEN 3 THEN 'TEAM'
             WHEN 2 THEN 'PRO'
             ELSE        'FREE'
           END,
    updated_at = NOW()
FROM org_tier_signals ots
WHERE o.id = ots.org_id
  AND o.tier <> CASE ots.rank
                  WHEN 4 THEN 'ENTERPRISE'
                  WHEN 3 THEN 'TEAM'
                  WHEN 2 THEN 'PRO'
                  ELSE        'FREE'
                END;
