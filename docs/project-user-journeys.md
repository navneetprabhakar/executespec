# Project-scoped user journeys

This doc walks a user through five named journeys that all begin inside a Project. A Project is the unit of work on ExecuteSpec — it owns one or more linked repos, a memory ledger, an architecture doc, RAG documents, members, an activity feed, and the runs / PR-reviews / code-reviews fired against those repos.

Each journey is a numbered click-path. Endpoints are cited in `METHOD /path` form and link to the source file. Tier gates are flagged inline. UI surfaces cite the page component path under `frontend/src/signal/pages/`.

Use `docs/user-journeys.md` for the system-wide (non-project-scoped) flows — signup, upgrade, BYOK, invite, SSO, audit, GDPR.

---

## Journey 1: Onboard a new repo to autopilot

Goal: a user lands on the Projects directory, creates a project, links a GitHub repo to it, lets the architecture pipeline run, kicks off a code review, and dispatches one autofix against the worst-severity bucket.

Tier requirement: any tier (FREE works for steps 1-5; the autofix in step 6 requires PRO+ because code-review is a tier-gated feature).

```
Projects directory
        │
        ▼
Create project   POST /api/projects
        │
        ▼
Link a repo     POST /api/repos               (registers under org or user scope)
        │       POST /api/projects/{id}/repos/{repoId}  (creates the link)
        ▼
Bootstrap fires async (.executespec/ PR opens on customer repo)
        │
        ▼
Generate arch    POST /api/projects/{id}/architecture/generate
                 (codemap → per-repo HLD/LLD → combined arch doc)
        │
        ▼
Browse arch     GET /api/projects/{id}/architecture          (latest)
                GET /api/projects/{id}/repos/{repoId}/architecture
        │
        ▼
Submit code review  POST /api/code-reviews   (tier-gated, PRO+ on most tiers)
        │           waits for QUEUED → CLONING → STATIC_RUNNING → LLM_RUNNING → COMPLETE
        ▼
Dispatch autofix   POST /api/code-reviews/{id}/autofix       body { severity: "CRITICAL" }
                   → opens a patch-mode run, one PR per severity
```

### Steps

1. The user clicks **Projects** in the nav. The Projects directory loads via `GET /api/projects` (in `frontend/src/signal/pages/ProjectsPage.tsx`). For each non-archived project it then fans out and calls `GET /api/projects/{id}/dashboard` + `GET /api/runs?projectId={id}` (per project) to compute a recency-weighted average score. (The N+1 pattern is acknowledged in the research as expensive on accounts with many projects.)

2. The user clicks **New project**, fills in `name` + `description`, and submits. The frontend calls `POST /api/projects` against `src/main/java/com/navneet/executespec/auth/controller/ProjectController.java`. The body is validated by `CreateProjectRequest` — `name` is required, `<` / `>` are rejected (Finding 6), `aiProvider` must match `ANTHROPIC|GEMINI|GOOGLE|OPENAI`. The controller delegates to `ProjectService.createProject`, which enforces V140 tier caps (`max_projects_per_org` from `tier_config`), adds the creator as `PROJECT_ADMIN` to `project_member`, and emits a `PROJECT_CREATED` activity row.

3. The user is taken to the empty project page (`ProjectComposePage` is the default route for `/projects/:projectId` per `frontend/src/App.tsx`). They click the **Repos** tab (`ProjectReposPage.tsx`). The page loads `GET /api/projects/{id}/repos` (linked) and `GET /api/repos` (available in their scope).

4. The user clicks **Link new repo**. The Add panel opens, listing every GitHub repo the user's PAT can see via `GET /api/repos/github`. They pick one and submit. The frontend first registers it via `POST /api/repos` (`CreateRepoRequest`: `scope` ORG|USER, `githubRepoFullName`, optional `attachAnyway` for R-325 big-repo bypass). `ProjectRepoService.createRepo` checks the size gate `executespec.repo.max-size-kb` (default 250 000), detects a monorepo subdir (R-322), runs `StackDetector` for `(language, framework, version)`, and seeds `last_synced_sha`.

