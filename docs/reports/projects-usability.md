# Projects feature — usability audit

> Scope: end-to-end usability of the Projects surface for an authenticated
> ENTERPRISE user. Captured 2026-04-16. Live preview + source-level walk of
> `frontend/src/{components,pages}/project/**` and the backing controllers.

## Journey 1 — empty / brand-new project

### Path
1. **Create:** `+ New Project` on `/org/projects` opens the create modal →
   `POST /api/projects` ([ProjectController.java:29](src/main/java/com/navneet/executespec/auth/controller/ProjectController.java:29)).
2. **Land:** `/projects/{id}` opens the dashboard. Default tab: `Overview`.
3. **Empty signals:** every panel renders a custom empty string —
   *"No repositories linked yet"* (`ProjectRepoManager.tsx:85`),
   *"Nothing submitted yet"* (`ProjectDashboardPage.tsx:398`),
   *"No project memory yet. Memory is generated automatically after each
   completed run."* (`ProjectMemoryPanel.tsx:62`),
   *"No validation reports yet — tech-debt signals will appear once runs
   start completing"* (visible on screenshot above the launches section).
4. **First action:** the only meaningful CTA visible is `+ Add repository`
   inside the Repos tab — but the user lands on Overview, where the only
   contextual hint is *"Click 'Launch' to kick off a cross-repo execution"*.

### Issues
- **No onboarding orchestration.** A first-time user lands on a dashboard with
  six visible empty panels (`Project summary`, `Workspace health`, `Recent
  submissions`, `Recent pull requests`, `Score trend`, `Project settings`).
  Each whispers "no data yet" but none point the user to the next step.
- **`Launch` button is enabled with zero repos linked.** The right-rail Launch
  CTA is always blue/clickable. Clicking it opens the New Run flow and the
  user can submit a run that has nothing to operate on — fails downstream.
  Found on `/projects/{id}` header (top-right, blue button).
- **`Project summary` shows `Analyse` even when nothing exists.** The button
  exists in the empty state without explaining what would be analysed
  ([ProjectDashboardPage.tsx:378](frontend/src/pages/project/ProjectDashboardPage.tsx:378)).
- **Github Owner field is unmarked.** *"Default GitHub org/owner for new
  repos created during a Launch. Overridable per-repo."* — accurate but the
  field has no validation hint, no placeholder example, and no link to "how
  do I find this".
- **Status pills on the empty repos list could be misread.** "ACTIVE"
  (project status) sits next to "ANTHROPIC" (provider). Both look like
  pills; one is functional state, the other is config. They should be
  visually different.

### Recommendations
- **Replace the 6-panel empty dashboard** with a single 3-step setup card:
  *(1) Link a repository → (2) Run Analyse → (3) Launch a requirement.*
  Show progress dots; collapse to the regular dashboard when step 3 is
  reached at least once.
- **Disable `Launch`** until ≥1 repo is linked. Tooltip on hover:
  *"Link at least one repository to launch."*
- **Hide `Analyse`** until ≥1 repo has been linked. Same for the Architecture,
  Docs, Memory tabs — gate them with a "Connect a repo to enable" lock state.
- **Distinguish state pills** (`ACTIVE`) from config pills
  (`ANTHROPIC`/`OPENAI`/`GEMINI`) — different shape (pill vs. tag), different
  color family.

## Journey 2 — existing-repo project

### Path
1. From an empty project, user opens the **Repos** tab → `+ Add repository`
   → `AddRepoModal` (dual-tab: *Pick existing* / *Create new*).
2. *Pick existing* lists everything from `GET /api/repos?scope=ALL` plus the
   user's own GitHub repos via PAT (`listGitHubRepos`).
3. Selecting a repo calls `POST /api/projects/{id}/repos/{repoId}` to link.
4. After ≥1 repo is linked, `Analyse` becomes useful → backend
   `ProjectArchitectureService.generate()` builds codemaps + architecture doc
   + summary; UI surfaces progress on `ProjectSummaryPanel`.
5. `Workspace health` panel populates with per-repo SHA + webhook status +
   open-PR count.

### Issues
- **`Analyse` is a multi-minute job with no progress bar.** Once clicked,
  the button disables but the only feedback is the panel re-rendering when
  done. For a 5-repo project this can take >2 min. No streaming progress, no
  cancel.
