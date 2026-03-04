# Multi-Agent Coding System — Project Context
## Master Reference for New Threads

> Feed this document at the start of any new conversation to restore full project context.
> Last updated: March 2026. **Tiers 1–8 complete. Tier 9 (Playground) planned. Spring AI 1.1.0. 2-provider fallback (Anthropic → OpenAI). Model matrix: Planner=Sonnet 4.6, Workers/Reviewer/Compiler=Haiku 4.5. React 18 SPA frontend.** `mvn compile` + `mvn test` (104 unit tests) pass clean.

---

## What We Are Building

A **multi-agent coding system** 


**Use cases**: internal tool workflows, SaaS prototyping, boilerplate automation, learning.
**Interfaces**: REST API + Web UI dashboard.
**Target worker capability**: write/edit files, compile/build, run tests, shell commands, search/grep.

**Tech stack**: Java 21, Spring Boot 3.3.0, Spring AI 1.0.0, PostgreSQL, JGit 6.9.0, Maven.
**LLM providers**: Anthropic (primary), OpenAI (fallback — `gpt-4o`). Gemini removed.
**Package root**: `com.navneet.agentteam`
**Build**: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile`

---

## Architecture in One Page

```
User request (REST POST /api/runs)
     │
     ▼
PipelineHarness  (Semaphore — max 3 concurrent runs)
     │
     ├─ PlannerAgent ─────────────────────── Single API call → TaskPlan JSON
     │                                       Model: claude-sonnet-4-6
     │                                       Routes: HIGH/MEDIUM/LOW → haiku-4-5
     │
     ├─ ApprovalGate ─────────────────────── CompletableFuture → POST /api/runs/{id}/approve
     │
     ├─ WorkerPool ────────────────────────── Virtual threads, parallel by dependsOn
     │    ├─ WorkerAgent [task-A] ────────── Agentic loop, XML tool-call format
     │    └─ WorkerAgent [task-B] ────────── Own git branch: agent/{runId8}/{taskId}
     │         └─ Tools: FileSystemTool, ShellTool, GitTool, FileLockTool
     │
     ├─ CompilerAgent ─────────────────────── Detects build tool, compiles, fixes errors (max 3 attempts)
     │                                       Non-fatal — never blocks merge gate
     │
     ├─ ReviewerAgent ─────────────────────── Single API call → ReviewReport JSON
     │                                       Checks README.md updated (hard gate)
     │
     ├─ ApprovalGate ─────────────────────── CompletableFuture → POST /api/runs/{id}/merge
     │
     ├─ GitMergeService ─────────────────── Topological merge to main, enforces README
     │
     └─ Post-merge (async virtual threads)
          ├─ ApiTestSuiteAgent ────────────── Generates api-docs/openapi.yaml (OpenAPI 3.0)
          ├─ E2eTestGeneratorAgent ────────── [Tier 9] Generates playground-tests.json from OpenAPI + code
          ├─ PreviewService ───────────────── npm install && npm run build, marks hasFrontend=true
          ├─ ContextAgent ────────────────── Updates CODEBASE_CONTEXT.md
          └─ PromptOptimiserAgent ─────────── Every 10 runs: analyses patterns, patches prompts
```

---

## Three Critical Architecture Decisions

**Decision 1 — Agent-to-Agent Communication**
PostgreSQL-backed inbox (`agent_inbox` table). Any agent messages any other agent directly.
Messages injected as user turns at next API call.
**File-first discipline**: API contracts and shared types go to `.agent-context/` files — not messages. Files survive compaction. Messages don't.

**Decision 2 — Git Operations**
Each worker gets its own branch: `agent/{runId8}/{taskId}`.
Workers commit freely to own branch. File-lock registry prevents conflicts.
Harness merges to main after human approval, in dependency order.
Workers cannot: merge, push to main, checkout other branches.

**Decision 3 — Context Management**
Two-tier memory: volatile conversation + durable checkpoint files.
Harness-controlled compaction (not model-controlled) at 70–80% threshold.
Threshold is based on `lastCallInputTokens` (current context size), NOT cumulative.
Before compaction: write checkpoint → disk. After: fresh file reads from disk, not memory.

---

## LLM Model Configuration

### Role-Anchored Model Matrix (current)

| Role | Agent(s) | Model | Rationale |
|---|---|---|---|
| **Planner** | PlannerAgent | `claude-sonnet-4-6` | Strong reasoning for task decomposition |
| **Workers** | WorkerAgent, CompilerAgent, ApiTestSuiteAgent | `claude-haiku-4-5-20251001` | Fast + cheap for code-write-test-fix loop |
| **Reviewer** | ReviewerAgent | `claude-haiku-4-5-20251001` | Review + README check |
| **Scout** | ContextAgent, PromptOptimiserAgent | `claude-haiku-4-5-20251001` | Text summarisation + pattern analysis |
| **Recovery** | EscalationService | `claude-sonnet-4-6` | Retry with stronger model |

### How Model Override Works
- **PlannerAgent**: `resolveModelName()` returns `claude-sonnet-4-6`; attached as `AnthropicChatOptions` per call via `LlmRouter.callWithModelOptions()`.
- **WorkerAgent / CompilerAgent / ApiTestSuiteAgent**: use `CHEAP_CHAIN` (`claude-haiku-4-5-20251001`).
- **ReviewerAgent**: `resolveModelName()` returns `claude-haiku-4-5-20251001`.
- **ContextAgent / PromptOptimiserAgent**: model value from `agent.context.model` / `agent.optimiser.model` yaml keys.

### Fallback Chains (2-provider: Anthropic → OpenAI)

```
CAPABLE_CHAIN:  anthropic/claude-sonnet-4-6         →  openai/gpt-4o
CHEAP_CHAIN:    anthropic/claude-haiku-4-5-20251001  →  openai/gpt-4o
```

`LlmRouter` selects chain based on primary model name. Falls back on rate-limit (15 s sleep + retry) or any non-transient provider error. **Gemini removed** — no longer in the fallback chain.

### Token Limits

| Setting | Value |
|---|---|
| `spring.ai.anthropic.chat.options.max-tokens` | 16 000 |
| `spring.ai.openai.chat.options.max-tokens` | 16 000 |
| Token budget LOW | 2 048 |
| Token budget MEDIUM | 4 096 |
| Token budget HIGH | 8 192 |

---

## Database Schema (PostgreSQL)

```
Connection: jdbc:postgresql://localhost:5432/agentteam
Credentials: ${POSTGRES_USER:postgres} / ${POSTGRES_PASSWORD:postgres}
Schema management: spring.jpa.hibernate.ddl-auto=update (auto-creates / safe on restart)
```

```sql
-- Core pipeline
pipeline_run        — run lifecycle, model config, token totals, skipApproval flag,
                      hasFrontend, hasApiSpec, hasTestSuite flags
