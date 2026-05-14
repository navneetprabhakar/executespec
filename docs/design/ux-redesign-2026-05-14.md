# UX Redesign Spec — ExecuteSpec (2026-05-14)

> Companion to [`ux-assessment-2026-05-14.md`](ux-assessment-2026-05-14.md).
> Resolves every P0 / P1 finding from the assessment in a single coherent
> redesign aligned with Apple's Human Interface Guidelines, with **zero loss
> of features, routes, or functional capability**.

## Design principles (the discipline this redesign commits to)

1. **Content first, chrome second.** The user came here to ship code. Chrome
   exists to point at the work and disappear. No marketing copy on utility
   pages. Status before sermon.
2. **One anchor, supporting cast.** Every page has exactly one hero element
   (a verdict, a score, a status, a search field). Everything else demotes
   visually around it.
3. **Mobile is the floor, not the fallback.** Layout is designed at 375 px
   first. 768 / 1280 / 1440+ are progressive enhancements, never the
   primary target.
4. **Touch the agent, don't navigate around it.** Composer / Launch / Spec
   submission is one tap away on every signed-in surface.
5. **Depth carries information.** A card that is interactive lifts on hover;
   a card that is decorative does not. A button that is destructive sits at
   the bottom of a sheet; a button that is primary sits at the top.
6. **Motion is functional, not decorative.** Every transition either reveals
   a relationship (this list came from this card) or confirms a state change
   (this filter is now active). No motion exists for its own sake.
7. **Reversibility.** Every destructive action has an undo or a confirm.
   Every filter has a "Clear all." Every modal has Escape, Cancel, and
   tap-outside-to-dismiss.
8. **Calm typography.** JetBrains Mono throughout (per user requirement).
   Size + weight + opacity carry the entire typographic hierarchy. No
   ALL-CAPS labels on body content; ALL-CAPS reserved for section headers.

## Token-system refinements

Single file change: [`frontend/src/tokens.css`](../../frontend/src/tokens.css).
New tokens layer on top — no existing token is removed.

### Surface ladder (4 layers, named after their job)

```
--surface-page    : graphite-0 (existing --geist-bg)
--surface-card    : graphite-2 (existing --geist-bg-secondary)
--surface-raised  : graphite-3 (new — for hover, modal, dropdown)
--surface-sunken  : graphite-1 (new — for inputs, code blocks)
```

Cards at rest = `--surface-card`. On hover = `--surface-raised`. Inputs +
code blocks (lower than the card) = `--surface-sunken`. This is the same
3-layer pattern Apple uses in macOS Big Sur+ (window background → control
background → control highlight).

### Border hierarchy

```
--hairline       : graphite-5 (subtle, decorative; existing
                   --border-divider-subtle)
--border-default : graphite-7 (AA non-text floor; existing
                   --border-default)
--border-strong  : graphite-8 (focus + hover; existing --border-hover)
```

### Depth (shadows)

```
--shadow-card-rest    : 0 1px 0 rgba(255,255,255,0.04) inset,
                        0 1px 2px rgba(0,0,0,0.4);
--shadow-card-hover   : 0 1px 0 rgba(255,255,255,0.06) inset,
                        0 6px 16px rgba(0,0,0,0.5);
--shadow-sheet        : 0 -12px 32px rgba(0,0,0,0.6);
--shadow-overlay      : 0 24px 64px rgba(0,0,0,0.7);
```

Each card carries a 1-px inner highlight on top edge — that's the trick that
makes a flat dark surface look like a real, slightly raised plane. Apple
calls this an "inner light"; macOS controls have used it since Aqua.

### Hit targets

```
--hit-min-mobile : 44px   (Apple HIG minimum)
--hit-min-desktop: 32px   (Apple HIG minimum for mouse-pointer surfaces)
--hit-comfort    : 48px   (preferred mobile primary actions)
```

A utility class `.hit-target-mobile` enforces `min-height: var(--hit-min-
mobile)` and `min-width: var(--hit-min-mobile)` for inline-link controls
on phone.

### Motion vocabulary (4 named tracks)

```
--motion-press    : transform 80ms cubic-bezier(0.4,0,0.6,1)
                    (button-press scale 0.97)
--motion-tap      : opacity 120ms ease-out
                    (link / chip tap state)
--motion-reveal   : opacity 200ms cubic-bezier(0.2,0,0,1),
                    transform 200ms cubic-bezier(0.2,0,0,1)
                    (sheet, drawer, dropdown reveal)
--motion-pulse    : background-color 320ms ease-out
                    (state-change tint, e.g. filter applied)
```

