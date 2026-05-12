# `frontend/` — React + Vite dashboard

> Last reviewed: 2026-05-12 (1.0.0-M1).

React 18 + Vite 5 + Tailwind CSS 3.4 + TypeScript 5.3. Two coexisting chromes share Zustand stores; both build into the Spring Boot JAR at `src/main/resources/static/`.

## Top-level layout

```
frontend/
├── package.json                     # deps + scripts (build / dev / format:check / lint)
├── vite.config.ts                   # Vite config + dev-server proxy /api → :8080
├── tailwind.config.ts               # token-aware Tailwind (var(--c-*) palette)
├── tsconfig.json                    # strict TS; path aliases for shared primitives
├── .prettierrc.json + .prettierignore
├── .eslintrc.cjs                    # @typescript-eslint/* + react + react-hooks
├── .pa11yci.json                    # a11y gate (public surfaces)
├── A11Y.md                          # a11y enablement guide
├── public/
│   ├── manifest.webmanifest
│   ├── sw.js                        # PWA service worker (prod-only registration)
│   └── trust-pack.pdf               # Trust Pack download asset
└── src/
    ├── main.tsx                     # app entry + pre-paint theme + PostHog init
    ├── App.tsx                      # routes
    ├── tokens.css                   # Graphite+Signal palette tokens + 4-band score colours
    ├── components/
    │   ├── layout/                  # V2 sidebar shell (AppLayout, Sidebar, Header)
    │   ├── runs/                    # RunCard, RunDetailTabs, InlineRunDetail, ValidationReportPanel, ScoreCard, ScoreRing, VerdictPill, SignalBreakdown, CostProgressBar, RunSpecBlock, FirstRunCelebration, PrReviewButton
    │   ├── project/                 # ProjectDashboardPage, PulseTab, RepoCard, TechDebtPanel, WorkspaceHealthPanel, PostMergeBar, CodeQualityPanel, GeneratedDocsPanel, ProjectMemoryPanel, ProjectChatPanel, ArchitectureViewer
    │   ├── billing/                 # PlanCard, BillingPage, InvoicesPage
    │   ├── auth/                    # LoginPage, RegisterPage, SettingsPage
    │   ├── execution/               # ExecutionStepper, FailureDecisionModal, CancellationPanel
    │   ├── shared/                  # Button, Checkbox, Radio, ResponsiveModal, Tooltip, Glossary, Drawer, EstimateChip, MarkdownContent
    │   └── design-system/           # contrast.{ts,test.ts} — WCAG ratio helpers + dormant vitest-shape regression test
    ├── v3/                          # V3 top-bar shell (Shell, V3TopBar, V3HomeRedirect, V3* pages)
    │   ├── Shell.tsx
    │   ├── pages/                   # V3Landing, V3ProjectHome, V3EmptyProject, V3Insights, V3Library, V3Migrations, V3Settings, …
    │   └── stores/useV3ProjectStore.ts
    ├── stores/                      # Zustand: useAuthStore, useUiStore (theme), useRunStore, useProjectStore, useExecutionStore
    ├── pages/                       # top-level routes for the V2 shell — RunsListPage, RunDetail, AdminPage, OrgProjectsPage, PlansPage, IntegrationsPage, ExecSummaryPage, SharedReportPage, TierLifecyclePage, etc
    ├── lib/                         # api.ts (single backend client) + analytics.ts (posthog wrapper) + glossary.ts (5-term dictionary)
    └── styles/v3.css                # V3-shell-specific styles + scroll-fade utility
```

## Two coexisting chromes

| Chrome                             | Layout                                      | Path prefix                                                                                                                                                                                                              | Purpose                                                            |
| ---------------------------------- | ------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------ |
| **V2 sidebar shell** (`AppLayout`) | Left sidebar + breadcrumb header            | `/runs`, `/admin/*`, `/audit`, `/invoices`, `/accounting`, `/exec`, `/org/*`, `/projects/:id/dashboard`, `/projects/:id/executions/*`, `/welcome`, `/deployments`, `/legacy/billing`                                     | Long-form workbench surfaces (admin, billing, project dashboards). |
| **V3 top-bar shell** (`Shell`)     | Top-bar with ProjectSwitcher + theme toggle | `/projects/:slug`, `/projects/:slug/runs/:runShort`, `/projects/empty`, `/insights`, `/billing`, `/pricing`, `/settings`, `/integrations`, `/team`, `/trust`, `/library`, `/migrations`, `/exec-demo`, `/home`, `/claim` | Short-form conversation + landing surfaces.                        |

