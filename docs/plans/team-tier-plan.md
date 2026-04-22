# TEAM tier — execution plan

> **Scope:** add a new tier between PRO and MAX (shaped for 2-20 dev teams), **plus** generic 3-state tier-lifecycle tooling (`LISTED` / `HIDDEN` / `SUNSETTING`) so PRO can be hidden on TEAM launch and any tier can be retired cleanly later. Bundled per Option Z chosen 2026-04-22.
> **Owner:** Navneet
> **Origin:** PM-view persona analysis 2026-04-22 (session: "how do we increase the scores for each persona + PM view"). Flagged as P0 — "PRO is the anti-tier" was the single strongest cross-persona signal.
> **Target ship:** 2026-04-28 → 2026-05-06 (1.5 dev-weeks, 7 working days). Land before Product Hunt launch so team-buyer traffic doesn't bounce off PRO.
> **Status:** PLANNED. Not started. Do not begin implementation until this plan is explicitly approved.
> **Related:** `docs/plans/tier-lifecycle-plan.md` (standalone reference for the lifecycle tooling — this doc folds it in for the TEAM launch), `docs/reports/session-handoff-2026-04-21.md` (pricing v3), `website/tier-decision.md` (tier rationale), `website/pricing-analysis.md` (cost data), `docs/plans/persona-roadmap-2026-04-22.md` (master roadmap).

## 1 · Why this exists

Persona assessment on 2026-04-22 gave PRO a rating of **3-5 out of 10** from every buyer persona:
- Engineer: "same price as Cursor but 5 HIGH runs/mo vs. Cursor's unlimited completions"
- VP of Tech: "20 credits × 30 devs = $600/mo for 600 credits total; catastrophic positioning"
- C-Suite: "wrong shape for a small engineering team, I'd skip PRO and go straight to MAX"

MAX scored 6-8, ENTERPRISE scored 4-7 (potential). **PRO is converting individual buyers badly and losing team buyers at the door.** A TEAM tier at $49/seat fixes the gap — matches the $49-79/seat zone occupied by Cursor Teams, Copilot Business, Linear Standard.

## 2 · Tier shape

| Dimension | PRO (hidden — see §2a) | **TEAM (new)** | MAX (today) |
|---|---|---|---|
| Price | $20/mo | **$49/seat/mo** | $29/mo + BYOK |
| Credits | 20/mo | **50 credits/seat/mo, shared pool** | Unlimited (fair-use) |
| Seats | 1 | **2-20, seat-based billing** | 1 (individual BYOK) |
| Credit pool | per-user | **org-shared** | n/a |
| Projects | 2 | **5** | 10 |
| Repos per project | 2 | **5** | 10 |
| SSO | — | **Google OIDC (optional org-level enforcement)** | — |
| Admin console | — | **Seat management · pooled billing · audit feed** | — |
| Retention | 90 days | **180 days** | 90 days |
| BYOK | — | — | ✅ |

**Positioning:** TEAM is "Pro for 2-20 devs who share one tool." MAX stays as the BYOK power-user / small-startup tier. ENTERPRISE stays as "MAX + SSO/SAML + SLA + custom contract." PRO is hidden from new buyers but remains active for existing subscribers — see §2a.

## 2a · PRO handling — HIDDEN state via generic lifecycle tooling (decided 2026-04-22)

**Decision:** PRO becomes a grandfathered tier. Hidden from PlansPage / UpgradeModal / PricingPage for new buyers, but existing PRO subscribers stay on PRO indefinitely with no change to price / features / quota.

**Why hide instead of remove:**
- Zero churn risk — no forced migration for paying customers
- Trivial rollback — flip the state to `LISTED` if TEAM conversion disappoints
- Matches SaaS industry pattern (Stripe, Linear, Notion, Figma all grandfather legacy plans)
- No Terms / MSA renegotiation required — PRO tier still exists, just invisible

**Implementation: generic 3-state tier lifecycle** (see §2b for the mechanism). PRO moves to `HIDDEN` on the day TEAM ships. Long-term retirement to `SUNSETTING` is a separate explicit decision (the lifecycle tooling supports it; this plan does not trigger it).

**Why $49/seat:** anchors below Cursor Teams ($40 unlimited completions, $80 unlimited agent requests), above Copilot Business ($19/seat). The 50 credits/seat at $1/credit = $50 retail value — we lose $1/seat to positioning. Worth it.

