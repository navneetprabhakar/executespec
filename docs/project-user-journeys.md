# Project-scoped user journeys

> Five named, end-to-end journeys against the project workbench (`/projects/:id/*`). Each step cites the endpoint and the file that owns it. Derived from the code on this branch (migration head V199).

## Journey 1 — Onboard a new repo to autopilot

The team's first project is fresh. Goal: have ExecuteSpec aware of the repo, see an architecture doc, see an initial code-quality scan, and resolve the first batch of tech debt.

1. **Create the project.** UI: `/projects` → "New project". `PromptModal` collects `{name, description?}`. Posts `POST /api/projects {name, description?, aiProvider?, githubOwner?}` (`auth/controller/ProjectController`). Capped by `tier_config.max_projects_per_org`.
2. **Link the GitHub repo.** UI: `/projects/:projectId/repos` → "Add repo". Calls `POST /api/projects/{id}/repos {repoUrl}` (`project/controller/ProjectRepoController`). Backend resolves the repo via the user's PAT, stores `project_repo` row with default branch + GitHub installation id. Capped by `tier_config.max_repos_per_project`.
3. **Install the webhook.** Same page → "Install webhook" → `POST /api/repos/{repoId}/webhook` writes a `pull_request` + `check_run` webhook to GitHub using the user's PAT. Sets `project_repo.webhook_installed=TRUE` (V101).
4. **Regenerate architecture.** UI: `/projects/:projectId/architecture` → "Regenerate". Calls `POST /api/projects/{id}/architecture/generate`. `codeintel/ProjectArchitectureService.regenerate` runs the 3-step pipeline (per-repo codemap → per-repo HLD/LLD → cross-repo synthesis). Status visible via SSE / poll on `GET /api/projects/{id}/architecture/versions`.
5. **Run a whole-repo code review scan.** UI: `/projects/:projectId/code` → Code review sub-tab → "Run scan". Calls `POST /api/code-reviews {projectId, repoFullName}` (`codereview/CodeReviewController`). `CodeReviewAgent` walks the repo on PLANNING tier, writes findings to `code_review_finding`. Quota-gated by `tier_config.code_review_monthly_quota`.
6. **Apply a tech-debt autofix.** UI: `/projects/:projectId/code` → Tech debt sub-tab. `TechDebtPanel` shows categories × severities × file hotspots from `GET /api/projects/{id}/tech-debt?since=30`. Click "Fix" on the top-cost category → `POST /api/projects/{id}/tech-debt/dispatch-fix {category}` (`project/techdebt/TechDebtFixService`). Routed through `FindingPatchSubmitter` as a PATCH-mode run with `source="finding:TECH_DEBT_<CATEGORY>"`. Opens a new PR for that category.
7. **Review and merge the PR.** `pull_request_record` row visible on `/projects/:projectId/pulse`. Click through to GitHub, review, merge. Webhook fires `pull_request.closed` → `GitHubWebhookController` updates `pull_request_record.ci_status` and `merged_at`.
8. **Architecture re-runs automatically?** Not yet — the user needs to click "Regenerate" again or wait for the next manual trigger. `StalenessService` will flag the architecture doc as STALE on the dashboard once the merged SHA differs from `project_architecture_doc.generated_from_sha`.

## Journey 2 — Spec a feature against an existing project

Goal: implement a new feature spec against the project's already-linked repos with multi-repo awareness, plan approval, and a verified score on the resulting PR.

1. **Open the project compose surface.** UI: `/projects/:projectId` (the default sub-page is `ProjectComposePage.tsx`). Pre-fills the project context — composer auto-selects all linked repos in `selectedRepoIds`.
2. **Refine the spec.** Use the embedded `SpecChat` ("Build my spec") which calls `POST /api/conversation` (`api/controller/ConversationController`) to assist drafting. The estimate chip surfaces p25/p75 task + cost predictions via `GET /api/projects/{id}/estimate?requirement=<spec>` once spec length ≥12 chars (debounced 600ms).
3. **Pick repos to scope.** Toggle chips drop or include specific `selectedRepoIds`. If the user wants to limit blast radius (e.g. only touch the backend repo), they un-toggle the frontend repo here.
4. **Set the hard cap.** Numeric input clamps at the tier's `tierMaxCap` (FREE $3 / PRO-TEAM $25 / ENTERPRISE $1000). Defaults to `min(2, tierMaxCap)`.
5. **Submit.** `POST /api/runs {userRequest, aiProvider, executionMode: 'PRODUCTION', projectId, selectedRepoIds, userCostCap}` → 202 + RunSummary. Navigates to `/plan/:runId`.
6. **Approve the plan.** `PlanPage.tsx` polls `GET /api/runs/{id}` until status `AWAITING_APPROVAL`, shows the plan card with classifier verdict + estimated credits + cap. "Approve · charge N credits" calls `POST /api/runs/{id}/approve {approved: true, creditsConfirmed}`. Charges credits via `tierEnforcementService.assertAndIncrementRunCount(userId, credits)`.
7. **Watch live.** Auto-redirect to `/live/:runId`. SSE stream (`useSse(runId)`) populates the live log + task progress + cost chip. `CostProgressBar` shows spend vs hard cap.
8. **Review the PR.** Terminal status → auto-navigates to `/result/:runId`. `ScoreCard` shows the verifiedScore + verdict (≥85 Shippable / 70-84 Needs review / <70 Failed). For multi-repo, the PR tab shows one row per repo with cross-link summary text in each PR body. Open each PR on GitHub, review, merge.

