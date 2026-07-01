# Restoring the Geist-clone (Vercel-clone) Theme

> **Era:** `d3422ac2` (Apr 1, 2026) → `8c6a5dcc` (Apr 21, 2026) — the era the codebase's own commit history calls "the Vercel/Geist clone aesthetic" (see the `tokens.css` header comment added when this era was retired: *"Design goal: decouple ExecuteSpec from the Vercel/Geist clone aesthetic"*).
> **Visual identity:** True black `#000000` background, Vercel's exact CTA blue `#0070F3`, flat neutral gray scale (no violet, no glassmorphism, no blur), sharp/flat cards, Geist Sans/Mono fonts.
> **Scope:** CSS/font changes only. No backend, no routing, no React component logic changes required.
>
> **Correction note:** an earlier version of this document mislabeled the Mar 1 root-commit era (violet `#8B5CF6` + `backdrop-filter` glassmorphism) as "Geist-clone." That era is a different, earlier aesthetic — see [`theme-restore-mission-control.md`](theme-restore-mission-control.md) (Variant A) for it instead. This document now covers the genuinely distinct black+Vercel-blue era that followed it.

---

## Why this is a separate era from "Mission Control"

| | Mission Control (Mar 1–28) | **Geist-clone (this doc, Apr 1–21)** | Graphite+Signal (current, Apr 21→) |
|---|---|---|---|
| Page background | `#0A0A0F` (near-black) | **`#000000` (true black)** | `#10131a` (warmed graphite) |
| Brand accent | Violet `#8B5CF6` | **Vercel blue `#0070F3`** | Signal Blue `#3b6fcc` (`#5b8def` on dark) |
| Card treatment | Glassmorphism (blur) or flat (variant B) | **Flat, sharp border, no blur** | Flat, subtle border |
| Status colors | success=emerald, uses violet for "thinking" | **success=blue `#0070F3`, no violet/emerald at all** | success=green, distinct hue per status group |
| Font | Geist Sans/Mono (CDN) or DM Sans+JetBrains Mono | **Geist Sans/Mono (local `@font-face`, not CDN)** | JetBrains Mono (sole face, Google Fonts) |

The Geist-clone era is the only one of the three with **zero violet and zero emerald** anywhere in the palette — everything is grayscale + one blue. If you're trying to reproduce "black background, Vercel-blue buttons, no purple anywhere," this is the doc you want. If you want "black background with a violet/purple accent," use the Mission Control doc instead.

---

## Overview of changes

| File | Change type | Summary |
|------|-------------|---------|
| `frontend/src/design-system/primitives.css` | Edit | Surface ladder → true black; brand family → Vercel blue (no violet) |
| `frontend/src/tokens.css` | Edit | Gray scale + accent + status literals → Geist-clone values |
| `frontend/src/index.css` | Edit | Add local Geist `@font-face` + flat (non-blurred) card utility |
| `frontend/src/ide/ide.css` | Edit | 6 IDE token overrides + font family (flat, no blur) |
| `frontend/src/console/console.css` | Edit | Dark-mode token overrides + font family |
| `frontend/index.html` | Edit | Remove Google Fonts links (fonts load via local `@font-face`, not CDN) |

---

## Step 1 — `frontend/src/design-system/primitives.css`

This file didn't exist yet during the Apr 2026 era (it was introduced Apr 28 as an abstraction layer during the token-aliasing refactor). To reproduce the look on the *current* architecture, retarget the primitives to the Geist-clone hexes — no violet anywhere.

### Surface ladder (graphite → true black + flat gray scale)

