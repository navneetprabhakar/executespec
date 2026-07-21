-- V310 — billing integrity hardening.
--
-- Three independent corrections from the 2026-07-20 repo audit:
--
--   RR-418  billing_plan.period stored 'YEARLY' (V146, V177) while every runtime path
--           recognised only 'ANNUAL'. Annual customers paid annual pricing but received a
--           30-day local entitlement and a MONTHLY Razorpay plan. Forward-migrate the value
--           and constrain the column so the split contract cannot reappear.
--
--   RR-048  Prorated upgrade refunds recorded a credit note, a "refund issued" audit row and a
--           customer email for Stripe subscriptions even though no Stripe refund was ever
--           issued (the code only called the Razorpay refund API). Refund intent now becomes a
--           durable obligation; the credit note is only generated once a gateway refund has
--           actually confirmed.
--
--   RR-049  On paid upgrade, failure to cancel the OLD gateway subscription was caught and
--   RR-352  ignored while the local row was marked UPGRADED, so the customer could keep being
--   RR-353  billed twice with nothing recorded. Tier SUNSETTING had the same shape: the tier
--           state committed while per-subscriber cancellation was flag-gated off or ran inside
--           the same rollbackable transaction as the gateway call. Both now persist one durable
--           obligation per pending gateway action, committed BEFORE the external call, so a
--           failed or partial cascade is a visible, retryable work item rather than silent
--           divergence between gateway truth and ExecuteSpec truth.

-- ---------------------------------------------------------------------------
-- RR-418 — canonical annual period value
-- ---------------------------------------------------------------------------

UPDATE billing_plan
   SET period = 'ANNUAL',
       updated_at = now()
 WHERE period = 'YEARLY';

ALTER TABLE billing_plan
  DROP CONSTRAINT IF EXISTS ck_billing_plan_period;

ALTER TABLE billing_plan
  ADD CONSTRAINT ck_billing_plan_period
  CHECK (period IN ('MONTHLY', 'ANNUAL'));

-- ---------------------------------------------------------------------------
-- RR-048 / RR-049 / RR-352 / RR-353 — durable gateway obligations
-- ---------------------------------------------------------------------------
--
-- One row per pending irreversible gateway action. The row is committed before the external
-- call so a crash, rollback or partial cascade leaves a recoverable work item instead of a
-- lost obligation. `idempotency_key` is the deduplication contract: it is derived from the
-- exact (kind, subscription, payment) tuple, so a retry of the same upgrade or sunset cascade
-- reuses the existing obligation rather than issuing a second refund/cancellation.

CREATE TABLE IF NOT EXISTS billing_gateway_obligation (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    -- PRORATED_REFUND | CANCEL_OLD_SUBSCRIPTION | TIER_SUNSET_CANCEL
    kind                varchar(40)  NOT NULL,

    -- PENDING | SUCCEEDED | FAILED | UNSUPPORTED
    --   UNSUPPORTED = the action cannot be performed automatically on this gateway and needs
    --   an operator. It is deliberately NOT a success state and never produces customer-facing
    --   "done" evidence.
    status              varchar(20)  NOT NULL DEFAULT 'PENDING',

    subscription_id     uuid,
    user_id             varchar(64),
    org_id              varchar(64),

    gateway             varchar(20),
    -- gateway subscription id / payment id the action targets
    gateway_ref         varchar(255),

    amount_minor        bigint,
    currency            varchar(3),

    -- deterministic dedupe key; see class docs on BillingGatewayObligationService
    idempotency_key     varchar(255) NOT NULL,

    attempts            int          NOT NULL DEFAULT 0,
    last_error          text,
    -- gateway-side identifier of the completed action (refund id, cancellation id)
    external_ref        varchar(255),
    detail              text,

    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    resolved_at         timestamptz,

    CONSTRAINT ck_billing_gateway_obligation_kind
      CHECK (kind IN ('PRORATED_REFUND', 'CANCEL_OLD_SUBSCRIPTION', 'TIER_SUNSET_CANCEL')),
    CONSTRAINT ck_billing_gateway_obligation_status
      CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'UNSUPPORTED'))
);

-- The dedupe contract. A duplicate upgrade/sunset attempt must find the existing obligation,
-- not create a second irreversible gateway action.
CREATE UNIQUE INDEX IF NOT EXISTS ux_billing_gateway_obligation_idem
  ON billing_gateway_obligation (idempotency_key);

-- Operator/retry sweep: unresolved obligations, oldest first.
CREATE INDEX IF NOT EXISTS ix_billing_gateway_obligation_open
  ON billing_gateway_obligation (status, kind, created_at)
  WHERE status IN ('PENDING', 'FAILED', 'UNSUPPORTED');

CREATE INDEX IF NOT EXISTS ix_billing_gateway_obligation_subscription
  ON billing_gateway_obligation (subscription_id);

-- ---------------------------------------------------------------------------
-- RR-184 — credit-note balance lookups
-- ---------------------------------------------------------------------------
--
-- The remaining creditable balance on an invoice is now computed by summing the credit notes
-- already issued against it, under a FOR UPDATE lock on the original. That sum needs an index;
-- without one it is a sequential scan of the whole invoice table on every refund.

CREATE INDEX IF NOT EXISTS ix_invoice_original_invoice_id
  ON invoice (original_invoice_id)
  WHERE original_invoice_id IS NOT NULL;