Existing `--ease-out`, `--ease-spring`, `--duration-*` tokens are kept for
back-compat; new code reads from the named motion tracks.

### Radius collapse

```
--radius-control : 8px   (buttons, pills, chips, inputs — was 6/8 mixed)
--radius-card    : 14px  (cards, sheets, modals — was 12/16 mixed)
--radius-pill    : 9999px (chips with rounded ends only)
```

Existing 4/6/8/12/16/2xl radii kept for back-compat — new components consume
the role-named ones.

### Breakpoint tokens

```
--bp-phone   : 480px
--bp-tablet  : 768px
--bp-desktop : 1024px
--bp-wide    : 1280px
--bp-xl      : 1536px
```

Exposed via media queries + a unified `useBreakpoint()` hook that replaces
`useIsMobile` and `useIsPhone` (both kept as thin wrappers for back-compat).

---

## Chrome (TopBar + BottomNav)

### TopBar redesign

**Goals:** clear identity on every viewport, agent-input prominent on tablet
+ desktop, one hamburger that opens a unified mobile drawer with search.

**Desktop layout** (≥ 768 px):

```
┌──────────────────────────────────────────────────────────────────────────┐
│ [ES]  [▾ Hostel Eval ANTHROPIC]  [🔍 Search or jump…  ⌘K]  ⓘ ◷ 🔔 [SA] │
└──────────────────────────────────────────────────────────────────────────┘
  56px height. Sticky, translucent (blur 14px sat 140%).
  - Brand mark (link → /home)
  - Project switcher (current project name, ▾ caret, click → switcher
    popover with recent projects + "All projects →")
  - Center: command input. Click or ⌘K → CmdK palette. Replaces the
    invisible-hotkey-only CmdK with a visible affordance.
  - Status pulse dot (running runs counter)
  - Usage badge (FREE/PRO quota)
  - Notification bell
  - Avatar menu
```

**Mobile layout** (< 768 px):

```
┌────────────────────────────────────────┐
│ [ES]  Hostel Eval ANTHROPIC ▾    [☰]   │
└────────────────────────────────────────┘
  52px height. Sticky, translucent.
  - Brand mark (small, tap → /home)
  - Project name as the title (single line, truncated with ellipsis).
    Tap → project switcher sheet (slides up from bottom, iOS-style)
  - Hamburger (☰) → unified drawer
```

Brand mark height is reduced to `28px` (was `40px`) to match iOS Settings
title bar treatment. Border `--hairline` rather than `--border-default` to
read as a layer separator, not a wall.

### BottomNav refinements

Keep the 4-slot structure. Add:

- **Filled vs outline icons** — Lucide ships filled glyphs (`Home` →
  `HomeIcon` filled when active). Active = filled + brand colour. Inactive =
  outline + `--text-muted`.
- **Active indicator pill** — a 4 px tall × 32 px wide pill in
  `--accent-blue` slides under the active icon (no jumping). Transitions
  via `--motion-reveal`.
- **Press feedback** — `transform: scale(0.95)` on `:active`.
- **Badge support** — top-right red dot on Runs when a run needs approval,
  on You when there are notifications.

Slot label-set:

```
🏠 Home    ⚡ Runs    📊 Insights    🙂 You
```

