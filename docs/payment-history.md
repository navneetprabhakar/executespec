# Payment History And Audit

> Recreated 2026-06-10 from billing/payment audit code. This is the current reconciliation map.

## Stores

| Store | Purpose | Code |
| --- | --- | --- |
| `payment_audit` | User/org payment, tier, refund, top-up, and gateway audit rows | `PaymentAudit` + `PaymentAuditService` |
| `webhook_event_log` | Gateway webhook receipt/processing ledger | `WebhookEventLogRepository` |
| `billing_webhook_event` | Billing webhook domain events | `billing/entity/BillingWebhookEvent.java` |
| `billing_subscription` | Active/inactive subscription state | `BillingSubscription` |
| `invoice` | Invoice metadata and PDF references | `Invoice` |
| `run_topup` | Purchased top-up credit state | `RunTopupService` |

## Read APIs

| Endpoint | Role | Notes |
| --- | --- | --- |
| `GET /api/billing/payment-history?orgId=` | `ORG_ADMIN`, `BILLING_ADMIN`, `SUPER_ADMIN` | Org-scoped; non-super-admin cannot read another org |
| `GET /api/admin/payment-audit` | `SUPER_ADMIN` | Filter by `orgId` or `userId`; page size capped |
| `GET /api/admin/payment-audit/export` | `SUPER_ADMIN` | CSV export capped at 10,000 rows |
| `GET /api/admin/webhook-events` | `SUPER_ADMIN` | Optional `gateway` filter |

## Webhook Flow

Gateway webhooks must verify signatures before mutating subscription state:

- Stripe verifies `Stripe-Signature` in `StripePaymentGateway`.
- Razorpay verifies HMAC in the Razorpay gateway path.
- Billing services write audit rows for state changes and payment events.

The review batch added/verified tests around webhook ordering and payment state, but live gateway sandbox proof is still a pending task in `docs/pending.md`.

## Operational Reconciliation

When a payment looks wrong:

1. Find `payment_audit` rows for the org/user.
2. Check `webhook_event_log` for receipt and processing status.
3. Check `billing_subscription` for active subscription state.
4. Check invoices and top-up rows if the issue is credit/payment specific.
5. Confirm gateway dashboard object ids match the audit row `paymentRefId`/gateway ids.

## Code Pointers

- `src/main/java/com/navneet/executespec/billing/controller/PaymentAuditController.java`
- `src/main/java/com/navneet/executespec/auth/service/PaymentAuditService.java`
- `src/main/java/com/navneet/executespec/billing/controller/BillingWebhookController.java`
- `src/main/java/com/navneet/executespec/billing/service/BillingService.java`
- `src/main/java/com/navneet/executespec/billing/service/RunTopupService.java`
- `src/main/java/com/navneet/executespec/billing/gateway/`
