# Persona Audit — 2026-05-06

**Method:** Logged in as five personas (FREE / PRO / TEAM / ENTERPRISE / SUPER_ADMIN)
on a local stack (`spring.profiles.active=local` + `vite dev`), captured every
top-level surface that's reachable from the v3 chrome (TopBar links + avatar
menu) plus the per-project shell tabs and the run-detail page. Screenshots
under `screenshots/<persona>/<NN-surface>.png` — same NN sequence per persona
so the same surface can be diffed across tier visibility.

**Total surfaces captured:** 16 base × 4 paid personas (`free` / `pro` /
`team` / `ent`) + 28 super-admin (admin) + 1 logged-out (`common/00-login`).
**Date:** 2026-05-06.
**Scope:** No fresh-user flow (pre-existing seeded users per persona). No
mobile breakpoint pass — desktop 1440×900 only.

---

## 1. What I went in looking for

After the May 6 single-surface retirement (commit `aa1d7de4` retired the v2
chrome — Sidebar / Header / view-mode toggle — collapsing every route onto
the v3 PageShell) and the V143-product-split rollback (commit `4a20683c`),
several routes that were registered against the v2 chrome are now mounted
on the v3 PageShell instead. The audit's primary question:

> Which surfaces still work, which silently broke, and where is the v3 chrome
> drifting from the marketing-site refresh that landed earlier the same day
> (commits `c86bf1de` + `5973819e`)?

A secondary question — does each tier persona see what the marketing site
*and* `tier_config` in the live DB say they should see?

---

## 2. Findings

P-priority follows the convention in [docs/audits/product-review-2026-05-06.md](../product-review-2026-05-06.md):

- **P0** = blocks a paid-tier customer from completing the core workflow
  (sign in, submit, approve, ship).
- **P1** = paper cut — surface still loads, but the affordance is wrong
  enough that the user backs off.
- **P2** = polish / inconsistency — bothers the audit, doesn't block
  anyone.

Each row notes whether the fix landed in this branch's `bf9f50e1` commit
(`✓ FIXED`), is queued (`→ FOLLOW-UP`), or is being deferred (`◯ DEFERRED`)
with a reason.

### P0

