# Enterprise UX Audit — Project & Run surfaces

> Auditor: code-only walkthrough on `feature/worker-runaway-fixes`.
> Scope: ExecuteSpec frontend (`frontend/src/`) — Project lifecycle, Run lifecycle, cross-cutting friction.
> Method: read sources, cross-reference `frontend/CLAUDE.md`, walk a fresh enterprise org-admin's mental model.

## 1. Executive summary

The dominant intuitiveness gap is **two parallel, structurally incompatible product surfaces sharing the user's URL space**, with no signposting between them and no consistent place to do the same job. After login, a user with at least one project lands on `/projects/{id}` (the V3 Slack/Linear chat-thread surface in `V3Shell`) — but every meaningful Project capability except submitting a follow-up run lives at `/projects/{id}/dashboard` (the V2 four-tab Pulse/Repos/Runs/Code/Chat shell with its own breadcrumb chrome). The chat-thread page knows this — it has a "Chat with project →" button that hard-jumps to `/projects/{id}/dashboard?tab=chat` when the user wants the actual chat tools, plus an "+ Attach repo" popover that does its own thing instead of routing into the Repos tab. Once the user is in the dashboard, there's no way back to the V3 surface, no acknowledgment that they ever existed, and the Runs tab's "Run detail" sub-tab uses a third UI (`InlineRunDetail`) that is materially different from the standalone `/runs/{id}` view (`RunDetail`) which itself ships dead code paths gated behind feature flags. The same data (last run, score, signal chips) renders three times in three styles. Until the surface split is resolved, every other finding below is downstream of this one — users do not feel the product is "the project workbench"; they feel it is "two products glued together."

