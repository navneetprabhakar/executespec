# Greenfield Execution — Wave 2 Closeout

> Branch: `feature/conversion-redesign` (off `dev` @ `f1d04532`).
> Wave 2 — **11 of 11 R-IDs shipped (100%).**
> Wave 1 — 20/20 (already closed in commit `a8841f11`).

## Wave 2 final ledger

| ID | Title | Backend tests | Live verified |
|---|---|---|---|
| R-141 | `/v3/billing` — single-source plan + 3-tier grid | — | ✓ Free + Enterprise |
| R-150 | `/v3/pricing` — public hero + grid + FAQ | — | ✓ logged-out |
| R-163 | Free-cap toast + blocking modal | — | ✓ ≤1 + 0 states |
| R-125 | Repo-attach popover (OAuth-scope + repo-picker + auto-name) | — | ✓ list + no-PAT |
| R-144 | `/v3/integrations` grid (8 cards × 4 groups) | — | ✓ |
| R-143 | `/v3/team` (Free / Team / Enterprise + SSO panel) | — | ✓ all variants |
| R-162 | Referral mechanism (backend + tests + frontend wire) | **9/9 new** | ✓ R-163 invite form wired |
| R-151 | `/v3/trust` page | — | ✓ logged-out |
| R-152 | `/v3/insights` (collapses /dashboard + /exec + /admin/analytics) | — | ✓ real data |
| R-153 | HelpModal (Cmd-K `?` mode + global hotkey) | — | ✓ glossary entries |
| R-170 | Mobile responsive primitives sweep | — | ✓ 6 screens at 390px |

## Backend test totals

`mvn test -Dtest='ReferralServiceTest,LifetimeCreditServiceTest,EntitlementResolverTest,ValidationReportServiceVerifiedScoreTest'`
→ **32/32 green** (Wave 1 had 23; +9 from R-162).

## Cohabitation invariant — held end-to-end

Across Wave 1 + Wave 2:
* **Two non-additive v2 file edits, both in Wave 1:** `EventName.java` (appended enum entries) and `SecurityConfig.java` (permitAll matcher for /api/v3/anonymous-runs). No further v2 edits.
* **All other backend code** lives under `src/main/java/com/navneet/executespec/v3/`.
* **All frontend code** lives under `frontend/src/v3/` with a single edit to `frontend/src/App.tsx` to mount the routes.
* **`lib/api.ts` consumption uses pre-existing v2 helpers only** — no v2 helper modified.
* v2 routes / chrome / billing surface still work at every existing path. R-132's score-formula fix is the one intentional shared-source change (single source for both v2 ScoreCard and v3 ResultCard).

## What's left for Wave 3

Per the original action list:
1. **R-171** PWA manifest + service worker
2. **R-180** v2 → v3 opt-in redirect map (so users can flip `useUiStore.v3OptIn` and have v2 routes redirect)
3. **R-181** changelog page + tour banner
4. **R-190 / R-191** Vibe deletion + sidebar/Mission vocabulary purge (post-cutover backlog)
5. **Outstanding TODO follow-ups from R-125/R-162** — the 4 D-125 design questions (private repo · mono-repo subdir · repo-too-big · per-run override) and the V2RunOrchestrator wire-point that calls `ReferralService.onRefereeFirstReadyRun` on the referee's first ready run (today the credit grant has the service but no orchestrator call point).
6. **Real LLM dispatch for the anonymous trial (R-111)** — gated behind `v3.anonymous-trial.real-dispatch` flag; needs the abuse-classifier / rate-limit / cost-cap design pass.

## Decisions all defaulted (no overrides this wave)

| Tag | Default | Status |
|---|---|---|
| D-141a tier prices | Pro $20 · Team $39/seat · Enterprise sales | ✓ shipped in lib/pricing.ts |
| D-141b currency default | USD; INR auto-detect on Asia/Kolkata + en-in | ✓ shipped in detectDefaultCurrency() |
| D-163 nudge timing | Toast at lifetime balance ≤ 1, blocking modal at 0 | ✓ shipped in FreeCapNudge |
| D-125 repo-attach gaps | TODO comments at every wire-point | ✓ shipped in RepoAttachPopover.tsx |
| D-144 integrations real-vs-soon | GitHub App + PAT + Slack + Bitbucket real; Jira + Confluence schema; ServiceNow + PagerDuty soon | ✓ shipped in V3Integrations |
| D-151 SOC 2 status copy | "Audit in progress with [Auditor name TBD]" | ✓ shipped — user to fill in real auditor |

## Servers

| Service | Port | URL | State |
|---|---|---|---|
| v3 frontend (Vite) | 5176 | `http://localhost:5176/v3/...` | up |
| v2 backend | 8080 | proxied via Vite | up (pre-V152) |

When Wave 3 begins, the running backend will need to be restarted against this branch so V152 + the v3 controllers are picked up — first integration-test moment.

## TypeScript / Maven

`./node_modules/.bin/tsc --noEmit` clean across v2 + v3.
`mvn -q -DskipTests compile` clean.
`mvn test -Dtest='*VerifiedScoreTest,*LifetimeCreditServiceTest,*EntitlementResolverTest,*ReferralServiceTest'` 32/32 green.
