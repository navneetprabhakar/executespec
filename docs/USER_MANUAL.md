# ExecuteSpec User Manual

> Operator's reference for every page a signed-in user can reach, plus the public surfaces (auth, legal, shared links).
>
> This manual maps **route path - what the page does - which API endpoints it calls - who can see it**. The **VS Code-style IDE workspace** (`frontend/src/ide/`) is the only chrome since the 2026-05-22 IDE-only switch; every protected route renders an IDE-native surface. The route paths, behaviours, and API calls below remain current — but the chrome/nav description and some page-component names still reference the retired Signal shell, pending a full IDE-native rewrite of this manual.

## How the app is laid out

ExecuteSpec ships a single SPA mounted at `frontend/src/main.tsx`. Routing lives in [`frontend/src/App.tsx`](../frontend/src/App.tsx). All authenticated pages render inside [`frontend/src/ide/IdeWorkspace.tsx`](../frontend/src/ide/IdeWorkspace.tsx) — a VS Code-style workspace:

- **Desktop** - a left **activity bar** (icon rail) + an **Explorer** panel (live runs + projects tree) + the editor/surface area + a bottom **status bar**, with a **⌘K command palette** to jump to a run, project, or action.
- **Mobile** - a top bar + slide-in drawer + bottom tab bar.

> **Heads up — the route tables below are stale on chrome framing.** They were written for the retired Signal shell (sidebar groups, page-component names like `HomePage`/`ResultPage`). The route paths, behaviours, tier-gating, and API calls remain accurate and useful as a route inventory; the navigation/sidebar framing and component names do not reflect the current IDE chrome. A full IDE-native rewrite of this manual is pending.

### Sidebar group at a glance

```
+----------------------------------------------------------+
| ExecuteSpec                                              |
| ---------------------                                    |
| DAILY                                                    |
|   Home                /home                              |
|   New spec            /compose                           |
|   Live & recent       /runs                              |
|   Plans               /plans                             |
| WORKSPACE                                                |
|   Projects            /projects                          |
|   Templates           /templates                         |
|   Library     [BETA]  /library                           |
|   Migrations  [BETA]  /migrations                        |
|   Deployments [BETA]  /deployments         (PRO+ gate)   |
|   Insights            /insights                          |
| ACCOUNT                                                  |
|   Billing             /billing                           |
|   Top-up credits      /topup                             |
|   Integrations        /integrations                      |
|   API keys            /api-keys            (PRO+ gate)   |
|   Org members         /org-members                       |
|   Org settings        /org/settings                      |
|   Account             /account                           |
| SYSTEM                                                   |
|   Notifications       /notifications                     |
|   Search              /search                            |
|   Audit log           /audit               (TEAM+ gate)  |
|   Admin               /admin               (SUPER_ADMIN) |
|   Trust               /trust                             |
|                                                          |
|   Theme: Dark mode / Light mode                          |
+----------------------------------------------------------+
```

### Mobile bottom tab bar

```
+------+------+--------+----------+---------+
| Home | Runs |  +New  | Projects | Account |
+------+------+--------+----------+---------+
```

The middle "New" tab is a floating action button (FAB) styled in `signal.css` `.tab.fab`.

### Tier visibility gating (shell-level)

Items get hidden, disabled, or shown with an upgrade CTA according to the user's tier plus `permissions` from `api.getMyPermissions()`. Tier checks at the shell level:

| Item             | Visible if                                                            |
| ---------------- | --------------------------------------------------------------------- |
| `/deployments`   | `permissions.canDeploy !== false` (PRO+; `false` for FREE)            |
| `/api-keys`      | `tier !== 'FREE'`                                                     |
| `/audit`         | `tier === 'TEAM' \|\| 'ENTERPRISE' \|\| isSuperAdmin`                 |
| `/admin`         | `isSuperAdmin`                                                        |
| `/org-members`   | Always rendered; invite/remove gated to `ORG_ADMIN \|\| SUPER_ADMIN`  |
| `/org/settings`  | Always rendered; edit gated to `ORG_ADMIN \|\| SUPER_ADMIN`           |

`BETA` badges (added 2026-05-16) sit on Library, Migrations, and Deployments to signal that backend coverage is still firming up.

---

## Daily group

The everyday surfaces: the home dashboard, the spec composer, the run list, and the plans page.

### `/home` - Home dashboard

- **Page:** [`frontend/src/signal/pages/HomePage.tsx`](../frontend/src/signal/pages/HomePage.tsx)
- **What it shows.** A time-of-day greeting, a "live-run hero" card if any run is currently in flight, a 4-card quick-action grid (New spec / Projects / All runs / Project chat), a recent-runs preview (skeleton-loaded), and a project list. The page polls every 5s while a live run is present, otherwise every 30s.
- **APIs called.** `api.getRuns({page: 0, size: 8})`, `api.getProjects()`, `api.getRun(runId)` for the hero detail when a live run is detected.
- **Tier gating.** None for the page itself. The "All runs" quick-action card surfaces `permissions.currentRunCount / monthlyRunLimit` so FREE users see their cap up-front.

### `/compose` - Spec composer

- **Page:** [`frontend/src/signal/pages/ComposePage.tsx`](../frontend/src/signal/pages/ComposePage.tsx)
- **What it shows.** A spec textarea, an AI-provider chip selector (Anthropic / OpenAI / Gemini), an optional project picker, per-project repo multi-select, a tier-aware cost-cap input, and a debounced p25/p75 estimate from project history. Submits a `BUILD`-mode run.
- **APIs called.** `api.getProjects()`, `api.getProjectRepos(projectId)`, `api.estimateRun(projectId, spec)`, `api.createRun(body)`.
- **Tier gating.** `tierMaxCap` = FREE->$3, ENTERPRISE->$1000, else $25 (PRO/TEAM). The "at-cap" banner surfaces when `permissions.monthlyRunLimit` has been hit. Default AI provider follows `user.defaultProvider`. A `sessionStorage` handoff (`signal:pending-spec`) picks up specs prepared on `/welcome`, `/library/:slug`, or `/templates`.
- **Bundled.** A `SpecChat` collapsible panel ([`SpecChat.tsx`](../frontend/src/signal/components/SpecChat.tsx)) lets the user iterate the spec with `api.conversationChat` before submission.

### `/plan`, `/plan/:runId` - Plan-gate review

