# Restoring the Geist-clone Theme

> **Era:** Oldest recoverable era — root commit `1b0ffc66`  
> **Visual identity:** Pure black `#0A0A0F`, violet brand `#8B5CF6`, emerald streaming log `#10B981`, glassmorphism cards, Geist Sans/Mono fonts  
> **Scope:** CSS/font changes only. No backend, no routing, no React component logic changes required.

---

## Overview of changes

| File | Change type | Summary |
|------|-------------|---------|
| `frontend/src/design-system/primitives.css` | Edit | 9 token changes: pure-black surface ladder + violet brand |
| `frontend/src/tokens.css` | Edit | 3 semantic accent changes |
| `frontend/src/index.css` | Edit | Add Geist CDN font imports + glassmorphism utility classes |
| `frontend/src/ide/ide.css` | Edit | 6 IDE token overrides + font family |
| `frontend/src/console/console.css` | Edit | 4 console token overrides + font family |
| `frontend/index.html` | Edit | Swap Google Fonts link for Geist CDN |

---

## Step 1 — `frontend/src/design-system/primitives.css`

These are the raw color primitives. The surface ladder needs to shift from warmed graphite back to pure black, and the brand color family needs to change from Signal Blue to violet.

### Surface ladder (graphite → pure black)

```diff
- --c-graphite-0: #10131a;  /* page bg */
+ --c-graphite-0: #0a0a0f;  /* page bg */

- --c-graphite-1: #161a23;  /* lowest surface */
+ --c-graphite-1: #111118;

- --c-graphite-2: #1a1f29;  /* card */
+ --c-graphite-2: #1a1a24;

- --c-graphite-3: #232936;  /* card-elevated */
+ --c-graphite-3: #242432;

- --c-graphite-4: #2d3441;  /* surface border (subtle) */
+ --c-graphite-4: #2e2e3e;
```

> `--c-graphite-5` through `--c-graphite-8` (borders, disabled fg) — leave unchanged.

### Brand color family (Signal Blue → Violet)

```diff
- --c-signal-blue: #3b6fcc;
+ --c-signal-blue: #8b5cf6;        /* violet brand */

- --c-signal-blue-hover: #2b5bb3;
+ --c-signal-blue-hover: #7c3aed;  /* violet hover */

- --c-signal-blue-on-dark: #7aa5f5;
+ --c-signal-blue-on-dark: #a78bfa; /* light violet — for text/links on dark bg */

- --c-signal-teal: #7ad1c8;
+ --c-signal-teal: #10b981;         /* emerald — used as the gradient pair end */
```

> `--c-success`, `--c-warning`, `--c-error`, `--c-info`, `--c-pop-orange`, `--c-accent-purple`, `--c-accent-pink` — **leave unchanged**.

---

## Step 2 — `frontend/src/tokens.css`

The semantic token layer aliases `primitives.css`. Because primitives changed, most tokens auto-resolve correctly. Only the three explicit hex literals in `:root` need updating.

### In `:root { }` (dark default)

```diff
- --accent-blue: #5b8def;
+ --accent-blue: #8b5cf6;           /* resolves via --c-signal-blue override above, but this literal override must also change */

- --accent-blue-hover: #7aa5f5;
+ --accent-blue-hover: #7c3aed;

  --accent-blue-text: var(--c-signal-blue-on-dark);  /* no change — resolves to #a78bfa now */
```

> `--brand-grad` already reads `linear-gradient(135deg, var(--accent-blue) 0%, var(--c-signal-teal) 100%)` — it will automatically resolve to violet→emerald. No change needed.

### In `[data-theme='light'] { }` (light mode override)

Leave the light-mode block unchanged. The Geist-clone era was dark-only; the light overrides are a safety net that won't be visible in normal use after Step 5.

---

## Step 3 — `frontend/src/index.css`

### 3a. Add Geist font imports at the very top (before the existing `@import` lines)

```css
@import url('https://cdn.jsdelivr.net/npm/geist@1.2.0/dist/fonts/geist-sans/style.css');
@import url('https://cdn.jsdelivr.net/npm/geist@1.2.0/dist/fonts/geist-mono/style.css');
```

The file currently starts with:
```css
/* R-300 (Wave 6) — primitives must load BEFORE tokens.css ... */
@import './design-system/primitives.css';
@import './tokens.css';
```

After the edit the top should be:
```css
@import url('https://cdn.jsdelivr.net/npm/geist@1.2.0/dist/fonts/geist-sans/style.css');
@import url('https://cdn.jsdelivr.net/npm/geist@1.2.0/dist/fonts/geist-mono/style.css');

/* R-300 (Wave 6) ... */
@import './design-system/primitives.css';
@import './tokens.css';
```

### 3b. Add glassmorphism and gradient-mesh utility classes at the bottom of the file

