# Project: ExecuteSpec

> This file is read by Claude Code at session start. Keep it accurate.
> Last updated: 2026-03-18

## Architecture

- **Backend:** Java 21, Spring Boot 3.3, Spring AI 1.1.0
- **Frontend:** React 18, Vite 5, Tailwind CSS 3.4, TypeScript 5.3
- **Database:** PostgreSQL (Cloud SQL) for state, Redis for caching + gate coordination
- **Infra:** GCP Mumbai (asia-south1), Cloud Run for previews, GCE for main app
- **Multi-agent pipeline:** Orchestrator (PipelineHarness) → PlannerAgent (Sonnet) → WorkerAgent per task (Haiku) → CompilerAgent → ReviewerAgentV2 → GitMergeService
- **LLM Router:** 3-provider fallback chains (Anthropic → Google → OpenAI) with tier-based model escalation (FAST → CAPABLE → POWER)
- **Gate system:** Redis-backed approval gates (plan + merge) with 500ms polling, 2h TTL
- **Progressive merge:** Each task merged to main immediately after validation
- **Auth:** JWT (access 24h, refresh 7d), RBAC with SUPER_ADMIN / ORG_ADMIN / BILLING_ADMIN / DEVELOPER / PROJECT_ADMIN / PROJECT_DEVELOPER roles
- **Tiers:** FREE (5 runs/month) → PRO (100/month) → ENTERPRISE (unlimited)
- **Billing:** Razorpay integration for subscriptions + run top-up credits
- **Observability:** Micrometer + OTel → Prometheus + Tempo + Grafana (local) / Cloud Trace + Cloud Monitoring (GCP)

## Code Conventions

- **Base package:** `com.navneet.executespec`
- **Layers:** controller → service → agent → llm (via LlmRouter)
- **DTOs:** Java records where possible. Lombok `@Data`/`@Builder` for JPA entities.
- **Dependency injection:** Constructor injection via Lombok `@RequiredArgsConstructor` (no `@Autowired` field injection)
- **LLM prompts:** External files in `src/main/resources/prompts/*.txt` — never inline strings
- **Worker tool format:** XML `<tool_call>` format with manual dispatch and runId injection (NOT Spring AI native tool calling)
- **Completion detection:** Worker response must START with `{` (strict JSON check)
- **Naming:** `*Agent.java` for agents, `*Service.java` for services, `*Controller.java` for REST, `*Spec.java` for security specifications
- **DB migrations:** Flyway `V{n}__description.sql` in `src/main/resources/db/migration/` (currently V1–V31)

## Patterns: MUST Follow

- LLM calls go through `LlmRouter` — supports multi-provider fallback, per-run API key resolution, and tier-based model routing
- Agent outputs validated by `CodeValidatorImpl` (pluggable gate chain: SYNTAX → TYPE → LINT → TEST) before merge
- Structured logging with traceId/spanId via Micrometer context propagation (MDC auto-populated)
- Constructor injection only via `@RequiredArgsConstructor`
- Run ownership fields (`createdByUserId`, `orgId`, `projectId`) stamped BEFORE async pipeline thread starts (race condition fix)
- Gate-loss fallback: `approve()` → if gate missing → auto-fail with message
- Sliding window for LLM context: first 2 + last 18 messages
- Token cost control: `readFile` 8k chars, `shell` 6k chars, `diff` 5k chars truncation limits
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21` must be set before any Maven command (shell sessions may not inherit it)

## Patterns: MUST NOT Use

- Field injection (`@Autowired` on fields)
- Raw String for LLM prompts (use prompt files in `resources/prompts/`)
- Direct HTTP calls to LLM providers (always go through `LlmRouter`)
- Swallowed exceptions (log + rethrow or handle explicitly)
- Setting run ownership fields AFTER `submitRun()` returns (causes async thread race condition)
- `Thread.ofVirtual().start()` without context wrapping (`ContextSnapshotFactory.captureAll().wrap(runnable)`)

## Build & Test

```bash
# IMPORTANT: Always set JAVA_HOME for Maven commands
export JAVA_HOME=/opt/homebrew/opt/openjdk@21

# Backend build
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q

# Frontend build
npm --prefix frontend run build

# Full verify (both)
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q && npm --prefix frontend run build

# Run locally
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run

# Local observability stack (Grafana + Prometheus + Tempo)
cd observability && docker compose up -d
# Grafana: http://localhost:3001 (admin/admin)
# Prometheus: http://localhost:9090
# Tempo: http://localhost:3200
```

## Required Environment Variables

```bash
# Required
ANTHROPIC_API_KEY=       # Claude API key
OPENAI_API_KEY=          # OpenAI API key

# Optional
GOOGLE_API_KEY=          # Gemini API key (for LLM fallback chain)
GITHUB_PAT=              # GitHub Personal Access Token
REDIS_HOST=              # Default: localhost
SLACK_CLIENT_ID=         # Slack app credentials
SLACK_CLIENT_SECRET=
SLACK_SIGNING_SECRET=
DASHBOARD_URL=           # Frontend URL for Slack links