- **Page:** [`frontend/src/signal/pages/PlanPage.tsx`](../frontend/src/signal/pages/PlanPage.tsx)
- **What it shows.** While the backend is classifying/planning, polls `api.getRun(runId)` every 2s and shows phase progress. Once status hits `AWAITING_APPROVAL`, surfaces the planned task list, the credit estimate, the cost cap, and Approve / Reject buttons. Auto-redirects to `/live/:runId` once `EXECUTING`, or `/result/:runId` once terminal.
- **APIs called.** `api.getRun(runId)` (polled), `api.approveRun(runId, true|false)`.
- **Tier gating.** None. PRODUCTION mode runs always stop here; PROTOTYPE mode skips the gate.
- **Notable.** Uses the `Glossary` tooltip primitive ([`Glossary.tsx`](../frontend/src/signal/components/Glossary.tsx)) for the `classifier` term.

### `/live`, `/live/:runId` - Live-run watch

- **Page:** [`frontend/src/signal/pages/LivePage.tsx`](../frontend/src/signal/pages/LivePage.tsx)
- **What it shows.** SSE-driven live view of an executing run. Shows `LiveHero` (running-now ribbon + elapsed/tasks/spent), `CostProgressBar` (green/amber/red bands), per-task progress, and a live log of `StreamCard`s (tool / llm / warn / ok variants). 8s polling backs up the SSE in case of disconnect. Includes a Cancel button (confirmed via `ConfirmModal`).
- **APIs called.** `api.getRun(runId)` (every 8s), `api.cancelRun(runId)`. SSE: `/api/runs/{id}/events` via the `useSse(runId)` hook plus a scoped token from `api.getSseToken(runId)`.
- **Tier gating.** None.
- **Phase labels.** `Classifying`, `Planning`, `Awaiting approval`, `Reviewing changes`, `Switching provider`, `Paused`, `Running now`.

### `/result`, `/result/:runId`, `/runs/:runShort`, `/projects/:projectId/runs/:runShort` - Run result detail

- **Page:** [`frontend/src/signal/pages/ResultPage.tsx`](../frontend/src/signal/pages/ResultPage.tsx)
- **What it shows.** A 9-tab result viewer. Tabs (lazy-loaded): **Overview** (verdict score, status reason, headline metrics), **Tasks** (per-task status + retry button), **Timeline** (LLM call ladder), **Code** (changed-files tree + content + diff via `CodeView`), **Cost** (token + USD breakdown), **PR** (per-repo PR cards with CI status), **Validation** (issues + score breakdown, PDF download, share link), **Tests** (test report + apply-fixes), **Chat** (post-run iterate / chat). Headline row offers "Try another provider" re-run (excludes current provider).
- **APIs called.** A long list driven by tab selection. Aggregated:
  - Run + reports: `api.getRun(id)`, `api.getValidationReport(id)`, `api.retryValidationReport(id)`, `api.downloadValidationReportPdf(id)`, `api.shareValidationReport(id)`.
  - Timeline + workspace: `api.getTimeline(id)`, `api.getFilesWithStatus(id)`, `api.getFile(id, path)`, `api.getFileDiff(id, path)`, `api.downloadWorkspace(id)`.
  - Run chat + iterate: `api.getRunChat(id)`, `api.sendRunChatMessage(id, text)`, `api.iterateRun(id, text)`.
  - Tests: `api.getTestReport(id)`, `api.runTests(id)`, `api.applyTestFixes(id, signature)`.
  - Tasks + re-run: `api.retryTask(id, taskId)`, `api.createRun(body)` (provider re-run).
  - Preview + push: `api.startPreview(id)`, `api.pushToScm(id)`.
- **Tier gating.** `canPreview = permissions?.canDeploy !== false` - PRO+ can use "Start preview"; FREE users see the same button labelled "Upgrade".
- **Verdict thresholds.** Shippable >=85, Needs review 70-84, Blocked <70 (per [`signal/lib/runUtils.ts`](../frontend/src/signal/lib/runUtils.ts) `scoreKind`).

### `/runs` - All runs

- **Page:** [`frontend/src/signal/pages/AllRunsPage.tsx`](../frontend/src/signal/pages/AllRunsPage.tsx)
- **What it shows.** A flat run history list with six filter tabs (All / Live / Shipped / Needs / Failed / Archived) and infinite-scroll Load-more. Counts memoised from the local list. Status thresholds use the standard 85/70 score bands.
- **APIs called.** `api.getRuns({page: 0, size: 30})` + paginated reloads.
- **Tier gating.** None.

### `/plans` - Plans & pricing (authenticated)

- **Page:** [`frontend/src/signal/pages/account/PlansPage.tsx`](../frontend/src/signal/pages/account/PlansPage.tsx)
- **What it shows.** Plan comparison with monthly/yearly toggle (yearly saves 20%). Renders FREE / TEAM / ENTERPRISE side-by-side with feature bullets, "RECOMMENDED" and "CURRENT" badges, and a Subscribe action. PRO is grandfathered: new buyers cannot pick it; the button is disabled with a grandfathered banner.
- **APIs called.** `api.getPricing()` for the source-of-truth price table. Raw `POST /api/billing/subscribe` for the subscribe action. ENTERPRISE opens `mailto:sales@executespec.ai`.
- **Tier gating.** None for visibility. The CURRENT badge marks the user's current tier.

---

## Workspace group

Project workspaces (the multi-repo home for a piece of software), spec templates, the readymade Library, the Migrations recipe catalog, Deployments, and the Insights analytics page.

### `/projects` - Projects directory

- **Page:** [`frontend/src/signal/pages/ProjectsPage.tsx`](../frontend/src/signal/pages/ProjectsPage.tsx)
- **What it shows.** A directory of every project the user can see, split into three tabs: Mine, Shared with me, Archived. For each non-archived project, hydrates dashboard + recent-runs in a second pass to compute a recency-weighted average score (weights decay 1.0 -> 0.46 over the last 10 runs). New-project button uses a `PromptModal` (name + description).
- **APIs called.** `api.getProjects()`, then per-project `api.getProjectDashboard(p.id)` + `api.getRuns({projectId, page: 0, size: 10})`. `api.createProject({name, description})` on submit.
- **Tier gating.** None at the page level.
- **Note.** The per-project N+1 hydration loop is acknowledged as expensive on accounts with many projects.

### `/projects/empty` - Empty-state landing

- **Page:** `EmptyProjectPage` (named export from [`ExtraPages.tsx`](../frontend/src/signal/pages/ExtraPages.tsx))
- **What it shows.** Static content for users who land on the directory with no projects - guidance + CTA to create the first project.
- **APIs called.** None.
- **Tier gating.** None.

### `/projects/:projectId` - Project compose (default landing)

