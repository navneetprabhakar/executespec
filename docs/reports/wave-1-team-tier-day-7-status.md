# Wave 1 — TEAM tier + lifecycle tooling · Day 7 status

> **Branch:** `feature/wave-1-team-tier-and-lifecycle`
> **Commits (top of branch):**
> - `bf73eb69` Day 6 — /admin/tiers page + lifecycle controller
> - `09986126` Day 5 — TEAM PlansPage card + seats picker + OrgAdmin seat panel
> - `13b20f9f` Day 4 — seat enforcement + webhook sync + seats-adjust endpoint
> - `3cce4dee` Day 3 — gateway cancel-at-period-end + seat-aware Stripe checkout + SUNSETTING side-effects (flag-gated)
> - `1e004a61` Day 2 — TEAM tier + PRO HIDDEN + billing lifecycle gate
> - `9e3c6d6e` Day 1 — tier lifecycle columns + state-machine service
> - `944dd0c5` Stripe integration + test plan (docs)
>
> **Status:** Dev complete. Prod cutover pending Stripe dashboard work + live smoke (per `stripe-integration-and-test-plan.md` §3 + §5).
> **Last updated:** 2026-04-22.

## What shipped

### Backend
- **V121** — `tier_config.lifecycle_state` + `billing_plan.lifecycle_state` VARCHAR(16) CHECK-constrained columns with `lifecycle_changed_at/_by/_reason` metadata and per-column indexes.
- **V122** — TEAM tier `tier_config` row (min seats 2, max 20, concurrent 5, deploys 20, retention 180d) + flips `tier='PRO'` row to `lifecycle_state='HIDDEN'` with reason "TEAM launch — PRO grandfathered".
- **V123** — 4 `billing_plan` rows for TEAM (Monthly/Yearly × USD/INR), `per_seat_*` + `included_seats=1` set; 10% annual discount; `org.seat_count INT NOT NULL DEFAULT 1` column.
- **V124** — seeds `billing.lifecycle.sunsetting.enabled=false` feature flag.
- **`TierLifecycleService`** + **`TierLifecycleController`** — 3-state machine with transition guards (LISTED ↔ HIDDEN free, HIDDEN → SUNSETTING gated by 14-day observation + typed confirmation, no direct LISTED → SUNSETTING). `listAll()` aggregates subscriber count + MRR (USD cents) per tier.
- **SUNSETTING side-effects** (flag-gated) — enumerate `BillingSubscription.findActiveByTier`, call `PaymentGateway.cancelAtPeriodEnd` per subscription, set `status='CANCELLING_AT_PERIOD_END'`, write `TIER_SUNSET_QUEUED` audit rows.
- **Stripe**: `cancelAtPeriodEnd` + `updateSubscriptionQuantity` + seat-aware `createCheckoutSession` overload. Webhook `checkout.session.completed` now fetches Subscription to read `line_items[0].quantity` and syncs to `BillingSubscription.totalSeats` + `org.seat_count`.
- **Razorpay**: `cancelAtPeriodEnd` override delegates to existing BillingGateway `cancel_at_cycle_end`. Seat-aware checkout deferred (see stripe-integration-and-test-plan §6).
- **TEAM tier routing** — `TierEnforcementService` allows TEAM users through the org-counter path (shared credit pool reuses existing `Org.monthlyRunCount` with `effectiveOrgLimit = seats × 50`). `OrgService.assertCanCreateOrg` allows TEAM.
- **Seat enforcement** — `InvitationService.createInvitation` rejects with **402 Payment Required** when TEAM org hits `subscription.totalSeats` cap (distinct from 403 "upgrade tier").
- **`POST /api/org/me/seats/adjust`** — ORG_ADMIN/SUPER_ADMIN only. Rejects 400 if reducing below active member count, 400 if outside [2,20], 402 if no active TEAM subscription. Pushes to Stripe via `updateSubscriptionQuantity` (prorated), eagerly syncs `BillingSubscription.totalSeats` + `org.seat_count`; webhook is idempotent.
- **Billing endpoint filter** — `GET /api/billing/plans` strips rows where either `tier_config.lifecycle_state != 'LISTED'` OR `billing_plan.lifecycle_state != 'LISTED'` (unless caller is SUPER_ADMIN with `?includeHidden=true`). `POST /api/billing/subscribe` rejects 404 on non-LISTED plans.

