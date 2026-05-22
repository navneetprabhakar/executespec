# ExecuteSpec — Frontend UI feature inventory

> Generated 2026-05-22 by reading every file under `frontend/src/signal/` and `frontend/src/ide/`. Documentation deliverable; no source was modified. Legacy chromes (`components/`, `pages/`, `v3/`) are deliberately out of scope.

ExecuteSpec ships **two coexisting chromes**, selected by `useUiStore.uiMode` (`'signal' | 'ide'`, persisted to `localStorage['es:uiMode']`, applied to `<html data-ui>`; public flag `ui.ide.default` flips the cohort default). `App.tsx` renders a **mode-specific route subtree** via `protectedChildren(ide)` — each route uses its IDE-native component when `ide===true`, else the Signal page. Both chromes share the same router, the same `lib/api.ts` client, and the same Zustand stores.

**Routing facts that drive this inventory** (from `App.tsx`):

- Core surfaces are gated `ide ? <IdeX/> : <SignalX/>`: `/home`, `/runs`, `/compose`, `/plan(/:runId)`, `/live/:runId`, `/result/:runId`, `/runs/:runShort`, `/projects`, `/projects/:projectId` (+ all 9 sub-tab routes), `/projects/:projectId/runs/:runShort`, `/account`, `/settings`, `/settings/manage`, `/integrations`, `/admin` (bare).
- **Signal-only even in IDE mode** (no `ide ?` ternary — these always render the Signal page inside whichever shell is active): `/live` (bare, no runId), `/result` (bare), `/projects/empty`, `/billing`, `/plans`, `/topup`(→redirect), `/api-keys`, `/org-members`, `/org/settings`, `/insights`, `/library` (+`/:slug`), `/migrations` (+`/:slug`), `/templates`, `/audit`, `/deployments`, `/notifications`, `/search`, `/more`, `/admin/*` (sub-routes), and every public route (`/login`, `/register`, `/signup`, `/verify`, `/verify-email`, `/forgot-password`, `/reset-password`, `/accept-invite`, `/auth/callback`, `/welcome`, `/pricing`, `/trust`, `/terms`, `/privacy`, `/dpa`, `/shared/:token`, `/reports/shared/:token`, `/projects/snapshot/:token`).

---

# Signal UI feature inventory

The production chrome. Shell: `signal/components/Shell.tsx` (mobile top-bar + bottom `TabBar` + side drawer). Pages live in `signal/pages/**`. Reusable widgets in `signal/components/**` (`Compose`, `CodeView`, `FileTree`, `Markdown`, `Modal`, `Loaders`, `Primitives`, `SpecChat`, `CostProgressBar`, `BrandLogos`, `Glossary`).

## Home — `/home` (`pages/HomePage.tsx`)
- Time-of-day greeting top-bar; Search + Notifications icon buttons; avatar.
- Live-run hero card (`LiveHero`) when a non-terminal run exists — shows run id, request, tasks done/total, elapsed, spend; Plan + Watch-live actions. Polls every 5s while live, else 30s.
- Quick-action grid: New spec (⌘N), Projects (active count), All runs (monthly run cap `used/limit`), Project chat (first project).
- Recent runs list (last 6, score chip + cost, links to `/live` or `/result`).
- Your projects list (top 3, repo/run counts).
- **api:** `getRuns`, `getProjects`, `getRun`.

## Compose — `/compose` (`pages/ComposePage.tsx`)
- Spec textarea (`ComposerCard`) + submit.
- Provider chips: Anthropic / OpenAI / Gemini (+ OpenRouter chip only for ENTERPRISE).
- `SpecChat` "Build my spec" assistant (refine → apply into composer).
- Project picker chips (Sandbox or any project); repo-scoping chips (`selectedRepoIds`) when a project is chosen.
- Cost & cap: model, pause-for-approval note, editable spending-limit `userCostCap` (clamped to per-tier max: FREE $3 / PRO·TEAM $25 / ENTERPRISE ∞).
- p25/p75 task+cost estimate card (project history) — debounced.
- "Saved templates" row → `/templates`. At-cap warning card with top-up/upgrade links.
- **api:** `getProjects`, `getProjectRepos`, `estimateRun`, `createRun`.

## Plan — `/plan`, `/plan/:runId` (`pages/PlanPage.tsx`)
- Plan-approval gate. Polls run every 2s through CLASSIFYING/PLANNING; auto-redirects to `/live` (EXECUTING) or `/result` (COMPLETED).
- Plan summary, classification, task list (`TaskLine` with files + complexity), credits + cap hero, run-details KvList.
- Approve (charges credits) / Cancel buttons. Glossary tooltip on "classifier". Deep-link fallback when no runId.
- **api:** `getRun`, `approveRun`.

## Live — `/live`, `/live/:runId` (`pages/LivePage.tsx`)
- Real-time **SSE** event stream via `useSse(runId)` + `useSseStore`; falls back to polled `run.events`. Live-log pane (auto-scroll, last 100, `StreamCard` per tool/llm/warn/ok). Connection-status chip (streaming/connecting/reconnecting/polling).
- `LiveHero` with human phase label (Classifying/Planning/Awaiting approval/Reviewing/Switching provider/Paused/Running now); Raise-cap + Background actions.
- **`CostProgressBar`** spend-vs-hard-cap (green/amber/red) when hardCap set.
- Per-task list (`TaskLine` w/ iter + spend). Cancel run via `ConfirmModal`. Background poll every 8s for terminal transition.
- **api:** `getRun`, `cancelRun` (+ SSE token via the hook).