(label "You" stays; the icon swaps to a filled circle on active to look
account-y. Apple's iOS Photos app uses the same pattern.)

### Mobile drawer (unified secondary nav)

Replaces the current 18-item flat list:

- **Sticky header**: brand mark + close X.
- **Search input** (`Search ⌘K` style) — types into CmdK directly; debounced
  results appear below.
- **Identity card**: name, email, tier pill, "Switch organization →" if
  multi-org.
- **Project section** (when on `/projects/:id`): the 6 project tabs as a
  vertical list with icons.
- **Workspace section**: Runs / Library / Migrations / Insights.
- **Account section**: Account / Billing / Pricing / Team / Integrations /
  Trust.
- **Admin sections** (gated): Org admin (collapsed by default), Super admin
  (collapsed by default).
- **Footer**: Help / Sign out.

Drawer width: `min(88vw, 360px)`. Slides in from right with
`--motion-reveal`. Scrim is `rgba(0,0,0,0.6)` with `blur(2px)`.

---

## Project dashboard (`/projects/:id`)

The most-used surface. Total header rebuild.

### Mobile (375 px) — new layout

```
┌──────────────────────────────────────────┐
│ TopBar (52px) — handled separately       │
├──────────────────────────────────────────┤
│ [ic] Hostel Eval ANTHROPIC               │  ← Title row (line-clamp:1)
│      2 repos · ANTHROPIC · 5d ago        │  ← Meta line (one row)
├──────────────────────────────────────────┤
│ ┌────────────────────────────────────┐   │
│ │ ●  READY · last run                │   │  ← Hero card
│ │                                    │   │     verdict-first
│ │ Add Hello from ExecuteSpec audit   │   │
│ │ run to README                      │   │
│ │ d8cab376 · 5d ago                  │   │
│ │                                    │   │
│ │  ⊙ 50      [View report] [Open]    │   │  ← Score ring inline (72px)
│ │            $1.72  • build ✓ • r ✓  │   │
│ └────────────────────────────────────┘   │
│                                          │
│ [Launch new run]                         │  ← Primary action (full-bleed
│                                          │     button, hit-comfort 48px)
│                                          │
│ ╭─ Compose · Pulse · Repos · Runs ─╮     │  ← Segmented tab control
│ ╰──Code · Chat──More ▾─────────────╯     │     (4 visible + More overflow
│                                          │      menu — no scroll)
│ ┌────────────────────────────────────┐   │
│ │ tab body                           │   │
│ └────────────────────────────────────┘   │
└──────────────────────────────────────────┘
```

**Removed from above the fold:**
- `← Projects` link (moved into TopBar's project switcher; user can swipe
  it from there).
- The 6-cell stat grid (STATUS / PROVIDER / REPOS / DONE / SCORE / SPEND).
  Provider + repos move to the meta-line. Done / Spend move into the hero
  card. Score ring moves into the hero card.
- The 4-button action cluster (Repos / Launch / sparkle / …). Repos
  becomes a tab. Launch becomes the prominent body-button. Sparkle moves
  to a quick-action FAB. … overflows into the TopBar context menu.

**Added above the fold:**
- A verdict-first hero card (the single most-asked-for thing in a
  console: "is my last run shippable?").
- One unambiguous primary CTA.

### Desktop (≥1280 px) — new layout

```
┌──────────────────────────────────────────────────────────────────────────┐
│ TopBar 56px                                                              │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  [ic] Hostel Eval ANTHROPIC                                              │
│       2 repos · ANTHROPIC · 5d ago                  [Launch new run]     │
│                                                                          │
│  ┌─────────────────────────────────────┐ ┌────────────────────────────┐  │
│  │ Last run                            │ │ This week                  │  │
│  │ ●  READY                            │ │ ─────────────────────────  │  │
│  │ Add Hello from ExecuteSpec audit…   │ │  Runs    5                 │  │
│  │ d8cab376 · 5d ago                   │ │  Spend   $14.20            │  │
│  │                                     │ │  Score   72 avg            │  │
│  │  ⊙ 50    build ✓ · reviewer ✓       │ │  PRs     3 open, 2 merged  │  │
│  │  [View report]  [Open run]          │ │                            │  │
│  └─────────────────────────────────────┘ └────────────────────────────┘  │
│                                                                          │
│  ╭─Compose──Pulse──Repos──Runs──Code──Chat╮                              │
│  │ tab body — fills remaining viewport      │                            │
│  ╰──────────────────────────────────────────╯                            │
└──────────────────────────────────────────────────────────────────────────┘
```

Desktop fills the viewport (already in `Shell.tsx` classifyRoute). The
2-column hero (last-run + week-summary) replaces the 4-column "Build &
Ship / Roadmap / Activity" strip — same data, better hierarchy.

Tab indicator: animated underline that slides between tabs via
`--motion-reveal` (Apple HIG segmented control style).

---

## Runs list (`/runs`)

### Mobile

```
┌──────────────────────────────────────────┐
│ TopBar                                   │
├──────────────────────────────────────────┤
│ Runs                                     │  ← Compressed header (no
│ 12 runs · 3 active · $14.20 this week    │     marketing copy)
│ ─────────────────────────────────────    │
│  Scope: [Mine] [Org] [All]               │  ← Filter row with active
│  Status: [Any] [Complete] [Working] […]  │     state visible
│  [Reset all filters]    (if any active)  │
│                                          │
│ [+ Launch new run]                       │  ← Always-visible action
│                                          │
│ ┌────────────────────────────────────┐   │
│ │ ● COMPLETE  · 484253e0 · $1.08 · 2d│   │  ← Card with status dot
│ │ Todo Calendar UI App               │   │     replacing pill (visual
│ │ 3 repos · ANTHROPIC                │   │     anchor, smaller)
│ └────────────────────────────────────┘   │
│ ┌────────────────────────────────────┐   │
│ │ ● COMPLETE  · 6f7a9e22 · $0.41 · 3d│   │
│ │ Add Hello from ExecuteSpec audit … │   │
│ └────────────────────────────────────┘   │
└──────────────────────────────────────────┘
```

Filter row sticks under the header on scroll. Tap a chip → activates +
shows count badge if multi-select. Active filters surface a "Reset" pill.

### Desktop

3-column responsive list. Title-row + filter-row + table-style list with
sortable columns: Status / Title / Repo / Cost / Started / Score.

---

## Run detail (`/runs/:id`)

### Mobile

```
┌──────────────────────────────────────────┐
│ TopBar                                   │
├──────────────────────────────────────────┤
│ ← All runs                               │
│                                          │
│ ┌────────────────────────────────────┐   │
│ │ ⚠️  Needs review                   │   │  ← Verdict-first hero
│ │ Todo Calendar UI App               │   │
│ │ d8cab376 · 5d ago · 8m 24s         │   │
│ │                                    │   │
│ │  ⊙ 50          build ✓ · 0/3 ⚠     │   │  ← Score + signals
│ │                                    │   │
│ │  [View PR]  [Open report]  [Re-run]│   │
│ └────────────────────────────────────┘   │
│                                          │
│ ╭ Summary · Plan · Diff · Tests · Logs ╮ │  ← Segmented tabs
│ │ tab body                              │ │     (no horizontal scroll)
│ ╰───────────────────────────────────────╯ │
└──────────────────────────────────────────┘
```

Currency formatter pass: `$1.08` not `$1.0813`. Helper utility added at
`frontend/src/lib/formatCost.ts`:

```ts
export function formatCost(usd: number): string {
  if (usd < 1) return `$${usd.toFixed(2)}`;
  if (usd < 100) return `$${usd.toFixed(2)}`;
  if (usd < 10000) return `$${Math.round(usd)}`;
  return `$${(usd / 1000).toFixed(1)}k`;
}
```

### Desktop

Same hero. 3-column body kept for power users (file tree | diff | overview).
Right rail collapses below 1024 px into a tab.

---

## Library + Migrations (`/library`, `/migrations`)

App-Store-style gallery. Pattern:

```
┌──────────────────────────────────────────┐
│ Library                                  │
│ 28 solutions · 5 categories              │
│                                          │
│ ┌─Featured──────────────────────────┐    │
│ │                                   │    │  ← One large featured card
│ │  Today: Anthropic chat starter    │    │     (curated weekly)
│ │  ≈ 4 credits · 8 files             │    │
│ │  [Add to project]                 │    │
│ └───────────────────────────────────┘    │
│                                          │
│  [All] [People & Money 8] [Marketing 7]  │  ← Category chips
│  [UI & Growth 7] [Data 6]                │
│                                          │
│  🔍 Search solutions…                    │
│                                          │
│ ┌──────────┐ ┌──────────┐ ┌──────────┐   │
│ │ Card 1   │ │ Card 2   │ │ Card 3   │   │  ← Grid (2-col phone,
│ ├──────────┤ ├──────────┤ ├──────────┤   │     3-col tablet,
│ │ Card 4   │ │ Card 5   │ │ Card 6   │   │     4-col desktop)
│ └──────────┘ └──────────┘ └──────────┘   │
└──────────────────────────────────────────┘
```

Card structure: 32 px icon (provider/category), 1-line title, 2-line brief,
meta-row (category pill, credits estimate, files estimate), tap-press scale
0.97, hover lift `--shadow-card-hover`.

---

## Billing + Pricing

### Billing (`/billing`)

Lead with the current plan card (kept). Replace the 2-number "Runs / Spend"
panel with a usage histogram (last-30-days bar chart, `recharts`). Add a
disclosure-collapsed "Compare plans" panel — open by default for FREE/PRO,
collapsed by default for TEAM/ENTERPRISE.

### Pricing (`/pricing`)

Group model cards by provider with a section header. Inside each provider,
sort by tier (Architect → Workhorse → Utility). Add a clear edit affordance
to the per-1M-token cost (small ✏ on hover; tap-target on phone).

---

## Settings (`/settings`)

Replace flat scroll with anchored sections.

**Desktop**: left rail of section links (sticky), right side renders the
section.

**Mobile**: top-level sections as accordion cards. Tapping a section
expands inline; double-tap collapses. State persists in URL hash so a
shared link opens at the right section.

Sections (no functional removal):

```
1. Account            (name, email, password, MFA)
2. Workspace          (default theme, time zone)
3. Integrations       (GitHub PAT, Slack, Bitbucket)
4. Notifications      (per-event toggles)
5. Standards          (org-level standards — TEAM+)
6. SSO                (org SSO — TEAM+)
7. Org                (member management — ORG_ADMIN+)
8. Danger zone        (delete account, export data)
```

---

## Login (`/login`)

Keep the split layout. Refinements:

- Inflate every hit target to 44 px (password reveal icon, social buttons).
- Soften the marketing-list bullet icons (smaller, less brand-blue, more
  text-muted).
- Add a one-time motion on first render: the headline fades in via
  `--motion-reveal` (200 ms). No looping, no typing animation.
- "Continue with Google" + "Continue with GitHub" buttons get the new
  `--shadow-card-rest` inner highlight.

---

## Surface-by-surface acceptance criteria

Every redesigned surface must pass:

1. **375 px viewport** — no horizontal scroll, every interactive element
   ≥ 44 px tall, primary action visible without scroll.
2. **768 px viewport** — layout adapts cleanly (no awkward 2-col→1-col
   jump mid-card).
3. **1280 px viewport** — content fills the viewport horizontally where
   classified `wide+fill`, no more than 24 px of trailing right whitespace
   on a `wide` page.
4. **Dark + light themes** — both legible, both AA-contrast on body text.
5. **Keyboard navigation** — Tab order matches visual order, focus rings
   visible, every interactive control reachable.
6. **Screen reader** — every region has an `aria-label`; every status
   colour has a textual equivalent.
7. **Reduced motion** — `@media (prefers-reduced-motion: reduce)` collapses
   `--motion-reveal` to 0 duration and disables the press scale.

## Implementation plan (mapped to TODOs)

1. **Tokens** (`frontend/src/tokens.css`) — add the new surface / depth /
   motion / radius / hit-target / breakpoint tokens; preserve every
   existing name.
2. **TopBar** (`frontend/src/v3/components/TopBar.tsx`) — rebuild with
   visible command input + smaller brand pill + cleaner mobile drawer.
3. **BottomNav** (`frontend/src/v3/components/BottomNav.tsx`) — active
   indicator pill, filled icons, press feedback.
4. **Project dashboard hero** (`frontend/src/pages/project/ProjectDashboardPage.tsx`
   + `PulseTab.tsx`) — replace header region with the new hero +
   metric strip.
5. **Tabs** (new helper `frontend/src/components/shared/SegmentedTabs.tsx`)
   — iOS-style segmented control used by project dashboard, run detail.
6. **Runs list** (`frontend/src/v3/pages/RunsListPage.tsx`) — strip
   marketing copy, compress header, sticky filter row with reset.
7. **Library + Migrations** (`frontend/src/v3/pages/LibraryPage.tsx`,
   `MigrationsPage.tsx`) — featured hero, category chips, grid.
8. **Run detail hero** (`frontend/src/components/runs/RunDetailTabs.tsx`
   header region) — verdict-first hero, score ring inline.
9. **Cost formatter** (`frontend/src/lib/formatCost.ts`) — kill 4-decimal
   currency.
10. **Settings sections** (`frontend/src/pages/SettingsPage.tsx`) —
    accordion / anchor pattern.
11. **Card primitives** (`frontend/src/components/shared/Card.tsx`,
    `Surface.tsx`) — codify the inner-highlight + hover-lift treatment so
    every card in the app gets it for free.
12. **Motion utilities** (`frontend/src/index.css`) — `.press-effect`,
    `.hover-lift`, `.tap-fade` utility classes wired to the new motion
    tokens.

**No backend file changes.** Every API client call remains identical.
Every API contract is unchanged.

## Out of scope (explicit non-goals)

- Font swap (the user said: keep JetBrains Mono).
- Palette change (Graphite + Signal stays — only the surface ladder gets a
  proper inner-light treatment).
- Feature removal — every feature stays.
- Backend changes — none.
- Marketing-site changes (`website/` directory is untouched).
- New routes / new tabs / new gates.