**Why shared credit pool, not per-seat:** teams don't consume evenly. A 10-dev team has 3 heavy users who'd exhaust their personal quota while 7 light users stockpile theirs. Shared pool = "all seats = 500 credits/mo total" is simpler, maps to how devs actually collaborate.

## 2b · Tier lifecycle tooling (bundled with TEAM launch per Option Z)

**Replaces** the single-purpose `pricing.pro.visible` feature flag idea with a reusable 3-state mechanism on `tier_config` + `billing_plan`.

| State | Visibility | New signups | Renewals for existing | Typical transition from |
|---|---|---|---|---|
| `LISTED` (default) | Shown on PlansPage / UpgradeModal / PricingPage | Accepted | Continue | — |
| `HIDDEN` | Hidden from every public surface | Rejected (404 "plan not available") | **Continue untouched** | `LISTED` (instant, reversible) |
| `SUNSETTING` | Hidden + banner on existing-subscriber dashboard | Rejected | **`cancel_at_period_end` set on Stripe/Razorpay** + email to affected subscribers | `HIDDEN` (must pass confirmation modal; not reversible direct) |

**Transitions are asymmetric and intentional:**
- `LISTED` ↔ `HIDDEN` — instant, fully reversible, zero customer impact.
- `HIDDEN` → `SUNSETTING` — requires explicit SUPER_ADMIN action + modal that pre-computes MRR impact + requires typing the tier name to confirm.
- `SUNSETTING` → `HIDDEN` — technically possible but requires per-customer Stripe API calls to un-cancel; surfaced in UI as "partially reversible, contact Stripe support."
- **Never direct `LISTED` → `SUNSETTING`** — forced observation window of at least 14 days in `HIDDEN` before sunset is legal.

**Schema addition (Flyway V121 — lifecycle migration):**
```sql
ALTER TABLE tier_config
  ADD COLUMN lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'LISTED'
    CHECK (lifecycle_state IN ('LISTED', 'HIDDEN', 'SUNSETTING')),
  ADD COLUMN lifecycle_changed_at TIMESTAMPTZ,
  ADD COLUMN lifecycle_changed_by VARCHAR(64);

ALTER TABLE billing_plan
  ADD COLUMN lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'LISTED'
    CHECK (lifecycle_state IN ('LISTED', 'HIDDEN', 'SUNSETTING')),
  ADD COLUMN lifecycle_changed_at TIMESTAMPTZ,
  ADD COLUMN lifecycle_changed_by VARCHAR(64);
```

Both levels independently mutable so we can hide a single cycle (e.g. Yearly INR) without hiding the whole tier.

**Backend behavior:**

```
LISTED     → current behavior
HIDDEN     → GET /api/billing/plans filters out hidden rows (SUPER_ADMIN can pass ?includeHidden=true)
             POST /api/billing/subscribe rejects purchase for HIDDEN tier (404)
             BillingSubscription reads / renewals continue untouched
SUNSETTING → all of HIDDEN, PLUS:
             - enumerate ACTIVE BillingSubscription rows on the tier
             - for each: StripeGateway.cancelAtPeriodEnd(subscriptionId)
                        + RazorpayGateway.cancelAtPeriodEnd(subscriptionId)
             - set BillingSubscription.status = 'CANCELLING_AT_PERIOD_END'
             - send `tier-sunsetting.html` email to each affected user
             - existing PeriodEndDowngradeScheduler handles the cutover
             - audit row per affected subscription (event_type = TIER_SUNSET_QUEUED)
```

**Admin UI (`/admin/tiers` — new page):**

```
┌ FREE        [LISTED ▼]       active                      0 subscribers · $0 MRR
├ PRO         [HIDDEN ▼]       hidden since 2026-05-05     N subscribers · $Y MRR (continues)
├ TEAM        [LISTED ▼]       active (launched 2026-05-05) 0 subscribers · $0 MRR (new)
├ MAX         [LISTED ▼]       active                      M subscribers · $Z MRR
└ ENTERPRISE  [LISTED ▼]       active                      K subscribers · custom MRR
```

`HIDDEN`-to-`SUNSETTING` transition opens a confirmation modal:

> **Moving PRO to SUNSETTING will queue cancellation at period end for 12 active subscriptions totalling $240/mo in MRR.**
>
> Each affected subscriber will receive a notice email 60 days before their next renewal, with options to upgrade to TEAM ($49/seat), MAX ($29 + BYOK), or drop to FREE.
>
> This is partially reversible — un-cancelling requires per-customer Stripe API calls.
>
> Type `PRO` below to confirm.
> [    ]    [Cancel]    [Move to SUNSETTING]