## Result — `/result`, `/result/:runId`, `/runs/:runShort`, `/projects/:projectId/runs/:runShort` (`pages/ResultPage.tsx`)
Flagship run-detail. Top-bar Download-workspace + Share icon buttons. **9 tabs** (`TabsStrip`):

| Tab | Status | Contents |
|-----|--------|----------|
| **Overview** | present | Plain-English verdict sentence (measured signals only); `ScoreCard` (verdict + verified score, ≥85 Shippable / 70-84 Needs review / <70 Failed); copyable submitted prompt; Signal-breakdown KvList (Build / Tests / Coverage / Reviewer / Accuracy(LLM) / Security / Architecture / status-reason / "Skipped" list — only measured rows render); "What now" rows: Iterate, **Try another provider** (re-run on the 2 other providers), Preview deploy (PRO+ gated), Download PDF, Share read-only, Download workspace |
| **Tasks** | present | Per-task `TaskLine`; failed/partial tasks get a Retry-task button |
| **Timeline** | present | Lazy `getTimeline`; per-task horizontal Gantt-style bars (start/width/duration, color by status) |
| **Code** | present | Lazy `getFilesWithStatus`; `FileTree` (folder tree) + `CodeView` with Content/Diff toggle (`getFile` / `getFileDiff`); Download-workspace button |
| **Cost** | present | Totals KvList (cost, hard cap, credits, estimate range, tokens, elapsed) + per-phase cost (Classifier/Outline/Expansion/Coherence) |
| **PR** | present | `PrCard` w/ Open-on-GitHub when PR exists; else Push-to-GitHub button |
| **Validation** | present | Report KvList (Build/Tests/Coverage Δ/Reviewer/Security/Architecture); Issues list (severity dot, file:line); Download PDF + Retry generation |
| **Tests** | present | Lazy `getTestReport`; summary KvList (total/passed/failed/pass-rate); Re-run tests; Root causes (w/ "Apply fix as new run"); Suggestions; per-test rows |
| **Chat** | present | Run-scoped chat (`getRunChat` / `sendRunChatMessage`), Markdown replies, "Iterate as new run" (summarizes convo via `conversationChat` → forks spec into `createRun`) |

- **api:** `getRun`, `getValidationReport`, `getTimeline`, `getFilesWithStatus`, `getFile`, `getFileDiff`, `getRunChat`, `sendRunChatMessage`, `conversationChat`, `getTestReport`, `runTests`, `applyTestFixes`, `retryTask`, `downloadValidationReportPdf`, `shareValidationReport`, `downloadWorkspace`, `retryValidationReport`, `startPreview`, `pushToScm`, `createRun`.

## All Runs — `/runs` (`pages/AllRunsPage.tsx`)
- Filter tabs with counts: All / Live / Shipped / Needs / Failed / Archived.
- Run rows (score chip + cost, status dot, relative time, links live/result). Load-more pagination (30/page).
- **api:** `getRuns`.

## Projects list — `/projects` (`pages/ProjectsPage.tsx`)
- Tabs: Mine / Shared with me / Archived (counts). New-project via `PromptModal` (name + description). Search icon button.
- Project cards (gradient initials avatar; repo/run/open-PR counts; recency-weighted avg score, spend, tech-debt; status pill New/Healthy/Live/Archived). Stats hydrated per-project from dashboard + recent runs.
- **api:** `getProjects`, `getProjectDashboard`, `getRuns`, `createProject`.

## Project Compose (project default tab) — `/projects/:projectId` (`pages/project/ProjectComposePage.tsx`)
- Default landing for a project. `ProjectTabs` strip. Share + More icon buttons.
- Live-run hero (if any). `ComposerCard` + provider chips + `SpecChat`. Repo-scoping chips. Estimate card. Recent-runs-in-project list. "This project" KvList + quick links (Pulse/Code/Chat/Settings).
- **api:** `getProjectRepos`, `getRuns`, `estimateRun`, `createRun` (+ `useProjectMeta`).

### Project tab strip (`pages/project/ProjectTabs.tsx`)
Shared across all project sub-pages: **Compose · Pulse · Repos · Runs · Code · Chat · Activity · Members · Architecture · Settings**. Counts (repos/runs/code/members) read from dashboard + members + repos, clamped ≥0.
- **api:** `getProjectDashboard`, `getProjectMembers`, `getProjectRepos`.

## Project · Pulse — `/projects/:projectId/pulse` (`pages/project/ProjectPulsePage.tsx`)
- Live-run hero. Metric grid: Avg score / Avg run time / Spent 30d / PRs merged 30d. Workspace-health repo rows (drift + webhook + open-PR). Open-PR `PrCard`s. Recent-runs list.
- **api:** `getProjectDashboard`, `getRuns`, `getProjectRepos`, `getWorkspaceHealth`.

## Project · Repos — `/projects/:projectId/repos` (`pages/project/ProjectReposPage.tsx`)
- Link-repo panel (`listRepos('ALL')`), per-repo cards: HEAD/branch, drift, webhook status, open PRs, architecture status. Actions: View architecture, Install webhook, Sync, Unlink (`ConfirmModal`).
- **api:** `getProjectRepos`, `getWorkspaceHealth`, `listRepos`, `linkProjectRepo`, `unlinkProjectRepo`, `installRepoWebhook`, `syncRepo`.