- **Page:** [`frontend/src/signal/pages/project/ProjectComposePage.tsx`](../frontend/src/signal/pages/project/ProjectComposePage.tsx)
- **What it shows.** Per-project spec composer. Pre-selects the project's default AI provider, all repos checked, displays a tier chip, hydrates a debounced p25/p75 estimate, picks up `sessionStorage` handoffs from sandbox / library / chat-compile flows.
- **APIs called.** `api.getProjectRepos(id)`, `api.getRuns({projectId, page: 0, size: 5})`, `api.estimateRun(id, spec)`, `api.createRun(body)`.
- **Tier gating.** Shows tier chip + runs-used progress (`permissions.currentRunCount / monthlyRunLimit`).
- **Note.** This - not Pulse - is the default tab when opening a project, matching the historical v3 parity (intentional per the route comment in `App.tsx`).

All other project surfaces sit under a shared `ProjectTabs` strip ([`ProjectTabs.tsx`](../frontend/src/signal/pages/project/ProjectTabs.tsx)). The strip loads dashboard + members + repos in parallel to compute tab counts. Tab order: Compose, Pulse, Repos, Runs, Code, Chat, Activity, Members, Architecture, Settings.

### `/projects/:projectId/pulse` - Project pulse (dashboard)

- **Page:** [`frontend/src/signal/pages/project/ProjectPulsePage.tsx`](../frontend/src/signal/pages/project/ProjectPulsePage.tsx)
- **What it shows.** Live-run hero (when any run is in flight), 4 quick-stat cards (avg score / avg run time / 30-day spend / PRs merged), workspace-health rollup (per-repo SHA drift + webhook + open PR count), open-PR list, and a recent-runs preview.
- **APIs called.** `api.getProjectDashboard(id)`, `api.getRuns({projectId, page: 0, size: 6})`, `api.getProjectRepos(id)`, `api.getWorkspaceHealth(id)`.
- **Tier gating.** None.

### `/projects/:projectId/repos` - Linked repos

- **Page:** [`frontend/src/signal/pages/project/ProjectReposPage.tsx`](../frontend/src/signal/pages/project/ProjectReposPage.tsx)
- **What it shows.** Per-repo cards with HEAD SHA / drift / webhook / open-PR status. Inline "available repos" panel lets the user link a repo. Per-repo actions: View architecture, Install webhook (if missing), Sync (if drifting), Unlink.
- **APIs called.** `api.getProjectRepos(id)`, `api.getWorkspaceHealth(id)`, `api.listRepos('ALL')`, `api.linkProjectRepo(id, repoId)`, `api.unlinkProjectRepo(id, repoId)`, `api.installRepoWebhook(repoId)`, `api.syncRepo(repoId)`.
- **Tier gating.** None.
- **Unlink** is `ConfirmModal`-protected.

### `/projects/:projectId/runs`, `/projects/:projectId/runs/:runShort` - Project runs + PRs

- **Page:** [`frontend/src/signal/pages/project/ProjectRunsPage.tsx`](../frontend/src/signal/pages/project/ProjectRunsPage.tsx)
- **What it shows.** Two sub-tabs:
  - **Runs** - a split-list view (filter strip All / Live / Shipped / Needs / Failed on the left; inline run-detail pane on the right) with the same `CodeView` / `KvList` rendering as the standalone result page.
  - **PRs** - a list of open PRs across the project's linked repos, with an autofix-review action per PR.
- **APIs called.** `api.getRuns({projectId, page: 0, size: 50})`, `api.getRun(selectedRunId)`, `api.getValidationReport(selectedRunId)`, `api.getProjectDashboard(id)`, `api.refreshProjectPrs(id)`, `api.fetchPrReviewComments(id, prNumber, repoFullName)`.
- **Tier gating.** None.
- **Note.** The inline detail pane historically used 90/70 thresholds, drifting from the main app's 85/70. Treat the standalone Result page as the source of truth.

### `/projects/:projectId/code` - Code & quality

- **Page:** [`frontend/src/signal/pages/project/ProjectCodePage.tsx`](../frontend/src/signal/pages/project/ProjectCodePage.tsx)
- **What it shows.** Six sub-tabs:
  - **Browse** - live GitHub-proxied source viewer (file tree + content pane using `FileTree` + `CodeView`), powered by the user's PAT.
  - **Tech debt** - category x severity matrix with per-category "Fix" autofix dispatch (lands a patch-mode run).
  - **Code review** - latest per-repo full-repo scan results.
  - **Health** - per-repo coverage / lint / build rollups.
  - **Standards** - org compliance report (TEAM+).
  - **Docs** - generate / download EXPLANATION.md + SETUP.md.
- **APIs called.** `api.getTechDebt(id, 30)`, `api.getProjectRepos(id)`, `api.getProjectDoc(id, type)`, `api.getWorkspaceHealth(id)`, `api.getStandardsReport(id, orgId, 30)`, `api.listRepoFiles(id, repoId)`, `api.readRepoFile(id, repoId, path)`, `api.dispatchTechDebtFix(id, category)`, `api.generateProjectDoc(id, type)`, `api.downloadProjectDoc(id, type)`. Code-review submit/list call `/api/code-reviews` directly via `fetch`.
- **Tier gating.** Standards sub-tab requires `user.orgId` (effectively TEAM+/ENTERPRISE) and shows an empty-state upgrade card otherwise.

### `/projects/:projectId/chat` - Project chat

- **Page:** [`frontend/src/signal/pages/project/ProjectChatPage.tsx`](../frontend/src/signal/pages/project/ProjectChatPage.tsx)
- **What it shows.** Project-scoped chat with a memory-digest banner, a compile-to-spec action (drops a spec into the composer), compact + clear actions, and `Markdown`-rendered assistant replies. Polls memory stats every 30s.
- **APIs called.** `api.getProjectChatInfo(id)`, `api.getProjectMemory(id)` (polled), raw `POST /api/projects/{id}/chat`, `api.compileProjectChatToSpec(id)`, `api.compactProjectChat(id)`, `api.clearProjectChat(id)`.
- **Tier gating.** Backend gates this feature to **TEAM+ / ENTERPRISE**. The page catches a 403 / "Team or Enterprise" error from `/info` and flips into an upgrade-card state with the composer disabled.
- **Clear chat** is `ConfirmModal`-protected.

### `/projects/:projectId/activity` - Activity feed

- **Page:** [`frontend/src/signal/pages/project/ProjectActivityPage.tsx`](../frontend/src/signal/pages/project/ProjectActivityPage.tsx)
- **What it shows.** Project activity feed (partitioned by month server-side) with type filter (all / runs / prs / members / standards). Polls every 30s for new events.
- **APIs called.** `api.getActivityFeed(id, {limit: 50, sinceDays: 90})`.
- **Tier gating.** None.