- **The `Workspace health` chips are dense.** *"up to date - f1c7642 + f5210d2"*
  rendered in mono is correct but unreadable to a non-engineer; "set up webhook"
  and "1 open PR" sit next to it without visual hierarchy.
- **`Reset workspace` is in the same panel as Add repository** but the
  v2.5.4 spec made it an explicit "Reset Workspace Sheet" with a preview of
  per-repo SHA movement before confirming. The sheet is good; the discovery
  isn't — the only entry point is a button buried in the Repos tab.
- **No visual indication that a repo is private vs public.** Both render as
  `org/repo`. PAT status is implied by the fact that the repo loaded at all.

### Recommendations
- **Stream `Analyse` progress** via SSE. Backend already has SSE infra; reuse
  the `EXEC_*` channel pattern with a per-repo progress event.
- **Reformat `Workspace health` row** to: `org/repo` (sans, primary) → branch
  chip → "up to date" badge → SHA pair (mono, secondary) → action chips on
  the right edge ("Set up webhook", "View 1 PR"). Visual hierarchy by size,
  not just by adjacency.
- **Surface `Reset workspace` from the Overview tab** when any repo's
  `Workspace health` shows drift — small chip *"3 repos drifted — reset?"*.

## Journey 3 — multiple launches on an existing project

### Path
1. From `/projects/{id}` user clicks the persistent `Launch` button (top-right).
2. `ExecutionSetup` opens (`/projects/{id}/executions/new`).
3. Spec text + repo selection + provider override + (optional) TDD toggle +
   (optional) template picker. Estimate chip pulses while debouncing
   (v2.5.2).
4. Submit → `POST /api/executions`, dashboard returns to `/projects/{id}` and
   the `Launches` tab updates with the new row.
5. Each `ExecutionRun` fans out to one `ExecutionSolution` per repo in scope.
   Solutions show as nested rows (`SolutionSection`, collapsible per
   v2.4.0).

### Issues
- **Launches list rows are visually identical regardless of solution count.**
  A 1-solution run and a 5-solution run look the same in
  `ProjectLaunchesList.tsx:36`. The user can't tell at a glance whether a
  launch touched 1 or 5 repos.
- **Multiple launches over time have no grouping.** A project that has run
  20 launches over a month shows them as a flat list ordered by recency. No
  filtering by status, no grouping by week, no per-repo filter.
- **Per-launch retry creates a *new* `ExecutionSolutionRun`** but the UI
  presents this as a sibling card below the original — easy to lose track of
  whether the row you're staring at is attempt #1 or #3.
- **Cross-launch dependencies are not surfaced.** If launch B depends on a
  PR opened by launch A, there is no UI link between them. The `cross-link`
  in `GitHubRepoService.updatePullRequestBody` exists on the GitHub side, but
  the dashboard doesn't reflect it.
- **No "compare two launches" view.** A common ask after multiple launches
  on the same project is "what changed between L7 and L8?" — no surface.

### Recommendations
- **Add a repo-count chip** to each launch row: `repos × 5` next to the
  status pill. Makes blast radius readable at a glance.
- **Add filters above `Launches`**: by status, by user, by date window. The
  data is already in the rollup endpoint.
- **Group retries** under the original launch — collapsible "+2 retries" row
  rather than 3 sibling rows.
- **Cross-launch link** — when an `ExecutionSolution` includes a PR that
  another launch's solution depended on, surface "Linked to L7" inline.

## Journey 4 — Project chat

### Path
- Tab `Chat` on the project dashboard. Empty state: icon + 3 quick actions
  (*"Project summary"*, *"Architecture"*, *"Debt + risks"*). Backed by
  `ProjectChatService` with retrieval over project codemaps + uploaded docs +
  memory snapshot ([ProjectChatPanel.tsx:51](frontend/src/components/project/ProjectChatPanel.tsx:51)).
- *Launch from chat:* `LaunchFromChatModal` (line 249) compiles the chat
  thread into a spec via `compileProjectChatToSpec` and submits a run.

### Issues
- **No threading / no history beyond a single linear conversation.** Users
  can't park a question and start a new one; the next message continues the
  same thread. No "New chat" button.
