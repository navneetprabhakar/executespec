# ExecuteSpec — Multi-Agent Autonomous Coding System

> A human-in-the-loop pipeline that decomposes natural-language goals into parallel coding tasks, executes them with LLM worker agents, reviews and merges the result, then optionally deploys to GCP.

---

## Key Features

- **Multi-agent pipeline** — Planner → parallel Workers (up to 10 concurrent) → Compiler → Reviewer → merge gate
- **Human-in-the-loop** — two approval gates (plan gate + merge gate) with full override support
- **Multi-instance ready** — Redis-backed gates (no in-memory CompletableFutures); LB cookie affinity for SSE; ShedLock for distributed job locking
- **GCS workspace archiving** — completed run workspaces auto-archived to GCS after 24 h; downloads redirect to signed GCS URLs (zero server bandwidth)
- **10-language deployment** — Spring Boot, Gradle, Node.js, Python, Go, Ruby, PHP, Rust, .NET, Static — auto-detected, auto-Dockerfiled
- **Per-user GitHub integration** — push generated code to users' own GitHub repos; clone existing repos to start from
- **Per-user AI keys** — users + orgs can supply their own Anthropic/OpenAI/Gemini keys (resolution: user → org → system)
- **Auth & multi-tenancy** — JWT authentication, orgs, projects, tiers (FREE/PRO/ENTERPRISE), role-based access (ORG_ADMIN, DEVELOPER, BILLING_ADMIN)
- **External integrations** — Jira, Confluence, ServiceNow, PagerDuty webhooks trigger runs automatically
- **Real-time observability** — SSE event stream, D3.js Gantt timeline, token bubble chart, agent comms graph
- **Self-healing** — stuck-run monitor, LLM call guard (rate-limit retry + fallback), prompt optimiser
- **Post-run AI chat** — iterate on the generated code via a conversation panel
- **GCP Cloud Run deployment** — Docker build → Artifact Registry → Cloud Run, 7-day TTL cleanup

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.3.0 |
| AI / LLM | Spring AI 1.1.0 |
| Primary LLM | Claude (Anthropic) — Haiku 4.5 (workers) + Sonnet 4.6 (planner) |
| Fallback LLM | OpenAI GPT-4o / GPT-4o-mini |
| Database | PostgreSQL 15+ (Cloud SQL, private IP) |
| Cache / Gates | Redis — Cloud Memorystore (gate signaling + ShedLock) |
| Object Store | Google Cloud Storage (workspace archives + CI/CD JARs) |
| ORM | Spring Data JPA + Hibernate |
| Migrations | Flyway (V1–V12) |
| Git | JGit 6.9.0 |
| Auth | JWT (Spring Security) — 24h access + 7d refresh |
| Frontend | React 18 + Vite 5 + Tailwind 3.4 + shadcn/ui + Zustand + D3.js |
| Infra | GCE MIG (2× n2-standard-4) + Cloud Load Balancer + Cookie Affinity |
| Build | Maven (`JAVA_HOME=/opt/homebrew/opt/openjdk@21`) |

---

## Quick Start

### Prerequisites

- Java 21 (`/opt/homebrew/opt/openjdk@21`)
- PostgreSQL running on `localhost:5432` with database `agentteam`
- Node.js 18+ (for frontend build)
- Redis running on `localhost:6379` (for gate signaling + ShedLock)
  ```bash
  docker run -d -p 6379:6379 redis:7-alpine
  ```

### Environment Variables

```bash
export ANTHROPIC_API_KEY=sk-ant-...      # required
export OPENAI_API_KEY=sk-...             # required for fallback
export POSTGRES_USER=postgres
export POSTGRES_PASSWORD=postgres
export REDIS_HOST=localhost              # Redis host (default: localhost)
# Optional
export GITHUB_PAT=ghp_...               # system-level GitHub PAT fallback
export GITHUB_OWNER=your-github-username
export GCS_WORKSPACE_BUCKET=executespec-workspaces   # for workspace archiving
export GCP_PROJECT=my-gcp-project
```

### Start (with supervisor — recommended)

```bash
# Build frontend + start backend with auto-restart and health monitoring
./supervisor.sh start

./supervisor.sh status   # check state
./supervisor.sh logs     # tail app logs
./supervisor.sh stop     # graceful stop
```

### Start (manual)

```bash
# Build frontend
npm --prefix frontend install
npm --prefix frontend run build

# Run backend
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run -DskipTests
```

