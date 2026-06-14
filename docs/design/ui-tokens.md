# UI Tokens

> Design-token reference for the ExecuteSpec frontend.
>
> **Source of truth:**
>
> - `frontend/src/tokens.css` — Graphite + Signal tokens, light + dark themes
> - `frontend/src/signal/signal.css` — Signal-scoped chrome tokens + components
> - `frontend/tailwind.config.ts` — Tailwind v4 theme extensions that point at the CSS vars
> - `frontend/src/design-system/primitives.css` — primitive `--c-*` palette (loaded first)
>
> Tokens defined in those files override anything documented here. When they disagree, the CSS
> wins — update this file.

---

## Theming model

Two parallel theme attributes coexist:

| Attribute           | Scope                                     | Values            | Where it's set                                                                                                           |
| ------------------- | ----------------------------------------- | ----------------- | ------------------------------------------------------------------------------------------------------------------------ |
| `data-theme`        | Root `tokens.css` selectors               | `light` (or none) | `tokens.css` defaults to dark via `:root`; `[data-theme='light']` flips it.                                              |
| `data-signal-theme` | `signal.css` (scoped under `.signal-app`) | `light`, `dark`   | `frontend/src/signal/hooks/useSignalTheme.ts` — written to BOTH the shell root and `<html>` so public routes get themed. |

`frontend/src/main.tsx` also sets the `.signal-app` class on `<html>` and reads
`localStorage['signal:theme']` on first paint to avoid a flash of wrong theme. Tailwind's `dark:`
variant is defined as `&:where([data-theme='dark'], [data-theme='dark'] *)` (Tailwind v4
`@custom-variant` directive in `index.css`). That variant only matches when `data-theme='dark'` is
present — the default dark state is not wired to that variant, so `dark:` utilities are mostly
inactive today.

The dark theme is the default (the `:root` block in `tokens.css`); the `[data-theme='light']`
override flips the surface ladder, borders, text, accents, and elevation. Signal's chrome is
scoped under `.signal-app` and uses its own `--signal-*` token set with a separate light-default
behaviour (Signal's `.signal-app` declares light values; `.signal-app[data-signal-theme='dark']`
overrides).

### Console shell bridge (2026-06-12)

The protected web app now renders inside `frontend/src/console/ConsoleShell.tsx` by default. That
shell defines the `--ide-*` bridge tokens locally in `frontend/src/console/console.css` so the
existing IDE-native work surfaces can keep their component classes while the global chrome uses the
Merge Rail / Codex-like graphite palette instead of the retired VS Code metaphor.

Current console token direction:

| Role                   | Light     | Dark                  |
| ---------------------- | --------- | --------------------- |
| Page/editor background | `#f6f7f8` | `#0b0d10`             |
| Sidebar / cards        | `#ffffff` | `#11151b` / `#14171c` |
| Primary text           | `#111318` | `#eceef2`             |
| Muted text             | `#687181` | `#8a94a6`             |
| Accent                 | `#2f64d6` | `#4f7fe7`             |
| Success                | `#139a67` | `#7ad1c8`             |
| Warning                | `#b88700` | `#fbbf24`             |
| Error                  | `#d14b4b` | `#f08a8a`             |

Responsive shell rules also live in `console.css`: mobile top-bar icon targets are 44px, the drawer
hides the bottom tab bar while open, and `/compose` enters a focused mobile mode so its fixed action
footer is not stacked above a second global nav.

### Merge Rail app shell alignment (2026-06-14)

The protected IDE shell keeps the historical `--ide-*` token names for compatibility, but the
values no longer mirror VS Code Dark+. `frontend/src/ide/ide.css` now uses the same Merge Rail
graphite direction as auth and the CLI/VS Code clients: `#0b0d10` page, `#11151b` side/card,
`#171b22` raised/input, `#5b8def` accent, `#7ad1c8` success/rail. The login/auth surface in
`frontend/src/signal/signal.css` was tightened to the same surface ladder and smaller radii, and
the auth console mock now consumes `--signal-*` tokens instead of fixed dark literals.

Design constraints for future theme edits:

- Keep functionality and selectors stable; these are token/value changes, not a new shell.
- Do not reintroduce VS Code Dark+ blue (`#007acc`) as the primary brand accent.
- Keep work-surface cards at 8px radius or below unless a component is explicitly marketing/auth.
- Prefer solid surface steps, border opacity, and inner highlights over decorative gradients/blobs.

---

## Surface ladder

The "ladder" goes from the page background (the lowest, most-recessed surface) to the most-raised
card surface that can sit on top.

### Dark theme (`:root` in `tokens.css`)

