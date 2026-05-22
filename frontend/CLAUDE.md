# `frontend/` — React + Vite dashboard

> Last reviewed: 2026-05-16 (1.0.0-M1).

React 18 + Vite 5 + Tailwind CSS 4 + TypeScript 5.3. **Two live chromes, A/B-switched by `useUiStore.uiMode`:** the production **Signal** shell (`frontend/src/signal/`) and the VS Code-style **IDE** workspace (`frontend/src/ide/`). The legacy V2 sidebar + V3 top-bar shells (`components/`, `pages/`, `v3/`) were **deleted** (commit `5f5e4aaa`); only `signal/`, `ide/`, `hooks/`, `lib/`, `stores/`, `types/`, `design-system/` remain under `src/`. Single Vite build into the Spring Boot JAR at `src/main/resources/static/`.

**Chrome A/B switch + IDE overhaul (2026-05-22, `frontend/src/ide/`):** `App.tsx` renders a **mode-specific route subtree** based on `useUiStore.uiMode` (`'signal' | 'ide'`, persisted to `es:uiMode`, applied to `<html data-ui>`): Signal mode mounts `signal/components/Shell`; IDE mode mounts `ide/IdeWorkspace` — a real VS Code workspace (activity bar + Explorer with live runs/projects + editor area + status bar + ⌘K command palette) with **its own core surfaces**, NOT wrapped Signal pages: `IdeWelcome` (workspace home), `IdeRunResult` (file-tree + editor tabs + the `IdeKit` code viewer + run-info panel — the flagship), `IdeCompose`, `IdeLive`, `IdeProject` (project workspace — composer + repo scoping + recent project runs, `ide/IdeProject.tsx`). **All core surfaces are now IDE-native at full Signal-parity depth** (2026-05-22 rebuild): project workbench — 10 sub-tabs incl. Code's 6 sub-tabs (browse / tech-debt+autofix / **CodeReviewPanel** / health / standards / docs), Architecture's 5 tabs (current / versions-diff / invariants / ADRs / contracts), Repos link-panel, Runs Runs/PRs split-pane + **PrReviewPanel**, Chat/Settings(RAG+memory)/Members(invite+share); run loop — Result 9 tabs (incl. Timeline + Chat/iterate), Live SSE console + CostProgressBar + cancel, Compose project/repo/cap/estimate/mode/TDD/Figma, run-management (fork/archive/delete/deploy/pause/resume), PR-comment inbox, preview lifecycle; plus Deployments + GCP, Admin 6-tab console, Account (notif-prefs/GDPR/DSAR/erasure), Integrations. **Dedicated PR Review + Code Review agent panels** live in `signal/components/PrReviewPanel.tsx` + `CodeReviewPanel.tsx` (shared `variant='signal'|'ide'`). Library + Migrations are LIVE (real API, not stubs). Remaining Signal-fallback inside the IDE shell (deliberate): billing / plans / org-settings / api-keys / org-members / notifications / search + discovery pages (insights/library/migrations-detail/templates/audit). The **V3 multi-solution execution lane** (`/api/executions`) is intentionally NOT surfaced — it's feature-flagged OFF (`TierConfig.executionViewEnabled=false` default; `App.tsx` redirects `/projects/:id/executions/*` away), so V2 is the only active execution path. `ide/IdeKit.tsx` = code viewer + command palette + tokenizer + badges; `ide/ide.css` = VS Code Dark+/Light+ tokens themed via `[data-theme]` (follows OS). Rollout: public flag `ui.ide.default` flips the cohort default; an explicit choice (Account → Preferences "Interface", or the IDE status-bar "⇄ Signal UI") always wins. Distinct desktop (activity bar / Explorer / editor) vs mobile (top-bar + drawer + bottom tabs) layouts.

## Signal primitives (`signal/components/`)

The reusable surface that every Signal page builds on. **Net-new this round (2026-05-15→16):**

- `Modal.tsx` — `Modal`, `ConfirmModal`, `PromptModal` with focus-trap, ESC dismiss, click-backdrop dismiss. Mobile-friendly (slides up on narrow screens). Replaces every `window.confirm` / `window.prompt` across Signal pages (9 destructive surfaces + 3 prompt surfaces verified).
- `Loaders.tsx` — `Spinner`, `LoadingState`, `SkeletonRow`, `InlineLoader`. Wired on HomePage / ProjectPulsePage / ProjectComposePage so the first paint shows shimmer rows instead of the empty state.
- `CodeView.tsx` — regex-tokenised syntax highlighter with Content/Diff toggle. Replaces V3's Monaco editor (saves ~600 KB on the bundle). Languages: TS/JS/Java/Python/JSON/SQL/Bash/YAML.
- `Markdown.tsx` — ~3 KB custom GFM-subset parser (no react-markdown). **All chat UI renders every message (both user + assistant) through it** — SpecChat, ProjectChatPage, ResultPage run chat, and the IDE project chat. `.signal-md*` classes are styled in `signal.css` (Signal) and re-mapped to `--ide-*` tokens via `.ide-root .signal-md*` in `ide.css` so markdown looks native in both chromes.
- `CostProgressBar.tsx` — green 0–80 % / amber 80–100 % / red ≥100 % of hard cap, `role='progressbar'`, `aria-valuenow`. Wired on LivePage.
- `BrandLogos.tsx` — real multicolour SVG logos for Google, GitHub, Slack, Jira, Confluence, Bitbucket, Stripe, Razorpay.
- `SpecChat.tsx` — "Build my spec" trigger + assistant chat with Markdown rendering. The launcher is also exposed on ComposePage / ProjectComposePage.