## Project · Runs — `/projects/:projectId/runs` (`pages/project/ProjectRunsPage.tsx`)
- **2 sub-tabs:** **Runs** (present — filter sub-tabs All/Live/Shipped/Needs/Failed + split-pane: list left, inline run detail right with `ScoreCard`+tasks+PR, "Open full page"/"Watch live") and **PRs** (present — open-PR cards w/ +/− diff, CI status chip, review verdict, comment count; Refresh-PR-snapshot; "Autofix review" via `fetchPrReviewComments`).
- **api:** `getRuns`, `getRun`, `getValidationReport`, `getProjectDashboard`, `refreshProjectPrs`, `fetchPrReviewComments`.

## Project · Code — `/projects/:projectId/code` (`pages/project/ProjectCodePage.tsx`)
**6 sub-tabs** (`TabsStrip`):

| Sub-tab | Status | Contents |
|---------|--------|----------|
| **Browse** | present (default) | Live source via `listRepoFiles` / `readRepoFile` (GitHub-proxied PAT); repo picker chips, repo overview card, `FileTree` + `CodeView` two-pane, Open-in-GitHub |
| **Tech debt** | present | `getTechDebt` category cards (crit/maj/min pills) + "Autofix N →" per category (`dispatchTechDebtFix`) |
| **Code review** | present | Per-repo latest scan (severity pills CRITICAL/MAJOR/MINOR/INFO) via raw `/api/code-reviews?latest`; New-scan via raw `/api/code-reviews/submit`; Download report (Markdown) |
| **Health** | present | `getWorkspaceHealth` per-repo coverage/build/lint/webhook grid |
| **Standards** | present | `getStandardsReport` org-standards violations (TEAM+) |
| **Docs** | present | Generated docs (EXPLANATION/SETUP): Generate/Regen/Download via `getProjectDoc` / `generateProjectDoc` / `downloadProjectDoc` |

- **api:** `getTechDebt`, `getProjectRepos`, `getProjectDoc`, `getWorkspaceHealth`, `getStandardsReport`, `listRepoFiles`, `readRepoFile`, `dispatchTechDebtFix`, `generateProjectDoc`, `downloadProjectDoc` (+ raw fetch for code-reviews).

## Project · Chat — `/projects/:projectId/chat` (`pages/project/ProjectChatPage.tsx`)
- Memory digest card (run count). Full chat thread (Markdown assistant bubbles), pinned composer. TEAM+ gate card on 403. Header actions: **Compile to spec** (`compileProjectChatToSpec` → prefill composer), Compact, Clear (`ConfirmModal`). Memory polls every 30s.
- **api:** `getProjectChatHistory`, `getProjectMemory`, `compileProjectChatToSpec`, `compactProjectChat`, `clearProjectChat` (+ raw `/api/projects/{id}/chat` POST).

## Project · Architecture — `/projects/:projectId/architecture` (`pages/project/ProjectArchitecturePage.tsx`)
**5 sub-tabs:** **Current** (present — arch doc Markdown + staleness pill + Regenerate), **Versions** (present — version list + **compare-versions diff picker** with changed-file list), **Invariants** (present — pass/fail per invariant), **ADRs** (present — ADR rows w/ status pills), **Contracts** (present — per-repo coverage detectors + known gaps + Rescan/Check-drift + project-wide drift).
- **api:** `getArchitectureDoc`, `getArchitectureVersions`, `listProjectInvariants`, `listProjectInvariantViolations`, `listProjectAdrs`, `getArchitectureStaleness`, `getProjectRepos`, `getContractCoverage`, `getArchitectureDiff`, `generateArchitecture`, `rescanContractCoverage`, `checkRepoDrift`, `checkProjectDrift`.

## Project · Members — `/projects/:projectId/members` (`pages/project/ProjectMembersPage.tsx`)
- Team list (ext-member EXT badge, role pills, remove via `ConfirmModal`). Invite-teammate card (gated by org `externalSharingEnabled` flag / SUPER_ADMIN; role chips PROJECT_VIEWER/DEVELOPER/ADMIN) via raw `/api/projects/{id}/invites`. Share-links section (create PROJECT_SNAPSHOT public link, copy, revoke) via raw `/api/projects/{id}/share-links`.
- **api:** `getProjectMembers`, `removeProjectMember`, `getMyOrg` (+ raw fetch for invites/share-links).

## Project · Activity — `/projects/:projectId/activity` (`pages/project/ProjectActivityPage.tsx`)
- Filter tabs All/Runs/PRs/Members/Standards. Event rows (type dot, actor, relative time). Export-filtered-CSV. Polls every 30s.
- **api:** `getActivityFeed`.

## Project · Settings — `/projects/:projectId/settings` (`pages/project/ProjectSettingsPage.tsx`)
**3 sub-tabs:** **Settings** (present — name/description/provider/target-branch/branch-naming/github-owner form via raw PATCH; metadata KvList; danger zone Archive/Delete via `ConfirmModal`), **Documents** (present — RAG upload/list/delete via `listProjectDocuments`/`uploadProjectDocument`/`deleteProjectDocument`), **Memory** (present — memory stats KvList + Compact + planner-memory digest).
- **api:** `archiveProject`, `deleteProject`, `listProjectDocuments`, `uploadProjectDocument`, `deleteProjectDocument`, `getProjectMemory`, `compactProjectMemory` (+ raw PATCH `/settings`).

