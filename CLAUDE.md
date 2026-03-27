# Project: ExecuteSpec

> This file is read by Claude Code at session start. Keep it accurate.
> Last updated: 2026-03-27

## Architecture

- **Backend:** Java 21, Spring Boot 3.3, Spring AI 1.1.0
- **Frontend:** React 18, Vite 5, Tailwind CSS 3.4, TypeScript 5.3
- **Database:** PostgreSQL (Cloud SQL) for state, Redis for caching + gate coordination
- **Infra:** GCP Mumbai (asia-south1), Cloud Run for previews (dev/prod), Docker Compose for local previews, GCE for main app
- **Multi-agent pipeline:** PipelineHarness → PlannerAgent (Sonnet) → Plan Gate → SequentialTaskExecutor (Haiku per task) → CompilerAgent → Auto-merge all tasks → ReviewerAgentV2 (inspect + auto-fix) → ValidationReport → COMPLETE
- **LLM Router:** 3-provider fallback chains (Anthropic → Google → OpenAI) with weighted round-robin (4:3:3) and tier-based model escalation (FAST → CAPABLE → POWER)
- **Gate system:** Redis-backed plan approval gate with 500ms polling, 2h TTL. Merge is automatic (no manual gate).
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
- **DB migrations:** Flyway `V{n}__description.sql` in `src/main/resources/db/migration/` (currently V1–V39)

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
| POST | `/api/runs/{id}/merge` | Merge gate (legacy — merge is now automatic) |
| DELETE | `/api/runs/{id}` | Cancel/delete run |
| POST | `/api/runs/{id}/tasks/{taskId}/retry` | Retry failed task |
| GET | `/api/runs/{id}/events` | SSE event stream |

### Validation Report
| GET | `/api/runs/{id}/report` | Get validation report |
| GET | `/api/runs/{id}/report/pdf` | Download report as PDF |
| POST | `/api/runs/{id}/report/share` | Generate share link |
| GET | `/api/reports/shared/{token}` | Public shared report (no auth) |

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
| Validation report | `report/ValidationReportService.java`, `report/ValidationReportGenerator.java` |
| Report controller | `report/ValidationReportController.java` |
| Report PDF | `report/ValidationReportPdfService.java` |
| Report entity | `report/entity/ValidationReport.java` |
| Report frontend | `frontend/src/components/runs/ValidationReportPanel.tsx` |
| Shared report page | `frontend/src/pages/SharedReportPage.tsx` |
| Preview service | `deployment/preview/PreviewService.java` (dual-mode: docker/cloud-run) |
| Preview config | `deployment/preview/PreviewProperties.java` (mode, TTL, concurrency) |
| Caddy proxy manager | `deployment/preview/CaddyProxyManager.java` (Docker mode only) |
| Cloud Run client | `deployment/CloudRunClient.java` |
| Artifact Registry | `deployment/ArtifactRegistryClient.java` |
| Deployment service | `deployment/DeploymentService.java` |
| Tier permissions | `auth/service/TierPermissionService.java` |
| Billing service | `billing/service/BillingService.java` |
| Slack integration | `integration/slack/SlackNotificationService.java` |
| DB migrations | `resources/db/migration/V*.sql` (V1–V39) |
| LLM prompts | `resources/prompts/*.txt` |
| App config | `resources/application.yml` |
| Frontend entry | `frontend/src/main.tsx` |
| Frontend API client | `frontend/src/lib/api.ts` |
| Observability stack | `observability/docker-compose.yml` |
| OTel config | `config/ObservabilityConfig.java`, `config/CustomMetricsConfig.java` |
| Routing config | `config/RoutingProperties.java` (provider weights 4:3:3) |
| Test generation | `testgen/TestResultController.java` (apply-test-fixes) |
| Infra (Terraform) | `infra/` |

## Model Matrix

