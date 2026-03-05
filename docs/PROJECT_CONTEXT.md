# PROJECT_CONTEXT.md — ExecuteSpec

## Project Identity
- **Name**: ExecuteSpec
- **Type**: Multi-agent autonomous coding system
- **Status**: Production-deployed (GCP GCE, live customers)
- **Repo**: Spring Boot 3.3 + React 18 monorepo (`com.navneet.agentteam`)

---

## Architecture

### Pipeline Flow
```
Human prompt → PlannerAgent → [PLAN APPROVAL GATE] → WorkerAgents (parallel)
→ PreviewAgent → ReviewerAgent → [MERGE GATE] → GitMergeService → GitHub push
→ PostMergeOrchestrator (ContextAgent + PromptOptimiserAgent + ApiTestSuiteAgent)
```

### Key Design Principles
- Workers use XML `<tool_call>{"name":"...", "args":{...}}</tool_call>` format — NOT Spring AI native tool calling
- Tool dispatch is manual: `parseXmlToolCalls()` + `dispatchTool()` with `runId` injected automatically
- Completion detection: response must START with `{` (strict JSON check — `looksLikeCompletionReport()`)
- Semaphore `runSlots(10)` limits concurrent runs
- Gates (plan + merge) use Redis polling (500ms loop) — NOT in-memory CompletableFuture (multi-instance safe)
- SSE is in-memory (SseBroadcaster); LB uses cookie affinity so clients hit same instance as pipeline thread

---

## Stack

| Component | Technology |
|-----------|-----------|
| Java | 21 (`JAVA_HOME=/opt/homebrew/opt/openjdk@21`) |
| Framework | Spring Boot 3.3.0 |
| AI SDK | Spring AI 1.1.0 |
| Planner LLM | `claude-sonnet-4-6` |
| Worker/Reviewer/Scout LLM | `claude-haiku-4-5-20251001` |
| Fallback LLM | `gpt-4o` / `gpt-4o-mini` (OpenAI) |
| Database | PostgreSQL 15 (Cloud SQL, private IP) — `jdbc:postgresql://localhost:5432/agentteam` |
| Cache/Gates | Redis (Cloud Memorystore 1GB Basic) — gate signaling + ShedLock |
| Object Store | Google Cloud Storage (`executespec-workspaces` + `executespec-deploy`) |
| Auth | JWT — 1440min (24h) access + 7-day refresh |
| Frontend | React 18 + Vite 5 + Tailwind 3.4 + shadcn/ui + Zustand + D3.js + Framer Motion |
| Build | Maven (Java) + npm (frontend, embedded in JAR via `src/main/resources/static/`) |
| Infra | GCE MIG (2× n2-standard-4) + Cloud Load Balancer + Cookie Affinity |

---

## Package Structure (`com.navneet.agentteam`)

