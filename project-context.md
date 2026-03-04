# ExecuteSpec — Project Context
## Master Reference for New Threads

> Feed this document at the start of any new conversation to restore full project context.
> Last updated: March 2026. **Tiers 1–8 complete. Tier 9 (Playground) planned. Spring AI 1.1.0. 2-provider fallback (Anthropic → OpenAI). Model matrix: Planner=Sonnet 4.6, Workers/Reviewer/Compiler=Haiku 4.5. React 18 SPA frontend. Full auth/multi-tenancy (JWT + orgs + projects + tiers). Per-user GitHub PAT. 10-language deployment.**
> `mvn compile` + `npm run build` both pass clean.

---

## What We Are Building

A **multi-agent autonomous coding system** that takes a natural-language specification, decomposes it into parallel tasks, runs LLM worker agents to write real code, reviews and merges the result, then optionally deploys to GCP Cloud Run.

**Use cases**: internal tool workflows, SaaS prototyping, boilerplate automation, learning.
**Interfaces**: REST API + React 18 SPA dashboard.
**Target worker capability**: write/edit files, compile/build, run tests, shell commands, search/grep.

**Tech stack**: Java 21, Spring Boot 3.3.0, Spring AI 1.1.0, PostgreSQL, JGit 6.9.0, Maven.
**LLM providers**: Anthropic (primary), OpenAI (fallback — `gpt-4o`/`gpt-4o-mini`). Gemini fully removed.
**Maven artifactId**: `executespec`
**Package root**: `com.navneet.agentteam`
**Build**: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile`

---

## Architecture in One Page

```
User request (REST POST /api/runs)
     │
     ▼
PipelineHarness  (Semaphore — max 5 concurrent runs)
     │
     ├─ PlannerAgent ─────────────────────── Single API call → TaskPlan JSON
     │                                       Model: claude-sonnet-4-6
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
          ├─ PreviewService ───────────────── npm install && npm run build, marks hasFrontend=true
          ├─ ContextAgent ────────────────── Updates CODEBASE_CONTEXT.md
          ├─ PromptOptimiserAgent ─────────── Every 10 runs: analyses patterns, patches prompts
          └─ GitHub push ──────────────────── If user has PAT configured (repoMode=NEW or EXISTING)
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
- **WorkerAgent / CompilerAgent / ApiTestSuiteAgent**: use `UTILITY_CHAIN` (`claude-haiku-4-5-20251001`).
- **ReviewerAgent**: `resolveModelName()` returns `claude-haiku-4-5-20251001`.
- **ContextAgent / PromptOptimiserAgent**: model value from `agent.context.model` / `agent.optimiser.model` yaml keys.

### Fallback Chains (2-provider: Anthropic → OpenAI)

```
ARCHITECT_CHAIN: anthropic/claude-opus-4-6           →  openai/gpt-4o
CAPABLE_CHAIN:   anthropic/claude-sonnet-4-6         →  openai/gpt-4o
UTILITY_CHAIN:   anthropic/claude-haiku-4-5-20251001 →  openai/gpt-4o-mini
```

`LlmRouter` selects chain based on primary model name. Falls back on rate-limit (15 s sleep + retry) or any non-transient provider error. **Gemini fully removed** — no longer in the fallback chain.

### Token Limits

| Setting | Value |
|---|---|
| `spring.ai.anthropic.chat.options.max-tokens` | 16 000 |
| `spring.ai.openai.chat.options.max-tokens` | 16 000 |
| Token budget LOW | 2 048 |
| Token budget MEDIUM | 4 096 |
| Token budget HIGH | 8 192 |

---

## Auth & Multi-Tenancy

### Overview
Full JWT-based authentication with multi-org support, role-based access control, project membership, and tier enforcement.

### JWT Configuration
- Access token: 1440 min (24 hours)
- Refresh token: 7 days
- Both tokens stored in PostgreSQL (`app_user` for refresh token)

### Tiers

| Tier | Runs/Month | Project Access | Notes |
|---|---|---|---|
| FREE | 5 (fixed) | None | Own runs only |
| PRO | 50 (org-overridable) | Yes | Full project access |
| ENTERPRISE | Unlimited* | Yes | `api_keys_provided=true` → unlimited; else 5000/month |

### Roles

**Org-level roles** (`org_member.role`):
- `SUPER_ADMIN` — global access across all orgs (system account)
- `ORG_ADMIN` — manage org, members, projects; sees all runs in org
- `BILLING_ADMIN` — billing and tier management; sees all runs in org
- `DEVELOPER` — submit runs; sees only projects they're members of

**Project-level roles** (`project_member.project_role`):
- `PROJECT_ADMIN` — manage project members
- `PROJECT_DEVELOPER` — access project runs

### RunVisibilitySpec
- `SUPER_ADMIN` → all runs
- `ORG_ADMIN` / `BILLING_ADMIN` → all runs in org
- `DEVELOPER` → runs in projects they belong to
- `FREE` → own runs only

### Default Admin (seeded by V5 migration)
- Email: `admin@executespec.ai`
- Password: `Admin@123`

### Auth Entities

