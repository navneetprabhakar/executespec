# Payment history & audit

How every paid event in ExecuteSpec is captured, surfaced, retained, and (when
applicable) reconciled with the upstream payment gateway. This doc is sourced
from the code as it stands today — every claim below maps to a file and line
range you can grep.

## Stores

Two append-only tables back the audit surface; one tracks the business event,
the other tracks the raw webhook envelope.

### `payment_audit` — business-level events

Created in
[`V4__user_auth_and_org.sql`](../src/main/resources/db/migration/V4__user_auth_and_org.sql)
(lines 146–163, original 5-column shape) and expanded in
[`V55__payment_audit_expansion.sql`](../src/main/resources/db/migration/V55__payment_audit_expansion.sql)
to add gateway provenance columns + idempotency.

Entity:
[`auth/entity/PaymentAudit.java`](../src/main/java/com/navneet/executespec/auth/entity/PaymentAudit.java).

| Column               | Type               | Notes                                                                                          |
| -------------------- | ------------------ | ---------------------------------------------------------------------------------------------- |
| `id`                 | `VARCHAR(255)` PK  | Service-generated `UUID.randomUUID().toString()` (see `PaymentAuditService.record`, line 72).  |
| `entity_type`        | `VARCHAR(32)` NN   | `USER` or `ORG`. Carries the legacy "what does this row attach to" axis.                       |
| `entity_id`          | `VARCHAR(255)` NN  | The user-id or org-id matching `entity_type`.                                                  |
| `event_type`         | `VARCHAR(64)` NN   | Discriminator. See the full enumeration table below.                                           |
| `old_tier`           | `VARCHAR(32)`      | Pre-event tier (FREE / STARTER / PRO / TEAM / ENTERPRISE / MAX). Nullable on first assignment. |
| `new_tier`           | `VARCHAR(32)`      | Post-event tier. Nullable on pure refunds (no tier change).                                    |
| `payment_ref_id`     | `VARCHAR(255)`     | Legacy external-ref column. Newer call sites use `gateway_payment_id` below instead.           |
| `performed_by`       | `VARCHAR(255)`     | Actor user-id — admin id for admin moves, user-id for self-serve, == `user_id` for webhooks.   |
| `notes`              | `TEXT`             | Human reason e.g. `"Upgrade: PRO → TEAM"`, `"Cancels at period end: <ts>"`, `"Org created"`.   |
| `gateway_provider`   | `VARCHAR(20)`      | V55. `STRIPE` or `RAZORPAY` (or null for admin / system moves).                                |
| `gateway_event_id`   | `VARCHAR(255)`     | V55. Upstream webhook event id when the row was caused by a webhook.                           |
| `gateway_payment_id` | `VARCHAR(255)`     | V55. Razorpay payment id / Stripe payment-intent id.                                           |
| `currency`           | `VARCHAR(8)`       | V55. ISO-4217 (`INR`, `USD`). Set on subscription activations and refunds.                     |
| `amount_minor`       | `BIGINT`           | V55. Amount in the smallest currency unit (paise / cents). Refunds store positive amount.      |
| `metadata`           | `JSONB`            | V55. Open-ended extension column — not populated by any current call site.                     |
| `idempotency_key`    | `VARCHAR(255)`     | V55. `"<gateway>:<gateway_event_id>"` when both are present. Indexed for dedup queries.        |
| `user_id`            | `VARCHAR(255)`     | V55. Direct user pointer (in addition to `entity_id`) so org-scoped events can resolve a user. |
| `org_id`             | `VARCHAR(255)`     | V55. Direct org pointer. Powers the org-scoped `/api/billing/payment-history` query.           |
| `created_at`         | `TIMESTAMPTZ` NN   | Defaults to `Instant.now()` in the entity builder.                                             |

Indexes (V55 + V4):
`(entity_type, entity_id)`, `event_type`, `gateway_provider`, `created_at`,
`idempotency_key`, `user_id`, `org_id`. Nothing has `UNIQUE` — the
`idempotency_key` index exists for fast dedup probes but is not enforced as a
constraint, so duplicate-key inserts are silently allowed if a call site
double-records. Today no call site does.

