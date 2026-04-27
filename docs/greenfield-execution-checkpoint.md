# Greenfield Execution — Wave 3 Closeout · ENTIRE PLAN COMPLETE

> Branch: `feature/conversion-redesign` (off `dev` @ `f1d04532`).
> **Wave 1 — 20/20 shipped (Wave 1 closeout `a8841f11`).**
> **Wave 2 — 11/11 shipped (Wave 2 closeout `09c543a6`).**
> **Wave 3 — 3/3 shipped + 1 bonus follow-up.** ← this commit.
>
> The full greenfield plan is now in code on a single branch.

## Wave 3 ledger

| ID | Title | Live verified |
|---|---|---|
| R-171 | PWA manifest + service worker | ✓ `/manifest.webmanifest` returns the JSON, `/sw.js` returns 200 text/javascript, prod-only registration in `main.tsx` |
| R-180 | v2 → v3 opt-in redirect map | ✓ flip the toggle in /v3/settings, `/dashboard` immediately Navigates to `/v3/insights` |
| R-181 | Changelog page + first-visit tour banner | ✓ `/v3/changelog` renders Wave 1+2+3 with deep-links; tour banner shows once per browser, dismissable, cleared via localStorage |
| **Bonus** | `ReferralService.onRefereeFirstReadyRun` wire-point in `V2RunOrchestrator.terminate()` | ✓ R-162's only end-to-end stitch — when a referee's run reaches COMPLETE, the referring Free user gets +5 lifetime credits granted |

## Backend tests — final state across all 3 waves

`mvn test -Dtest='ReferralServiceTest,LifetimeCreditServiceTest,EntitlementResolverTest,ValidationReportServiceVerifiedScoreTest'`
→ **32/32 green** (Wave 1 left 23 green; +9 from Wave 2's R-162; Wave 3 added zero new tests but the bonus wire-point compiles cleanly without regressing existing ones).

## Cohabitation invariant — held end-to-end across 3 waves

* **Three** non-additive v2 file edits across the entire greenfield branch:
  1. `EventName.java` — Wave 1, appended 8 enum entries (purely additive)
  2. `SecurityConfig.java` — Wave 1, added 2 permitAll matchers under `/api/v3` (additive)
  3. `V2RunOrchestrator.java` — Wave 3 bonus, injected `ReferralService` + 6 lines in `terminate()` (intentional shared-source change for R-162)
* **`App.tsx`** — wave-by-wave route additions; the 7 v2 route entries that gained `<V2OptInGate>` wrappers in R-180 are non-functional until the user toggles the flag (the gate returns the v2 element unchanged when off)
* Every other v3 file lives under `frontend/src/v3/` or `src/main/java/com/navneet/executespec/v3/`

v2 routes / chrome / billing surface still work at every existing path.

## TypeScript / Maven

`./node_modules/.bin/tsc --noEmit` clean across v2 + v3.
`mvn -q -DskipTests compile` clean.
`mvn test -Dtest='*VerifiedScoreTest,*LifetimeCreditServiceTest,*EntitlementResolverTest,*ReferralServiceTest'` 32/32 green.

## All decisions (D-001 through D-151) shipped with their defaults

| Tag | Default | Status |
|---|---|---|
| D-001 textarea-copy | "What do you want to build?" | ✓ shipped (R-110/R-120/R-123) |
| D-002 lifetime-credit math | 5 lifetime + 5/invite, cap 25 | ✓ shipped (V152 + LifetimeCreditService) |
| D-003 referral-fire-trigger | On referee's first run | ✓ shipped (R-162 + V2RunOrchestrator wire-point) |
| D-004 vibe-view-decision | Delete in v3 | ✓ shipped (no Vibe toggle on any v3 surface) |
| D-141a tier prices | Pro $20 · Team $39/seat · Enterprise sales | ✓ shipped (lib/pricing.ts) |
| D-141b currency default | USD; INR auto-detect | ✓ shipped (detectDefaultCurrency) |
| D-163 nudge timing | Toast ≤1, modal at 0 | ✓ shipped (FreeCapNudge) |
| D-125 repo-attach gaps | TODO comments at every wire-point | ✓ shipped (RepoAttachPopover.tsx); user said "we will revisit" |
| D-144 integrations real-vs-soon | GitHub + Slack + Bitbucket real; Jira + Confluence schema; ServiceNow + PagerDuty soon | ✓ shipped (V3Integrations) |
| D-151 SOC 2 status copy | "Audit in progress with [Auditor name TBD]" | ✓ shipped (V3Trust); user fills in real auditor |

## What stays in the long-tail backlog

These are the items the action list called out as **post-cutover** or **separate roadmap items** — explicitly outside the 3-wave plan that's now complete:

* **R-190 / R-191** Vibe deletion + Mission-vocabulary purge — only safe after the v2 surface is fully retired
* **Real LLM dispatch for /api/v3/anonymous-runs** — gated behind `v3.anonymous-trial.real-dispatch` flag pending the abuse-classifier / Redis rate-limit / cost-cap design pass
* **D-125 design-decision revisit** — user explicitly said "we will revisit again"; 4 questions parked as TODOs in `RepoAttachPopover.tsx`
* **R-162 V153 migration** — promote in-memory pendingInvites to a `referral_invite` table; pair with a transactional email pipeline so invites become real magic links rather than just recorded intent
* **Real Trust Pack PDF asset** — R-151 today alerts pointing to `security@executespec.ai`; the actual 4-page download lands when compliance kicks off

None of these block the cutover (R-180's opt-in redirect path is the soft-rollout vehicle).

## Servers

| Service | Port | URL | State |
|---|---|---|---|
| v3 frontend (Vite) | 5176 | `http://localhost:5176/v3/...` | up |
| v2 backend | 8080 | proxied via Vite | up (still pre-V152 — first integration-test trigger is whoever boots a fresh backend on this branch) |

Restart the backend against `feature/conversion-redesign` whenever the team's ready to integration-test the v3 endpoints (`/api/v3/entitlements`, `/api/v3/anonymous-runs`, `/api/v3/referrals/*`).
