# Stripe price IDs — ops env-var reference

> M-029 (Wave 3) — operator-only checklist for the Stripe environment
> variables that gate the prod billing path.
> Companion to [`docs/payment-history.md`](payment-history.md).

## TL;DR

The prod backend reads Stripe credentials from 5 environment
variables (or 7 once TEAM monthly + TEAM yearly + MAX yearly all
ship as separate price objects). They are **deliberately unset on
prod** until ops wires them — the dev profile defaults to Razorpay
sandbox, so prod stays in a known-good "Stripe disabled" state
until explicitly enabled.

## Required env vars

| Env var | Purpose | Where it surfaces in code |
|---|---|---|
| `STRIPE_SECRET_KEY` | Server-side API auth (`sk_live_…`) | `application-prod.yml:billing.stripe.secret-key` → `StripePaymentGateway` |
| `STRIPE_PUBLISHABLE_KEY` | Client-side checkout key (`pk_live_…`) | `application-prod.yml:billing.stripe.publishable-key` → `/api/billing/stripe-publishable-key` returns this for the frontend |
| `STRIPE_WEBHOOK_SECRET` | HMAC verification on incoming webhooks (`whsec_…`) | `application-prod.yml:billing.stripe.webhook-secret` → `StripeWebhookController` |
| `STRIPE_PRO_PRICE_ID` | Stripe Price ID for the legacy PRO monthly plan (HIDDEN to new buyers; grandfathered subscribers stay on this) | `application-prod.yml:billing.stripe.pro-price-id` |
| `STRIPE_ENTERPRISE_PRICE_ID` | Stripe Price ID for the ENTERPRISE plan (sales-managed) | `application-prod.yml:billing.stripe.enterprise-price-id` |

## Plus (when V146 annual + V144 TEAM ship to prod):

| Env var | Purpose | Notes |
|---|---|---|
| `STRIPE_TEAM_MONTHLY_PRICE_ID` | TEAM per-seat monthly ($49/seat) | V144 reinstated this tier on `feature/v2.7-foundation`; ops must add the matching Stripe Price object before the env var is set. |
| `STRIPE_TEAM_YEARLY_PRICE_ID` | TEAM per-seat yearly (17% discount) | V146. Needs a separate Stripe Price with `billing_scheme=per_unit` + `recurring.interval=year`. |
| `STRIPE_MAX_PRICE_ID` | MAX flat $29/mo BYOK | V144 / V141. The MAX tier id was renamed → TEAM in V141 then unrenamed in V144; this env var stays `MAX_*` to match the current tier id. |
| `STRIPE_MAX_YEARLY_PRICE_ID` | MAX flat yearly | V146. Optional — annual on a flat-fee tier is convenience only. |

## Gateway flag (when to flip)

`application-prod.yml`:

```yaml
billing:
  default-gateway: ${BILLING_DEFAULT_GATEWAY:stripe}
  stripe:
    enabled: ${BILLING_STRIPE_ENABLED:true}
```

- `BILLING_STRIPE_ENABLED=true` (default) — Stripe is the prod
  default for USD; Razorpay still handles INR.
- `BILLING_STRIPE_ENABLED=false` — emergency fallback. Routes
  every checkout to Razorpay. Use this if Stripe webhooks are
  failing en masse and you need to stop new charges from
  succeeding silently.
- `BILLING_DEFAULT_GATEWAY=razorpay` — secondary fallback for
  full revert.

## Setup checklist (ops, one-time per env)

1. **Stripe dashboard → Developers → API keys**
   - Generate a restricted secret key (`sk_live_…`) with
     read+write on Subscriptions / Invoices / Webhooks. Avoid the
     full account-wide secret.
   - Generate a publishable key for the prod domain.

2. **Stripe dashboard → Products**
   - Create products + prices matching the V143/V144/V146 schema.
     One Price per (tier, period, currency). Per-seat prices use
     `billing_scheme=per_unit` + `recurring.usage_type=licensed`.
   - Capture each Price ID (`price_…`) for the env vars below.

3. **Stripe dashboard → Webhooks**
   - Add endpoint `https://api.executespec.ai/api/webhooks/stripe`.
   - Subscribe to: `checkout.session.completed`,
     `customer.subscription.updated`, `customer.subscription.deleted`,
     `invoice.payment_failed`, `invoice.payment_succeeded`.
   - Copy the signing secret (`whsec_…`).

4. **Set the env vars** (Cloud Run / GCE — wherever the prod jar runs)
   ```bash
   STRIPE_SECRET_KEY=sk_live_...
   STRIPE_PUBLISHABLE_KEY=pk_live_...
   STRIPE_WEBHOOK_SECRET=whsec_...
   STRIPE_PRO_PRICE_ID=price_...
   STRIPE_ENTERPRISE_PRICE_ID=price_...
   STRIPE_TEAM_MONTHLY_PRICE_ID=price_...
   STRIPE_TEAM_YEARLY_PRICE_ID=price_...
   STRIPE_MAX_PRICE_ID=price_...
   STRIPE_MAX_YEARLY_PRICE_ID=price_...
   BILLING_STRIPE_ENABLED=true
   BILLING_DEFAULT_GATEWAY=stripe
   ```

5. **Smoke test** (before the first real customer)
   - Use a Stripe test mode key + a 4242-4242-4242-4242 card to
     run a TEAM checkout end-to-end.
   - Verify the webhook fires + a `payment_audit` row lands with
     `gatewayProvider='STRIPE'`.
   - Verify the org's `tier` flips to TEAM + `seat_count` matches.
   - Tear down the test customer in Stripe.

6. **Document the price-id → tier mapping** in your ops handbook
   (off-repo) so the next person knows which `price_…` belongs to
   which tier without spelunking through the dashboard.

## Common ops gotchas

- **Webhook secret rotation:** Stripe lets you have multiple
  endpoints with different secrets. To rotate, add a second
  endpoint with the same URL + new secret, switch the env var,
  then delete the old endpoint after one billing cycle. Don't
  hot-swap a single endpoint's secret.
- **Test-mode vs live-mode keys:** Stripe SDK uses whichever the
  secret key is. Mixing modes (e.g. live secret + test publishable)
  fails silently in checkout.
- **Currency mismatch:** A USD-priced Stripe Price won't accept INR
  payment methods. Don't reuse Price IDs across currencies — make
  separate Prices.

## Where this connects

- [`docs/payment-history.md`](payment-history.md) — the runtime
  story of how rows land in `payment_audit` per gateway. This doc
  is the static config that enables that flow.
- `application-prod.yml` — actual env-var bindings.
- `StripePaymentGateway` + `StripeWebhookController` — code that
  reads the env vars at boot.