| Token                  | Value                          | Used for                                                                                          |
| ---------------------- | ------------------------------ | ------------------------------------------------------------------------------------------------- |
| `--geist-bg`           | `var(--c-graphite-0)`          | Page background.                                                                                  |
| `--geist-bg-secondary` | `var(--c-graphite-2)`          | Secondary surface (toolbars, side panes).                                                         |
| `--bg-primary`         | `var(--geist-bg)`              | Alias — added to fix transparent-drawer bug in MoreDrawer, Drawer, RunStamp, EmptyProjectLanding. |
| `--surface-page`       | `var(--c-graphite-0)`          | UX-overhaul role-named alias for page bg.                                                         |
| `--surface-card`       | `var(--c-graphite-2)`          | Default card surface.                                                                             |
| `--surface-raised`     | `var(--c-graphite-3)`          | Raised state (hover, dropdowns).                                                                  |
| `--surface-sunken`     | `var(--c-graphite-1)`          | Sunken surface (well, inset).                                                                     |
| `--gray-100`           | `var(--c-graphite-2)`          | Tailwind `gray-100`.                                                                              |
| `--gray-200`           | `var(--c-graphite-3)`          | Tailwind `gray-200`.                                                                              |
| `--gray-300`           | `var(--c-graphite-4)`          | Tailwind `gray-300`.                                                                              |
| `--gray-400`           | `var(--c-graphite-5)`          | Tailwind `gray-400`.                                                                              |
| `--gray-500`           | `var(--c-graphite-6)`          | Tailwind `gray-500`.                                                                              |
| `--gray-600`           | `var(--c-graphite-6)`          | Collapsed onto `graphite-6` in Wave 6.                                                            |
| `--gray-700`           | `var(--c-ink-300)` (`#8A94A6`) | Tailwind `gray-700`.                                                                              |
| `--gray-800`           | `#aab2bf` (literal)            | Tailwind `gray-800`. The only literal hex outside the primitive layer.                            |
| `--gray-900`           | `var(--c-ink-200)` (`#C9D1DB`) | Tailwind `gray-900`.                                                                              |
| `--gray-1000`          | `var(--c-ink-100)` (`#ECEEF2`) | Brightest gray (close to white).                                                                  |

### Light theme (`[data-theme='light']` overrides)

| Token                  | Value                 |
| ---------------------- | --------------------- |
| `--geist-bg`           | `var(--c-paper-0)`    |
| `--geist-bg-secondary` | `var(--c-paper-1)`    |
| `--bg-primary`         | `var(--geist-bg)`     |
| `--surface-page`       | `var(--c-paper-0)`    |
| `--surface-card`       | `var(--c-paper-1)`    |
| `--surface-raised`     | `#ffffff`             |
| `--surface-sunken`     | `var(--c-paper-2)`    |
| `--gray-100`           | `var(--c-paper-1)`    |
| `--gray-200`           | `var(--c-paper-2)`    |
| `--gray-300`           | `var(--c-paper-3)`    |
| `--gray-400`           | `var(--c-paper-4)`    |
| `--gray-500`           | `#959eab`             |
| `--gray-600`           | `#6e7785`             |
| `--gray-700`           | `var(--c-graphite-6)` |
| `--gray-800`           | `var(--c-graphite-5)` |
| `--gray-900`           | `var(--c-graphite-3)` |
| `--gray-1000`          | `var(--c-graphite-0)` |

---

## Borders

`tokens.css` raises borders to the SC 1.4.11 (non-text contrast) AA floor.

| Token                     | Dark default                               | Light override      | Used for                                                         |
| ------------------------- | ------------------------------------------ | ------------------- | ---------------------------------------------------------------- |
| `--border-default`        | `var(--c-graphite-7)` (`#5E6771`, ≈3.05:1) | `#bfc6d0` (paper-4) | Default card / input border.                                     |
| `--border-hover`          | `var(--c-graphite-8)` (≈4.5:1)             | `#a0a8b5`           | Hover state on the same elements.                                |
| `--border-divider-subtle` | `var(--c-graphite-5)`                      | `#d8dce3` (paper-3) | Intentional non-AA hairlines (table rows). Documented exception. |
| `--hairline`              | `var(--c-graphite-5)`                      | `var(--c-paper-3)`  | UX-overhaul role-named border for sections.                      |

---

## Text

| Token              | Dark default                   | Light override        | Used for                      |
| ------------------ | ------------------------------ | --------------------- | ----------------------------- |
| `--text-primary`   | `var(--c-ink-100)` (`#ECEEF2`) | `var(--c-graphite-0)` | Body copy, headings.          |
| `--text-secondary` | `var(--c-ink-200)` (`#C9D1DB`) | `var(--c-graphite-5)` | Secondary copy.               |
| `--text-muted`     | `var(--c-ink-300)` (`#8A94A6`) | `#6e7785`             | Captions, hints, placeholder. |

---

