# Payment history (Razorpay + Stripe)

> Operator + customer-facing reference for the unified `payment_audit` log:
> what's recorded, where it comes from for each gateway, and how to read it.
> Last reviewed: 2026-05-12 (1.0.0-M1).

ExecuteSpec routes payments through two gateways: **Razorpay** (default for
INR / India) and **Stripe** (default for prod USD via
`billing.default-gateway: stripe`). Both write into the same
`payment_audit` table (entity at
`src/main/java/com/navneet/executespec/auth/entity/PaymentAudit.java`).
Customers see one unified history at `GET /api/billing/payment-history`;
SUPER_ADMIN gets the cross-org view at `GET /api/admin/payment-audit`.

## Endpoints

| Method | Path | Auth | Notes |
|---|---|---|---|
| `GET`  | `/api/billing/payment-history?orgId=...` | `ORG_ADMIN` / `BILLING_ADMIN` / `SUPER_ADMIN` | Org-scoped page (default `size=50`, max 200). |
| `GET`  | `/api/admin/payment-audit?...` | `SUPER_ADMIN` | Cross-org filterable. |
| `GET`  | `/api/admin/payment-audit/export` | `SUPER_ADMIN` | CSV export of the filtered set. |
| `GET`  | `/api/admin/webhook-events?gateway=...` | `SUPER_ADMIN` | Raw webhook log (separate from `payment_audit`). |

## Recorded fields (`payment_audit` columns)

| Column | Source — Razorpay | Source — Stripe | Notes |
|---|---|---|---|
| `id` | UUID at insert | UUID at insert | App-generated. |
| `entityType` | `USER` or `ORG` | same | Driven by who paid. |
| `entityId` | `appUser.id` or `org.id` | same | FK; not enforced at DB layer. |
| `eventType` | `PLAN_SUBSCRIBED` / `PLAN_UPGRADED` / `TOPUP_PURCHASED` / `PAYMENT_FAILED` / `REFUND_ISSUED` / `SUBSCRIPTION_CANCELLED` | same | Canonical taxonomy regardless of gateway. |
| `oldTier`, `newTier` | for tier transitions only | same | Null for top-ups + refunds. |
| `paymentRefId` | `razorpay_payment_id` | `stripe_payment_intent` (`pi_...`) | The user-facing payment ref shown on invoices. |
| `gatewayProvider` | `'RAZORPAY'` | `'STRIPE'` | Required since v2.6.0. |
| `gatewayEventId` | `payment.captured` etc. | `payment_intent.succeeded` etc. | Webhook event id used for idempotency. |
| `gatewayPaymentId` | `pay_*` (Razorpay) | `pi_*` (Stripe) | Same payment ref, gateway-namespaced. |
| `currency` | `INR` (typical) | `USD` (typical) | ISO 4217. |
| `amountMinor` | paise (₹100 = 10000) | cents ($49 = 4900) | Always minor units; convert on display. |
| `metadata` | webhook JSON envelope | webhook JSON envelope | jsonb; gateway-specific shape. |
| `idempotencyKey` | webhook `event.id` | webhook `event.id` | Same column for both — dedupe is at the row level. |
| `notes` | free-text from admin grants / refund flows | same | Operator-only. |
| `performedBy` | userId of admin if manual; null for webhook-driven | same | Audit trail. |
| `createdAt` | server `now()` at write | same | Not the gateway timestamp — see `metadata.event.created` for that. |

## Where each row gets written

### Razorpay path

1. Customer hits `POST /api/billing/subscribe` with `gateway=RAZORPAY`.
2. `RazorpayPaymentGateway.createSubscription` returns a checkout payload.
3. Customer completes checkout in the embedded Razorpay widget.
4. Razorpay webhook → `POST /api/webhooks/razorpay` (HMAC SHA-256 verified
   in `RazorpayWebhookController` against
   `application.yml:billing.razorpay.webhook-secret`).
5. `BillingService.handleRazorpayPaymentEvent` parses the event, calls
   `PaymentAuditService.record(...)` with `gatewayProvider='RAZORPAY'`,
   `paymentRefId=payment.id`, `amountMinor=payment.amount` (already in
   paise from Razorpay).
