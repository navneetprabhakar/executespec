# Stripe price IDs — ops checklist

Operational reference for the four Stripe price IDs the platform reads from
the environment, plus the supporting Stripe configuration (secret key,
webhook secret, gateway-enable flags, currency routing). All claims below
trace to a single source of truth: the code on `main` today.

## SKUs the platform expects

Four `${billing.stripe.*-price-id*}` keys are wired from
[`application.yml`](../src/main/resources/application.yml) (lines 771–782)
into
[`StripePaymentGateway.java`](../src/main/java/com/navneet/executespec/billing/gateway/StripePaymentGateway.java)
(lines 36–46).

| YAML key                          | `@Value` field      | Env var                      | Role                                                                                                                |
| --------------------------------- | ------------------- | ---------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| `billing.stripe.pro-price-id`     | `proPriceId`        | `STRIPE_PRO_PRICE_ID`        | PRO tier monthly subscription. Also the ultimate fallback when `resolvePriceId(planId)` cannot map the plan to anything else (StripePaymentGateway lines 361, 391, 404). |
| `billing.stripe.enterprise-price-id` | `enterprisePriceId` | `STRIPE_ENTERPRISE_PRICE_ID` | ENTERPRISE tier. Read only when `BillingPlan.tier=ENTERPRISE` (or the legacy planId string contains `"enterprise"`). |
| `billing.stripe.team-price-id`    | `teamPriceId`       | `STRIPE_TEAM_PRICE_ID`       | TEAM tier USD per-seat. **Must be a Stripe Price with per-unit pricing enabled** — `LineItem.setQuantity(seats)` is honoured (StripePaymentGateway lines 88–93). Self-serve seat range is 2–20 (`BillingService.subscribe` lines 178–183). |
| `billing.stripe.team-price-id-inr`| `teamPriceIdInr`    | `STRIPE_TEAM_PRICE_ID_INR`   | TEAM tier INR per-seat. Optional. Picked when `BillingPlan.baseAmountInr > 0 && baseAmountUsd == 0` (StripePaymentGateway line 375) OR when the legacy planId string contains both `"team"` and `"inr"` (line 387–389). |

There is no `STARTER_PRICE_ID` or `MAX_PRICE_ID`. STARTER is mapped to the
auth-tier `FREE` inside `BillingService.upgradeTier` (line 1143) and never
checks out via Stripe. MAX was retired in
[`V165__drop_max_tier_team_at_39.sql`](../src/main/resources/db/migration/V165__drop_max_tier_team_at_39.sql);
legacy MAX subscriptions are now folded into TEAM via the switch fall-through
at line 1148.

## Plan tier → price ID resolution

`StripePaymentGateway.resolvePriceId(planId)` (lines 360–392) runs three
steps in order:

1. **DB column lookup.** If `planId` parses as a UUID, look up the row in
   `billing_plan` and use its `stripe_price_id` column if non-blank. This is
   the authoritative path once ops has seeded the column post-Stripe-Product
   creation; it makes test↔live swaps a SQL update, not a code change. The
   `billing_plan.stripe_price_id` column comes from
   [`V23__billing_system.sql`](../src/main/resources/db/migration/V23__billing_system.sql)
   line 25.
2. **Tier + currency fallback.** If the DB column is blank, branch on
   `plan.tier`:
   - `ENTERPRISE` → `enterprisePriceId`
   - `TEAM` → `teamPriceIdInr` IFF the plan is INR-priced AND `teamPriceIdInr`
     is set; otherwise `teamPriceId`
   - `PRO` (and default) → `proPriceId`
   (`resolvePriceIdByTier`, lines 394–406.)
3. **Legacy string pattern.** If `planId` is NOT a UUID (legacy callers pass
   raw strings), match against the lowercased planId: `"enterprise"`,
   `"team"` (with optional `"inr"` modifier), else default to `proPriceId`
   (lines 384–391).