## Account — `/account`, `/settings`, `/settings/manage` (`pages/account/AccountPage.tsx`)
- Profile card (tier/usage/role chips). Quick links: Billing, Top up, Compare plans, API keys (BYOK), Integrations, Org members + Org settings (admin only).
- **Preferences:** Theme (light/dark), **Interface** (Signal / IDE — BETA toggle via `setUiMode`), View mode (read-only).
- Plan-limits KvList. **Notifications** preference toggles (`getNotificationPreferences` / `updateNotificationPreference`). Security & data: change password, active sessions, **Export my data (GDPR)** (raw `/api/auth/me/data-export`). Sign out.
- **api:** `getNotificationPreferences`, `updateNotificationPreference`, `getMyPermissions`, logout (+ raw GDPR export).

## Billing — `/billing` (`pages/account/BillingPage.tsx`)
- Subscription summary (tier, seats, MRR, renewal). FREE→BYOK upsell card. This-cycle runs-used meter. Invoices list (raw `/api/billing/payment-history`). Payment-method card (Update — non-wired stub).
- **api:** `getAnalyticsBilling`, `getAnalyticsUsage` (+ raw payment-history).

## Plans — `/plans` (`pages/account/PlansPage.tsx`)
- Monthly/Yearly toggle. 3-col plan cards (FREE/TEAM/ENTERPRISE) from `getPricing` with feature bullets; current-plan + recommended badges; PRO grandfathered note. Subscribe via raw `/api/billing/subscribe` (ENTERPRISE → mailto sales).
- **api:** `getPricing` (+ raw subscribe).

## Top up — `/topup` (route redirects to `/billing`; page exists: `pages/account/MiscAccountPages.tsx::TopupPage`)
- Credit-pack picker (`adminListTopupPackages`, falls back to static set for non-admins); buy via raw `/api/billing/topup/purchase`. **Note:** the `/topup` route is a `<Navigate to='/billing'>` redirect, so `TopupPage` is not reachable directly (linked-to but dead in the router).
- **api:** `adminListTopupPackages` (+ raw purchase).

## Integrations — `/integrations` (`pages/account/MiscAccountPages.tsx::IntegrationsPage`)
- Rows by category: Source control (GitHub install/uninstall, Bitbucket connect), Notifications (Slack connect/disconnect), Issue trackers (Jira/Confluence connect). Per-provider credential `PromptModal`s; disconnect `ConfirmModal`s. Brand-logo marks.
- **api:** `getIntegrationStatus`, `getSlackStatus`, `getGitHubAppStatus`, `getSlackInstallUrl`, `getGitHubAppInstallUrl`, `disconnectSlack`, `uninstallGitHubApp`, `updateJiraSettings`, `updateConfluenceSettings`, `updateBitbucketSettings`.

## API keys (BYOK) — `/api-keys` (`pages/account/MiscAccountPages.tsx::ApiKeysPage`)
- Per-provider cards (Anthropic/OpenAI/Gemini): password input + Save&validate; FREE-tier gated. BYOK explainer.
- **api:** `validateAiKey`, `updateAiKeySettings`.

## Org members — `/org-members` (`pages/account/MiscAccountPages.tsx::OrgMembersPage`)
- Seats-used card + invite form (email + role DEVELOPER/BILLING_ADMIN/ORG_ADMIN). Member rows (role `<select>`, Remove via `ConfirmModal`). Pending-invites list (Resend).
- **api:** `getMyOrg`, `getOrgMembers`, `getOrgInvitations`, `inviteMember`, `resendInvitation`, `removeMember`, `changeMemberRole`.

## Org settings — `/org/settings` (`pages/LegalPages.tsx::OrgSettingsPage`)
- Profile (name + authorised domains). **SSO** (Google OIDC / Okta SAML / Azure AD SAML; metadata URL, login domain, enforce toggle; Save/Clear). **AI provider keys** (org-wide BYOK: Anthropic/OpenAI/Gemini + OpenRouter for ENTERPRISE; write-only inputs). **Model matrix** (F3 — per-tier × provider override `<select>`s, default fallback; ENTERPRISE-editable). **External sharing** toggle (V198). **Compliance** KvList (SOC2/GDPR/DPA/audit links).
- **api:** `getMyOrg`, `getSsoConfig`, `getOrgModelMatrix`, `updateOrgModelMatrix`, `updateOrg`, `updateSsoConfig`, `clearSsoConfig`, `updateOrgAiKeySettings`, `updateExternalSharing`.

## Admin — `/admin`, `/admin/*` (`pages/MiscPages.tsx::AdminPage`)
- SUPER_ADMIN only (redirects otherwise). Platform snapshot (active orgs, signups 30d, runs 30d from funnel). Tier-lifecycle rows. Feature-flags KvList (first 12, read-only). Operations: payment audit (stub row), webhook log (stub row), Reconcile-orphan-runs (raw POST).
- **api:** `fetchTiersLifecycle`, `adminListOrgs`, `adminListFeatureFlags`, `getActivationFunnel` (+ raw reconcile).

## Notifications — `/notifications` (`pages/MiscPages.tsx::NotificationsPage`)
- Filter tabs All/Run/Team/Billing/System. Notification rows (unread dot, severity tone, deep-link by type); Mark-all-read; per-row mark-read on click.
- **api:** `getNotifications`, `markNotificationRead`, `markAllNotificationsRead`.

