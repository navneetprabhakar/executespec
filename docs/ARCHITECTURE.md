# ExecuteSpec Architecture

This document is the engineering-level map of the system. It is intended for engineers
who need to find their way around the codebase, understand where a given concern lives,
and trace a request from the React composer to the GitHub pull request it eventually
produces.

`Version`: `1.0.0-M1` (single source: [`Version`](../Version) / [`VERSION`](../VERSION) /
`pom.xml`). DB head in the current worktree: `V250`. Stack: Java 21, Spring Boot 3.5.14, Spring AI 1.1.6,
PostgreSQL + Redis. Frontend: React 18 + Vite + Tailwind v4. The backend ships as a
single Spring Boot JAR; the frontend build is served from `static/`.

---

## 1. System overview

```
                          ┌──────────────────────────────────────────────┐
                          │   Cloudflare → app.executespec.ai / *.dev    │
                          └────────────────────┬─────────────────────────┘
                                               │
                                  ┌────────────▼────────────┐
                                  │ React 18 SPA (IDE-first)│
                                  │   frontend/src/App.tsx  │
                                  │   served from static/   │
                                  └────────────┬────────────┘
                                               │ HTTPS (JWT 1h / refresh 7d)
                                               │ SSE  (5-min scoped tokens)
                                               │
       ┌───────────────────────────────────────▼──────────────────────────────────────┐
       │                          Spring Boot 3.5.14 (Java 21)                        │
       │                       single JAR, virtual threads on GCP                     │
       │                                                                              │
       │  ┌────────────────────┐    ┌────────────────────┐    ┌────────────────────┐  │
       │  │ api/controller/*   │    │   ratelimit/       │    │  api/advice/       │  │
       │  │ 107 controllers    │←──▶│  RateLimitFilter   │    │ Validation/Global/ │  │
       │  │  REST + SSE        │    │  per-tier per-IP   │    │ OptimisticLock     │  │
       │  └─────────┬──────────┘    └────────────────────┘    └────────────────────┘  │
       │            │                                                                 │
       │            ▼                                                                 │
       │  ┌──────────────────────────┐         ┌──────────────────────────────────┐   │
       │  │ integration/             │         │  state/StateStore                │   │
       │  │ RunOrchestrator + helpers│────────▶│  the single mutation API to      │   │
       │  │ submitRun / cancel /     │         │  pipeline_run + agent_task etc.  │   │
       │  │ pause / resume / retry   │         └──────────────────────────────────┘   │
       │  └────────┬─────────────────┘                                                │
       │           │                                                                  │
       │  ┌────────▼──────────────────┐  ┌──────────────────────┐                     │
       │  │ harness/                  │  │ agent/*              │                     │
       │  │ SequentialExecutor        │──│ 40 agents:           │                     │
       │  │ per-task tier-routed loop │  │ SmartClassifier,     │                     │
       │  │ smoke-tests + retries     │  │ OutlinePlanner,      │                     │
       │  └────────┬──────────────────┘  │ TaskExpander,        │                     │
       │           │                     │ Coherence,           │                     │
       │  ┌────────▼──────────────────┐  │ Worker, Reviewer,    │                     │
       │  │ tool/* (28 tools)         │  │ Patch, ApiTest,      │                     │
       │  │ readFile/writeFile/edit/  │  │ Validation, etc.     │                     │
       │  │ list/search/shell/git/    │  └─────────┬────────────┘                     │
       │  │ buildProject/runTests/    │            │                                  │
       │  │ completeTask              │  ┌─────────▼────────────┐                     │
       │  └───────────────────────────┘  │ agent/LlmRouter      │                     │
       │                                 │ provider-native      │                     │
       │                                 │ tool-calling         │                     │
       │                                 └──┬───────┬───────┬───┘                     │
       └────────────────────────────────────┼───────┼───────┼─────────────────────────┘
                                            │       │       │
                                ┌───────────▼──┐ ┌──▼──┐ ┌──▼──────────┐
                                │  Anthropic   │ │ OAI │ │   Gemini    │
                                │  (Spring AI) │ │ REST│ │   REST      │
                                └──────────────┘ └─────┘ └─────────────┘

Substrate                                Side-cars (off the hot path)
─────────                                ──────────────────────────────
                                         ┌───────────────────────────┐
   ┌───────────────────────┐             │  integration/scm/         │
   │ PostgreSQL (Cloud SQL)│             │   ScmPushService          │
   │ Flyway V1..V248       │             │   → GitHub / Bitbucket    │
   │ 127 entity/table cls  │             ├───────────────────────────┤
   └───────────────────────┘             │  cleanroom containers     │
                                         │   docker exec --rm        │
   ┌───────────────────────┐             │   SWE-bench / ProgramBench│
   │  Redis (Lettuce)      │             ├───────────────────────────┤
   │  pub/sub + cache +    │             │  billing                  │
   │  ShedLock + queues    │             │   Stripe (USD)            │
   └───────────────────────┘             │   Razorpay (INR)          │
                                         └───────────────────────────┘
```

Key non-obvious points:

- A *single* Spring Boot JAR serves prod + dev. The frontend is bundled into
  `src/main/resources/static/` at build time. There is no separate frontend service.
- Cloud Run is used only for *previews* and a separate `preview-engine` deployment
  pipeline. The main application runs on a GCE VM in `us-east4`. See
  [`docs/DEPLOYMENT.md`](DEPLOYMENT.md).
- Cleanroom containers (SWE-bench, ProgramBench) are spawned via `docker --rm
  --network none -v <hostDir>:/workspace`. The `BuildProjectTool`, `ShellTool`, and
  `RunTestsTool` route through `ProgramBenchContainerStore` /
  `SwebenchContainerStore` when a container id is set on the run.
- Terminal-Bench is intentionally different: ExecuteSpec runs the server-side
  `WorkerAgent` loop as the remote brain, while the Harbor adapter executes tool
  calls inside the Terminal-Bench container and posts tool results back. The server
  surface is operator-only (`/api/v2/test/terminal-bench/start`), `SUPER_ADMIN`
  gated, and active only on `local` / `dev` profiles.

---

## 2. Backend layering

The backend follows a strict layered structure. The rule: a controller never talks to
a tool, an agent never talks to a controller, and only `LlmRouter` and
`FallbackLlmRouter` talk to LLM provider SDKs.

```
controller  → service          → agent              → tool                → LlmRouter
────────────────────────────────────────────────────────────────────────────────────
RunController  RunOrchestrator    WorkerAgent          ReadFileTool         LlmRouter
              SequentialExecutor   OutlinePlannerAgent  WriteFileTool        (Spring AI
              StateStore           ReviewerAgent        EditFileTool          + native
              ValidationReport-    TaskExpanderAgent    BuildProjectTool      OpenAI/
                Service            CoherencePlanner-    ShellTool             Gemini
                                     Agent              RunTestsTool          REST)
                                   SmartClassifier-     GitStatusTool
                                     Agent              GitDiffTool
                                                        GitCommitTool
                                                        CompleteTaskTool
```

Concrete worked example, from request to LLM:

1. `POST /api/runs` lands on
   [`api/controller/RunController.submit`](../src/main/java/com/navneet/executespec/api/controller/RunController.java).
   The controller validates `RunRequest`, runs `InputSanitizer`, checks tier quota
   (`TierEnforcementService.assertAndIncrementRunCount`), and calls
   `v2Orchestrator.submitRun(...)`.
2. [`integration/RunOrchestrator.submitRun`](../src/main/java/com/navneet/executespec/integration/RunOrchestrator.java)
   persists a `pipeline_run` row via `StateStore`, then submits a background pipeline
   future onto a `Executors.newFixedThreadPool` pool sized by
   `PoolSizing.coreMultiple(1, 4, 16)`.
3. The pipeline thread runs phases: workspace init →
   [`SmartClassifierAgent`](../src/main/java/com/navneet/executespec/agent/SmartClassifierAgent.java) →
   [`OutlinePlannerAgent`](../src/main/java/com/navneet/executespec/agent/OutlinePlannerAgent.java) →
   plan gate (`GateStore.awaitPlanGate`) →
   [`SequentialExecutor.execute`](../src/main/java/com/navneet/executespec/harness/SequentialExecutor.java).
4. `SequentialExecutor` loops over `Plan.tasks` and dispatches each to
   [`WorkerAgent.run`](../src/main/java/com/navneet/executespec/engine/WorkerAgent.java),
   which iterates LLM-tool-call-LLM until `completeTask` fires.