**Routes:**
- `GET /api/admin/tiers` — list with MRR + subscriber count per tier
- `PUT /api/admin/tiers/{tier}/lifecycle` — body `{state, reason}`, SUPER_ADMIN
- `PUT /api/admin/billing-plans/{planId}/lifecycle` — granular per-plan override
- `GET /api/billing/plans?includeHidden=true` — SUPER_ADMIN dev-time inspection

**Email template:** new `email/tier-sunsetting.html` fired from `SUNSETTING` transition; renders plan name, period end date, upgrade options. Same Graphite+Signal palette as `tier-upgraded.html`.

**Reuse across tiers:** the lifecycle tooling is generic. PRO is the first customer; MAX or any future experiment tier can use the same state machine without new code.

**Why include in TEAM launch (Option Z):** PRO must transition to `HIDDEN` on the day TEAM ships, or the pricing page shows both PRO and TEAM side-by-side and buyers get confused. A temporary feature flag (Option X) would work but we'd immediately have to replace it with the generic tool in 2-3 weeks anyway. Cost: +2 days in the TEAM shipping window for genuinely reusable infra. Deferred: the `SUNSETTING` transition + email template (can land in the same release but doesn't need to fire on day one).

## 3 · Scope — what ships in v1 (the 1.5-week window)

**IN scope — TEAM tier (core):**
1. New `TEAM` row in `tier_config` (monthly_run_limit derived from seat count × 50)
2. 4 new rows in `billing_plan` (Team Monthly INR + USD, Team Yearly INR + USD)
3. Seat-based Stripe checkout path (uses existing `LineItem.setQuantity()`)
4. Seat-based Razorpay checkout path (verify existing flow handles quantity — may need a tweak)
5. `Org.seatCount` column + enforcement when inviting members (max = subscription.totalSeats)
6. Frontend PlansPage: insert TEAM card between PRO and MAX, with seat-count input
7. Frontend OrgAdminDashboard: seat assignment + pooled credit burn + "add seats" CTA
8. PRO → TEAM upgrade path (same modal, user picks seat count)
9. `TierEnforcementService` routes TEAM users through org-counter (already works — reusing existing path)
10. Update USER_MANUAL.md + docs/document_map.md + CLAUDE.md §Tiers

**IN scope — tier lifecycle tooling (bundled):**
11. `lifecycle_state` column on both `tier_config` and `billing_plan` (V121 migration)
12. `TierLifecycleService` with `LISTED` / `HIDDEN` / `SUNSETTING` state machine
13. Admin UI `/admin/tiers` with per-tier state dropdown + MRR-impact confirmation modal
14. Backend filtering: `GET /api/billing/plans` strips non-`LISTED` unless SUPER_ADMIN passes `?includeHidden=true`
15. Purchase rejection: `POST /api/billing/subscribe` 404s on non-`LISTED` tiers
16. `tier-sunsetting.html` email template (template lands in this release; first fire is post-launch, manually triggered)
17. `StripePaymentGateway.cancelAtPeriodEnd()` + `RazorpayPaymentGateway.cancelAtPeriodEnd()` helpers
18. **Day-1 transition: PRO → `HIDDEN`** (single SQL UPDATE in the TEAM launch migration)

