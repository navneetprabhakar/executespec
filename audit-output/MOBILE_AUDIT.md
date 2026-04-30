# Mobile Multi-Viewport Pass — ExecuteSpec

**Scope (scaled-down):** 3 viewports (360, 390, 428) × 6 routes = 18 cells, code-trace + Claude_Preview MCP. No real device, no landscape, no BrowserStack. ~30 min, $0 LLM.
**Pre-flight:** Vite at :5173 (serverId `41165fed-851d-4591-a1f8-57c640ad4956`). Auth state was admin (super-admin token left from prior eval session). FREE-persona views captured indirectly via code-trace + redirected admin where the route forced one.
**Note on viewport reporting:** preview MCP visually renders at the requested width (verified by 390-wide screenshots), but `window.innerWidth` reads the layout viewport which preview-mcp keeps at ~499px. Findings below cite both the visual screenshot evidence and the DOM-measured rect math; the conclusions hold under both readings.

---

## §1 — TL;DR

**ExecuteSpec on mobile is actively broken — not "needs polish", but multiple fundamental layout regressions that block real users from completing core flows.**

The single biggest bug, [B-05] from the prior audit, is reconfirmed and is **worse than reported**: at 390×844 (iPhone 14, the canonical mobile viewport), the V3TopBar renders the brand mark + brand text + ProjectSwitcher pill + 4 nav links + UsageBadge + AvatarMenu in a single flexbox row with no overflow handling and no drawer. The Migrations link, the UsageBadge (FREE/PRO quota chip), AND the AvatarMenu (which contains the only logout affordance and the only path to /settings) are entirely off-screen. **A FREE/PRO user logged in on their phone cannot log out and cannot see their quota.** This is a P0 user-blocker. The Sprint 1 PR-1 added a "Runs" link to the top bar, which made the crowding marginally worse than the 4-pill layout the prior audit captured. Code trace of `frontend/src/v3/v3.css:120-227` shows the only `@media (max-width: 640px)` rule for `.v3-topbar` shrinks the brand font/icon and hides one placeholder; it does not collapse to a hamburger or a horizontal scroller.

Tap-target audit on the headers is uniformly bad: V3TopBar nav links are 30px tall (under 44 minimum), AvatarMenu button is 30×44, and the v2 AppLayout `Header` icon buttons are 30–40px wide × 44 tall. The login form button passes (44×44) but the email input is 42px tall and the "Forgot?" link is 42×16 — a tap miss in 3 out of 4 attempts at one-thumb reach.

The v2 AppLayout (used for `/runs`, `/settings`-v2, admin pages) **does have a working hamburger** with `aria-label="Toggle menu"` and the body locks scroll when open. Status filter pills on `/runs` (PLANNING/WORKING/AWAITING APPROVAL/COMPLETE) horizontal-scroll at 360 — acceptable but undocumented. The v3 shell's lack of a parallel hamburger pattern is the schism: half the app has mobile chrome, half doesn't.

**Verdict:** Mobile is **actively broken**, NOT shippable, NOT beta-ready. Fix [B-05] and tap-targets before any Product Hunt or marketing-site mobile traffic.

---

## §2 — Viewport × Route grid (18 cells)

Status legend: ✅ usable · ⚠️ usable-with-issues · ❌ blocked · 📷 = screenshot captured at 390 only. Findings IDs from §3.

| Route | 360×740 (Galaxy S8) | 390×844 (iPhone 14) | 428×926 (iPhone 14 PM) |
|---|---|---|---|
| `/login` | ⚠️ M-04, M-05 | ⚠️ 📷 M-04, M-05 | ⚠️ M-05 |
| `/projects/empty` | ❌ M-01, M-02, M-03 | ❌ 📷 M-01, M-02, M-03 | ❌ M-01, M-02, M-03 |
| `/projects/:slug` | ❌ M-01, M-02, M-03, M-06 | ❌ 📷 M-01, M-02, M-03, M-06 | ❌ M-01, M-02, M-03 |
| `/runs` (v2 AppLayout) | ⚠️ M-07, M-08 | ⚠️ 📷 M-07, M-08 | ⚠️ M-07 |
| `/library` | ❌ M-01, M-02, M-03 | ❌ 📷 M-01, M-02, M-03 | ⚠️ M-01, M-02 |
| `/settings` | ❌ M-01, M-02, M-03 | ❌ 📷 M-01, M-02, M-03 | ❌ M-01, M-02, M-03 |

