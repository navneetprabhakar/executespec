# Restoring the Graphite+Signal Theme

> **Era:** Mid-era dark-default app shell
> **Visual identity:** Warmed graphite `#10131a` page bg, Signal Blue `#5b8def` accent, flat elevated cards (no glassmorphism), JetBrains Mono, dark-first
> **Scope:** CSS changes only + one store/HTML default change. No backend, no routing, no React component logic changes required.

> **Implementation status:** Applied on branch `codex/web-agent-remote-sandbox-ux-plan` on 2026-07-01.
> The implementation uses `frontend/index.html` plus `frontend/src/main.tsx` for dark-first
> bootstrapping, keeps `[data-theme='light']` as the opt-in light override, and applies the optional
> IDE graphite alignment.

---

## Overview of changes

| File | Change type | Summary |
|------|-------------|---------|
| `frontend/src/console/console.css` | **Edit (required)** | Promote dark values to default, set accent to `#5b8def` |
| `frontend/src/stores/useUiStore.ts` or `frontend/index.html` | **Edit (required)** | Make dark the default theme for new visitors |
| `frontend/src/ide/ide.css` | Edit (optional) | Align bg to graphite-0 for visual consistency |
| `frontend/src/design-system/primitives.css` | **No change** | Already uses graphite-0 `#10131a` — correct |
| `frontend/src/tokens.css` | **No change** | Already uses `#5b8def` accent — correct |
| `frontend/index.html` | **No change** | JetBrains Mono already loaded |

---

## Why so few changes?

The `primitives.css` and `tokens.css` layers are **already Graphite+Signal** — they were never changed when Merge Rail shipped. The only divergence is:

1. The **console shell** (`console.css`) currently defaults to a **light cream** appearance and uses a darker Merge Rail blue (`#2f64d6` / `#4f7fe7`). The Graphite+Signal era used dark graphite by default with `#5b8def` Signal Blue.
2. The **app default theme** is currently light (or `prefers-color-scheme`). Graphite+Signal was dark by default.

---

## Step 1 — `frontend/src/console/console.css` (required, main change)

### Current structure (light default)

```css
.con-root {
  /* LIGHT default — cream/white shell */
  --ide-bg-editor: #f6f7f8;
  --ide-bg-sidebar: #ffffff;
  --ide-accent: #2f64d6;   /* darker Merge Rail blue */
  --ide-fg: #252a33;
  /* ... */
}

[data-theme='dark'] .con-root {
  /* dark override */
  --ide-bg-editor: #0b0d10;
  --ide-bg-sidebar: #11151b;
  --ide-accent: #4f7fe7;
  /* ... */
}
```

### Target structure (dark default = Graphite+Signal)

**Replace** the entire `.con-root { }` block and `[data-theme='dark'] .con-root { }` block with:

```css
/* Graphite+Signal dark default */
.con-root {
  --ide-bg-editor: #10131a;          /* graphite-0 */
  --ide-bg-sidebar: #161a23;         /* graphite-1 */
  --ide-bg-activitybar: #161a23;
  --ide-bg-panel: #161a23;
  --ide-bg-input: #1a1f29;           /* graphite-2 */
  --ide-bg-tab-active: #1a1f29;
  --ide-bg-tab-inactive: #161a23;
  --ide-bg-statusbar: #101825;
  --ide-bg-widget: #1a1f29;
  --ide-fg: #c9d1db;
  --ide-fg-muted: #8a94a6;
  --ide-fg-bright: #eceef2;
  --ide-fg-statusbar: #ffffff;
  --ide-border: rgba(255, 255, 255, 0.08);
  --ide-border-strong: rgba(255, 255, 255, 0.16);
  --ide-accent: #5b8def;             /* Signal Blue */
  --ide-accent-fg: #ffffff;
  --ide-hover: #1f2736;
  --ide-list-active: #162338;
  --ide-list-inactive: #1b2a3b;
  --ide-green: #10b981;
  --ide-yellow: #f5c451;
  --ide-red: #f17878;
  --ide-blue: #7aa5f5;
  --ide-add: rgba(74, 129, 5, 0.32);
  --ide-del: rgba(128, 23, 23, 0.4);
  --ide-shadow: rgba(0, 0, 0, 0.46);

  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--ide-bg-editor);
  color: var(--ide-fg);
  font-family:
    'JetBrains Mono',
    ui-monospace,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    system-ui,
    sans-serif;
  overflow: hidden;
}

/* Light theme override — available if user toggles light mode */
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
  --ide-accent: #2f64d6;
  --ide-accent-fg: #ffffff;
  --ide-hover: #eef1f5;
  --ide-list-active: #e8eeff;
  --ide-list-inactive: #eef1f5;
  --ide-green: #139a67;
  --ide-yellow: #b88700;
  --ide-red: #d14b4b;
  --ide-blue: #4f7fe7;
  --ide-add: rgba(19, 154, 103, 0.14);
  --ide-del: rgba(209, 75, 75, 0.12);
  --ide-shadow: rgba(17, 19, 24, 0.12);
}
```

### Key differences from current Merge Rail