## Journey 3 — Migrate the project's tech stack (e.g. JS → TS)

Goal: incrementally migrate the project's source files to a new stack using a modernization recipe, with parity tests + plan-gate per cluster.

1. **Pick a migration recipe.** UI: `/migrations` (sidebar tagged `BETA`). Grid of recipes (JS→TS, Express→Fastify, Mongoose→Prisma, React class→hooks, Webpack→Vite, Pages→App Router). Click "JavaScript → TypeScript".
2. **Configure.** Detail page (`MigrationDetailPage` in `ExtraPages.tsx`) shows step-by-step ("Scan all .js/.jsx files → cluster by dependency graph → start at leaves → rename + infer types → run `tsc --noEmit` → open one PR per cluster ~30 files"). KvList configures target repo, strictness flags, PR-size cap, test command, est total cost.
3. **Dry-run.** UI calls `POST /api/migrations/recipes/{slug}/dry-run {projectId, repoId, config}` (`migration/controller/MigrationController`). Returns the per-cluster plan: number of files, estimated cost, estimated time. UI surfaces the dry-run summary inline.
4. **Apply.** "Kick off migration" → `POST /api/migrations/recipes/{slug}/apply {projectId, repoId, config}`. Backend dispatches a sequence of V2 BUILD-mode runs through `RunOrchestrator.submitRun` — one per cluster (~30 files).
5. **Approve each cluster's plan gate.** Each cluster pauses at `AWAITING_APPROVAL`. Heads-up copy from the detail page: "We pause at every plan gate. You're never further than one approval away from the next batch."
6. **Merge cluster PRs incrementally.** Each cluster's `ScmPushService.push` opens a PR with `tsc --noEmit` already passing. Merge → CI stays green → next cluster fires.
7. **Watch project memory build.** Each completed run appends a digest to `project_run_ledger` (`memory/RunLedgerAgent`). After 10 runs, `MemoryCompactionService` rewrites a `project_memory_snapshot`. The planner reads this on subsequent runs via `ProjectMemoryProvider.buildPlannerContext(projectId)` so each cluster has context on what the previous ones did.
8. **Regenerate architecture when done.** `/projects/:projectId/architecture` → "Regenerate" → the 3-step pipeline picks up the new file structure. `StalenessService` clears the STALE badge.

## Journey 4 — Use a readymade library piece (e.g. Stripe checkout)

Goal: drop a pre-built solution (Stripe checkout, OAuth login, RAG-with-pgvector, etc.) into the project with minimal spec authoring.

1. **Open the library.** UI: `/library` (sidebar tagged `BETA`). Grid of solutions from `LIBRARY_SOLUTIONS` static catalogue (Stripe checkout, OAuth login, RAG with pgvector, GitHub Actions CI, Docker compose dev, Razorpay checkout). Each card shows estimated time + credit cost.
2. **Pick a solution.** Click "Stripe checkout" → `/library/stripe-checkout` (`LibraryDetailPage`). Shows "What you'll get" checklist + pre-filled spec textarea + "Used by 1,247 orgs" stats.
3. **Tweak the spec.** Edit the textarea. The default text reads: "Add Stripe checkout to the API. Both subscription and one-time charge endpoints. Webhook handler at /webhooks/stripe with HMAC SHA-256 signature verification. Persist customer + invoice. Failed-payment retry with 3-attempt dunning. Add test-mode env var + sandbox keys. Tests must still pass."
4. **Fill slot values (when wired through the API).** `GET /api/readymade/solutions/{slug}` returns slot definitions (e.g. currency, env-var prefix). UI presents slot inputs.
5. **Instantiate.** UI calls `POST /api/readymade/solutions/{slug}/instantiate {slots, projectId}` (`readymade/controller/ReadymadeController`). Backend renders the spec template with slot substitutions and calls `RunOrchestrator.submitRun(...)` to dispatch a normal V2 BUILD-mode run. Returns the new `runId`.
6. **Approve and ship.** From here the flow matches Journey 2 steps 6-8 — `/plan/:runId` → approve → `/live/:runId` → `/result/:runId` with the PR ready for review.

