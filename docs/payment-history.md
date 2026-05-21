# Payment History & Audit

How ExecuteSpec records, surfaces, and queries every payment-relevant event — Razorpay subscriptions and top-ups, Stripe checkouts and invoices, admin-driven tier changes, founder-pricing allocations, and the dunning/refund tail.

This doc covers:

- The three stores that together form the payment audit trail (`payment_audit`, `webhook_event_log`, `billing_webhook_event`).
- Every call site that writes to `payment_audit`.
- The webhook flow on both gateways, including signature verification, idempotency keys, and the always-200 contract.
- The two customer-facing read endpoints (`/api/billing/payment-history`, `/api/admin/payment-audit`) plus the CSV export.
- The retention story (or rather, the absence of one).
- The tier upgrade/downgrade audit trail that flows through the same chokepoint.

## The three stores

There are three tables behind the audit trail. They were introduced in three different waves and serve different purposes; their boundaries are not perfectly clean.

### `payment_audit` (V4 baseline + V55 expansion)

Append-only journal of every payment-relevant event for an entity (user or org). Originally a thin per-user/per-org tier-change log (V4); expanded in V55 to also be the unified gateway-event audit log spanning Razorpay and Stripe.

Defined at [`auth/entity/PaymentAudit.java`](../src/main/java/com/navneet/executespec/auth/entity/PaymentAudit.java).

| Column                | Type                          | Source       | Notes                                                                                                              |
| --------------------- | ----------------------------- | ------------ | ------------------------------------------------------------------------------------------------------------------ |
| `id`                  | `VARCHAR` PK                  | V4           | Application-assigned; format opaque to schema.                                                                     |
| `entity_type`         | `VARCHAR(32)` NOT NULL        | V4           | `USER` or `ORG`. Identifies the audited principal.                                                                 |
| `entity_id`           | `VARCHAR` NOT NULL            | V4           | FK-shaped reference to `app_user.id` or `org.id`.                                                                  |
| `event_type`          | `VARCHAR(64)` NOT NULL        | V4           | Free-form discriminator. See "Event type vocabulary" below.                                                        |
| `old_tier`            | `VARCHAR(32)`                 | V4           | Pre-event tier (when applicable).                                                                                  |
| `new_tier`            | `VARCHAR(32)`                 | V4           | Post-event tier (when applicable).                                                                                 |
| `payment_ref_id`      | `VARCHAR`                     | V4           | Legacy free-form reference; Stripe / Razorpay IDs now go in `gateway_*` columns.                                   |
| `performed_by`        | `VARCHAR`                     | V4           | Acting user id (`SYSTEM` for webhook-originated events).                                                            |
| `notes`               | `TEXT`                        | V4           | Human-readable rationale; carries refund reasons, escalation notes, etc.                                           |
| `gateway_provider`    | `VARCHAR(20)`                 | V55          | `RAZORPAY` / `STRIPE` / null for non-gateway events. Mixed case appears in older rows (V46 wrote lowercase).         |
| `gateway_event_id`    | `VARCHAR(255)`                | V55          | Gateway-issued event id; one half of the idempotency key.                                                          |
| `gateway_payment_id`  | `VARCHAR(255)`                | V55          | The `pay_xxx` / `pi_xxx` payment id when the event has one.                                                        |
| `currency`            | `VARCHAR(8)`                  | V55          | ISO 4217. Minor-unit denomination is implied (`INR`/`USD`/`EUR`/`GBP`).                                            |
| `amount_minor`        | `BIGINT`                      | V55          | Smallest-unit amount (paise for INR, cents for USD/EUR/GBP).                                                       |
| `metadata`            | `JSONB`                       | V55          | Free-form provider payload subset (cf. `BillingService` writes).                                                   |
| `idempotency_key`     | `VARCHAR(255)`                | V55          | Composite `{gatewayProvider}:{gatewayEventId}`. Powers webhook replay detection.                                   |
| `user_id`             | `VARCHAR`                     | V55          | Denormalised owner id for fast per-user filtering.                                                                  |
| `org_id`              | `VARCHAR`                     | V55          | Denormalised org for org-scoped reads.                                                                              |
| `created_at`          | `TIMESTAMPTZ` NOT NULL        | V4           | Append time (set by `PaymentAuditService.record`, not by DB default).                                              |

