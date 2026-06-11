# UI/UX review + recommendations — surface by surface

> **Status: IMPLEMENTED (wave 2, 2026-06-11) — see RL-2026-06-021.** Everything in Waves A–D landed except two deliberate deferrals: **RES-2** (10→5 tab consolidation — coupled to the Console-default decision) and the **Console default flip** itself (founder decision; every flip-*blocker* in C1 is done). Corrections discovered while implementing: vite `manualChunks` + CI vitest already existed (RL-2026-05-041 — the C13 "no code-splitting / dormant tests" claims were stale); **T4 and T5 are NOT bugs** (amber ≈7.6:1 on graphite-2; the light theme already swaps `--accent-blue` — both now pinned by `contrast.test.ts` + `token-bridge.test.ts`, the latter of which caught 3 real undefined-token consumers instead). The text below is preserved as the review-time record.
> **Original status:** REVIEW + IMPLEMENTATION PLAN — written 2026-06-11. Intended as the **source of truth for implementation**: every recommendation names the file, the current behavior, and the target behavior.
> **Method:** (1) full code review of every route in `App.tsx` (33 protected + 14 public) and all chrome/theme CSS; (2) a **live visual pass** against the running local backend with real data — both chromes (IDE + Console), both themes, at 1440×900 and 375×812 — findings marked **[verified live]** were observed in the browser, not inferred from code; (3) first-hand token/theme source verification (`main.tsx`, `useSignalTheme.ts`, `useUiStore.ts`, `tokens.css`, `ide.css`, `signal.css`, `console.css`).
> **Direction anchor:** recommendations align with the active **web-control-plane reframe** ([`docs/plans/web-control-plane-reframe.md`](web-control-plane-reframe.md), RL-2026-05-052): web = mission control (Console), VS Code extension = in-editor IDE. This review treats the Console as the destination chrome and the IDE chrome as the legacy fallback.
> **IDs:** `T#` theme system · `C#` cross-cutting · per-surface tables use P0–P3 severity (P0 broken/blocking · P1 bad UX · P2 polish · P3 nice-to-have).

---

## 0. Executive summary

The product is **functionally broad and visually coherent in dark mode at desktop** — the token system, JetBrains Mono identity, status/verdict color language, modal/loader primitives, and live-polling patterns are genuinely good foundations. The problems concentrate in five places:

1. **One real theme bug (T1):** two independent theme systems (`data-theme` vs `data-signal-theme`, `es:theme` vs `signal:theme`) desync, and **the public pricing page renders dark cards with near-invisible dark text on a light page** [verified live]. This is customer-facing and conversion-path.
2. **The compose form collapses under real data (RUN-1):** the project picker renders **every project as a chip** — with ~50 projects the Run button lands several screens down; on mobile it is one full-width chip per row, dozens of screens of thumb-scrolling [verified live].
3. **The product's headline metric is invisible in every list (C5):** no verified score and (except /runs) no cost on run rows/cards anywhere [verified live]. The verdict buckets also don't sum (30 runs → "Shipped 0, Failed 2") [verified live].
4. **Result-page overload:** 10 tabs + a flat 15-button action row where "Push to GitHub" is the blue primary even on a sandbox/benchmark run [verified live].
5. **Credit-spending actions lack confirmation (C8):** all four autofix dispatch surfaces fire a paid run on a single un-confirmed click.

6. **The in-app /plans page sells a tier ladder that doesn't exist (TIER-1)** [verified live as FREE]: no PRO card, TEAM at "$31/seat/mo · Annual billing" behind a default-on "Yearly · save 20%" toggle (TEAM is monthly-only at $39/seat per V165), plus the exact "500 credits/seat" / "$25 hard cost cap" invented limits the 2026-06-10 truth pass purged from the *public* pricing page. The truth pass fixed the marketing surfaces and missed the logged-in one. Details + the rest of the tier-gated findings in §7.

**Recommended order:** Wave A (theme bug + funnel blockers, ~1 week) → Wave B (commit to Console-as-default + navigation completeness) → Wave C (mobile hardening) → Wave D (a11y/copy/perf polish). Full roadmap in §6; TIER-1/2/3 join Wave A.

---

## 1. Theme assessment

### 1.1 What the theme system is (verdict: right idea, one bug, three liabilities)

Three token layers, correctly factored in principle:

| Layer | File | Role |
| --- | --- | --- |
| Primitives | `src/design-system/primitives.css` | raw palette (`--c-graphite-*`, `--c-paper-*`, `--c-signal-*`) — no semantics |
| Semantic | `src/tokens.css` (313 lines) | app roles (`--geist-bg`, `--accent-blue`, `--status-*`, spacing/radius/shadow/focus scales) + `[data-theme='light']` overrides |
| Chrome | `src/ide/ide.css` (`--ide-*`, VS Code Dark+/Light+), `src/signal/signal.css` (`--signal-*`), `src/console/console.css` (reuses `--ide-*`) | per-chrome skins; `ide.css:89–123` bridges `--signal-*` → `--ide-*` so reused Signal components follow the IDE theme |

JetBrains Mono is genuinely the single typeface everywhere (verified in `tailwind.config.ts` — both `sans` and `mono` resolve to it). Tailwind 4 config is token-aware (colors map to CSS vars). Spacing/radius/elevation scales exist and are used. Focus ring is defined centrally (`tokens.css` `--focus-ring`) and applied via `:focus-visible` on all interactive elements. `prefers-reduced-motion` globally disables animation. This is a better-than-average foundation — the issues below are fixable without a redesign.

### T1 — **Dual theme system: public pages break in light mode** (P0) [verified live]

- **Mechanism (verified first-hand):** there are TWO theme sources that never synchronize.
  - `stores/useUiStore.ts` — the app chrome's toggle → writes `localStorage['es:theme']` + `<html data-theme>`. `tokens.css` + `ide.css` + `console.css` read `[data-theme]`.
  - `src/main.tsx:35–47` (pre-paint) + `src/signal/hooks/useSignalTheme.ts:42–46` → write `localStorage['signal:theme']` + `<html data-signal-theme>`. ALL `.signal-*` component styles in `signal.css` read `[data-signal-theme]` (`signal.css:60`, `:578–592`).
  - `useSignalTheme.ts:5–6` admits it: *"Independent of the existing useUiStore … so the two systems coexist."*