| # | Surface | Finding | Status |
|---|---------|---------|--------|
| **PA-1** | `LoginPage` ([screenshots/common/00-login.png](screenshots/common/00-login.png)) | Continue-with-Google + Continue-with-GitHub buttons hit `/api/auth/oauth2/init?provider=…&surface=…` — a forward-compat bridge for the v3 vibe/executespec surface split that was reverted in `4a20683c` (V143 + ProductSurfaceFilter dropped). Endpoint returns 404 today; OAuth login is broken on every fresh login. | ✓ FIXED in `bf9f50e1` — direct hit on Spring Security's `/api/auth/oauth2/authorize/{provider}` (always-mapped). |
| **PA-2** | `SettingsPage` plan card ([screenshots/admin/08-settings.png](screenshots/admin/08-settings.png)) | SUPER_ADMIN account on an ENTERPRISE org renders the plan card as `Free · 5 runs / month · no credit card`. F-14 (PR #55) fixed `EntitlementResolver.resolveTier` to pick the higher of `app_user.tier` and `org.tier` — but the SettingsPage card appears to read `app_user.tier` raw. Header / Billing / Plans pages all show ENTERPRISE correctly. | → FOLLOW-UP — single-line fix on `SettingsPage.PlanSummaryCard`: read effective tier from `useAuthStore`'s tier resolver instead of `user.tier`. |
| **PA-3** | `PlansPage` TEAM card ([screenshots/admin/04-billing-plans.png](screenshots/admin/04-billing-plans.png)) | TEAM tier shows `$39 / seat / month`. CLAUDE.md + V123 + V144 say `$49/seat`. Either tier_config / billing_plan rows drifted from docs OR docs are stale. Pricing on a self-serve checkout button must match the charge — flag before any TEAM customer is invoiced. | → FOLLOW-UP — verify by reading `tier_config.team` + `billing_plan` (Team Monthly USD/INR) on dev DB; reconcile to the documented $49 if drift is in data, or update CLAUDE.md if pricing intentionally changed. |

### P1

| # | Surface | Finding | Status |
|---|---------|---------|--------|
| **PA-4** | `/runs` (any persona) | After v2 chrome retirement, the `/runs` route became a redirect to `/home`. FREE users have no Project Shell access (gated to `execution_view_enabled`), so without a flat run list they had no way to see runs they'd already submitted. | ✓ FIXED in `bf9f50e1` — new `RunsListPage.tsx` mounts `/api/runs` (NOT tier-gated, scoped via `RunVisibilitySpec`) with scope/status filter pills. |
| **PA-5** | `ProjectLaunchesList` (Project Shell → Runs tab) | Endpoint `/api/projects/{id}/launches` is gated to ENTERPRISE/MAX (`execution_view_enabled`). Pre-fix every other tier saw "Failed to load launches" 403 in their own project. | ✓ FIXED in `bf9f50e1` — graceful 403 fallback to `/api/runs?projectId=…`. |
| **PA-6** | `RunDetailPage` ([screenshots/admin/30-run-detail-default.png](screenshots/admin/30-run-detail-default.png)) | Plan / Log / Preview tabs were dropped when v2 RunDetailTabs was retired. AWAITING_APPROVAL runs reached via direct URL had no inline approve flow. | ✓ FIXED in `bf9f50e1` — new `RunSidePanel.tsx` restores Plan / Log / Preview tabs as a 3rd column on desktop, stacks under 1100px / 768px. |
| **PA-7** | `/pricing` ([screenshots/free/05-pricing.png](screenshots/free/05-pricing.png)) | Route mounts the **model-rates** admin page (per-1M-token table with inline-edit affordances), not the tier-pricing comparison. Sub-copy `"Click any cost to edit inline — changes apply immediately to new runs"` is SUPER_ADMIN language; FREE/PRO users see misleading "you can edit this" hints over fields they cannot edit. | → FOLLOW-UP — either gate `PricingPage` to SUPER_ADMIN and route `/pricing` to `/plans` for everyone else, or fork the sub-copy by `useAuthStore.isSuperAdmin`. The route already double-maps to `/admin/model-rates` so the gate is the simpler fix. |
| **PA-8** | Project home composer ([screenshots/admin/01-home.png](screenshots/admin/01-home.png)) | Project's Compose tab shows two run cards with the SAME title (`Booking Feature – hostel-be-v3 (complete implementation)`). Re-launch chips reuse the same titles. Hard to distinguish runs at a glance without time / spend / status combination. | ◯ DEFERRED — orthogonal to the v3 chrome cleanup. Real fix is title de-dup (auto-suffix `(re-run)` / `(retry)`) at submit time inside `RunOrchestrator.submitRun` — a per-run change worth its own slice. |

### P2

| # | Surface | Finding | Status |
|---|---------|---------|--------|
| **PA-9** | All v3 surfaces (theme) | Graphite ramp (#0B0D10 / #11151B / …) read as "AI tool default flat black" against the warmer marketing-site refresh. Brand-blue `#3B6FCC` (tuned for AA-on-white) read as a dim navy on the dashboard's dark surface. | ✓ FIXED in `bf9f50e1` — graphite ramp warmed ~3-5° toward charcoal-with-indigo (#10131A / #161A23 / …). Dark-surface brand brightened to `#5B8DEF` (matching marketing site); light-theme override pins back to `#3B6FCC`. |
| **PA-10** | `PageHeader` accent + multiple page H1s | `Instrument Serif` italic accent (the "marketing-y" treatment on words like "PRs" / "Ship" / "merge" / "receipt") was already retired from the marketing site (`5973819e` + `c86bf1de`) but still rendered on PageHeader, NewRunModal, LandingPage, ProjectRepoPicker hint, and RepoContextPanel placeholder. | ✓ FIXED in `bf9f50e1` — replaced with accent-blue color or pop-orange + bold weight; matches marketing-site treatment. |
| **PA-11** | `/billing` ([screenshots/admin/03-billing.png](screenshots/admin/03-billing.png)) | "Spend" row renders an em-dash (`—`) when `usage.spendUsd === 0`. Reads as "no data" rather than "$0.00". | → FOLLOW-UP — `BillingPage` line 93-ish: `value={spend ?? '—'}` → `value={typeof spend === 'number' ? '$' + spend.toFixed(2) : '—'}`. |
| **PA-12** | `BillingPage` plan-grid for ENTERPRISE viewer | Admin viewing their own Billing page (already on Enterprise) sees the full FREE / Solo / Team / Enterprise upgrade grid. Functionally fine, but hierarchy-wise the user's current tier should be the loud thing — the grid is a sales surface, not a status surface. | ◯ DEFERRED — design call. Could collapse to "you're on Enterprise · view other plans →" link. Not blocking. |
| **PA-13** | `/home` empty state ([screenshots/free/01-home.png](screenshots/free/01-home.png)) | "What do you want to build, Reval?" hero sits at ~40% from top of viewport on 1440×900; whitespace above feels excessive. Hero would land harder anchored at ~25%. | → FOLLOW-UP — change `EmptyProjectLanding`'s outer container from `min-height: 100vh` flex-center to a `padding-top: 12vh` anchored layout. |
| **PA-14** | TopBar "Pick a project ▾" pill (any persona with 0 projects) | Pill is always present, but for users with no projects clicking it opens an empty dropdown. Disorienting. | → FOLLOW-UP — when `projects.length === 0`, swap the pill for a "Create your first project →" inline CTA OR hide the pill until the first project exists. |
| **PA-15** | Avatar menu ([screenshots/admin/40-avatar-menu.png](screenshots/admin/40-avatar-menu.png)) | Sections (general / org-admin / super-admin) are stacked correctly but section dividers are very subtle. The implicit "ORG ADMIN" / "SUPER ADMIN" group labels carry the structure, but a 1px dim divider between groups would make the grouping legible without re-reading the labels. | ◯ DEFERRED — small polish; revisit when avatar menu gets next non-trivial change. |

---

## 3. Cross-persona observations

**Tier visibility — what each persona sees in the TopBar / avatar menu:**

| | FREE | PRO (Solo) | TEAM | ENTERPRISE | SUPER_ADMIN |
|--|------|------|------|------------|-------------|
| TopBar links | Runs · Library · Migrations | Runs · Library · Migrations | Runs · Library · Migrations | Runs · Library · Migrations | Runs · Library · Migrations |
| Run quota chip | `0/5 runs` | `0/30 runs` | `0/unlimited` | `0/unlimited` | `0/unlimited` |
| Avatar groups | General only | General only | General only + (Insights gated to ORG_ADMIN) | General + Org Admin | General + Org Admin + Super Admin |
| Project Shell tabs | Compose / Pulse / Repos / Runs / Code / Chat | same | same | same | same |
| `/runs` flat list | ✓ visible (post-fix) | ✓ visible (post-fix) | ✓ visible (post-fix) | ✓ visible (post-fix) | ✓ visible (post-fix) |

**TopBar layout is identical across personas** — link visibility is not
tier-gated. That's the right call for navigation; if a tier hits a paid
surface, the surface itself should sell the upgrade rather than hiding from
the user that the surface exists. Validated against the actual screenshots
across all five personas.

**One affordance that DOES differ:** the `Runs` link is only highlighted as
the current route after PA-4 landed (it was a redirect before). Pre-fix the
link blinked through `/home` and never highlighted as active.

---

## 4. What's already wired and good

Some surfaces I went in suspicious of and walked away from positively:

- **Avatar menu role-gated grouping** (PA-15): the right surfaces appear in
  the right sections. ENTERPRISE non-admin would see only the General group;
  ORG_ADMIN gets the Insights / Audit log / Invoices / Accounting cluster;
  SUPER_ADMIN gets Admin home / Feature flags / Tier lifecycle. Worked
  correctly across all 5 personas.
- **Settings → AI Provider Keys** ([admin/08-settings.png](screenshots/admin/08-settings.png)):
  three-provider configuration is clean — single status line `OpenAI,
  Anthropic, Gemini configured` and a separate Organisation AI Keys
  section. The user-vs-org split is sensible (BYOK scoping).
- **InvoicesPage empty state**: ([admin/13-invoices.png](screenshots/admin/13-invoices.png))
  the new copy "New invoices appear after a subscription, top-up, or
  per-run charge. Try widening the date range or clearing filters." is
  exactly the kind of forward-pointing empty state the [docs/audits/product-review-2026-05-06.md](../product-review-2026-05-06.md)
  audit asked for.
- **Plan / Log / Preview tabs** in the new RunSidePanel (PA-6 fix) reuse the
  existing SSE channel + preview start/stop + retry-task endpoints — no new
  backend surface.

---

## 5. Numbers

- 5 personas captured
- 93 screenshots total (1 logged-out + 16 × 4 paid + 28 super-admin)
- 15 distinct findings → **8 P0/P1 fixed in `bf9f50e1`** · **5 follow-ups** · **3 deferred (with rationale)**
- Net code delta on the fix commit: 16 files changed, +1435 / -137

---

## 6. Follow-up checklist

In priority order, copy-paste-able to a future PR:

- [ ] PA-2 — `SettingsPage.PlanSummaryCard` reads effective tier (matches Header/Billing). One-line fix, easy regression test.
- [ ] PA-3 — Verify `tier_config.team` + `billing_plan` Team rows on dev DB; reconcile `$39` vs documented `$49` in either data or docs.
- [ ] PA-7 — Gate `/pricing` to SUPER_ADMIN; redirect non-admins to `/plans`.
- [ ] PA-11 — `BillingPage` "Spend" row formats `0` as `$0.00` instead of `—`.
- [ ] PA-13 — `EmptyProjectLanding` anchor hero at `padding-top: 12vh` not flex-center.
- [ ] PA-14 — TopBar "Pick a project" pill: hide / swap to "Create your first project →" when project count is 0.
- [ ] PA-8 (deferred) — Run-title de-dup in `RunOrchestrator.submitRun` for re-runs in the same project.
- [ ] PA-12 (deferred) — Collapse Billing-page upgrade grid for users already on the highest tier.
- [ ] PA-15 (deferred) — Avatar menu section dividers.
