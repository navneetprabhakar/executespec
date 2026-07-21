-- RR-056 / RR-054 (2026-07-22) — billing webhook temporal correctness.
--
-- The Stripe and Razorpay webhook handlers (plus the Razorpay post-checkout callback) both drive a
-- full subscription activation — retire superseded subscription, generate invoice, notify — with no
-- idempotency gate, so a callback+webhook race or a replayed event duplicated invoices and
-- notifications. And every handler overwrote status/period in ARRIVAL order using Instant.now(),
-- ignoring the provider's own event time, so a delayed event could regress ACTIVE back to PAST_DUE.
--
-- Two additive, safe schema changes back the fix. Neither rewrites existing rows.

-- RR-056 — one durable claim row per (gateway, immutable gateway payment/invoice id). The unique
-- index makes the INSERT the atomic winner-selection: the first callback OR webhook to reach a
-- payment id owns the activation; every duplicate loses the insert (ON CONFLICT DO NOTHING in the
-- activation transaction) and skips the non-idempotent effects.
CREATE TABLE IF NOT EXISTS billing_activation_claim (
    id VARCHAR(64) PRIMARY KEY,
    gateway VARCHAR(20) NOT NULL,
    claim_key VARCHAR(200) NOT NULL,
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_billing_activation_claim_once
    ON billing_activation_claim(gateway, claim_key);

-- RR-054 — provider-event high-water mark. A transition is rejected when its provider event time is
-- older than the one already applied, so the last writer is decided by provider time rather than by
-- delivery order. Nullable: legacy rows and events without a usable time are never treated as stale.
ALTER TABLE billing_subscription
    ADD COLUMN IF NOT EXISTS last_provider_event_at TIMESTAMPTZ;