- **Citations to retrieved chunks are not visible.** The backend retrieves
  k=3 / cap 1500 tokens of context but the assistant reply doesn't show
  which doc/codemap it pulled from. CLAUDE.md flagged this as the v2.5.10
  hallucination risk for `AccuracyAssessmentService`; the same risk applies
  here without the same fix.
- **Quick actions don't change** as the project develops. After the user has
  asked "Architecture" three times, the suggestion is still there.
- **Launch-from-chat lands on the regular Run flow** with the spec
  pre-filled; users have to remember they came from chat. The provenance link
  isn't preserved on the run.

### Recommendations
- **Inline citations** on every assistant message: `(from arch.md §3, codemap
  for hostel-be-v3, project memory R12)`. The backend already knows what was
  retrieved.
- **"New chat"** button + side-rail history of past chats per project.
- **Adapt quick actions** based on the latest project state — "Why did L8
  PARTIAL?" if the most recent launch ended PARTIAL.
- **Carry chat provenance into the launched run** so the run detail page
  shows "Launched from chat thread X" with a back link.

## Status messages — dev/UI parity

Backend Execution flow emits:
`SETUP → ANALYSING → ARCH_REVIEW → EXECUTING → COMPLETE`
(`ExecutionOrchestrator.java`).

Frontend display dictionaries:
- [`constants.ts` STATUS_COLORS](frontend/src/lib/constants.ts) — covers
  WORKING, PLANNING, REVIEWING, AWAITING_APPROVAL, COMPLETE, FAILED, etc.
- [`StatusBadge.tsx` DISPLAY_NAMES](frontend/src/components/shared/StatusBadge.tsx) — same.

**Unmapped:** `SETUP`, `ANALYSING`, `ARCH_REVIEW`. They render through the
fallback `.replace(/_/g, ' ')` and get no color. Result: a launch in
ANALYSING shows an uncoloured "Analysing" pill while every other status
pops. Fix: add the three values to both dictionaries (mapped to the
PLANNING / REVIEWING color family).

**Dead enums:** `QUEUED_BATCH`, `IN_PROGRESS_BATCH`, `BATCH_COMPLETE` still
in both dictionaries; CLAUDE.md notes V2 backend never emits them. Strip.

## Empty-state inventory

| Tab | Empty copy | Source |
|---|---|---|
| Overview / Submissions | "Nothing submitted yet. Click 'Launch' to kick off a cross-repo execution." | ProjectDashboardPage:398 |
| Launches | "No launches yet. Click 'Launch' to start a cross-repo run." | ProjectLaunchesList:27 |
| PRs | "No pull requests yet." | ProjectPrList:12 |
| Memory | "No project memory yet. Memory is generated automatically after each completed run." | ProjectMemoryPanel:62 |
| Documents | drag-drop upload area with instructions | ProjectDocumentsPanel:62 |
| Chat | icon + message + 3 quick actions | ProjectChatPanel:110 |
| Repos | "No repositories linked yet. Click 'Add repository' to get started." | ProjectRepoManager:85 |
| Tasks (sidebar) | "No runs yet." | ProjectDashboardPage:435 |

Three different framings — "Nothing submitted yet", "No X yet", "No X yet.
Click Y" — for the same underlying state ("zero entities"). Pick one
template (`<EmptyState title="No launches yet" hint="Click Launch above"
icon={Rocket} />`) and route everything through it.

## Quick wins (do this week)

1. Disable `Launch` until ≥1 repo linked (1-line change in
   `ProjectDashboardPage.tsx`).
2. Add SETUP / ANALYSING / ARCH_REVIEW to STATUS_COLORS + DISPLAY_NAMES.
3. Use `StatusBadge` on Launches + Tasks lists (replaces 2 inline status
   spans, gains the v2.5.10 tooltip).
4. Strip dead `*_BATCH` mappings.
5. Inline citations on chat replies — backend already returns them, just
   surface them.

## Larger work (next sprint)

6. New-project onboarding card (3-step flow) replacing the current 6-panel
   empty dashboard.
7. Per-launch repo-count chip + filters + grouped retries on the Launches
   list.
8. Streaming `Analyse` progress via SSE.
9. Single shared `EmptyState` primitive replacing the 8 hand-rolled
   variants.