Beyond the split, the second-tier complaints cluster around: (a) friction-laden first-run path that requires linking a repo, generating architecture, and configuring a GitHub PAT before a Launch button stops being grayed out, with no progress indicator across those gates; (b) a Run detail surface with multiple competing scoring narratives (Verified, Est., Verdict pill, Status reason, four-band ScoreCard, two banners) that the user has to mentally collapse; (c) RBAC visibility holes — the dashboard does not differentiate ORG_ADMIN vs DEVELOPER vs VIEWER affordances, so non-admins see CTAs that 403; (d) deep-link state that stops short of actually being deep-linkable (`?tab=foo` is read once on mount but never written back, so URLs don't reflect the tab the user is looking at).

## 2. Findings by surface

### 2.1 Project surface (creation → workbench)

**P-01 [P0] Two parallel project surfaces, no clear winner**
- *What the user sees:* After login, V3HomeRedirect (`pages/V3HomeRedirect.tsx:65`) routes to `/projects/{firstProjectId}` which renders `V3ProjectHome` — a Slack-like chat thread with a Composer pinned to the bottom. There is no tab strip and no health/repo/run rollup. To do anything beyond submit a follow-up run, the user must click "Chat with project →" or manually navigate to `/projects/{id}/dashboard`.
- *Why it's confusing:* The two surfaces have different chrome (`V3Shell` vs `AppLayout`), different IA (chat thread vs 4 tabs), different data (V3 reads `useV3ProjectStore`, dashboard reads `useProjectStore`), and different terminology ("bubbles" vs "Pulse"). An enterprise user being shown the product cannot describe "what page does X live on?" because the answer is "depends which subdomain of the app you happened to land on."
- *Concrete fix:* Pick one. Either retire V3ProjectHome by making `/projects/:slug` redirect to `/projects/:slug/dashboard` (cheapest), or fold the chat composer into PulseTab as the hero affordance and delete V3ProjectHome. The current "Chat with project →" link in `V3ProjectHome.tsx:132-149` admits the conflict — the chat surface there is feature-poorer than the dashboard's chat tab. Cleanup target: `App.tsx:260-262` (drop the V3ProjectHome route) + remove `V3HomeRedirect.tsx:65` redirect-to-V3 fork.

**P-02 [P0] First-run wall: 4 sequential gates, no progress UI**
- *What the user sees:* New project → land on PulseTab empty state → "Link a repo" → AddRepoModal → pick existing or create new → if no GitHub PAT, see "No repos visible. Set a GitHub PAT + owner under Settings → GitHub, then Refresh" (`AddRepoModal.tsx:222-225`) → leave Project, configure PAT in Settings, come back → re-open AddRepoModal → link a repo → go to Code tab → click "Generate" architecture → wait for codemap → only THEN can Launch.
- *Why it's confusing:* Nothing tells the user this is a 4-step path. The "Launch" button at the project header is grayed out with `Link a repo to enable` (`ProjectDashboardPage.tsx:634-637`), but that's only one of four gates. There's no checklist, no progress bar, no "you are step 2 of 4". Each gate feels terminal until the user happens to discover the next.
- *Concrete fix:* Replace the empty-state PulseTab with a 4-step progress checklist component: (1) Link a repo, (2) Add GitHub PAT, (3) Generate architecture, (4) First Launch. Each row collapsed when complete. Add to `EmptyProjectLanding.tsx` so it shows on `noRuns && repoCount === 0`. The `EmptyProjectLanding` already has the right hooks; it just needs the explicit ordered list.

**P-03 [P1] AddRepoModal does not surface the PAT gap until the user clicks**
- *What the user sees:* Empty Project → "Link a repo" → AddRepoModal opens with empty list and no warning. Buried in the empty-state copy at `AddRepoModal.tsx:223`: "Set a GitHub PAT + owner under Settings → GitHub, then Refresh."
- *Why it's confusing:* The modal pre-loads remote repos via `listGitHubRepos` (`AddRepoModal.tsx:43-49`), catches 401/4xx silently, and shows "No repos visible." The user cannot tell whether they have zero repos in their org or whether their PAT is missing.
- *Concrete fix:* In `AddRepoModal.tsx` distinguish "PAT missing" (call `useAuthStore`, check `user.hasGithubPat`) from "0 repos returned." When PAT missing, render the M-008 inline `AddGitHubPatDrawer` link directly inside the modal — same pattern NewRunModal already uses (`NewRunModal.tsx:555-567`). Don't make the user leave Project to fix Project.

**P-04 [P1] Architecture generation has no kickoff CTA on the empty state**
- *What the user sees:* After linking a repo, the user lands back on PulseTab. PulseTab's HealthCard says "1 repo linked, codemaps current." Nothing prompts them to generate the architecture document. The only path is: switch to Code tab → Architecture sub-tab → click "Generate."
- *Why it's confusing:* The Launch button does work post-link without an architecture doc, but the planner runs blind without it (no `{{PROJECT_BRIEF}}` injection). Users who skip this step get worse plans; nothing in the UI tells them.
- *Concrete fix:* Add a HealthCard row "Architecture: not generated — Generate now" with a click-through to `/projects/{id}/dashboard?tab=code&sub=architecture`. Edit `PulseTab.tsx:300-350`. Or, more aggressively, auto-trigger arch generation on first repo link and show a status chip ("Analysing your repos…").

**P-05 [P1] Repos tab requires manual click; Repos chip in header buries it**
- *What the user sees:* The project header has a `Repos · N` chip at `ProjectDashboardPage.tsx:291-316` that opens AddRepoModal directly, but it does NOT switch to the Repos tab. The user adds a repo and is left wherever they were, with no confirmation that the repo is linked unless they happen to switch to Repos.
- *Why it's confusing:* The chip's behavior depends on `repoCount` — when `repoCount === 0` it pops AddRepoModal; when `> 0` it… also pops AddRepoModal. There's no "browse linked repos" affordance that doesn't require a tab switch.
- *Concrete fix:* Split the chip into two: clicking the chip switches to the Repos tab (browse mode); a dedicated `+ Add` icon button next to it pops AddRepoModal. `ProjectDashboardPage.tsx:291` change.

**P-06 [P2] Project creation jumps to dashboard, but dashboard has no onboarding**
- *What the user sees:* `OrgProjectsPage.handleCreate` (line 461) navigates to `/projects/{id}/dashboard` immediately after create. The dashboard renders EmptyProjectLanding with two CTAs: "Start a run" (disabled until a repo is linked) and "Link a repo." So the new project's first paint is two greyed-out buttons.
- *Why it's confusing:* The OrgProjectsPage starter templates (`OrgProjectsPage.tsx:580-606`) show 3 representative project shapes ("API + backend service" etc.). After project creation, that context is lost — the user lands on a generic empty state.
- *Concrete fix:* Pass the chosen template through navigation state to `EmptyProjectLanding`, render a tailored hero ("API project — start by linking your service repo"). `OrgProjectsPage.tsx:461` should `navigate(`/projects/{id}/dashboard`, {state: {fromTemplate: t}})`.

**P-07 [P2] Sidebar Projects section duplicates OrgProjects without explanation**
- *What the user sees:* Sidebar shows `Projects` section header (linkable to `/org/projects`) plus per-project entries (`Sidebar.tsx:240-282`). Clicking the section header takes you to OrgProjectsPage; clicking a project takes you to the project dashboard. There's a "Manage projects" Link tooltip (line 243) but nothing visually distinguishes the section header.
- *Why it's confusing:* The user has three ways to find a project: sidebar list, `/org/projects`, `/home` redirect. They look different and have different feature sets (member management lives only in `/org/projects`).
- *Concrete fix:* Add a tiny "Manage" link on hover next to the section header. Or, simpler, make the section header a true heading not a link, and add a `+` button beside it that opens "New project" inline. `Sidebar.tsx:241-254`.

**P-08 [P2] Project dashboard chrome tells you you're at "Projects / Dashboard," not the project name**
- *What the user sees:* `Header.tsx:15` returns `{primary: 'Projects', secondary: 'Dashboard'}` for any `/projects/...` path. The breadcrumb is "ExecuteSpec / Projects / Dashboard" — but the project name only appears in the page-body header at `ProjectDashboardPage.tsx:281-284`.
- *Why it's confusing:* Switching browser tabs, the breadcrumb is the same for every project. Mobile users (no body header visible at scroll) lose project identity entirely.
- *Concrete fix:* Pass the project name to the Header via Zustand (`useUiStore` already exists). `Header.tsx:14` accept project name override; `ProjectDashboardPage.tsx:124` set it. Breadcrumb becomes "ExecuteSpec / Projects / {Acme-Backend}".

**P-09 [P2] Top-level "Repos" tab promotion is confusing alongside Code → Architecture**
- *What the user sees:* The IA cleanup notes (`ProjectDashboardPage.tsx:49-53`) say Repos was promoted from Code → Repos sub-tab to a top-level tab. But `CodeTab.tsx:34-42` still shows Architecture / Health / Tech debt / Standards / Contracts / ADRs / Invariants. So the user has two related navigations: top-tab Repos, sub-tab Architecture/Health/etc. Clicking "Architecture" doesn't tell you which repo's arch you're seeing — `ArchitectureViewer.tsx` shows the cross-repo synthesis.
- *Why it's confusing:* Users with 1 repo find this fine. Users with 5 repos cannot tell whether to manage repo X under Repos tab or under Code → Architecture (which produces a cross-repo doc but also lists per-repo subdocs).
- *Concrete fix:* Either (a) add a per-repo arch viewer link inside RepoCard (`/projects/{id}/repos/{repoId}/architecture` exists in API), or (b) explicit copy on the Architecture sub-tab: "Cross-repo architecture. For per-repo HLD+LLD, see Repos → click repo." Edit `ArchitectureViewer.tsx:87`.

**P-10 [P2] Generate Docs panel is in MoreDrawer, not surfaced from the project**
- *What the user sees:* `MoreDrawer.tsx:135` mounts `GeneratedDocsPanel` only when the user clicks the More button → "Generated docs" panel. There's no link from PulseTab. Users who don't know the feature exists never find it.
- *Why it's confusing:* "Generated Docs" shipped in v2.5.12 as a marquee feature. Hiding it 2 clicks deep contradicts the launch.
- *Concrete fix:* Add a "Generated Docs" tile to PulseTab below HealthCard, showing "Explanation: not generated · Setup: not generated" with Generate buttons. `PulseTab.tsx:108`.

**P-11 [P3] OrgProjectsPage "Top provider" KPI tile is dead weight**
- *What the user sees:* OrgProjectsPage hero strip has a "Top provider" tile (`OrgProjectsPage.tsx:553-565`). This is the most-used provider across all projects.
- *Why it's confusing:* Operationally meaningless. An enterprise admin wants "monthly spend," "credits remaining," "members near quota" — not "you mostly use Claude."
- *Concrete fix:* Replace with a meaningful KPI: `Spend MTD` from `dashboard.totalCostUsd`, or `Credits remaining` from billing-status. Pull from `fetchBillingStatus` already used in `Sidebar.tsx:93`.

**P-12 [P3] EmptyProjectLanding sample gallery loads onboarding samples regardless of context**
- *What the user sees:* `EmptyProjectLanding.tsx:55-61` always calls `listOnboardingSamples()`. These are the v2.6.1 Wave-2 starter specs (web-api/frontend/cli) — generic, not project-aware. An enterprise user with a Java backend project sees a "Build a CLI tool" sample.
- *Why it's confusing:* Project's `aiProvider` and linked-repo stack are known; the sample list doesn't filter by them.
- *Concrete fix:* Filter samples by detected stack from `repos[0].detectedStack`. `EmptyProjectLanding.tsx:55` accept `repos` prop, intersect with `s.category`.

### 2.2 Run surface (submit → monitor → review)

**R-01 [P0] Run detail has THREE different layouts depending on entry point**
- *What the user sees:* `/runs/{id}` → `RunDetail.tsx` (full chrome, action bar, NewRunModal-style header). `/projects/{id}/dashboard?tab=runs` → `InlineRunDetail.tsx` (compressed, no Pause/Cancel, different layout). `/projects/{slug}/runs/{runShort}` → `V3RunDetail` (chat-bubble result card).
- *Why it's confusing:* Same run, three views. The InlineRunDetail (`components/project/InlineRunDetail.tsx`) does NOT include the Pause/Cancel/Retry-failed-tasks buttons, the Re-run flow, the MobileCollapsibleReports, or the live log tab — just a compressed strip with Sync/Reset/Push buttons. A user inspecting a non-terminal run from inside the project dashboard cannot pause it without navigating to the standalone run page.
- *Concrete fix:* Unify on RunDetailTabs. Replace `InlineRunDetail.tsx`'s body with `<RunDetailTabs>` mounted with the run prop. Or make `?tab=runs&sub=detail` deep-link to `/runs/{id}` in a frame. Cleanup: `components/project/InlineRunDetail.tsx` (~430 LOC), reduce to a thin wrapper.

**R-02 [P0] Plan approval gate buries the plan**
- *What the user sees:* When status === AWAITING_APPROVAL, the GateBanner shows "This spec will use N credits (~$X)" with Approve/Reject buttons (`RunDetail.tsx:441-471`). The user must click into the Plan tab in RunDetailTabs to see the actual task decomposition. Approval happens before they read the plan.
- *Why it's confusing:* Enterprise users want to see *what they're approving*. The banner gives a credit count but no task list, no per-task complexity, no provider per task. Edit Plan is gated behind `V2_LEGACY_UI.editRunPlan = false` (`constants.ts:144`) — there's no way to push back on the plan.
- *Concrete fix:* Inline the plan summary into the gate banner. `RunDetail.tsx:441` — render a 5-line collapsed plan preview ("Task 1: Add /api/health endpoint · LOW · Anthropic LIGHTWEIGHT…") above Approve/Reject. Reuse `PlanTab` rendering from RunDetailTabs.

**R-03 [P0] Run page uses three competing scoring narratives**
- *What the user sees:* On COMPLETE runs, the user sees:
  1. The header status badge (e.g. "Complete · with warnings")
  2. The review banner: "92/100 verified · Est. 88" (`RunDetail.tsx:573-613`)
  3. ValidationReportPanel header: VerdictPill (Shippable / Needs review / Blocked) + "Has issues" chip + "Ver 92/100" chip (`ValidationReportPanel.tsx:312-348`)
  4. SignalBreakdown chip below: "Build ✓ · Tests 73/73 · Reviewer CLEAN"
  5. Status reason narrative below that
  6. Expanded ScoreCard with 6 dimensional bars + Overall + Verdict pill + Glossary on "wiring pass"
- *Why it's confusing:* Six surfaces all claiming to summarize the same run. The user has to read all of them to be sure they agree. Plan 1 #3 (per `frontend/CLAUDE.md`) collapsed the dual numeric scores into VerdictPill but kept the numeric chip "for back-compat" — meaning both render today.
- *Concrete fix:* One header surface. Drop the review banner duplication in `RunDetail.tsx:573-613` (Plan 1 already moved this signal into VerdictPill). Drop the "Has issues" chip in `ValidationReportPanel.tsx:325-331` (subsumed by VerdictPill). Keep VerdictPill, SignalBreakdown, ScoreCard. Net: 6 → 3.

**R-04 [P1] NewRunModal forces a binary Prototype/Production toggle without explanation**
- *What the user sees:* NewRunModal toggles between "Prototype Mode" (auto-approved, fast, auto-preview) and "Production" (plan review, full validation, code review) — `NewRunModal.tsx:480-524`. Default is Prototype.
- *Why it's confusing:* "Prototype" sounds throwaway; an enterprise customer wants real code. But Prototype Mode is the default. The label fights the user's mental model. The block "Auto-approved, fast models, auto-preview" doesn't explain that Prototype skips the cost-control gate.
- *Concrete fix:* Rename Prototype → "Quick (skip review)" and Production → "Reviewed (full pipeline)." Default flip to Reviewed for orgs with `tier === 'TEAM' || 'ENTERPRISE'`. `NewRunModal.tsx:46-49` + label change.

**R-05 [P1] "Build My Spec" button leads to a chat-mode UI users don't expect**
- *What the user sees:* In NewRunModal compose mode, below the textarea there's a "Build My Spec" button (`NewRunModal.tsx:457-469`) that swaps the modal into a chat with the AI. The user types more, AI replies, eventually emits "REQUIREMENT SUMMARY:" which the user clicks "Use as Goal →" to extract.
- *Why it's confusing:* The button copy is terse and doesn't tell the user this is a side-conversation that won't submit a run. It also has its own message limit (50). Users who click it expecting to launch are surprised.
- *Concrete fix:* Rename "Build My Spec" → "Refine spec with AI assistant". Add a help tooltip. `NewRunModal.tsx:467`.

**R-06 [P1] Live log is hidden one tab deep**
- *What the user sees:* RunDetailTabs has Overview / Plan / Timeline / Live Log / Code / Preview / (Chat). Default tab is Overview. While a run is WORKING, Overview shows token totals + cost — the user has to click "Live Log" to see what's happening.
- *Why it's confusing:* Enterprise users monitoring a long run want a tail view. A separate tab forces a click and a context switch.
- *Concrete fix:* On non-terminal status, default the active tab to "Live Log" (or Timeline). `RunDetailTabs.tsx:93` — read run status and seed `activeTab`.

**R-07 [P1] Re-run only opens NewRunModal pre-filled — does not actually re-run**
- *What the user sees:* "Re-run" button (`RunDetail.tsx:217-234`) doesn't dispatch a new run; it sets `pendingNewRunConfig` and shows a toast "Re-run configuration loaded. Adjust and launch when ready." The user must then find/open NewRunModal manually.
- *Why it's confusing:* The button label is "Re-run." Users expect it to launch.
- *Concrete fix:* Either rename to "Re-run (edit first)" or open NewRunModal automatically. `RunDetail.tsx:232` — also call `setNewRunModalOpen(true)`.

**R-08 [P1] Apply suggestion / RunConversation has dead/broken paths**
- *What the user sees:* RunConversation (`RunConversation.tsx`) has buttons for "Iterate as New Run," "Apply changes" (creates a new run), and the validation-issue auto-fix flow. The Iterate button calls `setPendingNewRunConfig` but the apply path uses `applyToRun` which still works. Plan-edit and Implement-suggestions are gated to false in `V2_LEGACY_UI`.
- *Why it's confusing:* Enterprise users see clearly-labeled "Apply Test Fixes" or "Implement Suggestions" buttons in the panel — and they fail with 410 Gone. Today the buttons are hidden via flags but the surrounding copy still mentions the missing actions in places. `RunConversation.tsx:59-67` builds a prompt assuming auto-fix works.
- *Concrete fix:* Sweep `V2_LEGACY_UI.applyTestFixes / implementSuggestions / rework` consumers. If gated false, suppress the prompt-building affordance entirely. `RunConversation.tsx:57` shouldn't be reachable when the apply button is hidden.

**R-09 [P2] Run header contradicts itself when PARTIAL build passes**
- *What the user sees:* `RunDetail.tsx:336-349` has a special case: when `finalBuildPassed(activeRun)` AND status === 'PARTIAL', it shows a custom "Complete · with warnings" chip in amber. Otherwise it shows StatusBadge with statusReason tooltip.
- *Why it's confusing:* The status is *still* PARTIAL on the run record but the chip says Complete. Two ways to think about the same run.
- *Concrete fix:* Make backend authoritative. If build passes, terminal status should already be COMPLETE with `statusReason=CLEAN_WITH_INTERMEDIATE_WARNINGS` per V107. The frontend special-case suggests historical data. Audit V108 backfill; remove `finalBuildPassed(activeRun) && status === 'PARTIAL'` branch when DB is consistent.

**R-10 [P2] Push & open PR exists in two forms with different defaults**
- *What the user sees:* RunDetail has a "GitHub" / "Bitbucket" action button that triggers `pushToGitHub` or `pushToScm` (`RunDetail.tsx:166-197`). InlineRunDetail has its own `doPush` (`InlineRunDetail.tsx:99-113`) that infers `repoMode` from `run.repoUrl`.
- *Why it's confusing:* Same run, two different push paths with different code. If the user pushes from the dashboard view it might not match what the standalone view would have done.
- *Concrete fix:* Single helper. Move push logic into a `usePushRun(runId)` hook, used by both surfaces. Files: `RunDetail.tsx:166`, `InlineRunDetail.tsx:99`.

**R-11 [P2] Failed-tasks retry button is buried in the action bar**
- *What the user sees:* When a run is COMPLETE/FAILED with some PARTIAL tasks, RunDetail shows a "Retry Failed (N)" button (`RunDetail.tsx:708-718`) in the action bar. The button only appears when `failedTasks.length > 0`, but the action bar is horizontally scrollable.
- *Why it's confusing:* The most-clicked recovery action on a partial run is hidden in a scrollable action bar between Re-run and a workspace download.
- *Concrete fix:* Promote retry-failed-tasks into the validation report panel — it has the most context (it lists which tasks failed). `ValidationReportPanel` should grow a "Retry these N failed tasks" CTA inline with the issue list.

**R-12 [P2] Per-run PR list lives in two places**
- *What the user sees:* RunDetailTabs Overview tab shows `getRunPrs(runId)` PRs. Project dashboard's Runs tab → "Pull requests" sub-tab shows `ProjectPrRollup` which lists all PRs for the project across runs.
- *Why it's confusing:* PRs are interesting "by run" (what shipped?) and "by project" (what's open?). Both views are real but neither links to the other. A user looking at a PR row in ProjectPrRollup has to copy the run ID to navigate.
- *Concrete fix:* Each PR row should link to the originating run + its detail tab. `ProjectPrRollup.tsx` add `<Link to={`/runs/{prRow.runId}`}>` per row.