| Package | Contents |
|---------|----------|
| `agent/` | PlannerAgent, WorkerAgent, ReviewerAgent, LlmRouter (3-chain), CostEstimator, TokenBudgetMonitor, LlmOutputParser |
| `agent/model/` | TaskPlan, TaskDefinition, WorkerReport, ReviewReport |
| `api/controller/` | RunController, SseController, MetricsController, PricingController, FeasibilityController, TimelineController, DeploymentController, LocalPreviewController, IntegrationController, IntegrationStatusController |
| `api/dto/` | RunSummary, RunDetail, TaskSummary, TaskDefinition |
| `apitests/` | ApiTestSuiteAgent (generates openapi.yaml after merge) |
| `auth/` | AppUser, OrgMember, Org, Project, ProjectMember, TierConfig entities + services + controllers; AuthService, OrgService, ProjectService, TierEnforcementService, InvitationService |
| `auth/dto/` | OrgDto, ProjectDto, UserDto, etc. |
| `compiler/` | CompilerAgent |
| `config/` | AgentProperties, AgentModelConfig (HTTP/1.1, 300s timeout — NO AnthropicCacheInterceptor), ModelPricingInitializer, WebConfig, SchedulingConfig (ShedLock LockProvider) |
| `context/` | ContextAgent (post-merge codebase scanner), ContextAgentScanner, ContextAgentProperties |
| `deployment/` | DeploymentService, DeploymentProperties, DockerfileGenerator, LocalPreviewService, PreviewAgent, GcsWorkspaceStore (GCS upload/download/signed URLs), WorkspaceArchiveJob (hourly, ShedLock), WorkspaceExportService (zip + GCS fallback), WorkspaceFileService, DeploymentGitHubService, ArtifactRegistryClient, CloudRunClient |
| `harness/` | PipelineHarness (main orchestrator), WorkerPool, ApprovalGate, GitMergeService, GateStore (Redis gate signaling + polling), MergeBlockedException |
| `harness/event/` | PipelineEvent, PostMergeOrchestrator |
| `integration/` | jira, confluence, servicenow, pagerduty adapters + IntegrationManager |
| `optimiser/` | PromptOptimiserAgent, PromptAuditService, PromptPatternAnalyser, PromptOptimiserProperties |
| `recovery/` | StuckRunMonitor, RunRecoveryService, StuckRunEvaluator, LlmCallGuard, EscalationService, RecoveryProperties |
| `repo/` | GitHubRepoService |
| `sse/` | RunEvent (record + factory methods), SseBroadcaster (in-memory, sticky via LB) |
| `state/` | StateStore (all DB ops), JPA entities: PipelineRun (+gcsWorkspaceKey/gcsArchivedAt), AgentTask, AgentMessage, FileLock, AgentInbox, GateEvent, CompactionEvent, RunRecovery, PromptAuditEntry, RunRepo, Deployment, IntegrationRun, IntegrationEventLog |
| `state/repository/` | PipelineRunRepository (findRunsToArchive query) |
| `tenant/` | TenantInterceptor |
| `tool/` | FileSystemTool, ShellTool, GitTool (commitIfDirty()), FileLockTool |

---

## LlmRouter Chains

| Chain | Primary | Fallback |
|-------|---------|---------|
| `ARCHITECT_CHAIN` | `claude-opus-4-6` | `gpt-4o` |
| `CAPABLE_CHAIN` | `claude-sonnet-4-6` | `gpt-4o` |
| `UTILITY_CHAIN` | `claude-haiku-4-5-20251001` | `gpt-4o-mini` |

Agent assignments: PlannerAgent → CAPABLE_CHAIN; WorkerAgent, CompilerAgent, ApiTestSuiteAgent, ReviewerAgent, ContextAgent, PromptOptimiserAgent → UTILITY_CHAIN.

---

## Key Design Decisions

