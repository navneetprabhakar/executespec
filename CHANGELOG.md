# Changelog

All notable changes to ExecuteSpec are documented in this file.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and [Semantic Versioning](https://semver.org/).

---

## [2.3.0] — 2026-04-14

Execution View — a project-level workbench that decomposes a cross-repo requirement into ordered solutions, drives each solution through a V2 pipeline run, and bundles the results into per-repo pull requests. ENTERPRISE-only, gated by the `execution.view.enabled` feature flag.

### Added

- **Backend package `execution/`:**
  - Entities + JPA repos: `ExecutionRun`, `ExecutionSolution`, `ExecutionSolutionRepo`, `ExecutionContextHandover`, plus the `execution_solution_dependency` DAG edge table.
  - `SolutionDependencyResolver` — topological layering + blocked-downstream detection; pure logic.
  - `FileConflictDetector` — cross-references predicted target files across solutions.
  - `ExecutionOrchestrator` — drives setup → ANALYSING → ARCH_REVIEW → PLANNING → EXECUTING → COMPLETE/ABORTED; delegates arch analysis to `ProjectArchitectureService`.
  - `ExecutionPlannerService` — LLM-backed decomposition via `execution_planner.txt`; persists plan + individual solutions with dependency edges.
  - `SolutionExecutionService` — bridges a single solution to `V2RunOrchestrator.submitRun(...)` (skipApproval, skipGate); tracks tags via `ExecutionBranchService`.
  - `ExecutionBranchService` — `exec/<runId>` branch + per-solution start/end markers; opens final per-repo PRs through `GitHubRepoService` + `PullRequestRecordService`.
  - `CrossRepoContextService` — layer 1 interface-contract handover persisted to `execution_context_handover`; layers 2/3 (diff codemap + test anchors) wired but deferred.
  - `IncrementalCodeMapService` — wraps `CodeMapGenerator` for post-solution codemap refresh.
  - `ExecutionController` — 10 REST endpoints + `/sse-token` + `/events` SSE; feature-flag + tier-gated; org-scoped via `AppUser.orgId` vs `Project.orgId`.
  - `ExecutionEventTypes` — SCREAMING_SNAKE_CASE SSE constants (`EXEC_*`).
- **Backend plumbing:**
  - Migration V89 — `execution_run` + `execution_solution` + `execution_solution_dependency` + `execution_solution_repo` + `execution_context_handover`, indexes, V67-style RLS policies.
  - Feature flag `execution.view.enabled` (default `false`).
  - `tier_config.execution_view_enabled` boolean column (ENTERPRISE seed = TRUE).
  - `TierConfig.executionViewEnabled` field wired through the entity.
- **Prompts:** `prompts/execution_planner.txt` (the decomposition prompt; embeds `{{PROJECT_MEMORY}}` from v2.2.0).
- **Frontend:**
  - Types: `frontend/src/types/execution.ts`.
  - API client: `frontend/src/lib/execution-api.ts`.
  - Zustand store: `frontend/src/stores/useExecutionStore.ts`.
  - SSE hook: `frontend/src/hooks/useExecutionSse.ts`.
  - Page: `frontend/src/pages/execution/ExecutionViewPage.tsx`.
  - Components (in `components/execution/`): `ExecutionStepper`, `ExecutionSetup`, `ExecutionAnalysis`, `ArchReviewGate`, `ExecutionPlanReview`, `DependencyGraph`, `ConflictWarning`, `ExecutionMonitor`, `SolutionCard`, `SolutionLogStream`, `FailureDecisionModal`, `ExecutionComplete`, `ExecutionReportPanel`.
  - Routes: `/projects/:projectId/executions/new`, `/projects/:projectId/executions/:executionId`.
  - Entry point: "New Execution" button on `ProjectDashboardPage` (dashboard-level, tier-gated server-side).

### Migrations

- **V89** `execution_view.sql`.

### Production-grade additions

- **V2 completion polling** — `SolutionExecutionService` now blocks a virtual thread polling `PipelineRunRepository` every 10s (2h cap) and maps V2 terminal status to the solution lifecycle:
  - V2 `COMPLETE` → solution `DONE`, actual tokens + cost copied from the V2 run, handover contracts written, JIRA transitioned to `Done`.
  - V2 `PARTIAL` → retry up to `max_retries`, with a JIRA comment per retry; final failure transitions to `Blocked`.
  - V2 `FAILED` / `CANCELLED` / timeout → solution `FAILED`, run `PAUSED`, JIRA transitioned to `Blocked`, `EXEC_PAUSED_FOR_DECISION` emitted.
- **JIRA sync (enterprise-level integration)** — new `ExecutionJiraSync` service uses the org's existing JIRA credentials (`Org.jira_base_url` / `email` / `api_token`) plus a new `Org.jira_project_key` column. Toggle per run via `ExecutionRun.jiraEnabled` (UI checkbox on `ExecutionSetup`). Lifecycle:
  - Solution start (first attempt only) → create JIRA ticket, persist `solution.jira_ticket_id`.
  - Retry / manual decision → comment on ticket.
  - Solution done / skipped → comment + transition to `Done`.
  - Solution failed → comment + transition to `Blocked`.
  - JIRA sync is best-effort: all failures are logged + swallowed, never fail the run.
- **Enriched final PR body** — per-repo PR now bundles a markdown summary table (status / tokens / PR / JIRA per solution), a call-out for any failed solutions with phase + reason, and overall stats. Replaces the old one-liner-per-solution body.

### Schema additions (V89)

- `execution_run.jira_enabled BOOLEAN NOT NULL DEFAULT FALSE`.
- `org.jira_project_key VARCHAR(64)` — enterprise-level default project key used by Execution View JIRA sync.

### Known deferrals

- True per-solution workspace handoff (sharing the execution branch between V2 runs) is not yet plumbed — each solution still creates a fresh V2 workspace and relies on the handover contract text for context. Shared workspace is a follow-up.
- `CrossRepoContextService` layers 2 (diff codemap) and 3 (test anchors) exist as stubs; layer 1 (interface contracts) is live.
- Git tag / revert operations (rollback of a failed solution's commit range) — tag names are still tracked in `execution_solution.git_tag_start/end` as metadata, but actual `git tag` / `git revert` execution is deferred.

### Operational notes

- Feature flag `execution.view.enabled` is **off** by default. Flip it on for ENTERPRISE canary tenants once a project has its first arch doc generated.
- Tier gating is enforced server-side via `TierConfig.executionViewEnabled` + `AppUser.orgId → Org.tier` lookup.
- SSE channel key is `exec:{executionRunId}` to avoid collisions with per-run events. Scoped SSE tokens carry `scope=sse, runId=exec:{id}` and are validated by `JwtUtil.validateSseToken`.

---

## [2.2.0] — 2026-04-14

Project Memory — a compressed per-run ledger + periodic compacted snapshot injected into `PlannerAgentV2` so subsequent runs in the same project can build on prior patterns, decisions, and debt instead of starting stateless.

### Added

- **`project_run_ledger`** (V88) — append-only, one row per completed project-scoped run. Stores a caveman-English digest (~60 tokens) of goal / changes / decisions / debt produced by `RunLedgerAgent` at LIGHTWEIGHT tier.
- **`project_memory_snapshot`** (V88) — versioned, rewritten every 10 runs by `MemoryCompactionService` so token cost stays bounded as projects grow past 50 runs.
- **`RunLedgerAgent`** — post-complete hook in `V2RunOrchestrator`; idempotent by `runId`, non-fatal on failure. Triggers compaction at `runNumber ≥ 20 && runNumber % 10 == 0`.
- **`MemoryCompactionService`** — re-squashes older entries into the snapshot; marks compacted entries via `compacted_into` FK to preserve provenance.
- **`ProjectMemoryProvider`** — builds the planner context block (snapshot + last 10 uncompacted entries). Reported `MemoryStats` feed the dashboard.
- **Planner injection** — `PlannerRequestV2.projectMemory` wired into `{{PROJECT_MEMORY}}` marker in `prompts/v2/planner.md`. Empty for first-run projects.
- **API** — `GET /api/projects/{id}/memory`, `GET /api/projects/{id}/memory/ledger` via `ProjectMemoryController`.
- **Frontend** — Memory tab added to `ProjectDashboardPage`; `ProjectMemoryPanel` renders token-budget bar, stats tiles, compacted history, and recent-run entries.
- **Config** — `RunLedgerAgent: LIGHTWEIGHT` added to the model matrix in `application.yml`.

### Migrations

- **V88** `project_memory.sql` — `project_run_ledger` + `project_memory_snapshot` with RLS policies matching V67.

### Notes

- Memory generation is a silent background concern — no SSE events, no user-visible failure modes. If the LLM call fails or the project has no id, the run terminates normally.
- Scaling: 1–10 runs → raw entries (~1k tokens); 10–50 → snapshot + last 10 (~4k); 50–200 → re-squashed snapshot (~6k); RAG deferred to post-pilot.

---

## [2.1.1] — 2026-04-14

Pilot v3.0 — project workspace layer on top of V2 — plus a bug sweep surfaced
by the first customer test runs.

### Added

#### Project workspace (R1)
- `ProjectDashboardPage` at `/projects/:projectId/dashboard` with metric tiles
  (active / completed / failed runs + average accuracy), per-project score
  trend chart (recharts), recent runs + recent PRs tabs, and deep-drill tabs
  for Tasks / Repos / Architecture / Team.
- `ProjectDashboardController` (`GET /api/projects/{id}/dashboard`) aggregates
  counts + recent activity + arch-doc status (NONE / GENERATED / STALE).
- `ProjectScoresController` (`GET /api/projects/{id}/scores`) returns the
  full score history for the trend chart.
- `Project` entity gains `branchNamingPattern`, `targetBranch`,
  `defaultReviewers` (V81) — used by the R5 multi-repo push path.

#### Multi-repo projects (R2)
- `ProjectRepo` dual-scope registry (`ORG` = shared across org, `USER` =
  personal) + `ProjectRepoLink` join table (V81). Linking rules: ORG
  repo must match the project's org; USER repo must be owned by the linker.
- `ProjectRepoController` — `/api/repos` for lifecycle, `/api/projects/{id}/repos/{repoId}` for link management.
- `WorkspaceInitService.cloneMultipleRepos` clones every selected repo into
  `workspace/<runId>/repos/<name>/`. Per-repo failures don't block siblings.
- `RunRequest.selectedRepoIds` + `PipelineRun.selectedRepoIds` (V86) persist
  the selection for the post-run push path.
- `NewRunModal` replaces the single-repo URL input with a multi-select when
  the project has linked repos.

#### Codebase intelligence (R3)
- `ProjectCodeMap` (per-repo) + `ProjectArchitectureDoc` (project-wide,
  versioned, annotatable) persisted via V84.
- `CodeMapGenerator` deterministic scan: file-tree layout + route regex
  extraction (Spring / Express / FastAPI) + model detection by filename.
- `ArchitectureDocGenerator` composes per-repo codemaps + annotations
  into a single markdown doc. Annotations flow into the assessment prompt.
- `StalenessService` (Caffeine 5-min cache) compares stored
  `generated_from_shas` against current HEAD SHAs — powers the amber
  "N/M repos ahead" badge on `ArchitectureViewer`.

#### Feature assessment (R4)
- `FeatureAssessmentService` runs before the planner and produces an
  `AssessmentResult`. Heuristic by default; LLM-backed (Anthropic) when
  the `assessment.llm.enabled` feature flag is on. Silent heuristic
  fallback on any LLM failure.
- `AssessmentPanel` surfaces the assessment above the plan-approval gate.

#### Git workflow (R5)
- `pull_request_record` table (V82) — one row per PR opened by the
  platform. Indexed by `(repo_full_name, pr_number)`.
- `PullRequestRecordService` writes rows on creation; new
  `GitHubWebhookController` at `POST /webhooks/github` verifies
  `X-Hub-Signature-256` (HMAC SHA-256, constant-time) and flips status on
  `pull_request.closed` events.
- `ScmPushService` now reads `Project.branchNamingPattern` /
  `targetBranch` / `defaultReviewers`, and the new `pushMultiRepo` path
  handles per-subdirectory push + per-repo PR + cross-linking via the new
  `GitHubRepoService.updatePullRequestBody`.
- `PullRequestController` — `GET /api/runs/{id}/prs`. Rendered by
  `RunPrList` in the run detail sidebar.

#### Multi-dimension review scoring (R6)
- `ReviewScoringService` derives six dimensions (code quality, test
  coverage, architecture, security, performance, design compliance) from
  existing review data — no extra LLM call. Columns added via V83.
- `ScoreCard` at the top of `ValidationReportPanel`: coloured bars
  (≥80 green / 60–79 yellow / <60 red).

#### Figma URL (R7)
- `RunRequest.figmaUrl` + `pipeline_run.figma_url` (V85). Drives
  `designComplianceScore` and is passed to the planner prompt. New URL
  input on `NewRunModal`.

#### Codemap-on-clone (PRO / MAX / ENTERPRISE)
- `run_code_map` (V87) + `RunCodeMapService`. For existing-repo runs on
  paid tiers: scans repo's file tree + routes, Anthropic compresses to
  ≤3000-token brief, persists raw + summary + file/route counts + tokens.
  Summary becomes the planner's `workspaceSummary`. FREE tier excluded.
- Stats surfaced as "Codemap pulled — N files, K routes (T tokens)"
  above the plan-approval gate on `RunDetail`.

#### Stack detection + drift
- `StackDetector` infers `{language, framework, version}` from root
  marker files; version regex-extracted from `pom.xml` / `package.json` /
  `pyproject.toml`.
- `StackRefreshJob` (`@Scheduled` daily 03:00 with `@SchedulerLock`)
  refreshes `detected_stack` + `last_synced_sha` for every
  `project_repo` not refreshed in the last 20h. Counters:
  `executespec.stack.refresh.{changed,unchanged,failed}`.
- `ProjectArchitectureService` captures per-repo SHAs via the new
  `GitHubRepoService.getBranchHeadSha` helper.

### Fixed

- **Cancel wrote PARTIAL instead of CANCELLED.** `V2RunOrchestrator.terminate`
  now preserves a user-initiated CANCELLED status when the pipeline thread
  races past the cancel write.
- **Hard cap not enforced mid-task.** `WorkerAgentV2` loads
  `pipeline_run.hardCapUsd` at task start and checks cost every 3 iterations.
  On breach the loop exits cleanly with the new
  `WorkerResult.Outcome.BUDGET_EXCEEDED`.
- **Planner under-classified long specs.** Hardcoded
  `DEFAULT_CLASSIFICATION=MEDIUM` replaced with `SpecClassifier` (length +
  bullet count + distinct domain keywords + comprehensive markers → picks
  `SINGLE_SHOT` / `MEDIUM` / `MULTI_TASK` dynamically).
- **`testCoverageScore` stuck at 20.** New `TestCoverageRescorer`
  listens for `TestExecutionCompletedEvent` and re-derives the score
  from actual test execution results.
- **Hibernate schema-validation refused to boot.** V86 adds
  `pipeline_run.selected_repo_ids`. Six entity fields now carry
  `@JdbcTypeCode(SqlTypes.JSON)` so `String → jsonb` binding works.
- **PARTIAL + A looked like failure.** Green "✓ final build passed"
  chip next to the status on PARTIAL runs whose reviewer's final build
  passed.

### Migrations

- **V81** — `project_repo` (ORG|USER) + `project_repo_link` + `project`
  workflow columns
- **V82** — `pull_request_record`
- **V83** — `validation_report` six score columns
- **V84** — `project_code_map` + `project_architecture_doc`
- **V85** — `pipeline_run.assessment_result` + `figma_url`
- **V86** — `pipeline_run.selected_repo_ids`
- **V87** — `run_code_map`

### Operational notes

- **New env var:** `GITHUB_WEBHOOK_SECRET` required for `/webhooks/github`
  (returns 401 when unset).
- **New feature flag:** `assessment.llm.enabled` (default off).
- **New scheduled job:** `StackRefreshJob` — uses existing `shedlock` table.

---

## [2.1.0] — 2026-04-12

### Added

#### Harness V2 — new sequential-first pipeline (production path behind V1's `/api/runs`)

- **`V2RunOrchestrator`** — integration entry point. Persists runs to V1's `pipeline_run` / `agent_task` tables so `GET /api/runs/{id}` stays shape-compatible; only `harnessVersion` distinguishes V2 rows. Pipeline phases: PLANNING → (optional plan gate) → WORKING → REVIEWING → validation report + test generation → terminal. Dedicated `v2-orchestrator` thread pool so V1 and V2 back-pressure independently.
- **Native tool calling for all three providers** — `LlmRouterV2` dispatches through Spring AI for Anthropic and through custom wrappers for OpenAI (`OpenAiNativeToolClient`) and Gemini (`GeminiNativeToolClient`) to work around Spring AI 1.1.4 regressions (OpenAI `extra_body` 400, Gemini multi-turn `Optional.get()` crash, Gemini 2.5+ `thoughtSignature` round-trip).
- **Per-complexity tier-based model routing** — `SequentialExecutorV2.tierConfigFor()` resolves a model per task via `ModelResolver`: LOW → LIGHTWEIGHT, MEDIUM → WORKHORSE, HIGH → PLANNING. HIGH tasks on Anthropic upgrade to Sonnet, on OpenAI to gpt-5.4.
- **Per-complexity iteration + max-token caps** — LOW=12 iters / 4k tokens, MEDIUM=22 / 8k, HIGH=35 / 16k.
- **Anthropic prompt caching (`CONVERSATION_HISTORY`)** — `AnthropicCacheOptions` with explicit per-message-type `messageTypeMinContentLength` thresholds so cache_control blocks actually emit. Cache reads at 10% of input rate; typical 20-iter task saves ~70% on input tokens. OpenAI + Gemini rely on server-side automatic caching.
- **Plan approval gate** — PRODUCTION runs pause at `AWAITING_APPROVAL` post-plan, emit `GATE_OPENED` SSE with credits + estimated cost, block on `GateStore.awaitPlanGate()`. PROTOTYPE and `skipApproval=true` runs bypass. `/api/runs/{id}/approve` signals `GateStore.setPlanGate()` directly for V2. Reject → run `CANCELLED`.
- **Tier-aware cost tracking** — `V2RunCostTracker` records per-call tokens + cost (cache-aware), exposes live snapshot. Pre-plan `estimateRunTierAware()` sums per-task cost using each task's tier-resolved model. Phase-level token attribution (planner / worker / reviewer) via snapshot-diff populates `plannerInputTokens`, `reviewerInputTokens`, etc. on `PipelineRun` so the UI's "Token Usage by Model" panel reconciles. Writes a `run_cost_log` row at terminal.
- **Workspace + artifacts** — workspace initialized via V1-shared `WorkspaceInitService.ensureRunWorkspace()`. `.agent-context/project.json` written with planner's `project_type` so `DevDockerfileGenerator` picks the right Dockerfile. Planner-authored `README.md` + orchestrator fallback run-meta README. Final `git add -A && git commit` so `/push-github` works.
- **Design system wiring** — `UiStyleResolver` (reads 5000+ DESIGN.md files under `executespec.designs.root-path`) resolves a design block post-plan from `extractStyleHint(userRequest)` + `plan.projectType()`; block prepended to every worker task's system prompt.
- **Run title** — `plan.planTitle` → `PipelineRun.runTitle`. Per-agent `*ActualModel` fields populated so the UI no longer shows "unknown".
- **Reviewer (`ReviewerAgentV3`)** — two passes (general + wiring) post-execute, followed by a final build. Iteration-limit outcomes fall through to the final-build verdict (`CLEAN / FIXED_ISSUES / UNRESOLVED_ISSUES`) instead of being misreported as `REVIEW_FAILED`; only hard `ERROR` outcomes map to `REVIEW_FAILED`.
- **Validation report + test generation** — post-review, synthesizes `ReviewReportV2` + `CompileResult` + `WorkerReport[]` from V2 state and invokes V1's `ValidationReportService.generateAndPersist()`. `TestGenOrchestrator.runTestGenPipeline()` runs next; `TestPreviewCoordinator` auto-starts preview for execution. OpenAI report calls reroute to Anthropic for the narrative generation only (V1 `LlmRouter` still trips the Spring AI 1.1.4 `extra_body` bug; V2 wrapper only fixes the main pipeline).
- **Preview on V2** — `PreviewService` confirmed harness-agnostic. 501 guards removed from `/preview/start`, `/preview/rebuild`, `/preview/stop`, `/preview/exec` for V2 runs.
- **SSE event stream** — `V2RunOrchestrator` emits `RUN_STATUS` on every phase + terminal `COMPLETE` + `broadcaster.complete(runId)`. `SequentialExecutorV2` emits `TASK_STATUS` at task start / terminal and persists the same value to `agent_task.status` so polled UI and SSE stream stay in lockstep. `LlmRouterV2` emits `TOKEN_USAGE` per successful dispatch. `PerkyMessenger` rotates encouraging `LOG` messages every 15s during PLANNING / WORKING / REVIEWING.
- **Deferred-action 501 shim (`V2DeferredActionGuard`)** — retry / pause / resume / rework / reopen-merge / tasks/{t}/retry / implement-suggestions / retry-validation / edit-plan / clarify / merge / chat / apply / iterate / run-tests / apply-test-fixes return HTTP 501 for V2 runs with a feature-name payload for UI toasts. V1 runs unchanged.
- **V1 classes annotated `@Deprecated(since = "v2")`** — 18 classes + 321 methods across `harness/` (PipelineHarness, SequentialTaskExecutor, TaskCompileSupervisor, SubtaskDecomposer, GapClosingService), `agent/` (WorkerAgent, PlannerAgent, LlmRouter, RequestTriageService), `compiler/CompilerAgent`, `reviewer/ReviewerAgentV2`, and the entire `batch/` package. Shared infrastructure is untouched.
- **V2 eval harness** — `scripts/v2-eval.sh` submits 3 complexity bands × 3 providers (9 runs, PROTOTYPE mode), polls to terminal, fetches validation reports, emits machine-readable JSON + markdown comparison. Specs in `scripts/v2-eval-specs/{low,medium,high}.txt`; output in `scripts/v2-eval-results/`.

### Changed

- **Default harness** — runs routed via `harness.version` feature flag (`v1` | `v2` | `v2-canary`). V1 remains the default; flip the flag to put V2 in front of all traffic (UX unchanged — same endpoints, same UI).
- **Run detail page** — nested scroll regions collapsed into one surface so the title bar + status icons scroll with the task list as a single unit.
- **Live log reconnect** — `useSse` hook no longer clears the log buffer on same-run re-mount; entries survive status-flip re-renders and SSE reconnects.
- **`RunEvent`** — new `tokenUsage(runId, in, out)` factory. `COST_UPDATE` reserved for terminal; per-call live stream emits `TOKEN_USAGE`.
- **`PipelineRun.harnessVersion`** — new column (V76 migration) with default `'v1'`. `RunDetail` / `RunSummary` DTOs + `RunMapper` expose it so the frontend can show a V1/V2 badge.
- **`harness.version` + `harness.v2.canary_percent`** — seeded by V77 migration.

### Fixed

- **Anthropic planner malformed tool payload recovery** — `PlannerAgentV2.salvageTasksFromBlob()` extracts the real tasks array when Anthropic occasionally emits `<parameter name="tasks">[...]` XML inline inside the `smokeTestStrategy` string. Empty-plan runs now fail cleanly with a descriptive reason instead of throwing from `ExecutionRequest.build()`.
- **Task status stuck on PENDING in UI** — `SequentialExecutorV2.persistTaskStatus` updates `AgentTask.status` transactionally at task start and on every terminal path so polled clients see live progression.
- **Reviewer false-failure on iteration cap** — see Reviewer entry above.
- **Test generation target** — `TestGenOrchestrator` now receives the full serialized `PlanV2` JSON (previously received just the plan title, producing zero test tasks).
- **Validation report `extra_body` error on OpenAI** — V2 swaps `run.aiProvider` to ANTHROPIC for the `ValidationReportService.generateAndPersist` call only, bypassing Spring AI 1.1.4's `extra_body` regression. Restored after the call.

---

## [2.0.0] — 2026-04-10

### Added

#### Per-Provider Run Pool (Item 0)
- **`ProviderRunPoolManager`** — one isolated thread pool per LLM provider (ANTHROPIC, OPENAI, GOOGLE), each with its own semaphore and `PriorityBlockingQueue`. Thread names follow the pattern `provider-pool-{provider}-{n}`. Capacity per provider is configurable via `agent.provider-pools.*` properties.
- **Priority queuing** — ENTERPRISE > PRO > FREE tier runs are submitted with corresponding priority levels; higher-tier users never wait behind free-tier runs within the same provider queue.
- **429 rejection** — when a provider's queue is at capacity, run submission returns HTTP 429 immediately instead of blocking the request thread.

#### Phase 0 — Stop the Bleeding
- **Issue #15 SSE Emitter Bounds** — maximum 5 emitters per run, 500 global cap. Metrics `executespec.sse.emitters.active` and `executespec.sse.emitters.rejected` expose emitter pressure. Excess emitter registrations are rejected with HTTP 429 and logged at WARN.
- **Issue #18 Workspace Secret Stripping** — `.env`, `*.pem`, `*.key`, `credentials.json`, and related secret files are stripped from ZIP workspace exports. `WorkspaceExporter` now filters these patterns before creating the archive, preventing secret exfiltration via workspace downloads.
- **Issue #17 Inter-task Budget Enforcement** — `SequentialTaskExecutor` checks the run cost budget between each task, not just within the worker loop. Tasks that would breach the hard budget ceiling are skipped and marked FAILED rather than proceeding to consume additional budget.

#### Phase 1 — Foundation
- **Issue #1 Durable Background Validation** — background validation state is now tracked in the `background_validation` table (V64 migration). Stalled validations are auto-retried up to 3 attempts. New endpoint `POST /api/runs/{id}/retry-validation` allows manual retry. Validation stage (REVIEW, TEST_GEN, TEST_EXEC, REPORT) and attempt count are persisted, enabling recovery across server restarts.
- **Issue #2 DB Gate Fallback** — `gate_record` table (V65 migration) mirrors all gate state from Redis. `GateStore` performs dual-write on every state change; on Redis miss, falls back to DB read. Eliminates the run auto-fail that previously occurred when Redis restarted mid-approval.
- **Issue #6 Credential Lifecycle Extension** — `RunKeyStore` TTL is now extended to cover the full background validation phase, not just the main pipeline. Accessing a key when none is present in OWN_KEYS mode throws `KeyResolutionException` instead of silently returning null.
- **Issue #27 Prompt Injection Defense** — `ContextSanitizer` strips `<system>` tags and common prompt injection patterns (`ignore previous instructions`, `[INST]`, etc.) from workspace context files (README, CODEBASE_CONTEXT.md) before they are injected into planner or worker prompts. Provides defense against second-order injection via repo content.
- **Issue #8 LLM Tracing** — `executespec.llm.latency` Micrometer timer and `executespec.llm.tokens.input`/`executespec.llm.tokens.output` distribution summaries added, all tagged by `provider`, `model`, and `agent_tier`. Provides per-provider cost visibility in Grafana.

#### Phase 2 — Scale & Correctness
- **Issue #4 Circuit Breaker Tuning** — `UNHEALTHY_THRESHOLD` raised from 5 to 10 consecutive failures; `RECOVERY_WINDOW` extended from 60s to 180s. HTTP 429 responses are now excluded from the failure count — rate limits no longer open the circuit breaker, preventing unnecessary provider abandonment during traffic spikes.
- **Issue #4 Per-Run Provider Pinning** — `RunProviderPinStore` (Redis-backed, 2h TTL) pins the first-successful provider to the run ID. Subsequent tasks in the same run reuse the pinned provider, eliminating mid-run provider switches that caused context drift and compilation failures.
- **Issue #5 Progressive Merge Validation** — `ProgressiveMergeValidator` performs a fast syntax check (javac/tsc/python -m py_compile) immediately after each task is merged to main, before the next task begins. Merge-breaking syntax errors are caught immediately rather than accumulating until the CompilerAgent phase.
- **Issue #3 Distributed SSE** — SSE event distribution now uses Redis Pub/Sub (`sse:channel:{runId}`) so any MIG instance can broadcast to any client, eliminating the requirement for load-balancer cookie affinity. An SSE replay log (`sse:replay:{runId}`, Redis list) stores the last 200 events per run. Clients reconnecting with `Last-Event-ID` receive missed events automatically.
- **Issue #3 Encrypted RunKeyStore** — API keys in `RunKeyStore` are now encrypted at rest using AES-256-GCM via `KeyEncryptor`. The encryption key is derived from `app.jwt.secret`. Keys are stored in Redis with a 2h TTL that extends for background validation.
- **Issue #7 Coherence-Aware Subtask Decomposition** — `CoherenceGroupAnalyzer` analyzes file dependency graphs before decomposition. Tightly coupled files (shared interfaces, co-located tests, same module) are kept in the same subtask rather than split, reducing cross-subtask import failures.
- **Issue #10 Side-Effect Outbox** — `side_effect_outbox` table (V69 migration) records all external side effects (GitHub PR creation, Slack notifications, JIRA comments, Confluence child pages). Failed side effects are retried with exponential backoff. Saga compensation entries allow rollback of partially-completed multi-step operations.
- **Issue #14 Prompt Versioning** — `PromptAssembler.assembleAndTrack()` computes a SHA-256 prefix hash of every assembled prompt and saves a row to the `prompt_audit` table (V72 migration). A rollback endpoint `POST /api/admin/prompts/{hash}/rollback` allows reverting to a previous prompt version. `agent_task.prompt_hash` column links each task to the exact prompt used.
- **Issue #19 Data Retention + GDPR** — configurable per-tier data retention (FREE=30 days, PRO=90 days, ENTERPRISE=365 days) enforced by a nightly scheduled job. Soft-delete columns (`deleted_at`, `retention_expires_at`) on core tables (V70 migration). `DELETE /api/users/me/data` endpoint performs GDPR erasure of the requesting user's personal data and associated runs.
- **Issue #25 Provider Degradation Queuing** — when all providers are simultaneously degraded (circuit open), incoming run submissions are queued in Redis rather than immediately rejected. Queued runs are automatically retried when any provider recovers. Queue depth is exposed as metric `executespec.provider.degradation_queue_depth`.

#### Phase 3 — Polish & Resilience
- **Issue #11 Smart Context Window** — message relevance scoring replaces the fixed sliding window. Tool failure messages score HIGH, file write confirmations score MEDIUM, status-check messages score LOW. The compaction algorithm retains the highest-scored messages within the token budget rather than blindly keeping the last N messages.
- **Issue #12 Triage Escalation Detector** — `TriageEscalationDetector` monitors running single-shot tasks and auto-escalates to multi-task if thresholds are crossed: file count >8 + iteration count >15, or wall-clock time >8 minutes, or compiler error count >5 after initial compile. Escalation events are recorded in the `triage_outcome` table (V71 migration) for planner feedback-loop training.
- **Issue #13 Headless Test Execution** — batch run test execution now uses Docker containers (`--read-only`, `--cap-drop ALL`, memory/CPU limits) instead of the host JVM process, matching the security posture of interactive runs.
- **Issue #16 Git Worktree Locking** — `GitWorktreeLock` (Redis-backed `SETNX` with 5-minute TTL) prevents two subtasks in the same workspace from running concurrent git operations. Lock acquisition is retried with exponential backoff before failing the subtask.
- **Issue #26 Global Preview Concurrency** — Redis-backed per-user preview limits: FREE=1, PRO=3, ENTERPRISE=10. Requests exceeding the limit return HTTP 429 with a message indicating the active preview count and user's limit.
- **Issue #28 PostgreSQL Row-Level Security** — RLS policies (V67 migration) on `pipeline_run`, `agent_task`, `validation_report`, and related tables using `current_setting('app.current_org_id')`. Every DB connection sets this parameter at session start via `DataSourceOrganizationFilter`, ensuring cross-org data isolation at the database layer.

#### New Flyway Migrations
- **V64** — `background_validation` table (stage, attempt count, last-attempt timestamp)
- **V65** — `gate_record` table (DB mirror of Redis gate state for fallback)
- **V66** — `app_user.token_invalidated_at` column (per-user force-logout timestamp)
- **V67** — RLS policies on core tables (`pipeline_run`, `agent_task`, `validation_report`)
- **V68** — `event_log` table + `pipeline_run.executing_instance` / `execution_heartbeat` columns
- **V69** — `side_effect_outbox` table (type, payload, status, attempts, compensation)
- **V70** — Retention policy columns (`deleted_at`, `retention_expires_at`) + `data_retention_policy` table
- **V71** — `triage_outcome` table (classification, escalation reason, outcome feedback)
- **V72** — `prompt_audit` table + `agent_task.prompt_hash` column
- **V73** — `plan_effectiveness` table (plan vs actual task metrics for planner feedback)

### Changed

- **Circuit breaker thresholds** — `UNHEALTHY_THRESHOLD` 5→10, `RECOVERY_WINDOW` 60s→180s (issue #4)
- **`LlmRouter` rate-limit backoff** — exponential backoff sequence extended to 2s → 5s → 10s → 20s → 60s before provider fallover, reducing unnecessary provider switches during burst rate-limit periods
- **`TriageEscalationDetector` thresholds** — file threshold configurable via `@Value("${agent.triage.file-threshold:8}")` (was hardcoded to 5); iteration threshold raised to 15
- **`ContextAgent`** — now applies the JSON patch array (`KEEP/UPDATE/ADD/REMOVE` operations) output by `context-agent.txt` to update `CODEBASE_CONTEXT.md` and `decisions.md`, replacing the previous behavior of writing the raw LLM response verbatim
- **`RunKeyStore`** — switched from plaintext `ConcurrentHashMap` to AES-256-GCM encrypted Redis storage; TTL extended to cover background validation phase
- **`GateStore`** — dual-write to Redis + `gate_record` table on all state changes; Redis miss falls back to DB read

### Fixed

- **`KeyEncryptor` property path** — corrected to `app.jwt.secret` (was referencing the wrong property path, causing startup failure when a custom JWT secret was configured)
- **`PromptAuditRepository` duplicate bean** — renamed to `PromptHashAuditRepository`; resolves Spring context startup failure when both `agent` and `router` packages defined a bean with the same name
- **Flyway V71–V73 FK references** — corrected foreign key column types from `UUID` to `VARCHAR(36)` to match the `pipeline_run.id` column definition; fixes migration failures on clean-schema deployments
- **`ContextAgent` raw response write** — `ContextAgent` now parses and applies the JSON patch array instead of writing the LLM's raw text response to `CODEBASE_CONTEXT.md`, preventing corruption of the context file on every run
- **`failureReason` shown on COMPLETE tasks** — subtask decomposition notes are no longer surfaced as `failureReason` on successfully completed tasks; `failureReason` is now null for any task in COMPLETE status
- **`TriageEscalationDetector` hardcoded thresholds** — file count threshold and iteration count threshold are now configurable via `@Value` annotations; previously hardcoded values required a code change to tune

---

## [1.5.1] — 2026-04-09

### Added
- **Billing plan restructure** — 4 new plans (Pro Monthly INR/USD, Max Monthly INR/USD). MAX tier with BYOK, unlimited runs, 3 concurrent. Old STARTER/PRO plans soft-deactivated. PLATFORM key mode renamed to MANAGED. (V62 migration)
- **Topup package restructure** — 6 split INR/USD packages (5/10/20 runs each). Old packages deactivated. Currency toggle on BillingPage. GST label on INR prices. (V63 migration)
- **Prorated refund on upgrade** — When upgrading (e.g. PRO → MAX), unused runs refunded automatically. Refund = MIN(runs-based, time-based) to prevent gaming. Razorpay partial refund issued, credit note generated, refund email sent.
- **BYOK enforcement for MAX tier** — `ApiKeyResolver.validateKeyAvailability()` blocks MAX users without API keys. Frontend: warning banner in NewRunModal, Launch button disabled, provider pills greyed out with lock icon for unconfigured providers.
- **Top-up button** — Sidebar and Header show "Top-up Runs" for PRO users (hidden for MAX/ENTERPRISE — unlimited runs). Links to `/billing#topup` with auto-scroll.
- **RBAC map** — Comprehensive `docs/RBAC.md` documenting all roles, tiers, endpoint authorization, permission matrix, and frontend access controls.
- **GST in invoice email** — Invoice email body now shows subtotal, GST (18%), and total amount. Thymeleaf template updated with conditional GST row.

### Changed
- **Provider naming: Google → Gemini** — Backend `ModelController.PROVIDER_LABELS` and all frontend references (OrgProjectsPage, PrivacyPage, TermsPage, DpaPage) now display "Gemini" instead of "Google" for the AI provider.
- **Sidebar buttons restyled** — Upgrade and Top-up buttons now use `bg-accent-blue` solid fill (matching Launch Run button) instead of muted grey/translucent styles.
- **Unlimited runs display** — BillingPage shows "X runs used (Unlimited)" instead of "X / -1" for MAX tier. Progress bar hidden for unlimited metrics.
- **Upgrade pill for PRO** — Header shows Upgrade pill for both FREE and PRO users.
- **`canManageAiKeys` for solo MAX users** — `TierPermissionService.freeUserPermissions()` now grants `canManageAiKeys` for MAX/ENTERPRISE solo users (was always false).
- **Buy Extra Runs hidden for MAX** — Top-up section on BillingPage hidden for MAX/ENTERPRISE tiers.
- **Right-click disabled** — Context menu prevented across entire UI.

### Fixed
- **Duplicate email on upgrade** — `upgradeTier()` no longer sends `sendTierUpgradeEmail`; `activateSubscription()` already sends `sendSubscriptionActivated`. Single email per activation.
- **Refresh token blocklist race condition** — `INSERT ... ON CONFLICT DO NOTHING` atomic upsert prevents duplicate key errors on concurrent logout.
- **Upgrade blocked for PRO → MAX** — Tier rank comparison now allows upgrades to higher tiers. Same/lower tier blocked with "Cancel first" message.
- **Refund policy in Terms** — DisclaimerModal item 4 updated with full prorated refund policy. New item 5 for non-refundable top-ups.

---

## [1.5.0] — 2026-04-09

### Added
- **Batch auto-retry** — Failed batch tasks are automatically retried in real-time after batch results are processed. `onBatchComplete()` queries for FAILED tasks, resets them to PENDING, and executes via `SequentialTaskExecutor` before compile.
- **Manual test execution (Beta)** — New "Run Tests" button in PreviewTab allows manual test execution against a running preview for batch runs. `POST /api/runs/{runId}/run-tests` validates preview is RUNNING and test plan is COMPILED, then triggers `TestPreviewCoordinator.triggerManualExecution()`.
- **Batch badge** — Emerald pill badge on RunCard (list view) and RunDetailTabs (detail view) indicating batch execution mode. `approvedExecutionMode` field added to `RunSummary` DTO.
- **Batch model attribution** — `BatchResumeService` now resolves the WORKHORSE-tier model via `ModelResolver` and sets `model`/`provider` on each completed task, fixing "unknown" display in token usage.
- **`StateStore.resetTaskForRetry()`** — JPQL update resetting task status to PENDING with cleared failure fields for batch retry flow.
- **`StateStore.setTaskModelProvider()`** — Sets model/provider on tasks after batch resume without coupling to `markTaskComplete()`.

### Changed
- **Batch runs skip auto-preview** — `TestPreviewCoordinator.onTestsReady()` detects batch runs and publishes an empty `TestExecutionCompletedEvent` instead of starting auto-preview. Unblocks validation report generation without wasting preview resources.
- **Batch debug logging removed** — Removed raw API response dumps from `AnthropicBatchClient`, `GoogleBatchClient`, and `OpenAiBatchClient` (status responses, result bodies, truncated previews). Removed unused `truncate()` helpers.

---

## [1.4.0] — 2026-04-07

### Added
- **Phase-based subtask decomposition** — Complex single-file tasks (MEDIUM/HIGH complexity) now decompose into method-level phase subtasks. MEDIUM → 2 phases, HIGH → 3 phases. Phase 1 creates the file skeleton; subsequent phases read and extend it. Complements existing file-based decomposition for multi-file tasks.
- **Subtask write nudge** — WorkerAgent injects a forced write instruction when a subtask has consumed 5+ tool calls with zero `writeFile` dispatches. Breaks the read-loop pattern (Gemini reads repeatedly without writing). Nudge includes the owned file name and remaining budget.
- **Subtask iteration limit** — `SUBTASK_ITERATION_LIMIT = 15` for subtasks (vs 35 for normal tasks). 15 iterations gives headroom for context reads (CODEBASE_CONTEXT, task-assignments, file-locks, listFiles) + writeFile.
- **Fabrication escalation on iteration-limit failures** — `isFabricationFailure()` in `SequentialTaskExecutor` now also detects iteration-limit failures (`"tool-call iterations"`). Subtasks that hit the iteration cap are retried with PLANNING tier model.
- **Per-response tool call cap** — `MAX_TOOL_CALLS_PER_RESPONSE = 15` prevents Gemini's 100+ call batches from exhausting the iteration budget in one response.
- **Read-only tool dedup cache** — Caches results of read-only tools (readFile, listFiles, searchFiles, git_status, git_diff). Cache hits are FREE — don't count toward `toolCallCount`. Invalidated on file writes.
- **10s polling on run details** — RunsPage polls `fetchRun()` every 10 seconds while the run is in an active status.

### Changed
- **Status color mapping** — Distinct colors per semantic status group: Gray (inactive), Blue (executing), Purple (thinking/review), Amber (waiting), Green (success), Orange (partial), Red (failure), Pink (blocked). Updated across constants.ts, tokens.css, tailwind.config.ts, TaskGraph.tsx, TokenBubbleChart.tsx, ActivityFeed.tsx.
- **Logging levels** — Agent/compiler/reviewer/testgen loggers set to INFO (was DEBUG). Spring AI chat client set to WARN. Removes verbose LLM input/output noise from logs.
- **Worker prompts (all 3 variants)** — Added PROJECT STRUCTURE CONSISTENCY section to `worker-gemini.txt` and `worker-openai.txt` (was already in `worker.txt`). Language-specific rules for Java, Python, Node.js, Go, Rust to prevent cross-task package/module mismatches.
- **Reviewer prompt** — Added 5-axis REVIEW FLAGS, dynamic tool budget, FAST EXIT GUARD, 4-tier severity rules, verification story requirement.
- **Compiler prompt** — Added DEBUGGING METHODOLOGY, AMBIGUOUS SYMBOL RESOLUTION, CASCADING FAILURE AWARENESS, ERROR PRIORITY table.
- **Test planner prompt** — Added TEST STRATEGY PRINCIPLES, IMPLICIT TEST CASES, TEST ANTI-PATTERNS sections.
- **Test worker prompt** — Added PROVE-IT PRINCIPLE, AAA structure guidance, REAL OVER MOCK preference ordering.
- **Test fix suggestions prompt** — Added CLASSIFICATION taxonomy, CASCADING FAILURE DETECTION, increased word limit 60→80.
- **Validation report prompt** — Added PARTIAL RUN HANDLING section.

---

## [1.3.0] — 2026-04-06

### Added
- **Bitbucket SCM integration** — Full Bitbucket support as a second SCM provider alongside GitHub. `BitbucketRepoService` handles clone, branch creation, and PR via Bitbucket REST API v2 with Basic auth. `BitbucketAdapter` (InboundAdapter + OutboundAdapter) under `integration/bitbucket/` for webhook ingestion and outbound PR comments on run completion. App password encrypted at rest via `EncryptedValueConverter` (AES-256-GCM).
- **SCM-agnostic pipeline push** — `PipelineHarness.pushToScmIfConfigured()` replaces `pushToGitHubIfConfigured()` at all 4 call sites. Routes to GitHub or Bitbucket based on user credentials. New `scm_pr_url` and `scm_provider` columns on `pipeline_run` (backward compat: `github_pr_url` retained).
- **Bitbucket API endpoints** — `PUT /api/auth/settings/bitbucket` (save credentials), `POST /api/auth/settings/bitbucket/validate` (test connection), `POST /api/runs/{runId}/push-scm` (SCM-agnostic push).
- **Bitbucket frontend integration** — Bitbucket card on IntegrationsPage with credential form (username, app password, workspace, repo slug), test connection, and save. SCM-agnostic PR link and push button on RunDetail (dynamic label: "View on GitHub" / "View on Bitbucket").
- **V59 migration** — `bitbucket_username`, `bitbucket_app_password`, `bitbucket_workspace`, `bitbucket_repo_slug` on `app_user` and `pipeline_run`; `scm_pr_url`, `scm_provider` on `pipeline_run`.
- **Responsive frontend design** — Comprehensive mobile/tablet support across all pages:
  - New shared utilities: `useBreakpoint()` hook (xs/sm/md/lg/xl), `ResponsiveModal` (full-screen on mobile), `ResponsiveTable` (cards on mobile, table on desktop)
  - RunDetailTabs: scrollable tab bar, responsive metadata grid
  - TaskGraph: vertical card list on mobile, SVG graph on desktop
  - NewRunModal: full-screen sheet on mobile
  - DeployWizardModal: full-screen on mobile, responsive step indicator
  - AdminPage: `overflow-x-auto` tables, hidden secondary columns on mobile, responsive filters
  - BillingPage, PlansPage, SettingsPage, AuditPage, InvoicesPage, AccountingPage, DeploymentsPage: responsive grids, stacking layouts, touch-friendly buttons
  - CommandPalette: mobile-sized results list, 44px touch targets
  - PreviewTab: responsive iframe height, wrapped controls

### Changed
- **Planner prompt** — Added "SINGLE-FILE COMPLEXITY TRAP" rule: complex single-file components (calendars, dashboards, form wizards with 4+ features) must be split into sequential tasks building on each other in the same file. Prevents 35-iteration exhaustion on monolithic UI tasks.
- **GitTool** — Added `pushToBranchWithBasicAuth()` for Bitbucket URL format (`username:appPassword@bitbucket.org`)

### Deprecated
- `pushToGitHubIfConfigured()` in PipelineHarness (use `pushToScmIfConfigured()`)
- `POST /api/runs/{runId}/push-github` endpoint (use `/push-scm`)
- `github_pr_url` field on PipelineRun (use `scmPrUrl`)

---

## [1.2.2] — 2026-04-06

### Changed
- **Google PLANNING model** — Upgraded from `gemini-2.5-flash` → `gemini-3-flash-preview` based on eval results (9.5 overall, 5/7 pass rate, 22s avg latency vs 9.1/4/7/51s). WORKHORSE and LIGHTWEIGHT remain `gemini-2.5-flash`.
- **Model matrix YAML** — Added `gemini-3-flash-preview` to canonical model registry, updated legacy aliases

---

## [1.2.1] — 2026-04-06

### Fixed
- **Gitignore** — Fixed `eval-output/` → `eval-outputs/` to match actual folder name

---

## [1.2.0] — 2026-04-06

### Added
- **Subtask decomposition** — `SubtaskDecomposer` splits multi-file tasks into per-file subtasks at execution time. Each subtask produces exactly one `writeFile` call, eliminating fabrication and reducing token waste on retries. Tracked via `parentTaskId`, `subtaskIndex`, `subtaskTotal` on `AgentTask`.
- **Self-play prevention** — `FABRICATED_TOOL_RESULT_PATTERN` strips fabricated `<tool_result>` blocks from Anthropic model responses before tool call parsing. Prevents model from simulating entire tool conversations in a single response.
- **XML-first parsing for PATH B** — WorkerAgent now uses `parseXmlToolCalls()` first for PATH B providers (Anthropic/Google), with `parseJsonToolCalls()` as fallback. Fixes JSON short-circuit that lost 2nd/3rd tool calls in multi-tool responses.
- **Task graph subtask grouping** — Frontend `TaskGraph.tsx` rewritten with subtask groups: smaller nodes (164×36) grouped under parent with dashed border, expand/collapse chevron, progress bar ("N/M"), and aggregate status dot. Backward compatible with non-subtask runs.
- **V58 migration** — `parent_task_id`, `subtask_index`, `subtask_total` columns on `agent_task`

### Changed
- **Model matrix** — Fixed CLAUDE.md and README.md model matrix tables to match actual YAML config (Anthropic WORKHORSE = `claude-haiku-4-5-20251001`, Google = `gemini-2.5-flash`)
- **Anthropic tool calling** — Moved from native tool calling (broken in Spring AI 1.1.0) to XML prompt-based PATH B with self-play prevention

### Removed
- **LlmRouter debug logging** — Removed temporary Anthropic request/response debug logs (~30 lines)

### Files Added
- `harness/SubtaskDecomposer.java` — Multi-file task decomposition
- `resources/db/migration/V58__subtask_support.sql` — Subtask schema

### Files Modified
- `agent/WorkerAgent.java` — Self-play prevention, XML-first parsing, truncation salvage fix
- `agent/LlmRouter.java` — Removed debug logging
- `api/controller/RunController.java` — TaskGraphNode includes parentTaskId/subtaskIndex/subtaskTotal
- `frontend/src/lib/api.ts` — TaskGraphNode type updated with subtask fields
- `frontend/src/components/runs/TaskGraph.tsx` — Full rewrite with subtask grouping
- `state/entity/AgentTask.java` — Subtask decomposition fields
- `harness/SequentialTaskExecutor.java` — SubtaskDecomposer integration

---

## [1.1.0] — 2026-04-04

### Added
- **Design system integration** — 54 company design systems (Stripe, Linear, Notion, Supabase, Spotify, etc.) loaded from filesystem-backed DESIGN.md files with Caffeine caching (30min TTL)
- **3-signal UI style resolution** — explicit user hint > planner `ui_style` > project-type heuristic; covers planned and single-shot paths
- **Token-budget truncation** — design files capped at 2500 tokens, keeping priority sections (theme, colors, typography, components, layout)
- **Fuzzy design matching** — exact, lowercase, contains, base-name (.app/.ai strip), and prefix matching against 54 available designs
- **Planner UI style detection** — planner prompt updated to output `ui_style` field with curated design list
- **GCS design sync** — design files synced from GCS bucket to GCE instance on every deploy and boot (`gsutil -m rsync`)
- **CSP `frame-src` directive** — allows Cloud Run preview iframes (`*.run.app`) on the parent page
- **21 unit tests** for design system features (UiStyleResolverDesignTest)

### Changed
- **Default AI provider** — Launch New Run always defaults to OpenAI (ignores localStorage persistence)
- **Worker prompt injection** — replaced static `css-styling.txt` with dynamic DESIGN.md content per run
- **PipelineHarness** — 3-signal resolution wired after planning and in single-shot path

### Removed
- **Model override dropdown** — removed from Launch New Run modal (auto model selection only)
- **css-styling.txt** — replaced by filesystem-backed DESIGN.md files

### Files Added
- `harness/DesignSystemProperties.java` — config properties for design system
- `test/.../UiStyleResolverDesignTest.java` — 21 unit tests

### Files Modified
- `harness/UiStyleResolver.java` — major refactor: filesystem loading, cache, fuzzy match, 3-signal resolution
- `agent/WorkerAgent.java` — design content loading per run
- `agent/model/TaskPlan.java` — added `uiStyle` field
- `harness/PipelineHarness.java` — 3-signal resolution wiring
- `resources/prompts/planner.txt` — UI STYLE DETECTION instruction
- `resources/application.yml` — `executespec.designs` config block
- `config/SecurityProperties.java` — added `frame-src` CSP directive
- `frontend/.../ProviderModelSelector.tsx` — default to OpenAI, removed model override
- `.github/workflows/deploy.yml` — GCS design sync step
- `infra/scripts/startup.sh` — GCS design sync on boot

---

## [1.0.0] — 2026-04-03

### Initial Production Release

- **Multi-agent pipeline** — PlannerAgent → Plan Gate → SequentialTaskExecutor (tiered parallel) → CompilerAgent → Auto-merge → COMPLETE → Auto-preview → Background validation
- **3-provider LLM routing** — Anthropic, Google, OpenAI with YAML model matrix, Redis-backed circuit breaker (5 failures → open, 60s recovery)
- **Agent tiers** — PLANNING (Opus/GPT-5.4/Gemini Pro), WORKHORSE (Sonnet/GPT-5.4-mini/Flash), LIGHTWEIGHT (Haiku/GPT-5.4-mini/Flash)
- **Progressive merge** — each task merged to main immediately after completion
- **Request triage** — single-shot fast path for simple requests
- **Background validation** — ReviewerAgentV2 + TestGenOrchestrator + ValidationReport run after COMPLETE
- **Dual-mode previews** — Cloud Run (dev/prod, 5-min TTL) and Docker Compose (local, Caddy proxy)
- **JWT auth + OAuth2** — Google and GitHub login, RBAC with 6 roles, scoped SSE tokens
- **Multi-gateway billing** — Razorpay + Stripe via PaymentGatewayRouter, subscriptions, top-up credits, GST invoicing
- **Feature flags** — DB-backed with Redis pub/sub cache invalidation
- **Rate limiting** — Redis-backed per-tenant sliding window
- **8-layer security** — request validation, input sanitization, LLM prompt defense, code abuse scanning, shell sandbox, file sandbox, process isolation, container hardening
- **Integrations** — JIRA, Confluence, Slack, GitHub (branch + PR creation), ServiceNow, PagerDuty
- **Eval framework** — model evaluation across tiers and providers
- **GCP infrastructure** — GCE + Cloud SQL + Redis + GCS + Load Balancer + Cloud Run, Terraform-managed
- **CI/CD** — GitHub Actions with Workload Identity Federation, GCS JAR releases, health checks
- **Observability** — Micrometer + OTel → Prometheus + Tempo + Grafana (local) / Cloud Trace + Cloud Monitoring (GCP)
- **56 Flyway migrations** (V1–V56)
