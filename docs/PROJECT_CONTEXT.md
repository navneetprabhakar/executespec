# ExecuteSpec — Project Context & Recreation Guide

> **Purpose:** Everything needed to understand, maintain, and recreate ExecuteSpec from scratch using an AI coding assistant.

---

## 1. What Is ExecuteSpec?

ExecuteSpec is a **multi-agent autonomous coding system** that converts natural language specifications into working code. Users submit a plain-English description; the system orchestrates AI agents through a structured pipeline to plan, implement, validate, review, and merge the code — with human-in-the-loop approval gates.

---

## 2. Technology Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| Language | Java | 21 |
| Framework | Spring Boot | 3.3.0 |
| AI Framework | Spring AI | 1.1.0 |
| Database | PostgreSQL | 15+ |
| Cache/Gates | Redis | 7+ |
| Git Operations | JGit | 6.9.0 |
| Schema Migration | Flyway | (Spring Boot managed) |
| Frontend | React 18 + TypeScript + Vite 5 + Tailwind CSS | |
| Build | Maven (backend), npm (frontend) | |
| Deployment | GCP (Cloud SQL, GCE, GCS, Redis) | |
| Auth | JWT (access: 24h, refresh: 7d) | |
| Utilities | Lombok, Jackson, SLF4J | |

**Environment:**
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21` (macOS)
- PostgreSQL on `localhost:5432`, database `executespec`
- Redis on `localhost:6379`
- Node.js 18+ for frontend

---

## 3. Architecture

```
User Request (REST / Webhook)
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│                    PipelineHarness                        │
│  ┌──────────┐   ┌──────────┐   ┌──────────────────────┐ │
│  │ Planner  │──▶│ Approval │──▶│ SequentialTaskExecutor│ │
│  │  Agent   │   │   Gate   │   │  (Worker + Validator) │ │
│  └──────────┘   └──────────┘   └──────────┬───────────┘ │
│                                            │             │
│  ┌──────────┐   ┌──────────┐   ┌──────────▼───────────┐ │
│  │ Compiler │──▶│ Reviewer │──▶│     Merge Gate       │ │
│  │  Agent   │   │  Agent   │   │  (Human Approval)    │ │
│  └──────────┘   └──────────┘   └──────────┬───────────┘ │
│                                            │             │
│  ┌─────────────────────────────────────────▼───────────┐ │
│  │            GitMergeService → Post-Merge              │ │
│  │  (ContextAgent + PromptOptimiserAgent + ApiTestAgent) │ │
│  └──────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

### Pipeline Flow

1. **PlannerAgent** — Decomposes user request into ordered tasks with complexity ratings (LOW/MEDIUM/HIGH). Uses `claude-sonnet-4-6`.
2. **Plan Approval Gate** — Human reviews task breakdown. Can skip via `skipApproval` flag.
3. **SequentialTaskExecutor** — Executes tasks in order. Per task: creates worktree → runs WorkerAgent → validates code → retries with error feedback + model escalation on failure → progressive merge to main.
4. **CompilerAgent** — Post-worker compilation validation (up to 3 rounds).
5. **ReviewerAgent** — Code review with CRITICAL/MINOR issue classification.
6. **Merge Approval Gate** — Human reviews code + review findings. Can bypass via `autoMerge` flag.
7. **GitMergeService** — Final merge with 4-tier conflict resolution.
8. **Post-merge** — ContextAgent (docs), PromptOptimiserAgent (prompt tuning), ApiTestSuiteAgent (OpenAPI spec).

**Decoupled services (REST-triggered, not in pipeline):**
- `LocalPreviewService` — ProcessBuilder-based dev server
- `ConversationAgent` — Post-run AI chat with workspace context

---

## 4. Package Structure

Base: `com.navneet.executespec`