## Search — `/search` (`pages/MiscPages.tsx::SearchPage`)
- ⌘K-style search box; client-side filter over loaded runs + projects. Quick-actions + Recent when empty. Enter opens first hit.
- **api:** `getRuns`, `getProjects`.

## More — `/more` (`pages/MiscPages.tsx::MorePage`)
- Catch-all navigation index (Workspace / Account & billing / Team / Discover / Admin sections). Surfaced via the mobile bottom `TabBar`.
- **api:** `getUnreadCount`, `getProjects`, `getRuns`.

## Insights — `/insights` (`pages/ExtraPages.tsx::InsightsPage`)
- 30d/90d/YTD toggle. Metric grid (avg score / avg run time / total spend / runs completed). Cost-by-provider KvList (+ cache hit %). Run-outcomes stacked bar (shipped/needs/failed). Most-active-user row.
- **api:** `getMetrics`, `getModelComparison`, `getUserRunsSummary`.

## Library — `/library`, `/library/:slug` (`pages/ExtraPages.tsx::LibraryPage` / `LibraryDetailPage`)
- **Static / hardcoded.** List = 6 hardcoded `LIBRARY_SOLUTIONS` cards (category tabs are non-functional placeholders). Detail = a single hardcoded Stripe-checkout page (does not read `:slug`; "Run this solution" links to `/plan`). **No api calls** despite `listLibrary`/`getLibraryDetail` existing in api.ts.

## Migrations — `/migrations`, `/migrations/:slug` (`pages/ExtraPages.tsx::MigrationsPage` / `MigrationDetailPage`)
- **Static / hardcoded.** List = 6 hardcoded `MIGRATIONS` cards. Detail = a single hardcoded JS→TS recipe (ignores `:slug`; links to `/plan`). **No api calls** despite `listMigrations`/`getMigrationDetail` existing.

## Templates — `/templates` (`pages/ExtraPages.tsx::SpecTemplatesPage`)
- Project picker chips. Template list (Use → prefill composer; Delete via `ConfirmModal`). New-template via `PromptModal` (name + body).
- **api:** `getProjects`, `listSpecTemplates`, `createProjectSpecTemplate`, `deleteSpecTemplate`.

## Audit — `/audit` (`pages/ExtraPages.tsx::AuditLogPage`)
- Filter tabs All/Auth/Billing/Members/Admin. Log rows (action, actor, target, timestamp). Tier/role-aware 403 messaging. Export-CSV.
- **api:** `getAuditLogs`.

## Deployments — `/deployments` (`pages/ExtraPages.tsx::DeploymentsPage`)
- Deployment rows (status dot, URL, environment). Open / Rollback / Destroy (both via `ConfirmModal`).
- **api:** `getDeployments`, `rollbackDeployment`, `deleteDeployment`.

## Trust / Empty project / Public pricing (`pages/ExtraPages.tsx`)
- **Trust** `/trust` — static compliance/security copy (SOC2/DPA/encryption), no api.
- **Empty project** `/projects/empty` — static onboarding (3 paths: link repo / sandbox / library), no api.
- **Public pricing** `/pricing` — static 4-tier card grid (FREE/SOLO/TEAM/ENTERPRISE), no api.

## Auth surfaces (`pages/AuthPages.tsx`, `AuthCallbackPage.tsx`, `ExtraPages.tsx`)
- **Login** `/login` — email/password + Google/GitHub OAuth (direct `/api/auth/oauth2/authorize/*`); decodes OAuth error bounces. **api:** `useAuthStore.login`.
- **Signup** `/register`, `/signup` — email/password + OAuth; derives names. **api:** `authRegister`.
- **Verify** `/verify`, `/verify-email` — magic-link verify or signup splash. **api:** `authVerifyEmail`.
- **Welcome** `/welcome` — 2-step sandbox-starter picker + GitHub-app install. **api:** `getGitHubAppInstallUrl`.
- **Forgot/Reset password** `/forgot-password`, `/reset-password`. **api:** `authForgotPassword`, `authResetPassword`.
- **Accept invite** `/accept-invite`. **api:** `acceptProjectInvite`.
- **Auth callback** `/auth/callback` — stores OAuth JWT, loads me, redirects. **api:** `useAuthStore.loadMe`.

## Shared (public, no auth) — `/shared/:token`, `/reports/shared/:token`, `/projects/snapshot/:token` (`pages/MiscPages.tsx::SharedPage`)
- Read-only shared report: `ScoreCard`, spec, PR card. **api:** `getSharedReport`.

## Legal — `/terms`, `/privacy`, `/dpa` (`pages/LegalPages.tsx`)
- Static copy-only pages. No api.

**Signal screen count: 47** (Home, Compose, Plan, Live, Result, All Runs, Projects list, Project Compose, Project Pulse, Project Repos, Project Runs, Project Code, Project Chat, Project Architecture, Project Members, Project Activity, Project Settings, Account, Billing, Plans, Top up, Integrations, API keys, Org members, Org settings, Admin, Notifications, Search, More, Insights, Library, Library detail, Migrations, Migration detail, Templates, Audit, Deployments, Trust, Empty project, Public pricing, Login, Signup, Verify, Welcome, Forgot/Reset password, Accept invite, Shared report, Legal).

---

# IDE UI feature inventory

