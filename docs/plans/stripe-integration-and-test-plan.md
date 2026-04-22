# Stripe integration — completion + test plan

> **Purpose:** close the remaining gaps in Stripe wiring so the v2.6.0 TEAM tier can actually take a test-mode payment end-to-end on dev, and define a repeatable test protocol we can run before every tier launch / pricing change.
> **Target window:** 2026-04-23 → 2026-04-28 (5 working days, runs in parallel with Wave 1 Day 4-7 per [`team-tier-plan.md`](team-tier-plan.md)).
> **Status:** PLANNED. Wave 1 Days 1-3 already shipped the gateway code (cancel-at-period-end + seat-aware checkout + lifecycle filter) — what's missing is (a) Stripe-dashboard artefacts, (b) price-id resolution for TEAM, (c) webhook seat sync, (d) end-to-end test protocol.
> **Last updated:** 2026-04-22.
> **Related:** [`team-tier-plan.md`](team-tier-plan.md) (execution schedule), [`tier-lifecycle-plan.md`](tier-lifecycle-plan.md) (lifecycle mechanics), `docs/reports/session-handoff-2026-04-21.md` §2.3 (prod Stripe default gateway), `docs/DEPLOYMENT.md` §Application Configuration Reference (config table).

## 1 · Current state — what's already wired

**Code (shipped on `feature/wave-1-team-tier-and-lifecycle`):**
- `StripePaymentGateway.createCheckoutSession` — exists, supports single-seat. v2.6.0 adds seat-aware overload `createCheckoutSession(entity, planId, seats, success, cancel)` that wires `LineItem.setQuantity(seats)` + records `seats` in session metadata.
- `StripePaymentGateway.cancelSubscription(subId)` — immediate cancel via `Subscription.cancel(setProrate=true)`.
- `StripePaymentGateway.cancelAtPeriodEnd(subId)` — v2.6.0. `Subscription.update(setCancelAtPeriodEnd(true))`. Idempotent.
- `StripePaymentGateway.handleWebhook(payload, sig)` — verifies signature, returns parsed event; actual event processing lives in `BillingService.processStripeWebhook`.
- `StripePaymentGateway.createTopupCheckoutSession(...)` — one-time checkout for run top-ups.
- `PaymentGatewayRouter` — resolves `STRIPE` by name or per-entity default; wired via `BillingEntityResolver`.

**Config (env vars, already declared in `application.yml` + `application-prod.yml`):**
- `STRIPE_SECRET_KEY`
- `STRIPE_WEBHOOK_SECRET`
- `STRIPE_PUBLISHABLE_KEY` (frontend, referenced in docs/DEPLOYMENT)
- `STRIPE_PRO_PRICE_ID` — maps to the existing PRO billing_plan rows
- `STRIPE_ENTERPRISE_PRICE_ID` — maps to the ENTERPRISE billing_plan rows

**DB:**
- `billing_plan.stripe_price_id` (VARCHAR 50) — present but NULL on existing PRO/TEAM rows
- `billing_plan.stripe_product_id` (VARCHAR 50) — present but NULL

**Prod env status (per session-handoff-2026-04-21 §2.3):**
- `application-prod.yml` has `billing.default-gateway: ${BILLING_DEFAULT_GATEWAY:stripe}` + `billing.stripe.enabled: ${BILLING_STRIPE_ENABLED:true}` — BOTH env-gated, so they only take effect when ops wires all 5 STRIPE_* variables on the prod GCE VM. **Currently prod has none of these set**, so the Razorpay path is still the effective default.

## 2 · Gaps to close