1. **WorkerAgent XML tool calls**: `<tool_call>{"name":"...", "args":{...}}</tool_call>`. RunId injected by dispatcher. NOT Spring AI native. `@Tool` methods require runId which the model doesn't know.
2. **Prompt caching**: `SYSTEM_ONLY` strategy. Sliding window: first 2 + last 18 messages per API call. `AnthropicChatOptions.builder().maxTokens(maxOut).cacheOptions(SYSTEM_ONLY)` only — no toolCallbacks.
3. **Redis gates**: `gate:plan:{runId}` and `gate:merge:{runId}` keys (TTL 2h). Pipeline thread polls every 500ms. REST endpoint sets key on any instance. `GateStore.java` in `harness/`.
4. **SSE sticky sessions**: SseBroadcaster is in-memory. LB cookie affinity (GENERATED_COOKIE, TTL 86400) ensures clients connect to same instance as pipeline thread.
5. **Gate-slot release pattern**: Semaphore slot released before `gate.get()`, reacquired after. `doReviewAndMerge(run, holdsComputeSlot)` flag controls conditional release.
6. **GCS workspace archiving**: Runs archived to `gs://executespec-workspaces/workspaces/{runId}.zip` after 24h. Download redirects to signed URL (302). `WorkspaceArchiveJob` runs hourly with ShedLock.
7. **Frontend embedded in JAR**: `npm run build` → `src/main/resources/static/` (vite outDir). `SpaFallbackController` serves SPA.
8. **Per-user API keys**: Resolution order: user key → org key → system key (application.yml). Stored encrypted in DB.
9. **application.yml secrets**: ALL use `${VAR:}` (empty default). NO hardcoded values ever.
10. **GitTool.commitIfDirty()**: Stages + commits if dirty, silent no-op if clean. Used by ApiTestSuiteAgent to prevent retried workers from picking up openapi.yaml via `git add .`.
11. **lombok.config**: `copyableannotations += @Qualifier`.
12. **WorkspaceInitService**: Per-run isolated workspace `workspace/{runId}/`. `git config user.email/user.name` set before first commit.
13. **Completion detection**: Response must START with `{` — `looksLikeCompletionReport()` strict check. Prevents false positives on prose containing JSON field names.

---

## Auth & Multi-Tenancy

| Tier | Run Limit | Notes |
|------|-----------|-------|
| FREE | 5/month | Fixed |
| PRO | 50/month | Org-overridable |
| ENTERPRISE | Unlimited (w/ api_keys_provided=true) | Else 5000/month |

- **Org roles**: SUPER_ADMIN (global) | ORG_ADMIN | BILLING_ADMIN | DEVELOPER
- **Project roles**: PROJECT_ADMIN | PROJECT_DEVELOPER
- **Run visibility**: SUPER_ADMIN → all; ORG_ADMIN/BILLING_ADMIN → all in org; DEVELOPER → own projects; FREE → own runs
- **Admin account**: `admin@executespec.ai` / `Admin@123`

---

## Database Migrations

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
| V10 | Per-user AI provider API keys (Anthropic, OpenAI, Gemini) + JIRA/Confluence creds |
| V11 | Org-level AI provider API keys |
| V12 | GCS workspace archive columns (gcs_workspace_key, gcs_archived_at) on pipeline_run |

---

## REST API (Key Endpoints)

```
POST   /api/runs                              Submit run (202)
GET    /api/runs?status=&projectId=           List runs
GET    /api/runs/{id}                         Run detail
POST   /api/runs/{id}/approve                 Approve/reject plan gate
POST   /api/runs/{id}/merge                   Approve/reject merge gate
DELETE /api/runs/{id}                         Cancel run
POST   /api/runs/{id}/tasks/{tid}/retry       Retry task (PARTIAL/FAILED/BLOCKED/PENDING/LOCKED)
GET    /api/runs/{id}/events                  SSE stream
GET    /api/runs/{id}/download                Download workspace zip (302→GCS signed URL if archived)
GET    /api/runs/{id}/files                   List workspace files
GET    /api/runs/{id}/file?path=              Read workspace file
GET    /api/runs/{id}/timeline                Run timeline
GET    /api/runs/{id}/recovery                Recovery info
GET    /api/runs/{id}/preview                 Preview info
POST   /api/runs/{id}/preview/start           Start preview
DELETE /api/runs/{id}/preview/stop            Stop preview
GET    /actuator/health                       LB health check
POST   /api/auth/login                        Login
POST   /api/auth/register                     Register
POST   /api/auth/refresh                      Refresh JWT
POST   /api/auth/logout                       Logout
POST   /api/auth/settings/github              Save GitHub PAT + owner
GET    /api/projects                          List projects
POST   /api/projects                          Create project (ORG_ADMIN+)
GET    /api/projects/{id}/members             List members
POST   /api/projects/{id}/members             Add member (ORG_ADMIN+)
DELETE /api/projects/{id}/members/{userId}    Remove member (ORG_ADMIN+)
PUT    /api/projects/{id}/archive             Archive project
GET    /api/metrics                           Global stats
POST   /api/feasibility                       Estimate cost
GET    /api/model-comparison?days=30          Model comparison
GET    /api/deployments                       List deployments
POST   /api/deployments                       Create deployment
DELETE /api/deployments/{id}                  Delete deployment
POST   /api/integrations/{source}/webhook     Inbound webhook
GET    /api/integrations/status               Integration status
GET    /api/org/me                            My org
GET    /api/org/me/members                    Org members
DELETE /api/org/me/members/{userId}           Remove member
PUT    /api/org/me/members/{userId}/suspend   Suspend member
GET    /api/admin/users                       All users (SUPER_ADMIN)
PUT    /api/admin/users/{id}/tier             Change user tier
PUT    /api/admin/orgs/{id}/run-limit         Override org run limit
GET    /api/pricing                           Pricing table
PUT    /api/pricing/{provider}/{model}        Update pricing
```