| Tier resolver input                                  | Resolved price ID env var                    |
| ---------------------------------------------------- | --------------------------------------------- |
| `BillingPlan.tier=PRO`                               | `STRIPE_PRO_PRICE_ID`                         |
| `BillingPlan.tier=TEAM` (USD-priced plan row)        | `STRIPE_TEAM_PRICE_ID`                        |
| `BillingPlan.tier=TEAM` (INR-priced plan row + INR id set) | `STRIPE_TEAM_PRICE_ID_INR`              |
| `BillingPlan.tier=TEAM` (INR plan, no INR id set)    | `STRIPE_TEAM_PRICE_ID` (USD price used for INR plan — known footgun) |
| `BillingPlan.tier=ENTERPRISE`                        | `STRIPE_ENTERPRISE_PRICE_ID`                  |
| `BillingPlan.tier=MAX` (legacy)                      | `STRIPE_PRO_PRICE_ID` (falls through default — likely wrong; MAX rows should be migrated to TEAM) |
| `BillingPlan.tier=STARTER` / `FREE` / unknown        | `STRIPE_PRO_PRICE_ID` (default fallback — MUST not actually checkout; subscribe() blocks FREE on line 203) |
| `BillingPlan.stripe_price_id` non-blank              | the DB value, bypasses everything above       |

## Supporting Stripe environment variables

Beyond the four price IDs, the Stripe gateway needs three more variables and
two gateway-enable flags. From
[`StripePaymentGateway`](../src/main/java/com/navneet/executespec/billing/gateway/StripePaymentGateway.java)
+ [`PaymentGatewayRouter`](../src/main/java/com/navneet/executespec/billing/gateway/PaymentGatewayRouter.java)
+ [`application.yml`](../src/main/resources/application.yml) lines 771–789
+ [`application-prod.yml`](../src/main/resources/application-prod.yml) lines 158–170:

| Env var                       | YAML key                          | What it does                                                                                                                                                                            |
| ----------------------------- | --------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `STRIPE_SECRET_KEY`           | `billing.stripe.secret-key`       | Server-side API key (`sk_test_...` or `sk_live_...`). Wired into the static `com.stripe.Stripe.apiKey` field at `@PostConstruct` (StripePaymentGateway lines 52–60). If blank → gateway logs `"Stripe gateway disabled"` and the bean stays inert. |
| `STRIPE_PUBLISHABLE_KEY`      | `billing.stripe.publishable-key`  | Client-side key (`pk_test_...` / `pk_live_...`). Not read by Java directly today — emitted by the billing config endpoint for the frontend to use during Checkout redirects.            |
| `STRIPE_WEBHOOK_SECRET`       | `billing.stripe.webhook-secret`   | `whsec_...`. Passed to `com.stripe.net.Webhook.constructEvent(payload, signatureHeader, webhookSecret)` (StripePaymentGateway line 134). If blank → ALL incoming Stripe webhooks are rejected with `"Webhook secret not configured"` (line 129). |
| `BILLING_STRIPE_ENABLED`      | `billing.stripe.enabled`          | Master switch. Dev default `false` (application.yml line 772). Prod default `true` (application-prod.yml line 161). Read by `PaymentGatewayRouter.stripeEnabled` (line 27) — controls whether the Stripe gateway is selectable + whether the webhook endpoint accepts events (`BillingWebhookController` line 80 returns 403 when disabled). |
| `BILLING_RAZORPAY_ENABLED`    | `billing.razorpay.enabled`        | Same shape as above for Razorpay. Dev default `true` (line 784). Prod default `true` (line 170). At least ONE of the two flags MUST be true — `PaymentGatewayRouter.resolveDefault` throws `IllegalStateException("No payment gateway is enabled.")` otherwise (line 95). |
| `BILLING_DEFAULT_GATEWAY`     | `billing.default-gateway`         | Tie-breaker when the per-entity preference is empty AND currency routing doesn't apply. Dev default `razorpay` (application.yml line 767); prod default `stripe` (application-prod.yml line 159). String, lowercased internally. |

