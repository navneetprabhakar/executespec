# ExecuteSpec — Architecture

Version `1.0.0-M1`. Flyway head **V199**. Source-of-truth references are file paths; if this doc and the code disagree, the code wins.

Sibling docs: [`CODEMAP.md`](CODEMAP.md) (mechanical index), [`document_map.md`](document_map.md), [`RBAC.md`](RBAC.md), [`DEPLOYMENT.md`](DEPLOYMENT.md).

---

## 1. System overview

```
                                  +-----------------------------+
                                  |   Customer / Operator       |
                                  |  (browser, GitHub webhook,  |
                                  |   Stripe / Razorpay)        |
                                  +--------------+--------------+
                                                 |
                                                 v
                  +------------------------------+-----------------------------+
                  |                Spring Boot single JAR (:8080)              |
                  |                                                            |
                  |  +---------------------+    +----------------------------+ |
                  |  | REST controllers    |    |  Static frontend assets    | |
                  |  | /api/runs           |    |  (built React, served      | |
                  |  | /api/projects/...   |--->|  from /resources/static)   | |
                  |  | /api/billing/...    |    +----------------------------+ |
                  |  | /webhooks/github    |                                   |
                  |  | (98 controllers)    |                                   |
                  |  +----------+----------+                                   |
                  |             |                                              |
                  |             v                                              |
                  |  +----------+-----------+   +---------------------------+  |
                  |  | services / agents    |   | tools/*  (16 files)       |  |
                  |  | (125 *Service, 17    |-->| readFile / writeFile /    |  |
                  |  |  *Agent)             |   | editFile / shell / git*   |  |
                  |  +----------+-----------+   | / buildProject / runTests |  |
                  |             |               | / completeTask            |  |
                  |             v               +-------------+-------------+  |
                  |  +----------+-----------+                 |                |
                  |  | LlmRouter (V2)       |                 |                |
                  |  | + FallbackLlmRouter  |                 |                |
                  |  | + OpenAi / Gemini    |                 |                |
                  |  |   NativeToolClient   |                 |                |
                  |  +-+--------+--------+--+                 |                |
                  |    |        |        |                    |                |
                  +----|--------|--------|--------------------|----------------+
                       |        |        |                    |
                       v        v        v                    v
                +------+--+ +---+----+ +-+------+   +---------+-------------+
                |Anthropic| | OpenAI | | Gemini |   |  workspace/<runId>/   |
                | API     | | API    | | API    |   |  (git clones,         |
                +---------+ +--------+ +--------+   |   build artefacts)    |
                                                    +---------+-------------+
                                                              |
                                  +---------------------------+---------------------+
                                  |                                                 |
                                  v                                                 v
                       +----------+----------+                          +-----------+----------+
                       |  PostgreSQL         |                          |  Redis (Lettuce)     |
                       |  (Flyway, head V199)|                          |  gate signals,       |
                       |  ~96 @Table classes |                          |  SSE replay,         |
                       +---------------------+                          |  classifier cache,   |
                                                                        |  rate-limit buckets, |
                                                                        |  conversation snaps  |
                                                                        +----------------------+

   Side channels:
     - GitHub (PR open/refresh, check-runs, webhook /webhooks/github HMAC SHA-256)
     - Cleanroom Docker containers (SWE-bench / ProgramBench, --rm --network none)
     - GCP Cloud Run + Cloud Build (on-demand previews + permanent deployments)
     - Stripe + Razorpay webhooks (/api/webhooks/{stripe,razorpay}, HMAC verified)
```

The whole platform is a single Spring Boot 3.5.14 JAR (Java 21, Spring AI 1.1.6). The compiled React bundle is copied into `src/main/resources/static/` at build time (see `frontend/vite.config.ts` — `build.outDir`) and served from the same process; no separate frontend deployment.

---

## 2. Backend layering

Strict five-layer pipeline. Each call from a controller eventually lands on `LlmRouter` (or `FallbackLlmRouter` for the legacy non-tool agents).

```
controller  ->  service  ->  agent  ->  tool  ->  LlmRouter / FallbackLlmRouter  ->  provider SDK
```

Concrete example, the run-submit path:

| Layer | File |
|---|---|
| Controller | [`src/main/java/com/navneet/executespec/api/controller/RunController.java`](../src/main/java/com/navneet/executespec/api/controller/RunController.java) — `POST /api/runs` → `submit()` |
| Service / orchestrator | [`src/main/java/com/navneet/executespec/integration/RunOrchestrator.java`](../src/main/java/com/navneet/executespec/integration/RunOrchestrator.java) — `submitRun(...)` and the build-mode + patch-mode pipelines |
| Sequential executor | [`src/main/java/com/navneet/executespec/harness/SequentialExecutor.java`](../src/main/java/com/navneet/executespec/harness/SequentialExecutor.java) — per-task tier-routed dispatch |
| Agent | [`src/main/java/com/navneet/executespec/agent/WorkerAgent.java`](../src/main/java/com/navneet/executespec/agent/WorkerAgent.java) — multi-turn loop with native tool calls |
| Tool | [`src/main/java/com/navneet/executespec/tool/`](../src/main/java/com/navneet/executespec/tool/) — `readFile`, `writeFile`, `editFile`, `listFiles`, `searchFiles`, `shell`, `gitStatus`, `gitDiff`, `gitCommit`, `buildProject`, `runTests`, `completeTask` |
| LLM router | [`src/main/java/com/navneet/executespec/agent/LlmRouter.java`](../src/main/java/com/navneet/executespec/agent/LlmRouter.java) + `OpenAiNativeToolClient.java` + `GeminiNativeToolClient.java` |