agent_task          — per-task status, assignedModel, branch name, compaction count,
                      last_activity_at, stuck_detected_at, recovery_attempt, currentProvider
agent_message       — task-level activity log (taskId FK)
file_lock           — file ownership registry (path → taskId, runId)
agent_inbox         — per-agent message inbox (provider fallback events)
model_pricing       — 6 active rows; purged+reseeded on every startup
run_recovery        — stuck run classification, action taken, restart run link
prompt_audit_entry  — before/after content, diff, patterns, post-change failure rate
gate_event          — gate open/resolved timestamps, approved flag
compaction_event    — compaction checkpoint markers (Tier 4 Gantt)

-- Tier 6
deployment          — GCP deploy lifecycle, image URI, service URL, TTL
run_repo            — GitHub repo per run (URL, owner, mode, push status)

-- Tier 7
integration_run     — maps integration source → pipeline run
integration_event_log — raw webhook event history, 7-day TTL

-- Tier 9: Playground (planned)
playground_session  — runId FK, baseUrl, status (IDLE/RUNNING/STOPPED), createdAt, lastActiveAt
e2e_test_suite      — runId FK, testCount, testsJson (text blob), generatedAt
e2e_test_result     — suiteId FK, testId, testName, status (PASS/FAIL/ERROR/SKIP),
                      httpStatus, responseBody, durationMs, ranAt