**R-13 [P3] "Archive run" warning copy is too aggressive**
- *What the user sees:* Archive confirm modal says "This action is permanent and cannot be undone" + "Archived runs will be permanently deleted after 90 days" (`RunDetail.tsx:856-867`).
- *Why it's confusing:* Archive ≠ delete. Users archive to reduce list clutter; the modal makes it sound like a destructive deletion.
- *Concrete fix:* Soften: "Archive removes this run from your active list. Archived runs are read-only and auto-purge after 90 days. You can still open the run by URL." `RunDetail.tsx:858-867`.

### 2.3 Cross-cutting friction

**X-01 [P0] URL state is one-way — query params are read on mount, never written**
- *What the user sees:* `ProjectDashboardPage.tsx:108-111` reads `?tab=foo` *once* on mount via `useMemo`. Switching tabs (`setTab(t.key)`) does not update `window.location.search`. So after the user clicks Code → Repos → Architecture, the URL still says `?tab=overview`.
- *Why it's confusing:* Bookmarking the current view doesn't work. Sharing the URL with a teammate sends them to the dashboard's default tab. Browser back-button doesn't restore the previous tab.
- *Concrete fix:* On every `setTab/setRunsSub/setCodeSub`, push the new query string via `useNavigate({replace: true})` or `window.history.replaceState`. The drawer code already does this (`ProjectDashboardPage.tsx:218-222`); apply the pattern to all four state slices. Pattern from `MoreDrawer.tsx:241-258` (drawer-state event sync).