# Observability
OTEL_SAMPLING_PROBABILITY=1.0    # Trace sampling (1.0 = 100%)
OTEL_METRICS_ENABLED=false       # OTLP metrics export
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318/v1/traces
```

## API Contracts

### Run Lifecycle
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/runs` | Submit run (202) |
| GET | `/api/runs` | List runs (filtered by visibility + tier) |
| GET | `/api/runs/{id}` | Run detail |
| POST | `/api/runs/{id}/approve` | Plan gate |
| POST | `/api/runs/{id}/merge` | Merge gate (MERGE/DECLINE/REWORK/IMPLEMENT_SUGGESTIONS) |
| DELETE | `/api/runs/{id}` | Cancel/delete run |
| POST | `/api/runs/{id}/tasks/{taskId}/retry` | Retry failed task |
| GET | `/api/runs/{id}/events` | SSE event stream |

### Preview & Deploy
| POST | `/api/runs/{id}/preview/start` | Start Cloud Run preview |
| DELETE | `/api/runs/{id}/preview/stop` | Stop preview |
| POST | `/api/runs/{id}/deploy` | Deploy to Cloud Run (permanent) |
| POST | `/api/runs/{id}/push-github` | Push to GitHub |

### Auth & Org
| POST | `/api/auth/register` | Self-register |
| POST | `/api/auth/login` | Login → JWT |
| GET | `/api/auth/me/permissions` | Tier-based permissions |
| POST | `/api/org` | Create org |
| POST | `/api/org/invite` | Invite user |

### Billing
| GET | `/api/billing/plans` | List billing plans |
| POST | `/api/billing/subscribe` | Initiate Razorpay subscription |
| POST | `/api/billing/topup/purchase` | Buy run top-up credits |

Full API reference: see memory file `api-endpoints.md`

## Key File Locations

| Purpose | Path |
|---------|------|
| Pipeline orchestrator | `harness/PipelineHarness.java` |
| Sequential task executor | `harness/SequentialTaskExecutor.java` |
| Worker agent | `agent/WorkerAgent.java` |
| LLM router (multi-provider) | `agent/LlmRouter.java` |
| Model router (tier escalation) | `router/ModelRouter.java` |
| State store | `state/StateStore.java` |
| Code validator | `validator/CodeValidatorImpl.java` |
| Compiler agent | `compiler/CompilerAgent.java` |
| Reviewer agent | `reviewer/ReviewerAgentV2.java` |
| Preview (Cloud Run) | `deployment/preview/PreviewService.java` |
| Deployment service | `deployment/DeploymentService.java` |
| Tier permissions | `auth/service/TierPermissionService.java` |
| Billing service | `billing/service/BillingService.java` |
| Slack integration | `integration/slack/SlackNotificationService.java` |
| DB migrations | `resources/db/migration/V*.sql` (V1–V31) |
| LLM prompts | `resources/prompts/*.txt` |
| App config | `resources/application.yml` |
| Frontend entry | `frontend/src/main.tsx` |
| Frontend API client | `frontend/src/lib/api.ts` |
| Observability stack | `observability/docker-compose.yml` |
| OTel config | `config/ObservabilityConfig.java`, `config/CustomMetricsConfig.java` |
| Infra (Terraform) | `infra/` |

## Model Matrix

| Role | Agent(s) | Model |
|------|----------|-------|
| Planner | PlannerAgent | `claude-sonnet-4-6` |
| Worker | WorkerAgent, CompilerAgent, ApiTestSuiteAgent | `claude-haiku-4-5-20251001` |
| Reviewer | ReviewerAgentV2 | `claude-haiku-4-5-20251001` |
| Scout | ContextAgent, PromptOptimiserAgent | `claude-haiku-4-5-20251001` |
| Chat | ConversationAgent | `claude-haiku-4-5-20251001` |

## Current State

### Working
- Full pipeline: plan → approve → work → compile → review → merge (with progressive merge)
- Multi-provider LLM routing with automatic fallback (Anthropic → Google → OpenAI)
- 15-minute wall-clock worker time limit with stall detection and model escalation
- Cloud Run previews with 10-min TTL and countdown timer
- JWT auth with RBAC, org management, project scoping
- Razorpay billing (subscriptions + run top-up credits)
- JIRA/Confluence context extraction and enrichment
- Slack notifications (Block Kit messages)
- Chat copilot + apply suggestion (chat/review improvements on same run)
- Observability: Micrometer traces + custom metrics (30+ meters) + structured logging
- Local Grafana dashboards (ExecuteSpec Overview + JVM/HTTP)

### In Progress
- GCP Cloud Monitoring metrics exporter (bean not yet wired)
- Deploy Engine v2 (Paths B & C: Docker Compose on VM, Terraform + Managed Services — 0-10% done)

### Known Tech Debt
- V3 migration type mismatch: `actual_cost_usd` must be `DOUBLE PRECISION` (not `DECIMAL(10,6)`)
- Branch retry: `GitTool.createBranch()` catches RefAlreadyExistsException → checkout fallback
- Gate lost on restart: approve() → if gate missing → auto-fail with message
- StartupRunResumer can race with GateFallback on orphaned merge gates (mitigated with activeResumeGuard)
