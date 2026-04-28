# ExecuteSpec — Pre-Deploy Open-Items Audit

**Compiled:** 2026-04-28
**Branch:** `feature/v3-conversion`
**Tip:** `cb98ed67` (post-Wave-7 + R-319 revert)
**Purpose:** comprehensive sweep of every still-open item from waves 1-7 + handoff + checkpoints, classified for deploy readiness.

---

## Severity / status counts

| Status | Count | Items |
|---|---|---|
| **actually-shippable-now** | **1** | R-320 |
| parked-on-product-decision | 6 | R-141, D-125 #1, D-125 #2, D-125 #3, D-125 #4, anon-run real LLM |
| parked-on-external-asset | 4 | R-204, R-313, R-318, SSO-config |
| parked-as-post-cutover-only | 2 | R-190, R-191 |
| withdrawn | 1 | R-319 |
| **Total** | **14** | |

**Net: only 1 item blocks "no meaningful work remains" before deploy.**

---

## Sources consulted

| # | Source | What it surfaces |
|---|---|---|
| 1 | `docs/strict-ux-thread-handoff.md` §5 (unblocked-but-still-parked) + §6 (deliberately not touched) + §7 (decisions parked with defaults) | R-141 / R-151 / SSO / D-125 / R-190 / R-191 / anon-run dispatch |
| 2 | `docs/plans/wave-7-issue-list.md` | R-310 – R-319 (R-319 marked WITHDRAWN) + the 3 decision-needed defaults (R-313 logos, R-318 Cal.com, R-319 SOC 2) |
| 3 | `docs/greenfield-execution-checkpoint.md` (Wave 1-3 closing notes) | Same as above plus the historical context for each park |
| 4 | Wave 4-7 commit log on `feature/v3-conversion` (`git log --oneline`) | Confirmed every wave's checkpoint fully shipped — no in-flight commits left |
| 5 | Source-level `TODO(R-…)` / `TODO(D-…)` grep across `frontend/src/v3/`, `frontend/src/components/`, `frontend/src/pages/`, `frontend/src/lib/` | 4 × D-125 in `RepoAttachPopover.tsx`, 1 × R-204 in `V3Landing.tsx`, 1 × R-151 in `V3Trust.tsx`, 1 × R-141 in `V3Billing.tsx` |
| 6 | Wave 7 decision-needed flags from this thread | R-313 / R-318 / R-319 |

---

## actually-shippable-now (1 item)

### R-320 — Trust Pack PDF (replaces `alert()` stub on `/v3/trust`)

| Field | Value |
|---|---|
| Source | `frontend/src/v3/pages/V3Trust.tsx:22` `TODO(R-151 follow-up)` + handoff §5 row "R-151 Trust Pack PDF" + checkpoint deferred-list "Real Trust Pack PDF asset" |
| Description | Today the "Download trust pack" button on `/v3/trust` calls a literal `alert()` saying "email security@executespec.ai with your vendor-risk template." That's a stub — it works but reads as broken to procurement-evaluator personas hitting the page during a vendor review. |
| Severity | **Medium** — Mike + Dana persona-relevant at procurement stage; not a deploy blocker on its own (alert still gives them a contact path) but a clean ship before a deployment removes the awkward edge. |
| Effort | **S** — generate PDF from existing trust-page content via Playwright headless, drop at `frontend/public/trust-pack.pdf`, swap the click handler to `window.open('/trust-pack.pdf','_blank')`. Same Playwright pattern used to render F1/F2 reports + the OG hero (G8). No new tooling. |
| Personas | EM Mike, VP Eng Dana |
| Acceptance | (a) `frontend/public/trust-pack.pdf` exists and is non-empty. (b) Click handler on the trust page opens it in a new tab via real download URL. (c) PDF content is the actual trust-page sections (Audit & certifications, Data flow, Compliance, Sub-processors, RTO/RPO, Contact) — *no fabricated SOC 2 ETA* (carries the same honest "not yet certified" line shipped in the R-319 revert). (d) `trackV3('trust_page_pdf_downloaded')` still fires. (e) `tsc --noEmit` clean. |
| Decision | None — content is the existing on-page text rendered to PDF; no new claims. |