## Accent — Signal palette

The accent set went through a round-3 contrast audit (the F-16 commit). Three brand-blue
slots cover the use cases that previously collapsed into one.

| Token                 | Dark default                                                                | Light override | Used for                                                                                 |
| --------------------- | --------------------------------------------------------------------------- | -------------- | ---------------------------------------------------------------------------------------- |
| `--accent-blue`       | `#5b8def`                                                                   | `#2563eb`      | Button surface — brightened for graphite-0 readability per 2026-05-06 website parity.    |
| `--accent-blue-hover` | `#7aa5f5`                                                                   | `#1d4ed8`      | Hover state on `--accent-blue` buttons.                                                  |
| `--accent-blue-text`  | `var(--c-signal-blue-on-dark)`                                              | `#2563eb`      | Brand-blue **text** on dark/light surfaces. AA-body alias (Tailwind `accent.blue-text`). |
| `--accent-harness`    | `var(--c-accent-purple)`                                                    | —              | Agent-orchestration tone — used for the "harness" surface.                               |
| `--accent-planner`    | `var(--c-info)` (info-cyan)                                                 | —              | Planner / info phase. Split from brand-blue in Wave-5 E.2 — was the same colour before.  |
| `--brand-grad`        | `linear-gradient(135deg, var(--accent-blue) 0%, var(--c-signal-teal) 100%)` | —              | Logo, primary CTA, scorecard highlight.                                                  |

---

## Status

Semantic status colours (the F-17 audit, 2026-05 polish).

| Token                | Dark default                  | Light override        | Used for                                                                                               |
| -------------------- | ----------------------------- | --------------------- | ------------------------------------------------------------------------------------------------------ |
| `--status-success`   | `var(--c-success)`            | —                     | COMPLETED, healthy, SHIPPABLE.                                                                         |
| `--status-executing` | `#5b89e0` (AA pass at 5.21:1) | —                     | EXECUTING phase. R11 + F-17 audit re-tuned this off pure brand-blue.                                   |
| `--status-thinking`  | `var(--c-accent-purple)`      | —                     | CLASSIFYING / PLANNING / classifier reasoning.                                                         |
| `--status-waiting`   | `var(--c-warning)`            | `#d97706`             | AWAITING_APPROVAL plan-gate.                                                                           |
| `--status-partial`   | `#f5a524`                     | —                     | PARTIAL terminal — softer amber, not red-adjacent.                                                     |
| `--status-error`     | `var(--c-error)` (`#EF4444`)  | `#dc2626`             | FAILED. V3 drift resolved.                                                                             |
| `--status-blocked`   | `var(--c-accent-pink)`        | —                     | BLOCKED.                                                                                               |
| `--status-queued`    | `#66707d`                     | —                     | QUEUED.                                                                                                |
| `--numeric-neutral`  | `var(--c-ink-200)`            | `var(--c-graphite-6)` | Money / tokens / stats. Used to be amber — moved to neutral to stop "everything looks like a warning." |
| `--status-building`  | `var(--c-warning)`            | —                     | Legacy alias for `--status-waiting`.                                                                   |

---

## Radius

| Token              | Value                                                    |
| ------------------ | -------------------------------------------------------- |
| `--radius-sm`      | `4px`                                                    |
| `--radius-md`      | `6px`                                                    |
| `--radius-lg`      | `8px`                                                    |
| `--radius-xl`      | `12px`                                                   |
| `--radius-2xl`     | `16px`                                                   |
| `--radius-full`    | `9999px`                                                 |
| `--radius-control` | `8px` — UX-overhaul role-named (controls/inputs/buttons) |
| `--radius-card`    | `14px` — cards                                           |
| `--radius-pill`    | `9999px` — pills, chips                                  |

---

## Spacing scale

`rem`-based scale, declared in `tokens.css`:

| Token        | Value     |
| ------------ | --------- |
| `--space-0`  | `0`       |
| `--space-1`  | `0.25rem` |
| `--space-2`  | `0.5rem`  |
| `--space-3`  | `0.75rem` |
| `--space-4`  | `1rem`    |
| `--space-5`  | `1.25rem` |
| `--space-6`  | `1.5rem`  |
| `--space-8`  | `2rem`    |
| `--space-10` | `2.5rem`  |
| `--space-12` | `3rem`    |
| `--space-16` | `4rem`    |

Layout gutters:

| Token                   | Value            | Used for                               |
| ----------------------- | ---------------- | -------------------------------------- |
| `--page-gutter-mobile`  | `var(--space-4)` | `.page-gutter` mobile (default).       |
| `--page-gutter-tablet`  | `var(--space-6)` | `.page-gutter` at `min-width: 640px`.  |
| `--page-gutter-desktop` | `var(--space-8)` | `.page-gutter` at `min-width: 1024px`. |
| `--page-stack`          | `var(--space-6)` | Vertical rhythm between page sections. |