**X-02 [P0] No RBAC differentiation in Project surfaces**
- *What the user sees:* DEVELOPER and VIEWER roles see the same Project dashboard chrome as ORG_ADMIN. The Launch button, Generate Architecture, Reset Workspace, and Add Repo all appear actionable. Backend will reject with 403 only after click.
- *Why it's confusing:* Enterprise org admins onboarding their team report "my devs say it's broken" because the buttons appear to work and then 403. The dashboard makes no distinction. `ProjectDashboardPage.tsx` does NOT read `useAuthStore.user.orgRole` or project membership role.
- *Concrete fix:* Read project member role on dashboard mount (already wired via `getProjectMembers`). Disable + tooltip every admin-gated CTA for non-admins. `ProjectDashboardPage.tsx:613` (Launch) + `ProjectRepoManager.tsx:84` (Add repo) + `ArchitectureViewer.tsx:131` (Generate). Audit pattern: how `OrgProjectsPage.tsx:386-395` gates Archive on `isAdmin`.

**X-03 [P1] Audit trail discoverability is anemic**
- *What the user sees:* Project Activity feed lives in MoreDrawer → Activity panel (`MoreDrawer.tsx:137`). No top-level link. PulseTab shows last 5 events as a preview. No org-wide audit trail link from the project.
- *Why it's confusing:* SOC2-asking enterprise customers ask "where's the audit log for project X?" The answer is "open More drawer → Activity → set filters" — and the filters are by-event-type-comma-separated text input (`ActivityTab.tsx:84-89`), not chips.
- *Concrete fix:* Add an "Activity" top-level link in V3TopBar for the project (or a Project menu in AvatarMenu). Improve ActivityTab filters: replace comma-separated text input with multi-select chips by event type (PR_OPENED, PR_MERGED, RUN_FAILED, etc.). `ActivityTab.tsx:82-89`.

