-- GA-readiness P2.3 + P2.4 (2026-05-06) — Solo Yearly billing_plan rows +
-- founder pricing discount schema.
--
-- P2.3: V146 added annual rows for TEAM + MAX; this migration adds Solo
-- Yearly USD/INR so the new Solo tier (V176) participates in the annual
-- toggle. Discount mirrors V146's flat 17% ("two months free").
--
--   * Solo Yearly USD: $29/mo × 12 × 0.83 ≈ $289/yr → 28,900 cents
--   * Solo Yearly INR: ₹2,499/mo × 12 × 0.83 ≈ ₹24,890/yr → 2,489,000 paise
--
-- P2.4: founder_pricing_discount table — atomic 50%-off-lifetime allocation
-- for the first 100 Solo subscribers (audit §6 recommendation). Schema only;
-- the BillingService.subscribe pre-checkout wiring + Stripe coupon mapping
-- lands as a P2.4-followup (needs ops to create the FOUNDER50_LIFETIME
-- coupon on Stripe).
--
-- Idempotent (BEGIN/COMMIT, NOT EXISTS guards).

BEGIN;

-- ── 1. Solo Yearly USD ─────────────────────────────────────────────────
INSERT INTO billing_plan (
    id, name, tier, key_mode, product_access, period,
    base_amount_inr, base_amount_usd, per_seat_inr, per_seat_usd,
    included_seats, run_limit, annual_discount_pct, is_founder_pricing, active,
    lifecycle_state, lifecycle_changed_at, lifecycle_changed_by, lifecycle_reason,
    created_at, updated_at
)
SELECT
    gen_random_uuid(), 'Solo Yearly USD', 'PRO', 'MANAGED',
    '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'YEARLY',
    0, 28900, 0, 0,
    1, 360, 17, FALSE, TRUE,
    'LISTED', now(), 'V177', 'GA-readiness P2.3 — Solo annual at $289/yr (≈17% off)',
    now(), now()
WHERE NOT EXISTS (
    SELECT 1 FROM billing_plan WHERE name = 'Solo Yearly USD' AND active = TRUE
);

-- ── 2. Solo Yearly INR ─────────────────────────────────────────────────
INSERT INTO billing_plan (
    id, name, tier, key_mode, product_access, period,
    base_amount_inr, base_amount_usd, per_seat_inr, per_seat_usd,
    included_seats, run_limit, annual_discount_pct, is_founder_pricing, active,
    lifecycle_state, lifecycle_changed_at, lifecycle_changed_by, lifecycle_reason,
    created_at, updated_at
)
SELECT
    gen_random_uuid(), 'Solo Yearly INR', 'PRO', 'MANAGED',
    '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'YEARLY',
    2489000, 0, 0, 0,
    1, 360, 17, FALSE, TRUE,
    'LISTED', now(), 'V177', 'GA-readiness P2.3 — Solo annual at ₹24,890/yr (≈17% off)',
    now(), now()
WHERE NOT EXISTS (
    SELECT 1 FROM billing_plan WHERE name = 'Solo Yearly INR' AND active = TRUE
);

-- ── 3. founder_pricing_discount ───────────────────────────────────────
-- Schema for the global founder-pricing knob. One active row per (tier,
-- offer name) — the singleton "first 100 SOLO" discount lives here. Code
-- path that consumes it: FounderPricingService.tryAllocate(userId, tier)
-- atomically increments current_count under a row-level lock and returns
-- the discount when allocation succeeds, or empty when sold out.
CREATE TABLE IF NOT EXISTS founder_pricing_discount (
    id              UUID PRIMARY KEY,
    name            TEXT NOT NULL UNIQUE,
    tier            TEXT NOT NULL,
    discount_pct    INT NOT NULL CHECK (discount_pct > 0 AND discount_pct <= 100),
    max_subscribers INT NOT NULL CHECK (max_subscribers > 0),
    current_count   INT NOT NULL DEFAULT 0 CHECK (current_count >= 0),
    is_lifetime     BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at      TIMESTAMPTZ,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    stripe_coupon_id TEXT,
    razorpay_offer_id TEXT,
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Track which user got which discount — single row per (user, discount).
-- Used by the lookup path so a user who churns + re-subscribes still
-- inherits their original discount (lifetime semantics).
CREATE TABLE IF NOT EXISTS founder_pricing_allocation (
    id            UUID PRIMARY KEY,
    discount_id   UUID NOT NULL REFERENCES founder_pricing_discount(id),
    user_id       VARCHAR(255) NOT NULL,
    org_id        VARCHAR(255),
    allocated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at    TIMESTAMPTZ,
    revoke_reason TEXT,
    UNIQUE(discount_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_founder_pricing_allocation_user
    ON founder_pricing_allocation(user_id);
CREATE INDEX IF NOT EXISTS idx_founder_pricing_allocation_active
    ON founder_pricing_allocation(discount_id) WHERE revoked_at IS NULL;

-- Seed the singleton "first 100 Solo subscribers" discount. Idempotent via
-- ON CONFLICT (name).
INSERT INTO founder_pricing_discount (
    id, name, tier, discount_pct, max_subscribers, current_count,
    is_lifetime, expires_at, active, stripe_coupon_id, razorpay_offer_id, notes
)
VALUES (
    gen_random_uuid(),
    'FOUNDER50_SOLO_LIFETIME',
    'PRO',
    50,
    100,
    0,
    TRUE,
    NULL,
    TRUE,
    NULL,  -- ops to populate after creating the Stripe coupon
    NULL,  -- ops to populate after creating the Razorpay offer
    'GA-readiness P2.4 — first 100 Solo subscribers get 50% off lifetime. Stripe coupon FOUNDER50_LIFETIME / Razorpay offer to be created by ops before this is wired into BillingService.subscribe.'
)
ON CONFLICT (name) DO NOTHING;

COMMIT;