### Currency-based routing (independent of `default-gateway`)

`application.yml` lines 785–789 declare a static `currency-gateway-map`:

```
INR: RAZORPAY
USD: STRIPE
EUR: STRIPE
GBP: STRIPE
```

This map is referenced operationally — the per-entity `paymentGateway` field
on `BillingEntity` is set by `handleStripeCheckoutCompleted` and Razorpay's
analogous flow to "stripe" / "razorpay" once the customer has actually paid
through one (BillingService lines 968–986). New customers fall back to
`BILLING_DEFAULT_GATEWAY` until their first successful checkout.

For India traffic: keep `BILLING_DEFAULT_GATEWAY=razorpay` AND
`BILLING_RAZORPAY_ENABLED=true`. The Razorpay-side env vars are separate from
this file — see `RAZORPAY_KEY_ID` / `RAZORPAY_KEY_SECRET` /
`RAZORPAY_WEBHOOK_SECRET` / `RAZORPAY_WEBHOOK_URL` in
[`.env.template`](../.env.template) lines 24–28 and
[`RazorpayConfig.java`](../src/main/java/com/navneet/executespec/billing/config/RazorpayConfig.java).

## Test mode vs live mode

Stripe's secret + webhook secret are mode-scoped — `sk_test_*` / `whsec_*`
created against your Stripe test dashboard will not work against live mode
and vice versa. The two are flipped per-environment via env vars, not via a
runtime feature flag, so:

| Environment | `STRIPE_SECRET_KEY`        | `STRIPE_WEBHOOK_SECRET`     | `STRIPE_PUBLISHABLE_KEY` | `BILLING_STRIPE_ENABLED` |
| ----------- | -------------------------- | --------------------------- | ------------------------ | ------------------------ |
| Local dev   | `sk_test_*` (or blank, gateway disabled) | `whsec_*` (test mode) | `pk_test_*`              | `false` (default)        |
| `dev.executespec.ai` | `sk_test_*`                | `whsec_*` (test mode)     | `pk_test_*`              | `true`                   |
| Production  | `sk_live_*`                | `whsec_*` (live mode)       | `pk_live_*`              | `true`                   |

The price IDs themselves are ALSO mode-scoped — `price_*` created in the
Stripe test dashboard cannot be checked out with a `sk_live_*` key. So a
prod-mode switch is a four-line `.env` flip: secret key + webhook secret +
publishable key + all four price IDs. The DB-column override path is the
escape hatch when ops needs to swap one price (e.g. a promotional plan) at a
time without redeploying — set `billing_plan.stripe_price_id` directly.

## Rotating a Stripe price ID without downtime

The DB-column lookup path (Step 1 of `resolvePriceId`) is the rotation lever.
Existing subscribers on the old price keep renewing via the Stripe
Subscription's stored `priceId` until cancelled — Stripe doesn't migrate
existing subs when you swap the env var. So a rotation only affects NEW
checkouts.

To rotate the TEAM USD price (illustrative):

1. Create the new price in Stripe (same Product, new price). Note the new
   `price_*` id.
2. Decide the cutover window: do all new TEAM checkouts go to the new price
   from instant T?
3. **Preferred path (no deploy):** `UPDATE billing_plan SET stripe_price_id
   = 'price_NEW' WHERE tier = 'TEAM' AND base_amount_usd > 0;`. Step 1 of
   `resolvePriceId` picks up the new id on the next checkout. The env var
   `STRIPE_TEAM_PRICE_ID` is now the fallback for plan rows that don't have
   the column set — leave it pointed at the old price so anything that
   somehow bypasses the DB still uses a working price.
4. **Alternative (env-var only):** redeploy with `STRIPE_TEAM_PRICE_ID=price_NEW`.
   This works ONLY if every TEAM plan row has `stripe_price_id IS NULL`
   (Step 1 falls through to Step 2). Verify with `SELECT id, tier,
   stripe_price_id FROM billing_plan WHERE tier='TEAM' AND
   stripe_price_id IS NOT NULL;` — if any rows come back, the env var swap
   won't affect them.
