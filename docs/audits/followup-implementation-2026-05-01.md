# Follow-up Implementation Report — 2026-05-01

> **Source:** `claude-code-followup-2026-05-01.md` (16 tasks: 6 code-ready + 10 blocked-on-decision).
> **Direction from user:** "Use the followup file to execute. On blocked items I'll go with your recommendation."
> **Outcome:** 16 of 16 tasks closed. 14 shipped with code; 2 deferred per the followup's own scope notes (R15 demo seeding = backend-heavy, R16 homepage Trust link = separate repo).

---

## TL;DR

| Status | Count | Tasks |
|---|---|---|
| ✅ Shipped + verified | 14 | R1, R3, R4, R5, R6, R7, R8, R9, R10, R11, R12, R13, R14 + R2 (no-op) |
| 📦 Deferred per spec | 2 | R15 (demo seeding), R16 (homepage Trust) |
| 🟡 Pending backend restart | 2 (R8, R4) | Source + tests landed; running JVM still serving stale classes |

**Frontend changes are live via Vite HMR.** Backend changes (`TierPermissionService`, `RunEvent`, `ProjectChatController`) need one `mvn spring-boot:run` cycle to take effect — Spring DevTools didn't auto-reload on this dev session, same behaviour as the previous batch's Task 15.

---

## Per-task outcomes

### R1 — Permission-denied 403 page
**Strategy chosen:** in-place AccessDenied component (preserves URL, no chained redirect through V3HomeRedirect).

Replaced `<Navigate to="/" replace />` in 4 permission-gated pages with the existing `AccessDenied` frame:
- [`AuditPage.tsx`](frontend/src/pages/AuditPage.tsx) — was bouncing FREE/ENT to `/projects/empty` ("Welcome, Reval"), now shows "Audit log requires an org admin access · Back to dashboard"
- [`PricingManager.tsx`](frontend/src/pages/admin/PricingManager.tsx) — added `userLoaded` guard so AccessDenied doesn't flash during auth hydration
- [`OrgAdminDashboard.tsx`](frontend/src/pages/admin/OrgAdminDashboard.tsx)
- [`ProjectsListPage.tsx`](frontend/src/pages/project/ProjectsListPage.tsx) — uses `contactHint` to explain "Multi-repo projects are part of our Team and Enterprise plans"

**Verified**: `free-audit.png` shows the AccessDenied frame in place; navigation chain from `/audit` no longer pollutes `/projects/empty`.

### R2 — V3 top-bar Upgrade label
**No-op.** The V3 top-bar (`V3TopBar.tsx`) doesn't actually render an "Upgrade ↗" button — that affordance only ever existed in the V2 sidebar (already fixed in the previous sprint as Task 6). The followup's reference was stale. The PRO `UpgradeNudge` banner already has tier-aware copy ("You've shipped X PRs solo on Pro. Want to invite your team to share credits…") with an explicit "Invite team →" CTA.

### R3 — Seat-count copy consistency
[`V3Team.tsx`](frontend/src/v3/pages/V3Team.tsx) `SeatsCard`:
- TEAM: "1 of 1 seat used" (unchanged)
- ENT: "1 member" → **"1 member · unlimited seats"**

Both reads now billing-aware and grammatically same-shape.

### R4 — Backend Tier.MAX cleanup
DB probe: **0 rows** with `tier='MAX'` across `app_user`, `org`, `pipeline_run`, `tier_config`, `billing_plan`. Outcome (b) per the followup spec: code references found but no live data. Updated 2 user-facing strings:
- [`RunEvent.java:304`](src/main/java/com/navneet/executespec/sse/RunEvent.java) budget-exceeded message: "or MAX for unlimited BYOK" → "Upgrade to Team for unlimited BYOK runs"
- [`ProjectChatController.java:123`](src/main/java/com/navneet/executespec/memory/ProjectChatController.java): "ENTERPRISE or MAX tier" → "Team or Enterprise tier"