Tailwind also exposes a discrete spacing scale (in `tailwind.config.ts`):

| Tailwind class | px equivalent |
| -------------- | ------------- |
| `sp-1`         | `4px`         |
| `sp-2`         | `8px`         |
| `sp-3`         | `12px`        |
| `sp-4`         | `16px`        |
| `sp-6`         | `24px`        |
| `sp-8`         | `32px`        |
| `sp-12`        | `48px`        |
| `sp-16`        | `64px`        |
| `sp-24`        | `96px`        |
| `sp-32`        | `128px`       |

---

## Elevation

| Token           | Dark default                       | Light override                         |
| --------------- | ---------------------------------- | -------------------------------------- |
| `--elevation-1` | `0 1px 2px 0 rgba(0, 0, 0, 0.4)`   | `0 1px 2px 0 rgba(15, 23, 42, 0.05)`   |
| `--elevation-2` | `0 4px 12px 0 rgba(0, 0, 0, 0.5)`  | `0 4px 12px 0 rgba(15, 23, 42, 0.08)`  |
| `--elevation-3` | `0 12px 32px 0 rgba(0, 0, 0, 0.6)` | `0 12px 32px 0 rgba(15, 23, 42, 0.12)` |

The 2026-05-14 UX overhaul adds "inner light" shadow tokens that combine a top inset highlight with
a soft outer shadow (Aqua / liquid-glass effect):

| Token                 | Dark default                                                              | Light override                                                              |
| --------------------- | ------------------------------------------------------------------------- | --------------------------------------------------------------------------- |
| `--shadow-card-rest`  | `inset 0 1px 0 rgba(255, 255, 255, 0.04), 0 1px 2px rgba(0, 0, 0, 0.4)`   | `inset 0 1px 0 rgba(255, 255, 255, 0.7), 0 1px 2px rgba(15, 23, 42, 0.04)`  |
| `--shadow-card-hover` | `inset 0 1px 0 rgba(255, 255, 255, 0.06), 0 8px 20px rgba(0, 0, 0, 0.55)` | `inset 0 1px 0 rgba(255, 255, 255, 0.9), 0 8px 20px rgba(15, 23, 42, 0.08)` |
| `--shadow-sheet`      | `0 -12px 32px rgba(0, 0, 0, 0.6)`                                         | `0 -12px 32px rgba(15, 23, 42, 0.1)`                                        |
| `--shadow-overlay`    | `0 24px 64px rgba(0, 0, 0, 0.7)`                                          | `0 24px 64px rgba(15, 23, 42, 0.14)`                                        |

---

## Motion

`tokens.css` motion vocabulary (Apple HIG-style refinement layer, 2026-05-14):

| Token             | Value                                                                                  |
| ----------------- | -------------------------------------------------------------------------------------- |
| `--ease-out`      | `cubic-bezier(0.16, 1, 0.3, 1)`                                                        |
| `--ease-spring`   | `cubic-bezier(0.34, 1.56, 0.64, 1)`                                                    |
| `--duration-fast` | `120ms`                                                                                |
| `--duration-base` | `200ms`                                                                                |
| `--duration-slow` | `320ms`                                                                                |
| `--motion-press`  | `transform 80ms cubic-bezier(0.4, 0, 0.6, 1)`                                          |
| `--motion-tap`    | `opacity 120ms ease-out`                                                               |
| `--motion-reveal` | `opacity 200ms cubic-bezier(0.2, 0, 0, 1), transform 200ms cubic-bezier(0.2, 0, 0, 1)` |
| `--motion-pulse`  | `background-color 320ms ease-out`                                                      |

Reduced motion: `index.css` ships a `@media (prefers-reduced-motion: reduce)` block at the end of
the file that zeroes every animation / transition / transform `!important`. Anything you build
should still feel right with motion off.

Animations declared in `tailwind.config.ts` (each maps to a `@keyframes` block in the config):

| Class name       | Duration                  |
| ---------------- | ------------------------- |
| `slide-in-right` | `0.3s ease-out`           |
| `fade-in-up`     | `0.4s ease-out`           |
| `shimmer`        | `2s linear infinite`      |
| `log-line`       | `0.15s ease-out`          |
| `status-pulse`   | `2s ease-in-out infinite` |

---

## Focus ring

```css
--focus-ring: 0 0 0 2px var(--geist-bg), 0 0 0 4px var(--accent-blue);
```

Applied globally via:

```css
:where(
  button,
  a,
  input,
  textarea,
  select,
  [role="button"],
  [role="tab"]
):focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
  border-radius: inherit;
}
```

Both themes use the same shape; only `--geist-bg` and `--accent-blue` swap.