```
├── agent/              # PlannerAgent, WorkerAgent, ReviewerAgent, ConversationAgent,
│   │                   # LlmRouter, CostEstimator, TokenBudgetMonitor, LlmOutputParser,
│   │                   # ApiKeyResolver, RunKeyStore
│   └── model/          # TaskPlan, TaskDefinition, WorkerReport, ReviewReport
├── harness/            # PipelineHarness, SequentialTaskExecutor, WorkerPool,
│   │                   # ApprovalGate, GateStore, GitMergeService, MergeDecision
│   └── event/          # PipelineEvent, PostMergeOrchestrator
├── state/              # StateStore (all DB ops), InboxService
│   └── entity/         # PipelineRun, AgentTask, AgentMessage, FileLock, GateEvent, etc.
├── tool/               # FileSystemTool, ShellTool, GitTool, FileLockTool, ShellSandboxPolicy
├── validator/           # CodeValidator, CodeValidatorImpl
│   ├── model/          # Language, ValidationLevel, ValidationResult, ValidationError, etc.
│   ├── execution/      # ProcessExecutor + output parsers (javac, tsc, mypy, eslint, ruff)
│   ├── language/       # JavaToolchain, TypeScriptToolchain, PythonToolchain, ReactToolchain
│   ├── gate/           # GateChain (SYNTAX → TYPE → LINT → TEST)
│   └── prompt/         # RetryPromptBuilder
├── router/             # ModelRouter, ModelTier, ModelSelection, ProviderHealthTracker
├── compiler/           # CompilerAgent
├── reviewer/           # ReviewerAgentV2 (agentic two-phase review)
├── context/            # ContextAgent, ContextAgentScanner
├── optimiser/          # PromptOptimiserAgent, PromptPatternAnalyser, PromptAuditService
├── apitests/           # ApiTestSuiteAgent
├── recovery/           # StuckRunMonitor, RunRecoveryService, StuckRunEvaluator, EscalationService
├── deployment/         # LocalPreviewService, DeploymentService, DockerfileGenerator,
│   │                   # WorkspaceExportService, WorkspaceFileService (+ GitHub fallback),
│   │                   # GcsWorkspaceStore, WorkspaceArchiveJob
│   └── strategy/       # 11 deployment strategies (SpringBoot, Node, Python, Go, Rust, etc.)
├── auth/               # AppUser, Org, Project, OrgMember, ProjectMember, TierConfig,
│   │                   # AuthService, OrgService, ProjectService, TierEnforcementService
│   ├── security/       # JwtFilter, RunVisibilitySpec, SecurityConfig
│   ├── controller/     # AuthController, OrgController, ProjectController
│   └── dto/            # OrgDto, ProjectDto, UserDto
├── integration/        # IntegrationManager + jira/confluence/servicenow/pagerduty adapters
├── sse/                # RunEvent (record + factory methods), SseBroadcaster
├── api/                # RunController, SseController, DeploymentController, etc.
│   └── dto/            # RunSummary, RunDetail, TaskSummary
├── config/             # AgentProperties, AgentModelConfig, WebConfig
├── pricing/            # ModelPricingInitializer, ModelPricingRepository
├── repo/               # GitHubRepoService (random repo names, descriptions, collision handling)
└── tenant/             # TenantInterceptor
```

---

## 5. Critical Design Decisions

### Tool-Call Format
WorkerAgent uses **XML tool calls**: `<tool_call>{"name":"...", "args":{...}}</tool_call>`. NOT Spring AI native tool calling. Reason: `@Tool` methods require `runId` which the model doesn't know. Dispatch is manual via `parseXmlToolCalls()` + `dispatchTool()` with `runId` injected automatically.

### Completion Detection
WorkerAgent response must START with `{` (strict JSON check via `looksLikeCompletionReport()`). Prevents false positives on prose containing field names.

### Context Window Management
- **Sliding window**: First 2 messages + last 18 kept per API call
- **Progress checkpoints**: Every 15 iterations, re-inject task ID, objective, criteria
- **Completion urgency**: At iteration 75 (5 before cap), force JSON output
- **Pre-call token estimation**: If messages > 30, estimate tokens (chars/4), compact proactively
- **Emergency compaction**: HARD_STOP tries compaction first; only truly stops if recently compacted