5. `WorkerAgent` calls
   [`agent/LlmRouter.call(LlmRequest)`](../src/main/java/com/navneet/executespec/agent/LlmRouter.java).
   Anthropic dispatches through Spring AI's `AnthropicChatModel`; OpenAI dispatches
   through [`OpenAiNativeToolClient`](../src/main/java/com/navneet/executespec/agent/OpenAiNativeToolClient.java)
   (because Spring AI 1.1.4 mis-serialises `extra_body`); Gemini dispatches through
   [`GeminiNativeToolClient`](../src/main/java/com/navneet/executespec/agent/GeminiNativeToolClient.java).
   Each call routes through
   [`cost/RunCostTracker`](../src/main/java/com/navneet/executespec/cost/RunCostTracker.java)
   for token accounting.
6. The LLM returns tool calls. They are dispatched by `ContextBoundToolCallback`
   inside [`tool/ToolRegistry`](../src/main/java/com/navneet/executespec/tool/ToolRegistry.java),
   which hands each call to the matching `Tool` (e.g. `WriteFileTool`,
   `BuildProjectTool`). `ToolRegistry.bindForWorker(ctx)` excludes `gitCommit` from
   the worker tool set (commits happen at run-end).

### Legacy non-tool path

A second LLM router survives:
[`agent/FallbackLlmRouter`](../src/main/java/com/navneet/executespec/agent/FallbackLlmRouter.java)
(1040 lines, YAML-driven fallback chain via
[`router/ModelRegistry`](../src/main/java/com/navneet/executespec/router/ModelRegistry.java)
and circuit-breaker via
[`router/ProviderHealthTracker`](../src/main/java/com/navneet/executespec/router/ProviderHealthTracker.java)).
It is the path for agents that do *not* use native tool calling:
`AccuracyAssessmentService`, `ConversationAgent`, `ContextAgent`, `testgen/*`,
`ValidationReportGenerator`. These flow `Spring AI Prompt → callWithFallbackTracked
→ provider → ChatResponse`. Same pricing path through `RunCostTracker`, but no tool
dispatch happens.

### CLI / VS Code agent sessions

`agentsession/` is the durable protocol layer for terminal and VS Code agent shells, exposed under
`/api/agent/*` by
[`agentsession/AgentSessionController`](../src/main/java/com/navneet/executespec/agentsession/AgentSessionController.java):
`/sessions` (+ `/{id}`, `/{id}/events`), recorded and streaming turns
(`/sessions/{id}/turns` + `/sessions/{id}/turns/stream`), the static `/actions` catalog,
`/intent/interpret` (rule-based first-pass intent classification via `AgentIntentService`),
`/context/resolve` (repo→project resolution), and gate decision cards (`/gates`, `/gates/{runId}`).
It is separate from `pipeline_run`: an open-ended session can interpret intent, resolve the current
repo to a project, list gates, or propose a run without becoming a billable execution run.
Cost-bearing or destructive actions are represented as typed confirmation-required proposals; the
existing run approval path remains the only place where run credits are charged. The streaming-turn
path bridges to `SpecChatService.runTurn` (gated by `spec.chat.enabled`); when the session carries a
`projectId` it also binds `ProjectChatTools` so CLI/VS Code chat can answer project/code questions
with the same tool surface as web project chat. Per-call usage is metered to `agent_usage_ledger`
with `billingPolicy=INCLUDED`. `V240` adds `agent_session`, `agent_turn`, `agent_action_event`, and
`agent_usage_ledger` for this separation.

Provider choice for these surfaces is cross-surface account state, not a client-local default.
`app_user.preferred_ai_provider` (V242) is read/written via
`GET/PUT /api/auth/me/provider-preference`
([`auth/service/AiProviderPreferenceService`](../src/main/java/com/navneet/executespec/auth/service/AiProviderPreferenceService.java)):
explicit provider input on a run, chat turn, or project update is remembered there; an absent
provider resolves through the account preference before falling back to the project/YAML default. Web,
CLI, and VS Code all share the one preference.

---

## 3. Run lifecycle (`POST /api/runs` to GitHub PR)

Trace through a `BUILD`-mode run end-to-end. References are to
[`integration/RunOrchestrator.java`](../src/main/java/com/navneet/executespec/integration/RunOrchestrator.java)
unless noted otherwise.

### Submit (synchronous, returns 202)

1. **Controller** —
   [`RunController.submit`](../src/main/java/com/navneet/executespec/api/controller/RunController.java)
   validates `RunRequest` (Bean Validation), then scans `userRequest` with
   `InputSanitizer` (NFKC + zero-width strip + base64 decode-and-scan), then calls
   `tierEnforcementService.assertAndIncrementRunCount`, then dispatches to the
   orchestrator. Returns the `RunSummary` mapped from the freshly-persisted row.
   **Local-hands gate (V241):** a submit with `localExecution=true` is rejected with
   409 while `worker.local-hands.enabled` is OFF (default), and rejected with 403 unless
   the request is PAT-authenticated (`JwtAuthenticationFilter.AUTH_SOURCE_PAT`) — the web
   UI cannot enforce local command/file approvals, so only CLI / VS Code PAT sessions may
   run locally (RL-2026-06-037). Accepted local submits echo `localExecutionAccepted=true`
   on the summary.
2. **Orchestrator stamp** — `submitRun(...)` (20-arg canonical signature; 7 progressive
   overloads exist for callsite back-compat) stamps `runId`, `provider`, `model`,
   `plannerDbModel` (tier-resolved via `ModelResolver.resolve("OutlinePlannerAgent")`),
   `reviewerDbModel`, `harnessVersion="v2"`, `createdByUserId`, `orgId`, `projectId`.
   These ownership fields and submit-time continuation policy markers are stamped *before* the
   async pipeline thread starts (race-condition fix from 2026-04 / RL-2026-06-227). Persists
   `pipeline_run` with `status=PLANNING`, publishes `PipelineEvent.RunStarted`, submits the
   pipeline future.

### Pipeline phases (background thread)

The async `runPipeline(...)` method (called from a `Future` on `pool`) executes the
phases below.

#### 0a. BYOK key resolution

[`agent/RunKeyContextStore.resolveAndPut(runId, userId)`](../src/main/java/com/navneet/executespec/agent/RunKeyContextStore.java)
populates the per-run Caffeine cache of API keys via
[`agent/ApiKeyResolver`](../src/main/java/com/navneet/executespec/agent/ApiKeyResolver.java)
(user → org → system precedence; honours `keySourceMode` ∈ `{SYSTEM, OWN_KEYS,
HYBRID}`). Evicted in `finally`. OWN_KEYS orgs are exempted from the platform cost
cap.

#### 0a-bis. Workspace init

[`harness/WorkspaceInitService.ensureRunWorkspace(runId)`](../src/main/java/com/navneet/executespec/harness/WorkspaceInitService.java)
creates `<agent.workspace>/<runId>/`, runs `git init -b main`, writes a baseline
`.gitignore` + `.gitattributes` (with `README.md merge=union`), commits. If the spec
references multiple repos, `cloneMultipleRepos` clones each linked repo to
`<workspace>/<runId>/repos/<name>/`, captures a `RunRepoSnapshot` row per repo
(used by `RunSyncService.syncToOriginalCommit`), and augments the `.gitignore` via
[`harness/VendoredPathGuard`](../src/main/java/com/navneet/executespec/harness/VendoredPathGuard.java)
to keep `node_modules/`, `target/`, etc. out of accidental commits.

#### V182 PATCH-mode dispatch

If `run_mode = PATCH`, `runPatchPipeline(...)` runs instead of the rest. See section
4.

#### 0b. Per-run codemap (PRO+, EXISTING repos)

`runCodeMapService.generateForRun(...)` builds a per-file map (functions, classes,
imports) idempotent by HEAD SHA.

#### 0c. Feature assessment

[`assessment/FeatureAssessmentService`](../src/main/java/com/navneet/executespec/assessment/FeatureAssessmentService.java)
emits an `AssessmentResult` (summary, complexity S/M/L, files affected, risks).
Default path is deterministic length+keyword scan; LLM path is feature-flagged.

#### 1. Plan

- `SpecClassifier.classify(spec)` (deterministic heuristic).
- `runClassifierShadow(...)` calls
  [`SmartClassifierAgent.classify`](../src/main/java/com/navneet/executespec/agent/SmartClassifierAgent.java)
  on LIGHTWEIGHT tier. Heuristic-fast-path returns `SINGLE_SHOT` at 0ms / $0 for
  specs ≤150 chars. Otherwise an LLM verifier emits strict JSON
  `{strategy, estimatedTaskCount, estimatedRepoTouchCount, requiresEpics, reasoning}`
  cached in Redis (1h TTL).