---

## Hit targets

Apple HIG-aligned tap-target floors:

| Token               | Value  | Used for                              |
| ------------------- | ------ | ------------------------------------- |
| `--hit-min-mobile`  | `44px` | Mobile button / link / tab floor.     |
| `--hit-min-desktop` | `32px` | Desktop hover-precision floor.        |
| `--hit-comfort`     | `48px` | Primary-CTA comfort target on mobile. |

`index.css` enforces the mobile floor globally under `@media (max-width: 1023px)`:

```css
button,
a[role="button"],
[role="tab"] {
  min-height: 44px;
}
```

There's also a coarser `@media (pointer: coarse)` block that bumps inline links, checkbox/radio
inputs, and selects to `min-height: 40px` minimum.

---

## Breakpoints

Declared in `tokens.css` and consumed by the `useBreakpoint()` hook:

| Token          | Value    |
| -------------- | -------- |
| `--bp-phone`   | `480px`  |
| `--bp-tablet`  | `768px`  |
| `--bp-desktop` | `1024px` |
| `--bp-wide`    | `1280px` |
| `--bp-xl`      | `1536px` |

The mobile UI cuts over to desktop chrome at `1024px` (the persistent sidebar in `Shell.tsx`
flips on at `min-width: 1024px`).

---

## Font scale

`rem`-based:

| Token         | Value       |
| ------------- | ----------- |
| `--text-xs`   | `0.75rem`   |
| `--text-sm`   | `0.8125rem` |
| `--text-base` | `0.9375rem` |
| `--text-lg`   | `1.0625rem` |
| `--text-xl`   | `1.25rem`   |
| `--text-2xl`  | `1.5rem`    |
| `--text-3xl`  | `1.875rem`  |
| `--text-4xl`  | `2.5rem`    |
| `--text-5xl`  | `3.25rem`   |

Mobile typography lift (the Track A.1 hammer in `index.css`) overrides any arbitrary
`text-[10/10.5/11/11.5/12px]` Tailwind class to a `13px` floor on `@media (max-width: 767px)`,
because 562 individual call sites were using arbitrary pixel sizes below the legibility floor.
Anything below `12px` is forbidden on mobile.

---

## Fonts

```css
font-family: "JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, monospace;
```

This applies to `body`, every form control (via the `input, textarea, select, button, optgroup`
inheritance fix), and Tailwind's `sans` + `mono` font-family classes (they're aliased to the same
chain in `tailwind.config.ts`). Geist Sans / Geist Mono were retired on 2026-05-11 — JetBrains
Mono is the only typeface across the app.

Loaded from Google Fonts in `signal.css` with weight ranges:

- `Space Grotesk` `300..700` — used for `.signal-app` body copy (chrome-only)
- `JetBrains Mono` `400..600` — used for everything else (including code, stats, monospaced UI)

Signal additionally uses `font-family: 'Space Grotesk', ...` for its `body` style. Component
classes that want monospace explicitly opt in (e.g. `.signal-kv-list .v .mono` switches back to
JetBrains).

The frontend ships JetBrains Mono with a **variable** font axis, so all weights are served by a
single file.

---

## Signal-scoped tokens (`.signal-app`)

These are scoped under the Signal shell — they never leak into other chromes. Light values
default; dark values live under `.signal-app[data-signal-theme='dark']`.

### Surface ladder (light default)

| Token                     | Value       | Used for                                                     |
| ------------------------- | ----------- | ------------------------------------------------------------ |
| `--signal-bg`             | `#fbfaf6`   | Page background.                                             |
| `--signal-card-bg`        | `#ffffff`   | Default card surface.                                        |
| `--signal-card-elev`      | `#f4f2eb`   | Raised state (chip backgrounds, secondary buttons).          |
| `--signal-bg-translucent` | `rgba(...)` | Backdrop blurs for sticky bars.                              |
| `--signal-phone-frame`    | `#0e0f12`   | Dark phone-frame fallback (legacy, mostly retired in shell). |

### Surface ladder (dark override)

| Token                      | Value                       |
| -------------------------- | --------------------------- |
| `--signal-bg`              | `#0f1014`                   |
| `--signal-card-bg`         | `#1b1c22`                   |
| `--signal-card-elev`       | (shifted darker)            |
| `--signal-hero-bg-1` / `2` | `#1f2028` / `#15161b`       |
| `--signal-hairline`        | `rgba(255, 255, 255, 0.08)` |
| `--signal-hairline-strong` | `rgba(255, 255, 255, 0.16)` |

### Text

| Token                | Light default | Dark override |
| -------------------- | ------------- | ------------- |
| `--signal-ink`       | `#0e0f12`     | `#f3f1ed`     |
| `--signal-ink-soft`  | `#2d2e33`     | (inverted)    |
| `--signal-text-dim`  | `#6b6960`     | (inverted)    |
| `--signal-text-mute` | `#9a968a`     | (inverted)    |

