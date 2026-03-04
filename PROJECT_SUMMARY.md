# ExecuteSpec — Project Summary

> Multi-agent autonomous coding system built on Spring Boot 3.3 + Spring AI 1.1.0 + PostgreSQL.
> A human-in-the-loop pipeline that takes a natural-language goal, decomposes it into parallel tasks,
> executes them with LLM worker agents, reviews the result, and optionally deploys to GCP Cloud Run.
> Full auth/multi-tenancy (JWT, orgs, projects, tiers). Per-user GitHub PAT. 10-language deployment.

---

## Table of Contents

1. [Overview](#1-overview)
2. [Tech Stack](#2-tech-stack)
3. [Architecture — Tier by Tier](#3-architecture--tier-by-tier)
4. [LLM Model Configuration](#4-llm-model-configuration)
5. [Auth & Multi-Tenancy](#5-auth--multi-tenancy)
6. [Per-User GitHub Integration](#6-per-user-github-integration)
7. [10-Language Deployment](#7-10-language-deployment)
8. [Package Structure](#8-package-structure)
9. [REST API Reference](#9-rest-api-reference)
10. [Database Entities](#10-database-entities)
11. [Web UI](#11-web-ui)
12. [LLM Prompts](#12-llm-prompts)
13. [Configuration Reference](#13-configuration-reference)
14. [Running the Application](#14-running-the-application)

---

## 1. Overview

ExecuteSpec is a fully autonomous multi-agent software engineering system. Given a natural-language specification, it:

1. **Plans** — A Planner agent decomposes the goal into 1–6 parallelisable tasks using `claude-sonnet-4-6`.
2. **Human approves** — The plan is held at a gate until a human approves or rejects it via REST API or dashboard.
3. **Executes** — Worker agents run tasks in parallel, using file-system and shell tools to write real code.
4. **Compiles** — A Compiler agent checks the output; if errors are found, it iteratively fixes them (up to 3 attempts).
5. **Reviews** — A Reviewer agent inspects the full output for correctness, completeness, and README coverage.
6. **Human merges** — A second gate waits for human approval before committing to the target repository.
7. **Post-merge** — An API spec agent generates OpenAPI 3.0 YAML; a Preview service builds frontend output; optional GitHub push.
8. **Deploys** *(optional)* — 10 language strategies auto-detect project type, generate an optimised Dockerfile, and deploy to GCP Cloud Run.

The pipeline is fully observable via **Server-Sent Events (SSE)**, a **D3.js Gantt timeline**, a **force-directed comms graph**, and a real-time log viewer — all in a React 18 SPA dashboard.

---

## 2. Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.3.0 |
| AI / LLM | Spring AI 1.1.0 |
| Primary LLM | Claude (Anthropic) — Haiku 4.5 (workers) + Sonnet 4.6 (planner) |
| Fallback LLM | OpenAI GPT-4o / GPT-4o-mini |
| Database | PostgreSQL 15+ (`jdbc:postgresql://localhost:5432/agentteam`) |
| ORM | Spring Data JPA + Hibernate |
| Migrations | Flyway V1–V8 |
| Git | JGit 6.9.0 |
| Auth | JWT (Spring Security) — 24h access + 7d refresh tokens |
| Code generation | Lombok |
| JSON | Jackson |
| Frontend | React 18 + Vite 5 + Tailwind 3.4 + shadcn/ui + Zustand + Recharts/D3.js |
| Deployment | GCP Cloud Run + Artifact Registry |
| Build | Maven (JAVA_HOME=/opt/homebrew/opt/openjdk@21) |
| Maven artifactId | `executespec` |

---

## 3. Architecture — Tier by Tier

### Tier 1 — Core Loop
Single-threaded pipeline: Planner → Worker → Reviewer.
Spring singleton agents, XML tool-call protocol (`<tool_call>{...}</tool_call>`), file-system and shell tools.

### Tier 2 — Persistence + State Store
All DB access centralised in `StateStore`. Entities: `PipelineRun`, `AgentTask`, `AgentMessage`, `FileLock`, `AgentInbox`.

### Tier 3 — Async REST + React SPA + SSE
- `PipelineHarness.submitRun()` — async with `Semaphore(maxRuns=5)`
- `CompletableFuture` plan gate → resolved by `POST /api/runs/{id}/approve`
- `CompletableFuture` merge gate → resolved by `POST /api/runs/{id}/merge`
- SSE broadcaster (`SseBroadcaster`) emits `RunEvent` records; event types are `SCREAMING_SNAKE_CASE`; payload field is `payload`
- React 18 + Vite 5 + Tailwind 3.4 + shadcn/ui SPA dashboard

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
- **`ContextAgent`** — virtual thread; post-merge; scans runs for patterns; updates `CODEBASE_CONTEXT.md`
- **`PromptOptimiserAgent`** — virtual thread; every 10 runs; analyses failed prompts and proposes improvements

### Tier 6 — GCP Deployments + 10-Language Support
Docker build → Artifact Registry → Cloud Run.
**10 deployment strategies** auto-detect project type via `@Order`-sorted beans:
Spring Boot (1), Gradle (2), Node.js (3), Python (4), Go (5), Ruby (6), PHP (7), Rust (8), .NET (9), Static (10).
Versioned revisions, 7-day TTL cleanup, workspace zip download, `WorkspaceFileService` with path traversal protection.

### Tier 7 — External Integrations
Webhook-driven pipeline triggers from Jira, Confluence, ServiceNow, PagerDuty.
Pattern: `InboundAdapter → normalise to RunRequest → pipeline → OutboundAdapter → write back`.
`skipApproval` flag for fully automated pipelines. Idempotency via `integration_event_log`.

### Tier 8 — Compile, API Spec, Preview
- **`CompilerAgent`**: detects build tool (pom.xml/build.gradle/package.json), compiles, sends errors to LLM in JSON-patch format, applies fixes iteratively (max 3 attempts); always non-fatal
- **`ApiTestSuiteAgent`**: post-merge; reads `*Controller.java` files; generates `api-docs/openapi.yaml` (OpenAPI 3.0)
- **`PreviewService`**: post-merge; detects frontend files; runs `npm install && npm build`; marks run `hasFrontend=true`
- **`HealthController`**: `GET /api/health` — DB ping, uptime formatted, run counts

### Auth & Multi-Tenancy
Full JWT-based authentication with multi-org support, project membership, and tier enforcement.
Flyway V4–V8. Roles: SUPER_ADMIN, ORG_ADMIN, BILLING_ADMIN, DEVELOPER. Tiers: FREE (5/mo), PRO (50/mo), ENTERPRISE (unlimited). Default admin: `admin@executespec.ai` / `REDACTED_FOR_PUBLIC_RELEASE`.

### Per-User GitHub PAT (V8)
Each user stores their own GitHub PAT in Settings. PAT stamped onto `pipeline_run` at submission. Run modes: `NEW` (create repo), `EXISTING` (clone → modify → push). `WorkspaceInitService.cloneFromRepo()` handles EXISTING workspaces.

### 🔲 Tier 9 — Playground (planned)
Interactive environment for loading, previewing, and testing generated projects without leaving the dashboard.

---

## 4. LLM Model Configuration

### Active Models

| Role | Model | Provider | When Used |
|---|---|---|---|
| Planner | `claude-sonnet-4-6` | Anthropic | Task decomposition |
| Reviewer | `claude-haiku-4-5-20251001` | Anthropic | Review after all workers complete |
| Workers (all complexities) | `claude-haiku-4-5-20251001` | Anthropic | Code writing, compilation, API spec |
| Context/Optimiser | `claude-haiku-4-5-20251001` | Anthropic | Post-merge scanning + prompt analysis |
| Recovery escalation | `claude-sonnet-4-6` | Anthropic | Stuck/failed task retry |

### Fallback Chains

```
ARCHITECT_CHAIN: anthropic/claude-opus-4-6           →  openai/gpt-4o
CAPABLE_CHAIN:   anthropic/claude-sonnet-4-6         →  openai/gpt-4o
UTILITY_CHAIN:   anthropic/claude-haiku-4-5-20251001 →  openai/gpt-4o-mini
```

`LlmRouter` selects chain based on primary model name; falls back on rate-limit (15 s sleep) or any non-transient Anthropic error. Gemini fully removed.

### Token Limits

| Setting | Value |
|---|---|
| `spring.ai.anthropic.chat.options.max-tokens` | 16 000 |
| `spring.ai.openai.chat.options.max-tokens` | 16 000 |
| Token budget LOW | 2 048 |
| Token budget MEDIUM | 4 096 |
| Token budget HIGH | 8 192 |
| Compaction warn-pct | 0.25 |
| Compaction compact-pct | 0.35 |
| Compaction hard-stop-pct | 0.55 |

---

## 5. Auth & Multi-Tenancy

### JWT Configuration
- Access token: 1440 minutes (24 hours)
- Refresh token: 7 days

### Tiers

| Tier | Runs/Month | Notes |
|---|---|---|
| FREE | 5 (fixed) | Own runs only, no project access |
| PRO | 50 (org-overridable via `monthlyRunLimitOverride`) | Full project access |
| ENTERPRISE | Unlimited* | `api_keys_provided=true` → unlimited; else 5000/month |

### Roles

**Org-level** (`org_member.role`): `SUPER_ADMIN`, `ORG_ADMIN`, `BILLING_ADMIN`, `DEVELOPER`

**Project-level** (`project_member.project_role`): `PROJECT_ADMIN`, `PROJECT_DEVELOPER`

### RunVisibilitySpec
- `SUPER_ADMIN` → all runs
- `ORG_ADMIN` / `BILLING_ADMIN` → all runs in org
- `DEVELOPER` → runs in projects they belong to
- `FREE` → own runs only

### Flyway Migrations

| Version | Description |
|---|---|
| V1 | Core pipeline schema |
| V2–V3 | Feature additions |
| V4 | Auth schema: `app_user`, `org`, `org_member`, `project`, `project_member` |
| V5 | Seed admin user (`admin@executespec.ai` / `REDACTED_FOR_PUBLIC_RELEASE`) |
| V6 | `tier_config` table + org run-limit overrides |
| V7 | Fix admin password hash (BCrypt correction) |
| V8 | `github_pat` + `github_owner` on `app_user` + `pipeline_run` |

---

## 6. Per-User GitHub Integration

### Settings → GitHub Integration
Users enter their GitHub PAT + owner (username or org) in the Settings page. Saved via `PUT /api/auth/settings/github`.

### Run Modes
In the New Run modal, users can select:
- **None** — no GitHub interaction
- **Create new repo** — `repoMode=NEW`: auto-create private GitHub repo after merge, push code; `run.repoUrl` populated
- **Start from existing** — `repoMode=EXISTING`: clone specified URL at workspace init, modify, push back

### PAT Priority
Run-level PAT (from user account) > System-level PAT (from `application.yml`).

### UI
- **NewRunModal.tsx**: GitHub collapsible section with 3 radio options; animated URL input for EXISTING mode; warning shown when no PAT configured
- **RunCard.tsx**: shows GitHub link chip (with GitHub icon) when `run.repoUrl` is set
- **SettingsPage.tsx**: PAT password input (show/hide toggle) + owner field + save button + status badge

---

## 7. 10-Language Deployment

Strategies are Spring `@Component` beans collected as `List<DeploymentStrategy>` — `@Order` determines detection priority.

| Priority | Strategy | Detection | Container |
|---|---|---|---|
| 1 | SpringBootDeploymentStrategy | `pom.xml` | `eclipse-temurin:21-jdk` → `eclipse-temurin:21-jre-alpine` |
| 2 | GradleDeploymentStrategy | `build.gradle` / `build.gradle.kts` | `gradle:8-jdk21` → `eclipse-temurin:21-jre-alpine` |
| 3 | NodeDeploymentStrategy | `package.json` | Server→`node:20-slim`; SPA build→multi-stage nginx |
| 4 | PythonDeploymentStrategy | `requirements.txt` / `pyproject.toml` / `Pipfile` | `python:3.11-slim`; Django/FastAPI/Flask auto-detected |
| 5 | GoDeploymentStrategy | `go.mod` | `golang:1.22-alpine` → `alpine:3.19` |
| 6 | RubyDeploymentStrategy | `Gemfile` | `ruby:3.3-slim`; Rails/Sinatra/Rack auto-detected |
| 7 | PhpDeploymentStrategy | `composer.json` | `php:8.2-apache` |
| 8 | RustDeploymentStrategy | `Cargo.toml` | `rust:1.77-slim` → `debian:bookworm-slim` |
| 9 | DotNetDeploymentStrategy | `*.csproj` / `*.sln` | `dotnet/sdk:8.0` → `dotnet/aspnet:8.0` |
| 10 | StaticDeploymentStrategy | `index.html` | `nginx:1.25-alpine` |

All containers expose port 8080. `LocalPreviewService.detectProjectType()` mirrors the same priority order.

---

## 8. Package Structure

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
│   ├── LlmRouter.java                     # ARCHITECT/CAPABLE/UTILITY chains; provider fallback
│   └── CostEstimator.java                 # DB-backed cost estimate
│
├── api/
│   ├── controller/                        # RunController, SseController, MetricsController,
│   │                                      # PricingController, FeasibilityController,
│   │                                      # TimelineController, DeploymentController,
│   │                                      # LocalPreviewController, IntegrationController,
│   │                                      # IntegrationStatusController
│   ├── dto/                               # All request/response DTOs
│   └── RunMapper.java
│
├── apitests/                              # ApiTestSuiteAgent, ApiTestSuiteProperties
│
├── auth/
│   ├── entity/                            # AppUser (+githubPat/githubOwner), Org, OrgMember,
│   │                                      # Project, ProjectMember, TierConfig
│   ├── repository/                        # Auth entity repositories
│   ├── service/                           # AuthService, OrgService, ProjectService,
│   │                                      # TierEnforcementService, InvitationService
│   ├── controller/                        # AuthController, OrgController, ProjectController,
│   │                                      # SuperAdminController
│   ├── dto/                               # UserDto (+githubOwner, hasGithubPat), OrgDto,
│   │                                      # ProjectDto, OrgMemberDto, ProjectMemberDto
│   └── security/                          # JwtTokenProvider, AppUserDetails, SecurityConfig
│
├── compiler/                             # CompilerAgent, CompileResult, CompilerProperties
│
├── config/
│   ├── AgentProperties.java              # @ConfigurationProperties(prefix="agent")
│   ├── AgentModelConfig.java             # Spring AI ChatModel beans; NO AnthropicCacheInterceptor
│   ├── ModelPricingInitializer.java      # @PostConstruct — upserts canonical pricing rows
│   └── WebConfig.java
│
├── context/                              # ContextAgent, ContextAgentScanner, ContextAgentProperties
│
├── deployment/
│   ├── strategy/                         # DeploymentStrategy interface + 10 implementations
│   │                                     # (@Order 1–10: Spring Boot through Static)
│   ├── PreviewAgent.java                 # Deterministic Dockerfile + docker-compose.yml gen
│   ├── DeploymentService.java
│   ├── DeploymentProperties.java
│   ├── DockerfileGenerator.java          # Routes to strategy; findFirst() on @Order-sorted list
│   ├── LocalPreviewService.java          # detectProjectType() mirrors @Order priority
│   ├── ArtifactRegistryClient.java
│   ├── CloudRunClient.java
│   ├── WorkspaceExportService.java
│   ├── WorkspaceFileService.java
│   └── DeploymentCleanupJob.java
│
├── harness/
│   ├── PipelineHarness.java              # Orchestrator; clone-vs-init branch; run-level PAT push
│   ├── WorkerPool.java                   # Concurrent task execution + cancel/retry
│   ├── ApprovalGate.java                 # CompletableFuture gate wrapper
│   ├── GitMergeService.java              # README enforcement hard gate
│   ├── WorkspaceInitService.java         # ensureRunWorkspace() + cloneFromRepo()
│   ├── event/                            # PipelineEvent, PostMergeOrchestrator
│   └── MergeBlockedException.java
│
├── integration/
│   ├── jira/                             # JiraAdapter, JiraClient, JiraWebhookPayload
│   ├── confluence/                       # ConfluenceAdapter, ConfluenceClient, …
│   ├── servicenow/                       # ServiceNowAdapter, ServiceNowClient, …
│   ├── pagerduty/                        # PagerDutyAdapter, PagerDutyClient, …
│   ├── IntegrationManager.java           # setHarness() breaks circular dep
│   └── IntegrationEventLogCleanupJob.java
│
├── optimiser/                            # PromptOptimiserAgent, PromptPatternAnalyser,
│                                         # PromptAuditService, PromptOptimiserProperties
│
├── recovery/
│   ├── LlmCallGuard.java                 # Retry + failure classification
│   ├── StuckRunMonitor.java              # @Scheduled every 2 min
│   ├── RunRecoveryService.java           # setPipelineHarness() circular dep break
│   ├── StuckRunEvaluator.java
│   ├── EscalationService.java
│   └── RecoveryProperties.java
│
├── repo/
│   └── GitHubRepoService.java            # createRepo() with per-call PAT/owner override
│
├── sse/
│   ├── RunEvent.java                     # Record + factory methods; SCREAMING_SNAKE_CASE types
│   └── SseBroadcaster.java
│
├── state/
│   ├── StateStore.java                   # All DB access; listRuns() accepts optional projectId
│   ├── InboxService.java                 # Per-agent inbox
│   ├── entity/                           # 13 JPA entities
│   └── repository/                       # 13 JPA repositories
│
├── tenant/
│   └── TenantInterceptor.java            # JPA EntityListeners — injects tenantId on save
│
└── tool/
    ├── FileSystemTool.java               # .normalize() prevents path traversal
    ├── ShellTool.java
    ├── GitTool.java                      # commitIfDirty() — silent no-op if clean
    └── FileLockTool.java                 # releaseAllLocksForTask / releaseAllLocksForRun
```

---

## 9. REST API Reference

### Runs

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/runs` | Submit new run — returns 202 with `runId` |
| `GET` | `/api/runs?status=&projectId=` | List runs with filters |
| `GET` | `/api/runs/{runId}` | Full run detail with tasks |
| `POST` | `/api/runs/{runId}/approve` | Resolve plan gate (approve/reject) |
| `POST` | `/api/runs/{runId}/merge` | Resolve merge gate (approve/reject) |
| `DELETE` | `/api/runs/{runId}` | Cancel in-progress run |
| `POST` | `/api/runs/{runId}/tasks/{taskId}/retry` | Retry PARTIAL/BLOCKED/FAILED/PENDING/LOCKED task |
| `GET` | `/api/runs/{runId}/events` | **SSE stream** — live `RunEvent` feed |
| `GET` | `/api/runs/{runId}/timeline` | Gantt + gate event data for D3.js |
| `GET` | `/api/runs/{runId}/download` | Download workspace as `.zip` |
| `GET` | `/api/runs/{runId}/files` | List workspace files |
| `GET` | `/api/runs/{runId}/file?path=` | Read single workspace file |
| `POST` | `/api/runs/{runId}/preview/start` | Start local preview |
| `DELETE` | `/api/runs/{runId}/preview/stop` | Stop local preview |

### Auth & Org

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/auth/register` | Register new user |
| `POST` | `/api/auth/login` | Login → JWT tokens |
| `POST` | `/api/auth/refresh` | Refresh access token |
| `POST` | `/api/auth/logout` | Invalidate refresh token |
| `PUT` | `/api/auth/settings/github` | Save GitHub PAT + owner for current user |
| `GET` | `/api/org/me` | My org details |
| `GET` | `/api/org/me/members` | Org member list |
| `PUT` | `/api/org/me/members/{id}/suspend` | Suspend member |
| `DELETE` | `/api/org/me/members/{id}` | Remove member |

### Projects

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/projects` | List projects (visibility-controlled) |
| `POST` | `/api/projects` | Create project (ORG_ADMIN+) |
| `GET` | `/api/projects/{id}` | Project detail |
| `PUT` | `/api/projects/{id}` | Update project |
| `PUT` | `/api/projects/{id}/archive` | Archive project |
| `GET` | `/api/projects/{id}/members` | Project members |
| `POST` | `/api/projects/{id}/members` | Add member |
| `DELETE` | `/api/projects/{id}/members/{userId}` | Remove member |

### Metrics & Comparison

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/metrics` | Global run/token/cost statistics |
| `GET` | `/api/model-comparison?days=30` | Per-model performance stats |
| `GET` | `/api/pricing` | All model pricing rows |
| `PUT` | `/api/pricing/{provider}/{model}` | Upsert pricing |
| `POST` | `/api/feasibility` | Estimate cost+complexity without running workers |
| `GET` | `/api/health` | DB ping, uptime, run counts |

### Deployments

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/deployments` | Deploy run to GCP Cloud Run |
| `GET` | `/api/deployments` | List all deployments |
| `GET` | `/api/deployments/{id}` | Deployment detail |
| `DELETE` | `/api/deployments/{id}` | Delete deployment |
| `POST` | `/api/deployments/{id}/rollback` | Rollback to previous version |
| `GET` | `/api/deployments/live` | List LIVE deployments only |

### Integrations & Admin

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/integrations/{source}/webhook` | Inbound webhook (jira/confluence/servicenow/pagerduty) |
| `GET` | `/api/integrations/status` | Enabled integration sources |
| `GET` | `/api/admin/users` | All users (SUPER_ADMIN) |
| `PUT` | `/api/admin/users/{id}/tier` | Change user tier |
| `PUT` | `/api/admin/orgs/{id}/run-limit` | Override org run limit |

---

## 10. Database Entities

| Entity | Table | Purpose |
|---|---|---|
| `PipelineRun` | `pipeline_run` | Top-level run record; `skipApproval`, `projectId`, `orgId`, `createdByUserId`, `repoMode`, `repoUrl`, `githubPat` (V8), `githubOwner` (V8) |
| `AgentTask` | `agent_task` | Per-task status; `assignedModel`, `stuckDetectedAt`, `recoveryAttempt` |
| `AgentMessage` | `agent_message` | Per-task activity log |
| `FileLock` | `file_lock` | Concurrent file access control |
| `AgentInbox` | `agent_inbox` | Per-agent message inbox |
| `GateEvent` | `gate_event` | Plan/merge gate transition history |
| `CompactionEvent` | `compaction_event` | Context compaction checkpoints |
| `RunRecovery` | `run_recovery` | Recovery attempt log per run |
| `PromptAuditEntry` | `prompt_audit_entry` | Prompt pattern audit trail |
| `Deployment` | `deployment` | GCP deployment record; 10-strategy `DeployStrategy` enum |
| `RunRepo` | `run_repo` | Git repo info per run |
| `IntegrationRun` | `integration_run` | Maps integration source → pipeline run |
| `IntegrationEventLog` | `integration_event_log` | Raw webhook event history (7-day TTL) |
| `ModelPricing` | `model_pricing` | Input/output cost per million tokens |
| `AppUser` | `app_user` | Users; `githubPat`, `githubOwner` (V8) |
| `Org` | `org` | Organisations; tier, run-limit overrides |
| `OrgMember` | `org_member` | User ↔ org with role |
| `Project` | `project` | Projects within org |
| `ProjectMember` | `project_member` | User ↔ project with projectRole |
| `TierConfig` | `tier_config` | Per-tier limits (seeded V6) |

Schema managed via **Flyway V1–V8** (not hibernate DDL auto).

Run status flow: `QUEUED → PLANNING → AWAITING_APPROVAL → WORKING → REVIEWING → AWAITING_MERGE → COMPLETE/FAILED`
Task status flow: `PENDING → LOCKED → WORKING → COMPLETE / PARTIAL / FAILED / BLOCKED`

---

## 11. Web UI

React 18 SPA (`frontend/` directory) built with **Vite 5**, **Tailwind 3.4**, **shadcn/ui**, **Zustand**, **Recharts + D3.js v7**, **Framer Motion**.

- Dev: `npm --prefix frontend run dev` → `:5173` (proxies `/api` → `:8080`)
- Build: `npm --prefix frontend run build` → `src/main/resources/static/`

### Pages

| Page | Route | Description |
|---|---|---|
| **Runs** | `/` | 60/40 split: run list + live detail panel with 7 tabs |
| **Dashboard** | `/dashboard` | 4 metric cards + TokenBubbleChart + ModelComparisonChart + ActivityFeed |
| **Pricing** | `/pricing` | Model pricing table + FeasibilityEstimator |
| **Deployments** | `/deployments` | GCP deployment list + status |
| **Integrations** | `/integrations` | Integration source status + manual trigger |
| **Settings** | `/settings` | App config + **GitHub Integration** (PAT + owner + status badge) |
| **Org Projects** | `/org/projects` | Project management — expandable cards + member management (ORG_ADMIN) |
| **Admin Panel** | `/admin` | User/org/tier management — 4 tabs (SUPER_ADMIN) |

### Run Detail Tabs

| Tab | Description |
|---|---|
| Overview | Status, timing, model, cost, approve/merge buttons |
| Tasks | TaskCard per task: assigned model, retry button for PARTIAL/FAILED/BLOCKED/PENDING/LOCKED |
| Log | SSE-powered live log with milestone banners + file-op rows (useSse hook) |
| Timeline | D3.js Gantt of task durations + gate events |
| Files | Workspace file browser |
| Preview | Embedded iframe (`hasFrontend=true`) |
| API Docs | Swagger UI from generated `openapi.yaml` (`hasApiSpec=true`) |

### Key Frontend Files

| Path | Purpose |
|---|---|
| `frontend/src/lib/api.ts` | Typed fetch wrappers; `AuthUser` has `githubOwner`, `hasGithubPat`; `RunSummary` has `repoUrl`; `updateGithubSettings()` |
| `frontend/src/stores/` | useRunStore, useAuthStore, useMetricsStore, useSseStore, useUiStore |
| `frontend/src/hooks/useSse.ts` | SSE EventSource; SCREAMING_SNAKE_CASE event types; reads `data.payload`; 5s polling fallback |
| `frontend/src/components/runs/NewRunModal.tsx` | GitHub collapsible section: None / Create new / Start from existing |
| `frontend/src/components/runs/RunCard.tsx` | GitHub link chip when `run.repoUrl` set |
| `frontend/src/components/runs/RunList.tsx` | Run list with project filter bar; 5s polling |
| `frontend/src/components/runs/TaskCard.tsx` | Task status + retry button for retryable states |
| `frontend/src/components/runs/LiveLog.tsx` | SSE log with milestone banners + file-op rows |
| `frontend/src/pages/SettingsPage.tsx` | GitHub PAT input (show/hide) + owner + save |
| `frontend/src/pages/OrgProjectsPage.tsx` | Expandable project cards + member management |

---

## 12. LLM Prompts

All prompts in `src/main/resources/prompts/`:

| File | Agent | Key Rules |
|---|---|---|
| `planner.txt` | PlannerAgent | `assigned_model` = haiku; `project_setup` first; always add `generate_preview` task; 1–6 tasks max |
| `worker.txt` | WorkerAgent | 7 steps; step 7 = **UPDATE README.md** (hard requirement); XML `<tool_call>` format |
| `reviewer.txt` | ReviewerAgent | 5 checks; step 5 = **README UPDATE CHECK** (blocks merge) |
| `context-agent.txt` | ContextAgent | Lightweight run pattern analysis; update `CODEBASE_CONTEXT.md` |
| `compiler.txt` | CompilerAgent | Compile error analysis; JSON-patch `[{path, content}]` fix format |
| `api-test-suite.txt` | ApiTestSuiteAgent | OpenAPI 3.0 YAML spec from `*Controller.java` files |

---

## 13. Configuration Reference

### `application.yml` — Key Sections

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/agentteam
    username: ${POSTGRES_USER:postgres}
    password: ${POSTGRES_PASSWORD:postgres}
  ai.anthropic.chat.options:
    model: claude-haiku-4-5-20251001    # default; agents override per-call
    max-tokens: 16000
  ai.openai.chat.options:
    model: gpt-4o
    max-tokens: 16000

agent:
  workspace: ./workspace
  concurrency.max-runs: 5
  token-budget: {low: 2048, medium: 4096, high: 8192}
  compaction: {warn-pct: 0.25, compact-pct: 0.35, hard-stop-pct: 0.55}
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
    jira:       {enabled: false}
    confluence: {enabled: false}
    servicenow: {enabled: false}
    pagerduty:  {enabled: false}

security.jwt:
  access-token-expiration-minutes: 1440
  refresh-token-expiration-days: 7
```

### Environment Variables

| Variable | Default | Purpose |
|---|---|---|
| `ANTHROPIC_API_KEY` | *(required)* | Anthropic API key |
| `OPENAI_API_KEY` | *(required for fallback)* | OpenAI API key |
| `POSTGRES_USER` | `postgres` | DB username |
| `POSTGRES_PASSWORD` | `postgres` | DB password |
| `GITHUB_PAT` | *(empty)* | System-level GitHub PAT fallback |
| `GITHUB_OWNER` | `navneetprabhakar` | System-level GitHub owner fallback |
| `GCP_PROJECT` | `my-gcp-project` | GCP project ID for deployments |

---

## 14. Running the Application

### Prerequisites

- Java 21 (`/opt/homebrew/opt/openjdk@21`)
- PostgreSQL running on `localhost:5432` with database `agentteam`
- Node.js 18+ (for frontend build)
- Anthropic API key in environment

### Start with Supervisor (Recommended)

```bash
./supervisor.sh start     # build + start with health monitoring + auto-restart
./supervisor.sh status    # check state
./supervisor.sh logs      # tail app logs
./supervisor.sh restart   # graceful restart
./supervisor.sh stop      # graceful stop
./supervisor.sh build     # build only
```

Supervisor features:
- States: `STARTING → RUNNING → RESTARTING → UNHEALTHY → CRASHED → FAILED → STOPPED`
- Exponential backoff: 5 s → 10 s → … → 120 s cap (max 10 crashes)
- Health check via `GET /api/health` every 20 s

### Start (Manual)

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run -DskipTests
```

### Frontend Dev Mode

```bash
npm --prefix frontend run dev    # → http://localhost:5173 (proxies /api → :8080)
```

### Compile Check

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q
npm --prefix frontend run build
```

### Dashboard

```
http://localhost:8080
```

---

## Build Status

`mvn compile -q` — ✅ passes clean
`npm --prefix frontend run build` — ✅ passes clean

**Total:** ~165 Java source files · 45+ REST endpoints · 20 DB entities (V1–V8) · 6 LLM prompts · React 18 SPA frontend
