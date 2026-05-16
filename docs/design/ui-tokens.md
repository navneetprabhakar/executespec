# UI design tokens

> Source of truth: [`frontend/src/tokens.css`](../../frontend/src/tokens.css), [`frontend/src/design-system/primitives.css`](../../frontend/src/design-system/primitives.css), and [`frontend/tailwind.config.ts`](../../frontend/tailwind.config.ts). If this doc disagrees with those files, the CSS wins.

This doc is generated from the code. Every variable name and value below is verbatim from the CSS files cited above.

## 1. Two-layer system

Token resolution flows through two layers:

1. **Primitive layer** (`design-system/primitives.css`) — raw color values prefixed `--c-*`. No semantic meaning ("`--c-graphite-2` is a colour, not a card surface").
2. **Semantic layer** (`tokens.css`) — meaning-named tokens like `--text-primary`, `--accent-blue`, `--status-success`. Each aliases a primitive (or another semantic, recursively).

Component CSS only reads semantic tokens. Touching primitives in a component CSS rule is a smell — change the semantic alias instead.

Theming flips the semantic layer via `[data-theme='light']` overrides in `tokens.css`. Primitives stay constant; aliases re-point.

## 2. Primitive layer (`primitives.css`)

### 2.1 Graphite (dark surface ladder, 9 steps)

| Variable           | Value     | Role                                                  |
| ------------------ | --------- | ----------------------------------------------------- |
| `--c-graphite-0`   | `#10131A` | Page background (dark default)                        |
| `--c-graphite-1`   | `#161A23` | Lowest surface                                        |
| `--c-graphite-2`   | `#1A1F29` | Card                                                  |
| `--c-graphite-3`   | `#232936` | Card-elevated                                         |
| `--c-graphite-4`   | `#2D3441` | Surface border (subtle)                               |
| `--c-graphite-5`   | `#3D4553` | Surface divider                                       |
| `--c-graphite-6`   | `#545C6A` | Disabled foreground                                   |
| `--c-graphite-7`   | `#5E6771` | `--border-default` — AA non-text passing (3.05 on g-2)|
| `--c-graphite-8`   | `#7B8593` | `--border-hover` — AA-large body passing              |

The 2026-05-06 website-parity refresh warmed each step ~3-5° toward charcoal-with-indigo to match the marketing site.

### 2.2 Paper (light surface ladder, 5 steps)

| Variable        | Value     | Role                                       |
| --------------- | --------- | ------------------------------------------ |
| `--c-paper-0`   | `#FAFAFB` | Page background (light)                    |
| `--c-paper-1`   | `#F3F4F6` | Card (light)                               |
| `--c-paper-2`   | `#E7E9EE` | Card-elevated (light)                      |
| `--c-paper-3`   | `#D8DCE3` | Border (light)                             |
| `--c-paper-4`   | `#BFC6D0` | Border-strong (light, AA-non-text floor)   |

### 2.3 Ink scale (text)

| Variable        | Value     | Role                                                          |
| --------------- | --------- | ------------------------------------------------------------- |
| `--c-ink-100`   | `#ECEEF2` | Primary text                                                  |
| `--c-ink-200`   | `#C9D1DB` | Secondary text                                                |
| `--c-ink-300`   | `#8A94A6` | Muted text                                                    |
| `--c-ink-400`   | `#5E6771` | Subtle / caption text (same hex as graphite-7 by design)      |

### 2.4 Brand — Signal Blue + Teal

| Variable                   | Value     | Role                                                                       |
| -------------------------- | --------- | -------------------------------------------------------------------------- |
| `--c-signal-blue`          | `#3B6FCC` | Primary CTA surface — AA-body on white text (4.85 contrast)                |
| `--c-signal-blue-hover`    | `#2B5BB3` | Hover (darker, not lighter — preserves contrast)                           |
| `--c-signal-teal`          | `#7AD1C8` | Gradient destination (logo, primary CTA, scorecard highlight)              |
| `--c-signal-blue-on-dark`  | `#7AA5F5` | Body-text-on-dark variant — AA-body on graphite-2 (~7.4 contrast)          |

### 2.5 Pop — marketing accent

| Variable           | Value     | Role                                          |
| ------------------ | --------- | --------------------------------------------- |
| `--c-pop-orange`   | `#EC4899` | (Misnamed — actually pink since the R10 retarget) marketing gradient accent |
| `--c-pop-amber`    | `#FBBF24` | Pop amber for gradient pairs                  |

`--c-pop-orange` was retargeted from orange (`#F97316`) to pink (`#EC4899`) to avoid clashing with `--c-warning` (`#F59E0B`) and `--status-partial` (`#F5A524`). The variable name stays "orange" to avoid churning callers.

### 2.6 Semantic states