```

Run status flow: `QUEUED → PLANNING → AWAITING_APPROVAL → WORKING → REVIEWING → AWAITING_MERGE → COMPLETE/FAILED`
Task status flow: `PENDING → LOCKED → WORKING → COMPLETE / PARTIAL / FAILED / BLOCKED`

---

## Package Structure

```
com.navneet.agentteam/
├── AgentTeamApplication.java         # Entry point; @EnableScheduling, @EnableConfigurationProperties
│
├── agent/
│   ├── model/                        # TaskPlan, TaskDefinition (+assignedModel), WorkerReport,
│   │                                 # ReviewReport, TaskReview, ReviewIssue, IterationEstimate
│   ├── PlannerAgent.java             # Goal → TaskPlan JSON; planWithEstimates() for feasibility
│   ├── WorkerAgent.java              # XML <tool_call> format; singleton, per-task state in params
│   ├── ReviewerAgent.java            # ReviewReport; README check (step 5)
│   ├── TokenBudgetMonitor.java
│   ├── LlmRouter.java                # Single CAPABLE_CHAIN [sonnet → gpt-4o]; resolveChain()
│   └── CostEstimator.java            # DB-backed cost estimation for feasibility
│
├── api/
│   ├── controller/
│   │   ├── RunController.java        # /api/runs CRUD + gates + retry + download + files
│   │   ├── SseController.java        # /api/runs/{id}/events SSE stream
│   │   ├── MetricsController.java    # /api/metrics
│   │   ├── PricingController.java    # /api/pricing CRUD
│   │   ├── FeasibilityController.java # /api/feasibility
│   │   ├── TimelineController.java   # /api/runs/{id}/timeline + /api/model-comparison
│   │   ├── DeploymentController.java # /api/deployments CRUD + rollback + live
│   │   ├── IntegrationController.java # /api/integrations webhook + trigger + status
│   │   └── HealthController.java     # /api/health — DB status, uptime, active runs
│   ├── dto/                          # RunRequest, RunSummary, RunDetail (+hasTestSuite), TaskSummary,
│   │                                 # ApprovalRequest, MetricsResponse, PricingRow,
│   │                                 # FeasibilityRequest, FeasibilityReport, TaskFeasibility,
│   │                                 # DeployRequest, DeploymentSummary, DeploymentDetail
│   └── RunMapper.java
│
├── apitests/                         # ApiTestSuiteAgent (post-merge OpenAPI 3.0 gen)
│                                     # ApiTestSuiteProperties
│
├── compiler/                         # CompilerAgent (pre-review compile+fix, max 3 attempts)
│                                     # CompileResult (record: passed/skipped/failed)
│                                     # CompilerProperties
│
├── config/
│   ├── AgentProperties.java          # @ConfigurationProperties(prefix="agent")
│   │                                 # Inner classes: Roles, TokenBudget, Compaction,
│   │                                 # Concurrency, GitHub, Compiler, ApiTestSuite, Preview,
│   │                                 # Playground  [Tier 9]
│   ├── AgentModelConfig.java         # Spring AI ChatModel @Qualifier beans
│   ├── ModelPricingInitializer.java  # @PostConstruct — deletes stale rows, upserts 6 canonical rows
│   └── WebConfig.java
│
├── context/                          # ContextAgent (virtual thread, post-merge)
│                                     # ContextAgentScanner, ContextAgentInput
│                                     # ContextAgentProperties
│
├── deployment/
│   ├── strategy/                     # DeploymentStrategy (interface)
│   │                                 # SpringBootDeploymentStrategy (eclipse-temurin base)
│   │                                 # StaticDeploymentStrategy (nginx:1.25-alpine)
│   ├── DeploymentService.java
│   ├── DeploymentProperties.java     # @ConfigurationProperties record
│   ├── DockerfileGenerator.java
│   ├── DeploymentGitHubService.java  # separate from GitHubRepoService to avoid package conflict
│   ├── ArtifactRegistryClient.java
│   ├── CloudRunClient.java
│   ├── WorkspaceExportService.java   # zip download
│   ├── WorkspaceFileService.java     # read/list files with path traversal protection
│   └── DeploymentCleanupJob.java     # @Scheduled — deletes deployments older than ttl-days
│
├── harness/
│   ├── PipelineHarness.java          # Orchestrator; Semaphore(maxRuns), CompletableFuture gates
│   │                                 # Wires: compilerAgent, apiTestSuiteAgent, previewService
│   │                                 # contextAgent, promptOptimiserAgent, recoveryService
│   │                                 # integrationManager, deploymentService
│   │                                 # e2eTestGeneratorAgent  [Tier 9]
│   ├── WorkerPool.java               # Concurrent tasks; taskThreads map; cancelTask/resubmitTask
│   ├── ApprovalGate.java             # CompletableFuture<Boolean> gate wrapper
│   ├── GitMergeService.java          # Merge + enforceReadmeUpdated() hard gate
│   ├── WorkspaceInitService.java
│   └── MergeBlockedException.java
│
├── integration/
│   ├── jira/                         # JiraAdapter, JiraClient, JiraWebhookPayload
│   ├── confluence/                   # ConfluenceAdapter, ConfluenceClient, ConfluenceWebhookPayload
│   ├── servicenow/                   # ServiceNowAdapter, ServiceNowClient, ServiceNowWebhookPayload
│   ├── pagerduty/                    # PagerDutyAdapter, PagerDutyClient, PagerDutyWebhookPayload
│   ├── IntegrationManager.java       # setHarness() breaks circular dep (same pattern as RecoveryService)
│   ├── IntegrationController.java
│   ├── IntegrationProperties.java
│   ├── IntegrationConfig.java
│   └── IntegrationEventLogCleanupJob.java
│
├── optimiser/                        # PromptOptimiserAgent (virtual thread, every 10 runs)
│                                     # PromptPatternAnalyser, PromptPatterns
│                                     # PromptAuditService, PromptOptimiserProperties
│
├── playground/                       # [Tier 9 — planned]
│   ├── PlaygroundController.java     # /api/playground/** REST endpoints
│   ├── PlaygroundService.java        # Session management; base URL resolution
│   ├── PlaygroundTestRunner.java     # HTTP test executor (virtual thread; streams SSE)
│   ├── PlaygroundWebSocketProxy.java # Server-side WS relay (browser ↔ Spring ↔ target)
│   ├── E2eTestGeneratorAgent.java    # LLM agent: generates playground-tests.json post-merge
│   └── PlaygroundProperties.java
│
├── preview/                          # PreviewService (npm build post-merge)
│                                     # PreviewController (GET /api/runs/{id}/preview/** SPA fallback)
│                                     # PreviewProperties
│
├── pricing/                          # ModelPricing (JPA entity), ModelPricingId (composite key)
│                                     # ModelPricingRepository
│
├── recovery/
│   ├── LlmCallGuard.java             # Wraps ChatModel; retries rate-limit/server errors
│   │                                 # Classifies: AuthFailure, BudgetExhausted, ContextTooLong
│   ├── StuckRunMonitor.java          # @Scheduled every 2 min
│   ├── RunRecoveryService.java       # setPipelineHarness() breaks circular dep
│   ├── StuckRunEvaluator.java
│   ├── EscalationService.java
│   ├── RecoveryProperties.java       # capableModels.anthropic = claude-sonnet-4-6
│   ├── StuckClassification.java      # Enum: CONTEXT_LIMIT, RATE_LIMIT, TOKEN_BUDGET, …
│   └── LlmCallException.java
│
├── repo/
│   └── GitHubRepoService.java
│
├── sse/
│   ├── RunEvent.java                 # Record + factory methods: runStatus, taskStatus, log,
│   │                                 # gateOpened, gateResolved, costUpdate, queued, complete,
│   │                                 # recoveryAttempted, runRestarted,
│   │                                 # deployStarted/Building/Pushing/Live/Failed/Deleted
│   │                                 # apiSpecReady, previewReady
│   │                                 # testsGenerated, testRunStarted, testResult, testRunComplete [Tier 9]
│   └── SseBroadcaster.java          # ConcurrentHashMap<runId, CopyOnWriteArrayList<SseEmitter>>
│
├── state/
│   ├── StateStore.java               # All DB access; inner records: MetricsData, TimelineData,
│   │                                 # ModelComparisonData, ModelStatData
│   ├── InboxService.java             # Per-agent inbox send/drain/query
│   ├── entity/                       # 17 JPA entities (14 existing + PlaygroundSession,
│   │                                 # E2eTestSuite, E2eTestResult) [Tier 9]
│   └── repository/                   # 16 JPA repositories (13 existing + 3 Playground) [Tier 9]
│
└── tool/
    ├── FileSystemTool.java           # .normalize() prevents path traversal
    ├── ShellTool.java
    ├── GitTool.java
    └── FileLockTool.java             # releaseAllLocksForTask / releaseAllLocksForRun
