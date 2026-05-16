# Stripe Price IDs — Operations

How Stripe price identifiers flow from env vars through Spring config into the gateway adapter, how the resolver chain decides which one to use, and the operational steps for rotating or testing them.

This doc covers:

- Every `STRIPE_*_PRICE_ID` env var and where it lands.
- The resolution order inside `StripePaymentGateway.resolvePriceId(...)` — DB column first, env var fallback.
- Supporting env vars: secret/publishable/webhook secrets, gateway-enable toggles, default-gateway selector.
- Currency-to-gateway routing (INR -> Razorpay; USD/EUR/GBP -> Stripe).
- Rotation procedure (DB column preferred; env-var swap is a fallback).
- Test mode vs live mode.
- The webhook event types the integration actually handles.

## Env vars at a glance

The platform reads four Stripe price-id env vars. Three for canonical tiers + one INR override:

| Env var                          | YAML key                          | Tier        | Currency | Notes                                                                                  |
| -------------------------------- | --------------------------------- | ----------- | -------- | -------------------------------------------------------------------------------------- |
| `STRIPE_PRO_PRICE_ID`            | `billing.stripe.pro-price-id`     | PRO         | USD      | Single price across all PRO USD plans (monthly + yearly fall through to same env if no DB row). |
| `STRIPE_ENTERPRISE_PRICE_ID`     | `billing.stripe.enterprise-price-id` | ENTERPRISE | USD      | ENTERPRISE rarely uses Stripe checkout (custom-contract path); env var still wired for self-serve fallback. |
| `STRIPE_TEAM_MONTHLY_PRICE_ID`   | `billing.stripe.team-price-id`    | TEAM        | USD      | Per-seat. Quantity passed at checkout-session create time.                              |
| `STRIPE_TEAM_PRICE_ID_INR`       | `billing.stripe.team-price-id-inr` | TEAM       | INR      | Override when a TEAM customer's billing entity prefers INR (rare; Stripe in India is limited). |

These are wired in `src/main/resources/application.yml` under the `billing.stripe.*` block (placeholder values for local) and overridden via env-only resolution in `application-prod.yml`. Default local + dev values are empty / placeholder; production fills via env.

```yaml
# application.yml
billing:
  stripe:
    enabled: false
    secret-key: ${STRIPE_SECRET_KEY:}
    publishable-key: ${STRIPE_PUBLISHABLE_KEY:}
    webhook-secret: ${STRIPE_WEBHOOK_SECRET:}
    pro-price-id: ${STRIPE_PRO_PRICE_ID:}
    enterprise-price-id: ${STRIPE_ENTERPRISE_PRICE_ID:}
    team-price-id: ${STRIPE_TEAM_MONTHLY_PRICE_ID:}
    team-price-id-inr: ${STRIPE_TEAM_PRICE_ID_INR:}
```

And in `application-prod.yml`:

```yaml
billing:
  default-gateway: stripe
  stripe:
    enabled: ${BILLING_STRIPE_ENABLED:true}
    secret-key: ${STRIPE_SECRET_KEY}
    publishable-key: ${STRIPE_PUBLISHABLE_KEY}
    webhook-secret: ${STRIPE_WEBHOOK_SECRET}
    pro-price-id: ${STRIPE_PRO_PRICE_ID}
    enterprise-price-id: ${STRIPE_ENTERPRISE_PRICE_ID}
    team-price-id: ${STRIPE_TEAM_MONTHLY_PRICE_ID}
    team-price-id-inr: ${STRIPE_TEAM_PRICE_ID_INR}
  razorpay:
    enabled: ${BILLING_RAZORPAY_ENABLED:false}
```

## Resolution order

Source of truth for resolution is in [`billing/gateway/StripePaymentGateway.java`](../src/main/java/com/navneet/executespec/billing/gateway/StripePaymentGateway.java). The two relevant private methods:

```
private String resolvePriceId(String planId);
private String resolvePriceIdByTier(String tier, boolean isInr);
```

The chain that decides which Stripe price id gets used at checkout-session creation:

```
StripePaymentGateway.createCheckoutSession(entity, planId, seats, successUrl, cancelUrl)
        │
        ├─ 1. planRepo.findById(planId).getStripePriceId()       [billing_plan.stripe_price_id column]
        │       └─ if non-blank -> use this
        │
        ├─ 2. resolveTierForPlan(planId) -> tier  (PRO / ENTERPRISE / TEAM)
        │
        ├─ 3. resolvePriceIdByTier(tier, isInr=<billing-entity-currency=INR?>)
        │       ├─ TEAM    + isInr=true     -> ${billing.stripe.team-price-id-inr}
        │       ├─ TEAM    + isInr=false    -> ${billing.stripe.team-price-id}
        │       ├─ ENTERPRISE              -> ${billing.stripe.enterprise-price-id}
        │       ├─ PRO                     -> ${billing.stripe.pro-price-id}
        │       └─ <anything else>         -> ${billing.stripe.pro-price-id}  (FOOTGUN)
        │
        └─ Stripe.SessionCreateParams.builder()
              .setLineItem(price=<resolved>, quantity=<seats>)
              ...
```

There are two foot-guns in this chain:

1. **`STRIPE_PRO_PRICE_ID` as the universal fallback.** `resolvePriceIdByTier` falls back to `pro-price-id` for any tier it doesn't recognise. If you ever add a new tier (e.g. `STARTER`) and forget to plumb it into both `resolvePriceIdByTier` and YAML, customers will silently get charged at the PRO price. Test the resolution path explicitly when you add or rename a tier.

2. **Legacy "MAX" tier alias.** V141 → V144 → V165 walked MAX <-> TEAM through three migrations and the dust settled at "TEAM at $39/seat" (V165). Any DB row that still carries `tier='MAX'` from before V165 would fall to the PRO fallback above. V165 deletes MAX from `tier_config` and migrates `billing_plan.tier='MAX'` rows, but old `pipeline_run.user_tier='MAX'` rows are remapped (not deleted) and could in theory reach this code path if anyone re-runs a historical checkout flow. Unlikely but worth flagging.

The DB column `billing_plan.stripe_price_id` is the preferred source. It's a `VARCHAR(50)` on `billing_plan` (added in V23). Env-var fallback is the safety net for plans that haven't been seeded with a Stripe id yet.

### `resolveTierForPlan(planId)`

Walks `BillingPlanRepository.findById(planId)` and returns `BillingPlan.tier`. If the plan isn't in the DB (e.g. some stale UI sent an old UUID), the call returns null and `resolvePriceId` ends up at the PRO fallback. Best practice: keep `billing_plan.stripe_price_id` populated for every active plan you intend to sell.

## Supporting env vars

| Env var                       | YAML key                         | Default      | Purpose                                                                                       |
| ----------------------------- | -------------------------------- | ------------ | --------------------------------------------------------------------------------------------- |
| `STRIPE_SECRET_KEY`           | `billing.stripe.secret-key`      | (empty)      | `sk_test_*` / `sk_live_*`. Set on `Stripe.apiKey` in `StripePaymentGateway.init()`.            |
| `STRIPE_PUBLISHABLE_KEY`      | `billing.stripe.publishable-key` | (empty)      | `pk_test_*` / `pk_live_*`. Surfaced to frontend for Stripe.js init.                            |
| `STRIPE_WEBHOOK_SECRET`       | `billing.stripe.webhook-secret`  | (empty)      | `whsec_*`. Passed to `Webhook.constructEvent` for signature verification.                      |
| `BILLING_STRIPE_ENABLED`      | `billing.stripe.enabled`         | `false` (local) / `true` (prod) | Controls whether the Stripe webhook endpoint responds and whether `PaymentGatewayRouter.resolve` ever returns the Stripe adapter. |
| `BILLING_RAZORPAY_ENABLED`    | `billing.razorpay.enabled`       | `true`       | Mirror flag for Razorpay; defaults true in dev, env-driven in prod.                            |
| `BILLING_DEFAULT_GATEWAY`     | `billing.default-gateway`        | `razorpay` (dev) / `stripe` (prod) | Used by `PaymentGatewayRouter.resolveDefault()` when the billing entity has no explicit gateway preference. |
| `RAZORPAY_KEY_ID`             | `razorpay.key-id`                | (empty)      | `rzp_test_*` / `rzp_live_*`. Basic auth for Razorpay REST.                                     |
| `RAZORPAY_KEY_SECRET`         | `razorpay.key-secret`            | (empty)      | Paired with key-id. Also used to sign subscription verification HMACs.                          |
| `RAZORPAY_WEBHOOK_SECRET`     | `razorpay.webhook-secret`        | (empty)      | Verifies HMAC-SHA256 over Razorpay webhook bodies.                                              |