### LLM Provider Strategy
Three fallback chains with 3 providers:
- `ARCHITECT_CHAIN`: `claude-opus-4-6` → `gemini-2.5-pro` → `gpt-4o`
- `CAPABLE_CHAIN`: `claude-sonnet-4-6` → `gemini-3.1-flash-lite` → `gpt-4o`
- `UTILITY_CHAIN`: `claude-haiku-4-5-20251001` → `gemini-3.1-flash-lite` → `gpt-4o-mini`

Guardrail modes: **IMMEDIATE** (planner/reviewer: always fallback) and **TASK_BOUNDARY** (worker: fallback only at iter 0 or just-compacted).

### Prompt Caching
Anthropic `SYSTEM_ONLY` strategy. Cache TTL = 5 min. `AnthropicChatOptions.builder().maxTokens(maxOut).cacheOptions(SYSTEM_ONLY)` — no toolCallbacks.

### API Key Resolution
Resolution: **user key → org key → system key** based on org's `keySourceMode`:
- `SYSTEM`: Force platform keys only
- `OWN_KEYS`: User/org keys required
- `HYBRID` (default): User/org first, system fallback

### Approval Gates (Redis)
GateStore is Redis-backed (`gate:plan:{runId}`, `gate:merge:{runId}`, TTL 2h). Pipeline thread polls 500ms. Any MIG instance can handle the approval REST call. Two bypass flags: `skipApproval` (plan gate) and `autoMerge` (merge gate) -- both stored on PipelineRun.

### Progressive Merge
SequentialTaskExecutor merges each task to main immediately after validation. Later tasks see all previous output.

### Security
- `FileSystemTool.safeResolve()`: all paths must stay inside `workspace/{runId}/`
- `ShellSandboxPolicy`: configurable command allow/blocklist
- Sensitive files (`.env`, `credentials.json`): WARN logged but NOT blocked

### SSE
SseBroadcaster is in-memory. LB cookie affinity (GENERATED_COOKIE, TTL 86400) ensures clients hit same instance as pipeline thread.

### Frontend Embedding
`npm run build` → `src/main/resources/static/`. Spring Boot serves SPA via `SpaFallbackController`.

---

## 6. Model Matrix

| Role | Agent(s) | Default Model |
|------|----------|---------------|
| Planner | PlannerAgent | `claude-sonnet-4-6` |
| Worker | WorkerAgent, CompilerAgent, ApiTestSuiteAgent | `claude-haiku-4-5-20251001` |
| Reviewer | ReviewerAgent | `claude-haiku-4-5-20251001` |
| Scout | ContextAgent, PromptOptimiserAgent | `claude-haiku-4-5-20251001` |
| Chat | ConversationAgent | `claude-haiku-4-5-20251001` |

ModelRouter escalation: `FAST (Haiku)` → `CAPABLE (Sonnet)` → `POWER (Opus)`.

---

## 7. Database Migrations (V1–V17)

| Version | Description |
|---------|-------------|
| V1 | Core schema (pipeline_run, agent_task, agent_message, etc.) |
| V2 | Add tenant_id |
| V3 | Cost and cache fields |
| V4 | Auth schema (app_user, org, project, org_member, project_member) |
| V5 | Seed admin user |
| V6 | Tier config + org run-limit overrides |
| V7 | Fix admin password hash |
| V8 | Per-user GitHub settings (github_pat, github_owner) |
| V9 | Post-run conversation history + iteration linkage |
| V10 | Per-user AI provider keys + Jira/Confluence creds |
| V11 | Org-level AI provider keys |
| V12 | GCS workspace archive columns |
| V13 | Merge conflict tracking table |
| V14 | Key source mode + provider restrictions |
| V17 | Auto-merge flag (pipeline_run.auto_merge) |

---

## 8. REST API (Key Endpoints)