| Gap | Impact | Fix |
|---|---|---|
| **2.1** No Stripe Product / Price created for TEAM tier in Stripe dashboard | TEAM checkout would 500 ("price not found") | Create Product + 2 Prices (USD + INR) in Stripe test mode, capture price IDs |
| **2.2** `STRIPE_TEAM_PRICE_ID` env var not declared in application.yml | `StripePaymentGateway.resolvePriceId()` defaults to `proPriceId` for TEAM — wrong price charged | Add env var declaration + update `resolvePriceId` to match TEAM |
| **2.3** `billing_plan.stripe_price_id` NULL on new TEAM rows (V123) | DB-driven price resolution (future) can't find the Stripe price | Follow-up migration or manual UPDATE after Stripe Product creation — deferred until DB-driven resolution ships |
| **2.4** `BillingService.subscribe` hardcodes `seats=1` when calling gateway | TEAM checkout charges 1 seat regardless of user's seat picker | Thread `seats` through `CreateSubscriptionRequest` → `subscribe(userId, planId, seats, ...)` → `gateway.createCheckoutSession(entity, planId, seats, ...)` |
| **2.5** Webhook `checkout.session.completed` doesn't read `line_items.quantity` → doesn't set `BillingSubscription.totalSeats` or sync `org.seat_count` | TEAM subscriptions created but seat count stays at default | Extend `BillingService.processStripeWebhook` to pull `line_items` from Stripe + write `totalSeats` + update `org.seat_count` |
| **2.6** No Stripe test-mode webhook listener configured for dev | Can't test end-to-end without a public webhook URL | Stripe CLI `stripe listen --forward-to dev.executespec.ai/api/webhooks/stripe` OR use Stripe's test-mode webhook config pointed at dev |
| **2.7** No automated integration test covering Stripe TEAM checkout → webhook → tier upgrade | Regression risk on every gateway change | Write one E2E integration test using Stripe's test mode + Wiremock for webhook delivery (P2) |
| **2.8** Stripe refund path (`BillingService.issueProratedRefund`) is a TODO | Mid-cycle upgrade on a Stripe subscription leaks proration credit | Implement `refund` helper using `Refund.create()` — out of scope for this plan but tracked |

## 3 · Stripe dashboard runbook (do once per tier launch)

**Who:** Navneet (Stripe admin).
**When:** before Day 5 of the TEAM tier plan, i.e. by 2026-05-02.
**Where:** https://dashboard.stripe.com/test — ensure you're in **Test mode** (toggle top-left).

### 3.1 Create Product

1. Stripe Dashboard → Products → **+ Add product**
2. Name: `ExecuteSpec TEAM`
3. Description: `Team tier — shared credit pool + multi-repo projects`
4. Image: upload `website/press-kit/ss-01-dashboard.png` (optional)
5. Save

### 3.2 Create Prices (2 per product — USD + INR)

For each price:
1. Open the TEAM Product
2. **+ Add another price**
3. **Pricing model:** `Standard pricing`
4. **Per unit:** enable (this is the seat flag)
5. **Amount:**
   - USD: `$49.00`
   - INR: `₹3,999.00`
6. **Billing period:** `Monthly`
7. **Usage is metered:** OFF (we bill up-front per seat, not per-run)
8. Save. Capture the `price_XXXXXX` ID.

### 3.3 Capture + set env vars

After both USD + INR prices are created:
```bash
# Dev GCE (or local .env)
export STRIPE_TEAM_PRICE_ID=price_1TEAMusd....       # USD price
export STRIPE_TEAM_PRICE_ID_INR=price_1TEAMinr....   # INR price (see §4.1 for usage)
```

Add to `application.yml` under `billing.stripe:` (Gap 2.2 work below).

### 3.4 Webhook endpoint

Either:
- **Option A (dev):** Stripe CLI `stripe listen --forward-to https://dev.executespec.ai/api/webhooks/stripe` — prints the webhook signing secret, copy to `STRIPE_WEBHOOK_SECRET` on dev.
- **Option B (stable):** Stripe Dashboard → Developers → Webhooks → + Add endpoint pointing at `https://dev.executespec.ai/api/webhooks/stripe`, select events: `checkout.session.completed`, `invoice.paid`, `customer.subscription.updated`, `customer.subscription.deleted`. Capture the signing secret.

Prod setup: a separate endpoint on the prod Stripe account pointed at `https://executespec.ai/api/webhooks/stripe`, same event list, different signing secret.