| Entity | Table | Purpose |
|---|---|---|
| `AppUser` | `app_user` | Users; `githubPat`, `githubOwner` columns (V8) |
| `Org` | `org` | Organisation; `monthlyRunLimitOverride`, `apiKeysProvided` |
| `OrgMember` | `org_member` | User ↔ org link with role |
| `Project` | `project` | Project within org; `createdBy`, `status` |
| `ProjectMember` | `project_member` | User ↔ project link with `projectRole` |
| `TierConfig` | `tier_config` | Per-tier limits (seeded by V6) |

### Auth Controllers & Endpoints

```
POST /api/auth/register                    # Register new user
POST /api/auth/login                       # Login → {accessToken, refreshToken, user}
POST /api/auth/refresh                     # Refresh access token
POST /api/auth/logout                      # Invalidate refresh token
PUT  /api/auth/settings/github             # Save githubPat + githubOwner for current user

GET  /api/org/me                           # My org details
GET  /api/org/me/members                   # Org member list
PUT  /api/org/me/members/{id}/suspend      # Suspend member
DELETE /api/org/me/members/{id}            # Remove member

GET  /api/projects                         # My projects (visibility-controlled)
POST /api/projects                         # Create project (ORG_ADMIN+)
GET  /api/projects/{id}                    # Project detail
PUT  /api/projects/{id}                    # Update project
PUT  /api/projects/{id}/archive            # Archive project
GET  /api/projects/{id}/members            # Project members
POST /api/projects/{id}/members            # Add member (ORG_ADMIN+)
DELETE /api/projects/{id}/members/{userId} # Remove member

GET  /api/admin/users                      # All users (SUPER_ADMIN)
PUT  /api/admin/users/{id}/tier            # Change user tier
PUT  /api/admin/orgs/{id}/run-limit        # Override org run limit
```

---

## Per-User GitHub PAT Integration

Users store their own GitHub PAT in Settings. At run-submission time, the PAT is stamped onto the `pipeline_run` record. The run-level PAT takes precedence over the system-level PAT from `application.yml`.

### AppUser columns (added V8)
```java
@Column(name = "github_pat")
private String githubPat;

@Column(name = "github_owner")
private String githubOwner;
```

### PipelineRun columns (added V8)
```java
@Column(name = "github_pat")
private String githubPat;

@Column(name = "github_owner")
private String githubOwner;
```

### Run modes
- `repoMode=null` — no GitHub interaction
- `repoMode=NEW` — auto-create private GitHub repo after merge, push code
- `repoMode=EXISTING` — clone specified repo URL at workspace init → modify → push back

### Workspace init (WorkspaceInitService)
- `repoMode=EXISTING` → `cloneFromRepo(runId, repoUrl, pat)`: PAT injected into clone URL; git identity set to `agent@executespec.local`
- Otherwise → `ensureRunWorkspace(runId)`: fresh `git init`

### PlannerAgent context for EXISTING repos
When `repoMode=EXISTING`, the user message is prepended with an `[EXISTING CODEBASE]` block telling the planner the workspace already contains the full codebase and to plan changes on top of existing code.

### GitHubRepoService
Accepts per-call `pat` and `owner` overrides. Falls back to system-level credentials. Tries org endpoint first (`POST /orgs/{owner}/repos`), falls back to user endpoint.

---

## Database Schema (PostgreSQL)

```
Connection: jdbc:postgresql://localhost:5432/agentteam
Credentials: ${POSTGRES_USER:postgres} / ${POSTGRES_PASSWORD:postgres}
Schema management: Flyway migrations V1–V8
```

### Core Pipeline Tables
```sql
pipeline_run        — run lifecycle; repoMode, repoUrl, githubPat, githubOwner (V8);
                      skipApproval, projectId, orgId, createdByUserId
agent_task          — per-task status, assignedModel, branch, compaction count,
                      last_activity_at, stuck_detected_at, recovery_attempt, currentProvider
agent_message       — task-level activity log (taskId FK)
file_lock           — file ownership registry (path → taskId, runId)
agent_inbox         — per-agent message inbox
model_pricing       — 6 active rows; purged+reseeded on every startup
run_recovery        — stuck run classification, action taken, restart run link
prompt_audit_entry  — before/after content, diff, patterns, post-change failure rate
gate_event          — gate open/resolved timestamps, approved flag
compaction_event    — compaction checkpoint markers (Tier 4 Gantt)
```

### Auth Tables (Flyway V4–V7)
```sql
app_user        — users; passwordHash (BCrypt); githubPat, githubOwner (V8)
org             — organisations; monthlyRunLimitOverride, apiKeysProvided
org_member      — user ↔ org join; role enum
project         — projects within org; createdBy; status (ACTIVE/ARCHIVED)
project_member  — user ↔ project join; projectRole enum
tier_config     — tier limits seeded by V6
```

### Deployment & Integration Tables (Tier 6–7)
```sql
deployment          — GCP deploy lifecycle, image URI, service URL, TTL
run_repo            — GitHub repo per run (URL, owner, mode, push status)
integration_run     — maps integration source → pipeline run
integration_event_log — raw webhook event history, 7-day TTL
```

