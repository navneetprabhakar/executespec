-- V54: Multi-currency support for Stripe gateway
-- Adds multi-currency pricing, org country/currency, billing profile tax IDs,
-- and gateway tracking for topup purchases.

-- ── billing_plan: multi-currency pricing ─────────────────────────────────────
ALTER TABLE billing_plan ADD COLUMN IF NOT EXISTS price_usd_cents BIGINT;
ALTER TABLE billing_plan ADD COLUMN IF NOT EXISTS price_eur_cents BIGINT;
ALTER TABLE billing_plan ADD COLUMN IF NOT EXISTS price_gbp_cents BIGINT;

-- ── billing_subscription: currency & amount tracking ─────────────────────────
ALTER TABLE billing_subscription ADD COLUMN IF NOT EXISTS currency VARCHAR(8) DEFAULT 'INR';
ALTER TABLE billing_subscription ADD COLUMN IF NOT EXISTS amount_minor BIGINT;

-- ── org: country & default currency ──────────────────────────────────────────
ALTER TABLE org ADD COLUMN IF NOT EXISTS country VARCHAR(4);
ALTER TABLE org ADD COLUMN IF NOT EXISTS default_currency VARCHAR(8) DEFAULT 'INR';

-- ── billing_profile: tax ID fields ──────────────────────────────────────────
ALTER TABLE billing_profile ADD COLUMN IF NOT EXISTS tax_id VARCHAR(64);
ALTER TABLE billing_profile ADD COLUMN IF NOT EXISTS tax_id_type VARCHAR(32);

-- ── topup_package: multi-currency + Stripe price ID ─────────────────────────
ALTER TABLE topup_package ADD COLUMN IF NOT EXISTS price_usd_cents BIGINT;
ALTER TABLE topup_package ADD COLUMN IF NOT EXISTS price_eur_cents BIGINT;
ALTER TABLE topup_package ADD COLUMN IF NOT EXISTS stripe_price_id VARCHAR(255);

-- ── invoice: currency column ────────────────────────────────────────────────
-- currency column already exists on invoice table (from initial schema)

-- ── run_topup: gateway tracking ─────────────────────────────────────────────
ALTER TABLE run_topup ADD COLUMN IF NOT EXISTS gateway_provider VARCHAR(20);

-- ── Backfill existing data ──────────────────────────────────────────────────
UPDATE billing_subscription SET currency = 'INR' WHERE currency IS NULL;
UPDATE run_topup SET gateway_provider = 'RAZORPAY' WHERE gateway_provider IS NULL AND gateway_order_id IS NOT NULL;