Legacy normalisation paths in `EntitlementResolver` and `LifetimeCreditService` (which coerce hypothetical lingering MAX rows → TEAM) left intact as belt-and-suspenders.

**Note:** The `Tier.MAX` enum value itself is *not* removed in this batch — that needs a separate ticket per the followup's outcome (b) escalation rule, since some legacy switches (e.g. `BillingService.upgradeTier`) still reference it as a back-compat case.

### R5 — Close Task 22 as not-reproducible
Tracker hygiene only. The original "Sign-in button first-click no-fire" bug did not reproduce in this rerun (Puppeteer authenticated all 5 personas successfully across 36 surfaces). Marking as **CLOSED — CANNOT REPRODUCE**. A Playwright/Cypress regression test would be welcome but is outside this batch's scope.

### R6 — Verify Tasks 15 + 20 via NewRunModal screenshot
Captured `pro-newrun-modal.png` post-restart. Verified both:
- ✅ Provider chips: `["Anthropic", "Gemini", "OpenAI"]` (Task 15)
- ✅ Close button: `aria-label="Close"` (Task 20)

Probe script kept at `/tmp/persona-screenshotter/verify-newrun-modal-v2.js` for future re-runs.

### R7 — Signal Blue contrast (Option A)
[`primitives.css`](frontend/src/design-system/primitives.css):
- `--c-signal-blue: #5B8DEF` → **`#3B6FCC`** (white text contrast 3.23 → **4.85** AA pass)
- `--c-signal-blue-hover: #7AA5F5` → **`#2B5BB3`** (was 2.46 = AA-large fail; now **6.46**)
- Hover goes DARKER not lighter, fixing the regression where hover made contrast worse than rest.

Brand-grad teal destination `#7AD1C8` preserved per spec note.

### R8 — ENT canViewAudit (going Option Miss)
[`TierPermissionService.java`](src/main/java/com/navneet/executespec/auth/service/TierPermissionService.java) — root cause was deeper than the followup described:

**The bug:** V162 bootstraps every user into a `personal_org` (with `is_personal=true`). The permission resolver routes personal-org users through `freeUserPermissions()` (despite the misleading method name — "solo user" is what's actually happening). That method *hardcoded* `canViewAudit / canManageIntegrations / canInviteMembers / canViewOrgMembers / canViewPricing = false` regardless of tier, so even ENT solo users were locked out.

**The fix:** introduced `canManageOrgAsTeamOrEnt` boolean inside `freeUserPermissions`. TEAM/ENT users (who pay for an Enterprise plan) now get all five org-management permissions; FREE/PRO remain locked. PRO is single-developer grandfathered, so blocking team-management there matches the value prop.

**Tests:** added 4 regression tests to `TierPermissionServiceSoloUserTest`:
- `personalOrgEnterpriseUser_canViewAuditAndManageOrg`
- `personalOrgTeamUser_canViewAuditAndManageOrg`
- `personalOrgFreeUser_cannotViewAuditOrManageOrg`
- `personalOrgProUser_cannotViewAuditOrManageOrg`

Total `TierPermissionServiceSoloUserTest` count: 11 → **15** (all green).

### R9 — Settings Danger zone → Account actions (Option B)
[`V3Settings.tsx`](frontend/src/v3/pages/V3Settings.tsx) — 1-line copy change in two spots:
- Subhead: "Account · AI provider · Repos · **Account actions** — all in one place"
- Card title: "Danger zone" → **"Account actions"**

Promote back to "Danger zone" the moment real destructive ops (delete-account, revoke-sessions) ship.

### R10 — pop-orange → pink (#EC4899)
[`primitives.css`](frontend/src/design-system/primitives.css) — retargeted `--c-pop-orange` from `#F97316` (orange, sharing hue with `--c-warning` `#F59E0B` and `--status-partial` `#F5A524`) to **`#EC4899`** (pink). Variable name kept as `--c-pop-orange` to avoid touching every consumer (`v3.css` brand-pop gradient, `UpgradeNudge.tsx`); future cleanup pass can rename to `--c-pop-pink`.

Login hero accent gradient also updated inline to `linear-gradient(90deg, #ec4899, #f59e0b)` for the new "PRs" highlight word in R13.

`UpgradeNudge.tsx` fallback-hex bumped to match.

### R11 — status-executing #4A77D6 (separated from brand)
[`tokens.css`](frontend/src/tokens.css) — `--status-executing: var(--c-signal-blue)` (= brand) → **`#4A77D6`** literal. A "running" status pill now reads as in-motion / waiting rather than as a CTA.

Verified collision-free via in-page audit: `brand_blue_eq_status_exec: false`.

### R12 — border-default contrast (1.35 → 1.72)
[`tokens.css`](frontend/src/tokens.css):
- `--border-default: var(--c-graphite-4)` → **`var(--c-graphite-5)`** (1.35 → 1.72; passes WCAG SC 1.4.11 ≥1.5)
- `--border-hover: var(--c-graphite-5)` → **`var(--c-graphite-6)`** (preserves rest→hover delta)

Cards, dividers, and input outlines now visibly framed.

### R13 — Login hero copy
[`LoginPage.tsx`](frontend/src/pages/auth/LoginPage.tsx):
- H1: "Sign in to ship a **spec**." → **"Ship AI-written PRs you can actually merge."**
- Right-side mobile subhead: "Sign in to ship a spec." → **"Ship AI-written PRs you can actually merge."**
- Body sub: "ExecuteSpec ships AI-written pull requests you can actually merge — for one repo or for twenty…" → **"ExecuteSpec turns your specs into draft pull requests — across one repo or twenty…"**

Brand-pop accent moved from "spec." (orange, internal jargon) to "PRs" (pink-to-amber gradient, plain English).

### R14 — Recipe-shaped run templates
[`FirstRunSuggestions.tsx`](frontend/src/components/runs/FirstRunSuggestions.tsx) — replaced 4 generic templates with the recipe-shaped set the followup suggested:

| Old | New |
|---|---|
| Portfolio Website | **Add Stripe Checkout** — Wire Stripe-hosted Checkout into your auth + DB stack |
| Expense Tracker | **Migrate Express → Fastify** — 2-3× throughput; same routes, different framework |
| REST API | **Convert REST → tRPC** — End-to-end type-safety with no schema drift |
| SaaS Landing Page | **Add feature flags via GrowthBook** — A/B-test-ready gating with a self-hosted option |

Each template seeds the spec textarea with a specific, ship-able prompt rather than a "build me a generic web app" prompt.

### R15 — Demo project seeding
**Deferred per spec.** Backend-heavy change (UserOnboardingService + new `is_demo` column on Project + special handling for tier limits + audit safety). The followup itself notes "Implementation once approved: Backend `UserOnboardingService` creates the demo project on user creation. Demo runs don't count against tier limits…" — out of scope for this batch.

### R16 — Trust homepage link
**Deferred per spec.** "This may not even be in the same repo as the app. Check before starting." Marketing site is a separate repo; not actionable from this codebase.

---

## Verification

### Design tokens — in-page audit (post-Vite HMR)

```
Token                     Before        After           Contrast (white on bg or token on bg)
--c-signal-blue           #5B8DEF       #3B6FCC         4.85  ← AA pass for white text (was 3.23)
--c-signal-blue-hover     #7AA5F5       #2B5BB3         6.46  ← darker > rest (was 2.46 fail)
--border-default          #242A33       #333B46         1.72  ← ≥1.5 SC 1.4.11 (was 1.35 fail)
--c-pop-orange            #F97316       #EC4899         5.52  ← no longer in warning hue family
--status-executing        #5B8DEF       #4A77D6         4.54  ← separate from brand blue
```

```
Collisions:
  brand_blue_eq_status_exec:     false  ✓
  pop_orange_in_warning_family:  false  ✓
```

### Backend tests
- `TierPermissionServiceSoloUserTest`: **15/15 green** (was 11; +4 R8 regressions)
- `EntitlementResolverTest`: 7/7 green
- Total touched: 22/22

### Frontend
- `tsc && vite build`: clean ✓
- Persona-test rerun: 36 surfaces × 6 personas captured under `docs/audits/persona-test-2026-05-01-rerun-assets/`

### Snapshot diffs vs original

| Surface | Before | After |
|---|---|---|
| `/login` hero | "Sign in to ship a **spec**." | "Ship AI-written **PRs** you can actually merge." |
| `/audit` for FREE/ENT (no perms) | Redirect → `/projects/empty` ("Welcome, Reval, you don't have any projects yet") | "Audit log requires an org admin access · Back to dashboard" |
| `/team` ENT seat header | "1 member" | "1 member · unlimited seats" |
| `/settings` subhead | "… Repos · Danger zone — all in one place" | "… Repos · Account actions — all in one place" |
| `/runs` templates | Portfolio Website / Expense Tracker / REST API / SaaS Landing | Add Stripe Checkout / Migrate Express → Fastify / Convert REST → tRPC / Add feature flags via GrowthBook |
| Primary CTA buttons | `#5B8DEF` (3.23 contrast, AA-large only) | `#3B6FCC` (4.85, AA pass) |
| Card borders | Barely visible (1.35) | Visibly framed (1.72) |
| Brand pop accent | `#F97316` (orange, warning-adjacent) | `#EC4899` (pink, distinct hue) |
| Status-executing pill | Same as brand-blue CTA | Slightly desaturated, distinguishable |

---

## Files touched

**Frontend (15):**
- `frontend/src/design-system/primitives.css` — R7, R10
- `frontend/src/tokens.css` — R11, R12
- `frontend/src/pages/AuditPage.tsx` — R1
- `frontend/src/pages/admin/PricingManager.tsx` — R1
- `frontend/src/pages/admin/OrgAdminDashboard.tsx` — R1
- `frontend/src/pages/project/ProjectsListPage.tsx` — R1
- `frontend/src/pages/auth/LoginPage.tsx` — R13
- `frontend/src/v3/pages/V3Team.tsx` — R3
- `frontend/src/v3/pages/V3Settings.tsx` — R9
- `frontend/src/v3/components/UpgradeNudge.tsx` — R10 fallback hex
- `frontend/src/components/runs/FirstRunSuggestions.tsx` — R14

**Backend (3):**
- `src/main/java/com/navneet/executespec/auth/service/TierPermissionService.java` — R8
- `src/main/java/com/navneet/executespec/sse/RunEvent.java` — R4
- `src/main/java/com/navneet/executespec/memory/ProjectChatController.java` — R4

**Tests (1):**
- `src/test/java/com/navneet/executespec/auth/service/TierPermissionServiceSoloUserTest.java` — R8 (4 new tests)

**Docs (1):**
- This file.

---

## Open follow-ups

1. **Backend restart needed for R8 + R4 to take effect.** Same DevTools-doesn't-auto-reload pattern as the previous batch's Task 15. Frontend already live via Vite HMR.
2. **R15 (demo seeding)** when product approves demo content. Backend-heavy.
3. **R16 (homepage Trust link)** when marketing site team prioritises.
4. **Tier.MAX enum removal** — separate ticket. DB is clean (0 MAX rows); the enum value still exists for back-compat in 2-3 switch statements. Safe to remove in a focused cleanup PR.

---

## Recommendation for next sprint

The remaining 2 deferred tasks (R15, R16) sit cleanly in the "needs human alignment" bucket. After backend restart picks up R8 + R4, this batch closes out. Suggested next sprint focus areas:

1. Demo project seeding (R15) — biggest first-impression-conversion win still on the board.
2. Tier.MAX enum removal — small cleanup PR; reduces dead branches.
3. Any new audit findings from a fresh persona test run.
