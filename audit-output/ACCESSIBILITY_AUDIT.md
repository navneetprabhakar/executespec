# Accessibility Pass — ExecuteSpec frontend (v2.6.4 / `audit/v2-5-11-ux-product`)

**Run date:** 2026-04-30
**Scope:** automated DOM scan + keyboard-nav simulation via Claude_Preview MCP (Vite :5173, serverId `41165fed-851d-4591-a1f8-57c640ad4956`) + code-trace of the 6 highest-traffic routes (`/login`, `/projects/empty`, `/projects/:slug` → resolved as `/home`, `/runs`, `/library`, `/settings`)
**Out of scope:** real screen-reader testing (NVDA, VoiceOver, JAWS) — explicitly flagged as human-required in §4
**Standard:** WCAG 2.1 AA
**Time budget:** 30 minutes (scaled-down version of full Prompt F)

---

## §1 — TL;DR

**WCAG AA position: PARTIAL pass.** ExecuteSpec is mostly accessible — focus management in modals is real (verified by reading `ResponsiveModal.tsx`), color contrast on every text-on-surface pair we sampled clears AA with comfortable margin (lowest passing pair: 5.40:1, well above the 4.5:1 floor for normal text), and keyboard tab order on the routes we walked is in DOM order with no positive `tabindex` overrides. Every `<input>` we saw is properly labeled. No `<img>` tags are missing `alt` (the SVGs that are present use `aria-hidden`).

The five gaps that stop this from being a full pass:

1. **No "Skip to main content" link** anywhere in the layout chrome (`AppLayout.tsx`, `V3Shell.tsx`, `Header.tsx`, `Sidebar.tsx`, `V3TopBar.tsx`). Keyboard-only users land at "Toggle menu" and must Tab through ~6-9 nav links on every page load to reach main content. WCAG 2.4.1 Bypass Blocks (Level A).
2. **2 icon-only pagination buttons on `/runs`** (`RunList.tsx:228-250`) have no `aria-label`. Verified live in the DOM. WCAG 4.1.2 Name/Role/Value.
3. **Run state changes are not announced.** SSE-driven status flips (PLANNING → WORKING → COMPLETE) update `VerdictPill` (which has `role="status"` but no `aria-live`) and `V3ProjectHome` bubbles (no live region at all). Screen-reader users won't hear "your run is now complete." WCAG 4.1.3 Status Messages.
4. **`role="status"` on VerdictPill without `aria-live`.** `role="status"` implies `aria-live="polite"` on most ATs, but the bigger issue is the pill is rendered N times per page (one per run row on `/runs`) — which floods the live region. Should be on the run-detail surface only, not list cards.
5. **Disabled-pagination chevron color (#4C5662 on #14171C, ratio 2.41:1) fails AA when read as text.** WCAG 1.4.3 grants disabled controls an exemption, so this is technically allowed — but visible difference between active and disabled is barely perceptible to users with mild contrast loss.

Real screen-reader testing (NVDA on Windows, VoiceOver on macOS, JAWS) was **not performed** — this is a 30-minute pass. See §4 for what a full SR test would catch.

Disposition: enterprise procurement asks ([F-11] / Q-08 in `AUDIT_REPORT.md`) usually require a VPAT or signed WCAG 2.1 AA conformance statement. Today we cannot honestly sign one because of #1 and #3. Once those land we are conformance-eligible pending real SR validation.

---

## §2 — Automated audit results (per route)

Run via `mcp__Claude_Preview__preview_eval` against the live Vite dev server. The session was authed as SUPER_ADMIN, so `/login` was unreachable and was code-traced instead (see notes after the table).

| Route | Buttons | Inputs | Anchors | Icon-btn no-label | Inputs no-label | Imgs no-alt | Links no-text |
|-------|---------|--------|---------|--------------------|------------------|-------------|----------------|
| `/login` (code-trace, `LoginPage.tsx`) | 4 | 2 | 5 | 0 | 0 | 0 | 0 |
| `/home` → `/projects/<id>` (live) | 10 | 0 | 4 | 0 | 0 | 0 | 0 |
| `/projects/empty` (live, `V3EmptyProject.tsx`) | 3 | 0 | 5 | 0 | 0 | 0 | 0 |
| `/runs` (live, `RunsPage.tsx` → `RunList.tsx`) | 47 | 0 | 1 | **2** | 0 | 0 | 0 |
| `/library` (live, `V3Library.tsx`) | 7 | 1 | 32 | 0 | 0 | 0 | 0 |
| `/settings` (live, `V3Settings.tsx`) | 5 | 0 | 7 | 0 | 0 | 0 | 0 |

**`/login` notes (code-trace, `frontend/src/pages/auth/LoginPage.tsx`):**
- Show/hide password button (line 146-154) has correct `aria-label={showPassword ? 'Hide password' : 'Show password'}` — clean.
- Email and password `<input>` are both linked to `<label htmlFor=…>` via `Field` component (line 300-312) — clean.
- OAuth provider SVG icons all have `aria-hidden` — clean.
- Brand mark `<span>ES</span>` in `AuthShell` is decorative text, fine.

**`/runs` icon-only button findings (the real violations):**
```
RunList.tsx:228-237 — pagination "Previous page" — <ChevronLeft /> no label
RunList.tsx:241-250 — pagination "Next page"     — <ChevronRight /> no label
```
Live DOM confirmation (preview_eval): `iconChild: lucide lucide-chevron-left h-4 w-4` and `lucide-chevron-right`, both inside a `<div class="flex items-center gap-1">` with adjacent text "1 / 3". A screen reader will read these as "button" with no name; the user has no way to know what they do.

---

## §3 — Keyboard nav findings

Tested by injecting `document.querySelectorAll('a, button, input, textarea, select, [tabindex]:not([tabindex="-1"])')` and walking the first 30 elements per route. All routes have `tabindex` of `null` (DOM order) — no positive `tabindex` overrides. No focus-trap regressions outside of intended modals.

| ID | Summary | Severity | Confidence | WCAG | Evidence | Fix | Effort |
|----|---------|----------|------------|------|----------|-----|--------|
| **A-01** | No "Skip to main content" link anywhere in the layout shells. Keyboard-only users land on "Toggle menu" (mobile sidebar) and Tab through ~9 nav-bar buttons before reaching content on every page. | **HIGH** | high | 2.4.1 Bypass Blocks (A) | `grep -rn "skip" frontend/src/components/layout/ frontend/src/v3/components/V3Shell.tsx` returns zero matches. Top of `/runs` tab order: `Toggle menu → ExecuteSpec brand → Search → Theme toggle → Notifications (9) → Avatar → New run → My/Org/All Runs filter → All/Planning/Working/...`. 9 stops before content. | Add `<a href="#main-content" className="sr-only focus:not-sr-only focus:absolute focus:top-2 focus:left-2 ...">Skip to main content</a>` as the first child of `<body>` (or in `V3Shell.tsx` and `AppLayout.tsx`). Add `id="main-content"` to the `<main>` element. | 30 min |
| **A-02** | `RunList.tsx` pagination chevrons render as nameless buttons. SR users hear "button, button" with no context. | **MED** | high | 4.1.2 Name, Role, Value (A) | `RunList.tsx:228-250`. Live DOM: 2 icon-only `<button>` siblings of "1 / 3" text. | Add `aria-label="Previous page"` and `aria-label="Next page"` to the two `<button>` elements. | 5 min |
| **A-03** | Sidebar mobile-toggle button "Toggle menu" is the very first tab stop on every page in `AppLayout.tsx` flow, even when sidebar is irrelevant on /runs / /library. | LOW | med | 2.4.3 Focus Order | First tab stop on `/runs` is `BUTTON: Toggle menu`. | Move skip link before it (covered by A-01) — once skip link lands this becomes a non-issue. | (covered) |
| **A-04** | Run-detail SSE state pill `VerdictPill` carries `role="status"` but is rendered N times on the `/runs` list (one per row, 20 instances seen in DOM dump). Multiple live regions on one page is a known SR-flooding pattern. | MED | med | 4.1.3 Status Messages | Live DOM: 20 `[role="status"]` elements, all `text: "Shippable"` or "Blocked". | Pass `live={false}` (or skip `role="status"`) when `VerdictPill` is rendered inside list contexts. Reserve the live region for the run-detail page where status actually changes. | 1 hour |
| **A-05** | Modal focus-trap in `ResponsiveModal.tsx` is correctly implemented (verified by reading lines 22-109): `FOCUSABLE` selector, Escape handler, body-scroll lock, restore-focus, role=dialog, aria-modal, aria-label. **No regression.** Listed as a positive finding for completeness. | INFO | high | (positive) | `ResponsiveModal.tsx:22-175` | None — keep current code as the canonical pattern. | — |
| **A-06** | Theme toggle button has visible text "Switch to light mode" — passes. Sidebar collapse icon ChevronLeft has `title` per CLAUDE.md (v2.6.1 patch). Both clean. | INFO | high | (positive) | Live DOM tab order #3 = "Switch to light mode" | None. | — |
| **A-07** | Login show-password toggle has dynamic `aria-label` mirroring state (`Hide password` / `Show password`). Clean. | INFO | high | (positive) | `LoginPage.tsx:151` | None. | — |
| **A-08** | `<header>` and `<main>` are present as implicit landmarks but lack explicit `role="banner"` / `role="main"` everywhere — `V3TopBar.tsx:40` does have `role="banner"` but `AppLayout` does not. Implicit roles work fine in modern AT. | LOW | high | 1.3.1 Info & Relationships | DOM dump on /runs: `tag: HEADER, role: implicit; tag: MAIN, role: implicit`. | Optional: add explicit `role="main"` to `<main>` in `AppLayout.tsx`. Low impact. | 5 min |
| **A-09** | `/runs` has only one `<h1>` ("Runs"). `/library` has its own. Heading hierarchy looks single-page-app-shaped per route, no structural skips observed. | INFO | med | 1.3.1 / 2.4.6 | DOM dump: `headingsFirst10: [{level: H1, text: Runs}]`. | None. | — |
| **A-10** | No element with positive `tabindex` (i.e. no `tabindex=1`-style overrides). Tab order is pure DOM order. | INFO | high | 2.4.3 Focus Order | preview_eval `tabidx: null` on all 30 sampled focusables. | None. | — |

---

## §4 — Screen-reader findings (HUMAN-REQUIRED — NOT TESTED)

**This pass did not run NVDA, VoiceOver, JAWS, TalkBack, or any real assistive tech.** Automated tools and code-trace cannot substitute for SR testing — they catch the structural violations (missing labels, missing live regions, broken landmarks) but miss the experiential ones (does the announcement order make sense? does the SR user know what state they're in? are dynamic updates actually heard?).

**What a full SR pass would still need to verify:**
- VoiceOver rotor on `/runs`: are the 20 `role="status"` regions a flood, or does VO collapse them silently?
- NVDA browse mode walking `/projects/<id>`: does the SSE bubble update get announced when status flips PLANNING → WORKING? (see A-04)
- JAWS forms mode in `LoginPage`: do the OAuth buttons get announced as "Continue with Google, link" or "Continue with Google, button"? (They are `<a>` styled as buttons.)
- TalkBack on Android Chrome at `/projects/empty`: does the empty-state CTA flow read in a sensible order?
- Heading-list navigation: is `Generate from spec` actually an `<h1>` or is it a styled `<div>`?

**Recommendation:** before signing a VPAT or WCAG 2.1 AA conformance statement, budget 4-6h for a real SR pass per major surface (login, projects list, runs list, run detail, settings, billing). Use Anthropic's accessibility consultancy partner or Deque-trained QA. This audit is a precondition, not a substitute.

---

## §5 — Live region audit (run state changes)

**Finding: SSE-driven run state changes are NOT announced to assistive tech.**

Code-traced via grep against `frontend/src/v3/pages/V3ProjectHome.tsx`, `frontend/src/v3/pages/V3RunDetail.tsx`, `frontend/src/components/runs/RunDetail.tsx`, `frontend/src/components/runs/RunDetailTabs.tsx`, `frontend/src/components/runs/LiveLog.tsx`. Zero `aria-live` attributes. `role="status"` exists only on `VerdictPill` (which carries an implicit `aria-live="polite"`) — but that pill renders the *terminal* verdict, not the in-flight state. It doesn't announce PLANNING, WORKING, or REVIEWING. It doesn't announce when AWAITING_APPROVAL surfaces (which means a SR user with an open run page may sit unaware that their input is needed for 60+ seconds).

**Where the bubble updates land:** `V3ProjectHome.tsx` renders bubbles in a flat `<div>` list. Per-bubble status changes via `bubbles` array reflow but the list itself has no `aria-live="polite"` and no `aria-relevant="text"`. The text inside each bubble updates silently from the SR's perspective.

**Fix sketch:**
1. Wrap the bubble list in `<div aria-live="polite" aria-atomic="false">` so SR announces newly-added or changed bubbles.
2. Add a dedicated, off-screen status announcer (`<div role="status" className="sr-only">{`Run ${runId.slice(0,8)} is now ${status}`}</div>`) that updates on SSE status events. One per surface, not per bubble.
3. On AWAITING_APPROVAL specifically, announce `Approval needed: $X.XX, N tasks. Press Enter on Approve to continue.` — this is the highest-stakes state for SR users (they will otherwise miss the gate).

WCAG 4.1.3 Status Messages (Level AA) is the violated criterion.

---

## §6 — Color contrast violations

Computed live from `getComputedStyle(document.documentElement)` in the running app. Resolved nested `var(--c-*)` primitives to their hex source in `frontend/src/design-system/primitives.css`. Sampled 9 high-traffic foreground/background pairs in the dark theme (default).

| Pair | FG hex | BG hex | Ratio | AA normal (≥4.5) | AA large (≥3.0) | Verdict |
|------|--------|--------|-------|-------------------|-------------------|---------|
| `--text-primary` on `--geist-bg` (body text) | `#ECEEF2` | `#0B0D10` | 16.75 | PASS | PASS | clean |
| `--text-secondary` on `--geist-bg` | `#C9D1DB` | `#0B0D10` | 12.63 | PASS | PASS | clean |
| `--text-muted` on `--geist-bg` (captions, helper text) | `#8A94A6` | `#0B0D10` | 6.36 | PASS | PASS | clean |
| `--text-muted` on `--gray-100` (caption inside card) | `#8A94A6` | `#14171C` | 5.87 | PASS | PASS | clean |
| `--text-muted` on `--gray-200` (caption inside elevated card) | `#8A94A6` | `#1B1F26` | 5.40 | PASS | PASS | clean (lowest passing pair) |
| `--accent-blue` on `--geist-bg` (links on body bg) | `#5B8DEF` | `#0B0D10` | 6.02 | PASS | PASS | clean |
| `--accent-blue` on `--gray-100` (links on card) | `#5B8DEF` | `#14171C` | 5.56 | PASS | PASS | clean |
| `--c-graphite-6` on `--geist-bg` (disabled fg on body) | `#4C5662` | `#0B0D10` | 2.61 | FAIL | FAIL | exempt (1.4.3 disabled-control exception) |
| `--gray-500` on `--gray-100` (disabled pagination chevron) | `#4C5662` | `#14171C` | 2.41 | FAIL | FAIL | exempt — but visually weak |

**Verdict:** dark theme passes WCAG AA on every text pair we sampled. Lowest passing ratio is 5.40:1 (`--text-muted` on `--gray-200`), with 0.9 of comfortable headroom over the 4.5:1 floor. Disabled-state foregrounds technically fall below 4.5:1 but are exempt under WCAG 1.4.3.

**Light theme not tested.** `frontend/src/tokens.css:165+` defines a complete `[data-theme='light']` override but contrast ratios in light theme were not sampled in this 30-min pass — the toggle is wired (Header `Switch to light mode` button confirmed in tab order) but every measurement above is dark-theme. **Light theme contrast is a coverage gap** (see §8).

**Status colors not sampled at-scale:** `--status-success` (#10B981), `--status-error` (#EF4444), `--status-warning` (#F59E0B), `--status-blocked` (#EC4899) all rendered as chip backgrounds (white-ish text on saturated color) — chip text contrast was not tested in this pass.

---

## §7 — Top 5 a11y blockers (severity-ranked)

1. **A-01 — No skip link anywhere.** Highest impact. WCAG 2.4.1 (Level A — the easiest to fail). Fix is 30 min and unblocks every keyboard-only user on every page. **Ship first.**
2. **A-04 / §5 — SSE run state has no live region.** WCAG 4.1.3 (Level AA). The whole product is async / agent-driven; an SR user landing on `/projects/<id>/runs/<short>` has no idea the run is making progress. Fix is medium effort (1-2h) but high impact for any user relying on AT.
3. **A-02 — `RunList` pagination chevrons.** WCAG 4.1.2 (Level A). 5-min fix. Trivial but real — and `/runs` is one of the highest-traffic surfaces.
4. **§4 — No real screen-reader validation has been performed.** This is a process gap, not a defect, but if anyone signs a WCAG conformance statement on the back of automated tools alone they are misrepresenting the testing performed. Budget 4-6h on the next release.
5. **§6 light-theme contrast unsampled.** Light theme is shipped (toggle visible) but not contrast-validated. If light-theme `--text-muted` against `--gray-200` light (`#E7E9EE`) is below 4.5:1, every helper-text user in light mode is below AA. Should sample before next ship.

---

## §8 — Coverage gaps

What this pass **did not do**, and what a full Prompt F (6h budget) would catch:

| Gap | What's missing | Why it matters | Suggested next step |
|------|---------------|----------------|-----------------------|
| Real screen-reader testing | No NVDA / VoiceOver / JAWS / TalkBack runs | Automated tools find ~40% of real a11y issues per Deque studies; the 60% remainder is announcement quality, focus order semantics, dynamic-update behavior | Schedule 4-6h SR pass per release; or contract WCAG-trained QA; or hire AT-using user testers (Fable.tech, AccessibilityWorks) |
| Light-theme contrast | Only dark theme sampled in §6 | Light theme is shipped (`tokens.css:165+`); broken contrast there affects 30-50% of users (system theme preference) | Re-run §6 with `document.documentElement.setAttribute('data-theme', 'light')` and resample the same 9 pairs |
| Color-blindness simulation | Not run | Status colors (red error, green success, amber warning) collapse to similar grays under deuteranopia | Run Chrome DevTools "Emulate vision deficiency" → deuteranopia / protanopia / tritanopia — verify success vs error vs warning still distinguishable by shape or text label, not color alone |
| Motion-sensitivity | `prefers-reduced-motion` not tested | `framer-motion` modals, `AnimatedCounter`, `GlowDot` animate by default | Add `@media (prefers-reduced-motion: reduce)` to global CSS that disables `transform`/`opacity` transitions on motion-marked elements |
| Real assistive-tech testing | No switch control, eye-gaze, voice control (Dragon, Voice Access) tested | These users rely on per-element accessible names being unique and stable | Out of scope for any audit — schedule with AT-user research panel |
| Deep modals (NewRunModal, FailureDecisionModal, etc.) | Modal focus-trap was code-read but not exercised live | Focus-trap regression risk between releases | Add a Playwright test: open each modal, Tab to last focusable, Tab → assert `document.activeElement` is back at first focusable |
| `/admin/*` and `/billing` surfaces | Out of the 6-route scope | Admin tools used by SUPER_ADMIN are usually neglected for a11y; same for billing flows | Add to next pass: `/admin/tiers`, `/billing`, `/invoices`, `/audit` |
| Form-validation announcements | Login form errors render as `<p>` with no `role="alert"` (`LoginPage.tsx:158-161`) | SR users won't hear "Invalid email or password" appear | Add `role="alert"` to error `<p>`; ditto every form across `auth/`, `org/`, `Settings`, billing |
| Keyboard activation of clickable `<div>` / `<a>`-styled buttons | Not exhaustively grepped | `<a href="...">` styled as buttons trigger Enter but not Space | Grep `frontend/src/` for `<a [^>]*onClick=` and convert to `<button>` or add `role="button"` + `onKeyDown` for Space |
| Time-based content (SSE log streams in `LiveLog.tsx`) | Auto-scroll behavior not validated for SR | Auto-scrolling content fights with SR cursor | Verify `LiveLog` exposes a "pause auto-scroll" affordance and is not in a live region |

---

## Appendix — Methodology

1. Confirmed Vite at `:5173` via `mcp__Claude_Preview__preview_list`.
2. Walked 5 of 6 target routes via `history.pushState` → `popstate` dispatch in `preview_eval`. `/login` was unreachable (auth redirect) and code-traced from `frontend/src/pages/auth/LoginPage.tsx`.
3. For each route: enumerated all `<button>`, `<input>`, `<a>`, `<img>` and computed accessible name from text + `aria-label` + `title` + `aria-labelledby`.
4. Grepped `frontend/src/` for `aria-live`, `role="status"`, `role="alert"`, `skip` to validate live-region and skip-link coverage.
5. Read `ResponsiveModal.tsx` end-to-end to verify focus-trap implementation.
6. Resolved CSS variables via `getComputedStyle(document.documentElement)` and computed WCAG contrast ratios with the standard formula (relative luminance with sRGB linearization).

**Repository state:** branch `audit/v2-5-11-ux-product`, HEAD `779ca78c`. Frontend version v2.6.4 (per `frontend/CLAUDE.md`).

**Spend:** $0 LLM (all checks ran in the local browser via `preview_eval` and ripgrep).