### `/projects/:projectId/members` - Project members

- **Page:** [`frontend/src/signal/pages/project/ProjectMembersPage.tsx`](../frontend/src/signal/pages/project/ProjectMembersPage.tsx)
- **What it shows.** Two halves: (1) `ProjectMember` rows with role + remove action; (2) share-links management for read-only public-URL access. Invite form supports email + role (`PROJECT_VIEWER` / `PROJECT_DEVELOPER` / `PROJECT_ADMIN`).
- **APIs called.** `api.getProjectMembers(id)`, `api.removeProjectMember(id, userId)`, `api.getMyOrg()` (for the V198 flag), raw `fetch` for `/api/projects/{id}/invites` (POST) and `/api/projects/{id}/share-links` (GET/POST/DELETE).
- **Tier gating.**
  - `canManage = user.orgRole === 'ORG_ADMIN' || user.globalRole === 'SUPER_ADMIN'`.
  - **V198 (2026-05-16):** the invite UI is hidden unless `isSuperAdmin || externalSharingEnabled === true`. When off, shows an "External sharing is off - Enable in Org settings" card.
  - Share-links are independent of V198 and remain available where the page permits.
- **Remove member** is `ConfirmModal`-protected.

### `/projects/:projectId/architecture` - Architecture

- **Page:** [`frontend/src/signal/pages/project/ProjectArchitecturePage.tsx`](../frontend/src/signal/pages/project/ProjectArchitecturePage.tsx)
- **What it shows.** Five sub-tabs: **Current** (latest HLD/LLD markdown), **Versions** (history list + diff picker), **Invariants** (with pass/fail counts), **ADRs** (status pills), **Contracts** (per-repo coverage + drift detection + dismiss/refresh).
- **APIs called.** `api.getArchitectureDoc(id)`, `api.getArchitectureVersions(id)`, `api.listProjectInvariants(id)`, `api.listProjectInvariantViolations(id)`, `api.listProjectAdrs(id, false)`, `api.getArchitectureStaleness(id)`, `api.getProjectRepos(id)`, `api.getContractCoverage(id, repoId)`, `api.rescanContractCoverage(id, repoId)`, `api.checkRepoDrift(id, repoId)`, `api.checkProjectDrift(id)`, `api.generateArchitecture(id)`, `api.getArchitectureDiff(id, from, to)`.
- **Tier gating.** None at the page level.

### `/projects/:projectId/settings` - Project settings

- **Page:** [`frontend/src/signal/pages/project/ProjectSettingsPage.tsx`](../frontend/src/signal/pages/project/ProjectSettingsPage.tsx)
- **What it shows.** Three sub-tabs: **Settings** (name / description / default AI provider / target branch / branch naming pattern / GitHub owner), **Documents** (RAG document upload / list / delete), **Memory** (compact / view snapshot). Danger zone at the bottom: archive + delete.
- **APIs called.** Raw `PATCH /api/projects/{id}/settings`, `api.archiveProject(id)`, `api.deleteProject(id)`, `api.listProjectDocuments(id)`, `api.uploadProjectDocument(id, file)`, `api.deleteProjectDocument(id, docId)`, `api.getProjectMemory(id)`, `api.compactProjectMemory(id)`.
- **Tier gating.** `canEdit = isSuperAdmin || user.orgRole === 'ORG_ADMIN'`. Edit fields are disabled and the danger zone is hidden unless `canEdit`.
- **Archive / delete / doc-delete** are `ConfirmModal`-protected.

### `/templates` - Spec templates

- **Page:** `SpecTemplatesPage` (named export from [`ExtraPages.tsx`](../frontend/src/signal/pages/ExtraPages.tsx))
- **What it shows.** Project + org-scoped spec templates. Project picker; per-template card with apply action that pushes the spec to `/compose` via `sessionStorage`. Create-template uses a `PromptModal`; delete is `ConfirmModal`-protected.
- **APIs called.** `api.getProjects()`, `api.listSpecTemplates(projectId)`, `api.createProjectSpecTemplate(projectId, body)`, `api.deleteSpecTemplate(projectId, id)`.
- **Tier gating.** None.

### `/library`, `/library/:slug` - Library (BETA)

- **Pages:** `LibraryPage`, `LibraryDetailPage` (named exports from [`ExtraPages.tsx`](../frontend/src/signal/pages/ExtraPages.tsx))
- **What it shows.** Readymade solutions catalog with a fixed 6-entry array on the list page and static demo content on the detail page. Backend wrappers exist in `api.listLibrary`, `api.getLibraryDetail`, `api.resolveLibraryVariant`, `api.instantiateLibrarySolution`, `api.favouriteLibrarySolution` - but the current Signal pages do not call them; they render static content as a preview surface.
- **APIs called.** None today (hard-coded array).
- **Tier gating.** Tagged `BETA` in the sidebar.

### `/migrations`, `/migrations/:slug` - Migrations (BETA)

- **Pages:** `MigrationsPage`, `MigrationDetailPage` (named exports from [`ExtraPages.tsx`](../frontend/src/signal/pages/ExtraPages.tsx))
- **What it shows.** Migration-recipe catalog with a 6-entry hard-coded list and static demo on the detail page. Like `/library`, the typed API wrappers exist (`api.listMigrations`, `api.getMigrationDetail`, `api.migrationDryRun`, `api.migrationApply`) but the Signal pages do not call them yet.
- **APIs called.** None today (hard-coded array).
- **Tier gating.** Tagged `BETA` in the sidebar.

### `/deployments` - Deployments (BETA, PRO+)

- **Page:** `DeploymentsPage` (named export from [`ExtraPages.tsx`](../frontend/src/signal/pages/ExtraPages.tsx))
- **What it shows.** Active deployments list with per-deployment Rollback + Destroy actions. Each destructive action is gated by a `ConfirmModal`.
- **APIs called.** `api.getDeployments()`, `api.rollbackDeployment(id)`, `api.deleteDeployment(id)`.
- **Tier gating.** Sidebar entry is hidden when `permissions.canDeploy === false` (FREE). Page itself does not re-check, so a direct URL hit on FREE still renders an empty list. Tagged `BETA`.

### `/insights` - Insights analytics

- **Page:** `InsightsPage` (named export from [`ExtraPages.tsx`](../frontend/src/signal/pages/ExtraPages.tsx))
- **What it shows.** Per-user run analytics - 30/90/365-day window toggle, headline metrics, by-provider breakdown, summary card.
- **APIs called.** `api.getMetrics(days)`, `api.getModelComparison(days)`, `api.getUserRunsSummary(days)`.
- **Tier gating.** None.

---

## Account group