- Project-scoped runs hydrate `{{PROJECT_MEMORY}}` via
  [`memory/ProjectMemoryProvider`](../src/main/java/com/navneet/executespec/memory/),
  `{{PROJECT_BRIEF}}` via `ProjectBriefService`, and
  `{{REFERENCE_DOCS}}` via `RetrievalService.retrieve(projectId, query, k, cap)`
  (RAG over pgvector).
- Strategy dispatch:
  - `SINGLE_SHOT` → `OutlinePlannerAgent.synthesizeSingleShot(req)` (no LLM call;
    `RunTitleSummarizer` LIGHTWEIGHT call only).
  - `SMALL` / `MEDIUM` → `OutlinePlannerAgent.plan(req, Mode.FULL_PLAN)` with
    `submitOutline` tool. 32k max output, 8-minute wall-clock cap.
  - `LARGE` / `LEGACY` → `runLargePipeline`: skeleton phase via
    `OutlinePlannerAgent.plan(req, SKELETON_ONLY)` (id/title/complexity/repos/
    dependsOn/type only) → parallel
    [`TaskExpanderAgent.expand`](../src/main/java/com/navneet/executespec/agent/TaskExpanderAgent.java)
    per leaf task via the pool → coherence pass via
    [`CoherencePlannerAgent.patchIfNeeded`](../src/main/java/com/navneet/executespec/agent/CoherencePlannerAgent.java)
    emitting minimal-delta `patchPlan` tool calls (ADD_MEETS_REQUIREMENT,
    ADD_DEPENDS_ON, REMOVE_DEPENDS_ON, etc.).
  - `LEGACY` enables recursive `expandEpic` to depth-2 for EPIC tasks.
- Strategy is gated by
  [`agent/PlanStrategyCircuitBreaker.degradeIfOpen`](../src/main/java/com/navneet/executespec/agent/PlanStrategyCircuitBreaker.java):
  5 consecutive failures of a strategy in a 1h window opens the breaker and degrades
  the ladder LEGACY → LARGE → MEDIUM → SMALL.

#### 1b. Plan gate (PRODUCTION only)

`PROTOTYPE` mode bypasses. `PRODUCTION` mode flips status to `AWAITING_APPROVAL`,
calls
[`harness/GateStore.openPlanGate(runId)`](../src/main/java/com/navneet/executespec/harness/GateStore.java)
(write-through: in-memory `CompletableFuture` + Redis + `gate_record` DB row), then
blocks on `awaitPlanGate` (polls Redis at 500ms, falls back to DB on Redis outage).
SSE `gateOpenedWithCredits` event fires for the UI.

The user calls `POST /api/runs/{id}/approve` which atomically claims the gate via
`gateRepository.tryResolveIfPending(...)` (returns 0 row count → HTTP 409 race-loser;
1 row count → winner). Approval charges credits via `creditsCharged` × tier multiplier.

#### 2. Execute

[`SequentialExecutor.execute(ExecutionRequest)`](../src/main/java/com/navneet/executespec/harness/SequentialExecutor.java)
iterates plan tasks. Per task:

- `tierConfigFor(complexity, runProvider, runModel, projectRun)` returns a
  `TierConfig(provider, model, maxIter, maxTokens, stagnationWarnAfter,
  stagnationForceAfter, wallClockSeconds)`.
  - `LOW` → LIGHTWEIGHT tier, 60 iters, 4096 tokens, stagnationForce=16, 30-min wall.
  - `MEDIUM` → WORKHORSE, 120 iters, 8192 tokens, stagnationForce=28, 45-min wall.
  - `HIGH` → PLANNING tier (full Sonnet/Pro/gpt-5.4), 180 iters, 16384 tokens,
    stagnationForce=40, 75-min wall.
  - Project runs apply a ×2 multiplier to `maxIter` + `wallClockSeconds`.
- `composeSystemPrompt(...)` injects: per-project-type scaffold from
  `prompts/scaffolds/<key>.md`, design-system block from `UiStyleResolver`, RAG
  reference-docs block, multi-repo workspace summary.
- Dispatches to `WorkerAgent.run(WorkerTaskRequest)` which loops:
  LLM call → dispatch tool calls → on `completeTask` return `COMPLETED`. Resilience
  levers fire at end-of-budget:
  - **Lever A (force-edit watchdog)** — at `min(0.7×maxIter, stagnationForce-1)`
    iterations with `totalWriteCalls=0`, injects "commit to ANY edit now" UserMessage.
  - **Lever B (best-guess salvage)** — on `ITERATION_LIMIT_EXCEEDED`,
    `BUDGET_EXCEEDED`, or `COMPLETED_BY_STAGNATION` with zero writes, makes one final
    LLM call attempting to salvage code.
  - **Test-verification gate** (V182) — required when `requireTestVerification=true`;
    blocks `COMPLETED` until a `runTests` call returns
    `failed:[] errored:[] exit_code:0` within 5 iterations of `completeTask`.
  - **Build-verification gate** (2026-05-08) — required when
    `requireBuildVerification=true`; needs a `buildProject` exit=0 within 5 iterations.
- After each task, a smoke test runs via
  [`BuildProjectTool`](../src/main/java/com/navneet/executespec/tool/BuildProjectTool.java)
  (one retry on failure with the build error injected; HIGH-complexity gets two
  retries).
- Outcomes mapped into `TaskOutcome.Status` (SUCCESS, SUCCESS_AFTER_RETRY,
  COMPLETED_WITH_WARNING, WORKER_FAILED, SKIPPED). Aggregated into `ExecutionResult`.
- Conversation snapshots persisted every 5 iters to Redis at
  `v2:agent-ctx:{runId}:{taskId}` (24h TTL) via
  [`context/ContextStore`](../src/main/java/com/navneet/executespec/context/).

#### 2.5. Lint auto-fix

[`lint/LintPass.run(workspace)`](../src/main/java/com/navneet/executespec/lint/LintPass.java)
detects formatters by filesystem markers (prettier, spotless-maven, spotless-gradle,
black, gofmt, rustfmt). 5-min per-tool timeout, 10-min total budget. Strips
secret-bearing env vars before spawning. Gated by `lint.autofix.enabled`. Non-fatal.

#### 3. Reviewer

[`reviewer/ReviewerAgent.review(ReviewerRequest)`](../src/main/java/com/navneet/executespec/reviewer/)
runs two passes: general code review + project-type-aware wiring pass. Wiring pass is
skipped when `linkedRepoCount <= 1 && planTaskCount <= 1`. ProgramBench bumps the
reviewer iteration cap to 80. Anthropic + OpenAI reviewers stay on PLANNING tier;
Gemini reviewer routes to WORKHORSE (`gemini-3-flash-preview`) because Pro's
4096-token implicit-cache threshold drifts and never hits cache.

After the reviewer verdict, `PostEditFormatter.run(...)` applies a final formatter
sweep so CI lint gates on the resulting PR don't fail.

#### 4. README ensure + 4b. Status resolution

`ensureReadme(...)` synthesises a placeholder if absent. `resolveTerminalStatus(...)`
pre-derives the run's `StatusAndReason` from `ExecutionResult` + `ReviewerResult` so
the validation report's `pipeline_result` reconciliation has the right anchor (2026-
05-13 audit fix: always derive, independent of `scoring.v2-verified` flag).

#### 5. Validation report

[`report/ValidationReportService.generateAndPersist(runId, ReportInput)`](../src/main/java/com/navneet/executespec/report/ValidationReportService.java)
synthesises `WorkerReports`/`ReviewReport`, runs
`AccuracyAssessmentService.assess(...)`, then:

- LLM-rendered narrative via
  [`report/ValidationReportGenerator`](../src/main/java/com/navneet/executespec/report/ValidationReportGenerator.java)
  through `FallbackLlmRouter` with `FallbackMode.IMMEDIATE`.
- Computes `verifiedScore` — additive-weighted by default since 2026-05-19
  (flag `scoring.additive-v2`, seeded by V201; `computeVerifiedScoreAdditive`):
  ```
  verifiedScore = clamp(0, 100, round( Σ(weight × slotScore) / Σ(weight) ))   // over fired slots
  CODE_DELIVERED(30)  delivery + endpoint coverage, hard 0-floor when filesGenerated == 0
  BUILD(25)           PASSING 100 / FAILING 0; skipped when finalBuildPassed null
  TESTS(20)           real passed/total blended with coverage quality, else damped LLM grade
  REVIEWER(20)        CLEAN 100 / FIXED 85 / UNRESOLVED 40 / FAILED 10
  CRITICALS(5)        100 − 20×unfixedCriticalCount, floored 0
  ```
  Unmeasured signals drop from both numerator and denominator (no implicit
  ×1.0). `scoring.gaming-guards` keeps the slot scores honest by linking code
  delivery to endpoint coverage and capping test credit when coverage quality is
  missing or partial. After the weighted score is computed, RL-2026-06-026 trust
  caps limit the headline when the reviewer failed, unresolved issues remain, the
  final build failed, concrete tests failed, or test coverage is missing/none.
  This keeps the number useful for routing while preventing a high headline from
  implying independent proof. `endpointFactor` and the two old trust-gate floors
  are retired — the reviewer's wiring pass already covers the bug class they
  guarded. The legacy
  multiplicative formula (`buildFactor × testFactor × reviewerFactor ×
  endpointFactor × criticalDamper`, multiplicative since 264cca58) survives
  behind `scoring.additive-v2=false`.