| Role | Agent(s) | Model |
|------|----------|-------|
| Planner | PlannerAgent | `claude-sonnet-4-6` |
| Worker | WorkerAgent, CompilerAgent, ApiTestSuiteAgent | `claude-haiku-4-5-20251001` |
| Reviewer | ReviewerAgentV2 | `claude-haiku-4-5-20251001` |
| Report | ValidationReportGenerator | `claude-haiku-4-5-20251001` |
| Scout | ContextAgent, PromptOptimiserAgent | `claude-haiku-4-5-20251001` |
| Chat | ConversationAgent | `claude-haiku-4-5-20251001` |

## Current State

### Working
- Full pipeline: plan → approve → work (sequential, progressive merge) → compile → auto-merge all → review (inspect + auto-fix) → **validation report** → COMPLETE
- Multi-provider LLM routing with automatic fallback (Anthropic → Google → OpenAI)
- 15-minute wall-clock worker time limit with stall detection and model escalation
- Dual-mode previews: Cloud Run (dev/prod, 5-min TTL, managed HTTPS) or Docker Compose (local, 10-min TTL, Caddy proxy)
- Caddy reverse proxy with Cloudflare Origin Certificate for local/VM preview routing
- JWT auth with RBAC, org management, project scoping
- Razorpay billing (subscriptions + run top-up credits)
- JIRA/Confluence context extraction and enrichment
- Slack notifications (Block Kit messages)
- Chat copilot + apply suggestion (chat/review improvements on same run)
- Observability: Micrometer traces + custom metrics (30+ meters) + structured logging
- Local Grafana dashboards (ExecuteSpec Overview + JVM/HTTP)
- GCP Cloud Trace (traces) + Cloud Monitoring (metrics) via `@Profile("gcp")` exporters
- Metrics wired into services: PipelineHarness, SequentialTaskExecutor, LlmRouter, PreviewService, DeploymentService, StuckRunMonitor, CodeValidatorImpl

### Recent Changes (2026-03-27)
- Preview mode: dual-mode support — `cloud-run` for dev/prod (5-min TTL, Artifact Registry + Cloud Run), `docker` for local (10-min TTL, Docker Compose + Caddy)
- Caddy TLS: switched from broken internal CA to Cloudflare Origin Certificate
- Apply-to-run: added validation report generation step (was missing from `executeApplySuggestion()`)
- Pagination: run list API now returns `PagedResponse<RunSummary>` with page/size/totalElements
- Startup script: Caddy JSON config with dynamic route management via admin API
- **Reliability fixes (deploy/preview):**
  - All `gcloud`/`docker` subprocess commands have timeouts (deploy: 10min, delete: 5min, list: 2min)
  - `deploy()` method fixed for pipe buffer deadlock (stdout/stderr read in parallel)
  - ACTIVE_PREVIEWS gauge correctly decremented on ALL terminal states (FAILED, TIMED_OUT, STOPPED)
  - `fail()` now cleans up Cloud Run resources (service + image) on preview failure
  - `getLogs()` works for Cloud Run (fetches via `gcloud logging read`)
  - `probeApiDocs()` works for both Docker (localhost:port) and Cloud Run (service URL)
  - Virtual threads in PreviewService and DeploymentService wrapped with `ContextSnapshotFactory` for trace propagation
  - `healthCheck()` HTTP client has connect timeout (10s) and per-request timeout (15s)
  - `pushImage()` in ArtifactRegistryClient has 10-min timeout

### In Progress
- Production deployment preparation

### Known Tech Debt
- V3 migration type mismatch: `actual_cost_usd` must be `DOUBLE PRECISION` (not `DECIMAL(10,6)`)
- Branch retry: `GitTool.createBranch()` catches RefAlreadyExistsException → checkout fallback
- Gate lost on restart: approve() → if gate missing → auto-fail with message
- StartupRunResumer disabled — users resume stuck runs manually via UI
- ReviewerAgent V1 class still wired as @Service bean but unused in pipeline (blocks deletion of reviewer.txt prompt)
- Apply-to-run: `plan_version` duplicate key error on retry (incrementPlanVersion may not be working correctly)