**OUT of scope for v1** — captured in follow-up docs:
- Google OIDC SSO **enforcement** (can log in with email+password; TEAM admins can invite via Google but can't *require* it yet). SSO enforcement is P1 post-launch.
- Per-seat activity dashboards (pool view is enough for v1)
- Auto-downgrade on seat reduction (v1 = prorated credit, admin manually removes members)
- Seat-level audit log export (v1 = existing org-wide audit is enough)
- PRO → `SUNSETTING` transition (tooling ships, first fire is a deliberate decision 8+ weeks post-launch based on TEAM conversion data)
- "Compare plans side-by-side" PlansPage modal (bucket 1 post-launch polish)

## 4 · Exact surfaces — file + line pointers

_From the audit 2026-04-22. Pointers may drift — re-grep before editing._

### 4.1 Backend — tier config
- `src/main/java/com/navneet/executespec/auth/entity/TierConfig.java:1-87` — entity definition, no code change (DB row add only)
- `src/main/java/com/navneet/executespec/auth/service/TierEnforcementService.java:426-436` — `getMonthlyLimit()` reads from DB; add TEAM fallback constant at lines 54-57
- `src/main/java/com/navneet/executespec/auth/service/TierEnforcementService.java:244-246` — complexity-gate check currently matches MAX/ENTERPRISE/PRO; add TEAM
- `src/main/java/com/navneet/executespec/auth/service/OrgService.java:45` — FREE-only gate on org creation; allow TEAM

### 4.2 Backend — billing
- `src/main/java/com/navneet/executespec/billing/entity/BillingPlan.java:29-31,51-58` — `tier`, `per_seat_inr`, `per_seat_usd`, `included_seats` columns **already exist**; no schema change
- `src/main/java/com/navneet/executespec/billing/entity/BillingSubscription.java:55-59` — `totalSeats`, `extraSeats` **already exist**
- `src/main/java/com/navneet/executespec/billing/gateway/StripePaymentGateway.java:59-62` — `SessionCreateParams.LineItem.setQuantity()` already wired; verify seat count flows through
- `src/main/java/com/navneet/executespec/billing/gateway/RazorpayPaymentGateway.java` — audit `BillingGateway` seat-quantity handling; may need a minor patch

### 4.3 Backend — org + seat enforcement
- `src/main/java/com/navneet/executespec/auth/entity/Org.java:40-45` — `monthlyRunCount` + `runCountResetAt` already org-level; **no changes** for shared pool
- `src/main/java/com/navneet/executespec/auth/entity/Org.java` — add `seatCount INT` column (Flyway migration)
- `src/main/java/com/navneet/executespec/auth/service/InvitationService.java` — enforce `org.memberCount < subscription.totalSeats` before accepting invite

### 4.4 Frontend
- `frontend/src/pages/PlansPage.tsx:51-56` — change `['PRO', 'MAX']` → `['PRO', 'TEAM', 'MAX']`; add seat-count picker on TEAM card
- `frontend/src/pages/admin/OrgAdminDashboard.tsx:1-100` — add "Seats" section: list/invite/remove, pooled-credit meter, "add seats" CTA
- `frontend/src/lib/api.ts` — new `purchaseTeamSubscription(seats: number, billingCycle, gateway)` function

### 4.5 Flyway migrations (3 new — Option Z adds lifecycle columns)
- `V121__tier_lifecycle_state.sql` — `ALTER TABLE tier_config ADD lifecycle_state + lifecycle_changed_at + lifecycle_changed_by`; same 3 columns on `billing_plan`. CHECK constraint on the state enum.
- `V122__team_tier_config.sql` — INSERT into `tier_config` (`tier='TEAM'`, `lifecycle_state='LISTED'` explicitly, `monthly_run_limit` computed downstream, other flags). **Same migration** updates existing PRO row to `lifecycle_state='HIDDEN'` (explicit day-1 hide).
- `V123__team_billing_plans.sql` — INSERT 4 rows into `billing_plan` (Team/Monthly/INR, Team/Monthly/USD, Team/Yearly/INR, Team/Yearly/USD, all `LISTED`). Also adds `org.seat_count INT NOT NULL DEFAULT 1`. **Same migration** sets existing PRO billing_plan rows to `lifecycle_state='HIDDEN'`.

### 4.6 Backend — lifecycle service (new)
- `src/main/java/com/navneet/executespec/billing/lifecycle/TierLifecycleService.java` (new)
- `src/main/java/com/navneet/executespec/billing/lifecycle/TierLifecycleController.java` (new, routes under `/api/admin/tiers`)
- `src/main/java/com/navneet/executespec/billing/gateway/StripePaymentGateway.java` — new method `cancelAtPeriodEnd(String subscriptionId)` that calls `Subscription.update(new SubscriptionUpdateParams.Builder().setCancelAtPeriodEnd(true).build())`
- `src/main/java/com/navneet/executespec/billing/gateway/RazorpayPaymentGateway.java` — new method `cancelAtPeriodEnd(String subscriptionId)` that calls Razorpay subscription cancel API with `cancel_at_cycle_end=1`
- `src/main/java/com/navneet/executespec/billing/controller/BillingController.java` — `GET /api/billing/plans` filters on `lifecycle_state='LISTED'` (unless `?includeHidden=true` + SUPER_ADMIN)
- `src/main/java/com/navneet/executespec/billing/service/BillingService.java::subscribe()` — rejects non-`LISTED` plan with `ResponseStatusException(404, "Plan not available")`

### 4.7 Frontend — admin tier UI (new)
- `frontend/src/pages/admin/TierLifecyclePage.tsx` (new, route `/admin/tiers` behind `isSuperAdmin`)
- `frontend/src/components/admin/TierLifecycleModal.tsx` (new — SUNSETTING confirmation modal with MRR pre-computation + tier-name typing gate)
- `frontend/src/lib/api.ts` — `fetchTiersWithLifecycle()`, `updateTierLifecycle(tier, state, reason)`, `updatePlanLifecycle(planId, state, reason)`
- `frontend/src/pages/PlansPage.tsx:51-56` — `['PRO', 'MAX']` → `['PRO', 'TEAM', 'MAX']` **and** filter out any tier where `lifecycle_state !== 'LISTED'` (so PRO vanishes on ship-day via the V122 migration)

### 4.8 Email template (new)
- `src/main/resources/templates/email/tier-sunsetting.html` — Graphite+Signal palette, renders `{planName, periodEnd, upgradeOptions, baseUrl}`. Fired only on `SUNSETTING` transition, not on TEAM launch day.

## 5 · Day-by-day execution (2026-04-28 Tue → 2026-05-06 Wed)

7 working days + 2 buffer. Starts Tuesday so TEAM lands before PH traffic week. Day 4 is the April 30 complimentary-PRO promo execution — kept light deliberately.

**Day 1 (Apr 28, Tue):** Lifecycle schema + DB migrations
- Write V121 (lifecycle columns on both tables), V122 (TEAM tier_config + PRO → HIDDEN), V123 (4 billing_plan rows + org.seat_count)
- Test migrations forward + rollback on local Postgres
- Stub `TierLifecycleService` with the 3-state enum + transition validation (no Stripe call yet)
- Unit test the state machine transitions: LISTED↔HIDDEN reversible, LISTED→SUNSETTING rejected, HIDDEN→SUNSETTING requires confirmation flag

**Day 2 (Apr 29, Wed):** Backend — TEAM tier + billing filtering
- Add TEAM to `TierEnforcementService` fallback constants + complexity gate + `OrgService.assertCanCreateOrg`
- Update `BillingService.subscribe` to reject non-`LISTED` plans (404 "Plan not available")
- Update `BillingController.getPlans` to filter on `lifecycle_state='LISTED'` (SUPER_ADMIN `?includeHidden=true` bypass)
- Unit test: `assertAndIncrementRunCount()` on a TEAM user hits org-counter, not user-counter

**Day 3 (Apr 30, Thu):** Billing gateway + seat flow · **[also: complimentary PRO promo execution per separate plan — reserve 1-2h morning]**
- Stripe TEAM checkout → `LineItem.quantity = seats`, subscription `totalSeats` persisted on webhook
- `StripePaymentGateway.cancelAtPeriodEnd()` — 10 LOC, tested against Stripe test mode
- Razorpay TEAM checkout (audit + patch if needed); `RazorpayPaymentGateway.cancelAtPeriodEnd()`
- Integration test against Stripe test mode: $49 × 5 seats = $245 charge

**Day 4 (May 1, Fri):** Seat enforcement + org admin backend
- Add `org.seat_count` column sync from `BillingSubscription.totalSeats` on webhook
- `InvitationService` blocks invites when `org.memberCount >= subscription.totalSeats` (402 + "buy more seats")
- New endpoint `POST /api/org/{id}/seats/adjust` — admin-only, updates Stripe subscription quantity + local row

**Day 5 (May 2, Sat):** Frontend — PlansPage + OrgAdminDashboard
- PlansPage: TEAM card between PRO + MAX, seat picker (2-20), live price preview; PRO card disappears automatically because V122 flipped it to HIDDEN
- OrgAdminDashboard: "Seats" section (table + add/remove/invite-more)
- E2E manual test on local: signup as new user → create org → upgrade to TEAM with 3 seats → invite 2 members → verify pooled credit meter

**Day 6 (May 3, Sun):** Frontend — admin tier lifecycle UI
- `/admin/tiers` page with per-tier dropdown + MRR column
- `TierLifecycleModal` confirmation UX for SUNSETTING transition (tier-name typing gate, MRR pre-compute)
- Hit `PUT /api/admin/tiers/PRO/lifecycle` with `{state:'HIDDEN'}` from the UI to confirm the round-trip works (it's already HIDDEN from Day 1 migration; this verifies the flip path)

**Day 7 (May 4, Mon):** Copy + docs + dev smoke
- Copy pass on TEAM positioning (PlansPage card + tooltip + upgrade modal)
- Update USER_MANUAL.md §Tiers (§13 or new), CLAUDE.md §Tiers, website/tier-decision.md + website/index.html
- New email template `tier-sunsetting.html` (ships with code; first fire is post-launch)
- Deploy to `dev.executespec.ai`, run smoke: signup → upgrade → invite flow end-to-end with real payment-test-mode flow
- Verify PRO is gone from PlansPage for fresh signups; existing PRO accounts still see their plan on SettingsPage

**Day 8 (May 5, Tue):** Prod cutover
- PR `dev` → `main`, review, merge
- Deploy to prod GCE
- Flyway migrations run cleanly on prod (V121-V123)
- Smoke on executespec.ai: signup → TEAM upgrade → invite
- Verify legacy PRO user on prod: still sees "PRO" tier in their dashboard, still gets renewed by Stripe
- Update `website/index.html` hero copy + cross-link `website/tier-decision.md`

**Day 9 (May 6, Wed):** Buffer + announcement
- Address any prod bugs from Day 8 smoke
- Social + newsletter announcement: "TEAM tier is live — $49/seat for 2-20 dev teams"
- Mark PRO tier as "grandfathered" in Stripe dashboard (stop adding Stripe customers; legacy auto-renews continue)
- Flip `/admin/tiers` from dev-only gate (if we chose to gate it) to SUPER_ADMIN always
- Set 90-day calendar reminder: "Review PRO renewal tail, consider PRO → SUNSETTING if TEAM conversion > 25%"

## 6 · Pricing math + margin check

| Item | Number |
|---|---|
| TEAM price | $49/seat/mo |
| Credits per seat | 50/mo |
| Pool for 10 seats | 500 credits/mo |
| Real LLM cost (blended mix: 70% LOW, 20% MEDIUM, 10% HIGH+Mobile) | **$0.33/credit** (from `website/pricing-analysis.md §4`) |
| Cost per seat (worst case: 50 credits fully consumed) | $16.50 |
| Gross margin per seat | **$32.50 → 66%** |
| At 10 seats: monthly revenue | $490 |
| At 10 seats: monthly LLM cost (100% utilization) | $165 |
| Gross margin at full utilization | **$325 → 66%** |
| Realistic utilization (mix of heavy + light users) | ~60% → $99 LLM cost → **80% margin** |

**Downside scenario:** Anthropic-preferring team burns pool on HIGH runs at $2.65/credit (per-run cost, 4-credit HIGH). 500-credit pool @ HIGH-only = 125 HIGH runs × $2.65 = $331 LLM cost on $490 revenue = **32% margin.** Still positive. The CAC-absorb-on-Anthropic-HIGH pattern from pricing v3 holds at team scale too.

## 7 · Backward compatibility

- **Existing PRO users:** unaffected. PRO tier continues to exist with 20 credits/mo. TEAM is additive.
- **Existing ENTERPRISE users:** unaffected. ENTERPRISE continues to require custom contract.
- **Existing MAX users:** unaffected, but should be shown a "switch to TEAM if your org has 3+ devs" nudge in the billing UI.
- **FREE users:** unaffected.
- **PRO → TEAM upgrade:** new flow, opens seat-count picker. Backend prorates mid-cycle.
- **TEAM → MAX downgrade:** allowed; removes team seats, user becomes solo MAX.

## 8 · Success metrics

| Metric | Baseline (today) | 30-day target post-TEAM | Data source |
|---|---|---|---|
| Team-sized orgs (≥2 members) on paid | unknown — need to count | +50% | `org` JOIN `billing_subscription` |
| Average ACV for team buyer | ~$20 (PRO) | $240+ ($49 × ~5 seats avg) | `run_cost_log` + `billing_subscription` |
| "Too few credits" support tickets from team buyers | qualitative | ↓ 80% | `contact_feedback` inbox |
| PRO → TEAM upgrade rate (among orgs with ≥2 members) | 0% | 25% | `activity_audit` tier change events |
| TEAM tier retention month-2 | n/a | ≥ 70% | `billing_subscription` renewals |

**Kill criteria:** if 30-day retention on TEAM is < 50% OR avg seats purchased < 3, re-evaluate pricing/positioning. A 2-seat TEAM at $98 is not meaningfully different from 2× PRO at $40; if buyers are purchasing 2 seats consistently, we priced wrong.

## 9 · Related P0 items (separate plan docs)

The persona analysis identified two other P0 items that are **out of scope** here but are now captured in their own plan docs:

1. **Activation instrumentation (6-event funnel)** — [`docs/plans/activation-instrumentation-plan.md`](activation-instrumentation-plan.md). Unblocks every future pricing / tier / feature decision. 2-week estimate. Starts 2026-05-07 after TEAM ships.
2. **TTFV < 3 min (sample spec + sandbox repo on signup)** — [`docs/plans/ttfv-reduction-plan.md`](ttfv-reduction-plan.md). Largest drop-off in the funnel. 2-week estimate. Runs in parallel with activation instrumentation since the surface areas don't overlap.

Master sequencing + P1/P2/P3 items captured at [`docs/plans/persona-roadmap-2026-04-22.md`](persona-roadmap-2026-04-22.md).

## 10 · Pre-flight checklist (run before Day 1)

- [ ] Stripe test mode has a TEAM Product + Price ID at $49/mo with per-unit pricing enabled
- [ ] Razorpay test mode plan for Team (optional — India-local TEAM is small addressable market; may defer)
- [ ] Backup `tier_config`, `billing_plan`, `org` tables (`pg_dump` before migration)
- [ ] Verify `Org.seat_count` column doesn't already exist (audit was read-only — re-check)
- [ ] Feature flag `team.tier.enabled` seeded OFF — gate PlansPage + purchase flow until Day 7 smoke passes
- [ ] Feature flag `admin.tiers.ui.enabled` seeded OFF until Day 6 (dev-side verification)
- [ ] Test-mode user with existing PRO subscription — use to verify PRO → TEAM upgrade proration
- [ ] Capture current PRO subscriber count + MRR for the `SUNSETTING` modal's baseline
- [ ] Confirm Stripe `Subscription.cancelAtPeriodEnd=true` does NOT refund mid-cycle (test in Stripe test mode)

## 11 · Open decisions (resolve before Day 1)

1. **Team minimum seats:** is it 2 or 3? Recommend 2 (lowest friction for early-stage teams) — decide.
2. **Max seats in self-serve:** is it 20 or 50? Recommend 20 (force Enterprise conversation above that) — decide.
3. **Included seats in base price:** should `$49` include 1 seat (so 5 seats = $245) or $49 = entry price then $49/additional seat (so 5 seats = $245 too)? Identical math; pick the copy that lands better.
4. **INR pricing:** exchange rate per v2.6.0 pivot is market-based. ₹4,000/seat/mo or ₹3,999 anchor? Round down for India-friendly anchor — recommend ₹3,999.
5. **Annual discount:** standard is 10 months for 12 prepaid. Ship on Day 5 or defer to v2? Recommend ship — 2 extra rows in `billing_plan`.

## 12 · What this plan deliberately does NOT do

- **No SSO enforcement.** v1 = Google OAuth2 available as a login option for TEAM admins (already exists). "TEAM = must log in via OAuth" is P1.
- **No SAML.** Stays in ENTERPRISE tier.
- **No seat-level audit log.** Org-wide audit is enough for v1.
- **No per-seat activity dashboards.** Pool view only.
- **No downgrade-on-seat-reduction automation.** v1 = admin manually removes members when reducing seats, proration happens via Stripe.
- **No self-serve seat gifting / founder-grant.** Admin upgrades via `SuperAdminController` if needed.

---

## Appendix A — Copy for PlansPage TEAM card

```
TEAM — $49/seat/mo
For engineering teams of 2-20.

Everything in PRO, plus:
  • 50 credits/seat/month (shared pool)
  • Google OIDC sign-in
  • 5 projects · 5 repos per project
  • Pooled org admin dashboard
  • 180-day run history
  • Team invoice (consolidated billing)

[  Seat count: [ 3 ▼ ]  ]
Total: $147/month — 150 credits pooled across your team

[Upgrade to TEAM →]
```

## Appendix B — Copy for PRO deprecation nudge (optional)

On the PRO card, add a small banner:

```
ⓘ Building as a team? TEAM starts at $98/mo for 2 seats with 100 pooled credits.
  Same features you get here, built for multi-user billing. [Compare →]
```