### Run/Task Status Flows
```
Run:  QUEUED → PLANNING → AWAITING_APPROVAL → WORKING → REVIEWING → AWAITING_MERGE → COMPLETE/FAILED
Task: PENDING → LOCKED → WORKING → COMPLETE / PARTIAL / FAILED / BLOCKED
```

---

## Package Structure

```
com.navneet.agentteam/
├── AgentTeamApplication.java         # Entry point; @EnableScheduling, @EnableConfigurationProperties
│
├── agent/
│   ├── model/                        # TaskPlan, TaskDefinition (+assignedModel), WorkerReport,
│   │                                 # ReviewReport, TaskReview, ReviewIssue
│   ├── PlannerAgent.java             # Goal → TaskPlan JSON; planWithEstimates() for feasibility
│   ├── WorkerAgent.java              # XML <tool_call> format; singleton, per-task state in params
│   ├── ReviewerAgent.java            # ReviewReport; README check (step 5)
│   ├── TokenBudgetMonitor.java
│   ├── LlmRouter.java                # ARCHITECT/CAPABLE/UTILITY chains; resolveChain()
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
│   │   ├── DeploymentController.java # /api/deployments CRUD
│   │   ├── LocalPreviewController.java # /api/runs/{id}/preview start/stop
│   │   ├── IntegrationController.java # /api/integrations webhook + trigger + status
│   │   └── IntegrationStatusController.java
│   ├── dto/                          # RunRequest (+repoUrl/repoMode), RunSummary (+repoUrl),
│   │                                 # RunDetail, TaskSummary (+failureReason),
│   │                                 # ApprovalRequest, MetricsResponse, PricingRow,
│   │                                 # FeasibilityRequest, FeasibilityReport,
│   │                                 # DeployRequest, DeploymentSummary
│   └── RunMapper.java
│
├── apitests/                         # ApiTestSuiteAgent (post-merge OpenAPI 3.0 gen)
│
├── auth/
│   ├── entity/                       # AppUser, Org, OrgMember, Project, ProjectMember, TierConfig
│   ├── repository/                   # Repositories for all auth entities
│   ├── service/                      # AuthService, OrgService, ProjectService,
│   │                                 # TierEnforcementService, InvitationService,
│   │                                 # GmailOAuthService, MailService
│   ├── controller/                   # AuthController, OrgController, ProjectController,
│   │                                 # SuperAdminController
│   ├── dto/                          # UserDto (+githubOwner, hasGithubPat),
│   │                                 # OrgDto, ProjectDto, ProjectMemberDto, OrgMemberDto,
│   │                                 # TierConfig-related DTOs
│   ├── security/                     # JwtTokenProvider, AppUserDetails, SecurityConfig
│   └── AppUserRepository.java
│
├── compiler/                         # CompilerAgent (pre-review compile+fix, max 3 attempts)
│
├── config/
│   ├── AgentProperties.java          # @ConfigurationProperties(prefix="agent")
│   │                                 # Inner classes: TokenBudget, Compaction, Concurrency,
│   │                                 # GitHub, Compiler, ApiTestSuite, Preview, Deployment
│   ├── AgentModelConfig.java         # Spring AI ChatModel beans; JdkClientHttpRequestFactory,
│   │                                 # HTTP/1.1, 300s read timeout. NO AnthropicCacheInterceptor.
│   ├── ModelPricingInitializer.java  # @PostConstruct — deletes stale rows, upserts canonical rows
│   └── WebConfig.java
│
├── context/                          # ContextAgent (virtual thread, post-merge)
│
├── deployment/
│   ├── strategy/                     # DeploymentStrategy (interface)
│   │   ├── SpringBootDeploymentStrategy.java   # @Order(1)
│   │   ├── GradleDeploymentStrategy.java       # @Order(2)
│   │   ├── NodeDeploymentStrategy.java         # @Order(3)
│   │   ├── PythonDeploymentStrategy.java       # @Order(4)
│   │   ├── GoDeploymentStrategy.java           # @Order(5)
│   │   ├── RubyDeploymentStrategy.java         # @Order(6)
│   │   ├── PhpDeploymentStrategy.java          # @Order(7)
│   │   ├── RustDeploymentStrategy.java         # @Order(8)
│   │   ├── DotNetDeploymentStrategy.java       # @Order(9)
│   │   └── StaticDeploymentStrategy.java       # @Order(10)
│   ├── PreviewAgent.java             # Generates Dockerfile + docker-compose.yml deterministically
│   ├── DeploymentService.java
│   ├── DeploymentProperties.java
│   ├── DockerfileGenerator.java      # Routes to correct strategy; findFirst() on ordered list
│   ├── LocalPreviewService.java      # detectProjectType() mirrors @Order priority
│   ├── DeploymentGitHubService.java
│   ├── ArtifactRegistryClient.java
│   ├── CloudRunClient.java
│   ├── WorkspaceExportService.java   # zip download
│   ├── WorkspaceFileService.java     # read/list files with path traversal protection
│   └── DeploymentCleanupJob.java     # @Scheduled — deletes deployments older than ttl-days
│
├── harness/
│   ├── PipelineHarness.java          # Orchestrator; Semaphore(maxRuns), CompletableFuture gates
│   │                                 # clone-vs-init branch for EXISTING repoMode
│   │                                 # pushToGitHubIfConfigured() uses run-level PAT
│   ├── WorkerPool.java               # Concurrent tasks; cancelTask/resubmitTask
│   ├── ApprovalGate.java             # CompletableFuture<Boolean> gate wrapper
│   ├── GitMergeService.java          # Merge + enforceReadmeUpdated() hard gate
│   ├── WorkspaceInitService.java     # ensureRunWorkspace() + cloneFromRepo()
│   ├── event/                        # PipelineEvent, PostMergeOrchestrator
│   └── MergeBlockedException.java
│
├── integration/
│   ├── jira/                         # JiraAdapter, JiraClient, JiraWebhookPayload
│   ├── confluence/                   # ConfluenceAdapter, ConfluenceClient
│   ├── servicenow/                   # ServiceNowAdapter, ServiceNowClient
│   ├── pagerduty/                    # PagerDutyAdapter, PagerDutyClient
│   ├── IntegrationManager.java       # setHarness() breaks circular dep
│   └── IntegrationEventLogCleanupJob.java
│
├── optimiser/                        # PromptOptimiserAgent (virtual thread, every 10 runs)
│
├── recovery/
│   ├── LlmCallGuard.java             # Wraps ChatModel; retries rate-limit/server errors
│   ├── StuckRunMonitor.java          # @Scheduled every 2 min
│   ├── RunRecoveryService.java       # setPipelineHarness() breaks circular dep
│   ├── StuckRunEvaluator.java
│   ├── EscalationService.java
│   └── RecoveryProperties.java
│
├── repo/
│   └── GitHubRepoService.java        # createRepo() with overridePat/overrideOwner support
│
├── sse/
│   ├── RunEvent.java                 # Record + factory methods; type names in SCREAMING_SNAKE_CASE
│   └── SseBroadcaster.java
│
├── state/
│   ├── StateStore.java               # All DB access; listRuns() accepts optional projectId filter
│   ├── InboxService.java
│   ├── entity/                       # PipelineRun, AgentTask, AgentMessage, FileLock, AgentInbox,
│   │                                 # GateEvent, CompactionEvent, RunRecovery, PromptAuditEntry,
│   │                                 # RunRepo, Deployment, IntegrationRun, IntegrationEventLog
│   └── repository/
│
├── tenant/
│   └── TenantInterceptor.java        # JPA @EntityListeners — injects tenantId on save
│
└── tool/
    ├── FileSystemTool.java           # .normalize() prevents path traversal
    ├── ShellTool.java
    ├── GitTool.java                  # commitIfDirty() — silent no-op if clean
    └── FileLockTool.java             # releaseAllLocksForTask / releaseAllLocksForRun
```

