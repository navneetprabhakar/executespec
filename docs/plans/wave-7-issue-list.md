# ExecuteSpec — Wave 7 Issue List

**Compiled:** 2026-04-28
**Sources:** `/tmp/executespec-e2e-test-report-post-w6.md` (F1) + `/tmp/executespec-persona-conversion-report.md` (F2)
**Branch:** `feature/v3-conversion`
**Numbering:** continues from R-309 (Wave 6 close) → starts at R-310

---

## Severity counts

| Severity | Count | IDs |
|---|---|---|
| **Critical** | 1 | R-310 |
| **High** | 5 | R-311 · R-312 · R-313 · R-314 · R-315 |
| **Medium** | 4 | R-316 · R-317 · R-318 · R-319 |
| **Low** | 0 | (none — Wave 6 closed all polish items) |
| **Total** | 10 | |

---

## Decision-needed flags

| ID | Decision | Default if no answer |
|---|---|---|
| R-313 | Real customer logo for /v3/landing proof slot — which logo, with what permission? | Ship 4 grey placeholder logo cards + a `// TODO(R-313): swap for real logos` comment + the live shipping count from `ExecSnapshot`. Render the section behind a feature flag `landing.proof-slot.enabled` defaulting OFF. Founder swaps logos + flips flag when ready. |
| R-318 | Real Cal.com / Calendly URL for Enterprise demo CTA | Ship a `mailto:sales@executespec.ai?subject=...&body=...` link as the placeholder. Source comment captures the Cal.com swap candidate. |
| R-319 | Actual SOC 2 Type II completion ETA | Ship copy "SOC 2 Type II audit underway — completion targeted Q3 2026". Easy 1-line edit when real ETA is known. |

**No item is blocked.** Each decision-needed item ships a sensible default; founder swaps in the real value with a one-line edit.

---

## Critical (1 item)

### R-310 — Empty-project funnel: replace `?`-headed `/v3/p/empty` placeholder with a real empty-state

| Field | Value |
|---|---|
| Source | F1 §2.2 (Free) + F1 §2.3 (Pro) + F1 §3 F-001 + F2 §1 + F2 P1 |
| Description | When a user has zero projects, the SPA redirects them to `/v3/p/empty`, which fires `GET /api/projects/empty` (404) and renders `<h1>?</h1>`. Reads as "the product is broken". Hits `/v3/home`, `/dashboard`, `/projects` redirects on Free + the literal `/v3/p/empty` on Pro. |
| Severity | **Critical** — broken flow, persona-blocking for Sam. Single biggest "is this broken?" trigger in the product. |
| Effort | S (≤ 1 day; one new component + one router rule) |
| Personas | Solo Sam (catastrophic on return-visit) · Senior Priya (mild on first-run if no sandbox) |
| Acceptance | (a) `/v3/p/empty` and any redirect that lands there renders a `V3EmptyProject` component with copy "You don't have any projects yet — let's create your first one." + a primary CTA. (b) The CTA either auto-routes to `/v3/projects/new` after 0 ms or links to it. (c) No 404 fired against `/api/projects/empty`. (d) Walker re-run finds zero "?"-headed pages on Free + Pro. |
| Decision | None |

---

## High (5 items)

### R-311 — `/audit` non-admin: explicit access-denied component

| Field | Value |
|---|---|
| Source | F1 §2.5 (Enterprise) + F1 §3 F-002 + F2 EM Mike |
| Description | Enterprise developer (role `DEVELOPER`) clicks Audit log → API returns 403 → SPA renders `<h1>?</h1>`. Reads as a bug. |
| Severity | **High** — Mike-blocking; conversion-relevant for Team→Enterprise. |
| Effort | XS (one component, one error handler) |
| Personas | EM Mike · Senior Priya (if she ever clicks Audit on a Team plan) |
| Acceptance | When `/audit` returns 403, render `<AccessDenied feature="Audit log" requiredRole="ORG_ADMIN" />` with copy: "Audit log requires admin role — contact your org admin to grant ORG_ADMIN." Plus a "Back to dashboard" link. No more "?"-page. |
| Decision | None |

### R-312 — In-app upgrade nudge for Pro at run #5

