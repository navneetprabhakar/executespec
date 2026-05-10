# frontend/ — React Dashboard

> Last updated: 2026-05-10 iter-3 (mobile sweep round-3:
> wordmark hide + portaled drawer + scroll-fade indicator on
> `fix/mobile-tablet-comprehensive-2026-05-10`).
>
> **2026-05-10 iter-3 — comprehensive mobile + tablet sweep
> (`fix/mobile-tablet-comprehensive-2026-05-10`, NOT yet merged):**
>
> The previous two mobile passes addressed specific surfaces flagged
> by the user but left systemic issues — (a) the topbar wordmark
> stayed wide enough on 375px to push ProjectSwitcher into a 2-line
> ellipsis, (b) horizontally-scrollable rails (tab strips, summary
> bars, admin tables) had `overflow-x-auto + scrollbar-hide` but no
> visual cue that there was content past the right edge so users read
> "Cha…" / "Tier C…" / "ACTI" as bugs, and (c) the mobile nav drawer
> was rendered inside `<header className='v3-topbar'>`, which has
> `backdrop-filter: blur(8px)` — and per CSS spec, `backdrop-filter`
> creates a containing block for fixed-positioned descendants. The
> drawer's `position: fixed; right: 0; top: 0; bottom: 0` was scoped
> to the 52px-tall topbar, rendering off-canvas. Hamburger appeared
> to do nothing on mobile.
>
> Five structural fixes:
>
> 1. **TopBar wordmark hides below 480px** — only the `ES` logo mark
>    stays. Logo + ARIA label preserve brand recognition. Frees ~120px
>    on phones for ProjectSwitcher.
> 2. **ProjectSwitcher trigger sized to fit** — `maxWidth: 100%` +
>    `minWidth: 0` (was `maxWidth: 280` hardcoded) so the trigger
>    shrinks under the brand + hamburger pressure on 320px viewports.
> 3. **`v3-scroll-fade-x` utility class** in `v3.css` — `mask-image`
>    gradient that fades the right 24px so users see "more to scroll"
>    instead of a hard cut-off. Disabled at `min-width: 768px` (no
>    fade on tablets/desktop). Applied to: ProjectDashboardPage's
>    top-tab strip + Runs sub-tab strip + SummaryBar pill row;
>    CodeTab's sub-tab strip; AdminPage's top-tab strip and every
>    `overflow-x-auto` table wrapper (7 sites); TierLifecyclePage,
>    FeatureFlagsPage, IntegrationsPage, OrgSettingsPage scroll
>    wrappers.
> 4. **Mobile drawer portaled to `document.body`** via
>    `createPortal` — escapes the `.v3-topbar` containing block so
>    `position: fixed` is now relative to the viewport (the user's
>    expected behaviour). Pre-fix: drawer panel rendered off-canvas
>    inside the 52px-tall header, scrim invisible, hamburger
>    appeared broken.
> 5. **v3 design tokens hoisted to `:root`** in `v3.css` — the
>    drawer (and any future React portal that lands in
>    `document.body`) can now resolve `var(--v3-surface)`,
>    `var(--v3-border)`, `var(--v3-text)`, etc. Previously these
>    were scoped to `.v3-root`, so the portaled drawer's
>    `background: var(--v3-surface)` evaluated to
>    `rgba(0,0,0,0)` — invisible. Drawer scrim also strengthened
>    `rgba(0,0,0,0.5)` → `rgba(0,0,0,0.7)` + 2px backdrop-blur for
>    a clearer modal-layer feel.
>
> Verified live at `http://localhost:5173` against
> `https://dev.executespec.ai` backend, mobile (375×812) +
> tablet (768×1024) viewports, every authenticated surface a
> super admin can reach (38 routes captured) plus the unauthenticated
> login + website landing. No layout regressions on tablet/desktop.
>
> **2026-05-10 — mobile-responsive sweep + InlineRunDetail
> layout fix + single-score collapse + website softening on
> `fix/mobile-responsive-ui-2026-05-10` / PR #95.**
>
> **2026-05-10 — mobile-responsive + run-detail UX batch (PR #95):**
>
> - **Mobile pass (sub-640px / sub-420px):** `TopBar` drawer caps at
>   `min(85vw, 320px, 100vw - 32px)` with safe-area insets so iPhone
>   notches and home-indicator don't crop content; `NotificationPanel`
>   gutter widened (1rem → 2rem) + viewport-aware `max-h`;
>   `ProjectChatPanel` header flex-wraps and message bubbles cap at
>   `max-w-[min(85%, 320px)] min-w-0 overflow-hidden`;
>   `LaunchFromChatModal` outer flex now has p-3/sm:p-4 + max-h on the
>   panel; `ProjectDashboardPage` tab strip + sub-nav get `scrollbar-hide`
>   and the "More" button text collapses to icon-only via
>   `hidden sm:inline`; `RunSidePanel` 7-tab bar switches from `flex:1`
>   per tab to `flex 1 1 auto + min-width: max-content + overflow-x:
auto` so 7 tabs no longer squish on 320px; the chat composer hits
>   the 44px tap-target floor + `safe-area-inset-bottom`;
>   `RunDetailPage` outer wrapper gains `overflow-y: auto` so the
>   stacked mobile layout (status / file tree / diff / side panel)
>   scrolls instead of clipping behind `v3-main--fill`'s
>   `overflow: hidden`. Admin tables (`AdminPage`, `FeatureFlagsPage`,
>   `TierLifecyclePage`, `IntegrationsPage`, `OrgSettingsPage`) wrapped
>   in `<div overflow-x-auto>` with `min-w-[Npx] md:min-w-0`. v3.css
>   foundations: `100dvh` against iOS Safari URL-bar collapse,
>   `safe-area-inset-top` padding, iOS textarea-focus min/max caps so
>   the keyboard doesn't fight the composer in landscape.
> - **InlineRunDetail layout** (commit `2e41a3b5`): restructured from
>   `flex justify-between` (REQUEST text left + 9 buttons right with
>   wasted vertical column on the right) to three vertical rows —
>   action cluster up top (full-width, flex-wrap) → stats line (Status
>   / VerdictPill / Tasks / Tokens / Cost / Provider) → bordered
>   REQUEST card. Long specs (>320 chars) collapse to `line-clamp-6`
>   with a `Show all (N chars)` / `Show less` toggle in the card
>   header. **2026-05-10 follow-up**: added an `Update PR` button on
>   already-pushed terminal runs so users can re-push to the existing
>   branch after a per-task retry — `RunOrchestrator.retryTask` does
>   NOT auto-push (the V2 push code only runs in the main `runPipeline`
>   path), so the gap was that a retry would write new files to the
>   workspace but the PR stayed frozen until a manual push.
> - **Single-score collapse** (commit `6f6feeff`): the dual
>   `Verified N · Est. M` display was confusing because the two
>   numbers can disagree by 30+ points on the same run (deterministic
>   build+tests+verdict math vs LLM self-rating).
>   `ValidationReportPanel` hero ScoreRing label dropped from "verified
>   / 100" to just "score / 100"; the secondary "LLM estimate" tile is
>   gone. `ScoreCard` header dropped the `· Est. N` inline qualifier.
>   `verifiedScore` (V106 deterministic) is preferred;
>   `accuracyScore` is a legacy fallback for pre-V106 rows. Net
>   user-visible effect: one number per run, no more "is this run a
>   92 or a 17?" ambiguity. **Note**: a separate backend bug had
>   `extractIssues` dropping the `SYNTH-ACC-*` IDs when translating
>   `ReviewIssue` → `ReportIssueDTO`, which made dashboard issues look
>   like reviewer-flagged criticals when they were AccuracyAssessment
>   LLM hallucinations — fixed in the same PR by setting `source =
"ACCURACY_LLM"` (vs `"REVIEWER_AGENT"`) on SYNTH-ACC-\* derived
>   rows so the dashboard panel can group them.
> - **Marketing site copy softening** (commit `f7d8c856`, deployed to
>   Cloudflare worker `square-bar-e1dc` version `148ed763` on
>   2026-05-10): hero sub-headline + founder block + one FAQ entry
>   were rewritten to drop disparaging mentions of competitors
>   (legal-risk on comparative-advertising claims). Net hero now
>   reads: `AI-written PRs you can actually merge. · Plan, build,
test, review, and open a real pull request — with a Ready / Needs
review / Failed verdict on every run. ~80% of real runs land
Ready.` Comparison table + neutral FAQ entries (with "many teams
>   use both" framing) kept as-is; those are factual feature-comparison
>   context, not disparagement.
>
> Last updated before today: 2026-05-07 (website-mockup parity sweep on `feature/product-review-fixes-2026-05-06` — new v3 primitives + Shell-driven layout policy + PulseTab redesign; v2.6.4 — UX overhaul v4 IA cleanup; v2.5.12: design-system primitives)
>
> **2026-05-07 — website-mockup parity sweep (`feature/product-review-fixes-2026-05-06`, NOT yet merged):**
>
> Closes the user feedback that v3 surfaces had "a lot of empty space" and "do not follow the website's Project Workspace design aesthetic." Three structural changes:
>
> - **New v3 primitives in `frontend/src/v3/v3.css`** — `.v3-frame` (radius 14, soft shadow, mirrors `.ws-mockup-frame`), `.v3-card` (radius 10, mirrors `.ws-mockup-card`), `.v3-rollup` (3-up grid → stacks at 720px, mirrors `.ws-mockup-rollup`), `.v3-rollup-cell` (radius 8, padding 12/14), `.v3-eyebrow` (mono uppercase 10px label), `.v3-mono` (font helper), `.v3-chip` + `.v3-chip--ok/--warn/--err/--info/--neutral` (mono pills, mirrors `.ws-mockup-chip*`), `.v3-spendbar` (horizontal rollup-bar with mono values, mirrors `.ws-mockup-spendbar`), `.v3-statk` (mono UPPER stat key), `.v3-hero-grid` (1fr 220px → stacks at 720px). New tokens: `--v3-radius-frame: 14px` + `--v3-radius-rollup: 8px`. Light-theme override block adds a softer shadow on paper-\* surfaces.
> - **Shell-driven `--wide` / `--fill` layout policy** — `Shell.classifyRoute(pathname)` returns `{wide, fill}` per route so layout policy lives in one place instead of duplicated `useEffect` blocks across pages. Three buckets: _wide+fill_ for inspection / workbench surfaces (RunDetail, project shell, library / migration detail); _wide only_ for list + analytics pages (Runs, Library, Migrations, Insights, Team, Trust, Billing, ExecDemo); _default narrow_ for centered welcomes (Auth, EmptyProject, Landing/Composer). Removes the per-page DOM mutation in `RunDetailPage.tsx` + `ProjectDashboardPage.tsx`.
> - **`PulseTab.tsx` redesigned** to use the new primitives end-to-end — last-run hero in `.v3-card`, verified score ring in a sibling `.v3-card`, project spend bar via `.v3-spendbar`, Health · Knowledge · Activity rollup via `.v3-rollup` + `.v3-rollup-cell`, signal chips via `.v3-chip--ok/warn/err`. The previous Tailwind-token version (`bg-[var(--gray-100)]`, `border-[var(--border-default)]`) leaked v2 design tokens into a v3 shell; new version is fully on the v3 token namespace so light-theme + paper-\* cascade cleanly. Sister-pages `InsightsPage` (Tile + Section), `BillingPage` (4 sections), and `RunsListPage` (frame + empty state) swapped to the new primitives for consistency.
>
> **v2.6.4 — UX overhaul v4 (`feature/ux-overhaul-v4-pulse-tab`, NOT yet merged):** 6→4 top-tab IA cleanup per docs/plans/ux-overhaul-v4.md (was `/tmp/eval-reports/wireframe-report.md`). Project dashboard's first paint dropped from 25 affordances to ~10. Five new components, one large refactor.
>
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
>
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
>
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
> v2.5.5 — Batch 7: `ArchitectureViewer` gains a source-file-changes section under the text diff (per-repo Added/Modified/Removed/Renamed with ±LoC, `getArchitectureDiff`). — Batch 6: `FailureDecisionModal` rewrite (button grid + blocked-downstream list); new `ResetWorkspaceSheet` with SHA preview; `PostMergeBar` stacks up to 2 full rows + "+N more merged" chip (backend list changed to `PostMergeBar[]`); estimate chip has a skeleton pulsing state + basis tooltip. — Batch 5 UX polish: `ExecutionStepper` with friendly labels + elapsed time; new `RepoChip`/`extractRepoShortName` on `TaskCard`; new `StalenessWarning` in `ExecutionSetup` (non-blocking amber block + Analyse CTA); `TechDebtPanel` upgraded to recharts horizontal `BarChart` with color-coded categories + severity chips. — Batch 4: `TechDebtPanel` on Project Overview (issues-by-category/severity + file hotspots, `getTechDebt`); `TemplatePicker` dropdown above the requirement textarea in `ExecutionSetup` (`listSpecTemplates`/`createProjectSpecTemplate`/`deleteSpecTemplate`); TDD mode checkbox in `NewRunModal` (threads `tddMode: boolean` into `RunRequest`); `CancellationPanel` renders live cleanup steps on the ABORTED execution view (`useExecutionStore.compensations` slice fed by EXEC_COMPENSATION_UPDATE/\_DONE SSE events). — Batch 3: `WorkspaceHealthPanel` on Project Overview (origin-SHA drift + webhook + open-PR chips, cached 10 min in Redis via `getWorkspaceHealth`); `PostMergeBar` above the tab strip renders a "✓ PR merged — Start next requirement →" banner polled on mount (`getPostMergeBar`, `dismissPostMergeBar`); `ExecutionSetup` shows a debounced `~M–N tasks · ~$X–$Y` chip below the requirement textarea (`estimateRun`, no LLM call). — Batch 2: "Reset to run state" button on `InlineRunDetail` (`syncRunToOriginal`), webhook status chip + "Install webhook" button on `RepoCard` (`installRepoWebhook`), "Reset workspace" button on `ProjectRepoManager` (re-analyses all linked repos). New `webhookInstalled: boolean` on `ProjectRepo`. — v2.5.1: PR refresh button (`ProjectPrRollup`), Architecture diff + branch input (`ArchitectureViewer`), Run Sync from branch (`InlineRunDetail`), persistent Cancel on Launch header + Resume button on `ExecutionMonitor`, `ExecutionSetup` shows repo githubRepoFullName instead of UUID, new API helpers `refreshProjectPrs` / `analyseProject(branch)` / `syncRun` / `executionApi.resume`)
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

- **`runs/`** — RunDetail, RunDetailTabs (batch badge + per-run PR list), RunCard (batch badge), RunList, RunConversation, NewRunModal (now: project-linked multi-repo select + Figma URL), TaskCard, TaskGraph (subtask grouping), PreviewTab ("Run Tests" button, Beta), LiveLog, ValidationReportPanel (now: ScoreCard at top), TestReportPanel, TestProgressTracker, ProviderModelSelector, FirstRunSuggestions, VibeProgressNarrative, VibeRunSummary, **AssessmentPanel** (pilot R4 — appears above the plan-approval gate when `RunDetail.assessmentResult` is set), **ScoreCard** (pilot R6 — 6 colored progress bars + overall accuracy), **VerdictPill** (Plan 1 #3 on `feature/product-split-prep` — Shippable/Needs review/Blocked derived from build+tests+reviewer+verifiedScore+statusReason; leads ValidationReportPanel/ScoreCard/SignalBreakdown header; numeric scores demoted to disclosure). **SignalBreakdown** (Plan 1 #3) — Verified+Est numeric segments dropped (collapsed into VerdictPill); kept on prop API for back-compat.
- **`project/`** (pilot v3.0 + v2.2.0 + v2.5.12 + **v2.6.4 IA cleanup**) — ProjectRepoManager, RepoCard (scope chip), AddRepoModal (Pick existing / Create new — migrated to ResponsiveModal v2.5.12), RepoRelationshipEditor, StackBadge, ArchitectureViewer (markdown + version dropdown + amber stale badge driven by `getArchitectureStaleness`), ProjectTaskList, ProjectPrList, ProjectScoreTrend (recharts LineChart, overall + per-dimension), **ProjectMemoryPanel** (v2.2.0), **GeneratedDocsPanel** (v2.5.12), TechDebtPanel, WorkspaceHealthPanel, PostMergeBar, ResetWorkspaceSheet, **PulseTab** (v2.6.4 — last-run hero + verified-score ring + health rollup + activity preview), **CodeTab** (v2.6.4 — Architecture / Repos / Health / Tech debt / Standards / Contracts inline), **MoreDrawer** (v2.6.4 — slide-over for Settings / Generated Docs / Memory / Activity / Documents / Team), **RunStamp** (v2.6.4 — 1-line shareable summary + share-link + embed HTML, wired into ValidationReportPanel via stampMeta), **EmptyProjectLanding** (v2.6.4 — single-textbox empty state + sample-spec gallery + share-link continue)
- **`run/`** — FileTree, FileViewer (RunTimeline removed — V2 has no COMPILING/MERGING/AWAITING_MERGE phases)
- **`layout/`** — AppLayout, Header, Sidebar, CommandPalette, NotificationBell, NotificationPanel
- **`dashboard/`** — ActivityFeed, AgentCommGraph, DateRangeFilter, MetricCard, ModelComparisonChart, TokenBubbleChart, UserRunsChart
- **`billing/`** — BillingProfileForm, DisclaimerModal, PlanCard, RazorpayCheckout, RazorpayOrderCheckout, UpgradeNudge, UsageBadge
- **`deployments/`** — DeployModal, DeployWizardModal, DeploymentCard
- **`pricing/`** — PricingTable, FeasibilityEstimator
- **`integrations/`** — IntegrationCard, IntegrationLogo
- **`shared/`** — AnimatedCounter, EmptyState, GlassPanel, GlowDot, MarkdownContent, SkeletonLoader, StatusBadge, Toast, ViewModeToggle, ResponsiveModal (v2.5.12 hardened), **Button** (v2.5.12 cva primitive), **Checkbox** (v2.5.12), **Radio** (v2.5.12), **Tooltip** (v2.6.1 + Plan 1 #4 — `wrap` + `maxWidthClass` props for multi-line definitions), **Glossary** (Plan 1 #4 — wraps any term with hover/focus tooltip carrying the definition from `lib/glossary.ts`; falls through cleanly when term unknown)

> **Per-seat TEAM UI restored (on `feature/product-split-prep`, NOT yet merged):** `PlanCard.tsx` regains its per-seat picker for TEAM (2-20 seats); `OrgAdminDashboard.tsx` regains its TEAM seats panel + `handleSeatsAdjust` handler; tier union types in `lib/api.ts` / `useViewMode.ts` / `TierBadge.tsx` re-expand to `'FREE' \| 'PRO' \| 'TEAM' \| 'MAX' \| 'ENTERPRISE'`; `adjustOrgSeats` helper restored in `lib/api.ts`. Plus 11 tier-string flips across `QuotaUsageBar` / `Header` / `Sidebar` / `ViewModeToggle` / `NewRunModal` / `ProviderModelSelector` / `UsageBadge` / `SettingsPage` / `BillingPage` / `OrgSettingsPage` / `ProjectsListPage` reverse V141's `'MAX'` → `'TEAM'` rename. Surface mapping (TEAM → Build, MAX → Vibe) policed by backend `TierProductSurfaceMapper`; frontend reads it via the `/api/billing/plans` payload (no client-side enforcement yet).

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
- `glossary.ts` — Plain-English definitions for ExecuteSpec jargon (Plan 1 #4 on `feature/product-split-prep`). Seed: codemap, wiring pass, verified score, status reason, classifier. Consumed by `<Glossary term="...">`. Add new entries as they surface in user testing.

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
- **Vibe\* components** — VibeRunSummary, VibeProgressNarrative: simplified UX for non-technical users.
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