`agent/` contains 17 `*Agent.java` files (full list in `CODEMAP.md`). `tool/` holds the `Tool` contract and 11 concrete tools + `CompleteTaskTool` + 4 facade variants (`FileLockTool`, `FileSystemTool`, `GitTool` are higher-level composites).

**Rules** (also enforced by `arch/` ArchUnit tests):
- LLM calls go through `LlmRouter` (native tool agents) or `FallbackLlmRouter` (non-tool legacy agents). No agent calls Anthropic / OpenAI / Gemini SDKs directly.
- DI is constructor-only via Lombok `@RequiredArgsConstructor`. No `@Autowired` on fields.
- Run ownership fields (`createdByUserId`, `orgId`, `projectId`) are stamped on the entity **before** the async pipeline thread starts (see `RunOrchestrator.submitRun` — every field passed explicitly into the method signature for exactly this reason).
- LLM prompts live in `src/main/resources/prompts/*.md` and are assembled via `agent/PromptAssembler.java` (resolves `{{include:<fragment>}}` markers from `prompts/fragments/` and `{{VAR}}` substitutions). Agents never call `Resource.getContentAsString()` directly.

---

## 3. Run lifecycle (`POST /api/runs` → PR)

The default flow is **BUILD** mode. Trace:

1. **`RunController.submit`** (`src/main/java/com/navneet/executespec/api/controller/RunController.java:98`). Synchronous gates:
   - `guard.requireAuthenticated` — JWT must resolve to an `AppUser`.
   - `InputSanitizer.scan` — NFKC normalize, strip zero-width chars, base64 decode-and-scan; throws on prompt-injection patterns.
   - `featureFlagService.isEnabled("submissions.paused")` — maintenance gate.
   - `TierEnforcementService.assertAndIncrementRunCount` — pessimistic-lock TOCTOU-safe monthly cap (single TX).
   - `UsageTrackingService.assertWithinLimits` — billing surface check (HTTP 402 on exceed).
   - `ApiKeyResolver.validateKeyAvailability` — in BYOK / `OWN_KEYS` mode, at least one enabled provider must have a key.
2. **`RunOrchestrator.submitRun`** persists the row (`pipeline_run.status=PLANNING`) with every pre-async field stamped, then submits the pipeline future to its dedicated `ExecutorService`. Returns HTTP `202 Accepted` with a `RunSummary` to the controller.
3. **Async pipeline thread** (build mode):
   1. Probe `pipeline_run.run_mode` — if `PATCH`, branch to `runPatchPipeline` (see §4); otherwise continue.
   2. Optional codemap (`codeintel/RunCodeMapService`) for paid tiers on existing-repo runs.
   3. `assessment/FeatureAssessmentService` heuristic + LLM-augmented assessment, attached to the run row.
   4. `SmartClassifierAgent` (LIGHTWEIGHT tier) picks `PlanStrategy ∈ {SINGLE_SHOT, SMALL, MEDIUM, LARGE, LEGACY}`. Heuristic fast-path for tiny specs; LLM verifier for the rest; Redis-cached 1h (`planner:classify:<sha>`).
   5. `OutlinePlannerAgent` (PLANNING tier) emits a `Plan` via the `submitPlan` tool. SMALL/MEDIUM use `FULL_PLAN` mode; LARGE/LEGACY use `SKELETON_ONLY`.
   6. For LARGE/LEGACY: parallel `TaskExpanderAgent` (WORKHORSE tier) over a 4-thread pipeline pool, then `CoherencePlannerAgent` (LIGHTWEIGHT tier) `patchPlan` reconciles dependencies + hard constraints.
   7. **Plan gate** — `pipeline_run.status=AWAITING_APPROVAL`. SSE event `PLAN_READY` fires. In PRODUCTION mode the thread blocks on `harness/GateStore`; in PROTOTYPE mode it bypasses. Approval (`POST /api/runs/{id}/approve`) charges credits and signals the gate.
   8. `SequentialExecutor.runAll` (`src/main/java/com/navneet/executespec/harness/SequentialExecutor.java`) walks the task list. Per-task:
      - Resolve the tier from task complexity (LOW=LIGHTWEIGHT, MEDIUM=WORKHORSE, HIGH=PLANNING).
      - Dispatch a fresh `WorkerAgent` with its `ExecutionContext` and `ToolRegistry.bindForWorker(ctx)` (gitCommit excluded — push happens at run-end).
      - Iter caps per tier: LOW=30 / 4k output, MEDIUM=50 / 8k, HIGH=75 / 16k.
      - Two resilience levers fire at end-of-budget — Lever A (force-edit watchdog at 0.7× maxIter, UserMessage so Anthropic cache stays intact) and Lever B (best-guess salvage on iter-limit / budget-exceeded / stagnation).
      - Per-task `buildProject` smoke at end.
      - Conversation snapshots persisted every 5 iters to Redis at `v2:agent-ctx:{runId}:{taskId}` (24h TTL).
   9. `ReviewerAgent` (PLANNING tier) runs the general + wiring passes (project-type aware) then `PostEditFormatter` (`reviewer/PostEditFormatter.java`) applies deterministic formatting.
   10. `report/ValidationReportService` builds the `ValidationReport`; `scoring/` computes the multiplicative `verifiedScore = clamp(0, 100, baseAccuracy × buildOK × testRatio × reviewerMultiplier − criticalPenalty)`.
   11. `integration/scm/ScmPushService.pushMultiRepo` opens the PR(s). `PullRequestSnapshot` rows persisted; `pull_request_record` table tracks state.
   12. Background test generation (`testgen/`) + auto-preview (5-min TTL) kicked off.
   13. Terminal status: `COMPLETE` / `PARTIAL` + `status_reason ∈ {CLEAN_COMPLETION, CLEAN_WITH_INTERMEDIATE_WARNINGS, FINAL_BUILD_FAILED, TESTS_FAILED:N_OF_M, REVIEWER_UNRESOLVED, REVIEWER_FAILED, ALL_TASKS_FAILED, LEGACY_UNKNOWN}` (V107).