- Exposes `verificationLedger[]` on the report DTO. Each row labels the claim,
  source (`DETERMINISTIC`, `LLM_REVIEWER`, `LLM_ASSESSMENT`,
  `LLM_ASSISTED_COUNT`, or `INTERNAL_FORMULA`), confidence, observed/total
  values, and artifact reference. This is the public explanation layer behind
  `verifiedScore`; the score itself remains an internal formula summary.
- Computes `rubricScore` (additive 25/20/20/15/10/10 dimension breakdown, PR-E
  Phase A, 2026-05-13 / V194). Spec-aware via `rubricTestPointsFromCoverageLevel`.
- SUPER_ADMINs can run `POST /api/admin/validation-reports/backfill-verified-score`
  to recompute `verifiedScore` and `rubricScore` for existing reports. The
  endpoint accepts optional `{runId, limit, dryRun}`; use `dryRun=true` before a
  broad pass.
- Reroutes provider OPENAI → ANTHROPIC for the report LLM call to dodge Spring AI
  1.1.4 OpenAI `extra_body` bug. Flagged as a billing-attribution hazard
  (audit E #37); a proper fix is to surface an explicit provider override on
  `generateAndPersist`.
- For `runtimeSurface=HTTP`, kicks off `testGenOrchestrator.runTestGenPipeline` in
  background. For `CLI`, runs
  [`smoketest/CliSmokeRunner`](../src/main/java/com/navneet/executespec/smoketest/CliSmokeRunner.java).

#### 6. Final commit + 6b. Ledger

`git add -A && git commit --allow-empty` via `commitWorkspaceOutputs`. Then
[`memory/RunLedgerAgent.generateLedgerEntry(...)`](../src/main/java/com/navneet/executespec/memory/)
appends a digest to `project_run_ledger` for project memory. Every 10 runs,
`MemoryCompactionService` rewrites a versioned `project_memory_snapshot`.

#### 7. Terminate

`terminate(runId, finalStatus, finalReason, ...)`:

- Finalises `RunCostTracker`, persists `actualCostUsd`, total tokens, `statusReason`.
- Writes `RunCostLog`.
- Calls `referralCompletionHook.onTerminalStatus` (R-162 invite credit grant).
- Records `costHistoryService.recordOnTerminal`.
- Publishes `PipelineEvent.RunCompleted` or `RunFailed`.
- Tracks `EventTrackingService` `RUN_COMPLETE` event.

#### 7b. Auto-push

For `COMPLETE` or `PARTIAL` with EXISTING-mode repos,
[`integration/scm/ScmPushService.push(fresh)`](../src/main/java/com/navneet/executespec/integration/scm/ScmPushService.java)
runs. Single-repo path → branch push + PR create + reviewer request from the
project's `defaultReviewers` JSON. Multi-repo path → per-subdir push + per-repo PR +
cross-link via `GitHubRepoService.updatePullRequestBody` patching each PR body with
the sibling list. PRs persisted to `pull_request_record` (V169 added `project_id` +
`kind`; V170 added `ci_auto_fix_attempts`; V186 added `ci_status`). The
representative PR URL is stamped on `pipeline_run.github_pr_url` (the full per-repo
list is at `GET /api/runs/{id}/prs`). A one-shot `runSkipPushFlag.consume(runId)`
override skips auto-push.

---

## 4. Two run modes: `BUILD` vs `PATCH`

`pipeline_run.run_mode` (V182). Default is `BUILD` — section 3 above.

`PATCH` mode (`RunOrchestrator.runPatchPipeline`) is used by:

- SWE-bench Lite + ProgramBench benchmark adapters (bench-mode).
- Four customer-facing autofix surfaces, all routed through the chokepoint
  [`finding/FindingPatchSubmitter`](../src/main/java/com/navneet/executespec/finding/FindingPatchSubmitter.java)
  with `source="finding:<TYPE>"` and the finding key stamped on
  `pipeline_run.triggered_by_finding` (V183):
  1. **PR Review autofix** —
     [`prreview/PrReviewAutofixService`](../src/main/java/com/navneet/executespec/prreview/).
     PR Review verdict popover → "Autofix N comments" → pushes to PR head branch
     via `branchOverride`.
  2. **Tech debt autofix** —
     [`project/techdebt/TechDebtFixService`](../src/main/java/com/navneet/executespec/project/techdebt/).
     TechDebtPanel category row → "Fix" → fresh PR per category.
  3. **CI failure autofix** —
     [`project/pr/CiFailureAutoFixService`](../src/main/java/com/navneet/executespec/project/pr/).
     GitHub `check_run` webhook auto-fires on PR CI failure. Gated by flag
     `ci.autofix.enabled` (default OFF). Max 3 attempts per PR.
  4. **Code review severity autofix** —
     [`codereview/CodeReviewAutofixService`](../src/main/java/com/navneet/executespec/codereview/).
     CodeQualityPanel severity chip → one PR per severity.

Differences from BUILD:

- Skips classifier, planner, reviewer.
- Single code-gen engine loop:
  [`engine/CodeGenEngineImpl.solve`](../src/main/java/com/navneet/executespec/engine/CodeGenEngineImpl.java)
  builds one `WorkerTaskRequest` and calls `WorkerAgent` with no per-task fragmentation.
  Per-provider iteration cap: Anthropic/DeepSeek/OpenRouter/Fireworks 220, OpenAI 150,
  Gemini 180. Stagnation disabled. `requireTestVerification=true` for customer patch
  sources and OFF for blind `swebench*` / `benchmark*` sources whose target tests are held out.
- BYOK cost-cap exemption: OWN_KEYS orgs skip the platform cap.
- Outcome mapping: `COMPLETED` / `COMPLETED_BY_STAGNATION` /
  `STOPPED_WITHOUT_COMPLETE` → `COMPLETE`; `ITERATION_LIMIT_EXCEEDED` /
  `BUDGET_EXCEEDED` → `PARTIAL`; `ERROR` → `FAILED`.
- For sources whose name starts with `"finding:"`, runs `PostEditFormatter.run` and
  pushes via `scmPushService.push(...)` automatically on terminal status (no
  separate gate).

Terminal-Bench persists runs with `runMode=PATCH` for reporting consistency, but it
does **not** use `RunOrchestrator.runPatchPipeline`. It is a direct remote-worker
path in [`terminalbench/TerminalBenchRunService`](../src/main/java/com/navneet/executespec/terminalbench/TerminalBenchRunService.java):
the service registers a `RemoteToolDispatcher` session, invokes `WorkerAgent`, emits
tool calls over SSE, and waits for the Harbor adapter to execute them in the
Terminal-Bench container. Terminal-Bench requests below the service default are
normalized to 120 worker iterations, with artifact checkpoints at about iterations
16/32, a restricted terminal finalizer at iteration 64 even when shell calls keep
counting as progress, and a 1200s server-side wall clock below Harbor's 1350s
agent timeout. Wall-clock-exceeded terminal runs get one restricted finalizer
attempt before failing. If a terminal-mode worker gives a second text-only
response after the normal nudge, `WorkerAgent` runs a forced terminal finalizer
so the model must call `completeTask` or write/edit the exact artifact.
The latest measured DeepSeek first-20 comparable run used the earlier
100-floor/finalizer policy and held at 12/20 resolved (60%) for $0.400126 over 47m52s: the extra headroom fixed
some artifact/completion misses, but remaining prompt-addressable failures were
verifier-shaped, especially `/tests` visibility, named-error sweeps,
detection-first no-op handling for clean transform inputs, and golden/reference
performance thresholds. The Terminal-Bench system prompt and terminal nudges now
make those scorer-facing checks explicit.

---

## 5. Frontend chrome (Console control plane)

The protected web app is now the Console control plane. `App.tsx` mounts
[`ConsoleShell`](../frontend/src/console/ConsoleShell.tsx) for every protected
route; the old `localStorage es:console='1'` opt-in path and
`IdeWorkspace` shell are removed. The `frontend/src/ide/` package remains the
route-level implementation layer for runs, projects, billing, org settings,
benchmark tracking, and related protected surfaces. The in-editor IDE metaphor
belongs to the VS Code extension, not the web chrome.

`frontend/src/signal/` now holds the standalone public pages (auth, marketing, legal,
shared links), `AdminPage` for `/admin/*`, and shared components/styles reused by
the IDE. The legacy V2 sidebar and V3 top-bar shells were deleted; the old production
Signal chrome was ported into IDE-native surfaces and removed in the 2026-05-23
cleanup.

### Route table

The router lives in
[`frontend/src/App.tsx`](../frontend/src/App.tsx). The protected shell is
[`frontend/src/console/ConsoleShell.tsx`](../frontend/src/console/ConsoleShell.tsx).
Heavy protected areas are lazy-loaded so the run loop remains in the entry path.
Highlights:

- **Public**: `/login`, `/register`, `/welcome`, `/pricing`, `/trust`, `/terms`,
  `/privacy`, `/dpa`, `/shared/:token`, `/reports/shared/:token`, `/forgot-password`,
  `/reset-password`, `/accept-invite`, `/auth/callback`.
- **Protected** (under `<ProtectedRoute>` + Console shell):
  - Run loop: `/home`, `/runs`, `/compose`, `/plan/:runId`, `/live/:runId`,
    `/result/:runId`.
  - Project pages: `/projects`, `/projects/:projectId` (Compose default landing),
    `/projects/:id/pulse`, `/repos`, `/runs`, `/code`, `/chat`, `/activity`,
    `/members`, `/architecture`, `/settings`.
  - Account: `/account`, `/billing`, `/plans`, `/topup`, `/integrations`,
    `/api-keys`, `/org-members`, `/org/settings`.
  - Cross-cutting: `/insights`, `/library`, `/migrations`, `/templates`, `/audit`,
    `/deployments`, `/notifications`, `/search`, `/more`, `/admin`.
- **Legacy redirects**: `/signal/*`, `/v3/*`, `/v4/*`, `/p/*`, `/dashboard`,
  `/exec`, `/team` etc. are absorbed by `LegacyRedirect` and rewritten.

### Primitives and surfaces

Console components live under [`frontend/src/console/`](../frontend/src/console/):
`ConsoleShell`, `ConsoleHome`, and `ConsoleDecisions`. Route-level protected
surfaces live under [`frontend/src/ide/`](../frontend/src/ide/): `IdeSurfaces`,
`IdeRunResult`, `IdePlan`, `IdeProject*`, `IdeSystem`, `IdeDiscovery`,
`IdeBilling`, `IdeOrg`, and `IdeSettings`. Shared
Signal-era primitives such as `Markdown`, review panels,
modals, auth layout, and public-page CSS remain in `frontend/src/signal/`.

### Telemetry

[`frontend/src/main.tsx`](../frontend/src/main.tsx) calls `initTelemetry()` before
render — instruments all `fetch()` calls. Service worker (`/sw.js`) registered in
prod only. Theme bootstrap supports the IDE/Signal token stack before React paint.

Design tokens at
[`frontend/src/tokens.css`](../frontend/src/tokens.css) — `:root` declares dark
defaults; `[data-theme='light']` overrides for light. The 2026-05-14 UX overhaul
added role-named surface tokens (`--surface-page`, `--surface-card`,
`--surface-raised`, `--surface-sunken`), Apple-HIG motion vocabulary
(`--motion-press`, `--motion-tap`, `--motion-reveal`, `--motion-pulse`), and hit
target floors (44px mobile, 32px desktop).

---

## 6. Storage

### PostgreSQL (Cloud SQL in prod)

Schema is managed by Flyway. Migration head in the current worktree is `V250`. All migrations live in
[`src/main/resources/db/migration/V*.sql`](../src/main/resources/db/migration/).
Hibernate is configured with `ddl-auto=validate` — Flyway is the single source of
truth.

Connection pool: HikariCP, `maximum-pool-size=30`, `minimum-idle=5`,
`leak-detection-threshold=90000`. `spring.jpa.open-in-view=false`. Hibernate
`connection.handling_mode=DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION`.

There are 127 entity/table classes. The hottest table is
[`pipeline_run`](../src/main/java/com/navneet/executespec/state/entity/PipelineRun.java)
(597-line entity covering plan/review JSON, harness version, run mode, per-agent
token counters, cost estimates, batch state, hierarchical-planner phase costs,
ownership/RBAC, SCM fields (GitHub + Bitbucket), GCS archive markers, pause/resume
state, soft-delete tombstone). All mutations flow through
[`state/StateStore`](../src/main/java/com/navneet/executespec/state/StateStore.java)
(2143 lines; the single mutation API).

Tenant scoping: 12+ entities are tenant-aware via
`@EntityListeners(TenantInterceptor.class)` — `pipeline_run`, `agent_task`,
`agent_message`, `agent_inbox`, `run_repo`, `integration_run`,
`integration_event_log`, `gate_event`, `run_recovery`, `deployment`, and others.
Tenant id defaults to `"default"` and is propagated by AOP.

### Redis (Lettuce client)

Local config defaults to `localhost:6379`. Prod uses GCE VM redis. Lettuce pool
sized at `max-active=50, min-idle=5, test-on-borrow=true, test-while-idle=true`.

Redis is used for:

- **SSE pub/sub fanout** for cross-instance horizontal scale —
  [`sse/SseBroadcaster`](../src/main/java/com/navneet/executespec/sse/SseBroadcaster.java).
  Channel prefix `sse:`. Replay log at `sse:replay:{runId}` (capped 500 events, 24h
  TTL). Self-origin echo suppression via envelope `{"from": instanceId, "data": ...}`.
- **Gate signalling** —
  [`harness/GateStore`](../src/main/java/com/navneet/executespec/harness/GateStore.java).
  Plan gates at `gate:plan:{runId}` (4h TTL). DB fallback via `gate_record` on Redis
  outage.
- **Rate-limit counters** —
  [`ratelimit/RateLimitFilter`](../src/main/java/com/navneet/executespec/ratelimit/RateLimitFilter.java).
  Per-identifier counters at `rl:{group}:{identifier}` (60s window). Per-tier cache
  at `rl:tier:{userId}` (2-min TTL). Fail-open at 50% capacity on Redis outage.
- **Distributed locking** — ShedLock 7.7.0 with the Redis Lettuce provider. Used by
  `WorkspaceDiskCleanupJob`, `StaleScmCleanupJob`, and other `@SchedulerLock`
  jobs.
- **Per-run worker context snapshots** at `v2:agent-ctx:{runId}:{taskId}` (24h TTL)
  — written every 5 iterations by `WorkerAgent`.
- **BYOK key cache** —
  [`agent/RunKeyContextStore`](../src/main/java/com/navneet/executespec/agent/RunKeyContextStore.java)
  uses an in-process Caffeine cache (1024 × 6h TTL), backed by `RunKeyStore`
  Redis (AES-256-GCM encrypted, 2h TTL, key prefix `runkey:`) for cross-instance
  recovery.
- **Provider circuit breaker** — `ProviderHealthTracker` at `provider:health:{NAME}`
  (180s recovery window) and `provider:failures:{NAME}` (300s counter TTL).
- **Plan strategy circuit breaker** — `PlanStrategyCircuitBreaker` at
  `planner:strategy:open:{strategy}` and `planner:strategy:fail:{strategy}` (1h
  windows, 5-failure threshold).
- **Provider pin** — `RunProviderPinStore` at `run:provider:pin:{runId}` (2h TTL,
  first-write-wins).
- **Apply-suggestion lifecycle** at `v2:apply:result:{runId}` (1h TTL) —
  `ApplyResultStore` so the UI can poll `GET /api/runs/{id}/apply/status` even
  after the original POST stream dies.
- **Webhook idempotency** — `integration_event_log` table (idempotency keyed by
  `(source_type, event_id)`, pruned daily).
- **Skip-push flag** at `run:skip-push:{runId}` (24h TTL, atomic get-and-delete).
- **Classifier cache** at `planner:classify:{sha256(spec+repos+projectId)}` (1h TTL).
- **Gate signalling key collision detection** — `setIfAbsent` (SET NX) semantics
  used in several places.

---

## 7. LLM model resolution

YAML is the *single source of truth* for every model identifier, context window,
max-output, pricing rate, display label, fallback chain, free-tier lock, agent→tier
map, and provider-default model. Configuration is in
[`src/main/resources/application.yml`](../src/main/resources/application.yml) under
the `model-matrix.*` tree, bound to
[`router/ModelMatrixProperties`](../src/main/java/com/navneet/executespec/router/ModelMatrixProperties.java).

[`router/ModelRegistry`](../src/main/java/com/navneet/executespec/router/ModelRegistry.java)
reads the matrix and exposes:

- `canonicalize(name)` — resolves legacy aliases.
- `providerFor(name)`, `contextLimitFor(name)`, `maxOutputForModel(name)`,
  `pricingFor(name)`, `displayLabelFor(name)`.
- `fallbackChainFor(name)` — returns provider/model pairs for the model's
  chain-tier (ARCHITECT, CAPABLE, UTILITY).
- `workhouseModelFor(provider)` / `planningModelFor(provider)` /
  `lightweightModelFor(provider)`.

A `@PostConstruct validate()` boots-fast-fails if a referenced model is missing.

[`router/ModelResolver.resolve(agentName, userProvider)`](../src/main/java/com/navneet/executespec/router/ModelResolver.java)
is the public entry. Resolution chain:

1. Map agent simple-name → `AgentTier` via `model-matrix.agent-tier-map`.
2. Pick model from `model-matrix.providers.<userProvider>.<tier>`.
3. If the user's provider is unhealthy (circuit-breaker via
   `ProviderHealthTracker`), failover through `model-matrix.failover-order`.
