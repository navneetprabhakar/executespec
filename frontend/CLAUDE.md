# frontend/ — React Dashboard

> Last updated: 2026-04-14 (v2.3.0 — execution view: `pages/execution/ExecutionViewPage` + `components/execution/*` + `stores/useExecutionStore` + `hooks/useExecutionSse` + `lib/execution-api` + "New Execution" button on `ProjectDashboardPage`)

React 18 + Vite 5 + Tailwind CSS 3.4 + TypeScript 5.3

## Structure

### Pages (`src/pages/`)
- **Standalone** (15): DashboardPage, RunsPage, BillingPage, SettingsPage, DeploymentsPage, IntegrationsPage, SharedReportPage, AccountingPage, AuditPage, PricingPage, PlansPage, DpaPage, InvoicesPage, PrivacyPage, TermsPage
- **`admin/`** (6): AdminPage, AdminInviteModal, FeatureFlagsPage, GrantRunsModal, OrgAdminDashboard, PricingManager
- **`auth/`** (7): LoginPage, RegisterPage, ForgotPasswordPage, ResetPasswordPage, VerifyEmailPage, AuthCallbackPage, AcceptInvitePage
- **`org/`** (2): OrgProjectsPage, OrgSettingsPage
- **`project/`** (1, pilot v3.0): ProjectDashboardPage — route `/projects/:projectId/dashboard`. 5 tabs (Overview / Tasks / Repos / Architecture / Team). Reads `useProjectStore`.

### Components (`src/components/`)
- **`runs/`** — RunDetail, RunDetailTabs (batch badge + per-run PR list), RunCard (batch badge), RunList, RunConversation, NewRunModal (now: project-linked multi-repo select + Figma URL), TaskCard, TaskGraph (subtask grouping), PreviewTab ("Run Tests" button, Beta), LiveLog, ValidationReportPanel (now: ScoreCard at top), TestReportPanel, TestProgressTracker, ProviderModelSelector, TechBadges, FirstRunSuggestions, VibeProgressNarrative, VibeRunSubmit, VibeRunSummary, **AssessmentPanel** (pilot R4 — appears above the plan-approval gate when `RunDetail.assessmentResult` is set), **ScoreCard** (pilot R6 — 6 colored progress bars + overall accuracy)
- **`project/`** (pilot v3.0 + v2.2.0) — ProjectRepoManager, RepoCard (scope chip), AddRepoModal (Pick existing / Create new), RepoRelationshipEditor, StackBadge, ArchitectureViewer (markdown + version dropdown + amber stale badge driven by `getArchitectureStaleness`), ProjectTaskList, ProjectPrList, ProjectScoreTrend (recharts LineChart, overall + per-dimension), **ProjectMemoryPanel** (v2.2.0 — token-budget bar, compacted-history expandable, recent-run caveman-English entries; driven by `getProjectMemory`)
- **`run/`** — FileTree, FileViewer (RunTimeline removed — V2 has no COMPILING/MERGING/AWAITING_MERGE phases)
- **`layout/`** — AppLayout, Header, Sidebar, CommandPalette, NotificationBell, NotificationPanel
- **`dashboard/`** — ActivityFeed, AgentCommGraph, DateRangeFilter, MetricCard, ModelComparisonChart, TokenBubbleChart, UserRunsChart
- **`billing/`** — BillingProfileForm, DisclaimerModal, PlanCard, RazorpayCheckout, RazorpayOrderCheckout, UpgradeNudge, UsageBadge
- **`deployments/`** — DeployModal, DeployWizardModal, DeploymentCard
- **`pricing/`** — PricingTable, FeasibilityEstimator
- **`integrations/`** — IntegrationCard, IntegrationLogo
- **`shared/`** — AnimatedCounter, EmptyState, GlassPanel, GlowDot, MarkdownContent, SkeletonLoader, StatusBadge, Toast, ViewModeToggle
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
- **Vibe* components** — VibeRunSubmit, VibeRunSummary, VibeProgressNarrative: simplified UX for non-technical users.
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