VS Code-style chrome. Workspace shell: `ide/IdeWorkspace.tsx` (activity bar + Explorer + editor `<Outlet/>` + status bar + ⌘K palette; distinct mobile top-bar/drawer/bottom-tabs). Shared kit: `ide/IdeKit.tsx` (`IdeBadge`, `IdeCodeViewer` w/ diff+tokenizer, `IdeCommandPalette` + `usePaletteHotkey`, `IdeFileTree`, `FileIcon`/`FolderIcon`, `ResizeHandle`, `IdeLoader`, `statusKind`). Styling: `ide/ide.css` (VS Code Dark+/Light+ tokens via `[data-theme]`). The shell still imports `signal/signal.css` and reuses `signal/components/SpecChat` + `signal/components/Markdown`.

## IDE Workspace chrome — `ide/IdeWorkspace.tsx`
- **Activity bar:** Workspace / New spec / All runs / Projects + sidebar-toggle + Account. **Explorer:** Open (New spec, All runs), Recent runs (live `getRuns`), Projects (`getProjects`), System→Admin (SUPER_ADMIN). Resizable (persisted width). **Status bar:** branch, tier badge, ⌘K, theme cycle, **⇄ Signal UI** (switch back to Signal), "IDE · Beta".
- **⌘K command palette:** Go-to (Workspace/Runs/Projects), New spec, Toggle theme, Switch to Signal, + Open-run per recent run.
- Mobile: hamburger drawer + bottom tab bar.
- **api:** `getRuns`, `getProjects`.

## IdeWelcome — `/home` (`ide/IdeSurfaces.tsx::IdeWelcome`)
- IDE-native workspace home. New-spec / All-runs buttons. Recent-runs card grid (status badge, provider, time). Projects card grid.
- **api:** `getRuns`, `getProjects`.
- *vs Signal HomePage:* no live-run hero, no quick-action grid, no monthly-run-cap surface, no 5s live-poll.

## IdeCompose — `/compose` (`ide/IdeSurfaces.tsx::IdeCompose`)
- IDE-native. Spec textarea + `SpecChat`. Provider `<select>` (Anthropic/OpenAI/Gemini — **no OpenRouter**). Mode `<select>` (Production/Prototype). Submit → `/live`.
- **api:** `createRun`.
- *vs Signal ComposePage:* **no project picker, no repo-scoping, no `userCostCap` control, no estimate card, no at-cap warning, no templates link.** Has a Mode toggle Signal lacks (Signal compose is always PRODUCTION).

## IdeLive — `/live/:runId` (`ide/IdeSurfaces.tsx::IdeLive`) — bare `/live` falls back to Signal `LivePage`
- IDE-native, **polling only** (4s). Status badge + status reason + run summary + provider/worker-model KvList. "View result →" when terminal.
- **api:** `getRun`.
- *vs Signal LivePage:* **no SSE stream, no live-log pane, no `CostProgressBar`, no per-task list, no cancel button, no phase label.** Substantially thinner.

## IdePlan — `/plan`, `/plan/:runId` (`ide/IdePlan.tsx`)
- IDE-native plan gate. Polls 2s; auto-routes to live/result. Plan summary + strategy/tasks/credits/estimate/hard-cap KvList + numbered task list. Approve (charges `creditsCharged`) / Cancel.
- **api:** `getRun`, `approveRun`, `cancelRun`.

## IdeRunResult — `/result/:runId`, `/runs/:runShort`, `/projects/:projectId/runs/:runShort` (`ide/IdeRunResult.tsx`)
IDE flagship. **7 tabs** (`ide-rtabs`):

| Tab | Status | Contents | vs Signal |
|-----|--------|----------|-----------|
| **Overview** | present | Verdict badge + score/100; Signals card (Build/Tests/Reviewer/Issues/Files/Pipeline); Run card (status/provider/worker/cost/credits); submitted prompt; "What now": Open/Push PR, Preview, Report PDF, Share, Try-other-provider | parity-ish |
| **Code** | present | `IdeFileTree` + editor **tabs** (multi-file open/close) + `IdeCodeViewer` (diff for ADDED files); resizable | richer editor tabs than Signal; uses `getFiles`/`getFileDiff`/`getFile` |
| **Tasks** | present | Per-task rows (status badge, duration, provider/model/tokens, failure reason, Retry on failed/partial/blocked) | parity |
| **Validation** | present | Report KvList (pipeline/files/build-breakers/review-failures/issues) + Issues list (severity badge, file, stage, fix) + PDF | parity |
| **Cost** | present | Totals KvList + per-phase (Classifier/Outline/Expansion) | parity (no Coherence row) |
| **PR** | present | PR URL + Open-on-GitHub, else Push-to-GitHub | parity |
| **Tests** | present | Lazy `getTestReport`; passed/failed/errors summary + duration; Re-run; root-cause groups | thinner — **no per-test rows, no suggestions, no "Apply fix as new run"** |

- **Missing vs Signal Result:** no **Timeline** tab, no **Chat** tab.
- **api:** `getRun`, `getValidationReport`, `getTestReport`, `getFiles`, `getFileDiff`, `getFile`, `pushToScm`, `startPreview`, `downloadValidationReportPdf`, `shareValidationReport`, `createRun`, `retryTask`, `runTests`.

## IdeRuns — `/runs` (`ide/IdeMore.tsx::IdeRuns`)
- IDE-native flat run list (status badge, title, provider, time). 40/page, no pagination, no filters.
- **api:** `getRuns`.
- *vs Signal AllRunsPage:* no filter tabs/counts, no load-more.

