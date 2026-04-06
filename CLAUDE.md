# Project: ExecuteSpec

> This file is read by Claude Code at session start. Keep it accurate.
> Last updated: 2026-04-06

## Architecture

- **Backend:** Java 21, Spring Boot 3.3, Spring AI 1.1.0
- **Frontend:** React 18, Vite 5, Tailwind CSS 3.4, TypeScript 5.3
- **Database:** PostgreSQL (Cloud SQL) for state, Redis for caching + gate coordination + feature flags + rate limiting
- **Infra:** GCP N. Virginia (us-east4), Cloud Run for previews (dev/prod), Docker Compose for local previews, GCE for main app
- **Multi-agent pipeline:** PipelineHarness → PlannerAgent → Plan Gate → SequentialTaskExecutor (parallel tiers, with SubtaskDecomposer for multi-file tasks) → CompilerAgent → Auto-merge all tasks → **COMPLETE** → Auto-preview (5-min TTL) → Background validation thread: ReviewerAgentV2 (inspect + auto-fix) + TestGenOrchestrator + TestPreviewCoordinator (reuses preview) + ValidationReport
- **Subtask decomposition:** Tasks with 2+ owned files are decomposed into per-file subtasks at execution time by `SubtaskDecomposer`. Each subtask produces exactly one `writeFile` call, eliminating fabrication. Subtasks tracked via `parentTaskId`/`subtaskIndex`/`subtaskTotal` on `AgentTask`.
- **Project type detection:** Planner declares `project_type` in plan JSON → written to `.agent-context/project.json` → read by preview/deploy Dockerfile generators (fallback: file-based heuristics)
- **LLM Router:** User-selected provider per project (Anthropic/Google/OpenAI). Default provider: OpenAI. YAML-configured model matrix maps `AgentTier x Provider → Model` via `ModelResolver`. Health-based failover via `ProviderHealthTracker` (Redis-backed circuit breaker, 5 failures → open, 60s TTL auto-recovery). Agent tiers: PLANNING, WORKHORSE, LIGHTWEIGHT.
- **Gate system:** Redis-backed plan approval gate with 500ms polling, 2h TTL, 24h max wait timeout. Merge is automatic (no manual gate).
- **Progressive merge:** Each task merged to main immediately after completion (validation deferred to review phase)
- **Auth:** JWT (access 1h, refresh 7d) + OAuth2 (Google, GitHub). Scoped SSE tokens (5-min TTL, `scope=sse` + `runId` claim) for event streams. RBAC with SUPER_ADMIN / ORG_ADMIN / BILLING_ADMIN / DEVELOPER / PROJECT_ADMIN / PROJECT_DEVELOPER roles. Force-logout via feature flag epoch-based JWT invalidation. JWT secret validated at startup in prod (`JwtSecretValidator`).
- **Tiers:** FREE (5 runs/month) → PRO (100/month) → ENTERPRISE (unlimited)
- **Billing:** Multi-gateway via `PaymentGatewayRouter` (Razorpay + Stripe). Subscriptions, run top-up credits, invoicing with GST, payment audit trail.
- **Security:** 8-layer defense-in-depth: request validation, input sanitization (`InputSanitizer` — NFKC normalization, zero-width strip, base64 decode+scan), LLM prompt defense, code abuse scanning (`CodeAbuseScanner` — crypto, reverse shell, PowerShell, DNS exfil, cloud metadata, obfuscation), shell sandbox, file sandbox, process isolation, container hardening (metadata endpoint blocking, env var whitelist). Configurable CSP/HSTS/X-Frame-Options via `SecurityProperties`.
- **Feature flags:** DB-backed `FeatureFlagService` with Redis pub/sub cache invalidation + 60s scheduled reload. Admin-manageable via `FeatureFlagController`.
- **Rate limiting:** Redis-backed per-tenant rate limiting via `RateLimitFilter`. Endpoint groups: AUTH, RUNS, WEBHOOK, READ, WRITE, PREVIEW, DEPLOY with per-tier limits.
- **Observability:** Micrometer + OTel → Prometheus + Tempo + Grafana (local) / Cloud Trace + Cloud Monitoring (GCP)

## Code Conventions