5. Run a smoke checkout against the new price (one TEAM subscribe in a
   throwaway test org) before clearing the rollback plan.
6. Old subscribers keep paying the old price until they cancel + resubscribe.
   If you need to migrate them, use Stripe's "Update subscription items"
   from the dashboard (no code path in our gateway for it today).

There is **no feature flag** for "use new price for X% of traffic" — rotation
is binary.

## Webhook events Stripe dispatches against us

From `BillingService.processStripeWebhook` switch at line 912:

| Stripe event                       | Handler                                                                                                                                                                            | Effect                                                                                                                                |
| ---------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| `checkout.session.completed`       | `handleStripeCheckoutCompleted` (lines 955–1039)                                                                                                                                   | Persist `stripeCustomerId` on user/org; fetch seat quantity off the Stripe Subscription; set local sub status `ACTIVE`.               |
| `invoice.paid`                     | `handleStripeInvoicePaid` (lines 1041–1090)                                                                                                                                        | Extend period; call `upgradeTier(...)` (bridges to `app_user.tier` + `org.tier`); generate invoice via `generateStripeSubscriptionInvoice` and email.  |
| `invoice.payment_failed`           | `handleStripeInvoicePaymentFailed` (lines 1092–1113)                                                                                                                               | Mark sub `PAST_DUE`; send `sendPaymentFailed` email.                                                                                  |
| `customer.subscription.deleted`    | `handleStripeSubscriptionDeleted` (lines 1115–1132)                                                                                                                                | `downgradeToFree(sub)` — flip tier back to FREE.                                                                                      |
| anything else                      | `default -> log.debug("Stripe webhook: unhandled event type: {}", eventType)` (line 917)                                                                                            | No-op. The raw payload is still persisted in `webhook_event_log` + `billing_webhook_event` for forensic replay.                       |

In the Stripe dashboard you only need to subscribe to these four events on
the webhook endpoint at `https://<domain>/api/webhooks/stripe`. Sending
others wastes Stripe quota but is harmless — the raw payload is logged and
ignored.

## Setup checklist (new environment)

1. **Stripe dashboard.** Create the Product + four Prices (PRO USD,
   ENTERPRISE USD, TEAM USD per-unit, TEAM INR per-unit). Copy each
   `price_*` id.
2. **Webhook endpoint.** In the Stripe dashboard, create a webhook at
   `https://<domain>/api/webhooks/stripe` and subscribe to
   `checkout.session.completed`, `invoice.paid`, `invoice.payment_failed`,
   `customer.subscription.deleted`. Copy the signing secret (`whsec_*`).
3. **Secrets.** Store `sk_live_*`, `whsec_*`, `pk_live_*`, and the four
   `price_*` ids in your secret manager (GCP Secret Manager in our case —
   never commit any of these to git; `.env.template` shows the test-mode
   form, the prod values live in the deploy env only).
4. **App env vars.** Make sure the runtime sees:
   - `BILLING_STRIPE_ENABLED=true`
   - `STRIPE_SECRET_KEY=sk_live_*`
   - `STRIPE_WEBHOOK_SECRET=whsec_*`
   - `STRIPE_PUBLISHABLE_KEY=pk_live_*`
   - `STRIPE_PRO_PRICE_ID=price_*`
   - `STRIPE_ENTERPRISE_PRICE_ID=price_*`
   - `STRIPE_TEAM_PRICE_ID=price_*`
   - `STRIPE_TEAM_PRICE_ID_INR=price_*` (only if you have an INR variant in
     Stripe; if absent, TEAM-INR checkouts will silently fall back to the
     USD price — known footgun, see resolution table above).
5. **Seed `billing_plan.stripe_price_id` (optional but recommended).** For
   every plan row you want pinned to a specific Stripe Price (e.g. dev vs
   live diverging, A/B test pricing), `UPDATE billing_plan SET
   stripe_price_id = '...' WHERE id = '...';`. This bypasses the env-var
   fallback for that plan.