---

## Token Budget (`application.yml`)

```yaml
agent:
  token-budget:
    max-tokens: 16000
    low: 2048       # LOW complexity max output tokens
    medium: 4096    # MEDIUM complexity max output tokens
    high: 8192      # HIGH complexity max output tokens
  compaction:
    warn-pct: 0.25
    compact-pct: 0.35
    hard-stop-pct: 0.55
  concurrency:
    max-runs: 10
```

---

## WorkerAgent Tool Dispatch

| Tool call name | Java method |
|----------------|-------------|
| readFile / read_file | `fsTool.readFile(runId, path)` |
| writeFile / write_file | `fsTool.writeFile(runId, path, content)` |
| editFile / edit_file | `fsTool.editFile(runId, path, oldStr, newStr)` |
| listFiles / list_files | `fsTool.listFiles(runId, path)` |
| searchFiles / search_files | `fsTool.searchFiles(runId, pattern, directory)` |
| run / run_command / run_build / run_tests | `shellTool.run(runId, command, timeoutSeconds)` |
| status / git_status | `gitTool.status(runId)` |
| diff / git_diff | `gitTool.diff(runId)` |
| commit / git_commit | `gitTool.commit(runId, message)` |

Result truncation limits: `readFile` → 8k chars, shell → 6k chars, diff → 5k chars.

---

## PipelineHarness Key Flows

1. `submitRun()` → acquire `runSlot` semaphore → PlannerAgent → plan gate (Redis poll 500ms)
2. Slot released before gate wait; gate key set by REST endpoint on any instance
3. Workers run in parallel (`WorkerPool`) → PreviewAgent → ReviewerAgent → merge gate (Redis poll)
4. **PARTIAL state**: if any tasks incomplete → mark PARTIAL, skip merge gate; user must retry tasks
5. `doReviewAndMerge(run, holdsComputeSlot)` — called from initial + retry paths; slot released only when `holdsComputeSlot=true`
6. `retryPartialTask()` — accepts PARTIAL/BLOCKED/FAILED/PENDING/LOCKED; auto-picks all incomplete siblings; full reviewer → merge gate cycle
7. PostMergeOrchestrator (event-driven via `MergeCompleted`): ContextAgent + PromptOptimiserAgent + ApiTestSuiteAgent

---

## Prompts (Key Rules)

- **worker.txt**: `<tool_call>` XML format; step 7 = UPDATE README.md (hard requirement); project_setup task writes pom.xml/package.json first
- **planner.txt**: `assigned_model = haiku`; must include `project_setup` as first dependency; always adds `generate_preview` task
- **reviewer.txt**: step 5 = README UPDATE CHECK

---

## Recurring Issues & Fixes