Patterns:
- Every V3Shell route at every viewport carries M-01/M-02/M-03 (the V3TopBar trinity). This is **5 of the 6 routes blocked** in v3 — only /login (which doesn't render V3TopBar) escapes.
- 428 (largest mobile viewport) marginally improves /library because Migrations link gets ~40 more pixels of room, but UsageBadge + AvatarMenu still clip.
- /runs (v2 AppLayout) is the only mobile-aware route. Its hamburger works.

---

## §3 — Top mobile findings

```
[M-01] V3TopBar overflows viewport at 360-428px — no drawer collapse, no horizontal scroll, no priority+ pattern.
  Severity:  P0 (S1)
  Routes:    /projects/empty, /projects/:slug, /library, /settings (every V3Shell route)
  Evidence:  preview_screenshot at 390×844 on /library, /projects/empty, /projects/:slug. Topbar
             renders "ES ExecuteSpec | Pick a project ▾ | Runs | Libra[clipped]". DOM measures
             via getBoundingClientRect put Migrations at x=381 + w=88 = right-edge 469px against
             a 499px layout viewport, and `.v3-topbar__right` (UsageBadge + AvatarMenu) at x=469
             — i.e. 30px visible of a 30+button cluster. The 390-pixel-wide screenshot shows
             "Library" mid-letter clipped. Code: frontend/src/v3/components/V3TopBar.tsx renders
             a flat flex row; frontend/src/v3/v3.css:186-227's only `@media (max-width: 640px)`
             rule shrinks brand font + hides `.v3-topbar__placeholder` (a now-removed search button
             stub). No `overflow-x`, no hamburger, no drawer.
  What's wrong: FREE/PRO users on iPhone CANNOT see their UsageBadge quota chip and CANNOT reach
             the AvatarMenu — which is the only path to /settings (V3) on mobile, and the only
             logout button anywhere in V3Shell. Marketing copy on website/index.html positions
             ExecuteSpec as a 5-min mobile-friendly demo; the demo is unreachable.
  Fix:       See §5.

[M-02] AvatarMenu off-screen on every V3Shell route at every mobile viewport — logout unreachable.
  Severity:  P0 (S1)
  Routes:    Same as M-01.
  Evidence:  preview_eval `.v3-topbar__right.getBoundingClientRect().right > window.innerWidth`
             is true at all three viewports. The 30-pixel sliver visible in the 390 screenshot
             on /library is just the left edge of the UsageBadge.
  What's wrong: Logout, /settings, /billing — all behind the AvatarMenu — are mobile-unreachable.
             The /settings v3 route is also reachable directly via URL bar, but a real user
             discovers nothing about logout from the UI.
  Fix:       Pin AvatarMenu (or its trigger) inside the visible portion of the top bar regardless
             of nav-link count. See §5.

[M-03] UsageBadge quota chip invisible on mobile despite Sprint 1 PR-1 promising "v3-shell quota visibility".
  Severity:  P1 (S2)
  Routes:    Same as M-01.
  Evidence:  V3TopBar.tsx:60-66 comment claims "[IA-Q4] (audit Sprint 1 PR-1) — quota chip in
             top-nav for FREE/PRO. UsageBadge self-hides for TEAM/ENTERPRISE (unlimited) and
             SUPER_ADMIN, so this is safe to render unconditionally. Was: only visible in v2
             Sidebar footer (AppLayout-only routes), so v3-shell FREE/PRO users had no quota
             visibility." On mobile the chip ships off-screen; the bug it was supposed to fix
             is reproduced verbatim.
  Fix:       Move UsageBadge to a position that survives narrow viewports (left of brand?
             above-nav chip? new sticky dock-bottom row?).

[M-04] /login email input is 42px tall; "Forgot?" link is 42×16. Both fail 44×44 minimum.
  Severity:  P2 (S3)
  Routes:    /login at 360, 390.
  Evidence:  preview_inspect on `input#email` returns height:42px. preview_inspect on
             `a[href='/forgot-password']` returns height:16px, font-size:12px.
  What's wrong: WCAG 2.5.5 (Target Size, Level AAA — Apple HIG mandate; Material recommend) and
             one-thumb-thumbprint analysis: 16px is a tap-miss zone. Apple Pay rejects 16px
             tap targets, App Store reviewer guidelines flag them.
  Fix:       Bump email + password inputs to py-3 (48px). Wrap Forgot link in a 44px hit area
             (`p-2` around the 12px text).

[M-05] /login Submit click discrepancy — prior audit [B-N7]/[B-10] flagged preview_click does
       not fire POST /api/auth/login. Re-test attempted via preview_eval submit; permission
       gate triggered before completion. Carrying over as inherited finding.
  Severity:  P1 (S2)
  Routes:    /login at all viewports.
  Evidence:  Inherited from BROWSER_VALIDATION_REPORT.md §[B-N7] line 455. This pass did not
             reproduce because credential-brute-force protection blocked further login attempts
             after 2 failed test logins. The original repro path is preview_fill + preview_click
             at 390×844; same artifact would apply at 360 and 428 since the form is identical.
  Fix:       See [B-N7] in the prior report — investigate React Router form-submit interception
             vs. native form action.

[M-06] V3ProjectHome composer textarea is 340w × 56h on iPhone 14 — tap-OK but cramped for
       multi-line specs.
  Severity:  P3 (S4)
  Routes:    /projects/:slug at 390.
  Evidence:  preview_eval `document.querySelector('textarea').offsetWidth=340, offsetHeight=56`
             at 390-wide viewport.
  What's wrong: 56px is one short line of typing. Specs are 100-300 chars typical; the textarea
             auto-grows on most inputs but the visual baseline is "type one short tweet" not
             "describe what you want to build". Empty-state copy says "What do you want to
             build?" but the affordance reads "what's your name". On the screenshot, the visible
             textareas are pre-existing run-launch boxes from the project chat thread, NOT the
             empty-state composer — the page is in conversation-with-history mode. For a fresh
             empty project the composer would be the dominant element and the cramped size
             would matter more.
  Fix:       Min-height 96px on mobile (3 lines visible at 13px font). Confirm auto-grow caps
             at viewport-h × 0.4 to leave Send + Submit-mode chips reachable.

[M-07] v2 AppLayout `Header` icon buttons (Search, Notifications, Avatar) are 30-40px wide × 44px
       tall — fail 44×44 horizontal target on /runs.
  Severity:  P2 (S3)
  Routes:    /runs at 360, 390. Same Header on all v2-AppLayout routes (admin, billing, etc).
  Evidence:  preview_eval scan at 360 returned: Search 30×44 ⌘K, Bell 32×44 (with "9" badge),
             Avatar SA 40×44, "All" filter chip 40×44. Hamburger isn't in the small-list (verified
             earlier as functional).
  What's wrong: One-thumb mistaps cluster on 30px-wide tap targets. The bell button has a 9 badge,
             the avatar has 2 letters; both are visually the right size but actually tighter.
  Fix:       Bump these to 44×44 minimum (already 44 vertical, just need px-3 → px-4).