Billing, top-ups, integrations, AI keys (BYOK), org members, org settings, and the user account hub.

### `/billing` - Billing

- **Page:** [`frontend/src/signal/pages/account/BillingPage.tsx`](../frontend/src/signal/pages/account/BillingPage.tsx)
- **What it shows.** Tier + seats card, this-cycle runs gauge with progress bar (`currentRunCount / monthlyRunLimit`), invoice list (last 6), payment-method card.
- **APIs called.** `api.getAnalyticsBilling('org', user.orgId)`, `api.getAnalyticsUsage('org', user.orgId, 30)`, raw `GET /api/billing/payment-history`.
- **Tier gating.** Visible to all authenticated users.

### `/topup` - Top-up credits

- **Page:** `TopupPage` (named export from [`account/MiscAccountPages.tsx`](../frontend/src/signal/pages/account/MiscAccountPages.tsx))
- **What it shows.** A 3-pack list of additional-run packages. If `api.adminListTopupPackages()` returns 403, falls back to a hard-coded 3-pack catalog. Purchase opens a gateway redirect.
- **APIs called.** `api.adminListTopupPackages()`, raw `POST /api/billing/topup/purchase`.
- **Tier gating.** None at the page; reads `permissions` to surface entitlement state.

### `/integrations` - Integrations

- **Page:** `IntegrationsPage` (named export from [`account/MiscAccountPages.tsx`](../frontend/src/signal/pages/account/MiscAccountPages.tsx))
- **What it shows.** Per-provider connect/disconnect rows for **GitHub App**, **Slack**, **Jira**, **Confluence**, **Bitbucket**. Each row uses a `PromptModal` for credential capture and a `ConfirmModal` for disconnect.
- **APIs called.** `api.getIntegrationStatus()`, `api.getSlackStatus()`, `api.getGitHubAppStatus()`, `api.getSlackInstallUrl()`, `api.getGitHubAppInstallUrl()`, `api.disconnectSlack()`, `api.uninstallGitHubApp()`, `api.updateJiraSettings(...)`, `api.updateConfluenceSettings(...)`, `api.updateBitbucketSettings(...)`.
- **Tier gating.** None.

### `/api-keys` - AI API keys (BYOK, PRO+)

- **Page:** `ApiKeysPage` (named export from [`account/MiscAccountPages.tsx`](../frontend/src/signal/pages/account/MiscAccountPages.tsx))
- **What it shows.** Three secret-input rows for OpenAI / Anthropic / Gemini keys, each with a Validate action before save.
- **APIs called.** `api.validateAiKey(provider, key)`, `api.updateAiKeySettings(openai, anthropic, gemini)`.
- **Tier gating.** `canManage = tier !== 'FREE'`. The UI is disabled with an upgrade CTA card otherwise. Sidebar entry is hidden on FREE.

### `/org-members` - Org members

- **Page:** `OrgMembersPage` (named export from [`account/MiscAccountPages.tsx`](../frontend/src/signal/pages/account/MiscAccountPages.tsx))
- **What it shows.** Org-wide member list with seat counter, invite form (`DEVELOPER` / `BILLING_ADMIN` / `ORG_ADMIN`), pending-invitations list with resend, and per-member remove + role-change.
- **APIs called.** `api.getMyOrg()`, `api.getOrgMembers()`, `api.getOrgInvitations()`, `api.inviteMember({email, orgRole})`, `api.resendInvitation(id)`, `api.removeMember(userId)`, `api.changeMemberRole(userId, role)`.
- **Tier gating.** `canInvite = user.orgRole === 'ORG_ADMIN' || user.globalRole === 'SUPER_ADMIN'`. Invite form, role select, and remove button are all gated. Remove uses `ConfirmModal`.

### `/org/settings` - Org settings

- **Page:** `OrgSettingsPage` (named export from [`LegalPages.tsx`](../frontend/src/signal/pages/LegalPages.tsx))
- **What it shows.** Four sections:
  - **Profile** - org name + domains.
  - **SSO** - provider picker (GOOGLE_OIDC / OKTA_SAML / AZURE_AD_SAML), metadata URL, domain, enforced toggle, clear button.
  - **External sharing** - V198 per-org toggle (`org.external_sharing_enabled`); gates cross-org invites on `/projects/:id/members`.
  - **Compliance** - read-only status block.
- **APIs called.** `api.getMyOrg()`, `api.getSsoConfig()`, `api.updateOrg({name, domains})`, `api.updateSsoConfig({...})`, `api.clearSsoConfig()`, `api.updateExternalSharing(enabled)`.
- **Tier gating.** `canEdit = isSuperAdmin || user.orgRole === 'ORG_ADMIN'`. Non-admins see read-only.
- **Clear SSO** is `ConfirmModal`-protected.

### `/account`, `/settings`, `/settings/manage` - Account hub

- **Page:** [`frontend/src/signal/pages/account/AccountPage.tsx`](../frontend/src/signal/pages/account/AccountPage.tsx)
- **What it shows.** Avatar + chips header (tier chip; conditional SUPER_ADMIN chip), quick-link rows to billing / topup / plans / api-keys / integrations / org-members / org-settings, theme + view-mode prefs, plan limits, notification-preferences toggle matrix, security + sign-out, GDPR data-export button.
- **APIs called.** `api.getNotificationPreferences()`, `api.updateNotificationPreference(eventType, channel, enabled)`, `api.getMyPermissions()`, raw `fetch('/api/auth/me/data-export')` for the GDPR ZIP.
- **Tier gating.** Org-members / Org-settings rows are hidden unless `user.orgRole === 'ORG_ADMIN' || user.globalRole === 'SUPER_ADMIN'`. The "Plan limits" panel surfaces `permissions.canViewLiveLog` (PRO+), `permissions.canManageAiKeys` (PRO+), `permissions.canViewAudit` (TEAM+), `permissions.canDeploy` (PRO+).

---

## System group

Cross-cutting surfaces - notifications, search, audit log, admin console, public trust page.

### `/notifications` - In-app notifications

- **Page:** `NotificationsPage` (named export from [`MiscPages.tsx`](../frontend/src/signal/pages/MiscPages.tsx))
- **What it shows.** Notification inbox with category filter (all / run / team / billing / system) and bulk mark-all-read. Each row deep-links by type: `RUN`/`PR` -> `/runs`, `BILLING` -> `/billing`, `MEMBER` -> `/org-members`, default -> `/home`.
- **APIs called.** `api.getNotifications(0, 50)`, `api.markNotificationRead(id)`, `api.markAllNotificationsRead()`.
- **Tier gating.** None.

### `/search` - Search palette