- **Base package:** `com.navneet.executespec`
- **Layers:** controller → service → agent → llm (via LlmRouter)
- **DTOs:** Java records where possible. Lombok `@Data`/`@Builder` for JPA entities.
- **Dependency injection:** Constructor injection via Lombok `@RequiredArgsConstructor` (no `@Autowired` field injection)
- **LLM prompts:** External files in `src/main/resources/prompts/*.txt` — never inline strings
- **Worker tool format:** XML `<tool_call>` format with manual dispatch and runId injection (NOT Spring AI native tool calling). Self-play prevention: fabricated `<tool_result>` blocks stripped before parsing. XML-first parsing for PATH B (Anthropic/Google), JSON fallback.
- **Completion detection:** Worker response must START with `{` (strict JSON check)
- **Naming:** `*Agent.java` for agents, `*Service.java` for services, `*Controller.java` for REST, `*Spec.java` for security specifications
- **DB migrations:** Flyway `V{n}__description.sql` in `src/main/resources/db/migration/` (currently V1–V58)

## Patterns: MUST Follow

- LLM calls go through `LlmRouter` — supports multi-provider fallback, per-run API key resolution, and tier-based model routing
- Agent outputs validated by `CodeValidatorImpl` during review phase (CompilerAgent + ReviewerAgentV2), not per-task
- Structured logging with traceId/spanId via Micrometer context propagation (MDC auto-populated)
- Constructor injection only via `@RequiredArgsConstructor`
- Run ownership fields (`createdByUserId`, `orgId`, `projectId`) stamped BEFORE async pipeline thread starts (race condition fix)
- Gate-loss fallback: `approve()` → if gate missing → auto-fail with message
- Sliding window for LLM context: first 2 + last 18 messages
- Token cost control: `readFile` 8k chars, `shell` 6k chars, `diff` 5k chars truncation limits
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21` must be set before any Maven command (shell sessions may not inherit it)
- Payment webhook signatures verified before processing (Razorpay HMAC, Stripe `Stripe-Signature`)
- Feature flags checked via `FeatureFlagService.isEnabled()` — never hardcode toggles

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
OPENAI_API_KEY=          # OpenAI API key (default provider)
ANTHROPIC_API_KEY=       # Claude API key

# Optional — LLM
GOOGLE_API_KEY=          # Gemini API key (for LLM fallback chain)

# Optional — Integrations
GITHUB_PAT=              # GitHub Personal Access Token
SLACK_CLIENT_ID=         # Slack app credentials
SLACK_CLIENT_SECRET=
SLACK_SIGNING_SECRET=
DASHBOARD_URL=           # Frontend URL for Slack links

# Optional — OAuth2
GOOGLE_OAUTH_CLIENT_ID=      # Google OAuth2 login
GOOGLE_OAUTH_CLIENT_SECRET=
GITHUB_OAUTH_CLIENT_ID=      # GitHub OAuth2 login
GITHUB_OAUTH_CLIENT_SECRET=

# Optional — Billing
RAZORPAY_KEY_ID=         # Razorpay payment gateway
RAZORPAY_KEY_SECRET=
STRIPE_SECRET_KEY=       # Stripe payment gateway
STRIPE_WEBHOOK_SECRET=

# Optional — Infrastructure
REDIS_HOST=              # Default: localhost

# Optional — Observability
OTEL_SAMPLING_PROBABILITY=1.0    # Trace sampling (1.0 = 100%)
OTEL_METRICS_ENABLED=false       # OTLP metrics export
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318/v1/traces
```

## API Contracts

### Run Lifecycle
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/runs` | Submit run (202) |
| GET | `/api/runs` | List runs (paginated, filtered by visibility + tier) |
| GET | `/api/runs/{id}` | Run detail (includes `githubPrUrl`) |
| POST | `/api/runs/{id}/approve` | Plan gate approval |
| POST | `/api/runs/{id}/merge` | Merge gate (legacy — merge is now automatic) |
| DELETE | `/api/runs/{id}` | Cancel/delete run |
| POST | `/api/runs/{id}/archive` | Archive run |
| POST | `/api/runs/{id}/tasks/{taskId}/retry` | Retry failed task |
| GET | `/api/runs/{id}/events` | SSE event stream (scoped token) |
| GET | `/api/runs/{id}/sse-token` | Get scoped SSE token (5-min TTL) |

### Validation Report
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/runs/{id}/report` | Get validation report |
| GET | `/api/runs/{id}/report/pdf` | Download report as PDF |
| POST | `/api/runs/{id}/report/share` | Generate share link |
| GET | `/api/reports/shared/{token}` | Public shared report (no auth) |