### Development mode

```bash
# Frontend dev server (proxies /api → localhost:8080)
npm --prefix frontend run dev    # → http://localhost:5173

# Backend
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run -DskipTests
```

### Compile check

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q
npm --prefix frontend run build
```

Open **http://localhost:8080** to access the dashboard.

---

## Architecture

```
POST /api/runs
      │
      ▼
PipelineHarness (Semaphore — max 10 concurrent runs)
      │
      ├─ PlannerAgent ──────────── claude-sonnet-4-6 → TaskPlan JSON (1–6 tasks)
      │
      ├─ ▶ Plan approval gate ──── POST /api/runs/{id}/approve
      │        (Redis polling — works across any instance in MIG)
      │
      ├─ WorkerPool ──────────────  Virtual threads, parallel by dependsOn
      │    ├─ WorkerAgent [A] ────  XML tool-call format; own git branch
      │    └─ WorkerAgent [B] ────  Tools: FileSystem, Shell, Git, FileLock
      │
      ├─ CompilerAgent ─────────── Detects build tool; compile + fix (max 3 rounds); non-fatal
      │
      ├─ ReviewerAgent ─────────── JSON ReviewReport; README check hard gate
      │
      ├─ ▶ Merge approval gate ─── POST /api/runs/{id}/merge
      │        (Redis polling — works across any instance in MIG)
      │
      ├─ GitMergeService ────────── Topological merge to main
      │
      └─ Post-merge (async)
           ├─ ApiTestSuiteAgent ── Generates api-docs/openapi.yaml
           ├─ ContextAgent ─────── Updates CODEBASE_CONTEXT.md
           ├─ PromptOptimiser ──── Every 10 runs: patches prompts
           ├─ PreviewService ───── npm build → hasFrontend flag
           └─ GitHub push ──────── If user has PAT configured
```

### GCP Production Architecture

```
Internet
    │
    ▼
Cloud Load Balancer (HTTPS, managed SSL, 600s timeout, cookie affinity)
    │
    ▼
MIG — 2× n2-standard-4 GCE VMs
    │   ├─ Spring Boot app  :8080
    │   ├─ /data/workspace/  ← pd-ssd 100 GB (ACTIVE runs only, <24 h)
    │   └─ ADC service account → GCS, Cloud SQL, Redis
    │
    ├──► Cloud SQL PostgreSQL 15  (private IP, same VPC)
    │
    ├──► Cloud Memorystore Redis  (1 GB Basic — gate signaling + ShedLock)
    │
    └──► GCS  executespec-workspaces  (archived run workspaces, signed URL downloads)
         GCS  executespec-deploy      (CI/CD JARs keyed by commit SHA)
