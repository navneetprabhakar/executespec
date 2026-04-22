# Wave 5 — startup programs + signup polish + progressive disclosure

> **Window:** 2026-06-18 → 2026-07-01 (2 weeks)
> **Status:** PLANNED. V129 schema shipped; services + frontend incremental.
> **Origin:** [persona-roadmap-2026-04-22.md §5.5](persona-roadmap-2026-04-22.md).

## Scope overview

Eleven items in the roadmap (W5.1–W5.11). Most are small (3-4 days each);
we bundle them into this wave because they're all "polish + program"
work — no single item is load-bearing on any other wave.

## Per-item status

### W5.1 — Startup program (50% off MAX for < 20 employees, 12 months)
**Schema (V129):** `startup_program_application` table with status machine
(PENDING / APPROVED / DECLINED) + `discount_expires_at`.

**Backend work (not yet committed):**
- `POST /api/startup-program/apply` — captures company + size + website
- `GET /api/admin/startup-program` (SUPER_ADMIN) — queue view
- `PUT /api/admin/startup-program/{id}/decide` — approve/decline
- `BillingService.subscribe` — honour startup-discount when active on user
  (apply 50% off line-item price for MAX plan)

**Frontend:**
- PlansPage MAX card shows "Startup program — 50% off for a year" when
  the user hasn't applied
- `/startup-program` page — apply form + status tracker

### W5.2 — Startup Enterprise carve-out ($99/seat, 10-seat min, no MSA)
**Backend:** add a third path in the Stripe/Razorpay subscribe flow
for `tier=ENTERPRISE_STARTUP` — fixed $99/seat, minimum 10 seats, no
custom contract. Still gated behind an application flow (reuse
`team_trial_request` table from Wave 3 with a new `target_tier` field).

**Schema addition (follow-up migration V130):**
```sql
ALTER TABLE team_trial_request ADD COLUMN target_tier VARCHAR(16) NOT NULL DEFAULT 'TEAM';
```

### W5.3 — Migration playbook + template spec library
**Content-only:**
- `docs/migration-from-cursor.md` — "switch from Cursor in a week"
  runbook with before/after code examples
- `docs/migration-from-copilot.md` — same for Copilot Business
- `src/main/resources/sample-specs/` gets 5 more templates:
  auth-flow, rest-api-crud, db-migration, payment-webhook, csv-ingest

**Frontend:** `/migrate` page linking to both playbooks + sample library.

### W5.4 — Weekly office hours (founder-run, 30 min)
**No code:** calendar + Luma event. Link from `/billing` page's "Get
help" area for paying tiers.

### W5.5 — Progressive disclosure (critical-path-only UI)
**Frontend-only:**
- New `useCriticalPath()` hook — returns true when user has completed
  `spec → plan → run → PR merged` at least twice
- Sidebar shows only `Runs / Projects / Plans / Settings` until hook
  returns true; all other nav items hidden behind an "Advanced" toggle
- Feature flag `ui.progressive-disclosure.enabled` (default OFF — opt-in
  during the wave; flip to default-on after Wave 5 ships)

### W5.6 — BYOK credit-like usage meter
**Frontend-only:**
- `BillingPage` shows monthly LLM spend per provider for MAX tier users
- Reads existing `run_cost_log` data via new
  `GET /api/billing/byok-usage?period=monthly` endpoint

**Backend endpoint is trivial:** 20 LOC aggregating `run_cost_log`
by provider + month.

### W5.7 — Publish fair-use numbers for MAX
**Content-only:**
- `website/pricing-analysis.md` MAX section adds: "Fair use: 500 runs/month
  per seat before we reach out. Above that, upgrade to ENTERPRISE for
  unlimited + SLA."
- PlansPage MAX card tooltip mirrors the number.

### W5.8 — Shareable link on FREE tier
**Backend:** FREE tier currently can't share run reports. Flip the
`feature_flag:share.free-tier.enabled` to true + remove the tier gate
in `ValidationReportController.generateShareLink`.

### W5.9 — 14-day MAX trial on signup
**Schema (V129):** `app_user.trial_tier`, `trial_started_at`, `trial_ends_at`.

**Backend work:**
- `AuthService.register` / `findOrCreateFromOAuth` — set `trial_tier=MAX`
  + `trial_started_at=now()` + `trial_ends_at=now()+14d` on new signup
- `PeriodEndDowngradeScheduler` — adds a branch: when
  `app_user.trial_ends_at < now()` AND `trial_tier != null`, null out
  trial columns (user's real tier stays unchanged — the trial was
  additive, not a subscription)
- `TierEnforcementService.resolveEffectiveTier(user)` — returns
  `trial_tier` when trial window is active, else `user.tier`

**Frontend:** banner on RunsPage for the first 14 days — "Your MAX trial
ends in N days. Subscribe to keep it."

### W5.10 — "Invite 2 teammates to your free org"
**Schema (V129):** `app_user.can_invite_teammates BOOLEAN`.

**Backend work:**
- `POST /api/org/free-team` — creates a FREE-tier org with the user as
  ORG_ADMIN, flips `can_invite_teammates = true`
- `InvitationService.createInvitation` — on FREE-tier orgs, cap members
  at 3 (1 admin + 2 teammates) instead of 10 (PRO cap)

**Frontend:** CTA on RunsPage for solo FREE users — "Bring 2 teammates
for free."

### W5.11 — Celebrate + 1-click upgrade-whole-team-to-MAX
**Frontend:** when a run completes on a FREE-team org, the
`FirstRunCelebration` banner (Wave 2.2) gains a "Upgrade the whole
team to MAX" button that pre-fills the TEAM tier purchase flow with
the current org's seat count.

## What's committed now

- V129 schema (startup program + 14-day trial + teammate flag)
- This plan doc

## What's deferred to the Wave 5 window

- `StartupProgramService` + controller + admin queue page
- `/startup-program` frontend application page
- `TierEnforcementService.resolveEffectiveTier` trial branch
- `PeriodEndDowngradeScheduler` trial expiry branch
- `/api/org/free-team` endpoint
- Sample spec library expansion (5 more templates)
- `useCriticalPath()` hook + Sidebar "Advanced" toggle
- `GET /api/billing/byok-usage` endpoint + BillingPage meter
- FREE-tier share-link flag flip
- Celebrate screen MAX-upgrade CTA
- Content docs (migration playbooks, fair-use, pricing-analysis
  update)

## Success criteria

- [ ] Startup can apply via `/startup-program`; admin approval grants
  50% off MAX for 12 months
- [ ] Enterprise Startup tier purchasable at $990/mo (10 seats × $99)
- [ ] First-login user sees 14-day MAX trial banner, can use MAX features
  for 14 days, then drops cleanly back to FREE
- [ ] FREE user can invite 2 teammates into a free-tier org
- [ ] FREE user can share a validation report via `/reports/shared/{token}`
- [ ] MAX user sees per-provider spend on BillingPage
- [ ] Progressive disclosure toggle-able; "Advanced" items accessible
  on-demand

## Non-goals

- SOC 2 Type I certification (external, Q3 2026)
- Customer logos on enterprise page (needs consent)
- Office hours calendar automation (manual for v1)
- Advanced / Critical-path UI default-on (wait for 30 days of
  opt-in data before flipping)