4. **SSE stream** — `GET /api/runs/{id}/events?token=<jwt>` (scoped 5-min JWT, `scope=sse`). Event types: `PLAN_READY`, `RUN_STATUS`, `TASK_STATUS`, `LIVE_LOG`, `RUN_COST`, `EXEC_COMPENSATION_*`, `RUN_PROVIDER_SWITCHED`, `COMPLETE`. Replay buffer in Redis (`sse:replay:<runId>`).

`pipeline_run` row fields populated through this flow (entity at [`src/main/java/com/navneet/executespec/state/entity/PipelineRun.java`](../src/main/java/com/navneet/executespec/state/entity/PipelineRun.java)): `status`, `statusReason`, `plan`, `planStrategy`, `planRequirementsJson`, `costEstimate`, `actualCostUsd`, `creditsCharged`, `hardCapUsd`, `userCostCap`, `githubPrUrl`, `verifiedScore`, `runMode`, `triggeredByFinding`, plus per-phase cost columns `{classifier,outline,expansion,coherence}_{input,output}_tokens` + `_cost_usd`.

---

## 4. Two run modes — BUILD vs PATCH

[`src/main/java/com/navneet/executespec/state/RunMode.java`](../src/main/java/com/navneet/executespec/state/RunMode.java) defines the discriminator (`BUILD` / `PATCH`). Stored as a string on `pipeline_run.run_mode` (V182).

| Aspect | BUILD | PATCH |
|---|---|---|
| Default for product entry points | yes | no — used only by SWE-bench + ProgramBench + the 4 finding-autofix surfaces |
| Classifier, outline planner, coherence planner | yes | skipped |
| Plan gate (`AWAITING_APPROVAL`) | yes (PRODUCTION) | bypassed |
| Per-task split + SequentialExecutor | yes | no — single `PatchAgent` loop on the whole request |
| Reviewer pass | yes | skipped |
| Per-provider iter cap | tier-derived (30/50/75) | Anthropic 150 / OpenAI 100 / Gemini 120 |
| Test verification on `completeTask` | per-task `buildProject` smoke | `runTests` outcome must be `failed:[], errored:[], exit_code:0` within last 5 iters |
| Auto-push | per `runBuildPipeline` end | per `runPatchPipeline` terminal via `scmPushService.push()` |

The orchestrator branch lives in `RunOrchestrator.java` around line 900 — a `RunMode.isPatch(modeProbe.getRunMode())` probe routes to `runPatchPipeline(...)`.

**Four customer-facing PATCH surfaces** dispatch through one chokepoint [`finding/FindingPatchSubmitter.java`](../src/main/java/com/navneet/executespec/finding/FindingPatchSubmitter.java) (the row gets `triggered_by_finding=<id>`, V183):

| Phase | Surface | Trigger |
|---|---|---|
| 1 | `prreview/PrReviewAutofixService` | PR Review verdict popover "Autofix N comments" — pushes to PR head branch via `branchOverride` |
| 2 | `project/techdebt/TechDebtFixService` | Tech-debt panel "Fix" per category — fresh PR |
| 3 | `project/pr/CiFailureAutoFixService` | GitHub `check_run` webhook on CI failure — flag `ci.autofix.enabled`, default OFF, max 3 attempts/PR |
| 4 | `codereview/CodeReviewAutofixService` | Code Review severity chips ("N CRITICAL" / "N MAJOR") — one PR per severity |

---

## 5. Frontend layout

[`frontend/src/main.tsx`](../frontend/src/main.tsx) is the entry; [`frontend/src/App.tsx`](../frontend/src/App.tsx) holds routes and a `LegacyRedirect` collapsing every removed `/v3/*` / `/v4/*` / `/signal/*` / `/p/*` URL to the canonical Signal path.

