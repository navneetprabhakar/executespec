# Project: ExecuteSpec

> This file is read by Claude Code at session start. Keep it accurate.
> Last updated: 2026-04-12

## Architecture

- **Backend:** Java 21, Spring Boot 3.3, Spring AI 1.1.4
- **Harness V2 (in progress):** Sequential-first redesign on `feature/harness-v2`. New package tree `com.navneet.executespec.v2.*`. Native tool calling for all 3 providers (Anthropic, OpenAI, Gemini). See `docs/harness-v2-execution-plan.md`. **V2 naming convention: provider is `gemini` (V1 used `google`)**.
- **V2 OpenAI native tool calling:** Spring AI 1.1.4 has a regression that adds `extra_body` to OpenAI requests whenever `toolCallbacks` is set, regardless of any other option (verified across `gpt-4o`, `gpt-4o-mini`, `gpt-5.4`, `gpt-5.4-mini`). OpenAI's API rejects this with `Unrecognized request argument supplied: extra_body`. **V2 bypasses Spring AI for OpenAI tool calls** via `com.navneet.executespec.v2.agent.OpenAiNativeToolClient` — direct `RestClient` POST to `/v1/chat/completions`, parses response into Spring AI's `ChatResponse` shape so downstream V2 code stays provider-agnostic. Anthropic + Gemini work fine on Spring AI 1.1.4 — wrapper is OpenAI-only.
- **V2 tool layer (Phase 1):** Unified `Tool` contract in `com.navneet.executespec.v2.tool` (Tool / ToolResult sealed Ok|Err with ErrorKind taxonomy / ExecutionContext). 10 tools registered: `readFile` (with line-range), `writeFile` (NEW files only — errors if exists), `editFile` (multi-edit array), `listFiles` (recursive + glob), `searchFiles` (native, no shell subprocess), `shell` (reuses V1 ShellSandboxPolicy), `gitStatus`, `gitDiff`, `gitCommit`, `buildProject` (uses `BuildCommandResolver` — planner-declared > framework-inferred > no-op fallback). `ToolRegistryV2.bind(ExecutionContext)` returns Spring AI `ToolCallback`s with the context captured. No FileLockTool (V2 sequential).
- **V2 LLM router (Phase 2/3 baseline):** `com.navneet.executespec.v2.agent.LlmRouterV2` — provider-agnostic `call(LlmRequestV2)` that routes to native tool calling for all 3 providers and binds tools via `ToolRegistryV2.bind(ctx)` automatically. Dispatch: anthropic → Spring AI native, **openai → `OpenAiNativeToolClient`** (bypasses Spring AI 1.1.4's `extra_body` bug), **gemini → `GeminiNativeToolClient`** (bypasses Spring AI 1.1.4's `Optional.get()` bug in multi-turn response parsing + handles Gemini 2.5+ thought-signature round-trip). `internalToolExecutionEnabled(false)` everywhere — harness keeps loop control. Multi-provider failover, ProviderHealthTracker circuit breaker, per-run API key resolution, tier-based model routing deferred to Phase 2.x / Phase 9 canary.
- **V2 Gemini native tool calling:** `com.navneet.executespec.v2.agent.GeminiNativeToolClient` — direct REST POST to `/v1beta/models/{model}:generateContent`. Handles three Gemini-specific quirks: (1) schema sanitization — strips `additionalProperties` recursively (Gemini's schema validator rejects it); (2) `toolConfig.functionCallingConfig.mode=AUTO` to constrain structured output; (3) **thoughtSignature round-trip** via Caffeine cache keyed by synthetic tool-call ID (10k entries, 30-min TTL) — Gemini 2.5+ thinking models require each `functionCall` part to carry the `thoughtSignature` originally emitted with it, otherwise the API returns 400 "missing thought_signature" (3-preview) or silently degrades to Python-code emission → MALFORMED_FUNCTION_CALL (2.5-flash). Without the round-trip, multi-turn Gemini is broken on 2.5+. Spring AI's `AssistantMessage.ToolCall` record has no field for the signature, hence the out-of-band cache.
- **V2 worker agent (Phase 3 baseline):** `com.navneet.executespec.v2.agent.WorkerAgentV2.run(WorkerTaskRequest) → WorkerResult` — sequential single-agent loop. For each iteration: call router → collect tool calls across all generations → if `completeTask` invoked, terminate with `COMPLETED` → else dispatch each tool through registry, append `ToolResponseMessage` to history, loop. Cap: 40 iterations default. Outcomes: `COMPLETED | STOPPED_WITHOUT_COMPLETE | ITERATION_LIMIT_EXCEEDED | ERROR`. **Native completion** via `CompleteTaskTool` (replaces V1's fragile JSON-prefix heuristic). Multi-turn message threading via Spring AI's `AssistantMessage` (with toolCalls) + `ToolResponseMessage`. `OpenAiNativeToolClient` extended to serialize all 4 message types (system/user/assistant+toolCalls/tool) to OpenAI's wire format. Hybrid context fill, 75% compaction, Redis persistence deferred to Phase 8.
- **V2 planner (Phase 4):** `com.navneet.executespec.v2.agent.PlannerAgentV2.plan(PlannerRequestV2) → PlanV2` — produces a structured plan via a single restricted-tool LLM call. Technique: inline `submitPlan` `ToolCallback` whose input schema IS the plan schema; the model emits structured output directly via native tool calling (no JSON-in-text parsing). Planner extracts the tool-call arguments and parses them into `PlanV2`. `LlmRequestV2.toolsOverride` added so the router honors an explicit callback list instead of binding the full V2 tool set. `SINGLE_SHOT` classification bypasses the LLM entirely and synthesizes a 1-task plan from the spec (fast path for trivial requests). Task caps: SINGLE_SHOT=1, MEDIUM=4, MULTI_TASK=unlimited — enforced post-parse with truncation + warning if the model over-shoots. Plan schema new fields: `buildCommand` (optional explicit build command), `smokeTestStrategy` (`BUILD` | `PREVIEW_HEALTH` | `TEST_SUITE`), per-task `hardConstraints` array. Prompt template at `resources/prompts/v2/planner.md` with explicit CONSTRAINT PROPAGATION section. **Gemini-specific**: when `toolsOverride` is non-empty the router auto-sets `toolConfig.functionCallingConfig.mode=ANY` + `allowedFunctionNames=[...]` — prevents gemini-2.5-flash's Python-code degradation under heavy prompt load.
- **V2 sequential executor (Phase 5):** `com.navneet.executespec.v2.harness.SequentialExecutorV2.execute(ExecutionRequest) → ExecutionResult` — runs a plan's tasks in order, one worker invocation per task. Per-task flow: compose system prompt (base + hardConstraints + touchesFiles hint) + user prompt (description) → `WorkerAgentV2.run()` → if worker didn't reach `COMPLETED` record `WORKER_FAILED` → else run `buildProject` smoke test via the plan's `buildCommand` → smoke passes → `SUCCESS` → smoke fails → ONE retry with build error injected into user prompt → retry succeeds → `SUCCESS_AFTER_RETRY` → retry fails → `COMPLETED_WITH_WARNING` and continue. Aggregate verdict: `ALL_SUCCESS | PARTIAL_WITH_WARNINGS | FAILED | ABORTED`. Rule-based failure handling only (LLM-tier `TaskSupervisorV2` deferred). No SSE events, no Redis context persistence, no cross-task sharing yet (Phase 8 owns context persistence).
- **V2 reviewer (Phase 7):** `com.navneet.executespec.v2.reviewer.ReviewerAgentV3.review(ReviewerRequestV2) → ReviewerResult` (named V3 — V1 already owns both `ReviewerAgent` and `ReviewerAgentV2`; V3 continues the project-wide version sequence) — post-execution code reviewer that runs after the executor completes. Two sequential review passes, each a narrowly-scoped `WorkerAgentV2` invocation with a different system prompt: (1) **general review** targets logic bugs, incomplete implementations, contract violations, obvious runtime failures — explicitly out of scope: style, naming, refactoring preferences; (2) **wiring review** is the project-type-aware specialized sub-phase (Rust `mod.rs`, TS barrels, Spring component-scan / controller registration, Express route registration, Python decorator registration, Go package imports). After both passes, runs the build command once more and emits a verdict: `CLEAN | FIXED_ISSUES | UNRESOLVED_ISSUES | REVIEW_FAILED`. Implementation is a thin composition over `WorkerAgentV2` — reuses the agent loop, native tool calling, and `completeTask` signaling. Prompts: `resources/prompts/v2/reviewer-general.md`, `resources/prompts/v2/reviewer-wiring.md`. Default cap 30 iterations per pass (smaller than worker's 40 — review is inspection + targeted edits, not generation).
- **Frontend:** React 18, Vite 5, Tailwind CSS 3.4, TypeScript 5.3
- **Database:** PostgreSQL (Cloud SQL) for state, Redis for caching + gate coordination + feature flags + rate limiting
- **Infra:** GCP N. Virginia (us-east4), Cloud Run for previews (dev/prod), Docker Compose for local previews, GCE for main app
- **Multi-agent pipeline:** PipelineHarness → PlannerAgent → Plan Gate → (Real-time: SequentialTaskExecutor | Batch: BatchClientRouter → BatchPollService → BatchResumeService) → CompilerAgent → Auto-merge all tasks → **COMPLETE** → Auto-preview (5-min TTL) → Background validation thread: ReviewerAgentV2 (inspect + auto-fix) + TestGenOrchestrator + TestPreviewCoordinator (reuses preview) + ValidationReport
- **Batch execution:** All 3 providers (Anthropic/OpenAI/Google) support batch APIs with 50% cost discount. User chooses real-time or batch at plan approval. Batch flow: QUEUED_BATCH → IN_PROGRESS_BATCH → BATCH_COMPLETE → auto-retry failed tasks in real-time → compile → COMPLETE. `BatchPollService` polls every 30s with `@SchedulerLock` (Redis). `BatchResumeService` parses results, writes files, resolves WORKHORSE model/provider for task attribution, publishes `BatchCompleteEvent`. PipelineHarness `onBatchComplete()` retries failed tasks via `SequentialTaskExecutor`, then resumes compile. Batch runs skip auto-preview; `TestPreviewCoordinator` publishes empty event to unblock validation report.
- **Subtask decomposition:** Tasks with 2+ owned files are decomposed into per-file subtasks at execution time by `SubtaskDecomposer`. Each subtask produces exactly one `writeFile` call, eliminating fabrication. Subtasks tracked via `parentTaskId`/`subtaskIndex`/`subtaskTotal` on `AgentTask`.
- **Project type detection:** Planner declares `project_type` in plan JSON → written to `.agent-context/project.json` → read by preview/deploy Dockerfile generators (fallback: file-based heuristics)
- **LLM Router:** User-selected provider per project (Anthropic/Google/OpenAI). Default provider: OpenAI. YAML-configured model matrix maps `AgentTier x Provider → Model` via `ModelResolver`. Health-based failover via `ProviderHealthTracker` (Redis-backed circuit breaker, 5 failures → open, 60s TTL auto-recovery). Agent tiers: PLANNING, WORKHORSE, LIGHTWEIGHT.
- **Gate system:** Redis-backed plan approval gate with 500ms polling, 2h TTL, 24h max wait timeout. Merge is automatic (no manual gate).
- **Progressive merge:** Each task merged to main immediately after completion (validation deferred to review phase)
- **Auth:** JWT (access 1h, refresh 7d) + OAuth2 (Google, GitHub). Scoped SSE tokens (5-min TTL, `scope=sse` + `runId` claim) for event streams. RBAC with SUPER_ADMIN / ORG_ADMIN / BILLING_ADMIN / DEVELOPER / PROJECT_ADMIN / PROJECT_DEVELOPER roles. Force-logout via feature flag epoch-based JWT invalidation. JWT secret validated at startup in prod (`JwtSecretValidator`).
- **Tiers:** FREE (5 runs/month, 1-credit specs only) → PRO (100/month) → ENTERPRISE (unlimited). Each run costs 1–N credits proportional to estimated LLM cost: `credits = max(1, ceil(bufferedEstimate / $5.0))`.
- **Billing:** Multi-gateway via `PaymentGatewayRouter` (Razorpay + Stripe). Subscriptions, run top-up credits, invoicing with GST, payment audit trail. Cost-proportional credits: charged at plan approval (not submission), refunded on FAILURE. `run_cost_log` table tracks estimated vs actual cost for pricing analytics.
- **Security:** 8-layer defense-in-depth: request validation, input sanitization (`InputSanitizer` — NFKC normalization, zero-width strip, base64 decode+scan), LLM prompt defense, code abuse scanning (`CodeAbuseScanner` — crypto, reverse shell, PowerShell, DNS exfil, cloud metadata, obfuscation), shell sandbox, file sandbox, process isolation, container hardening (metadata endpoint blocking, env var whitelist). Configurable CSP/HSTS/X-Frame-Options via `SecurityProperties`.
- **Feature flags:** DB-backed `FeatureFlagService` with Redis pub/sub cache invalidation + 60s scheduled reload. Admin-manageable via `FeatureFlagController`.
- **Rate limiting:** Redis-backed per-tenant rate limiting via `RateLimitFilter`. Endpoint groups: AUTH, RUNS, WEBHOOK, READ, WRITE, PREVIEW, DEPLOY with per-tier limits.
- **Observability:** Micrometer + OTel → Prometheus + Tempo + Grafana (local) / Cloud Trace + Cloud Monitoring (GCP)
- **Prompt architecture:** Composable fragment system via `PromptAssembler` (`@Service`). Shared rules stored in `prompts/fragments/*.md`, injected via `{{include:name}}` markers. Per-project-type scaffold templates in `prompts/scaffolds/*.md`, injected via `{{SCAFFOLD_TEMPLATE}}`. `assembleAndTrack()` computes SHA-256 prefix hash, saves row to `prompt_audit` table. Inline CSS removed from worker system prompts (`{{STYLING_SECTION}}` = empty string); actual design content arrives via `buildFrontendBlock()` → `UiStyleResolver` → filesystem folder in `application.yml`.
- **Request triage:** Three-tier classification (`SINGLE_SHOT | MEDIUM | MULTI_TASK`) via `RequestTriageService`. Full-stack pattern safety check before LLM call. Workspace context (`file_tree`, `detected_frameworks`) injected into triage prompt. Structured JSON output `{"classification", "confidence", "reason"}`. Confidence < 0.7 → escalates to MULTI_TASK. MEDIUM → planner capped at 4 tasks.

## Code Conventions

- **Base package:** `com.navneet.executespec`
- **Layers:** controller → service → agent → llm (via LlmRouter)
- **DTOs:** Java records where possible. Lombok `@Data`/`@Builder` for JPA entities.
- **Dependency injection:** Constructor injection via Lombok `@RequiredArgsConstructor` (no `@Autowired` field injection)
- **LLM prompts:** External files in `src/main/resources/prompts/*.txt` — never inline strings
- **Worker tool format:** XML `<tool_call>` format with manual dispatch and runId injection (NOT Spring AI native tool calling). Self-play prevention: fabricated `<tool_result>` blocks stripped before parsing. XML-first parsing for PATH B (Anthropic/Google), JSON fallback.
- **Completion detection:** Worker response must START with `{` (strict JSON check)
- **Naming:** `*Agent.java` for agents, `*Service.java` for services, `*Controller.java` for REST, `*Spec.java` for security specifications
- **DB migrations:** Flyway `V{n}__description.sql` in `src/main/resources/db/migration/` (currently V1–V73)
- **Prompt assembly:** LLM prompts assembled via `PromptAssembler.assemble(path, vars)` — resolves `{{include:fragment}}` markers from `prompts/fragments/` and `{{VAR}}` substitutions. Never use `Resource.getContentAsString()` directly in agents.

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
| POST | `/api/runs/{id}/approve` | Plan gate approval (charges N credits; body: `{approved, creditsConfirmed?}`) |
| POST | `/api/runs/{id}/merge` | Merge gate (legacy — merge is now automatic) |
| DELETE | `/api/runs/{id}` | Cancel/delete run |
| POST | `/api/runs/{id}/archive` | Archive run |
| POST | `/api/runs/{id}/tasks/{taskId}/retry` | Retry failed task |
| GET | `/api/runs/{id}/events` | SSE event stream (scoped token) |
| GET | `/api/runs/{id}/sse-token` | Get scoped SSE token (5-min TTL) |
| POST | `/api/runs/{id}/run-tests` | Manually trigger test execution (Beta, requires preview RUNNING) |

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

### Notifications
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/notifications` | List notifications (paginated) |
| GET | `/api/notifications/unread-count` | Unread notification count |
| POST | `/api/notifications/{id}/read` | Mark notification as read |
| POST | `/api/notifications/read-all` | Mark all notifications as read |

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
| Compile supervisor | `harness/TaskCompileSupervisor.java` (PASS/AUTO_FIX/RETRY_TASK/CONTINUE_WITH_WARNING) |
| Supervision action enum | `harness/SupervisionAction.java` |
| Supervision decision record | `harness/TaskCompileDecision.java` |
| Subtask decomposer | `harness/SubtaskDecomposer.java` (multi-file → per-file subtask splitting) |
| Run cost budget | `harness/RunCostBudgetService.java` (silent hard cap only, no progressive warnings) |
| Cost estimator | `agent/CostEstimator.java` (per-run cost estimate + credit calculation) |
| Cost estimate DTO | `agent/RunCostEstimate.java` (credits, hard cap, per-task breakdown) |
| Cost audit log | `billing/cost/RunCostLog.java`, `billing/cost/RunCostLogRepository.java` |
| Batch client interface | `batch/LlmBatchClient.java` (submit, poll, get results, cancel) |
| Batch client router | `batch/BatchClientRouter.java` (routes to Anthropic/OpenAI/Google batch client) |
| Anthropic batch client | `batch/AnthropicBatchClient.java` (POST /v1/messages/batches) |
| OpenAI batch client | `batch/OpenAiBatchClient.java` (JSONL file upload + batch create) |
| Google batch client | `batch/GoogleBatchClient.java` (inline + file-based modes) |
| Batch request builder | `batch/BatchRequestBuilder.java` (builds BatchRequests from TaskPlan) |
| Batch poll service | `batch/BatchPollService.java` (@Scheduled 30s + @SchedulerLock) |
| Batch resume service | `batch/BatchResumeService.java` (processes results, writes files, publishes event) |
| Notification entity | `notification/Notification.java` |
| Notification service | `notification/InAppNotificationService.java` |
| Notification controller | `notification/NotificationController.java` (REST: GET/POST /api/notifications) |
| Notification listener | `notification/RunCompletionNotifier.java` (creates notifications on run complete/fail) |
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
| Request triage | `agent/RequestTriageService.java` (3-tier: SINGLE_SHOT/MEDIUM/MULTI_TASK, workspace context injection) |
| Prompt assembler | `agent/PromptAssembler.java` (fragment resolution, variable substitution, hash tracking) |
| Prompt audit entity | `agent/PromptAudit.java`, `agent/PromptAuditRepository.java` |
| Failure pattern service | `agent/FailurePatternService.java` (queries recent runs, returns markdown bullet list for planner) |
| Prompt fragments | `resources/prompts/fragments/*.md` (security-rules, preview-defaults, cors-config, etc.) |
| Scaffold templates | `resources/prompts/scaffolds/*.md` (spring-boot, node-backend, node-frontend, python, go, etc.) |
| API prober | `testgen/ApiProber.java` (probes live preview endpoints before test generation) |
| API surface extractor | `testgen/ApiSurfaceExtractor.java` (regex-extracts routes from workspace for test planner) |
| Slack integration | `integration/slack/SlackNotificationService.java` |
| GitHub repo service | `integration/github/GitHubRepoService.java` (branch, PR creation, default branch lookup) |
| Git tool | `tool/GitTool.java` (workspace git ops, `pushToBranch()` for existing repos) |
| JIRA adapter | `integration/jira/JiraAdapter.java` (inbound webhook + outbound comments) |
| Confluence adapter | `integration/confluence/ConfluenceAdapter.java` (inbound webhook + outbound child pages) |
| Integration manager | `integration/IntegrationManager.java` (routes webhooks, listens for MergeCompleted) |
| Test generation orchestrator | `testgen/TestGenOrchestrator.java` |
| Test preview coordinator | `testgen/TestPreviewCoordinator.java` (owns preview lifecycle for test execution) |
| Test execution service | `testgen/TestExecutionService.java` |
| Test result controller | `testgen/TestResultController.java` (apply-test-fixes, run-tests) |
| DB migrations | `resources/db/migration/V*.sql` (V1–V73; V72=prompt_audit, V73=plan_effectiveness) |
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
| PLANNING | PlannerAgent, ReviewerAgentV2, TaskCompileSupervisor | `claude-sonnet-4-6` | `gemini-3-flash-preview` | `gpt-5.4` |
| WORKHORSE | WorkerAgent, CompilerAgent, TestPlannerAgent, TestWorkerAgent, ApiTestSuiteAgent, ConflictResolverAgent, PromptOptimiserAgent, ValidationReportGenerator | `claude-haiku-4-5-20251001` | `gemini-3-flash-preview` | `gpt-5.4` |
| LIGHTWEIGHT | ConversationAgent, ContextAgent, TestReportGenerator, RequestTriageService | `claude-haiku-4-5-20251001` | `gemini-3-flash-preview` | `gpt-5.4` |

Default provider: OpenAI (user-selected per project). Failover order: Anthropic→[OpenAI, Google], Google→[OpenAI, Anthropic], OpenAI→[Google, Anthropic]. Stall escalation promotes WorkerAgent from WORKHORSE → PLANNING tier. See `application.yml` → `model-matrix:` for full configuration.

## Current State

### Working
- Full pipeline: plan → approve → work (tiered parallel, progressive merge) → compile → auto-merge all → **COMPLETE** → auto-preview (5-min TTL) → background validation (review + test gen + test exec + report)
- User-selected AI provider per project (Anthropic/Google/OpenAI) with YAML model matrix and health-based failover
- 25-minute wall-clock worker time limit with stall detection and model escalation
- Dual-mode previews: Cloud Run (dev/prod, 5-min TTL, managed HTTPS) or Docker Compose (local, 5-min TTL, Caddy proxy with Cloudflare Origin Certificate)
- JWT auth + OAuth2 (Google, GitHub) with RBAC, org management, project scoping
- Multi-gateway billing via PaymentGatewayRouter (Razorpay + Stripe): subscriptions, run top-up credits, invoicing with GST
- Cost-proportional run credits: `credits = max(1, ceil(estimate × 1.10 / $5.0))`. Charged at plan approval, refunded on FAILURE. FREE tier limited to 1-credit specs. Insufficient credits → suggest top-up or tier upgrade.
- `run_cost_log` audit table: tracks estimated vs actual cost, margin, budget-hit rate per run for pricing analytics
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
- Request triage: three-tier (SINGLE_SHOT / MEDIUM / MULTI_TASK) with workspace context, confidence scoring, and full-stack safety pattern
- Observability: Micrometer traces + custom metrics (30+ meters) + structured logging
- Local Grafana dashboards (ExecuteSpec Overview + JVM/HTTP)
- GCP Cloud Trace (traces) + Cloud Monitoring (metrics) via `@Profile("gcp")` exporters
- Model evaluation framework (`eval/` package)
- Atomic quota enforcement (pessimistic locking, single-TX check+increment, multi-credit support)
- Atomic token counter updates (SQL-level increment, no read-modify-write)
- Bounded thread pool for pipeline runs (CallerRunsPolicy backpressure)
- Gate wait timeout (24h max, prevents indefinite thread block)
- Semaphore enforcement on all run entry points (startIterationRun, resumeRun, executeApplySuggestion)
- Concurrent preview limit race fix (synchronized block)
- Scoped SSE tokens (5-min TTL, per-runId, prevents cross-run eavesdropping)
- JWT secret startup validation (prod profile, blocks dev placeholder)
- Tier-based deployment limits (enforces `max_deployments` from tier_config)
- Silent hard cap cost enforcement (checks budget every 5 LLM calls in worker loop; ceiling = credits × $5 × 1.5; no progressive warnings — user approved cost upfront)
- Batch execution: dual-option plan approval (real-time vs batch with 50% discount), all 3 LLM providers (Anthropic/OpenAI/Google), @Scheduled polling with @SchedulerLock, event-driven pipeline resume, auto-retry failed batch tasks in real-time, batch model/provider attribution, batch badge on run cards/details
- Manual test execution (Beta): POST /runs/{runId}/run-tests endpoint, "Run Tests" button in PreviewTab (requires preview RUNNING)
- In-app notification system: JPA entity, REST API, NotificationBell in header, run completion/failure notifications
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
- Prompt architecture remediation (19 issues): shared fragment system (`PromptAssembler`), scaffold templates, planner restructured to ≤3500 words with priority tiers, per-language complexity heuristics, prompt version tracking (SHA-256 hash → `prompt_audit`), failure pattern injection into planner, live API probe for test generation (`ApiProber`), API surface extraction for test planner (`ApiSurfaceExtractor`), conflict resolver structured JSON output with risk level, token cost awareness in worker prompts, self-review enforcement (post-commit `listFiles` required), command allowlist in `ProcessExecutor`
- **TaskCompileSupervisor:** Per-task intelligent compile triage between merge and revert. Rule-based fast path classifies errors as CASCADING / STUB / FIXABLE / STRUCTURAL; PLANNING-tier LLM call for borderline cases. Actions: PASS (cascading noise, keep merge), AUTO_FIX (targeted `CompilerAgent.applyTargetedFix()` on owned files only), RETRY_TASK (re-run worker with targeted feedback), CONTINUE_WITH_WARNING (errors in non-owned files). Replaces the old "always revert + retry" logic that caused PARTIAL cascades. Metric: `executespec.supervisor.decision` tagged by action.
- **Gap-closer reliability:** `GapClosingService` tracks attempted gap texts across cycles via `HashSet` — each gap gets at most 1 attempt, eliminating duplicate task records on multi-cycle runs. Gap task complexity upgraded LOW → MEDIUM so adaptive iteration cap uses the MEDIUM bucket (28 cold-start vs 20), giving gap tasks sufficient budget for codebase exploration before writing.
- **WorkerAgent method chain fix:** `buildInitialUserMessage`, `handlePromptBasedToolCalls`, `handlePathBCompletion`, and `injectFabricationRetryContext` now correctly thread `AgentTask agentTask` as a separate parameter alongside `TaskDefinition taskDef`, fixing a compile error where `getParentTaskId()` was called on the wrong type.
- **FileViewer markdown preview:** `.md` files in the workspace viewer show a Preview/Source toggle. Preview mode renders via `MarkdownContent`; Source mode uses Monaco editor.
- **UI gap task display:** Gap-closing tasks (`_gap_` taskId pattern) excluded from main task counters in RunDetail and RunDetailTabs. Shown collapsed in a "Gap fixes (background)" section, deduplicated by title.
- **Provider eval script (4-dimension):** `scripts/test-providers.sh` measures cost, time, accuracy (from validation report), and efficiency (tokens, tasks) per provider. Outputs per-provider table + timestamped JSON results file.

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
- `context-agent.txt` outputs JSON patch array (`KEEP/UPDATE/ADD/REMOVE`) but `ContextAgent.java` still writes raw LLM response — Java-side patch application not yet implemented
- **Validation report `status` field:** `ValidationReport` entity has no `status` field — `/api/runs/{id}/report` returns 404 when not ready and 200 with data when ready. Eval script and any consumers must detect presence via `accuracyScore != null`, not `status == "COMPLETE"`.