| Field | Value |
|---|---|
| Source | F2 §2 Senior Priya + F2 P3 + F2 §week-4 |
| Description | Pro users have no in-app prompt to invite their team. Conversion to Team is pull-only. |
| Severity | **High** — measurable conversion lever (Pro → Team). |
| Effort | M (one banner component + run-count integration + dismiss state) |
| Personas | Senior Priya |
| Acceptance | Pro users with ≥ 5 successful runs (lifetime, not monthly — runs are sticky milestones) see a one-line dismissible banner on the v3 dashboard: "You've shipped 5 PRs solo. Want to invite your team to share credits and review?" → CTA to `/v3/team` upgrade flow. Dismissal persists in `localStorage` keyed by user. Banner does not show for non-Pro tiers. |
| Decision | None |

### R-313 — Customer-proof slot on `/v3/landing` (logo placeholder + live shipping count)

| Field | Value |
|---|---|
| Source | F2 §3 P2 + F2 VP Eng Dana + F2 §week-4 |
| Description | The `/v3/landing` hero lacks any external proof. Generic "trusted by" row only. Need a real logo + a real number. |
| Severity | **High** — Dana-blocking; mid-funnel proof point that converts demo-shoppers. |
| Effort | M (proof section component + ExecSnapshot wiring + flag) |
| Personas | VP Eng Dana · EM Mike |
| Acceptance | (a) New `LandingProofSection` component sits directly under the hero. (b) Renders 4 logo placeholder cards (`<div role="img" aria-label="Placeholder customer logo">`). (c) Renders the live ship-count headline pulled from `/api/exec-snapshot/totals`: "X PRs shipped in the last 30 days". (d) Whole section gated behind feature flag `landing.proof-slot.enabled` (default OFF). (e) Source comment documents the logo-swap path. |
| Decision | **Yes** — real logos. Default if no answer = ship placeholders behind OFF flag. |

### R-314 — Pricing value-anchor copy on Pro card

| Field | Value |
|---|---|
| Source | F2 §1 Solo Sam |
| Description | Current Pro card subhead reads "≈ 20 LOW runs, 6 HIGH runs, or 10 Mobile runs" — math homework. Need a value-anchor like "Enough to keep one side-project alive forever". |
| Severity | **High** — Sam-blocking copy at the pricing decision point. |
| Effort | XS (1-line copy change in `PlanCard.tsx` for v3/v2 pricing; same on website) |
| Personas | Solo Sam |
| Acceptance | (a) v3 pricing page Pro card subhead reads "Enough to keep a side-project shipping every week" + the math fine-print stays as a `<small>` second line. (b) Website pricing card same change. (c) `/v3/pricing` walker capture shows the new copy. |
| Decision | None |

### R-315 — Gate anon `/v3/team` fetches behind auth