4. If adaptive routing is enabled (`routing.adaptive-routing.enabled` + JVM strict
   `-Dexecutespec.strict-provider=false`),
   `ModelPerformanceTracker` can substitute a provider with ≥10pp better score over
   the last 24h sliding window (5-sample minimum).

Agent → tier mapping (excerpt; `application.yml` is authoritative):

| Tier        | Anthropic                   | OpenAI         | Gemini             | DeepSeek                    | Fireworks                 | Agents                                                                                                                             |
| ----------- | --------------------------- | -------------- | ------------------ | --------------------------- | ------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| PLANNING    | `claude-sonnet-4-6`         | `gpt-5.4`      | `gemini-3.5-flash` | `deepseek-v4-flash-native` | `deepseek-v4-pro-fw`      | OutlinePlannerAgent, ReviewerAgent, RepoArchitectureGenerator, ArchitectureDocGenerator, ProjectChatCompiler, PrReviewAgent, CodeReviewAgent |
| WORKHORSE   | `claude-sonnet-4-6`         | `gpt-5.4`      | `gemini-3.5-flash` | `deepseek-v4-flash-native` | `deepseek-v4-pro-fw`      | WorkerAgent, TaskExpanderAgent, TestPlannerAgent, TestWorkerAgent, ApiTestSuiteAgent, ValidationReportGenerator                    |
| LIGHTWEIGHT | `claude-haiku-4-5-20251001` | `gpt-5.4-mini` | `gemini-3.5-flash` | `deepseek-v4-flash-native` | `deepseek-v4-flash-fw`    | SmartClassifierAgent, CoherencePlannerAgent, ContextAgent, RunLedgerAgent, RunTitleSummarizer, ConversationAgent                   |