Indexes added in V55: `event_type`, `gateway_provider`, `created_at`, `idempotency_key`, `user_id`, `org_id`. The V4 baseline also kept `(entity_type, entity_id)` for the legacy lookup path.

Sample event-type vocabulary (this is a documented convention, not a `CHECK` constraint — the column is plain `VARCHAR(64)`):

- Subscription lifecycle: `SUBSCRIPTION_ACTIVATED`, `SUBSCRIPTION_RENEWED`, `SUBSCRIPTION_CANCELLED`, `SUBSCRIPTION_DOWNGRADED`, `SUBSCRIPTION_PAYMENT_FAILED`
- One-time payments: `TOPUP_PURCHASED`, `TOPUP_GRANTED` (admin), `TOPUP_EXPIRED`
- Tier changes: `TIER_CHANGED` (admin-driven), `TIER_UPGRADED` (paid), `TIER_DOWNGRADED_TO_FREE`
- Refunds: `REFUND_ISSUED`, `CREDIT_NOTE_GENERATED`, `UPGRADE_PRORATED_REFUND`
- Webhook bookkeeping: `WEBHOOK_RECEIVED`, `WEBHOOK_PROCESSED`, `WEBHOOK_IGNORED`

In practice, what gets written depends on the call site (see "Call sites" below). Treat the vocabulary as conventional, not enumerated.

### `webhook_event_log` (V55)

The audit log for every received gateway webhook. Unique key on `(gateway, event_id)` makes it the canonical idempotency store. Defined at [`billing/entity/WebhookEventLog.java`](../src/main/java/com/navneet/executespec/billing/entity/WebhookEventLog.java).

| Column              | Type                       | Notes                                                                                  |
| ------------------- | -------------------------- | -------------------------------------------------------------------------------------- |
| `id`                | `BIGSERIAL` PK             | DB-generated.                                                                          |
| `gateway`           | `VARCHAR(20)`              | `RAZORPAY` or `STRIPE` (mixed-case rows possible from older code paths).               |
| `event_id`          | `VARCHAR(255)`             | Gateway-issued. Razorpay calls these `id`; Stripe `evt_xxx`.                            |
| `event_type`        | `VARCHAR(128)`             | e.g. `payment.captured`, `invoice.paid`, `customer.subscription.deleted`.               |
| `payload_hash`      | `VARCHAR(64)`              | SHA-256 of the raw body, for tamper-evidence and partial-equality checks.               |
| `raw_payload`       | `TEXT`                     | The exact body received (kept for replay debugging).                                    |
| `processing_status` | `VARCHAR(32)` default `RECEIVED` | Lifecycle: `RECEIVED` -> `PROCESSED` / `IGNORED` / `FAILED`.                      |
| `error_message`     | `TEXT`                     | Populated when `processing_status=FAILED`.                                              |
| `attempts`          | `INT` default 1            | Bumped if the same `event_id` is received again (re-delivery dedupe).                   |
| `created_at`        | `TIMESTAMPTZ`              | Defaulted at builder construction time (no DB default, no `@PrePersist`).               |
| `processed_at`      | `TIMESTAMPTZ`              | Stamped when processing finishes (success or failure).                                  |

Unique constraint `(gateway, event_id)` — this is the gate that makes the always-200 webhook contract safe. Re-deliveries flip to `attempts++` instead of producing duplicate side effects.

Read paths: `BillingWebhookController` reads to dedupe before dispatch; SUPER_ADMIN reads paginated lists via `WebhookEventLogRepository.findAllByOrderByCreatedAtDesc` (see `/api/admin/webhook-events` below).

Anomaly: `createdAt` is initialised in the builder, not via `@PrePersist`. If a `WebhookEventLog.builder()` is held long before saving, the timestamp will reflect the build moment, not the flush. Inconsequential today but worth knowing.

### `billing_webhook_event` (V23)

The original webhook idempotency marker, created with V23 as part of the initial billing schema. Defined at [`billing/entity/BillingWebhookEvent.java`](../src/main/java/com/navneet/executespec/billing/entity/BillingWebhookEvent.java).