6. **Currency routing.** Confirm `application.yml` `billing.currency-gateway-map`
   matches your gateway availability — today USD/EUR/GBP all route to Stripe
   and INR routes to Razorpay. Razorpay is the only INR-capable gateway in
   the codebase.
7. **Smoke test.**
   - PRO USD: subscribe a test user → reach Stripe checkout → use a test
     card (Stripe `4242 4242 4242 4242` in test mode) → verify
     `checkout.session.completed` lands in `webhook_event_log` AND a
     `SUBSCRIPTION_CREATED` row appears in `payment_audit`.
   - TEAM USD with `seats=3`: confirm the Stripe Subscription line item
     shows `quantity=3` and `BillingSubscription.totalSeats=3` after the
     `checkout.session.completed` webhook.
   - INR path: subscribe via Razorpay (Stripe doesn't take INR through us);
     confirm `subscription.charged` lands in `billing_webhook_event` and a
     `SUBSCRIPTION_ACTIVATED` row appears in `payment_audit`.
8. **Verify default-gateway.** Hit `POST /api/billing/subscribe` from a
   fresh user with no `paymentGateway` set and confirm the routing matches
   the env var.

## Failure modes worth pre-empting

| Symptom                                                                                                                  | Likely cause                                                                                                                                                                                                                                  |
| ------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Stripe webhooks log `"Stripe webhook secret not configured — rejecting webhook"`                                          | `STRIPE_WEBHOOK_SECRET` is blank in the runtime env. Set it; existing webhook events are NOT replayed automatically — replay manually from Stripe dashboard.                                                                                  |
| All Stripe webhooks return 403                                                                                            | `BILLING_STRIPE_ENABLED=false`. Flip to true OR explicitly run on Razorpay (set `BILLING_DEFAULT_GATEWAY=razorpay` and don't expose `/api/webhooks/stripe`).                                                                                   |
| Stripe checkout creates a session that charges $0 / wrong amount                                                          | Either the `stripe_price_id` in `billing_plan` points at a deleted/archived Stripe Price, or the env-var fallback is pointed at a test-mode price while running with a live `STRIPE_SECRET_KEY`. Both are mode-mismatch class errors.         |
| TEAM checkout charges base price × 1 instead of × seats                                                                   | The Stripe Price configured for `STRIPE_TEAM_PRICE_ID` is NOT a per-unit price. Fix in Stripe (Price → "Charge per unit" + quantity-aware), not in code — `LineItem.setQuantity(seats)` is sent unconditionally (StripePaymentGateway line 92). |
| TEAM-INR checkout silently uses USD price                                                                                 | `STRIPE_TEAM_PRICE_ID_INR` is blank. The resolver falls back to `teamPriceId` (StripePaymentGateway line 399).                                                                                                                                  |
| `IllegalStateException: No payment gateway is enabled.` at startup                                                       | Both `BILLING_STRIPE_ENABLED` and `BILLING_RAZORPAY_ENABLED` are false. At least one must be true (`PaymentGatewayRouter.resolveDefault` line 95).                                                                                              |
| Subscriber retries an upgrade and gets a duplicate `payment_audit` row                                                    | Not a bug — `paymentAuditService.record` does NOT dedupe on `idempotency_key`. Upstream webhook dedup (`webhookRepo.existsByGatewayAndEventId`) blocks duplicate event processing, but a user clicking "Upgrade" twice produces two `SUBSCRIPTION_CREATED` rows by design (one is a pre-payment intent that will time out). |
| Stripe customer's `SUBSCRIPTION_ACTIVATED` row is missing from `payment_audit`                                            | Expected. The Razorpay-only `verifyAndActivate` path is the only writer of that event type. For Stripe, lifecycle reconstruction joins `payment_audit` (CREATED + CANCELLED + REFUND) with `billing_webhook_event` (CHECKOUT_COMPLETED + PAID).  |