| Token | Current Merge Rail (light default) | Graphite+Signal |
|-------|-------------------------------------|-----------------|
| `.con-root` default bg | `#f6f7f8` cream | `#10131a` dark graphite |
| `.con-root` default accent | `#2f64d6` dark blue | `#5b8def` Signal Blue |
| Dark override accent | `#4f7fe7` brighter blue | `#5b8def` Signal Blue |
| Default text color | `#252a33` dark on light | `#c9d1db` light on dark |
| Status bar bg | `#111318` | `#101825` (slightly warmer) |

---

## Step 2 — Set dark as the default theme (required)

The console shell change above sets dark as the CSS default, but the React store or `index.html` also controls which `data-theme` attribute is set on `<html>`. Without this step, users who haven't set a preference may see a flash of light mode on first load.

### Option A — `frontend/index.html` (recommended, prevents FOUC)

Add this script in `<head>` **before** any `<link>` or `<style>` tags:

```html
<script>
  (function() {
    var saved = localStorage.getItem('ui-theme');
    document.documentElement.setAttribute(
      'data-theme',
      saved || 'dark'   // default to dark if no saved preference
    );
  })();
</script>
```

### Option B — `frontend/src/stores/useUiStore.ts`

Find the Zustand store's initial state (search for `theme:` or `initUiMode`). Change the default:

```diff
- theme: 'light',
+ theme: 'dark',
```

> If `initUiMode()` hard-pins `'console'` and the theme is set separately, look for the theme initialization — it may read from `localStorage` with a fallback. Change the fallback value there.

---

## Step 3 — `frontend/src/ide/ide.css` (optional, visual consistency)

The IDE shell dark bg is currently `#0b0d10` (Merge Rail very dark). Aligning it to graphite-0 `#10131a` makes the IDE surfaces feel consistent with the console shell:

```diff
  .ide-root {
-   --ide-bg-editor: #0b0d10;
+   --ide-bg-editor: #10131a;

-   --ide-bg-sidebar: #11151b;
+   --ide-bg-sidebar: #161a23;

-   --ide-bg-activitybar: #171b22;
+   --ide-bg-activitybar: #161a23;

-   --ide-bg-panel: #0f1217;
+   --ide-bg-panel: #161a23;
  }
```

The accent `--ide-accent: #5b8def` is already correct in `ide.css` — no change needed there.

---

## Steps NOT required

### `frontend/src/design-system/primitives.css` — no change

The primitive layer is already Graphite+Signal:
```css
--c-graphite-0: #10131a;  ✓ correct
--c-signal-blue: #3b6fcc; /* note: this is the light-mode CTA value */
--c-signal-blue-on-dark: #7aa5f5;  /* used for text links, not buttons */
```
The `--accent-blue: #5b8def` that matters for button surfaces lives in `tokens.css` `:root` and is already correct.

### `frontend/src/tokens.css` — no change

```css
--accent-blue: #5b8def;      ✓ already Signal Blue
--accent-blue-hover: #7aa5f5; ✓ correct
--brand-grad: linear-gradient(135deg, var(--accent-blue) 0%, var(--c-signal-teal) 100%);
```

All correct. The console was overriding these with a darker blue at the component level — fixed in Step 1.

### `frontend/index.html` fonts — no change

JetBrains Mono is already loaded. No font changes needed.

---

## What Graphite+Signal does NOT restore

These are layout/chrome differences, not theme differences. Restoring them would require rebuilding deleted React components:

| Feature | Reason not restorable by theme alone |
|---------|--------------------------------------|
| Top-nav-bar layout | Was `signal/components/Shell.tsx` — deleted 2026-05-23 |
| Full-width run dashboard grid | Was `signal/pages/` route components — deleted 2026-05-23 |
| Compact sidebar with run pills | Was part of the deleted Signal chrome |

The ConsoleShell layout (current sidebar + top bar) will remain. Only the color palette and dark-default behavior changes.

---

## Verification checklist

After applying Steps 1–2 (and optionally Step 3):

- [ ] Default page background is dark graphite `#10131a` (not cream or pure black)
- [ ] Primary buttons / active nav items show `#5b8def` Signal Blue (not `#2f64d6` or violet)
- [ ] Sidebar (`#161a23`) is visibly lighter than page bg (`#10131a`) — 2-layer depth
- [ ] Cards appear flat (no blur, no glassmorphism) with a subtle `inset 0 1px 0 rgba(255,255,255,0.04)` inner highlight
- [ ] New visitors land on dark mode by default (check in incognito / cleared localStorage)
- [ ] Toggling to light mode still works (shows cream shell)
- [ ] Text is `#c9d1db` (secondary) and `#eceef2` (primary) on dark surfaces — readable
- [ ] Run status RUNNING dot is `#5b8def` (executing token), not violet or green
- [ ] Score indicators and success states remain green `#10b981`

---

## Apply order

1. Edit `frontend/src/console/console.css` — swap dark/light blocks
2. Edit `frontend/index.html` — add FOUC-prevention script (or edit the Zustand store)
3. Optionally edit `frontend/src/ide/ide.css` — align bg values
4. Run `npm --prefix frontend run build` and smoke-test in incognito