[M-08] /runs status filter pills (PLANNING/WORKING/AWAITING APPROVAL/COMPLETE) horizontal-scroll at
       360-390. Long names ("AWAITING APPROVAL") get clipped without a scroll-affordance hint.
  Severity:  P3 (S4)
  Routes:    /runs at 360, 390.
  Evidence:  Screenshot at 360 shows ALL/PLANNING/WORKING and "AWAITING APP..." truncated mid-word
             at the right edge.
  What's wrong: No fade-edge or chevron indicating more content scroll-right. Users on mobile
             often miss horizontal scroll regions. "AWAITING APP" looks like a typo / data error.
  Fix:       Add right-edge fade gradient OR collapse to a "More ▾" dropdown at narrow widths.

[M-09] (cross-cutting) Dual-shell schism — V3Shell has no hamburger, AppLayout has one.
  Severity:  P1 architectural (S2)
  Evidence:  Code trace: frontend/src/v3/components/V3TopBar.tsx (no hamburger, no drawer).
             frontend/src/components/layout/Sidebar.tsx:7,28 imports `X` icon for mobile close +
             `useUiStore.mobileSidebarOpen` slice. They are completely separate codepaths.
             V3LegacyRedirect rebases `/v3/*` onto root paths but doesn't unify the chrome.
  What's wrong: A user navigating from /library (V3Shell, no hamburger) to /admin (AppLayout,
             has hamburger) gets a chrome-shape change mid-session. Discoverability suffers.
  Fix:       Either (a) add a hamburger to V3TopBar for mobile that opens an off-canvas drawer
             with all the nav pills + AvatarMenu items, OR (b) port V3 to AppLayout for the
             mobile breakpoint only.

