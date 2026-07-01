# Restoring the Mission Control Theme

> **Era:** March 2026 — the original brand identity  
> **Visual identity:** Pure black `#0A0A0F` surfaces, violet `#8B5CF6` accent, emerald `#10B981` success/streaming  
> **Scope:** CSS + font changes only. No backend, no routing, no React component logic.
>
> **Not to be confused with:** the era immediately after this one (Apr 1–21, 2026) swapped violet for Vercel's exact blue `#0070F3` and dropped to true black `#000000` with zero glassmorphism — the codebase's own commit history calls that one "the Vercel/Geist clone aesthetic." See [`theme-restore-geist-clone.md`](theme-restore-geist-clone.md) for that one instead. This document's "Geist Sans/Mono" fonts overlap with that later era, but the *color palette* here is violet, not blue.

---

## Two sub-variants

The Mission Control era ran in two visually distinct modes. Both use the same colour palette; they differ only in fonts and card treatment.

| | Variant A — Glassmorphism | Variant B — Flat MC |
|---|---|---|
| **Active** | Mar 1 → Mar 28, 2026 | Mar 28 → Apr 1, 2026 |
| **Body font** | `Geist Sans` (jsdelivr CDN) | `DM Sans` (Google Fonts) |
| **Code font** | `Geist Mono` (jsdelivr CDN) | `JetBrains Mono` (Google Fonts) |
| **Cards** | `backdrop-filter: blur(12px)`, `border-radius: 12px`, inner highlight | Flat border only, `border-radius: 2px`, no blur |
| **Page bg** | Gradient mesh (violet + cyan + emerald radial gradients over `#0A0A0F`) | Solid `#0A0A0F` |

Both variants look like "pure black with violet" — the Glassmorphism variant has more depth; the Flat variant is stark and terminal-like.

---

## Overview of changes

| File | Variant A | Variant B |
|------|-----------|-----------|
| `frontend/src/design-system/primitives.css` | **Edit** (required, shared) | same |
| `frontend/src/tokens.css` | **Edit** (required, shared) | same |
| `frontend/src/index.css` | **Edit** — Geist CDN @import + glass utilities | **Edit** — no @import, no glass |
| `frontend/src/ide/ide.css` | **Edit** — Geist Sans font | **Edit** — DM Sans font |
| `frontend/src/console/console.css` | **Edit** — dark default, violet, Geist Sans | **Edit** — dark default, violet, DM Sans |
| `frontend/index.html` | **Edit** — add Geist CDN preconnects | **Edit** — add Google Fonts DM Sans |
| `frontend/src/stores/useUiStore.ts` | **Edit** — default theme `'dark'` | same |

---

## Step 1 — `frontend/src/design-system/primitives.css` (required, both variants)

### Surface ladder: graphite → pure black

```diff
- --c-graphite-0: #10131a;
+ --c-graphite-0: #0a0a0f;   /* pure black page bg */

- --c-graphite-1: #161a23;
+ --c-graphite-1: #111118;

- --c-graphite-2: #1a1f29;
+ --c-graphite-2: #1a1a24;

- --c-graphite-3: #232936;
+ --c-graphite-3: #242432;

- --c-graphite-4: #2d3441;
+ --c-graphite-4: #2e2e3e;
```

> Leave `--c-graphite-5` through `--c-graphite-8` unchanged.

### Brand family: Signal Blue → Violet

```diff
- --c-signal-blue: #3b6fcc;
+ --c-signal-blue: #8b5cf6;           /* violet */

- --c-signal-blue-hover: #2b5bb3;
+ --c-signal-blue-hover: #7c3aed;     /* deeper violet hover */

- --c-signal-blue-on-dark: #7aa5f5;
+ --c-signal-blue-on-dark: #a78bfa;   /* light violet — for text/links on dark */

- --c-signal-teal: #7ad1c8;
+ --c-signal-teal: #10b981;           /* emerald — gradient pair end + streaming */
```

> `--c-success`, `--c-warning`, `--c-error`, `--c-info`, `--c-pop-orange`, `--c-pop-amber`, `--c-accent-purple`, `--c-accent-pink` — **leave unchanged**.

---

## Step 2 — `frontend/src/tokens.css` (required, both variants)