| Column          | Type             | Notes                                          |
| --------------- | ---------------- | ---------------------------------------------- |
| `id`            | `UUID` PK        | Application-assigned UUID.                     |
| `gateway`       | `VARCHAR(20)`    | `RAZORPAY` or `STRIPE`.                        |
| `event_id`      | `VARCHAR(200)`   | Gateway event id.                              |
| `event_type`    | `VARCHAR(80)`    | Webhook event subtype.                         |
| `raw_payload`   | `JSONB`          | Parsed JSON payload.                           |
| `processed`     | `BOOLEAN`        | Idempotency flag.                              |
| `processed_at`  | `TIMESTAMPTZ`    | When processing finished.                      |
| `error_message` | `VARCHAR`        | Populated on processing failure.               |
| `created_at`    | `TIMESTAMPTZ`    | Stamped by `@PrePersist`.                      |

Unique key: `(gateway, event_id)`.

This table predates `webhook_event_log` (V55). It still gets written by `BillingService.processRazorpayWebhook`, which calls into `BillingWebhookEventRepository.existsByGatewayAndEventId` to short-circuit re-delivery. The two tables are near-duplicates — both keyed on `(gateway, event_id)`, both track processed/failed state. They have not been merged because:

1. V23 rows predate V55 and the older code path still references them.
2. `webhook_event_log` carries `payload_hash` and `attempts` that the older table lacks.
3. Migration would require a join-and-merge backfill plus controller refactor; cost > benefit so far.

Anomaly worth carrying in your head: two stores, two repositories (`BillingWebhookEventRepository`, `WebhookEventLogRepository`). Both write paths fire on Razorpay events (via `BillingService.processRazorpayWebhook` -> `BillingWebhookEvent`, and via `BillingWebhookController` -> `WebhookEventLog`). The Stripe path only writes `WebhookEventLog`. Double-bookkeeping is intentional for the Razorpay path; just be aware when reconciling.

## `PaymentAuditService.record(...)` — call sites

`PaymentAuditService` ([`auth/service/PaymentAuditService.java`](../src/main/java/com/navneet/executespec/auth/service/PaymentAuditService.java)) is the single chokepoint for writing `payment_audit` rows. It exposes two overloads:

```java
// Legacy 7-arg form — used for admin tier changes that don't touch a gateway
void record(String entityType, String entityId, String eventType,
            String oldTier, String newTier,
            String performedBy, String notes);

// Full 12-arg form — used for every gateway event since V55
void record(String entityType, String entityId, String eventType,
            String oldTier, String newTier,
            String performedBy, String notes,
            String gatewayProvider, String gatewayEventId, String gatewayPaymentId,
            String currency, Long amountMinor,
            String userId, String orgId);
```

The full overload derives `idempotency_key = "{gatewayProvider}:{gatewayEventId}"` so the same provider event gets the same row on re-delivery (when called twice with the same ids, the second insert is the operator's responsibility to detect — there's no DB unique index on `idempotency_key`, just a B-tree index for lookups).

Concrete call sites (every place that records):

