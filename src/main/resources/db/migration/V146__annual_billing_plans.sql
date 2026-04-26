-- =====================================================================
-- V146: Annual billing plans (Phase 1.3 #32).
--
-- Adds YEARLY-period rows for TEAM (per-seat) and MAX (BYOK flat) so
-- the /plans toggle can default to annual and offer a discount. Was
-- deferred in V123 ("Yearly deferred per team-tier-plan.md §11.5") —
-- now wired with a flat 17% discount (≈ "2 months free" pricing).
--
-- Pricing:
--   * TEAM Yearly INR: ₹3,999/seat/mo × 12 × 0.83 ≈ ₹39,830/seat/yr
--                     → 3,983,000 paise per seat
--   * TEAM Yearly USD: $49/seat/mo × 12 × 0.83 ≈ $488/seat/yr
--                     → 48,800 cents per seat
--   * MAX Yearly INR:  $29 ≈ ₹2,400/mo × 12 × 0.83 ≈ ₹23,900/yr
--                     → 2,390,000 paise base
--   * MAX Yearly USD:  $29/mo × 12 × 0.83 ≈ $289/yr
--                     → 28,900 cents base
--
-- annual_discount_pct = 17 stored on each row so the /plans page can
-- render "Save 17% with annual" copy without re-deriving the math.
--
-- Idempotent: WHERE NOT EXISTS guards on each insert. Out-of-scope:
-- Stripe/Razorpay annual plan IDs — ops will add them alongside the
-- existing monthly env vars.
-- =====================================================================

BEGIN;

-- ── TEAM Yearly INR ─────────────────────────────────────────────────
INSERT INTO billing_plan (
    id, name, tier, key_mode, product_access, period,
    base_amount_inr, base_amount_usd, per_seat_inr, per_seat_usd,
    included_seats, run_limit, annual_discount_pct, is_founder_pricing, active,
    lifecycle_state, lifecycle_changed_at, lifecycle_changed_by, lifecycle_reason,
    created_at, updated_at
)
SELECT
    gen_random_uuid(), 'Team Yearly INR', 'TEAM', 'MANAGED',
    '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'YEARLY',
    0, 0, 3983000, 0,
    2, -1, 17, FALSE, TRUE,
    'LISTED', now(), 'system-migration', 'V146 annual plans (Phase 1.3 #32)',
    now(), now()
WHERE NOT EXISTS (
    SELECT 1 FROM billing_plan WHERE name = 'Team Yearly INR' AND active = TRUE
);

-- ── TEAM Yearly USD ─────────────────────────────────────────────────
INSERT INTO billing_plan (
    id, name, tier, key_mode, product_access, period,
    base_amount_inr, base_amount_usd, per_seat_inr, per_seat_usd,
    included_seats, run_limit, annual_discount_pct, is_founder_pricing, active,
    lifecycle_state, lifecycle_changed_at, lifecycle_changed_by, lifecycle_reason,
    created_at, updated_at
)
SELECT
    gen_random_uuid(), 'Team Yearly USD', 'TEAM', 'MANAGED',
    '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'YEARLY',
    0, 0, 0, 48800,
    2, -1, 17, FALSE, TRUE,
    'LISTED', now(), 'system-migration', 'V146 annual plans (Phase 1.3 #32)',
    now(), now()
WHERE NOT EXISTS (
    SELECT 1 FROM billing_plan WHERE name = 'Team Yearly USD' AND active = TRUE
);

-- ── MAX Yearly INR ──────────────────────────────────────────────────
INSERT INTO billing_plan (
    id, name, tier, key_mode, product_access, period,
    base_amount_inr, base_amount_usd, per_seat_inr, per_seat_usd,
    included_seats, run_limit, annual_discount_pct, is_founder_pricing, active,
    lifecycle_state, lifecycle_changed_at, lifecycle_changed_by, lifecycle_reason,
    created_at, updated_at
)
SELECT
    gen_random_uuid(), 'Max Yearly INR', 'MAX', 'BYOK',
    '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'YEARLY',
    2390000, 0, 0, 0,
    1, -1, 17, FALSE, TRUE,
    'LISTED', now(), 'system-migration', 'V146 annual plans (Phase 1.3 #32)',
    now(), now()
WHERE NOT EXISTS (
    SELECT 1 FROM billing_plan WHERE name = 'Max Yearly INR' AND active = TRUE
);

-- ── MAX Yearly USD ──────────────────────────────────────────────────
INSERT INTO billing_plan (
    id, name, tier, key_mode, product_access, period,
    base_amount_inr, base_amount_usd, per_seat_inr, per_seat_usd,
    included_seats, run_limit, annual_discount_pct, is_founder_pricing, active,
    lifecycle_state, lifecycle_changed_at, lifecycle_changed_by, lifecycle_reason,
    created_at, updated_at
)
SELECT
    gen_random_uuid(), 'Max Yearly USD', 'MAX', 'BYOK',
    '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'YEARLY',
    0, 28900, 0, 0,
    1, -1, 17, FALSE, TRUE,
    'LISTED', now(), 'system-migration', 'V146 annual plans (Phase 1.3 #32)',
    now(), now()
WHERE NOT EXISTS (
    SELECT 1 FROM billing_plan WHERE name = 'Max Yearly USD' AND active = TRUE
);

-- Apply annual_discount_pct to existing MONTHLY plans for UI display.
-- (The percent value is shown on /plans even on monthly toggle so the
-- user sees "17% off if billed annually" alongside the monthly price.)
UPDATE billing_plan
   SET annual_discount_pct = 17
 WHERE active = TRUE
   AND tier IN ('TEAM', 'MAX')
   AND annual_discount_pct = 0;

COMMIT;