Direct providers are Anthropic, OpenAI, Gemini, and native DeepSeek. Aggregator
providers are OpenRouter and Fireworks: OpenRouter can route the verified
`OpenRouterModelMapper` allow-list and now sends `provider.sort=latency`; Fireworks
can route the verified `FireworksModelMapper` allow-list once a Fireworks key is
configured. `RunCostTracker` prices literal OpenRouter/Fireworks matrix rows first
and falls back only for legacy OpenRouter-routed direct-provider models.

Per-provider implicit-cache is enabled — Gemini Flash threshold 1024 tokens, Pro
4096; Anthropic uses explicit `cache_control` blocks. `RunCostTracker` records
`cache_read_input_tokens` on every call for providers that expose it. Reviewer routes
Gemini to WORKHORSE (Flash) because Pro's 4096 threshold misses every implicit-cache
hit on the reviewer's drifting system prompt.

Two known gaps:

- **Bug #1c (router strict-provider bypass)** —
  `ModelResolver.selectAdaptiveProvider` can silently switch providers based on
  historical success rate even when `-Dexecutespec.strict-provider=true` is set.
  The JVM-flag check is implemented but the order of guards has a small hole; fix
  is deferred.
- **Planner model sourced from run model, not tier** — `OutlinePlannerAgent`
  constructs LLM requests using `req.model()` (the run-level
  `defaultModelFor(provider)`, which is haiku for anthropic) rather than
  `ModelResolver.resolve("OutlinePlannerAgent", provider).getModel()`. Editing
  `anthropic.planning` in YAML has no effect on the planner today. Tracked.

---

## 8. Workspace and multi-repo model

Per-run workspaces live on local disk at `<agent.workspace>/<runId>/`. Default
`agent.workspace` is `./workspace`. Workspace lifecycle is owned by
[`harness/WorkspaceInitService`](../src/main/java/com/navneet/executespec/harness/WorkspaceInitService.java).

Single-repo run:

```
workspace/<runId>/
├── .git/                  (initialised by ensureRunWorkspace)
├── .gitignore             (target/, node_modules/, etc.)
├── .gitattributes         (README.md merge=union, CHANGELOG.md merge=union)
└── <source files>
```

Multi-repo run (linked via `RunRequest.selectedRepoIds`):

```
workspace/<runId>/
├── .git/                  (outer wrapper repo)
├── repos/
│   ├── service-api/
│   │   ├── .git/
│   │   └── <source>
│   ├── service-frontend/
│   │   ├── .git/
│   │   └── <source>
│   └── ...
└── (no top-level source)
```

Per-repo clone SHAs are captured in
[`run_repo_snapshot`](../src/main/java/com/navneet/executespec/state/entity/RunRepoSnapshot.java)
(V100) and used by
[`run/sync/RunSyncService`](../src/main/java/com/navneet/executespec/run/sync/RunSyncService.java)
for `POST /api/runs/{id}/sync/original`.

Multi-repo summary (a markdown header listing each `repos/<name>/` subdir) is built
by `RunOrchestrator.buildMultiRepoSummary` and threaded into
`ExecutionRequest.workspaceSummary` so the worker's system prompt knows the layout.
`BuildCommandResolver.wrapForReposSubdirIfNeeded` handles per-subdir build dispatch
(e.g. `cd repos/api && mvn verify`).

Multi-repo push is handled by `ScmPushService.pushMultiRepo`: each linked repo is
pushed and PR'd separately, then cross-linked by patching each PR body with the
sibling list via `GitHubRepoService.updatePullRequestBody`. The representative PR
URL is stamped on `pipeline_run.github_pr_url`; the full per-repo list is at
`GET /api/runs/{id}/prs` via `pull_request_record`.

Disk is ephemeral on Cloud Run and may be wiped on GCE VM restart. DB blobs are
authoritative. Long-term archive is handled by `GcsWorkspaceStore` (GCS bucket
`executespec-workspaces`, archive-after 24h, retention 30d) when
`agent.deployment.workspace.gcs-enabled=true`. Daily disk cleanup is performed by
[`compliance/WorkspaceDiskCleanupJob`](../src/main/java/com/navneet/executespec/compliance/WorkspaceDiskCleanupJob.java):
ORPHAN (no DB row), TERMINAL_OLD (>14d after terminal), TOMBSTONED (deleted/archived
>30d).

Workspace path safety is enforced by
[`tool/PathSafety.safeResolve`](../src/main/java/com/navneet/executespec/tool/PathSafety.java)
and
[`tool/FileSandboxPolicy`](../src/main/java/com/navneet/executespec/tool/FileSandboxPolicy.java).
The sandbox layers MEDIUM/HIGH restrictions on top of workspace-root containment;
LOW is a no-op. MEDIUM allows known build files (package.json, pom.xml, etc.) and
allow-listed directories (src, test, tests, public, static, etc.). HIGH narrows
allowed dotfiles to a tiny whitelist (`.env.example`, `.gitignore`,
`.gitattributes`, `.dockerignore`).

---

## 9. Observability

Micrometer + OpenTelemetry pipeline. Local dev exports to Prometheus + Tempo +
Grafana via `observability/docker-compose.yml`. Prod GCP exports to Cloud Trace +
Cloud Monitoring via `com.google.cloud.opentelemetry:exporter-trace` and
`exporter-metrics` (`@Profile("gcp")` activates them; `application.yml` `gcp`
profile section toggles `tracing.enabled=true` + `metrics.export.enabled=false`).

### Custom metrics

All under the `executespec.v2.*` namespace. Bundled in
[`observability/Metrics`](../src/main/java/com/navneet/executespec/observability/Metrics.java):

- `run.outcome{overall}`, `run.latency{overall}`.
- `planner.latency{classification,provider,model}`, `planner.tasks{classification}`.
- `task.status`, `task.latency`, `task.smoketest{result}`.
- `worker.iterations{outcome,provider,model}`, `worker.outcome`,
  `worker.toolcall{tool}`.