- **Page:** `SearchPage` (named export from [`MiscPages.tsx`](../frontend/src/signal/pages/MiscPages.tsx))
- **What it shows.** Search field plus local fuzzy match against the most recent 50 runs and all projects. Hits link straight to the relevant detail page.
- **APIs called.** `api.getRuns({page: 0, size: 50})`, `api.getProjects()`.
- **Tier gating.** None.

### `/audit` - Audit log

- **Page:** `AuditLogPage` (named export from [`ExtraPages.tsx`](../frontend/src/signal/pages/ExtraPages.tsx))
- **What it shows.** Org-scoped audit log with category filter (all / auth / billing / members / admin) and CSV export (in-place via `Blob` URL).
- **APIs called.** `api.getAuditLogs({page, size})`.
- **Tier gating.** TEAM+ / ENTERPRISE / SUPER_ADMIN. On 403, the page tailors the message:
  - FREE/PRO -> "TEAM+ feature - Upgrade your plan."
  - TEAM+ non-ORG_ADMIN -> "Ask your org admin."
  - TEAM+ ORG_ADMIN -> "Enable in Org Settings > Compliance."

### `/more` - Mobile more sheet

- **Page:** `MorePage` (named export from [`MiscPages.tsx`](../frontend/src/signal/pages/MiscPages.tsx))
- **What it shows.** Aggregator landing reachable from the mobile tab bar's "more" entry. Displays unread notification count, project count, total run count, and rows for every nav destination not in the bottom tabs. Includes a tier-aware Audit-log row and a SUPER_ADMIN-only Admin row.
- **APIs called.** `api.getUnreadCount()`, `api.getProjects()`, `api.getRuns({page: 0, size: 1})`.
- **Tier gating.** Audit-log row hidden unless `tier === 'TEAM' || 'ENTERPRISE' || isSuperAdmin`. Admin section hidden unless `isSuperAdmin`.

### `/admin`, `/admin/*` - Admin console (SUPER_ADMIN)

- **Page:** `AdminPage` (named export from [`MiscPages.tsx`](../frontend/src/signal/pages/MiscPages.tsx))
- **What it shows.** SUPER_ADMIN-only console. Tier-lifecycle controls (LISTED / HIDDEN / SUNSETTING), org list, feature flags, 30-day activation funnel, and an Execution-reconcile action (marks orphaned non-terminal `ExecutionRun` rows FAILED).
- **APIs called.** `api.fetchTiersLifecycle()`, `api.adminListOrgs()`, `api.adminListFeatureFlags()`, `api.getActivationFunnel(30)`, raw `POST /api/admin/executions/reconcile`.
- **Tier gating.** Bounces to `/home` immediately if `!isSuperAdmin`. Sidebar entry is hidden otherwise.

### `/trust` - Trust & security

- **Page:** `TrustPage` (named export from [`ExtraPages.tsx`](../frontend/src/signal/pages/ExtraPages.tsx))
- **What it shows.** Static security copy describing the 8-layer defense-in-depth stack, guardrail levels, and data handling.
- **APIs called.** None.
- **Tier gating.** None - visible to all (sidebar entry routes here, but the route is also reachable when signed out).

---

## Auth flows

The public auth surfaces (login / signup / verify / forgot / reset / OAuth callback / welcome / accept-invite). They render WITHOUT the Signal sidebar - most use the shared `AuthLayout` ([`AuthLayout.tsx`](../frontend/src/signal/components/AuthLayout.tsx)) which puts the form on the left and a decorative brand panel on the right (hidden <=920px).

### `/login` - Login

- **Page:** `LoginPage` (named export from [`AuthPages.tsx`](../frontend/src/signal/pages/AuthPages.tsx))
- **What it shows.** Email/password form + Google/GitHub OAuth buttons. Decodes OAuth error codes from the URL (`oauth_no_email`, `oauth_email_conflict`, `oauth_failed`).
- **APIs called.** The store action `login(email, password)` (which wraps `api.authLogin`). OAuth direct-hits `/api/auth/oauth2/authorize/{google,github}`.
- **Tier gating.** None.

### `/register`, `/signup` - Signup

- **Page:** `SignupPage` (named export from [`AuthPages.tsx`](../frontend/src/signal/pages/AuthPages.tsx))
- **What it shows.** Email + password + first/last name form. Local helper `deriveNames(email)` infers first + last from the email local-part.
- **APIs called.** `api.authRegister({email, password, firstName, lastName, signupSource: 'signal'})`.
- **Tier gating.** None.

### `/verify`, `/verify-email` - Email verification

- **Page:** `VerifyPage` (named export from [`AuthPages.tsx`](../frontend/src/signal/pages/AuthPages.tsx))
- **What it shows.** Confirmation screen - when `?token=` is present, calls verify and surfaces success / failure.
- **APIs called.** `api.authVerifyEmail(token)`.
- **Tier gating.** None.

### `/welcome` - Sandbox picker (public, post-signup)

- **Page:** `WelcomePage` (named export from [`AuthPages.tsx`](../frontend/src/signal/pages/AuthPages.tsx))
- **What it shows.** Two-step onboarding: pick a hard-coded sandbox preset (REST API / React component / Python CLI) then optionally link a GitHub App. Stashes the chosen spec into `sessionStorage` (`signal:pending-spec`) so `/compose` picks it up.
- **APIs called.** `api.getGitHubAppInstallUrl()`.
- **Tier gating.** None.

### `/forgot-password` - Forgot password

- **Page:** `ForgotPasswordPage` (named export from [`ExtraPages.tsx`](../frontend/src/signal/pages/ExtraPages.tsx))
- **What it shows.** Email field; on submit kicks off a recovery email.
- **APIs called.** `api.authForgotPassword(email)`.
- **Tier gating.** None.

### `/reset-password` - Reset password

- **Page:** `ResetPasswordPage` (named export from [`ExtraPages.tsx`](../frontend/src/signal/pages/ExtraPages.tsx))
- **What it shows.** New-password form (token from `?token=`). On success redirects to `/login`.
- **APIs called.** `api.authResetPassword(token, password)`.
- **Tier gating.** None.

### `/accept-invite` - Accept invite (cross-org `ProjectMember`)

- **Page:** `AcceptInvitePage` (named export from [`ExtraPages.tsx`](../frontend/src/signal/pages/ExtraPages.tsx))
- **What it shows.** Reads `?token=` and asks the user to accept. Requires email match server-side.
- **APIs called.** `api.acceptProjectInvite(token)`.
- **Tier gating.** None at the UI level; backend enforces email match.

### `/auth/callback` - OAuth callback