## Journey 5 — Triage a failed or low-score PR with autofix

Goal: a PR opened against the project has either a CI failure, a low verifiedScore, or unresolved review comments. Use one of the four autofix surfaces to push targeted fixes without re-typing the spec.

### Path 5a — Address PR review comments (autofix phase 1)

1. **Open the PR.** UI: `/projects/:projectId/pulse` → recent PR card, or directly from `/result/:runId` → PR tab.
2. **Run a PR Review.** Click "PR Review" on the PR row. UI calls `POST /api/pr-reviews {projectId, repoFullName, prNumber}` (`prreview/PrReviewController`). PRO+ only; quota-gated by `tier_config.pr_review_monthly_quota`. `PrReviewAgent` polls GitHub for review comments + diff hunks, persists verdict to `pr_review`.
3. **Click the verdict popover.** `PrReviewButton` (in `components/runs/`) opens the verdict popover with the review's findings.
4. **Autofix.** "Autofix N comments" → `POST /api/pr-reviews/{id}/autofix`. `PrReviewAutofixService.dispatch` builds a synthetic spec from the comments, dispatches via `FindingPatchSubmitter.submit` with `runMode=PATCH` + `branchOverride=<PR head branch>` so commits land on the same branch.
5. **Watch the patch run.** New runId visible on `/runs`. Auto-pushes on terminal — new commits appear on the PR. CI re-runs.

### Path 5b — Bucket-fix code-review findings (autofix phase 4)

1. **Run a whole-repo code review scan.** UI: `/projects/:projectId/code` → Code review sub-tab → "Run scan". `POST /api/code-reviews {projectId, repoFullName}`. `CodeReviewAgent` (PLANNING tier) writes findings to `code_review_finding` with severities CRITICAL / MAJOR / MINOR.
2. **Read the severity chips.** `CodeQualityPanel` shows "3 CRITICAL", "8 MAJOR", "21 MINOR".
3. **Pick a severity to fix.** Click "3 CRITICAL" → `POST /api/code-reviews/{scanId}/autofix {severity: 'CRITICAL'}`. `CodeReviewAutofixService.dispatch` aggregates the CRITICAL-severity findings into a focused spec, dispatches PATCH-mode run with `source="finding:CODE_REVIEW_CRITICAL"`. One PR per severity.
4. **Review the new PR.** Same shape as Journey 2 step 8.

### Path 5c — Resolve a CI failure (autofix phase 3, webhook-driven, OFF by default)

1. **GitHub CI fails.** A PR check fires `check_run.completed` with `conclusion=failure`. Webhook delivered to `POST /webhooks/github` (`project/pr/GitHubWebhookController`). HMAC SHA-256 verified.
2. **Webhook decoder.** Locates the matching `pull_request_record` by `head_sha`. Increments `ci_auto_fix_attempts` (V170; max 3 per PR).
3. **Feature flag check.** `featureFlagService.isEnabled("ci.autofix.enabled")` (default OFF). When OFF, just logs and returns 202.
4. **When enabled (TEAM+ org operator action):** `CiFailureAutoFixService.handle` builds a spec from the failing job's log tail and dispatches via `FindingPatchSubmitter.submit` with `runMode=PATCH` + `branchOverride=<PR head branch>` + `source="finding:CI_FAILURE"`.
5. **New commits push to PR.** CI re-runs automatically. After 3 attempts on the same PR, the webhook stops auto-firing (cap enforced by `ci_auto_fix_attempts`).

### Path 5d — Iterate on a low-score run

1. **Open the failed/low-score run.** UI: `/result/:runId` shows `verifiedScore < 70` → "Failed" verdict, or 70–84 → "Needs review".
2. **Try another provider.** "Try another provider" row on the Overview tab. Click "Anthropic" / "OpenAI" / "Gemini" → `api.createRun({userRequest, aiProvider, executionMode: <same>, skipApproval: <if PROTOTYPE>, projectId})` → routes to `/live/:newRunId`.
3. **Iterate on the spec.** Chat tab → write a refinement message → "Iterate as new run" → `POST /api/runs/{id}/iterate {userRequest}` (`api/controller/RunConversationController`). Creates a fresh run from the source run's codebase as starting point. Routes to `/plan/:newRunId`.
4. **Retry a single failed task.** Tasks tab → on a FAILED or PARTIAL task → "Retry task" → `POST /api/runs/{runId}/tasks/{taskId}/retry`. Re-runs just that task with `requireBuildVerification=true`. Does NOT auto-push; the user clicks "Update PR" afterwards to push the result.
</content>
</invoke>