- **Observed failure [verified live]:** with `data-theme='light'` (from the IDE status-bar toggle) and `data-signal-theme='dark'` (from OS preference at pre-paint), **/pricing renders a light page background with dark `.signal-plan` cards whose feature text is dark-on-dark (illegible) and whose RECOMMENDED badge is a blank amber pill**. Every public Signal page (login, register, pricing, trust, legal, shared) can render in this Franken-state; which state a visitor gets depends on which localStorage keys exist.
- **Fix (do exactly this):**
  1. Make `useUiStore` the single owner. In its theme `set`/`init`, write BOTH attributes: `data-theme` and `data-signal-theme` (same resolved value), and persist only `es:theme`.
  2. In `src/main.tsx` pre-paint block (lines 35–47): read `es:theme` (fall back to legacy `signal:theme` once, for migration), resolve `system` against `prefers-color-scheme`, set both attributes.
  3. Delete `useSignalTheme.ts` and its callers (the public pages' theme toggles should call `useUiStore.setTheme`).
  4. Follow-up (cleanup, not required for the fix): migrate `signal.css` selectors `[data-signal-theme=…]` → `[data-theme=…]` and drop the second attribute entirely.
  5. Regression check: `/pricing`, `/login`, `/terms`, `/shared/:token` in both themes, plus one IDE surface (theme bridge unaffected).

### T2 — Manual `--signal-*`→`--ide-*` bridge is a drift trap (P2)

`ide.css:89–123` hand-maps every Signal token consumed inside the IDE. A new `--signal-*` token added without a bridge entry silently renders with the wrong (dark-only) value inside the chrome. **Fix:** add a comment-block checklist at the top of the bridge + a 20-line vitest that parses both files and fails when a `--signal-*` var referenced by any component lacks a bridge entry (wire it when vitest lands, see C13).

### T3 — Hardcoded color inventory (P2, fix in one sweep)

| Location | Value | Replace with |
| --- | --- | --- |
| `signal/components/auth/ProtectedRoute.tsx` | `bg-[#0f1117]` | `bg-geist` (maps to `var(--geist-bg)`) |
| `signal/pages/CliLoginPage.tsx:84` | `#16a34a` success text | `var(--signal-mint)` |
| `signal/pages/CliLoginPage.tsx:131` | `#dc2626` error text | `var(--signal-coral)` |
| `signal.css:541` | fallback hex `#5443a8` for undefined `--signal-lavender-text` | define the token in both theme blocks |
| `ide/IdeWorkspace.tsx`, `IdeRunResult.tsx`, `IdeSettings.tsx` | inline `style={{color:'#fff'}}` / `background:'#fff'` (3 sites) | `var(--ide-accent-fg)` / `var(--ide-fg-bright)` |

(`IdeIcons.tsx` brand-colored SVG fills are intentional — leave.)

### T4 — Amber status contrast likely fails AA non-text on dark (P2)

Computed from token hexes: `--status-waiting` `#F59E0B` ≈ 2.8:1 and `--status-partial` `#F5A524` ≈ 2.5:1 on graphite-2 — below the 3.0 floor the rest of the palette was explicitly tuned to (cf. the F-15/F-16 audit comments in `tokens.css`). **Fix:** un-dormant `design-system/contrast.test.ts` (it already pins (token, surface) pairs), confirm, and bump the dark-theme ambers (light theme already uses `#D97706`).

### T5 — Focus ring invisible in light mode inside the IDE (P2)

`--focus-ring`'s outer ring is `--accent-blue` (`#5B8DEF`) which is AA-large on dark but washes out on white; the `[data-theme='light']` block in `ide.css:52–77` doesn't override it. **Fix:** add `--focus-ring: 0 0 0 2px var(--ide-bg-editor), 0 0 0 4px #1d4ed8;` to the light block.

### T6 — Monospace-for-everything: keep, with one carve-out (design decision)

JetBrains Mono as the single face is the brand and works for UI chrome, data, and code [verified live — the Console hero and run lists read well]. It costs readability on **long-form prose**: legal pages, architecture docs, generated docs, multi-paragraph chat replies (line after line of mono at 13–14px). **Recommendation:** keep mono as the only UI face; add ONE prose exception class (`.prose-body { font-family: var(--font-prose) }`, a humanist sans) applied to `.signal-md` long-form containers on legal pages + architecture/doc viewers only. If brand purity wins, accept and instead raise long-form line-height to 1.65 and cap measure at ~72ch.

### T7 — Z-index magic numbers (P3)

15 magic values across the three CSS files (1, 20, 25, 30, 40, 50, 55, 60, 100, 200, 9999). No collisions observed today, but the `9999` (signal.css portal) is a bail-out. **Fix:** define `--z-sticky:20; --z-tabs:30; --z-drawer:50; --z-backdrop:60; --z-modal:100; --z-toast:120;` in `tokens.css` and substitute mechanically.

### T8 — Motion tokens defined but unused (P3)

`--motion-press/tap/reveal/pulse` exist (`tokens.css:217–222`); components use inline transitions. Adopt in `Button`/`Modal`/cards opportunistically — not a standalone work item.

---

## 2. Cross-cutting findings (apply to many surfaces — fix once, centrally)

### C1 — Commit to the Console as the default chrome (the single highest-leverage decision)

The side-by-side is no longer close [verified live in both]:

- **IDE chrome:** the Explorer sidebar duplicates the main content (Recent runs + Projects appear twice on /home); "New spec" is a peer icon, not a hero; the editor metaphor only makes sense on the run-detail surface; the mobile layout stacks TWO bottom bars (blue status bar + tab bar ≈ 13% of a 375×812 viewport); the "Try Console ⤴" status-bar button does a raw `window.location.reload()`.
- **Console chrome:** correct hero ("Ship a change from a spec" + value-prop + Start-a-run CTA), Needs-your-attention section, single bottom bar on mobile, plan-gate indicator in the top bar, "← Classic IDE" escape hatch.

**What blocks the default flip (the complete list, from code + live):**
1. "New run" button text wraps to two lines at 375px [verified live] — icon-only below 480px (`console.css`, add `@media (max-width:480px) .con-newrun span { display:none }`).
2. ConsoleHome "Needs your attention" lacks the PR-comments row the reframe plan specifies (call `getPrComments`-backed inbox count; render alongside plan gates + failures).
3. Account sub-routes (`/billing`, `/api-keys`, `/org-members`, `/org/settings`, `/integrations`) and Discover routes (`/insights`, `/library`, `/migrations`, `/templates`) unreachable from Console nav — add an Account dropdown (top bar) + a collapsible "More" nav group (left rail).
4. Command palette covers only 6 commands — register every route (one static array; trivial).
5. C2/C3/C4 below (titles, breadcrumbs, focus) should land with the flip so the new default is complete.
Flip mechanics: invert the `localStorage es:console` check in `App.tsx:112/211` to opt-OUT (`es:ide='1'`), keep the IDE reachable for a release, then remove.

### C2 — `document.title` is never set anywhere (P1, trivial)

Every tab reads "ExecuteSpec" across 47 routes. **Fix:** add `hooks/useDocumentTitle.ts` (`useEffect` set + cleanup) and call it in each surface: run detail `"{runTitle} · ExecuteSpec"`, project `"{project.name} · {tab}"`, etc. ~20 one-line insertions.

### C3 — No breadcrumbs / in-app back affordance on nested surfaces (P1)

`/projects/:id/chat` etc. give no path context; browser-back is the only exit [verified live]. **Fix:** one shared `<Crumbs items={[{label,to},…]}>` component rendered above the content by `IdeProject*`/`IdeRunResult` (and Console equivalents): `Projects › {name} › Chat`. Mobile: collapse to `‹ {parent}`.

### C4 — No focus management on route change (P1, a11y)

Focus stays on the clicked nav button after navigation; screen-reader/keyboard users never enter the new content (WCAG 2.4.3). **Fix:** in both shells, `useEffect` on `location.pathname` → `mainRef.current?.focus()` where `<main tabIndex={-1} role='main'>`. Pair with a skip-to-content link as the first tabbable element.

### C5 — Verified score + cost invisible in lists (P1) [verified live]

The scoring system is the product's differentiator; no list shows it: home run cards (IDE + Console), Explorer rows, Console recent-runs, project cards, project Pulse run rows — all show only status pill + provider + relative time. `/runs` shows cost + a score column (renders "—" when unscored) — the only one. **Fix:** define ONE `RunRow`/`RunCard` spec and reuse: `[status pill] title (2-line clamp) · {project|Sandbox} · relTime · [score badge if scored] · [cost]`. Score badge uses the canonical `verdictFor` bands. Project cards gain: last-run verdict + open-PR count + tech-debt count.

### C6 — Verdict buckets don't sum and "this cycle" contradicts the list (P1) [verified live]

- `/runs` filter chips read "All 30 · Live 0 · Shipped 0 · Needs review 0 · Failed 2" — 28 scoreless COMPLETE runs belong to no bucket. **Fix:** add a "Done (unscored)" bucket or make "Shipped" = terminal-success-regardless-of-score and rely on the score badge for nuance; chips must sum to All.
- `/home` greeting says "0 runs this cycle" directly above 12 runs from 3h ago [verified live] (the counter reads the monthly *quota* usage, which benchmark/sandbox runs don't increment). **Fix:** either count all runs in the current month here, or relabel: "0 of ∞ plan runs used this month".
- Same string on /compose renders "· 0 / ∞ runs this cycle · ENTERPRISE tier" with a leading orphan "·" [verified live] — drop the leading separator when the first segment is empty (`IdeSurfaces.tsx` subtitle builder).

### C7 — Empty/error-state inconsistency; no Retry anywhere (P2)

Empty states vary between passive ("No runs yet.") and actionable ("No messages yet — ask about your codebase."). API-failure states render red text with no retry; the user must full-reload. **Fix:** extend the `Banner`/error primitive with `onRetry` and adopt the "empty + next action" pattern everywhere (standard strings in §5).

### C8 — Credit-spending dispatches have no confirmation (P0)

All four autofix surfaces fire a paid PATCH run on one click with no cost statement: PR-review "Autofix review" (`IdeProjectTabs.tsx:919`), tech-debt per-category "Autofix N →" (`IdeProjectMore.tsx:675`), code-review severity chips (`CodeReviewPanel.tsx`), and the run-detail "Try {provider}" reruns [verified live — sit unlabeled next to Share/Fork]. **Fix:** route all four through one `<ConfirmRunDispatch estimate=… credits=… what=…>` modal (reuse `ConfirmModal`): "This dispatches a new run (≈$X / Y credits). A PR will be opened on completion." Include the post-dispatch run link (already done — keep).

### C9 — Mobile system issues (beyond per-surface fixes)

1. **IDE double bottom-bar** [verified live]: blue status bar + tab bar stack ≈110px. Hide `.ide-statusbar` under 860px and surface tier + plan-gate inside the drawer (moot if C1 flips first — Console has one bar).
2. **Tab strips overflow with no affordance**: 10 project tabs / 10 result tabs scroll horizontally; result page shows a scrollbar [verified live], project tabs show nothing. **Fix (one CSS rule):** right-edge fade mask on `.ide-rtabs` + `scrollbar-width: thin`.
3. **Split panes don't collapse at 375**: project Runs split (`IdeProjectTabs.tsx:727`) keeps a 280px left column; Browse file pane fixed 260px. **Fix:** stack to one column under 600px; detail opens as a slide-over.
4. **Breakpoint policy:** single 860px cutover means portrait iPad (768px) gets the phone UI [verified live at 800px]. Acceptable for now — document it; revisit with a 600/900/1200 ladder during Wave C.
5. Run-detail mobile title renders ~34px × 3 lines for a truncated title [verified live] — cap `.ide-rtitle` (or equivalent) at 22px under 600px + 2-line clamp.

### C10 — Keyboard layer is one shortcut deep (P2)

⌘K exists and is good; `hooks/useKeyboardShortcuts.ts` is exported but never called (dead). **Fix:** wire it in the shells: `c`→/compose, `g r`→/runs, `g p`→/projects, `?`→shortcut sheet; register all routes in the palette (C1.4).

### C11 — `/search` exists but is orphaned; notifications use hand-rolled routing (P2)

No nav entry points at `/search` in either chrome. Notification clicks do `history.pushState + PopStateEvent` (`IdeSystem.tsx:189–200`) instead of `navigate()` — breaks on malformed targets. **Fix:** add search to top bar + palette; replace with `useNavigate()`.

### C12 — OAuth `AuthorizationRequestRepository` + session-state caveat (carried from the distributed-systems review)

The cookie-based return-URL fix landed, but Spring's default in-session authorization-request repo likely remains — verify and replace with the cookie-based repository (also listed as D8 in [`distributed-architecture-and-launch-readiness-2026-06-11.md`](distributed-architecture-and-launch-readiness-2026-06-11.md)). UI impact: intermittent "authorization_request_not_found" on OAuth.

### C13 — Quality gates to make all this stick (P1, engineering)

Zero frontend unit tests run in CI; the contrast regression test is dormant; eslint is warn-only; the bundle is one ~802KB chunk (known debt). **Fix bundled with this review's work:** wire vitest (start with `contrast.test.ts` + the T2 bridge test + `verdictFor` bands), make eslint errors fail CI, add `manualChunks` (vendor/markdown/highlight) + `React.lazy` on the heaviest routes (IdeDiscovery, IdeProjectMore, IdeBenchmark, AdminPage). pa11y-ci config exists (`.pa11yci.json`) — run it in CI on the 7 public pages after T1 lands.

---

## 3. Surface-by-surface findings + recommendations

Severity tags as defined above. Items already covered by a `T#`/`C#` are not repeated.

### 3.1 Public + auth (all standalone Signal pages — all affected by T1 first)

**/login + /register (`signal/pages/AuthPages.tsx`, `AuthLayout`)** — verdict: solid structure (OAuth-first, editor-mock brand panel, ≤920px single column), five fixes:
| ID | Sev | Issue | Fix |
| --- | --- | --- | --- |
| AUTH-1 | P1 | Password rule (12+ chars) only as low-contrast hint under the field (`AuthPages.tsx:335–338`, `--signal-text-mute` ≈3:1) | Visible rule line above the field at `--signal-text-dim`; live ✓/✗ as the user types; `aria-describedby` binding |
| AUTH-2 | P1 | No show/hide password toggle anywhere (login, signup, reset) | Eye toggle button inside the field, `aria-pressed`, type swap |
| AUTH-3 | P2 | "Forgot password?" lives inside the password field block — easy to miss / off-screen on mobile pre-focus | Move to the form footer row, ghost-link style |
| AUTH-4 | P2 | Error banners lack `role='alert'`; OAuth icons lack `aria-hidden` | Add `role='alert' aria-live='polite'` to `ErrorBanner`; `aria-hidden='true'` on decorative SVGs |
| AUTH-5 | P3 | OAuth buttons are `<a>` styled as buttons | Keep (links are fine) but ensure `:focus-visible` ring applies |

**/verify-email** — P2: magic-link verification shows static text with no spinner (looks hung); success auto-redirects in 800ms (unreadable). Fix: `<Spinner/>` during verify; on success show the message + a "Continue to sign-in" button (keep a 3s auto-redirect).
**/auth/callback (`AuthCallbackPage.tsx`)** — P2: spinner references a keyframe not imported on this page (static spinner); error auto-redirects after 2s. Fix: use the shared `Spinner`; replace auto-redirect with a "Back to sign-in" button. P3: preserve deep-link in the no-`redirect` fallback.
**/cli-login (`CliLoginPage.tsx`)** — P1: hardcoded colors (T3); P2: malformed-state error says "re-run the command in your terminal" with no client context — reword: "Your CLI/extension sent an invalid sign-in request. Return to your terminal and run `executespec login` again."; inline-style buttons lack hover/focus — use `.signal-btn` classes.
**/welcome (onboarding)** — P1: Step-1 spec selection isn't carried into "Use sandbox for now" (navigates to /compose without pre-filling the chosen spec — read `signal:pending-spec` in IdeCompose; verify the handoff). P2: cards need `aria-pressed` selection state; GitHub-App card should state permissions + uninstall path.
**/forgot-password + /reset-password (`ExtraPages.tsx`)** — AUTH-1/AUTH-2 apply; P2: success message visible only ~1s before redirect — same fix as verify-email.
**/accept-invite** — P3: no Decline affordance; add a secondary "Decline" (or at minimum "Not now" link) so the only exit isn't closing the tab.
**/pricing (`PublicPricingPage`)** — **P0 = T1 breakage** [verified live]. After T1: P1 maintainability — the cards are hand-synced to the DB (the 2026-06-10 truth pass aligned them; values currently correct [verified live: FREE $0/15 runs/$3 cap · PRO $29/30 runs/$5 cap · TEAM $39/seat · ENT custom]) — source them from `GET /api/billing/plans` like `IdePlans` does. P2: state TEAM is monthly-only on the card; give RECOMMENDED a reason line ("Most popular for teams on their own keys"); FREE card CTA should be a real `/register` link.
**/trust, /terms, /privacy, /dpa (`LegalPages.tsx`, `TrustPage`)** — P2: add `id` anchors per `h3` for deep-linking; expand "8 layers" with the actual list (it exists in CLAUDE.md §Security); auto-stamp "Last updated" from git at build (a 5-line vite plugin) — the hand-set dates have already drifted once. T6 prose carve-out applies here first.
**/shared/:token (`MiscPages.tsx`)** — P2: this is a growth surface; ensure a tasteful "Built with ExecuteSpec — see the run" CTA on shared reports (top-right, not a banner), verified in both themes (T1 applies).
**website/index.html** — P2: verify every BreadcrumbList schema section exists on-page with matching `id`s; confirm `og-hero.png` exists <200KB; CTA-above-the-fold check at 375px. (Marketing site is otherwise out of scope here.)

### 3.2 Chrome

**IdeWorkspace (`ide/IdeWorkspace.tsx`)** — kept as legacy fallback per C1; do only: remove the reload-based "Try Console ⤴" once Console is default; hide the status bar on mobile (C9.1). The Explorer/main duplication and editor-metaphor issues are accepted-as-is for a fallback chrome.
**Command palette (`ide/IdeKit.tsx:175–258`)** — good bones (fuzzy match, keyboard nav, live recent runs). Add: all routes (C1.4), `description` field on `PaletteCmd`, and section headers (Go / Runs / Account).
**ConsoleShell + ConsoleHome (`console/`)** — the C1 completion list, plus: P2 run rows say "no project · 3h ago" for sandbox runs [verified live] → "Sandbox · 3h ago"; P2 add score/cost per C5; P3 project cards in the home grid show name+description only → add last-run verdict chip.
**PlanGateIndicator** — already excellent; no changes. Keep its placement in the Console top bar.

### 3.3 Run loop

**/home — IdeWelcome (`ide/IdeSurfaces.tsx:74–297`)** — superseded by ConsoleHome at the C1 flip. Until then: C6 counter fix; C5 score on cards; P2 empty-state copy should state the cost model ("Starter specs run in a sandbox · 1 credit ≈ $5 · no card needed").

**/compose — IdeCompose (`ide/IdeSurfaces.tsx`)** — the funnel's top. Verdict: right fields, wrong scaling.
| ID | Sev | Issue | Fix |
| --- | --- | --- | --- |
| RUN-1 | **P0** [verified live] | Project picker renders ALL projects as chips — ~50 chips ≈ 6 rows on desktop, dozens of full-width rows on mobile; PROVIDER/MODEL/MODE/CAP + **Run spec** end up screens below the fold | Replace with a searchable combobox (reuse palette list mechanics): default collapsed showing `Sandbox (no repo)` + 4 most-recent projects as chips + "All projects…" opener with type-ahead. Persist last selection per session |
| RUN-2 | P1 | `MODEL · BETA` select truncates its own default label ("Recommended (defaul…") [verified live] | Widen the select / shorten label to "Recommended" with the resolved model as a sub-line |
| RUN-3 | P1 | Figma URL input unlabeled (placeholder only, `IdeSurfaces.tsx:696`); Provider/Model/Mode/Cap groups are styled divs, not fieldsets | `<label htmlFor>` each control; wrap option groups in `role='group'` + `aria-labelledby` |
| RUN-4 | P1 | No mobile layout for the option grid — selects render side-by-side at 375 | `@media (max-width:600px)`: stack options full-width |
| RUN-5 | P2 | Mode copy "Production (plan gate) / Prototype (auto)" is jargon at the moment of choice | "Production — pauses for your approval · Prototype — runs straight through (no approval)" |
| RUN-6 | P2 | "USD · max $∞" for uncapped tiers [verified live] | "No platform cap on your plan" text instead of $∞ |
| RUN-7 | P2 | Estimate card has no freshness/refresh | "Estimate from project history · updated Xm ago" + refresh icon-button |

**/plan/:id — IdePlan (`ide/IdePlan.tsx`)** — verdict: strong gate, copy fixes:
- P1 credit copy ambiguity (`:142–145`): "fully refunded if the run fails" → "Charged on approval. Fully refunded if the run fails (build broken or run errored). Cancelling before completion refunds unspent credit."
- P1 non-AWAITING statuses render a bare badge — add context banner: "This run is {status-phrase}. This page updates automatically." (it already redirects from /result; make /plan equally self-explanatory).
- P2 "Revise plan" affordance is a low-contrast ghost (`:156–183`) — promote to a visible secondary button "✎ Request changes"; `maxLength={2000}` + counter on the feedback textarea.

**/live/:id — IdeLive (`ide/IdeSurfaces.tsx:758–1070`)** — verdict: genuinely good live surface (SSE + poll fallback, cancel, dual cost bars). Fixes:
- P1 log legibility: type labels are raw enums color-coded by a cryptic mapping — map to friendly labels+icons (`🔧 tool` / `🧠 model` / `⚠ warning`), and label the two cost bars ("vs estimate" / "vs hard cap") since they look identical when estimate≈cap.
- P2 SSE status chips are transport jargon ("reconnecting…", "polling") → "Live", "Reconnecting — brief delay…", "Live (5s refresh)".
- P2 cancel button: style as danger-ghost (`var(--ide-red)`) + keep the existing confirm.
- P2 mobile hides the task list entirely (`ide.css:870–879`) → inline one-line summary above the log: "▶ task 2/3 — {title}".
- P3 "Waiting for the first tool call… the agent is warming up." → append "(usually 30–60s)".

**/result/:id — IdeRunResult (`ide/IdeRunResult.tsx`, flagship)** — verdict: verdict-first header is right; the rest needs an information-architecture pass.
| ID | Sev | Issue | Fix |
| --- | --- | --- | --- |
| RES-1 | **P0** [verified live] | "WHAT NOW" = flat row of 10–15 equal buttons; "Push to GitHub" is the blue primary even for sandbox/bench runs; "Try openai"/"Try gemini" lowercase | Three groups: **Primary** (context-aware single filled CTA: PR exists→"Open PR"; pushable→"Push to GitHub"; else→"Iterate"), **Secondary ghosts** (Preview · Deploy · Report PDF · Share · Try another provider — capitalized, behind C8 confirm), **Overflow "⋯" menu** (Fork/Archive/Delete, delete stays confirmed) |
| RES-2 | P1 | 10 tabs (Overview Code Tasks Timeline Logs Validation Cost PR Tests Chat) [verified live] | Keep 5: **Overview · Code · Checks · PR · Chat**. "Checks" merges Validation+Tests+Cost as sections; Tasks+Timeline+Logs fold into Overview (timeline) + a "Logs" disclosure. Preserve deep-links: old tab query params map to new anchors |
| RES-3 | P1 [verified live] | Scoreless runs (PATCH/bench/legacy) show a wall of "—" in Signals with no explanation | When all signals are unmeasured: collapse the card to one line — "No verified score for this run type (benchmark/patch run)." |
| RES-4 | P2 | Header title truncates mid-word without ellipsis [verified live]; 28px score co-equal with verdict pill | 2-line clamp + ellipsis + full title in `title=`; score at 20px after the verdict pill |
| RES-5 | P2 | Validation issues lack "jump to code"; diff view lacks line numbers | Issue rows link `file:line` → Code tab + highlight; add line-number gutter in diff mode |
| RES-6 | P2 | Logs tab is a static replay with no freshness signal | "Last updated relTime · Refresh" header row |
| RES-7 | P2 | PR tab "No PR yet" unexplained | "A PR opens when you push. Requires a GitHub token in Integrations →" |
| RES-8 | P3 | Chat auto-scroll yanks the view on new messages | Only auto-scroll when already near bottom |

**/runs — IdeRuns (`ide/IdeMore.tsx`)** — verdict: best list in the app (filters, counts, cost) [verified live]. Fixes: C6 bucket sum; C5 row spec adoption; P2 add a text filter (id/title substring); P3 row hover affordance.

### 3.4 Project workbench

**/projects (`IdeProjects.tsx`)** — P2: health cards should surface last-run verdict + open PRs + debt count (C5); empty-filter copy per §5.
**/projects/:id (`IdeProject.tsx`)** — **P1 (IA):** the landing tab is the Composer; most arrivals are status checks. **Fix:** land on Pulse (`/projects/:id` → redirect `…/pulse`), give Pulse a compact "New spec" entry card up top; Composer stays the second tab. (Cheaper alternative if the redirect is contentious: status strip above the composer — but the redirect matches the control-plane direction.)
**Tabs strip (`IdeProjectTabs.tsx:105–123`)** — C9.2 fade affordance; P2 hide count badges under 600px.
**Pulse** — verdict: excellent live polling pattern (12s, hidden-tab-aware) — keep. P2: metric labels need Glossary tooltips (Avg score bands; "Spend · 30d" scope); P2 add codemap-stale recovery CTA linking to Repos (the Health sub-tab has the same gap, `IdeProjectMore.tsx:712`).
**Runs split-pane** — C9.3 stack at 600px; P2 right-pane empty state already good ("Pick a run on the left…").
**Repos** — verdict: Sync-vs-Regenerate tooltips and real Language/Size are right [post RL-2026-05-025/026]. P2: webhook Install button needs a purpose `title`; P3: confirm-on-Regenerate ("LLM call, ~1 min").
**Code → 6 sub-tabs** — Browse: mobile `<select>` over 100+ files is unusable — searchable filter list (`IdeProjectMore.tsx:625`); Tech debt + Code review: C8 confirms on every Autofix button; Standards: violations need a "Learn more"/rule link; Docs: **P1 generation/upload status never polls** — "Generating…" and "EMBEDDING" can sit forever; poll every 2–3s until terminal (mirror the project-settings doc-indexing poll that already exists).
**Chat** — verdict: good (TEAM-gate handled, markdown both directions, Enter/Shift-Enter). P2: "⌁ Compile to spec" → "Generate spec from chat" + tooltip; "Compact" needs a `title` explaining summarize-and-keep-recent.
**Architecture** — verdict: ArchDocView lazy sections are smart. P2: version-diff needs column labels for A/R/M; mobile `maxHeight: 70vh` → `40vh` under 600px (`IdeProjectMore.tsx:1256`); T6 prose carve-out applies.
**Members** — P2: invite copy under-explains (magic-link validity, revocation); external-sharing upsell links `/org/settings` for non-admins → gate the link, show "Ask your org admin" otherwise (`:1840–1860`).
**Activity** — P2: stack the 3-column rows vertically under 600px.
**Settings** — P2: branch-pattern variables need an example expansion line (`:2216`); memory "snapshot version —" needs a Glossary entry.

### 3.5 Settings / billing / org / system

**/account — IdeAccount (`ide/IdeMore.tsx`)** — **P1 (IA):** settings sprawl across 8 routes with no grouping. **Fix:** restructure the Account sidebar into three labeled groups — *Plan & billing* (/billing, /plans), *Organization* (/org-members, /org/settings, /integrations, /api-keys), *Developer* (/deployments, PAT tokens) — same links, headed sections (`IdeMore.tsx:575–604`). P2: notification-prefs toggle busy-state is a 0.6-opacity blink — use a clearer disabled+spinner treatment.
**/billing — IdeBilling** — P1: the FREE→BYOK upsell card says "PRO" but routes to a page where TEAM is the actionable tier (`IdeBilling.tsx:220–252`) — rewrite around TEAM + deep-link `/plans`; P2: invoice empty state needs timing + a subscription-confirmation pointer; P2: subscription summary wraps poorly at 375 — grid `auto-fit minmax(200px,1fr)`.
**/plans — IdePlans** — P1: per-card sub-copy inconsistency (only TEAM mentions seats) — normalize: FREE "For solo developers · 15 runs/mo", TEAM "Unlimited runs on your keys · 1–20 seats", ENTERPRISE "SSO · SLA · custom models"; P2: PRO grandfathered state needs an explicit "You're on a legacy plan" callout rather than a disabled button surprise.
**/api-keys — IdeApiKeys (`IdeOrg.tsx`)** — P1: write-only inputs need the "Leave blank to keep your current key" hint + per-provider Connected pill aligned to the row; P1: DeepSeek + Fireworks rows show for all tiers while org-level gates them to ENTERPRISE — match the gate or add an upgrade hint; P2: hold success message ≥4s.
**/org/settings — IdeOrgSettings (incl. F3 grid)** — P1: the model-matrix grid needs a 3-line legend (what PLANNING/WORKHORSE/LIGHTWEIGHT mean; what "Default · {model}" resolves from; how to revert) before the cards (`IdeOrg.tsx:1038–1044`); P2: matrix save → ConfirmModal ("applies to all org runs"); P2: SSO provider chips overflow at 375 → `<select>` under 480px; P3: Suspend/Reinstate both always render — render one by state.
**/integrations — IdeIntegrations (`IdeSettings.tsx`)** — P1: status pills equate "token saved" with "Connected" — add per-provider "Test connection" actions surfacing the existing validate endpoints, pill states: Connected ✓ / Saved (unverified) / Not set; P2: multi-field `PromptModal`s need per-field hint lines (Jira base-URL example etc.).
**/admin — IdeAdmin** — P1: 27 feature-flag toggles apply instantly with no confirm — `ConfirmModal` per flip (dangerous=true) + show flag description; P2: Users/Orgs tables need pagination + search before user counts grow.
**/notifications, /search, /audit (`IdeSystem.tsx`)** — C11 routing fix; P2 "Mark all read" busy-disable; P2 search empty state should echo the query + suggest formats; P2 notification filter tabs → `<select>` under 480px.
**/insights, /library, /migrations, /templates (`IdeDiscovery.tsx`)** — P2: detail pages need the C3 crumb ("← Library"); P3: insights CSV export; decision: if these don't earn traffic post-Console-flip, fold Library+Migrations under a single "Examples" surface (they cost nav surface area).
**/deployments** — no findings beyond C5/C7 patterns.
**/benchmark — IdeBenchmark** — operator-only; already verified live during its build; exempt from this pass.

---

## 4. What is GOOD — explicitly keep (do not "fix")

1. **Verdict language**: single `verdictFor` source (≥85 Shippable / 70–84 Needs review / <70 Failed) used consistently.
2. **PlanGateIndicator** — polling, auto-dismiss, popover → /plan. The model for every future "attention" chip.
3. **IdeLive**: SSE-with-poll-fallback + cost bars + cancel; run-state auto-redirects on /result (non-terminal → /plan | /live).
4. **Modal/Loaders primitives**: focus-trap, ESC, backdrop, mobile slide-up; shimmer loaders.
5. **Hidden-tab-aware 12s polling pattern** (workspace lists, Pulse, ConsoleHome) — efficient and consistent.
6. **IdeKit code viewer**: tokenizer + LCS diff at ~0 bundle cost (vs Monaco).
7. **Token discipline overall**: only ~6 hardcoded color sites in ~75K LOC (T3) is unusually clean.
8. **Light theme inside the app chrome** [verified live on /compose]: fully usable — T1 is a public-page wiring bug, not a palette gap.
9. **Console hero + attention-first dashboard** [verified live] — the reframe is working; finish it rather than re-litigating it.
10. **Suggest-first SpecChat** (auto-propose on open) and the Markdown pipeline (GFM both chromes).

---

## 5. Copy standards (apply mechanically)

| Where | Current (verbatim) | Replace with |
| --- | --- | --- |
| /compose subtitle | "· 0 / ∞ runs this cycle · ENTERPRISE tier" | "ENTERPRISE · no run limit" (omit empty segments + leading dot) |
| /home greeting | "0 runs this cycle" | "{n} runs this month" (all runs) or "0 of {cap} plan runs used" |
| Run rows (Console) | "no project" | "Sandbox" |
| Result actions | "Try openai" / "Try gemini" | "Try OpenAI" / "Try Gemini" (provider display names from the model matrix) |
| Plan gate | "fully refunded if the run fails" | "refunded if the run fails (build broken or run errored)" |
| Live status | "reconnecting…" / "polling" | "Reconnecting — brief delay…" / "Live (5s refresh)" |
| Live empty | "Waiting for the first tool call… the agent is warming up." | + " (usually 30–60s)" |
| Project chat | "⌁ Compile to spec" | "Generate spec from chat" |
| Mode picker | "Prototype (auto)" | "Prototype — runs straight through (no approval)" |
| Cost cap | "USD · max $∞" | "No platform cap on your plan" |
| Empty states | "No runs yet." / "No repos linked." | Always pair with the action: "No runs yet — describe a change in New spec to start." / "No repos linked — link one in the Repos tab." |
| CLI login error | "re-run the command in your terminal" | "Your CLI/extension sent an invalid sign-in request. Return to your terminal and run `executespec login` again." |

Standing rules: sentence case everywhere (no Title Case buttons); status enums never shown raw to users (map COMPLETE→Complete etc. — already mostly true); every em-dash placeholder ("—") in a metrics context needs either a tooltip or a one-time legend; provider names always display-cased from the registry.

---

## 6. Implementation roadmap

**Wave A — bugs + funnel blockers (~1 week, ship before any marketing push):**
1. T1 theme unification (+ pa11y/Playwright check over the 7 public pages in both themes)
2. RUN-1 compose project combobox
3. C8 dispatch confirmations (one shared modal, 4 call sites)
4. RES-1 action-row grouping + RES-3 scoreless-signals collapse
5. C6 counters (buckets sum + cycle copy) and T3 hardcoded-color sweep (half-day together)
6. TIER-1 /plans truth fix (cards from `GET /api/billing/plans`, kill phantom yearly toggle, settle PRO's presence) + TIER-2 /billing upsell retarget + TIER-3 /api-keys gate-disable (§7)

**Wave B — commit to Console (1–1.5 weeks):**
6. C1 completion list (mobile New-run, PR-comments attention row, Account dropdown + Discover group, palette = all routes)
7. C2 titles + C3 breadcrumbs + C4 focus management (land with the flip)
8. C5 unified RunRow/RunCard with score+cost (IDE + Console lists)
9. Flip default → Console (`es:ide` opt-out); keep IDE one release; then strip the dead chrome
10. Project landing → Pulse (3.4)

**Wave C — mobile hardening (~1 week, verify each at 375×667 + 768×1024 per the standing rule):**
11. C9.2 tab-strip fades · C9.3 split-pane stacking · RUN-4 compose option stacking · run-detail title clamp · billing/org wraps · activity stacking · mobile file search (3.4 Code)
12. IDE double-bar removal (if the IDE survives as fallback)

**Wave D — polish + guardrails (parallel/ongoing):**
13. C13: vitest (contrast + bridge + verdict tests) → eslint errors fail CI → route-level code-splitting → pa11y in CI
14. §5 copy sweep; AUTH-1/2 password UX; integrations Test-connection; F3 legend; admin flag confirms; T4 amber bump; T5 focus ring; doc-status polling (3.4); T6 prose decision; T7 z-scale
15. Discovery-surface consolidation decision after 30 days of Console analytics

**Acceptance per wave:** Wave A — /pricing legible in both themes from any prior localStorage state; a 50-project org reaches Run-spec in ≤1 scroll; no paid dispatch without a cost-stating confirm; /runs chips sum. Wave B — every route reachable from chrome nav or palette in ≤2 interactions; tab titles unique; axe/pa11y pass on Console home. Wave C — all listed surfaces usable one-handed at 375 with no horizontal scroll except intentional tab strips (which show fades).

---

## 7. Addendum — tier-differentiated pass (fresh logins via `~/Desktop/local_creds.txt`)

> The §0–§6 visual pass ran on a leftover ENTERPRISE session. This addendum re-ran the tier-sensitive surfaces with **fresh logins** as `revalidate-free@test.local` (FREE) and `revalidate-team@test.local` (TEAM) at 1440×900, dark. All items below are [verified live].

### TIER-1 — In-app /plans contradicts the public pricing page AND the DB ladder (**P0**, `ide/IdeBilling.tsx` IdePlans)

Observed as FREE: three cards (FREE / TEAM / ENTERPRISE) — **no PRO card** (public /pricing lists PRO $29/mo as LISTED); TEAM priced **"$31/seat/mo"** with sub-copy **"Annual billing."** under a **default-selected "Yearly · save 20%"** toggle — but TEAM is **monthly-only at $39/seat** (V165 deactivated yearly rows; no replacement landed); TEAM bullets claim **"500 credits / seat"** and **"$25 hard cost cap"** — the precise invented limits RL-2026-06-019 removed from the public page (TEAM has *no* per-run cap; `resolveRunCostBudget` returns −1). A FREE user clicking **Upgrade** on this card is promised a price, cadence, and limits the backend doesn't implement — checkout will bill $39 monthly or fail.
**Fix:** render the cards from `GET /api/billing/plans` (the YAML/DB-driven endpoint — same remedy §3.1 prescribes for the public page; do both from one shared mapper). Remove the Monthly/Yearly toggle for tiers with no yearly row (or hide the toggle entirely until a yearly TEAM price exists). Decide PRO's presence once (it is LISTED in the DB — either show it here or change its lifecycle state) so /billing, /plans, and /pricing agree. Add the same "compare" link from /billing's Change-plan button so there is exactly one plans surface.

### TIER-2 — /billing BYOK upsell dead-ends (**P1**, `ide/IdeBilling.tsx:220–252`)

The FREE billing page's upsell card reads "Bring your own keys, bypass the $3 cap. **PRO** uses your Anthropic / OpenAI / Gemini keys directly… **See PRO**" → links to /plans → **which has no PRO card** (TIER-1). Funnel dead-end confirmed end-to-end. Also names only 3 providers; BYOK now spans 5 (V237 added DeepSeek + Fireworks).
**Fix:** reword around the tier that is actually purchasable on /plans (TEAM today): "Upgrade to TEAM — run on your own Anthropic / OpenAI / Gemini / DeepSeek / Fireworks keys, no platform cap." Deep-link with the target card highlighted (`/plans?highlight=TEAM`).

### TIER-3 — /api-keys on FREE: gate banner + fully enabled form (**P1**, `IdeOrg.tsx` IdeApiKeys)

"BYOK is a PRO+ feature. Upgrade →" renders correctly — but **all five provider inputs and their "Save & validate" buttons remain enabled** below it; a FREE user fills the form and only learns at submit (server 403). Also every placeholder reads "(sk-…)": correct for OpenAI/DeepSeek, wrong for Anthropic (`sk-ant-…`), Gemini (`AIza…`), Fireworks (`fw_…`).
**Fix:** when the tier gate is closed, render rows `disabled` with a single Upgrade CTA; per-provider placeholder examples; keep the existing server-side enforcement as the backstop.

### TIER-4 — F3 model-matrix grid: correct data, invisible read-only state (**P2**, `IdeOrg.tsx` IdeOrgSettings)

As TEAM, all 18 selects are properly `disabled` and an upgrade note exists (verified via DOM) — **but disabled selects render visually identical to enabled ones** (no dimming, normal chevrons), so a TEAM admin will click-and-wonder. **Fix:** `.ide-root .ide-select:disabled { opacity:.55; cursor:not-allowed; }` + move the ENTERPRISE note directly above the first tier card.
**Important non-bug:** the grid shows `Default · claude-sonnet-4-6` / `gpt-5.4` / `gemini-3.5-flash` for ALL tiers including Lightweight. That is **faithful to the running YAML** — commit `387c3c39` (2026-06-02) deliberately pinned all tiers to flagship per provider, superseding the haiku/mini lightweight design. The *docs* (root CLAUDE.md tier table, the YAML's own "LIGHTWEIGHT tier for Anthropic" note on the haiku entry) are now stale, and the cost posture of LIGHTWEIGHT agents changed — flagged separately as a config/docs reconciliation task; do NOT "fix" the grid.

### TIER-5 — TEAM org-settings offers SAML SSO with enforcement copy (**P2**, `IdeOrg.tsx` SSO section)

TEAM admins see provider chips (Google OIDC / **Okta SAML / Azure AD SAML**) with "Members signing in with another method are blocked once SSO is enforced" — while [`docs/sso-saml-wiring.md`](../sso-saml-wiring.md) states SAML login/enforcement is **not implemented** (config storage only) and /plans sells "SSO + SCIM" as ENTERPRISE. **Fix:** label the SAML options "config only — contact us to enable" (or hide them below ENTERPRISE), and align the enforcement sentence with what the backend actually does per provider.

### TIER-6 — Small fixes from this pass (P3)

`/billing` "Successful runs (30d): —" should read `0` for a zero-run account; the §5 copy rule (em-dash needs a tooltip or a real zero) applies.

### What the tier pass confirmed is GOOD

- **FREE first-run home** [verified live]: "Ship your first verified PR — runs in a sandbox project, costs 1 credit, takes a few minutes" + samples + GitHub connect — the best empty state in the app, and it answers §3.3's cost-copy concern for the home surface.
- **Quota counter works for capped tiers**: "0 / 15 runs this cycle" renders correctly for FREE (C6's complaint stands only for the unlimited-tier "0 / ∞" + benchmark-data case).
- **Compose tier gating is correct**: FREE sees no MODEL·BETA selector (ENTERPRISE-only) and "max $3" cap; with a single project the form fits above the fold — confirming RUN-1 is purely a many-projects scaling failure.
- **Seats section copy** (TEAM): prorated-sync + can't-reduce-below-active-members explanation is exactly the right level of detail.
- **External-sharing toggle** (TEAM): clear consequence copy, correct default.

---

## Appendix — live-verification evidence log (2026-06-11)

Local backend (`:8080`, real data: 30 TB runs + ~50 eval projects) via Vite dev server; Chrome viewport runs at 1440×900 and 375×812.

| Surface | State checked | Key observation |
| --- | --- | --- |
| /home (IDE) | dark, 1440 + ~800 | sidebar/main duplication; no scores on cards; "0 runs this cycle" over 12 visible runs; double bottom-bar on mobile widths |
| /runs | dark, 1440 | best list (cost+score cols); buckets don't sum (All 30 / Shipped 0 / Failed 2) |
| /result/:id (TB run) | dark 1440 + light 375 | 10 tabs; all-dash Signals; 10-button action row, blue "Push to GitHub" primary; "Try openai/gemini"; mid-word title truncation; mobile title ~34px ×3 lines; tab strip h-scrolls with visible scrollbar |
| /compose | dark+light, 1440 + 375 | ~50-chip project wall pushing options+CTA below fold (desktop) / dozens of screens (mobile); MODEL select label truncated; "max $∞"; orphan "·" in subtitle |
| Console /home | light, 1440 + 375 | hero + attention-first layout works; single mobile bar; "New run" wraps at 375; rows lack score/cost; "no project" noise |
| /pricing | light-mode mismatch state | **dark cards on light page, illegible bullets, blank RECOMMENDED badge** (T1) |
| Theme toggle | both chromes | `es:theme`/`data-theme` flips app chrome only; `signal:theme`/`data-signal-theme` governs public pages (mechanism confirmed in `main.tsx:35–47`, `useSignalTheme.ts:39–47`) |
| Browser console | whole session | zero JS errors/warnings across all navigation |

**Tier pass (fresh logins per `~/Desktop/local_creds.txt`; the rows above ran on a leftover ENTERPRISE session):**

| Surface | Account | Key observation |
| --- | --- | --- |
| /login form | — | submitted live (email+password) for both users; redirects to /home; dark brand panel renders while app chrome was light (T1 boundary) |
| /home, /compose | revalidate-free (FREE) | first-run empty state excellent; "0 / 15" quota correct; no MODEL selector; "max $3"; one-chip project list = form fits above fold |
| /plans | FREE | **TIER-1**: no PRO card; TEAM "$31/seat/mo · Annual billing" + default Yearly-save-20% toggle; "500 credits/seat" + "$25 hard cost cap" bullets |
| /billing | FREE | **TIER-2**: "See PRO" upsell → plans page without PRO; "Successful runs (30d) —" |
| /api-keys | FREE | **TIER-3**: upgrade banner + all 5 provider forms enabled; "(sk-…)" placeholder on non-sk providers |
| /org/settings | revalidate-team (TEAM) | **TIER-4/5**: 18/18 matrix selects disabled but visually enabled (DOM-verified); grid faithfully renders the `387c3c39` flagship-pinned YAML; SAML SSO config + enforcement copy shown to TEAM |