```
POST   /api/runs                          Submit run (202)
GET    /api/runs?status=&projectId=       List runs
GET    /api/runs/{id}                     Run detail
POST   /api/runs/{id}/approve             Plan gate
POST   /api/runs/{id}/merge              Merge gate
POST   /api/runs/{id}/tasks/{tid}/retry  Retry task
POST   /api/runs/{id}/pause              Pause run
POST   /api/runs/{id}/edit-and-restart   Restart with edited goal
POST   /api/runs/{id}/iterate            Create iteration from chat
GET    /api/runs/{id}/events             SSE stream
GET    /api/runs/{id}/files              List files
GET    /api/runs/{id}/file?path=         Read file
GET    /api/runs/{id}/download           Download workspace zip
POST   /api/runs/{id}/conversation       Chat on completed runs
POST   /api/runs/{id}/preview/start      Start preview
DELETE /api/runs/{id}/preview/stop       Stop preview
POST   /api/runs/{id}/deploy             Deploy to Cloud Run
POST   /api/runs/{id}/push-github       Push workspace to GitHub
POST   /api/integrations/jira/trigger   Manual Jira trigger
POST   /api/integrations/confluence/trigger  Manual Confluence trigger
POST   /api/auth/login                   Login → JWT
POST   /api/auth/register               Register
PUT    /api/org/me/settings/keys         Update org API keys
GET    /actuator/health                  Health check
```

---

## 9. Auth & Multi-Tenancy

**Tiers:** FREE (10 runs/month) | PRO (100/month) | ENTERPRISE (unlimited with keys)

**Roles:**
- Global: SUPER_ADMIN
- Org: ORG_ADMIN | BILLING_ADMIN | DEVELOPER
- Project: PROJECT_ADMIN | PROJECT_DEVELOPER

**Run Visibility:** SUPER_ADMIN → all; ORG_ADMIN → all in org; DEVELOPER → own projects; FREE → own runs

**Admin:** `admin@executespec.ai` (password set via V5/V7 migration; see DB seed scripts)

---

## 10. WorkerAgent Tool Dispatch

| Tool call name | Java method |
|---|---|
| readFile / read_file | `fsTool.readFile(runId, path)` |
| writeFile / write_file | `fsTool.writeFile(runId, path, content)` |
| editFile / edit_file | `fsTool.editFile(runId, path, oldStr, newStr)` |
| listFiles / list_files | `fsTool.listFiles(runId, path)` |
| searchFiles / search_files | `fsTool.searchFiles(runId, pattern, directory)` |
| run / run_command / run_build / run_tests | `shellTool.run(runId, command, timeout)` |
| status / git_status | `gitTool.status(runId)` |
| diff / git_diff | `gitTool.diff(runId)` |
| commit / git_commit | `gitTool.commit(runId, message)` |

Result truncation: readFile → 8k chars, shell → 6k chars, diff → 5k chars.

---

## 11. Configuration (`application.yml`)

```yaml
agent:
  workspace: ./workspace
  roles: { planner: anthropic, worker: anthropic, reviewer: anthropic }
  token-budget:
    max-tokens: 16000
    low: 2048 | medium: 4096 | high: 8192
    hard-ceiling-pct: 0.90
  compaction:
    warn-pct: 0.25 | compact-pct: 0.35 | hard-stop-pct: 0.55
  concurrency:
    max-runs: 10 | max-iterations: 80
  compiler:
    enabled: true | max-attempts: 3
executespec:
  validator:
    enabled: true | per-task-level: TYPE | max-retries: 3
```

All secrets use `${VAR:}` pattern. Never hardcode.

---

## 12. Key File Locations

| Purpose | Path |
|---------|------|
| Pipeline orchestrator | `harness/PipelineHarness.java` |
| Worker agent | `agent/WorkerAgent.java` |
| LLM router | `agent/LlmRouter.java` |
| State store | `state/StateStore.java` |
| DB migrations | `resources/db/migration/V*.sql` |
| Agent prompts | `resources/prompts/*.txt` |
| Config | `resources/application.yml` |
| Frontend entry | `frontend/src/main.tsx` |
| API client | `frontend/src/lib/api.ts` |
| CI/CD | `.github/workflows/deploy.yml` |

---

## 13. GCP Infrastructure