6. Row lands in `payment_audit`. `webhook_event_log` also gets a raw row
   for replay/debug.

### Stripe path

1. Customer hits `POST /api/billing/subscribe` with `gateway=STRIPE`
   (or no gateway in prod, which falls through to
   `BILLING_DEFAULT_GATEWAY=stripe`).
2. `StripePaymentGateway.createSubscription` creates a Stripe Checkout
   Session (mode `subscription`); returns the `checkoutUrl`.
3. Frontend `window.location.href = checkoutUrl` → user pays at
   `checkout.stripe.com`.
4. Stripe webhook → `POST /api/webhooks/stripe` (signature verified via
   `Stripe-Signature` header against
   `STRIPE_WEBHOOK_SECRET`).
5. `BillingService.handleStripeCheckoutCompleted` (and the per-event
   handlers below it) call `PaymentAuditService.record(...)` with
   `gatewayProvider='STRIPE'`, `paymentRefId=session.payment_intent`,
   `amountMinor=session.amount_total` (already in cents from Stripe).
6. Row lands in `payment_audit`. `webhook_event_log` mirror-writes.

## Reading a row — common questions

- **"Why is the amount 4900 when the customer paid $49?"** Minor units —
  divide by 100 and format with `currency` for display. Frontend helper:
  `formatMinorCurrency(amountMinor, currency)` in `lib/utils.ts`.
- **"Which payment matches a Stripe `pi_*`?"** Filter by `gatewayPaymentId`
  (or `paymentRefId` — they're the same string for Stripe).
- **"Why are some rows missing `gatewayProvider`?"** Pre-V55 rows. The
  column was added by `V55__payment_audit_expansion.sql`; the backfill
  marked those as `RAZORPAY` since that was the only gateway at the time.
  Frontend should default to `RAZORPAY` when `gatewayProvider` is null.
- **"Why is there a `webhook_event_log` row but no `payment_audit` row?"**
  Either the webhook was a non-payment event (e.g. `customer.created`),
  or the corresponding `BillingService` handler dropped the event after
  business-rule validation (e.g. amount mismatch, unknown plan id).
  The webhook log keeps both for debugging; `payment_audit` only keeps
  events that affected billing state.

## Refund flow

- Razorpay: `BillingService.issueRefund` calls Razorpay's refund API,
  records an audit row with `eventType=REFUND_ISSUED`, links back to
  the original payment via `metadata.original_payment_ref`.
- Stripe: `BillingService.issueProratedRefund:961` is a TODO (per root
  `CLAUDE.md` "Known deferred"). Currently no automated refunds on
  Stripe; ops must trigger refunds in the Stripe dashboard and call
  `PaymentAuditService.recordManualRefund(...)` to write the audit row.
  Tracked under M-044's follow-up.

## CSV export format

`GET /api/admin/payment-audit/export` streams CSV with columns:
`id, createdAt, entityType, entityId, eventType, gatewayProvider,
gatewayPaymentId, currency, amountMinor, oldTier, newTier, performedBy,
notes`.
Metadata jsonb is intentionally excluded (too noisy for spreadsheets);
the API endpoint is the canonical way to read full payload + idempotency
state.

## Operator gotchas

- **Idempotency:** Both gateways send retries on transient failures.
  `BillingService` checks `payment_audit.idempotencyKey` before writing
  a new row. If you see ≥2 rows with the same `gatewayEventId`, that's
  a code bug — file an issue.
- **Currency mismatch on cross-region orgs:** An org that switched its
  default currency mid-flight may have rows mixing INR + USD. The org's
  current default is on `org.preferred_currency`, but historical rows
  preserve what was charged at the time.
- **Tier rollback after a failed Stripe checkout:** Stripe doesn't
  guarantee `checkout.session.expired` always fires. If an org's tier
  shows as upgraded but `payment_audit` has no `PLAN_SUBSCRIBED` row,
  check `webhook_event_log` for a `checkout.session.completed` that
  `BillingService` dropped.