| Issue | Fix |
|-------|-----|
| Branch retry fails | `GitTool.createBranch()` catches `RefAlreadyExistsException` → plain checkout fallback |
| Token cost explosion | Sliding window (first 2 + last 18) + result truncation |
| Completion false positive | `looksLikeCompletionReport()` requires response starts with `{` |
| OpenAPI spec deleted on retry | `gitTool.commitIfDirty()` immediately after ApiTestSuiteAgent writes spec |
| 403 on file access | `reqText()` in `api.ts` needs `headers: { ...authHeader() }` |
| PENDING/LOCKED not retryable | `retryPartialTask()` extended to accept these statuses; `otherIncomplete` filter includes them; `TaskCard` shows retry button when `runStatus=PARTIAL/FAILED` |
| Admin password wrong | Fixed via V7 migration (UPDATE app_user SET password_hash = correct bcrypt hash) |
| Gate lost on server restart | `PipelineHarness.approve()` → if gate missing → auto-fail with message |
| Tools broken (ToolCallingChatOptions) | Was dead code — removed; restored XML parsing in WorkerAgent |

---

## Build Commands

```bash
# Compile check
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q

# Build JAR (skips tests)
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn package -DskipTests

# Build frontend (output → src/main/resources/static/)
npm --prefix frontend run build

# Start with monitoring
./supervisor.sh start

# SSH to GCE via IAP
gcloud compute ssh VM --tunnel-through-iap --zone=ZONE
```

---

## GCP Infrastructure

| Resource | Details |
|----------|---------|
| GCE MIG | 2× n2-standard-4, zone us-central1-a |
| Boot disk | 30GB pd-ssd |
| Data disk | 100GB pd-ssd, mounted `/data` (workspaces) |
| Cloud SQL | PostgreSQL 15, db-custom-2-3840, private IP only |
| Redis | Cloud Memorystore 1GB Basic |
| GCS | `executespec-workspaces` (run archives), `executespec-deploy` (CI/CD JARs) |
| Load Balancer | External Application LB, HTTPS, 600s timeout, GENERATED_COOKIE affinity (TTL 86400) |
| CI/CD | GitHub Actions → JAR keyed by SHA → GCS → SSH via IAP → symlink → restart |
| Rollback | Re-symlink previous SHA JAR + `systemctl restart` (~20s, no rebuild) |
| Service account | `executespec-sa` with `cloudsql.client`, `storage.objectAdmin`, `secretmanager.secretAccessor`, `iam.serviceAccountTokenCreator` |
| JVM flags | `-Xms1g -Xmx10g -XX:+UseZGC -XX:+ZGenerational -XX:MaxGCPauseMillis=100` |

---

## Web UI

- **Dev**: `npm --prefix frontend run dev` → `:5173` (proxies `/api` → `:8080`)
- **Build**: `npm --prefix frontend run build` → `src/main/resources/static/`
- **Pages**: Runs (+ project filter), Dashboard (metrics + D3 charts), Pricing + Feasibility, Deployments, Integrations, Settings, Org Projects (member management), Org Settings, Admin Panel (4 tabs)
- **SPA fallback**: `config/SpaFallbackController.java`
- **Key components**: RunList (project filter bar), TaskCard (failure reason + retry for PENDING/LOCKED), LiveLog (milestone banners + file-op rows), MarkdownContent (AI chat formatting), OrgProjectsPage (expandable cards + member management)

---

## Branch State

- **main**: production-ready, all features merged
- **Stable checkpoint**: commit `5aee1b3` on `feature/multi-lang-preview-ai-conversation` (last known-good before redundancy refactoring)
- **Rollback**: `git checkout feature/multi-lang-preview-ai-conversation && git reset --hard 5aee1b3`

---

## PostgreSQL Credentials

```
Host:     localhost:5432 (Cloud SQL via proxy or private IP)
Database: agentteam
User:     ${POSTGRES_USER:postgres}
Password: ${POSTGRES_PASSWORD:postgres}
```

All secrets in `application.yml` use `${VAR:}` pattern (empty default). Never hardcode values.