### `webhook_event_log` — raw webhook envelope (Stripe-only)

Created in
[`V55__payment_audit_expansion.sql`](../src/main/resources/db/migration/V55__payment_audit_expansion.sql)
(lines 24–43). Entity:
[`billing/entity/WebhookEventLog.java`](../src/main/java/com/navneet/executespec/billing/entity/WebhookEventLog.java).

| Column              | Type             | Notes                                                                       |
| ------------------- | ---------------- | --------------------------------------------------------------------------- |
| `id`                | `BIGSERIAL` PK   | Auto-incremented surrogate.                                                 |
| `gateway`           | `VARCHAR(20)` NN | `STRIPE` (today only Stripe writes here — see "Asymmetry" note below).      |
| `event_id`          | `VARCHAR(255)` NN | Stripe event id (`evt_...`).                                               |
| `event_type`        | `VARCHAR(128)` NN | e.g. `checkout.session.completed`.                                         |
| `payload_hash`      | `VARCHAR(64)`    | SHA-256 of the raw payload bytes (`BillingWebhookController.sha256`).       |
| `raw_payload`       | `TEXT`           | The exact raw body Stripe POSTed. Kept for forensic replay.                 |
| `processing_status` | `VARCHAR(32)` NN | `RECEIVED` (default), `PROCESSED`, `IGNORED` (duplicate replay), `FAILED`.  |
| `error_message`     | `TEXT`           | Set when `processing_status=FAILED`.                                        |
| `attempts`          | `INT` NN         | Bumped to N+1 on duplicate replay.                                          |
| `created_at`        | `TIMESTAMPTZ` NN | `Instant.now()` at first POST.                                              |
| `processed_at`      | `TIMESTAMPTZ`    | Stamped when the handler reaches the success path.                          |

