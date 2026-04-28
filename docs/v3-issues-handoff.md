# v3-issues handoff for fresh thread (post-shipped state · 2026-04-28)

## TL;DR

Two batches shipped end-to-end this session:

  1. **4-concern fix batch** (root-route + remove-What's-new + remove-
     changelog + email-sweep) — GH Action `25043079648` green at T+374s,
     Cloudflare redeploy `8c205e26`.
  2. **R-190 + R-191 post-cutover cleanup** — Vibe UI surface deleted +
     Mission/Specify vocabulary purged. GH Action `25047545954` green at
     T+382s.

`main` FF'd to dev after each. **`origin/dev` = `origin/main` =
`6eee94fe`.** No work in flight; clean state for the next thread.

## Branch + worktree state

| Field | Value |
|---|---|
| Working branch | **`feature/v3-conversion`** |
| Worktree path | **`/Users/navneetprabhakar/Documents/antigravity/executespec/.claude/worktrees/cranky-herschel-4d02cf/`** |
| `origin/feature/v3-conversion` tip | **`6eee94fed32796bc71e1db485b0c52e5638b0a6a`** |
| `origin/dev` tip | **`6eee94fed32796bc71e1db485b0c52e5638b0a6a`** ← matches feature |
| `origin/main` tip | **`6eee94fed32796bc71e1db485b0c52e5638b0a6a`** ← matches dev (FF'd this turn) |
| Local commits ahead of origin | **0** |
| Uncommitted edits | **none** (only `src/main/resources/static/trust-pack.pdf` untracked — Vite build artifact, ignore) |

## What shipped this session

### 4-concern batch (2026-04-28)

| # | Concern | Commit | Status |
|---|---|---|---|
| 1 | Root route → `/v3/landing` for anon (funnel fix) | `12d59e18` | ✅ shipped + verified live |
| 2 | Remove "What's new" surface (R-181 TourBanner) | `c260d3fc` (bundled) | ✅ shipped + verified live |
| 3 | Remove `/v3/changelog` page | `c260d3fc` (bundled) | ✅ shipped + verified live |
| 4 | Sweep visible emails → `admin@executespec.ai` | `c260d3fc` (bundled) | ✅ shipped + verified live (dev backend + Cloudflare) |
| meta | Premature handoff doc (overwritten by this version) | `50024fb4` | superseded — this file replaces the staleness |

### Earlier this turn (lock-the-rule chore)

| Commit | Subject | Why |
|---|---|---|
| `6903173c` | `chore(scripts): redirect slice-email.py recipient to admin@executespec.ai` | Email-recipient rule lock — automated emails now go to `admin@executespec.ai`. Bundled into the same dev push. |

### R-190 + R-191 post-cutover cleanup (2026-04-28)

| # | Item | Commit | Status |
|---|---|---|---|
| R-190 | Remove Vibe UI surface (post-cutover; backend product-surface arch untouched) | `697e6fb8` | ✅ shipped + verified live |
| R-191 | Purge Mission vocabulary + replace "Don't prompt. Specify." tagline | `6eee94fe` | ✅ shipped + verified live |

**R-190 scope (UI Vibe-A only):** deleted `Vibe*.tsx` + `lib/vibeNarrative.ts`; collapsed `'vibe' | 'pro' | 'project'` ViewMode union to `'pro' | 'project'`; dropped `isVibe` / `vibeRunDefaults` / `escalateToPro` helpers from `useViewMode`; collapsed `isVibe` branches across RunDetail / RunDetailTabs / RunCard / RunList / NewRunModal / RunsPage / DashboardPage; dropped Vibe button from ViewModeToggle; narrowed `lib/api.ts` AuthUser type + added `normalizeAuthUser()` to coerce legacy `view_mode='vibe'` rows to `'pro'` at the parse boundary (called from useAuthStore.login + refreshAccessToken + loadMe — no V145 SQL migration shipped per the explicit "frontend-normalize only" decision). Backend Vibe-B (V143 product_split ENUM, ProductSurfaceFilter, TierProductSurfaceMapper, OAuthSurfaceResolver) intentionally untouched.

**R-191 scope:** `LoginPage.tsx` "Don't prompt. Specify." → "Sign in to ship a spec." + subtitle swapped to v3-aligned "AI-written pull requests…" hero copy. `SharedReportPage.tsx` footer "Don't prompt. Specify." → "AI-written pull requests you can actually merge." `glossary.ts` mission entry replaced with run entry. `ExecSummaryPage.tsx` / `TeamMemberDashboard.tsx` / `ProjectDashboardPage.tsx` "Mission(s)" copy → "Run(s)". Stale historical-context comments in Sidebar / App.tsx trimmed.

### GH Action `25047545954` — final state (R-190 + R-191)

```
status:        completed
conclusion:    success
total runtime: 382 s (6m22s)
all 25 steps:  green (Slack success notify fired)
```

Stage timing matched the prior run within ±10 s on every stage.

### Post-deploy verification (R-190 + R-191) — every probe green

| Probe | Result |
|---|---|
| `/login` contains "Don't prompt" | **0** ✅ |
| `/login` contains "Specify." | **0** ✅ |
| `/login` contains "Sign in to ship a spec." | **present** ✅ |
| `/login` contains verbatim hero "AI-written pull requests" | **present** ✅ |
| ViewModeToggle "Vibe" button on header | **gone** ✅ (toggle is retired from the header anyway per Phase 1.1 #8) |
| VibeProgressNarrative element on run detail | **gone** ✅ |
| `localStorage.es:viewMode='vibe'` after authed page load | **normalized to 'pro'** ✅ |
| `body.textContent` "Mission" hits on / (authed admin) | **0** ✅ |
| Run cards show "Code generated" / "Run failed" copy | confirmed via screenshot ✅ |

### GH Action `25043079648` — final state

```
status:        completed
conclusion:    success
total runtime: 374 s (5m14s)
all 25 steps:  green (no skips on the failure path; Slack success notify fired)
```

Stage timing:
- Build frontend: ~25 s
- Build JAR + tests (702+ green, AsyncAllowlistTest 1/1): ~110 s
- GCP auth + GCS upload: ~50 s
- GCE prep + sync + env patch: ~55 s
- Deploy JAR + JVM startup wait: ~80 s
- Health check: ~26 s
- Cleanup + Slack notify: ~28 s

### Cloudflare deploy

Worker `square-bar-e1dc` (custom domains: `executespec.ai` + `www.executespec.ai`)
- **Pre-batch version:** `30d0d193-3d10-473f-b6ac-3f0696ef4b0c`
- **Post-batch version:** `8c205e26-081b-405d-bd40-0ef3338be085`
- Files updated: `index.html` + `llms.txt` (concern 4 email swaps)
- Deploy duration: ~28 s end-to-end
- Auth: OAuth-token-on-this-machine for `admin@executespec.ai`

### Post-deploy verification (all probes green)

| Probe | Result |
|---|---|
| Anon visit to `https://dev.executespec.ai/` | Final URL **`/v3/landing`** (was `/login` pre-deploy) |
| h1 on root | `"ExecuteSpec ships AI-written pull requests…actually merge — for one repo or for twenty."` (verbatim hero) |
| password field on root | **absent** (was present pre-deploy) |
| TourBanner element on `/v3/landing` | **0** (concern 2) |
| `/v3/changelog` content | falls through to login (anon, unknown route) — does NOT render V3Changelog component |
| `body.textContent` grep "Changelog" / "What's new" | not found (concern 3) |
| Email grep on `/v3/landing` DOM | only `admin@executespec.ai` |
| Email grep on `/v3/trust` DOM | only `admin@executespec.ai` |
| Email grep on `/v3/pricing` DOM | only `admin@executespec.ai` |
| Email grep on `/v3/team` DOM | `admin@executespec.ai` + 4 `@yourco.com` PreviewMembersCard sample roster (leave-classified per audit) |
| `executespec.ai/` (Cloudflare prod) email grep | only `admin@executespec.ai` |
| `executespec.ai/llms.txt` (prod) email grep | only `admin@executespec.ai` |
| Verbatim hero copy on prod | "AI-written pull requests" + "for one repo or for twenty" ≥1 each (preserved) |

### Screenshots

| Path | What |
|---|---|
| `/tmp/concern1-screenshots/before.png` | BEFORE — anon visit to `dev.executespec.ai/` shows the login form (the bug) |
| `/tmp/concern1-screenshots/after.png` | AFTER — anon visit lands on `/v3/landing` with the verbatim hero (the fix) |

## What's still parked (NOT this thread's job)

| Item | Why parked | Re-open trigger / unblock |
|---|---|---|
| **D-125 RepoAttachPopover — 4 product decisions** | User wants to revisit; my recommendations surfaced but not approved | Founder picks an option per question. Recommendations on file: Q1=staged (b)+(c) ship now / (a) defer; Q2=`.executespec/root.yml` marker file; Q3=hard threshold + opt-in toggle; Q4=finish multi-repo project flow (V143/V144 partial). |
| **Anonymous-run real LLM dispatch flag** | User said "ignore for now"; flag stays OFF. Real dispatcher impl + per-call cost cap + aggregate daily cap don't exist. | If founder wants to flip later, ship aggregate count tracker first (S effort) on stub mode for ≥1 week of baseline traffic before the M-L real-dispatch impl. |
| **Hero video recording** (R-204) | Real-asset task — a 30-second screen capture of spec→plan→build→PR→Ready is needed. Reminder set for 2026-05-05. | Drop `hero-demo.mp4` (≤8 MB H.264) at `frontend/public/` + flip `USE_REAL_VIDEO` constant in V3Landing.tsx line 498. |
| **R-313 customer logos** (TrustedByStrip placeholder) | Need a signed customer-permission + a logo SVG/PNG | Edit slot config in `V3Landing.tsx` TrustedByStrip section — 5-min change. |
| **R-318 Cal.com URL** for Enterprise demo CTA | Mailto fallback ships today; Cal.com account setup is the unblock | 1-line href swap on `PricingGrid.tsx` line 140 — change `mailto:admin@executespec.ai?subject=...` → real Cal.com URL. |
| **R-319 SOC 2 ETA copy** | Withdrawn 2026-04-28; "Not yet certified" line is live | When a real audit engagement is signed → re-issue with actual auditor name + completion month. |
| **SSO config screen** on `/v3/team` | V127 schema in place; UI deferred until live IdP for round-trip QA (Okta / Auth0 tenant) | Live IdP test tenant + ~30-min click-through QA. |
| **R-141 GST tab** on `/v3/billing` | Needs `org.country` field on `/api/v3/entitlements`; backend has no country field today | Decide country source (Stripe / self-declare / Geo-IP) + 1-row migration + 5-line entitlements DTO patch + flip `showGst` to `org.country === 'IN'`. |

## Post-UX scope queued (memory file `executespec_post_ux_scope.md`)

The user's persistent memory holds queued future scope: **CLI / agent
builder / readymade solutions / plugins / pre-built use cases.** All
of these are deliberately deferred until the UX-conversion arc closes.

## Standing rules + invariants

- **Branch:** `feature/v3-conversion` is the working branch for v3 work. **No PR opens. Push enabled.**
- **Cohabitation:** keep new work under `frontend/src/v3/`. v2 untouched **except** sanctioned shared-source edits (per the strict-UX handoff): `EventName.java` (additive enum), `SecurityConfig.java` (one `permitAll`), `V2RunOrchestrator.java` (R-162 referral wire-point), and now `ProtectedRoute.tsx` (concern 1 root-route special-case — invariant-preserving change since v2 routes still hit `/login` as before).
- **Per-3-items checkpoint:** commit + push + dispatch update.
- **Each commit:** `feat(v3):` / `fix(v3):` / `chore(v3):` + concise subject + co-author footer.
- **Verify before push:**
  - `tsc --noEmit` after frontend changes
  - `mvn -q -DskipTests compile` after backend changes
  - Full `mvn test` between batches (NOT skipped — AsyncAllowlistTest is sensitive to new `@Async` methods + must be in sync; failure cascades the GH Action deploy)
- **Stop only for:** real product decision the user must make / item turns out already done / red tests / hard blocker.

## Email rules (in memory file `executespec_email_recipient.md`)

- **Automated email TO field:** `admin@executespec.ai` (was `navneet.prabhakar007@gmail.com` until 2026-04-28).
- **Visible website + in-app emails:** `admin@executespec.ai` (concern 4 from this batch — fully shipped + verified).
- **SMTP creds:** Gmail app password from `src/main/resources/application-dev.yml` (`app.mail.smtp.password`). Sender FROM is always `admin@executespec.ai`.
- **In-repo sender script:** `scripts/slice-email.py` — `RECIPIENT` constant updated in commit `6903173c`.

## Stack URLs / ports

| Surface | URL | Notes |
|---|---|---|
| Local backend | `http://localhost:8081` | Spring profile `local` — `mvn spring-boot:run` |
| Local frontend | `http://localhost:5173` (Vite default) | `npm --prefix frontend run dev` |
| Local static website | `http://localhost:8989` | Optional, was used in F1 sanity walks |
| Deployed dev | `https://dev.executespec.ai` | deploys via push to origin/dev → `deploy.yml` GH Action |
| Production website | `https://executespec.ai` | Cloudflare Worker `square-bar-e1dc` — separate `wrangler deploy` from worktree |

The local stack from earlier in the session was likely killed when the user closed terminal sessions. Bring back up if needed:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run -Dspring-boot.run.profiles=local
npm --prefix frontend run dev   # other terminal
```

## Test users

| Email | Password | Tier | Role | Where |
|---|---|---|---|---|
| `admin@executespec.ai` | `REDACTED_FOR_PUBLIC_RELEASE` | ENTERPRISE | SUPER_ADMIN | **dev.executespec.ai** (note: `REDACTED_FOR_PUBLIC_RELEASE` — three not four) |
| `admin@executespec.ai` | `REDACTED_FOR_PUBLIC_RELEASE4` | ENTERPRISE | SUPER_ADMIN | LOCAL stack only — different password |
| `freshux-{free,pro,team,enterprise}@test.local` | `FreshUx@1234!` | various | various | local-stack tier-test users |

## Deploy protocol (recap)

Push to `origin/dev` automatically fires `deploy.yml` GH Action. Stage order:

1. Set up job / Checkout / Java 21 / Node.js
2. **Build frontend** (Vite → `src/main/resources/static/`)
3. **Build JAR + run unit tests** (702+ expected; `AsyncAllowlistTest` is sensitive — every new `@Async` method must be in the allowlist or the build fails)
4. Authenticate to GCP / gcloud CLI / GCS upload
5. **Deploy JAR to GCE (dev)**
6. **Wait for app startup** (Flyway migrations apply here; if a migration fails the JVM never comes up)
7. **Health check**

Last successful runs:
- `25043079648` (this batch, 374 s, 2026-04-28T08:51:18Z)
- `25040076734` (prior, 355 s, 2026-04-28T07:40:49Z)

`main` is now aligned with dev (FF'd this turn). `main` is not on the deploy path — `deploy.yml` only fires on push to `dev`. `main` lagging dev was the standing concern; now resolved.

## Memory references

In `/Users/navneetprabhakar/Library/Application Support/Claude/.../memory/`:
- `executespec_post_ux_scope.md` — queued future scope (CLI / agent builder / ready-mades / plugins / pre-built use cases)
- `executespec_email_recipient.md` — both email rules above (TO field + visible website)

## Recent gotchas / state-at-handoff

- **Parent repo branch.** `/Users/navneetprabhakar/Documents/antigravity/executespec/` was on `feature/event-tracking-jsonb-fix` earlier today, which caused a stale Cloudflare deploy (pushed pre-Wave-5 website content). The previous thread switched it to `dev` so it's safe for direct-from-parent deploys at `27e468c8` matching dev tip at the time. **Always deploy Cloudflare from the worktree path** to be safe — that's where the live edits land. (Today's Cloudflare deploy was from the worktree.)
- **`src/main/resources/static/trust-pack.pdf`** — untracked Vite build artifact in the worktree. Vite re-creates it on every `npm run build` (it's the R-320 Trust Pack PDF copied from `frontend/public/trust-pack.pdf`). Don't commit it; it's regenerated.
- **Wrangler auth** is OAuth-based on this machine for `admin@executespec.ai`. Permissions include `workers:write`. To re-deploy: `cd .../worktrees/cranky-herschel-4d02cf/website && npx wrangler deploy`.
- **`docs/pre-deploy-closeout.md`** is the pre-batch close-out doc from earlier this morning. The 13 deferred items there are still deferred (this 4-concern batch didn't touch any of them); R-319 SOC 2 stays withdrawn; R-313 / R-318 placeholders preserved.

## Done — what the next thread can pick up cleanly

The branch + dev + main + Cloudflare are all aligned and live. The work-list in this handoff doc is **what's parked**, not **what's left to ship**. Pick from "What's still parked" only when the user asks for it.

The most likely next ask is one of:
- D-125 product decisions (4 questions — recommendations awaiting nod)
- Hero video drop (when the recording is captured)
- Real customer logo / Cal.com URL swaps (1-line edits when assets land)
- Post-UX scope (CLI / agent builder / etc.) — fresh build-out, separate arc

If the next ask is a brand-new concern, follow the standing protocol:
1. Branch stays `feature/v3-conversion`
2. Per-3-items checkpoint commits + push
3. tsc + mvn compile + mvn test before push
4. FF dev → watch deploy.yml → post-deploy verify
5. Update this doc when the new batch ships
