# Greenfield Execution — Checkpoint

> Written by the implementing session. Refreshed every 3 R-items.
> Branch: `feature/conversion-redesign` (off `dev` @ `f1d04532`).

## Status

* **Wave:** 1 of 3 — *Build the v3 spine end-to-end.*
* **Items shipped:** 18 of 20 in Wave 1 (90%)
* **Remaining in Wave 1:** R-110 (`/v3/landing`) · R-111 (anonymous run endpoint) · R-112 (`/v3/auth`)
* **Items in flight:** none
* **Blocked / decision-needed:** none (defaults applied for D-001..D-004)

## Done so far

| ID | Title | Live? |
|---|---|---|
| R-101 | v3 router shell + dark theme lock | ✓ verified |
| R-105 | v3 terminology source-of-truth | ✓ consumed everywhere |
| R-102 | TopBar | ✓ verified |
| R-106 | Pill + Term primitives + glossary | ✓ verified |
| R-103 | CmdK skeleton (action mode) | ✓ verified |
| R-104 | AvatarMenu | ✓ verified |
| R-132 | Honest-Verdict backend patch | ✓ 8/8 tests green; affects v2 + v3 |
| R-124 | ProjectSwitcher | ✓ verified |
| R-120/121/122/123 | Chat-shaped project home + Composer + PlanCard + ResultCard | ✓ verified |
| R-130 | PR-shaped run detail | ✓ verified live (real failure run) |
| R-131 | RunFailureSummary | ✓ verified live (3 issues listed) |
| R-161 | V152 + LifetimeCreditService | ✓ 10/10 tests green |
| R-142 | EntitlementResolver + `/api/v3/entitlements` | ✓ 5/5 tests green; UI consumer in Wave 2 |
| R-160 | PostHog v3 events (8 backend enums + 14 frontend events) | ✓ Composer wired |
| R-140 | `/v3/settings` single-page card stack | ✓ verified live |

## Backend test totals

`mvn test -Dtest='*VerifiedScoreTest,*LifetimeCreditServiceTest,*EntitlementResolverTest'`
→ **23/23 green** across 3 v3 test classes.

## Cohabitation summary (recap)

* `/v3/*` route prefix on the frontend; `/api/v3/*` on the backend.
* v2 untouched at every existing path. Only `EventName.java` got new enum values appended (additive).
* v3 forces `data-theme="dark"` on document root only while a v3 page is mounted.
* v3 TopBar renders OUTSIDE v2's `AppLayout` — no Sidebar, no v2 header, no breadcrumb.
* v3 reuses v2's `useAuthStore`, `useUiStore`, `lib/api.ts`, `AppUserDetails`, `AppUserRepository`, `OrgRepository`, `TierConfigRepository`, `lib/analytics.ts`.
* V152 migration is purely additive (3 new columns on `app_user`, all with defaults).
* R-132 backend patch is single-source — both v2 and v3 surface honest scores.
* `/api/v3/entitlements` is the new single tier-truth source for v3; v2 keeps reading via `TierEnforcementService` unchanged.

## Up next (closes Wave 1 in one batch)

R-111 (anonymous-run backend) → R-110 (`/v3/landing` page consuming it) → R-112 (`/v3/auth` saving the anonymous run to the new account). The "no signup → first value → save the run → upgrade" loop in Section 5 of the greenfield doc.

## Decisions (defaults applied)

| Tag | Default | Status |
|---|---|---|
| D-001 textarea-copy | "What do you want to build?" + "Reply or describe a follow-up change…" | Shipped in R-120/123 |
| D-002 lifetime-credit math | 5 lifetime + 5/invite, cap 25 | Shipped in V152 + LifetimeCreditService |
| D-003 referral-fire-trigger | On referee's first run | R-162 (Wave 2) — schema column already in V152 |
| D-004 vibe-view-decision | Delete in v3 | All v3 screens have no Vibe toggle |

## Live-test caveat (recap)

The running 8080 backend is from a sibling worktree pre-V152. Verifying `/api/v3/entitlements` end-to-end via the browser requires a backend restart against `feature/conversion-redesign`. Skipped this session — first UI consumer (R-141) lands in Wave 2 and will integration-test naturally. The contract is fully covered by 15 unit tests.

## Servers

| Service | Port | URL | State |
|---|---|---|---|
| v3 frontend (Vite) | 5176 | `http://localhost:5176/v3/...` | up |
| v2 backend (Spring Boot) | 8080 | proxied via Vite | up (pre-V152) |
| v2 backend (alt) | 8081 | direct | up |
| v2 frontend (Vite) | 5174 | `http://localhost:5174/` | up (other concurrent session) |

## TypeScript / Maven

`./node_modules/.bin/tsc --noEmit` clean across v2 + v3.
`mvn compile -DskipTests` clean.
`mvn test -Dtest='*VerifiedScoreTest,*LifetimeCreditServiceTest,*EntitlementResolverTest'` 23/23 green.