### Frontend
- **PlansPage**: tier order updated `['PRO','MAX']` → `['PRO','TEAM','MAX']`. PRO auto-hides for non-admin callers because backend filters it. TEAM card shows seat picker (2-20, default 3) + live price preview. `subscribe()` threads `seats` through to Stripe checkout.
- **PlanCard**: TIER_RANK adds TEAM=2.5 (fractional so upgrade/downgrade math stays correct). TEAM-specific feature list replaces generic "X seats included".
- **OrgAdminDashboard**: new "Team seats" GlassPanel above Billing Overview, only visible when `org.tier === 'TEAM'`. 3 KPIs (active members / seats, credits pool, usage this period) + inline seat-adjust form. Calls `adjustOrgSeats()`, shows success message or error inline.
- **`/admin/tiers`** new page (SUPER_ADMIN only) — table with state chips (LISTED green / HIDDEN amber / SUNSETTING red), subscriber count, MRR (USD). List/Hide/Sunset action buttons gated by current state. SUNSETTING action opens a modal with MRR impact pre-computation + reason textarea + typed-tier-name confirmation.
- **Admin nav entry** in `AdminPage.tsx` (Tier lifecycle button, Layers icon).
- **Type updates**: `AuthUser.tier` + `useViewMode` Tier union extend with `'TEAM'`.

### Docs
- `CLAUDE.md` — Tiers bullet + migration range + Key File Locations row for v2.6.1 Wave 1.
- `docs/USER_MANUAL.md` — new "v2.6.1 what's new" section (team buyer + SUPER_ADMIN surfaces).
- `docs/document_map.md` — Wave 1 entry linking both plan docs.

## Verification done

- `JAVA_HOME=... mvn test` → **288 tests, 0 failures, 0 errors** on every Day 1-6 commit.
- `npx tsc --noEmit` → clean on Day 5 + Day 6.
- `npm --prefix frontend run build` → 1.91 MB bundle, no errors.
- `preview_start vite-dev` → React app loads cleanly, no console errors (backend not running so plans/org data not exercised at runtime).

## What's NOT done (explicitly)

- **Stripe dashboard artefacts** — Product + 2 Prices (USD + INR) need to be created manually in Stripe test mode; blocks end-to-end purchase flow. Runbook: `docs/plans/stripe-integration-and-test-plan.md §3`.
- **`STRIPE_TEAM_PRICE_ID`** env var + `resolvePriceId()` update for TEAM — Gap 2.2 of Stripe plan. `resolvePriceId` currently defaults TEAM purchases to `proPriceId` (wrong). Needs env wiring + code tweak before the first real TEAM checkout can run.
- **Live dev smoke** — §5 of stripe plan. Requires env vars + Stripe CLI or webhook endpoint pointed at `dev.executespec.ai`.
- **Razorpay TEAM seat-aware path** — deferred per stripe-plan §6 until Stripe TEAM conversion validates.
- **`SUNSETTING` live fire** — feature flag is OFF. First real fire is an explicit operator decision, minimum 14 days after PRO was flipped to HIDDEN.
- **`tier-sunsetting.html`** email template — not shipped in Day 6 (out of scope for Wave 1 dev; flag is OFF so no live fire depends on it).

## Known limitations

- **SUPER_ADMIN 400 if `SUNSETTING` transition attempted without `confirmTierName`** — intentional guard (server-side + UI), documented.
- **`OrgAdminDashboard` seats panel shows `members.length` as "active members"** — this is the analytics-window member count, NOT the authoritative `org_member` row count. Close enough for v1; reconcile with server-authoritative count post-Wave-1.
- **`BillingService.handleStripeCheckoutCompleted`** fetches the Stripe Subscription eagerly per checkout (adds 1 network round-trip). Could be avoided by expanding `line_items` on the session; deferred as an optimization.

## Next steps

Per `stripe-integration-and-test-plan.md`:
1. **Day 1 (2026-04-23):** Stripe dashboard runbook §3 — create Product + 2 Prices + webhook.
2. **Day 2 (2026-04-24):** Gap 2.2 + 2.4 — env var + `resolvePriceId` + seats flow in BillingService.
3. **Day 3-5:** Live dev smoke end-to-end (§5 protocol).
4. **2026-04-28/29:** Prod cutover per `team-tier-plan.md §5` Day 8.