In `:root { }`, update the three explicit hex literals that don't auto-resolve through primitives:

```diff
- --accent-blue: #5b8def;
+ --accent-blue: #8b5cf6;           /* violet */

- --accent-blue-hover: #7aa5f5;
+ --accent-blue-hover: #7c3aed;

  --accent-blue-text: var(--c-signal-blue-on-dark);  /* no change — resolves to #a78bfa */
```

`--brand-grad` already reads `linear-gradient(135deg, var(--accent-blue) 0%, var(--c-signal-teal) 100%)` — it now automatically resolves to **violet → emerald**.

Leave the `[data-theme='light'] { }` block unchanged.

---

## Step 3A — `frontend/src/index.css` (Variant A: Glassmorphism only)

### 3A-1. Add Geist font imports at the very top (before all other @import lines)

```css
@import url('https://cdn.jsdelivr.net/npm/geist@1.2.0/dist/fonts/geist-sans/style.css');
@import url('https://cdn.jsdelivr.net/npm/geist@1.2.0/dist/fonts/geist-mono/style.css');

/* existing imports follow */
@import './design-system/primitives.css';
@import './tokens.css';
```

### 3A-2. Add glassmorphism + gradient mesh utilities at the bottom of the file

```css
/* ── Mission Control (Glassmorphism) era utilities ───────────────────── */
.glass-panel {
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  background: rgba(255, 255, 255, 0.03);
  border-color: rgba(255, 255, 255, 0.06);
  border-radius: 12px;
  box-shadow: inset 0 1px 0 0 rgba(255, 255, 255, 0.03);
}

.gradient-mesh {
  background:
    radial-gradient(ellipse at 20% 50%, rgba(139, 92, 246, 0.04) 0%, transparent 50%),
    radial-gradient(ellipse at 80% 20%, rgba(6, 182, 212, 0.03) 0%, transparent 50%),
    radial-gradient(ellipse at 50% 80%, rgba(16, 185, 129, 0.02) 0%, transparent 50%),
    #0a0a0f;
}
```

To apply the gradient mesh to the page background, add `class="gradient-mesh"` to `<body>` in `frontend/index.html`.

---

## Step 3B — `frontend/src/index.css` (Variant B: Flat MC only)

No font `@import` needed here (font loads from `<link>` in `index.html`).

Add flat-card utility at the bottom of the file:

```css
/* ── Mission Control (Flat) era utilities ────────────────────────────── */
.mc-card {
  background: var(--c-graphite-2);            /* #1a1a24 */
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 2px;
  box-shadow: inset 0 1px 0 0 rgba(255, 255, 255, 0.02);
}
```

---

## Step 4 — `frontend/src/console/console.css` (required, both variants)

Replace the entire `.con-root { }` block **and** the `[data-theme='dark'] .con-root { }` block with the following. This makes dark the CSS default and demotes light to an explicit override.