### Hairlines

| Token                      | Light default | Dark override               |
| -------------------------- | ------------- | --------------------------- |
| `--signal-hairline`        | `#e6e2d8`     | `rgba(255, 255, 255, 0.08)` |
| `--signal-hairline-strong` | `#bfb9a8`     | `rgba(255, 255, 255, 0.16)` |

### Accents

| Token                    | Value (light) | Used for                                                  |
| ------------------------ | ------------- | --------------------------------------------------------- |
| `--signal-y`             | `#f4d35e`     | Brand yellow — hero accent, "Live now" eyebrow.           |
| `--signal-coral`         | `#ff6b6b`     | Live dot, errors, FAILED state, "danger" buttons.         |
| `--signal-mint`          | `#7ce3b4`     | COMPLETED / SHIPPABLE, "mint" buttons.                    |
| `--signal-lavender`      | `#c4b8e0`     | Default pill-dot, tool stream cards.                      |
| `--signal-ocean`         | `#5c9ddb`     | Secondary blue (less brand-loaded than yellow).           |
| `--signal-y-soft`        | derived       | Soft yellow card background (10–14% opacity).             |
| `--signal-y-text`        | derived       | AA-body yellow text on the yellow-soft surface.           |
| `--signal-coral-soft`    | derived       | Soft coral background.                                    |
| `--signal-coral-text`    | derived       | AA-body coral text.                                       |
| `--signal-mint-soft`     | derived       | Soft mint background.                                     |
| `--signal-mint-text`     | derived       | AA-body mint text.                                        |
| `--signal-mint-on-light` | derived       | Mint that reads on the light page background.             |
| `--signal-lavender-soft` | derived       | Soft lavender — nav-badge BETA chip, default stream card. |

### Radius

| Token           | Value  | Used for                              |
| --------------- | ------ | ------------------------------------- |
| `--signal-r-sm` | `10px` | Small pills, status chips.            |
| `--signal-r-md` | `16px` | Default card radius.                  |
| `--signal-r-lg` | `22px` | Hero card, splash, plans card.        |
| `--signal-r-xl` | `28px` | Composer card, plan-gate, score card. |

### Shadow

| Token                    | Used for                        |
| ------------------------ | ------------------------------- |
| `--signal-shadow`        | Default card shadow.            |
| `--signal-shadow-strong` | Modals, toasts, floating pills. |

---

## Tailwind theme extensions

`frontend/tailwind.config.ts` is var-driven — the palette can be themed at runtime through
`tokens.css`. The relevant `theme.extend` entries:

```ts
colors: {
  geist: {
    bg: 'var(--geist-bg)',
    'bg-secondary': 'var(--geist-bg-secondary)',
  },
  gray: {
    100: 'var(--gray-100)',
    // ...10-stop ramp through 1000
  },
  accent: {
    blue: 'var(--accent-blue)',
    'blue-hover': 'var(--accent-blue-hover)',
    'blue-text': 'var(--accent-blue-text)',
    red: 'var(--status-error)',
    amber: 'var(--status-waiting)',
    green: 'var(--status-success)',
    harness: 'var(--accent-harness)',
    planner: 'var(--accent-planner)',
  },
  status: {
    success: 'var(--status-success)',
    executing: 'var(--status-executing)',
    thinking: 'var(--status-thinking)',
    waiting: 'var(--status-waiting)',
    partial: 'var(--status-partial)',
    error: 'var(--status-error)',
    blocked: 'var(--status-blocked)',
    queued: 'var(--status-queued)',
    building: 'var(--status-building)',  // legacy alias
  },
  border: {
    default: 'var(--border-default)',
    hover: 'var(--border-hover)',
  },
},
textColor: {
  primary: 'var(--text-primary)',
  secondary: 'var(--text-secondary)',
  muted: 'var(--text-muted)',
},
height: { dvh: '100dvh' },
minHeight: { dvh: '100dvh' },
maxHeight: { dvh: '100dvh' },
fontFamily: {
  sans: ['JetBrains Mono', 'ui-monospace', 'SFMono-Regular', 'Menlo', 'monospace'],
  mono: ['JetBrains Mono', 'ui-monospace', 'SFMono-Regular', 'Menlo', 'monospace'],
},
borderRadius: {
  sm: 'var(--radius-sm)',
  md: 'var(--radius-md)',
  lg: 'var(--radius-lg)',
},
```

Important: `accent.green`, `accent.amber`, and `accent.red` are all CSS-var aliases to the
matching `--status-*` token (`success` / `waiting` / `error`). They exist so legacy class names
like `bg-accent-green` keep working without splitting the palette — a `2.6.0`-era cleanup
documented in the inline comment.

