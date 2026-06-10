# Stripe Price IDs Operations

> Recreated 2026-06-10 from `StripePaymentGateway` and `application.yml`.

## Environment Variables

| Env var | YAML key | Purpose |
| --- | --- | --- |
| `STRIPE_SECRET_KEY` | `billing.stripe.secret-key` | Stripe API key |
| `STRIPE_PUBLISHABLE_KEY` | `billing.stripe.publishable-key` | Frontend publishable key |
| `STRIPE_WEBHOOK_SECRET` | `billing.stripe.webhook-secret` | Webhook signature verification |
| `STRIPE_PRO_PRICE_ID` | `billing.stripe.pro-price-id` | PRO fallback price |
| `STRIPE_ENTERPRISE_PRICE_ID` | `billing.stripe.enterprise-price-id` | ENTERPRISE fallback price |
| `STRIPE_TEAM_PRICE_ID` | `billing.stripe.team-price-id` | TEAM per-seat price |
| `STRIPE_TEAM_PRICE_ID_INR` | `billing.stripe.team-price-id-inr` | TEAM INR Stripe override |

Older docs used a Team-monthly env name; the current application config reads `STRIPE_TEAM_PRICE_ID`.

## Resolution Order

`StripePaymentGateway.resolvePriceId(planId)` resolves price ids in this order:

1. If `planId` is a UUID and `billing_plan.stripe_price_id` is populated, use the DB value.
2. Otherwise use tier/currency env fallback:
   - `ENTERPRISE` -> `STRIPE_ENTERPRISE_PRICE_ID`
   - `TEAM` -> `STRIPE_TEAM_PRICE_ID` or `STRIPE_TEAM_PRICE_ID_INR`
   - `PRO` -> `STRIPE_PRO_PRICE_ID`
3. If `planId` is a legacy string, pattern-match `enterprise` / `team`.
4. Unknown tier/path falls back to `STRIPE_PRO_PRICE_ID`.

The DB column is the preferred operational control because it supports test/live rotation without code changes.

## Rotation Procedure

1. Create the new Stripe Price in the Stripe dashboard.
2. Update `billing_plan.stripe_price_id` for the active plan row.
3. Run a checkout smoke in Stripe test mode.
4. Confirm `checkout.session.completed` activates the expected tier/seats.
5. Confirm invoice PDF generation and payment audit rows.
6. Only use env-var rotation when changing a global fallback.

## Gateway Routing

Gateway routing lives in `PaymentGatewayRouter`:

- entity preferred gateway when present and enabled,
- configured default gateway,
- Razorpay fallback,
- Stripe fallback if Razorpay disabled and Stripe enabled,
- error if no gateway enabled.

## Webhook Handling

Stripe signature verification happens in `StripePaymentGateway.handleWebhook`. Domain processing is handled by billing services/controllers after the event is validated.

Sandbox proof remains required before treating payment changes as production-ready.

## Code Pointers

- `src/main/java/com/navneet/executespec/billing/gateway/StripePaymentGateway.java`
- `src/main/java/com/navneet/executespec/billing/gateway/PaymentGatewayRouter.java`
- `src/main/java/com/navneet/executespec/billing/controller/BillingWebhookController.java`
- `src/main/resources/application.yml`
