# Greenfield Execution — Checkpoint

> Written by the implementing session. Refreshed every 3 R-items.
> Branch: `feature/conversion-redesign` (off `dev` @ `f1d04532`).

## Status

* **Wave:** 1 of 3 — *Build the v3 spine end-to-end.*
* **Items shipped:** 6 of 20 in Wave 1 (30%)
* **Items in flight:** none — moving to next batch
* **Blocked / decision-needed:** none (defaults applied for D-001/D-002/D-003/D-004; user can override at any point and I'll rip-and-redo)

## Done so far

| ID | Title | Files | Verified |
|---|---|---|---|
| R-101 | v3 router shell + dark-only theme lock | `frontend/src/v3/components/V3Shell.tsx` · `frontend/src/v3/v3.css` · `frontend/src/v3/pages/V3HomePlaceholder.tsx` · `frontend/src/App.tsx` | Browser preview at `localhost:5176/v3/home`; renders top-bar + body, no sidebar, no console errors |
| R-105 | v3 terminology source-of-truth | `frontend/src/v3/lib/terminology.ts` | Module compiles; downstream components consume via named exports |
| R-102 | TopBar v3 component | `frontend/src/v3/components/V3TopBar.tsx` | Visible in screenshot; logo+wordmark left, project-switcher placeholder centre, ⌘K + avatar right |
| R-106 | Pill + Term primitives + glossary | `frontend/src/v3/components/Pill.tsx` · `frontend/src/v3/components/Term.tsx` · `frontend/src/v3/lib/glossary.ts` | All 6 tones (ok/warn/err/neutral/info/brand) × 2 sizes verified live; Term renders `?` glyph + native tooltip |
| R-103 | CmdK skeleton with action mode | `frontend/src/v3/components/CmdK.tsx` · `frontend/src/v3/stores/useV3Ui.ts` | Cmd-K toggles palette; 14 actions with admin-gated subset; ↑↓/Enter/Esc work; click-outside dismisses |
| R-104 | AvatarMenu | `frontend/src/v3/components/AvatarMenu.tsx` (mounted in `V3TopBar`) | SUPER_ADMIN session: dropdown shows Account / Billing & plan / Team / Integrations / Insights / Help / Sign out + identity header |

## Cohabitation summary (recap)

* `/v3/*` route prefix. v2 untouched at every existing path.
* v3 forces `data-theme="dark"` on document root only while a v3 page is mounted; theme is restored on unmount so v2's toggle keeps working.
* v3 TopBar renders OUTSIDE v2's `AppLayout` — no Sidebar, no v2 header, no breadcrumb.
* v3 reuses v2's `useAuthStore` and `useUiStore` for auth + theme so RBAC + settings stay consistent across the cohabiting surfaces.

## Up next

R-132 (Honest-Verdict backend patch — small, surgical, fixes the v2 lie immediately) → R-124 (ProjectSwitcher) → R-120 (project home) → checkpoint.

## Decisions still pending acknowledgement (defaults applied)

| Tag | Default | Will appear in |
|---|---|---|
| D-001 textarea-copy | *"What do you want to build?"* | R-110 `/v3/landing`, R-120 `/v3/p/{slug}` empty state |
| D-002 lifetime-credit math | 5 lifetime + 5/invite, cap 25 | R-161 V143 migration, R-142 entitlements |
| D-003 referral-fire-trigger | On referee's first run | R-162 referral mechanism (Wave 2) |
| D-004 vibe-view-decision | Delete in v3; v2 untouched until cutover | All v3 screens (no Vibe toggle) |

## Servers + verification

| Service | Port | URL | State |
|---|---|---|---|
| v3 frontend (Vite) | 5176 | `http://localhost:5176/v3/home` | up |
| v2 backend (Spring Boot, local profile) | 8080 | proxied via Vite | up |
| v2 backend (Spring Boot, alt) | 8081 | direct | up |
| v2 frontend (Vite) | 5174 | `http://localhost:5174/` | up (other concurrent session) |

## TypeScript

`./node_modules/.bin/tsc --noEmit` runs clean — no errors across v2 + v3.