---

## Score palette (4 bands)

The verified score (`0..100`) is mapped to one of four bands. The mapping lives in
`frontend/src/signal/lib/runUtils.ts`:

```ts
export function scoreKind(
  score?: number | null,
): "good" | "warn" | "fail" | "default" {
  if (score == null) return "default";
  if (score >= 85) return "good";
  if (score >= 70) return "warn";
  if (score > 0) return "fail";
  return "default";
}
```

| Band      | Threshold              | Token used          | Verdict label (UI)                    |
| --------- | ---------------------- | ------------------- | ------------------------------------- |
| `good`    | `score >= 85`          | `--signal-mint`     | "Shippable"                           |
| `warn`    | `70..84`               | `--signal-y`        | "Needs review"                        |
| `fail`    | `0 < score < 70`       | `--signal-coral`    | "Failed"                              |
| `default` | `score == null` or `0` | `--signal-text-dim` | (no verdict — score not yet computed) |

The trailing-cell colour mapping in `Primitives.tsx` mirrors this for inline row stats:

```ts
type RowTrailing = "good" | "warn" | "fail" | "default";
```

Live status pills (`runUtils.statusToDot`) also map to the same colour set, plus
`'live'` (animated coral) and `'lavender'` (default fallback) for an extra dimension:

```ts
function statusToDot(
  s?: string,
): "live" | "mint" | "coral" | "signal" | "lavender" {
  // CLASSIFYING/PLANNING/EXECUTING/AWAITING_APPROVAL/RUNNING/PROVIDER_SWITCHING/PAUSED → 'live'
  // COMPLETED → 'mint'
  // FAILED/CANCELLED → 'coral'
  // PARTIAL → 'signal' (brand yellow)
  // else → 'lavender'
}
```

---

## File-icon palette

`frontend/src/signal/components/FileIcon.tsx` ships VS Code / IntelliJ-style inline SVG icons.
Each icon is a small hand-rolled SVG (avoids a third-party dependency). The dispatcher walks
extension/name rules to pick:

| Icon             | Match                                                                                                                                 | Brand colour                   |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------ |
| `TestIcon`       | `*.test.{ts,tsx,js,jsx}`, `*.spec.{ts,tsx,js,jsx}`, `test.java`, `tests.java`                                                         | `#10B981` (mint)               |
| `DockerIcon`     | `Dockerfile`, `*.dockerfile`                                                                                                          | `#2496ED`                      |
| `ConfigIcon`     | `.gitignore`, `.gitattributes`, `.dockerignore`, `.editorconfig`, `.env(.*)`, `.npmrc`, `.prettierrc(.json)`, `.eslintrc.(cjs\|json)` | `#888`                         |
| `MarkdownIcon`   | `*.md`, `readme`, `license`                                                                                                           | `#519ABA`                      |
| `JavaIcon`       | `.java`                                                                                                                               | `#ED8B00`                      |
| `KotlinIcon`     | `.kt`, `.kts`                                                                                                                         | gradient `#7F52FF → #E44857`   |
| `PythonIcon`     | `.py`, `.pyi`                                                                                                                         | `#3776AB` + `#FFD43B`          |
| `TypeScriptIcon` | `.ts`, `.mts`, `.cts`                                                                                                                 | `#3178C6`                      |
| `ReactIcon`      | `.tsx`, `.jsx` (matched before plain TS/JS)                                                                                           | `#61DAFB`                      |
| `JavaScriptIcon` | `.js`, `.mjs`, `.cjs`                                                                                                                 | `#F7DF1E` on `#000`            |
| `GoIcon`         | `.go`                                                                                                                                 | `#00ADD8`                      |
| `RustIcon`       | `.rs`                                                                                                                                 | `#000` + `#DEA584`             |
| `RubyIcon`       | `.rb`                                                                                                                                 | `#CC342D`                      |
| `HtmlIcon`       | `.html`, `.htm`                                                                                                                       | `#E34F26`                      |
| `CssIcon`        | `.css`, `.scss`, `.less`                                                                                                              | `#1572B6`                      |
| `JsonIcon`       | `.json`                                                                                                                               | `#FAB005`                      |
| `YamlIcon`       | `.yml`, `.yaml`                                                                                                                       | `#CB171E`                      |
| `XmlIcon`        | `.xml`, `pom.xml`                                                                                                                     | `#005A9E`                      |
| `TomlIcon`       | `.toml`                                                                                                                               | `#9C4221`                      |
| `ShellIcon`      | `.sh`, `.bash`, `.zsh`                                                                                                                | `#4A4A4A` bg + `#4EAB4E` glyph |
| `SqlIcon`        | `.sql`                                                                                                                                | `#336791`                      |
| `ImageIcon`      | `.png`, `.jpg`, `.jpeg`, `.gif`, `.svg`, `.webp`, `.ico`                                                                              | `#A074C4`                      |
| `FolderIcon`     | (any folder; `open` shifts the path)                                                                                                  | `#5B8DEF` (signal blue)        |
| `DefaultIcon`    | fallback                                                                                                                              | `#888`                         |

