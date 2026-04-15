# Project: ExecuteSpec

> This file is read by Claude Code at session start. Keep it accurate.
> Last updated: 2026-04-15 (v2.5.5 — Batch 7: architecture diff source-file correlation via GitHub Compare API)

## Architecture

- **Backend:** Java 21, Spring Boot 3.3, Spring AI 1.1.4
- **Harness:** V2 is the default and only supported path for new runs (`com.navneet.executespec.v2.*`). V1 (`harness/`, `agent/`, `compiler/`, `reviewer/`, `batch/`) is frozen pending deletion — see [docs/plans/deprecation-removal-plan.md](docs/plans/deprecation-removal-plan.md) for the phased rollout. Shared infrastructure (StateStore, PipelineRun/AgentTask entities, CostEstimator, ApiKeyResolver, WorkspaceInitService, GitHubRepoService, ModelResolver, GateStore, UiStyleResolver) stays live under V2.
- **V2 pipeline:** `V2RunOrchestrator` (entry) → `PlannerAgentV2` (plan as a `submitPlan` tool call) → plan gate (PRODUCTION pauses at AWAITING_APPROVAL, PROTOTYPE bypasses) → `SequentialExecutorV2` (tier-routed worker per task, one at a time) → `ReviewerAgentV3` (general + wiring passes) → validation report + test-gen kickoff → auto-preview. Runs persist to the shared `pipeline_run`/`agent_task` tables.
- **V2 tools:** Unified `Tool` contract in `v2.tool` — `readFile`, `writeFile`, `editFile`, `listFiles`, `searchFiles`, `shell`, `gitStatus`, `gitDiff`, `gitCommit`, `buildProject`, plus `completeTask` for native termination. `ToolRegistryV2.bind(ExecutionContext)` returns Spring AI `ToolCallback`s with run context captured. Sequential-by-design: no FileLockTool.
- **V2 LLM router:** `LlmRouterV2.call(LlmRequestV2)` uses native tool calling for all 3 providers. Anthropic via Spring AI; OpenAI via `OpenAiNativeToolClient` (bypasses Spring AI 1.1.4's `extra_body` bug); Gemini via `GeminiNativeToolClient` (handles schema sanitization + thought-signature round-trip for 2.5+ models). Every call flows through `V2RunCostTracker` for token + cost accounting.
- **V2 worker:** `WorkerAgentV2` — single-agent, multi-turn loop. Per-complexity caps: LOW=12 iters/4k tokens, MEDIUM=22/8k, HIGH=35/16k. Outcomes: COMPLETED | STOPPED_WITHOUT_COMPLETE | ITERATION_LIMIT_EXCEEDED | ERROR. Native completion via `CompleteTaskTool`. Conversation snapshots saved to Redis every 5 iterations via `ContextStoreV2` (24h TTL) for recovery.
- **V2 planner:** `PlannerAgentV2` emits a structured plan via a restricted-tool `submitPlan` call (no JSON-in-text parsing). Schema includes `buildCommand`, `smokeTestStrategy` (BUILD | PREVIEW_HEALTH | TEST_SUITE), `runtimeSurface` (HTTP | CLI | LIBRARY | STATIC_WEB), per-task `hardConstraints`. Classification (SINGLE_SHOT | MEDIUM | MULTI_TASK) caps task count (1 / 4 / unlimited). `SINGLE_SHOT` bypasses the LLM and synthesises a 1-task plan.
- **V2 sequential executor:** per task — compose system + user prompt → `WorkerAgentV2.run()` → if worker hit `completeTask`, run `buildProject` smoke test → pass=SUCCESS, fail=ONE retry with build error injected, retry fail=COMPLETED_WITH_WARNING. Aggregate: ALL_SUCCESS | PARTIAL_WITH_WARNINGS | FAILED | ABORTED. Per-task model tier: LOW→LIGHTWEIGHT, MEDIUM→WORKHORSE, HIGH→PLANNING.
- **V2 reviewer:** `ReviewerAgentV3` — two `WorkerAgentV2`-backed passes (general + wiring), 30 iter cap each. Wiring pass is project-type-aware (Rust `mod.rs`, TS barrels, Spring component-scan, Express routes, Python decorators, Go imports). Final verdict: CLEAN | FIXED_ISSUES | UNRESOLVED_ISSUES | REVIEW_FAILED. Iteration-limit outcomes fall through to final-build verdict rather than masquerading as REVIEW_FAILED.
- **V2 cost + tokens:** `V2RunCostTracker` accumulates per-call usage + cost (Anthropic cache-aware). Phase-level attribution (planner / reviewer) persisted on `PipelineRun` via snapshot diffs; per-task worker tokens on `AgentTask` rows. Run totals are built atomically via `PipelineRunRepository.addTokens` calls from each phase — terminal `terminate()` no longer overwrites, only bumps up. Pre-plan `estimateRunTierAware()` populates `costEstimateLow/High`, `creditsCharged`, `hardCapUsd`.
- **V2 observability:** `V2Metrics` — `executespec.v2.*` meters for run.outcome + run.latency, planner.latency + planner.tasks, task.status + task.latency + task.smoketest, worker.iterations + worker.outcome + worker.toolcall, llm.latency, reviewer.verdict, context.save + context.save.bytes. Dump endpoint `/api/v2/test/metrics`. Prometheus scrape at `/actuator/prometheus`.
- **V2 recovery:** `StartupRunResumerV2` scans non-terminal V2 runs on boot (PLANNING/WORKING/REVIEWING/AWAITING_APPROVAL) and fails them cleanly with a clear reason + terminal SSE, so the UI surfaces the interruption instead of leaving runs stuck. Full replay (restore conversation history + resume at saved iteration) is a planned follow-up — see [docs/plans/harness-v2-remaining-work.md](docs/plans/harness-v2-remaining-work.md) Gap 2.
- **V2 cancel:** `V2RunOrchestrator.cancel(runId)` is the V2 path for `DELETE /api/runs/{id}` — signals the plan gate, interrupts the pipeline future, writes CANCELLED status + terminal SSE.
- **V2 eval harness:** `scripts/v2-eval.sh` submits LOW / MEDIUM / HIGH specs × anthropic / openai / gemini (9 runs, PROTOTYPE), polls each to terminal, emits machine-readable JSON + a markdown comparison to `scripts/v2-eval-results/v2-eval-<timestamp>.{json,md}`. Specs live in `scripts/v2-eval-specs/{low,medium,high}.txt`.
- **Batch 7 — arch diff source-file correlation (v2.5.5):** New `ArchitectureDiffService` + `GET /api/projects/{id}/architecture/diff?from=&to=` returns per-repo added/modified/removed/renamed files between two architecture versions. Uses the `generated_from_shas` map already persisted on `ProjectArchitectureDoc` — for each repo present in both versions, calls `GitHubRepoService.compareCommits(repoFullName, baseSha, headSha, pat)` which hits GitHub's `GET /repos/{o}/{r}/compare/{base}...{head}` endpoint. No new schema, no agent-task filesChanged plumbing. `ArchitectureViewer` renders a "Source file changes" section under the existing LCS text diff when the user opens Diff vs v{N-1} — grouped by repo, subgrouped by status (Added/Modified/Removed/Renamed), each file shows `+additions -deletions`. Silent fallback when the SHAs are missing (pre-v3.0 rows) or GitHub returns non-200. No migrations.
- **Batch 6 — regression fix + UX polish (v2.5.4):** **Fix**: `ExecutionRunRepository.findListByProjectId` / `findDetailById` dropped `solutions.repoMappings` from the `@EntityGraph` — Hibernate can't JOIN FETCH two List-typed collections in one query (MultipleBagFetchException on `/api/projects/{id}/prs` and `/launches`). The list endpoint now joins `solutions` only; `repoMappings` load lazily per-solution under OSIV during DTO mapping. **UX-5.2 Failed Solution UI**: `FailureDecisionModal` rewritten — replaces the dropdown+submit with dedicated action buttons (Retry / Skip this+dependents / Fix manually / ⚠ Attempt anyway), computes blocked downstream solutions from `dependencyIds`, and shows an amber block listing them before the decision. **UX-4.1 Reset sheet**: new `ResetWorkspaceSheet` component replaces the inline toast from the Repos tab — previews per-repo SHA movement (old→new, "already up to date") before confirming; on submit calls `analyseProject`, refetches health, and shows a ✓ line when done. **UX-1.3 Multi-banner stacking**: `PostMergeCascadeService` now writes to a Redis List (`project:post-merge:<id>`, trimmed to 3 newest, TTL refreshed on push) via `readPostMergeBars` / `dismissPostMergeBars`. Controller endpoint returns `List<PostMergeBar>`. Frontend shows top 2 banners + a "+N more merged" chip with full details in the tooltip. **UX-2.2 Skeleton**: `ExecutionSetup` estimate chip now has a pulsing skeleton state while the debounced request is in-flight + a tooltip on the basis string explaining how the estimate is computed. No migrations.
- **Batch 5 UX polish (v2.5.3):** Frontend-only pass against [docs/plans/executespec-ux-plan.md]. `ExecutionStepper` swapped to user-facing labels ("Analysing codebase", "Building", "Paused — action needed") with hover descriptions + live elapsed time on the active step (driven off `executionRun.updatedAt`). `RepoChip` + `extractRepoShortName(...texts)` surfaced on `TaskCard` — derives `repos/<name>/` prefix from title/objective/completion-notes so multi-repo runs get repo attribution without a backend DTO change (TaskGraph node chip deferred — SVG). `StalenessWarning` on `ExecutionSetup` calls the existing `workspace-health` endpoint on mount + selection change; when any selected repo is stale it renders an amber non-blocking block with "Analyse before planning" (invokes `analyseProject` + refetches health) and "Proceed anyway" CTAs. `TechDebtPanel` upgraded: horizontal recharts `BarChart` for issues-by-category with color coding (LOGIC blue, SECURITY red, PERFORMANCE amber, …), severity rendered as colored chips (BLOCKER/MAJOR/MINOR/INFO) instead of plain rows. No backend changes, no migrations.
- **Batch 4 — tech debt, templates, TDD, cancel rollback (v2.5.2):** New `TechDebtService` + `GET /api/projects/{id}/tech-debt?since=<days>` aggregates `validation_report.issues_json` into category/severity counts + top-5 file hotspots + dimension trend (no LLM call). `TechDebtPanel` renders on Project Overview with All-time / 30-day toggle. Spec templates land at V102 (`project_spec_template`, owner_type PROJECT|ORG, soft-delete). New `SpecTemplateService` + `SpecTemplateController` expose CRUD via `/api/projects/{id}/templates` + `/api/orgs/{orgId}/templates`; project list merges project + org-scoped templates in one call. `TemplatePicker` dropdown sits above the requirement textarea in `ExecutionSetup`, with inline "Save current as template" using whatever the user has typed. TDD Mode (Phase 4.3): `RunRequest.tddMode` (Boolean) threads through `V2RunOrchestrator.submitRun` (new overload, defaults false on legacy callers) → `runPipeline` → `PlannerRequestV2.tddMode` → `{{TDD_MODE_BLOCK}}` slot in `prompts/v2/planner.md`. `PlannerAgentV2.renderTddBlock` injects an instruction telling the planner to lead with test tasks and have implementation tasks declare `dependsOn` the test ids. Toggle exposed in `NewRunModal`. Phase 2.3 cancel rollback: `ExecutionOrchestrator.abortExecution` now runs `runCompensations` after the status flip — iterates every `ExecutionSolution` with a `prUrl`, calls `GitHubRepoService.closePullRequest(repoFullName, prNumber, pat)` (PATCH `/repos/{owner}/{repo}/pulls/{n}` with `state=closed`, idempotent on 422), and emits `EXEC_COMPENSATION_UPDATE` (RUNNING → COMPLETE/FAILED) per step plus a final `EXEC_COMPENSATION_DONE` summary on the existing exec SSE channel. `useExecutionStore` gains a `compensations[]` slice + `applyCompensationUpdate`/`setCompensationDone` actions. `CancellationPanel` renders the live cleanup view (✓/✗ per PR, "Close on GitHub" deep-link on failure) on the ABORTED status page. Migration V102.
- **Batch 3 — workspace health, N+1, post-merge, estimation (v2.5.2):** New `WorkspaceHealthService` + `GET /api/projects/{id}/workspace-health` rolls up per-repo SHA drift (codemap vs current origin HEAD, origin lookup cached 10 min in Redis at `project:origin-sha:<repoId>`), webhook install status, open-PR count, and last-analysed timestamp. `WorkspaceHealthPanel` renders the report on the Project Overview tab with per-repo chips + an "Analyse all" button. `ExecutionRunRepository.findListByProjectId` uses `@EntityGraph(solutions, solutions.repoMappings)` — the Launches list endpoint (`/api/projects/{id}/launches`) + project PR rollup (`/api/projects/{id}/prs`) now single-query their solution data instead of N+1-ing per row. `PostMergeCascadeService.writePostMergeBar` drops a transient Redis marker (`project:post-merge:<projectId>`, 10 min TTL) on PR merge; `PostMergeBar` component polls `GET /api/projects/{id}/post-merge-bar` on dashboard mount and renders a "✓ PR #N merged — Start next requirement →" banner above the tab strip (dismissible, auto-routes to Launch). New heuristic `EstimationService` + `GET /api/projects/{id}/estimate?requirement=…` returns p25/p75 task + cost estimates from the project's last 20 completed runs (bucketed by requirement length), falling back to project-average then platform-average; `ExecutionSetup` shows a debounced gray chip under the requirement textarea (no LLM call). No new migrations in Batch 3.
- **Batch 2 — worker RAG, snapshots, webhooks, reset (v2.5.2):** `SequentialExecutorV2` now injects a task-scoped `{{REFERENCE_DOCS}}` block into every worker's system prompt via `RetrievalService.retrieve(projectId, task.description(), k=2, cap=800)` — planner already had its own wider k=3/1500 retrieval, the worker pass is narrower but keeps API contracts / limits / field names in front of the code-writing LLM. `ExecutionRequest` gained a `projectId` field; `V2RunOrchestrator` threads it through. `WorkspaceInitService.cloneMultipleRepos` now captures `git rev-parse HEAD` per repo right after clone and persists a `run_repo_snapshot` row (V100). `RunSyncService.syncToOriginalCommit(runId)` + `POST /api/runs/{id}/sync/original` reset the workspace back to those exact SHAs — distinct from `POST /api/runs/{id}/sync` which resets to current origin HEAD. "Reset to run state" button added to `InlineRunDetail`. New-repo Launches auto-install the GitHub pull_request webhook via `GitHubRepoService.installWebhook` (requires `github.webhook.callback-url` + `github.webhook.secret`); `ProjectRepoService.createAndLinkNewRepo` records the result on `ProjectRepo.webhookInstalled` (V101 — NOT NULL default FALSE). Linked existing repos get a manual "Install webhook" button on `RepoCard` that calls `POST /api/repos/{repoId}/webhook` using the caller's PAT (needs `admin:repo_hook`). Webhook status chip surfaces on every RepoCard. `ProjectRepoManager` adds a "Reset workspace" button that calls the existing `analyseProject` path to re-pull every linked repo and regenerate codemaps + architecture before a Launch. Migrations V100, V101.
- **Batch 1 hardening (v2.5.2):** `StalenessService` moved from Caffeine (per-JVM) to Redis (`project:stale:<id>`, 5-min TTL) so invalidation on one MIG node is visible to every other node — no more stuck STALE badges after a generate. New `ProjectWorkspaceWarmer.warmDiskCache(projectId)` (`@Async`) pulls DB-authoritative codemaps + architecture.md and mirrors them to the local project workspace; called post-generate so the node that ran `generate()` warms its disk, and exposed for future first-access hooks on other nodes. `ProjectWorkspaceService.writeRawFile(projectId, path, content)` is a no-commit mirror path (atomic tmp-rename + Redis lock) used by the warmer. `V2RunOrchestrator.normalizeProvider` now takes `projectId`+`runId`, falls back to `project.ai_provider` before the platform default (OPENAI), and logs WARN at each fallback — legacy `ExecutionRun` rows with null `ai_provider` no longer silently land on OPENAI. V99 backfill migration closes the same gap for existing rows. New `SUPER_ADMIN` endpoint `POST /api/admin/executions/reconcile` (powered by `AdminReconciliationService` + `ExecutionRunRepository.findStaleNonTerminal`) marks `ExecutionRun` rows stuck in non-terminal status >30 min as FAILED with reason `reconciled_orphan_v251` — operational cleanup for pre-v2.5.1 orphans. Migration V99.
- **Project hardening + Launch loop (v2.5.1):** `spring.jpa.open-in-view=true` (flipped from false) eliminates the entire class of lazy-init crashes that hit every detached-entity DTO mapper in the execution + project controllers. `ExecutionPlannerService.persistPlan` now falls back to name-match (full or short `githubRepoFullName`) when the LLM returns a repo name instead of a UUID in the `repoId` field — the previous `UUID.fromString` throw left `repoId=null`, skipped the clone, and triggered `maxRetries=3` per solution. `SolutionExecutionService.submitSolutionRun` now passes `ExecutionRun.aiProvider` to `V2RunOrchestrator.submitRun` (was silently defaulting to openai). `ProjectArchitectureController.analyse` + `ExecutionOrchestrator.triggerAnalysis` thread the caller's `AppUser.githubPat` into `ProjectArchitectureService.generate(projectId, branch, pat)` — private repos were returning empty trees under the system PAT. New endpoints: `POST /api/projects/{id}/prs/refresh` (polls GH + post-merge cascade), `POST /api/projects/{id}/analyse?branch=<name>`, `POST /api/runs/{id}/sync?branch=<name>`, `POST /api/executions/{id}/resume`. Architecture diff UI (LCS line diff of `architecture.md` vN vs vN-1). `GitHubRepoService.listRepoFiles` + `readRepoFile` no longer hardcode `main`. Launch header has a persistent Cancel button on every non-terminal step. Migrations V96-V98 (auto-push flag on PLATFORM scope, execution_* id columns widened to VARCHAR(64) + tenant_isolation policy drop-recreate dance).
- **Project Workspace + RAG (v2.5.0):** Per-project workspace at `workspace/projects/<projectId>/` (codemaps/, architecture.md, summary.md, docs/, .git) owned by `ProjectWorkspaceService` with atomic writes + Redis-backed lock. `ProjectArchitectureService.generate()` now writes each codemap + architecture.md + summary.md to disk; DB blobs remain authoritative (Cloud Run / GCE VMs are ephemeral). `ProjectBriefService` summarises arch into 2-4 sentences via LIGHTWEIGHT-tier LLM, builds a `{{PROJECT_BRIEF}}` block injected into `PlannerAgentV2`. `POST /api/projects/{id}/analyse` + `GET /api/projects/{id}/summary` are the new entry points (legacy `/architecture/generate` still works). `V2RunOrchestrator` does pre-execution lazy refresh via `StalenessService` and post-success staleness invalidation. `ReadFileTool` accepts `project:` prefix and falls back to DB when the disk cache is missing. Always-on RAG via Tika `DocumentExtractor` + 500/50-token `ChunkingService` + Spring AI OpenAI `EmbeddingService` (text-embedding-3-small) + JDBC `ChunkStore` over pgvector; `ProjectDocumentService` + `/api/projects/{id}/documents` handle upload / async index pipeline (UPLOADED -> EXTRACTING -> EMBEDDING -> READY/FAILED). `RetrievalService.retrieve(projectId, query, k=3, cap=1500)` is wired into the planner (`{{REFERENCE_DOCS}}`) and `ProjectChatService`. Architecture tab gets a GitHub-style `Diff vs v{N-1}` button (LCS line diff). Auto-push of successful project runs is now ON by default. Memory snapshot ceiling bumped 8K -> 12K. Migrations V95 (pgvector + project_document + project_document_chunk), V96/V97 (auto-push flag).
- **Project Shell + Launch (v2.4.0):** ENTERPRISE + MAX premium feature. Project dashboard gains Launches + Pull Requests tabs; "Launch" creates an `ExecutionRun` with optional new-repo specs (`NewRepoSpec` — name/description/techHint/githubOwner/visibility). `ExecutionOrchestrator` pre-creates new repos via `ProjectRepoService.createAndLinkNewRepo` before persisting the Launch. Solutions are now multi-run: each V2 dispatch (initial + retry + manual fix) writes an `execution_solution_run` row; UI's `SolutionSection` is collapsible and links each attempt to the existing `RunDetailPage`. Project-level rollup endpoints under `/api/projects/{id}/{launches,prs,overview}` + pre-flight `/launches/validate-repo-access`. Scaffold taxonomy at `/api/scaffolds`. Migration V90.
- **Execution View (`execution/`, v2.3.0):** Project-level workbench for multi-repo, multi-solution work. `ExecutionOrchestrator` drives an `ExecutionRun` through SETUP → ANALYSING → ARCH_REVIEW → PLANNING → EXECUTING → COMPLETE. `ExecutionPlannerService` decomposes the requirement (with `{{PROJECT_MEMORY}}`) into ordered `ExecutionSolution`s — each targeting one repo and dispatched through `V2RunOrchestrator.submitRun(...)` by `SolutionExecutionService`. Dependency layering via `SolutionDependencyResolver`, cross-solution file conflicts via `FileConflictDetector`. Per-solution markers live on `exec/<runId>` branches through `ExecutionBranchService`; final per-repo PRs opened via `GitHubRepoService` + `PullRequestRecordService`. Handover contracts persist in `execution_context_handover` via `CrossRepoContextService`. SSE channel `sse:channel:exec:{id}` with `EXEC_*` events. Feature flag `execution.view.enabled` (default off) + ENTERPRISE tier gate (`tier_config.execution_view_enabled`). Migration V89.
- **Project memory (`memory/`, v2.2.0):** Every completed project-scoped run appends a caveman-English digest to `project_run_ledger` via `RunLedgerAgent` (LIGHTWEIGHT tier, post-`generateValidationReport`, non-fatal). `MemoryCompactionService` rewrites a versioned `project_memory_snapshot` every 10 runs (starting at R20), keeping planner context bounded regardless of project age. `ProjectMemoryProvider.buildPlannerContext(projectId)` feeds `{{PROJECT_MEMORY}}` into `PlannerAgentV2`'s prompt so new runs know what was built, what patterns were set, and what debt was left. Surfaced to the UI via `GET /api/projects/{id}/memory` + Memory tab on the project dashboard (`ProjectMemoryPanel`). Migration V88.
- **Pilot v3.0 — project workspace (`project/`, `codeintel/`, `assessment/`, `scoring/`):** Project becomes the unit of organization. New `ProjectDashboardController` aggregates active/completed/failed counts + score trend. Dual-scope `ProjectRepo` registry (ORG = shared, USER = personal) joined to projects via `project_repo_link`; multi-repo selection in `NewRunModal` clones each into `workspace/<runId>/repos/<name>/` via `WorkspaceInitService.cloneMultipleRepos`. `ProjectArchitectureService` versions per-repo `ProjectCodeMap` + project-wide `ProjectArchitectureDoc`; `StalenessService` (Caffeine 5-min cache) drives the dashboard's STALE flag using stored generation SHAs. `FeatureAssessmentService` runs before planner (heuristic by default; LLM-backed when `assessment.llm.enabled` flag is on); the `AssessmentPanel` surfaces complexity + risks + cross-repo deps above the plan-approval gate. `ScmPushService.pushMultiRepo` handles per-subdir push + per-repo PR + cross-link via `GitHubRepoService.updatePullRequestBody`; PRs persisted to `pull_request_record` (R5), webhook at `/webhooks/github` flips status (HMAC SHA-256 verified). `ReviewScoringService` derives 6 dimensions (code quality / test coverage / architecture / security / performance / design compliance) from review issues + accuracy report — no extra LLM call. `ScoreCard` at top of `ValidationReportPanel`. Optional Figma URL on `RunRequest` drives design-compliance scoring. `StackRefreshJob` (`@Scheduled` daily 03:00, `@SchedulerLock`) refreshes `detected_stack` + `last_synced_sha` org-wide.
- **Frontend:** React 18, Vite 5, Tailwind CSS 3.4, TypeScript 5.3
- **Database:** PostgreSQL (Cloud SQL) for state, Redis for caching + gate coordination + feature flags + rate limiting
- **Infra:** GCP N. Virginia (us-east4), Cloud Run for previews (dev/prod), Docker Compose for local previews, GCE for main app
- **Batch execution:** Not yet re-implemented under V2. V1 batch (`batch/*`) is frozen; the V2 re-implementation is scoped in [docs/plans/v2-batch-execution-plan.md](docs/plans/v2-batch-execution-plan.md) and waits on deprecation P3.
- **Project type detection:** Planner declares `project_type` in plan JSON → written to `.agent-context/project.json` → read by preview/deploy Dockerfile generators (fallback: file-based heuristics)
- **LLM Router:** V2 uses `LlmRouterV2` with native tool calling; YAML-configured model matrix maps `AgentTier x Provider → Model` via `ModelResolver`. Provider circuit breaker via `ProviderHealthTracker` (Redis-backed, 5 failures → open, 60s TTL auto-recovery). Agent tiers: PLANNING, WORKHORSE, LIGHTWEIGHT.
- **Gate system:** Redis-backed plan approval gate with 500ms polling, 2h TTL, 24h max wait timeout. V2 auto-merges per task — no merge gate.
- **Auth:** JWT (access 1h, refresh 7d) + OAuth2 (Google, GitHub). Scoped SSE tokens (5-min TTL, `scope=sse` + `runId` claim) for event streams. RBAC with SUPER_ADMIN / ORG_ADMIN / BILLING_ADMIN / DEVELOPER / PROJECT_ADMIN / PROJECT_DEVELOPER roles. Force-logout via feature flag epoch-based JWT invalidation. JWT secret validated at startup in prod (`JwtSecretValidator`).
- **Tiers:** FREE (5 runs/month, 1-credit specs only) → PRO (100/month) → ENTERPRISE (unlimited). Each run costs 1–N credits proportional to estimated LLM cost: `credits = max(1, ceil(bufferedEstimate / $5.0))`.
- **Billing:** Multi-gateway via `PaymentGatewayRouter` (Razorpay + Stripe). Subscriptions, run top-up credits, invoicing with GST, payment audit trail. Cost-proportional credits: charged at plan approval (not submission), refunded on FAILURE. `run_cost_log` table tracks estimated vs actual cost for pricing analytics.
- **Security:** 8-layer defense-in-depth: request validation, input sanitization (`InputSanitizer` — NFKC normalization, zero-width strip, base64 decode+scan), LLM prompt defense, code abuse scanning (`CodeAbuseScanner` — crypto, reverse shell, PowerShell, DNS exfil, cloud metadata, obfuscation), shell sandbox, file sandbox, process isolation, container hardening (metadata endpoint blocking, env var whitelist). Configurable CSP/HSTS/X-Frame-Options via `SecurityProperties`.
- **Feature flags:** DB-backed `FeatureFlagService` with Redis pub/sub cache invalidation + 60s scheduled reload. Admin-manageable via `FeatureFlagController`.
- **Rate limiting:** Redis-backed per-tenant rate limiting via `RateLimitFilter`. Endpoint groups: AUTH, RUNS, WEBHOOK, READ, WRITE, PREVIEW, DEPLOY with per-tier limits.
- **Observability:** Micrometer + OTel → Prometheus + Tempo + Grafana (local) / Cloud Trace + Cloud Monitoring (GCP)
- **Prompt architecture:** Composable fragment system via `PromptAssembler` (`@Service`). Shared rules stored in `prompts/fragments/*.md`, injected via `{{include:name}}` markers. Per-project-type scaffold templates in `prompts/scaffolds/*.md`, injected via `{{SCAFFOLD_TEMPLATE}}`. `assembleAndTrack()` computes SHA-256 prefix hash, saves row to `prompt_audit` table. Inline CSS removed from worker system prompts (`{{STYLING_SECTION}}` = empty string); actual design content arrives via `buildFrontendBlock()` → `UiStyleResolver` → filesystem folder in `application.yml`.
- **Request classification:** V2 folds triage into `PlannerAgentV2` — classification (SINGLE_SHOT | MEDIUM | MULTI_TASK) decides task caps (1 / 4 / unlimited) and whether to bypass the LLM (SINGLE_SHOT uses a synthesised 1-task plan).

## Code Conventions

- **Base package:** `com.navneet.executespec`
- **Layers:** controller → service → V2 agent → V2 tool → `LlmRouterV2`. V1 `LlmRouter` is still alive for legacy agents (test-gen, `AccuracyAssessmentService`, `ConversationAgent`) until those migrate — see deprecation plan P4.
- **DTOs:** Java records where possible. Lombok `@Data`/`@Builder` for JPA entities.
- **Dependency injection:** Constructor injection via Lombok `@RequiredArgsConstructor` (no `@Autowired` field injection)
- **LLM prompts:** External files in `src/main/resources/prompts/*.txt` — never inline strings
- **Worker tool format:** Provider-native tool calling end-to-end via `LlmRouterV2`. Strict JSON schemas on every `v2/tool` entry. No XML `<tool_call>` parsing in V2 (that was a V1 idiom).
- **Completion detection:** `CompleteTaskTool` — the worker calls it as a native tool to terminate the loop. No JSON-prefix heuristic.
- **Naming:** `*Agent.java` for agents, `*Service.java` for services, `*Controller.java` for REST, `*Spec.java` for security specifications
- **DB migrations:** Flyway `V{n}__description.sql` in `src/main/resources/db/migration/` (currently V1–V102)
- **Prompt assembly:** LLM prompts assembled via `PromptAssembler.assemble(path, vars)` — resolves `{{include:fragment}}` markers from `prompts/fragments/` and `{{VAR}}` substitutions. Never use `Resource.getContentAsString()` directly in agents.

## Patterns: MUST Follow

- LLM calls go through `LlmRouter` — supports multi-provider fallback, per-run API key resolution, and tier-based model routing
- Agent outputs validated by the post-execute `ReviewerAgentV3` (general + wiring passes) + a final `buildProject` smoke, not by per-task compilation
- Structured logging with traceId/spanId via Micrometer context propagation (MDC auto-populated)
- Constructor injection only via `@RequiredArgsConstructor`
- Run ownership fields (`createdByUserId`, `orgId`, `projectId`) stamped BEFORE async pipeline thread starts (race condition fix)
- Gate-loss fallback: `approve()` → if gate missing → auto-fail with message
- Sliding window for LLM context: first 2 + last 18 messages
- Token cost control: `readFile` 8k chars, `shell` 6k chars, `diff` 5k chars truncation limits
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21` must be set before any Maven command (shell sessions may not inherit it)
- Payment webhook signatures verified before processing (Razorpay HMAC, Stripe `Stripe-Signature`)
- Feature flags checked via `FeatureFlagService.isEnabled()` — never hardcode toggles

## Patterns: MUST NOT Use

- Field injection (`@Autowired` on fields)
- Raw String for LLM prompts (use prompt files in `resources/prompts/`)
- Direct HTTP calls to LLM providers (always go through `LlmRouter`)
- Swallowed exceptions (log + rethrow or handle explicitly)
- Setting run ownership fields AFTER `submitRun()` returns (causes async thread race condition)
- `Thread.ofVirtual().start()` without context wrapping (`ContextSnapshotFactory.captureAll().wrap(runnable)`)

## Build & Test

```bash
# IMPORTANT: Always set JAVA_HOME for Maven commands
export JAVA_HOME=/opt/homebrew/opt/openjdk@21

# Backend build
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q

# Frontend build
npm --prefix frontend run build

# Full verify (both)
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q && npm --prefix frontend run build

# Run locally
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run

# Local observability stack (Grafana + Prometheus + Tempo)
cd observability && docker compose up -d
# Grafana: http://localhost:3001 (admin/admin)
# Prometheus: http://localhost:9090
# Tempo: http://localhost:3200
```

## Required Environment Variables

```bash
# Required
OPENAI_API_KEY=          # OpenAI API key (default provider)
ANTHROPIC_API_KEY=       # Claude API key

# Optional — LLM
GOOGLE_API_KEY=          # Gemini API key (for LLM fallback chain)

# Optional — Integrations
GITHUB_PAT=              # GitHub Personal Access Token
SLACK_CLIENT_ID=         # Slack app credentials
SLACK_CLIENT_SECRET=
SLACK_SIGNING_SECRET=
DASHBOARD_URL=           # Frontend URL for Slack links

# Optional — OAuth2
GOOGLE_OAUTH_CLIENT_ID=      # Google OAuth2 login
GOOGLE_OAUTH_CLIENT_SECRET=
GITHUB_OAUTH_CLIENT_ID=      # GitHub OAuth2 login
GITHUB_OAUTH_CLIENT_SECRET=

# Optional — Billing
RAZORPAY_KEY_ID=         # Razorpay payment gateway
RAZORPAY_KEY_SECRET=
STRIPE_SECRET_KEY=       # Stripe payment gateway
STRIPE_WEBHOOK_SECRET=

# Optional — Infrastructure
REDIS_HOST=              # Default: localhost

# Optional — Observability
OTEL_SAMPLING_PROBABILITY=1.0    # Trace sampling (1.0 = 100%)
OTEL_METRICS_ENABLED=false       # OTLP metrics export
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318/v1/traces
```

## API Contracts

### Run Lifecycle
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/runs` | Submit run (202) |
| GET | `/api/runs` | List runs (paginated, filtered by visibility + tier) |
| GET | `/api/runs/{id}` | Run detail (includes `githubPrUrl`) |
| POST | `/api/runs/{id}/approve` | Plan gate approval (charges N credits; body: `{approved, creditsConfirmed?}`) |
| POST | `/api/runs/{id}/sync/original` | v2.5.2 — reset workspace to the exact SHAs captured at run start (from `run_repo_snapshot`) |
| POST | `/api/repos/{repoId}/webhook` | v2.5.2 — install a pull_request webhook on this repo using caller's PAT |
| GET | `/api/projects/{id}/workspace-health` | v2.5.2 — per-repo SHA drift + webhook + open-PR rollup for Project Overview |
| GET | `/api/projects/{id}/post-merge-bar` | v2.5.2 — read transient post-merge banner marker (null when absent) |
| DELETE | `/api/projects/{id}/post-merge-bar` | v2.5.2 — dismiss post-merge banner |
| GET | `/api/projects/{id}/estimate?requirement=<text>` | v2.5.2 — heuristic task/cost estimate from project history (no LLM) |
| GET | `/api/projects/{id}/tech-debt?since=<days>` | v2.5.2 — issues-by-category/severity + file hotspots + dimension trend |
| GET | `/api/projects/{id}/templates` | v2.5.2 — list spec templates (project + org-scoped merged) |
| POST | `/api/projects/{id}/templates` | v2.5.2 — create project-scoped spec template |
| POST | `/api/orgs/{orgId}/templates` | v2.5.2 — create org-scoped spec template |
| DELETE | `/api/projects/{id}/templates/{templateId}` | v2.5.2 — soft-delete a template |
| GET | `/api/projects/{id}/architecture/diff?from=<v>&to=<v>` | v2.5.5 — per-repo source file changes between two arch versions (GitHub compare API) |
| POST | `/api/runs/{id}/merge` | Merge gate (legacy — merge is now automatic) |
| DELETE | `/api/runs/{id}` | Cancel/delete run |
| POST | `/api/runs/{id}/archive` | Archive run |
| POST | `/api/runs/{id}/tasks/{taskId}/retry` | Retry failed task |
| GET | `/api/runs/{id}/events` | SSE event stream (scoped token) |
| GET | `/api/runs/{id}/sse-token` | Get scoped SSE token (5-min TTL) |
| POST | `/api/runs/{id}/run-tests` | Manually trigger test execution (Beta, requires preview RUNNING) |
| GET | `/api/runs/{id}/prs` | Pilot R5 — list PRs opened by this run with status |

### Validation Report
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/runs/{id}/report` | Get validation report |
| GET | `/api/runs/{id}/report/pdf` | Download report as PDF |
| POST | `/api/runs/{id}/report/share` | Generate share link |
| GET | `/api/reports/shared/{token}` | Public shared report (no auth) |

### Preview & Deploy
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/runs/{id}/preview/start` | Start preview (Cloud Run or Docker) |
| DELETE | `/api/runs/{id}/preview/stop` | Stop preview |
| POST | `/api/runs/{id}/deploy` | Deploy to Cloud Run (permanent) |
| POST | `/api/runs/{id}/push-github` | Push to GitHub (single-repo path; multi-repo branches into `ScmPushService.pushMultiRepo`) |

### Pilot v3.0 — Project workspace
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/projects/{id}/dashboard` | Aggregated metrics, recent runs/PRs, arch-doc status (NONE/GENERATED/STALE) |
| GET | `/api/projects/{id}/memory` | Project memory — compacted snapshot + last 10 uncompacted entries + stats (v2.2.0) |
| GET | `/api/projects/{id}/memory/ledger` | Full append-only ledger for a project (v2.2.0) |
| GET | `/api/projects/{id}/scores` | Score trend feed for the dashboard chart |
| POST | `/api/repos` | Create repo at chosen scope (ORG requires ORG_ADMIN) |
| GET | `/api/repos?scope=ORG\|USER\|ALL` | List repos visible to caller |
| POST | `/api/repos/{repoId}/sync` | Re-detect stack + refresh `last_synced_sha` |
| DELETE | `/api/repos/{repoId}` | Delete repo (must not be linked to any project) |
| GET | `/api/projects/{id}/repos` | List repos linked to this project |
| POST | `/api/projects/{id}/repos/{repoId}` | Link an existing repo to the project |
| DELETE | `/api/projects/{id}/repos/{repoId}` | Unlink (repo itself survives) |
| PUT | `/api/projects/{id}/repos/{repoId}/relationships` | Update relationship tags |
| POST | `/api/projects/{id}/architecture/generate` | Regenerate codemaps + arch doc (versioned) |
| GET | `/api/projects/{id}/architecture` | Latest arch doc |
| GET | `/api/projects/{id}/architecture/versions` | All versions |
| PUT | `/api/projects/{id}/architecture/annotate` | Save user annotations on the latest doc |
| GET | `/api/projects/{id}/architecture/staleness` | Per-repo SHA drift report (5-min cached) |
| GET | `/api/projects/{id}/repos/{repoId}/codemap` | Latest codemap for a single repo |
| POST | `/webhooks/github` | GitHub PR webhook (HMAC SHA-256 verified, secret via `github.webhook.secret`) |

### Auth & Org
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/auth/register` | Self-register |
| POST | `/api/auth/login` | Login → JWT |
| POST | `/api/auth/refresh` | Refresh JWT token |
| DELETE | `/api/auth/logout` | Logout (clears refresh cookie) |
| POST | `/api/auth/forgot-password` | Request password reset email |
| POST | `/api/auth/reset-password` | Reset password with token |
| GET | `/api/auth/verify-email` | Verify email link |
| GET | `/api/auth/me/permissions` | Tier-based permissions |
| GET | `/api/auth/oauth2/authorize` | OAuth2 authorization redirect |
| GET | `/api/auth/oauth2/callback/{provider}` | OAuth2 callback (Google/GitHub) |
| POST | `/api/org` | Create org |
| POST | `/api/org/invite` | Invite user |

### Billing & Payments
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/billing/plans` | List billing plans |
| POST | `/api/billing/subscribe` | Initiate subscription (routed to Razorpay or Stripe) |
| POST | `/api/billing/topup/purchase` | Buy run top-up credits |
| GET | `/api/billing/payment-history` | Org-scoped payment history |
| POST | `/api/webhooks/razorpay` | Razorpay webhook (HMAC verified, no auth) |
| POST | `/api/webhooks/stripe` | Stripe webhook (Stripe-Signature verified, no auth) |

### Notifications
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/notifications` | List notifications (paginated) |
| GET | `/api/notifications/unread-count` | Unread notification count |
| POST | `/api/notifications/{id}/read` | Mark notification as read |
| POST | `/api/notifications/read-all` | Mark all notifications as read |

### Models & Feature Flags
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/models/available` | Model matrix + provider tags |
| GET | `/api/feature-flags` | List all feature flags (SUPER_ADMIN) |
| POST | `/api/feature-flags/{key}` | Update feature flag (SUPER_ADMIN) |

### Admin
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/admin/payment-audit` | Payment audit log (paginated, SUPER_ADMIN) |
| GET | `/api/admin/payment-audit/export` | Export audit as CSV (SUPER_ADMIN) |
| GET | `/api/admin/webhook-events` | Webhook event log (filterable, SUPER_ADMIN) |
| POST | `/api/admin/executions/reconcile` | Mark orphaned non-terminal ExecutionRuns as FAILED (SUPER_ADMIN; body: `{staleMinutes?}`) |

## Key File Locations

| Purpose | Path |
|---------|------|
| V2 run orchestrator | `v2/integration/V2RunOrchestrator.java` |
| V2 sequential executor | `v2/harness/SequentialExecutorV2.java` |
| V2 planner | `v2/agent/PlannerAgentV2.java` |
| V2 worker | `v2/agent/WorkerAgentV2.java` |
| V2 reviewer | `v2/reviewer/ReviewerAgentV3.java` |
| V2 LLM router | `v2/agent/LlmRouterV2.java` + `OpenAiNativeToolClient` / `GeminiNativeToolClient` |
| V2 tool registry | `v2/tool/ToolRegistryV2.java` + `v2/tool/*` (10 tools + `CompleteTaskTool`) |
| V2 context store | `v2/context/ContextStoreV2.java` (Redis snapshot, 24h TTL) |
| V2 cost tracker | `v2/cost/V2RunCostTracker.java` (per-call accumulator + cache-aware pricing) |
| V2 startup resumer | `v2/recovery/StartupRunResumerV2.java` (fail-cleanly on boot) |
| V2 CLI smoke runner | `v2/smoketest/CliSmokeRunner.java` |
| V2 metrics | `v2/observability/V2Metrics.java` |
| V1 legacy (deprecated) | `harness/PipelineHarness.java`, `agent/PlannerAgent.java`, `agent/WorkerAgent.java`, `batch/*`, `compiler/*`, `reviewer/ReviewerAgentV2.java` — pending deletion per `docs/plans/deprecation-removal-plan.md` |
| Cost estimator (shared) | `agent/CostEstimator.java` (used by both V1 and V2 paths) |
| Cost audit log | `billing/cost/RunCostLog.java`, `billing/cost/RunCostLogRepository.java` |
| Notification entity | `notification/Notification.java` |
| Notification service | `notification/InAppNotificationService.java` |
| Notification controller | `notification/NotificationController.java` (REST: GET/POST /api/notifications) |
| Notification listener | `notification/RunCompletionNotifier.java` (creates notifications on run complete/fail) |
| Design system config | `harness/DesignSystemProperties.java` |
| UI style resolver | `harness/UiStyleResolver.java` (design loading, fuzzy match, 3-signal resolution) |
| Model resolver (matrix-based) | `router/ModelResolver.java` |
| Model matrix config | `router/ModelMatrixProperties.java` |
| Provider circuit breaker | `router/ProviderHealthTracker.java` (Redis-backed) |
| State store | `state/StateStore.java` |
| Validation report | `report/ValidationReportService.java`, `report/ValidationReportGenerator.java` |
| Report controller | `report/ValidationReportController.java` |
| Report PDF | `report/ValidationReportPdfService.java` |
| Report entity | `report/entity/ValidationReport.java` |
| Report frontend | `frontend/src/components/runs/ValidationReportPanel.tsx` |
| Shared report page | `frontend/src/pages/SharedReportPage.tsx` |
| Preview service | `deployment/preview/PreviewService.java` (dual-mode: docker/cloud-run) |
| Preview config | `deployment/preview/PreviewProperties.java` (mode, TTL, concurrency) |
| Caddy proxy manager | `deployment/preview/CaddyProxyManager.java` (Docker mode only) |
| Cloud Run client | `deployment/CloudRunClient.java` |
| Artifact Registry | `deployment/ArtifactRegistryClient.java` |
| Deployment service | `deployment/DeploymentService.java` |
| Dockerfile gen (preview) | `deployment/preview/DevDockerfileGenerator.java` (reads `.agent-context/project.json`) |
| Dockerfile gen (deploy) | `deployment/DockerfileGenerator.java` (strategy pattern, reads `project.json`) |
| Security config | `auth/security/SecurityConfig.java` |
| JWT secret validator | `auth/security/JwtSecretValidator.java` (prod-only startup check) |
| Security properties | `config/SecurityProperties.java` (CSP, HSTS, X-Frame-Options) |
| JWT utility | `auth/security/JwtUtil.java` (includes scoped SSE tokens) |
| OAuth2 handler | `auth/oauth/OAuthSuccessHandler.java` |
| Tier permissions | `auth/service/TierPermissionService.java` |
| Tier enforcement | `auth/service/TierEnforcementService.java` |
| Billing service | `billing/service/BillingService.java` |
| Payment gateway router | `billing/gateway/PaymentGatewayRouter.java` (Razorpay + Stripe) |
| Stripe gateway | `billing/gateway/StripePaymentGateway.java` |
| Invoice service | `billing/service/InvoiceService.java` |
| Payment audit | `billing/service/PaymentAuditService.java` |
| Analytics service | `billing/service/AnalyticsService.java` |
| Feature flags | `config/featureflag/FeatureFlagService.java`, `config/featureflag/FeatureFlagController.java` |
| Rate limiting | `ratelimit/RateLimitFilter.java` |
| Input sanitizer | `security/InputSanitizer.java` |
| Code abuse scanner | `security/CodeAbuseScanner.java` |
| Request classification | Folded into `v2/agent/PlannerAgentV2.java` (`Classification: SINGLE_SHOT | MEDIUM | MULTI_TASK`) |
| Prompt assembler | `agent/PromptAssembler.java` (fragment resolution, variable substitution, hash tracking) |
| Prompt audit entity | `agent/PromptAudit.java`, `agent/PromptAuditRepository.java` |
| Failure pattern service | `agent/FailurePatternService.java` (queries recent runs, returns markdown bullet list for planner) |
| Prompt fragments | `resources/prompts/fragments/*.md` (security-rules, preview-defaults, cors-config, etc.) |
| Scaffold templates | `resources/prompts/scaffolds/*.md` (spring-boot, node-backend, node-frontend, python, go, etc.) |
| API prober | `testgen/ApiProber.java` (probes live preview endpoints before test generation) |
| API surface extractor | `testgen/ApiSurfaceExtractor.java` (regex-extracts routes from workspace for test planner) |
| Slack integration | `integration/slack/SlackNotificationService.java` |
| GitHub repo service | `integration/github/GitHubRepoService.java` (branch, PR creation, default branch lookup) |
| Git tool | `tool/GitTool.java` (workspace git ops, `pushToBranch()` for existing repos) |
| JIRA adapter | `integration/jira/JiraAdapter.java` (inbound webhook + outbound comments) |
| Confluence adapter | `integration/confluence/ConfluenceAdapter.java` (inbound webhook + outbound child pages) |
| Integration manager | `integration/IntegrationManager.java` (routes webhooks, listens for MergeCompleted) |
| Test generation orchestrator | `testgen/TestGenOrchestrator.java` |
| Test preview coordinator | `testgen/TestPreviewCoordinator.java` (owns preview lifecycle for test execution) |
| Test execution service | `testgen/TestExecutionService.java` |
| Test result controller | `testgen/TestResultController.java` (apply-test-fixes, run-tests) |
| Project workspace | `project/service/ProjectRepoService.java`, `project/controller/ProjectRepoController.java`, `project/dashboard/ProjectDashboardController.java`, `project/scores/ProjectScoresController.java` |
| Project repo entities | `project/entity/ProjectRepo.java` (ORG/USER scope), `project/entity/ProjectRepoLink.java` |
| PR records + webhook | `project/pr/PullRequestRecord.java`, `project/pr/PullRequestRecordService.java`, `project/pr/GitHubWebhookController.java`, `project/pr/PullRequestController.java` |
| Stack detection | `project/service/StackDetector.java`, `project/service/StackRefreshJob.java` (`@Scheduled` + `@SchedulerLock`) |
| Codebase intelligence | `codeintel/CodeMapGenerator.java`, `codeintel/ArchitectureDocGenerator.java`, `codeintel/ProjectArchitectureService.java`, `codeintel/ProjectArchitectureController.java`, `codeintel/StalenessService.java` |
| Feature assessment | `assessment/FeatureAssessmentService.java` (heuristic + LLM via `assessment.llm.enabled` flag), `assessment/AssessmentResult.java` |
| Review scoring | `scoring/ReviewScoringService.java` (6 dimensions, deterministic heuristic from existing review data) |
| Multi-repo push | `integration/scm/ScmPushService.pushMultiRepo`, `tool/GitTool.commitAndPushSubdir` |
| Workspace multi-repo | `harness/WorkspaceInitService.cloneMultipleRepos` |
| Dashboard frontend | `frontend/src/pages/project/ProjectDashboardPage.tsx`, `frontend/src/components/project/*` |
| Project memory | `memory/RunLedgerAgent.java`, `memory/MemoryCompactionService.java`, `memory/ProjectMemoryProvider.java`, `memory/ProjectMemoryController.java`, `memory/entity/ProjectRunLedger.java`, `memory/entity/ProjectMemorySnapshot.java` |
| Workspace disk warmer (v2.5.2) | `project/workspace/ProjectWorkspaceWarmer.java` (@Async DB→disk mirror), `ProjectWorkspaceService.writeRawFile` |
| Admin reconcile (v2.5.2) | `execution/service/AdminReconciliationService.java`, `POST /api/admin/executions/reconcile` on `SuperAdminController` |
| Run repo snapshot (v2.5.2) | `state/entity/RunRepoSnapshot.java`, `state/repository/RunRepoSnapshotRepository.java`, `WorkspaceInitService.cloneMultipleRepos` (SHA capture), `RunSyncService.syncToOriginalCommit` |
| Worker RAG injection (v2.5.2) | `SequentialExecutorV2.fetchReferenceDocs` + `composeSystemPrompt` (`{{REFERENCE_DOCS}}` block), `ExecutionRequest.projectId` |
| GitHub webhook auto-provision (v2.5.2) | `GitHubRepoService.installWebhook`, `ProjectRepoService.installWebhook`, `ProjectRepoController` POST `/api/repos/{repoId}/webhook`, `ProjectRepo.webhookInstalled` |
| Workspace health (v2.5.2) | `project/service/WorkspaceHealthService.java`, `WorkspaceHealthPanel.tsx`, `GET /api/projects/{id}/workspace-health` |
| Post-merge banner (v2.5.2) | `PostMergeCascadeService.writePostMergeBar/readPostMergeBar/dismissPostMergeBar`, `components/project/PostMergeBar.tsx` |
| Launch estimation (v2.5.2) | `project/estimation/EstimationService.java`, `ExecutionSetup.tsx` debounced chip, `GET /api/projects/{id}/estimate` |
| Execution list N+1 fix (v2.5.2) | `ExecutionRunRepository.findListByProjectId` + `findDetailById` with `@EntityGraph` |
| Tech debt (v2.5.2) | `project/techdebt/TechDebtService.java`, `TechDebtPanel.tsx`, `GET /api/projects/{id}/tech-debt` |
| Spec templates (v2.5.2) | `project/template/{ProjectSpecTemplate,SpecTemplateService,SpecTemplateController}.java`, `TemplatePicker.tsx`, V102 |
| TDD mode (v2.5.2) | `RunRequest.tddMode`, `PlannerRequestV2.tddMode`, `PlannerAgentV2.renderTddBlock`, `{{TDD_MODE_BLOCK}}` slot in `prompts/v2/planner.md`, NewRunModal toggle |
| Cancel rollback (v2.5.2) | `ExecutionOrchestrator.runCompensations`, `GitHubRepoService.closePullRequest`, `EXEC_COMPENSATION_UPDATE`/`_DONE` events, `CancellationPanel.tsx` |
| Arch diff source correlation (v2.5.5) | `codeintel/ArchitectureDiffService.java`, `GitHubRepoService.compareCommits`, `GET /api/projects/{id}/architecture/diff`, `ArchSourceChanges` inside `ArchitectureViewer.tsx` |
| Execution View | `execution/controller/ExecutionController.java`, `execution/service/{ExecutionOrchestrator,ExecutionPlannerService,SolutionExecutionService,ExecutionBranchService,CrossRepoContextService,IncrementalCodeMapService,SolutionDependencyResolver,FileConflictDetector}.java`, `execution/entity/{ExecutionRun,ExecutionSolution,ExecutionSolutionRepo,ExecutionContextHandover}.java`, `execution/event/ExecutionEventTypes.java` |
| DB migrations | `resources/db/migration/V*.sql` (V1–V99; V81=project settings + repo registry, V82=pull_request_record, V83=validation_report scores, V84=codebase intelligence, V85=assessment_result + figma_url, V86=pipeline_run.selected_repo_ids, V87=run_code_map, V88=project memory, V89=execution view, V90=project shell + multi-run solutions + MAX tier gate, V99=backfill execution_run.ai_provider, V100=run_repo_snapshot, V101=project_repo.webhook_installed, V102=project_spec_template) |
| LLM prompts | `resources/prompts/*.txt` (15 main + 13 eval) |
| App config | `resources/application.yml` |
| Frontend entry | `frontend/src/main.tsx` |
| Frontend API client | `frontend/src/lib/api.ts` |
| Observability stack | `observability/docker-compose.yml` |
| OTel config | `config/ObservabilityConfig.java`, `config/CustomMetricsConfig.java` |
| Eval framework | `eval/EvalController.java`, `eval/ModelEvaluator.java` |
| Infra (Terraform) | `infra/` |

## Model Matrix

| Agent Tier | Agents | Anthropic | Google | OpenAI |
|------------|--------|-----------|--------|--------|
| PLANNING | PlannerAgentV2 (HIGH tasks, reviewer) | `claude-sonnet-4-6` | `gemini-3-flash-preview` | `gpt-5.4` |
| WORKHORSE | WorkerAgentV2 (MEDIUM tasks), TestPlannerAgent, TestWorkerAgent, ApiTestSuiteAgent, ValidationReportGenerator | `claude-haiku-4-5-20251001` | `gemini-3-flash-preview` | `gpt-5.4` |
| LIGHTWEIGHT | WorkerAgentV2 (LOW tasks), ConversationAgent, ContextAgent, TestReportGenerator | `claude-haiku-4-5-20251001` | `gemini-3-flash-preview` | `gpt-5.4` |

Default provider: OpenAI (user-selected per project). Provider failover is available via `ProviderHealthTracker` (V1 router only — V2 `LlmRouterV2` is direct-dispatch with circuit-breaker metrics but no failover chain). See `application.yml` → `model-matrix:` for full configuration.

## Current State

### Working
- Full V2 pipeline: plan (native `submitPlan` tool) → approve → sequential per-task worker (tier-routed) → reviewer (general + wiring) → **COMPLETE** → auto-preview (5-min TTL) → background validation (report + test gen + test exec)
- V2 startup recovery: non-terminal V2 runs at boot are marked FAILED with a clear reason (replay is a planned follow-up)
- V2 cancel: `DELETE /api/runs/{id}` on V2 runs interrupts the pipeline, signals the plan gate, writes CANCELLED + terminal SSE
- V2 run-stats correctness: per-phase token persistence atomically increments run totals; `terminate()` only bumps up, never shrinks
- User-selected AI provider per project (Anthropic/Gemini/OpenAI) with YAML model matrix
- Dual-mode previews: Cloud Run (dev/prod, 5-min TTL, managed HTTPS) or Docker Compose (local, 5-min TTL, Caddy proxy with Cloudflare Origin Certificate)
- JWT auth + OAuth2 (Google, GitHub) with RBAC, org management, project scoping
- Multi-gateway billing via PaymentGatewayRouter (Razorpay + Stripe): subscriptions, run top-up credits, invoicing with GST
- Cost-proportional run credits: `credits = max(1, ceil(estimate × 1.10 / $5.0))`. Charged at plan approval, refunded on FAILURE. FREE tier limited to 1-credit specs. Insufficient credits → suggest top-up or tier upgrade.
- `run_cost_log` audit table: tracks estimated vs actual cost, margin, budget-hit rate per run for pricing analytics
- Payment audit trail with CSV export + webhook event log (admin)
- Feature flag system (DB + Redis pub/sub + admin UI) for platform toggles
- Force-logout via feature flag epoch-based JWT invalidation
- Redis-backed per-tenant rate limiting (AUTH, RUNS, WEBHOOK, READ, WRITE, PREVIEW, DEPLOY groups)
- 8-layer security: request validation, input sanitization (NFKC + zero-width strip + base64 decode), LLM prompt defense, code abuse scanning (crypto, shells, PowerShell, DNS exfil, metadata), shell sandbox, file sandbox, process isolation, container hardening (metadata blocking, env whitelist)
- Configurable security headers (CSP, HSTS, X-Frame-Options) via SecurityProperties
- JIRA/Confluence inbound webhooks + outbound completion comments (via MergeCompleted event, both PROTOTYPE and PRODUCTION)
- Slack notifications (Block Kit messages)
- GitHub branch + PR creation for existing-repo runs (branch `executespec/{runId-short}`, PR opened automatically)
- Chat copilot + apply suggestion (chat/review improvements on same run)
- Run archive feature
- Observability: Micrometer traces + V2 custom metrics (`executespec.v2.*`) + structured logging
- Local Grafana dashboards (ExecuteSpec Overview + JVM/HTTP)
- GCP Cloud Trace (traces) + Cloud Monitoring (metrics) via `@Profile("gcp")` exporters
- Model evaluation framework (`eval/` package)
- Atomic quota enforcement (pessimistic locking, single-TX check+increment, multi-credit support)
- Atomic token counter updates (SQL-level increment, no read-modify-write)
- Bounded thread pool for pipeline runs (CallerRunsPolicy backpressure)
- Gate wait timeout (24h max, prevents indefinite thread block)
- Semaphore enforcement on all run entry points (startIterationRun, resumeRun, executeApplySuggestion)
- Concurrent preview limit race fix (synchronized block)
- Scoped SSE tokens (5-min TTL, per-runId, prevents cross-run eavesdropping)
- JWT secret startup validation (prod profile, blocks dev placeholder)
- Tier-based deployment limits (enforces `max_deployments` from tier_config)
- Silent hard cap cost enforcement (ceiling = credits × $5 × 1.5; no progressive warnings — user approved cost upfront)
- Manual test execution (Beta): POST /runs/{runId}/run-tests endpoint, "Run Tests" button in PreviewTab (requires preview RUNNING)
- In-app notification system: JPA entity, REST API, NotificationBell in header, run completion/failure notifications
- Containerized test execution (Docker with --read-only, --cap-drop ALL, memory/CPU limits)
- Env var whitelist for Docker subprocess (PATH, HOME, DOCKER_HOST only — no leaked secrets)
- ServiceNow webhook HMAC verification
- Actuator endpoint restriction (health+prometheus public, rest SUPER_ADMIN)
- Design system integration: 54 company DESIGN.md files loaded from filesystem, 3-signal resolution (explicit hint > planner ui_style > project type heuristic), Caffeine cache (30min), token-budget truncation (2500 tokens), GCS-synced on deploy/boot
- CSP `frame-src` directive for Cloud Run preview iframes
- Prompt architecture: shared fragment system (`PromptAssembler`), scaffold templates, prompt version tracking (SHA-256 hash → `prompt_audit`), failure pattern injection into planner, live API probe for test generation (`ApiProber`), API surface extraction for test planner (`ApiSurfaceExtractor`), command allowlist in `ProcessExecutor`
- **FileViewer markdown preview:** `.md` files in the workspace viewer show a Preview/Source toggle. Preview mode renders via `MarkdownContent`; Source mode uses Monaco editor.
- **V2 eval matrix:** `scripts/v2-eval.sh` — LOW/MEDIUM/HIGH × anthropic/openai/gemini, produces JSON + markdown comparison under `scripts/v2-eval-results/`
- **Pilot v3.0 — project workspace** (commit `6bbfde9b`): project dashboard with metrics + score trend + recent runs/PRs; ORG/USER scoped repo registry with multi-repo selection in NewRunModal; per-repo codemaps + versioned project architecture doc with annotations + STALE detection; pre-plan feature assessment (heuristic by default; LLM-backed when `assessment.llm.enabled` flag is on); 6-dimension review scoring on validation report; per-run PR list with GitHub webhook for merge status; multi-repo PR creation with cross-linking; daily `StackRefreshJob` keeps `detected_stack` + `last_synced_sha` fresh; optional Figma URL drives design-compliance scoring.

### In Progress
- Production deployment preparation

### Known Tech Debt
- **V2 batch not implemented** — V2 re-implementation scoped in `docs/plans/v2-batch-execution-plan.md`; V1 batch already deleted, dependency unblocked.
- **V2 startup replay (WORKING/REVIEWING) pending** — boot-time recovery marks interrupted WORKING/REVIEWING runs FAILED; full conversation replay is planned (`docs/plans/harness-v2-remaining-work.md` Gap 2). AWAITING_APPROVAL runs: the approve endpoint now detects the no-thread case and fails cleanly with 409, avoiding silent hangs.

### Recently Resolved (2026-04-13, branch `feature/v2-next`)
- ~~**Intermediate smoke-test false negatives**~~ — multi-task BUILD plans used to mark intermediate tasks PARTIAL when the scaffold couldn't build yet. `SequentialExecutorV2` now skips smoke on non-final tasks for multi-task plans; only the final task + reviewer final build are authoritative.
- ~~**Per-task worker tokens equal-split**~~ — `V2RunCostTracker` now keeps a per-task accumulator keyed by `runId|taskId`; `LlmRouterV2` passes the task id on every dispatch; `distributeWorkerTokens` uses real per-task snapshots with equal-split only for the residual.
- ~~**Gate lost on restart**~~ — `GateStore.isPlanGateWaiting()` lets the approve endpoint detect when the pipeline thread is gone (server restart during AWAITING_APPROVAL) and fail cleanly with 409 instead of hanging.
- ~~**`@Lazy @Autowired` on `PreviewService`**~~ — `TestPreviewCoordinator` now injects `ObjectProvider<PreviewService>` via the constructor, matching the rest of the codebase.
- ~~**ValidationReport `status` field**~~ — added `status` column (PENDING / COMPLETE / FAILED) via V80; service writes `COMPLETE` on successful persistence. Consumers no longer have to infer status from `accuracyScore != null`.
- ~~**Default provider OpenAI**~~ — `application.yml` already defaults to `anthropic` (legacy note dropped).
- ~~**`GitTool.createBranch()` catch-and-recover**~~ — inspected; the `findRef` pre-check + `RefAlreadyExistsException` catch is intentional belt-and-suspenders for a JGit ref-DB race after thread interrupt. Not debt; kept as-is.
- ~~**Pilot v3.0 four deferrals**~~ — repo SHA tracking, multi-repo PR creation, scheduled stack refresh + version field, and LLM-backed assessment all shipped in commit `6bbfde9b`.
