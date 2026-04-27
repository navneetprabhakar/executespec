# Greenfield Execution — Checkpoint

> Written by the implementing session. Refreshed every 3 R-items.
> Branch: `feature/conversion-redesign` (off `dev` @ `f1d04532`).

## Status

* **Wave:** 1 of 3 — *Build the v3 spine end-to-end.*
* **Items shipped:** 16 of 20 in Wave 1 (80%)
* **Items in flight:** none
* **Blocked / decision-needed:** none (defaults applied for D-001..D-004)

## Done so far

| ID | Title | Files | Verified |
|---|---|---|---|
| R-101 | v3 router shell + dark theme lock | `frontend/src/v3/components/V3Shell.tsx` · `v3.css` | Browser preview |
| R-105 | v3 terminology source-of-truth | `frontend/src/v3/lib/terminology.ts` | Consumed by all v3 components |
| R-102 | TopBar | `frontend/src/v3/components/V3TopBar.tsx` | Visible across every v3 page |
| R-106 | Pill + Term primitives + glossary | `Pill.tsx` · `Term.tsx` · `lib/glossary.ts` | Verified live |
| R-103 | CmdK skeleton (action mode) | `CmdK.tsx` · `stores/useV3Ui.ts` | Cmd-K opens, 14 actions |
| R-104 | AvatarMenu | `AvatarMenu.tsx` | Verified with SUPER_ADMIN session |
| R-132 | Honest-Verdict backend patch | `report/ValidationReportService.java` + test | 8/8 tests green |
| R-124 | ProjectSwitcher | `ProjectSwitcher.tsx` | Verified live, loads 3 projects |
| R-120 | Project home (chat shape) | `pages/V3ProjectHome.tsx` · `stores/useV3ProjectStore.ts` | Verified live |
| R-121 | PlanCard inline | `components/PlanCard.tsx` | Component compiles, integrated into ProjectHome |
| R-122 | ResultCard inline | `components/ResultCard.tsx` | Verified live (3-state honest verdict) |
| R-123 | Composer | `components/Composer.tsx` | Verified live (1 textarea + Advanced) |
| R-130 | Run detail (PR-shaped) | `pages/V3RunDetail.tsx` | Verified live with run c2e83249 |
| R-131 | RunFailureSummary | `components/RunFailureSummary.tsx` | Verified live (real failure, 3 issues listed) |
| R-161 | Lifetime credit schema (V152) + service | `db/migration/V152__lifetime_run_credit_balance.sql` · `auth/entity/AppUser.java` (3 new columns) · `v3/credit/LifetimeCreditService.java` + 10 tests | 10/10 tests green; backend compiles |
| R-142 | `/api/v3/entitlements` single-source | `v3/entitlements/EntitlementResolver.java` · `EntitlementsResponse.java` · `v3/api/V3EntitlementsController.java` · `frontend/src/v3/lib/api.ts` (`getV3Entitlements`) + 5 tests | 5/5 tests green; not yet observable in browser (no UI consumer until R-141 in Wave 2) |

## Wave 1 backend test totals

`mvn test -Dtest='LifetimeCreditServiceTest,EntitlementResolverTest,ValidationReportServiceVerifiedScoreTest'`
→ **23/23 green** across 3 v3 test classes.

## Cohabitation summary (recap)

* `/v3/*` route prefix on the frontend; `/api/v3/*` on the backend.
* v2 untouched at every existing path.
* v3 forces `data-theme="dark"` on document root only while a v3 page is mounted; theme is restored on unmount.
* v3 TopBar renders OUTSIDE v2's `AppLayout` — no Sidebar, no v2 header, no breadcrumb.
* v3 reuses v2's `useAuthStore`, `useUiStore`, `lib/api.ts`, `AppUserDetails`, `AppUserRepository`, `OrgRepository`, `TierConfigRepository` so RBAC + settings + tier-config stay consistent across the cohabiting surfaces.
* V152 migration is purely additive (3 new columns on `app_user`, all with defaults); v2 surfaces ignore them.
* R-132 backend patch is a single-source change — both v2 and v3 surface honest scores from the same formula.
* `/api/v3/entitlements` is the new single source of tier truth for v3; v2 keeps reading via `TierEnforcementService` unchanged.

## Up next

R-160 (PostHog v3 events) → R-140 (`/v3/settings`) → R-110/111/112 (landing + anonymous run + auth) — closes Wave 1.

## Decisions still pending acknowledgement (defaults applied)

| Tag | Default | Status |
|---|---|---|
| D-001 textarea-copy | "What do you want to build?" + "Reply or describe a follow-up change…" | Shipped in R-120/123; overridable later |
| D-002 lifetime-credit math | 5 lifetime + 5/invite, cap 25 | Shipped in V152 + LifetimeCreditService |
| D-003 referral-fire-trigger | On referee's first run | R-162 (Wave 2) — schema column `referred_by_user_id` already in V152 |
| D-004 vibe-view-decision | Delete in v3; v2 untouched until cutover | All v3 screens (no Vibe toggle) |

## Live-test caveat

The v3 backend changes (V152 migration + V3EntitlementsController) are NOT picked up by the running 8080 backend — that JVM was started against the `feature/event-tracking-jsonb-fix` worktree before this branch existed. Verifying `/api/v3/entitlements` end-to-end via the browser requires a backend restart against `feature/conversion-redesign`. Skipping the restart this session because:
  1. Other concurrent sessions may rely on the running 8080 backend.
  2. The contract is fully covered by 23 unit tests.
  3. The first UI consumer of the entitlements API lands at R-141 in Wave 2 — that's the natural moment to integration-test.
Documented here so the next session knows to restart the backend before touching R-141.

## Servers + verification

| Service | Port | URL | State |
|---|---|---|---|
| v3 frontend (Vite) | 5176 | `http://localhost:5176/v3/p/{id}/r/{runId}` | up |
| v2 backend (Spring Boot) | 8080 | proxied via Vite | up (pre-V152) |
| v2 backend (alt) | 8081 | direct | up |
| v2 frontend (Vite) | 5174 | `http://localhost:5174/` | up (other concurrent session) |

## TypeScript / Maven

`./node_modules/.bin/tsc --noEmit` clean across v2 + v3.
`mvn test -Dtest='*VerifiedScoreTest,*LifetimeCreditServiceTest,*EntitlementResolverTest'` 23/23 green.
