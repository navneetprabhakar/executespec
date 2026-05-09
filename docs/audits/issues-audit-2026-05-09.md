# ExecuteSpec — Exhaustive Issues Audit

**Date:** 2026-05-09
**Branch:** `claude/find-latest-branch-1ALO0` (synced to `main` after merging PRs #90, #91, #92)
**Scope:** Backend (Spring Boot 3.3 + Spring AI), Frontend (React 18 + Vite + Tailwind), UX journeys
**Methodology:** Two-pass audit — first pass for breadth, second pass for depth — plus journey-based UX walkthrough

---

## Summary

| Severity | Count |
|---|---|
| **P0** | 16 |
| **P1** | 53 |
| **P2** | 51 |
| **Total unique** | **120** |

### ID reference

- **B-01..B-28** — backend findings (security, concurrency, performance, architecture, recovery, tests)
- **F-01..F-29** — frontend technical findings (SSE, state, modals, theme, mobile, v2/v3 fork, performance, a11y, routing, forms)
- **U-01..U-70** — UX journey findings (onboarding, plan approval, live progress, run-detail, multi-repo, free-tier, admin, taxonomy, cross-cutting, polish, help)
- **R-01..R-04** — user-reported bugs (mapped to F-IDs)

### Reported-bug → finding ID map

| Reported | Mapped to | Description |
|---|---|---|
| R-01 | F-03 | "Open Architecture" link does nothing |
| R-02 | F-04 | Project Chat scrolls page |
| R-03 | F-05 | Run Chat wipes on remount + missing run details |
| R-04 | F-25 | File icons confusing |

---

## A. Security

| IDs | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| B-01 | P0 | `sh -c "..."` bypasses sandbox — first-token allowlist misses `-c` flag and quoted payload | `tool/ShellSandboxPolicy.java:321-418` | Reject `sh\|bash\|zsh -c` outright |
| B-02 | P0 | Symlink traversal — `Path.relativize()` ignores symlinks; worker can write to `/etc/passwd` via workspace symlink | `tool/FileSandboxPolicy.java:132-141` | `target.toRealPath()` + re-check containment |
| B-13 | P1 | SSE force-logout doesn't terminate active streams (≤5min leak per emitter) | `auth/security/JwtUtil.java:135-142` | Redis pub/sub `force-logout:userId` → broadcaster completes emitters |
| B-25 | P2 | `InputSanitizer` decodes legit base64 (image/JWT) → false positives on user input | `security/InputSanitizer.java:163-188` | Decode only with binary signal or explicit `base64:` prefix |
| B-26 | P2 | `CodeAbuseScanner` over-broad DNS exfil regex — flags `nslookup $(whoami)` | `security/CodeAbuseScanner.java:56-58` | Narrow regex; rely on shell sandbox for `$(...)` |

## B. Concurrency, billing & data integrity

| IDs | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| B-03 | P0 | StateStore token-counter race on retry — non-atomic read-modify-write | `state/StateStore.java:330-342` | Atomic SQL `UPDATE … SET total = GREATEST(0, total - ?)` |
| B-04 | P0 | Plan-approval double-approval race — TOCTOU in fallback path | `harness/GateStore.java:115-164` | Unique partial index + `SELECT FOR UPDATE` |
| B-05 | P0 | RunCostTracker in-memory accumulator; pod crash mid-run = silent billing leak | `cost/RunCostTracker.java:145-151` | Outbox pattern with persisted events |
| B-06 | P0 | LLM calls inside `@Transactional`; Anthropic path has no timeout | `agent/LlmRouter.java:164-172, 282-303` | Move LLM call out of TX; per-provider 60s read timeout |
| B-19 | P1 | SequentialExecutor retry tokens lost on mid-retry crash → undercharged user | `harness/SequentialExecutor.java:481-522, 671-690` | Persist retry tokens before second smoke test |
| B-20 | P1 | LLM router 429 responses still recorded in cost tracker → user charged for failed calls | `agent/LlmRouter.java` 429 path | Only record on success; retry-with-backoff for 429 |
| B-11 | P1 | `ProviderHealthTracker` non-atomic INCR+EXPIRE → circuit can stick open across pods | `router/ProviderHealthTracker.java:96-123` | Single Lua EVAL: INCR + EXPIRE + threshold + health SET |
| B-12 | P1 | `RunOrchestrator.cancel` TOCTOU — interrupts a thread that may have already terminated | `integration/RunOrchestrator.java:1849-1882` | Lock + AtomicReference CAS at terminal transitions |

## C. Reliability & error handling

| IDs | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| B-09 | P0 | `@Async` paths swallow exceptions silently — no metric, no alert | `activity/ActivityEventEmitter.java:91-100`, `codeintel/ProjectArchitectureService.java:552-554` | Catch → emit metric → rethrow or surface via outbox |
| B-23 | P2 | No 5xx exception handler — bare exceptions return HTML, not JSON | `api/advice/*.java` | Global `@ExceptionHandler(Throwable)` returning JSON |

## D. Performance

| IDs | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| B-07 | P0 | ReviewerAgent uses unbounded `Executors.newCachedThreadPool()` | `reviewer/ReviewerAgent.java` | Bounded pool via central `ExecutorRegistry` |
| B-08 | P0 | `MonthlyRunCounterResetJob` is N+1 over all users at midnight IST | `auth/service/MonthlyRunCounterResetJob.java:54-88` | Single SQL `UPDATE app_user SET personal_run_count=0 WHERE …` |
| B-14 | P1 | N+1 in `ProjectExportService.composeBundle` (loop calls `findById`) | `export/ProjectExportService.java:118-134` | `findAllById` + in-memory join |
| B-15 | P1 | 8+ executor pools, no central registry, no per-pool metrics | `RunConversationController.applyExecutor`, `CodeReviewService.pool`, `ReviewerAgent.passExecutor`, +5 | Central `ExecutorRegistry` with named bounded pools |
| B-16 | P1 | 189/212 `@Transactional` missing `readOnly=true` | many | Sweep, default to `readOnly` on `@*Repository` reads |
| F-08 | P1 | RunDetailTabs callback churn → 60-100ms jank on tab switch (5k-file run) | `components/runs/RunDetailTabs.tsx:127-183` | `useCallback([])`, virtualize FileTree above 2k entries |

## E. Backend architecture

| IDs | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| B-10 | P0 | Agent layer imports billing (`LlmRouter` → `RunCostTracker`) | `agent/LlmRouter.java:14` | Outbox event; agent emits, billing consumes |
| B-17 | P1 | `RunController` imports repositories directly — layering violation | `api/controller/RunController.java:33-34, 62, 66` | Move to service |
| B-21 | P1 | `OutlinePlannerAgent` dispatches LLM work to `ForkJoinPool.commonPool()` | `agent/OutlinePlannerAgent.java` | Use named `agentExecutor` |
| B-24 | P2 | `PipelineRun` god entity (594 LOC, 50+ columns) | `state/entity/PipelineRun.java` | Decompose into `@Embeddable` value objects |

## F. Recovery, migrations, tests

| IDs | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| B-22 | P1 | StartupRunResumer marks WORKSPACE-GONE runs as COMPLETE | `recovery/StartupRunResumer.java:220-239` | `Files.exists(workspacePath)` precheck |
| B-18 | P1 | V141 (drop TEAM, rename MAX→TEAM) vs V144 (reinstate per-seat TEAM) on different branches; merge order matters | `db/migration/V141`, `V144` | Document sequence + pre-flight check |
| B-27 | P2 | 27/72 packages have zero tests (`recovery`, `validator`, `swebench`, `testgen`, `scoring` — critical paths) | various | Cover top-5 critical-path packages first |
| B-28 | P2 | No test for `StartupRunResumer` | `recovery/` | 4-case test (OK/WORKSPACE_GONE/EXHAUSTED/PLAN_MISSING) |

## G. SSE & state sync

| IDs | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| F-02 | P0 | SSE replay/no-dedup → state rolls backward on reconnect | `hooks/useSse.ts:57-80` | Event-id ring buffer + `Last-Event-Id` header |
| F-06 | P1 | Scoped SSE token TTL=5min, never refreshed; live log silently freezes | `hooks/useSse.ts:61-69` | Refresh on 401; toast at 30s silence |
| F-07 | P1 | Logout clears `useAuthStore` only; sibling stores leak across user switch | `stores/useAuthStore.ts:51-59` | `logout()` resets every store |
| F-09, U-13 | P1 | No `visibilitychange` refetch — metrics + cost ticker freeze when tab backgrounded | `App.tsx` (missing) | Global hook: refetch on `visibilityState='visible'` |

## H. Routing & deep-links

| IDs | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| F-03, R-01 | P0 | "Open Architecture →" dead — redirect drops `?tab=code` and force-sets `?tab=pulse`; ProjectDashboardPage reads URL once on mount | `App.tsx:393-395`, `pages/project/ProjectDashboardPage.tsx:145`, `v3/components/RepoContextPanel.tsx:118` | Drop redirect detour; `useEffect` on `useLocation().search` |
| F-24 | P1 | Deep-link query params silently dropped — only `tab`/`sub`/`codeSub` honored | `pages/project/ProjectDashboardPage.tsx:145-160` | Generic `useUrlState<T>()` |

## I. Forms & validation

| IDs | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| F-10 | P1 | Toast-only validation; no field-level errors; ConversationModal clears input on send fail | `components/runs/NewRunModal.tsx:200-500`, `v3/components/ConversationModal.tsx:307-326` | `<Field>` + `<FieldError>` primitives |

## J. Modals, layout & accessibility

| IDs | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| F-01, U-28, U-40 | P0 | 5 v3 modals raw `position:fixed` — no focus trap, no Esc; FreeCapBlockingModal has no X | `v3/components/{ConversationModal,FreeCapBlockingModal,RepoAttachPopover,RunPreviewPane,CmdK}.tsx` | Migrate all to ResponsiveModal |
| F-04, R-02 | P1 | Project Chat steals page scroll — chat-tab pane has no fixed height | `pages/project/ProjectDashboardPage.tsx:589` | Add `h-full min-h-0` on chat-tab pane |
| F-21 | P1 | Color-only status indicators (RepoContextPanel staleness chip + Pill component) | `v3/components/RepoContextPanel.tsx:101-114`, `Pill.tsx` | Add ✓/⚠ glyph + leading icon |
| F-22 | P1 | Heading hierarchy broken (no `<h1>` on RunDetailPage happy path; modal titles are styled `<div>`) | `v3/pages/RunDetailPage.tsx:316,368`, `ConversationModal.tsx:159-164` | Use semantic `<h1>`/`<h2>` |
| F-23 | P1 | Icon-only buttons + inputs without `<label>` (placeholder-only) | `v3/components/FreeCapNudge.tsx:230-245`, `ConversationModal.tsx:307` | Add `aria-label` / wrap in `<label>` |
| F-14 | P1 | Multi-scroll: RunSidePanel maxHeight + diff editor + page all scroll independently | `v3/components/RunSidePanel.tsx:178` | Single anchored scroll model |

## K. Mobile

| IDs | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| U-41 | P0 | Strategic: product not designed for mobile — composer wide-form, file tree assumes desktop, RunSidePanel 7-tab horizontal | global | Explicit mobile-restricted view <768px: runs list + dashboard rollup + notifications first-class; run detail = Pill + verdict + cost + "View on desktop" CTA |
| F-19 | P1 | 5 spots break <420px (modal overflow, button text overflow, message bubbles, popover, tab strip) | `ConversationModal.tsx:134`, `FreeCapBlockingModal.tsx:206-247`, `RepoAttachPopover`, `ProjectChatPanel.tsx:249`, `RunSidePanel` tab strip | Responsive font sizes + `max-w-[85%]` on bubbles + horizontal scroll on tab strip |
| U-42 | P1 | NotificationBell dropdown overflows on mobile (was patched in v2.6.0; check v3) | v3 NotificationBell | Audit mobile width |

## L. Theme & tokens

| IDs | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| F-18 | P1 | 5 hardcoded hexes bypass theme tokens — light theme breaks | `components/auth/ProtectedRoute.tsx:7`, `pages/auth/AuthCallbackPage.tsx:6`, `pages/IntegrationsPage.tsx:7`, `pages/admin/PricingManager.tsx:7`, `pages/admin/OrgAdminDashboard.tsx:2-3` | Lint rule against `bg-[#…]` outside `tokens.css` |
| F-26 | P2 | 3 v3 surfaces still on raw `bg-[var(--gray-100)]` instead of v3 primitives | `v3/pages/RunDetailPage.tsx:346-355`, `v3/components/RunSidePanel.tsx:164-179`, `ConversationModal.tsx:131-147` | Migrate to `.v3-frame`/`.v3-card` |

## M. v2/v3 fork

| IDs | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| F-15 | P1 | Run-detail forked: v2 `RunDetailTabs` 2,911 LOC + v3 `RunDetailPage+RunSidePanel` 4,028 LOC | `components/runs/`, `v3/pages/RunDetailPage.tsx`, `v3/components/RunSidePanel.tsx` | Pick v3, port score richness, delete v2 |
| F-16 | P1 | Validation/score forked: v2 has 6 components (1,047 LOC); v3 has just `Pill` (91 LOC) | `components/runs/{ValidationReportPanel,ScoreCard,ScoreRing,SignalBreakdown,VerdictPill,RunStamp}`, `v3/components/Pill.tsx` | Port v2 score components into v3 |
| F-17 | P1 | `useV3ProjectStore` + `useV3Ui` mirror v2 stores | `v3/stores/` | Delete; route v3 pages to v2 stores |
| F-27 | P2 | `applyToRun`/`iterateRun` re-imported in RunSidePanel after PR #91 (deleted-then-readded smell) | `v3/components/RunSidePanel.tsx:26-34` | Investigate provenance |

## N. Empty / loading / error states

| IDs | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| F-12 | P1 | 7/10 v3 pages missing error state (InsightsPage spins forever on failure) | `v3/pages/InsightsPage.tsx:39,364`, `RunsNewPage`, `TeamPage`, `LibraryPage`, `MigrationDetailPage`, `AuthPage`, `TrustPage`, `ExecDemoPage` | `<DataState>` wrapper + `useFetch<T>` |
| U-45 | P1 | Empty states are sad, not actionable — "No runs yet", "No projects yet", "No notifications" | global | Every empty state: 1-line explanation + 1 CTA |

## O. Onboarding & first-run

| IDs | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| U-01 | P1 | Terminology fragmentation: Run / Mission / Execution / Launch / Submission | sidebar, NewRunModal title, ExecutionRun, URL paths | Pick `Run` everywhere user-facing; drop `Mission`; `Launch` is verb-only |
| U-02 | P1 | Empty-project landing buries sample gallery; "Start a run" wrong primary CTA | `v3/pages/EmptyProjectPage.tsx`, `EmptyProjectLanding.tsx` | Flip CTA order; sample gallery above fold; "Try on a public repo" zero-commitment input |
| U-03 | P1 | NewRunModal exposes 10 controls before user has seen what the product does | `components/runs/NewRunModal.tsx` | Beginner mode → "Customize" disclosure |
| U-04 | P2 | Sandbox onboarding wired in backend but no frontend nudge between signup and first action | post-signup redirect | Route through `/welcome` → sample gallery |
| U-05 | P2 | No first-time tooltips / product tour / "what's this" affordance | global | One-time guided tour + tooltips on the 5 most-confusing controls |

## P. Plan approval gate

| IDs | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| U-06 | P0 | Plan tasks shown as worker prompts, not plain-English — no "what does this do" | RunDetail AWAITING_APPROVAL | Generate 1-line plain-English description per task at planning time |
| U-07 | P1 | Credits→dollars mental math implicit ("4 credits") | PlanApprovalGate | Show both: "4 credits ($20)" |
| U-08 | P1 | Cost cap policy not surfaced — user doesn't know they get refunded on FAILED | plan modal | Microcopy: "Refunded if the build fails. Capped at $X" |
| U-09 | P1 | No "ask the assistant about this plan" — approve blind or cancel | plan modal | "Ask why this plan" link opens chat with plan pre-loaded |
| U-10 | P2 | Plan iteration history not visible — if planner re-ran, user can't see what changed | plan modal | Diff between plan v1 and v2 |

## Q. Live progress (WORKING / REVIEWING)

| IDs | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| U-11 | P1 | Live log shows raw model output / tool calls — not narrative | RunDetail / RunSidePanel | Translate `WORKER_TOOL_CALL` SSE events to present-tense prose above raw log |
| U-12 | P1 | No duration prediction; cost chip says "$5–$15" but no time range | RunDetail header | Surface `EstimationService` time prediction + elapsed |
| U-14 | P2 | No "pause" affordance — only cancel; cancellation rolls back work | RunDetail | Pause at task boundary, resume later |
| U-15 | P2 | No streaming preview of what files have been touched so far | RunDetail | Live file-tree update with "Just modified" pulse |

## R. Run detail / review

| IDs | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| F-13, U-16 | P0 | 5 tab clicks per fix — Plan/Tests/Code/Verification/Chat scattered | `v3/components/RunSidePanel.tsx:163-214` | Consolidate into "Review" tab as default; persistent Apply/Fix/Iterate above tab strip |
| F-11, U-17 | P1 | v3 RunDetailPage missing ScoreRing / numeric `Verified N/100` / RunStamp / statusReason tooltip | `v3/pages/RunDetailPage.tsx:365-367`, `v3/components/RunSidePanel.tsx` | Port v2 score richness |
| F-05, R-03 | P1 | Run Chat wipes on remount; v3 ChatPane uses local `useState`; run details seeded invisibly | `v3/components/RunSidePanel.tsx:2189, 2243-2265` | Hydrate from `/api/runs/{id}/conversation` + add run-summary header |
| U-18 | P1 | Chat opens to empty input with no priming | RunSidePanel ChatPane | Seed visible system summary + 3 suggested prompts |
| U-19 | P1 | Apply/Fix/Iterate has no confirmation, no cost preview, no spec diff | RunSidePanel actions | Drawer: estimate + spec diff + confirm |
| U-20 | P1 | Failure summary only handles known shapes; opaque outcomes get generic "something went wrong" | `v3/components/RunFailureSummary.tsx` | Map every `statusReason` to one-line explainer + suggested action |
| U-21 | P1 | Success state doesn't celebrate or surface next loop | RunDetail COMPLETE | Post-completion micro-CTA bar |
| F-25, R-04 | P2 | File icons confusing — emoji map (`🟡 kt`, `🟨 js`, identical `⚛️` for jsx+tsx) | `components/run/FileTree.tsx:207-233` | lucide-react FileCode/FileJson + per-extension tint, OR vscode material icons |
| F-29 | P2 | No "rerun from this task" with cost estimate; no spec copy/diff; no per-task→file link | `v3/pages/RunDetailPage.tsx`, `RunSidePanel.tsx` | Add affordances |
| U-22 | P2 | No "rerun from this task" with cost estimate | `TaskTimeline` | Per-task rerun + confirmation drawer |
| U-23 | P2 | No spec copy button, no spec diff across iterations | RunDetail | Copy + diff affordances |
| U-24 | P2 | No per-task → file link — clicking a Timeline task doesn't jump to its files | RunDetail | Wire `agent_task.filesChanged` to file-tree highlight |

## S. Project chat

| IDs | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| F-04, R-02 | P1 | Project Chat steals page scroll (= J row above) | `pages/project/ProjectDashboardPage.tsx:589` | Add `h-full min-h-0` on chat-tab pane |
| F-20 | P1 | Silent successes — `compactProjectChat`, `analyseProject`, `applyToRun`, `iterateRun`, `submitReview` no toast | various | Standardize toast on every mutating action |

## T. Multi-repo

| IDs | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| U-25 | P1 | File tree groups by path, not by repo; no per-repo collapse, no repo color-tagging | `v3/pages/RunDetailPage.tsx` file tree | Top-level group by repo + per-repo PR status chip + collapse |
| U-26 | P2 | Cross-solution dependencies (T2 depends on T1 in another repo) invisible in UI | Timeline | Dependency arrows + "blocked by" badges |
| U-27 | P2 | Per-repo PR status not surfaced inline — must click into PR list | RunDetail header | Per-repo PR chips next to repo names |

## U. Free-tier & billing UX

| IDs | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| U-29 | P1 | Cap-block forces "upgrade or bust" — no "queue for next month" | cap-block modal | Add queue option |
| U-30 | P1 | "Upgrade to Pro" doesn't show TEAM / Enterprise trial / actual consumed | cap-block | Link to `/pricing?from=cap-block&consumed=N` with PRO highlighted |
| U-31 | P1 | Tier comparison page doesn't show user's current tier inline | `PlansPage` | "You're on FREE" badge on FREE card |
| U-32 | P2 | No usage forecast — user can't predict whether they'll hit cap this month | dashboard / billing | "At current pace you'll hit cap on day 23" line |
| U-33 | P2 | Invoice download not linked from cap-block | cap-block | Link to InvoicesPage |
| U-34 | P2 | Currency display: INR vs USD by geo-detect, no toggle; user traveling sees wrong currency | global pricing | Manual override in settings |

## V. Admin / operator

| IDs | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| U-35 | P1 | Admin tables — no filter / search / saved views / bulk actions | `/admin/*` | Standard table primitive |
| U-36 | P1 | SUPER_ADMIN debugging billing must scroll to find a user; no jump-to | `/admin/users` | Search-as-you-type + recent-users sidebar |
| U-37 | P2 | Feature flag toggles have no observability | `/admin/feature-flags` | Per-flag panel with last-30-days metric |
| U-38 | P2 | Tier lifecycle controls don't preview impact ("8 users on this tier") | `TierLifecyclePage` | Pre-toggle confirmation with impact summary |
| U-39 | P2 | No "impersonate user" affordance for support flows | `/admin/users` | Time-limited impersonation token + audit log |

## W. Status taxonomy & confidence framing

| IDs | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| U-43 | P1 | Status taxonomy maze — status × statusReason × verifiedScore × accuracyScore × reviewerVerdict × finalBuildPassed × testsPassed/Total = 6 independent dimensions | global | Publish single user-facing taxonomy: "build, tests, review — and a confidence score." Hide rest behind disclosure |
| U-44 | P1 | Confidence framing abstract — `Verified 92/100` without explanation | global | Lead with concrete artifacts: ✓ Built · ✓ 73/73 tests · ✓ Reviewer clean · ✓ 4/4 requirements |
| U-59 | P1 | No "what we changed and why" summary on COMPLETE | RunDetail success | Generated 3-line summary at run end |
| U-60 | P1 | Cost breakdown opaque — total only, no "Planner $2 · Worker $8 · Reviewer $2.50" | RunDetail / RunSidePanel | Cost breakdown chip series |

## X. Cross-cutting / globals

| IDs | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| U-46 | P1 | No global search — runs by spec text, PRs by title, projects | global | Cmd-K unified searcher |
| U-47 | P1 | No undo / soft-delete on destructive actions | global | 30-day soft-delete window with restore toast |
| U-48 | P1 | Notification preferences (V139 backend) have no UI surface | settings | Per-event-type × per-channel opt-in/out matrix |
| U-49 | P1 | No "what changed since last visit" digest | dashboard | "Since you were away" banner on first dashboard load per session |
| U-50 | P2 | Keyboard shortcuts inconsistent — Cmd-/ doesn't open chat, arrows don't navigate file tree, Esc misses 5 modals | global | Audit; publish shortcut sheet (visible via `?` press) |
| U-51 | P2 | Notifications don't batch | `RunCompletionNotifier` + frontend | Debounce + group on same project within 5min |
| U-52 | P2 | No timezone display — timestamps are user-local but unlabeled | global | "12:45 PM (UTC+05:30)" or hover-to-see UTC |
| U-53 | P2 | Default sort orders inconsistent | global | Default to "most recent activity" |
| U-54 | P2 | Pagination has no "jump to last" / "jump to N" / page-size selector | global tables | Standard pagination primitive |
| U-55 | P2 | No "Recently viewed" affordance | global | Recent items in Cmd-K + sidebar |
| U-56 | P2 | RunCard in list view has no verdict pill or score | `RunCard.tsx` | Surface verdict + score |
| U-57 | P2 | Share-link surface has no expiry preview | share-links table | Expiry chip on every row + countdown |
| U-58 | P2 | Validation report PDF — page breaks not enforced; long reports break mid-section | `validation-report-pdf.html` | CSS `page-break-inside: avoid` |
| U-61 | P2 | Provider used not surfaced clearly on run card | RunCard, RunDetail header | Provider chip on every run surface |
| U-62 | P2 | No "compare runs" view | run list | Multi-select + "Compare" |
| F-28 | P2 | More drawer is the only path to Settings/Docs/Memory; only accessible from Pulse tab | `pages/project/ProjectDashboardPage.tsx`, `v3/components/MoreDrawer.tsx` | Header link to drawer from any project sub-route |

## Y. Microinteractions / polish

| IDs | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| U-63 | P2 | Loading uses spinner / skeleton / shimmer inconsistently | global | One pattern: skeleton for known-shape, spinner for unknown |
| U-64 | P2 | Animation durations not standardized — every component picks its own | `v3.css` + inline | Tokens: `--motion-fast: 150ms`, `--motion-base: 200ms`, `--motion-slow: 400ms` |
| U-65 | P2 | `prefers-reduced-motion` not honored | global | `@media (prefers-reduced-motion: no-preference)` wrap |
| U-66 | P2 | No completion feedback — tab title doesn't pulse, no celebrate banner | RunDetail terminal | Tab-title pulse `· Run complete` + brief animation |
| U-67 | P2 | Button hover states inconsistent — some lift, some color-shift, some nothing | global | Standardize via `Button.tsx` primitive (audit usage) |

## Z. Help & docs

| IDs | Sev | Issue | Where | Fix |
|---|---|---|---|---|
| U-68 | P1 | No contextual help — `?` icons sparse; no inline "how does this work" | global | `<HelpDot>` primitive linking to glossary or docs |
| U-69 | P2 | No public changelog — users don't see what shipped | global | `/changelog` rendered from CLAUDE.md or curated source |
| U-70 | P2 | Error toasts don't link to docs | global | Toast accepts optional doc-link slot |

---

## Patterns worth calling out

About **30 of the P1 items collapse to 8 missing primitives**:

| Primitive | Knocks out |
|---|---|
| `<DataState>` + `useFetch<T>` | F-12, U-45, parts of U-20 |
| `<Field>` + `<FieldError>` | F-10 |
| `<HelpDot>` + extended `<Glossary>` | U-43, U-44, U-68 |
| Standard `<Table>` (filter/sort/bulk/page-size) | U-35, U-36, U-53, U-54 |
| Standard `<Pagination>` | U-54 |
| Standard `<EmptyState>` (with CTA slot) | U-45, U-49 |
| Motion tokens (`--motion-*`) + reduced-motion wrapper | U-64, U-65, U-67 |
| `useUrlState<T>()` | F-24, parts of F-03 |

A second cluster — **~15 backend items collapse to 3 architectural moves**:

| Move | Knocks out |
|---|---|
| Central `ExecutorRegistry` (named bounded pools, per-pool metrics, per-call timeouts) | B-06, B-07, B-15, B-21 |
| Outbox pattern for cost + activity events | B-05, B-09, B-10, parts of B-19/B-20 |
| Atomic-SQL sweep (replace read-modify-write with `UPDATE … SET col = ? WHERE …`) | B-03, B-08, B-11, B-16 |

Building those 11 primitives + 3 backend moves would close ~55 of the 120 issues. The remaining 65 are individually-scoped fixes.

---

## Recommended first-three PRs

**PR-A (1 week) — quick wins, no architecture**
- F-01 mobile modal sweep (ResponsiveModal migration)
- R-01..R-04 reported bugs (F-03, F-04, F-05, F-25)
- F-12/U-45 `<DataState>` + `<EmptyState>` primitives
- F-10 `<Field>` + `<FieldError>` primitive

**PR-B (2 weeks) — user-confidence pillar**
- F-11/U-17 score parity in v3 RunSidePanel (port ScoreRing + numeric `Verified N/100` + statusReason tooltip + RunStamp)
- F-13/U-16 tab consolidation in RunSidePanel
- U-06 plan-approval rework (plain-English task descriptions)

**PR-C (1 week) — documentation + microcopy**
- U-01 naming sweep (Run / Mission / Launch consolidation)
- U-11 narrative event stream (translate `WORKER_TOOL_CALL` to prose)
- U-43/U-44 status-taxonomy doc + concrete-artifact framing

---

## Audit methodology

This audit was produced via three parallel deep-dive passes on 2026-05-09:

1. **Backend audit (first + second pass)** — 28 issues across security, concurrency, performance, architecture, recovery, tests
2. **Frontend audit (first + second pass)** — 29 technical issues across SSE, state, modals, theme, mobile, fork, performance, a11y, routing, forms
3. **UX journey walkthrough** — 70 issues across onboarding, plan approval, live progress, run-detail, multi-repo, free-tier, admin, taxonomy, cross-cutting, polish, help

Each finding includes file:line citations and a concrete fix shape. Severity tags follow:

- **P0** — broken / unrecoverable / security or data-integrity risk
- **P1** — significant friction or systemic regression
- **P2** — cleanup, polish, nice-to-have

User-reported bugs are mapped to their corresponding finding IDs in the table at the top of this document.
