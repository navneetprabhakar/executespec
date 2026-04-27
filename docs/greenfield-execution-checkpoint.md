# Greenfield Execution — Checkpoint

> Written by the implementing session. Refreshed every 3 R-items.
> Branch: `feature/conversion-redesign` (off `dev` @ `f1d04532`).

## Status

* **Wave:** 1 of 3 — *Build the v3 spine end-to-end.*
* **Items shipped:** 14 of 20 in Wave 1 (70%)
* **Items in flight:** none — moving to next batch
* **Blocked / decision-needed:** none (defaults applied for D-001..D-004)

## Done so far

| ID | Title | Files | Verified |
|---|---|---|---|
| R-101 | v3 router shell + dark theme lock | `frontend/src/v3/components/V3Shell.tsx` · `v3.css` · `App.tsx` | Browser preview at `localhost:5176/v3/home` |
| R-105 | v3 terminology source-of-truth | `frontend/src/v3/lib/terminology.ts` | Consumed by all v3 components |
| R-102 | TopBar | `frontend/src/v3/components/V3TopBar.tsx` | Visible across every v3 page |
| R-106 | Pill + Term primitives + glossary | `Pill.tsx` · `Term.tsx` · `lib/glossary.ts` | All tones × sizes verified live |
| R-103 | CmdK skeleton (action mode) | `CmdK.tsx` · `stores/useV3Ui.ts` | Cmd-K opens, 14 actions, admin-gated |
| R-104 | AvatarMenu | `AvatarMenu.tsx` (mounted in `V3TopBar`) | SUPER_ADMIN: dropdown shows correct items + identity |
| R-132 | Honest-Verdict backend patch | `report/ValidationReportService.java` + `ValidationReportServiceVerifiedScoreTest.java` | 8/8 tests green; affects v2 & v3 simultaneously |
| R-124 | ProjectSwitcher | `ProjectSwitcher.tsx` | Loads 3 projects; trigger label tracks current |
| R-120 | Project home (chat shape) | `pages/V3ProjectHome.tsx` · `stores/useV3ProjectStore.ts` | Pulse + thread + composer at `/v3/p/{slug}` |
| R-121 | PlanCard inline | `components/PlanCard.tsx` | Inline plan-approval card replaces v2 modal |
| R-122 | ResultCard inline | `components/ResultCard.tsx` | 3-state honest verdict + 4 evidence chips |
| R-123 | Composer | `components/Composer.tsx` | 1 textarea + Quick run + Advanced; ⌘+Enter; sample chips |
| R-130 | Run detail (PR-shaped) | `pages/V3RunDetail.tsx` | At `/v3/p/{slug}/r/{runShort}`: status header + evidence chips + file tree + content view; "Open PR ↗" + "Re-prompt" actions |
| R-131 | RunFailureSummary | `components/RunFailureSummary.tsx` | Lists what went wrong (build / tests / reviewer) + top 3 issues + "Re-prompt with this context" CTA — only renders when verdict is Needs review or Failed |

## Cohabitation summary (recap)

* `/v3/*` route prefix. v2 untouched at every existing path.
* v3 forces `data-theme="dark"` on document root only while a v3 page is mounted; theme is restored on unmount so v2's toggle keeps working.
* v3 TopBar renders OUTSIDE v2's `AppLayout` — no Sidebar, no v2 header, no breadcrumb (the in-page `← Back to project` link in run-detail is the only crumb in the entire v3 surface).
* v3 reuses v2's `useAuthStore`, `useUiStore`, and `lib/api.ts` so RBAC + settings + API contracts stay consistent across the cohabiting surfaces.
* R-132 is a backend patch — its effect is visible to BOTH v2 and v3 surfaces (the formula is the single source). v2 ScoreCard immediately starts showing more honest numbers without any frontend change.

## Up next

R-142 (`/api/v3/entitlements` single-source tier API) → R-161 (V143 lifetime credit schema) → R-140 (`/v3/settings`) → R-160 (PostHog events). After that the auth + landing trio (R-110/111/112) closes Wave 1.

## Decisions still pending acknowledgement (defaults applied)

| Tag | Default | Status |
|---|---|---|
| D-001 textarea-copy | "What do you want to build?" + "Reply or describe a follow-up change…" | Shipped in R-120/123; overridable later |
| D-002 lifetime-credit math | 5 lifetime + 5/invite, cap 25 | Lands with R-161 V143 next |
| D-003 referral-fire-trigger | On referee's first run | R-162 (Wave 2) |
| D-004 vibe-view-decision | Delete in v3; v2 untouched until cutover | All v3 screens (no Vibe toggle) |

## Servers + verification

| Service | Port | URL | State |
|---|---|---|---|
| v3 frontend (Vite) | 5176 | `http://localhost:5176/v3/p/{id}/r/{runId}` | up |
| v2 backend (Spring Boot) | 8080 | proxied via Vite | up |
| v2 backend (alt) | 8081 | direct | up |
| v2 frontend (Vite) | 5174 | `http://localhost:5174/` | up (other concurrent session) |

## TypeScript / Maven

`./node_modules/.bin/tsc --noEmit` clean across v2 + v3.
`mvn test -Dtest='ValidationReportServiceVerifiedScoreTest'` 8/8 green.