---

## Prompt Files

```
src/main/resources/prompts/
├── planner.txt           JSON-only, 1–6 tasks, explicit boundaries + acceptance criteria
│                         Hard Rule: assigned_model = claude-haiku-4-5-20251001 (all tasks)
│                         Must include project_setup as first dependency
│                         Always adds generate_preview task
├── worker.txt            XML <tool_call> format; Step 7 = UPDATE README.md (hard requirement)
│                         project_setup task writes pom.xml/package.json first
├── reviewer.txt          JSON-only; Step 5 = README UPDATE CHECK (blocks merge if missing)
├── context-agent.txt     Single call: update CODEBASE_CONTEXT.md after run
├── compiler.txt          Compile error analysis; returns JSON-patch [{path, content}] fixes
└── api-test-suite.txt    OpenAPI 3.0 YAML spec generation from *Controller.java files
```

**Key worker prompt rules** (enforce if regenerating):
- Tool call format: `<tool_call>{"name": "...", "args": {...}}</tool_call>`
- Working sequence: read context → plan → write → commit → build → test → self-review → update README → report
- Commit after every file write (data loss protection)
- Check `file-locks.json` before writing any file
- Report JSON starts with `{` — completion detection requires response to START with `{`
- "Do not modify pom.xml" — except project_setup task

---

## Configuration (`application.yml` key values)

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/agentteam
    username: ${POSTGRES_USER:postgres}
    password: ${POSTGRES_PASSWORD:postgres}
  flyway:
    enabled: true
    locations: classpath:db/migration
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
  token-budget: {low: 2048, medium: 4096, high: 8192}
  compaction: {warn-pct: 0.25, compact-pct: 0.35, hard-stop-pct: 0.55}
  github:
    pat: ${GITHUB_PAT:}                         # system-level fallback
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
  integrations:
    jira:       {enabled: false, skip-approval: false}
    confluence: {enabled: false, skip-approval: false}
    servicenow: {enabled: false, skip-approval: false}
    pagerduty:  {enabled: false, skip-approval: false}

security:
  jwt:
    access-token-expiration-minutes: 1440    # 24 hours
    refresh-token-expiration-days: 7