Both share `useAuthStore`, `useUiStore` (theme), `useRunStore`, `useProjectStore`. V3 adds `useV3ProjectStore`. State survives navigation between chromes — users don't lose context crossing the boundary.

`SecurityConfig` whitelists `/api/shared/**` + `/api/invites/preview` for public read paths (V175 cross-org sharing + share-link surfaces).

## Tokens + theming

Single source of truth: [`docs/design/ui-tokens.md`](../docs/design/ui-tokens.md). Palette is **Graphite + Signal**:

- Graphite base (`--c-graphite-*`) for surfaces + chrome.
- Signal Blue (`#5B8DEF`) for primary actions + brand accents.
- 4-band score palette — green (pass) / blue (info) / amber (partial / needs attention) / red (failed).
- `[data-theme='light']` overrides for the light theme. `useUiStore.setTheme(t)` applies pre-paint via `<html data-theme>` + persists to localStorage.

WCAG-AA enforced for body text + form controls. `frontend/src/design-system/contrast.{ts,test.ts}` ships pure-function ratio helpers + a vitest-shape regression test pinning every (token, surface) ratio + 3 counter-examples that must still fail. Dormant until vitest is wired into CI.

JetBrainsMono variable font is the single typeface for the entire app (PR #108, 2026-05-11 unification).

## API client

`frontend/src/lib/api.ts` is the **single** API client file. Every backend endpoint consumed from the frontend lives here. ArchUnit test `arch/ApiContractTest` asserts every URL in api.ts resolves to a registered backend `@*Mapping` — caught one drift on first run.

For the full audit of which endpoint is consumed by which frontend file, see [`docs/frontend-api-usage.md`](../docs/frontend-api-usage.md).

## Build + lint gates

```bash
npm install
npm run build               # production build
npm run dev                 # Vite dev server (proxies /api → :8080)
npm run format              # prettier --write
npm run format:check        # prettier --check (CI gate)
npm run lint                # eslint (CI gate)
npm run lint:fix            # eslint --fix
```

CI runs `format:check` + `lint` on every PR (`.github/workflows/lint.yml`).

Documented deviations from Google TypeScript style in [`docs/design/code-style.md`](../docs/design/code-style.md): default exports OK for `*.tsx` (D-T1), `any` is warn-mode (D-T2), interface vs type both accepted (D-T3), JSDoc on internal types skipped (D-T4).

## Notable components

### Run detail

- `RunDetailTabs` — main run detail with live cost polling (5s while non-terminal) + file auto-refresh + SSE event subscription.
- `ScoreCard` + `ScoreRing` — multiplicative verifiedScore visualization (4-band colour, 132px hero ring).
- `VerdictPill` — Shippable / Needs review / Blocked derived from build + tests + reviewer + verifiedScore. Numerics demoted to disclosure.
- `SignalBreakdown` — "Build ✓ · Tests N/M · Reviewer CLEAN · Verified N" chip row on patch-mode runs.
- `CostProgressBar` — live spend vs budget on Credits panel (green 0-80% / amber 80-100% / red ≥100%, aria-progressbar attributes).
- `InlineRunDetail` — embedded run detail in V3 project chat thread; "Update PR" button on `alreadyPushed=true` runs to re-push after a per-task retry.

### Project workbench

- `ProjectDashboardPage` — 5-group tabbed dashboard (Pulse / Repos / Runs / Code / Chat).
- `PulseTab` — last-run hero + ScoreRing + signal chips + workspace health rollup.
- `ProjectChatPanel` — full-height chat with compaction + reset + per-bubble copy + branch override on launch.
- `TechDebtPanel` — category × severity matrix; "Fix" button per category → autofix Phase 2 dispatch.
- `CodeQualityPanel` — Code Review scan results; severity chips → autofix Phase 4 dispatch.
- `PrReviewButton` — inline icon button on every PR row → opens PR Review verdict popover with "Autofix N comments" → autofix Phase 1.
- `WorkspaceHealthPanel` — per-repo SHA drift + webhook + open-PR count.
- `PostMergeBar` — stacked banner above the dashboard for recent merges.
- `ArchitectureViewer` — per-version arch diff (LCS + GitHub `compareCommits`) on a click.
- `GeneratedDocsPanel` — Generate / Regenerate / View / Download for EXPLANATION + SETUP docs.

### Shared primitives

- `Button`, `Checkbox`, `Radio` — cva variant + size system.
- `ResponsiveModal` — focus-trap + Escape + `disableDismiss` + `hideCloseButton` + ARIA role=dialog.
- `Tooltip` — CSS-only, 250ms delay, hover/focus reveal.
- `Glossary` — hover/focus tooltip with plain-English definitions for `codemap`, `wiring pass`, `verified score`, `status reason`, `classifier`.
- `Drawer` — slide-in panel primitive.
- `EstimateChip` — debounced p25/p75 task + cost estimate chip under the requirement textarea.

### Theme + analytics

- `useUiStore.ts` — theme slice with `setTheme(t)` / `toggleTheme()`, pre-paint `<html data-theme>` apply + localStorage persistence.
- `analytics.ts` — thin wrapper over `posthog-js`. Enabled only when `VITE_POSTHOG_KEY` is set. Events mirror the 6-event activation funnel (SIGNUP / PROJECT_CREATED / SPEC_SUBMITTED / PLAN_APPROVED / RUN_COMPLETE / PR_MERGED).

## SSE event handling

`useSse(runId, token)` hook subscribes to `/api/runs/{id}/events?token=<jwt>` (scoped JWT, 5-min TTL, `scope=sse` claim). Event types it dispatches into the store:

- `PLAN_READY` — opens plan-approval modal.
- `RUN_STATUS` — updates status badge.
- `TASK_STATUS` — updates per-task progress.
- `LIVE_LOG` — appends tool call + LLM completion to the live-log pane.
- `RUN_COST` — updates `CostProgressBar` + cost chip.
- `EXEC_COMPENSATION_UPDATE` / `EXEC_COMPENSATION_DONE` — cancel rollback live cleanup view.
- `RUN_PROVIDER_SWITCHED` — toast on provider fallback.
- `COMPLETE` — terminal `{status, statusReason}` payload; flips badge + tooltip.

## Accessibility

- `frontend/.pa11yci.json` — pa11y-ci config for public surfaces (login, register, plans, pricing, terms, privacy, dpa).
- Dormant WCAG ratio regression test at `src/design-system/contrast.test.ts`.
- See `frontend/A11Y.md` for the enablement guide.

## Recent work (kept as breadcrumbs)

- **PR refresh button** also polls CI check-runs alongside open/merged state (PR #106 / V186, 2026-05-11).
- **SPA fallback** for 4-segment routes (e.g. `/projects/:slug/runs/:runShort/...`); favicon redirect; V3 chat actions restored; file scope toggle.
- **RunDetail auto-refresh** — files refresh every 5s while non-terminal (was: only on RUN_STATUS SSE). Folder tree replaces flat list of changed files. Goal / userRequest expandable with `RunSpecBlock`.
- **JetBrainsMono unification** + 11 audit fixes across login + dashboard + project surfaces (PR #108, 2026-05-11).
- **Glossary primitive + 5-term dictionary** + extended Tooltip with `wrap` + `maxWidthClass` for multi-line text.
- **PR Review autofix + Tech-debt fix + Code Review autofix** wired through `FindingPatchSubmitter` in the backend; UI dispatch buttons in their respective panels.
- **External project sharing UI** — invite popover + share-link popover on ProjectMembersPanel + public `Shared*Page` components.
- **Glossary + VerdictPill rollout** — numerics on ValidationReportPanel / ScoreCard / SignalBreakdown demoted to disclosure; pill leads.