```css
/* ── Geist-clone era utilities ────────────────────────────────── */
.glass-panel {
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  background: rgba(255, 255, 255, 0.03);
  border-color: rgba(255, 255, 255, 0.06);
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

> To apply the gradient mesh to the page background, add `class="gradient-mesh"` to the `<body>` element in `frontend/index.html`, or add `body { background: var(--geist-bg); }` and override `--geist-bg` with the mesh. The simpler path is using the `gradient-mesh` class directly on the body/root.

---

## Step 4 — `frontend/src/ide/ide.css`

Update the token block inside `.ide-root { }`. Only the changed values are listed.

```diff
  .ide-root {
-   --ide-bg-editor: #0b0d10;
+   --ide-bg-editor: #0a0a0f;      /* pure black */

-   --ide-bg-sidebar: #11151b;
+   --ide-bg-sidebar: #111118;

-   --ide-bg-activitybar: #171b22;
+   --ide-bg-activitybar: #0a0a0f; /* collapsed into page bg */

-   --ide-accent: #5b8def;
+   --ide-accent: #8b5cf6;         /* violet */

-   --ide-green: #7ad1c8;
+   --ide-green: #10b981;          /* emerald */

-   --ide-blue: #7aa5f5;
+   --ide-blue: #a78bfa;           /* light violet */

    /* Change the font family */
-   font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif;
+   font-family: 'Geist Sans', -apple-system, BlinkMacSystemFont, system-ui, sans-serif;
  }
```

Also add a mono font override for code/terminal elements within `.ide-root`:

```css
.ide-root code,
.ide-root pre,
.ide-root .font-mono {
  font-family: 'Geist Mono', 'JetBrains Mono', ui-monospace, 'SFMono-Regular', monospace;
}
```

The `[data-theme='light'] .ide-root` block — leave unchanged (light mode will still fall back to Merge Rail light values, which is fine since Geist-clone was dark-only).

---

## Step 5 — `frontend/src/console/console.css`

The console shell (`con-root`) currently defaults to light cream. To restore Geist-clone, update the **dark-mode override** block and change the font:

### In `.con-root { }` — add font change

```diff
  .con-root {
    /* existing token values stay ... */
+   font-family: 'Geist Sans', Inter, -apple-system, BlinkMacSystemFont, system-ui, sans-serif;
  }
```

### In `[data-theme='dark'] .con-root { }` — update accent and backgrounds

```diff
  [data-theme='dark'] .con-root {
-   --ide-bg-editor: #0b0d10;
+   --ide-bg-editor: #0a0a0f;

-   --ide-bg-sidebar: #11151b;
+   --ide-bg-sidebar: #111118;

-   --ide-accent: (current dark value);
+   --ide-accent: #8b5cf6;         /* violet */

+   --ide-green: #10b981;          /* emerald */
  }
```

---

## Step 6 — `frontend/index.html`

Swap the JetBrains Mono Google Fonts link for Geist CDN preconnect + stylesheet links.

Find the existing Google Fonts `<link>` tags (they will look like):
```html
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:..." rel="stylesheet">
```

Replace with:
```html
<link rel="preconnect" href="https://cdn.jsdelivr.net">
<link rel="stylesheet"
  href="https://cdn.jsdelivr.net/npm/geist@1.2.0/dist/fonts/geist-sans/style.css">
<link rel="stylesheet"
  href="https://cdn.jsdelivr.net/npm/geist@1.2.0/dist/fonts/geist-mono/style.css">
```

> The CDN imports in Step 3 and these `<link>` tags are redundant — either approach works. The `<link>` tags give faster first-paint (browser discovers fonts before parsing CSS); the `@import` in index.css works without touching HTML. Use both for belt-and-suspenders or just one.

---

## Step 7 — Set dark as the default theme

The Geist-clone era was dark-only. Currently the console defaults to light. Force dark for first-time visitors:

### Option A — `frontend/index.html` (fastest, prevents FOUC)

Add this script block in `<head>` before any stylesheets:
```html
<script>
  (function() {
    var saved = localStorage.getItem('ui-theme');
    if (!saved) {
      document.documentElement.setAttribute('data-theme', 'dark');
    } else {
      document.documentElement.setAttribute('data-theme', saved);
    }
  })();
</script>
```

### Option B — `frontend/src/stores/useUiStore.ts`

Find the Zustand store's initial state and change the default theme:
```diff
- theme: 'light',
+ theme: 'dark',
```
(The exact field name may vary — search for `theme:` in the store file.)

---

## What does NOT change

- **Backend** — zero Java/Spring changes
- **Routing / App.tsx** — no changes
- **React components (.tsx files)** — no changes
- **Tailwind config** — the config already aliases to CSS vars (`var(--accent-blue)` etc.), so it picks up token changes automatically
- **Score band colors** — green/amber/red bands are semantic status tokens, unchanged
- **`--c-success`, `--c-error`, `--c-warning`, `--c-info`** — unchanged
- **Light-mode token block** — left as-is (safety net)
- **All API client code** — unchanged

---

## Apply order

1. `primitives.css` — foundation; everything aliases from here
2. `tokens.css` — semantic layer
3. `index.css` — fonts + utility classes
4. `ide.css` — IDE shell tokens
5. `console.css` — console shell tokens
6. `index.html` — font links + dark-default script
7. Run `npm --prefix frontend run build` and verify in browser

---

## Quick visual verification checklist

After applying:
- [ ] Page background is pure black `#0A0A0F` (not warm graphite)
- [ ] Primary buttons show violet `#8B5CF6` (not blue)
- [ ] Running run log / streaming output shows emerald `#10B981`
- [ ] Font renders as Geist Sans (clean geometric, not monospace-default)
- [ ] Cards have a subtle blur/translucency effect (glassmorphism)
- [ ] Brand gradient goes violet → emerald (not blue → teal)
- [ ] Score ring / active tabs use violet accent