```diff
- --c-graphite-0: #10131a;  /* page bg */
+ --c-graphite-0: #000000;  /* page bg — true black, not near-black */

- --c-graphite-1: #161a23;  /* lowest surface */
+ --c-graphite-1: #0a0a0a;

- --c-graphite-2: #1a1f29;  /* card */
+ --c-graphite-2: #111111;

- --c-graphite-3: #232936;  /* card-elevated */
+ --c-graphite-3: #1a1a1a;

- --c-graphite-4: #2d3441;  /* surface border (subtle) */
+ --c-graphite-4: #252525;

- --c-graphite-5: #3d4553;  /* surface divider */
+ --c-graphite-5: #333333;

- --c-graphite-6: #545c6a;  /* disabled fg */
+ --c-graphite-6: #555555;

- --c-graphite-7: #6a737e;  /* border-default */
+ --c-graphite-7: #666666;

- --c-graphite-8: #7b8593;  /* border-hover */
+ --c-graphite-8: #888888;
```

### Brand color family (Signal Blue → Vercel blue; NO violet)

```diff
- --c-signal-blue: #3b6fcc;
+ --c-signal-blue: #0070f3;         /* exact Vercel CTA blue */

- --c-signal-blue-hover: #2b5bb3;
+ --c-signal-blue-hover: #0060d0;   /* darker on hover, matches the era's convention */

- --c-signal-blue-on-dark: #7aa5f5;
+ --c-signal-blue-on-dark: #3291ff; /* lighter blue for text/links on black */

- --c-signal-teal: #7ad1c8;
+ --c-signal-teal: #0070f3;         /* era had no teal — brand gradient collapses to solid blue */
```

> `--c-accent-purple` / `--c-accent-pink` — **do not use these** for any brand or status role in this era. The era's "thinking" and "blocked" states are grayscale (see status table below), not purple/pink.

### Text ink scale (warm ink → true grayscale)

```diff
- --c-ink-100: #eceef2;  /* primary */
+ --c-ink-100: #ededed;

- --c-ink-200: #c9d1db;  /* secondary */
+ --c-ink-200: #a1a1a1;

- --c-ink-300: #8a94a6;  /* muted */
+ --c-ink-300: #666666;

- --c-ink-400: #5e6771;  /* subtle */
+ --c-ink-400: #555555;
```

> `--c-success`, `--c-warning`, `--c-error`, `--c-info` — see the status-token table below; several of these need to change too, since this era's status semantics were genuinely different (success used blue, not green).

---

## Step 2 — `frontend/src/tokens.css`

### In `:root { }` (dark default)

```diff
- --accent-blue: #5b8def;
+ --accent-blue: #0070f3;

- --accent-blue-hover: #7aa5f5;
+ --accent-blue-hover: #0060d0;

  --accent-blue-text: var(--c-signal-blue-on-dark);  /* resolves to #3291ff now */
```

### Status tokens — this era's semantics genuinely differ, not just a recolor

The Apr 1 commit used **success = blue** (matching the brand color, Vercel-style — a "deploy succeeded" blue banner), not green:

```diff
- --status-success: #10b981;      /* green */
+ --status-success: #0070f3;      /* blue — matches brand in this era */

- --status-building / --status-executing: (varies)
+ --status-building: #f5a623;     /* amber — unchanged role */

- --status-error: #ef4444;
+ --status-error: #e5484d;        /* slightly more muted red than current */

- --status-queued: #666666;       /* unchanged — already gray in both eras */
```

> A week later (`ab49db4d`, Apr 7) this same era refined status colors to a distinct-hue-per-group scheme closer to today's (blue=executing, purple=thinking, amber=waiting, orange=partial, red=error, pink=blocked, gray=queued) — but even that later refinement kept the *background* black and the *brand* blue. If you want the exact Apr 7–21 look rather than the Apr 1–7 look, keep `--status-success: #10b981` (green) and use the distinct-hue set instead of blue-for-everything. Either sub-variant is a legitimate "Geist-clone" snapshot; the true-black-background + Vercel-blue-brand pairing is the constant across both.

### Radius

```diff
- (graphite era uses --radius-* aliasing --mc-radius: 2px or component defaults)
+ --radius-sm: 2px;
+ --radius-md: 4px;
+ --radius-lg: 6px;
```

