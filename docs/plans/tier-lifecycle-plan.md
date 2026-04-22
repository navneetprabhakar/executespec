# Tier lifecycle tooling — standalone reference

> **Scope:** generic 3-state lifecycle machine (`LISTED` / `HIDDEN` / `SUNSETTING`) for `tier_config` + `billing_plan` rows. Reusable across any tier retirement or paused-tier scenario.
> **Shipping:** bundled with the TEAM tier launch (Option Z, 2026-04-28 → 2026-05-06) — see [`team-tier-plan.md`](team-tier-plan.md) §2b for the in-context shipping log.
> **Standalone doc because:** the tooling outlives TEAM launch. Future decisions (retire MAX, experiment with a "Solo" tier, pause ENTERPRISE during a rewrite) reuse this without touching TEAM-specific code.
> **Status:** PLANNED, bundled into TEAM launch.
> **Last updated:** 2026-04-22.

## 1 · What problem this solves

SaaS tier changes are **four distinct operations** that tools commonly collapse into one:

1. Hide a plan from new buyers (UI + purchase gate)
2. Stop renewals on existing subscriptions (gateway subscription cancellation)
3. Migrate existing subscribers off (notification + options)
4. Delete the plan from the DB (never actually done for auditability)

A single "delete" or "deprecate" checkbox forces all 4 at once, which creates forced churn and legal-notice issues. The 3-state lifecycle separates #1 (HIDDEN) from #2+#3 (SUNSETTING) with an explicit transition between them, and keeps #4 out of scope entirely (data stays, always).

## 2 · State machine

```
              ┌────────────┐
              │   LISTED   │   default · shown everywhere · accepting new
              └─────┬──────┘
      ┌─────────────┴─────────────┐
      │                           │
   flip instant                flip rejected
  (SUPER_ADMIN)                (transition forbidden)
      │
      ▼
┌────────────┐
│   HIDDEN   │   hidden from public surfaces · existing keep renewing
└─────┬──────┘
      │
  explicit confirm
  (MRR modal · typed tier name · 14-day observation window)
      │
      ▼
┌──────────────┐
│  SUNSETTING  │   HIDDEN + cancel-at-period-end queued on gateway
└──────────────┘       + affected users emailed
```

**Key constraints:**
- `LISTED` → `HIDDEN` — **instant**, no confirmation, fully reversible.
- `HIDDEN` → `LISTED` — **instant**, reverses a hide.
- `HIDDEN` → `SUNSETTING` — requires SUPER_ADMIN + typed tier-name confirmation + MRR-impact modal. Minimum 14 days since `HIDDEN` transition (enforced in controller).
- `SUNSETTING` → `HIDDEN` — allowed but flagged as "partially reversible" (un-cancelling is per-customer Stripe call). UI surfaces a warning.
- `LISTED` → `SUNSETTING` — **rejected** (422 Unprocessable). Must pass through `HIDDEN` first.
- `SUNSETTING` → `LISTED` — **rejected** (422). Un-sunset then re-list as two steps.

## 3 · Schema

Both `tier_config` and `billing_plan` get the same columns, mutable independently (a tier can be `LISTED` but one of its billing_plan rows — e.g. Yearly INR — can be `HIDDEN` to pull that specific cycle from sale while keeping Monthly USD sellable).

```sql
-- Flyway V121 (shipped as part of TEAM launch)
ALTER TABLE tier_config
  ADD COLUMN lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'LISTED'
    CHECK (lifecycle_state IN ('LISTED', 'HIDDEN', 'SUNSETTING')),
  ADD COLUMN lifecycle_changed_at TIMESTAMPTZ,
  ADD COLUMN lifecycle_changed_by VARCHAR(64),
  ADD COLUMN lifecycle_reason TEXT;

ALTER TABLE billing_plan
  ADD COLUMN lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'LISTED'
    CHECK (lifecycle_state IN ('LISTED', 'HIDDEN', 'SUNSETTING')),
  ADD COLUMN lifecycle_changed_at TIMESTAMPTZ,
  ADD COLUMN lifecycle_changed_by VARCHAR(64),
  ADD COLUMN lifecycle_reason TEXT;
```