**X-04 [P1] Cost visibility is buried**
- *What the user sees:* PulseTab does not show cost. SummaryBar shows "spend $X" only when `totalCostUsd > 0` (`ProjectDashboardPage.tsx:526-528`) — so a fresh project hides it. Per-run cost is in RunDetailTabs Overview. Org-wide spend is at `/exec` which is gated to `canViewCostBreakdown` (PRO+).
- *Why it's confusing:* Enterprise admins want to know "how much did this project cost this month?" without leaving the project. The data exists; it's just split across three pages.
- *Concrete fix:* Add a "Spend MTD: $X" tile to PulseTab's hero row. Read from project dashboard endpoint (`useProjectStore.dashboard.totalCostUsd`). Surface in `PulseTab.tsx` ScoreCardCompact alongside the verified score, or as a 4th card in the hero.

**X-05 [P1] Team activity has no surface**
- *What the user sees:* Members are listed in OrgProjectsPage > expanded card > Members section. There's no "team activity" view (who's running what). RunsPage has a `viewer` filter by user but no leaderboard, no per-member quotas, no "this week's runs by Erin."
- *Why it's confusing:* Enterprise managers ask "who's been running things?" and get pointed to either the Audit log (events) or per-user dashboards at `/team/{userId}` (admin-only).
- *Concrete fix:* Add a Team panel under MoreDrawer that's actually built — currently it just says "Team management is available via the existing Project members page" (`MoreDrawer.tsx:140-143`). Pull RunsPage's per-user filter into a top-3-runners chart for the project.

