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
* **D-125 design-decision revisit** — user explicitly said "we will revisit again"; 4 questions parked as TODOs in `RepoAttachPopover.tsx`
* **Real Trust Pack PDF asset** — R-151 today alerts pointing to `security@executespec.ai`; the actual 4-page download lands when compliance kicks off

### Post-Wave-3 follow-ups landed (2026-04-27)

Two backlog items have been progressed in-place after the original Wave-3 closeout — both shipped on `feature/conversion-redesign`, three commits since `09c543a6`:

* **R-162 V153 migration + email pipeline + signup binding** — `ReferralInvite` JPA entity over a new `referral_invite` table (V153) replaces the Wave-2 in-memory `ConcurrentHashMap`. `MailService.sendV3ReferralInvite` + Thymeleaf template `email/v3-referral-invite.html` send the magic-link signup email best-effort (mail failure WARNs but never aborts the invite write). `AuthService.register` + `findOrCreateFromOAuth` auto-call `ReferralService.bindReferral` on every new-user creation (idempotent + best-effort, matches by email so no token plumbing required). The referral chain is now end-to-end: invite → email → click → signup → bind → first run → credit grant.
* **R-111 anti-abuse infrastructure** — promoted the in-memory rate-limit + session store to Redis under new `v3/anonrun/` package. `AnonymousRunRateLimiter` (Redis INCR-then-EXPIRE, 3/hour per IP), `AnonymousRunSessionStore` (Redis JSON, 24h TTL, idempotent `bindToUser`), `AbuseHeuristic` (prompt-injection + banned-topic pre-flight gate), `AnonymousRunDispatcher` interface (default = `StubAnonymousRunDispatcher`; real LLM impl is one `@Primary @Bean` away). New `V3SignupClaimController` exposes `POST /api/v3/auth/claim-anonymous-session` (authenticated); `V3Auth.tsx` captures `?ref` / `?anon` URL params, bridges them across the OAuth round-trip via sessionStorage, and fires the claim post-login. **The dispatch flag `v3.anonymous-trial.real-dispatch` remains default OFF** — the actual LLM dispatch impl still ships separately whenever ops signs off on the cost-tracking + per-call cost cap + outline-only prompt design.

What's still genuinely outside this branch:

* **Real LLM dispatch impl for /api/v3/anonymous-runs** — the scaffold is now production-ready (rate-limit + session + abuse heuristic + dispatcher seam), but the actual Anthropic LIGHTWEIGHT impl with hard $0.01 per-call cap + outline-only prompt + ops spend dashboard is the deferred slice. When ops wants to enable, register a `@Primary @Bean` implementing `AnonymousRunDispatcher`.

None of these block the cutover (R-180's opt-in redirect path is the soft-rollout vehicle).

## Servers

| Service | Port | URL | State |
|---|---|---|---|
| v3 frontend (Vite) | 5176 | `http://localhost:5176/v3/...` | up |
| v2 backend | 8080 | proxied via Vite | up (still pre-V152 — first integration-test trigger is whoever boots a fresh backend on this branch) |

Restart the backend against `feature/conversion-redesign` whenever the team's ready to integration-test the v3 endpoints (`/api/v3/entitlements`, `/api/v3/anonymous-runs`, `/api/v3/referrals/*`).