`UNIQUE (gateway, event_id)` enforces dedup at the database level — duplicate
Stripe deliveries fail the insert and route the controller through the
"increment attempts, mark IGNORED" branch
([`BillingWebhookController.logWebhookEvent`](../src/main/java/com/navneet/executespec/billing/controller/BillingWebhookController.java#L107-L140)).

### `billing_webhook_event` — full webhook envelope (Razorpay + Stripe)

Predates V55. Created in
[`V23__billing_system.sql`](../src/main/resources/db/migration/V23__billing_system.sql)
(lines 70–84). Entity:
[`billing/entity/BillingWebhookEvent.java`](../src/main/java/com/navneet/executespec/billing/entity/BillingWebhookEvent.java).

| Column          | Type             | Notes                                                                  |
| --------------- | ---------------- | ---------------------------------------------------------------------- |
| `id`            | `UUID` PK        |                                                                        |
| `gateway`       | `VARCHAR(20)` NN | `RAZORPAY` and `STRIPE` (both write here from `BillingService`).       |
| `event_id`      | `VARCHAR(200)` NN | Razorpay: `<eventType>_<entityId>_<created_at>` synthesized id.       |
| `event_type`    | `VARCHAR(80)` NN | e.g. `subscription.charged`, `payment.captured`.                       |
| `raw_payload`   | `JSONB` NN       | Parsed payload column.                                                 |
| `processed`     | `BOOLEAN` NN     | Set true after `processWebhookEvent` succeeds.                         |
| `processed_at`  | `TIMESTAMPTZ`    |                                                                        |
| `error_message` | `TEXT`           | (Unused today — errors are logged but not written to the row.)         |
| `created_at`    | `TIMESTAMPTZ` NN |                                                                        |

`UNIQUE INDEX idx_webhook_dedup ON (gateway, event_id)` enforces dedup —
`BillingService.processRazorpayWebhook` line 687 and
`processStripeWebhook` line 893 both probe `existsByGatewayAndEventId(...)`
before saving.

#### Asymmetry between the two webhook tables (read this before debugging)

There are **two webhook tables** and they are not symmetrically populated:

| Path                                                | Writes to `webhook_event_log` | Writes to `billing_webhook_event` |
| --------------------------------------------------- | :---------------------------: | :-------------------------------: |
| Razorpay `POST /api/webhooks/razorpay`              |              no               |                yes                |
| Stripe `POST /api/webhooks/stripe`                  |              yes              |                yes                |

`BillingWebhookController.handleStripeWebhook` calls `logWebhookEvent(...)`
which targets `webhook_event_log`, then forwards the event to
`BillingService.processStripeWebhook` which ALSO writes a row to
`billing_webhook_event` (lines 893–907). Razorpay only hits the latter.

The admin "list webhook events" endpoint
[`GET /api/admin/webhook-events`](../src/main/java/com/navneet/executespec/billing/controller/PaymentAuditController.java#L60-L74)
queries `webhook_event_log` exclusively, so Razorpay webhooks DO NOT appear
there. Razorpay raw payloads are queryable only via direct SQL against
`billing_webhook_event`.

## Service: `PaymentAuditService`

Single writer surface for `payment_audit`. Lives at
[`auth/service/PaymentAuditService.java`](../src/main/java/com/navneet/executespec/auth/service/PaymentAuditService.java).

Two overloaded `record(...)` methods:

1. **Legacy 7-arg variant** (lines 24–47) — `entityType, entityId, eventType,
   oldTier, newTier, performedBy, notes`. Delegates to the full variant with
   all gateway fields null. Used by admin-driven tier moves and free org
   provisioning.
2. **Full 14-arg variant** (lines 50–98) — adds `gatewayProvider,
   gatewayEventId, gatewayPaymentId, currency, amountMinor, userId, orgId`.
   Used by `BillingService` for everything that touches money.

Behavioral details:

- `id` = `UUID.randomUUID().toString()` — generated server-side, NOT from the
  gateway event id, so duplicate webhooks (which are blocked upstream in
  `processStripeWebhook` / `processRazorpayWebhook` via `webhookRepo.existsBy...`)
  cannot collide on PK.
- `idempotency_key` is computed inside the service as
  `gatewayProvider + ":" + gatewayEventId` IFF both are non-null; otherwise
  null. **The field is informational** — the index exists, but there is no
  unique constraint and `record(...)` doesn't probe for an existing key
  before inserting. Idempotency is enforced upstream by the webhook stores.
- Every write also `log.info(...)`s a one-liner: `Payment audit: <entityType>
  <entityId> <eventType> → <newTier> gateway=<provider> (by <performedBy>)`.
  Useful when the audit table is unreachable but logs aren't.

Read methods (lines 102–116):

- `findAll(Pageable)` → newest first.
- `findByOrg(orgId, Pageable)` → newest first.
- `findByUser(userId, Pageable)` → newest first.
- `findByEntity(entityType, entityId)` → list, newest first.

CSV export (lines 120–151):

- Header: `id,entity_type,entity_id,event_type,old_tier,new_tier,gateway,currency,amount_minor,performed_by,notes,created_at`.
- Standard RFC-4180-ish quoting (`csvField` escapes commas, quotes, newlines).
- Used by `GET /api/admin/payment-audit/export`. Fetches the first **10,000**
  rows via `PageRequest.of(0, 10_000)` — so the export silently truncates on
  large installs. (No streaming / paginated export today.)

## Event-type enumeration (every `record(...)` call site)

Every site that writes to `payment_audit` today, with the exact event-type
string and the fields the call site populates. The V4 migration comment
mentions `PLAN_DOWNGRADED`, `PAYMENT_RECEIVED`, `PAYMENT_FAILED`, and
`MANUAL_OVERRIDE` as historical strings but no current code path emits them.

### Tier moves (admin path)

| Event type      | Call site                                                                                                                                                       | Triggers when                       | Fields populated                                                                                              |
| --------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| `PLAN_UPGRADED` | [`SuperAdminController.updateUserTier`](../src/main/java/com/navneet/executespec/auth/controller/SuperAdminController.java#L366-L367) line 366                  | SUPER_ADMIN flips a user's tier     | `entity_type=USER`, `entity_id=user-id`, `old_tier`, `new_tier`, `performed_by=admin-id`. Legacy 7-arg.       |
| `PLAN_UPGRADED` | [`OrgService` line 752](../src/main/java/com/navneet/executespec/auth/service/OrgService.java#L752)                                                             | Org tier is changed by admin/system | `entity_type=ORG`, `entity_id=org-id`, `old_tier`, `new_tier`, `performed_by`. Legacy 7-arg.                  |
| `PLAN_ASSIGNED` | [`OrgService` line 97](../src/main/java/com/navneet/executespec/auth/service/OrgService.java#L97)                                                               | New org created via self-serve      | `entity_type=ORG`, `entity_id=org-id`, `new_tier=org.tier`, `performed_by=creatorUserId`, `notes="Org created"`. |
| `PLAN_ASSIGNED` | [`OrgService` line 303](../src/main/java/com/navneet/executespec/auth/service/OrgService.java#L303)                                                             | SUPER_ADMIN creates a PRO org       | Same as above with `new_tier="PRO"`, `notes="Created by super admin"`.                                        |

### Subscription lifecycle (paid path)

All five sites are inside
[`BillingService.java`](../src/main/java/com/navneet/executespec/billing/service/BillingService.java)
and use the 14-arg variant. They write `entity_type=USER` and direct
`user_id` + `org_id` pointers, so the org-scoped history view can pull them.

| Event type              | Line range  | Triggers when                                                                                                                | Gateway fields set                                                                                              |
| ----------------------- | ----------- | ---------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| `SUBSCRIPTION_UPGRADED` | 255–269     | `subscribe()` finds an existing active sub at a lower tier and replaces it with a higher one (after refund).                 | `gateway_provider=existing.paymentGateway` (STRIPE/RAZORPAY). No payment id (payment hasn't happened yet).      |
| `SUBSCRIPTION_CREATED`  | 343–357     | `subscribe()` returns a checkout/init result (pre-payment).                                                                  | `gateway_provider=gateway.gatewayType()`. No payment id (post-checkout webhook fires `SUBSCRIPTION_ACTIVATED`). |
| `SUBSCRIPTION_ACTIVATED`| 409–423     | `verifyAndActivate(...)` — user has completed Razorpay client-side checkout and the payment signature verifies.              | `gateway_provider=sub.paymentGateway`, `gateway_payment_id=request.razorpayPaymentId()`, `currency="INR"`.      |
| `SUBSCRIPTION_CANCELLED`| 481–495     | `cancel()` — user cancels, schedules period-end downgrade. (Webhook later flips to FREE; no separate audit row for that.)    | `gateway_provider=sub.paymentGateway`, no payment id, `new_tier="FREE"`.                                        |
| `PRORATED_REFUND`       | 1301–1315   | `issueProratedRefund(...)` — invoked inside `subscribe()` when upgrading mid-period; calls `RazorpayGateway.refundPartial`.  | `gateway_provider=existing.paymentGateway`, `gateway_payment_id=invoice.gatewayPaymentId`, `currency="INR"`, `amount_minor=refundAmount`. |

#### What is NOT in `payment_audit`

- **Top-up purchases.** `RunTopupService.verifyAndCredit` (line 218) and
  `grantBonusRuns` (line 301) write `TOPUP_PURCHASED` / `TOPUP_ADMIN_GRANTED`
  to the **generic activity audit** (`ActivityAuditService.record(...)`), not
  `payment_audit`. So when a customer asks "where's my $5.99 top-up?", the
  answer is in `activity_audit` (entity_type=`BILLING`), not the payment audit
  table. The `notes` JSON has `quantity` + `orderId` + `amountInr`.
- **Webhook-driven Razorpay subscription state transitions** (`subscription.charged`,
  `subscription.halted`, `subscription.cancelled` — see `processWebhookEvent`
  lines 737–810). They mutate `billing_subscription.status` and may
  `downgradeToFree(...)` (line 1324), but they do NOT emit a `payment_audit`
  row. The raw webhook payload remains queryable in `billing_webhook_event`.
- **Stripe subscription state transitions** (`invoice.paid`,
  `invoice.payment_failed`, `customer.subscription.deleted` — `processStripeWebhook`
  switch at line 912). Same pattern: subscription mutated, no `payment_audit` row.

This is a known asymmetry: the audit table is "transitions a human or admin
caused" (subscribe / cancel / refund / admin tier flips); the webhook tables
are "events the gateway told us about." Reconstructing a full subscription
lifecycle requires joining both. The DSAR query
[`PaymentAuditRepository.findByUserIdOrOrgId`](../src/main/java/com/navneet/executespec/auth/repository/PaymentAuditRepository.java#L30-L35)
pulls from `payment_audit` only.

## Webhook flow

Both gateways post unauthenticated (in the JWT sense) to
[`BillingWebhookController`](../src/main/java/com/navneet/executespec/billing/controller/BillingWebhookController.java);
authentication is the upstream HMAC signature. Both endpoints **always return
200** so the gateway doesn't retry on a downstream bug — the raw payload is
already persisted by the time the handler errors, so reprocessing is a SQL
exercise.

### Razorpay — `POST /api/webhooks/razorpay`

1. **Header check.** Reject 401 if `X-Razorpay-Signature` missing
   (`BillingWebhookController` lines 44–47).
2. **HMAC verification.**
   [`WebhookSignatureVerifier.verifyWebhookSignature`](../src/main/java/com/navneet/executespec/billing/security/WebhookSignatureVerifier.java#L28-L38).
   Algorithm: `HMAC-SHA256(rawPayload, razorpay.webhookSecret)` using
   `javax.crypto.Mac` + `apache-commons-codec` for hex. Comparison via
   `MessageDigest.isEqual` (constant-time, prevents timing leaks).
3. **Dedup key.**
   `<eventType>_<subId-or-orderId-or-paymentId>_<createdAt>` —
   `BillingService.processRazorpayWebhook` lines 661–684. Probed against
   `billing_webhook_event(gateway='RAZORPAY', event_id=...)` line 687. If
   present → return early without processing.
4. **Persist raw.** Insert into `billing_webhook_event` with `processed=false`,
   raw payload as JSONB.
5. **Dispatch.** `processWebhookEvent(eventType, entityId, payload)` lines
   719–811:
   - `order.paid` / `payment.captured` → `handleOrderPaymentWebhook` (top-up
     credit flow, lines 817–876).
   - `subscription.authenticated` → mark sub `ACTIVE`.
   - `subscription.charged` → extend period, ensure tier, generate renewal
     invoice + email.
   - `subscription.pending` → mark `PAST_DUE`, send payment-failed email.
   - `subscription.halted` → all retries exhausted, `downgradeToFree(...)`.
   - `subscription.cancelled` / `subscription.completed` → mark cancelled and
     downgrade if past period end.
   - default → log at DEBUG, no-op.
6. **Mark processed.** `event.setProcessed(true); event.setProcessedAt(...)`.

### Stripe — `POST /api/webhooks/stripe`

1. **Header check.** Reject 401 if `Stripe-Signature` missing
   (lines 75–77 of `BillingWebhookController`). Reject 403 if
   `gatewayRouter.isStripeEnabled()=false`.
2. **HMAC verification.**
   [`StripePaymentGateway.handleWebhook`](../src/main/java/com/navneet/executespec/billing/gateway/StripePaymentGateway.java#L126-L149)
   calls `com.stripe.net.Webhook.constructEvent(payload, signatureHeader,
   webhookSecret)` from the official Stripe SDK. The SDK enforces tolerance
   on the `t=` timestamp in the signature header (default 5 minutes; not
   tunable from our config) and verifies the `v1=` HMAC-SHA256 over
   `<timestamp>.<payload>` using `billing.stripe.webhook-secret`.
3. **Dedup key.** Stripe's own `evt_...` event id. Probed in
   `webhook_event_log(gateway='STRIPE', event_id=...)` by
   `BillingWebhookController.logWebhookEvent` line 112 (duplicates flip the
   row to `processing_status=IGNORED` and bump `attempts`); ALSO probed in
   `billing_webhook_event` by `BillingService.processStripeWebhook` line 893.
4. **Persist raw.** Both `webhook_event_log` and `billing_webhook_event` get
   a row.
5. **Dispatch.** `BillingService.processStripeWebhook` switch at line 912:
   - `checkout.session.completed` → `handleStripeCheckoutCompleted` (lines
     955–1039). Persists `stripeCustomerId` on user/org, fetches seat
     quantity from the Subscription, calls `setStatus("ACTIVE")` etc.
   - `invoice.paid` → `handleStripeInvoicePaid` (lines 1041–1090). Extends
     subscription period, calls `upgradeTier(...)`, generates invoice via
     `invoiceService.generateStripeSubscriptionInvoice` and emails it.
   - `invoice.payment_failed` → mark `PAST_DUE`, send payment-failed email.
   - `customer.subscription.deleted` → `downgradeToFree(sub)`.
   - default → log at DEBUG.
6. **Mark processed.** Same as Razorpay.

#### Stripe-specific note: no `PaymentAudit` rows from webhooks

The Stripe webhook handlers DO update the subscription state and write to
`webhook_event_log` + `billing_webhook_event`, but they DO NOT call
`paymentAuditService.record(...)`. The pre-checkout `SUBSCRIPTION_CREATED`
audit row is written by `subscribe()` BEFORE the Stripe checkout happens; for
Stripe customers there is no `SUBSCRIPTION_ACTIVATED` row because
`verifyAndActivate` is the Razorpay-only post-checkout endpoint.
Reconstructing a Stripe customer's lifecycle requires joining `payment_audit`
(for the create + cancel + refund events) with `billing_webhook_event` (for
the actual `invoice.paid` activation + renewals).

## User-facing surface

### `GET /api/billing/payment-history`

Lives in
[`PaymentAuditController.paymentHistory`](../src/main/java/com/navneet/executespec/billing/controller/PaymentAuditController.java#L77-L85)
(the `@GetMapping` uses the absolute path `/api/billing/payment-history`).

Scope:
- **`@PreAuthorize("hasAnyRole('ORG_ADMIN', 'BILLING_ADMIN', 'SUPER_ADMIN')")`.**
- Required query param `orgId`. The controller does NOT cross-check the
  caller's org-membership before the query — it just delegates to
  `auditService.findByOrg(orgId, pageable)`. **A BILLING_ADMIN of one org
  CAN read another org's payment history by passing a different `orgId`.**
  This is a known authorization gap; SUPER_ADMIN access is the only intended
  cross-org path.
- Returns a `Page<PaymentAudit>`. Default page size 50, max 200 (`size`
  silently clamped by `Math.min(size, 200)`).

### Admin surface

| Method | Path                                | Role             | Source                                                                                              |
| ------ | ----------------------------------- | ---------------- | --------------------------------------------------------------------------------------------------- |
| `GET`  | `/api/admin/payment-audit`          | SUPER_ADMIN only | `PaymentAuditController.listAudit` (lines 28–45). Optional `orgId` / `userId` query filters.        |
| `GET`  | `/api/admin/payment-audit/export`   | SUPER_ADMIN only | `PaymentAuditController.exportAuditCsv` (lines 48–57). Returns `text/csv`. Truncates at 10k rows.   |
| `GET`  | `/api/admin/webhook-events`         | SUPER_ADMIN only | `PaymentAuditController.listWebhookEvents` (lines 60–74). Queries `webhook_event_log` (Stripe-only). |

### DSAR data-export surface

[`DsarController`](../src/main/java/com/navneet/executespec/auth/dsar/DsarController.java#L122)
calls `paymentRepo.findByUserIdOrOrgId(user.getId(), user.getOrgId())` to
include every audit row that touches the user OR their org. The query lives
at
[`PaymentAuditRepository` lines 30–35](../src/main/java/com/navneet/executespec/auth/repository/PaymentAuditRepository.java#L30-L35).
Note this pulls from `payment_audit` only; webhook tables are not exported.

## Retention

**No purge job exists.** The three schedulers in
[`billing/scheduler/`](../src/main/java/com/navneet/executespec/billing/scheduler/)
— `DunningScheduler` (daily 9 AM), `PeriodEndDowngradeScheduler` (every 30
min), and `RenewalReminderScheduler` (daily 9:15 AM) — none touch
`payment_audit`, `webhook_event_log`, or `billing_webhook_event`. No
`application.yml` key controls retention. No `@Scheduled` method in the
billing package references either table.

The tables are append-only and grow forever. For GA at current volume this is
fine; if it becomes a problem, the right knob is a Postgres partitioning
strategy on `created_at` plus a manual archive job — not a JPA scheduled
delete (the DSAR query needs full history, so any purge would have to
coordinate with the data-export contract).

## Cross-references

| Concern                          | Code path                                                                                                                                                                                                  |
| -------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Audit entity                     | [`auth/entity/PaymentAudit.java`](../src/main/java/com/navneet/executespec/auth/entity/PaymentAudit.java)                                                                                                |
| Audit writer + reader            | [`auth/service/PaymentAuditService.java`](../src/main/java/com/navneet/executespec/auth/service/PaymentAuditService.java)                                                                                |
| Audit repository                 | [`auth/repository/PaymentAuditRepository.java`](../src/main/java/com/navneet/executespec/auth/repository/PaymentAuditRepository.java)                                                                    |
| Admin + user-facing endpoints    | [`billing/controller/PaymentAuditController.java`](../src/main/java/com/navneet/executespec/billing/controller/PaymentAuditController.java)                                                              |
| Webhook entrypoint               | [`billing/controller/BillingWebhookController.java`](../src/main/java/com/navneet/executespec/billing/controller/BillingWebhookController.java)                                                          |
| Razorpay HMAC                    | [`billing/security/WebhookSignatureVerifier.java`](../src/main/java/com/navneet/executespec/billing/security/WebhookSignatureVerifier.java)                                                              |
| Stripe HMAC                      | `com.stripe.net.Webhook.constructEvent` (SDK) via [`billing/gateway/StripePaymentGateway.java`](../src/main/java/com/navneet/executespec/billing/gateway/StripePaymentGateway.java#L126-L149)            |
| Webhook event log (Stripe)       | [`billing/entity/WebhookEventLog.java`](../src/main/java/com/navneet/executespec/billing/entity/WebhookEventLog.java)                                                                                    |
| Webhook event log (both gateways)| [`billing/entity/BillingWebhookEvent.java`](../src/main/java/com/navneet/executespec/billing/entity/BillingWebhookEvent.java)                                                                            |
| Razorpay webhook dispatch        | `BillingService.processRazorpayWebhook` + `processWebhookEvent` (BillingService.java lines 650–811)                                                                                                       |
| Stripe webhook dispatch          | `BillingService.processStripeWebhook` + handlers (BillingService.java lines 886–1132)                                                                                                                    |
| Original schema                  | [`V4__user_auth_and_org.sql`](../src/main/resources/db/migration/V4__user_auth_and_org.sql) (V4 — 5-column `payment_audit`)                                                                              |
| Gateway-aware expansion          | [`V55__payment_audit_expansion.sql`](../src/main/resources/db/migration/V55__payment_audit_expansion.sql) (adds gateway columns + `webhook_event_log`)                                                   |
| Billing tables                   | [`V23__billing_system.sql`](../src/main/resources/db/migration/V23__billing_system.sql) (`billing_plan`, `billing_subscription`, `billing_webhook_event`, `billing_usage`)                                |