- **Page:** [`frontend/src/signal/pages/AuthCallbackPage.tsx`](../frontend/src/signal/pages/AuthCallbackPage.tsx)
- **What it shows.** Spinner / error message. Receives JWT tokens from the backend redirect, stores `access_token` in `localStorage`, explicitly strips any legacy `refresh_token` from `localStorage` (the refresh token now lives in an HttpOnly cookie). Preserves marketing-site `?spec=` through to `/home`.
- **APIs called.** Indirect - calls `loadMe()` from the auth store, which hits `api.authMe()`.
- **Tier gating.** None.

---

## Public marketing & legal surfaces

These render without auth. They share the lightweight `LegalShell` wrapper (from `LegalPages.tsx`) or stand alone with `AuthLayout`.

### `/pricing` - Public pricing

- **Page:** `PublicPricingPage` (named export from [`ExtraPages.tsx`](../frontend/src/signal/pages/ExtraPages.tsx))
- **What it shows.** Hard-coded pricing tiers (FREE / TEAM / ENTERPRISE) intended for prospects. Authenticated users land on `/plans` instead.
- **APIs called.** None - static content.
- **Tier gating.** None.

### `/terms`, `/privacy`, `/dpa` - Legal trio

- **Pages:** `TermsPage`, `PrivacyPage`, `DpaPage` (named exports from [`LegalPages.tsx`](../frontend/src/signal/pages/LegalPages.tsx))
- **What they show.** Standard Terms of Service, Privacy Policy, and Data Processing Addendum. Copy-only - no interactive elements.
- **APIs called.** None.
- **Tier gating.** None.

### `/shared/:token`, `/reports/shared/:token`, `/projects/snapshot/:token` - Public shared snapshots

- **Page:** `SharedPage` (named export from [`MiscPages.tsx`](../frontend/src/signal/pages/MiscPages.tsx))
- **What it shows.** Public read-only viewer for a shared report or project snapshot - no login required. Renders headline verdict + meta. The page predates the unification pass and uses 90/70 verdict thresholds rather than the standard 85/70.
- **APIs called.** `api.getSharedReport(token)`.
- **Tier gating.** None - the token is the authn surface. Backend tier-gates link **creation**, not consumption.

---

## Component primitives surfaced across pages

Most pages compose the same building blocks. Knowing where they live helps if you need to debug or extend the chrome:

| Primitive               | File                                                                                       | Used on                              |
| ----------------------- | ------------------------------------------------------------------------------------------ | ------------------------------------ |
| `Feed`, `Card`, `Row`, `Chip`, `StatusPill`, `Button`, `KvList`, `TabsStrip` | [`Primitives.tsx`](../frontend/src/signal/components/Primitives.tsx) | Every page                            |
| `LiveHero`, `ScoreCard`, `StreamCard`, `PrCard`, `ComposerCard`, `TaskLine` | [`Compose.tsx`](../frontend/src/signal/components/Compose.tsx) | `/compose`, `/live`, `/result`, project variants |
| `CostProgressBar`        | [`CostProgressBar.tsx`](../frontend/src/signal/components/CostProgressBar.tsx)          | `/live`, `/result`                    |
| `Spinner`, `LoadingState`, `SkeletonRow`, `InlineLoader` | [`Loaders.tsx`](../frontend/src/signal/components/Loaders.tsx)                      | Every page (replaces bare "Loading...") |
| `SpecChat`               | [`SpecChat.tsx`](../frontend/src/signal/components/SpecChat.tsx)                        | `/compose`, `/projects/:id`           |
| `Markdown`               | [`Markdown.tsx`](../frontend/src/signal/components/Markdown.tsx)                        | `SpecChat`, `ProjectChatPage`, validation report |
| `Modal`, `ConfirmModal`, `PromptModal` | [`Modal.tsx`](../frontend/src/signal/components/Modal.tsx)            | Every destructive action               |
| `Glossary`               | [`Glossary.tsx`](../frontend/src/signal/components/Glossary.tsx)                        | `/plan`, glossary terms inline         |
| `FileTree` + `FileIcon`  | [`FileTree.tsx`](../frontend/src/signal/components/FileTree.tsx), [`FileIcon.tsx`](../frontend/src/signal/components/FileIcon.tsx) | `/result` Code tab, `/projects/:id/code` Browse |
| `CodeView`               | [`CodeView.tsx`](../frontend/src/signal/components/CodeView.tsx)                        | `/result` Code tab + diff, project Browse |
| `BrandLogos`             | [`BrandLogos.tsx`](../frontend/src/signal/components/BrandLogos.tsx)                    | `/integrations`, `/login`              |
| `useProjectMeta(id)`     | [`useProjectMeta.ts`](../frontend/src/signal/hooks/useProjectMeta.ts)                    | All `/projects/:id/*` pages            |
| `useSignalTheme(ref)`    | [`useSignalTheme.ts`](../frontend/src/signal/hooks/useSignalTheme.ts)                    | `Shell.tsx` (theme toggle)             |
| `toRunView`, `relTime`, `statusToDot`, `scoreKind`, `runTitleFor` | [`runUtils.ts`](../frontend/src/signal/lib/runUtils.ts) | All run-row pages                      |
| Verdict derivation       | [`lib/verdict.ts`](../frontend/src/lib/verdict.ts) - `deriveVerdict(signals)`           | `ResultPage`, project Runs inline detail |
| Glossary copy            | [`lib/glossary.ts`](../frontend/src/lib/glossary.ts) - 13 tooltip entries               | `Glossary` tooltips                    |
| Status color maps        | [`lib/constants.ts`](../frontend/src/lib/constants.ts)                                  | Status dots / chips                    |
| Tech-stack detection     | [`lib/techDetect.ts`](../frontend/src/lib/techDetect.ts) - 19 tech keyword defs         | Run cards, run-detail headers          |
| API client               | [`lib/api.ts`](../frontend/src/lib/api.ts) - ~3.6k LOC of typed REST helpers            | Every page                             |

---

## Legacy URLs (auto-redirected)

`App.tsx::LegacyRedirect` rewrites old shell/V3/V4 paths to their Signal equivalents. If you have bookmarks from before May 2026, they will continue to work:

