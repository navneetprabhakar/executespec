# Persona Test — Round 3 (regenerated, post-fix)

**Date:** 2026-05-01
**Branch:** `audit/v2-5-11-ux-product` (post round-3 fix commit)
**Stack:** restarted local backend (Spring Boot 3.3, profile `local`) + Vite dev (5173) + Postgres 18
**Method:** puppeteer-core driving system Chrome (1440×900, dpr 1) + direct API probes against `localhost:8080`. 110 screenshots under `persona-test-2026-05-01-r3-assets/`.

> This is the **post-fix** revalidation. The original round-3 audit report (now superseded) flagged five items: F-14 (V3 entitlement tier-leak), F-15/F-16/F-17 (WCAG-AA contrast), D3 (empty-state copy), and tier-lifecycle audit-log churn. All five shipped on the audit branch in the commit immediately preceding this report. F-14 also exposed F-21 (PRO V3 billing rendering "Unlimited" despite a 20/month cap), which was patched in the same batch. This document is the screenshot-driven proof that every item is gone.

---

## Personas

| Tag    | Account                              | Tier        | Org role  | View  |
|--------|--------------------------------------|-------------|-----------|-------|
| admin  | `admin@executespec.ai`               | ENTERPRISE  | super     | pro   |
| ent    | `revalidate-ent@test.local`          | ENTERPRISE  | ORG_ADMIN | vibe  |
| team   | `revalidate-team@test.local`         | TEAM        | ORG_ADMIN | vibe  |
| pro    | `revalidate-pro@test.local`          | PRO (HIDDEN)| ORG_ADMIN | vibe  |
| free   | `revalidate-free@test.local`         | FREE        | ORG_ADMIN | vibe  |

All five authenticate cleanly against the freshly-restarted JVM.

---

## §0 — Score card (after fixes)

| ID | Item                                                          | Pre-fix | Post-fix | Source |
|----|---------------------------------------------------------------|---------|----------|--------|
| F-14 | `/api/v3/entitlements.tierKey` for paid users                | FREE for ENT/TEAM/PRO | matches `me.tier` for all 5 personas | API probe + V3 billing footer screenshots |
| F-15 | `--border-default` contrast on graphite-2                    | 1.59 (FAIL SC 1.4.11) | **3.13** (PASS) | live computed-style probe |
| F-16 | Body-text-on-dark for signal-blue (`--accent-blue-text`)     | n/a — would have used `--accent-blue` at 3.71 | **7.29** (AA-body PASS) | live computed-style probe |
| F-17 | `--status-executing` contrast on graphite-2                  | 4.19 (AA-large only) | **5.22** (AA-body PASS) | live computed-style probe |
| F-21 | V3Billing run-usage line for paid tiers                       | "Unlimited runs" hardcoded for any non-FREE tier | reads `runLimitMonthly` per tier — PRO renders 20 of 20, TEAM/ENT unlimited | screenshot |
| D3 | V3EmptyProject copy                                            | "Welcome, Reval." + body-noun-only + "the long way" link | "What do you want to build, Reval?" + tier-aware body + "Already have a GitHub repo? Attach it first →" | screenshot |
| TL | Tier lifecycle audit-log leak                                  | "TEAM tier reinstated (V144) — pro..." inline truncate | date-only column with dashed-underline hover for full reason | screenshot |
| Process | Token-contrast regression coverage                       | none — process gap | `frontend/src/design-system/contrast.{ts,test.ts}` (dormant vitest) + `.pa11yci.json` + `A11Y.md` | files |

**Net:** 7 confirmed-fixed, 1 process-gap closure, 0 net-new bugs.

---

## §1 — F-14 verification (live API)

`me.tier` (legacy) vs `entitlements.tierKey` (v3) probed against the freshly-restarted JVM:

| persona | `me.tier` | `entitlements.tierKey` | `runLimitMonthly` | Match? |
|---------|-----------|------------------------|-------------------|--------|
| admin   | ENTERPRISE| ENTERPRISE             | -1                | ✅     |
| ent     | ENTERPRISE| ENTERPRISE             | -1                | ✅     |
| team    | TEAM      | TEAM                   | -1                | ✅     |
| pro     | PRO       | PRO                    | 20                | ✅     |
| free    | FREE      | FREE                   | 10                | ✅     |

The "every paid user falls back to FREE" class is gone end-to-end. The frontend `canonicalTier(meTier, ent.tierKey)` patch is now redundant defence-in-depth — the backend resolver picks the higher rank itself, so `me.tier` and `ent.tierKey` agree at the source.

---

## §2 — F-15/16/17 verification (live computed-style probe)

Sampled against the live dark theme (gray-100 = `#14171C`).