| Caller                                                              | What it logs                                                                                                    | Event types                                                                              |
| ------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| `BillingService.processRazorpayWebhook` ([`billing/service/BillingService.java`](../src/main/java/com/navneet/executespec/billing/service/BillingService.java)) | Subscription lifecycle (`authenticated`, `charged`, `pending`, `halted`, `cancelled`, `completed`) and one-time `payment.captured` / `order.paid` for top-ups. | `SUBSCRIPTION_ACTIVATED`, `SUBSCRIPTION_RENEWED`, `SUBSCRIPTION_CANCELLED`, `SUBSCRIPTION_PAYMENT_FAILED`, `TOPUP_PURCHASED` |
| `BillingService.processStripeWebhook`                               | `checkout.session.completed`, `invoice.paid`, `invoice.payment_failed`, `customer.subscription.deleted`.        | `SUBSCRIPTION_ACTIVATED`, `SUBSCRIPTION_RENEWED`, `SUBSCRIPTION_PAYMENT_FAILED`, `SUBSCRIPTION_CANCELLED` |
| `BillingService.upgradeTier(userId, orgId, plan)`                   | Internal tier promotion from successful subscription activation. Writes both `USER` and `ORG` rows (BUG-009 fix). | `TIER_UPGRADED`                                                                          |
| `BillingService.downgradeToFree(sub)`                               | End-of-period downgrade or cancellation cascade. Mirrors `user.tier` and `org.tier` together.                   | `TIER_DOWNGRADED_TO_FREE`                                                                |
| `BillingService.issueProratedRefund(...)`                           | Mid-cycle upgrade prorated refund — least of runs-based and time-based formulas; writes refund-id + credit-note. | `UPGRADE_PRORATED_REFUND`, `REFUND_ISSUED`                                               |
| `BillingService.verifyAndActivate(userId, request)`                 | Manual Razorpay `payment.captured` verification path (frontend posts the triple after Razorpay checkout).        | `SUBSCRIPTION_ACTIVATED`                                                                  |
| `BillingService.cancel(userId)`                                     | Customer-initiated cancel-at-period-end.                                                                         | `SUBSCRIPTION_CANCELLED`                                                                  |
| `BillingService.updateSeats(userId, newTotalSeats)`                 | TEAM-tier seat adjustment (Stripe-only path; Razorpay returns 501).                                              | `SUBSCRIPTION_SEATS_UPDATED`                                                              |
| `RunTopupService.verifyAndCredit(...)`                              | Razorpay one-time top-up payment verification.                                                                   | `TOPUP_PURCHASED`                                                                         |
| `RunTopupService.grantBonusRuns(...)`                               | SUPER_ADMIN-driven bonus-credit grant (defaults to 90-day expiry).                                               | `TOPUP_GRANTED`                                                                           |
| `RunTopupService.expireOverdueTopups()`                             | Scheduled expiry of past `expires_at` rows (only for granted credits; purchases never expire).                   | `TOPUP_EXPIRED`                                                                           |
| `SuperAdminController.PUT /api/admin/users/{id}/tier`               | SUPER_ADMIN manually flips a user's tier.                                                                        | `TIER_CHANGED`                                                                            |
| `SuperAdminController.PUT /api/admin/orgs/{id}/tier`                | SUPER_ADMIN manually flips an org's tier.                                                                        | `TIER_CHANGED`                                                                            |
| `TierLifecycleService.executeSunsettingSideEffects(...)`            | Bulk cascade — for each active sub on a SUNSETTING tier, calls gateway `cancelAtPeriodEnd` and audits each.       | `SUBSCRIPTION_CANCELLED_BY_SUNSETTING`                                                    |
| `FounderPricingService.tryAllocate(...)`                            | Locks the founder-pricing discount row, increments counter, writes allocation. Records allocation as audit row. | `FOUNDER_PRICING_ALLOCATED`                                                               |
| `FounderPricingService.revokeAllocation(...)`                       | Reverses an allocation when admin rescinds a founder slot.                                                       | `FOUNDER_PRICING_REVOKED`                                                                 |

Note on `BillingService.processStripeWebhook`: the function writes the raw event to `webhook_event_log` BEFORE processing and relies on `BillingWebhookController` having called `gatewayRouter.stripe().handleWebhook(...)` first for signature verification. If anyone ever calls `processStripeWebhook` directly without that prerequisite, the signature check is skipped. Mitigated for now by call-site convention; documented here so the convention doesn't get accidentally violated.

## Webhook flow

Two endpoints — one per gateway — receive webhooks. Both follow the same skeleton: signature-verify, dedupe-via-`webhook_event_log`, dispatch to `BillingService.processXxxWebhook`, log + reply 200. Re-delivery and signature failures alike return 200 (after logging) so the gateway doesn't retry and force a duplicate side effect.

Implementation: [`billing/controller/BillingWebhookController.java`](../src/main/java/com/navneet/executespec/billing/controller/BillingWebhookController.java).

### Razorpay — `POST /api/webhooks/razorpay`

```
HTTP request:
  body = raw JSON payload
  X-Razorpay-Signature = HMAC-SHA256(rawPayload, webhookSecret) hex
```

1. Read raw body. If `X-Razorpay-Signature` is missing -> 401 (signature presence is mandatory; no payload should be processed without it).
2. `WebhookSignatureVerifier.verifyWebhookSignature(rawPayload, signature)` ([`billing/security/WebhookSignatureVerifier.java`](../src/main/java/com/navneet/executespec/billing/security/WebhookSignatureVerifier.java)) — recomputes HMAC-SHA256 over the body using `razorpay.webhook-secret`, compares with `MessageDigest.isEqual` (constant-time). Failure -> 401.
3. Compute SHA-256 of the raw body, look up `(gateway='RAZORPAY', event_id=payload.id)` in `webhook_event_log`. If found -> bump `attempts`, return 200 with status `IGNORED_DUPLICATE`.
4. Insert a `webhook_event_log` row with `processing_status=RECEIVED`, `payload_hash=<sha256>`, `raw_payload=<body>`.
5. Dispatch to `BillingService.processRazorpayWebhook(rawPayload, signature)`. Inside, the same payload triggers a second idempotency check against `billing_webhook_event` (V23 table); the per-event handler emits `payment_audit` rows.
6. Flip `processing_status` to `PROCESSED` (or `FAILED` with `error_message`). Return 200.

