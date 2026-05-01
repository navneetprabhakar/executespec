# Persona Test — Round 4 (post Google-style refactor)

**Date:** 2026-05-04
**Branch:** `chore/google-style-refactor` (post 0839e887 — CI lint wired)
**Scope:** validate that the codebase-wide style refactor (1ac68a2c, 1312 files, +140k -117k) didn't break any UI surface.
**Method:** puppeteer-core driving system Chrome (1440×900, dpr 1) + direct API probes. 110 screenshots under `persona-test-2026-05-04-r4-assets/`.

> The round-3 audit (now superseded) closed 7 credibility-class bugs. Round 4 is the **regression check** for the mechanical style refactor that landed on top of those fixes — making sure spotless + prettier + eslint didn't change semantics anywhere visible to users. **No new bugs found.** Every fix from r3 still holds; UI is byte-equivalent except where a token / copy change deliberately landed.

---

## §0 — What changed since r3

| Surface | Pre-r4 (r3 commit) | Post-r4 (this branch) | User-visible delta |
|---|---|---|---|
| Java code | mixed style | google-java-format'd | none — pure formatting |
| TS/TSX/CSS | mixed style | prettier'd + eslint --fix'd | none — pure formatting |
| `pom.xml` | no formatter | spotless-maven-plugin wired into `verify` | future PRs format-checked at CI |
| `frontend/package.json` | no lint deps | prettier + eslint + plugins added | new `format` / `lint` scripts |
| `.github/workflows/deploy.yml` | `mvn package` | `mvn verify` (picks up spotless) + frontend `format:check` + `lint` gates | deploys fail on style drift |
| `.github/workflows/lint.yml` | n/a | NEW — PR-time lint job, parallel Java + frontend | PR shows red on style violations |
| `docs/design/code-style.md` | n/a | NEW — documents 7 deviations | reference for reviewers |

Total commits on `chore/google-style-refactor` since `dev`:
```
0839e887 ci(lint): wire google-java-format + prettier + eslint into deploy + PR gates
1ac68a2c chore(style): apply Google Java + TypeScript style guide across the codebase
```

---

## §1 — Live API verification (F-14 still holds)

`me.tier` (legacy `/api/auth/me`) vs `entitlements.tierKey` (`/api/v3/entitlements`) probed against the freshly-restarted post-refactor JVM:

| persona | `me.tier` | `entitlements.tierKey` | `runLimitMonthly` | Match? |
|---|---|---|---|---|
| admin   | ENTERPRISE| ENTERPRISE | -1 | ✅ |
| ent     | ENTERPRISE| ENTERPRISE | -1 | ✅ |
| team    | TEAM      | TEAM       | -1 | ✅ |
| pro     | PRO       | PRO        | 20 | ✅ |
| free    | FREE      | FREE       | 10 | ✅ |

All five personas resolve to the right tier end-to-end. The F-14 fix (`EntitlementResolver` higher-rank-wins) survived spotless reformatting unchanged, as expected.

---

## §2 — Live CSS contrast probe (F-15/16/17 still holds)

Live computed-style probe against the dark theme (gray-100 = `#14171C`):

| Token | Value | Pair | Ratio | AA-Body 4.5 | AA-Non-text 3.0 |
|---|---|---|---|---|---|
| `--border-default` | `#5e6771` | on graphite-2 | **3.13** | n/a | ✅ |
| `--border-hover` | `#7B8593` | on graphite-2 | **4.81** | n/a | ✅ |
| `--status-executing` | `#5b89e0` | on graphite-2 | **5.22** | ✅ | ✅ |
| `--accent-blue-text` | `#7aa5f5` | on graphite-2 | **7.29** | ✅ | ✅ |
| `--accent-blue` (button) | `#3B6FCC` | on white | **4.85** | ✅ | ✅ |

All four post-r3-fix targets continue to land at their AA thresholds. Prettier touched these CSS files but didn't change any hex values.

---

## §3 — Per-persona surface walkthrough

### 3.1 Super-admin

#### Admin console — Users tab

![](persona-test-2026-05-04-r4-assets/admin-console.png)

- 6 quick-action buttons: Analytics · Pricing · Feature Flags · Tier lifecycle · Verify audit chain · Halt all runs (red).
- 9 tabs: Users (24) · Organisations (19) · Tier Config · Escalations · Top-Up Pkgs (5) · Usage Reports · Payment Audit · Webhooks.
- Tier dropdown live dump confirms the 4-tier post-V165 ladder: `['Free', 'Team', 'Pro (grandfathered)', 'Enterprise']`.