5. The frontend then calls `POST /api/projects/{projectId}/repos/{repoId}` to create the join row. `ProjectRepoService.linkToProject` enforces:
   - V140 tier cap `max_repos_per_project` per project,
   - M-039 `allowed_github_owners` allowlist on the org,
   - scope eligibility (org-scope repos must match caller's org).

   On success it dispatches `BootstrapService.bootstrapAsync` (`src/main/java/com/navneet/executespec/folder/BootstrapService.java`). The bootstrap service:
   - pre-flights the user's PAT scopes (`GET /user`, requires `repo`),
   - renders `.executespec/README.md` from `resources/templates/executespec/README.md.template`,
   - opens a setup PR on the customer repo via `ExecutespecFolderWriter`,
   - persists `folder_status=BOOTSTRAP_PR_OPEN` on the `project_repo` row,
   - records the PR via `PullRequestRecordService.recordCreated(... Kind.BOOTSTRAP)`,
   - emits an activity event.

   When the customer merges the bootstrap PR, the GitHub webhook flips `folder_status` to `BOOTSTRAPPED` (handled in `PullRequestRecordService.flipFolderStatusToBootstrapped`).

6. The user goes to the **Architecture** tab (`ProjectArchitecturePage.tsx`) and clicks **Generate**. The frontend hits `POST /api/projects/{id}/architecture/generate` (`src/main/java/com/navneet/executespec/codeintel/ProjectArchitectureController.java`). The controller calls `ProjectArchitectureService.generate(...)`, which runs the 3-step pipeline:
   - Step 1: `CodeMapGenerator.generateWithSha` lists repo root via GitHub Trees API and emits a markdown codemap (files / routes / models / per-file function lists, Java via `JavaAstSymbolExtractor`, others via regex). Persisted as a `ProjectCodeMap` row, keyed by repo SHA.
   - Step 2: `RepoArchitectureGenerator` (LIGHTWEIGHT tier) turns the codemap into a per-repo HLD+LLD markdown. Persisted as `RepoArchitectureDoc`.
   - Step 3: `ArchitectureDocGenerator` (PLANNING tier) synthesises a combined cross-repo project architecture doc from all per-repo arch docs. Persisted as `ProjectArchitectureDoc` with a JSONB `generated_from_shas` map and `generated_from_branches` (V192).

   The service short-circuits when the per-repo fingerprint matches the latest doc and its content is non-blank (PR-K cache).

7. The arch page reloads via `GET /api/projects/{id}/architecture` and renders the markdown. The user can switch to per-repo arch via `GET /api/projects/{id}/repos/{repoId}/architecture`, or browse files via `GET /api/projects/{id}/repos/{repoId}/files` (file-browse, 1 MB cap with path-traversal guard).

8. The user clicks the **Code** tab (`ProjectCodePage.tsx`), then **Code review**. They click **Submit scan**. The frontend POSTs to `/api/code-reviews` (`CodeReviewController.java`) with `{projectId, repoId, repoFullName}`. `CodeReviewService.assertTierAndQuota` checks:
   - FREE tier → `TierBlockedException` (HTTP 403),
   - `tier_config.code_review_enabled=false` → 403,
   - `quota >= 0` and monthly count over → `QuotaExhaustedException` (HTTP 429).

   On pass, `submitScan` is idempotent on `(projectId, repoFullName, scannedSha)`: if a COMPLETE row exists at the current HEAD SHA, it returns immediately. Otherwise it persists a `code_review_scan` row in `QUEUED`, broadcasts via SSE channel `code-review-{scanId}`, and schedules `runScan` on a daemon pool (sized `PoolSizing.coreMultiple(1, 2, 8)`).

9. `runScan` walks the lifecycle: `CLONING` (via `WorkspaceInitService.cloneFromRepo`), `STATIC_RUNNING` (via `SemgrepRunner` with `--config p/ci`), `LLM_RUNNING` (top-20 file samples scored by path, runs `CodeReviewAgent` — PLANNING tier, single `submitCodeReview` tool call, 16k max tokens). On COMPLETE it persists every agent issue + leftover (non-dropped, non-rewritten) static finding into `code_review_issue`, renders a severity-grouped markdown report via `CodeReviewMarkdownRenderer`, and calls `costTracker.finalizeRun`.

10. The Code page renders the scan via `GET /api/projects/{id}/code-reviews/latest?repo=...` and `GET /api/code-reviews/{id}`. The user sees severity chips with counts (CRITICAL / MAJOR / MINOR / INFO). They click **N CRITICAL** to dispatch an autofix.

11. The frontend POSTs `/api/code-reviews/{id}/autofix` body `{severity: "CRITICAL"}`. `CodeReviewController.autofix` calls `projectAccessGuard.assertCanWrite` (closes the audit B #12 write-gate gap) and `CodeReviewAutofixService.dispatchFixForSeverity`. The autofix service:
    - validates severity against `{CRITICAL, MAJOR, MINOR, INFO}`,
    - asserts scan is COMPLETE,
    - filters OPEN issues by severity,
    - resolves the linked repo id from `project_repo_link` (case-insensitive `repoFullName` match),
    - builds a per-issue prompt (cap 25 issues, 600 chars per suggested fix),
    - submits a `Finding(FindingType.CODE_REVIEW_ISSUE, ...)` via `FindingPatchSubmitter`. The submitter dedupes per `scanId|severity` key and dispatches a `runMode=PATCH` pipeline run with `source="finding:CODE_REVIEW_ISSUE"`. The patch-mode pipeline auto-pushes via `ScmPushService.push()` on terminal, opening one PR per severity bucket.

12. The user lands back on the project's **PRs** sub-tab (`ProjectRunsPage.tsx` sub=`prs`) and watches the new PR. `POST /api/projects/{id}/prs/refresh` is hit on focus to poll GitHub for OPEN→MERGED transitions and CI rollup. Merging the PR fires `PostMergeCascadeService.onMerged` → fast path (banner in Redis) + async heavy path (`StalenessService.invalidate` + `ProjectArchitectureService.generate`).

### Anomalies the user may hit

- The bootstrap PR can fail at scope-check time if the caller's PAT lacks `repo`. `BootstrapService.checkPatScopes` persists `folder_status=BOOTSTRAP_FAILED` and emits an activity row; the user fixes their PAT and retries the link.
- If the repo is over 250 MB and the user did not pass `attachAnyway=true`, `ProjectRepoService.createRepo` rejects at registration time (R-323).
- If the project is a cross-org `ProjectMember`, `ProjectRepoController.listProjectRepos` calls `assertCanRead` (admits) but the service's own `requireProjectAccess` re-rejects on org mismatch — research flags this as a real bug that blocks cross-org members from reading their own linked repos.

---

## Journey 2: Spec a feature against an existing project

Goal: a user opens a project that already has linked repos + architecture, types a spec into the composer, picks which repos to target, approves the plan, and watches the run open a PR.

Tier requirement: any tier. The composer is the standard run-submission surface.

```
Project landing (default route /projects/:projectId)
        │
        ▼
Compose page    GET /api/projects/{id}/repos
                GET /api/runs?projectId=…  (recent activity, hero)
                GET /api/projects/{id}/estimate?requirement=…  (debounced p25/p75)
        │
        ▼
Submit run     POST /api/runs            body { projectId, selectedRepoIds, userRequest, … }
        │
        ▼
Live SSE       GET /api/runs/{id}/sse-token
               GET /api/runs/{id}/events?token=…
        │
        ▼
Plan gate     POST /api/runs/{id}/approve   body { approved, creditsConfirmed }
        │
        ▼
Live run + auto-push to PR  → ProjectRunsPage shows the new PR
```

### Steps

1. The user clicks a project in the directory. They land on `/projects/:projectId`, which `App.tsx` routes to `ProjectComposePage` (default tab, v3 parity). The page loads:
   - `GET /api/projects/{id}/repos` to populate the repo checklist,
   - `GET /api/runs?projectId={id}&page=0&size=5` for the recent-activity rail and live-run hero detection,
   - the project's default `aiProvider` (from `ProjectDto`).

2. The user types into the spec textarea. As they type, the page debounces `GET /api/projects/{id}/estimate?requirement=...` to `EstimationService.estimate` (`src/main/java/com/navneet/executespec/project/estimation/EstimationService.java`). The service buckets the requirement by length (short / medium / long), reads the last 20 completed runs in the project, and returns p25/p75 task count + cost. Falls back to a hardcoded global average for brand-new projects (`Estimate(3, 7, 0.05, 0.25, "Platform average (no project history yet)")`).

3. The user toggles the AI-provider chip (Anthropic / OpenAI / Gemini) and the cost-cap input. The cap is tier-clamped per the composer: FREE → $3, PRO/TEAM → $25, ENTERPRISE → $1000 (read from `useAuthStore((s) => s.permissions)`).

4. The user clicks **Submit**. The frontend POSTs `/api/runs` (`RunController.submit` in `src/main/java/com/navneet/executespec/api/controller/RunController.java`). The body uses `RunRequest`:
   - `userRequest` is `@NotBlank @Size(max=50_000)` (aliased from `requirement` / `spec`),
   - `aiProvider` matches `ANTHROPIC|GEMINI|GOOGLE|OPENAI`,
   - `projectId` ≤36 chars,
   - `selectedRepoIds` is `List<UUID>`,
   - `executionMode` matches `PROTOTYPE|PRODUCTION`,
   - `userCostCap` ∈ [0.10, 1000.00].

5. The controller runs the input through `InputSanitizer` (8-layer security — NFKC + zero-width strip + base64 decode + scan against injection / shell / abuse patterns). It then atomically increments + checks quota via `TierEnforcementService.assertAndIncrementRunCount` (pessimistic lock; auto-consumes a bonus top-up when the user is over). On success it returns 202 and `RunOrchestrator.submitRun` runs the V2 pipeline:
   - `SmartClassifierAgent` (LIGHTWEIGHT) classifies the spec → `SINGLE_SHOT | SMALL | MEDIUM | LARGE | LEGACY`,
   - `OutlinePlannerAgent` (PLANNING tier) emits a `Plan` via the `submitPlan` tool. For `LARGE` / `LEGACY` plans it splits into skeleton + parallel `TaskExpanderAgent` (WORKHORSE) + `CoherencePlannerAgent` (LIGHTWEIGHT),
   - Pipeline pauses at `AWAITING_APPROVAL` (PRODUCTION) or bypasses gate (PROTOTYPE).

6. The user lands on `/plan/:runId` (`PlanPage.tsx`). It polls `GET /api/runs/{id}` every 2 s while CLASSIFYING / PLANNING, then renders the plan once `currentGate=PLAN`. They see the task list, classifier reasoning, per-phase cost estimate (`classifierCostUsd`, `outlineCostUsd`, `expansionCostUsd`, `coherenceCostUsd` from `RunDetail`), and the credit ceiling.

7. The user clicks **Approve plan**. The frontend POSTs `/api/runs/{id}/approve` (`RunController.approvePlan`) with `ApprovalRequest{approved:true, creditsConfirmed:N}`. The controller:
   - re-verifies `creditsConfirmed` matches the current estimate (409 on stale pricing),
   - runs M-032/M-033 governance gates (spend cap, admin-approval-above-USD),
   - applies the gate-loss fallback (auto-FAIL if no pipeline waiting),
   - charges credits via `usageTrackingService.increment`,
   - emits `PLAN_APPROVED` analytics.

8. The user lands on `/live/:runId` (`LivePage.tsx`). It calls `GET /api/runs/{id}/sse-token` for a 5-minute scoped token (`scope=sse`, `runId` claim — `JwtUtil.generateSseToken`), then opens an `EventSource` to `GET /api/runs/{id}/events?token=...` (`SseController`). It also polls `GET /api/runs/{id}` every 8 s as a safety net.

9. `SequentialExecutor` runs each task through a tier-routed `WorkerAgent` (per-complexity caps LOW=30/4 k, MEDIUM=50/8 k, HIGH=75/16 k). Each task gets a per-task `buildProject` smoke test. `ReviewerAgent` runs general + wiring passes plus `PostEditFormatter`. On `COMPLETED`, `ValidationReportService` persists the report; `RunCostTracker` finalises cost; `ScmPushService.pushMultiRepo` opens one PR per repo (linking them via `GitHubRepoService.updatePullRequestBody`); the auto-preview kicks off (5-min TTL Cloud Run).

10. The user clicks **View result** when status reaches `COMPLETED` / `PARTIAL`. `ResultPage.tsx` loads `GET /api/runs/{id}` + `GET /api/runs/{id}/report` + `GET /api/runs/{id}/prs` and renders 9 tabs (Overview / Tasks / Timeline / Code / Cost / PR / Validation / Tests / Chat). They click the PR link, review on GitHub, merge.

11. Merging fires `POST /webhooks/github` (`GitHubWebhookController` in `src/main/java/com/navneet/executespec/project/pr/GitHubWebhookController.java`). HMAC SHA-256 verified, `pull_request.closed && merged` routes through `PullRequestRecordService.applyWebhookUpdate` and `PostMergeCascadeService.onMerged` (fast banner + async arch regen).

### Anomalies

- If the user picks a FREE-tier-blocked HIGH-complexity plan, `TierEnforcementService.assertComplexityAllowed` rejects at approval time.
- If `creditsConfirmed` doesn't match what the planner re-estimated by the time the user clicks Approve, the call 409s and the page reloads the plan.
- BUG-006 carry: `RunController.forkRun` calls `assertCanSubmitRun` (non-incrementing probe) rather than `assertAndIncrementRunCount`. A FREE user could in theory escape the 5/month limit through repeated forks; research flags this as not yet patched.

---

## Journey 3: Migrate the project's tech stack

Goal: a user wants to upgrade Spring Boot 2.x → 3.x (or Java 17 → 21, etc.) across one or more repos in a project. They browse the migrations catalog, pick a recipe, run a dry-run, then apply.

Tier requirement: any tier. The catalog is static content; the apply step opens a normal run, which respects tier caps.

Engineering-honest note: as of the research snapshot, `/migrations` is a **static catalog** rendered from a hard-coded `MIGRATIONS` array in `frontend/src/signal/pages/ExtraPages.tsx`. There are no backend `/api/migrations` endpoints. The detail page's "apply" action hands off to the composer with a pre-filled spec via `sessionStorage`. There is no separate dry-run sandbox — the standard preview (5-min Cloud Run TTL) on the resulting run plays that role.

```
Migrations directory     /migrations (static catalog, no API)
        │
        ▼
Migration detail        /migrations/:slug  (static demo content, no API)
        │
        ▼
"Apply to project" → seeds sessionStorage 'signal:pending-spec'
        │
        ▼
Compose page picks up pre-filled spec → POST /api/runs as in Journey 2
```

### Steps

1. The user clicks **Migrations** in the nav. The route `/migrations` renders `MigrationsPage` (`frontend/src/signal/pages/ExtraPages.tsx`). The page renders a hard-coded `MIGRATIONS` array (6 entries: Spring Boot 2→3, JDK upgrades, React 16→18, etc.). No backend call is made.

2. The user clicks a recipe. The route `/migrations/:slug` renders `MigrationDetailPage` — also static content. The detail page shows:
   - what this migration does,
   - sample before/after diff,
   - estimated complexity,
   - "Apply to a project" CTA.

3. Clicking **Apply to a project** stashes the recipe's spec body into `sessionStorage.setItem('signal:pending-spec', ...)` and navigates to `/compose` (system-level) or `/projects/:projectId` (project-level). The composer (`ComposePage.tsx` or `ProjectComposePage.tsx`) reads `sessionStorage.getItem('signal:pending-spec')` on mount and pre-fills the textarea.

4. The user picks a project (or stays in the current project), confirms the targeted repos, and submits. From here the flow is identical to Journey 2 starting at step 4 — `POST /api/runs` with a pre-filled `userRequest`, plan gate, approve, run.

5. The plan gate shows the migration broken into per-file tasks (e.g. `pom.xml`, `application.yml`, controller migration, deprecated-API replacements). The user can preview the change via the auto-preview before merging the PR.

6. After the run terminates with a PR, the user reviews and merges. The tech-debt rollup updates on the next dashboard load via `GET /api/projects/{id}/tech-debt?since=30` (`TechDebtService.build` in `src/main/java/com/navneet/executespec/project/techdebt/TechDebtService.java`), which reads `validation_report.issues_json` and builds histograms by category + severity + top-5 file hotspots.

### What's not built yet

The research notes do not describe any dedicated `MigrationRecipe` entity, `/api/migrations` endpoints, slot-value persistence, or a dedicated dry-run sandbox beyond what the standard run preview offers. The page exists as a content surface that funnels into the standard run pipeline. If you need true dry-run + diff preview before applying, you get the standard auto-preview on the PR-bound run, not a separate migration-engine surface.

---

## Journey 4: Use a readymade library piece

Goal: a user wants to add a pre-built component (auth setup, Stripe checkout, file upload) to one of their project repos. They browse the library, pick a solution, fill in slot values, and apply.

Tier requirement: any tier. Same model as Journey 3 — catalog is static; apply is a normal run.

Engineering-honest note: `/library` is also a static catalog (`LibraryPage` + `LibraryDetailPage` in `ExtraPages.tsx`), backed by a hard-coded `LIBRARY_SOLUTIONS` array (6 entries). There are no backend `/api/library/*` endpoints, no slot-value persistence, no `LibrarySolution` entity, no "preview before apply" beyond the standard run preview. The library is a templated-spec generator that hands off to the composer.

```
Library directory       /library  (static catalog)
        │
        ▼
Solution detail         /library/:slug  (static demo, fill slot values in UI)
        │
        ▼
"Apply to project" → templated spec + slot values → sessionStorage
        │
        ▼
Compose → POST /api/runs (same path as Journey 2)
```

### Steps

1. The user clicks **Library** in the nav. `/library` → `LibraryPage`. They browse 6 readymade solutions, each with a title, short description, and stack tag.

2. They click a solution. `/library/:slug` → `LibraryDetailPage`. The page shows the solution's full description, requirements, and any slot inputs (e.g. "Webhook URL", "Stripe price ID"). Slot values are local component state — not persisted, not server-side validated. The static catalog is the source of truth.

3. The user fills slot values and clicks **Apply to a project**. The page composes a spec string by templating the slot values into a recipe body, stashes it via `sessionStorage.setItem('signal:pending-spec', ...)`, and navigates to the composer.

4. From the composer onward, the flow is identical to Journey 2 — submit a run, approve the plan, watch the live page, review the PR.

5. After the PR merges, `PostMergeCascadeService.onMerged` invalidates staleness and (async) regenerates architecture so the new component shows up in the next arch doc version.

### What's not built yet

There is no library-slot-value persistence, no "my saved library applications" list, no `LibraryPiece` entity, and no per-piece versioning. The catalog is purely UI. Note `SpecTemplateService` does exist for reusable spec skeletons (per-project or per-org), exposed at `GET POST /api/projects/{id}/templates` and `POST /api/orgs/{orgId}/templates` (`SpecTemplateController.java`) — but that's a separate surface from `/library` and is reached via the templates page (`/templates`) which is also static-driven.

---

## Journey 5: Triage a failed PR

Goal: a PR ExecuteSpec opened is failing CI, has reviewer comments, or shows up in the project's tech-debt rollup. The user wants to triage and dispatch the right autofix surface.

There are **three autofix surfaces** that all funnel through `FindingPatchSubmitter` (`src/main/java/com/navneet/executespec/finding/FindingPatchSubmitter.java`), each opening a patch-mode (`runMode=PATCH`) run that auto-pushes on terminal. A fourth, **Phase 3 CI failure webhook** (`CiFailureAutoFixService`), is OFF by default behind feature flag `ci.autofix.enabled`.

Tier requirement:
- PR Review autofix: PRO+ (PR Review feature itself is tier-gated; `tier_config.pr_review_enabled` + `pr_review_monthly_quota`).
- Code Review severity-bucket autofix: PRO+ (same; `code_review_enabled` + `code_review_monthly_quota`).
- Tech-debt category autofix: any tier where the project has runs to roll up (TechDebt page is visible to all; the underlying issues come from prior runs' validation reports).

```
              PR Review verdict        →  Autofix N comments       → patch run → PR push
                  ▲
                  │ POST /api/pr-reviews             (PRO+, monthly quota)
                  │ POST /api/pr-reviews/{id}/autofix
                  │
PR / project ──────                                                                    ──→ one PR per surface
                  │
                  │ POST /api/code-reviews                          (PRO+, monthly quota)
                  │ POST /api/code-reviews/{id}/autofix             body { severity }
                  │
                  ▼
              Code Review severity bucket → Autofix N CRITICAL → patch run → PR push

              Tech-Debt rollup → Fix category → patch run → PR push
              POST /api/projects/{id}/tech-debt/fix  body { category }
```

### Steps — PR Review autofix (Phase 1)

1. The user lands on `/projects/:projectId/runs` (sub=`prs`) (`ProjectRunsPage.tsx`). The PR list comes from `GET /api/projects/{id}/dashboard` (`PullRequestRecord` rows). They see an open PR with a yellow CI status dot.

2. They click the PR row. The frontend calls `POST /api/pr-reviews` body `{projectId, repoFullName, prNumber}` (`PrReviewController.submit`). `PrReviewService.assertTierAndQuota` enforces:
   - FREE / `pr_review_enabled=false` → 403 `TierBlockedException`,
   - quota `-1` = unlimited, `0` = blocked, otherwise count since UTC month start.

3. `submitReview` is idempotent on `(repo, pr, head_sha)` — re-submitting the same PR at the same head SHA returns the existing row. On a fresh submission it persists `pr_review.status=QUEUED`, emits SSE on `pr-review-{id}`, and dispatches `PrReviewAgent` on a daemon pool. The agent (PLANNING tier, single `submitReview` tool call, 8k max tokens) reads the PR diff via `GitHubRepoService` (200 000-char diff cap) and emits a verdict (`APPROVE | REQUEST_CHANGES | COMMENT`) plus line-anchored comments with severity (`CRITICAL | MAJOR | MINOR | INFO`) and category (`BUG | SECURITY | PERFORMANCE | STYLE | LOGIC | TEST_GAP`).

4. The user opens the PR review verdict popover and clicks **Autofix N comments**. The frontend POSTs `/api/pr-reviews/{id}/autofix`. `PrReviewController.autofix` runs `projectAccessGuard.assertCanRead(projectId)` then `PrReviewAutofixService.dispatch`. Guards (all return 409 on failure):
   - 404 if review missing,
   - 409 if status ≠ COMPLETE,
   - 409 if verdict == APPROVE,
   - 409 if no comments,
   - 409 if no linked repo,
   - 502 if GitHub returns no head branch,
   - 409 if PR is closed unmerged.

5. The service fetches the PR head branch (for `branchOverride`) and builds a focused per-comment prompt (file:line + severity + category + body for each comment, plus a constraints block — use `runTests`, fall back to `shell`, do not branch / push, do not modify unrelated files / tests, do not reply inline). It submits a `Finding(FindingType.PR_REVIEW, reviewId, projectId, [linkedRepoId], prompt, snap.headBranch(), userId, null)` via `FindingPatchSubmitter`.

6. The submitter dedupes per finding key (`PR_REVIEW|{reviewId}`), dispatches with `runMode=PATCH`, `source="finding:PR_REVIEW"`, and `branchOverride=<PR head branch>`. The patch-mode pipeline (single thin-agent loop; no classifier / planner / reviewer; per-provider iter caps Anthropic 150 / OpenAI 100 / Gemini 120) runs against the failing-comment spec. On terminal, `ScmPushService.push()` auto-pushes to the SAME PR branch — no new branch, no new PR.

7. The user watches the patch run on `/live/:runId`, then re-runs the PR review (or just reloads the PR on GitHub) to confirm the comments are addressed.

### Steps — Code Review severity-bucket autofix (Phase 4)

8. From the **Code** tab → **Code review** sub-tab, the user sees the severity chips (`N CRITICAL`, `N MAJOR`, etc.) from the most recent `code_review_scan` for that repo.

9. They click **N CRITICAL**. The frontend POSTs `/api/code-reviews/{id}/autofix` body `{severity: "CRITICAL"}`. The controller runs `projectAccessGuard.assertCanWrite` (closes audit B #12) and `CodeReviewAutofixService.dispatchFixForSeverity` (Journey 1 step 11 covers this in detail). One PR per severity bucket.

10. The dispatched patch run opens a **new** PR branch (no branch override at scan-time; this autofix surface creates a fresh PR per severity, unlike PR Review which pushes to the existing PR's head).

### Steps — Tech-debt category autofix (Phase 2)

11. From the **Pulse** tab the user clicks through to the tech-debt rollup (or directly on `/projects/:projectId/code` sub=`debt`). `GET /api/projects/{id}/tech-debt?since=30` returns a `TechDebtService.Report` with:
    - `issuesByCategory: Map<String, Integer>`,
    - `issuesBySeverity: Map<String, Integer>`,
    - `topFiles: List<FileHotspot>` (top-5),
    - `scoreTrend: List<DimensionPoint>`,
    - `averageAccuracyScore`, `totalRunsAnalyzed`.

    The data source is `validation_report.issues_json` from prior completed runs in the project — no LLM, no scan; it's a rollup.

12. The user clicks **Fix** next to a category (e.g. `LOGIC_ERROR`). The frontend POSTs `/api/projects/{id}/tech-debt/fix` body `{category: "LOGIC_ERROR"}`. `ProjectDashboardController.fixTechDebt` calls `TechDebtFixService.dispatchFixForCategory`. The service:
    - merges `issues_json` history across runs,
    - takes the top 30 issues for the category (`MAX_ISSUES_IN_PROMPT`),
    - builds a prompt with file / severity / description for each,
    - submits a `Finding(FindingType.TECH_DEBT, projectId|CATEGORY, projectId, selectedRepoIds, prompt, null, userId, null)` via `FindingPatchSubmitter`.

13. The submitter dedupes per finding key, dispatches the patch run, and auto-pushes a fresh PR per category. The user watches the run on `/live/:runId` and the PR appears on `/projects/:projectId/runs` sub=`prs`.

### Steps — CI failure webhook (Phase 3, OFF by default)

14. If `ci.autofix.enabled` is flipped on for an org, GitHub's `check_run` webhook auto-fires `CiFailureAutoFixService.onCheckRunFailed` on PR CI failure. Per-PR cap: `MAX_AUTO_FIX_ATTEMPTS=3` (V170 column `pull_request_record.ci_auto_fix_attempts`). The service seeds the failure log into the prompt and dispatches a patch run against the same head branch. This stays off until SWE-bench Lite resolved% justifies the unattended-bot path.

### Anomalies & gotchas

- `PrReviewService.runReview` hardcodes `aiProvider = "anthropic"` (line 261, TODO: "default for now; PROJECT-aware override in slice 3"). The project's `aiProvider` is ignored for PR Review until slice 3 lands.
- `PrReviewAgent` reads its prompt directly via `StreamUtils.copyToString` instead of going through `PromptAssembler` — it doesn't get `{{include:fragment}}` substitution.
- `SemgrepRunner.parse` maps semgrep ERROR → MAJOR rather than CRITICAL — every CRITICAL label in a code-review scan comes from the LLM augmentation pass, never from semgrep itself.
- The autofix endpoints across all three surfaces share the `FindingPatchSubmitter` chokepoint, so dedup is automatic if the user double-clicks Fix. The dedup key is per-surface (`PR_REVIEW|{reviewId}` vs `CODE_REVIEW_ISSUE|{scanId}|{severity}` vs `TECH_DEBT|{projectId}|{CATEGORY}`).

---

## Cross-cutting endpoints

These show up in multiple journeys:

| Purpose                          | Endpoint                                                       | Source                                                                 |
| -------------------------------- | -------------------------------------------------------------- | ---------------------------------------------------------------------- |
| Tenant guard (project)           | internal `ProjectAccessGuard.assertCanRead` / `assertCanWrite` | `src/main/java/com/navneet/executespec/project/security/ProjectAccessGuard.java` |
| Tenant guard (run)               | internal `RunAccessGuard.requireRunAccess` / `requireRunWriteAccess` | `src/main/java/com/navneet/executespec/auth/security/RunAccessGuard.java` |
| Visibility filter                | internal `RunVisibilitySpec.forUser`                           | `src/main/java/com/navneet/executespec/auth/security/RunVisibilitySpec.java` |
| Activity feed                    | `GET /api/projects/{id}/activity`                              | `src/main/java/com/navneet/executespec/activity/ActivityFeedController.java` |
| GitHub webhook receiver          | `POST /webhooks/github`                                        | `src/main/java/com/navneet/executespec/project/pr/GitHubWebhookController.java` |
| Project dashboard rollup         | `GET /api/projects/{id}/dashboard`                             | `src/main/java/com/navneet/executespec/project/dashboard/ProjectDashboardController.java` |
| Workspace health                 | `GET /api/projects/{id}/workspace-health`                      | same file, `WorkspaceHealthService`                                    |
| Architecture pipeline (3-step)   | `POST /api/projects/{id}/architecture/generate`                | `src/main/java/com/navneet/executespec/codeintel/ProjectArchitectureController.java` |
| RAG document upload              | `POST /api/projects/{id}/documents`                            | `src/main/java/com/navneet/executespec/project/rag/ProjectDocumentController.java` |
| Spec templates                   | `GET POST /api/projects/{id}/templates`                        | `src/main/java/com/navneet/executespec/project/template/SpecTemplateController.java` |
| Estimate                         | `GET /api/projects/{id}/estimate?requirement=...`              | `EstimationService` via `ProjectDashboardController`                   |
| Member list / cross-org invite   | `GET POST /api/projects/{id}/members`                          | `src/main/java/com/navneet/executespec/auth/controller/ProjectController.java` |

The PROJECT_VIEWER role is read-only — granted on cross-org `ProjectMember` rows since V175. Research flags that several write-effect endpoints currently call `assertCanRead` instead of `assertCanWrite` (`ProjectRepoController.linkToProject`, `SpecTemplateController` create/delete, `PullRequestController#refreshProjectPrs`, `ProjectDashboardController#fixTechDebt`, `#dismissPostMergeBar`). PROJECT_VIEWER members pass these write paths today.
