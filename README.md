# ExecuteSpec

**v1.2.0** · Multi-agent autonomous coding system. Decomposes natural-language goals into parallel coding tasks, executes them with LLM worker agents, validates, reviews, and deploys the result.

**Stack:** Java 21 · Spring Boot 3.3 · Spring AI 1.1.0 · PostgreSQL · Redis · React 18 · Vite 5 · Tailwind · GCP

---

## Quick Start

```bash
# Prerequisites: Java 21, PostgreSQL (localhost:5432/executespec), Redis, Node.js 18+

export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export OPENAI_API_KEY=sk-...        # Default provider
export ANTHROPIC_API_KEY=sk-ant-... # Fallback

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
POST /api/runs  →  RequestTriageService (single-shot fast path?)
                      ↓ (if complex)
                ReferenceExtractor (JIRA/Confluence context)
                      ↓
                Clarification Gate  (if ambiguous references detected)
                      ↓
                PlannerAgent (PLANNING tier)
                   → outputs project_type → .agent-context/project.json
                      ↓
                Plan Approval Gate  (or auto-bypass with skipApproval)
                      ↓
                SequentialTaskExecutor (tiered parallel groups)
                   → SubtaskDecomposer (splits multi-file tasks into per-file subtasks)
                   → WorkerAgent per task (WORKHORSE tier, 25-min limit, self-play prevention)
                   → Retry with model escalation on stall (WORKHORSE → PLANNING)
                   → Progressive merge to main after each task
                      ↓
                CompilerAgent (full project build, 3 rounds)
                      ↓
                Auto-merge all task branches
                      ↓
                ★ COMPLETE  →  Auto-preview (5-min TTL)
                      ↓ (background virtual thread)
                ReviewerAgentV2 (inspect merged code + auto-fix)
                      ↓
                TestGenOrchestrator (plan → generate → compile → execute)
                      ↓
                ValidationReport + TestReport
                      ↓
                MergeCompleted event → JIRA/Confluence/Slack outbound
                      ↓
                GitHub branch + PR (if existing repo configured)
```

---

## Key Features

- **Tiered parallel task execution** with progressive merge, model escalation (WORKHORSE → PLANNING on stall), and subtask decomposition (multi-file → per-file)
- **Self-play prevention** — strips fabricated `<tool_result>` blocks from LLM responses, XML-first parsing for PATH B providers
- **Plan approval gate** — human reviews task breakdown (auto-bypass via `skipApproval`)
- **Automatic merge** — no manual merge gate; pipeline auto-merges after compile
- **Background validation** — review, test gen, test exec, and report run in background after COMPLETE (preview available immediately)
- **Code validation** — language toolchains for Java, TypeScript, Python, React (syntax → type → lint → test)
- **ReviewerAgentV2** — inspects merged code, auto-fixes CRITICAL/MINOR issues (2 attempts per issue, max 8)
- **Test generation** — language-agnostic pipeline: plan → generate → compile → execute against live preview
- **Validation report** — LLM-generated quality summary, shareable via public URL, downloadable as PDF
- **Model routing** — 3-provider fallback (Anthropic/Google/OpenAI) with Redis-backed circuit breaker (5 failures → open, 60s recovery)
- **Auth** — JWT (24h/7d) + OAuth2 (Google, GitHub), RBAC, orgs, projects, tiers (FREE/PRO/ENTERPRISE)
- **Billing** — Multi-gateway (Razorpay + Stripe) via `PaymentGatewayRouter`, subscriptions + run top-up credits + invoicing with GST
- **Payment audit** — Full audit trail with CSV export, webhook event log (admin)
- **Feature flags** — DB-backed with Redis pub/sub cache invalidation, admin-manageable
- **Rate limiting** — Redis-backed per-tenant sliding window
- **JIRA/Confluence integration** — inbound webhooks create runs, outbound comments on MergeCompleted
- **Slack notifications** — org-level OAuth, per-project channel routing, Block Kit messages
- **Dual-mode previews** — Cloud Run (dev/prod, 5-min TTL, managed HTTPS) or Docker Compose (local, 5-min TTL, Caddy)
- **GitHub integration** — create repos, push code, create branches + auto-open PRs for existing repos
- **Post-run AI chat** — conversation panel with workspace context injection
- **Run archive** — archive completed runs
- **Request triage** — single-shot fast path for trivial requests
- **Design system integration** — 54 company design systems (Stripe, Linear, Notion, etc.) loaded from filesystem-backed DESIGN.md files with 3-signal priority resolution (explicit hint > planner > heuristic), Caffeine caching, and token-budget truncation
- **GCP deployment** — GCE MIG + Cloud SQL + Redis + GCS + Load Balancer + Cloud Run