```

### Environment Variables

| Variable | Default | Purpose |
|---|---|---|
| `ANTHROPIC_API_KEY` | *(required)* | Anthropic API key — no hardcoded fallback |
| `OPENAI_API_KEY` | *(required for fallback)* | OpenAI API key |
| `POSTGRES_USER` | `postgres` | DB username |
| `POSTGRES_PASSWORD` | `postgres` | DB password |
| `GITHUB_PAT` | *(empty)* | System-level GitHub PAT fallback |
| `GITHUB_OWNER` | `navneetprabhakar` | System-level GitHub org/user fallback |
| `GCP_PROJECT` | `my-gcp-project` | GCP project ID for deployments |
| `GMAIL_ENABLED` | `false` | Enable Gmail OAuth for email notifications |

---

## REST API — Complete Endpoint Reference

### Runs (`/api/runs`)

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/runs` | Submit new run — returns 202 with `runId` |
| `GET` | `/api/runs` | List runs; filter by `?status=&projectId=` |
| `GET` | `/api/runs/{runId}` | Full run detail with tasks |
| `POST` | `/api/runs/{runId}/approve` | Resolve plan gate (approve/reject) |
| `POST` | `/api/runs/{runId}/merge` | Resolve merge gate (approve/reject) |
| `DELETE` | `/api/runs/{runId}` | Cancel in-progress run |
| `POST` | `/api/runs/{runId}/tasks/{taskId}/retry` | Retry PARTIAL/BLOCKED/FAILED/PENDING/LOCKED task |
| `GET` | `/api/runs/{runId}/events` | **SSE stream** — live `RunEvent` feed |
| `GET` | `/api/runs/{runId}/recovery` | Recovery attempt history |
| `GET` | `/api/runs/{runId}/messages` | Agent message log (max 200) |
| `GET` | `/api/runs/{runId}/timeline` | Gantt + gate event data for D3.js |
| `GET` | `/api/runs/{runId}/download` | Download workspace as `.zip` |
| `GET` | `/api/runs/{runId}/files` | List workspace files |
| `GET` | `/api/runs/{runId}/file?path=` | Read single workspace file |
| `GET` | `/api/runs/{runId}/openapi.yaml` | Generated OpenAPI 3.0 spec |
| `GET` | `/api/runs/{runId}/preview/**` | Serve live frontend preview (SPA fallback) |
| `POST` | `/api/runs/{runId}/preview/start` | Start local preview server |
| `DELETE` | `/api/runs/{runId}/preview/stop` | Stop local preview server |

### Auth (`/api/auth`)

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/auth/register` | Register new user |
| `POST` | `/api/auth/login` | Login → JWT access + refresh tokens |
| `POST` | `/api/auth/refresh` | Refresh access token |
| `POST` | `/api/auth/logout` | Invalidate refresh token |
| `PUT` | `/api/auth/settings/github` | Save `githubPat` + `githubOwner` for current user |

### Org (`/api/org`)

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/org/me` | My org details |
| `GET` | `/api/org/me/members` | Org member list |
| `PUT` | `/api/org/me/members/{id}/suspend` | Suspend org member |
| `DELETE` | `/api/org/me/members/{id}` | Remove org member |

### Projects (`/api/projects`)

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/projects` | List projects (visibility-controlled) |
| `POST` | `/api/projects` | Create project (ORG_ADMIN+) |
| `GET` | `/api/projects/{id}` | Project detail |
| `PUT` | `/api/projects/{id}` | Update project |
| `PUT` | `/api/projects/{id}/archive` | Archive project |
| `GET` | `/api/projects/{id}/members` | Project members |
| `POST` | `/api/projects/{id}/members` | Add member (ORG_ADMIN+) |
| `DELETE` | `/api/projects/{id}/members/{userId}` | Remove member |

### Deployments (`/api/deployments`)

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/deployments` | Deploy run to GCP Cloud Run |
| `GET` | `/api/deployments` | List all deployments |
| `GET` | `/api/deployments/{id}` | Deployment detail |
| `DELETE` | `/api/deployments/{id}` | Delete deployment |
| `POST` | `/api/deployments/{id}/rollback` | Rollback to previous revision |
| `GET` | `/api/deployments/live` | List `LIVE` deployments only |