| Variable        | Value     | Role                                          |
| --------------- | --------- | --------------------------------------------- |
| `--c-success`   | `#10B981` | Green                                         |
| `--c-warning`   | `#F59E0B` | Amber (distinct hue from `--c-pop-amber`)     |
| `--c-error`     | `#EF4444` | Red                                           |
| `--c-info`      | `#22D3EE` | Cyan-teal (intentionally distinct from brand) |

### 2.7 Accent — non-brand visual variety

| Variable               | Value     | Role                                |
| ---------------------- | --------- | ----------------------------------- |
| `--c-accent-purple`    | `#A78BFA` | Harness, planner, thinking-state    |
| `--c-accent-pink`      | `#EC4899` | Blocked status                      |

## 3. Semantic layer — dark theme (default) from `tokens.css`

These aliases live in `:root`. Light-theme overrides are in section 4.

### 3.1 Surface ladder (legacy "geist-*" names + role-named aliases)

| Variable                    | Aliases               | Role                                                         |
| --------------------------- | --------------------- | ------------------------------------------------------------ |
| `--geist-bg`                | `var(--c-graphite-0)` | Page background                                              |
| `--geist-bg-secondary`      | `var(--c-graphite-2)` | Secondary page-level surface                                 |
| `--bg-primary`              | `var(--geist-bg)`     | Drawer / `MoreDrawer` / `RunStamp` page-equivalent surface   |
| `--surface-page`            | `var(--c-graphite-0)` | 2026-05-14 role-named alias for the page                     |
| `--surface-card`            | `var(--c-graphite-2)` | Card                                                         |
| `--surface-raised`          | `var(--c-graphite-3)` | Raised card / hover state                                    |
| `--surface-sunken`          | `var(--c-graphite-1)` | Sunken / inset                                               |

### 3.2 Gray scale (legacy step names)

| Variable     | Aliases                  | Notes                                          |
| ------------ | ------------------------ | ---------------------------------------------- |
| `--gray-100` | `var(--c-graphite-2)`    |                                                |
| `--gray-200` | `var(--c-graphite-3)`    |                                                |
| `--gray-300` | `var(--c-graphite-4)`    |                                                |
| `--gray-400` | `var(--c-graphite-5)`    |                                                |
| `--gray-500` | `var(--c-graphite-6)`    |                                                |
| `--gray-600` | `var(--c-graphite-6)`    | Collapsed onto g-6 (was `#66707D`)             |
| `--gray-700` | `var(--c-ink-300)`       | `#8A94A6`                                      |
| `--gray-800` | `#AAB2BF`                | No primitive — single literal consumer         |
| `--gray-900` | `var(--c-ink-200)`       | `#C9D1DB`                                      |
| `--gray-1000`| `var(--c-ink-100)`       | `#ECEEF2`                                      |

### 3.3 Borders

| Variable                   | Aliases                | Role                                                       |
| -------------------------- | ---------------------- | ---------------------------------------------------------- |
| `--border-default`         | `var(--c-graphite-7)`  | Card boundaries — AA non-text (3.05 on graphite-2)         |
| `--border-hover`           | `var(--c-graphite-8)`  | Hover step — AA-large body                                 |
| `--border-divider-subtle`  | `var(--c-graphite-5)`  | Explicit non-AA hairlines (table rows, inner dividers)     |
| `--hairline`               | `var(--c-graphite-5)`  | 2026-05-14 role-name for the same thing                    |

The R12/F-15 audit moved `--border-default` from `--c-graphite-5` (1.59 contrast, fail) → `--c-graphite-7` (3.05, pass) so card boundaries hit WCAG SC 1.4.11.

### 3.4 Text

| Variable           | Aliases               | Role                          |
| ------------------ | --------------------- | ----------------------------- |
| `--text-primary`   | `var(--c-ink-100)`    | Primary body text             |
| `--text-secondary` | `var(--c-ink-200)`    | Secondary body text           |
| `--text-muted`     | `var(--c-ink-300)`    | Muted / caption text          |

### 3.5 Accent (brand + roles)

