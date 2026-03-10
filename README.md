# ExecuteSpec

Multi-agent autonomous coding system. Decomposes natural-language goals into sequential coding tasks, executes them with LLM worker agents, validates, reviews, and merges the result.

**Stack:** Java 21 · Spring Boot 3.3 · Spring AI 1.1.0 · PostgreSQL · React 18 · Vite 5 · Tailwind

---

## Quick Start

```bash
# Prerequisites: Java 21, PostgreSQL (localhost:5432/executespec), Node.js 18+

export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANTHROPIC_API_KEY=sk-ant-...
export OPENAI_API_KEY=sk-...

# Option 1: Supervisor (recommended)
./supervisor.sh start

# Option 2: Manual
npm --prefix frontend install && npm --prefix frontend run build
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run -DskipTests
```

Open **http://localhost:8080**

Default admin: `admin@executespec.ai` / `REDACTED_FOR_PUBLIC_RELEASE` (forced password change on first login)

---

## Pipeline

```
POST /api/runs  →  ReferenceExtractor (JIRA/Confluence context)
                      ↓
                Clarification Gate  (if ambiguous references detected)
                      ↓
                PlannerAgent (Sonnet)
                      ↓
                Plan Approval Gate  (or auto-bypass with skipApproval)
                      ↓
                SequentialTaskExecutor
                   → WorkerAgent per task (Haiku)
                   → CodeValidator (compile/type/lint)
                   → Retry with model escalation on failure
                      ↓
                CompilerAgent (full project build)
                      ↓
                ReviewerAgent → Merge Gate  (or auto-bypass with autoMerge)
                      ↓
                GitMergeService → GitHub push → Post-merge agents
```

---

## Key Features

- **Sequential task execution** with per-task code validation and model escalation
- **Two human-in-the-loop gates** — plan approval + merge approval (with optional auto-bypass via `skipApproval` / `autoMerge`)
- **Code validation** — language toolchains for Java, TypeScript, Python, React (syntax → type → lint → test)
- **Model routing** — tier escalation (FAST → CAPABLE → POWER) with Anthropic → OpenAI failover
- **Auth & multi-tenancy** — JWT, orgs, projects, tiers (FREE/PRO/ENTERPRISE), RBAC
- **Organization management** — create orgs, invite members, manage projects, admin email, domain-based invitations with super-admin escalation
- **Org-level integrations** — JIRA/Confluence credentials managed at org level by admins with test-connection validation
- **JIRA/Confluence context extraction** — auto-detects issue keys and page URLs in run requests, fetches content via org credentials, enriches planner input; clarification gate for ambiguous mentions
- **Slack notifications** — org-level OAuth, per-project channel routing, configurable verbosity (MINIMAL/NORMAL/VERBOSE), Block Kit messages with interactive buttons
- **API key management** — SYSTEM / OWN_KEYS / HYBRID modes with per-provider restrictions
- **Process-based local preview** — auto-detects project type, starts dev server
- **Post-run AI chat** — conversation panel with workspace context injection
- **External integrations** — Jira, Confluence, ServiceNow, PagerDuty (webhook + manual triggers), Slack (push notifications)
- **GitHub integration** — auto-create repos with random names, push code, clone existing repos; GitHub-backed code viewing when local workspace is cleaned up
- **Self-healing workers** — progress checkpoints, completion urgency, emergency compaction
- **Real-time observability** — SSE events, D3.js Gantt timeline, token charts
- **GCP deployment** — GCE MIG + Cloud SQL + Redis + GCS + Load Balancer

---

## Documentation

| Document | Description |
|----------|-------------|
| [docs/WORKFLOWS.md](docs/WORKFLOWS.md) | Feature workflow diagrams — Mermaid user journey diagrams for every major feature |
| [docs/PROJECT_CONTEXT.md](docs/PROJECT_CONTEXT.md) | Full project context — architecture, packages, design decisions, recreation guide |
| [docs/JAVADOC.md](docs/JAVADOC.md) | Detailed code documentation for every package and class |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | Local development setup + GCP production deployment guide |
| [docs/requirements/](docs/requirements/) | Feature requirement docs (one per feature area) |

### Requirement Documents

| Document | Coverage |
|----------|----------|
| [core-pipeline.md](docs/requirements/core-pipeline.md) | Pipeline, planner, worker, gates, compiler, reviewer, merge, post-merge |
| [auth-multitenancy.md](docs/requirements/auth-multitenancy.md) | JWT auth, tiers, orgs, RBAC, visibility, projects |
| [api-key-management.md](docs/requirements/api-key-management.md) | Key sources, resolution, provider restrictions, LLM/model routing |
| [code-validation.md](docs/requirements/code-validation.md) | Validator library, toolchains, gate chain, retry with feedback |
| [deployment-preview.md](docs/requirements/deployment-preview.md) | Local preview, Cloud Run, workspace management, GCS, GitHub |
| [integrations.md](docs/requirements/integrations.md) | Jira, Confluence, ServiceNow, PagerDuty, Slack, context extraction, org-level credential management |
| [recovery-observability.md](docs/requirements/recovery-observability.md) | Stuck run monitor, recovery, health, SSE, token budget, cost |
| [post-run-features.md](docs/requirements/post-run-features.md) | Conversation, iteration, pause/edit, retry, post-merge agents |
| [user-onboarding-rbac.md](docs/requirements/user-onboarding-rbac.md) | User onboarding, org creation, and role-based access control |

---

## Database Migrations

20 Flyway migrations (V1–V20) run automatically on startup:

| Version | Description |
|---------|-------------|
| V1–V3 | Core schema, tenant_id, cost/cache fields |
| V4–V7 | Auth schema, admin seed, tier config, password fix |
| V8 | Per-user GitHub settings |
| V9 | Conversation history + iteration linkage |
| V10–V11 | Per-user + org-level AI provider keys |
| V12 | GCS workspace archive columns |
| V13 | Merge conflict tracking |
| V14 | Key source mode + provider restrictions |
| V17 | Auto-merge flag |
| V18 | Org-level integration settings (admin_email, JIRA/Confluence credentials) |
| V19 | Agent token breakdown (per-task input/output token tracking) |
| V20 | Slack integration (org_slack_integration, project_slack_channel, slack_notification_log) |

---

## Build Check

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q    # Java
npm --prefix frontend run build                           # Frontend
```
