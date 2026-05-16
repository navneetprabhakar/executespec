# ExecuteSpec — User Manual

> Page-by-page reference. Each section answers: what the page does, which API endpoints it hits, and which tier sees it. Derived from `frontend/src/signal/pages/*.tsx` and `frontend/src/lib/api.ts`. Last refreshed 2026-05-16.

## How the app is shelled

The production chrome is `Signal` (`frontend/src/signal/components/Shell.tsx`). It has a left sidebar (desktop) / hamburger drawer (mobile) plus a bottom tab bar on narrow viewports. Sidebar groups: **Daily** (Today / All runs / New spec / Insights), **Workspace** (Projects / Library `BETA` / Migrations `BETA` / Templates / Deployments `BETA` when `canDeploy`), **Account** (Billing / Integrations / API keys [PRO+] / Team / Audit log [TEAM+ or SUPER_ADMIN]), **System** (Notifications / Search / Account / Admin [SUPER_ADMIN]).

V3 and V2 chromes are retained for legacy redirect catching only — every product surface listed below runs under Signal.

## Daily surfaces

### `/home` (HomePage.tsx)

The "Today" feed. Renders:

- Time-of-day greeting + clock topbar.
- A **`LiveHero`** card for the first non-terminal run found (statuses `CLASSIFYING` / `PLANNING` / `EXECUTING` / `RUNNING` / `AWAITING_APPROVAL` / `PROVIDER_SWITCHING`). Hero shows runId, truncated user request, tasks done/total, elapsed time, dollar spend, and two CTAs ("Plan" → `/plan/:runId`, "Watch live" → `/live/:runId`).
- Quad-grid quick cards: New spec → `/compose`, Projects (count) → `/projects`, All runs (with monthly cap surfaced — "3 / 5 this cycle" when permissions are loaded), Project chat (first project's chat page).
- "Recent runs" — last 6 runs from `getRuns({page: 0, size: 8})`. Each row prefers `runTitle` (agent-generated short summary) over `userRequest`. Score chip uses ≥85 / 70–84 / <70 banding.
- "Your projects" — first 3 active projects from `getProjects()`.

API calls: `api.getRuns({page: 0, size: 8})`, `api.getProjects()`, `api.getRun(liveSummary.runId)` for hero hydration. Re-polls every 5s while a live run exists, otherwise 30s.

Tier visibility: all tiers.

### `/compose` (ComposePage.tsx)

The spec submission surface. Fields:

- **Spec textarea** — the user's natural-language requirement. Max 50 000 chars (`RunRequest.userRequest`).
- **Provider chip** — Anthropic / OpenAI / Gemini. Defaults to the user's `defaultProvider` setting.
- **Project chip row** — "Sandbox (no repo)" plus every active project from `getProjects()`. Selecting a project loads its repos via `getProjectRepos(projectId)` and enables the multi-repo chip row below.
- **Repo selection** — toggle which `selectedRepoIds` the agent may touch. Defaults to all of them. Capped by `maxReposPerProject` (FREE 1 / PRO 2 / TEAM 5 / ENTERPRISE -1).
- **Cost & cap** — numeric `userCostCap` input. `tierMaxCap` resolves to FREE $3 / PRO+TEAM $25 / ENTERPRISE $1000 (effectively unlimited).
- **Estimate from project history** — when a project is selected and the spec is ≥12 chars, `api.estimateRun(projectId, spec)` returns p25/p75 task + cost predictions, debounced 600ms.
- **SpecChat** — embedded `SpecChat.tsx` ("Build my spec" copilot) that lets the user refine the spec via assistant chat before submission.
- **Monthly cap banner** — when `permissions.currentRunCount >= permissions.monthlyRunLimit`, surfaces "You've used all N runs in this billing cycle" with `/topup` and `/plans` links.

Submit calls `api.createRun({userRequest, aiProvider, executionMode: 'PRODUCTION', projectId?, selectedRepoIds?, userCostCap?})` and navigates to `/plan/:runId`.

Tier visibility: all tiers. FREE is blocked when at quota.

### `/plan/:runId` (PlanPage.tsx)

Plan-approval gate. Polls `api.getRun(runId)` every 2s while status ∈ {CLASSIFYING, PLANNING}. Auto-redirects to `/live/:runId` on `EXECUTING` or `/result/:runId` on `COMPLETED`.

Renders the plan summary, classifier verdict (SINGLE_SHOT / SMALL / MEDIUM / LARGE / LEGACY), task count, estimated credits, hard cap, and per-task list with complexity tags. Approve button calls `api.approveRun(runId, true)` and navigates to `/live/:runId`. Cancel calls `api.approveRun(runId, false)` and returns to `/compose`.

API: `api.getRun`, `api.approveRun`.

Tier visibility: all tiers. PRO+ may skip via `RunRequest.skipApproval=true` from external callers, but the Signal composer always uses `PRODUCTION`.

### `/live/:runId` (LivePage.tsx)

Live run view. Subscribes to SSE at `/api/runs/{id}/events?token=<jwt>` via the `useSse(runId)` hook so the live log, task progress, cost chip, and status badge update sub-second. Also background-polls `api.getRun(runId)` every 8s as a safety net for SSE drops.

Renders:

- `LiveHero` with phase label ("Classifying" / "Planning" / "Awaiting approval" / "Reviewing changes" / "Switching provider" / "Paused" / "Running now").
- `CostProgressBar` — green 0–80% / amber 80–100% / red ≥100% of `hardCapUsd`. Hidden when no hard cap is set.
- Tasks card — per-task status (todo / live / done), iter count, dollar spend.
- Live log pane with auto-scroll. Prefers SSE entries; falls back to polled `run.events`. Chip in the header shows SSE status (`live · streaming` / `connecting…` / `reconnecting…` / `polling`).
- Cancel icon button → confirms then `api.cancelRun(runId)`.

Auto-navigates to `/result/:runId` on `COMPLETED` or `PARTIAL`.

API: `api.getRun`, `api.cancelRun`, scoped SSE token via `/api/runs/{id}/sse-token`.

Tier visibility: all tiers.

### `/runs` (AllRunsPage.tsx)

Paginated run list. Initial load `getRuns({page: 0, size: 30})`; "Load more" appends `page + 1`. Tabs filter by computed status: All / Live (CLASSIFYING/PLANNING/EXECUTING/RUNNING/AWAITING_APPROVAL) / Shipped (COMPLETED + score ≥85 or null) / Needs (COMPLETED + score 70–84, or PARTIAL) / Failed (FAILED/CANCELLED) / Archived.

Row clicks route to `/live/:runId` for live statuses, `/result/:runId` otherwise.

API: `api.getRuns`.

Tier visibility: all tiers.

### `/result/:id` (ResultPage.tsx)

The terminal-run workbench. Loads `api.getRun(id)` + `api.getValidationReport(id)` in parallel. Tab strip:

- **Overview** — `ScoreCard` (verdict + verifiedScore + status / total cost / PR open), Signal Breakdown KV list (Build / Tests / Coverage / Reviewer / Accuracy LLM / Security / Architecture / Status reason). "What now" rows: Iterate on this run, Try another provider (re-run with `createRun(spec, aiProvider=OTHER, projectId)`), Preview deploy (FREE blocked with "PRO+ feature" label), Download report (PDF), Share read-only report (generates 30-day share token via `shareValidationReport(id)`), Download workspace (zip).
- **Tasks** — per-task list with retry button on FAILED / PARTIAL tasks (`api.retryTask(runId, taskId)`).
- **Timeline** — Gantt-style bars from `api.getTimeline(id)`.
- **Code** — `FileTree` + `CodeView` (Content/Diff toggle). Files from `api.getFilesWithStatus(id)`; content via `api.getFile(id, path)`; diff via `api.getFileDiff(id, path)`.
- **Cost** — total cost, hard cap, credits charged, estimate range, input/output tokens, elapsed, per-phase cost (classifier / outline / expansion / coherence).
- **PR** — if `prUrl` exists, `PrCard` with "Open on GitHub". Otherwise "Push to GitHub" button (`api.pushToScm(id)`).
- **Validation** — full report (build / tests / coverage / reviewer / security / architecture) + issues list with severity dots + "Download PDF" / "Retry".
- **Tests** — total / passed / failed / pass rate, root causes (with "Apply fix as new run" → `api.applyTestFixes(id, signature)`), suggestions, per-test rows. "Re-run tests" calls `api.runTests(id)`.
- **Chat** — turn-based run-scoped chat (`api.getRunChat`, `api.sendRunChatMessage`). "Iterate as new run" calls `api.iterateRun(id, message)` and routes to `/plan/:newRunId`.

API: `api.getRun`, `api.getValidationReport`, `api.getTimeline`, `api.getFilesWithStatus`, `api.getFile`, `api.getFileDiff`, `api.getTestReport`, `api.getRunChat`, `api.sendRunChatMessage`, `api.iterateRun`, `api.retryTask`, `api.applyTestFixes`, `api.runTests`, `api.startPreview`, `api.downloadValidationReportPdf`, `api.shareValidationReport`, `api.downloadWorkspace`, `api.pushToScm`, `api.createRun` (for try-another-provider).

Tier visibility: all tiers. Preview deploy disabled on FREE (`permissions.canDeploy === false`).

### `/insights` (ExtraPages.tsx `InsightsPage`)

Analytics dashboard. Time-range tabs (30d / 90d / YTD). Four quick-cards: avg verified score, avg run time, total spend, runs completed (shipped/total). Below that: Cost by provider (per-provider spend + cache hit rate), Run outcomes bar (Shipped / Needs review / Failed / Cancelled stacked), Most active user.

API: `api.getMetrics(days)`, `api.getModelComparison(days)`, `api.getUserRunsSummary(days)`.

Tier visibility: all tiers (data is scoped to caller's org / user via backend visibility spec).

## Workspace surfaces

### `/projects` (ProjectsPage.tsx)

Project picker grid. Tabs: Mine / Shared with me / Archived. Each card shows initials avatar, repo count, run count, open PR count, avg score (recency-weighted across last 10 runs — weights decay 1.0 → 0.46), spend, tech debt count, plus a status pill (Live / Healthy / New / Archived).

API: `api.getProjects()`, then per-project `api.getProjectDashboard(id)` + `api.getRuns({projectId, page:0, size:10})`. "New project" → `PromptModal` → `api.createProject({name, description?})`.

Tier visibility: all tiers. Project count capped at `maxProjectsPerOrg`.

### `/projects/:projectId` (ProjectComposePage.tsx, default sub-page)

Compose tab — same composer shape as `/compose`, scoped to this project (so `selectedRepoIds` defaults to all of the project's linked repos). Routes to `/plan/:runId` on submit.

### `/projects/:projectId/pulse` (ProjectPulsePage.tsx)

Last-run hero + ScoreRing, signal chips (tasks / build / tests / reviewer / cost), workspace health rollup (per-repo SHA drift + webhook status + open PR count via `api.getWorkspaceHealth(projectId)`), recent activity (`api.getProjectActivity` poll-refresh every 30s).

### `/projects/:projectId/repos` (ProjectReposPage.tsx)

Project's linked repos. Add repo / unlink / install webhook. `api.getProjectRepos`, `api.linkProjectRepo`, `api.unlinkProjectRepo`, `api.installRepoWebhook`.

### `/projects/:projectId/runs` (ProjectRunsPage.tsx)

Same shape as `/runs` but filtered to `projectId`. Includes a per-run "Update PR" affordance for runs with `alreadyPushed=true` (re-pushes after a per-task retry).

### `/projects/:projectId/code` (ProjectCodePage.tsx)

Sub-tabs (Browse / Tech debt / Code review / Health / Standards / Docs):

- **Browse** (default since 2026-05-16) — repo picker + `FileTree` + `CodeView`. Fetches `api.getProjectRepoFiles(projectId, repoId)` + `api.getProjectRepoFile(projectId, repoId, path)` (GitHub-proxied via user's PAT).
- **Tech debt** — `api.getTechDebt(projectId, sinceDays=30)`. Categories × severities × file hotspots. Per-category "Fix" → `api.dispatchTechDebtFix` → PATCH-mode run.
- **Code review** — latest whole-repo scan from `api.getLatestCodeReview(projectId, repoFullName)`. Severity chips ("N CRITICAL", "N MAJOR") → `api.codeReviewAutofix(scanId, severity)` → PATCH-mode run per severity.
- **Health** — per-repo coverage/lint/build pass status from `api.getWorkspaceHealth`.
- **Standards** — TEAM+ org compliance report from `api.getStandardsReport(projectId, orgId, sinceDays)`.
- **Docs** — Generated Docs (EXPLANATION + SETUP) from `api.getProjectDoc(projectId, 'EXPLANATION')` / `api.getProjectDoc(projectId, 'SETUP')`. Generate / Regenerate / View / Download.

### `/projects/:projectId/chat` (ProjectChatPage.tsx)

Full-height chat against `ProjectChatService`. Posts to `api.sendProjectChatMessage(projectId, message)`. Apply-suggestion launches a project-scoped run via `api.applyProjectChat`. Compaction summarizes older N messages keeping last 6 verbatim. Tier-gated: FREE has `maxChatMessages=0` (chat disabled).

### `/projects/:projectId/activity` (ProjectActivityPage.tsx)

Project activity feed. `api.getProjectActivity(projectId)` paginated (partitioned by month server-side). Poll refresh every 30s.

### `/projects/:projectId/members` (ProjectMembersPage.tsx)

Project member list + invite popover + public share-link popover. Cross-org invite UI gated by `org.externalSharingEnabled` (V198) AND tier PRO+ AND ORG_ADMIN-of-own-org (or SUPER_ADMIN). API: `api.getProjectMembers`, `api.addProjectMember`, `api.removeProjectMember`, `api.createProjectInvite` (V175), `api.createProjectShareLink`.

### `/projects/:projectId/architecture` (ProjectArchitecturePage.tsx)

Per-version architecture doc + diff viewer. `api.getProjectArchitecture(projectId)`, `api.getProjectArchitectureVersions(projectId)`, `api.getProjectArchitectureDiff(projectId, fromVersion, toVersion)`. "Regenerate" triggers the 3-step pipeline via `api.regenerateProjectArchitecture(projectId)`.

### `/projects/:projectId/settings` (ProjectSettingsPage.tsx)

Project name / description / default AI provider / GitHub owner / branch-naming pattern / target branch / default reviewers. Archive / restore / soft-delete buttons (V191).

### `/library` (ExtraPages.tsx `LibraryPage`) — `BETA`

Hardcoded grid of 6 readymade solutions (Stripe checkout, OAuth login, RAG with pgvector, GitHub Actions CI, Docker compose dev, Razorpay checkout). Each card → `/library/:slug` detail view. Detail (`LibraryDetailPage`) shows pre-filled spec + "Run this solution" CTA.

Sidebar entry tagged `BETA` (Shell.tsx).

### `/migrations` (ExtraPages.tsx `MigrationsPage`) — `BETA`

Grid of 6 migration recipes (JS→TS, Express→Fastify, Mongoose→Prisma, React class→hooks, Webpack→Vite, Pages→App Router). Detail (`MigrationDetailPage`) shows step-by-step + config card + "Kick off migration" CTA.

Sidebar entry tagged `BETA`.

### `/templates` (ExtraPages.tsx `SpecTemplatesPage`)

Project-scoped saved spec templates. `api.listSpecTemplates(projectId)` / `api.createProjectSpecTemplate` / `api.deleteSpecTemplate`. "Use" stores the body in sessionStorage and routes to `/compose`.

### `/deployments` (ExtraPages.tsx `DeploymentsPage`) — `BETA`

Permanent Cloud Run deployments list. `api.getDeployments` / `api.rollbackDeployment` / `api.deleteDeployment`. Sidebar entry gated on `canDeploy` and tagged `BETA`.

Tier visibility: PRO+ (FREE has `canDeploy=false`).

## Account surfaces

### `/billing` (BillingPage.tsx)

Plan card (current tier + lifecycle), payment history (`api.getPaymentHistory`), invoices (`api.getInvoices`), upgrade / change plan buttons. Tier-aware: PRO is HIDDEN on plan picker (V122).

API: `api.getMyOrg`, `api.subscribe`, `api.getPaymentHistory`, `api.getInvoices`.

### `/plans` (PlansPage.tsx)

Plan picker. Pulls live plans via `api.getBillingPlans` so HIDDEN tiers (PRO) are filtered server-side. Three-column responsive grid on desktop, stacked on mobile (PR #138 fix).

### `/topup` (MiscAccountPages.tsx `TopupPage`)

Top-up credit packages. `api.adminListTopupPackages` (falls back to a static set for non-admin). Picking a pack + clicking "Buy" POSTs `/api/billing/topup/purchase` which returns a Razorpay or Stripe checkout URL.

### `/integrations` (MiscAccountPages.tsx `IntegrationsPage`)

GitHub PAT + Bitbucket app-password + Slack OAuth install + JIRA / Confluence credentials. Each card has "Connect / Test / Disconnect" actions backed by `api.updateGithubSettings`, `api.validateGitHub`, `api.installGitHubApp`, `api.getSlackInstallUrl`, `api.updateJiraSettings`, `api.validateJira`, etc.

### `/api-keys` (MiscAccountPages.tsx `ApiKeysPage`)

BYOK provider keys (Anthropic / OpenAI / Gemini). Per-user via `api.updateAiKeySettings`, per-org via `api.updateOrgAiKeySettings`. `keySourceMode` toggle (`SYSTEM` / `HYBRID` / `OWN_KEYS`).

Tier visibility: PRO / TEAM / ENTERPRISE only (hidden on FREE in `Shell.tsx`).

### `/org-members` (MiscAccountPages.tsx `OrgMembersPage`)

Org member list + invite + role change + suspend / remove. `api.getOrgMembers`, `api.inviteMember`, `api.changeMemberRole`, `api.orgSuspendMember`, `api.removeMember`, `api.getOrgInvitations`, `api.resendInvitation`. TEAM tier seat-adjust via `api.adjustOrgSeats(newSeats)`.

### `/audit` (ExtraPages.tsx `AuditLogPage`)

Org-scoped audit trail. `api.getAuditLogs({page, size})` returns activity_audit rows with hash-chain seq. Filter tabs (All / Auth / Billing / Members / Admin). "Export filtered as CSV" button.

Tier visibility: TEAM+ or SUPER_ADMIN. The 403 message branches on tier+role (PR #138 fix) — FREE/PRO get upsell copy, TEAM+ non-admin get "ask your org admin", TEAM+ admin get "enable in Org Settings → Compliance".

## System surfaces

### `/account` (AccountPage.tsx)

User profile + theme toggle + sign out + GDPR data export (`/api/auth/me/data-export` returns a ZIP). Notification preference toggles (per event type × channel) via `api.getNotificationPreferences` / `api.updateNotificationPreference`.

### `/notifications` (MiscPages.tsx `NotificationsPage`)

In-app inbox. `api.getNotifications(0, 50)`, `api.markNotificationRead`, `api.markAllNotificationsRead`. Filter tabs (All / Run / Team / Billing / System). Each notification row deep-links by event type (PLAN_READY → /runs, BILLING/QUOTA → /billing, MEMBER/INVITE → /org-members, default → /home).

### `/search` (MiscPages.tsx `SearchPage`)

Command-palette-style search. ⌘K shortcut. Indexes the caller's last 50 runs + all projects. Enter on first hit navigates. Quick actions when empty.

### `/admin` (MiscPages.tsx `AdminPage`)

SUPER_ADMIN console. Platform snapshot (active orgs, signups 30d, runs 30d via `api.getActivationFunnel`). Tier lifecycle list with current `LifecycleState` (LISTED / HIDDEN / SUNSETTING). Feature flags read-only list. Operations row with "Reconcile orphan runs" → POST `/api/admin/executions/reconcile`.

Bounces non-SUPER_ADMIN users to `/home` on mount.

## Auth surfaces

### `/login`, `/register`, `/verify-email`, `/forgot-password`, `/reset-password` (AuthPages.tsx + ExtraPages.tsx)

Email + password login with OAuth2 (Google / GitHub) buttons. Register validates `password.length ≥ 12` and stores `signupSource` / `utmSource` / `utmCampaign` attribution. Email verify auto-sessions on success (returns LoginResponse with `accessToken`).

API: `api.authRegister`, `api.authLogin`, `api.authRefresh`, `api.authLogout`, `api.authVerifyEmail`, `api.authResendVerification`, `api.authForgotPassword`, `api.authResetPassword`.

### `/auth/callback/:provider` (AuthCallbackPage.tsx)

OAuth2 callback handler. Exchanges code for tokens via `api.oauthCallback(provider, code, state)` and routes to `/home`.

### `/accept-invite` (ExtraPages.tsx `AcceptInvitePage`)

Project / org invite acceptance landing. Token from URL `?token=`. `api.acceptProjectInvite(token)` or `api.acceptInvite(token)`.

## Public surfaces

### `/shared/:token` (MiscPages.tsx `SharedPage`)

Public read-only run report. `api.getSharedReport(token)`. No auth required (`SecurityConfig` whitelists `/api/shared/**`). Shows ScoreCard + spec + PR link. Tagged "shared report · public link" banner.

### `/trust` (ExtraPages.tsx `TrustPage`)

Public Trust & Security page. SOC 2 / DPA / SSO+SCIM / GDPR badges. "What we encrypt" / "What we never do" copy. Document download rows (SOC 2, DPA, sub-processors list, pen-test report, privacy policy).

### `/pricing` (PublicPricingPage.tsx in ExtraPages.tsx)

Public-facing pricing comparison. Shows FREE / SOLO / TEAM / ENTERPRISE cards. Note: the SOLO card in the static markup is leftover from the V176/V177 wave that was retired by V185 — the live tier ladder is FREE / PRO (HIDDEN) / TEAM / ENTERPRISE.

### `/legal/*` (LegalPages.tsx)

Privacy, terms, DPA, cookie policy. Static. No auth.

## Onboarding flow

### `/welcome` (path defined in `App.tsx`)

Three-path empty state (`ExtraPages.tsx EmptyProjectPage`):

1. Connect GitHub → `/integrations`
2. Pick a starter sandbox (`api.listOnboardingSamples` + `api.pickOnboardingSample(sampleId)`) — auto-provisions a sandbox project (V162 + V163 personal org for solo users) and seeds a sample spec.
3. Browse the library → `/library`.

## Mobile

Bottom tab bar (`Shell.tsx MOBILE_TABS`): Today (`/home`) · Runs (`/runs`) · New (`/compose`, styled as FAB) · Projects (`/projects`) · More (`/account`).

The "More" page (`MiscPages.tsx MorePage`) is the mobile collection of every link that doesn't fit in 5 tabs — Workspace, Account & billing, Team, Discover, Admin (SUPER_ADMIN).

## Beta surfaces

Three sidebar entries are explicitly tagged `BETA` in `Shell.tsx`:

- `/library` (28 readymade solutions — only 6 wired today)
- `/migrations` (11 migration recipes — only 6 wired today)
- `/deployments` (Cloud Run + Docker preview promotion — works on PRO+ but described as pre-GA)

Treat these as feature-flag-style surfaces: data is real but the UX is still iterating.
</content>
</invoke>