| Token | Pair                              | Pre-fix | Post-fix | AA-Body (4.5)? | AA-Non-text (3.0)? |
|-------|-----------------------------------|---------|----------|----------------|---------------------|
| `--border-default` (`#5E6771`)    | on graphite-2 | 1.59 | **3.13** | n/a | ✅ |
| `--border-hover` (`#7B8593`)      | on graphite-2 | 2.41 | **4.81** | n/a | ✅ + AA-large |
| `--status-executing` (`#5B89E0`)  | on graphite-2 | 4.19 | **5.22** | ✅ | ✅ |
| `--accent-blue-text` (`#7AA5F5`)  | on graphite-2 | n/a (was --accent-blue at 3.71) | **7.29** | ✅ | ✅ |
| `--accent-blue` (`#3B6FCC`)       | on white      | 4.85 | 4.85 (unchanged — button surface) | ✅ | ✅ |
| `--text-primary` / on graphite-2  | n/a | 15.46 | ✅ | ✅ |
| `--text-muted` / on graphite-2    | n/a | 5.87  | ✅ | ✅ |
| `--status-success` / on graphite-2| n/a | 7.08  | ✅ | ✅ |
| `--c-pop-orange` (#EC4899)        | on graphite-2 | 5.09 | 5.09 (unchanged) | ✅ | ✅ |

Every dark-theme pair the round-3 audit flagged is now AA-passing. The math is now anchored against the live surface (graphite-2), not white — closing the process gap that produced the original misses.

---

## §3 — Surface-by-surface walkthrough (post-fix)

### 3.1 Super-admin

#### Admin console — Users tab

![](persona-test-2026-05-01-r3-assets/admin-console.png)

- 6 quick-action buttons: Analytics · Pricing · Feature Flags · Tier lifecycle · Verify audit chain · Halt all runs
- 9 tabs: Users (24) · Organisations (19) · Tier Config · Escalations · Top-Up Pkgs (5) · Usage Reports · Payment Audit · Webhooks
- **Tier dropdown dump (live):** `['Free', 'Team', 'Pro (grandfathered)', 'Enterprise']` — 4-tier post-V165 ladder, PRO copy carries the grandfathered hint inline.

#### Tier lifecycle (`/admin/tiers`) — TL fix landed

![](persona-test-2026-05-01-r3-assets/admin-tiers.png)

- 4-tier ladder: **Free LISTED · Team LISTED · Pro HIDDEN · Enterprise LISTED**.
- **"Changed" column is date-only.** Dashed-underline indicates hover-availability for the full reason. The previous inline truncation ("TEAM tier reinstated (V144) — pro...") that leaked migration-version churn into screenshots is gone.
- PRO row exposes List · Sunset; other rows show Hide.
- **Card / table border contrast is visibly tighter** (F-15 — graphite-7 instead of graphite-5). Compare with the pre-fix where dividers were nearly invisible.

#### Halt-all-runs typed-confirm modal

![](persona-test-2026-05-01-r3-assets/admin-halt-modal.png)

Headline + warning copy + optional reason input + "Type HALT to confirm" keyword gate. Submit stays disabled until both pass. Border contrast on the modal (which sits on graphite-3) is visibly stronger.

#### Other admin tabs

| Tab           | Screenshot                                  |
|---------------|---------------------------------------------|
| Organisations | `admin-tab-orgs.png`                        |
| Tier Config   | `admin-tab-tier-config.png`                 |
| Escalations   | `admin-tab-escalations.png`                 |
| Top-Up Pkgs   | `admin-tab-topup-pkgs.png`                  |
| Usage Reports | `admin-tab-usage-reports.png`               |
| Payment Audit | `admin-tab-payment-audit.png`               |
| Webhooks      | `admin-tab-webhooks.png`                    |
| Users         | `admin-tab-users.png`                       |

#### Side admin pages

- Pricing manager (`/admin/pricing`) — `admin-pricing.png`
- Analytics (`/admin/analytics`) — `admin-analytics.png`
- Feature flags (`/admin/feature-flags`) — `admin-feature-flags.png`
- Audit log (`/audit`) — `admin-audit.png`
- Exec summary (`/exec`) — `admin-exec-summary.png`

### 3.2 ENTERPRISE persona

#### V3 Billing — F-14 fix landed

![](persona-test-2026-05-01-r3-assets/ent-v3-billing.png)

- "**You're on Enterprise. Unlimited runs · 0 this period**" — tier banner ✅
- TEAM card has no "Current" badge; ENTERPRISE custom card sits at the bottom with Schedule a 30-min demo + Trust + compliance.
- **Footer audit row: `Tier: ENTERPRISE · run limit: unlimited`** (was `Tier: FREE · run limit: 10` pre-fix).

#### V3 Team — SSO panel (ENTERPRISE-only)

![](persona-test-2026-05-01-r3-assets/ent-v3-team.png)

SeatsCard "1 member · unlimited seats" + Members list + Invite form + Single-sign-on section with Talk to sales / Open admin buttons. SSO panel correctly hidden on TEAM.

#### V3 Insights (`/v3/dashboard` → redirected `/insights`)

![](persona-test-2026-05-01-r3-assets/ent-v3-dashboard.png)

5 KPI tiles (Runs · Spend · Success rate · Active projects · Needs attention) + 30d/90d/365d period selector + "✓ All clear in the last 30 days." Empty state passes the "is anything broken?" sniff test.

### 3.3 TEAM persona

#### V3 Billing — F-14 + F-21 verified

![](persona-test-2026-05-01-r3-assets/team-v3-billing.png)

- "**You're on Team. Unlimited runs · 0 this period**" — tier banner ✅
- TEAM card has "Current" badge ✅; Add seats → CTA on TEAM
- **Footer: `Tier: TEAM · run limit: unlimited`** (was `Tier: FREE · run limit: 10`)
- TEAM pricing: $39 / seat / month ✅ (post-V165)

#### V3 Team — TEAM has no SSO row

![](persona-test-2026-05-01-r3-assets/team-v3-team.png)

SeatsCard "1 of 1 seat used" + Manage seats CTA + Members list + Invite form. SSO section correctly hidden.

### 3.4 PRO persona (grandfathered)

#### V3 Billing — F-21 verified

![](persona-test-2026-05-01-r3-assets/pro-v3-billing.png)

- "**You're on Pro. 20 of 20 runs left this month**" — tier banner now reads the actual 20-cap (was "Unlimited runs · 0 this period" pre-F-21)
- Top-bar usage badge: **0/20 runs**
- **Soft budget: $40.00** (correctly derived from 20 runs × $2 baseline)
- PRO card highlighted "Current"; copy: "Existing subscribers only — new buyers, choose Team"
- Manage subscription → CTA
- **Footer: `Tier: PRO · run limit: 20`** (was `Tier: FREE · run limit: 10`)

### 3.5 FREE persona

#### V3 Billing — unchanged shape, but now AA-correct borders

![](persona-test-2026-05-01-r3-assets/free-v3-billing.png)

"You're on Free. 10 of 10 runs left this month" + full progress bar + Upgrade → CTA. FREE card has "Current" badge. Footer: `Tier: FREE · run limit: 10`.

#### V2 sidebar empty workspace — D3 reframe landed

![](persona-test-2026-05-01-r3-assets/free-projects.png)

- **H1: "What do you want to build, Reval?"** — outcome-led question (was "Welcome, Reval.")
- **Body: "Describe what you want to build and we'll ship a draft PR you can actually merge. Your free plan covers 10 PRs / month — about 10 small features or 6 large refactors."** — tier-aware, surfaces the human meaning of 10 runs
- Primary CTA: "Create my first project →"
- **Secondary: "Already have a GitHub repo? Attach it first →"** (was "Set up a project the long way →" — "long way" framing removed)
- Top-right: 0/10 runs progress bar visible

#### Plans page (V2 — public)

![](persona-test-2026-05-01-r3-assets/free-plans-detailed.png)

USD default + grandfathered PRO badge + "for new buyers, choose Team" copy.

### 3.6 Login hero (logged-out) — F-16 link contrast landed

![](persona-test-2026-05-01-r3-assets/login-hero.png)

- H1: **"Ship AI-written PRs you can actually merge."** with "PRs" in pink-amber gradient
- 3-bullet feature list with brand-blue icon chips
- Right pane: Sign in form with Google / GitHub OAuth above email/password
- **"Forgot?" + "Sign up free" links now use `--accent-blue-text` (#7AA5F5, 7.29 contrast)** — visibly more legible than the previous brand-blue (#3B6FCC, 3.71).

### 3.7 Mobile breakpoint + light theme

| Mobile — V3 dashboard | Mobile — projects |
|------|------|
| ![](persona-test-2026-05-01-r3-assets/ent-v3-dashboard-mobile.png) | ![](persona-test-2026-05-01-r3-assets/ent-projects-mobile.png) |

| Light theme — projects | Light theme — V3 dashboard |
|------|------|
| ![](persona-test-2026-05-01-r3-assets/admin-projects-light.png) | ![](persona-test-2026-05-01-r3-assets/admin-v3-dashboard-light.png) |

---

## §4 — Per-tier permission matrix (legacy `/api/auth/me/permissions`)

| Permission                | admin | ent  | team | pro   | free  |
|---------------------------|-------|------|------|-------|-------|
| `canManageAiKeys`         | ✓     | ✓    | ✓    | ✗     | ✗     |
| `canViewPricing`          | ✓     | ✓    | ✓    | ✗     | ✗     |
| `canManageIntegrations`   | ✓     | ✓    | ✓    | ✗     | ✗     |
| `canViewAudit`            | ✓     | ✓    | ✓    | ✗     | ✗     |
| `canViewLiveLog`          | ✓     | ✓    | ✓    | ✓     | ✓     |
| `canInviteMembers`        | ✓     | ✓    | ✓    | ✗     | ✗     |
| `canViewOrgMembers`       | ✓     | ✓    | ✓    | ✗     | ✗     |
| `canViewCostBreakdown`    | ✓     | ✓    | ✓    | ✗     | ✗     |
| `canManageBillingProfile` | ✓     | ✓    | ✓    | ✓     | ✗     |
| `canDeploy`               | ✓     | ✓    | ✓    | ✓     | ✗     |
| `monthlyRunLimit`         | -1    | -1   | -1   | 20    | 10    |
| `maxDeployments`          | -1    | -1   | -1   | 5     | 0     |

R8 (personal-org permission gap) holds across the matrix — paid-tier ORG_ADMINs on personal orgs get the org-management bundle, FREE/PRO stay denied.

---

## §5 — Process gap closure

The audit's "single most ROI-positive piece of CI tooling not yet in place" line landed as three artefacts:

- **`frontend/src/design-system/contrast.ts`** — pure-function WCAG ratio helpers (parseHex, luminance, contrastRatio, WCAG threshold constants). Zero deps, works in any test runner.
- **`frontend/src/design-system/contrast.test.ts`** — vitest-shape regression test pinning every (token, surface) pair this batch fixed. Includes 3 counter-examples that **must still fail** (pre-fix --border-default, pre-fix --status-executing, signal-blue-as-text on dark) — so we trust the math. Dormant until vitest is wired (same pattern as the existing `tierDisplay.test.ts`).
- **`frontend/.pa11yci.json`** — pa11y-ci config for the public, unauthenticated surface (login, register, forgot-password, plans, pricing, terms, privacy, dpa) using both axe-core and HTML CodeSniffer runners. Auth-gated routes deferred to a follow-up cookie-priming driver.
- **`frontend/A11Y.md`** — how to enable both checks locally + suggested CI rollout order.

Once vitest is installed, the contrast test alone will catch every regression in this batch's class (math against wrong surface, untracked palette drift) on `npm run build`.

---

## §6 — What's still on the radar (informational, not blocked)

These weren't part of the round-3 fix scope and were flagged in the original report or the user's response. None block this commit.

- **Sidebar still says "Runs"** — the Phase 1.1 "Missions" rename was reverted per R-191. Sidebar comment at line 103 of `Sidebar.tsx` documents the decision. Not a fix unless that decision flips.
- **`/v3/dashboard` route → page titled "Insights"** — `V3LegacyRedirect` strips the `/v3` prefix, so `/v3/dashboard` → `/dashboard` → `/insights` is a working two-hop. Page is correctly named "Insights".
- **Two coexisting shells (V2 sidebar + V3 top-bar)** — mid-refactor structural state. Out of scope for a token / resolver / copy batch.
- **Pricing-tier nomenclature thrash** — historical V141/V144/V165 churn is captured in the (now-suppressed) tier-lifecycle audit log. The user's framing was "either suppress legacy entries OR commit to a 90-day pricing freeze" — this batch took the suppression path. The freeze is a policy decision, not code.
- **Other ~50 `text-accent-blue` consumers on dark surfaces** — chip patterns where bg / border / text all share the colour, so the visual context softens the AA-body miss. Migration to `text-accent-blue-text` deferred to a follow-up sweep; the contrast.test.ts harness will catch any net-new regression.

---

## §7 — Recommended next batch (post-merge)

1. **Wire vitest** (5-min lift) — installs the dormant `contrast.test.ts` + `tierDisplay.test.ts`. Most-ROI item open.
2. **Pa11y-ci in CI** — separate workflow that boots Vite + crawls the public surface. Catches contrast / link-name / heading-order at PR time.
3. **Sweep the remaining ~50 `text-accent-blue` consumers** to `text-accent-blue-text` on dark surfaces. Largest lever for closing the long tail of AA-body misses on dark theme.
4. **Authenticated pa11y-ci pass** — cookie-priming driver before the crawler runs. Wave 2 of the a11y plan.
5. **Optional:** PRO V3 billing top-bar usage badge — currently shows "0/20 runs". Verify the badge color flips at 80% / 90% (it should, but didn't get an explicit test in this batch since the test fixture has 0 usage).

---

## §8 — Asset inventory

`docs/audits/persona-test-2026-05-01-r3-assets/` contains 110 PNG screenshots. Naming: `<persona>-<surface>[-<state>].png`. All captures are full-page at 1440×900 dpr 1, two captures at mobile (375×812 dpr 2), two captures in light theme.
