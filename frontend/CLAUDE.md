# frontend/ — React Dashboard

> Last updated: 2026-04-26 (v2.6.4 — UX overhaul v4 IA cleanup on `feature/ux-overhaul-v4-pulse-tab` — 6 top tabs → 4 (Pulse / Runs / Code / Chat) + More drawer + Pulse hero card + RunStamp share component + EmptyProjectLanding sample gallery; v2.6.1 chat UX bundle on dev; v2.5.12: design-system primitives + GeneratedDocsPanel)
>
> **v2.6.4 — UX overhaul v4 (`feature/ux-overhaul-v4-pulse-tab`, NOT yet merged):** 6→4 top-tab IA cleanup per docs/plans/ux-overhaul-v4.md (was `/tmp/eval-reports/wireframe-report.md`). Project dashboard's first paint dropped from 25 affordances to ~10. Five new components, one large refactor.
> - **Top tabs collapsed**: Overview · Work · Knowledge · Activity · Settings · Chat → **Pulse · Runs · Code · Chat** + **More ▾** drawer. Long-tail surfaces (Settings · Generated Docs · Memory · Activity full feed · Documents · Team) live in the drawer. Backward-compat: `?tab=overview/launches/prs/tasks/architecture/repos/docs/memory/activity/team` all map to the right new home (see `legacyTabToNew` in `ProjectDashboardPage.tsx`).
> - **`PulseTab.tsx`** (NEW, ~280 LOC): replaces the legacy 7-section Overview pane with a focused 4-card layout — (1) Last Run hero card (status + signal chips + View report + Open run), (2) Score ring (verified > est, with `est. N` strikethrough when they disagree), (3) Health & debt rollup (repos / stale codemaps / open PRs / last-analysed time), (4) Activity preview (last 5 events). Plus a compact "Recent pull requests" list when present. Empty state defers to `EmptyProjectLanding` only when **both** runs and repos are zero.
> - **`CodeTab.tsx`** (NEW, ~80 LOC): consolidates Architecture · Repos · Health · Tech debt · Standards · Contracts into one tab with a chip-style sub-tab strip. Was previously stitched across Knowledge → Architecture, Settings → Repos, and 4 standalone Overview panels. No new components — re-uses every panel verbatim.
> - **`MoreDrawer.tsx`** (NEW, ~210 LOC): right-edge slide-over (480px / full-width on mobile) with 6 panels (Settings · Generated Docs · Memory · Activity full feed · Reference docs · Team). Body-scroll lock + ESC to close + initial focus on the X button. Deep-linkable via `?drawer=<panel>`. Embedded `useDrawerState()` hook listens for the global `drawer-state` event so sibling components (PulseTab "Show all activity") can open the drawer without prop drilling.
> - **`RunStamp.tsx`** (NEW, ~190 LOC): UX report §6.3 #3 — every COMPLETE/PARTIAL run renders a 1-line shareable stamp (`Built in 11m50s · Verified 85 · 6/6 tasks · Anthropic`) at the bottom of the expanded `ValidationReportPanel`, with three buttons — Copy (text), Share (creates a public report link via `shareValidationReport` and copies the URL), Embed (reveals an embed-ready `<a>` HTML snippet for blogs / Slack). Wired in via `ValidationReportPanel.stampMeta` prop populated from `RunDetail.tsx` + `InlineRunDetail.tsx` (passes runStartedAt / runCompletedAt / tasksTotal / tasksComplete / provider). Free distribution loop without changing the run page UX.
> - **`EmptyProjectLanding.tsx`** (NEW, ~170 LOC): replaces the dashboard's "no runs yet" wall when no repos AND no runs — shows a single textbox-style invitation (`Ship verified code from a spec.`) + Start a run / Link a repo CTAs + a 6-card sample-spec gallery (consumes the existing `listOnboardingSamples` from V125's onboarding bundle) + a "Continue from share link" affordance for paste-a-shared-report-URL forking.
> - **`ProjectDashboardPage.tsx`** (REWRITE, 638 → 480 LOC): new 4-tab strip + Runs sub-tabs (Launches / Pull requests / Run detail) + compact one-row summary bar (replaces the 6 KPI tiles' "no data yet" sea) + More button in the top-right header. The legacy left-rail RunsSidebar still appears, but only on the Runs → Run detail path. Empty path renders `EmptyProjectLanding`; happy path renders `PulseTab` by default.
> - **Reused unchanged**: every existing panel (`ArchitectureViewer`, `ProjectRepoManager`, `TechDebtPanel`, `StandardsCompliancePanel`, `ContractCoveragePanel`, `WorkspaceHealthPanel`, `GeneratedDocsPanel`, `ProjectMemoryPanel`, `ActivityTab`, `ProjectDocumentsPanel`, `ProjectChatPanel`, `ProjectLaunchesList`, `ProjectPrRollup`, `InlineRunDetail`). UX-preservation contract held: no new tokens, no new fonts, no new primitives — just rearranged.
>
> **v2.6.1 (project-chat UX bundle, commit `ddf9c498` on dev):**
> - **Live cost polling on RunDetail Overview tab** (`RunDetailTabs.tsx`): the `liveCost`/`liveTokens` `useEffect` now polls `/runs/{id}/cost` every 5s while the run is non-terminal. Was firing once on mount + only re-firing on `run.status` change, so the cost chip stayed frozen at mount-time snapshot through the entire WORKING phase. The polling interval is cleared on unmount or status flip to terminal.
> - **Project Chat panel rebuild** (`ProjectChatPanel.tsx`):
>   - Layout fix: hardcoded `h-[600px]` → `h-full min-h-0`. `ProjectDashboardPage` main pane now opts out of padding/overflow on the chat tab so the panel fills the pane without double-scroll.
>   - Slim header (only when `messages.length > 0`) with **Compact** + **Reset** buttons grouped on the right. Reset clears history (`DELETE /api/projects/{id}/chat`); Compact summarises older N-tail messages into one ASSISTANT-voice `[Summary]` message and keeps the last 6 verbatim (`POST /api/projects/{id}/chat/compact`, returns 409 if <10 messages).
>   - Hover-revealed `Copy` / `Check` button below every bubble — copies the raw markdown source for assistant replies (so code blocks paste cleanly elsewhere). Confirmation flips to `Copied` for 1.5s.
>   - **Launch-from-chat redirect**: was navigating to `/runs/{runId}` (route doesn't exist in current router → fell through to landing page). Now routes to `/projects/{projectId}/dashboard` with `state.launchedRunId` for downstream use.
>   - **Per-launch branch input** on the Launch modal — applied to every selected repo. Use cases: re-launch on a PR branch to address review comments, or stack a staged launch onto an in-progress branch.
> - **Tooltip primitive** (`src/components/shared/Tooltip.tsx`, NEW): CSS-only, 250ms delay, hover/focus reveal, hide on leave/blur, `text` falsy → renders children unchanged. Available for migration off native `title=""` to a styled tooltip.
> - **Sidebar tooltip gaps** (`Sidebar.tsx`): mobile-close (X icon) and desktop-collapse (ChevronLeft icon) buttons were missing both `aria-label` and `title`. Patched. (Audit found these were the only icon-only chrome that lacked tooltips — every other icon-only button in the chat panel, ProjectMemoryPanel, ArchitectureViewer, NotificationPanel, RunDetailTabs already had `title=""` or visible text.)
> - New API helpers in `lib/api.ts`: `clearProjectChat(projectId)`, `compactProjectChat(projectId)`.
>
> **v2.5.12 (design primitives + Generate Docs UI + per-repo arch hooks):** new shared primitives in `src/components/shared/`:
> - `Button.tsx` — `class-variance-authority` (cva) variants (primary/secondary/tertiary/ghost/danger/success) × sizes (sm/md/lg/icon) + loading state with spinner.
> - `Checkbox.tsx` + `Radio.tsx` — native input wrapper that hides OS defaults and renders consistent dark-theme controls with optional label + description.
> - `ResponsiveModal.tsx` hardened: focus-trap (Tab/Shift+Tab cycling), Escape to close, body scroll lock, `disableDismiss` + `hideCloseButton` props, role=dialog + aria-modal + aria-label.
> - `EstimateChip.tsx` — debounced launch-cost estimate chip shared between `ExecutionSetup` (project Launch) and `NewRunModal` (when a project is selected).
>
> Migrated to use the new primitives (~9 modals): `GrantRunsModal`, `AdminInviteModal`, `ChangePasswordModal`, `ContactModal`, `UpgradeModal`, `DisclaimerModal`, `AddRepoModal`, `FailureDecisionModal`, `NewRunModal` (radios + checkboxes + Launch button).
>
> New panel: `src/components/project/GeneratedDocsPanel.tsx` — two `DocRow` cards (EXPLANATION + SETUP) on Project Overview tab. Generate / Regenerate / inline View (`MarkdownContent` with 400px scroll) / Download `.md`. Driven by new API helpers in `lib/api.ts`: `generateProjectDoc(projectId, type)`, `getProjectDoc(projectId, type)` (returns null on 204), `downloadProjectDoc(projectId, type)` (blob URL → anchor click → `<type>.md`).
>
> Honest scoring fixes: `RunDetail.tsx` top banner + `ScoreCard.tsx` header now prefer `verifiedScore` (deterministic V106) over `accuracyScore` (LLM, sometimes hallucinates). LLM `Est. N` shown as a subdued secondary when it disagrees. `ScoreCard` adds an "honest scoring" helper sentence when all non-null dimensions equal the anchor (no review issues = flat bars are arithmetically correct, not a stuck default).
>
> **v2.5.10 (verified score + honest PARTIAL):** new `src/components/runs/SignalBreakdown.tsx` — compact horizontal chip `Build ✓ · Tests 73/73 · Reviewer CLEAN · Verified 92 · Est. 97` surfaced below `ValidationReportPanel` (each segment colour-coded). `StatusBadge` (`src/components/shared/StatusBadge.tsx`) gains a native HTML `title` tooltip mapping `statusReason` values to human copy (CLEAN_COMPLETION / CLEAN_WITH_INTERMEDIATE_WARNINGS / FINAL_BUILD_FAILED / TESTS_FAILED:N_OF_M / REVIEWER_UNRESOLVED / REVIEWER_FAILED / ALL_TASKS_FAILED / LEGACY_UNKNOWN). `ValidationReportPanel` primary badge now shows `V {verifiedScore}/100` when present (deterministic); `accuracyScore` demoted to `Est. N/100` secondary badge. `DISPLAY_NAMES['PARTIAL']='Partial'` added (was falling through to upper-case). New fields on `lib/api.ts`: `RunSummary.statusReason`, `ValidationReportData.{finalBuildPassed, testsPassed, testsTotal, reviewerVerdict, verifiedScore}`. No tooltip library added.
>
> **v2.5.11 (Validation v3 / Pillar 1, PR #21 — backend only):** no frontend changes yet. Pillar 5 (upcoming) will add `src/components/runs/RequirementCoverageTable.tsx` inside the expanded `ValidationReportPanel` rendering each `RequirementEvidence` row (req id | status | impl cite | test cite | note) and extend `SignalBreakdown` with a `Coverage N/M` segment. Gated behind `validation.v3-strict` flag.
>
> v2.5.5 — Batch 7: `ArchitectureViewer` gains a source-file-changes section under the text diff (per-repo Added/Modified/Removed/Renamed with ±LoC, `getArchitectureDiff`). — Batch 6: `FailureDecisionModal` rewrite (button grid + blocked-downstream list); new `ResetWorkspaceSheet` with SHA preview; `PostMergeBar` stacks up to 2 full rows + "+N more merged" chip (backend list changed to `PostMergeBar[]`); estimate chip has a skeleton pulsing state + basis tooltip. — Batch 5 UX polish: `ExecutionStepper` with friendly labels + elapsed time; new `RepoChip`/`extractRepoShortName` on `TaskCard`; new `StalenessWarning` in `ExecutionSetup` (non-blocking amber block + Analyse CTA); `TechDebtPanel` upgraded to recharts horizontal `BarChart` with color-coded categories + severity chips. — Batch 4: `TechDebtPanel` on Project Overview (issues-by-category/severity + file hotspots, `getTechDebt`); `TemplatePicker` dropdown above the requirement textarea in `ExecutionSetup` (`listSpecTemplates`/`createProjectSpecTemplate`/`deleteSpecTemplate`); TDD mode checkbox in `NewRunModal` (threads `tddMode: boolean` into `RunRequest`); `CancellationPanel` renders live cleanup steps on the ABORTED execution view (`useExecutionStore.compensations` slice fed by EXEC_COMPENSATION_UPDATE/_DONE SSE events). — Batch 3: `WorkspaceHealthPanel` on Project Overview (origin-SHA drift + webhook + open-PR chips, cached 10 min in Redis via `getWorkspaceHealth`); `PostMergeBar` above the tab strip renders a "✓ PR merged — Start next requirement →" banner polled on mount (`getPostMergeBar`, `dismissPostMergeBar`); `ExecutionSetup` shows a debounced `~M–N tasks · ~$X–$Y` chip below the requirement textarea (`estimateRun`, no LLM call). — Batch 2: "Reset to run state" button on `InlineRunDetail` (`syncRunToOriginal`), webhook status chip + "Install webhook" button on `RepoCard` (`installRepoWebhook`), "Reset workspace" button on `ProjectRepoManager` (re-analyses all linked repos). New `webhookInstalled: boolean` on `ProjectRepo`. — v2.5.1: PR refresh button (`ProjectPrRollup`), Architecture diff + branch input (`ArchitectureViewer`), Run Sync from branch (`InlineRunDetail`), persistent Cancel on Launch header + Resume button on `ExecutionMonitor`, `ExecutionSetup` shows repo githubRepoFullName instead of UUID, new API helpers `refreshProjectPrs` / `analyseProject(branch)` / `syncRun` / `executionApi.resume`)
>
> v2.5.0 (Project Workspace + RAG): `ProjectDashboardPage` gains a Docs tab driven by new `ProjectDocumentsPanel` (drag-drop upload, async status, delete); Overview tab gains a Project Summary panel + Analyse button (`getProjectSummary` / `analyseProject`); `ArchitectureViewer` gets a "Diff vs v{N-1}" button + LCS line-diff renderer; `text-[12]` body rows on Project components normalised to `text-[11]` to match Run/Pro view density; new API helpers `listProjectDocuments` / `uploadProjectDocument` / `deleteProjectDocument`)

React 18 + Vite 5 + Tailwind CSS 3.4 + TypeScript 5.3

## Structure

### Pages (`src/pages/`)
- **Standalone** (15): DashboardPage, RunsPage, BillingPage, SettingsPage, DeploymentsPage, IntegrationsPage, SharedReportPage, AccountingPage, AuditPage, PricingPage, PlansPage, DpaPage, InvoicesPage, PrivacyPage, TermsPage
- **`admin/`** (6): AdminPage, AdminInviteModal, FeatureFlagsPage, GrantRunsModal, OrgAdminDashboard, PricingManager
- **`auth/`** (7): LoginPage, RegisterPage, ForgotPasswordPage, ResetPasswordPage, VerifyEmailPage, AuthCallbackPage, AcceptInvitePage
- **`org/`** (2): OrgProjectsPage, OrgSettingsPage
- **`project/`** (1, pilot v3.0; v2.6.4 IA cleanup): ProjectDashboardPage — route `/projects/:projectId/dashboard`. **4 top tabs (Pulse / Runs / Code / Chat) + More drawer** (was 6 tabs + 9 sub-tabs). Reads `useProjectStore`.

### Components (`src/components/`)
- **`runs/`** — RunDetail, RunDetailTabs (batch badge + per-run PR list), RunCard (batch badge), RunList, RunConversation, NewRunModal (now: project-linked multi-repo select + Figma URL), TaskCard, TaskGraph (subtask grouping), PreviewTab ("Run Tests" button, Beta), LiveLog, ValidationReportPanel (now: ScoreCard at top), TestReportPanel, TestProgressTracker, ProviderModelSelector, FirstRunSuggestions, VibeProgressNarrative, VibeRunSummary, **AssessmentPanel** (pilot R4 — appears above the plan-approval gate when `RunDetail.assessmentResult` is set), **ScoreCard** (pilot R6 — 6 colored progress bars + overall accuracy)
- **`project/`** (pilot v3.0 + v2.2.0 + v2.5.12 + **v2.6.4 IA cleanup**) — ProjectRepoManager, RepoCard (scope chip), AddRepoModal (Pick existing / Create new — migrated to ResponsiveModal v2.5.12), RepoRelationshipEditor, StackBadge, ArchitectureViewer (markdown + version dropdown + amber stale badge driven by `getArchitectureStaleness`), ProjectTaskList, ProjectPrList, ProjectScoreTrend (recharts LineChart, overall + per-dimension), **ProjectMemoryPanel** (v2.2.0), **GeneratedDocsPanel** (v2.5.12), TechDebtPanel, WorkspaceHealthPanel, PostMergeBar, ResetWorkspaceSheet, **PulseTab** (v2.6.4 — last-run hero + verified-score ring + health rollup + activity preview), **CodeTab** (v2.6.4 — Architecture / Repos / Health / Tech debt / Standards / Contracts inline), **MoreDrawer** (v2.6.4 — slide-over for Settings / Generated Docs / Memory / Activity / Documents / Team), **RunStamp** (v2.6.4 — 1-line shareable summary + share-link + embed HTML, wired into ValidationReportPanel via stampMeta), **EmptyProjectLanding** (v2.6.4 — single-textbox empty state + sample-spec gallery + share-link continue)
- **`run/`** — FileTree, FileViewer (RunTimeline removed — V2 has no COMPILING/MERGING/AWAITING_MERGE phases)
- **`layout/`** — AppLayout, Header, Sidebar, CommandPalette, NotificationBell, NotificationPanel
- **`dashboard/`** — ActivityFeed, AgentCommGraph, DateRangeFilter, MetricCard, ModelComparisonChart, TokenBubbleChart, UserRunsChart
- **`billing/`** — BillingProfileForm, DisclaimerModal, PlanCard, RazorpayCheckout, RazorpayOrderCheckout, UpgradeNudge, UsageBadge
- **`deployments/`** — DeployModal, DeployWizardModal, DeploymentCard
- **`pricing/`** — PricingTable, FeasibilityEstimator
- **`integrations/`** — IntegrationCard, IntegrationLogo
- **`shared/`** — AnimatedCounter, EmptyState, GlassPanel, GlowDot, MarkdownContent, SkeletonLoader, StatusBadge, Toast, ViewModeToggle, ResponsiveModal (v2.5.12 hardened), **Button** (v2.5.12 cva primitive), **Checkbox** (v2.5.12), **Radio** (v2.5.12)
- **`ui/`** — LockedFeature, UpgradeModal
- **`auth/`** — Auth-related UI components

### Hooks (`src/hooks/`)
- `useSse.ts` — SSE event stream hook (generic handler, auto-parses all event types). Fetches scoped SSE token per-runId before connecting (falls back to full JWT).
- `useViewMode.ts` — Toggle between standard and Vibe view modes
- `useKeyboardShortcuts.ts` — Global keyboard shortcut registration
- `useKeyboardScroll.ts` — Keyboard-driven scroll navigation
- `useIsMobile.ts` — Responsive breakpoint detection
- `useApi.ts` — API call wrapper with error handling
- `useTierPermissions.ts` — Tier-based feature gating

### Lib (`src/lib/`)
- `api.ts` — API client (axios-based, JWT auth). Includes `getSseToken(runId)` for scoped SSE tokens.
- `billing-api.ts` — Billing/subscription API client
- `invoice-api.ts` — Invoice API client
- `telemetry.ts` — Client-side telemetry/analytics
- `techDetect.ts` — Tech stack detection from file patterns
- `vibeNarrative.ts` — Narrative text generation for Vibe view
- `monacoSetup.ts` — Monaco editor initialization
- `monacoGeistTheme.ts` — Custom Monaco theme
- `constants.ts` — App-wide constants
- `utils.ts` — Shared utility functions

### Stores (`src/stores/` — Zustand)
- `useAuthStore.ts` — Auth state, JWT token management
- `useRunStore.ts` — Run list and active run state. `createRun` now accepts `selectedRepoIds` + `figmaUrl`.
- `useSseStore.ts` — SSE connection state and event buffering
- `useMetricsStore.ts` — Dashboard metrics and analytics data
- `useUiStore.ts` — UI preferences, sidebar state, modals
- `useProjectStore.ts` — pilot v3.0: per-project dashboard, repos, architecture doc, score trend. Exposed actions: `fetchDashboard`, `fetchRepos`, `fetchAvailableRepos`, `fetchArchitectureDoc`, `fetchScoreTrend`, `reset`.

### Types (`src/types/`)
- `project.ts` — pilot v3.0: `ProjectRepo`, `ProjectRepoLink`, `RepoScope`, `StackInfo`, `ProjectDashboardDTO`, `ScoreEntry`, `PrSummary`, `TaskSummary`, `ArchitectureDoc`, `AssessmentResult`, `LinkRepoRequest`.

## Key Components
- **RunDetail.tsx** — Main run view with Vibe View toggle. Shows gate banners (with credit count for multi-credit runs), task list, review, report, cost estimate. Plan approval sends `creditsConfirmed` to prevent stale approvals (409 on mismatch). Completed-run review banner surfaces the validation-report `accuracyScore` (colored by threshold: ≥90 green, ≥75 amber, else red); V1 `recommendation`/`recommendationCompat` fields no longer rendered. Batch UI (`batchAvailable`, dual-option cards, IN_PROGRESS_BATCH/QUEUED_BATCH progress) is still wired on the client but V2 backend does not produce batch runs yet — re-activates once the V2 batch plan ships.
- **NotificationBell.tsx** — Header notification bell with unread count badge. Polls every 30s.
- **NotificationPanel.tsx** — Dropdown notification list. Marks read, navigates to run on click.
- **RunDetailTabs.tsx** — Tabbed detail view. Overview tab shows GitHub PR link (`githubPrUrl`), per-copilot-run token breakdown, batch/realtime badge. Graph tab uses `TaskGraphGroups` to split copilot/review nodes into separate collapsible sections.
- **ProviderModelSelector.tsx** — AI provider selection (Anthropic/Gemini/OpenAI) for run submission.
- **CommandPalette.tsx** — Global command palette (keyboard shortcut activated).
- **Vibe* components** — VibeRunSummary, VibeProgressNarrative: simplified UX for non-technical users.
- **FileTree.tsx / FileViewer.tsx** — Workspace file browsing and viewing with Monaco editor. `.md` files show a Preview/Source toggle: Preview renders via `MarkdownContent`, Source shows Monaco.
- **TestProgressTracker.tsx** — Real-time test execution progress display.
- **DeployWizardModal.tsx** — Multi-step deploy configuration wizard.
- **FeasibilityEstimator.tsx** — Pre-run feasibility and complexity estimation.

## Patterns
- SSE events flow through generic `onmessage` handler — no per-type wiring needed
- Event type checks use SCREAMING_SNAKE_CASE (matches backend)
- Review display on COMPLETE runs uses `ValidationReport.accuracyScore` (fetched via `getValidationReport` + refreshed on `VALIDATION_REPORT_READY` SSE event). V1 `recommendation`/`overallConfidence` path retired.
- Toast notifications for SSE events (compaction, model escalation)
- Pagination: run list uses `PagedResponse<RunSummary>` with page/size/totalElements
- Task graph groups: nodes grouped by `_cop{N}_`/`_rev{N}_` prefix into separate collapsible sections
- **Subtask graph grouping:** Subtask nodes (with `parentTaskId`) rendered as smaller nodes (164×36) grouped under parent with dashed border, aggregate status dot, progress bar ("N/M"), and expand/collapse chevron. Uses variable-height layout — groups compute height based on collapsed/expanded state. Kahn's topological sort only operates on standalone + group parent nodes. Backward compatible: no `parentTaskId` = no groups = same as before.
- `useSse` preserves the live-log buffer when the hook remounts for the SAME runId (only clears on a genuine run switch) so the log doesn't vanish when a run flips to COMPLETE and RunDetail re-renders.
- SSE refetch triggers: V2 statuses (PLANNING, AWAITING_APPROVAL, WORKING, REVIEWING, COMPLETE, FAILED, CANCELLED) + legacy batch statuses (QUEUED_BATCH, IN_PROGRESS_BATCH) retained in `useSse.ts` for the upcoming V2 batch path. The V1 `COMPILING`/`MERGING`/`AWAITING_MERGE` strings still appear in the refetch list but are dead — V2 backend never emits them.
- Gap tasks (`_gap_` in taskId) excluded from main task counters in RunDetail and RunDetailTabs. Shown collapsed under "Gap fixes (background)", deduplicated by title to suppress retry duplicates.
- RunDetail API includes `githubPrUrl` field — displayed as clickable link in overview tab
- RunDetail API includes `creditsCharged` and `hardCapUsd` — displayed at plan approval gate for multi-credit runs
- `approveRun()` sends `creditsConfirmed` echoing the credit count shown in UI — backend returns 409 if pricing changed since plan
- `approveRun()` also sends `executionMode` ('REALTIME' or 'BATCH') when user selects execution path
- `runTests(runId)` triggers manual test execution (POST /runs/{runId}/run-tests)
- `RunSummary` includes `approvedExecutionMode` — used for batch badge on RunCard and RunDetailTabs
- Batch SSE events: BATCH_PROGRESS, BATCH_COMPLETE, BATCH_FAILED trigger `fetchRun()` refetch
- Notification API: `getNotifications()` (paginated), `getUnreadCount()`, `markNotificationRead()`, `markAllNotificationsRead()`
- OAuth callback handling via `AuthCallbackPage` (redirect-based flow)
- Razorpay checkout integration for subscriptions and order payments
- Feature flag admin via `FeatureFlagsPage` (SUPER_ADMIN only)

## V2 migration notes (backend deleted V1 on 2026-04-13)

The following client endpoints now return **HTTP 410 Gone** on V2 runs. UI call sites still exist and need a follow-up sweep to hide/remove them (tracked in `docs/plans/harness-v2-remaining-work.md` Gap 3):
- `POST /runs/{id}/clarify` · `/chat` · `/apply` · `/iterate` (V2 chat/iterate flow not specced)
- `POST /runs/{id}/merge` · `/reopen-merge` (permanent — V2 auto-merges per task)
- `POST /runs/{id}/retry` · `/pause` · `/resume` (blocked on V2 full-replay recovery)
- `POST /runs/{id}/rework` · `/implement-suggestions` · `/edit-plan`
- `POST /api/feasibility` (permanent — use plan-approval estimate)

`POST /runs/{id}/tasks/{taskId}/retry` and `POST /runs/{id}/retry-validation` are **live** under V2.

## Build
```bash
npm --prefix frontend run build
npm --prefix frontend run dev  # dev server on :5173
```
