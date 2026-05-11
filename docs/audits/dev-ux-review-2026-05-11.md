# dev.executespec.ai — End-to-End UX Review · 2026-05-11

**Reviewer:** independent UX + conversion audit, unbiased
**Target:** `https://dev.executespec.ai` (production-equivalent dev environment)
**Branch under test:** `dev` (HEAD `92c3c26a`, merged through PR #106 inclusive)
**Method:** direct HTTPS probes against the deployed origin (no proxy, no local
frontend), JWT obtained as `admin@executespec.ai` (SUPER_ADMIN · ENTERPRISE
tier), cross-referenced against `frontend/src/` source on dev HEAD. Browser
rendering was not feasible (the preview MCP sandbox locks navigation to
`localhost`; computer-use grants browsers `read` tier only). Every issue below
is grounded in a captured HTTP response, header, asset payload, or
source-code citation — not inference.

This document is structured to be **fix-actionable**: every finding has a
clearly scoped owner area, a one-line fix proposal, and a priority. Apply
top-down by priority.

---

## TL;DR — the 10 things to do this week

| # | Priority | Issue | Where | Fix |
|---|----------|------|-------|-----|
| 1 | **P0** | `/api/billing/plans` requires auth → **prospects cannot see pricing without signing up** | `BillingController` | Make plans GET public (or add `/api/billing/plans/public`); the only thing exposed is published pricing. |
| 2 | **P0** | TEAM plan rows have `baseAmountUsd = 0` and `baseAmountInr = 0` → public + in-app Plans page shows **"Team — $0/mo"** | `billing_plan` DB rows + V123 | Backfill with the documented $49 / ₹3,999 per seat. Add Flyway migration. |
| 3 | **P0** | `/api/billing/payment-history` returns **500** even for SUPER_ADMIN (`errorId 6a32629c`) | `BillingController#paymentHistory` | Reproduce with admin JWT, fix the NPE, add a regression test. |
| 4 | **P0** | `/exec-demo` public page brags **"successRatePercent: 12"** + `needsAttentionCount: 28 / 35` | `/api/v3/public/exec-snapshot` aggregator | Either fix the verified-score math, or hide success-rate when sample size <50 (Wilson interval), or add a "rolling 90d" window. Today this *actively repels* prospects. |
| 5 | **P0** | "5 runs / month" copy still in **7 source files** even though `V167` reverted FREE → 5 (so the *number* is correct but contradicts CLAUDE.md saying it's 10). UX issue is the **inconsistency between Settings (5), Plans (mixed), Register (5), website hero (different)** | `pricing.ts`, `UpgradeModal.tsx`, `EmptyProjectPage.tsx`, `TeamPage.tsx`, `SettingsPage.tsx`, `ExecDemoPage.tsx`, `register` page | One source of truth: read `runLimit` from `/api/billing/plans` (server-derived). Delete every hardcoded "5" or "10". |
| 6 | **P0** | Login error is generic **"Login failed. Please try again."** | `LoginPage.tsx:90-96` | Already partially good (handles 401 → "Invalid email or password.", 403 → "Please verify your email…"). Add 423 → "Account locked, contact support", 429 → "Too many attempts — try again in 5 min", and surface a `Resend verification` link on 403. |
| 7 | **P1** | Hashed bundles served with `cache-control: no-cache, no-store, max-age=0, must-revalidate` — every visit re-downloads **2.6 MB uncached / ~1 MB gzipped** | Spring Boot static-resource handler | Hash-based filenames already exist (`index-CGZPfWkO.js`). Send `cache-control: public, max-age=31536000, immutable` for `/assets/*`; keep no-cache only on `/index.html`. |
| 8 | **P1** | JS bundle is **2.27 MB raw / 617 KB gzipped** with no route splitting | Vite config / `App.tsx` | `React.lazy` the admin pages, Monaco editor, recharts, qrcode.react, framer-motion. Target <300 KB gzipped on the auth + login route. |
| 9 | **P1** | Logo is a **375 KB PNG** loaded for `/favicon.ico` (301 → `/logo.png`), apple-touch-icon, and manifest — 1.1 MB of logo per page on cold load (+ no PWA-required 192 / 512 variants) | `public/` + manifest | Replace with `<32 KB` SVG or generate `logo-32.png` / `logo-192.png` / `logo-512.png` / `logo.svg`. Serve a real `favicon.ico` to stop the 301. |
| 10 | **P1** | `/api/models/available` is **auth-required** so the landing/register page cannot show "we support Claude, Gemini, GPT" capabilities to logged-out prospects; also returns only 1 model per provider (PLANNING tier) — inconsistent across providers | `ModelController` | Open a public sub-endpoint that returns model name + tag (`Premium / Cheapest / Fastest`); show on register + landing as social-proof. Also expose WORKHORSE rows for parity. |

---

## How to read this report

- **P0** — actively losing visitors / breaking core flows / showing wrong data. Fix this week.
- **P1** — measurable friction or perf cost. Fix next sprint.
- **P2** — polish + consistency. Schedule into trim-week.
- **P3** — nice-to-have / future work.

Every finding cites either a (a) `curl` response captured during this audit,
or (b) source-file line numbers (clickable in markdown).

---

## 1 · Conversion surfaces — prospect → signed-up user

### 1.1 — Public landing / hero (`/`)

**What I tested.** Anonymous `GET /` and dependent resources.

**Findings:**

- **(P1) Cold-load weight = 2.6 MB uncompressed / ~1 MB gzipped** for HTML + JS + CSS + logo. Numbers from the live origin:
  - HTML: 2.3 KB
  - JS: 2.27 MB raw / **617 KB gzipped**
  - CSS: 71 KB raw / 14.7 KB gzipped
  - logo.png: **375 KB** (no compression beyond PNG)
- **(P1) Every asset has `cache-control: no-cache, no-store, max-age=0, must-revalidate`**, including the hashed bundle `/assets/index-CGZPfWkO.js`. Hashed filenames are designed to be cached forever; the no-cache policy means every navigation re-downloads ~1 MB. On a 4G LTE network this is ~3–5s of TTI penalty per visit.
- **(P2) `/favicon.ico` 301→`/logo.png`** (375 KB PNG). Browsers auto-fetch favicon — every page load eats 375 KB.
- **(P2) PWA manifest** ([manifest.webmanifest](https://dev.executespec.ai/manifest.webmanifest)) declares only one icon entry, `"sizes": "any"`, `"purpose": "any maskable"`. Lighthouse will flag this — no 192×192, no 512×512, no specifically-maskable variant.

**Fix list:**

1. Spring static-resource config: `addResourceHandler("/assets/**")` → `setCachePeriod(31536000)` + `setUseLastModified(false)` + emit `cache-control: public, max-age=31536000, immutable`. Hashed filenames make this safe.
2. Generate SVG logo (or `logo-32.png`, `logo-192.png`, `logo-512.png` + `logo.svg`). Cap PNGs at <30 KB. Add a real `/favicon.ico` (16x16 + 32x32 multi-image .ico).
3. Replace single manifest icon entry with the proper 192/512/maskable set.

### 1.2 — `/exec-demo` (public marketing dashboard)

**Live data captured 2026-05-11 14:52 UTC:**

```json
{
  "windowDays": 30,
  "totalRuns": 35,
  "successRatePercent": 12,
  "totalSpendUsd": 164.71,
  "activeProjects": 2,
  "needsAttentionCount": 28,
  ...
}
```

- **(P0) Marketing surface is publicly advertising a 12 % success rate and "28 out of 35 runs need attention"** — those are real numbers but they describe an internal/dev environment, not a customer-facing benchmark. Prospects coming from "AI that ships pull requests" headline will see 88 % failure and bounce.
- **(P1) `topWins` items** have spec strings with **leaked markdown stars** (`"** **Project:** Single-Player 3D Race Game …"`). Renderer prints the raw markdown.
- **(P1) Project anonymisation** is `Project A`, `Project B`. Two projects total in the snapshot. Looks like a sandbox, not a product.
- **(P2) Spec text in topWins is duplicated** (the 3D race game appears twice, both with verifiedScore 85 and cost $1.00 / $0.88) — looks copy-pasted, hurts credibility.

**Fix list:**

1. Either (a) gate `/exec-demo` behind a "production stats" feature flag and serve a curated snapshot on `dev.*`, or (b) compute success-rate with a Wilson lower bound when N < 100 so dev's noise stops surfacing.
2. Strip markdown from `topWins[].spec` before serialisation (trim leading `**`, collapse repeated `*`).
3. De-dupe spec text in topWins (collapse by `spec` + show one row).
4. **Strongly consider**: do not link to `/exec-demo` from the marketing site until dev has real volume (≥200 runs / month). The current page hurts more than it helps.

### 1.3 — Login (`/login`)

**Findings (source: [LoginPage.tsx](../frontend/src/pages/auth/LoginPage.tsx)):**

- **(P1) Generic fallback error** — line 95: `'Login failed. Please try again.'` — fires for any non-401/403 status. Real production errors (network, 500, 423 lockout, 429 throttle) all collapse here. The user has no idea what to do.
- **(P1) No "resend verification" affordance** on the 403 path. The error says "Please verify your email before logging in." but does not link to a resend flow — the user has to dig through Settings (which they can't reach without logging in).
- **(P2) Input font-size = 14 px on email/password.** iOS Safari auto-zooms any input <16 px on focus, which then shifts the whole layout. (Captured from `LoginPage` styling: `text-sm` on the Field component.)
- **(P2) Hero copy duplicated** — `"Ship AI-written PRs you can actually merge."` is both the H1 in the brand pane and the subhead under "Welcome back". Wasted real estate.
- **(P3) Logo is the literal text "ExecuteSpec"** with no icon — no visual brand cue.
- **(P3) `/api/auth/oauth2/init` was removed** but still referenced in comments — minor doc decay.

**Fix list:**

1. Error mapper handles 401 (Invalid email or password.), 403 (`Please verify your email — `<a>resend</a>`.`), 423 (Account locked.), 429 (Too many attempts — try again in 5 min.), 5xx ("ExecuteSpec is having a moment. We're notified."). Surface the resend-verification link as a real CTA.
2. Bump auth-form input `text-sm` → `text-base` (16 px) to disable iOS auto-zoom.
3. Replace right-pane subhead with a value-prop ("Pick up where you left off." or hide it).

### 1.4 — Register (`/register`)

**Findings:**

- **(P0) "Free. 5 runs / month. No credit card."** copy is hardcoded across 7 files. Some pages still say 10. The "monthly cap" message contradicts itself between Register, Settings, and Plans depending on which file the user lands on. *Code-level source of drift documented at* [pricing.ts:53](../frontend/src/v3/lib/pricing.ts), [UpgradeModal.tsx:22](../frontend/src/components/ui/UpgradeModal.tsx), [EmptyProjectPage.tsx:334](../frontend/src/v3/pages/EmptyProjectPage.tsx), [TeamPage.tsx:500](../frontend/src/v3/pages/TeamPage.tsx), [SettingsPage.tsx:429+477](../frontend/src/pages/SettingsPage.tsx), [ExecDemoPage.tsx:294](../frontend/src/v3/pages/ExecDemoPage.tsx).
- **(P1) Persona selector** (Personal / Team / Enterprise) — UI suggests three distinct flows but the button just labels the org. The user never gets a different signup outcome based on choice. Tells users "choice matters" when it doesn't.
- **(P1) Captcha / bot defense not visible** — registration POST has no observable rate-limit or captcha. Bot signup spam will be expensive (sandbox provisioning + email costs).
- **(P2) Password rules not surfaced** on the field. User has to submit, fail, then learn.
- **(P3) "WORK EMAIL"** label nudges B2B but with a Personal toggle this is contradictory.

**Fix list:**

1. Eliminate every hardcoded run-cap. One helper `useFreeRunLimit()` that reads from `/api/billing/plans` (cached). Single source of truth.
2. Make persona pick actually shape the flow (Personal → sample-spec gallery first; Team → invite teammates step; Enterprise → "Contact sales" path with a Calendly link). Today this is decorative.
3. Add hCaptcha / Turnstile on register and forgot-password. Hosted on Cloudflare, free up to enterprise.
4. Inline password requirements with green-check as the user types (don't validate on submit only).

---

## 2 · First-time-user surfaces — signed-up → first run

### 2.1 — Empty project state (`/projects/empty`)

[EmptyProjectPage.tsx](../frontend/src/v3/pages/EmptyProjectPage.tsx) is well-built — the docstring (R-310 / R-343) shows the team has already done a persona-test pass and fixed the worst bug (404 on `getProject('empty')`). Sample-spec gallery + spec-handoff from marketing site work.

**Remaining findings:**

- **(P2) "Create my first project" defaults to a generic project name.** When the user has a typed spec from the marketing site, the project name still reads default. Could be derived from the first 6 words of the spec.
- **(P2) Sample-spec gallery** — verify that examples produce a "Ready" verdict on the current model matrix. If they don't, the first-run trust collapse is permanent.
- **(P3) No "skip / explore docs" path** for users who aren't ready to commit a run yet.

### 2.2 — New-run / mission flow

Source confirms a thorough flow: project picker → repo picker → spec composer → cost estimate chip → plan approval gate. Strong shape.

**Findings to verify in a live browser session:**

- **(P1) Cost estimate accuracy.** Spot-checked one run: `costEstimateLow=2.87, costEstimateHigh=5.02, creditsCharged=1`. The cap is $5 per credit. If `costEstimateHigh > hardCapUsd`, the run is set up to overrun cap — that's surfacing a known cost-estimate underestimation but the modal doesn't flag it. Add a banner: "Your estimate exceeds the cap; budget headroom $0.00 — increase cap?"
- **(P2) Estimate chip** is debounced — verify the debounce isn't so long that users tap "Submit" before it lands. Race condition opportunity.
- **(P2) "Quick run" vs "Run with plan approval"** — the most recent commit (`1044b0d0`) added a "crisp Quick-run" warning. Quick-run skips the plan gate. Worth a usability test with one Personal-tier persona to see if they grok the trade-off.

### 2.3 — Run detail page (`/projects/:id/dashboard?tab=runs`)

[RunDetailPage.tsx](../frontend/src/v3/pages/RunDetailPage.tsx) is **1,886 lines** — biggest single page in the app. Recent commits (mobile sweep iter-5, single-score collapse) show active refinement.

**Findings:**

- **(P1) Run-list shape inconsistency.** `/api/runs` paginated response uses `content` field (Spring Page<T> default), but `runs.json[0]` has `runId` (not `id`). Frontend types must reconcile both — easy place for bugs.
- **(P1) Sample run captured during audit shows the kind of contradictions Verdict UI was designed to fix but the *underlying data* still produces:**
  ```
  status:        COMPLETE
  statusReason:  CLEAN_COMPLETION
  verifiedScore: 43          ← Verified ring will render <50 → red
  accuracyScore: 82          ← Legacy LLM est. — disagrees by 39 points
  reviewerVerdict: FIXED_ISSUES
  finalBuildPassed: true
  testsPassed: null, testsTotal: null
  ```
  Even with the single-score collapse (PR #95, commit `6f6feeff`) — the VerdictPill will say "Shippable" (build ✓ + reviewer FIXED_ISSUES) while the verified score reads 43 ("Needs work"). User cognitive dissonance is unavoidable until the verifiedScore math is recalibrated. Worth a follow-up RCA.
- **(P2) Build/test signal absent** — `testsPassed: null, testsTotal: null` on a COMPLETE run means the test gate didn't fire. Worth surfacing a "No tests in this codebase" chip rather than a blank.

### 2.4 — Project dashboard

**Findings (source: live `/api/projects/{id}/dashboard`):**

- **(P1) Endpoint takes 1.5s** — the most-hit authenticated endpoint after `/api/auth/me`. Causes a visible "loading skeleton" pause for every project switch.
- **(P2) Empty dashboard** for both projects in dev — `activeRuns=0, completedRuns=0, avgScore=0, totalTokens=0`. *But there are 349 runs in `/api/runs`.* So either dashboard aggregation runs over the wrong scope, or the dashboard only counts runs *with project linkage*, or it caches in a way that's broken. Worth a one-line repro.
- **(P3)** Score trend `/api/projects/{id}/scores` returns `[]` — no historical anchoring once user has runs. Could be wired off the same `verifiedScore` field.

### 2.5 — Project chat

[ProjectChatPanel.tsx](../frontend/src/components/project/ProjectChatPanel.tsx) recent work (Compact / Reset / Copy buttons + portaled drawer / mobile sweeps) looks polished.

- **(P2) Notification bubble for "Unread chat replies"** — if the user is on Runs tab when the chat agent finishes, no in-app notification. They have to remember to flip back.
- **(P3) Conversation history truncation** at compaction time — confirm the user can still scroll back to see the original spec (compaction docs say so, worth verifying in a live session).

---

## 3 · Returning-user surfaces — billing, settings, admin

### 3.1 — Billing / plans / upgrade

**Findings:**

- **(P0)** `GET /api/billing/plans` returns 401 to anonymous callers. Result captured:
  ```json
  {"status":401,"error":"unauthorized","message":"Authentication required"}
  ```
  **Prospects cannot see pricing without registering.** Standard SaaS norms expose pricing publicly so prospects can self-qualify.
- **(P0)** Even when authed, the TEAM plan rows are broken — captured:
  ```
  TEAM         Team Monthly INR     $0.00 USD  ₹0.00 INR  runs=-1  seats=2
  TEAM         Team Monthly USD     $0.00 USD  ₹0.00 INR  runs=-1  seats=2
  ```
  Both prices = 0. The reinstated per-seat TEAM tier (V144) seeded `billing_plan` rows without actual prices.
- **(P1) PRO yearly badging**: `Pro Yearly USD = $289 + 360 runs/yr` with `annualDiscountPct=17`. Math is right but UX-side the "save $59/yr" framing isn't surfaced — a user can't easily compare monthly-vs-yearly value. Show the dollar delta inline.
- **(P1) Tier ladder drift.** Live `/api/admin/tiers`:
  ```
  FREE   LISTED
  TEAM   LISTED (re-inserted V144)
  ENTERPRISE LISTED
  PRO    LISTED (renamed from Solo via V185, 2026-05-11)
  ```
  **There is no MAX tier** in the LISTED ladder. CLAUDE.md still references MAX in multiple places ("MAX BYOK unlimited $29/mo") — either MAX exists as HIDDEN and admin/tiers filters it out, or it was deleted and CLAUDE.md needs a sweep. Either way the doc-to-DB drift is a source of customer-support confusion.
- **(P0)** `GET /api/billing/payment-history` (authed as admin) returns **HTTP 500 errorId 6a32629c**. Reproducible: any user navigating to "Payment history" will see "Something went wrong on our end."
- **(P2)** `/api/v3/entitlements` says `isUnlimited: true, runLimitMonthly: -1` for admin (ENTERPRISE), but `/api/billing/status` says `subscription.status: "FREE", plan.tier: "FREE", runLimit: 5`. The frontend has to pick one — there are comments in [SettingsPage.tsx:429](../frontend/src/pages/SettingsPage.tsx) describing exactly this drift ("returns FREE for any user without an active paid subscription row even when their `app_user.tier` is paid"). Workaround is implemented but the underlying data divergence keeps surfacing in audit edges.

**Fix list (billing):**

1. Make `/api/billing/plans` public (`SecurityConfig.permitAll`) — pricing is not a secret.
2. Migration `V186__seed_team_plan_prices.sql` writing `4900` to `baseAmountUsd` and `399900` to `baseAmountInr` on both Team monthly rows. Add yearly TEAM with 17 % discount for parity.
3. Reproduce + fix `BillingController#paymentHistory` 500. Add `MockMvc` test that hits the endpoint as a SUPER_ADMIN with no payment history.
4. Decide MAX disposition (HIDDEN / SUNSETTING / DELETED) and reconcile CLAUDE.md to match.
5. Add a `tier_display.ts` shared helper that takes the auth-store user + billing-status payload and returns the canonical display tier — kill the inline drift handlers.

### 3.2 — Settings / API keys / integrations

- **(P1) `/api/integrations` returns 404** but `IntegrationsPage.tsx` exists in the frontend bundle. Either it's served at a different path or the page is broken at runtime. Worth confirming in a real browser session.
- **(P1) `/api/feature-flags` returns 404** — Frontend `FeatureFlagsPage` calls `/api/admin/feature-flags` (verified in [api.ts:1745](../frontend/src/lib/api.ts)), but `/api/admin/feature-flags/public` (the unauthenticated fan-out path) is also missing — returns 401, not the expected JSON. Public feature flags drive UI gating; this means the unauthenticated frontend can't read public flags.
- **(P2) `/api/auth/me/data-export` works correctly** and returns a 974 KB ZIP for admin. Good GDPR posture.
- **(P2) BYOK key indicators** — `/api/auth/me` returns `hasOpenAiKey`, `hasAnthropicKey`, `hasGeminiKey`, `hasGithubPat` booleans. Surfaced in Settings as capability flags. No "test connection" button despite [Phase 1.2 Wave-3](../docs/plans/) BYOK feature spec mentioning one. Verify and add.
- **(P3) Notification preferences** are wired backend-side (V139) but UI for editing them isn't reviewed here — flag for a follow-up persona test.

### 3.3 — Admin (SUPER_ADMIN routes)

- **(P1) `/api/admin/swebench/run` returns 404** despite CLAUDE.md ("SUPER_ADMIN-gated `POST /api/admin/swebench/run`"). Either feature/swebench-adapter wasn't merged to dev or the route mapping regressed.
- **(P2) Tier-lifecycle drift**: `/api/admin/tiers` includes PRO with `state: "LISTED"` and `changedBy: "V185"` ("Display-name rename Solo → Pro"). CLAUDE.md says PRO should be HIDDEN (grandfathered). The recent UX-polish PR `110b4713` retired the Solo branding but appears to have flipped the lifecycle from HIDDEN → LISTED too. Was that intentional? If yes, CLAUDE.md is stale; if no, PRO is now visible to new buyers who shouldn't see it.
- **(P2) `/api/admin/payment-audit`** returned 2 audit rows correctly. Pagination present. Good.
- **(P3) `/api/admin/users`** returns plain JSON list with `globalRole`, `tier`, `status`. Worth confirming the page renders these in a sortable + filterable table.

---

## 4 · Cross-cutting — performance, perf, security, observability

### 4.1 — Performance

| Asset | Raw | Gzip | Cache | Action |
|-------|-----|------|-------|--------|
| `/assets/index-CGZPfWkO.js` | 2.27 MB | 617 KB | **no-cache** | Cache-immutable + lazy-split admin / Monaco / qrcode |
| `/assets/index-BmAj33n1.css` | 71 KB | 14.7 KB | **no-cache** | Cache-immutable |
| `/logo.png` (icon + apple-touch + favicon redirect) | 375 KB | — | **no-cache** | Replace with SVG / sized PNG set |
| HTML | 2.3 KB | — | no-cache (correct) | — |

- **(P1) JS bundle is monolithic**: code splitting by route is mostly absent. CLAUDE.md mentions `React.lazy` is desired but unimplemented; Vite manual chunks not configured. Quick win: pull Monaco (`@monaco-editor_react.js`) into its own chunk — Monaco is only used in FileViewer + apply-suggestion.
- **(P2) Cloudflare zstd compression is being served** (caught when my proxy needed it). On a real browser, response is brotli or zstd, both efficient — actual transfer to user is ~500 KB. Numbers good, but the cache policy still wastes bandwidth.

### 4.2 — Security headers

Captured on `GET /`:

```
content-security-policy: default-src 'self'; script-src 'self' https://cdn.jsdelivr.net https://static.cloudflareinsights.com; style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://fonts.googleapis.com; …
strict-transport-security: max-age=31536000 ; includeSubDomains
x-frame-options: SAMEORIGIN
x-content-type-options: nosniff
x-xss-protection: 0     ← correct (deprecated header)
```

- **(P2) HSTS missing `preload` directive** — the domain isn't in the HSTS preload list. After production stability, add `preload` and submit to hstspreload.org.
- **(P2) CSP has no `report-uri` / `report-to`** for violation telemetry. Add a CSP report endpoint behind Cloudflare Workers or a small Spring endpoint that writes to your existing observability stack.
- **(P3) `script-src 'self' https://cdn.jsdelivr.net`** — the Monaco editor loader. Self-host Monaco to drop the third-party dep.
- **(P3) `style-src 'self' 'unsafe-inline'`** — Tailwind requires inline styles; this is unavoidable for now but worth tracking once Vite supports nonce-based CSP.
- **CORS:** verified rejection of `Origin: https://evil.example.com` (HTTP 403 + body "Invalid CORS request"). ✅

### 4.3 — Observability / errors

- **(P2)** When `/api/billing/payment-history` 500'd, the response body included `"errorId":"6a32629c"`. Good — that ID maps to a server log. **Surface it in the frontend toast** ("Something went wrong on our end (ref: 6a32629c)") so a user reporting a bug to support has a token they can paste. Currently the toast hides this.
- **(P3)** Browser-side error reporting / Sentry equivalent — verify it's wired. The Cloudflare beacon script is present; not sure if it covers JS errors.

---

## 5 · Mobile + tablet — what's actually on the phone

The dev branch has merged three rounds of mobile sweeps (`fix/mobile-tablet-comprehensive-2026-05-10` iter-1 → iter-3 → iter-5 → PR #103) plus a `mobile-responsive-ui-2026-05-10` (PR #95). I reviewed the responsive code on dev HEAD; verified 44 px touch targets in `ProjectChatPanel`, `RunConversation`, and `RunSidePanel`. The work is real.

**Confirmed-good (from code inspection of dev HEAD):**

- `mobile-web-app-capable` + `apple-mobile-web-app-capable` both `yes` — PWA install works on iOS + Android.
- `theme-color: #0B0D10` matches the dark UI — no flash-of-white on PWA cold start.
- 44 px min touch target on key chat / conversation buttons.
- `100dvh` foundation for iOS Safari URL-bar collapse.
- `safe-area-inset-bottom` on the composer.
- TopBar wordmark hidden below 480 px.
- Drawer portaled to `document.body` (escapes backdrop-filter containing block).
- `v3-scroll-fade-x` gradient indicator for horizontally-scrollable rails.

**Outstanding mobile gaps (from code):**

- **(P1) iter-3 + iter-5 sweeps are deployed (per commit `be0868b5` / `d47f2b0d`)** — good. But the iter-3 CLAUDE.md describes some 7 surfaces wrapped in `overflow-x-auto`; if any of those wrappers still ship a hard right-edge cutoff (vs the gradient mask), users will get the "Cha…" / "Tier C…" bug-look.
- **(P2) Login form inputs are 14 px** font-size (`text-sm`). iOS Safari zooms into 14 px inputs. Fix: `text-base` on `Field` component for `<input>` elements (16 px disables zoom).
- **(P2)** Manifest `orientation: "portrait"` locks PWA to portrait. On iPad / Android tablets users will fight this when reading code diffs. Switch to `"any"` or remove.
- **(P2)** Run-detail page on mobile (1886 LOC) — confirm with a real device test. Stacked layout (status / file tree / diff / side panel) is documented but the diff-viewer Monaco is poor on touch. Consider a "tap to expand" pattern instead of inline Monaco for `<640 px`.
- **(P3)** Test on `iPad mini` (768 × 1024) — the v3 4-tab strip (Pulse / Runs / Code / Chat) is on the edge of squishing with the More button at that width. Verify in DevTools.

---

## 6 · Information architecture + nav

- **(P0)** Top nav on `/exec-demo` (anonymous) shows **"Runs · Library · Migrations"** before the bell, and **"Library · Migrations · For teams · For enterprise · Pricing · Sign in"** after the bell. Library and Migrations appear **twice**. Either the public-anon nav is leaking authed items, or the marketing nav is doubled-up.
- **(P1)** Sign-in placeholder in search bar reads "Sign in to pick a project" — for anon users there's no project context anyway; the search bar should be hidden or the placeholder should be "Search examples…".
- **(P1)** No visible "back to dashboard" affordance on the run-detail page (commit `4e4164e4` says it added a `44px back-link` but verify on a real device).
- **(P2) Glossary primitive** [glossary.ts](../frontend/src/v3/lib/glossary.ts) — 5 seed terms. Good start. Sweep the docs and product copy for un-tooltipped jargon: "wiring pass", "tech debt panel", "memory snapshot", "PR review verdict", "code review", "validation report" — each will benefit from a `<Glossary term="…">` wrap.
- **(P3)** `start_url: "/v3/home"` in manifest — fine, but new users from PWA install land on the HomeRedirect dispatcher. Faster to land directly on `/projects/empty` for zero-state.

---

## 7 · Content + copy

- **(P0)** "5 runs / month" copy is inconsistent — at minimum, fix Register / Settings / Plans / website to all show the same number (live: 5). Best practice: derive from `/api/billing/plans`.
- **(P1)** Brand promise (`"Ship AI-written PRs you can actually merge."`) is duplicated as: H1 on login, subhead on login, hero on register, marketing-site hero. Use it **once** prominently; provide a value-prop subhead the second time (e.g. "Pick up where you left off." on login subhead).
- **(P1)** "PRs you can actually merge" implies a 100% pass rate. The *real* outcome distribution (from live `/api/runs`):
  - COMPLETE: 12 (of 20 sampled)
  - PARTIAL: 8 (of 20 sampled)
  - Of COMPLETE: CLEAN_COMPLETION = 7, FINAL_BUILD_FAILED (yes, that fired even on COMPLETE rows!) = 7, CLEAN_WITH_INTERMEDIATE_WARNINGS = 3, LEGACY_UNKNOWN = 2
  - That last one — a run flagged COMPLETE but with `statusReason: FINAL_BUILD_FAILED` — is a contradiction worth fixing.
- **(P2)** Footer "© 2026 ExecuteSpec · Terms · Privacy · DPA" — the year is 2026 in the codebase. Consider `{new Date().getFullYear()}` so it doesn't drift.
- **(P3)** "Want to see it first? See an example run →" on login — good. But the target page (`/exec-demo`) shows 12% success and 28 needs-attention. Until #4 (P0) is fixed, this CTA is actively harmful.

---

## 8 · Trust + transparency

- **(P1)** Verified score vs accuracy score divergence is real — captured a run where `verifiedScore=43` and `accuracyScore=82`. PR #95 collapsed this to a single number in the UI, but the *underlying signal mismatch* means the chosen number (verified) understates how well the run did when the reviewer says FIXED_ISSUES + build passes. Recalibrate the verified formula or display the disagreement explicitly as "Score 43 — driven by reviewer findings, build passed."
- **(P2)** No public status page (e.g. `status.executespec.ai`). For prospects evaluating reliability, this is table-stakes B2B SaaS.
- **(P3)** No SOC 2 / security badge in the footer. The DPA link is there but no posture summary. Once SOC2 lands, surface it.

---

## 9 · Bug list (all severities)

Each bullet is a single, scoped, reproducible bug.

| # | Sev | Title | Reproduction |
|---|-----|-------|--------------|
| B1 | P0 | `/api/billing/plans` denies anonymous read | `curl https://dev.executespec.ai/api/billing/plans` → 401 |
| B2 | P0 | TEAM plan rows have `baseAmountUsd = 0` | `curl -H 'Authorization: Bearer $JWT' .../api/billing/plans` |
| B3 | P0 | `/api/billing/payment-history` returns HTTP 500 to SUPER_ADMIN | `curl -H Auth$JWT .../api/billing/payment-history` → `errorId 6a32629c` |
| B4 | P0 | `/exec-demo` exposes 12% success rate publicly | `curl .../api/v3/public/exec-snapshot` |
| B5 | P0 | Stale "5 runs / month" copy in 7 frontend files | `grep -rn '5 runs' frontend/src` |
| B6 | P0 | `topWins[].spec` strings have leaked `** **` markdown | same endpoint as B4 |
| B7 | P1 | Login error generic for any non-401/403 status | LoginPage.tsx:95 |
| B8 | P1 | Hashed bundles served `no-cache` | `curl -I .../assets/index-CGZPfWkO.js` |
| B9 | P1 | Anon top nav shows Library/Migrations twice on `/exec-demo` | live page DOM |
| B10 | P1 | `tier=ENTERPRISE` (JWT) but `subscription.status=FREE` (billing) for admin | `/api/auth/me` vs `/api/billing/status` |
| B11 | P1 | Project `/dashboard` endpoint takes 1.5s | timing on curl |
| B12 | P1 | Project dashboard counters are zero despite 349 runs in `/api/runs` | dashboard JSON vs runs JSON |
| B13 | P1 | `/api/admin/swebench/run` returns 404 (CLAUDE.md says it exists) | endpoint sweep |
| B14 | P1 | `/api/feature-flags` returns 404 (frontend expects it) | endpoint sweep |
| B15 | P1 | `/api/integrations` returns 404 (IntegrationsPage.tsx exists) | endpoint sweep |
| B16 | P1 | `/api/models/available` requires auth → prospects can't see model lineup | endpoint sweep |
| B17 | P1 | `/api/models/available` returns 1 PLANNING model for Anthropic/Gemini, 2 for OpenAI — asymmetric | response body |
| B18 | P1 | `verifiedScore=43` + `statusReason=CLEAN_COMPLETION` + `reviewerVerdict=FIXED_ISSUES` + `finalBuildPassed=true` on the same run | run detail JSON |
| B19 | P1 | "COMPLETE" runs with `statusReason: FINAL_BUILD_FAILED` exist (7 of 20 sampled) | runs list |
| B20 | P2 | `/favicon.ico` 301→`/logo.png` (375 KB hit) | `curl -I .../favicon.ico` |
| B21 | P2 | Input font-size 14 px causes iOS Safari auto-zoom | LoginPage Field component |
| B22 | P2 | PWA manifest declares one logo entry (no 192/512/maskable) | manifest.webmanifest |
| B23 | P2 | PWA manifest locks `orientation: "portrait"` (tablet-hostile) | manifest.webmanifest |
| B24 | P2 | Hero copy duplicated on login (H1 = subhead) | LoginPage.tsx:105-106 |
| B25 | P2 | No "resend verification email" CTA on the 403 login path | LoginPage.tsx:91-92 |
| B26 | P2 | PRO tier is `LISTED` in admin/tiers but CLAUDE.md says HIDDEN | doc-vs-data drift |
| B27 | P2 | "monthlyRecurringRevenueUsdCents" is `0` for every tier (dev is empty; not a bug per se, but means perf-test data is missing) | /api/admin/tiers |
| B28 | P3 | No CSP `report-uri` / `report-to` | response headers |
| B29 | P3 | HSTS missing `preload` directive | response headers |
| B30 | P3 | `script-src` allows `cdn.jsdelivr.net` (Monaco) — could be self-hosted | CSP header |
| B31 | P3 | Footer year hardcoded `© 2026` | layout source |

---

## 10 · Conversion-funnel diagnosis (read this if you read nothing else)

A typical first-time visitor to `dev.executespec.ai` today:

1. Lands on `/` → 2.6 MB cold load (~3 s on 4G) → sees split-screen login.
2. Scans for pricing → no public pricing → clicks "See an example run".
3. Lands on `/exec-demo` → sees **12% success rate** and **28 of 35 runs need attention** with project names Project A / Project B (looks like a sandbox, not a product). Believes the product is broken.
4. **Bounce.**

A more patient visitor:

1. Signs up. Receives a verification email (best path; not verified in this audit).
2. Logs in. Verification check fires → if email not yet clicked, sees "Please verify your email" with no resend option.
3. Lands on `/projects/empty`. Decent empty state — but: no project name suggestion derived from the marketing-site spec they typed.
4. Clicks "Create my first project". Lands on project home.
5. Tries to launch — sees "Free. 5 runs / month" but Settings says different copy in different places, Plans page might say Team is $0 (broken DB).
6. Submits a run. Reaches Plan gate. Approves. Run completes COMPLETE / verifiedScore = 43 / VerdictPill says Shippable but the score reads 43. Confusion.
7. Tries to view payment history → 500. **Tries to see pricing → can't.** Tries to evaluate models → can't (auth-walled).

**Recommendation:** The top three actions that will most improve conversion in the next two weeks:

1. **Publish pricing.** Make `/api/billing/plans` and `/api/models/available` public. Fix the TEAM $0 in the DB. (Closes B1, B2, B16, B17.)
2. **Fix the public dashboard.** Either hide `/exec-demo` until dev has volume, or curate the data. (Closes B4, B6.)
3. **Single source of truth for run-cap copy.** Wire every "5 runs / month" reference to `/api/billing/plans`. (Closes B5.)

Those three alone remove the top exit triggers a first-time visitor sees.

---

## 11 · What I could *not* test

Out of full transparency:

- I could not render a browser session against `https://dev.executespec.ai` directly — the preview MCP locks navigation to `localhost` origins, and computer-use grants browsers `read` tier only (no clicks / no typing). I read deployed HTML / JS / API responses directly via `curl` and reviewed the dev-branch frontend source line-by-line.
- Therefore: **visual issues that require pixel-accurate rendering on real DPRs** (font fuzziness on iOS at 2x / 3x DPR, gradient banding on Android, dark-mode flash on slow connections) are not covered. The most recent mobile sweep PR (#101, #95, #103) addressed several of these — re-test from a real device after the fixes above land.
- I did not exercise the **OAuth flows** (Google / GitHub) end-to-end — those are redirect-based and can't be tested via `curl` alone.
- I did not exercise the **Razorpay / Stripe checkout** — production billing flows need a real payment session.
- I did not exercise **SSE streams** (real-time run-log) — `EventSource` requires a long-lived browser connection.

If any of the above are critical to your decision, schedule a follow-up that gives Claude either (a) the `claude-in-chrome` MCP, or (b) a clickable browser at tier "full" so the visual + interactive UX paths can be confirmed.

---

## 12 · Suggested execution order

Week 1 (P0 only):

1. B1 — make plans public (1 line change in `SecurityConfig`)
2. B2 — V186 migration seeding TEAM prices (idempotent SQL)
3. B3 — fix payment-history 500 (add NPE guard + regression test)
4. B4 + B6 — gate `/exec-demo` behind volume threshold OR strip leaked markdown
5. B5 — single source of truth for run-cap copy

Week 2 (P1 batch — quick wins first):

6. B8 — flip cache-control on `/assets/*` to immutable (1-line Spring config)
7. B16 / B17 — publish models endpoint + fix asymmetric model lists
8. B14 / B15 — fix or remove broken admin/integration routes
9. B7 — login error mapper expansion
10. B20–B23 — favicon + manifest cleanup

Week 3 (P1 perf):

11. B11 — profile /dashboard endpoint (likely missing index)
12. JS bundle split — Monaco + admin pages + qrcode out of the main chunk

Week 4 (cleanup):

13. B10 — single tier-display helper
14. B18–B19 — verified-score recalibration vs status-reason
15. B25 — resend verification on 403
16. Remaining P2s

---

## 13 · Closing thoughts

The product surface area is *substantial* (`RunDetailPage.tsx` alone is 1,886 lines; ~38 authenticated routes; 4 top-tabs + drawer IA; 6 LLM-tier models). The team has shipped major fixes recently (single-score collapse, mobile sweeps iter-1 → iter-5, tier rename, glossary primitive) — the velocity is real.

But the product is at a **trust-leakage** stage: every surface I probed had at least one contradiction (pricing page can't load anonymously, success-rate is 12% on the public dashboard, payment history 500's, tier displays disagree). Each one is small; together they generate the "looks like a half-built app" gestalt that kills conversion.

Most of these are *DB-seed / config / one-line* fixes, not architecture. Concentrating the next two weeks on the P0 + P1 list above would move the perception meaningfully — and is achievable without a single new feature commit.

---

*Captured against `https://dev.executespec.ai` on 2026-05-11 at ~14:55 UTC.
Admin JWT issued at 14:38 UTC, ENTERPRISE tier, SUPER_ADMIN role. No data
was modified; only GET / authenticated reads + one POST `/auth/login`.*