## 4 · Code changes (Gaps 2.2 / 2.4 / 2.5)

### 4.1 Gap 2.2 — declare TEAM price env + resolution

**`application.yml` + `application-prod.yml`:**
```yaml
billing:
  stripe:
    pro-price-id: ${STRIPE_PRO_PRICE_ID:}
    enterprise-price-id: ${STRIPE_ENTERPRISE_PRICE_ID:}
    team-price-id: ${STRIPE_TEAM_PRICE_ID:}
    team-price-id-inr: ${STRIPE_TEAM_PRICE_ID_INR:}  # currency-specific fallback
```

**`StripePaymentGateway`:**
```java
@Value("${billing.stripe.team-price-id:}")
private String teamPriceId;

@Value("${billing.stripe.team-price-id-inr:}")
private String teamPriceIdInr;

private String resolvePriceId(String planId) {
    if (planId == null) return proPriceId;
    String lower = planId.toLowerCase();
    if (lower.contains("enterprise")) return enterprisePriceId;
    if (lower.contains("team")) {
        // Prefer stripe_price_id on billing_plan row when present; fall back to env var.
        BillingPlan plan = planRepo.findById(UUID.fromString(planId)).orElse(null);
        if (plan != null && plan.getStripePriceId() != null) return plan.getStripePriceId();
        if (lower.contains("inr") && teamPriceIdInr != null && !teamPriceIdInr.isBlank()) return teamPriceIdInr;
        return teamPriceId;
    }
    return proPriceId;
}
```

Inject `BillingPlanRepository` into `StripePaymentGateway` via constructor (currently field-less Spring bean — add `@RequiredArgsConstructor` + final field).

### 4.2 Gap 2.4 — thread seats through BillingService

**`CreateSubscriptionRequest` (DTO):**
```java
public record CreateSubscriptionRequest(
    UUID planId,
    String gateway,
    String successUrl,
    String cancelUrl,
    Integer seats  // new — null = 1 for non-seat-priced plans
) {}
```

**`BillingService.subscribe` signature (add new overload, keep existing):**
```java
public SubscriptionInitResult subscribe(String userId, UUID planId, int seats,
                                         String preferredGateway, String successUrl, String cancelUrl) {
    ...
    // Enforce TEAM seat range (2-20)
    if ("TEAM".equals(plan.getTier())) {
        if (seats < 2 || seats > 20) {
            throw new BillingException(
                "TEAM tier requires 2-20 seats (got " + seats + "). " +
                "Contact sales for larger orgs.");
        }
    } else if (seats != 1) {
        log.warn("seats={} ignored for tier={} — forcing 1", seats, plan.getTier());
        seats = 1;
    }
    ...
    // Stripe branch:
    var checkout = gateway.createCheckoutSession(billingEntity, planId.toString(), seats, ...);
}

// Keep original signature for back-compat, delegate seats=1:
public SubscriptionInitResult subscribe(String userId, UUID planId,
                                         String preferredGateway, String successUrl, String cancelUrl) {
    return subscribe(userId, planId, 1, preferredGateway, successUrl, cancelUrl);
}
```

**`BillingController.subscribe`:**
```java
int seats = request.seats() != null ? request.seats() : 1;
return ResponseEntity.ok(billingService.subscribe(
    userDetails.getUser().getId(), request.planId(), seats,
    request.gateway(), request.successUrl(), request.cancelUrl()));
```

### 4.3 Gap 2.5 — webhook seat sync

In `BillingService.processStripeWebhook`, on `checkout.session.completed`:

```java
Session session = (Session) event.getDataObjectDeserializer()
        .getObject().orElseThrow(...);

// Line items may not be in the default payload — fetch explicitly.
SessionListLineItemsParams listParams = SessionListLineItemsParams.builder().build();
LineItemCollection items = session.listLineItems(listParams);
long quantity = items.getData().isEmpty() ? 1L : items.getData().get(0).getQuantity();

// Existing logic to find/create BillingSubscription:
BillingSubscription sub = ... ;
sub.setTotalSeats((int) quantity);
subRepo.save(sub);

// Sync org.seat_count if this subscription is org-owned
if (sub.getOrgId() != null) {
    orgRepo.findById(sub.getOrgId()).ifPresent(org -> {
        org.setSeatCount((int) quantity);
        orgRepo.save(org);
    });
}
```

Razorpay doesn't have a clean "line_items.quantity" concept at webhook time — seats there come from the existing `TopupPackage`-like subscription flow. Handle Razorpay TEAM in a follow-up (deferred — see §6 Razorpay track).

### 4.4 Follow-up — persist Stripe price IDs in billing_plan rows

Once prices exist in Stripe:
```sql
-- V125__team_stripe_price_ids.sql (deferred — runs after §3.3 price creation)
UPDATE billing_plan
   SET stripe_price_id = 'price_1TEAMusd....'
 WHERE name = 'Team Monthly USD' AND active = TRUE;

UPDATE billing_plan
   SET stripe_price_id = 'price_1TEAMinr....'
 WHERE name = 'Team Monthly INR' AND active = TRUE;
```

With this in place, `resolvePriceId` prefers the DB value over env var — lets us have multiple Stripe accounts (test/prod) configured without code changes.

## 5 · Test protocol (manual, before each tier launch)

Run this checklist end-to-end on dev before any tier-launch PR merges to `main`. Allow ~45 minutes.

### 5.1 Pre-flight

- [ ] Stripe Dashboard is in **Test mode** (red banner at top)
- [ ] `STRIPE_TEAM_PRICE_ID` env var is set on dev GCE
- [ ] Stripe CLI `stripe listen --forward-to https://dev.executespec.ai/api/webhooks/stripe` is running (or permanent webhook endpoint is configured)
- [ ] `webhook secret` captured into `STRIPE_WEBHOOK_SECRET`
- [ ] Dev server restarted after env var changes
- [ ] You have a Stripe test card handy: `4242 4242 4242 4242` · any future expiry · any 3-digit CVC · any postal code

### 5.2 Happy path — TEAM subscription, 5 seats, USD

1. [ ] Sign up as a fresh user on https://dev.executespec.ai
2. [ ] Verify email; navigate to Plans page
3. [ ] Confirm only **FREE / TEAM / MAX / ENTERPRISE** cards visible (PRO hidden — validates Day 2 lifecycle filter)
4. [ ] Click **TEAM** card; seat picker defaults to 2, set to **5**
5. [ ] Confirm total preview reads `$245/mo`
6. [ ] Click **Upgrade to TEAM**
7. [ ] Stripe-hosted checkout loads; enter test card `4242…`
8. [ ] Submit; Stripe redirects to success URL
9. [ ] Dev backend log shows `Stripe checkout session created: sessionId=...` and later `Stripe webhook received: type=checkout.session.completed`
10. [ ] On reload of dashboard, user shows tier=`TEAM`
11. [ ] Admin UI (or direct DB query) confirms:
    - `BillingSubscription.status = 'ACTIVE'`
    - `BillingSubscription.totalSeats = 5`
    - `Org.seat_count = 5`
    - `Org.tier = 'TEAM'`

### 5.3 Seat enforcement

1. [ ] As the TEAM admin, invite 5 members — all succeed
2. [ ] Try to invite a 6th — expect **402 "buy more seats"** (validates Day 4 InvitationService gate — TODO on that Day)
3. [ ] Adjust seats to 6 via admin UI (or direct API call to `POST /api/org/{id}/seats/adjust`) — Stripe subscription quantity updates on webhook
4. [ ] Confirm 6th invite now succeeds

### 5.4 Quota (shared credit pool)