```
frontend/src/
├── main.tsx                 entry: pre-paint .signal-app class, theme apply, telemetry init, SW register
├── App.tsx                  React Router routes — Signal is the only chrome
├── tokens.css               Graphite + Signal palette tokens, 4-band score colours
├── signal/                  the chrome
│   ├── components/Shell.tsx  shell layout (sidebar, header)
│   ├── pages/                12 top-level pages + project/* (11) + account/* (4) = 27 .tsx pages
│   ├── hooks/                SSE hook, theme hook, run polling hook
│   ├── icons/                inline SVG icon set
│   ├── lib/                  signal-internal helpers
│   └── signal.css            chrome-specific CSS
├── components/               reusable primitives: runs/, project/, billing/, auth/, execution/, shared/, design-system/
├── v3/                       retired chrome — kept only for legacy URL catching (Shell still imported nowhere from App.tsx)
├── v4/                       retired chrome — same status
├── pages/                    pre-Signal page set; some still mounted (admin/, auth/, execution/, onboarding/, org/, project/)
├── stores/                   Zustand: useAuthStore, useUiStore (theme), useRunStore, useProjectStore, useExecutionStore
├── lib/                      api.ts (single backend client), telemetry.ts (OTel), analytics.ts (PostHog), glossary.ts
└── hooks/                    shared hooks
```

The header note in `App.tsx` reads literally "Signal — the only design now. v2/v3/v4 chromes retired 2026-05-15." All current production routes mount under `<SignalShell />`. The `v3/` and `v4/` folders remain on disk only for legacy-redirect catching and a handful of demo pages.

Stores survive navigation across the legacy shell boundary so users don't lose context.

OTel browser telemetry initialises before `<App>` renders — see `frontend/src/lib/telemetry.ts`.

---

## 6. Storage

### PostgreSQL (Flyway-managed)

Head migration **V199** ([`src/main/resources/db/migration/V199__sync_org_tier_with_subscription_tier.sql`](../src/main/resources/db/migration/V199__sync_org_tier_with_subscription_tier.sql)). 199 versioned migrations total. 96 distinct `@Table` annotations across 121 `@Entity`-annotated classes (some embeddables, some inheriting). Flyway runs on boot (`spring.flyway.enabled=true`, `baseline-on-migrate=true`).

Notable entity files: [`PipelineRun`](../src/main/java/com/navneet/executespec/state/entity/PipelineRun.java), [`AgentTask`](../src/main/java/com/navneet/executespec/state/entity/AgentTask.java), [`ValidationReport`](../src/main/java/com/navneet/executespec/report/entity/ValidationReport.java), [`Project`](../src/main/java/com/navneet/executespec/project/entity/Project.java), [`Org`](../src/main/java/com/navneet/executespec/auth/entity/Org.java), [`AppUser`](../src/main/java/com/navneet/executespec/auth/entity/AppUser.java).

`spring.jpa.open-in-view=false` since v2.6.1 — connections released back to Hikari on tx commit (Hibernate `connection.handling_mode=DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION`). Hikari pool sized 30, with a 90s leak detection threshold tuned for the long async apply-suggestion path.

Flyway ignores `*:missing` to tolerate the V119 orphan from the preview-engine repo split. See the long comment in `application.yml` for the exit strategy.

### Redis (Lettuce client + ShedLock)

Single Redis instance; default `localhost:6379` (overridable via `REDIS_HOST`). Key prefixes in the code:

| Prefix | Purpose | Origin |
|---|---|---|
| `v2:agent-ctx:{runId}:{taskId}` | Conversation snapshots, 24h TTL | `context/ContextStore` |
| `v2:apply:result:{...}` | Project-chat apply-suggestion result cache | `memory/ProjectChatService` |
| `gate:plan:{runId}` | Plan-approval gate signal | `harness/GateStore` |
| `sse:replay:{runId}` | SSE event replay buffer | `sse/SseBroadcaster` |
| `planner:classify:{sha}` | Classifier result cache, 1h | `agent/SmartClassifierAgent` |
| `planner:strategy:open:{...}` / `planner:strategy:fail:{...}` | Plan-strategy circuit breaker | `agent/PlanStrategyCircuitBreaker` |
| `provider:health:{...}` / `provider:failures:{...}` / `provider:status:{...}` | Provider health tracker (legacy router) | `router/ProviderHealthTracker` |
| `run:provider:pin:{runId}` / `run:skip-push:{runId}` | Per-run flags, TTL-bound | `run/` |
| `git:lock:workspace:{...}` | Per-workspace JGit lock | `integration/scm/` |
| `project:origin-sha:{...}` / `project:post-merge:{...}` / `project:stale:{...}` / `project:ws-lock:{...}` | Project workspace coordination | `project/` + `codeintel/` |
| `rl:tier:{userId}` | Sliding-window tier rate limit | `ratelimit/RateLimitFilter` |
| `v3:anon-run:sess:{...}` / `v3:anon-run:rl:{...}` | Anonymous-trial session + rate limit | `anonrun/` |
| `preview:count:{...}` | Concurrent preview cap | `deployment/preview/` |
| `swebench:run:{...}` / `programbench:run:{...}` | Bench-mode run dispatch state | `swebench/`, `programbench/` |
| `model:stats:{...}` | Adaptive routing success-rate tracker | `router/ModelResolver` |
| `provider:model:tier` | Resolver cache | `router/` |