The gateway-enable flags interact with `PaymentGatewayRouter` ([`billing/gateway/PaymentGatewayRouter.java`](../src/main/java/com/navneet/executespec/billing/gateway/PaymentGatewayRouter.java)) like this:

```
PaymentGatewayRouter.resolve(billingEntity)
   │
   ├─ entity.getPaymentGateway() == "STRIPE"   && isStripeEnabled()    -> Stripe
   ├─ entity.getPaymentGateway() == "RAZORPAY" && isRazorpayEnabled()  -> Razorpay
   ├─ entity has no preference                                          -> resolveDefault()
   │       ├─ default-gateway=stripe   && stripe.enabled               -> Stripe
   │       ├─ default-gateway=razorpay && razorpay.enabled             -> Razorpay
   │       ├─ stripe.enabled                                           -> Stripe
   │       ├─ razorpay.enabled                                         -> Razorpay
   │       └─ neither enabled                                          -> IllegalStateException
```

The `BillingWebhookController.stripeWebhook` endpoint returns 403 if `billing.stripe.enabled=false` — webhooks must not be processed by deployments that don't speak Stripe.

## Currency routing

The platform pins gateways to currencies via a YAML map in `application.yml`:

```yaml
billing:
  currency-gateway-map:
    INR: RAZORPAY
    USD: STRIPE
    EUR: STRIPE
    GBP: STRIPE
```

In practice the routing is also influenced by the billing entity's stored `payment_gateway` column (`app_user.payment_gateway` / `org.payment_gateway`) and `stripe_customer_id` field. The customer's preference wins; the YAML map is the fallback for new customers without a recorded preference.

The `BILLING_DEFAULT_GATEWAY` env var (effectively the boot-time default) tilts the balance for new customers:

- Prod: `BILLING_DEFAULT_GATEWAY=stripe` (US-first).
- Dev: `BILLING_DEFAULT_GATEWAY=razorpay` (legacy default; India test path is easier with Razorpay's test cards).

The TEAM-INR price-id (`STRIPE_TEAM_PRICE_ID_INR`) exists because Stripe does support INR but with India-specific rules (mandates, ICICI rules). Most INR customers stay on Razorpay; this env is the bypass for the small minority who explicitly want Stripe-INR.

## Rotation procedure

Two rotation paths exist:

### Preferred: rotate via DB column

When you want to swap a Stripe price id (e.g. you re-created the price object in Stripe with different metadata, or you're sunsetting an old price), update `billing_plan.stripe_price_id` for the affected plan rows:

```sql
UPDATE billing_plan
   SET stripe_price_id = 'price_NEW_xxx',
       updated_at = NOW()
 WHERE id = '<plan-uuid>'
   AND active = TRUE;
```

The next `StripePaymentGateway.createCheckoutSession` call for that plan will use the new price. No restart required. The env var stays as a safety net; you can leave it in sync or out-of-date — DB wins.

### Fallback: env-var swap

If you're rotating across all PRO plans (or all TEAM USD plans) and don't want to touch DB rows:

1. Set the new env var value in the deployment manifest (Cloud Run service env or GCE systemd unit).
2. Restart the JVM. `@ConfigurationProperties` / `@Value` bindings reload at startup only.
3. After restart, all plans with `billing_plan.stripe_price_id IS NULL` use the new env var.

The env-var path is slower (requires restart) and broader (affects every plan that falls to the env-var fallback). Prefer the DB column route.

### Coordinating with Stripe

Before either rotation, double-check in Stripe Dashboard:

- The new price object exists and is `active=true` on the right Stripe product.
- Currency matches the plan's `base_amount_usd` / `base_amount_inr` denomination.
- Recurring interval (month / year) matches the plan's `period` (MONTHLY / YEARLY).
- If TEAM tier: ensure the price is `per_unit` (not flat) so `setQuantity(seats)` actually charges per seat.

After rotation, exercise one paid test flow end-to-end (see Test mode section below).

## Test mode vs live mode

Stripe key prefixes determine the mode at the SDK layer:

- `sk_test_*` + `pk_test_*` + `whsec_test_*` -> Stripe test mode. Cards like `4242 4242 4242 4242` work.
- `sk_live_*` + `pk_live_*` + `whsec_live_*` -> Stripe live mode. Real money moves.

The platform doesn't do mode-gating in code; it trusts whatever key it gets. Mixing test and live keys (e.g. `sk_test_*` with a `whsec_live_*`) will cause signature verification to fail with no clear message — symptom is HTTP 401 on every webhook. Quickest debug: check the env var values, then check the Stripe dashboard webhook log.

Local development: `application-local.yml` defaults to `billing.stripe.enabled=false` and `billing.default-gateway=razorpay`. The Razorpay test card noted in the project memory file (`5500 6700 0000 1002`) is the path of least resistance for end-to-end local checkout testing. If you need to exercise Stripe locally:

1. Set `BILLING_STRIPE_ENABLED=true`.
2. Set `STRIPE_SECRET_KEY=sk_test_*`, `STRIPE_PUBLISHABLE_KEY=pk_test_*`, `STRIPE_WEBHOOK_SECRET=whsec_*`.
3. Set the four price-id env vars to the test-mode prices you've created in Stripe Dashboard.
4. Use `stripe listen --forward-to http://localhost:8080/api/webhooks/stripe` (Stripe CLI) to relay webhooks.

Razorpay test mode is keyed by `rzp_test_*` ids; live mode by `rzp_live_*`. The `PlanSeederService` ([`billing/service/PlanSeederService.java`](../src/main/java/com/navneet/executespec/billing/service/PlanSeederService.java)) detects live vs test by inspecting `razorpay.key-id` prefix.

## Webhook events the integration handles

Only four Stripe event types produce side effects today. Everything else lands in `webhook_event_log` with `processing_status=IGNORED` (and counts as a 200 response so Stripe doesn't retry).

| Event type                          | Handler in `BillingService.processStripeWebhook` | Side effect                                                                                          |
| ----------------------------------- | ------------------------------------------------ | ---------------------------------------------------------------------------------------------------- |
| `checkout.session.completed`        | `handleStripeCheckoutCompleted(JsonNode)`        | Activate subscription, link customer-id, sync `org.seat_count` (TEAM tier) via `fetchStripeSubscriptionSeatCount`. Generate first invoice via `InvoiceService.generateStripeSubscriptionInvoice`. Write `payment_audit` rows for USER + ORG. Trigger `BillingNotificationService.sendSubscriptionActivated`. |
| `invoice.paid`                      | `handleStripeInvoicePaid`                        | Extend `billing_subscription.current_period_end`. Generate next-cycle invoice + PDF (stored in `invoice.gateway_invoice_id` + `invoice.gateway_pdf_url`). Record `SUBSCRIPTION_RENEWED` audit row. |
| `invoice.payment_failed`            | `handleStripeInvoicePaymentFailed`               | Flip `billing_subscription.status` to `PAST_DUE`. Fire `BillingNotificationService.sendPaymentFailed`. Record `SUBSCRIPTION_PAYMENT_FAILED` audit row. `DunningScheduler` picks up the row at next 09:00 IST cron. |
| `customer.subscription.deleted`     | `handleStripeSubscriptionDeleted`                | Call `BillingService.downgradeToFree(sub)` — flip user.tier=FREE, org.tier=FREE, sub.status=DOWNGRADED. Fire `sendDowngradedToFree` email. Record `TIER_DOWNGRADED_TO_FREE` audit row.       |

The Razorpay path handles a longer list — `subscription.authenticated`, `subscription.charged`, `subscription.pending`, `subscription.halted`, `subscription.cancelled`, `subscription.completed`, plus one-time `payment.captured` / `order.paid` for top-ups. See [`docs/payment-history.md`](payment-history.md) for the cross-gateway audit-trail story.

Anything outside these four Stripe types or those eight Razorpay subscription/payment types is ignored. Stripe sends ~40 event types — if you ever need to react to `payment_intent.succeeded` or `customer.updated`, the place to add a handler is the dispatch switch inside `BillingService.processStripeWebhook`. The new event will be persisted to `webhook_event_log` regardless, so you can also bolt on a one-time backfill if needed.

## Quick reference

**Need to know which env var feeds a tier?** Use this table:

| Customer wants            | Currency | Env var used                  | DB column override?                            |
| ------------------------- | -------- | ----------------------------- | ---------------------------------------------- |
| PRO plan (Solo Monthly USD / Yearly USD / Pro Monthly USD / Pro Yearly USD) | USD      | `STRIPE_PRO_PRICE_ID`         | `billing_plan.stripe_price_id` per row.        |
| TEAM plan (per-seat, USD) | USD      | `STRIPE_TEAM_MONTHLY_PRICE_ID` | `billing_plan.stripe_price_id` per row.        |
| TEAM plan (per-seat, INR) | INR      | `STRIPE_TEAM_PRICE_ID_INR`    | `billing_plan.stripe_price_id` per row.        |
| ENTERPRISE plan           | USD      | `STRIPE_ENTERPRISE_PRICE_ID`  | `billing_plan.stripe_price_id` per row.        |
| Any INR plan (FREE/PRO equivalents) on Razorpay  | INR | n/a (Razorpay) | `billing_plan.razorpay_plan_id` per row. |
| FREE plan                 | n/a      | n/a (no gateway, no checkout) | n/a — V25 backfills users with `billing_subscription.status='FREE'`. |

**Need to verify which gateway will be used for a customer?**

```
1. Check entity.getPaymentGateway() on app_user.payment_gateway / org.payment_gateway
   - If "STRIPE" or "RAZORPAY" and the matching gateway is enabled, that's the answer.
2. Otherwise, BILLING_DEFAULT_GATEWAY env (prod: stripe, dev: razorpay).
3. If only one gateway is enabled, that one wins regardless of preference.
4. If neither enabled, PaymentGatewayRouter throws IllegalStateException at request time.
```

**Need to verify which price id will be sent to Stripe for a specific checkout?**

```
1. Look up the plan row: SELECT id, tier, stripe_price_id FROM billing_plan WHERE id = '<planId>'.
2. If stripe_price_id is non-blank, that's it.
3. Otherwise tier -> env var:
     - PRO        -> STRIPE_PRO_PRICE_ID
     - ENTERPRISE -> STRIPE_ENTERPRISE_PRICE_ID
     - TEAM (USD) -> STRIPE_TEAM_MONTHLY_PRICE_ID
     - TEAM (INR) -> STRIPE_TEAM_PRICE_ID_INR
     - other      -> STRIPE_PRO_PRICE_ID (FOOTGUN)
```

## Cross-references

- Webhook flow, idempotency, and the full audit-trail picture: [`docs/payment-history.md`](payment-history.md).
- Gateway abstractions: [`billing/gateway/PaymentGateway.java`](../src/main/java/com/navneet/executespec/billing/gateway/PaymentGateway.java), [`StripePaymentGateway.java`](../src/main/java/com/navneet/executespec/billing/gateway/StripePaymentGateway.java), [`RazorpayGateway.java`](../src/main/java/com/navneet/executespec/billing/gateway/RazorpayGateway.java), [`PaymentGatewayRouter.java`](../src/main/java/com/navneet/executespec/billing/gateway/PaymentGatewayRouter.java).
- HMAC verification (Razorpay): [`billing/security/WebhookSignatureVerifier.java`](../src/main/java/com/navneet/executespec/billing/security/WebhookSignatureVerifier.java). Stripe verification is in the SDK (`Webhook.constructEvent`).
- Billing service orchestration: [`billing/service/BillingService.java`](../src/main/java/com/navneet/executespec/billing/service/BillingService.java).
- Plan seeding (Razorpay): [`billing/service/PlanSeederService.java`](../src/main/java/com/navneet/executespec/billing/service/PlanSeederService.java).
- Env-var template (root): [`.env.template`](../.env.template).
- Spring config: `src/main/resources/application.yml`, `application-dev.yml`, `application-prod.yml`, `application-local.yml`.