```

---

## Auth & Multi-Tenancy

ExecuteSpec has a full authentication system with JWT tokens, multi-org support, and role-based access control.

### Tiers

| Tier | Runs/month | Notes |
|---|---|---|
| FREE | 5 | Own runs only, no project access |
| PRO | 50 (org-overridable) | Full project access |
| ENTERPRISE | Unlimited* | Requires `api_keys_provided=true` |

### Roles (org-level)
- **ORG_ADMIN** — manage org, projects, members
- **BILLING_ADMIN** — billing, tier management
- **DEVELOPER** — submit runs, access assigned projects
- **SUPER_ADMIN** — global access across all orgs (system account)

### Default Admin

After first run Flyway seeds an admin account:
- Email: `admin@executespec.ai`
- Password: `REDACTED_FOR_PUBLIC_RELEASE`

### Key Auth Endpoints

```
POST /api/auth/register
POST /api/auth/login
POST /api/auth/refresh
POST /api/auth/logout
PUT  /api/auth/settings/github    # Save personal GitHub PAT + owner
GET  /api/org/me                  # My org details
GET  /api/projects                # Projects I have access to
```

---

## GitHub Integration (Per-User)

Each user can configure their own GitHub PAT in **Settings → GitHub Integration**.

When submitting a run, three modes are available:

| Mode | Behavior |
|---|---|
| **None** | No GitHub interaction |
| **Create new repo** | Auto-creates a private GitHub repo after merge, pushes code |
| **Start from existing** | Clones the specified repo → modifies → pushes back |

The user's PAT (stored on their account) takes precedence over any system-level PAT.

---

## 10-Language Deployment

ExecuteSpec auto-detects the project type and generates an optimised Dockerfile:

| Language | Detection Signal | Strategy |
|---|---|---|
| Spring Boot | `pom.xml` | Two-stage: `eclipse-temurin:21-jdk` → `eclipse-temurin:21-jre-alpine` |
| Gradle (JVM) | `build.gradle` / `build.gradle.kts` | Two-stage: `gradle:8-jdk21` → `eclipse-temurin:21-jre-alpine` |
| Node.js | `package.json` | Server: `node:20-slim` + `npm start`; SPA: multi-stage → nginx |
| Python | `requirements.txt` / `pyproject.toml` / `Pipfile` | `python:3.11-slim`; Django/FastAPI/Flask auto-detected |
| Go | `go.mod` | Two-stage: `golang:1.22-alpine` → `alpine:3.19` |
| Ruby | `Gemfile` | `ruby:3.3-slim`; Rails/Sinatra/Rack auto-detected |
| PHP | `composer.json` | `php:8.2-apache` |
| Rust | `Cargo.toml` | Two-stage: `rust:1.77-slim` → `debian:bookworm-slim` |
| .NET | `*.csproj` / `*.sln` | Two-stage: `dotnet/sdk:8.0` → `dotnet/aspnet:8.0` |
| Static | `index.html` | `nginx:1.25-alpine` |

---

## REST API Reference

### Runs

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/runs` | Submit new run (202) |
| `GET` | `/api/runs?status=&projectId=` | List runs |
| `GET` | `/api/runs/{id}` | Run detail + tasks |
| `POST` | `/api/runs/{id}/approve` | Approve/reject plan gate |
| `POST` | `/api/runs/{id}/merge` | Approve/reject merge gate |
| `DELETE` | `/api/runs/{id}` | Cancel run |
| `POST` | `/api/runs/{id}/tasks/{taskId}/retry` | Retry PARTIAL/FAILED/BLOCKED/PENDING/LOCKED task |
| `GET` | `/api/runs/{id}/events` | **SSE stream** |
| `GET` | `/api/runs/{id}/timeline` | D3.js Gantt data |
| `GET` | `/api/runs/{id}/download` | Download workspace zip (302→GCS signed URL if archived) |
| `GET` | `/api/runs/{id}/files` | List workspace files |
| `GET` | `/api/runs/{id}/file?path=` | Read workspace file |

### Auth & Org

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/auth/login` | Login → JWT tokens |
| `POST` | `/api/auth/register` | Register new user |
| `POST` | `/api/auth/refresh` | Refresh access token |
| `PUT` | `/api/auth/settings/github` | Save GitHub PAT + owner |
| `GET` | `/api/org/me` | My org info |
| `GET` | `/api/org/me/members` | Org members list |
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

### Deployments

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/deployments` | Deploy run to GCP Cloud Run |
| `GET` | `/api/deployments` | List deployments |
| `DELETE` | `/api/deployments/{id}` | Delete deployment |