Distributed locking for scheduled jobs via ShedLock (`@SchedulerLock`) backed by `RedisLockProvider`.

---

## 7. Workspace model

Configured by `agent.workspace` in `application.yml`; defaults to `./workspace` locally.

```
workspace/
└── <runId>/                            owned by run; transient on Cloud Run / GCE
    ├── repos/<repoName>/               git clones for multi-repo runs
    └── (single-repo runs: clone at workspace root)
```

Multi-repo runs: each linked `ProjectRepo` is cloned into `repos/<name>/` and `WorkerAgent`'s `ExecutionContext` carries a per-task working directory. Cleanroom containers (`SWE-bench`, `ProgramBench`) bind-mount the workspace dir at `/workspace` so host-side tools and container-side tools see the same files (see `swebench/` and `programbench/`).

Project workspaces are separate: `workspace/projects/<projectId>/` (codemaps, architecture docs, generated docs) owned by `ProjectWorkspaceService` with atomic writes + Redis-backed lock (`project:ws-lock:`). Disk is ephemeral; DB blobs are authoritative — `project_code_map`, `project_architecture_doc`, `repo_architecture_doc`, `project_generated_doc` rows hold the canonical content.

GCS workspace archive (post-run) lives at `executespec-workspaces` (controlled via `agent.deployment.workspace.gcs-*` keys, off by default in local dev).

---

## 8. LLM model resolution

Single source of truth: the `model-matrix` block in [`src/main/resources/application.yml`](../src/main/resources/application.yml) (line 156). Three providers, three tiers per provider, plus a canonical model registry, fallback chains, free-tier lock, agent→tier mapping, and per-provider failover order.

Resolution chain:

```
agent name  --(application.yml agent-tier-map)-->  tier
                                                     |
provider    --(application.yml model-matrix.providers.<provider>.<tier>)-->  model id
                                                     |
model id    --(application.yml model-matrix.models.<id>)-->  context-limit, max-output-tokens,
                                                              pricing, display label, chain tier
```

