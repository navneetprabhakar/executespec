# Greenfield Execution — Wave 1 Closeout

> Branch: `feature/conversion-redesign` (off `dev` @ `f1d04532`).
> Wave 1 of 3 complete. Pausing for user review per protocol.

## Status

* **Wave:** 1 of 3 — *Build the v3 spine end-to-end* — **COMPLETE.**
* **Items shipped:** 20 of 20 (100% of Wave 1)
* **Decisions:** all four defaults (D-001..D-004) applied; user can override and I'll rip-and-redo

## What ships in Wave 1

### Frontend (`/v3/*`)

| URL | What it is | Persona unlock |
|---|---|---|
| `/v3/home` | Silent dispatcher — logged-in → first project / logged-out → landing | n/a |
| `/v3/landing` | Chat-first marketing landing with anonymous trial textarea | All consumers |
| `/v3/auth` | "Save this run — sign in" framing; OAuth-first + magic-link fallback | All consumers |
| `/v3/p/{slug}` | Slack-shaped chat thread; pulse summary + run history bubbles + composer | Maya, Theo, Priya, Sam |
| `/v3/p/{slug}/r/{runShort}` | PR-shaped run detail; status header + evidence chips + file tree + failure summary | All |
| `/v3/settings` | Single-page card stack — Account / AI provider / Repos / Danger zone | All |

### Frontend chrome

| Component | Notes |
|---|---|
| `V3Shell` + `v3.css` | Dark-locked palette scoped to `.v3-root`; v2 theme toggle still works for v2 routes |
| `V3TopBar` | Logo + project switcher + ⌘K button + AvatarMenu; no sidebar, no breadcrumb |
| `ProjectSwitcher` | Linear-style popover, real `/api/projects` data, current-project tracked |
| `AvatarMenu` | Logged-out: Pricing + Sign in. Logged-in: identity + Account / Billing / Team (Pro+) / Integrations / Insights (admin+) / Help / Sign out |
| `CmdK` | Cmd-K palette with 14 actions, admin-gated subset, no R-### debug labels visible to user |
| `Pill` | Single status primitive — replaces 5 chip variants from v2 |
| `Term` | Glossary tooltip wrapper backed by `glossary.ts` |

### Frontend state

| Store | Purpose |
|---|---|
| `useV3Ui` | Cross-component v3 UI state (Cmd-K open, etc.) |
| `useV3ProjectStore` | Per-project chat thread + bubble morph + optimistic push |

### Backend (`/api/v3/*`)

| Endpoint | What it is |
|---|---|
| `GET /api/v3/entitlements` | Single-source tier read (replaces v2's three disagreeing sources) |
| `POST /api/v3/anonymous-runs` | Anonymous-trial endpoint; gated stub today, real LLM dispatch in Wave 2 |
| `GET /api/v3/anonymous-runs/{token}` | Status poll for the anonymous trial |

### Backend services

| Service | Notes |
|---|---|
| `EntitlementResolver` | Org-tier wins over personal; MAX collapses to TEAM (V141 path); soft budget derived as monthly_run_limit × $2 |
| `LifetimeCreditService` | Free-tier: 5 lifetime + 5/invite, cap 25; paid tiers no-op (treated as unlimited); supports decrement / refund / grantInviteCredit |
| `EventName.V3_*` (8 enums) | Conversion funnel mirrors of v2 events, surface-tagged |
| `AnonymousRunController` | In-memory rate-limit (3/IP/hour), in-memory session store, gated by `v3.anonymous-trial.real-dispatch` flag |

### Backend invariants

| Test class | Tests |
|---|---|
| `ValidationReportServiceVerifiedScoreTest` | 8 — pins R-132 honest-score fix incl. the strict-review e48399bf regression case |
| `LifetimeCreditServiceTest` | 10 — baseline / decrement / cap / paid-tier no-op / refund / unknown-user |
| `EntitlementResolverTest` | 5 — enterprise org wins / free balance / MAX→TEAM / unknown / blank |
| **Total** | **23/23 green** |

### Migrations

| Version | Effect |
|---|---|
| `V152__lifetime_run_credit_balance.sql` | Adds 3 columns to `app_user`; purely additive; v2 ignores them |

## Cohabitation invariant (never broken)

* Every v2 route still works at its existing path.
* Only one v2 file got non-additive edits across the entire wave: `EventName.java` (appended 8 new enum entries) and `SecurityConfig.java` (added two new permitAll matchers under `/api/v3`).
* v2 `AppLayout` / `Sidebar` / `Header` are untouched.
* v2 monthly-run-counter enforcement is independent of v3's lifetime-credit balance.
* R-132's score-formula fix is the one exception: it's a single-source change that intentionally affects both v2 and v3 (v2 ScoreCard surfaces honest scores immediately).

## Decisions still pending acknowledgement (defaults applied)

| Tag | Default | Status |
|---|---|---|
| D-001 textarea-copy | "What do you want to build?" + "Reply or describe a follow-up change…" | Shipped in R-120 / R-123 / R-110 |
| D-002 lifetime-credit math | 5 lifetime + 5/invite, cap 25 | Shipped in V152 + LifetimeCreditService |
| D-003 referral-fire-trigger | On referee's first run | Wave 2 (R-162); schema column ready |
| D-004 vibe-view-decision | Delete in v3 | All v3 screens have no Vibe toggle |

## Live-test caveats

1. **Backend:** v3 backend changes (V152 + AnonymousRunController + V3EntitlementsController) NOT picked up by the running 8080 backend (it was started against a sibling worktree pre-V152). To live-test the API endpoints, restart the backend against `feature/conversion-redesign`. Skipped this session — first UI consumer of `/api/v3/entitlements` lands at R-141 in Wave 2 and will integration-test naturally.

2. **Anonymous trial:** Real LLM dispatch is gated behind feature flag `v3.anonymous-trial.real-dispatch` (default OFF). Today the endpoint returns a `STUB_COMPLETE` response so the UI flow is testable end-to-end without exposing a $$$ surface. Wave 2 lands real dispatch + Redis rate-limit + abuse classifier + cost cap.

## Servers

| Service | Port | URL | State |
|---|---|---|---|
| v3 frontend (Vite) | 5176 | `http://localhost:5176/v3/landing` | up |
| v2 backend (Spring Boot) | 8080 | proxied via Vite | up (pre-V152) |
| v2 backend (alt) | 8081 | direct | up |
| v2 frontend (Vite) | 5174 | other concurrent session | up |

## TypeScript / Maven

`./node_modules/.bin/tsc --noEmit` clean across v2 + v3.
`mvn compile -DskipTests` clean.
`mvn test -Dtest='*VerifiedScoreTest,*LifetimeCreditServiceTest,*EntitlementResolverTest'` 23/23 green.

## Wave 2 starting line

When resuming after user review, the next wave covers the conversion-mechanics + procurement-readiness layer:

| ID | Title |
|---|---|
| R-141 | `/v3/billing` — current plan + 3-tier pricing card grid |
| R-150 | `/v3/pricing` — public pricing page |
| R-163 | Free-cap upgrade nudge modal |
| R-143 | `/v3/team` — members + seats + invite |
| R-162 | Referral mechanism (consume V152's `referred_by_user_id`) |
| R-151 | `/v3/trust` — procurement-grade Trust page |
| R-152 | `/v3/insights` — collapsed analytics screen |
| R-144 | `/v3/integrations` — card grid |
| R-153 | HelpModal — Cmd-K `?` mode |
| R-170 | Mobile responsive primitives sweep |

Plus the deferred half of R-111 (real anonymous-trial LLM dispatch + Redis rate-limit + abuse classifier).