1. [ ] TEAM admin submits 3 LOW runs — all charge org.monthlyRunCount
2. [ ] Member submits 2 LOW runs — also charge org.monthlyRunCount
3. [ ] `Org.monthly_run_count = 5`
4. [ ] `effectiveOrgLimit(org)` returns `seats × 50 = 300`
5. [ ] If flooded to exceed 300, `assertAndIncrementRunCount` throws 429 (can't easily test in manual protocol — covered by unit test)

### 5.5 Billing gate on HIDDEN tier (grandfathering verification)

1. [ ] As SUPER_ADMIN, confirm you can see PRO plan via `GET /api/billing/plans?includeHidden=true`
2. [ ] As regular user, `GET /api/billing/plans` does NOT include PRO
3. [ ] Try to subscribe to PRO directly via API: `POST /api/billing/subscribe {planId: <pro-plan-uuid>, seats: 1}` — expect **400 "Plan not available"**
4. [ ] Pick an existing PRO user (from prod-like fixture); confirm they still see tier=`PRO` in `/api/auth/me` and their subscription shows `ACTIVE`

### 5.6 Cancel-at-period-end (SUNSETTING dry-run)

Only runs after `billing.lifecycle.sunsetting.enabled=true` flipped on:
1. [ ] Pick a test-mode PRO subscription from §5.5
2. [ ] Flip the flag: `PUT /api/feature-flags/billing.lifecycle.sunsetting.enabled {value: 'true'}`
3. [ ] As SUPER_ADMIN, `PUT /api/admin/tiers/PRO/lifecycle {state: 'SUNSETTING', reason: 'test', confirmTierName: 'PRO'}`
4. [ ] Log shows `SUNSETTING queued: subId=sub_... gateway=STRIPE tier=PRO`
5. [ ] Stripe Dashboard → Subscriptions → find the sub → shows `Ends on <date>` + `Cancel at period end: Yes`
6. [ ] `BillingSubscription.status = 'CANCELLING_AT_PERIOD_END'`
7. [ ] Flip state back: `PUT /api/admin/tiers/PRO/lifecycle {state: 'HIDDEN', reason: 'revert test'}` — audit row recorded; **gateway side un-cancel is manual** (UI shows warning). Leave the test sub as-is; it'll downgrade at period end.
8. [ ] Flip flag off: `billing.lifecycle.sunsetting.enabled = false`

### 5.7 Edge cases

- [ ] **Zero-seat checkout:** attempt subscribe with seats=0 or seats=null → 400 `IllegalArgumentException`
- [ ] **seats=21 on TEAM:** expect 400 "TEAM tier requires 2-20 seats"
- [ ] **seats=5 on PRO:** expect log warning, seats forced to 1
- [ ] **Webhook replay:** re-send the same `checkout.session.completed` event → idempotent via `webhook_event_log` (already existing)
- [ ] **Stripe test card failure:** `4000 0000 0000 0002` (generic decline) → user sees decline, no subscription created
- [ ] **User cancels checkout:** Stripe redirects to `cancelUrl`; no subscription persisted

## 6 · Razorpay parallel track (lower priority)

India-local TEAM buyers use Razorpay. The minimum viable path:

1. **Razorpay dashboard — create subscription plan** for TEAM Monthly INR (₹3,999/month, recurring, per-seat via "unit" — Razorpay calls it `quantity`).
2. **`RazorpayGateway.createSubscription`** — add seat-aware variant similar to Stripe. Razorpay subscription API accepts `quantity` parameter.
3. **Webhook `subscription.activated`** — parse `quantity` from payload, write `BillingSubscription.totalSeats` + `Org.seat_count`.
4. Manual test protocol — same as §5.2-5.7 but against Razorpay test-mode endpoints.

Deferred until US Stripe TEAM conversion is validated (prevents duplicate work if TEAM pricing needs to shift).

## 7 · Success criteria

Complete this plan if all of the following are true:

- [ ] TEAM Monthly USD price created in Stripe test mode, env var set
- [ ] `mvn test` passes with new tests covering (a) seat-range validation in subscribe, (b) resolvePriceId picks TEAM correctly
- [ ] Manual §5 protocol executed end-to-end on dev with all checkboxes green
- [ ] Stripe CLI log shows `checkout.session.completed` → backend 200 → subscription ACTIVE
- [ ] `org.seat_count` matches purchased seats after webhook
- [ ] Screenshot of the final state (Billing page showing "TEAM · 5 seats · $245/mo · Next renewal ...") saved to `docs/reports/stripe-team-dev-validation-2026-04-XX.md`

## 8 · Risk register

| Risk | Mitigation |
|---|---|
| Stripe test mode subtly differs from live (webhook delivery timing, event payload shape) | The production webhook endpoint adds a redundant `customer.subscription.updated` listener to reconcile anything the `checkout.session.completed` handler missed; both paths converge on the same `BillingSubscription` update code. |
| Stripe price IDs leak into logs or audit rows | `PaymentAuditService` already redacts payload fields; verify no raw `price_` prefix appears in audit by scanning a sample audit row post-test |
| Webhook replay spams metrics | `webhook_event_log` table dedup (already exists via signature + event_id) |
| Seat-count drift: Stripe quantity != org.seat_count | Monthly reconciliation job (deferred — add to v2 if drift observed) |
| TEAM subscribers hit the 20-seat self-serve cap | Product decision — "contact sales" link on error; ENTERPRISE path handles this |
| Stripe dashboard access loss (founder vacation, lost 2FA) | Recovery codes stored in 1Password; see `docs/reports/session-handoff-2026-04-21.md` for ops continuity notes |

## 9 · Timeline

| Day | Date | Work | Blocker |
|---|---|---|---|
| 1 | 2026-04-23 Wed | §3 Stripe dashboard runbook (Product + 2 Prices + webhook) | Founder availability |
| 2 | 2026-04-24 Thu | §4.1 env var + resolvePriceId + §4.2 seats through BillingService | Price IDs from Day 1 |
| 3 | 2026-04-25 Fri | §4.3 webhook seat sync + unit tests | — |
| 4 | 2026-04-26 Sat | §5 manual protocol end-to-end (§5.1-5.4 happy path + seat enforcement) | Dev server reachable + webhook endpoint live |
| 5 | 2026-04-27 Sun | §5.5-5.7 edge cases + §5.6 SUNSETTING dry-run | Wave 1 Day 6 admin UI (or manual flag flip via SQL) |
| — | 2026-04-28 Mon | Buffer + §7 success-criteria sign-off + commit validation doc | — |

Finishes 1 day before the TEAM launch prod cutover on 2026-05-05.

## 10 · What this plan deliberately does NOT do

- **No Stripe live-mode setup.** Stays in test mode. Live cutover coordinates with the team-tier-plan Day 8 prod deploy and is scoped separately (`docs/reports/session-handoff-2026-04-21.md` §2.3).
- **No Stripe Tax wiring.** Tax collection for enterprise / non-US buyers is a Q3 project.
- **No Stripe Connect / marketplace.** Not in the business model.
- **No automated E2E Stripe test** (§2.7) — manual protocol is enough for Wave 1. Automated test is a Wave 3+ follow-up when we have >5 tier changes/quarter.
- **No Razorpay TEAM ship.** Deferred per §6 until Stripe TEAM conversion data justifies it.
- **No `issueProratedRefund` Stripe implementation** (§2.8). Tracked separately.
- **No currency-aware routing** (INR buyer → Razorpay automatically). Still manual env flag per session-handoff §5.2.

## 11 · Open decisions

- **Stripe test vs. live account split.** Do we need separate admin users for test + live, or one user with dashboard toggle? (default: one user, toggle)
- **Webhook secret rotation cadence.** Quarterly? On-demand? (default: on-demand + post-incident)
- **Stripe Tax.** Needed before prod live cutover if selling into US states with sales-tax nexus? (deferred — consult accountant)
- **INR exchange-rate drift.** ₹3,999 at $49 assumes 1 USD ≈ ₹81.6; if INR weakens > 5% (₹86+), raise INR price or absorb? (default: absorb up to ₹90, revisit quarterly)