### Other

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/metrics` | Global run/token/cost stats |
| `POST` | `/api/feasibility` | Estimate cost without running |
| `GET` | `/api/pricing` | Model pricing table |
| `GET` | `/actuator/health` | Spring Boot health (used by LB health check) |
| `POST` | `/api/integrations/{source}/webhook` | Inbound webhook (jira/confluence/servicenow/pagerduty) |

---

## Web UI Pages

| Page | Route | Description |
|---|---|---|
| **Runs** | `/` | Submit runs, live run list, 7-tab detail panel |
| **Dashboard** | `/dashboard` | Metrics, token chart, model comparison, activity feed |
| **Pricing** | `/pricing` | Model pricing + feasibility estimator |
| **Deployments** | `/deployments` | GCP deployment management |
| **Integrations** | `/integrations` | Integration status + manual trigger |
| **Settings** | `/settings` | App config + GitHub PAT + per-user AI keys |
| **Org Projects** | `/org/projects` | Project management (ORG_ADMIN) |
| **Admin Panel** | `/admin` | User/org/tier management (SUPER_ADMIN) |

---

## Database Migrations

| Version | Description |
|---|---|
| V1 | Core schema (pipeline_run, agent_task, agent_message, etc.) |
| V2 | Add tenant_id |
| V3 | Cost and cache fields |
| V4 | Auth schema (app_user, org, project, org_member, project_member) |
| V5 | Seed admin user (`admin@executespec.ai`) |
| V6 | Tier config + org run-limit overrides |
| V7 | Fix admin password hash |
| V8 | Per-user GitHub settings (`github_pat`, `github_owner`) |
| V9 | Post-run conversation history + iteration run linkage |
| V10 | Per-user AI provider keys (Anthropic, OpenAI, Gemini) + Jira/Confluence creds |
| V11 | Org-level AI provider keys (resolution: user → org → system) |
| V12 | GCS workspace archive columns (`gcs_workspace_key`, `gcs_archived_at`) |

---

## Project Structure

```
executespec/
├── src/main/java/com/navneet/agentteam/
│   ├── agent/              # PlannerAgent, WorkerAgent, ReviewerAgent, LlmRouter
│   ├── api/                # Controllers, DTOs, RunMapper
│   ├── apitests/           # ApiTestSuiteAgent
│   ├── auth/               # AppUser, Org, Project, JWT auth, controllers
│   ├── compiler/           # CompilerAgent
│   ├── config/             # AgentProperties, AgentModelConfig, WebConfig, SchedulingConfig
│   ├── context/            # ContextAgent (post-merge codebase scanner)
│   ├── deployment/         # DeploymentService, 10× strategy classes, DockerfileGenerator,
│   │                       #   GcsWorkspaceStore, WorkspaceArchiveJob, WorkspaceExportService
│   ├── harness/            # PipelineHarness, WorkerPool, ApprovalGate, GitMergeService,
│   │                       #   GateStore (Redis gate signaling + polling)
│   ├── integration/        # Jira, Confluence, ServiceNow, PagerDuty adapters
│   ├── optimiser/          # PromptOptimiserAgent
│   ├── recovery/           # StuckRunMonitor, RunRecoveryService, LlmCallGuard
│   ├── repo/               # GitHubRepoService
│   ├── sse/                # RunEvent, SseBroadcaster (in-memory; LB sticky sessions)
│   ├── state/              # StateStore, JPA entities (pipeline_run, agent_task, …)
│   ├── tenant/             # TenantInterceptor (multi-tenancy)
│   └── tool/               # FileSystemTool, ShellTool, GitTool, FileLockTool
├── src/main/resources/
│   ├── prompts/            # planner.txt, worker.txt, reviewer.txt, etc.
│   ├── db/migration/       # Flyway V1–V12
│   └── application.yml
├── docs/
│   ├── DEPLOYMENT.md       # Full GCP deployment guide + checklist
│   └── PROJECT_CONTEXT.md  # Compressed context for new AI conversation threads
├── .github/workflows/
│   └── deploy.yml          # CI/CD: build JAR → GCS (SHA-keyed) → GCE via IAP
├── frontend/               # React 18 + Vite 5 + Tailwind 3.4 + shadcn/ui
│   └── src/
│       ├── pages/          # RunsPage, DashboardPage, SettingsPage, etc.
│       ├── components/     # RunCard, TaskCard, NewRunModal, LiveLog, etc.
│       ├── stores/         # Zustand: useRunStore, useAuthStore, etc.
│       ├── hooks/          # useSse.ts (SSE with exponential backoff)
│       └── lib/            # api.ts (typed fetch wrappers)
├── supervisor.sh           # Process supervisor with health monitoring
└── openapi.yaml            # Project API specification
```

---

## CI/CD & Deployment

The CI/CD pipeline stores every JAR **keyed by commit SHA** in GCS — giving a full rollback history with no rebuilds.

```
git push main
    → GitHub Actions
    → mvn package -DskipTests
    → gsutil cp target/*.jar gs://executespec-deploy/releases/app-{SHA}.jar
    → gcloud compute ssh GCE --tunnel-through-iap
        → symlink app-{SHA}.jar → /opt/app/app.jar
        → systemctl restart executespec
        → curl /actuator/health
```

**Rollback** (~20 seconds, no rebuild):
```bash
ROLLBACK_SHA=abc123...
gcloud compute ssh executespec-prod --tunnel-through-iap --zone=us-central1-a \
  --command="ln -sf /opt/app/releases/app-${ROLLBACK_SHA}.jar /opt/app/app.jar && \
             sudo systemctl restart executespec && \
             echo ${ROLLBACK_SHA} | sudo tee /opt/app/RUNNING_SHA"
```

See [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) for the full step-by-step GCP setup guide.

---

## Build Status

```
mvn compile -q                  ✅ passes clean
npm --prefix frontend run build ✅ passes clean
```

**Total:** ~170 Java source files · 45+ REST endpoints · 18 DB entities (V1–V12) · 8 LLM prompts · React 18 SPA