**Resolution rule:** when filtering plans for a buyer,
```
effective_state = (billing_plan.lifecycle_state == 'LISTED' AND tier_config.lifecycle_state == 'LISTED')
                  ? 'LISTED' : 'NOT_LISTED'
```
i.e. both must be `LISTED` to show in public plans. Tier overrides plan — hide the tier, all its plans disappear.

## 4 · Backend surfaces

### 4.1 TierLifecycleService (new)

```java
// src/main/java/com/navneet/executespec/billing/lifecycle/TierLifecycleService.java
public enum LifecycleState { LISTED, HIDDEN, SUNSETTING }

public record TierLifecycleDto(String tier, LifecycleState state,
                                Instant changedAt, String changedBy, String reason,
                                long activeSubscribers, long monthlyRecurringRevenueUsd) {}

public interface TierLifecycleService {
    List<TierLifecycleDto> listAll();
    void transitionTier(String tier, LifecycleState target, String actorUserId, String reason);
    void transitionPlan(String planId, LifecycleState target, String actorUserId, String reason);
    void executeSunsettingSideEffects(String tier, String actorUserId);
}
```

Side-effects for `SUNSETTING` run in the service, not the controller:
- Query `BillingSubscription` where `tier = :tier` and `status = 'ACTIVE'`
- For each, call `PaymentGatewayRouter.get(gateway).cancelAtPeriodEnd(subscriptionId)`
- Update `BillingSubscription.status = 'CANCELLING_AT_PERIOD_END'`
- Record audit row (`event_type = 'TIER_SUNSET_QUEUED'`)
- Fire `tier-sunsetting.html` email per affected subscriber
- Return a summary `{tierTransitionedTo, subscriptionsQueued, emailsFired, errors[]}`

### 4.2 Routes

| Method | Path | Auth | Body |
|---|---|---|---|
| `GET` | `/api/admin/tiers` | SUPER_ADMIN | — |
| `PUT` | `/api/admin/tiers/{tier}/lifecycle` | SUPER_ADMIN | `{state, reason, confirmTierName?}` |
| `GET` | `/api/admin/billing-plans` | SUPER_ADMIN | `?lifecycle=LISTED\|HIDDEN\|SUNSETTING\|ALL` |
| `PUT` | `/api/admin/billing-plans/{planId}/lifecycle` | SUPER_ADMIN | `{state, reason}` |

### 4.3 Buyer-facing filter in existing endpoints

- `GET /api/billing/plans` — adds `WHERE billing_plan.lifecycle_state = 'LISTED' AND tier_config.lifecycle_state = 'LISTED'` unless caller is SUPER_ADMIN and passes `?includeHidden=true`.
- `POST /api/billing/subscribe` — rejects with `404 "Plan not available"` if target plan is not `LISTED`. Preserves existing JWT + org gating.

### 4.4 Gateway extensions

```java
// BillingGateway interface — new method
void cancelAtPeriodEnd(String subscriptionId);

// StripePaymentGateway
@Override
public void cancelAtPeriodEnd(String subscriptionId) {
    try {
        Subscription.retrieve(subscriptionId)
            .update(SubscriptionUpdateParams.builder().setCancelAtPeriodEnd(true).build());
    } catch (StripeException e) {
        throw new GatewayException("cancelAtPeriodEnd failed for " + subscriptionId, e);
    }
}

// RazorpayPaymentGateway
@Override
public void cancelAtPeriodEnd(String subscriptionId) {
    // Razorpay API: PATCH /v1/subscriptions/:id/cancel with {"cancel_at_cycle_end": 1}
    razorpayClient.Subscriptions.cancel(subscriptionId, true);
}
```

## 5 · Admin UI

