-- Wave 1 Day 2 (2026-04-22) — TEAM billing_plan rows + org.seat_count column.
--
-- Monthly rows only for v1; Yearly deferred per team-tier-plan.md §11.5.
-- Amounts are in the smallest currency unit (paise for INR, cents for USD).
-- Pricing:
--   * TEAM Monthly INR: ₹3,999/seat → 399900 paise per seat, base = 0
--   * TEAM Monthly USD: $49/seat  → 4900 cents per seat, base = 0
-- included_seats = 2 (runtime-enforced minimum). Maximum seats = 20 is a
-- runtime-only cap in BillingService.subscribe — no DB field needed.

-- 1. TEAM Monthly INR
INSERT INTO billing_plan (
    id, name, tier, key_mode, product_access, period,
    base_amount_inr, base_amount_usd, per_seat_inr, per_seat_usd,
    included_seats, run_limit, annual_discount_pct, is_founder_pricing, active,
    lifecycle_state, lifecycle_changed_at, lifecycle_changed_by, lifecycle_reason,
    created_at, updated_at
) VALUES (
    gen_random_uuid(), 'Team Monthly INR', 'TEAM', 'MANAGED',
    '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'MONTHLY',
    0, 0, 399900, 0,
    2, -1, 0, FALSE, TRUE,
    'LISTED', now(), 'system-migration', 'TEAM tier launch (v2.6.0 Wave 1)',
    now(), now()
);

-- 2. TEAM Monthly USD
INSERT INTO billing_plan (
    id, name, tier, key_mode, product_access, period,
    base_amount_inr, base_amount_usd, per_seat_inr, per_seat_usd,
    included_seats, run_limit, annual_discount_pct, is_founder_pricing, active,
    lifecycle_state, lifecycle_changed_at, lifecycle_changed_by, lifecycle_reason,
    created_at, updated_at
) VALUES (
    gen_random_uuid(), 'Team Monthly USD', 'TEAM', 'MANAGED',
    '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'MONTHLY',
    0, 0, 0, 4900,
    2, -1, 0, FALSE, TRUE,
    'LISTED', now(), 'system-migration', 'TEAM tier launch (v2.6.0 Wave 1)',
    now(), now()
);

-- 3. Per-plan limits — run_limit on BillingPlan is set to -1 (unlimited at
--    plan level); actual per-month cap is seats × 50 computed at runtime by
--    TierEnforcementService.
INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), id, 'RUNS', -1 FROM billing_plan WHERE name = 'Team Monthly INR' AND active = TRUE;
INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), id, 'PROJECTS', 5 FROM billing_plan WHERE name = 'Team Monthly INR' AND active = TRUE;
INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), id, 'STORAGE_MB', 5120 FROM billing_plan WHERE name = 'Team Monthly INR' AND active = TRUE;

INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), id, 'RUNS', -1 FROM billing_plan WHERE name = 'Team Monthly USD' AND active = TRUE;
INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), id, 'PROJECTS', 5 FROM billing_plan WHERE name = 'Team Monthly USD' AND active = TRUE;
INSERT INTO billing_plan_limit (id, billing_plan_id, metric, max_value)
SELECT gen_random_uuid(), id, 'STORAGE_MB', 5120 FROM billing_plan WHERE name = 'Team Monthly USD' AND active = TRUE;

-- 4. org.seat_count column — mirrors BillingSubscription.totalSeats, synced
--    on webhook events (§4.3 team-tier-plan.md). Default 1 so existing rows
--    behave like "solo orgs" until a TEAM subscription is created.
ALTER TABLE org
    ADD COLUMN seat_count INT NOT NULL DEFAULT 1;

CREATE INDEX idx_org_seat_count ON org(seat_count);