---

## parked-on-product-decision (6 items)

### R-141 — GST tab gating

| Field | Value |
|---|---|
| Source | `frontend/src/v3/pages/V3Billing.tsx:323` (`const showGst = false`) + handoff §5 |
| Reason parked | Needs `Org.country` field exposed on `/api/v3/entitlements`. `Org` entity has no country today. Requires a backend migration + a product call on the source-of-country: Stripe customer record? Self-declare? Geo-IP? |
| Unblock trigger | Decision on country source + a 1-row migration + a 5-line entitlements DTO patch + flip `showGst` to `org.country === 'IN'`. |

### D-125 #1 — Private repo with no GitHub App access

| Field | Value |
|---|---|
| Source | `frontend/src/v3/components/RepoAttachPopover.tsx:111` `TODO(D-125)` + docstring §1 |
| Reason parked | User explicitly said "we'll revisit" (handoff §6). Today the popover lists any repo the user's PAT can see; org-level App install is a fallback link to `/v3/integrations`. Open call: should the popover auto-detect "no access" and switch to App-install copy inline? |
| Unblock trigger | Product decision (PAT-fallback-only OR auto-switch-to-App-install). |

### D-125 #2 — Mono-repo subdirectory selection

| Field | Value |
|---|---|
| Source | `frontend/src/v3/components/RepoAttachPopover.tsx:174` `TODO(D-125 #2)` + docstring §2 |
| Reason parked | Today the repo root is assumed = project root. Mono-repo support requires an Advanced expander on the popover for `subdir`. Designed to land "once we have a real mono-repo in QA". |
| Unblock trigger | A QA mono-repo + subdirectory schema field decision. |

### D-125 #3 — Repo-too-big quota gate

| Field | Value |
|---|---|
| Source | `RepoAttachPopover.tsx` docstring §3 |
| Reason parked | Backend's existing clone-size limits apply silently. Open call: surface a warning in the popover when the API returns a known-large repo size. Threshold is the parked decision (1 GB? Number of files?). |
| Unblock trigger | Product decision on threshold + a "size" field on the repo-list API. |

### D-125 #4 — Per-run repo override

| Field | Value |
|---|---|
| Source | `frontend/src/v3/components/RepoAttachPopover.tsx:392` `TODO(D-125 #1)` + docstring §4 |
| Reason parked | "+ Connect a GitHub repo" is the only way to grow the project's repo list today. Per-run override deliberately deferred "until multi-repo runs become common". |
| Unblock trigger | Multi-repo run telemetry shows it's worth the extra UI surface. |

### Anonymous-run real LLM dispatch (`v3.anonymous-trial.real-dispatch` flag)

| Field | Value |
|---|---|
| Source | Handoff §6 + checkpoint deferred-list. `AnonymousRunDispatcher` seam shipped + flag-gated; flag stays OFF. |
| Reason parked | Cost-cap design pending (hard $0.01/call cap + outline-only prompt + abuse classifier + ops spend dashboard). User explicitly excluded from this thread's scope. |
| Unblock trigger | Ops greenlight on cost-cap + a `@Primary @Bean` impl of `AnonymousRunDispatcher` that respects the cap. |

---

## parked-on-external-asset (4 items)

### R-204 — Hero video on `/v3/landing`

| Field | Value |
|---|---|
| Source | `frontend/src/v3/pages/V3Landing.tsx:498` `const USE_REAL_VIDEO = false; // TODO(R-204): flip when hero-demo.mp4 ships` |
| Reason parked | Real-asset task — needs a 30-second screen-recording captured (spec → plan → build → PR → labelled-Ready). Existing CSS-frame `DemoAnimation` is the fallback today. |
| Unblock trigger | MP4 (≤8 MB, H.264) at `frontend/public/hero-demo.mp4` + poster JPG at `frontend/public/hero-demo-poster.jpg` + flip the `USE_REAL_VIDEO` constant. |

### R-313 — Real customer logos on `/v3/landing` TrustedByStrip