Provider → tier → model wiring (today's matrix; rebalanced 2026-05-16):

| Tier | Anthropic | OpenAI | Gemini |
|---|---|---|---|
| PLANNING | `claude-sonnet-4-6` | `gpt-5.4` | `gemini-3-pro-preview` |
| WORKHORSE | `claude-sonnet-4-6` | `gpt-5.4-mini` | `gemini-3-flash-preview` |
| LIGHTWEIGHT | `claude-haiku-4-5-20251001` | `gpt-5.4-mini` | `gemini-3-flash-preview` |

Agent → tier mapping (excerpt; full table in `application.yml agent-tier-map`):

- **PLANNING:** OutlinePlannerAgent, ReviewerAgent, RepoArchitectureGenerator, ArchitectureDocGenerator, ProjectChatCompiler, PrReviewAgent, CodeReviewAgent
- **WORKHORSE:** WorkerAgent, TaskExpanderAgent, TestPlannerAgent, TestWorkerAgent, ApiTestSuiteAgent, ProjectDocGeneratorService, ValidationReportGenerator, PromptOptimiserAgent
- **LIGHTWEIGHT:** SmartClassifierAgent, CoherencePlannerAgent, ContextAgent, RunLedgerAgent, RunTitleSummarizer, ProjectChatCompactor, ProjectBriefService, ConversationAgent, TestReportGenerator

`router/ModelRegistry.java` reads the matrix at startup; `router/ModelResolver.java` does adaptive-routing decisions (Redis-backed success-rate tracking — see `model:stats:` keys). `router/AgentTier.java` enumerates `PLANNING / WORKHORSE / LIGHTWEIGHT`.

Defaults:
- Default provider: **Anthropic** (changed from OpenAI in 1.0.0-M1).
- FREE tier locked to `openai / gpt-5.4-mini`.
- Per-provider failover order at `model-matrix.failover-order`: anthropic→openai→gemini, openai→gemini→anthropic, gemini→openai→anthropic.

Provider clients:
- **Anthropic** — via Spring AI starter (`spring-ai-starter-model-anthropic`). Cache_control blocks emitted by `LlmRouter` for explicit caching.
- **OpenAI** — direct via [`agent/OpenAiNativeToolClient.java`](../src/main/java/com/navneet/executespec/agent/OpenAiNativeToolClient.java) (bypasses Spring AI's `extra_body` bug; ~43% auto-cache hit verified).
- **Gemini** — direct via [`agent/GeminiNativeToolClient.java`](../src/main/java/com/navneet/executespec/agent/GeminiNativeToolClient.java) (schema sanitization for the strict Gemini tool-spec format + thought-signature round-trip; implicit-cache prefix preservation).

`cost/RunCostTracker.java` reads pricing from `model-matrix.models.<id>.pricing` on every call (input, output, and cached-input rates per 1M tokens) and writes `run_cost_log` rows + atomic `addTokens` updates on `pipeline_run`.

The legacy [`agent/FallbackLlmRouter.java`](../src/main/java/com/navneet/executespec/agent/FallbackLlmRouter.java) survives only for non-tool-calling agents: `AccuracyAssessmentService`, `ConversationAgent`, the `testgen/` agents. Same YAML for tier resolution; separate fallback chain.

---

## 9. Observability

Spring Boot Actuator + Micrometer tracing-bridge OTel + OpenTelemetry exporters. Dependencies in `pom.xml`:

- `spring-boot-starter-actuator` — `/actuator/prometheus`, `/actuator/health`.
- `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` + `opentelemetry-sdk-extension-autoconfigure` — trace propagation.
- `micrometer-registry-otlp` + `micrometer-registry-prometheus` — metrics export.
- `com.google.cloud.opentelemetry:exporter-trace` + `:exporter-metrics` — GCP Cloud Trace + Cloud Monitoring exporters (`@Profile("gcp")`).
- `logstash-logback-encoder` — JSON logs with traceId / spanId.

Custom metrics in `executespec.v2.*`: `run.{outcome,latency}`, `planner.{latency,tasks}`, `task.{status,latency,smoketest}`, `worker.{iterations,outcome,toolcall}`, `llm.latency`, `reviewer.verdict`, `context.{save,save.bytes}`. Dump endpoint `/api/v2/test/metrics` (test profile only).

Local observability stack — [`observability/docker-compose.yml`](../observability/docker-compose.yml):
- Prometheus :9090 (scrapes `host.docker.internal:8080/actuator/prometheus`)
- Tempo :3200 (Tempo API), :4317 OTLP gRPC, :4318 OTLP HTTP
- Grafana :3001 (admin/admin, anonymous viewer enabled)

GCP profile sends traces to Cloud Trace and metrics to Cloud Monitoring directly.

Published SLOs at [`docs/operations/slos.md`](operations/slos.md): run terminal in 15min ≥95%; verified-score correctness ≥90%; cost estimate within 30% on ≥80%; webhook ack in 60s ≥99%; hot-path 5xx <0.5%.

---

## 10. Security model

Eight-layer defense-in-depth, each layer in a discrete package so failure modes are isolated.

| Layer | Code |
|---|---|
| 1. Request validation (DTO + Spring Validation) | `api/dto/*` + `@Valid` on every controller method |
| 2. Input sanitization (NFKC, zero-width strip, base64 decode-scan) | [`security/InputSanitizer.java`](../src/main/java/com/navneet/executespec/security/InputSanitizer.java) |
| 3. LLM prompt defense (system-prompt fragments) | `prompts/fragments/security-rules-planner.md`, `security-rules-worker.md` |
| 4. Code abuse scanning (crypto, shells, PowerShell, DNS exfil, cloud metadata, obfuscation) | [`security/CodeAbuseScanner.java`](../src/main/java/com/navneet/executespec/security/CodeAbuseScanner.java) |
| 5. Shell sandbox (allowlist + blocked patterns) | `tool/ShellTool.java` consulting `ShellSandboxPolicy` |
| 6. File sandbox (workspace-root jail) | `tool/FileSystemTool.java` |
| 7. Process isolation (per-task `WorkerAgent` + Redis-snapshotted context) | `agent/WorkerAgent.java` |
| 8. Container hardening (`--rm --network none --read-only --cap-drop ALL`) | `swebench/`, `programbench/`, `testgen/` runners |

Three configurable guardrail levels (LOW / MEDIUM / HIGH) resolve through [`security/guardrail/GuardrailLevelResolver.java`](../src/main/java/com/navneet/executespec/security/guardrail/GuardrailLevelResolver.java) — feature-flag key `security.guardrail_level`.

Spring Security wiring at [`auth/security/SecurityConfig.java`](../src/main/java/com/navneet/executespec/auth/security/SecurityConfig.java) defines the single `SecurityFilterChain` bean. JWT auth filter is [`JwtAuthenticationFilter.java`](../src/main/java/com/navneet/executespec/auth/security/JwtAuthenticationFilter.java); JWT secret validation at boot via [`JwtSecretValidator.java`](../src/main/java/com/navneet/executespec/auth/security/JwtSecretValidator.java); per-run access guard at [`auth/security/RunAccessGuard.java`](../src/main/java/com/navneet/executespec/auth/security/RunAccessGuard.java) + spec-based filter at [`RunVisibilitySpec.java`](../src/main/java/com/navneet/executespec/auth/security/RunVisibilitySpec.java).

`AccessGuardCoverageTest` (ArchUnit, `src/test/java/.../arch/`) pins every controller method whose URL path touches `/runs/{` / `/projects/{` / `/executions/{` / `/launches/{` / `/repos/{` to call a tenant access guard. A small `KNOWN_LEGACY_DRIFT` allowlist tracks the remaining sites; new violators fail the build.

Configurable headers (CSP, HSTS, X-Frame-Options) via `app.security.*` in `application.yml`. Frame-options default `SAMEORIGIN` so preview iframes work; `DENY` for hard lockdown.

Webhook signature verification: GitHub webhook HMAC SHA-256 at `/webhooks/github`; Razorpay webhook HMAC SHA-256 at `/api/webhooks/razorpay`; Stripe webhook via the `Stripe-Signature` header at `/api/webhooks/stripe`.

Spec-based filters (e.g. `BillingSpecs`, `RunVisibilitySpec`) compose JPA `Specification<T>` predicates so org / project / role gates are uniform across services.

---

## 11. Side-system catalogue

- **GitHub** — [`repo/GitHubRepoService.java`](../src/main/java/com/navneet/executespec/repo/GitHubRepoService.java) for clone + PR open/refresh + check-runs polling. [`repo/BitbucketRepoService.java`](../src/main/java/com/navneet/executespec/repo/BitbucketRepoService.java) for the Bitbucket variant. Webhook receiver at `/webhooks/github`.
- **Cleanroom Docker containers** — SWE-bench Lite + ProgramBench use `--rm --network none -v <hostDir>:/workspace`. `ShellTool` + `BuildProjectTool` consult `ProgramBenchContainerStore` per invocation; when a `containerId` is set for the run, dispatch via `docker exec` instead of host shell.
- **GCP** — Cloud Run (on-demand previews + permanent deployments via [`deployment/`](../src/main/java/com/navneet/executespec/deployment/)), Cloud Build, Artifact Registry, GCS workspace archive, Secret Manager. Cloud SQL Postgres is the prod DB.
- **Cloudflare** — fronts `*.executespec.ai` (TLS + WAF).
- **Stripe + Razorpay** — `BillingService` + `PaymentGatewayRouter` (currency-routed: INR→Razorpay, USD/EUR/GBP→Stripe). Webhooks at `/api/webhooks/{razorpay,stripe}`.
- **OAuth2 social login** — Google + GitHub via `spring-boot-starter-oauth2-client`. Endpoints `/api/auth/oauth2/init`, `/authorize`, `/callback/{provider}`. Auto-config excluded when client IDs blank.
- **Gmail API + SMTP** — transactional email transport; `app.mail.transport=smtp|gmail-api`. Gmail API uses installed-app OAuth2 with `LocalServerReceiver` for first-run consent.
- **Slack** — OAuth2 install + Block Kit notification path. Off by default; `slack:` flags in `application.yml`.
- **PostHog** — product analytics (6-event activation funnel). No-op when `analytics.posthog.api-key` is blank.
- **Telegram operator bridge** — push heartbeat + pull-poll inbox for out-of-band ops. Chat ID + bot configured outside the code; not a customer feature.

---

## 12. Configuration surface

[`src/main/resources/application.yml`](../src/main/resources/application.yml) — base/local defaults. Profile overlays:

- `application-dev.yml` — dev VM (GCE), activated via `--spring.profiles.active=dev`
- `application-prod.yml` — prod VM, `--spring.profiles.active=prod`
- `application-local.yml{,.yaml}` — local-only overrides, gitignored

Notable knobs:

| Block | Purpose |
|---|---|
| `model-matrix` | LLM provider × tier × model wiring (single source of truth) |
| `agent.concurrency` | `max-runs`, `max-task-duration-minutes`, `safety-iteration-cap`, `task-max-retries` |
| `agent.provider-pools` | Per-provider concurrency + queue cap (HTTP 429 on overflow) |
| `agent.cost` | `credit-multiplier-usd=5.0`, `estimation-buffer`, `budget-margin`, `max-credits-free` |
| `agent.recovery` | Auto-restart heuristics + thresholds for `recovery/StartupRunResumer.java` |
| `agent.shell` | Sandbox toggle, timeout, allow/block lists |
| `agent.deployment` | Cloud Run defaults + artifact registry + workspace archive |
| `agent.integrations` | Jira / Confluence / ServiceNow / PagerDuty / Bitbucket / Slack toggles + creds |
| `agent.security.deps` | Dependency-install policy (lockfile required, audit required, pre-release blocked, allowed-registries) |
| `preview` | Mode (docker / cloud-run / vm), TTL, port range, concurrency per tier |
| `app.security` | CSP, HSTS, X-Frame-Options |
| `app.mail` | SMTP vs Gmail-API transport |
| `app.jwt` | Secret + expiry windows |
| `app.tiers` | Monthly + concurrent run limits per tier |
| `executespec.designs` | Curated style presets for the preview engine |
| `executespec.repo.max-size-kb` | Repo-size gate at clone time (default 250 000 KB) |
| `executespec.rate-limit.enabled`, `executespec.crypto`, `executespec.validator` | Cross-cutting toggles |
| `billing.*` | Default gateway, Stripe + Razorpay creds, currency map, founder-pricing slots |
| `analytics.posthog` | Activation funnel telemetry |
| `management.*` | Actuator exposure |

Feature flags resolve via [`config/featureflag/FeatureFlagService.java`](../src/main/java/com/navneet/executespec/config/featureflag/FeatureFlagService.java) over the `feature_flag` table. Active keys observed in the source (grep `isEnabled(...)`): `submissions.paused`, `v2.pause.enabled`, `scoring.v2-verified`, `project.runs.auto-push`, `lint.autofix.enabled`, `validation.v3-strict`, `assessment.llm.enabled`, `codemap.purpose-summaries`, `provider.smart-routing.enabled`, `routing.adaptive-routing.enabled`, `ci.autofix.enabled`, `harness.v2.batch_enabled`, `security.guardrail_level`, `execution.view.enabled`, `v3.anonymous-trial.real-dispatch`, `billing.lifecycle.sunsetting.enabled`.

---

## 13. Async + thread model

- Run pipelines: `RunOrchestrator` owns an `ExecutorService` (virtual threads since Java 21). Each run is one future.
- Plan-gate signal: `harness/GateStore` uses Redis pub/sub-ish gate signalling so cross-instance approval flows still work.
- Per-task worker: synchronous from the executor's point of view; the executor is the run-pipeline thread.
- Apply-suggestion in project chat: dedicated `applyExecutor` (4 threads in `memory/ProjectChatService`) so the 25-iter worker loop doesn't pin a Tomcat handler.
- Async work crossing thread boundaries must wrap the runnable in `ContextSnapshotFactory.captureAll().wrap(runnable)` so traceId + MDC + RequestAttributes propagate.
- Scheduled jobs: ShedLock + `@SchedulerLock` ensure single-instance execution across the cluster (Redis-backed `LockProvider`).
- Tomcat async request timeout: 15 min (`spring.mvc.async.request-timeout=900000`) — sized to the worst-case worker loop.

---

## 14. Build + deploy pipeline

CI workflows in [`.github/workflows/`](../.github/workflows):

- `lint.yml` — runs on PR against `dev` and `main`. Jobs: spotless:check (Java), prettier --check + eslint (frontend), `npm audit --audit-level=high` (warn-only).
- `deploy.yml` — runs on push to `dev` and `workflow_dispatch`. Workload Identity Federation; uploads JAR to `executespec-{env}-deploy` GCS bucket; reboots the GCE VM. Frontend lint + build runs before the JAR build so any frontend regression fails fast.

Local pipeline:

```bash
# 1. mvn verify   - spotless:check + jacoco + surefire unit tests (e2e/integration/chaos excluded)
# 2. npm run lint - eslint with --max-warnings 9999 (errors only block)
# 3. npm run build - tsc + vite into src/main/resources/static
# 4. mvn spring-boot:run - serve from :8080
```

Spotless config in `pom.xml`: google-java-format 1.22, `<style>GOOGLE</style>`, `removeUnusedImports`, import order `java,javax,jakarta,com,org,`, `formatAnnotations`. Bound to the `verify` phase so CI is the spotless gate.

Architecture-rule tests (ArchUnit 1.4): `arch/AccessGuardCoverageTest`, `arch/ApiContractTest` (asserts every URL in `frontend/src/lib/api.ts` resolves to a registered backend mapping), plus a few targeted layering checks.

---

## 15. Known weak spots (engineering-honest)

These exist in code today; treat them as signals when navigating, not as recommendations.

- **Library / Migrations / Deployments are pre-GA.** The Signal shell flags them with a BETA badge in `frontend/src/signal/components/Shell.tsx`. Library is a static-content browser; Migrations runs the recipe catalog but lacks polish; Deployments routes to Cloud Run but lacks the org-key flow.
- **Patch-mode tests run unattended only when `ci.autofix.enabled` is flipped on.** Phase 3's webhook stays OFF by default until SWE-bench Lite resolved-% justifies the unattended-bot path.
- **`v3/` and `v4/` page folders are retired.** Files remain on disk for legacy redirect catching. New work goes in `signal/`.
- **`MAX` tier is dropped.** The current ladder is FREE / PRO (HIDDEN, grandfathered) / TEAM ($39/seat BYOK, min 2 seats) / ENTERPRISE. V165 renamed MAX → TEAM @ $39/seat.
- **Adaptive routing can silently override `-Dexecutespec.strict-provider=true`** — `ModelResolver.selectAdaptiveProvider` switches providers based on historical success rate without consulting the strict flag. Tracked.
- **Outline planner uses run-level model, not its tier model.** `OutlinePlannerAgent` constructs the LLM request from `req.model()` which is the run-level default rather than `ModelResolver.resolve("OutlinePlannerAgent", provider)`. Swapping `anthropic.planning` in YAML has no effect on the planner today.
- **Flyway tolerates `*:missing`** — wide net to cover the V119 orphan from the preview-engine split. PR review on migrations is the compensating control; see the comment block in `application.yml`.

---

## 16. Glossary

| Term | Meaning |
|---|---|
| **BUILD mode** | Default V2 pipeline: classify → outline → executor → reviewer |
| **PATCH mode** | Single-agent loop on a failing-test spec; SWE-bench + 4 autofix surfaces |
| **Plan gate** | `pipeline_run.status=AWAITING_APPROVAL`. Human approval charges credits and signals the gate |
| **Verified score** | Multiplicative; `clamp(0,100, baseAccuracy × buildOK × testRatio × reviewerMultiplier − criticalPenalty)` |
| **Tier** (run) | LIGHTWEIGHT / WORKHORSE / PLANNING — agent-level model class |
| **Tier** (account) | FREE / PRO (HIDDEN) / TEAM / ENTERPRISE — billing class |
| **Run mode** | `pipeline_run.run_mode` column, V182 — `BUILD` or `PATCH` |
| **Cleanroom container** | `--rm --network none --read-only` Docker container for bench-mode runs |
| **Codemap** | LLM-built summary of repo structure; per-run + per-project variants |
| **Findings autofix** | Phases 1–4 — PR Review / TechDebt / CI failure / Code Review severity |
| **Signal** | The single production frontend chrome (since 2026-05-15). `v3/`, `v4/` retired |
