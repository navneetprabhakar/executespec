# Greenfield Execution — Wave 2 Checkpoint #3

> Branch: `feature/conversion-redesign` (off `dev` @ `f1d04532`).
> Wave 2 — 9 of 11 R-IDs shipped (82%).

## Done in Wave 2

| ID | Title | Verified |
|---|---|---|
| R-141 | `/v3/billing` — single-source plan card + 3-tier grid | ✓ Free + Enterprise live |
| R-150 | `/v3/pricing` — public hero + grid + FAQ | ✓ logged-out live |
| R-163 | Free-cap toast + blocking modal | ✓ balance≤1 + balance=0 live |
| R-125 | Repo-attach popover | ✓ list + no-PAT phases live |
| R-144 | `/v3/integrations` grid | ✓ 8 cards × 4 groups |
| R-143 | `/v3/team` (Free / Team / Enterprise variants) | ✓ all variants live; SSO panel ENTERPRISE-only |
| R-162 | Referral mechanism (backend + service + tests + frontend wire) | ✓ 9/9 tests green; R-163 invite form wired to real endpoint |
| R-151 | `/v3/trust` page | ✓ logged-out live |
| R-152 | `/v3/insights` (collapses dashboard+exec+admin-analytics) | ✓ live with REAL data: 41 runs · $33.35 · 73% success · spend-by-project · top wins |

## Backend tests

`mvn test -Dtest='ReferralServiceTest,LifetimeCreditServiceTest,EntitlementResolverTest,ValidationReportServiceVerifiedScoreTest'`
→ **32/32 green** (was 23 in Wave 1; +9 from R-162 ReferralServiceTest).

## TypeScript

`./node_modules/.bin/tsc --noEmit` clean.

## Cohabitation invariant

Wave 2 has touched ZERO v2 files non-additively. Every new component lives under `frontend/src/v3/`; every new backend service lives under `src/main/java/com/navneet/executespec/v3/`. `lib/api.ts` consumption only uses pre-existing v2 helpers (no v2 helper edits). The only backend additive edits across both waves: `EventName.java` (Wave 1; appended enum entries) + `SecurityConfig.java` (Wave 1; permitAll for /api/v3/anonymous-runs). R-162 added a new `/api/v3/referrals/*` controller; the existing `/api/**` authenticated rule covers it without SecurityConfig edits.

## Up next (closes Wave 2)

R-153 (HelpModal — extends Cmd-K with `?` mode) → R-170 (mobile responsive sweep). Two items remaining.

## Decisions all defaulted (D-141a/b/D-163/D-125/D-144/D-151) — no overrides this wave so far

## Servers

| Service | Port | URL | State |
|---|---|---|---|
| v3 frontend (Vite) | 5176 | `http://localhost:5176/v3/...` | up |
| v2 backend | 8080 | proxied via Vite | up (pre-V152 — restart pending for full integration) |