## IdeProjects — `/projects` (`ide/IdeProjects.tsx`)
- IDE-native health cards (avg score / spend / tech-debt placeholder; repo/run/PR counts; status badge). Mine/Archived filter. Inline new-project (name only).
- **api:** `getProjects`, `getProjectDashboard`, `createProject`.
- *vs Signal ProjectsPage:* no "Shared with me" tab, tech-debt always "—", no description field on create.

## IdeProject (project workspace) — `/projects/:projectId` (`ide/IdeProject.tsx`)
- IDE-native project composer. `ProjectTabs` strip. Spec textarea + `SpecChat`, repo-scoping badges, provider + **mode** `<select>`s, Submit → `/live`. Recent-project-runs card grid.
- **api:** `getProject`, `getProjectRepos`, `getRuns`, `createRun`.
- *vs Signal ProjectComposePage:* no estimate card, no live-run hero, no "This project" KvList/quick-links; adds a Mode toggle.

### IDE project tab strip (`ide/IdeProjectTabs.tsx::ProjectTabs`)
Same 10 tabs as Signal: **Compose · Pulse · Repos · Runs · Code · Chat · Architecture · Members · Activity · Settings** (no per-tab counts).

## IdeProject · Pulse — `/projects/:projectId/pulse` (`ide/IdeProjectTabs.tsx::IdeProjectPulse`)
- Metric cards (avg score / avg run time / spent 30d / PRs merged 30d) + workspace-health repo rows (drift/webhook/open-PR badges).
- **api:** `getProjectDashboard`, `getWorkspaceHealth`.
- *vs Signal Pulse:* no live-run hero, no open-PR cards, no recent-runs list.

## IdeProject · Runs — `/projects/:projectId/runs` (`ide/IdeProjectTabs.tsx::IdeProjectRuns`)
- Flat project-run list (status badge, title, time).
- **api:** `getRuns`.
- *vs Signal Project Runs:* **no PRs sub-tab, no filter sub-tabs, no inline split-pane detail.**