### Preview & Deploy
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/runs/{id}/preview/start` | Start preview (Cloud Run or Docker) |
| DELETE | `/api/runs/{id}/preview/stop` | Stop preview |
| POST | `/api/runs/{id}/deploy` | Deploy to Cloud Run (permanent) |
| POST | `/api/runs/{id}/push-github` | Push to GitHub (creates branch + PR) |

### Auth & Org
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/auth/register` | Self-register |
| POST | `/api/auth/login` | Login → JWT |
| POST | `/api/auth/refresh` | Refresh JWT token |
| DELETE | `/api/auth/logout` | Logout (clears refresh cookie) |
| POST | `/api/auth/forgot-password` | Request password reset email |
| POST | `/api/auth/reset-password` | Reset password with token |
| GET | `/api/auth/verify-email` | Verify email link |
| GET | `/api/auth/me/permissions` | Tier-based permissions |
| GET | `/api/auth/oauth2/authorize` | OAuth2 authorization redirect |
| GET | `/api/auth/oauth2/callback/{provider}` | OAuth2 callback (Google/GitHub) |
| POST | `/api/org` | Create org |
| POST | `/api/org/invite` | Invite user |

### Billing & Payments
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/billing/plans` | List billing plans |
| POST | `/api/billing/subscribe` | Initiate subscription (routed to Razorpay or Stripe) |
| POST | `/api/billing/topup/purchase` | Buy run top-up credits |
| GET | `/api/billing/payment-history` | Org-scoped payment history |
| POST | `/api/webhooks/razorpay` | Razorpay webhook (HMAC verified, no auth) |
| POST | `/api/webhooks/stripe` | Stripe webhook (Stripe-Signature verified, no auth) |

### Models & Feature Flags
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/models/available` | Model matrix + provider tags |
| GET | `/api/feature-flags` | List all feature flags (SUPER_ADMIN) |
| POST | `/api/feature-flags/{key}` | Update feature flag (SUPER_ADMIN) |