Idempotency keys:
- `webhook_event_log` unique key: `(gateway, event_id)`.
- `billing_webhook_event` unique key: `(gateway, event_id)` (same shape; different table; written by `BillingService`).
- `payment_audit.idempotency_key` = `RAZORPAY:{event_id}`.

Signature also handles two non-webhook flows:
- `verifyPaymentSignature(subscriptionId, paymentId, signature)` — Razorpay subscription verification (frontend posts triple after checkout).
- `verifyOrderPaymentSignature(orderId, paymentId, signature)` — top-up one-time payment verification.

Both sign-payload-shapes use Razorpay's `payment_id|subscription_id` or `order_id|payment_id` concatenation rule (HMAC-SHA256 with `key_secret`, not `webhook_secret`).

### Stripe — `POST /api/webhooks/stripe`

```
HTTP request:
  body = raw JSON payload
  Stripe-Signature = t=<unix>,v1=<sig>,v0=<sig>
```

1. Read raw body. Missing `Stripe-Signature` -> 401.
2. If `billing.stripe.enabled=false` -> 403 (the controller's switch — Stripe events should not be received in Razorpay-only deployments).
3. `gatewayRouter.stripe().handleWebhook(payload, signatureHeader)` ([`billing/gateway/StripePaymentGateway.java`](../src/main/java/com/navneet/executespec/billing/gateway/StripePaymentGateway.java)) calls `Webhook.constructEvent(payload, signatureHeader, webhookSecret)` from the Stripe SDK. This handles the timestamp tolerance + scheme `v1` HMAC. Failure -> 401.
4. Compute SHA-256, look up `(gateway='STRIPE', event_id=evt.id)` in `webhook_event_log`. Duplicate -> bump `attempts`, return 200.
5. Insert `webhook_event_log` row with `processing_status=RECEIVED`.
6. Dispatch to `BillingService.processStripeWebhook(payload, signatureHeader, eventType)`. The handler routes on `evt.type`:
   - `checkout.session.completed` -> activate subscription (extract billing entity from metadata, syncs `org.seat_count` for TEAM via `fetchStripeSubscriptionSeatCount`).
   - `invoice.paid` -> extend period + generate invoice PDF via `InvoiceService.generateStripeSubscriptionInvoice`.
   - `invoice.payment_failed` -> flip subscription to `PAST_DUE`, fire `BillingNotificationService.sendPaymentFailed`.
   - `customer.subscription.deleted` -> downgrade to FREE, fire downgrade email.
7. Flip `processing_status`. Return 200.

Idempotency: same shape as Razorpay — `webhook_event_log` unique key, `payment_audit.idempotency_key = STRIPE:{evt.id}`. Unlike Razorpay, Stripe path does NOT write `billing_webhook_event` (the older V23 table); only `webhook_event_log`.

### Always-200 contract

Both endpoints return 200 even when:
- Signature verification fails (401 to the client, but only because Spring intercepts — see anomaly below).
- Body is malformed (caught + logged + treated as IGNORED).
- Side-effect dispatch throws inside `BillingService` (caught at line 712 of `BillingService.processRazorpayWebhook` per the research note; `WebhookEventLog.processing_status=FAILED`).

Rationale: gateways retry on non-2xx. If we throw 500 on a processing bug, the gateway hammers us until it gives up; combined with the event having already been written with `processed=false` in `billing_webhook_event`, the same event could fire multiple side effects on retry. Better to log, return 200, and let an operator sweep `processing_status=FAILED` rows manually.

Side-effect: failed events sit silently in both `webhook_event_log` and `billing_webhook_event` until someone checks. There is no scheduled alert today. Operators should periodically scan `WHERE processing_status='FAILED'` rows — `/api/admin/webhook-events?gateway=RAZORPAY` surfaces the same data.

## Read endpoints

Three customer/admin surfaces read the audit trail. All live under [`billing/controller/PaymentAuditController.java`](../src/main/java/com/navneet/executespec/billing/controller/PaymentAuditController.java).

### `GET /api/billing/payment-history`

Role-gated: `ORG_ADMIN | BILLING_ADMIN | SUPER_ADMIN`. Paginated; returns rows for one org.

```
GET /api/billing/payment-history?orgId=<id>&page=0&size=20
Authorization: Bearer <jwt>

Response: Page<PaymentAudit>
```

The org scope is required (a BILLING_ADMIN cannot enumerate other orgs). For SUPER_ADMIN this is just a convenience; they can also use the admin endpoint.

Backed by `PaymentAuditRepository.findByOrgIdOrderByCreatedAtDesc(orgId, pageable)` ([`auth/repository/PaymentAuditRepository.java`](../src/main/java/com/navneet/executespec/auth/repository/PaymentAuditRepository.java)).

### `GET /api/admin/payment-audit`

`SUPER_ADMIN`-only. Paginated; optional `orgId` + `userId` filters.

```
GET /api/admin/payment-audit?page=0&size=50&orgId=<id>&userId=<id>
Authorization: Bearer <jwt>

Response: Page<PaymentAudit>
```

When both `orgId` and `userId` are blank, returns the global feed `findAllByOrderByCreatedAtDesc`. With filters, dispatches to `findByOrgIdOrderByCreatedAtDesc` or `findByUserIdOrderByCreatedAtDesc`.

### `GET /api/admin/payment-audit/export`

`SUPER_ADMIN`-only. CSV dump of the full audit table.

```
GET /api/admin/payment-audit/export
Authorization: Bearer <jwt>

Response: text/csv (byte[])
```

Built by `PaymentAuditService.exportCsv(records)`. The CSV is generated in-memory from `findAll(Pageable.unpaged())`. For a table with millions of rows this is a foot-gun — there's no streaming export. As of head V202 this hasn't been hit in production, but the call site is worth flagging.

### `GET /api/admin/webhook-events`

`SUPER_ADMIN`-only. Paginated webhook events. Optional `gateway` filter.

```
GET /api/admin/webhook-events?gateway=RAZORPAY&page=0&size=50
Authorization: Bearer <jwt>

Response: Page<WebhookEventLog>
```

Backed by `WebhookEventLogRepository.findByGatewayOrderByCreatedAtDesc` / `findAllByOrderByCreatedAtDesc` ([`billing/repository/WebhookEventLogRepository.java`](../src/main/java/com/navneet/executespec/billing/repository/WebhookEventLogRepository.java)).

### Aside: DSAR access

The user-facing GDPR / CCPA / DPDP export at `GET /api/dsar/me` ([`auth/dsar/DsarController.java`](../src/main/java/com/navneet/executespec/auth/dsar/DsarController.java)) includes payments via `PaymentAuditRepository.findByUserIdOrOrgId(userId, orgId)` — the M-017 DSAR-aware finder. The serialised payment list redacts `metadata` (raw payload is not re-emitted to the data subject).

The richer admin export is `GET /api/auth/me/data-export` which builds a ZIP through `UserDataExportService` ([`auth/service/UserDataExportService.java`](../src/main/java/com/navneet/executespec/auth/service/UserDataExportService.java)). Both flows respect the same `EXPORT_ROW_LIMIT = 200`; if your audit trail is longer than that, only the most recent 200 rows are included.

## Retention

There is no retention or purge job for any of the three stores.

- `payment_audit` grows unbounded.
- `webhook_event_log` grows unbounded.
- `billing_webhook_event` grows unbounded.

The activity-audit retention story (`activity.retention.days` feature flag, seeded by V150 to 365 days) does **not** apply to these tables — it gates `activity_audit` only.

Operational implication: schema head V202 doesn't ship a purge job. If you need bounded retention for compliance, you'll have to either (a) introduce a TTL job in a new package or (b) periodically `DELETE FROM payment_audit WHERE created_at < NOW() - INTERVAL '7 years'` (Indian GST law mandates 7 years; check your local equivalent).

There is also no archival to cold storage. If you need that, the canonical fields to preserve are `payment_audit.idempotency_key`, `payment_audit.gateway_event_id`, `webhook_event_log.payload_hash`, plus the original `webhook_event_log.raw_payload`.

## Tier upgrade / downgrade audit trail

Tier changes — both customer-driven (paying / cancelling) and admin-driven — write to `payment_audit` with `entity_type` in `{USER, ORG}` and `old_tier` / `new_tier` populated.

### Subscription-driven (gateway webhook path)

```
Stripe checkout.session.completed
   -> BillingService.processStripeWebhook
   -> upgradeTier(userId, orgId, plan)        [writes BOTH app_user.tier and org.tier — BUG-009 fix]
   -> PaymentAuditService.record(
        entityType=USER,  entityId=userId, eventType=TIER_UPGRADED, oldTier=<prev>, newTier=<plan.tier>, ...)
   -> PaymentAuditService.record(
        entityType=ORG,   entityId=orgId,  eventType=TIER_UPGRADED, oldTier=<prev>, newTier=<plan.tier>, ...)
```

The BUG-009 fix (head V199 backfill in `V199__sync_org_tier_with_subscription_tier.sql`) ensures `org.tier` matches `app_user.tier` and the active subscription's `billing_plan.tier`. Before this fix, drift was possible if `upgradeTier` updated `app_user` but not `org`. Both are now written together and both produce an audit row.

### Customer-driven cancel

```
POST /api/billing/cancel
   -> BillingService.cancel(userId)
   -> gateway.cancelAtPeriodEnd(subId)
   -> PaymentAuditService.record(
        entityType=USER, entityId=userId, eventType=SUBSCRIPTION_CANCELLED, ...)
```

`payment_audit` row written immediately; no `org.tier` flip until the period ends.

At period end the `PeriodEndDowngradeScheduler` ([`billing/scheduler/PeriodEndDowngradeScheduler.java`](../src/main/java/com/navneet/executespec/billing/scheduler/PeriodEndDowngradeScheduler.java)) runs every 30 minutes; for each `findCancelledPastPeriodEnd` row it flips `app_user.tier = FREE`, `org.tier = FREE`, `subscription.status = DOWNGRADED`, and `BillingService.downgradeToFree` writes the `TIER_DOWNGRADED_TO_FREE` audit row.

### Admin-driven tier flip

```
PUT /api/admin/users/{id}/tier  { tier: "PRO" }
   -> SuperAdminController.updateUserTier(id, body, principal)
   -> AuthService / direct repo update
   -> PaymentAuditService.record(
        entityType=USER, entityId=id, eventType=TIER_CHANGED, oldTier=<prev>, newTier="PRO",
        performedBy=<admin user id>, notes=<reason from body>, ...)
```

Same shape for `PUT /api/admin/orgs/{id}/tier`.

The audit row is written before the user is notified. The SUPER_ADMIN UI displays the trail back-to-back with billing webhooks, so operators can see "user X was on FREE -> got bumped to PRO by admin@executespec.ai on 2026-05-12" interleaved with the actual Stripe checkout that would have done the same thing.

### Tier lifecycle SUNSETTING cascade

When SUPER_ADMIN flips a tier to `SUNSETTING` via `PUT /api/admin/tiers/{tier}/lifecycle` ([`billing/lifecycle/TierLifecycleService.java`](../src/main/java/com/navneet/executespec/billing/lifecycle/TierLifecycleService.java)), `executeSunsettingSideEffects` iterates every active subscription on that tier and calls `gateway.cancelAtPeriodEnd()`. Each cancellation writes a `payment_audit` row with `event_type=SUBSCRIPTION_CANCELLED_BY_SUNSETTING`.

This is a best-effort cascade — the method is non-transactional. Partial failures leave the system half-cascaded with no rollback marker beyond the audit rows themselves. The result type `SunsettingSideEffectsResult(totalConsidered, queuedForCancellation, failed, errors)` surfaces the count of failures back to the admin UI.

## Cross-references

- Pricing IDs / env vars / Stripe rotation: see [`docs/stripe-price-ids-ops.md`](stripe-price-ids-ops.md).
- Billing data model entities (plans, subscriptions, invoices, top-ups): under [`src/main/java/com/navneet/executespec/billing/entity/`](../src/main/java/com/navneet/executespec/billing/entity/).
- Stripe + Razorpay gateway adapters: [`src/main/java/com/navneet/executespec/billing/gateway/`](../src/main/java/com/navneet/executespec/billing/gateway/).
- HMAC signature verification: [`billing/security/WebhookSignatureVerifier.java`](../src/main/java/com/navneet/executespec/billing/security/WebhookSignatureVerifier.java).
- Activity audit (the unrelated hash-chained audit log for non-payment events): [`auth/service/ActivityAuditService.java`](../src/main/java/com/navneet/executespec/auth/service/ActivityAuditService.java) + `activity_audit` table (V22 + V147 hash chain).