### Other

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/metrics` | Global run/token/cost statistics |
| `GET` | `/api/model-comparison?days=30` | Per-model performance stats |
| `GET` | `/api/pricing` | All model pricing rows |
| `PUT` | `/api/pricing/{provider}/{model}` | Upsert pricing |
| `POST` | `/api/feasibility` | Estimate cost+complexity without running workers |
| `GET` | `/api/health` | DB status, uptime, active/queued/total runs |
| `POST` | `/api/integrations/{source}/webhook` | Inbound webhook (jira/confluence/servicenow/pagerduty) |
| `GET` | `/api/integrations/status` | Enabled integration sources |
| `GET` | `/api/admin/users` | All users (SUPER_ADMIN) |
| `PUT` | `/api/admin/users/{id}/tier` | Change user tier |
| `PUT` | `/api/admin/orgs/{id}/run-limit` | Override org run limit |

---

## SSE Events

All SSE event type names are `SCREAMING_SNAKE_CASE`. The payload field is `payload` (not `data` or `status`).

```
RUN_STATUS       — payload = new run status string
TASK_STATUS      — payload = taskId|newStatus
GATE_OPENED      — payload = gateType (PLAN/MERGE)
GATE_RESOLVED    — payload = gateType|approved
LOG              — payload = log line text
COST_UPDATE      — payload = estimated cost
QUEUED           — payload = runId
COMPLETE         — payload = runId
RECOVERY_ATTEMPTED — payload = runId|action
RUN_RESTARTED    — payload = newRunId
DEPLOY_STARTED / DEPLOY_BUILDING / DEPLOY_PUSHING / DEPLOY_LIVE / DEPLOY_FAILED / DEPLOY_DELETED
API_SPEC_READY   — payload = runId
PREVIEW_READY    — payload = runId
```

Frontend `useSse.ts` subscribes to these and calls `patchRunStatus()` or `fetchRun()` accordingly.

---

## 10-Language Deployment Strategies

Strategies are `@Component` beans collected as `List<DeploymentStrategy>` in `DockerfileGenerator`. The `@Order` annotation determines detection priority (lower number = higher priority).

| @Order | Strategy | Detection Signal | Docker Base |
|---|---|---|---|
| 1 | `SpringBootDeploymentStrategy` | `pom.xml` | `eclipse-temurin:21-jdk` → `eclipse-temurin:21-jre-alpine` |
| 2 | `GradleDeploymentStrategy` | `build.gradle` / `build.gradle.kts` (no pom.xml) | `gradle:8-jdk21` → `eclipse-temurin:21-jre-alpine` |
| 3 | `NodeDeploymentStrategy` | `package.json` (no pom.xml/build.gradle) | Server: `node:20-slim`; SPA build: multi-stage → `nginx:1.25-alpine` |
| 4 | `PythonDeploymentStrategy` | `requirements.txt` / `setup.py` / `pyproject.toml` / `Pipfile` | `python:3.11-slim` |
| 5 | `GoDeploymentStrategy` | `go.mod` | `golang:1.22-alpine` → `alpine:3.19` |
| 6 | `RubyDeploymentStrategy` | `Gemfile` | `ruby:3.3-slim` |
| 7 | `PhpDeploymentStrategy` | `composer.json` | `php:8.2-apache` |
| 8 | `RustDeploymentStrategy` | `Cargo.toml` | `rust:1.77-slim` → `debian:bookworm-slim` |
| 9 | `DotNetDeploymentStrategy` | `*.csproj` / `*.sln` | `dotnet/sdk:8.0` → `dotnet/aspnet:8.0` |
| 10 | `StaticDeploymentStrategy` | `index.html` | `nginx:1.25-alpine` |

**Node.js dual-mode detection**: checks `package.json` dependencies for server frameworks (express, fastify, @nestjs/core, koa, hapi, next) vs SPA build tools (vite, react-scripts, @angular/core, @vue/cli-service, svelte). SPA mode does a multi-stage build and serves via nginx.

**`LocalPreviewService.detectProjectType()`** mirrors the same priority order as the strategy `@Order` values.

---

## Web UI

React 18 SPA (`frontend/` directory) built with **Vite 5**, **Tailwind 3.4**, **shadcn/ui** components, **Zustand** stores, **Recharts** + **D3.js v7** for charts, **Framer Motion** for animations.

Dev server: `npm --prefix frontend run dev` → `:5173` (proxies `/api` → `:8080`).
Production build: `npm --prefix frontend run build` → `src/main/resources/static/`.

### Pages

| Page | Route | Description |
|---|---|---|
| **Runs** | `/` | 60/40 split: run list + live detail panel with 7 tabs |
| **Dashboard** | `/dashboard` | Metric cards + TokenBubbleChart + ModelComparisonChart + ActivityFeed |
| **Pricing** | `/pricing` | Model pricing table + FeasibilityEstimator |
| **Deployments** | `/deployments` | GCP deployment list + status |
| **Integrations** | `/integrations` | Integration source status + manual trigger |
| **Settings** | `/settings` | App config + **GitHub Integration section** (PAT + owner) |
| **Org Projects** | `/org/projects` | Project management (expandable cards + member management) |
| **Admin Panel** | `/admin` | User/org/tier management (SUPER_ADMIN, 4 tabs) |

### Run Detail Tabs

| Tab | Description |
|---|---|
| Overview | Status, timing, model, cost, approve/merge buttons |
| Tasks | Per-task TaskCard: assigned model, retry button for PARTIAL/FAILED/BLOCKED/PENDING/LOCKED |
| Log | SSE-powered live log (useSse hook) with milestone banners + file-op rows |
| Timeline | D3.js Gantt of task durations + gate events |
| Files | Workspace file browser |
| Preview | Embedded iframe for frontend previews (`hasFrontend=true`) |
| API Docs | Swagger UI from generated `openapi.yaml` (`hasApiSpec=true`) |

### Key Frontend Files

| Path | Purpose |
|---|---|
| `frontend/src/lib/api.ts` | All typed fetch wrappers; `AuthUser` has `githubOwner`, `hasGithubPat`; `RunSummary` has `repoUrl`; `updateGithubSettings()` |
| `frontend/src/stores/` | useRunStore, useMetricsStore, useSseStore, useAuthStore, useUiStore (Zustand) |
| `frontend/src/hooks/useSse.ts` | SSE EventSource; event types in SCREAMING_SNAKE_CASE; reads `data.payload` field |
| `frontend/src/components/runs/NewRunModal.tsx` | GitHub collapsible section: None / Create new repo / Start from existing |
| `frontend/src/components/runs/RunCard.tsx` | Shows GitHub link chip when `run.repoUrl` is set |
| `frontend/src/pages/SettingsPage.tsx` | GitHub Integration section: PAT input + owner + save |

---

## WorkerAgent Tool Dispatch (`dispatchTool` switch)

| Tool call name | Java method |
|---|---|
| `readFile` / `read_file` | `fsTool.readFile(runId, path)` |
| `writeFile` / `write_file` | `fsTool.writeFile(runId, path, content)` |
| `editFile` / `edit_file` | `fsTool.editFile(runId, path, oldStr, newStr)` |
| `listFiles` / `list_files` | `fsTool.listFiles(runId, path)` |
| `searchFiles` / `search_files` | `fsTool.searchFiles(runId, pattern, directory)` |
| `run` / `run_command` / `run_build` / `run_tests` | `shellTool.run(runId, command, timeoutSeconds)` |
| `status` / `git_status` | `gitTool.status(runId)` |
| `diff` / `git_diff` | `gitTool.diff(runId)` |
| `commit` / `git_commit` | `gitTool.commit(runId, message)` |

---

## PipelineHarness Flow

1. `submitRun()` → Semaphore(maxRuns); plan via PlannerAgent (uses run's `plannerModel`)
2. Stamp `githubPat` + `githubOwner` from submitting user onto the run record
3. Plan approval gate (CompletableFuture) → `POST /api/runs/{id}/approve`
4. Gate-loss handling: if gate missing on approve (server restart) → auto-fail with message
5. Workspace init: if `repoMode=EXISTING` → `cloneFromRepo()`; else → `ensureRunWorkspace()`
6. Workers run in parallel (filter out `PREVIEW_TASK_ID` before `workerPool.runAll()`)
7. PreviewAgent.run() called after workers — generates Dockerfile + docker-compose.yml
8. PARTIAL state: if any worker tasks incomplete → mark PARTIAL, no merge gate
9. ReviewerAgent → merge gate → `POST /api/runs/{id}/merge`
10. `saveActualCost()` via CostEstimator after merge
11. Git tag applied, optional GitHub push (`pushToGitHubIfConfigured()` — uses run-level PAT)
12. PostMergeOrchestrator (event-driven): ContextAgent, PromptOptimiserAgent, ApiTestSuiteAgent, PreviewService

## Retry Flow (`retryPartialTask`)

- Accepts task status: PARTIAL, BLOCKED, FAILED, PENDING, LOCKED
- Resets task → re-runs worker → auto-picks up all other incomplete siblings
- After all tasks complete: reviewer cycle → merge gate (same flow as initial run)
- MergeCompleted event published → PostMergeOrchestrator fires

---

## Build Tier Status

### ✅ Tier 1 — Core Loop
Planner → approval gate → parallel workers → reviewer → merge gate → GitMergeService.
XML tool-call format, agentic loop, file locks, git branches.

### ✅ Tier 2 — Reliability Layer
Context compaction (harness-controlled), provider fallback with guardrails, `LlmRouter`, `TokenBudgetMonitor`, `CostEstimator`, inbox messaging, git run tagging.

### ✅ Tier 3 — Async REST + React SPA + SSE
Async REST with `Semaphore(maxRuns=5)`, `CompletableFuture` approval/merge gates, SSE broadcaster (SCREAMING_SNAKE_CASE event types, `payload` field), React 18 + Vite 5 + Tailwind 3.4 + shadcn/ui SPA, `model_pricing` table, README enforcement hard gate.

### ✅ Tier 4 — Visualisations
D3.js Gantt timeline, token bubble chart, model comparison chart, activity feed. `gate_event` + `compaction_event` tables.

### ✅ Tier 5 — Intelligence & Recovery
- **Stuck recovery**: `StuckRunMonitor` (@Scheduled 2 min) → `StuckRunEvaluator` → `RunRecoveryService`
- **LLM error handling**: `LlmCallGuard` wraps every call; retries rate-limit; classifies AuthFailure/BudgetExhausted/ContextTooLong
- **Context agent**: virtual thread; post-merge; updates `CODEBASE_CONTEXT.md`; non-fatal
- **Prompt optimiser**: every 10 runs; `PromptPatternAnalyser` → targeted prompt patch; full audit trail

### ✅ Tier 6 — GCP Deployments + 10-Language Support
Docker build → Artifact Registry → Cloud Run. **10 deployment strategies** (Spring Boot, Gradle, Node.js, Python, Go, Ruby, PHP, Rust, .NET, Static) with auto-detection and auto-generated Dockerfiles. Versioned revisions, 7-day TTL cleanup, workspace zip download.

### ✅ Tier 7 — External Integrations
Webhook-driven pipeline triggers from Jira, Confluence, ServiceNow, PagerDuty.
`skipApproval` flag for fully automated pipelines. Idempotency via `integration_event_log`. All disabled by default.

### ✅ Tier 8 — Compile, API Spec, Preview
`CompilerAgent` (compile+fix max 3), `ApiTestSuiteAgent` (OpenAPI 3.0), `PreviewService` (npm build), `HealthController`.

### ✅ Auth & Multi-Tenancy
JWT auth (24h/7d), orgs, projects, tiers (FREE/PRO/ENTERPRISE), roles, Flyway V4–V8.

### ✅ Per-User GitHub PAT
V8 migration adds `github_pat` + `github_owner` to `app_user` + `pipeline_run`. Run modes: NEW/EXISTING/null. Existing repo cloning via `WorkspaceInitService.cloneFromRepo()`.

### 🔲 Tier 9 — Playground (planned)
Interactive environment for loading, previewing, and testing generated projects. `E2eTestGeneratorAgent`, `PlaygroundController`, `PlaygroundService`, `PlaygroundTestRunner`, `PlaygroundWebSocketProxy`.

---

## Key Implementation Rules (Enforce in Every Session)

1. **Package root is `com.navneet.agentteam`** — never `com.example.*`
2. **Maven artifactId is `executespec`** (not `agentteam`)
3. **Database is PostgreSQL** — never H2 or SQLite. DB name: `agentteam`
4. **All agent models are `claude-haiku-4-5-20251001`** for workers; `claude-sonnet-4-6` for planner
5. **Model classes use `@Data @Builder @NoArgsConstructor @AllArgsConstructor`** — not records
6. **All LLM model classes** have `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)`
7. **`lombok.config`** has `copyableannotations += @Qualifier`
8. **Spring AI 1.1.0**: `ChatResponse.getResult().getOutput().getText()` (not `.getContent()`)
9. **`FileSystemTool.workspace()`** uses `.normalize()` to avoid SecurityException on path "."
10. **Workers never touch main branch** — own branch only: `agent/{runId8}/{taskId}`
11. **No mid-task provider switch** — BLOCKED task > silently downgraded task
12. **README.md enforced** — hard pre-merge check in `GitMergeService.enforceReadmeUpdated()`
13. **`ModelPricingInitializer`** deletes stale rows before upserting — no stale models accumulate
14. **All logging via SLF4J `@Slf4j`** — never `System.out`
15. **Circular deps broken via `setXxx()` called from `@PostConstruct`** — `RunRecoveryService.setPipelineHarness()` and `IntegrationManager.setHarness()` follow this pattern
16. **SSE event types are SCREAMING_SNAKE_CASE** (`RUN_STATUS`, `TASK_STATUS`, etc.); frontend reads `data.payload` field (not `data.status`)
17. **NO AnthropicCacheInterceptor** — `AgentModelConfig` uses `JdkClientHttpRequestFactory` with HTTP/1.1 and 300s read timeout; caching uses `AnthropicChatOptions.builder().cacheOptions(SYSTEM_ONLY)` per call
18. **`application.yml`**: all secrets use `${VAR:}` (empty default) — NO hardcoded fallback values
19. **`WorkspaceInitService`**: per-run isolated workspace `workspace/{runId}/`; git identity set before first commit
20. **`GitTool.commitIfDirty()`**: stages + commits if working tree dirty, silent no-op if clean

---

## Model Pricing Reference (Mar 2026 — canonical rows)

| Provider | Model | Input $/1M | Output $/1M | Role |
|---|---|---|---|---|
| anthropic | `claude-haiku-4-5-20251001` | $3.00 | $15.00 | ✅ ACTIVE — workers/reviewer/compiler |
| anthropic | `claude-sonnet-4-6` | $3.00 | $15.00 | ✅ ACTIVE — planner/recovery |
| anthropic | `claude-opus-4-6` | $15.00 | $75.00 | Escalation reference |
| openai | `gpt-4o-mini` | $0.15 | $0.60 | UTILITY_CHAIN fallback |
| openai | `gpt-4o` | $2.50 | $10.00 | CAPABLE_CHAIN fallback |

---

## Running the Application

```bash
# Prerequisites: Java 21, PostgreSQL on localhost:5432 (database: agentteam)

# With supervisor (recommended — auto-restarts, health monitoring)
./supervisor.sh start
./supervisor.sh status
./supervisor.sh logs

# Manual
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run -DskipTests

# Compile check
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q

# Frontend dev
npm --prefix frontend run dev   # → localhost:5173

# Dashboard
open http://localhost:8080
```

---

## How to Continue in a New Thread

**Tiers 1–8 complete plus Auth, GitHub PAT integration. Tier 9 (Playground) planned — see spec in older project-context versions.**
Feed this document at the start of any session to restore full context.

**For bug fixes**: Describe the error + paste stack trace. Reference the class by name.

**For new features**: Describe the feature. Ask for a plan before implementation.

**Key files to reference**:
- `project-context.md` — this file (at project root)
- `README.md` — user-facing overview (at project root)
- `application.yml` — all configuration
- `src/main/resources/prompts/` — all LLM prompts
- `src/main/resources/db/migration/` — Flyway V1–V8