- `llm.latency{provider,model,outcome}`,
  `llm.key_source{provider,source}` (V165 BYOK attribution).
- `classifier.verifier.engaged{result}`.
- `reviewer.verdict`.
- `context.save{result}`, `context.save.bytes`.

Other counters:

- `executespec.redis.connection_failures`, `executespec.redis.failopen`.
- `executespec.gate.db_fallback`.
- `executespec.provider.circuit_opened`, `executespec.provider.circuit_closed`,
  `executespec.provider.outage.queued_runs` (gauge).
- `executespec.routing.adaptive_switch{from,to,agent}`,
  `executespec.routing.failover{from,to,agent}`.
- `executespec.workspace.disk.reaped{category}`,
  `executespec.scm.cleanup.{closed,deleted,skipped}{kind}`.
- `executespec.data.retention.deleted_runs{trigger}`,
  `executespec.data.retention.deleted_projects{trigger}`.
- `executespec.sse.emitters.active` (gauge).
- `executespec.llm.rate_limited`.

### W0 per-call LLM usage attribution

Beyond the aggregate counters, every successful LLM call is recorded durably for
distributed-execution telemetry (RL-2026-06-043 / RL-2026-06-044).
[`cost/RunStepUsageRecorder`](../src/main/java/com/navneet/executespec/cost/RunStepUsageRecorder.java)
writes a [`RunStepUsage`](../src/main/java/com/navneet/executespec/cost/RunStepUsage.java) row
(`run_step_usage`, V243) per call carrying run/task/provider/model, input/output/cache-read/
cache-write/reasoning tokens, cost, and `call_index`, plus exact producer attribution
(`agentRole`, `stepId`, `retryOf`) drawn from the `LlmRequest`. Same-provider retries and
cross-provider failover rows point back to the original role/step. The write is its own
`REQUIRES_NEW` transaction and fail-soft (a failed insert is logged at debug and never breaks the
run). The table has no FK to `pipeline_run` because the same router path also serves agent
sessions, benchmarks, and local/dev diagnostics; raw provider-usage JSON is reserved for a later
phase.

[`sse/RunEvent`](../src/main/java/com/navneet/executespec/sse/RunEvent.java) carries an additive
event envelope (`schemaVersion=1` + a typed `data` map) alongside the legacy string `payload`, so
existing consumers keep working while richer fields ride along: `TOKEN_USAGE` includes typed
cost/cache/reasoning/call fields, `TOOL_CALL` includes typed remote-tool fields,
`TASK_WAVE_PLAN` exposes the advisory bounded safe-parallelism plan while execution remains
sequential, and `GOAL_DECISION` / `CONTINUATION_DECISION` expose task-boundary goal state,
recommended next step, and next-action advice.
Continuation decision data also carries the applied `execution.continuation.*` advisory limits so
live clients can explain which thresholds and ceilings shaped the recommendation. Goal decision
evidence includes RunCostTracker cost/token/cache/reasoning fields, task wall-clock, configured
no-progress/false-done/repeated-failure/edit-churn thresholds, conservative environment-block
and unsafe-continuation signatures, and concrete budget-ceiling reasons when configured
cost/token/time ceilings are crossed before proof of completion; those fields are structured in SSE
data, durable task JSON, the run-detail API, and the IDE Steps tab.
Run submit can override the benchmark-confidence guard for one run through
`RunRequest.requireBenchmarkPriorForAutomaticContinuation`; the default still requires compatible
benchmark evidence before automatic unapproved upgrades, while disabling it lets the selected
quality-ranked candidate auto-run inside the normal provider/model ceilings.
Repeated assistant explanations without new file-write or validation proof are recorded as
`staleExplanationCount=current/max` using `execution.continuation.max-stale-explanations`.
For run-scoped agent sessions, `AgentRunEventBridge` mirrors the typed task-wave and
goal/continuation run events into `agent_action_event` rows (`RUN_TASK_WAVE_PLAN`,
`RUN_GOAL_DECISION`, `RUN_CONTINUATION_DECISION`, `RUN_CONTINUATION_ACTION`,
`RUN_CONTINUATION_ACTION_SKIPPED`) and
`ValidationReportGoalProofEvaluator` maps the final validation report ledger into the same
`GoalDonenessDecision` contract, including `recommendedNextStep`. `/api/runs/{id}/report` exposes
that `runGoalDecision` beside the raw ledger, the Evidence tab renders both, and
`AgentValidationReportEventBridge` mirrors the ledger plus that proof as `RUN_VALIDATION_EVIDENCE`,
so web/CLI/VS Code clients can reload the same decision and evidence trail through
`/api/agent/sessions/{id}/events`.
When selected-candidate continuation acting is enabled, `CONTINUATION_ACTION` marks the actual
selected-candidate same-task rerun with decision, provider, model, reason, bounded attempt metadata,
and consumed limit metadata. If runtime ceilings block an otherwise actionable recommendation,
`CONTINUATION_ACTION_SKIPPED` carries the selected candidate plus the exhausted limit name and
used/max counts.
`GET /api/runs/{id}/cost` reflects this for live `v2` runs — it reads `RunCostTracker.snapshot` and
returns `schemaVersion`, `source` (`live_tracker` / `stored_total` / `legacy_estimator`),
`cacheReadTokens`, `cacheWriteTokens`, `reasoningTokens`, and `calls` in addition to cost and token
totals.

### Endpoints

- `/actuator/prometheus` — Prometheus scrape.
- `/actuator/health` — Boot health probe (used by readiness checks).
- `/api/health` —
  [`HealthController`](../src/main/java/com/navneet/executespec/api/controller/HealthController.java)
  custom liveness probe with 30s background-cached DB/Redis check (always 200 if
  JVM alive); `/api/health/ready` inline DB check (503 on DB down).
- `/api/v2/test/metrics` — internal metric dump.

### SLOs

5 published SLOs in [`docs/operations/slos.md`](operations/slos.md):

1. Run terminal in 15 min ≥ 95%.
2. Verified-score correctness ≥ 90%.
3. Cost estimate within 30% on ≥ 80% of runs.
4. Webhook handled within 60s ≥ 99%.
5. Hot-path 5xx < 0.5%.

---

## 10. Security model

8-layer defense-in-depth:

1. **Request validation** — Bean Validation on every `@RequestBody`. Jakarta
   `@NotBlank`, `@Size`, `@Pattern`, `@DecimalMin/Max`. See
   [`api/dto/RunRequest`](../src/main/java/com/navneet/executespec/api/dto/RunRequest.java)
   — `userRequest` capped at 50,000 chars, `repoUrl` restricted to HTTPS GitHub/
   GitLab/Bitbucket only (closes adversarial-test finding 7).
2. **Input sanitization** — `InputSanitizer` runs NFKC normalisation + zero-width
   character strip + base64 decode-and-scan before persisting `userRequest`. Spec
   field of `IntegrationManager.sanitizeAndCap` strips prompt-injection patterns
   and caps each field at 4,000 chars.
3. **LLM prompt defense** — system prompts include sandbox rules, tool-use rules,
   no-exfil rules. Strict JSON schemas on every tool entry in `tool/`.
4. **Code abuse scanning** — `CodeAbuseScanner` integrated into `FileSystemTool`
   detects crypto patterns, shell payloads, PowerShell, DNS exfil, cloud-metadata
   probes, and obfuscation. Blocks write.
5. **Shell sandbox** —
   [`tool/ShellSandboxPolicy`](../src/main/java/com/navneet/executespec/tool/ShellSandboxPolicy.java)
   (559 lines) splits chained commands and validates each segment against an
   allowlist of first-token verbs (mvn, gradle, npm, npx, node, python, etc.).
   BLOCKED_COMMANDS deny rm -rf /, mkfs, dd if=, fork bombs, chmod -R 777 /,
   reboot/shutdown/halt, iptables, ufw, useradd/del/mod, passwd, ncat, etc.
   BLOCKED_PATTERNS catch `curl | bash`, writes to `/etc /usr /var`, ssh/scp to
   remote, python socket import, base64 | bash, docker `--privileged`,
   stratum+tcp/known miners, /dev/tcp/, nc -e /bin/. Three guardrail levels (LOW /
   MEDIUM / HIGH) tighten the allowlist via `security.guardrail_level` flag.
   MEDIUM adds `\beval\b`, `\bexec\b` deny.
6. **File sandbox** —
   [`tool/FileSandboxPolicy`](../src/main/java/com/navneet/executespec/tool/FileSandboxPolicy.java)
   + `PathSafety.safeResolve` enforce workspace-root containment (lexical +
   symlink). MEDIUM allowed-dirs list and allowed-root-files list. HIGH tightens.