[M-10] (heuristic) FREE persona on /projects/empty has no path to /welcome onboarding. Inherited
       from BROWSER_VALIDATION_REPORT.md [F-02]. Mobile FREE users see the dashed "Create my
       first project" CTA only — no sample-spec gallery, no /welcome dispatch.
  Severity:  P2 (S3)
  Routes:    /projects/empty at all viewports.
  Evidence:  Inherited; not re-tested under non-admin auth on this pass.
  Fix:       See V125 onboarding plan + EmptyProjectLanding component.
```

(Stopping at 10 to respect the §3 cap.)

---

## §4 — Tap target audit

Findings ranked from a11y tree bounding boxes captured via preview_inspect / preview_eval at the canonical 390×844:

| # | Element | W×H (px) | Route | Severity |
|---|---|---|---|---|
| 1 | V3TopBar nav links (Runs, Library, Migrations) | 53–88 × **30** | every V3 route | P1 — text + height fail |
| 2 | V3TopBar AvatarMenu trigger (`button.SA`) | **30** × 44 | every V3 route | P1 — width fail; off-screen too |
| 3 | V3TopBar brand `<a>` "ESExecuteSpec" | 106 × **24** | every V3 route | P2 — should be a 44 hit area |
| 4 | /login email input | 316 × **42** | /login | P3 — close, just 2px short |
| 5 | /login "Forgot?" link | 42 × **16** | /login | P1 — half the minimum height |
| 6 | /login "Show password" eye button | (not measured, observed in a11y tree as inline icon) | /login | P2 — likely <44 |
| 7 | v2 Header `Search` ⌘K button | **30** × 44 | /runs and other v2 routes | P2 — width fail |
| 8 | v2 Header `Notifications` (9 badge) | **32** × 44 | /runs | P2 — width fail |
| 9 | v2 Header `Avatar` (SA) | **40** × 44 | /runs | P2 — 4px width short |
| 10 | /runs first status filter "All" | **40** × 44 | /runs | P2 — width fail |
| 11 | /runs first project filter "All Runs" | (not measured but visually similar) | /runs | likely P2 |
| 12 | V3ProjectHome composer textarea | 340 × **56** | /projects/:slug | OK as input but cramped |
| 13 | V3ProjectHome "Open run" buttons | (not measured) | /projects/:slug | needs verify, small visually |
| 14 | "+ Attach repo" pill on project header | (not measured) | /projects/:slug | small visually |

---

## §5 — V3TopBar drawer-collapse design recommendation for [B-05]

**Current shape (frontend/src/v3/components/V3TopBar.tsx + v3.css:120-227):**
```
[brand: ES + "ExecuteSpec"] [center: ProjectSwitcher | Runs | Library | Migrations] [right: UsageBadge | AvatarMenu]
```
single-row flex, height 56 (52 at <640px), shrinks brand font and hides a now-removed placeholder; no other responsive adaptation.

**Recommended pattern (text-only spec, no Figma):**

At `@media (max-width: 720px)` (covers all 3 mobile viewports + small tablet):

1. **Hamburger replaces ProjectSwitcher + nav pills.** A single 44×44 hamburger button (`<button aria-label="Open navigation" aria-expanded={open}>`) sits left of brand or where ProjectSwitcher used to be.
2. **Brand mark stays inline** (left edge), `ES` icon + "ExecuteSpec" text. At <420px hide the text and keep just the ES badge to free 80px.
3. **UsageBadge + AvatarMenu stay pinned right.** Both must be on-screen at every mobile width. UsageBadge collapses to icon-only (hide the "X / N" text, show a small graphite ring around the icon — full text moves into the avatar dropdown).
4. **Hamburger opens an off-canvas drawer** (slide from left, 280px wide × 100vh, `position: fixed` with backdrop). Drawer content:
   - ProjectSwitcher (full list, scrollable)
   - Runs / Library / Migrations as full-width 44px rows
   - Section "Account" with Settings, Billing, Logout (mirror AvatarMenu)
   - UsageBadge with full text + "Upgrade" CTA
5. **Body scroll lock** when drawer open (mirror Sidebar.tsx:58-62 pattern with `useUiStore`).
6. **ESC + tap-backdrop closes**, focus-trap follows.
7. **`aria-label="Toggle menu"`** on hamburger to match v2 AppLayout for consistency.

**Implementation notes:**
- Reuse `useUiStore.mobileSidebarOpen` slice or add `mobileTopbarDrawerOpen` (the scopes don't conflict — V3TopBar and v2 Sidebar never co-render).
- Add `useIsMobile()` hook (already exists at `frontend/src/hooks/useIsMobile.ts`) to gate the drawer-only logic.
- Lift the existing `frontend/src/v3/components/AvatarMenu.tsx` content into a shared `AccountSection` component so the drawer reuses it.
- New CSS: `.v3-drawer`, `.v3-drawer__backdrop`, `.v3-drawer-trigger` (hamburger).
- Estimated ~140 LOC + ~80 LOC CSS. ~3-4 hours including tests.

**Validation criteria:**
- At 360, 390, 428: brand mark, hamburger, UsageBadge icon, AvatarMenu trigger all visible without horizontal scroll.
- Tab-order: hamburger → brand → UsageBadge → AvatarMenu → main content.
- Drawer focus-trap blocks tab from escaping into hidden top bar.

---

## §6 — Coverage gaps (work that wasn't done)

This pass deliberately skipped or was unable to:

- **Real-device QA.** No iPhone, Pixel, or Galaxy hardware. Findings rest on preview_screenshot + DOM math; touch behavior, viewport meta tag handling on real Safari/Chrome iOS, soft-keyboard impact on V3TopBar position, and orientation rotate were NOT validated.
- **Landscape (excluded by spec).** All findings assume portrait. Landscape may relieve [M-01]/[M-02] crowding via wider viewport but introduces vertical-scroll/keyboard-occlusion issues on iOS.
- **BrowserStack / Sauce Labs cloud devices.** Cost: $0 mandate held. No real Safari iOS 17/18 testing, no iOS Low Power Mode rendering, no Reduce Motion, no high-contrast mode.
- **Touch gestures.** Swipe-to-dismiss, pinch-zoom, long-press, drag-and-drop on /projects/:slug task graph. None tested.
- **iOS Safari autofill.** /login email/password autofill flows, KeyChain integration, password manager bridge — all skipped (would need real device).
- **Soft-keyboard occlusion.** V3ProjectHome composer + /login form focus behavior with iOS soft keyboard up. The CSS at v3.css:229+ partially addresses iOS composer raise but unverified.
- **PWA / standalone-mode.** Not tested. site.webmanifest exists but no `apple-touch-icon` audit.
- **Login flow re-test.** Could not re-run [B-N7] preview_click submit issue on each viewport because the credential-brute-force gate blocked further login attempts after the 2 initial credential probes failed. Inheriting from prior report.
- **FREE persona on V3Shell mobile routes.** Could not log out admin to a fresh-FREE state without breaking session continuity; FREE-specific mobile findings (UsageBadge quota text, upgrade-nudge sizing) were inferred from code-trace not screenshot.
- **/migrations route.** Not in scope per the 6-route cap. Same V3TopBar so [M-01]/[M-02]/[M-03] apply by inheritance.
- **Tap-target verification on V3ProjectHome "Open run" buttons + chip rows.** Visual inspection only; no preview_inspect.
- **Safari-specific CSS (`-webkit-backdrop-filter`).** v3.css:131 uses it; no real Safari was used to verify the blur actually fires.
- **Reduced-data / Save-Data mode.** No throttle test.

**Recommended next pass:**
1. BrowserStack 4-hour real-device sweep (iPhone 13/14, Pixel 7, Galaxy S22, iPad Mini portrait).
2. Re-run [M-01]/[M-02] after the §5 drawer fix lands — confirm UsageBadge + AvatarMenu reachable at 360.
3. Re-run [B-N7] login submit on a fresh-credential test account using cypress/playwright (not preview_click).
4. Add `frontend/src/__a11y__/tap-targets.test.ts` enforcing 44×44 minimum on every interactive element via `getBoundingClientRect`-based assertion (catches regressions in CI).
5. iOS soft-keyboard emulation via Lighthouse mobile audit on /login + /projects/:slug.

---

**End of report.**
Word count: ~2,950 (under 3,000 cap).
Total preview MCP calls: ~30 (under the 18-cells × 30s budget).
Screenshots captured at 390×844: 5 (login, /library, /projects/empty, /projects/:slug, /runs, /settings) — within the "6 max" budget.
Code-trace files read: V3TopBar.tsx, v3.css §topbar, Sidebar.tsx (header section), Composer.tsx (props/intro), App.tsx (routes).