---

## Step 3 — `frontend/src/index.css`

### 3a. Add Geist font faces (local, not CDN)

This era loaded fonts from a local `node_modules/geist` package via `@font-face`, not jsdelivr CDN (that CDN approach belongs to the earlier Mission Control glassmorphism era — see the other doc). Add near the top of `tokens.css` or `index.css`:

```css
@font-face {
  font-family: 'Geist Sans';
  src: url('/node_modules/geist/dist/fonts/geist-sans/Geist-Variable.woff2') format('woff2');
  font-weight: 100 900;
  font-style: normal;
  font-display: swap;
}

@font-face {
  font-family: 'Geist Mono';
  src: url('/node_modules/geist/dist/fonts/geist-mono/GeistMono-Variable.woff2') format('woff2');
  font-weight: 100 900;
  font-style: normal;
  font-display: swap;
}
```

> Requires the `geist` npm package installed under `frontend/node_modules/geist`. If you'd rather not add a build dependency, substitute the jsdelivr CDN links from the Mission Control doc's Step 3a — the font family names are the same, only the delivery mechanism differs. The important visual distinction from Mission Control isn't the font-loading method, it's the color palette (no violet, no blur, true black).

### 3b. Flat card + terminal utilities (NOT glassmorphism)

```diff
  .glass-panel {
-   backdrop-filter: blur(12px);
-   -webkit-backdrop-filter: blur(12px);
-   background: rgba(255, 255, 255, 0.03);
-   border-color: rgba(255, 255, 255, 0.06);
-   box-shadow: inset 0 1px 0 0 rgba(255, 255, 255, 0.03);
+   @apply border rounded-md;
+   background: #111111;         /* var(--gray-100) equivalent */
+   border-color: #252525;       /* var(--border-default) equivalent */
  }

  .terminal-log {
    @apply font-mono text-sm leading-relaxed;
-   background: #0d0d14;
+   background: #000000;         /* var(--geist-bg) */
  }

  .gradient-mesh {
-   background: radial-gradient(...), ..., #0a0a0f;
+   background: #000000;         /* flat black — no gradient mesh in this era */
  }

  .shimmer {
    background: linear-gradient(
      90deg,
-     rgba(255, 255, 255, 0.03) 0%, rgba(255, 255, 255, 0.08) 50%, rgba(255, 255, 255, 0.03) 100%
+     #1a1a1a 0%, #252525 50%, #1a1a1a 100%
    );
  }
```

> No `.glass-panel` blur, no gradient mesh — this is the one era of the three with genuinely flat, sharp UI chrome. If your build still shows blur/gradient after this step, you're looking at leftover Mission Control CSS.

### 3c. Body font + background

```diff
  body {
-   background: var(--background);
-   color: var(--foreground);
+   background: var(--geist-bg);      /* resolves to #000000 */
+   color: var(--text-primary);       /* resolves to #ededed */
    font-family: 'Geist Sans', ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  }
```

---

## Step 4 — `frontend/src/ide/ide.css`

```diff
  .ide-root {
-   --ide-bg-editor: #0b0d10;
+   --ide-bg-editor: #000000;

-   --ide-bg-sidebar: #11151b;
+   --ide-bg-sidebar: #0a0a0a;

-   --ide-bg-activitybar: #171b22;
+   --ide-bg-activitybar: #000000;   /* collapses into page bg, no separate tint */

-   --ide-accent: #5b8def;
+   --ide-accent: #0070f3;           /* Vercel blue */

-   --ide-green: #7ad1c8;
+   --ide-green: #0070f3;            /* success reuses brand blue in this era, not green/teal */

-   --ide-blue: #7aa5f5;
+   --ide-blue: #3291ff;             /* lighter blue for on-dark text/links */

    /* Change the font family */
-   font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif;
+   font-family: 'Geist Sans', -apple-system, BlinkMacSystemFont, system-ui, sans-serif;
  }
```