Lives at `/admin/tiers`. Single page, no sub-routing.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Tier lifecycle                                               [Refresh]  │
├──────────────────────────────────────────────────────────────────────────┤
│  FREE         [LISTED ▼]     Active                    0 subs · $0 MRR   │
│  PRO          [HIDDEN ▼]     Hidden since May 5, 2026  12 subs · $240 MRR │
│  TEAM         [LISTED ▼]     Active (launched May 5)   0 subs · $0 MRR   │
│  MAX          [LISTED ▼]     Active                    8 subs · $232 MRR │
│  ENTERPRISE   [LISTED ▼]     Active                    2 subs · custom    │
├──────────────────────────────────────────────────────────────────────────┤
│  Per-plan overrides                                                       │
│  PRO Monthly USD     [HIDDEN]  (inherits from tier)                      │
│  PRO Monthly INR     [HIDDEN]  (inherits from tier)                      │
│  TEAM Monthly USD    [LISTED]                                            │
│  TEAM Monthly INR    [LISTED]                                            │
│  TEAM Yearly USD     [LISTED]                                            │
│  TEAM Yearly INR     [LISTED]                                            │
└──────────────────────────────────────────────────────────────────────────┘
```

### SUNSETTING confirmation modal

When selecting `SUNSETTING` from the dropdown:

```
┌─ Move PRO to SUNSETTING ──────────────────────────────────────────────┐
│                                                                        │
│  This will queue cancellation at period end for:                      │
│    • 12 active subscriptions                                          │
│    • $240/month in recurring revenue                                  │
│    • Average remaining cycle: 18 days                                 │
│    • Estimated revenue tail: $144 before full cutover                 │
│                                                                        │
│  Each affected subscriber will receive an email notice with options:   │
│    • Upgrade to TEAM ($49/seat) or MAX ($29 + BYOK)                   │
│    • Drop to FREE                                                      │
│                                                                        │
│  ⚠ Partially reversible — un-cancelling individual subscriptions     │
│     requires per-customer Stripe API calls after this executes.       │
│                                                                        │
│  To confirm, type the tier name: PRO                                  │
│  [ _______________________ ]                                           │
│                                                                        │
│  Reason (required, logged in audit):                                   │
│  [ _______________________________________________ ]                   │
│                                                                        │
│  [Cancel]                            [Move PRO to SUNSETTING]         │
└────────────────────────────────────────────────────────────────────────┘
```

## 6 · Email templates

### `tier-sunsetting.html` (new)

Variables: `{firstName, planName, periodEnd, upgradeUrl, downgradeUrl, baseUrl, adminEmail}`.

Copy:
> Hi {firstName},
>
> We're winding down the **{planName}** plan. Your current subscription will run to its natural end on **{periodEnd}** — no changes until then.
>
> When your plan ends, you'll drop to Free unless you pick one of these:
>
> • **[Move to TEAM →]({upgradeUrl}?tier=team)** — $49/seat/mo with 50 credits per seat, shared pool, org admin tools. Fits teams of 2-20.
> • **[Move to MAX →]({upgradeUrl}?tier=max)** — $29/mo + your own API keys, unlimited fair-use. Fits power users running their own infra.
> • **[Stay on Free →]({downgradeUrl})** — 5 credits/month, single-credit specs only. No credit card.
>
> Reply to this email with any questions — it goes to a human.
>
> — Navneet (founder)

## 7 · Initial seed + day-1 migration

On TEAM launch day (2026-05-05 per team-tier-plan §5), the V122 migration performs:

```sql
-- Introduce TEAM
INSERT INTO tier_config (...) VALUES ('TEAM', ..., 'LISTED', now(), 'system-migration', 'Introduced in v2.6.0 tier restructure');

-- Hide PRO for new buyers
UPDATE tier_config
   SET lifecycle_state = 'HIDDEN',
       lifecycle_changed_at = now(),
       lifecycle_changed_by = 'system-migration',
       lifecycle_reason = 'TEAM launch — PRO grandfathered (existing subscribers continue)'
 WHERE tier = 'PRO';

UPDATE billing_plan
   SET lifecycle_state = 'HIDDEN',
       lifecycle_changed_at = now(),
       lifecycle_changed_by = 'system-migration',
       lifecycle_reason = 'TEAM launch — PRO grandfathered'
 WHERE tier = 'PRO';