### Admin
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/admin/payment-audit` | Payment audit log (paginated, SUPER_ADMIN) |
| GET | `/api/admin/payment-audit/export` | Export audit as CSV (SUPER_ADMIN) |
| GET | `/api/admin/webhook-events` | Webhook event log (filterable, SUPER_ADMIN) |

## Key File Locations

| Purpose | Path |
|---------|------|
| Pipeline orchestrator | `harness/PipelineHarness.java` |
| Sequential task executor | `harness/SequentialTaskExecutor.java` |
| Subtask decomposer | `harness/SubtaskDecomposer.java` (multi-file → per-file subtask splitting) |
| Run cost budget | `harness/RunCostBudgetService.java` |
| Design system config | `harness/DesignSystemProperties.java` |
| UI style resolver | `harness/UiStyleResolver.java` (design loading, fuzzy match, 3-signal resolution) |
| Worker agent | `agent/WorkerAgent.java` |
| LLM router (multi-provider) | `agent/LlmRouter.java` |
| Model resolver (matrix-based) | `router/ModelResolver.java` |
| Model matrix config | `router/ModelMatrixProperties.java` |
| Provider circuit breaker | `router/ProviderHealthTracker.java` (Redis-backed) |
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
| Dockerfile gen (preview) | `deployment/preview/DevDockerfileGenerator.java` (reads `.agent-context/project.json`) |
| Dockerfile gen (deploy) | `deployment/DockerfileGenerator.java` (strategy pattern, reads `project.json`) |
| Security config | `auth/security/SecurityConfig.java` |
| JWT secret validator | `auth/security/JwtSecretValidator.java` (prod-only startup check) |
| Security properties | `config/SecurityProperties.java` (CSP, HSTS, X-Frame-Options) |
| JWT utility | `auth/security/JwtUtil.java` (includes scoped SSE tokens) |
| OAuth2 handler | `auth/oauth/OAuthSuccessHandler.java` |
| Tier permissions | `auth/service/TierPermissionService.java` |
| Tier enforcement | `auth/service/TierEnforcementService.java` |
| Billing service | `billing/service/BillingService.java` |
| Payment gateway router | `billing/gateway/PaymentGatewayRouter.java` (Razorpay + Stripe) |
| Stripe gateway | `billing/gateway/StripePaymentGateway.java` |
| Invoice service | `billing/service/InvoiceService.java` |
| Payment audit | `billing/service/PaymentAuditService.java` |
| Analytics service | `billing/service/AnalyticsService.java` |
| Feature flags | `config/featureflag/FeatureFlagService.java`, `config/featureflag/FeatureFlagController.java` |
| Rate limiting | `ratelimit/RateLimitFilter.java` |
| Input sanitizer | `security/InputSanitizer.java` |
| Code abuse scanner | `security/CodeAbuseScanner.java` |
| Request triage | `agent/RequestTriageService.java` |
| Slack integration | `integration/slack/SlackNotificationService.java` |
| GitHub repo service | `integration/github/GitHubRepoService.java` (branch, PR creation, default branch lookup) |
| Git tool | `tool/GitTool.java` (workspace git ops, `pushToBranch()` for existing repos) |
| JIRA adapter | `integration/jira/JiraAdapter.java` (inbound webhook + outbound comments) |
| Confluence adapter | `integration/confluence/ConfluenceAdapter.java` (inbound webhook + outbound child pages) |
| Integration manager | `integration/IntegrationManager.java` (routes webhooks, listens for MergeCompleted) |
| Test generation orchestrator | `testgen/TestGenOrchestrator.java` |
| Test preview coordinator | `testgen/TestPreviewCoordinator.java` (owns preview lifecycle for test execution) |
| Test execution service | `testgen/TestExecutionService.java` |
| Test result controller | `testgen/TestResultController.java` (apply-test-fixes) |
| DB migrations | `resources/db/migration/V*.sql` (V1–V58) |
| LLM prompts | `resources/prompts/*.txt` (15 main + 13 eval) |
| App config | `resources/application.yml` |
| Frontend entry | `frontend/src/main.tsx` |
| Frontend API client | `frontend/src/lib/api.ts` |
| Observability stack | `observability/docker-compose.yml` |
| OTel config | `config/ObservabilityConfig.java`, `config/CustomMetricsConfig.java` |
| Eval framework | `eval/EvalController.java`, `eval/ModelEvaluator.java` |
| Infra (Terraform) | `infra/` |

## Model Matrix

| Agent Tier | Agents | Anthropic | Google | OpenAI |
|------------|--------|-----------|--------|--------|
| PLANNING | PlannerAgent, ReviewerAgentV2 | `claude-sonnet-4-6` | `gemini-2.5-flash` | `gpt-5.4` |
| WORKHORSE | WorkerAgent, CompilerAgent, TestPlannerAgent, TestWorkerAgent, ApiTestSuiteAgent, ConflictResolverAgent, PromptOptimiserAgent, ValidationReportGenerator | `claude-haiku-4-5-20251001` | `gemini-2.5-flash` | `gpt-5.4-mini` |
| LIGHTWEIGHT | ConversationAgent, ContextAgent, TestReportGenerator, RequestTriageService | `claude-haiku-4-5-20251001` | `gemini-2.5-flash` | `gpt-5.4-mini` |

Default provider: OpenAI (user-selected per project). Failover order: Anthropic→[OpenAI, Google], Google→[OpenAI, Anthropic], OpenAI→[Google, Anthropic]. Stall escalation promotes WorkerAgent from WORKHORSE → PLANNING tier. See `application.yml` → `model-matrix:` for full configuration.

## Current State

### Working
- Full pipeline: plan → approve → work (tiered parallel, progressive merge) → compile → auto-merge all → **COMPLETE** → auto-preview (5-min TTL) → background validation (review + test gen + test exec + report)
- User-selected AI provider per project (Anthropic/Google/OpenAI) with YAML model matrix and health-based failover
- 25-minute wall-clock worker time limit with stall detection and model escalation
- Dual-mode previews: Cloud Run (dev/prod, 5-min TTL, managed HTTPS) or Docker Compose (local, 5-min TTL, Caddy proxy with Cloudflare Origin Certificate)
- JWT auth + OAuth2 (Google, GitHub) with RBAC, org management, project scoping
- Multi-gateway billing via PaymentGatewayRouter (Razorpay + Stripe): subscriptions, run top-up credits, invoicing with GST
- Payment audit trail with CSV export + webhook event log (admin)
- Feature flag system (DB + Redis pub/sub + admin UI) for platform toggles
- Force-logout via feature flag epoch-based JWT invalidation
- Redis-backed per-tenant rate limiting (AUTH, RUNS, WEBHOOK, READ, WRITE, PREVIEW, DEPLOY groups)
- 8-layer security: request validation, input sanitization (NFKC + zero-width strip + base64 decode), LLM prompt defense, code abuse scanning (crypto, shells, PowerShell, DNS exfil, metadata), shell sandbox, file sandbox, process isolation, container hardening (metadata blocking, env whitelist)
- Configurable security headers (CSP, HSTS, X-Frame-Options) via SecurityProperties
- JIRA/Confluence inbound webhooks + outbound completion comments (via MergeCompleted event, both PROTOTYPE and PRODUCTION)
- Slack notifications (Block Kit messages)
- GitHub branch + PR creation for existing-repo runs (branch `executespec/{runId-short}`, PR opened automatically)
- Chat copilot + apply suggestion (chat/review improvements on same run)
- Run archive feature
- Request triage (single-shot fast path for simple requests)
- Observability: Micrometer traces + custom metrics (30+ meters) + structured logging
- Local Grafana dashboards (ExecuteSpec Overview + JVM/HTTP)
- GCP Cloud Trace (traces) + Cloud Monitoring (metrics) via `@Profile("gcp")` exporters
- Model evaluation framework (`eval/` package)
- Atomic quota enforcement (pessimistic locking, single-TX check+increment)
- Atomic token counter updates (SQL-level increment, no read-modify-write)
- Bounded thread pool for pipeline runs (CallerRunsPolicy backpressure)
- Gate wait timeout (24h max, prevents indefinite thread block)
- Semaphore enforcement on all run entry points (startIterationRun, resumeRun, executeApplySuggestion)
- Concurrent preview limit race fix (synchronized block)
- Scoped SSE tokens (5-min TTL, per-runId, prevents cross-run eavesdropping)
- JWT secret startup validation (prod profile, blocks dev placeholder)
- Tier-based deployment limits (enforces `max_deployments` from tier_config)
- Mid-task cost enforcement (checks budget every 5 LLM calls in worker loop)
- Caffeine TTL caches replacing unbounded ConcurrentHashMaps (LlmRouter, RunCostBudgetService)
- Containerized test execution (Docker with --read-only, --cap-drop ALL, memory/CPU limits)
- Env var whitelist for Docker subprocess (PATH, HOME, DOCKER_HOST only — no leaked secrets)
- ServiceNow webhook HMAC verification
- Actuator endpoint restriction (health+prometheus public, rest SUPER_ADMIN)
- Design system integration: 54 company DESIGN.md files loaded from filesystem, 3-signal resolution (explicit hint > planner ui_style > project type heuristic), Caffeine cache (30min), token-budget truncation (2500 tokens), GCS-synced on deploy/boot
- CSP `frame-src` directive for Cloud Run preview iframes
- Subtask decomposition: multi-file tasks split into per-file subtasks (SubtaskDecomposer), tracked via parentTaskId/subtaskIndex/subtaskTotal
- Self-play prevention: WorkerAgent strips fabricated `<tool_result>` blocks from LLM responses, uses XML-first parsing for PATH B providers (Anthropic/Google)
- Task graph subtask grouping: frontend TaskGraph renders subtasks as smaller nodes grouped under parent with expand/collapse, progress bar, aggregate status

### In Progress
- Production deployment preparation

### Known Tech Debt
- **Model matrix cost:** WORKHORSE tier for Anthropic is `claude-haiku-4-5-20251001` — consider whether Sonnet should be used for complex tasks
- V3 migration type mismatch: `actual_cost_usd` must be `DOUBLE PRECISION` (not `DECIMAL(10,6)`)
- Branch retry: `GitTool.createBranch()` catches RefAlreadyExistsException → checkout fallback
- Gate lost on restart: approve() → if gate missing → auto-fail with message
- StartupRunResumer disabled — users resume stuck runs manually via UI
- Apply-to-run: `plan_version` duplicate key error on retry (incrementPlanVersion may not be working correctly)
- `@Lazy @Autowired` on `PreviewService` in `TestPreviewCoordinator` — only field injection in codebase, needed to break circular dependency
- Default provider is OpenAI but eval results show Anthropic (Haiku) and Google (Gemini) perform better — consider changing default