```css
.ide-root code,
.ide-root pre,
.ide-root .font-mono {
  font-family: 'Geist Mono', ui-monospace, 'SFMono-Regular', monospace;
}
```

> If you'd rather keep green as a distinct success color (the Apr 7–21 refined-status sub-variant), leave `--ide-green` at a green/teal value and only change `--ide-accent` + `--ide-bg-*`. Both are legitimate Geist-clone snapshots — see the status-token note in Step 2.

The `[data-theme='light'] .ide-root` block — leave unchanged (this era was dark-only in practice; the light block is a safety net).

---

## Step 5 — `frontend/src/console/console.css`

### In `.con-root { }` — add font change

```diff
  .con-root {
    /* existing token values stay ... */
+   font-family: 'Geist Sans', Inter, -apple-system, BlinkMacSystemFont, system-ui, sans-serif;
  }
```

### In `[data-theme='dark'] .con-root { }` — update backgrounds and accent

```diff
  [data-theme='dark'] .con-root {
-   --ide-bg-editor: #0b0d10;
+   --ide-bg-editor: #000000;

-   --ide-bg-sidebar: #11151b;
+   --ide-bg-sidebar: #0a0a0a;

-   --ide-bg-activitybar: #11151b;
+   --ide-bg-activitybar: #000000;

-   --ide-accent: #4f7fe7;
+   --ide-accent: #0070f3;

-   --ide-green: #7ad1c8;
+   --ide-green: #0070f3;

-   --ide-blue: #9dbbff;
+   --ide-blue: #3291ff;
  }
```

---

## Step 6 — `frontend/index.html`

This era loaded no Google Fonts at all — Geist fonts came from the local `@font-face` declarations in Step 3a. Remove any Google Fonts `<link>` tags entirely:

```diff
- <link rel="preconnect" href="https://fonts.googleapis.com" />
- <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
- <link href="https://fonts.googleapis.com/css2?family=..." rel="stylesheet" />
  <title>ExecuteSpec</title>
```

> No CDN preconnect either — this is the one era with zero external font network requests.

---

## Step 7 — Set dark as the default theme

Same as the other restoration docs — this era was dark-only in practice.

### Option A — `frontend/index.html` (fastest, prevents FOUC)

```html
<script>
  (function() {
    var saved = localStorage.getItem('ui-theme');
    document.documentElement.setAttribute('data-theme', saved || 'dark');
  })();
</script>
```

### Option B — `frontend/src/stores/useUiStore.ts`

```diff
- theme: 'light',
+ theme: 'dark',
```

---

## What does NOT change

- **Backend** — zero Java/Spring changes
- **Routing / App.tsx** — no changes
- **React components (.tsx files)** — no changes
- **Tailwind config** — aliases to CSS vars automatically
- **All API client code** — unchanged

---

## Apply order

1. `primitives.css` — foundation
2. `tokens.css` — semantic layer + status tokens
3. `index.css` — fonts (local `@font-face`) + flat utility classes (no blur)
4. `ide.css` — IDE shell tokens
5. `console.css` — console shell tokens
6. `index.html` — remove Google Fonts links + add dark-default script
7. Run `npm --prefix frontend run build` and verify in browser

---

## Quick visual verification checklist

After applying:
- [ ] Page background is **true black** `#000000` (not near-black `#0A0A0F`, not warm graphite)
- [ ] Primary buttons show **Vercel blue** `#0070F3` (not violet, not Signal Blue)
- [ ] **No violet or emerald appears anywhere** in the UI — this is the tell that distinguishes this era from Mission Control
- [ ] Cards are **flat with a sharp border** — no blur, no translucency
- [ ] No gradient mesh on the page background — flat black
- [ ] Font renders as Geist Sans (loaded without a network request to Google Fonts or jsdelivr, if using the local `@font-face` route)
- [ ] Brand "gradient" is really just solid blue (teal collapsed into blue in this era)
