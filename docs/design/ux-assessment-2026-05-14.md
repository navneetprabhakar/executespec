# UX Assessment — ExecuteSpec (2026-05-14)

> Unbiased end-to-end UX audit ahead of the redesign. Captured live on
> `feature/ux-overhaul-2026-05-14` against `localhost:5173` + backend on
> `spring.profile=local`. Logged in as ENTERPRISE / SUPER_ADMIN to access every
> surface, spot-checked FREE-tier paywall states.

## Scope

Every surface mounted by `App.tsx` was visited at three breakpoints:

- **375×812** (phone — iPhone 12 mini class, the worst-case primary target).
- **768×1024** (tablet — iPad portrait).
- **1280×800** (laptop — the smallest 13" laptop we ship to).

Surfaces audited:

| # | Route | Component | Audience |
|---|-------|-----------|----------|
| 1 | `/login` | `LoginPage` | Public |
| 2 | `/register` | `RegisterPage` | Public |
| 3 | `/home` → `/projects/:id` | `HomeRedirectPage` → `ProjectDashboardPage` | Auth |
| 4 | `/projects/:id?tab=compose` | `ComposeTab` (default) | Auth |
| 5 | `/projects/:id?tab=pulse` | `PulseTab` | Auth |
| 6 | `/projects/:id?tab=repos` | repos panel | Auth |
| 7 | `/projects/:id?tab=runs` | `ProjectLaunchesList` | Auth |
| 8 | `/projects/:id?tab=code` | `CodeTab` | Auth |
| 9 | `/projects/:id?tab=chat` | `ProjectChatPanel` | Auth |
| 10 | `/runs` | `RunsListPage` | Auth |
| 11 | `/runs/:id` | `RunDetailPage` (`RunDetailTabs`) | Auth |
| 12 | `/library` | `LibraryPage` | Auth |
| 13 | `/library/:slug` | `LibraryDetailPage` | Auth |
| 14 | `/migrations` | `MigrationsPage` | Auth |
| 15 | `/migrations/:slug` | `MigrationDetailPage` | Auth |
| 16 | `/billing` | `BillingPage` (v3) | Auth |
| 17 | `/pricing` | `PricingPage` | Auth |
| 18 | `/settings` | `SettingsPage` | Auth |
| 19 | `/integrations` | `IntegrationsPage` | Auth |
| 20 | `/team` | `TeamPage` | TEAM+ |
| 21 | `/trust` | `TrustPage` | Public |
| 22 | `/insights` | `InsightsPage` | Auth |
| 23 | `/admin/*` | `AdminPage` etc. | SUPER_ADMIN |

The audit treats the product as **a power-user agentic coding console with a
go-anywhere mobile companion** — the user has been explicit that the on-the-go
mobile experience is half the value proposition.

---

## Headline findings

> Severity legend: **P0** = must fix to ship the redesign · **P1** = ship-blocking
> for mobile-first claim · **P2** = polish · **P3** = nice-to-have.

### What's working

- **Typography** — JetBrains Mono is the brand. Distinctive, legible, reads as
  "developer tool." Single-font discipline is rare in dashboards this large.
- **Dark theme** — Graphite + Signal token system has earned its place. The
  Wave-6 surface ladder is real depth, not just slightly different greys.
- **Bottom nav exists on phone** — Home / Runs / Insights / You. The platform
  primitive is present. Just under-styled.
- **Skip-to-main-content link** — quietly excellent. WCAG 2.4.1 done right.
- **Status badge taxonomy** — colours map to semantic states consistently.
- **Verdict pill on run pages** — leads with "Shippable / Needs review /
  Blocked" instead of a verifiedScore number. Correct framing.

### What is fighting the user

#### P0-1. **Marketing voice on utility pages**

- `/runs` mobile renders the headline **"Every PR you've shipped, in one
  place."** It takes **4 lines / ~250 px** of the 812 px viewport before the
  first run card. A 4-paragraph description follows.
- `/library` and `/migrations` repeat the pattern with their own marketing
  preambles.
- `/pricing`, `/billing` start with `MODEL PRICING` / `Billing & plan` pills
  and verbose subheads.
- This is **a console**. The user came to do work. Marketing copy belongs on
  the landing page (`/` is already served by the static marketing site, per
  `App.tsx:103`). On the in-product utility pages, replace the marketing voice
  with a status header: "12 runs · 3 active · $14.20 this week."

#### P0-2. **Desktop content max-width is too narrow**

- `Shell.tsx:62` distinguishes "wide+fill" (workbench) vs "wide" (list) vs
  "default" (narrow centered) routes. Even the "wide" preset on the project
  dashboard renders content in a 960-px column on a 1280-px viewport →
  **~60% of pixels are empty void** on the right.
- The PR list at the bottom of the project dashboard truncates a column to
  show a `…` that exists only because we chose to render in 480 px of usable
  space.
- The "fill" preset is healthier but is **not applied to the dashboard tabs
  themselves** — only to RunDetail and shared sub-pages.

#### P0-3. **Mobile project header eats the fold**

On 375×812 the project dashboard burns the entire first fold to chrome before
the user can see a single run:

| Region | Height |
|--------|--------|
| TopBar | 56 px |
| `← Projects` link | 28 px |
| "PROJECT" caption + 2-line project title | ~140 px |
| Action cluster (Repos · 2 / + / Launch / sparkle / ...) | 60 px |
| 6-cell stat grid (STATUS / PROVIDER / REPOS / DONE / SCORE / SPEND) | ~180 px |
| Tab strip | 56 px |
| **Total chrome before first card** | **~520 px** |

That's 64 % of the phone viewport gone before the user sees their last run.
The last-run card lands below the fold even though it is the single most
important thing on the page.

#### P0-4. **Mobile action cluster is incoherent**

On the same project header at 375 px we render four side-by-side buttons of
three different shapes:

1. `Repos · 2 | +` — pill with combined label + plus icon (two actions in one
   target, dangerous).
2. `Launch` — outlined-button with rocket icon.
3. Sparkle icon-only button (no label, unclear purpose — turns out it is "AI
   suggestions").
4. `…` — overflow menu.

Three of those four don't pass the Apple HIG 44×44 hit-target floor cleanly,
and the combined "Repos · 2 | +" is two semantic actions stuffed into one
visual control. This is the worst control on the entire mobile surface.

#### P0-5. **Tab strip overflows silently on phone**

The project tab strip (Compose / Pulse / Repos / Runs / Code / Chat) horizontal-
scrolls on phone. **Chat is clipped to "Cha…"** at 375 px. Users have no
indicator that more tabs exist; the scroll bar appears only on touch. Same
pattern on the run detail tabs (Preview is clipped).

#### P0-6. **Stat grid uses 6 weak labels instead of one anchor stat**

The project header at mobile shows `STATUS / PROVIDER / REPOS / DONE /
SCORE / SPEND` as a 6-cell grid. Apple HIG (and every well-designed
analytics surface) leads with **one** anchor stat — the score or the
status — and demotes the rest to a chip row that progressively discloses.
Six caps-label stats give the user nothing to focus on.

#### P0-7. **Hamburger drawer is the only path to half the product**

`TopBar.tsx` collapses every nav item except the project switcher and the
hamburger at ≤768 px. The drawer contains:

- The project tabs (duplicated from the tab strip just below)
- Cross-project Runs / Library / Migrations
- Account / Billing / Pricing / Team / Integrations / Trust
- Org admin (Insights / Exec / Audit / Invoices / Accounting)
- Super admin (Admin / Feature flags / Tier lifecycle)

That's correct structurally (one menu beats nine top-bar pills), but the
drawer presentation is a flat list of 18+ items with weak section labels.
A user looking for "Billing" scrolls past five other things. There is no
search inside the drawer, no recently-used affordance, no priority order
beyond the role-bucket grouping.

#### P0-8. **No global search / command palette discoverability**

`CmdK.tsx` exists. It is bound to ⌘K globally. There is **no UI affordance**
hinting at it anywhere — the visible "Search ⌘K" button in the topbar was
removed on 2026-04-29 per the comment in `TopBar.tsx:27`. A keyboard hotkey
nobody knows about is the same as no hotkey. Mobile users have no way to
trigger it at all.

#### P1-1. **Cost over-precision**

Run detail mobile shows `Spent $1.0813`. Money to four decimals is a tell of
unfinished design. Apple money formatting: $1.08 (2dp under $100; 0 dp at
$100+; "k" suffix beyond $10k).

#### P1-2. **Filter pill rows lack a "clear" affordance**

`/runs` shows `Scope: Mine / Org / All` and `Status: Any / Complete / Working
/ Needs approval / Failed` as two pill rows. Once a user has selected three
filters there is no single "Reset" control; they have to click each pill
individually back to the default. Active filter count is not shown.

#### P1-3. **Card hover state on desktop is invisible**

Run-list cards, library cards, migration cards all use the same `<div>`
with `border` + `padding` and no hover affordance beyond a subtle border
brightness shift (`border-hover` token). On desktop the user has no signal
that a card is clickable until the cursor crosses into the title link.

#### P1-4. **TopBar height is inconsistent under blur**

The topbar uses `backdrop-filter: blur(8px)` which, per CSS spec, creates a
new containing block. The hamburger drawer had to be portaled to body to
escape it (`TopBar.tsx:178-184`). The fact that this workaround is required
is a smell — the topbar is doing too much. A clean topbar should use a
solid surface or a `position: sticky` body wrapper, not a fixed glass header
that fights with absolute positioning.

#### P1-5. **iOS safe-area is half-applied**

`BottomNav.tsx:85` honors `safe-area-inset-bottom`. `TopBar.tsx` does **not**
honor `safe-area-inset-top` on PWA-installed sessions. On notched iPhones the
brand mark sits under the status bar.

#### P1-6. **Tap targets below 44 px in multiple places**

- Project switcher chevron caret: ~16 px tall.
- Card "View →" / "Open run" / "View report" links: ~28 px tall.
- Login "Show password" eye icon: ~32 px tall.
- Filter pills on `/runs`: ~32 px tall.
- Cost / token rows on run detail Overview: inline links, no padding.

Apple HIG minimum is 44×44. Failing on phone breaks the "use it from the
phone" claim.

#### P1-7. **Empty / loading / error states are sparse**

- `/runs` skeleton is a single grey shimmer block, not a list-card skeleton.
- `/library` and `/migrations` go straight from skeleton to content with no
  micro-fade.
- The "no runs yet" empty state is text + button, no illustration or guidance
  on what to do first.
- 500 errors during the assessment surfaced as silent failed fetches; UI
  showed a stale empty state with no error indication.

#### P1-8. **Inconsistent corner radii across cards**

`tokens.css` defines a 5-step radius scale (4/6/8/12/16 px). Live surface:

- TopBar buttons: 6 px (`navLinkStyle`).
- Card containers: 12 px (`v3.css`).
- Filter pills: 9999 (full).
- Action cluster buttons: 8 px.
- Drawer panels: 10 px.

That's 5 different radii visible at once on the project dashboard. Pick
two — small (6) for inline controls, large (12) for cards — and live with
them everywhere.

#### P2-1. **Status pill colour vocabulary is overloaded**

`tokens.css:117-130` defines 8 status colours (success / executing /
thinking / waiting / partial / error / blocked / queued). All 8 are used
on the run detail Overview. Apple HIG and macOS Finder use 4 (green /
yellow / orange / red) consistently because the human eye can't
disambiguate 8 hues at glance-distance.

Recommendation: collapse to 4 semantic tints (success / progress / warn /
fail) + one neutral (queued). Use icon shape (●▲■◆) to carry the second
dimension instead of a new hue.

#### P2-2. **Animation discipline is missing**

`tokens.css:170-174` defines `--duration-fast` / `--ease-out` etc.
Searched the codebase: only 12 files use `var(--ease-out)`. Most cards,
modals, drawer slide-ins, tab transitions use the browser default or no
transition at all. iOS-level polish requires ~6 motions per page minimum
(button press, card hover, sheet slide, tab indicator slide, focus ring,
state-change tint).

#### P2-3. **Score ring lives in a 132 px column**

`ScoreRing.tsx` renders a 132 px ring. On the project dashboard mobile it
occupies the right column of the last-run card and gets **cut off at the
bottom** of the card. On desktop it floats next to a half-empty card. The
ring is the right idea — wrong layout. Should be a hero element, not a
column-mate.

#### P2-4. **Skeleton vs real-data jump**

`SkeletonLoader.tsx` is a single bar. The card it pretends to be has
title + meta-row + 2-pill row + 2-button row. The skeleton-to-content
swap is visually abrupt because the shapes don't match.

#### P3-1. **Glassmorphism vs flat — pick one**

Bottom nav uses `backdrop-filter: blur(12px)` (`BottomNav.tsx:82-83`).
TopBar uses `backdrop-filter: blur(8px)`. Cards are flat solid surfaces.
The chrome ends up looking like two different design eras.

#### P3-2. **No haptic / pseudo-haptic feedback on phone**

Phone Web doesn't have CoreHaptics, but a 60 ms `transform: scale(0.97)`
on button press is the cross-platform proxy. Currently buttons have no
press feedback at all.

#### P3-3. **`useIsMobile` vs `useIsPhone` — overlapping breakpoint hooks**

`useIsMobile` (≤768) is used by `TopBar`. `useIsPhone` is used by
`BottomNav`. There is no `useIsTablet` and no shared breakpoint constant
in tokens. Add `--breakpoint-phone: 480px` / `--breakpoint-tablet: 768px`
/ `--breakpoint-desktop: 1024px` and read both hooks from one source.

---

## Per-surface quick notes

### `/login`
- Split design is good. Left marketing panel, right form.
- Form is acceptable but the password "Show" eye icon is a ~32-px touch
  target on phone. Marketing list items have brand-blue rocket-style icons
  on a dark surface — fine.
- **Action:** keep split, raise hit targets, add a single line of motion on
  the marketing panel (subtle typing-in of the headline), and inflate the
  password reveal icon to 44 px.

### Project dashboard (`/projects/:id`)
- The single most-used surface. Suffers worst from P0-3 (chrome before
  content), P0-4 (action cluster), P0-5 (tab overflow), P0-6 (stat grid),
  P2-3 (score ring).
- Desktop has 60 % empty pixels (P0-2). PR table at bottom of `PulseTab`
  truncates columns it didn't need to.
- **Action:** total rebuild of header region. Apple-style:
  - Compressed top bar: brand pill, workspace pill, identity.
  - Project name as a large title under the topbar.
  - **One** hero card: last-run verdict + score ring + 2 primary actions
    ("View report", "Launch").
  - 3-tile metric strip beneath: SCORE / SPEND / DONE — no caps labels, just
    the numeric + tiny semantic dot.
  - Tabs as an iOS segmented control with persistent visible labels (no
    horizontal scroll on phone — collapse to "More ▾" if needed).

### Runs list (`/runs`)
- P0-1 (marketing copy), P0-2 (desktop empty space), P1-2 (no filter
  reset), P1-3 (card hover).
- Run cards are dense but readable. The status pill is well-placed.
- **Action:**
  - Replace marketing headline with `Runs · {count}` and a "Launch" CTA
    pinned right.
  - Sticky filter bar with chip count + Reset.
  - Card hover on desktop with elevation lift (+1 elevation step).
  - On phone, swipeable filter shelf (segmented control).

### Run detail (`/runs/:id`)
- 3-column desktop layout is dense but works. Mobile single-column with
  stacked tabs is acceptable but suffers from over-precision currency
  (P1-1) and tab overflow (P0-5).
- **Action:**
  - Verdict-first hero on every viewport.
  - Score ring as a small (96 px) round in the hero, not a column.
  - Tabs: Summary / Plan / Diff / Tests / Logs (rename for clarity).
  - Currency formatter pass — kill `$1.0813`.

### Library / Migrations (`/library`, `/migrations`)
- P0-1 (marketing copy), P1-3 (card hover), P1-7 (loading state).
- Grid of cards on desktop, single column on phone. Acceptable structure.
- **Action:**
  - App-Store-style: a featured-recipe hero card at top, then category
    chips, then grid.
  - Per-card visual hierarchy: icon | title | brief | meta-row (category
    pill, credits, files).
  - Hover lift + tap-press scale on phone.

### Billing (`/billing`)
- Decent IA. Plan card → usage → tier table.
- Issue: for ENTERPRISE/TEAM users, the full tier comparison is showing
  unnecessarily. Should default-collapsed with "Compare plans" disclosure.
- **Action:**
  - Lead with status card.
  - Usage histogram (last 30 days) replaces the 2-number summary.
  - Tier comparison collapsed under disclosure when user is on highest
    paid tier.

### Pricing (`/pricing`)
- Renders per-model pricing cards in a 3-col layout on desktop, 1-col
  phone.
- The "click to edit" affordance is invisible (no underline, no caret).
- **Action:**
  - Group cards by provider (Anthropic / OpenAI / Gemini) with header.
  - Add edit affordance ✏️ on hover; tap-to-edit on phone.
  - Group cards by tier badge (Architect / Workhorse / Utility) inside
    each provider.

### Settings (`/settings`)
- Flat scroll page. Adding the V194 SSO / Standards / Notification panels
  has made it long.
- **Action:**
  - Anchor nav on desktop (left rail) / accordion on phone.
  - Sections: Account / Workspace / Integrations / Notifications /
    Standards / Org / Danger zone.

### Integrations (`/integrations`)
- A grid of logos: GitHub, Slack, Linear. Each opens a config modal.
- Decent. Lacks a "status" indicator at-a-glance (connected vs not).
- **Action:** add status dot per integration card.

### Team (`/team`)
- Members table + invite. Functional. Mobile collapses to cards.
- **Action:** add bulk-invite paste (one email per line).

### Trust (`/trust`)
- Public page. Static.
- **Action:** match new card system.

### Insights (`/insights`)
- Sparse: charts of last-30-day cost + verifiedScore + run count.
- **Action:** larger charts, better titles, a single insight callout
  ("This week: 12 runs, $14.20 spend, 84 avg score — up 11 % WoW").

### Admin (`/admin`)
- Tables. Promoted to wide+fill recently. Acceptable.
- **Action:** add a single SUPER_ADMIN search bar that hits org / user /
  payment / run by ID.

---

## Inventory of design-system inconsistencies

These will be fixed in the redesign:

1. **Five active corner radii on the same page** (P1-8). Collapse to 2.
2. **Eight status hues** (P2-1). Collapse to 4 + 1 neutral; use icon shape
   to carry the second dimension.
3. **Two different blur values** in nav surfaces (P3-1). Match TopBar to
   BottomNav (`blur(12px) saturate(140%)`).
4. **Disparate hit-target sizes** (P1-6). Inflate to 44×44 minimum on
   mobile via `--hit-target-mobile: 44px` token + min-height utility.
5. **Two breakpoint hooks (`useIsMobile`, `useIsPhone`)** (P3-3). Funnel
   both through one `useBreakpoint()` hook reading shared tokens.
6. **Five button styles** (Primary, Secondary, Ghost, Outline-rounded,
   Inline-link). Collapse to 3 (Primary, Secondary, Ghost) with a "soft"
   variant for inline links.

---

## What the redesign will not change

(per user requirement: no functionality removed)

- Every route in `App.tsx` continues to mount.
- Every panel inside `ProjectDashboardPage` continues to render
  (`PulseTab`, `CodeTab`, `ComposeTab`, `ProjectChatPanel`,
  `ProjectLaunchesList`, `ProjectRepoManager`).
- Every entitlement gate (`useTierPermissions`, `RoleGuard`, FREE/PRO
  paywall modals) is preserved.
- Every Zustand store, API call, SSE subscription is preserved.
- Every feature flag continues to gate the same features.
- The font (JetBrains Mono) is preserved everywhere.
- The Graphite + Signal palette stays; only the surface ladder, depth
  treatment, and motion vocabulary are refined.

This is a **presentation + density + motion** overhaul, not a functional
rewrite. See `ux-redesign-2026-05-14.md` for the redesign spec.