The dispatch order matters: test files match before language, dotfiles before language,
`Dockerfile` before any general extension. New rules go in the same `FileIcon` dispatcher.

---

## Code viewer palette

`frontend/src/signal/components/CodeView.tsx` is a regex-tokenised code viewer (replaces Monaco
to save ~600KB of bundle). Syntax colours:

| Concept  | Colour    | Reasoning                                        |
| -------- | --------- | ------------------------------------------------ |
| Comments | `#7d8590` | Slate — recessed, low-attention.                 |
| Strings  | `#a5d6a3` | Green — easy to skim.                            |
| Numbers  | `#e5b977` | Amber — distinct from strings without screaming. |
| Keywords | `#5b8def` | Signal blue — matches `--accent-blue`.           |

Diff mode adds line-background tints (10–12% opacity):

| Marker         | Background                               |
| -------------- | ---------------------------------------- |
| `+` adds       | `rgba(124, 227, 180, 0.08)` (mint)       |
| `-` removes    | `rgba(255, 107, 107, 0.08)` (coral)      |
| `@@` hunk      | `rgba(91, 141, 239, 0.08)` (signal blue) |
| `+++/---` file | `rgba(244, 211, 94, 0.06)` (amber)       |

Languages with explicit keyword sets: `ts`, `tsx`, `js`, `jsx`, `java`, `python`, `json`, `yaml`,
`sql`, `bash`. Unknown extensions fall through to plain text with no highlighting.

---

## Cost progress bar

`frontend/src/signal/components/CostProgressBar.tsx` (and `tailwind.config.ts`'s `status` palette)
defines the three bands:

| Band   | Threshold     | Bar colour            |
| ------ | ------------- | --------------------- |
| `good` | `<80%` of cap | `var(--signal-mint)`  |
| `warn` | `80%..<100%`  | `var(--signal-y)`     |
| `fail` | `>=100%`      | `var(--signal-coral)` |

ARIA: `role='progressbar'`, `aria-valuenow`, `aria-valuemin=0`, `aria-valuemax=100`,
`aria-label` set to the prop. Label font is JetBrains Mono `11px`. Bar height `6px`, radius `3px`.

---

## WCAG contrast helpers

`frontend/src/design-system/contrast.ts` ships a small utility set for verifying any new token
combination passes WCAG AA. The library exposes:

- `relativeLuminance(hex)` — computes the WCAG relative luminance of a hex colour.
- `contrastRatio(fg, bg)` — returns the contrast ratio (1.0–21.0) between foreground and
  background, both hex.
- `meetsAA(fg, bg)` — boolean shortcut for the AA body-text floor (`>= 4.5:1`).
- `meetsAALarge(fg, bg)` — `>= 3:1` for "large text" (18pt+ or 14pt+ bold).

The accent + status tokens were tuned through round 3 of this audit (the F-15 and F-16 commits in
`tokens.css`) — borders are at `>= 3.05:1` (SC 1.4.11 non-text floor), accents and status text are
at `>= 4.5:1` (SC 1.4.3 body-text floor). New tokens must clear these floors before they land.

---

## Where tokens live (file map)

```
frontend/src/
├── design-system/
│   ├── primitives.css   #  --c-graphite-N, --c-paper-N, --c-ink-N, --c-signal-*, semantic
│   │                    #  primitives. Loaded first; never reference --c-* outside this file
│   │                    #  if a semantic alias exists.
│   └── contrast.ts      #  WCAG luminance + ratio helpers.
├── tokens.css           #  Graphite + Signal semantic tokens (--c-* → --geist-*, --status-*, etc).
│                        #  Dark default + [data-theme='light'] override.
├── index.css            #  Imports primitives → tokens → Tailwind v4 → tailwind.config.ts compat
│                        #  layer, then global utilities, motion vocabulary, page gutters.
├── signal/
│   └── signal.css       #  Signal chrome — --signal-* tokens, every component class, scoped
│                        #  under .signal-app so it can't leak into legacy shells.
└── tailwind.config.ts   #  theme.extend points every colour/spacing/font at the CSS vars above.
```

Loading order matters: `index.css` imports `primitives.css` first, then `tokens.css`, then
`tailwindcss` (Tailwind 4 single-directive form), then re-applies `tailwind.config.ts` via the v4
compat `@config` directive. `signal.css` is imported separately by `main.tsx` so it's available on
public routes too.