```css
/* Mission Control dark default */
.con-root {
  --ide-bg-editor: #0a0a0f;           /* pure black */
  --ide-bg-sidebar: #111118;
  --ide-bg-activitybar: #0a0a0f;      /* collapsed into page bg */
  --ide-bg-panel: #111118;
  --ide-bg-input: #1a1a24;
  --ide-bg-tab-active: #1a1a24;
  --ide-bg-tab-inactive: #111118;
  --ide-bg-statusbar: #0a0a0f;
  --ide-bg-widget: #1a1a24;
  --ide-fg: #e4e4e7;
  --ide-fg-muted: #a1a1aa;
  --ide-fg-bright: #ffffff;
  --ide-fg-statusbar: #ffffff;
  --ide-border: rgba(255, 255, 255, 0.06);
  --ide-border-strong: rgba(255, 255, 255, 0.12);
  --ide-accent: #8b5cf6;              /* violet */
  --ide-accent-fg: #ffffff;
  --ide-hover: rgba(255, 255, 255, 0.04);
  --ide-list-active: rgba(139, 92, 246, 0.12);   /* violet tint */
  --ide-list-inactive: #1a1a24;
  --ide-green: #10b981;               /* emerald */
  --ide-yellow: #f59e0b;
  --ide-red: #f43f5e;
  --ide-blue: #a78bfa;               /* light violet (replaces signal blue) */
  --ide-add: rgba(16, 185, 129, 0.14);
  --ide-del: rgba(244, 63, 94, 0.14);
  --ide-shadow: rgba(0, 0, 0, 0.56);

  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--ide-bg-editor);
  color: var(--ide-fg);
  overflow: hidden;

  /* VARIANT A (Glassmorphism) — use Geist Sans */
  font-family: 'Geist Sans', ui-monospace, -apple-system, BlinkMacSystemFont, system-ui, sans-serif;

  /* VARIANT B (Flat MC) — use DM Sans instead:
  font-family: 'DM Sans', ui-sans-serif, -apple-system, BlinkMacSystemFont, system-ui, sans-serif; */
}

/* Light theme override */
[data-theme='light'] .con-root {
  --ide-bg-editor: #f6f7f8;
  --ide-bg-sidebar: #ffffff;
  --ide-bg-activitybar: #ffffff;
  --ide-bg-panel: #ffffff;
  --ide-bg-input: #ffffff;
  --ide-bg-tab-active: #ffffff;
  --ide-bg-tab-inactive: #eef1f5;
  --ide-bg-statusbar: #111318;
  --ide-bg-widget: #ffffff;
  --ide-fg: #252a33;
  --ide-fg-muted: #687181;
  --ide-fg-bright: #111318;
  --ide-fg-statusbar: #ffffff;
  --ide-border: #dde2ea;
  --ide-border-strong: #b8c0cc;
  --ide-accent: #7c3aed;
  --ide-accent-fg: #ffffff;
  --ide-hover: #eef1f5;
  --ide-list-active: #ede9fe;
  --ide-list-inactive: #eef1f5;
  --ide-green: #059669;
  --ide-yellow: #b88700;
  --ide-red: #dc2626;
  --ide-blue: #7c3aed;
  --ide-add: rgba(5, 150, 105, 0.14);
  --ide-del: rgba(220, 38, 38, 0.12);
  --ide-shadow: rgba(0, 0, 0, 0.12);
}
```

---

## Step 5 — `frontend/src/ide/ide.css` (optional, visual consistency)

Update the token block inside `.ide-root { }` to match Mission Control colors. Only the changed values:

```diff
  .ide-root {
-   --ide-bg-editor: #0b0d10;
+   --ide-bg-editor: #0a0a0f;

-   --ide-bg-sidebar: #11151b;
+   --ide-bg-sidebar: #111118;

-   --ide-bg-activitybar: #171b22;
+   --ide-bg-activitybar: #0a0a0f;   /* collapsed into page bg */

-   --ide-bg-panel: #0f1217;
+   --ide-bg-panel: #111118;

-   --ide-accent: #5b8def;
+   --ide-accent: #8b5cf6;           /* violet */

-   --ide-green: #7ad1c8;
+   --ide-green: #10b981;            /* emerald */

-   --ide-blue: #7aa5f5;
+   --ide-blue: #a78bfa;             /* light violet */
  }
```

Also update the font-family in `.ide-root`:

**Variant A (Glassmorphism):**
```diff
-   font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif;
+   font-family: 'Geist Sans', -apple-system, BlinkMacSystemFont, system-ui, sans-serif;
```

Add Geist Mono for code blocks inside `.ide-root`:
```css
.ide-root code,
.ide-root pre,
.ide-root .font-mono {
  font-family: 'Geist Mono', ui-monospace, 'SFMono-Regular', monospace;
}
```

**Variant B (Flat MC):**
```diff
-   font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif;
+   font-family: 'DM Sans', -apple-system, BlinkMacSystemFont, system-ui, sans-serif;
```

And for code blocks:
```css
.ide-root code,
.ide-root pre,
.ide-root .font-mono {
  font-family: 'JetBrains Mono', ui-monospace, 'SFMono-Regular', monospace;
}
```

---

## Step 6 — `frontend/index.html` (fonts + dark default)

### Variant A — Glassmorphism (Geist CDN)

Remove any existing Google Fonts `<link>` tags. Add:

```html
<link rel="preconnect" href="https://cdn.jsdelivr.net">
<link rel="stylesheet"
  href="https://cdn.jsdelivr.net/npm/geist@1.2.0/dist/fonts/geist-sans/style.css">
<link rel="stylesheet"
  href="https://cdn.jsdelivr.net/npm/geist@1.2.0/dist/fonts/geist-mono/style.css">
```