| Field | Value |
|---|---|
| Source | Wave 7 issue list R-313 decision-needed flag + Wave 6 R-202 placeholder |
| Reason parked | Need real customer reference + use-permission. Today ships 5 placeholder cards + an "Add your logo →" mailto. |
| Unblock trigger | A signed permission + a logo SVG/PNG + a 5-minute edit to the slot config. |

### R-318 — Real Cal.com URL for Enterprise demo CTA

| Field | Value |
|---|---|
| Source | `frontend/src/v3/components/PricingGrid.tsx:130` Wave 7 R-318 placeholder |
| Reason parked | Mailto with pre-filled qualification template ships today. Cal.com / Calendly account setup is the unblock task. |
| Unblock trigger | A working Cal.com URL — 1-line href swap. |

### SSO config screen on `/v3/team`

| Field | Value |
|---|---|
| Source | Handoff §5 — `V3Team.tsx:636` |
| Reason parked | V127 schema (sso_provider / sso_enforced / sso_metadata_url / sso_domain) shipped a wave ago. Config UI deferred until a live IdP tenant is available for round-trip QA (Okta or Auth0). |
| Unblock trigger | A live IdP test tenant + 30 minutes of click-through QA. |

---

## parked-as-post-cutover-only (2 items)

### R-190 — Vibe View deletion

| Field | Value |
|---|---|
| Source | Handoff §6 + R-190 backlog row (handoff line 617) |
| Reason parked | `frontend/src/components/runs/Vibe*.tsx` + `useViewMode.ts` stay alive while v2 is live. Only safe to delete after the v2 surface is fully retired. |
| Unblock trigger | v2 → v3 cutover complete (no `/dashboard` v2 traffic). |

### R-191 — Sidebar / breadcrumb / Mission-vocabulary purge

| Field | Value |
|---|---|
| Source | Handoff §6 + R-191 backlog row (handoff line 623) |
| Reason parked | Same cutover gate as R-190. The "Mission" vocabulary in v2 sidebar is still wired in places where v3 says "Run". |
| Unblock trigger | Cutover complete. |

---

## withdrawn (1 item)

### R-319 — SOC 2 ETA copy on `/v3/trust`

| Field | Value |
|---|---|
| Source | Wave 7 issue list R-319 row (now marked WITHDRAWN) + revert commit `cb98ed67` |
| Reason withdrawn | Founder direction "ignore SOC 2 for now". Both the R-319 confident copy AND the pre-R-319 placeholder ("[Auditor TBD] · Anticipated Q3 2026") removed. |
| Re-open trigger | A real audit engagement signed → re-issue with actual auditor name + completion month. |

---

## Working defaults (NOT open items — listed for completeness only)

These have working defaults and are functioning correctly. Documented here so a deployer doesn't think they're missed work.

| Tag | Default | Revisit trigger |
|---|---|---|
| **D-001** textarea-copy | "What do you want to build?" + "Describe it in plain English — we'll ship a draft PR you can merge." | Conversion data shows textarea is bouncing visitors. |
| **D-002** lifetime-credit-math | 5 lifetime + 5 per invite, cap 25 | Anti-fraud signals OR upgrade-funnel data. |
| **D-003** referral-cap-mechanic | Fires on referee's first run reaching `COMPLETE` | Top-of-funnel conversion drops. |
| **D-004** build-vs-vibe-split | Delete Vibe path entirely in v3 (no toggle); v2 Vibe code untouched until cutover | Post-cutover R-190 / R-191. |

---

## Wave 8 plan

Single item: **R-320** (Trust Pack PDF). Single-commit checkpoint (no batching needed — only one shippable item).

| Ckpt | Items | Effort | Type |
|---|---|---|---|
| Wave 8 ckpt 1 | R-320 | S | Frontend asset + 1-line handler swap |

After ckpt 1 → write `docs/pre-deploy-closeout.md` → final dispatch with READY-FOR-DEPLOY verdict.

**No backend changes, no migrations, no new dependencies.**

---

## Verdict

**1 actually-shippable-now item** — R-320 Trust Pack PDF. Everything else is genuinely parked on product decisions, external assets, the cutover gate, or the founder's withdrawal. Once R-320 ships, **no meaningful work remains before a deploy**.
