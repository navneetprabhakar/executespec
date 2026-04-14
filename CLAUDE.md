# Project: ExecuteSpec

> This file is read by Claude Code at session start. Keep it accurate.
> Last updated: 2026-04-13 (pilot v3.0 shipped — see commit 6bbfde9b)

## Architecture

- **Backend:** Java 21, Spring Boot 3.3, Spring AI 1.1.4
- **Harness:** V2 is the default and only supported path for new runs (`com.navneet.executespec.v2.*`). V1 (`harness/`, `agent/`, `compiler/`, `reviewer/`, `batch/`) is frozen pending deletion — see [docs/plans/deprecation-removal-plan.md](docs/plans/deprecation-removal-plan.md) for the phased rollout. Shared infrastructure (StateStore, PipelineRun/AgentTask entities, CostEstimator, ApiKeyResolver, WorkspaceInitService, GitHubRepoService, ModelResolver, GateStore, UiStyleResolver) stays live under V2.
- **V2 pipeline:** `V2RunOrchestrator` (entry) → `PlannerAgentV2` (plan as a `submitPlan` tool call) → plan gate (PRODUCTION pauses at AWAITING_APPROVAL, PROTOTYPE bypasses) → `SequentialExecutorV2` (tier-routed worker per task, one at a time) → `ReviewerAgentV3` (general + wiring passes) → validation report + test-gen kickoff → auto-preview. Runs persist to the shared `pipeline_run`/`agent_task` tables.
- **V2 tools:** Unified `Tool` contract in `v2.tool` — `readFile`, `writeFile`, `editFile`, `listFiles`, `searchFiles`, `shell`, `gitStatus`, `gitDiff`, `gitCommit`, `buildProject`, plus `completeTask` for native termination. `ToolRegistryV2.bind(ExecutionContext)` returns Spring AI `ToolCallback`s with run context captured. Sequential-by-design: no FileLockTool.
- **V2 LLM router:** `LlmRouterV2.call(LlmRequestV2)` uses native tool calling for all 3 providers. Anthropic via Spring AI; OpenAI via `OpenAiNativeToolClient` (bypasses Spring AI 1.1.4's `extra_body` bug); Gemini via `GeminiNativeToolClient` (handles schema sanitization + thought-signature round-trip for 2.5+ models). Every call flows through `V2RunCostTracker` for token + cost accounting.
- **V2 worker:** `WorkerAgentV2` — single-agent, multi-turn loop. Per-complexity caps: LOW=12 iters/4k tokens, MEDIUM=22/8k, HIGH=35/16k. Outcomes: COMPLETED | STOPPED_WITHOUT_COMPLETE | ITERATION_LIMIT_EXCEEDED | ERROR. Native completion via `CompleteTaskTool`. Conversation snapshots saved to Redis every 5 iterations via `ContextStoreV2` (24h TTL) for recovery.
- **V2 planner:** `PlannerAgentV2` emits a structured plan via a restricted-tool `submitPlan` call (no JSON-in-text parsing). Schema includes `buildCommand`, `smokeTestStrategy` (BUILD | PREVIEW_HEALTH | TEST_SUITE), `runtimeSurface` (HTTP | CLI | LIBRARY | STATIC_WEB), per-task `hardConstraints`. Classification (SINGLE_SHOT | MEDIUM | MULTI_TASK) caps task count (1 / 4 / unlimited). `SINGLE_SHOT` bypasses the LLM and synthesises a 1-task plan.
- **V2 sequential executor:** per task — compose system + user prompt → `WorkerAgentV2.run()` → if worker hit `completeTask`, run `buildProject` smoke test → pass=SUCCESS, fail=ONE retry with build error injected, retry fail=COMPLETED_WITH_WARNING. Aggregate: ALL_SUCCESS | PARTIAL_WITH_WARNINGS | FAILED | ABORTED. Per-task model tier: LOW→LIGHTWEIGHT, MEDIUM→WORKHORSE, HIGH→PLANNING.
- **V2 reviewer:** `ReviewerAgentV3` — two `WorkerAgentV2`-backed passes (general + wiring), 30 iter cap each. Wiring pass is project-type-aware (Rust `mod.rs`, TS barrels, Spring component-scan, Express routes, Python decorators, Go imports). Final verdict: CLEAN | FIXED_ISSUES | UNRESOLVED_ISSUES | REVIEW_FAILED. Iteration-limit outcomes fall through to final-build verdict rather than masquerading as REVIEW_FAILED.
- **V2 cost + tokens:** `V2RunCostTracker` accumulates per-call usage + cost (Anthropic cache-aware). Phase-level attribution (planner / reviewer) persisted on `PipelineRun` via snapshot diffs; per-task worker tokens on `AgentTask` rows. Run totals are built atomically via `PipelineRunRepository.addTokens` calls from each phase — terminal `terminate()` no longer overwrites, only bumps up. Pre-plan `estimateRunTierAware()` populates `costEstimateLow/High`, `creditsCharged`, `hardCapUsd`.
- **V2 observability:** `V2Metrics` — `executespec.v2.*` meters for run.outcome + run.latency, planner.latency + planner.tasks, task.status + task.latency + task.smoketest, worker.iterations + worker.outcome + worker.toolcall, llm.latency, reviewer.verdict, context.save + context.save.bytes. Dump endpoint `/api/v2/test/metrics`. Prometheus scrape at `/actuator/prometheus`.
- **V2 recovery:** `StartupRunResumerV2` scans non-terminal V2 runs on boot (PLANNING/WORKING/REVIEWING/AWAITING_APPROVAL) and fails them cleanly with a clear reason + terminal SSE, so the UI surfaces the interruption instead of leaving runs stuck. Full replay (restore conversation history + resume at saved iteration) is a planned follow-up — see [docs/plans/harness-v2-remaining-work.md](docs/plans/harness-v2-remaining-work.md) Gap 2.
- **V2 cancel:** `V2RunOrchestrator.cancel(runId)` is the V2 path for `DELETE /api/runs/{id}` — signals the plan gate, interrupts the pipeline future, writes CANCELLED status + terminal SSE.
- **V2 eval harness:** `scripts/v2-eval.sh` submits LOW / MEDIUM / HIGH specs × anthropic / openai / gemini (9 runs, PROTOTYPE), polls each to terminal, emits machine-readable JSON + a markdown comparison to `scripts/v2-eval-results/v2-eval-<timestamp>.{json,md}`. Specs live in `scripts/v2-eval-specs/{low,medium,high}.txt`.
- **Pilot v3.0 — project workspace (`project/`, `codeintel/`, `assessment/`, `scoring/`):** Project becomes the unit of organization. New `ProjectDashboardController` aggregates active/completed/failed counts + score trend. Dual-scope `ProjectRepo` registry (ORG = shared, USER = personal) joined to projects via `project_repo_link`; multi-repo selection in `NewRunModal` clones each into `workspace/<runId>/repos/<name>/` via `WorkspaceInitService.cloneMultipleRepos`. `ProjectArchitectureService` versions per-repo `ProjectCodeMap` + project-wide `ProjectArchitectureDoc`; `StalenessService` (Caffeine 5-min cache) drives the dashboard's STALE flag using stored generation SHAs. `FeatureAssessmentService` runs before planner (heuristic by default; LLM-backed when `assessment.llm.enabled` flag is on); the `AssessmentPanel` surfaces complexity + risks + cross-repo deps above the plan-approval gate. `ScmPushService.pushMultiRepo` handles per-subdir push + per-repo PR + cross-link via `GitHubRepoService.updatePullRequestBody`; PRs persisted to `pull_request_record` (R5), webhook at `/webhooks/github` flips status (HMAC SHA-256 verified). `ReviewScoringService` derives 6 dimensions (code quality / test coverage / architecture / security / performance / design compliance) from review issues + accuracy report — no extra LLM call. `ScoreCard` at top of `ValidationReportPanel`. Optional Figma URL on `RunRequest` drives design-compliance scoring. `StackRefreshJob` (`@Scheduled` daily 03:00, `@SchedulerLock`) refreshes `detected_stack` + `last_synced_sha` org-wide.
- **Frontend:** React 18, Vite 5, Tailwind CSS 3.4, TypeScript 5.3
- **Database:** PostgreSQL (Cloud SQL) for state, Redis for caching + gate coordination + feature flags + rate limiting
- **Infra:** GCP N. Virginia (us-east4), Cloud Run for previews (dev/prod), Docker Compose for local previews, GCE for main app
- **Batch execution:** Not yet re-implemented under V2. V1 batch (`batch/*`) is frozen; the V2 re-implementation is scoped in [docs/plans/v2-batch-execution-plan.md](docs/plans/v2-batch-execution-plan.md) and waits on deprecation P3.
- **Project type detection:** Planner declares `project_type` in plan JSON → written to `.agent-context/project.json` → read by preview/deploy Dockerfile generators (fallback: file-based heuristics)
- **LLM Router:** V2 uses `LlmRouterV2` with native tool calling; YAML-configured model matrix maps `AgentTier x Provider → Model` via `ModelResolver`. Provider circuit breaker via `ProviderHealthTracker` (Redis-backed, 5 failures → open, 60s TTL auto-recovery). Agent tiers: PLANNING, WORKHORSE, LIGHTWEIGHT.
- **Gate system:** Redis-backed plan approval gate with 500ms polling, 2h TTL, 24h max wait timeout. V2 auto-merges per task — no merge gate.
- **Auth:** JWT (access 1h, refresh 7d) + OAuth2 (Google, GitHub). Scoped SSE tokens (5-min TTL, `scope=sse` + `runId` claim) for event streams. RBAC with SUPER_ADMIN / ORG_ADMIN / BILLING_ADMIN / DEVELOPER / PROJECT_ADMIN / PROJECT_DEVELOPER roles. Force-logout via feature flag epoch-based JWT invalidation. JWT secret validated at startup in prod (`JwtSecretValidator`).
- **Tiers:** FREE (5 runs/month, 1-credit specs only) → PRO (100/month) → ENTERPRISE (unlimited). Each run costs 1–N credits proportional to estimated LLM cost: `credits = max(1, ceil(bufferedEstimate / $5.0))`.
- **Billing:** Multi-gateway via `PaymentGatewayRouter` (Razorpay + Stripe). Subscriptions, run top-up credits, invoicing with GST, payment audit trail. Cost-proportional credits: charged at plan approval (not submission), refunded on FAILURE. `run_cost_log` table tracks estimated vs actual cost for pricing analytics.
- **Security:** 8-layer defense-in-depth: request validation, input sanitization (`InputSanitizer` — NFKC normalization, zero-width strip, base64 decode+scan), LLM prompt defense, code abuse scanning (`CodeAbuseScanner` — crypto, reverse shell, PowerShell, DNS exfil, cloud metadata, obfuscation), shell sandbox, file sandbox, process isolation, container hardening (metadata endpoint blocking, env var whitelist). Configurable CSP/HSTS/X-Frame-Options via `SecurityProperties`.
- **Feature flags:** DB-backed `FeatureFlagService` with Redis pub/sub cache invalidation + 60s scheduled reload. Admin-manageable via `FeatureFlagController`.
- **Rate limiting:** Redis-backed per-tenant rate limiting via `RateLimitFilter`. Endpoint groups: AUTH, RUNS, WEBHOOK, READ, WRITE, PREVIEW, DEPLOY with per-tier limits.
- **Observability:** Micrometer + OTel → Prometheus + Tempo + Grafana (local) / Cloud Trace + Cloud Monitoring (GCP)
- **Prompt architecture:** Composable fragment system via `PromptAssembler` (`@Service`). Shared rules stored in `prompts/fragments/*.md`, injected via `{{include:name}}` markers. Per-project-type scaffold templates in `prompts/scaffolds/*.md`, injected via `{{SCAFFOLD_TEMPLATE}}`. `assembleAndTrack()` computes SHA-256 prefix hash, saves row to `prompt_audit` table. Inline CSS removed from worker system prompts (`{{STYLING_SECTION}}` = empty string); actual design content arrives via `buildFrontendBlock()` → `UiStyleResolver` → filesystem folder in `application.yml`.
- **Request classification:** V2 folds triage into `PlannerAgentV2` — classification (SINGLE_SHOT | MEDIUM | MULTI_TASK) decides task caps (1 / 4 / unlimited) and whether to bypass the LLM (SINGLE_SHOT uses a synthesised 1-task plan).

## Code Conventions

- **Base package:** `com.navneet.executespec`
- **Layers:** controller → service → V2 agent → V2 tool → `LlmRouterV2`. V1 `LlmRouter` is still alive for legacy agents (test-gen, `AccuracyAssessmentService`, `ConversationAgent`) until those migrate — see deprecation plan P4.
- **DTOs:** Java records where possible. Lombok `@Data`/`@Builder` for JPA entities.
- **Dependency injection:** Constructor injection via Lombok `@RequiredArgsConstructor` (no `@Autowired` field injection)
- **LLM prompts:** External files in `src/main/resources/prompts/*.txt` — never inline strings
- **Worker tool format:** Provider-native tool calling end-to-end via `LlmRouterV2`. Strict JSON schemas on every `v2/tool` entry. No XML `<tool_call>` parsing in V2 (that was a V1 idiom).
- **Completion detection:** `CompleteTaskTool` — the worker calls it as a native tool to terminate the loop. No JSON-prefix heuristic.
- **Naming:** `*Agent.java` for agents, `*Service.java` for services, `*Controller.java` for REST, `*Spec.java` for security specifications
- **DB migrations:** Flyway `V{n}__description.sql` in `src/main/resources/db/migration/` (currently V1–V73)
- **Prompt assembly:** LLM prompts assembled via `PromptAssembler.assemble(path, vars)` — resolves `{{include:fragment}}` markers from `prompts/fragments/` and `{{VAR}}` substitutions. Never use `Resource.getContentAsString()` directly in agents.

## Patterns: MUST Follow

- LLM calls go through `LlmRouter` — supports multi-provider fallback, per-run API key resolution, and tier-based model routing
- Agent outputs validated by the post-execute `ReviewerAgentV3` (general + wiring passes) + a final `buildProject` smoke, not by per-task compilation
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
| GET | `/api/runs/{id}/prs` | Pilot R5 — list PRs opened by this run with status |

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
| POST | `/api/runs/{id}/push-github` | Push to GitHub (single-repo path; multi-repo branches into `ScmPushService.pushMultiRepo`) |

### Pilot v3.0 — Project workspace
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/projects/{id}/dashboard` | Aggregated metrics, recent runs/PRs, arch-doc status (NONE/GENERATED/STALE) |
| GET | `/api/projects/{id}/scores` | Score trend feed for the dashboard chart |
| POST | `/api/repos` | Create repo at chosen scope (ORG requires ORG_ADMIN) |
| GET | `/api/repos?scope=ORG\|USER\|ALL` | List repos visible to caller |
| POST | `/api/repos/{repoId}/sync` | Re-detect stack + refresh `last_synced_sha` |
| DELETE | `/api/repos/{repoId}` | Delete repo (must not be linked to any project) |
| GET | `/api/projects/{id}/repos` | List repos linked to this project |
| POST | `/api/projects/{id}/repos/{repoId}` | Link an existing repo to the project |
| DELETE | `/api/projects/{id}/repos/{repoId}` | Unlink (repo itself survives) |
| PUT | `/api/projects/{id}/repos/{repoId}/relationships` | Update relationship tags |
| POST | `/api/projects/{id}/architecture/generate` | Regenerate codemaps + arch doc (versioned) |
| GET | `/api/projects/{id}/architecture` | Latest arch doc |
| GET | `/api/projects/{id}/architecture/versions` | All versions |
| PUT | `/api/projects/{id}/architecture/annotate` | Save user annotations on the latest doc |
| GET | `/api/projects/{id}/architecture/staleness` | Per-repo SHA drift report (5-min cached) |
| GET | `/api/projects/{id}/repos/{repoId}/codemap` | Latest codemap for a single repo |
| POST | `/webhooks/github` | GitHub PR webhook (HMAC SHA-256 verified, secret via `github.webhook.secret`) |

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
| V2 run orchestrator | `v2/integration/V2RunOrchestrator.java` |
| V2 sequential executor | `v2/harness/SequentialExecutorV2.java` |
| V2 planner | `v2/agent/PlannerAgentV2.java` |
| V2 worker | `v2/agent/WorkerAgentV2.java` |
| V2 reviewer | `v2/reviewer/ReviewerAgentV3.java` |
| V2 LLM router | `v2/agent/LlmRouterV2.java` + `OpenAiNativeToolClient` / `GeminiNativeToolClient` |
| V2 tool registry | `v2/tool/ToolRegistryV2.java` + `v2/tool/*` (10 tools + `CompleteTaskTool`) |
| V2 context store | `v2/context/ContextStoreV2.java` (Redis snapshot, 24h TTL) |
| V2 cost tracker | `v2/cost/V2RunCostTracker.java` (per-call accumulator + cache-aware pricing) |
| V2 startup resumer | `v2/recovery/StartupRunResumerV2.java` (fail-cleanly on boot) |
| V2 CLI smoke runner | `v2/smoketest/CliSmokeRunner.java` |
| V2 metrics | `v2/observability/V2Metrics.java` |
| V1 legacy (deprecated) | `harness/PipelineHarness.java`, `agent/PlannerAgent.java`, `agent/WorkerAgent.java`, `batch/*`, `compiler/*`, `reviewer/ReviewerAgentV2.java` — pending deletion per `docs/plans/deprecation-removal-plan.md` |
| Cost estimator (shared) | `agent/CostEstimator.java` (used by both V1 and V2 paths) |
| Cost audit log | `billing/cost/RunCostLog.java`, `billing/cost/RunCostLogRepository.java` |
| Notification entity | `notification/Notification.java` |
| Notification service | `notification/InAppNotificationService.java` |
| Notification controller | `notification/NotificationController.java` (REST: GET/POST /api/notifications) |
| Notification listener | `notification/RunCompletionNotifier.java` (creates notifications on run complete/fail) |
| Design system config | `harness/DesignSystemProperties.java` |
| UI style resolver | `harness/UiStyleResolver.java` (design loading, fuzzy match, 3-signal resolution) |
| Model resolver (matrix-based) | `router/ModelResolver.java` |
| Model matrix config | `router/ModelMatrixProperties.java` |
| Provider circuit breaker | `router/ProviderHealthTracker.java` (Redis-backed) |
| State store | `state/StateStore.java` |
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
| Request classification | Folded into `v2/agent/PlannerAgentV2.java` (`Classification: SINGLE_SHOT | MEDIUM | MULTI_TASK`) |
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
| Project workspace | `project/service/ProjectRepoService.java`, `project/controller/ProjectRepoController.java`, `project/dashboard/ProjectDashboardController.java`, `project/scores/ProjectScoresController.java` |
| Project repo entities | `project/entity/ProjectRepo.java` (ORG/USER scope), `project/entity/ProjectRepoLink.java` |
| PR records + webhook | `project/pr/PullRequestRecord.java`, `project/pr/PullRequestRecordService.java`, `project/pr/GitHubWebhookController.java`, `project/pr/PullRequestController.java` |
| Stack detection | `project/service/StackDetector.java`, `project/service/StackRefreshJob.java` (`@Scheduled` + `@SchedulerLock`) |
| Codebase intelligence | `codeintel/CodeMapGenerator.java`, `codeintel/ArchitectureDocGenerator.java`, `codeintel/ProjectArchitectureService.java`, `codeintel/ProjectArchitectureController.java`, `codeintel/StalenessService.java` |
| Feature assessment | `assessment/FeatureAssessmentService.java` (heuristic + LLM via `assessment.llm.enabled` flag), `assessment/AssessmentResult.java` |
| Review scoring | `scoring/ReviewScoringService.java` (6 dimensions, deterministic heuristic from existing review data) |
| Multi-repo push | `integration/scm/ScmPushService.pushMultiRepo`, `tool/GitTool.commitAndPushSubdir` |
| Workspace multi-repo | `harness/WorkspaceInitService.cloneMultipleRepos` |
| Dashboard frontend | `frontend/src/pages/project/ProjectDashboardPage.tsx`, `frontend/src/components/project/*` |
| DB migrations | `resources/db/migration/V*.sql` (V1–V86; V81=project settings + repo registry, V82=pull_request_record, V83=validation_report scores, V84=codebase intelligence, V85=assessment_result + figma_url, V86=pipeline_run.selected_repo_ids) |
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
| PLANNING | PlannerAgentV2 (HIGH tasks, reviewer) | `claude-sonnet-4-6` | `gemini-3-flash-preview` | `gpt-5.4` |
| WORKHORSE | WorkerAgentV2 (MEDIUM tasks), TestPlannerAgent, TestWorkerAgent, ApiTestSuiteAgent, ValidationReportGenerator | `claude-haiku-4-5-20251001` | `gemini-3-flash-preview` | `gpt-5.4` |
| LIGHTWEIGHT | WorkerAgentV2 (LOW tasks), ConversationAgent, ContextAgent, TestReportGenerator | `claude-haiku-4-5-20251001` | `gemini-3-flash-preview` | `gpt-5.4` |

Default provider: OpenAI (user-selected per project). Provider failover is available via `ProviderHealthTracker` (V1 router only — V2 `LlmRouterV2` is direct-dispatch with circuit-breaker metrics but no failover chain). See `application.yml` → `model-matrix:` for full configuration.

## Current State

### Working
- Full V2 pipeline: plan (native `submitPlan` tool) → approve → sequential per-task worker (tier-routed) → reviewer (general + wiring) → **COMPLETE** → auto-preview (5-min TTL) → background validation (report + test gen + test exec)
- V2 startup recovery: non-terminal V2 runs at boot are marked FAILED with a clear reason (replay is a planned follow-up)
- V2 cancel: `DELETE /api/runs/{id}` on V2 runs interrupts the pipeline, signals the plan gate, writes CANCELLED + terminal SSE
- V2 run-stats correctness: per-phase token persistence atomically increments run totals; `terminate()` only bumps up, never shrinks
- User-selected AI provider per project (Anthropic/Gemini/OpenAI) with YAML model matrix
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
- Observability: Micrometer traces + V2 custom metrics (`executespec.v2.*`) + structured logging
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
- Silent hard cap cost enforcement (ceiling = credits × $5 × 1.5; no progressive warnings — user approved cost upfront)
- Manual test execution (Beta): POST /runs/{runId}/run-tests endpoint, "Run Tests" button in PreviewTab (requires preview RUNNING)
- In-app notification system: JPA entity, REST API, NotificationBell in header, run completion/failure notifications
- Containerized test execution (Docker with --read-only, --cap-drop ALL, memory/CPU limits)
- Env var whitelist for Docker subprocess (PATH, HOME, DOCKER_HOST only — no leaked secrets)
- ServiceNow webhook HMAC verification
- Actuator endpoint restriction (health+prometheus public, rest SUPER_ADMIN)
- Design system integration: 54 company DESIGN.md files loaded from filesystem, 3-signal resolution (explicit hint > planner ui_style > project type heuristic), Caffeine cache (30min), token-budget truncation (2500 tokens), GCS-synced on deploy/boot
- CSP `frame-src` directive for Cloud Run preview iframes
- Prompt architecture: shared fragment system (`PromptAssembler`), scaffold templates, prompt version tracking (SHA-256 hash → `prompt_audit`), failure pattern injection into planner, live API probe for test generation (`ApiProber`), API surface extraction for test planner (`ApiSurfaceExtractor`), command allowlist in `ProcessExecutor`
- **FileViewer markdown preview:** `.md` files in the workspace viewer show a Preview/Source toggle. Preview mode renders via `MarkdownContent`; Source mode uses Monaco editor.
- **V2 eval matrix:** `scripts/v2-eval.sh` — LOW/MEDIUM/HIGH × anthropic/openai/gemini, produces JSON + markdown comparison under `scripts/v2-eval-results/`
- **Pilot v3.0 — project workspace** (commit `6bbfde9b`): project dashboard with metrics + score trend + recent runs/PRs; ORG/USER scoped repo registry with multi-repo selection in NewRunModal; per-repo codemaps + versioned project architecture doc with annotations + STALE detection; pre-plan feature assessment (heuristic by default; LLM-backed when `assessment.llm.enabled` flag is on); 6-dimension review scoring on validation report; per-run PR list with GitHub webhook for merge status; multi-repo PR creation with cross-linking; daily `StackRefreshJob` keeps `detected_stack` + `last_synced_sha` fresh; optional Figma URL drives design-compliance scoring.

### In Progress
- Production deployment preparation

### Known Tech Debt
- **V2 batch not implemented** — V2 re-implementation scoped in `docs/plans/v2-batch-execution-plan.md`; V1 batch already deleted, dependency unblocked.
- **V2 startup replay (WORKING/REVIEWING) pending** — boot-time recovery marks interrupted WORKING/REVIEWING runs FAILED; full conversation replay is planned (`docs/plans/harness-v2-remaining-work.md` Gap 2). AWAITING_APPROVAL runs: the approve endpoint now detects the no-thread case and fails cleanly with 409, avoiding silent hangs.

### Recently Resolved (2026-04-13, branch `feature/v2-next`)
- ~~**Intermediate smoke-test false negatives**~~ — multi-task BUILD plans used to mark intermediate tasks PARTIAL when the scaffold couldn't build yet. `SequentialExecutorV2` now skips smoke on non-final tasks for multi-task plans; only the final task + reviewer final build are authoritative.
- ~~**Per-task worker tokens equal-split**~~ — `V2RunCostTracker` now keeps a per-task accumulator keyed by `runId|taskId`; `LlmRouterV2` passes the task id on every dispatch; `distributeWorkerTokens` uses real per-task snapshots with equal-split only for the residual.
- ~~**Gate lost on restart**~~ — `GateStore.isPlanGateWaiting()` lets the approve endpoint detect when the pipeline thread is gone (server restart during AWAITING_APPROVAL) and fail cleanly with 409 instead of hanging.
- ~~**`@Lazy @Autowired` on `PreviewService`**~~ — `TestPreviewCoordinator` now injects `ObjectProvider<PreviewService>` via the constructor, matching the rest of the codebase.
- ~~**ValidationReport `status` field**~~ — added `status` column (PENDING / COMPLETE / FAILED) via V80; service writes `COMPLETE` on successful persistence. Consumers no longer have to infer status from `accuracyScore != null`.
- ~~**Default provider OpenAI**~~ — `application.yml` already defaults to `anthropic` (legacy note dropped).
- ~~**`GitTool.createBranch()` catch-and-recover**~~ — inspected; the `findRef` pre-check + `RefAlreadyExistsException` catch is intentional belt-and-suspenders for a JGit ref-DB race after thread interrupt. Not debt; kept as-is.
- ~~**Pilot v3.0 four deferrals**~~ — repo SHA tracking, multi-repo PR creation, scheduled stack refresh + version field, and LLM-backed assessment all shipped in commit `6bbfde9b`.
