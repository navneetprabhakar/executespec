# frontend/ — React Dashboard

> Last updated: 2026-04-09

React 18 + Vite 5 + Tailwind CSS 3.4 + TypeScript 5.3

## Structure

### Pages (`src/pages/`)
- **Standalone** (15): DashboardPage, RunsPage, BillingPage, SettingsPage, DeploymentsPage, IntegrationsPage, SharedReportPage, AccountingPage, AuditPage, PricingPage, PlansPage, DpaPage, InvoicesPage, PrivacyPage, TermsPage
- **`admin/`** (6): AdminPage, AdminInviteModal, FeatureFlagsPage, GrantRunsModal, OrgAdminDashboard, PricingManager
- **`auth/`** (7): LoginPage, RegisterPage, ForgotPasswordPage, ResetPasswordPage, VerifyEmailPage, AuthCallbackPage, AcceptInvitePage
- **`org/`** (2): OrgProjectsPage, OrgSettingsPage

### Components (`src/components/`)
- **`runs/`** — RunDetail, RunDetailTabs (batch badge), RunCard (batch badge), RunList, RunConversation, NewRunModal, TaskCard, TaskGraph (subtask grouping), PreviewTab ("Run Tests" button, Beta), LiveLog, ValidationReportPanel, TestReportPanel, TestProgressTracker, ProviderModelSelector, TechBadges, FirstRunSuggestions, VibeProgressNarrative, VibeRunSubmit, VibeRunSummary
- **`run/`** — FileTree, FileViewer, RunTimeline
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
- `useRunStore.ts` — Run list and active run state
- `useSseStore.ts` — SSE connection state and event buffering
- `useMetricsStore.ts` — Dashboard metrics and analytics data
- `useUiStore.ts` — UI preferences, sidebar state, modals

## Key Components
- **RunDetail.tsx** — Main run view with Vibe View toggle. Shows gate banners (with credit count for multi-credit runs), task list, review, report, cost estimate. Plan approval sends `creditsConfirmed` to prevent stale approvals (409 on mismatch). Dual-option cards when `batchAvailable` (real-time vs batch with savings badge). Batch progress bar for IN_PROGRESS_BATCH/QUEUED_BATCH runs.
- **NotificationBell.tsx** — Header notification bell with unread count badge. Polls every 30s.
- **NotificationPanel.tsx** — Dropdown notification list. Marks read, navigates to run on click.
- **RunDetailTabs.tsx** — Tabbed detail view. Overview tab shows GitHub PR link (`githubPrUrl`), per-copilot-run token breakdown, batch/realtime badge. Graph tab uses `TaskGraphGroups` to split copilot/review nodes into separate collapsible sections.
- **RunTimeline.tsx** — Pipeline status bar. `mapStatusToPhase()` maps all statuses including COMPILING, MERGING, AWAITING_MERGE.
- **ProviderModelSelector.tsx** — AI provider selection (Anthropic/Google/OpenAI) for run submission.
- **CommandPalette.tsx** — Global command palette (keyboard shortcut activated).
- **Vibe* components** — VibeRunSubmit, VibeRunSummary, VibeProgressNarrative: simplified UX for non-technical users.
- **FileTree.tsx / FileViewer.tsx** — Workspace file browsing and viewing with Monaco editor.
- **TestProgressTracker.tsx** — Real-time test execution progress display.
- **DeployWizardModal.tsx** — Multi-step deploy configuration wizard.
- **FeasibilityEstimator.tsx** — Pre-run feasibility and complexity estimation.

## Patterns
- SSE events flow through generic `onmessage` handler — no per-type wiring needed
- Event type checks use SCREAMING_SNAKE_CASE (matches backend)
- Review display uses `recommendationCompat ?? recommendation` for V2 compat
- Toast notifications for SSE events (compaction, model escalation)
- Pagination: run list uses `PagedResponse<RunSummary>` with page/size/totalElements
- Task graph groups: nodes grouped by `_cop{N}_`/`_rev{N}_` prefix into separate collapsible sections
- **Subtask graph grouping:** Subtask nodes (with `parentTaskId`) rendered as smaller nodes (164×36) grouped under parent with dashed border, aggregate status dot, progress bar ("N/M"), and expand/collapse chevron. Uses variable-height layout — groups compute height based on collapsed/expanded state. Kahn's topological sort only operates on standalone + group parent nodes. Backward compatible: no `parentTaskId` = no groups = same as before.
- SSE refetch triggers include COMPILING and MERGING statuses (useSse.ts)
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

## Build
```bash
npm --prefix frontend run build
npm --prefix frontend run dev  # dev server on :5173
```