| Resource | Details |
|----------|---------|
| GCE MIG | 2x n2-standard-4, us-central1-a |
| Cloud SQL | PostgreSQL 15, private IP |
| Redis | Cloud Memorystore 1GB Basic |
| GCS | `executespec-workspaces` + `executespec-deploy` |
| Load Balancer | HTTPS, 600s timeout, GENERATED_COOKIE affinity |
| CI/CD | GitHub Actions → JAR (SHA-keyed) → GCS → GCE via IAP |
| JVM flags | `-Xms1g -Xmx10g -XX:+UseZGC -XX:+ZGenerational` |

---

## 14. Recreation Steps

1. Spring Boot scaffold with Web, JPA, PostgreSQL, Flyway, Lombok
2. Add Spring AI BOM + Anthropic/OpenAI/Google providers
3. Add JGit 6.9.0 + Redis starter
4. Create Flyway migrations V1–V14
5. Build core pipeline: PipelineHarness → PlannerAgent → WorkerPool → ReviewerAgent
6. Add tool system: FileSystemTool, ShellTool, GitTool (workspace sandboxing)
7. Add LLM routing: 3-chain failover + guardrail modes
8. Add token management: TokenBudgetMonitor, compaction, sliding window
9. Add approval gates: GateStore (Redis), SSE notifications
10. Add compiler validation: CompilerAgent
11. Add code review: ReviewerAgent + ReviewerAgentV2
12. Add merge service: GitMergeService with conflict resolution
13. Add auth: JWT, users, orgs, projects, tiers, RBAC
14. Add validator library: CodeValidator with language toolchains
15. Add model router: tier escalation + provider failover
16. Add sequential executor: validate-per-task + retry
17. Add recovery: StuckRunMonitor, auto-restart
18. Add deployment: 11 strategies + Cloud Run client
19. Add preview: LocalPreviewService (ProcessBuilder)
20. Add integrations: Jira, Confluence, ServiceNow, PagerDuty
21. Add post-merge agents: ContextAgent, PromptOptimiserAgent, ApiTestSuiteAgent
22. Add conversation: ConversationAgent with workspace context
23. Add cost estimation + pricing
24. Build React frontend with SSE, Zustand, tabs
25. Add CI/CD: GitHub Actions → GCS → GCE

---

## 15. Environment Variables

| Variable | Required | Default |
|----------|----------|---------|
| `ANTHROPIC_API_KEY` | Yes | — |
| `OPENAI_API_KEY` | Yes | — |
| `GOOGLE_API_KEY` | No | — |
| `POSTGRES_HOST` | No | `localhost` |
| `POSTGRES_PORT` | No | `5432` |
| `POSTGRES_DB` | No | `executespec` |
| `POSTGRES_USER` | No | `postgres` |
| `POSTGRES_PASSWORD` | No | `postgres` |
| `REDIS_HOST` | No | `localhost` |
| `GITHUB_PAT` | No | — |
| `GITHUB_OWNER` | No | — |
| `JWT_SECRET` | No | dev default |
| `GCP_PROJECT` | No | — |
| `CORS_ALLOWED_ORIGINS` | No | `http://localhost:5173` |

---

## 16. Build & Run

```bash
# Backend
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
mvn clean package -DskipTests

# Frontend
cd frontend && npm install && npm run build

# Run
java -jar target/executespec-0.0.1-SNAPSHOT.jar

# Dev mode
cd frontend && npm run dev    # localhost:5173
mvn spring-boot:run           # localhost:8080

# Compile check
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q
```

---

## 17. Recurring Issues & Fixes

| Issue | Fix |
|-------|-----|
| Branch retry fails | `GitTool.createBranch()` catches `RefAlreadyExistsException` → checkout fallback |
| Token cost explosion | Sliding window (first 2 + last 18) + result truncation |
| Completion false positive | `looksLikeCompletionReport()` requires response starts with `{` |
| 80-iteration cap hit | Progress checkpoints every 15 iters + urgency at iter 75 |
| Hard stop at 73% context | Pre-call estimation + emergency compaction |
| Chat has no code access | ConversationAgent injects file tree, README, context, plan, review |
| Gate lost on server restart | `approve()` → if gate missing → auto-fail with message |
| JAVA_HOME not set | Export `JAVA_HOME=/opt/homebrew/opt/openjdk@21` before mvn |