#### Tier lifecycle (`/admin/tiers`)

![](persona-test-2026-05-04-r4-assets/admin-tiers.png)

- 4-tier ladder: **Free LISTED · Team LISTED · Pro HIDDEN · Enterprise LISTED**.
- "Changed" column is date-only with dashed-underline indicating hover-availability — migration-version churn no longer leaks (TL fix from r3 holds).
- Card / table border contrast visibly tighter — F-15 token (`#5e6771`) holding at 3.13 vs graphite-2.

#### Halt-all-runs typed-confirm modal

![](persona-test-2026-05-04-r4-assets/admin-halt-modal.png)

Headline + warning copy + reason input + "Type HALT to confirm" gate. Submit stays disabled until both pass. Border contrast on the modal (which sits on graphite-3) is visibly stronger.

#### Other admin tabs (verified by click + capture)

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

#### Side admin pages (full captures)

- Pricing manager — `admin-pricing.png`
- Analytics — `admin-analytics.png`
- Feature flags — `admin-feature-flags.png`
- Audit log (`/audit`) — `admin-audit.png`
- Exec summary (`/exec`) — `admin-exec-summary.png`

### 3.2 ENTERPRISE persona

#### V3 Billing — F-14 holds

![](persona-test-2026-05-04-r4-assets/ent-v3-billing.png)

- "**You're on Enterprise. Unlimited runs · 0 this period**" — tier banner correct.
- Custom card at the bottom with Schedule a 30-min demo + Trust + compliance.
- **Footer audit row: `Tier: ENTERPRISE · run limit: unlimited`** (was `FREE / 10` pre-F-14).

#### V3 Team — SSO panel ENTERPRISE-only

![](persona-test-2026-05-04-r4-assets/ent-v3-team.png)

SeatsCard "1 member · unlimited seats" + Members list + Invite form + Single-sign-on section with Talk to sales / Open admin. SSO panel correctly hidden on TEAM.

#### V3 Insights (`/v3/dashboard` redirected to `/insights`)

![](persona-test-2026-05-04-r4-assets/ent-v3-dashboard.png)

5 KPI tiles + 30d/90d/365d period selector + "✓ All clear in the last 30 days." empty state.

### 3.3 TEAM persona

#### V3 Billing — F-14 + F-21 hold

![](persona-test-2026-05-04-r4-assets/team-v3-billing.png)

- "**You're on Team. Unlimited runs · 0 this period**" — tier banner correct.
- TEAM card has "Current" badge ✅; Add seats → CTA on TEAM.
- TEAM pricing: $39 / seat / month ✅ (post-V165).
- **Footer: `Tier: TEAM · run limit: unlimited`**.

#### V3 Team — TEAM has no SSO row

![](persona-test-2026-05-04-r4-assets/team-v3-team.png)

SeatsCard "1 of 1 seat used" + Manage seats CTA + Members list + Invite form. SSO section correctly hidden.

### 3.4 PRO persona (grandfathered)

#### V3 Billing — F-21 holds

![](persona-test-2026-05-04-r4-assets/pro-v3-billing.png)

- "**You're on Pro. 20 of 20 runs left this month**" — tier banner shows the actual 20-cap (was "Unlimited runs · 0" pre-F-21).
- Top-bar usage badge: **0/20 runs**.
- Soft budget: $40.00 (correctly derived from 20 × $2 baseline).
- PRO card highlighted "Current"; copy: "Existing subscribers only — new buyers, choose Team".
- Manage subscription → CTA.
- **Footer: `Tier: PRO · run limit: 20`**.

### 3.5 FREE persona

#### V3 Billing

![](persona-test-2026-05-04-r4-assets/free-v3-billing.png)

"You're on Free. 10 of 10 runs left this month" + full progress bar + Upgrade → CTA. FREE card has "Current" badge.

#### V2 sidebar empty workspace — D3 reframe holds

![](persona-test-2026-05-04-r4-assets/free-projects.png)

- **H1: "What do you want to build, Reval?"** — outcome-led question (D3 fix).
- **Body: "Describe what you want to build and we'll ship a draft PR you can actually merge. Your free plan covers 10 PRs / month — about 10 small features or 6 large refactors."** — tier-aware.
- Primary CTA: "Create my first project →"
- **Secondary: "Already have a GitHub repo? Attach it first →"** (no more "long way" framing).
- Top-right: 0/10 runs progress bar visible.

#### Plans page (V2 — public)

![](persona-test-2026-05-04-r4-assets/free-plans-detailed.png)