```

---

## Prompt Files

```
src/main/resources/prompts/
├── planner.txt           JSON-only, 1–6 tasks, explicit boundaries + acceptance criteria
│                         Hard Rule 6: assigned_model = claude-haiku-4-5-20251001 (all complexities)
├── worker.txt            XML <tool_call> format; Step 7 = UPDATE README.md (hard requirement)
├── worker-openai.txt     OpenAI-compatible variant
├── worker-gemini.txt     Gemini-compatible variant
├── reviewer.txt          JSON-only; Step 5 = README UPDATE CHECK (blocks merge if missing)
├── context-agent.txt     Single call: update CODEBASE_CONTEXT.md after run
├── compiler.txt          Compile error analysis; returns JSON-patch [{path, content}] fixes
├── api-test-suite.txt    OpenAPI 3.0 YAML spec generation from *Controller.java files
└── e2e-test-generator.txt  [Tier 9 — planned]
                          Input: openapi.yaml + controller files + README
                          Output: JSON array of test cases [{id, name, method, path, body,
                          expectedStatus, assertions:[{type, field, value}]}]
                          Rules: realistic sample data, cover happy path + error cases,
                          no hard-coded auth tokens, assertions must be machine-checkable
```

**Key worker prompt rules** (enforce if regenerating):
- Tool call format: `<tool_call>{"name": "...", "args": {...}}</tool_call>`
- Working sequence: read context → plan → write → commit → build → test → self-review → update README → report
- Commit after every file write (data loss protection)
- Check `file-locks.json` before writing any file
- Report JSON is the LAST thing in the response — no text after it

---

## Configuration (`application.yml` key values)

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/agentteam
    username: ${POSTGRES_USER:postgres}
    password: ${POSTGRES_PASSWORD:postgres}
  jpa:
    hibernate.ddl-auto: update
  ai:
    anthropic:
      chat.options:
        model: claude-haiku-4-5-20251001    # default; agents override per-call
        max-tokens: 16000
    openai:
      chat.options:
        model: gpt-4o
        max-tokens: 16000

agent:
  workspace: ./workspace
  concurrency:
    max-runs: 5
  roles:
    planner: anthropic
    worker: anthropic
    reviewer: anthropic
  token-budget: {low: 2048, medium: 4096, high: 8192}
  compaction: {warn-pct: 0.65, compact-pct: 0.75, hard-stop-pct: 0.90}
  github:
    pat: ${GITHUB_PAT:}
    default-owner: ${GITHUB_OWNER:navneetprabhakar}
  recovery:
    enabled: true
    warn-threshold-minutes: 15
    act-threshold-minutes: 30
    max-task-retries: 1
    capable-models:
      anthropic: claude-sonnet-4-6
      openai: gpt-4o
  context:
    enabled: true
    model: claude-haiku-4-5-20251001
    provider: anthropic
    run-interval: 1
  optimiser:
    enabled: true
    run-interval: 10
    analysis-window-runs: 20
    failure-rate-threshold: 0.20
    model: claude-haiku-4-5-20251001
    provider: anthropic
  compiler:
    enabled: true
    max-attempts: 3
    timeout-seconds: 300
    model: claude-haiku-4-5-20251001
    provider: anthropic
  api-test-suite:
    enabled: true
    model: claude-haiku-4-5-20251001
    provider: anthropic
  preview:
    enabled: true
  deployment:
    enabled: true
    gcp-project: ${GCP_PROJECT:my-gcp-project}
    gcp-region: us-central1
    ttl-days: 7
  integrations:            # all disabled by default; enable per-source in config
    jira:       {enabled: false, skip-approval: false, …}
    confluence: {enabled: false, skip-approval: false, …}
    servicenow: {enabled: false, skip-approval: false, …}
    pagerduty:  {enabled: false, skip-approval: false, …}

  # Tier 9 — Playground (planned)
  playground:
    enabled: true
    max-active-sessions: 5
    test-run-timeout-seconds: 120
    model: claude-haiku-4-5-20251001
    provider: anthropic
```