```

Everything else stays `LISTED` by default.

## 8 · Audit + observability

- Every transition writes to `activity_audit` with `event_type IN ('TIER_LIFECYCLE_CHANGED', 'PLAN_LIFECYCLE_CHANGED', 'TIER_SUNSET_QUEUED')`.
- Metric: `executespec.billing.tier.lifecycle.transitions` (counter by `from_state,to_state,tier`).
- Metric: `executespec.billing.tier.lifecycle.subscriptions_affected` (gauge per tier).
- Dashboard: new panel on the existing billing Grafana board showing "Tier state distribution" + "SUNSET queue size" over time.

## 9 · Backward compatibility + migration

- No existing tier / plan changes behavior when the lifecycle columns default to `LISTED`.
- Existing `BillingService.subscribe` path unchanged for `LISTED` plans.
- Existing `PeriodEndDowngradeScheduler` already handles `CANCELLING_AT_PERIOD_END` subscriptions correctly (no change needed).
- API consumers of `GET /api/billing/plans` see no schema change — hidden plans simply don't appear.
- Existing feature-flag pattern (`FeatureFlagService`) could in theory do the "hide" half of this, but **can't** do the "cancel-at-period-end" half. We replace feature-flag-driven hiding with lifecycle-state-driven hiding on day 1 so there's one source of truth.

## 10 · Security + authorization

- All `/api/admin/tiers/**` and `/api/admin/billing-plans/**` routes require `SUPER_ADMIN`.
- SUNSETTING transitions require the confirmation-name match server-side (not just UI) — the controller validates `req.confirmTierName == tier` before invoking the service.
- SUNSETTING transitions log the actor user id + IP + timestamp + reason.
- Rate-limit the SUNSETTING endpoint to 1 req/min (belt-and-suspenders — it's SUPER_ADMIN only).
- No external webhook exposure — all surface is internal admin UI.

## 11 · Testing plan

- Unit tests on `TierLifecycleService`:
  - Every valid transition (LISTED↔HIDDEN, HIDDEN→SUNSETTING, SUNSETTING→HIDDEN)
  - Every rejected transition (LISTED→SUNSETTING, SUNSETTING→LISTED)
  - 14-day observation-window enforcement for HIDDEN→SUNSETTING
  - Gateway failures during SUNSETTING side-effects — partial success handling
- Integration tests against Stripe test mode:
  - Create a subscription on PRO in test mode, transition PRO to SUNSETTING, verify `cancel_at_period_end = true` on Stripe side
  - Verify `BillingSubscription.status = 'CANCELLING_AT_PERIOD_END'` locally
  - Verify `tier-sunsetting.html` fires (mock SMTP in tests)
- Manual smoke on dev before prod cutover:
  - Create test org with test PRO subscription
  - Hit `/admin/tiers` as SUPER_ADMIN
  - Observe PRO is HIDDEN (from Day 1 migration)
  - Flip PRO to LISTED, verify it reappears on a fresh PlansPage load
  - Flip back to HIDDEN
  - Transition to SUNSETTING with confirmation modal
  - Verify email landed + Stripe subscription shows `cancel_at_period_end`

## 12 · Known limitations

- **Cannot "un-sunset" an already-cancelled subscription from the admin UI.** Once SUNSETTING fires, each customer who wants to stay must individually re-subscribe or a human operator must call Stripe API. The UI surfaces this as a warning; automating "un-sunset" is deferred to v2.
- **No per-org exemption.** If a specific enterprise customer wants to keep a sunsetting tier, we'd have to make their subscription an explicit exception in code — not handled by this tool.
- **Razorpay subscription-cancel API behaviors** differ from Stripe on partial-month refunds. Test mode coverage on Razorpay is less robust than Stripe; accept some risk that the first real Razorpay SUNSETTING may surface an edge case.
- **`SUNSETTING` doesn't change `tier_config.monthly_run_limit`** — subscribers who are in the period-end window keep their full quota until cutover. Intentional; denying service before the paid cycle ends is customer-hostile.

## 13 · Future extensions (explicitly not in v1)

- `ARCHIVED` state — post-sunset, for plans with zero subscribers, pulled out of admin UI entirely. Deferred — `SUNSETTING` plans with zero subscribers are fine to leave.
- Per-customer exemption from SUNSETTING — requires a `billing_subscription.lifecycle_exempt BOOLEAN` column. Not needed until we have a real enterprise ask.
- Scheduled-future transition — "move PRO to SUNSETTING on 2026-08-01" as a one-time scheduled job. Useful for announce-now-execute-later pattern; P2.
- Bulk-replace — "move everyone on PRO to TEAM with 1 seat." Aggressive migration tool; not needed for the grandfathered-PRO case.

## 14 · Success criteria

- Day 1: PRO hidden from PlansPage for new buyers; existing PRO subscribers see no change.
- Day 7: at least one successful LISTED↔HIDDEN round-trip via the admin UI on dev.
- Day 14: no escalations from PRO subscribers about missing features or unexpected billing changes.
- Month 3: lifecycle tooling used at least once for a non-PRO decision (e.g., hide a specific plan cycle), proving the generic value.