---

## Security

8-layer defense-in-depth model for running untrusted user input through LLM agents:

1. **Request validation** — Jakarta Bean Validation (`@Size`, `@Pattern`, `@NotBlank`) rejects malformed input
2. **Content scanning** — `InputSanitizer` detects prompt injection, shell embedding, abuse patterns (26 regexes + Unicode NFKC normalization + obfuscation detection)
3. **LLM prompt defense** — user input tagged as untrusted in planner/worker prompts; tool results wrapped with `untrusted="true"` to prevent second-order injection from repo files
4. **Code abuse scanning** — `CodeAbuseScanner` **blocks** writes of files matching malware patterns (crypto miners, reverse shells, data exfiltration, obfuscated payloads)
5. **Shell sandbox** — 3-layer validation: subshell blocking + blocklist (23) + pattern blocklist (43) + chained-command splitting + allowlist (32 prefixes)
6. **File sandbox** — path normalization + workspace boundary enforcement (`safeResolve()`)
7. **Process isolation** — secret env var stripping + 5-minute hard timeout + rate limiting (Redis per-tenant)
8. **Container hardening** — `.dockerignore` excludes `.env*`, `credentials.json`, `secrets.*` from preview/deploy images

Configurable security headers (CSP, HSTS, X-Frame-Options) via `SecurityProperties`.

Full details: [docs/requirements/security-guardrails.md](docs/requirements/security-guardrails.md)

---

## Documentation

| Document | Audience | Description |
|----------|----------|-------------|
| [CLAUDE.md](CLAUDE.md) | AI assistant | Session instructions, conventions, patterns |
| [CODEBASE_MAP.md](CODEBASE_MAP.md) | Engineers | Every class → one-line purpose |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Engineers | System design, Mermaid diagrams, design decisions |
| [docs/JAVADOC.md](docs/JAVADOC.md) | Engineers | Detailed code documentation per package and class |
| [docs/PRD.md](docs/PRD.md) | Product/Business | Product requirements, tiers, user journeys |
| [docs/WORKFLOWS.md](docs/WORKFLOWS.md) | Engineers/Product | Mermaid workflow diagrams for every feature |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | DevOps/Engineers | Local setup + GCP deployment + Terraform operations |
| [docs/CODE_GEN_ARCHITECTURE.md](docs/CODE_GEN_ARCHITECTURE.md) | Engineers | Multi-agent pipeline deep dive (18 sections) |
| [docs/frontend-api-usage.md](docs/frontend-api-usage.md) | Frontend Engineers | Frontend API client audit |
| [docs/run-workflow-sequence.md](docs/run-workflow-sequence.md) | Engineers | Run lifecycle state machine |
| [docs/requirements/](docs/requirements/) | Engineers | Feature requirement specs (10 domain files) |

---

## Database Migrations

58 Flyway migrations (V1–V58) run automatically on startup:

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
| V27–V29 | Apply suggestion, invoicing/GST, preview limits |
| V32–V34 | Plan versions, validation report |
| V35–V37 | Preview tracking, run title/summary, test generation |
| V38–V44 | Calibration, report flags, project AI provider, UI style |
| V45 | Widen plan_version columns |
| V46 | Stripe + payment gateway support |
| V47 | OAuth support (Google, GitHub login) |
| V48–V50 | View mode, feature flags, free tier restrictions |
| V51 | Force logout flag |
| V52 | GitHub PR URL |
| V53 | Archive run |
| V54 | Stripe multi-currency |
| V55 | Payment audit expansion |
| V56 | Task completion notes |
| V58 | Subtask support (parentTaskId, subtaskIndex, subtaskTotal) |

---

## Model Matrix

| Agent Tier | Agents | Anthropic | Google | OpenAI |
|------------|--------|-----------|--------|--------|
| PLANNING | PlannerAgent, ReviewerAgentV2 | `claude-sonnet-4-6` | `gemini-2.5-flash` | `gpt-5.4` |
| WORKHORSE | WorkerAgent, CompilerAgent, TestPlannerAgent, TestWorkerAgent + 4 more | `claude-haiku-4-5-20251001` | `gemini-2.5-flash` | `gpt-5.4-mini` |
| LIGHTWEIGHT | ConversationAgent, ContextAgent, TestReportGenerator, RequestTriageService | `claude-haiku-4-5-20251001` | `gemini-2.5-flash` | `gpt-5.4-mini` |

Default provider: **OpenAI** (user-selected per project). Health-based failover via Redis circuit breaker.

---

## Build Check

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q    # Java
npm --prefix frontend run build                           # Frontend
```
