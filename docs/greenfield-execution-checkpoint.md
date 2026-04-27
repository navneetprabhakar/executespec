# Greenfield Execution — Checkpoint

> Written by the implementing session. Refreshed every 3 R-items.
> Branch: `feature/conversion-redesign` (off `dev` @ `f1d04532`).

## Status

* **Wave:** 1 of 3 — *Build the v3 spine end-to-end.*
* **Items shipped:** 8 of 20 in Wave 1 (40%)
* **Items in flight:** none — moving to next batch
* **Blocked / decision-needed:** none (defaults applied for D-001..D-004)

## Done so far

| ID | Title | Files | Verified |
|---|---|---|---|
| R-101 | v3 router shell + dark-only theme lock | `frontend/src/v3/components/V3Shell.tsx` · `frontend/src/v3/v3.css` · `frontend/src/v3/pages/V3HomePlaceholder.tsx` · `frontend/src/App.tsx` | Browser preview at `localhost:5176/v3/home` |
| R-105 | v3 terminology source-of-truth | `frontend/src/v3/lib/terminology.ts` | Module compiles; consumed by all v3 components |
| R-102 | TopBar v3 component | `frontend/src/v3/components/V3TopBar.tsx` | Visible across every v3 page |
| R-106 | Pill + Term primitives + glossary | `frontend/src/v3/components/Pill.tsx` · `Term.tsx` · `lib/glossary.ts` | All tones × sizes verified live |
| R-103 | CmdK skeleton with action mode | `frontend/src/v3/components/CmdK.tsx` · `stores/useV3Ui.ts` | Cmd-K toggles palette, 14 actions, admin-gated subset |
| R-104 | AvatarMenu | `frontend/src/v3/components/AvatarMenu.tsx` | SUPER_ADMIN session: dropdown shows correct items + identity |
| R-132 | Honest-Verdict backend patch | `src/main/java/com/navneet/executespec/report/ValidationReportService.java` · `src/test/java/com/navneet/executespec/report/ValidationReportServiceVerifiedScoreTest.java` | 8/8 tests green; `mvn test` clean. The exact strict-review case (run e48399bf: build pass, no tests, CLEAN) now scores 80 not 90; v3 UI gates the badge entirely (see R-130) |
| R-124 | ProjectSwitcher | `frontend/src/v3/components/ProjectSwitcher.tsx` (mounted in `V3TopBar`) · `frontend/src/v3/pages/V3ProjectHomePlaceholder.tsx` · `App.tsx` route | Loads 3 projects from `/api/projects`; provider chip canonical-cased; click → URL `/v3/p/{id}` |

## Cohabitation summary (recap)

* `/v3/*` route prefix. v2 untouched at every existing path.
* v3 forces `data-theme="dark"` on document root only while a v3 page is mounted; theme is restored on unmount so v2's toggle keeps working.
* v3 TopBar renders OUTSIDE v2's `AppLayout` — no Sidebar, no v2 header, no breadcrumb.
* v3 reuses v2's `useAuthStore`, `useUiStore`, and `lib/api.ts` so RBAC + settings + API contracts stay consistent across the cohabiting surfaces.
* R-132 is a backend patch — its effect is visible to BOTH v2 and v3 surfaces (the formula is the single source). v2 frontend will start showing more honest scores immediately even before v3 ships.

## Up next

R-120 (`/v3/p/{slug}` real chat-shaped project home — the dominant screen). Big enough to land alone or with R-122/R-123/R-121 as a thread-shaped batch.

## Decisions still pending acknowledgement (defaults applied)

| Tag | Default | Will appear in |
|---|---|---|
| D-001 textarea-copy | *"What do you want to build?"* | R-120 (next), R-110 |
| D-002 lifetime-credit math | 5 lifetime + 5/invite, cap 25 | R-161, R-142 |
| D-003 referral-fire-trigger | On referee's first run | R-162 (Wave 2) |
| D-004 vibe-view-decision | Delete in v3; v2 untouched until cutover | All v3 screens |

## Servers + verification

| Service | Port | URL | State |
|---|---|---|---|
| v3 frontend (Vite) | 5176 | `http://localhost:5176/v3/home` | up |
| v2 backend (Spring Boot) | 8080 | proxied via Vite | up |
| v2 backend (alt) | 8081 | direct | up |
| v2 frontend (Vite) | 5174 | `http://localhost:5174/` | up (other concurrent session) |

## TypeScript / Maven

`./node_modules/.bin/tsc --noEmit` clean across v2 + v3.
`mvn test -Dtest='ValidationReportServiceVerifiedScoreTest'` 8/8 green.