7. **Process isolation** — virtual threads on `gcp` profile; `BuildProjectTool` and
   `ShellTool` use `ProcessBuilder` with env whitelist (PATH, HOME, DOCKER_HOST
   only). Hard timeouts at 30-300 seconds per tool.
8. **Container hardening** — cleanroom containers (SWE-bench, ProgramBench) run
   with `--rm --network none -v <hostDir>:/workspace`. Test execution containers
   run with `--read-only --cap-drop ALL` + memory/CPU limits + env-var whitelist.
   Terminal-Bench task containers are owned by the Harbor runner; ExecuteSpec only
   brokers remote tool calls/results for that surface.

Additional surfaces:

- **Auth** — `SecurityConfig` configures JWT (access 1h, refresh 7d) + OAuth2
  (Google, GitHub). OAuth authorization requests use signed, state-keyed,
  short-lived HttpOnly cookies (`CookieOAuth2AuthorizationRequestRepository`) so
  provider callbacks do not depend only on Tomcat session lookup. Scoped SSE tokens
  (5-min TTL, `scope=sse` + `runId` claim) protect event streams. JWT secret via
  `app.jwt.secret` (env var).
- **Rate limiting** —
  [`ratelimit/RateLimitFilter`](../src/main/java/com/navneet/executespec/ratelimit/RateLimitFilter.java)
  (`OncePerRequestFilter`) gates `/api/**`. Endpoint group classification (AUTH /
  WEBHOOK / RUNS / READ / WRITE / PREVIEW / DEPLOY) × tier (FREE / PRO /
  ENTERPRISE) × guardrail-level multiplier (LOW=×1, MEDIUM=×0.5 for write/deploy/
  preview, HIGH=×0.25 everywhere). Identifier: userId for authenticated, rightmost
  X-Forwarded-For IP for unauthenticated (leftmost is client-spoofable).
- **CORS** — `app.cors.allowed-origins` per-profile. Prod allows
  `https://executespec.ai`, `https://www.executespec.ai`,
  `https://app.executespec.ai`, `https://build.executespec.ai`.
- **Headers** — CSP, HSTS (max-age 31536000, include-sub-domains), X-Frame-Options
  (SAMEORIGIN) configured via
  [`config/SecurityProperties`](../src/main/java/com/navneet/executespec/config/).
- **Webhook signature verification** — all inbound webhooks verify HMAC-SHA256
  with constant-time compare via `MessageDigest.isEqual`. GitHub
  (`/webhooks/github`), Razorpay (`/api/webhooks/razorpay`), Stripe
  (`/api/webhooks/stripe`), Jira, Confluence, ServiceNow, PagerDuty, Bitbucket,
  Slack.
- **Access guards** — `RunAccessGuard`, `ProjectAccessGuard`, `OrgAccessGuard`,
  `GovernanceGuard` enforce tenant isolation. `AccessGuardCoverageTest`
  (ArchUnit) pins every controller method whose URL touches `/runs/{` /
  `/projects/{` / `/executions/{` / `/launches/{` / `/repos/{` to call a tenant
  access guard. `KNOWN_LEGACY_DRIFT` is now empty; new violators fail the build.

---

## 11. Background jobs

`@Scheduled` jobs in the codebase, plus `@SchedulerLock` notes for distributed
locking via ShedLock 7.7.0 (Redis provider). Distributed lock means the job runs
on exactly one JVM in the cluster per fire.

| Job                                    | Schedule                | Lock | Source                                                                                                              | Purpose                                                                                       |
| -------------------------------------- | ----------------------- | ---- | ------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| `WorkspaceDiskCleanupJob.cleanup`      | `0 30 3 * * *`          | yes  | [`compliance/WorkspaceDiskCleanupJob`](../src/main/java/com/navneet/executespec/compliance/WorkspaceDiskCleanupJob.java) | Daily 03:30. Sweeps orphan/old/tombstoned workspace dirs, max 5k per pass.                    |
| `StaleScmCleanupJob.cleanup`           | `0 45 3 * * *`          | yes  | [`compliance/StaleScmCleanupJob`](../src/main/java/com/navneet/executespec/compliance/StaleScmCleanupJob.java)      | Daily 03:45. Closes stale PRs (>14d) and deletes stale `executespec/*` branches (>30d).       |
| `DataRetentionService.runRetentionSweep` | `0 0 3 * * *`        | no   | [`compliance/DataRetentionService`](../src/main/java/com/navneet/executespec/compliance/DataRetentionService.java)  | Daily 03:00. Soft-deletes expired runs per tier_config, hard-deletes after 7d grace, purges archived runs >90d. |
| `IntegrationEventLogCleanupJob.pruneOldEvents` | `0 0 3 * * *`  | no   | [`integration/IntegrationEventLogCleanupJob`](../src/main/java/com/navneet/executespec/integration/IntegrationEventLogCleanupJob.java) | Daily 03:00. Prunes webhook idempotency entries >7d old.                                      |
| `SlackNotificationLogCleanupJob.cleanup` | `0 0 4 * * *`         | no   | [`integration/slack/SlackNotificationLogCleanupJob`](../src/main/java/com/navneet/executespec/integration/slack/SlackNotificationLogCleanupJob.java) | Daily 04:00. Prunes `slack_notification_log` rows >90d old.                                  |
| Cloud Run preview cleanup              | `agent.deployment.cleanup-cron` (`0 3 * * * *`) | no | `agent.deployment.*`     | Hourly cleanup of expired Cloud Run preview revisions.                                        |
| `ProviderStatusService.pollStatusPages` | every 60s             | no   | [`router/ProviderStatusService`](../src/main/java/com/navneet/executespec/router/ProviderStatusService.java)        | Polls Anthropic / OpenAI / Google status JSON endpoints, writes `provider:status:{NAME}` to Redis (TTL 90s). |
| `StartupRunResumer.onApplicationReady` | `ApplicationReadyEvent` | no | [`recovery/StartupRunResumer`](../src/main/java/com/navneet/executespec/recovery/StartupRunResumer.java)            | Boot-time recovery of mid-flight `v2` runs. Phase 2 replay via `RunOrchestrator.resumeAfterCrash`. |
| `RunTitleBackfillService.backfillTitles` | `ApplicationReadyEvent` | no | [`state/RunTitleBackfillService`](../src/main/java/com/navneet/executespec/state/RunTitleBackfillService.java)      | One-shot backfill of `run_title`/`run_summary` from `plan_json` for legacy pre-V36 runs.      |

Plus per-feature heartbeats and pollers:

- `PerkyMessenger` (`integration/PerkyMessenger`) — every 15s during long phases,
  emits SSE `LOG` events to keep proxies' idle timeouts warm. Daemon thread pool
  size 2.
- Operator Telegram bridge — heartbeat every 600s, `getUpdates` poll every 120s.
  Chat 6475810501. Out-of-band channel only; not customer-facing.

---

## 12. Build and deploy

Pointer only. Full instructions in [`docs/DEPLOYMENT.md`](DEPLOYMENT.md).

Local build:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn verify -q
npm --prefix frontend run build
```

Spotless 3.4.0 (`com.diffplug.spotless:spotless-maven-plugin`) is bound to the
`verify` phase via the `spotless-check` execution — formatting must pass before
the build succeeds. Google Java Format 1.22.0 with style `GOOGLE`,
`reflowLongStrings=true`, `removeUnusedImports`, import order `java, javax,
jakarta, com, org`.

Tests excluded by default: `e2e`, `integration`, `chaos` groups. Run integration
with `-Pintegration`.

Application coordinates: `com.navneet:executespec:1.0.0-M1`, packaging `jar`,
Spring Boot Parent `3.5.14`, Java 21. Single artifact built by
`spring-boot-maven-plugin`. Frontend is bundled into `static/`.

Local Grafana stack: `cd observability && docker compose up -d` →
[http://localhost:3001](http://localhost:3001) (admin/admin), Prometheus :9090,
Tempo :3200.

Deploy targets:

- **prod** — GCE VM in `us-east4`. Profile `prod`. `BILLING_DEFAULT_GATEWAY=stripe`,
  Cloudflare in front of `*.executespec.ai`.
- **dev** — same GCE VM but separate service. Profile `dev`. Domain
  `dev.executespec.ai`. Razorpay as default billing.
- **previews** — Cloud Run, 5-min TTL, separate `preview-engine` deployment.
- **local** — `application-local.yml` profile, postgres/postgres credentials,
  `localhost:6379` redis.

See [`docs/DEPLOYMENT.md`](DEPLOYMENT.md) for full env-var matrix and the GCP
deployment recipe.