| Legacy path                              | Redirects to                  |
| ---------------------------------------- | ----------------------------- |
| `/signal/*`                              | strip the `/signal` prefix    |
| `/v3/*`, `/v4/*`                         | strip the prefix              |
| `/p/empty`                               | `/projects/empty`             |
| `/p/:id/r/...`                           | `/projects/:id/runs/...`      |
| `/p/...`                                 | `/projects/...`               |
| `/projects/:id/executions(...)`          | `/projects/:id` (executions v3 launch system being rebuilt) |
| `/dashboard`                             | `/insights`                   |
| `/legacy/billing`                        | `/billing`                    |
| `/exec`, `/exec-demo`                    | `/insights`                   |
| `/accounting`, `/invoices`               | `/billing`                    |
| `/team`, `/team/:userId`                 | `/org-members`                |
| `/org/projects`                          | `/projects`                   |
| `/governance/audit`                      | `/audit`                      |
| `/runs/new`                              | `/compose`                    |
| `/runs/new/sandbox`                      | `/welcome`                    |
| `/admin/analytics|pricing|...`           | `/admin`                      |
| `/claim`                                 | `/welcome`                    |
| `/landing` or empty                      | `/home`                       |
| Any other unmatched path (`*`)           | `/home`                       |

---

## Tier feature matrix (consolidated)

| Surface                          | FREE         | PRO          | TEAM         | ENTERPRISE   | SUPER_ADMIN |
| -------------------------------- | ------------ | ------------ | ------------ | ------------ | ----------- |
| `/home`, `/runs`, `/compose`, `/result`, `/live`, `/plan` | yes | yes | yes | yes | yes |
| `/plans`                         | yes          | yes          | yes          | yes          | yes         |
| `/projects` + all sub-pages      | yes          | yes          | yes          | yes          | yes         |
| Project chat (`/projects/:id/chat`) | upgrade card | upgrade card | yes          | yes          | yes         |
| Project external invite UI (V198) | hidden      | hidden       | gated on org flag + ORG_ADMIN | gated on org flag + ORG_ADMIN | yes (bypass) |
| Project standards report         | hidden       | hidden       | yes          | yes          | yes         |
| `/templates`                     | yes          | yes          | yes          | yes          | yes         |
| `/library` (BETA)                | yes          | yes          | yes          | yes          | yes         |
| `/migrations` (BETA)             | yes          | yes          | yes          | yes          | yes         |
| `/deployments` (BETA)            | hidden       | yes          | yes          | yes          | yes         |
| `/insights`                      | yes          | yes          | yes          | yes          | yes         |
| `/billing`, `/topup`             | yes          | yes          | yes          | yes          | yes         |
| `/integrations`                  | yes          | yes          | yes          | yes          | yes         |
| `/api-keys` (BYOK)               | upgrade card | yes          | yes          | yes          | yes         |
| `/org-members`                   | yes (read)   | yes (read)   | yes (manage if ORG_ADMIN) | yes (manage if ORG_ADMIN) | yes |
| `/org/settings`                  | yes (read)   | yes (read)   | yes (edit if ORG_ADMIN) | yes (edit if ORG_ADMIN) | yes |
| `/account`                       | yes          | yes          | yes          | yes          | yes         |
| `/notifications`, `/search`      | yes          | yes          | yes          | yes          | yes         |
| `/audit`                         | hidden       | hidden       | yes          | yes          | yes         |
| `/admin`                         | hidden       | hidden       | hidden       | hidden       | yes         |
| `/trust`                         | yes          | yes          | yes          | yes          | yes         |
| Re-run on different provider     | yes          | yes          | yes          | yes          | yes         |
| Start preview from result        | upgrade button | yes        | yes          | yes          | yes         |
| Cost cap (per-run, max)          | $3           | $25          | $25          | $1000        | platform default |
| BYOK exempt from cost cap        | no           | conditional  | yes          | yes          | n/a         |
| Run quota                        | 5/mo         | grandfathered | per-seat   | unlimited    | n/a         |

> "Hidden" means the sidebar entry is not rendered; direct navigation may still surface an upgrade card or an empty state. Backend authorisation always re-enforces - the UI gates are advisory.

---

## Notes on stale or out-of-band behaviours

A few production-only details surfaced by the page-by-page audit:

- **Verdict threshold drift.** `SharedPage` (`/shared/:token`) and the inline run-detail pane on `ProjectRunsPage` still use 90/70 thresholds, predating the 85/70 unification. The Result page and `runUtils.scoreKind` are the source of truth.
- **Hidden routes.** `/more` is reachable via the mobile "more" tab but never appears in any sidebar group. It functions as a mobile-only aggregator.
- **`AccountPage` GDPR-export error path** uses `window.alert` instead of an in-place error banner - one of the few remaining legacy `alert` call sites in Signal.
- **Library / Migrations pages** ship with hard-coded arrays today. Backend wrappers (`api.listLibrary`, `api.listMigrations`, etc.) exist and are typed, but the BETA-labeled Signal pages do not call them yet.
- **Raw `fetch` callers** (not using typed `api.*` wrappers): `ProjectMembersPage` (invites + share-links), `ProjectChatPage` (chat POST), `ProjectCodePage` (code-reviews submit + list), `AdminPage` (executions/reconcile), `BillingPage` (payment-history), `TopupPage` + `PlansPage` (purchase + subscribe). Working today; candidates for normalisation into `lib/api.ts`.
- **Theme attributes.** `main.tsx` writes `data-signal-theme` to `<html>` (consumed by Signal's CSS in `signal/signal.css`). `tokens.css` separately checks `[data-theme='light']` - the two attributes do not overlap, and the Tailwind `dark:` variant is therefore silent unless other code sets `data-theme='dark'`.

---

## Going further

| To do this                                          | Read this                                                                                          |
| --------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| Add a new authenticated route                       | [`frontend/src/App.tsx`](../frontend/src/App.tsx); [`Shell.tsx`](../frontend/src/signal/components/Shell.tsx) `buildSidebar()` |
| Add a new API helper                                | [`frontend/src/lib/api.ts`](../frontend/src/lib/api.ts) - group with sibling endpoints              |
| Add or change a sidebar tier gate                   | [`Shell.tsx`](../frontend/src/signal/components/Shell.tsx) - `buildSidebar({tier, isSuperAdmin, canDeploy})` |
| Add a glossary tooltip term                         | [`frontend/src/lib/glossary.ts`](../frontend/src/lib/glossary.ts) + use `<Glossary term=...>` inline |
| Build a new run-row surface                         | Reuse `Row` + `runUtils.toRunView` + `statusToDot`/`scoreKind`/`runTitleFor`                        |
| Add a new modal / replace `window.confirm`          | [`Modal.tsx`](../frontend/src/signal/components/Modal.tsx) - `ConfirmModal` / `PromptModal`         |
| Add a new design token                              | [`frontend/src/tokens.css`](../frontend/src/tokens.css) + [`signal/signal.css`](../frontend/src/signal/signal.css) (Signal-scoped) |

Treat the route table in `App.tsx` and the API helper list in `lib/api.ts` as the canonical contract. Everything in this manual derives from them.