| Variable               | Value / alias                       | Role                                                          |
| ---------------------- | ----------------------------------- | ------------------------------------------------------------- |
| `--accent-blue`        | `#5B8DEF` (dark theme literal)      | Button surface — white text on top                            |
| `--accent-blue-hover`  | `#7AA5F5` (dark theme literal)      | Button hover                                                  |
| `--accent-blue-text`   | `var(--c-signal-blue-on-dark)`      | Brand-blue text on dark surface (#7AA5F5)                     |
| `--accent-harness`     | `var(--c-accent-purple)`            | Agent-orchestration purple                                    |
| `--accent-planner`     | `var(--c-info)`                     | Info-cyan, split from brand blue                              |
| `--brand-grad`         | `linear-gradient(135deg, var(--accent-blue) 0%, var(--c-signal-teal) 100%)` | Logo, primary CTAs, scorecard highlight |

Note: `--accent-blue` in **dark** theme is literal `#5B8DEF` (the brighter "marketing site" hex) for legibility on graphite-0. The primitive `--c-signal-blue` (`#3B6FCC`, AA-on-white) is the light-theme override target.

### 3.6 Status — distinct hue per semantic group

| Variable             | Value / alias                   | Role                                          |
| -------------------- | ------------------------------- | --------------------------------------------- |
| `--status-success`   | `var(--c-success)` (`#10B981`)  | Green                                         |
| `--status-executing` | `#5B89E0`                       | Blue, separated from brand (AA-body)          |
| `--status-thinking`  | `var(--c-accent-purple)`        | Purple                                        |
| `--status-waiting`   | `var(--c-warning)` (`#F59E0B`)  | Amber                                         |
| `--status-partial`   | `#F5A524`                       | Softer amber — uniquely positioned between warning and pop-amber |
| `--status-error`     | `var(--c-error)` (`#EF4444`)    | Red                                           |
| `--status-blocked`   | `var(--c-accent-pink)`          | Pink                                          |
| `--status-queued`    | `#66707D`                       | Historic literal — half-step between g-5 and ink-300 |
| `--status-building`  | `var(--c-warning)`              | Legacy alias                                  |
| `--numeric-neutral`  | `var(--c-ink-200)`              | Money / tokens / stats (was amber; collided with warning) |

### 3.7 Radius

| Variable          | Value     | Role                  |
| ----------------- | --------- | --------------------- |
| `--radius-sm`     | `4px`     | (was 2px)             |
| `--radius-md`     | `6px`     | (was 4px)             |
| `--radius-lg`     | `8px`     | (was 6px)             |
| `--radius-xl`     | `12px`    |                       |
| `--radius-2xl`    | `16px`    |                       |
| `--radius-full`   | `9999px`  | Pill                  |
| `--radius-control`| `8px`     | 2026-05-14 role-name  |
| `--radius-card`   | `14px`    | 2026-05-14 role-name  |
| `--radius-pill`   | `9999px`  | 2026-05-14 role-name  |

### 3.8 Spacing scale (rem)

| Variable      | Value      |
| ------------- | ---------- |
| `--space-0`   | `0`        |
| `--space-1`   | `0.25rem`  |
| `--space-2`   | `0.5rem`   |
| `--space-3`   | `0.75rem`  |
| `--space-4`   | `1rem`     |
| `--space-5`   | `1.25rem`  |
| `--space-6`   | `1.5rem`   |
| `--space-8`   | `2rem`     |
| `--space-10`  | `2.5rem`   |
| `--space-12`  | `3rem`     |
| `--space-16`  | `4rem`     |
| `--page-gutter-mobile`  | `var(--space-4)` |
| `--page-gutter-tablet`  | `var(--space-6)` |
| `--page-gutter-desktop` | `var(--space-8)` |
| `--page-stack`          | `var(--space-6)` |

### 3.9 Elevation (dark)

| Variable        | Value                                        |
| --------------- | -------------------------------------------- |
| `--elevation-1` | `0 1px 2px 0 rgba(0, 0, 0, 0.4)`             |
| `--elevation-2` | `0 4px 12px 0 rgba(0, 0, 0, 0.5)`            |
| `--elevation-3` | `0 12px 32px 0 rgba(0, 0, 0, 0.6)`           |

### 3.10 Shadows (2026-05-14 UX overhaul — dark)

| Variable                | Value                                                                            |
| ----------------------- | -------------------------------------------------------------------------------- |
| `--shadow-card-rest`    | `inset 0 1px 0 rgba(255, 255, 255, 0.04), 0 1px 2px rgba(0, 0, 0, 0.4)`           |
| `--shadow-card-hover`   | `inset 0 1px 0 rgba(255, 255, 255, 0.06), 0 8px 20px rgba(0, 0, 0, 0.55)`         |
| `--shadow-sheet`        | `0 -12px 32px rgba(0, 0, 0, 0.6)`                                                 |
| `--shadow-overlay`      | `0 24px 64px rgba(0, 0, 0, 0.7)`                                                  |

The inner-highlight trick (`inset 0 1px 0 rgba(255,255,255,0.04)`) is the macOS Aqua "inner light" — it makes a flat dark card read as a slightly raised plane.

### 3.11 Motion

| Variable             | Value                                                                                       |
| -------------------- | ------------------------------------------------------------------------------------------- |
| `--ease-out`         | `cubic-bezier(0.16, 1, 0.3, 1)`                                                              |
| `--ease-spring`      | `cubic-bezier(0.34, 1.56, 0.64, 1)`                                                          |
| `--duration-fast`    | `120ms`                                                                                      |
| `--duration-base`    | `200ms`                                                                                      |
| `--duration-slow`    | `320ms`                                                                                      |
| `--motion-press`     | `transform 80ms cubic-bezier(0.4, 0, 0.6, 1)`                                                |
| `--motion-tap`       | `opacity 120ms ease-out`                                                                     |
| `--motion-reveal`    | `opacity 200ms cubic-bezier(0.2, 0, 0, 1), transform 200ms cubic-bezier(0.2, 0, 0, 1)`       |
| `--motion-pulse`     | `background-color 320ms ease-out`                                                            |

### 3.12 Focus ring

| Variable        | Value                                                                |
| --------------- | -------------------------------------------------------------------- |
| `--focus-ring`  | `0 0 0 2px var(--geist-bg), 0 0 0 4px var(--accent-blue)`            |

Two-stop ring: 2 px of page bg, then 2 px of brand blue. Visible on every dark-theme control.

### 3.13 Font scale

| Variable        | Value      |
| --------------- | ---------- |
| `--text-xs`     | `0.75rem`  |
| `--text-sm`     | `0.8125rem`|
| `--text-base`   | `0.9375rem`|
| `--text-lg`     | `1.0625rem`|
| `--text-xl`     | `1.25rem`  |
| `--text-2xl`    | `1.5rem`   |
| `--text-3xl`    | `1.875rem` |
| `--text-4xl`    | `2.5rem`   |
| `--text-5xl`    | `3.25rem`  |

### 3.14 Hit targets

| Variable             | Value     | Role                              |
| -------------------- | --------- | --------------------------------- |
| `--hit-min-mobile`   | `44px`    | iOS / Android min tap target      |
| `--hit-min-desktop`  | `32px`    | Desktop min click target          |
| `--hit-comfort`      | `48px`    | Comfortable tap target            |

### 3.15 Breakpoints (shared with `useBreakpoint()`)

| Variable        | Value      |
| --------------- | ---------- |
| `--bp-phone`    | `480px`    |
| `--bp-tablet`   | `768px`    |
| `--bp-desktop`  | `1024px`   |
| `--bp-wide`     | `1280px`   |
| `--bp-xl`       | `1536px`   |

## 4. Semantic layer — light theme (`[data-theme='light']` overrides)

Activated by `document.documentElement.dataset.theme = 'light'` (set pre-paint by `main.tsx` from `localStorage.signal:theme`). Variables override the dark defaults.

### 4.1 Surfaces

| Variable                 | Light-theme alias / value          |
| ------------------------ | ---------------------------------- |
| `--geist-bg`             | `var(--c-paper-0)` (`#FAFAFB`)     |
| `--geist-bg-secondary`   | `var(--c-paper-1)` (`#F3F4F6`)     |
| `--bg-primary`           | `var(--geist-bg)`                  |
| `--surface-page`         | `var(--c-paper-0)`                 |
| `--surface-card`         | `var(--c-paper-1)`                 |
| `--surface-raised`       | `#FFFFFF`                          |
| `--surface-sunken`       | `var(--c-paper-2)`                 |

### 4.2 Gray scale (light)

| Variable     | Light-theme alias              |
| ------------ | ------------------------------ |
| `--gray-100` | `var(--c-paper-1)`             |
| `--gray-200` | `var(--c-paper-2)`             |
| `--gray-300` | `var(--c-paper-3)`             |
| `--gray-400` | `var(--c-paper-4)`             |
| `--gray-500` | `#959EAB` (between paper-4 and graphite-6) |
| `--gray-600` | `#6E7785`                      |
| `--gray-700` | `var(--c-graphite-6)`          |
| `--gray-800` | `var(--c-graphite-5)`          |
| `--gray-900` | `var(--c-graphite-3)`          |
| `--gray-1000`| `var(--c-graphite-0)`          |

### 4.3 Borders (light)

| Variable                  | Value     | Role                              |
| ------------------------- | --------- | --------------------------------- |
| `--border-default`        | `#BFC6D0` | AA non-text floor on light theme  |
| `--border-hover`          | `#A0A8B5` | AA-large hover step               |
| `--border-divider-subtle` | `#D8DCE3` | Explicit non-AA hairlines         |
| `--hairline`              | `var(--c-paper-3)` |                          |

### 4.4 Text (light)

| Variable           | Light-theme alias / value      |
| ------------------ | ------------------------------ |
| `--text-primary`   | `var(--c-graphite-0)`          |
| `--text-secondary` | `var(--c-graphite-5)`          |
| `--text-muted`     | `#6E7785`                      |

### 4.5 Accent (light) — deeper variants

| Variable                 | Light-theme value      | Notes                                                                |
| ------------------------ | ---------------------- | -------------------------------------------------------------------- |
| `--accent-blue`          | `#2563EB`              | Deeper than the dark default (AA on white)                           |
| `--accent-blue-hover`    | `#1D4ED8`              |                                                                      |
| `--accent-blue-text`     | `#2563EB`              | Same as button surface — light theme already has the right contrast  |
| `--status-error`         | `#DC2626`              | Light-mode-only deeper red                                           |
| `--status-waiting`       | `#D97706`              | Light-mode-only deeper amber                                         |
| `--numeric-neutral`      | `var(--c-graphite-6)`  |                                                                      |

### 4.6 Elevation (light)

| Variable        | Value                                                |
| --------------- | ---------------------------------------------------- |
| `--elevation-1` | `0 1px 2px 0 rgba(15, 23, 42, 0.05)`                  |
| `--elevation-2` | `0 4px 12px 0 rgba(15, 23, 42, 0.08)`                 |
| `--elevation-3` | `0 12px 32px 0 rgba(15, 23, 42, 0.12)`                |

### 4.7 Shadows (light)

| Variable              | Value                                                                                    |
| --------------------- | ---------------------------------------------------------------------------------------- |
| `--shadow-card-rest`  | `inset 0 1px 0 rgba(255, 255, 255, 0.7), 0 1px 2px rgba(15, 23, 42, 0.04)`                |
| `--shadow-card-hover` | `inset 0 1px 0 rgba(255, 255, 255, 0.9), 0 8px 20px rgba(15, 23, 42, 0.08)`               |
| `--shadow-sheet`      | `0 -12px 32px rgba(15, 23, 42, 0.1)`                                                      |
| `--shadow-overlay`    | `0 24px 64px rgba(15, 23, 42, 0.14)`                                                      |

### 4.8 Focus ring (light)

| Variable        | Value                                                                |
| --------------- | -------------------------------------------------------------------- |
| `--focus-ring`  | `0 0 0 2px var(--geist-bg), 0 0 0 4px var(--accent-blue)`            |

(Same recipe; values change because `--geist-bg` and `--accent-blue` shift.)

## 5. Signal-scoped tokens (`signal/signal.css`)

The Signal chrome owns its own token set, scoped to `.signal-app` so it never leaks. The pre-paint script in `main.tsx` adds the `signal-app` class to `<html>` and sets `data-signal-theme="dark"|"light"` based on `localStorage.signal:theme`.

### 5.1 Light (default) tokens

```
--signal-bg            #FBFAF6
--signal-card-bg       #FFFFFF
--signal-card-elev     #F4F2EB
--signal-bg-translucent rgba(251, 250, 246, 0.88)
--signal-phone-frame   #0E0F12

--signal-ink           #0E0F12
--signal-ink-soft      #2D2E33
--signal-text-dim      #6B6960
--signal-text-mute     #9A968A

--signal-hero-bg-1     #0E0F12
--signal-hero-bg-2     #15161B
--signal-hero-text     #FBFAF6
--signal-hero-text-dim rgba(251, 250, 246, 0.6)
--signal-hero-text-mute rgba(251, 250, 246, 0.15)
--signal-hero-glow     rgba(244, 211, 94, 0.18)
--signal-hero-btn-bg   rgba(255, 255, 255, 0.1)
--signal-hero-btn-bg-hover rgba(255, 255, 255, 0.18)

--signal-hairline         #E6E2D8
--signal-hairline-strong  #BFB9A8

--signal-y               #F4D35E   (Signal yellow accent)
--signal-y-soft          #FBEEC0
--signal-y-text          #5A4A08
--signal-coral           #FF6B6B
--signal-coral-soft      #FFD9D5
--signal-coral-text      #8A2828
--signal-mint            #7CE3B4
--signal-mint-soft       #D6F4E2
--signal-mint-text       #0E0F12
--signal-mint-text-on-light #1D6543
--signal-lavender        #C4B8E0
--signal-lavender-soft   #E8E2F3
--signal-ocean           #5C9DDB

--signal-r-sm    10px
--signal-r-md    16px
--signal-r-lg    22px
--signal-r-xl    28px
--signal-shadow  0 6px 16px -6px rgba(14, 15, 18, 0.08)
--signal-shadow-strong  rgba(14, 15, 18, 0.18)
```

### 5.2 Signal dark overrides (`[data-signal-theme='dark']`)

```
--signal-bg            #0F1014
--signal-card-bg       #1B1C22
--signal-card-elev     #0F1014
--signal-bg-translucent rgba(15, 16, 20, 0.88)
--signal-phone-frame   #34363D

--signal-ink           #F3F1ED
--signal-ink-soft      #D6D3CA
--signal-text-dim      #9A9B95
--signal-text-mute     #6E6E68

--signal-hero-bg-1     #1F2028
--signal-hero-bg-2     #15161B
--signal-hero-text-dim rgba(251, 250, 246, 0.55)
--signal-hero-text-mute rgba(255, 255, 255, 0.08)
--signal-hero-glow     rgba(244, 211, 94, 0.22)
--signal-hero-btn-bg   rgba(255, 255, 255, 0.07)
--signal-hero-btn-bg-hover rgba(255, 255, 255, 0.14)

--signal-hairline         rgba(255, 255, 255, 0.08)
--signal-hairline-strong  rgba(255, 255, 255, 0.16)

--signal-y-soft        rgba(244, 211, 94, 0.18)
--signal-y-text        #F4D35E
--signal-coral-soft    rgba(255, 107, 107, 0.18)
--signal-coral-text    #FF8585
--signal-mint-soft     rgba(124, 227, 180, 0.18)
--signal-mint-text-on-light #7CE3B4
--signal-lavender-soft rgba(196, 184, 224, 0.16)
```

## 6. Tailwind theme extension (`tailwind.config.ts`)

The Tailwind config maps utility keys to CSS variables — every color is `var(--token)`, never a literal hex. This is the bridge that lets utility classes pick up theme switches automatically.

```ts
darkMode: 'class',
content: ['./src/**/*.{ts,tsx}', './index.html'],
```

### 6.1 Color keys

```ts
geist: {
  bg: 'var(--geist-bg)',
  'bg-secondary': 'var(--geist-bg-secondary)',
},
gray: {
  100: 'var(--gray-100)',
  200: 'var(--gray-200)',
  300: 'var(--gray-300)',
  400: 'var(--gray-400)',
  500: 'var(--gray-500)',
  600: 'var(--gray-600)',
  700: 'var(--gray-700)',
  800: 'var(--gray-800)',
  900: 'var(--gray-900)',
  1000: 'var(--gray-1000)',
},
accent: {
  blue:        'var(--accent-blue)',
  'blue-hover':'var(--accent-blue-hover)',
  'blue-text': 'var(--accent-blue-text)',
  red:         'var(--status-error)',
  amber:       'var(--status-waiting)',
  green:       'var(--status-success)',
  harness:     'var(--accent-harness)',
  planner:     'var(--accent-planner)',
},
status: {
  success:   'var(--status-success)',
  executing: 'var(--status-executing)',
  thinking:  'var(--status-thinking)',
  waiting:   'var(--status-waiting)',
  partial:   'var(--status-partial)',
  error:     'var(--status-error)',
  blocked:   'var(--status-blocked)',
  queued:    'var(--status-queued)',
  building:  'var(--status-building)',
},
border: {
  default: 'var(--border-default)',
  hover:   'var(--border-hover)',
},
textColor: {
  primary:   'var(--text-primary)',
  secondary: 'var(--text-secondary)',
  muted:     'var(--text-muted)',
},
```

Use Tailwind utility forms in JSX:

```tsx
<div className='bg-status-success/10 text-status-success border-status-success/30 ...' />
```

### 6.2 Border radius

```ts
borderRadius: {
  sm: 'var(--radius-sm)',
  md: 'var(--radius-md)',
  lg: 'var(--radius-lg)',
}
```

### 6.3 Font family

Both `sans` and `mono` resolve to the same chain — JetBrainsMono is the single brand face:

```ts
fontFamily: {
  sans: ['JetBrains Mono', 'ui-monospace', 'SFMono-Regular', 'Menlo', 'monospace'],
  mono: ['JetBrains Mono', 'ui-monospace', 'SFMono-Regular', 'Menlo', 'monospace'],
}
```

### 6.4 Spacing extensions

Sub-pixel design grid:

```ts
spacing: {
  'sp-1':  '4px',
  'sp-2':  '8px',
  'sp-3':  '12px',
  'sp-4':  '16px',
  'sp-6':  '24px',
  'sp-8':  '32px',
  'sp-12': '48px',
  'sp-16': '64px',
  'sp-24': '96px',
  'sp-32': '128px',
}
```

### 6.5 Height utilities

```ts
height:    {dvh: '100dvh'},
minHeight: {dvh: '100dvh'},
maxHeight: {dvh: '100dvh'},
```

These exist so `h-dvh` works even on iOS Safari where `100vh` includes the URL bar.

### 6.6 Animation keyframes

```ts
animation: {
  'slide-in-right': 'slide-in-right 0.3s ease-out',
  'fade-in-up':     'fade-in-up 0.4s ease-out',
  shimmer:          'shimmer 2s linear infinite',
  'log-line':       'log-line 0.15s ease-out',
  'status-pulse':   'status-pulse 2s ease-in-out infinite',
}
```

Underlying keyframes (`slide-in-right`, `fade-in-up`, `shimmer`, `log-line`, `status-pulse`) are defined in the same `tailwind.config.ts` `keyframes` block.

### 6.7 PostCSS pipeline

Tailwind 4 ships PostCSS support as a separate package. The pipeline is configured in `frontend/postcss.config.js`:

```js
export default {
  plugins: {
    '@tailwindcss/postcss': {},
  },
};
```

Autoprefixer is no longer needed (Tailwind 4 bakes it in).

## 7. Typography

### 7.1 Font loading

JetBrainsMono is loaded via Google Fonts at the top of `frontend/src/signal/signal.css`:

```css
@import url('https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@300..700&family=JetBrains+Mono:wght@400..600&display=swap');
```

`display=swap` falls back to system fonts during the network load window. The Tailwind chain (`ui-monospace`, `SFMono-Regular`, `Menlo`, `monospace`) is the fallback.

The fallback choice is intentional — JetBrainsMono is the brand face on every surface (login + dashboard + project surfaces), so even the system-font flash looks monospace.

### 7.2 No more Geist Sans / Geist Mono

The PR #108 (2026-05-11) unification dropped both Geist faces. Every CSS var that used to resolve to a Geist family (`--v3-font-sans`, `--v3-font-mono`) now resolves to the same JetBrainsMono chain.

## 8. Score-band palette (verdict thresholds)

Source: [`frontend/src/components/runs/ScoreRing.tsx`](../../frontend/src/components/runs/ScoreRing.tsx) line 27–32 and [`frontend/src/lib/verdict.ts`](../../frontend/src/lib/verdict.ts).

### 8.1 4-band score color (`ScoreRing.defaultColor`)

```ts
function defaultColor(score: number): string {
  if (score >= 85) return 'var(--status-success)';    // green
  if (score >= 70) return 'var(--accent-blue)';        // blue
  if (score >= 50) return 'var(--status-waiting)';     // amber
  return 'var(--status-error)';                        // red
}
```

| Band             | Range  | Token alias            | Resolves to (dark)   |
| ---------------- | ------ | ---------------------- | -------------------- |
| Shippable        | ≥85    | `--status-success`     | `#10B981`            |
| Solid            | 70-84  | `--accent-blue`        | `#5B8DEF` (dark)     |
| Needs attention  | 50-69  | `--status-waiting`     | `#F59E0B`            |
| Failed           | <50    | `--status-error`       | `#EF4444`            |

### 8.2 Verdict thresholds (`lib/verdict.ts`)

```
verifiedScore < 50         → blocked
verifiedScore < 85         → needs-review
verifiedScore ≥ 85         → shippable
```

These thresholds were aligned at ≥85 in the Signal P0/P1 punch-list (2026-05-16). Pre-2026-05-16 ResultPage previously used a 90 threshold; that's been collapsed onto 85 so RunCard, ScoreRing, VerdictPill, HomePage, and AllRunsPage all agree.

The threshold is multiplicative — the `verifiedScore` itself is computed multiplicatively from build + tests + reviewer signals (see backend `scoring/`).

### 8.3 Pill styling

Used by [`VerdictPill.tsx`](../../frontend/src/components/runs/VerdictPill.tsx):

| Verdict          | Wrapper classes                                                            | Icon          |
| ---------------- | -------------------------------------------------------------------------- | ------------- |
| `shippable`      | `border-status-success/30 bg-status-success/10 text-status-success`        | `CheckCircle2`|
| `needs-review`   | `border-status-waiting/30 bg-status-waiting/10 text-status-waiting`        | `AlertTriangle`|
| `blocked`        | `border-status-error/30 bg-status-error/10 text-status-error`              | `XCircle`     |

## 9. Theming flow

### 9.1 Pre-paint theme application (`main.tsx`)

```ts
document.documentElement.classList.add('signal-app');
const saved = localStorage.getItem('signal:theme');
const sysDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
document.documentElement.setAttribute('data-signal-theme', saved || (sysDark ? 'dark' : 'light'));
```

Both `data-theme` (for the `tokens.css` overrides) and `data-signal-theme` (for the Signal-scoped overrides) are set before React renders. The first paint is correct — no FOUC.

### 9.2 User-driven flip

`stores/useUiStore.ts` exposes `setTheme(t)` / `toggleTheme()`. Both write `<html data-theme>` + `<html data-signal-theme>` and persist to `localStorage.signal:theme`.

### 9.3 `prefers-color-scheme` default

For first-time visitors with no saved preference, `tokens.css` declares:

```css
@media (prefers-color-scheme: light) {
  :root:not([data-theme]) {
    color-scheme: light;
  }
}
```

The browser's preferred theme is respected until the user toggles, after which the saved preference overrides.

## 10. Accessibility — WCAG-AA regression tests

### 10.1 Pure-function helpers (`design-system/contrast.ts`)

```ts
parseHex(hex: string): [number, number, number] | null
luminance(rgb: [number, number, number]): number
contrastRatio(fg: string, bg: string): number

export const WCAG = {
  AA_BODY:     4.5,   // normal-size text (≥14pt / ≥18pt bold)
  AA_LARGE:    3.0,   // large text
  AA_NON_TEXT: 3.0,   // UI components (borders, icons, focus rings)
  AAA_BODY:    7.0,   // aspirational
};
```

These are pure functions — no DOM, no CSS parsing. They make `contrast.test.ts` a meaningful regression guard regardless of the test runner (the file is currently dormant; vitest hasn't been wired into CI yet).

### 10.2 Token-level contrast contract

[`frontend/src/design-system/contrast.test.ts`](../../frontend/src/design-system/contrast.test.ts) (dormant — vitest-shape) pins ratios for the round-3 audit fixes:

- `--border-default` on `--c-graphite-2` ≥ 3.0 (was 1.59 — fixed by moving to `--c-graphite-7`).
- `--status-executing` on `--c-graphite-2` ≥ 4.5 (was 4.19 — fixed by moving to `#5B89E0`).
- `--c-signal-blue-on-dark` on `--c-graphite-2` ≥ 4.5 (the dark-surface body-text variant).

Any palette tweak that shifts a hex must update **both** the CSS and the fixed value in `contrast.test.ts` so the test stays a true regression guard.

### 10.3 Live a11y crawl (`.pa11yci.json`)

[`frontend/.pa11yci.json`](../../frontend/.pa11yci.json) runs pa11y-ci with axe-core + HTML CodeSniffer against every public surface:

| URL                              | Standard | Notes                              |
| -------------------------------- | -------- | ---------------------------------- |
| `/login`                         | WCAG2AA  |                                    |
| `/register`                      | WCAG2AA  |                                    |
| `/forgot-password`               | WCAG2AA  |                                    |
| `/plans`                         | WCAG2AA  |                                    |
| `/pricing`                       | WCAG2AA  |                                    |
| `/terms`                         | WCAG2AA  |                                    |
| `/privacy`                       | WCAG2AA  |                                    |
| `/dpa`                           | WCAG2AA  |                                    |

Runner config:

```json
{
  "standard": "WCAG2AA",
  "runners": ["axe", "htmlcs"],
  "timeout": 30000,
  "wait": 1500,
  "viewport": {"width": 1440, "height": 900},
  "ignore": ["warning", "notice"]
}
```

Auth-gated routes (`/projects/*`, `/admin/*`) aren't crawled yet — pa11y can't authenticate without a cookie-priming driver. Follow-up plan in [`frontend/A11Y.md`](../../frontend/A11Y.md).

### 10.4 Run a11y locally

From `frontend/A11Y.md`:

```bash
cd frontend
npm install --save-dev pa11y-ci
npm run dev          # in one terminal; serves at :5173
npx pa11y-ci         # in another; reads .pa11yci.json
```

For contrast regression tests:

```bash
cd frontend
npm install --save-dev vitest @vitest/ui jsdom
npx vitest run contrast
```

## 11. Where to add a new token

### 11.1 Adding a primitive

1. Add `--c-foo: #hex;` to `frontend/src/design-system/primitives.css`.
2. Add a CSS comment explaining what it's for and why an existing primitive can't be reused.
3. Update this doc (section 2).

### 11.2 Adding a semantic alias

1. Add `--your-token: var(--c-foo);` to `frontend/src/tokens.css` `:root` (the dark theme).
2. Add the light-theme override under `[data-theme='light']` if it should differ.
3. If the token has a Tailwind utility key, extend `frontend/tailwind.config.ts`.
4. Update this doc (section 3 + 4 + 6).

### 11.3 Adding a Signal-scoped token

1. Add `--signal-foo: #hex;` to the `.signal-app` block in `frontend/src/signal/signal.css`.
2. Add the dark-mode override under `.signal-app[data-signal-theme='dark']`.
3. Update this doc (section 5).

## 12. Quick-reference cheatsheet

| When you want                          | Use                                       |
| -------------------------------------- | ----------------------------------------- |
| Page background                        | `bg-geist-bg` or `var(--geist-bg)`        |
| Card surface                           | `var(--surface-card)` (role-named alias)  |
| Primary CTA                            | `bg-accent-blue text-white`               |
| Brand-coloured inline text             | `text-accent-blue-text`                   |
| Default border                         | `border border-border-default`            |
| Body text                              | `text-primary`                            |
| Muted secondary text                   | `text-muted`                              |
| Success badge                          | `bg-status-success/10 text-status-success border-status-success/30` |
| Warning / "needs attention"            | `bg-status-waiting/10 text-status-waiting border-status-waiting/30` |
| Error badge                            | `bg-status-error/10 text-status-error border-status-error/30`       |
| Score 0-100 ring                       | `ScoreRing` (auto-picks colour by band)   |
| Verdict pill                           | `VerdictPill` (≥85 / 70-84 / <70 bands)   |
| Focus ring                             | `box-shadow: var(--focus-ring)`           |
| Card shadow                            | `box-shadow: var(--shadow-card-rest)`     |
| 100% viewport height (iOS-safe)        | `h-dvh`                                   |
| Card radius                            | `rounded-[var(--radius-card)]` or `--signal-r-md` for Signal cards |
| Pill radius                            | `rounded-full` or `var(--radius-pill)`    |