## IdeProject · Repos — `/projects/:projectId/repos` (`ide/IdeProjectTabs.tsx::IdeProjectRepos`)
- Repo cards (drift/webhook/open-PR badges) + Sync / Install-webhook / Unlink.
- **api:** `getProjectRepos`, `getWorkspaceHealth`, `syncRepo`, `installRepoWebhook`, `unlinkProjectRepo`.
- *vs Signal Repos:* **no link-repo panel** (can't add a new repo from here — only sync/webhook/unlink existing); no architecture-status surface.

## IdeProject · Code — `/projects/:projectId/code` (`ide/IdeProjectMore.tsx::IdeProjectCode`)
- **Browse only.** Repo `<select>` + `IdeFileTree` + `IdeCodeViewer` two-pane (live source via `listRepoFiles`/`readRepoFile`).
- **api:** `getProjectRepos`, `listRepoFiles`, `readRepoFile`.
- *vs Signal Project Code:* **only 1 of 6 sub-tabs** — missing Tech debt, Code review, Health, Standards, Docs. (No `dispatchTechDebtFix`, no code-review scan, no workspace-health grid, no standards report, no generated-docs UI in IDE.)

## IdeProject · Chat — `/projects/:projectId/chat` (`ide/IdeProjectMore.tsx::IdeProjectChat`)
- Message thread (Markdown), composer, **Compile to spec**.
- **api:** `getProjectChatHistory`, `compileProjectChatToSpec` (+ raw `/chat` POST).
- *vs Signal Chat:* no memory-digest card, no Compact, no Clear, no TEAM+ gate card, no 30s memory poll.

## IdeProject · Architecture — `/projects/:projectId/architecture` (`ide/IdeProjectMore.tsx::IdeProjectArchitecture`)
- Architecture doc Markdown only.
- **api:** `getArchitectureDoc`.
- *vs Signal Architecture:* **only the "Current" view** — missing Versions/diff, Invariants, ADRs, Contracts (4 of 5 sub-tabs absent); no Regenerate button.

## IdeProject · Members — `/projects/:projectId/members` (`ide/IdeProjectMore.tsx::IdeProjectMembers`)
- Member rows (name/email/role badge, Remove).
- **api:** `getProjectMembers`, `removeProjectMember`.
- *vs Signal Members:* **no invite form, no share-links section, no external-sharing gate** (read+remove only).

## IdeProject · Activity — `/projects/:projectId/activity` (`ide/IdeProjectMore.tsx::IdeProjectActivity`)
- Event-feed rows (type, summary, actor). `sinceDays:30`.
- **api:** `getActivityFeed`.
- *vs Signal Activity:* no filter tabs, no CSV export, no 30s poll.

## IdeProject · Settings — `/projects/:projectId/settings` (`ide/IdeProjectMore.tsx::IdeProjectSettings`)
- Default-provider `<select>` + Save; Danger zone Archive/Delete (**`window.confirm`**, not the a11y `ConfirmModal`).
- **api:** `getProject`, `updateProjectSettings`, `archiveProject`, `deleteProject`.
- *vs Signal Settings:* **no Documents (RAG) sub-tab, no Memory sub-tab**; settings limited to provider (no name/description/branch/owner fields); reverts to `window.confirm`.

## IdeAccount — `/account`, `/settings`, `/settings/manage` (`ide/IdeMore.tsx::IdeAccount`)
- Profile card (tier/usage/role badges). Preferences: Interface (Signal/IDE), Theme (light/dark/**system** — IDE adds System). Manage links: Billing, Compare plans, API keys, Integrations, Org settings (admin). Sign out.
- **api:** `getMyPermissions`, logout.
- *vs Signal Account:* **no notification-preference toggles, no plan-limits KvList, no GDPR export, no change-password/sessions rows.**

## IdeIntegrations — `/integrations` (`ide/IdeSettings.tsx::IdeIntegrations`)
- Rows: GitHub (Install app), Slack (Connect, team name), Jira/Confluence/Bitbucket (connected badge from `enabledSources`). Connected/Not-connected badges.
- **api:** `getIntegrationStatus`, `getSlackStatus`, `getGitHubAppStatus`, `getGitHubAppInstallUrl`, `getSlackInstallUrl`.
- *vs Signal Integrations:* **no disconnect/uninstall actions, no Jira/Confluence/Bitbucket credential `PromptModal`s** (those three are connect-status-only in IDE; can't be connected from the IDE surface).

## IdeAdmin — `/admin` (bare) (`ide/IdeSettings.tsx::IdeAdmin`) — `/admin/*` sub-routes fall back to Signal `AdminPage`
- Canonical 6-stage activation funnel cards (Signups/Projects/Specs/Plans-approved/Runs-complete/PRs-merged, reads `stages[KEY].windowed`). Tier-lifecycle rows. Feature-flags list (read-only, all flags).
- **api:** `getActivationFunnel`, `adminListFeatureFlags`, `fetchTiersLifecycle`.
- *vs Signal Admin:* funnel uses the canonical 6 stages (vs Signal's 3 snapshot cards); flags list is complete (vs Signal's first-12); **no active-orgs card, no operations section (no reconcile-orphan-runs, no payment-audit/webhook rows).**

## IDE routes that fall back to Signal pages (no IDE-native component)
These render the Signal page **inside the IDE workspace shell** (the `ide ?` ternary resolves to the Signal component, or there is no ternary at all):

| Route | Renders (in IDE shell) | Reason |
|-------|------------------------|--------|
| `/live` (bare, no runId) | Signal `LivePage` | route has no `ide ?` branch for bare `/live` |
| `/result` (bare, no runId) | Signal `ResultPage` | route has no `ide ?` branch for bare `/result` |
| `/projects/empty` | Signal `EmptyProjectPage` | no IDE variant |
| `/billing` | Signal `BillingPage` | not yet rebuilt |
| `/plans` | Signal `PlansPage` | not yet rebuilt |
| `/topup` | redirect → `/billing` | — |
| `/api-keys` | Signal `ApiKeysPage` | no IDE variant |
| `/org-members` | Signal `OrgMembersPage` | no IDE variant |
| `/org/settings` | Signal `OrgSettingsPage` | not yet rebuilt (SSO + AI keys + model matrix) |
| `/insights` | Signal `InsightsPage` | no IDE variant |
| `/library`, `/library/:slug` | Signal `LibraryPage`/`LibraryDetailPage` | no IDE variant |
| `/migrations`, `/migrations/:slug` | Signal `MigrationsPage`/`MigrationDetailPage` | no IDE variant |
| `/templates` | Signal `SpecTemplatesPage` | no IDE variant |
| `/audit` | Signal `AuditLogPage` | no IDE variant |
| `/deployments` | Signal `DeploymentsPage` | no IDE variant |
| `/notifications` | Signal `NotificationsPage` | no IDE variant |
| `/search` | Signal `SearchPage` | no IDE variant (IDE has its own ⌘K palette instead) |
| `/more` | Signal `MorePage` | no IDE variant |
| `/admin/*` (sub-routes) | Signal `AdminPage` | only bare `/admin` is IDE-native |
| all public routes | Signal | auth/legal/pricing/shared render outside both shells |

**IDE surface count: 22** IDE-native components (IdeWorkspace chrome, IdeWelcome, IdeCompose, IdeLive, IdePlan, IdeRunResult, IdeRuns, IdeProjects, IdeProject, IdeProjectPulse, IdeProjectRuns, IdeProjectRepos, IdeProjectCode, IdeProjectChat, IdeProjectArchitecture, IdeProjectMembers, IdeProjectActivity, IdeProjectSettings, IdeAccount, IdeIntegrations, IdeAdmin, + the shared `ide/IdeProjectTabs::ProjectTabs` strip). ~20 routes fall back to Signal inside the IDE shell.

---

## Result page tab matrix (Signal vs IDE)

| Tab | Signal Result | IDE RunResult |
|-----|:-:|:-:|
| Overview | ✅ | ✅ |
| Tasks | ✅ | ✅ |
| Timeline | ✅ | ❌ absent |
| Code | ✅ (tree + content/diff toggle) | ✅ (tree + editor tabs + ADDED-diff) |
| Cost | ✅ (4 phases incl. Coherence) | ✅ (3 phases, no Coherence) |
| PR | ✅ | ✅ |
| Validation | ✅ | ✅ |
| Tests | ✅ (per-test rows + suggestions + apply-fix) | ✅ (summary + root causes only) |
| Chat | ✅ | ❌ absent |

## Project Code page sub-tab matrix (Signal vs IDE)

| Sub-tab | Signal Project Code | IDE Project Code |
|---------|:-:|:-:|
| Browse | ✅ | ✅ |
| Tech debt | ✅ | ❌ absent |
| Code review | ✅ | ❌ absent |
| Health | ✅ | ❌ absent |
| Standards | ✅ | ❌ absent |
| Docs | ✅ | ❌ absent |