The shared 4-band score palette + `verifiedScore` thresholds live in `Primitives.tsx` (Button kinds: `primary | secondary | mint | coral | ghost`). `VerdictPill` thresholds align with `RunCard` / `ScoreRing` / HomePage / AllRunsPage: **≥85 = Shippable**, 70–84 = Needs review, <70 = Failed (since 2026-05-16; previously ResultPage used 90).

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
- `ScoreCard` + `ScoreRing` — verifiedScore visualization (4-band colour, 132px hero ring). Backed by the additive scorer (`scoring.additive-v2`) since 2026-05-19. `ResultPage` (2026-05-19 honesty pass) leads with a plain-English verdict sentence built only from the _measured_ signals (build / tests / reviewer) plus an explicit "not measured" list, so the headline can't read "Failed 50%" on a run with a passing build + clean reviewer.
- `VerdictPill` — Shippable / Needs review / Blocked derived from build + tests + reviewer + verifiedScore. Numerics demoted to disclosure.
- `SignalBreakdown` — "Build ✓ · Tests N/M · Reviewer CLEAN · Verified N" chip row on patch-mode runs.
- `CostProgressBar` — live spend vs budget on Credits panel (green 0-80% / amber 80-100% / red ≥100%, aria-progressbar attributes).
- `InlineRunDetail` — embedded run detail in V3 project chat thread; "Update PR" button on `alreadyPushed=true` runs to re-push after a per-task retry; "Download code" button restores the V2-page parity (regression fix 2026-05-12).

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

