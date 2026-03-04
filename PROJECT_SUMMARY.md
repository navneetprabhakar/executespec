# ExecuteSpec — Project Summary

> Multi-agent autonomous coding system built on Spring Boot 3.3 + Spring AI 1.0 + PostgreSQL.
> A human-in-the-loop pipeline that takes a natural-language goal, decomposes it into parallel tasks,
> executes them with LLM worker agents, reviews the result, and optionally deploys to GCP Cloud Run.

---

## Table of Contents

1. [Overview](#1-overview)
2. [Tech Stack](#2-tech-stack)
3. [Architecture — Tier by Tier](#3-architecture--tier-by-tier)
4. [LLM Model Configuration](#4-llm-model-configuration)
5. [Package Structure](#5-package-structure)
6. [REST API Reference](#6-rest-api-reference)
7. [Database Entities](#7-database-entities)
8. [Web UI](#8-web-ui)
9. [LLM Prompts](#9-llm-prompts)
10. [Configuration Reference](#10-configuration-reference)
11. [Running the Application](#11-running-the-application)

---

## 1. Overview

ExecuteSpec is a fully autonomous multi-agent software engineering system. Given a natural-language specification, it:

1. **Plans** — A Planner agent decomposes the goal into 1–6 independent, parallelisable tasks, each assigned an appropriate LLM model based on complexity.
2. **Human approves** — The plan is held at a gate until a human approves or rejects it via the REST API or dashboard UI.
3. **Executes** — Worker agents run tasks in parallel, using file-system and shell tools to write real code.
4. **Compiles** — A Compiler agent checks the output; if errors are found, it iteratively fixes them (up to 3 attempts).
5. **Reviews** — A Reviewer agent inspects the full output for correctness, completeness, and README coverage.
6. **Human merges** — A second gate waits for human approval before committing to the target repository.
7. **Post-merge** — An API spec agent generates OpenAPI 3.0 YAML; a Preview service builds frontend output.
8. **Deploys** *(optional)* — The run can be deployed to GCP Cloud Run (Spring Boot) or served via nginx (static).

The pipeline is fully observable via **Server-Sent Events (SSE)**, a **D3.js Gantt timeline**, a **force-directed comms graph**, and a real-time log viewer — all in a single-page Alpine.js dashboard.

---

## 2. Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.3.0 |
| AI / LLM | Spring AI 1.1.0 |
| Primary LLM | Claude (Anthropic) — Haiku 4.5 + Sonnet 4.6 |
| Fallback LLM | OpenAI GPT-4o |
| Database | PostgreSQL 15+ (`jdbc:postgresql://localhost:5432/agentteam`) |
| ORM | Spring Data JPA + Hibernate |
| Git | JGit 6.9.0 |
| Code generation | Lombok |
| JSON | Jackson |
| Frontend | React 18 + Vite 5 + Tailwind 3.4 + shadcn/ui + Zustand + Recharts/D3 |
| Deployment | GCP Cloud Run + Artifact Registry |
| Build | Maven (JAVA_HOME=/opt/homebrew/opt/openjdk@21) |

---

## 3. Architecture — Tier by Tier

### Tier 1 — Core Agents
Single-threaded pipeline: Planner → Worker → Reviewer.
Spring singleton agents, XML tool-call protocol (`<tool_call>{...}</tool_call>`), file-system and shell tools.

### Tier 2 — Persistence + State Store
All DB access centralised in `StateStore`. Entities: `PipelineRun`, `AgentTask`, `AgentMessage`, `FileLock`, `AgentInbox`.

### Tier 3 — Async REST + Human Gates
- `PipelineHarness.submitRun()` — async with `Semaphore(maxRuns=3)`
- `CompletableFuture` plan gate → resolved by `POST /api/runs/{id}/approve`
- `CompletableFuture` merge gate → resolved by `POST /api/runs/{id}/merge`
- SSE broadcaster (`SseBroadcaster`) emits `RunEvent` records at every state transition

### Tier 4 — Observability
- **Gantt timeline** — D3.js Gantt with gate events (`GET /api/runs/{id}/timeline`)
- **Token bubble chart** — scatter chart per task
- **Agent comms graph** — force-directed SSE message graph
- **Model comparison** — grouped bar chart (`GET /api/model-comparison`)
- `GateEvent` + `CompactionEvent` entities tracked in DB

### Tier 5 — Recovery + Reliability
- **`StuckRunMonitor`** — `@Scheduled` every 2 min; detects inactive tasks
- **`RunRecoveryService`** — classifies failures, retries tasks, escalates to different model/provider
- **`LlmCallGuard`** — wraps ChatModel; retries rate-limit + server errors; classifies permanent failures (AuthFailure, BudgetExhausted, ContextTooLong)
- **`ContextAgent`** — virtual thread; scans runs for patterns
- **`PromptOptimiserAgent`** — virtual thread; analyses failed prompts and proposes improvements

### Tier 6 — GCP Deployments
- **`DeploymentService`** — builds Docker image, pushes to Artifact Registry, deploys to Cloud Run
- Two strategies: `SpringBootDeploymentStrategy` (eclipse-temurin base) + `StaticDeploymentStrategy` (nginx)
- **`WorkspaceExportService`** — zips workspace for download
- **`DeploymentCleanupJob`** — deletes deployments older than `ttl-days` (default 7)

### Tier 7 — External Integrations
Webhook-driven pipeline triggers from:
- **Jira** — status transition fires run; transitions updated on start/complete/fail
- **Confluence** — page label trigger; results written back as child page
- **ServiceNow** — incident categories trigger run; auto-resolve on completion
- **PagerDuty** — incident trigger types; auto-resolve support

All integrations: `skipApproval` flag bypasses the plan gate for fully automated pipelines.

### Tier 8 — Compile, API Spec, Preview
- **`CompilerAgent`** — detects build tool (Maven/Gradle/npm), compiles, sends errors to LLM in JSON-patch format, applies fixes (max 3 attempts); always non-fatal
- **`ApiTestSuiteAgent`** — post-merge; reads `*Controller.java` files, generates `api-docs/openapi.yaml` (OpenAPI 3.0)
- **`PreviewService`** — post-merge; detects frontend files, runs `npm install && npm run build`, marks run `hasFrontend=true`
- **`PreviewController`** — serves `GET /api/runs/{runId}/preview/**` with SPA fallback + path traversal protection

---

## 4. LLM Model Configuration

### Active Models

| Role | Model | Provider | When Used |
|---|---|---|---|
| Planner | `claude-sonnet-4-6` | Anthropic | Task decomposition |
| Reviewer | `claude-haiku-4-5-20251001` | Anthropic | Review after all workers complete |
| LOW workers | `claude-haiku-4-5-20251001` | Anthropic | < 100 lines, thin controllers, configs |
| MEDIUM workers | `claude-haiku-4-5-20251001` | Anthropic | 100–300 lines, service + business logic |
| HIGH workers | `claude-haiku-4-5-20251001` | Anthropic | 300+ lines, complex multi-component |
| Compiler agent | `claude-haiku-4-5-20251001` | Anthropic | Error analysis + multi-file fix |
| API spec agent | `claude-haiku-4-5-20251001` | Anthropic | OpenAPI 3.0 spec generation |
| Context agent | `claude-haiku-4-5-20251001` | Anthropic | Run pattern scanning |
| Optimiser | `claude-haiku-4-5-20251001` | Anthropic | Prompt pattern analysis |
| Recovery escalation | `claude-sonnet-4-6` | Anthropic | Stuck/failed task retry |

### Fallback Chain

```
anthropic/claude-sonnet-4-6  →  openai/gpt-4o      (CAPABLE_CHAIN — planner/recovery)
anthropic/claude-haiku-4-5-20251001  →  openai/gpt-4o  (CHEAP_CHAIN — workers/reviewer)
```

`LlmRouter` selects chain based on primary model name; falls back to `gpt-4o` on rate-limit (15 s sleep) or any non-transient Anthropic error.

### Token Limits

| Setting | Value |
|---|---|
| `spring.ai.anthropic.chat.options.max-tokens` | 16 000 |
| `spring.ai.openai.chat.options.max-tokens` | 16 000 |
| Token budget LOW | 2 048 |
| Token budget MEDIUM | 4 096 |
| Token budget HIGH | 8 192 |

---

## 5. Package Structure

```
com.navneet.agentteam
├── AgentTeamApplication.java              # Entry point; @EnableScheduling
│
├── agent/
│   ├── model/                             # TaskPlan, TaskDefinition, WorkerReport, ReviewReport, …
│   ├── PlannerAgent.java                  # Goal → tasks; planWithEstimates() for feasibility
│   ├── WorkerAgent.java                   # XML tool-call protocol; per-task state in params
│   ├── ReviewerAgent.java                 # Quality + README gate check
│   ├── TokenBudgetMonitor.java
│   ├── LlmRouter.java                     # Provider fallback with CHEAP/CAPABLE chains
│   └── CostEstimator.java                 # DB-backed cost estimate
│
├── api/
│   ├── controller/                        # RunController, SseController, MetricsController,
│   │                                      # PricingController, FeasibilityController,
│   │                                      # TimelineController, DeploymentController,
│   │                                      # HealthController
│   ├── dto/                               # All request/response DTOs
│   └── RunMapper.java
│
├── apitests/                              # ApiTestSuiteAgent, ApiTestSuiteProperties
├── compiler/                             # CompilerAgent, CompileResult, CompilerProperties
│
├── config/
│   ├── AgentProperties.java              # @ConfigurationProperties(prefix="agent")
│   ├── AgentModelConfig.java             # Spring AI ChatModel beans
│   ├── ModelPricingInitializer.java      # @PostConstruct — upserts 7 pricing rows
│   └── WebConfig.java
│
├── context/                              # ContextAgent, ContextAgentScanner, ContextAgentProperties
│
├── deployment/
│   ├── strategy/                         # DeploymentStrategy, SpringBootDeploymentStrategy,
│   │                                     # StaticDeploymentStrategy
│   ├── DeploymentService.java
│   ├── DeploymentProperties.java
│   ├── DockerfileGenerator.java
│   ├── ArtifactRegistryClient.java
│   ├── CloudRunClient.java
│   ├── WorkspaceExportService.java
│   ├── WorkspaceFileService.java
│   └── DeploymentCleanupJob.java
│
├── harness/
│   ├── PipelineHarness.java              # Orchestrator; Semaphore gates, CompletableFutures
│   ├── WorkerPool.java                   # Concurrent task execution + cancel/retry
│   ├── ApprovalGate.java                 # CompletableFuture gate wrapper
│   ├── GitMergeService.java              # README enforcement hard gate
│   ├── WorkspaceInitService.java
│   └── MergeBlockedException.java
│
├── integration/
│   ├── jira/                             # JiraAdapter, JiraClient, JiraWebhookPayload
│   ├── confluence/                       # ConfluenceAdapter, ConfluenceClient, …
│   ├── servicenow/                       # ServiceNowAdapter, ServiceNowClient, …
│   ├── pagerduty/                        # PagerDutyAdapter, PagerDutyClient, …
│   ├── IntegrationManager.java           # setHarness() breaks circular dep
│   ├── IntegrationController.java
│   ├── IntegrationProperties.java
│   ├── IntegrationConfig.java
│   └── IntegrationEventLogCleanupJob.java
│
├── optimiser/                            # PromptOptimiserAgent, PromptPatternAnalyser,
│                                         # PromptAuditService, PromptOptimiserProperties
│
├── preview/                              # PreviewService, PreviewController, PreviewProperties
│
├── pricing/                              # ModelPricing, ModelPricingId, ModelPricingRepository
│
├── recovery/
│   ├── LlmCallGuard.java                 # Retry + failure classification
│   ├── StuckRunMonitor.java              # @Scheduled every 2 min
│   ├── RunRecoveryService.java           # setPipelineHarness() circular dep break
│   ├── StuckRunEvaluator.java
│   ├── EscalationService.java
│   ├── RecoveryProperties.java
│   └── StuckClassification.java          # Enum: CONTEXT_LIMIT, RATE_LIMIT, …
│
├── repo/
│   └── GitHubRepoService.java
│
├── sse/
│   ├── RunEvent.java                     # Record + 20+ factory methods
│   └── SseBroadcaster.java              # ConcurrentHashMap<runId, CopyOnWriteArrayList<SseEmitter>>
│
├── state/
│   ├── StateStore.java                   # All DB access; inner records for metrics/timeline
│   ├── InboxService.java                 # Per-agent inbox
│   ├── entity/                           # 14 JPA entities
│   └── repository/                       # 13 JPA repositories
│
└── tool/
    ├── FileSystemTool.java               # .normalize() prevents path traversal
    ├── ShellTool.java
    ├── GitTool.java
    └── FileLockTool.java                 # releaseAllLocksForTask / releaseAllLocksForRun
```

---

## 6. REST API Reference

### Runs

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/runs` | Submit new run — returns 202 with `runId` |
| `GET` | `/api/runs` | List runs; filter by `?status=` |
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

### Metrics & Comparison

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/metrics` | Global run/token/cost statistics |
| `GET` | `/api/model-comparison?days=30` | Per-model performance stats |

### Pricing

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/pricing` | All model pricing rows |
| `GET` | `/api/pricing/{provider}/{model}` | Single model pricing |
| `PUT` | `/api/pricing/{provider}/{model}` | Upsert pricing |

### Feasibility

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/feasibility` | Estimate cost+complexity without running workers |

### Deployments

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/deployments` | Deploy run to GCP Cloud Run |
| `GET` | `/api/deployments` | List all deployments |
| `GET` | `/api/deployments/{id}` | Deployment detail |
| `DELETE` | `/api/deployments/{id}` | Delete deployment |
| `POST` | `/api/deployments/{id}/rollback` | Rollback to previous version |
| `GET` | `/api/deployments/live` | List LIVE deployments only |

### Integrations

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/integrations/{source}/webhook` | Inbound webhook (jira, confluence, servicenow, pagerduty) |
| `POST` | `/api/integrations/confluence/trigger` | Manual Confluence page trigger |
| `GET` | `/api/integrations/runs?source=` | List integration-triggered runs |
| `GET` | `/api/integrations/status` | Enabled integration sources |

### Health

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/health` | DB status, uptime, active/queued/total runs |

---

## 7. Database Entities

| Entity | Table | Purpose |
|---|---|---|
| `PipelineRun` | `pipeline_run` | Top-level run record; `skipApproval` flag for integrations |
| `AgentTask` | `agent_task` | One row per task; tracks `assignedModel`, `stuckDetectedAt`, `recoveryAttempt` |
| `AgentMessage` | `agent_message` | Per-task activity log; `taskId` FK |
| `FileLock` | `file_lock` | Concurrent file access control across workers |
| `AgentInbox` | `agent_inbox` | Per-agent message inbox for provider-fallback notifications |
| `GateEvent` | `gate_event` | Plan/merge gate transition history (Tier 4 Gantt) |
| `CompactionEvent` | `compaction_event` | Context compaction checkpoints (Tier 4) |
| `RunRecovery` | `run_recovery` | Recovery attempt log per run (Tier 5) |
| `PromptAuditEntry` | `prompt_audit_entry` | Prompt pattern audit trail (Tier 5) |
| `Deployment` | `deployment` | GCP deployment record with status + URL (Tier 6) |
| `RunRepo` | `run_repo` | Git repo info per run (Tier 6) |
| `IntegrationRun` | `integration_run` | Maps integration source → pipeline run (Tier 7) |
| `IntegrationEventLog` | `integration_event_log` | Raw webhook event history (Tier 7) |
| `ModelPricing` | `model_pricing` | Input/output cost per million tokens, seeded on startup |

Schema is auto-managed via `spring.jpa.hibernate.ddl-auto: update` — safe to restart.

---

## 8. Web UI

React 18 SPA (`frontend/` directory) — built with **Vite 5**, **Tailwind 3.4**, **shadcn/ui** components, **Zustand** stores, **Recharts** + **D3.js v7** for charts.
Production build output goes to `src/main/resources/static/` via `npm run build`. Dev server: `npm --prefix frontend run dev` → `:5173` (proxies `/api` → `:8080`).

### Pages

| Page | Route | Description |
|---|---|---|
| **Runs** | `/` | 60/40 split: run list + live detail panel with 7 tabs |
| **Dashboard** | `/dashboard` | 4 metric cards + TokenBubbleChart + ModelComparisonChart + ActivityFeed |
| **Pricing** | `/pricing` | Model pricing table + FeasibilityEstimator |
| **Deployments** | `/deployments` | GCP deployment list + status |
| **Integrations** | `/integrations` | Integration source status + manual trigger |
| **Settings** | `/settings` | Application configuration |

### Run Detail Tabs

| Tab | Description |
|---|---|
| Overview | Status, timing, model, cost, approve/merge buttons |
| Tasks | Per-task status card (TaskCard), assigned model, retry button |
| Log | SSE-powered live log viewer (useSse hook + RunDetailTabs) |
| Timeline | D3.js Gantt of task durations + gate events |
| Files | Workspace file browser |
| Preview | Embedded iframe for frontend previews (`hasFrontend=true`) |
| API Docs | Swagger UI from generated `openapi.yaml` (`hasApiSpec=true`) |

### Key Frontend Files

| Path | Purpose |
|---|---|
| `frontend/src/lib/api.ts` | All typed fetch wrappers (20+ endpoints) |
| `frontend/src/stores/` | useRunStore, useMetricsStore, useSseStore, useUiStore (Zustand) |
| `frontend/src/hooks/useSse.ts` | SSE EventSource with exponential backoff reconnect |
| `frontend/src/components/layout/` | Sidebar (Framer Motion), Header, AppLayout |
| `frontend/src/components/runs/` | RunCard, RunList, RunDetail, RunDetailTabs, TaskCard, NewRunModal |
| `frontend/src/components/dashboard/` | MetricCard (Recharts sparkline), TokenBubbleChart, ModelComparisonChart (D3), ActivityFeed |

---

## 9. LLM Prompts

All prompts in `src/main/resources/prompts/`:

| File | Agent | Key Rules |
|---|---|---|
| `planner.txt` | PlannerAgent | 6 hard rules; `assigned_model` routing (Sonnet=HIGH/MEDIUM, Haiku=LOW); 1–6 tasks |
| `worker.txt` | WorkerAgent | 7 steps; step 7 = **UPDATE README.md** (hard requirement); XML tool-call format |
| `worker-openai.txt` | WorkerAgent (OpenAI) | OpenAI-compatible variant |
| `worker-gemini.txt` | WorkerAgent (Gemini) | Gemini-compatible variant |
| `reviewer.txt` | ReviewerAgent | 5 checks; step 5 = **README UPDATE CHECK** |
| `context-agent.txt` | ContextAgent | Lightweight run pattern analysis |
| `compiler.txt` | CompilerAgent | Compile error analysis; JSON-patch fix format |
| `api-test-suite.txt` | ApiTestSuiteAgent | OpenAPI 3.0 YAML spec from controller code |

---

## 10. Configuration Reference

### `application.yml` — Key Sections

```yaml
spring.ai.anthropic.chat.options:
  model: claude-haiku-4-5-20251001    # default; agents override per-call
  max-tokens: 16000

spring.ai.openai.chat.options:
  model: gpt-4o
  max-tokens: 16000

agent:
  workspace: ./workspace
  concurrency.max-runs: 5              # max simultaneous pipeline runs
  token-budget: {low: 2048, medium: 4096, high: 8192}
  compaction: {warn-pct: 0.65, compact-pct: 0.75, hard-stop-pct: 0.90}

  recovery:
    enabled: true
    warn-threshold-minutes: 15
    act-threshold-minutes: 30
    max-task-retries: 1
    capable-models:
      anthropic: claude-sonnet-4-6
      openai: gpt-4o

  context:  {enabled: true, model: claude-haiku-4-5-20251001, run-interval: 1}
  optimiser: {enabled: true, run-interval: 10, analysis-window-runs: 20}

  compiler:
    enabled: true
    max-attempts: 3
    timeout-seconds: 300
    model: claude-haiku-4-5-20251001

  api-test-suite:
    enabled: true
    model: claude-haiku-4-5-20251001

  deployment:
    enabled: true
    gcp-project: ${GCP_PROJECT:my-gcp-project}
    gcp-region: us-central1
    ttl-days: 7

  integrations:
    jira:       {enabled: false, …}
    confluence: {enabled: false, …}
    servicenow: {enabled: false, …}
    pagerduty:  {enabled: false, …}
```

### Environment Variables

| Variable | Default | Purpose |
|---|---|---|
| `ANTHROPIC_API_KEY` | *(required)* | Anthropic API key |
| `OPENAI_API_KEY` | *(required for fallback)* | OpenAI API key |
| `POSTGRES_USER` | `postgres` | DB username |
| `POSTGRES_PASSWORD` | `postgres` | DB password |
| `GITHUB_PAT` | *(empty)* | GitHub personal access token |
| `GITHUB_OWNER` | `navneetprabhakar` | Default GitHub org/user |
| `GCP_PROJECT` | `my-gcp-project` | GCP project ID for deployments |

---

## 11. Running the Application

### Prerequisites

- Java 21 (`/opt/homebrew/opt/openjdk@21`)
- PostgreSQL running on `localhost:5432` with database `agentteam`
- Anthropic API key configured in `application.yml`

### Start (Manual)

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run
```

### Start with Supervisor (Recommended)

The `supervisor.sh` script at the project root provides a full state-machine supervisor:

```bash
# Start (builds + starts, monitors health, auto-restarts on crash)
./supervisor.sh start

# Check status
./supervisor.sh status

# View logs
./supervisor.sh logs

# Restart
./supervisor.sh restart

# Stop
./supervisor.sh stop

# Build only
./supervisor.sh build
```

**Supervisor features:**
- States: `STARTING → RUNNING → RESTARTING → UNHEALTHY → CRASHED → FAILED → STOPPED`
- Exponential backoff: 5 s → 10 s → 20 s → … → 120 s cap
- Max crashes before FAILED: 10
- Health check via `GET /api/health` every 20 s, startup wait up to 120 s
- PID tracking in `.supervisor/app.pid`
- Logs at `.supervisor/app.log` + `.supervisor/monitor.log`

### Compile Check

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q
```

### Access the Dashboard

```
http://localhost:8080
```

---

## Build Status

`mvn compile` — ✅ passes clean (Tiers 1–8 complete)
`mvn test` — ✅ 104 unit tests pass; `@Tag("e2e")` and `@Tag("integration")` excluded (require live DB + Docker)

**Total:** ~150 Java source files · 33 REST endpoints · 14 DB entities · 8 LLM prompts · React 18 SPA frontend