To activate the gradient mesh page background, add class to `<body>`:
```html
<body class="gradient-mesh">
```

### Variant B — Flat MC (DM Sans + JetBrains Mono, Google Fonts)

Remove any existing CDN font `<link>` tags. Add:

```html
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=DM+Sans:wght@400;500&family=JetBrains+Mono:wght@500;700&display=swap"
  rel="stylesheet">
```

---

## Step 7 — Set dark as the default theme (required, both variants)

### Option A — `frontend/index.html` (prevents FOUC, recommended)

Add this script block in `<head>` **before** any stylesheet `<link>` tags:

```html
<script>
  (function() {
    var saved = localStorage.getItem('ui-theme');
    document.documentElement.setAttribute('data-theme', saved || 'dark');
  })();
</script>
```

### Option B — `frontend/src/stores/useUiStore.ts`

Find the Zustand store initial state and change:

```diff
- theme: 'light',
+ theme: 'dark',
```

---

## What does NOT change

- **Backend** — zero Java/Spring changes
- **`App.tsx` / routing** — no changes
- **React components** — no `.tsx` changes
- **`tailwind.config.ts`** — already aliases to CSS vars, picks up token changes automatically
- **Score band colors** — semantic status tokens unchanged
- **Light-mode `[data-theme='light']` block** — preserved as a safety net

---

## Token comparison at a glance

| Token | Current (Graphite+Signal) | Mission Control (both variants) |
|-------|---------------------------|---------------------------------|
| Page bg | `#10131a` warmed graphite | `#0a0a0f` pure black |
| Sidebar | `#161a23` | `#111118` |
| Card | `#1a1f29` | `#1a1a24` |
| Card elevated | `#232936` | `#242432` |
| Border | `rgba(255,255,255,0.08)` | `rgba(255,255,255,0.06)` (more subtle) |
| Accent | `#5b8def` Signal Blue | `#8b5cf6` Violet |
| Accent hover | `#7aa5f5` | `#7c3aed` |
| Active item | `#162338` (blue tint) | `rgba(139,92,246,0.12)` (violet tint) |
| Green/success | `#10b981` | `#10b981` (unchanged) |
| Blue/info | `#7aa5f5` light blue | `#a78bfa` light violet |
| Status bar bg | `#101825` | `#0a0a0f` (merged with page) |

---

## Apply order

1. `frontend/src/design-system/primitives.css` — foundation
2. `frontend/src/tokens.css` — semantic layer
3. `frontend/src/index.css` — glass utilities (A) or flat utilities (B)
4. `frontend/src/console/console.css` — shell tokens + font (pick A or B)
5. `frontend/src/ide/ide.css` — optional consistency alignment
6. `frontend/index.html` — font links + dark-default FOUC script
7. `frontend/src/stores/useUiStore.ts` — if not using the HTML script approach
8. `npm --prefix frontend run build` and verify in incognito

---

## Variant A — Quick visual verification (Glassmorphism)

- [ ] Page bg is pure black `#0a0a0f` — warmer than pure `#000` but no blue/gray tint
- [ ] Cards have visible blur through them — a subtle depth layer beneath
- [ ] Cards have `border-radius: 12px` — rounded, not sharp corners
- [ ] Page background shows faint violet + cyan + emerald radial glows (gradient mesh)
- [ ] Primary buttons / active items show violet `#8b5cf6`
- [ ] Running log / streaming output shows emerald `#10b981`
- [ ] Font renders as Geist Sans — geometric, slightly condensed, not monospace
- [ ] New visitors land on dark mode (verify in incognito / cleared localStorage)

## Variant B — Quick visual verification (Flat MC)

- [ ] Page bg is pure black `#0a0a0f`
- [ ] Cards are flat — no blur, no glow, border only (`rgba(255,255,255,0.06)`)
- [ ] Cards have `border-radius: 2px` — near-square corners
- [ ] Primary buttons show violet `#8b5cf6`
- [ ] Running log shows emerald `#10b981`
- [ ] Body font is DM Sans — slightly rounded, humanist, clearly different from JetBrains Mono
- [ ] Code elements render in JetBrains Mono
- [ ] New visitors land on dark mode