- **Backend×UI feature audit + Bucket A restore (2026-05-22)** — full audit in `docs/audit/`: `backend-feature-inventory.md` (448 endpoints), `ui-feature-inventory.md` (Signal + IDE per-screen), `feature-gap-comparison.md` + `api-usage-matrix.tsv` (304 api fns → 130 Signal / 45 IDE / 159 unwired). Found two losses: ~153 backend capabilities surfaced in no chrome, and 86 dropped Signal→IDE. Restore tracked in `docs/audit/bucket-a-restore-plan.md`. **Wave A1 (complete, both chromes):** (1) run-management actions (fork / archive / unarchive / soft-delete) on the run-detail header — `ResultPage` (TopBar IconButtons + `ConfirmModal` for delete) and `IdeRunResult` ("What now" row), surfacing `forkRun`/`archiveRun`/`unarchiveRun`/`softDeleteRun`; (2) **PR review-comment inbox** (`signal/components/PrCommentInbox.tsx`, shared `variant=signal|ide`) on the run-detail PR tab — lists PENDING inbound comments with Dismiss + Fix (apply-as-fix), surfacing `getPrComments`/`dismissPrComment`/`applyPrComment` (path param is the inbox ROW id, not GitHub commentId); (3) **plan revision** on the gate — feedback textarea → `editRunPlan` re-plans, in `PlanPage` + `IdePlan`. All dark endpoints; archive round-trip + pr-comments shape verified live.
- **UX overhaul — Integrations + Admin IDE-native (2026-05-22)** — `ide/IdeSettings.tsx` adds `IdeIntegrations` (GitHub / Slack / Jira / Confluence / Bitbucket rows from `getIntegrationStatus` + `getSlackStatus` + `getGitHubAppStatus`, with Install-app / Connect actions) and `IdeAdmin` (activation funnel read from `getActivationFunnel().stages[KEY].windowed` — the canonical 6-stage funnel — + tier lifecycle + 27 feature flags). Wired into `App.tsx` `/integrations` + `/admin` behind the `ide` flag. With the prior project-workbench + account rebuilds, only billing / plans / org-settings remain on the Signal fallback. Browser-verified against the live backend with admin creds (funnel 11/6/10/3/10/0; both light + dark themes).
- **UX overhaul — IDE project workbench (2026-05-22)** — all 10 project sub-tabs IDE-native: `ide/IdeProjectTabs.tsx` (`ProjectTabs` strip + `IdeProjectPulse` / `IdeProjectRuns` / `IdeProjectRepos`) + `ide/IdeProjectMore.tsx` (`IdeProjectChat` / `IdeProjectCode` / `IdeProjectArchitecture` / `IdeProjectMembers` / `IdeProjectActivity` / `IdeProjectSettings`) + `ide/IdeProjects.tsx` (health cards) + `ide/IdeProject.tsx` (project composer w/ repo scoping). Canonical `repoDisplayName` / `repoIdFor` from `signal/lib/runUtils` fix the SHA-vs-name bug; loaders on every fetch.
- **UX overhaul — real IDE workspace (2026-05-22, RL-2026-05-017)** — `ide/IdeWorkspace` (activity bar + Explorer w/ live runs/projects + editor + status bar + ⌘K palette) with net-new core surfaces in `ide/IdeSurfaces.tsx` (`IdeWelcome`/`IdeRunResult`/`IdeCompose`/`IdeLive`) + `ide/IdeKit.tsx` (from-scratch code viewer + command palette + tokenizer). `App.tsx` renders a mode-specific route subtree. Replaced the Slice-1 reskin (`ChromeShell`/`IdeShell` deleted) after founder feedback it was "half baked." Browser-verified with live data + ⌘K + switch-back.
- **UX redesign Slice 1 — A/B chrome switch (2026-05-22, RL-2026-05-016)** — original A/B switch + `useUiStore.uiMode` + Account → Preferences "Interface" toggle. Superseded by RL-017's real workspace.
- **Org model-matrix override grid (2026-05-22, PR #159, F3)** — `OrgSettingsPage` "Model matrix" section: per-tier cards (planning/workhorse/lightweight) × provider `<select>`s, each defaulting to "Default · <yaml model>" with the provider's registered models as options. Reads/writes `getOrgModelMatrix` / `updateOrgModelMatrix` (`GET/PUT /api/org/me/model-matrix`). Editable only for ENTERPRISE (`view.editable`); read-only with an upgrade note otherwise. Backend resolves the override at `ModelResolver.lookupModel` via a run-scoped ThreadLocal.
- **Org AI-keys settings panel (2026-05-22, PR #159)** — `OrgSettingsPage` gained an "AI provider keys" section: per-provider rows (Anthropic / OpenAI / Gemini, + OpenRouter gated to ENTERPRISE) with Connected/Not-set `StatusPill` + write-only password input + per-provider Save → `updateOrgAiKeySettings`. Backend now treats a blank value as "leave unchanged" (was: clear), so one-provider saves don't wipe the others (`OrgServiceAiKeysTest`). First UI for org-level BYOK keys (previously API-only). UX is placeholder pending a design pass.
- **Signal P0/P1 punch-list (2026-05-16, PR #138)** — VerdictPill threshold aligned at ≥85; `Members · -1` regression hardened (ProjectTabs clamps counts ≥0 + reads real dashboard fields + Architecture promoted to a tab); /audit error message branches on tier+role; monthly run cap surfaced on /home; Signal Breakdown distinguishes 0 from "not measured"; Activity feed + project memory poll-refresh every 30 s; "Try another provider" row on Result page; runTitle preferred in list rows; SkeletonRow loaders on initial fetches; scroll fix (html/body/.signal-shell/.signal-main locked at 100dvh + `.signal-content`/`.signal-feed` get `min-height:0` so `overflow-y:auto` actually scrolls inside the Feed); ConfirmModal/PromptModal swap on all destructive surfaces; CostProgressBar on LivePage; Plans 3-col on desktop; LivePage hero label maps status → human phase; `external_sharing_enabled` UI toggle on Org Settings + invite-card gate on Project Members (V198).
- **PR refresh button** also polls CI check-runs alongside open/merged state (PR #106 / V186, 2026-05-11).
- **SPA fallback** for 4-segment routes (e.g. `/projects/:slug/runs/:runShort/...`); favicon redirect; V3 chat actions restored; file scope toggle.
- **RunDetail auto-refresh** — files refresh every 5s while non-terminal (was: only on RUN_STATUS SSE). Folder tree replaces flat list of changed files. Goal / userRequest expandable with `RunSpecBlock`.
- **JetBrainsMono unification** + 11 audit fixes across login + dashboard + project surfaces (PR #108, 2026-05-11).
- **Glossary primitive + 5-term dictionary** + extended Tooltip with `wrap` + `maxWidthClass` for multi-line text.
- **PR Review autofix + Tech-debt fix + Code Review autofix** wired through `FindingPatchSubmitter` in the backend; UI dispatch buttons in their respective panels.
- **External project sharing UI** — invite popover + share-link popover on ProjectMembersPanel + public `Shared*Page` components.
- **Glossary + VerdictPill rollout** — numerics on ValidationReportPanel / ScoreCard / SignalBreakdown demoted to disclosure; pill leads.