USD default + grandfathered PRO badge + "for new buyers, choose Team" copy.

### 3.6 Login hero (logged-out) — F-16 link contrast holds

![](persona-test-2026-05-04-r4-assets/login-hero.png)

- H1: "Ship AI-written PRs you can actually merge." with "PRs" in pink-amber gradient.
- 3-bullet feature list with brand-blue icon chips.
- **"Forgot?" + "Sign up free" links use `--accent-blue-text` (#7aa5f5, 7.29 contrast)** — visibly more legible than the previous brand-blue.

### 3.7 Mobile breakpoint + light theme

| Mobile — V3 dashboard | Mobile — projects |
|------|------|
| ![](persona-test-2026-05-04-r4-assets/ent-v3-dashboard-mobile.png) | ![](persona-test-2026-05-04-r4-assets/ent-projects-mobile.png) |

| Light theme — projects | Light theme — V3 dashboard |
|------|------|
| ![](persona-test-2026-05-04-r4-assets/admin-projects-light.png) | ![](persona-test-2026-05-04-r4-assets/admin-v3-dashboard-light.png) |

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

R8 (personal-org permission gap) holds — paid-tier ORG_ADMINs on personal orgs receive the org-management bundle; FREE/PRO correctly denied.

---

## §5 — Refactor regression check summary

| Class | Tested | Result |
|---|---|---|
| Java tests | `mvn test` (unit suite) | **839/839 green** — zero new failures |
| Java compile | `mvn compile -q` | clean |
| Java format | `mvn spotless:check` | clean |
| Frontend build | `npm run build` (`tsc && vite build`) | clean |
| Frontend format | `npm run format:check` | clean |
| Frontend lint | `npm run lint` | **0 errors / 316 warnings** (pre-refactor backlog) |
| F-14 (5 personas) | live API probe | match — all 5 personas |
| F-15/16/17 (token contrast) | live CSS probe | all 5 pairs at AA |
| F-21 (PRO 20-cap render) | screenshot | "20 of 20 runs left this month" rendering |
| D3 (empty-state copy) | screenshot | outcome-led + tier-aware |
| Tier-lifecycle reason hover | screenshot | date-only column |

**Net:** zero regressions introduced by the refactor. Everything that worked in r3 continues to work; tooling enforcement is now in place to keep it that way.

---

## §6 — CI lint enforcement (new)

The branch ships two enforcement points so the style baseline can't drift:

### 6.1 Deploy gate (`.github/workflows/deploy.yml`)

`mvn package` was bumped to `mvn verify`, which fires the `spotless-maven-plugin` binding (`phase=verify`). Frontend now runs `npm run format:check` + `npm run lint` between `npm ci` and `npm run build`. Any style drift fails the deploy job before the JAR uploads to GCS.

### 6.2 PR gate (`.github/workflows/lint.yml` — NEW)

Two parallel jobs on every PR to dev/main:
- **Java**: `mvn -B spotless:check` — typically <30s after the first PR primes the Maven cache.
- **Frontend**: `npm ci` + `npm run format:check` + `npm run lint`.

Failure messages point at the local fix command and reference `docs/design/code-style.md` for the deviations doc. The PR gate is the cheap-feedback layer; the deploy gate is the last line of defense.

---

## §7 — Documented deviations (`docs/design/code-style.md`)

Seven rules in the canonical guides are deliberately not enforced, each with a stated rationale + future-cleanup notes:

| ID | Surface | Rule | Why deferred |
|---|---|---|---|
| D-J1 | Java imports | wildcard imports retained | 217 files; OpenRewrite recipe available for follow-up |
| D-J2 | Java javadoc | top-level + REST controllers only | trivial getters/setters skipped |
| D-J3 | Java visibility | `// VisibleForTesting` comment vs annotation | dependency-light pattern |
| D-T1 | TS exports | default exports OK for `*.tsx` | React community convention |
| D-T2 | TS `any` | warn instead of error | existing `any` cleanup is per-callsite work |
| D-T3 | TS interface vs type | both accepted | both common in modern TS |
| D-T4 | TS jsdoc | public utilities / hooks / cross-module types only | signal-to-noise |

---

## §8 — Asset inventory

`docs/audits/persona-test-2026-05-04-r4-assets/` contains 110 PNG screenshots. Naming: `<persona>-<surface>[-<state>].png`. All captures are full-page at 1440×900 dpr 1, two captures at mobile (375×812 dpr 2), two captures in light theme.

Older audit reports + assets (rounds 1+2+3) deleted per the user's standing instruction; this round 4 supersedes all prior persona-test artefacts.