| Field | Value |
|---|---|
| Source | F1 §2.1 (Anon) + F1 §3 G-FOLLOW-UP-1 + F2 §2 Priya |
| Description | Anonymous visitor to `/v3/team` triggers 10 × 401 in console (`/api/org/me`, `/api/org/me/members`, etc.). Cosmetic but reads as broken when prospects evaluate team page from a shared link. |
| Severity | **High** (degraded from F1 "Polish" because F2 surfaced the persona impact: shared-link prospects are exactly Priya's tech lead) |
| Effort | S (gate fetches on `useAuth().isAuthenticated`) |
| Personas | anon prospects of Senior Priya |
| Acceptance | (a) Anon `/v3/team` walker re-run shows zero 401s in console. (b) Members + org-info fetches gated on auth state. (c) The page still shows the public preview (the section that doesn't need org data). |
| Decision | None |

---

## Medium (4 items)

### R-316 — `/admin` non-admin pre-redirect interstitial

| Field | Value |
|---|---|
| Source | F1 §2.5 (Enterprise) + F1 §3 F-003 |
| Description | Non-admin clicks `/admin` → route guard redirects, but `<h1>?</h1>` flashes briefly. |
| Severity | **Medium** — minor cosmetic; same root cause family as F-002. |
| Effort | XS (replace flash frame with interstitial) |
| Personas | EM Mike · any non-admin enterprise user |
| Acceptance | `/admin` for non-SUPER_ADMIN renders `<AccessDenied feature="Admin console" requiredRole="SUPER_ADMIN" />` for the entire pre-redirect window (or just guards the route entirely so the frame is never rendered). No more "?"-flash. |
| Decision | None |

### R-317 — TCO mini-calculator on `/v3/pricing`

| Field | Value |
|---|---|
| Source | F2 §1 VP Eng Dana + F2 §week-4 |
| Description | Dana wants to compare "200 devs × $X/seat × 12 mo" vs Devin's flat fee on the pricing page. No calculator today. |
| Severity | **Medium** — Dana-helping but not Dana-blocking. |
| Effort | M (small component: 2 sliders × 1 derived total; client-only; no API) |
| Personas | VP Eng Dana · EM Mike (sometimes) |
| Acceptance | (a) New `TcoEstimator` component on `/v3/pricing` between Team + Enterprise cards. (b) Two inputs: "How many engineers" (slider 5-500) + "How many months" (slider 6-36, default 12). (c) Output: monthly + annual TCO at Team-tier rate, with a "Compare with Enterprise" CTA. (d) Pure client computation, no API. |
| Decision | None |

### R-318 — Enterprise card: demo-scheduling CTA

| Field | Value |
|---|---|
| Source | F2 §1 EM Mike + F2 P3-implied + F2 §week-4 |
| Description | Enterprise card reads "Custom · Contact us" with no calendar embed. Mike wants to self-serve a 30-min demo. |
| Severity | **Medium** — slows Enterprise conversion but mailto: works as fallback. |
| Effort | XS (link change + copy) |
| Personas | EM Mike · VP Eng Dana |
| Acceptance | (a) Enterprise plan card has a primary CTA "Schedule a 30-min demo →" linking to `mailto:sales@executespec.ai?subject=Demo%20request%20%E2%80%94%20Enterprise&body=...` (URL-encoded subject + body with name/team-size template). (b) Source comment marks Cal.com swap candidate. |
| Decision | **Yes** — real Cal.com URL. Default if no answer = mailto. |

### R-319 — SOC 2 ETA copy on `/v3/trust`

| Field | Value |
|---|---|
| Source | F2 §1 VP Eng Dana |
| Description | Trust page says "SOC 2 in progress" without an ETA. InfoSec teams need a date. |
| Severity | **Medium** — Dana-helping at procurement stage. |
| Effort | XS (copy change) |
| Personas | VP Eng Dana |
| Acceptance | `/v3/trust` SOC 2 row reads: "SOC 2 Type II audit underway — completion targeted Q3 2026. Type I report available on request." |
| Decision | **Yes** — actual ETA. Default if no answer = Q3 2026. |

---

## Low (0 items)

Wave 6 closed every polish item. Cloudflare email-decode 404 on the website is local-dev-only (CF script not present in local stack) — not actionable, not tracked.

---

## Out of scope — testing gaps explicitly NOT a Wave 7 item

- Multi-repo Execution View end-to-end run (Enterprise feature; needs sandbox repo + webhook)
- Real Stripe / Razorpay checkout (would touch real payment gateway)
- GitHub PR creation flow on a real repo (needs sandbox with PAT)
- Full sandbox onboarding `/welcome` flow execution
- MEDIUM and HIGH complexity bands of pipeline (out-of-budget for Wave 7 scope)

These belong in a future "F3 — pipeline matrix" sweep.

---

## Wave 7 execution plan

Severity-ordered, batched into 3-item checkpoints:

| Checkpoint | Items | Estimated effort | Type |
|---|---|---|---|
| **Wave 7 ckpt 1** | R-310 + R-311 + R-312 | 1 Critical + 2 High | Frontend (V3EmptyProject + AccessDenied + UpgradeNudgeBanner) |
| **Wave 7 ckpt 2** | R-313 + R-314 + R-315 | 3 High | Frontend (LandingProofSection + Pro copy + auth gating) |
| **Mid-flight dispatch** | — | — | Summarise Critical + High shipped; user decides whether to continue |
| **Wave 7 ckpt 3** | R-316 + R-317 + R-318 + R-319 | 4 Medium | Frontend (admin interstitial + TCO + demo CTA + SOC 2 copy) |
| **Close dispatch** | — | — | All Critical + High + Medium shipped; no Lows queued |

Total: **10 commits** under Wave 7 (3 + 3 + 4 fixes; ckpt commits group adjacent fixes per the user instruction "batch where it makes sense").
