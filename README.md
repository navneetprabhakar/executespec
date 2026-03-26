# ExecuteSpec

Multi-agent autonomous coding system. Decomposes natural-language goals into sequential coding tasks, executes them with LLM worker agents, validates, reviews, and merges the result.

**Stack:** Java 21 · Spring Boot 3.3 · Spring AI 1.1.0 · PostgreSQL · React 18 · Vite 5 · Tailwind

---

## Quick Start

```bash
# Prerequisites: Java 21, PostgreSQL (localhost:5432/executespec), Redis, Node.js 18+

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
                   → Progressive merge to main after each task
                      ↓
                CompilerAgent (full project build)
                      ↓
                Auto-merge all task branches
                      ↓
                ReviewerAgentV2 (inspect merged code + auto-fix)
                      ↓
                Validation Report + Test Report
                      ↓
                COMPLETE → GitHub push → Post-merge agents
```

---

## Key Features

- **Sequential task execution** with per-task code validation and model escalation (FAST → CAPABLE → POWER)
- **Plan approval gate** — human reviews task breakdown (auto-bypass via `skipApproval`)
- **Automatic merge** — no manual merge gate; pipeline auto-merges after review
- **Code validation** — language toolchains for Java, TypeScript, Python, React, Go, Rust (syntax → type → lint → test)
- **ReviewerAgentV2** — inspects merged code, auto-fixes CRITICAL/MINOR issues (2 attempts, FAST → CAPABLE escalation)
- **Validation report** — LLM-generated quality summary, shareable via public URL, downloadable as PDF
- **Model routing** — 3-provider fallback (Anthropic → Google → OpenAI) with weighted round-robin (4:3:3)
- **Auth & multi-tenancy** — JWT, orgs, projects, tiers (FREE/PRO/ENTERPRISE), RBAC
- **Billing** — Razorpay subscriptions + run top-up credits
- **JIRA/Confluence context extraction** — auto-detects issue keys and page URLs, enriches planner input
- **Slack notifications** — org-level OAuth, per-project channel routing, Block Kit messages
- **Cloud Run previews** — auto-deploy for testing, 10-min TTL
- **Post-run AI chat** — conversation panel with workspace context injection
- **GitHub integration** — auto-create repos, push code, clone existing repos
- **GCP deployment** — GCE MIG + Cloud SQL + Redis + GCS + Load Balancer

---

## Documentation

| Document | Audience | Description |
|----------|----------|-------------|
| [CLAUDE.md](CLAUDE.md) | AI assistant | Session instructions, conventions, patterns |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Engineers | System design, Mermaid diagrams, design decisions |
| [docs/JAVADOC.md](docs/JAVADOC.md) | Engineers | Detailed code documentation per package and class |
| [docs/PRD.md](docs/PRD.md) | Product/Business | Product requirements, tiers, user journeys |
| [docs/WORKFLOWS.md](docs/WORKFLOWS.md) | Engineers/Product | Mermaid workflow diagrams for every feature |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | DevOps/Engineers | Local setup + GCP deployment + Terraform operations |
| [docs/requirements/](docs/requirements/) | Engineers | Feature requirement specs (one per domain) |

---

## Database Migrations

37 Flyway migrations (V1–V37) run automatically on startup:

| Version | Description |
|---------|-------------|
| V1–V3 | Core schema, tenant_id, cost/cache fields |
| V4–V7 | Auth schema, admin seed, tier config, password fix |
| V8–V9 | GitHub settings, conversation history |
| V10–V14 | AI provider keys, GCS, merge conflicts, key source mode |
| V15–V16 | Model metrics, prompt audit |
| V17–V20 | Auto-merge, integrations, Slack |
| V21–V22 | Tier permissions, activity audit |
| V23–V26 | Billing (plans, subscriptions, usage, top-up) |
| V27 | Apply suggestion |
| V28–V29 | Invoicing/GST, preview limits |
| V32 | Plan versions |
| V34 | Validation report |
| V37 | Test generation pipeline |

---

## Build Check

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q    # Java
npm --prefix frontend run build                           # Frontend
```