### Environment Variables

| Variable | Default | Purpose |
|---|---|---|
| `ANTHROPIC_API_KEY` | *(required)* | Anthropic API key — no hardcoded fallback |
| `OPENAI_API_KEY` | *(required for fallback)* | OpenAI API key |
| `POSTGRES_USER` | `postgres` | DB username |
| `POSTGRES_PASSWORD` | `postgres` | DB password |
| `GITHUB_PAT` | *(empty)* | GitHub personal access token |
| `GITHUB_OWNER` | `navneetprabhakar` | Default GitHub org/user |
| `GCP_PROJECT` | `my-gcp-project` | GCP project ID for deployments |

---

## REST API — Complete Endpoint Reference

### Runs (`/api/runs`)

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/runs` | Submit new run — returns 202 with `runId` |
| `GET` | `/api/runs` | List runs; optional `?status=` filter |
| `GET` | `/api/runs/{runId}` | Full run detail with tasks |
| `POST` | `/api/runs/{runId}/approve` | Resolve plan gate (approve/reject) |
| `POST` | `/api/runs/{runId}/merge` | Resolve merge gate (approve/reject) |
| `DELETE` | `/api/runs/{runId}` | Cancel in-progress run |
| `POST` | `/api/runs/{runId}/tasks/{taskId}/retry` | Force-retry a specific task |
| `GET` | `/api/runs/{runId}/events` | **SSE stream** — live `RunEvent` feed |
| `GET` | `/api/runs/{runId}/recovery` | Recovery attempt history |
| `GET` | `/api/runs/{runId}/messages` | Agent message log (max 200) |
| `GET` | `/api/runs/{runId}/timeline` | Gantt + gate event data for D3.js |
| `GET` | `/api/runs/{runId}/download` | Download workspace as `.zip` |
| `GET` | `/api/runs/{runId}/files` | List workspace files |
| `GET` | `/api/runs/{runId}/file?path=` | Read single workspace file |
| `GET` | `/api/runs/{runId}/openapi.yaml` | Generated OpenAPI 3.0 spec |
| `GET` | `/api/runs/{runId}/preview/**` | Serve live frontend preview (SPA fallback) |

### Other Core Endpoints

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/metrics` | Global run/token/cost statistics |
| `GET` | `/api/model-comparison?days=30` | Per-model performance stats |
| `GET` | `/api/pricing` | All 6 model pricing rows |
| `PUT` | `/api/pricing/{provider}/{model}` | Upsert pricing |
| `POST` | `/api/feasibility` | Estimate cost+complexity without running workers |
| `GET` | `/api/health` | DB status, uptime (formatted), active/queued/total runs |

### Deployments (`/api/deployments`)

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/deployments` | Deploy run to GCP Cloud Run |
| `GET` | `/api/deployments` | List all deployments |
| `GET` | `/api/deployments/{id}` | Deployment detail |
| `DELETE` | `/api/deployments/{id}` | Delete deployment |
| `POST` | `/api/deployments/{id}/rollback` | Rollback to previous revision |
| `GET` | `/api/deployments/live` | List `LIVE` deployments only |

### Integrations (`/api/integrations`)

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/integrations/{source}/webhook` | Inbound webhook (jira, confluence, servicenow, pagerduty) |
| `POST` | `/api/integrations/confluence/trigger` | Manual Confluence page trigger |
| `GET` | `/api/integrations/runs?source=` | List integration-triggered runs |
| `GET` | `/api/integrations/status` | Enabled integration sources |

### Playground (`/api/playground`) — Tier 9, planned

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/playground/projects` | List completed runs loadable as projects (enriched with hasFrontend, hasApiSpec, hasTestSuite, stack) |
| `POST` | `/api/playground/sessions` | Create or resume a playground session for a `runId` |
| `GET` | `/api/playground/sessions/{id}` | Session state (baseUrl, status) |
| `DELETE` | `/api/playground/sessions/{id}` | End session |
| `POST` | `/api/playground/sessions/{id}/api-request` | Proxy a REST API call to the session's `baseUrl` |
| `GET` | `/api/playground/runs/{runId}/tests` | Get generated E2E test suite |
| `POST` | `/api/playground/runs/{runId}/tests/run` | Execute tests; streams `TEST_RESULT` SSE events |
| `GET` | `/api/playground/runs/{runId}/tests/results` | Last test run results |
| `POST` | `/api/playground/runs/{runId}/tests/generate` | Re-trigger `E2eTestGeneratorAgent` |

### Health

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/health` | DB ping, uptime formatted, run counts |

---

## Web UI

Single-page Alpine.js + Tailwind CSS (CDN) app at `http://localhost:8080`.

| Tab | Description |
|---|---|
| **Runs** | Submit + live list; click to open detail panel |
| **Metrics** | Global counters + D3 charts (token bubble, agent comms graph) |
| **Pricing** | 6-row model pricing table; `✅ active` badge on `claude-haiku-4-5-20251001` |
| **Feasibility** | Cost estimate without executing; model picker (`[sonnet, opus]`) |
| **Deployments** | GCP deployment panel, deploy form, live status |
| **Integrations** | Source status; manual Confluence trigger |
| **Playground** | [Tier 9 — planned] Project selector + UI preview + API/WS test bed + E2E test runner |

**Run detail panel** (slide-over): Overview · Tasks · Log (SSE live) · Timeline (D3 Gantt) · Preview tab (iframe, if `hasFrontend=true`) · API Docs tab (Swagger UI, if `hasApiSpec=true`)

**Header health badge**: Polls `GET /api/health` every 30 s; animated ping dot, uptime, active run count.

### Playground Tab Layout (Tier 9 — planned)

```
┌─────────────────────────────────────────────────────────────────┐
│ [Project Selector — card grid of completed runs]                │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐ │
│  │ run-abc · Node  │  │ run-def · Java  │  │ run-xyz · React │ │
│  │ hasFrontend ✅  │  │ hasApiSpec ✅   │  │ hasFrontend ✅  │ │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘ │
├─────────────────────────────────────────────────────────────────┤
│ Selected: run-def (Java / backend-only)                         │
│                                                                 │
│  Base URL: [https://run-def.a.run.app] [Use GCP] [Custom]      │
│                                                                 │
│  ┌─── API Test Bed ───────────────┐  ┌─── Test Suite ─────────┐│
│  │ Endpoint: [POST /api/runs   ▾] │  │ ▶ Run All (12 tests)   ││
│  │ Body: { "userRequest": "..." } │  │                         ││
│  │ [Send]                         │  │ ✅ Submit run      45ms ││
│  │                                │  │ ✅ Get run detail  23ms ││
│  │ Response: 202 · 67ms           │  │ ❌ Delete run     401  ││
│  │ { "runId": "abc-123" }         │  │ ⏳ Approve gate...     ││
│  └────────────────────────────────┘  └────────────────────────┘│
│  [WebSocket Tab]                                                │
└─────────────────────────────────────────────────────────────────┘

For hasFrontend=true projects:
┌─────────────────────────────────────────────────────────────────┐
│  [📱 Tablet 🖥 Desktop] [↗ Open in tab]                        │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  <iframe src="/api/runs/{id}/preview/" …/>               │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

### Frontend Files (static/)

| File | Purpose |
|---|---|
| `index.html` | SPA; tabs including Playground |
| `app.js` | Alpine module loader; exposes viz globals |
| `style.css` | Tailwind-based; badges, log pane, gate banner, chart containers |
| `integrations.html` | Standalone dark-mode integrations dashboard |
| `viz/log-viewer.js` | SSE LogViewer; handles all RunEvent types incl. TEST_* [Tier 9] |
| `viz/timeline.js` | D3.js Gantt chart |
| `viz/comms-graph.js` | Force-directed agent comms graph |
| `viz/bubble.js` | Token scatter/bubble chart |
| `viz/model-compare.js` | Grouped bar chart for model comparison |
| `viz/utils.js` | STATUS_COLORS, fmtMs, fmtTokens, makeTooltip |
| `viz/api-tester.js` | [Tier 9] REST API request builder component |
| `viz/ws-tester.js` | [Tier 9] WebSocket tester (connect/send/receive) |
| `viz/test-runner.js` | [Tier 9] E2E test suite runner with SSE result stream |

---

## Build Tiers — Status

### ✅ Tier 1 — Core Loop
Planner → approval gate → parallel workers → reviewer → merge gate → GitMergeService.
XML tool-call format, agentic loop, file locks, git branches.

### ✅ Tier 2 — Reliability Layer
Context compaction (harness-controlled), provider fallback with guardrails, `LlmRouter`, `TokenBudgetMonitor`, `CostEstimator`, inbox messaging, git run tagging.

### ✅ Tier 3 — REST + Web UI + SSE
Async REST with `Semaphore`(maxRuns=3), `CompletableFuture` approval/merge gates, SSE broadcaster, Alpine.js + Tailwind SPA, `model_pricing` table, README enforcement hard gate.

### ✅ Tier 4 — Visualisations
Five D3.js v7 charts: Gantt timeline, force-directed agent comms graph, token bubble chart, model comparison bar chart, SSE log viewer. `gate_event` + `compaction_event` tables.

### ✅ Tier 5 — Intelligence
- **Stuck recovery**: `StuckRunMonitor` (@Scheduled 2 min) → `StuckRunEvaluator` → `RunRecoveryService`
- **LLM error handling**: `LlmCallGuard` wraps every call; retries rate-limit; classifies AuthFailure/BudgetExhausted/ContextTooLong
- **Context agent**: virtual thread; post-merge; updates `CODEBASE_CONTEXT.md`; non-fatal
- **Prompt optimiser**: every 10 runs or >20% failure rate; `PromptPatternAnalyser` → targeted prompt patch; full audit trail in `prompt_audit_entry`

### ✅ Tier 6 — GCP Deployments
Docker build → Artifact Registry → Cloud Run. Two strategies: `SpringBootDeploymentStrategy` + `StaticDeploymentStrategy` (nginx). Versioned revisions, 7-day TTL cleanup, workspace zip download, `WorkspaceFileService` with path traversal protection.

### ✅ Tier 7 — External Integrations
Webhook-driven pipeline triggers from Jira, Confluence, ServiceNow, PagerDuty.
Pattern: `InboundAdapter → normalise to RunRequest → pipeline → OutboundAdapter → write back`.
`skipApproval` flag for fully automated pipelines. Idempotency via `integration_event_log`. All integrations disabled by default; enable per-source via `agent.integrations.{source}.enabled=true`.

### ✅ Tier 8 — Compile, API Spec, Preview
- **`CompilerAgent`**: detects build tool (pom.xml/build.gradle/package.json), compiles, sends errors to LLM in JSON-patch format, applies fixes iteratively (max 3 attempts); always non-fatal
- **`ApiTestSuiteAgent`**: post-merge; reads `*Controller.java` files; generates `api-docs/openapi.yaml` (OpenAPI 3.0); served via `GET /api/runs/{id}/openapi.yaml`; Swagger UI tab in dashboard
- **`PreviewService`**: post-merge; detects frontend files; runs `npm install && npm build`; marks run `hasFrontend=true`; `PreviewController` serves preview with SPA fallback
- **`HealthController`**: `GET /api/health` — DB ping, uptime formatted, active/queued/total/completed/failed run counts
- **`supervisor.sh`** (project root): full state-machine process supervisor (STARTING → RUNNING → RESTARTING → UNHEALTHY → CRASHED → FAILED → STOPPED); exponential backoff (5 s → 120 s); max 10 crashes; health poll every 20 s; commands: `start|stop|restart|status|logs|build`

### 🔲 Tier 9 — Playground (planned)

Interactive environment for loading, previewing, and testing generated projects without leaving the dashboard.

**Sub-features:**

1. **Project Selector** — `GET /api/playground/projects` lists all `COMPLETE` runs enriched with `hasFrontend`, `hasApiSpec`, `hasTestSuite`, detected stack. Frontend card grid. Selecting a run loads the appropriate sub-experience.

2. **UI Preview (Internal Deploy)** — For `hasFrontend=true` projects. Promotes existing `PreviewController` iframe to a full-panel, resizable experience with mobile/tablet/desktop viewport toggles and a "pop out in new tab" button. No new backend work.

3. **Backend Test Bed** — For `hasFrontend=false` / API-only projects:
   - **REST API Tester**: reads generated `openapi.yaml`; endpoint picker pre-populated from paths; path params, query params, headers, body editor; `POST /api/playground/sessions/{id}/api-request` proxies call to `baseUrl`; response panel shows status, latency, body.
   - **WebSocket Tester**: `PlaygroundWebSocketProxy` relays browser WS ↔ target server (bypasses CORS/mixed-content). Connect/disconnect, message composer, scrolling message log.
   - **Base URL resolution**: GCP Cloud Run URL (if deployment exists) → user-supplied URL.

4. **E2E Test Generation + Execution**:
   - `E2eTestGeneratorAgent` (virtual thread, post-merge after `ApiTestSuiteAgent`): reads `openapi.yaml` + controller files + README → generates `playground-tests.json` (array of `{id, name, method, path, headers, body, expectedStatus, assertions}`).
   - Stored in `e2e_test_suite` table; `PipelineRun.hasTestSuite` set to `true`.
   - `PlaygroundTestRunner` executes tests against `baseUrl`; streams `TEST_RESULT` SSE events per test; stores results in `e2e_test_result`.
   - Frontend: test list with method badge, name, expected status; "Run All" / individual test buttons; real-time ✅/❌ badges; expand for request/response/assertion detail.
   - "Regenerate" button re-triggers `E2eTestGeneratorAgent`.

**New RunEvent types (Tier 9):**
```
TESTS_GENERATED   — payload: runId|count
TEST_RUN_STARTED  — payload: runId
TEST_RESULT       — payload: runId|testName|PASS|durationMs  (or FAIL|errorDetail)
TEST_RUN_COMPLETE — payload: runId|passed|failed|total
```

**New entities:** `PlaygroundSession`, `E2eTestSuite`, `E2eTestResult`
**New repositories:** `PlaygroundSessionRepository`, `E2eTestSuiteRepository`, `E2eTestResultRepository`
**New prompt:** `e2e-test-generator.txt`
**New viz modules:** `viz/api-tester.js`, `viz/ws-tester.js`, `viz/test-runner.js`

---

## Prompt Caching (Live — implemented Feb 2026)

Anthropic prompt caching is enabled via two small classes in `config/`:

| Class | Role |
|---|---|
| `AnthropicCacheInterceptor` | `ClientHttpRequestInterceptor`; URL-aware (only fires for `anthropic.com`). Adds `anthropic-beta: prompt-caching-2024-07-31` header and transforms the Spring AI-serialised `"system": "string"` field into the content-block array format Anthropic requires: `"system": [{"type":"text","text":"...","cache_control":{"type":"ephemeral"}}]` |
| `AgentModelConfig.anthropicPromptCachingCustomizer()` | `@Bean RestClientCustomizer` — registers the interceptor on the Spring Boot auto-configured `RestClient.Builder`, which Spring AI's `AnthropicAutoConfiguration` injects to build its internal HTTP client. One bean, zero changes to any agent. |

**Why it works**: Spring Boot's `RestClientCustomizer` beans are applied by `RestClientBuilderConfigurer` to every `RestClient.Builder` prototype bean at injection time. Spring AI's `AnthropicAutoConfiguration` is annotated `@ConditionalOnMissingBean(AnthropicApi.class)` and injects `RestClient.Builder` from the context — so our interceptor is present on every Anthropic API call transparently.

**Cache hit visibility**: `WorkerAgent.buildCacheNote()` reads `cache_creation_input_tokens` and `cache_read_input_tokens` from `ChatResponseMetadata` (Spring AI 1.0 surfaces Anthropic usage fields as map entries). Cache write/hit is appended to the per-iteration SSE log line, e.g. `Iteration 3 (850/420 tokens) [⚡ cache hit: 1842 tok]`.

**Expected gain**: `worker.txt` is ~1,800 tokens (above the 1,024-token Anthropic minimum). After iteration 1 (cache write), all subsequent iterations in the same task are cache reads: ~85% lower latency and ~90% lower cost on the system-prompt prefix. For a 10-iteration task this typically saves 3–6 minutes of LLM wait.

**Fallback safety**: If the JSON transform fails for any reason, `AnthropicCacheInterceptor.injectCacheControl()` swallows the exception and sends the original body — caching failure never blocks a pipeline call.

---

## Key Implementation Rules (Enforce in Every Session)

1. **Package root is `com.navneet.agentteam`** — never `com.example.*`
2. **Database is PostgreSQL** — never H2 or SQLite
3. **All models are `claude-haiku-4-5-20251001`** — haiku is gone; no cheap/capable split
4. **Model classes use `@Data @Builder @NoArgsConstructor @AllArgsConstructor`** — not records (for Jackson + builder compat). DTOs that were converted from records use `getXxx()` not `xxx()` accessor style
5. **All LLM model classes** have `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)`
6. **`lombok.config`** has `copyableannotations += @Qualifier`
7. **Spring AI 1.0.0**: `ChatResponse.getResult().getOutput().getText()` (not `.getContent()`)
8. **`FileSystemTool.workspace()`** uses `.normalize()` to avoid SecurityException on path "."
9. **Workers never touch main branch** — own branch only: `agent/{runId8}/{taskId}`
10. **No mid-task provider switch** — BLOCKED task > silently downgraded task
11. **README.md enforced** — hard pre-merge check in `GitMergeService.enforceReadmeUpdated()`
12. **`ModelPricingInitializer`** deletes stale rows before upserting — no stale models accumulate
13. **All logging via SLF4J `@Slf4j`** — never `System.out`
14. **`RestTemplateBuilder`** uses `.setConnectTimeout()` / `.setReadTimeout()` (not `.connectTimeout()`)
15. **Circular deps broken via `setXxx()` called from `@PostConstruct`** — `RunRecoveryService.setPipelineHarness()` and `IntegrationManager.setHarness()` follow this pattern
16. **`E2eTestGeneratorAgent`** runs post-merge on a virtual thread, same pattern as `ApiTestSuiteAgent` — non-fatal, never throws, always calls `safeGenerate()` wrapper [Tier 9]
17. **`PlaygroundTestRunner`** uses `RestTemplate` (not `WebClient`) for sync HTTP test execution; always honors `test-run-timeout-seconds` [Tier 9]

---

## Model Pricing Reference (Feb 2026 — 6 canonical rows)

| Provider | Model | Input $/1M | Output $/1M | Role |
|---|---|---|---|---|
| anthropic | `claude-haiku-4-5-20251001` | $3.00 | $15.00 | ✅ ACTIVE — all agents |
| anthropic | `claude-opus-4-5` | $15.00 | $75.00 | Escalation reference |
| openai | `gpt-4o-mini` | $0.15 | $0.60 | Fallback |
| openai | `gpt-4o` | $2.50 | $10.00 | Capable fallback |
| gemini | `gemini-2.0-flash` | $0.10 | $0.40 | Tertiary fallback |
| gemini | `gemini-2.5-pro` | $1.25 | $10.00 | Tertiary capable fallback |

---

## Running the Application

```bash
# Prerequisites: Java 21, PostgreSQL on localhost:5432 (database: agentteam), Anthropic API key in application.yml

# With supervisor (recommended — auto-restarts, health monitoring)
./supervisor.sh start
./supervisor.sh status
./supervisor.sh logs

# Manual
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run -DskipTests

# Compile check
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q

# Dashboard
open http://localhost:8080
```

The `launch.json` at `.claude/launch.json` uses `mvn spring-boot:run -DskipTests` so E2E tests (which require a live server) don't block server startup.

---

## How to Continue in a New Thread

**Tiers 1–8 are complete. Tier 9 (Playground) is planned — see spec above.**
Feed this document at the start of any session to restore full context.

**For bug fixes**: Describe the error + paste stack trace. Reference the class by name.

**For new features**: Describe the feature. Ask for a plan before implementation.

**For Tier 9 implementation**: Start with `E2eTestGeneratorAgent` (follows exact same pattern as `ApiTestSuiteAgent`) → then `PlaygroundController` + `PlaygroundService` → then frontend `playgroundView()` Alpine component → then `PlaygroundTestRunner` + SSE events → finally `PlaygroundWebSocketProxy`.

**Key files to reference**:
- `project-context.md` — this file (at project root)
- `application.yml` — all configuration
- `src/main/resources/prompts/` — all LLM prompts