**X-06 [P2] Sidebar Upgrade-to-Team CTA shows on FREE/PRO but TEAM has no upsell**
- *What the user sees:* `Sidebar.tsx:289-302` shows "Upgrade to Pro" or "Upgrade to Team" depending on tier. TEAM and ENTERPRISE see neither (and that's correct), but the legacy MAX → TEAM rename path means some grandfathered users see the wrong copy.
- *Why it's confusing:* Sidebar logic at line 101: `tier = (userTier ?? billingTier ?? '').toUpperCase().replace(/^MAX$/, 'TEAM')`. The replace is a workaround for a backend rename that happened in V141. It works but is brittle; new tiers will need new `replace` calls.
- *Concrete fix:* Move tier-coercion into `useAuthStore` selector, single source of truth. `Sidebar.tsx:101` becomes `const tier = useAuthStore(selectNormalizedTier)`.

**X-07 [P2] Theme toggle is a Sun/Moon icon with no system-default option**
- *What the user sees:* `Header.tsx:42-50` ThemeToggle button toggles dark ↔ light. There's no "match system."
- *Why it's confusing:* Enterprise customers running OS-level dark mode at night want auto-switch.
- *Concrete fix:* Add `system` as a third state. `useUiStore.theme: 'dark' | 'light' | 'system'`. Pre-paint logic should read `prefers-color-scheme` when `system`.

**X-08 [P2] No way to "exit" the V3ProjectHome chat thread**
- *What the user sees:* V3ProjectHome (`V3ProjectHome.tsx`) renders a chat thread with bubbles. The "+ Attach repo" + "Chat with project →" buttons are the only way out. No tabs, no breadcrumb back to OrgProjectsPage.
- *Why it's confusing:* The V3 surface has its own top-bar (`V3TopBar`) which has links to Runs/Library/Migrations but no "Back to project list."
- *Concrete fix:* Add a back-arrow inside V3ProjectHome leading to `/org/projects`. Or — see P-01 — retire V3ProjectHome.

**X-09 [P2] PostHog analytics calls are conditional on env var; no in-product opt-out**
- *What the user sees:* `App.tsx:158-178` calls `initAnalytics()` then `identify(user.id, ...)` with tier+orgId. PostHog is no-op when `VITE_POSTHOG_API_KEY` unset.
- *Why it's confusing:* GDPR / enterprise privacy review teams want a user-facing analytics toggle. None exists.
- *Concrete fix:* Add a `Settings → Privacy → "Send anonymous usage analytics"` checkbox that sets a localStorage flag; `analytics.ts` checks it before calling.

**X-10 [P2] Mobile experience for Project dashboard is degraded**
- *What the user sees:* SummaryBar is `flex-wrap` (`ProjectDashboardPage.tsx:535-548`). On mobile (<420px) it stacks 9 segments into 5 rows. Tab strip is `overflow-x-auto` (`ProjectDashboardPage.tsx:349`). Run sub-tabs same. PulseTab grid is `md:grid-cols-12` so on mobile the ScoreRing stacks below LastRunCard correctly, but HealthCard + ActivityCard rendering depth mean the user must scroll multiple screens to see the full Pulse.
- *Why it's confusing:* Mobile audit MOBILE_AUDIT.md (referenced in V3TopBar.tsx:21-30) flagged similar mobile issues elsewhere. Project dashboard wasn't fully audited.
- *Concrete fix:* Compact the SummaryBar to top-3 segments on mobile + a "more" chevron. Stack the hero LastRun/Score on mobile but pin Score above Run for vertical-scan order. `ProjectDashboardPage.tsx:535` and `PulseTab.tsx:137`.

**X-11 [P3] "Have a share link from a teammate?" affordance is below the fold**
- *What the user sees:* `EmptyProjectLanding.tsx:188-232` has a "Have a share link from a teammate? Continue from it →" button at the bottom of the empty state.
- *Why it's confusing:* This is a meaningful enterprise pattern — share-link forking — but rendered as last-resort copy after the sample gallery. Users who arrive via a Slack-shared run URL skip past it.
- *Concrete fix:* Move the share-link input to the top, above the sample gallery. Detect clipboard contents on mount; if it's a /reports/shared/ URL, pre-fill. `EmptyProjectLanding.tsx:65`.

**X-12 [P3] Glossary tooltips ship for "wiring pass" only — other terms are bare jargon**
- *What the user sees:* `Glossary` primitive (`shared/Glossary.tsx`) wraps "Reviewer" → "wiring pass" tooltip in `ValidationReportPanel.tsx:479` and "Quality dimensions" header in `ScoreCard.tsx:84`. Glossary keys: codemap, wiring pass, verified score, status reason, classifier (per `frontend/CLAUDE.md`).
- *Why it's confusing:* Other surfaces use the jargon raw: "Plan strategy: SMALL", "Phase: COHERENCE", "Reason: REVIEWER_UNRESOLVED". A new enterprise user encounters terms like "smoke test", "post-merge cascade", "stale codemap" without explanation.
- *Concrete fix:* Audit every uppercase enum surfaced in UI for Glossary applicability. Add entries to `lib/glossary.ts` and wrap with `<Glossary term="...">` in StatusBadge tooltip dictionary, ScoreCard label rows, etc.

**X-13 [P3] No project-level read-only "share" surface for review with non-users**
- *What the user sees:* SharedReportPage exists (`pages/SharedReportPage.tsx`) for one-shot validation report sharing. Project dashboard has no equivalent.
- *Why it's confusing:* Enterprise managers want to share project status with a non-user (CFO, PM lead). Today they screenshot.
- *Concrete fix:* Beyond scope of this audit but worth flagging: a `/projects/{id}/snapshot/{token}` URL that renders PulseTab read-only with metrics + last 10 runs.

**X-14 [P3] Notification panel doesn't deep-link into the project**
- *What the user sees:* NotificationBell + NotificationPanel poll `/api/notifications`. On click, navigate to run.
- *Why it's confusing:* A "PR merged" notification could deep-link to the project's PRs sub-tab; today it goes to the run.
- *Concrete fix:* Notification entity carries `projectId`; navigate to project dashboard with `?tab=runs&sub=prs` for PR events.

## 3. Recommended sequencing

### Quick wins (≤2h each, ship this sprint)
- **P-04** Architecture-not-generated row in HealthCard → click-through.
- **P-05** Repos chip → switch tab; separate `+ Add` button.
- **P-08** Pass project name to Header breadcrumb.
- **P-11** Replace "Top provider" KPI with "Spend MTD".
- **R-04** Rename Prototype → "Quick (skip review)" label flip.
- **R-05** Add tooltip to "Build My Spec".
- **R-06** Default to Live Log tab on non-terminal status.
- **R-07** Open NewRunModal automatically after Re-run sets pending config.
- **R-13** Soften Archive confirmation copy.
- **X-01** Sync `setTab/setRunsSub/setCodeSub` to URL via `replaceState`.
- **X-06** Move tier coercion into `useAuthStore` selector.
- **X-12** Add 4-6 glossary entries for visible jargon.

### Sprint 1 (1 week of focused work)
- **P-02** First-run progress checklist on EmptyProjectLanding (4 gates: repo / PAT / arch / launch).
- **P-03** Inline AddGitHubPatDrawer inside AddRepoModal when PAT missing.
- **P-09** Architecture sub-tab clarifying copy + per-repo arch links.
- **P-10** Promote Generated Docs out of MoreDrawer onto PulseTab.
- **R-02** Inline plan summary into AWAITING_APPROVAL gate banner.
- **R-03** Collapse run-completion scoring narratives 6 → 3.
- **R-08** Sweep dead RunConversation copy/buttons gated by V2_LEGACY_UI.
- **R-12** Cross-link PR rows to originating run + tab.
- **X-02** Wire RBAC role to disable admin-only CTAs across project surfaces.
- **X-04** Spend MTD tile on PulseTab hero.
- **X-10** Mobile audit pass for ProjectDashboardPage + PulseTab.

### Sprint 2 (2-3 weeks)
- **P-01** **Resolve the V3ProjectHome / ProjectDashboardPage split.** Pick one. Migrate users. Delete the other. Touches: `App.tsx:260-262`, `V3HomeRedirect.tsx:65`, `V3ProjectHome.tsx`, the v3 chat-thread store, all references in V3TopBar nav.
- **P-06** Template-aware EmptyProjectLanding via navigation state.
- **P-07** Sidebar projects section header + inline new-project button.
- **R-01** Unify run detail UI — InlineRunDetail becomes a thin wrapper over RunDetailTabs.
- **R-09** Backend status-reconciliation pass; remove `finalBuildPassed && PARTIAL` UI special case.
- **R-10** `usePushRun` hook unifying `RunDetail` + `InlineRunDetail` push flows.
- **R-11** Move retry-failed-tasks into ValidationReportPanel inline.
- **X-03** Top-level Activity link + chip-based filters in ActivityTab.
- **X-05** Team panel in MoreDrawer — actually built, with per-member runs.
- **X-08** V3ProjectHome back-link (or, see P-01, deletion).
- **X-09** Settings privacy toggle for analytics opt-out.

## 4. Out of scope / can't audit from code alone

- **Real-user time-on-task data:** how long does a fresh enterprise org-admin actually take to get from signup to first successful run? Code review can rank friction but not measure it.
- **Mobile field-test:** the audit notes mobile breakpoints in `ProjectDashboardPage.tsx`, `V3TopBar.tsx`, but real iOS/Android testing on viewport corners (320px, 768px tablet portrait, 1024px tablet landscape) is needed.
- **Screen-reader walkthrough:** App.tsx ships a skip-link and aria attributes are present in many places, but verifying NVDA/JAWS/VoiceOver flow through the 4-tab + drawer + modal stack requires AT testing.
- **Eye-tracking on the run-completion scoring stack:** R-03 claims six surfaces compete for the user's attention, but only a heatmap/click study can prove which they read first and which they ignore.
- **Tier-specific CTA copy A/B data:** does "Upgrade to Pro · 0/5 runs" out-perform "Upgrade for 20 credits/month" for FREE-tier conversion? The Sidebar.tsx upgrade CTAs are hard-coded.
- **Customer-specific RBAC needs:** the audit assumes the standard ORG_ADMIN/DEVELOPER/VIEWER ladder, but enterprises may want PROJECT_DEVELOPER (read-only) and BILLING_ADMIN (read-only on runs) variants. Field interviews needed.
- **PostHog funnel data:** the V3HomeRedirect persona-fork (Enterprise → /trust, Team → /team) was added per analytics, but the actual conversion lift cannot be evaluated from code.
- **Accessibility-of-color claims for the 4-band score palette:** WCAG-AA contrast ratios are documented in `docs/audits/dev-verification-2026-05-04.md` for tokens like `--status-executing`. The score-band hex values inside `ScoreCard.tsx` need an independent contrast check — code-level inspection cannot confirm rendered ratio.
- **The "Pulse vs PulseTab vs RunDetailTabs Overview" content overlap:** which one users actually use as their daily-driver isn't visible from code; needs session replay or NPS-by-surface.
- **Whether the 4-tab IA cleanup (v2.6.4) actually reduced cognitive load vs the 6-tab predecessor:** this audit accepts the cleanup at face value. A before/after task-completion-time study would settle it.

## Footnote — files most heavily audited

- `frontend/src/App.tsx`
- `frontend/src/pages/project/ProjectDashboardPage.tsx`
- `frontend/src/pages/org/OrgProjectsPage.tsx`
- `frontend/src/components/project/{PulseTab,EmptyProjectLanding,CodeTab,MoreDrawer,ProjectRepoManager,AddRepoModal,ProjectChatPanel,ArchitectureViewer,InlineRunDetail}.tsx`
- `frontend/src/components/runs/{NewRunModal,RunDetail,RunDetailTabs,ValidationReportPanel,ScoreCard,SignalBreakdown,VerdictPill,RunConversation}.tsx`
- `frontend/src/components/layout/{Header,Sidebar}.tsx`
- `frontend/src/v3/{components/V3Shell,components/V3TopBar,pages/V3HomeRedirect,pages/V3ProjectHome}.tsx`
- `frontend/src/lib/constants.ts`
- `frontend/src/pages/execution/ExecutionViewPage.tsx`
- `frontend/src/components/execution/ExecutionSetup.tsx`
