# ExecuteSpec Codebase Map

> Quick reference: every class → one-line purpose. Keep updated.
> Last updated: 2026-04-16 (v2.5.11 — Validation v3 Pillar 1: planner requirement graph; v2.5.10: verified score + status_reason; v2.5.9: cross-provider eval hardening)
>
> **v2.5.11 additions (Validation v3 / Pillar 1, PR #21):** `v2/agent/RequirementSpec.java` (new record — `{id, text, kind}` where `kind ∈ {FUNCTIONAL, NON_FUNCTIONAL, TEST, CONSTRAINT}`). `PlanV2.requirements` + `PlannedTaskV2.meetsRequirements` fields. `PlannerAgentV2.validatePlan(plan)` + one-retry-with-feedback on violation; second failure → `PLAN_COVERAGE_INVALID`. `prompts/v2/planner.md` gains Step 1 requirement extraction + invariant #9. Feature flag `validation.v3-strict` (default OFF in prod). `V2RunOrchestrator.persistPlan/persistTasks` serialize the graph. `PipelineRun.planRequirementsJson` + `AgentTask.meetsRequirementsJson` columns. `PlannerAgentV2ValidationTest` — 9 tests. Migration V109.
>
> **v2.5.10 additions (verified score + honest PARTIAL):** `validation_report.{final_build_passed, tests_passed, tests_total, reviewer_verdict, verified_score}` (V106). `pipeline_run.status_reason` (V107). V108 one-shot backfill reclassifies historical PARTIAL runs. `ValidationReportService.computeVerifiedScore(...)` — deterministic formula (`base40 + build20 + round(20*pass/total) + verdict + −5×unfixedCritical`). `ReviewScoringService.score(...)` accepts new `verifiedScore` parameter. `V2RunOrchestrator.resolveTerminalStatus()` — 7-branch decision tree for status + reason. `RunEvent.complete(runId,status,reason)` overload. `AccuracyAssessmentService` prompt — evidence citations required. Feature flag `scoring.v2-verified`. Frontend: new `SignalBreakdown.tsx` chip, `StatusBadge.tsx` tooltip, `ValidationReportPanel.tsx` primary/secondary score split, DTO additions in `api.ts`.
>
> **v2.5.9 additions (cross-provider eval hardening):** `ExecutionPlannerService` fixes: (1a) reads `run.aiProvider`, (1b) `FallbackMode.NO_FALLBACK`, (2) edge dedup + batch save. `V2RunOrchestrator.defaultModelFor(gemini)` → `gemini-3-flash-preview`. `PlannerAgentV2.salvagePlanFromText()` regex fallback. `LlmRouter` emits `RUN_PROVIDER_SWITCHED` SSE on fallback. System property `-Dexecutespec.strict-provider=true`. Known tech debt: `ModelResolver.selectAdaptiveProvider` still bypasses strict-mode (Bug #1c, not yet fixed).
>
> **v2.5.1 additions:** `project/pr/PrRefreshService` + `PostMergeCascadeService` (poll GH + invalidate staleness + regen arch + notification on OPEN→MERGED). `run/sync/RunSyncService` (hard-reset workspace from origin/<branch>; `POST /api/runs/{id}/sync`). `POST /api/executions/{id}/resume` on `ExecutionController` (re-kick executeApprovedPlan without redoing prior steps). `POST /api/projects/{id}/prs/refresh` on `PullRequestController`. `ExecutionPlannerService` name-match fallback for non-UUID `repoId`. `GitHubRepoService.listRepoFiles` / `readRepoFile` gain branch param. Frontend: `ArchitectureViewer` "Diff vs v{N-1}" LCS renderer + branch input; `ProjectPrRollup` Refresh button; `InlineRunDetail` Sync button; `ExecutionViewPage` persistent Cancel; `ExecutionMonitor` Resume button; `ExecutionSetup` shows repo githubRepoFullName. `spring.jpa.open-in-view=true`. Migrations V96/V97 (auto-push flag), V98 (execution_* id lengths + RLS drop-recreate).
>
> **v2.5.0 additions (Project Workspace + RAG):** `project/workspace/ProjectWorkspaceService` owns `workspace/projects/<id>/` (codemaps/, architecture.md, summary.md, docs/, .git) with Redis-locked atomic writes. `codeintel/ProjectBriefService` summarises arch into 2-4 sentences via LIGHTWEIGHT-tier LLM and builds the `{{PROJECT_BRIEF}}` planner block. `ProjectArchitectureService.generate()` extended to write codemaps + architecture.md + summary.md to disk. `POST /api/projects/{id}/analyse` + `GET /api/projects/{id}/summary` on `ProjectArchitectureController`. `V2RunOrchestrator` does pre-execution lazy refresh via `StalenessService` and post-success staleness invalidation. `ReadFileTool` accepts `project:` prefix with DB-authoritative fallback (`readFromDbFallback`). RAG package `project/rag/`: `DocumentExtractor` (Tika), `ChunkingService` (500/50-token, code-fence aware), `EmbeddingService` (Spring AI OpenAI text-embedding-3-small), `ChunkStore` (JDBC over pgvector), `ProjectDocumentService` (async UPLOADED→EXTRACTING→EMBEDDING→READY), `ProjectDocumentController` at `/api/projects/{id}/documents`, `RetrievalService` wired into `PlannerAgentV2` (`{{REFERENCE_DOCS}}`) and `ProjectChatService`. Frontend: `ProjectDocumentsPanel` (Docs tab), Architecture diff button (LCS line diff), Project Overview Summary panel + Analyse button. Auto-push flag flipped ON. `MAX_MEMORY_TOKENS` 8K→12K. Migrations V95 (pgvector + project_document + project_document_chunk), V96/V97 (auto-push flag).
>
> **v2.4.0 additions (Project Shell + Launch):** `ProjectLaunchController` (project-level rollup endpoints), `ScaffoldController` (`/api/scaffolds`), `ExecutionSolutionRun` entity + repo (multi-attempt history), `ProjectRepoService.createAndLinkNewRepo` (new-repo-at-Launch), enhanced `ExecutionSetup` with new-repo sub-form + dry-run modal + owner validation, `SolutionSection` (replaces flat `SolutionCard` with expandable per-attempt history linking to `RunDetailPage`), `ProjectLaunchesList` + `ProjectPrRollup` tabs on `ProjectDashboardPage`. Migration V90.
>
> **Execution View (`execution/`, v2.3.0):** `ExecutionOrchestrator` + `ExecutionPlannerService` + `SolutionExecutionService` + `ExecutionBranchService` + `CrossRepoContextService` + `IncrementalCodeMapService`; entities `ExecutionRun`/`ExecutionSolution`/`ExecutionSolutionRepo`/`ExecutionContextHandover`; pure logic `SolutionDependencyResolver` + `FileConflictDetector`. REST at `/api/projects/{id}/executions` + `/api/executions/{id}/...`. Feature-flag gated (`execution.view.enabled`) + ENTERPRISE-tier gated. Frontend: Execution View page + 13 components under `components/execution/`. Migration V89.
>
> **Project memory (`memory/`, v2.2.0):** `RunLedgerAgent` (post-complete, LIGHTWEIGHT tier) → `project_run_ledger`; `MemoryCompactionService` rewrites `project_memory_snapshot` every 10 runs (starting R20); `ProjectMemoryProvider.buildPlannerContext(projectId)` injects `{{PROJECT_MEMORY}}` into `PlannerAgentV2`. `ProjectMemoryController` at `/api/projects/{id}/memory[/ledger]`. Frontend: Memory tab on `ProjectDashboardPage` → `ProjectMemoryPanel`. Migration V88.
>
> **Note:** V2 is the default pipeline. Live V2 packages: `v2/integration/V2RunOrchestrator`, `v2/agent/PlannerAgentV2` + `WorkerAgentV2` + `LlmRouterV2`, `v2/harness/SequentialExecutorV2`, `v2/reviewer/ReviewerAgentV3`, `v2/tool/*`, `v2/context/ContextStoreV2`, `v2/cost/V2RunCostTracker`, `v2/recovery/StartupRunResumerV2`, `v2/observability/V2Metrics`. Most V1 packages below (`agent/PlannerAgent`+`WorkerAgent`, `harness/PipelineHarness`, `compiler/`, `reviewer/ReviewerAgentV2`, `batch/`) have been deleted per [docs/plans/deprecation-removal-plan.md](docs/plans/deprecation-removal-plan.md). Shared infra (StateStore, CostEstimator, GateStore, UiStyleResolver, WorkspaceInitService, ModelResolver, ApiKeyResolver) stays live under V2.
>
> **Pilot v3.0 (2026-04-14):**
> - `project/` — `ProjectRepoService/Controller`, `ProjectDashboardController`, `ProjectScoresController`, `entity/ProjectRepo` (ORG/USER scope), `entity/ProjectRepoLink`, `service/StackDetector`, `service/StackRefreshJob` (`@Scheduled` + `@SchedulerLock`), `pr/PullRequestRecord` + `Service` + `GitHubWebhookController` + `PullRequestController`
> - `codeintel/` — `CodeMapGenerator`, `ArchitectureDocGenerator`, `ProjectArchitectureService`+`Controller`, `StalenessService` (Caffeine 5-min), `RunCodeMapService` (paid-tier per-run codemap)
> - `assessment/` — `FeatureAssessmentService` (heuristic + LLM via `assessment.llm.enabled` flag), `AssessmentResult`
> - `scoring/` — `ReviewScoringService` (6 dims), `TestCoverageRescorer` (post-test event listener)
> - `integration/scm/ScmPushService.pushMultiRepo` + `tool/GitTool.commitAndPushSubdir` — multi-repo PR + cross-link
> - `harness/WorkspaceInitService.cloneMultipleRepos` — clones into `workspace/<runId>/repos/<name>/`
> - Migrations V81–V87
> - Frontend: `pages/project/ProjectDashboardPage`, `components/project/*`, `components/runs/AssessmentPanel` + `ScoreCard`

## Java (com.navneet.executespec.*)

### agent/ — LLM agents & routing
- `PlannerAgent` — Generates task plans from specs (PLANNING tier)
- `WorkerAgent` — Executes tasks, dispatches tools, 25-min limit, stall escalation. Self-play prevention (strips fabricated `<tool_result>` blocks). XML-first parsing for PATH B (Anthropic/Google)
- `ConversationAgent` — Chat copilot (LIGHTWEIGHT tier)
- `LlmRouter` — Multi-provider fallback (failover order per provider, circuit breaker integration)
- `LlmOutputParser` — Parses structured JSON from LLM responses
- `ApiKeyResolver` — Resolves API keys: user→org→system
- `ResolvedKeys` — Container for resolved credentials per provider
- `TokenBudgetMonitor` — Tracks per-run token budget (200k floor)
- `TokenBudgetPolicy` — Token limits & escalation thresholds
- `RunKeyStore` — Caches resolved keys per run
- `CostEstimator` — Estimates run cost from plan
- `ConflictResolverAgent` — Resolves merge conflicts (WORKHORSE tier)
- `RequestTriageService` — Single-shot fast path for simple requests (LIGHTWEIGHT tier)
- `ApplySuggestionTooLargeException` — Exception for oversized suggestions
- `model/TaskDefinition` — Single task in plan
- `model/TaskPlan` — Multi-task plan container (includes `projectType` field declared by planner)
- `model/WorkerReport` — Task execution report
- `model/TaskReview` — Task review verdict
- `model/ReviewReport` — Legacy V1 review (deprecated)
- `model/ReviewIssue` — Review issue detail
- `model/IterationEstimate` — Time/token estimate for retry

### router/ — Model selection & health tracking
- `ModelResolver` — Resolves agentTier x provider → model from YAML matrix
- `ModelMatrixProperties` — ConfigurationProperties for YAML model matrix
- `AgentTier` — Enum: PLANNING/WORKHORSE/LIGHTWEIGHT
- `ResolvedModel` — DTO: requestedProvider, actualProvider, model, agentTier, fallback
- `ProviderHealthTracker` — Redis-backed circuit breaker (5 failures → open, 60s TTL recovery)
- `LlmProvider` — Enum: ANTHROPIC/OPENAI/GOOGLE (with `.getTag()` for UI badges)
- `AvailableProviderResolver` — Resolves available providers per run based on API keys

### harness/ — Pipeline orchestration
- `PipelineHarness` — Main orchestrator: plan→work→compile→merge→COMPLETE→preview→background validation. Publishes MergeCompleted for integration outbound. Creates branch+PR for existing repos. Manages `backgroundValidationRuns` set and deferred cleanup. Batch auto-retry: `onBatchComplete()` retries failed batch tasks in real-time via SequentialTaskExecutor before compile
- `SequentialTaskExecutor` — Executes tasks in tiered parallel groups (virtual threads). SubtaskDecomposer splits multi-file tasks. Validation deferred to review phase. Budget checks (LLM calls + USD cost)
- `SubtaskDecomposer` — Decomposes multi-file tasks into per-file subtasks (2+ owned files → N subtasks, each produces one writeFile). Rework tasks excluded
- `ApprovalGate` — Plan approval gate interface
- `GateStore` — Redis-backed gate storage (500ms poll, 2h TTL)
- `GitMergeService` — Merges task branches, progressive merge under `mergeLock`
- `WorkerPool` — Thread pool for workers
- `WorkspaceInitService` — Git repo initialization per run
- `UiStyleResolver` — Design system integration: filesystem DESIGN.md loading, Caffeine cache, fuzzy matching, 3-signal resolution (explicit hint > planner > heuristic), token-budget truncation
- `DesignSystemProperties` — Config: rootPath, cacheTtl, maxTokens, defaultStyle, curatedStyles
- `LanguageValidator` — Blocks unsupported languages at submission
- `RunBudgetTracker` — LLM call budget tracking per run
- `RunCostBudgetService` — USD cost budget enforcement per tier
- `SingleShotExecutor` — Fast-path executor for trivial requests
- `StartupRunResumer` — (DISABLED) Auto-resumes stuck runs
- `ExecutionMode` — Enum: PROTOTYPE/PRODUCTION
- `MergeAnalysis/MergeDecision/MergeOutcome` — Merge decision models
- `MergeBlockedException` — Merge conflict exception
- `event/` — Pipeline event models (MergeCompleted, TestsReady, TestExecutionCompleted)

### compiler/ — Compilation
- `CompilerAgent` — Compiles full project (3 rounds), fixes errors (WORKHORSE tier)
- `CompileResult` — Compilation output model
- `CompilerProperties` — Config
- `DevDockerfileGenerator` — Generates preview Dockerfile (reads `.agent-context/project.json` for planner-declared project type, falls back to file-based heuristics)

### reviewer/ — Code review
- `ReviewerAgentV2` — Inspects merged code, auto-fixes CRITICAL/MINOR issues (PLANNING tier)
- `model/ReviewReportV2` — V2 review with flat issues[]
- `model/ReviewVerdict` — Enum: PASSED/PASSED_WITH_FIXES/NEEDS_ATTENTION/FAILED
- `model/IssueSeverity` — Enum: CRITICAL/MINOR
- `model/IssueCategory` — Enum: LOGIC/STYLE/SECURITY/PERFORMANCE/COMPLETENESS
- `model/FixInstruction` — Per-issue fix instruction

### report/ — Validation reports
- `ValidationReportService` — Aggregates data, generates report, persists
- `ValidationReportGenerator` — LLM-powered report text (WORKHORSE tier)
- `ValidationReportPdfService` — Report → PDF (OpenPDF + Flying Saucer)
- `ValidationReportController` — REST: report/PDF/share/public
- `ValidationReportRepository` — JPA repository
- `UsageReportService` — Usage analytics reports
- `entity/ValidationReport` — JPA entity
- `entity/PipelineResult` — Enum: PASSED/PASSED_WITH_FIXES/NEEDS_ATTENTION/FAILED
- `model/ReportInput` — Report generation input
- `model/ReportIssue` — Aggregated issue
- `model/ReportTestFailure` — Grouped test failure

### validator/ — Code validation gates
- `CodeValidator` — Validation interface
- `CodeValidatorImpl` — Gate chain: SYNTAX→TYPE→LINT→TEST
- `language/JavaToolchain` — Maven/javac/JUnit
- `language/PythonToolchain` — pip/mypy/ruff/pytest
- `language/TypeScriptToolchain` — npm/tsc/eslint
- `language/ReactToolchain` — npm/Vite/vitest
- `execution/ProcessExecutor` — Process spawner
- `execution/*OutputParser` — Per-tool output parsers
- `gate/GateChain` — Pluggable gate chain
- `model/ValidationGate` — Enum: SYNTAX/TYPE/LINT/TEST
- `model/ValidationResult` — Validation result
- `prompt/RetryPromptBuilder` — Retry prompts with error context

### recovery/ — Failure handling
- `RunRecoveryService` — Detects stuck runs (warn: 8min, act: 15min)
- `StuckRunEvaluator` — Classifies stuck run root causes
- `EscalationService` — Model escalation on failures (WORKHORSE → PLANNING)
- `OrphanedRunWatchdog` — Detects and recovers orphaned runs (8min threshold)
- `LlmCallGuard` — Protects LLM calls (timeouts/retries)
- `RecoveryProperties` — Recovery config

### state/ — Run state persistence
- `StateStore` — Central state manager (~1500 lines). Includes `resetTaskForRetry()` (batch retry) and `setTaskModelProvider()` (batch model attribution)
- `InboxService` — User inbox notifications
- `RunTitleBackfillService` — Backfills run titles
- `ApplySource` — Enum: COPILOT/REVIEW
- `entity/PipelineRun` — Run JPA entity (root aggregate). Includes `githubPrUrl` (V52), `archived` (V53)
- `entity/AgentTask` — Task JPA entity. Subtask fields: `parentTaskId`, `subtaskIndex` (1-based), `subtaskTotal`
- `entity/AgentMessage` — Chat message entity
- `repository/*Repository` — JPA repositories

### deployment/ — Preview & deploy
- `preview/PreviewService` — Dual-mode preview (docker/cloud-run, 5-min TTL). Stores `cloudRunServiceName` upfront (prevents orphan cleanup race). Concurrency: FREE=1, PRO=3, ENTERPRISE=10
- `preview/PreviewProperties` — Config: mode, TTL, concurrency, port-range
- `preview/CaddyProxyManager` — Caddy reverse proxy for Docker mode (Cloudflare Origin Certificate)
- `preview/ComposeFileGenerator` — Docker Compose YAML generation
- `preview/ComposeExecutor` — Docker Compose process runner
- `DeploymentService` — Full Cloud Run deployment (11 languages)
- `CloudRunClient` — GCP Cloud Run API wrapper (timeouts: deploy 10min, delete 5min, list 2min)
- `ArtifactRegistryClient` — Docker image build + push (10-min timeout)
- `GcsWorkspaceStore` — GCS workspace archival (24h delay)
- `WorkspaceRestoreService` — On-demand workspace restore
- `DeploymentGitHubService` — GitHub push integration
- `DockerfileGenerator` — Per-language production Dockerfile generation (reads `project.json`)
- `PreviewAgent` — Auto-preview after workers
- `WorkspaceArchiveJob` — Archives workspaces after 24h
- `DeploymentCleanupJob` — Cleans expired deployments
- `analyzer/` — Project analysis for deployment strategy
- `credential/` — Cloud credential management
- `engine/` — Deployment engine implementations
- `strategy/` — Deployment strategy selection

### testgen/ — Test generation
- `TestGenOrchestrator` — Orchestrates full pipeline: plan→generate→compile→execute (language-agnostic)
- `TestPreviewCoordinator` — Coordinates preview readiness + test execution. Reuses existing preview if running. Owns preview lifecycle for tests. Batch runs skip auto-preview (publishes empty event). Supports `triggerManualExecution()` for manual test runs
- `TestPlannerAgent` — Creates test plan from spec (WORKHORSE tier)
- `TestWorkerAgent` — Generates test code per task (WORKHORSE tier)
- `TestReportGenerator` — Generates fix suggestions for failed tests (LIGHTWEIGHT tier)
- `TestCompilationGate` — Test compilation + error fixing loop
- `TestProjectScaffold` — Maven/Gradle test project setup
- `TestExecutionService` — Runs compiled tests
- `TestExecutionOrchestrator` — Orchestrates test suite execution
- `TestResultController` — REST: apply-test-fixes, run-tests (manual test execution)
- `model/*` — Test plan/result/summary entities
- `repository/*` — Test JPA repositories

### auth/ — Authentication, OAuth2 & RBAC
- `security/SecurityConfig` — Spring Security config (JWT, OAuth2, CSP, HSTS, X-Frame-Options)
- `security/JwtUtil` — JWT gen/validation (HS256, 24h access/7d refresh, force-logout epoch check)
- `security/JwtAuthenticationFilter` — JWT HTTP filter
- `security/RunAccessGuard` — Run visibility RBAC
- `service/AuthService` — Register, login, refresh, password reset, email verification
- `service/OrgService` — Org management (create, invite, roles)
- `service/ProjectService` — Project CRUD
- `service/TierEnforcementService` — Tier limit enforcement (run/cost limits per tier)
- `service/TierPermissionService` — Tier feature permissions
- `oauth/OAuthSuccessHandler` — OAuth2 login success → JWT generation (creates user if new)
- `oauth/OAuthUserMapper` — Maps OAuth2 profile to AppUser
- `oauth/OAuthProfile` — OAuth2 user profile DTO
- `oauth/OAuthEmailConflictException` — Email already registered with different provider
- `entity/AppUser` — User entity
- `entity/Org` — Organization entity
- `entity/Project` — Project entity
- `controller/AuthController` — Login/register/logout/refresh/OAuth2/password-reset
- `dto/*` — Auth DTOs

### billing/ — Payments, subscriptions & audit
- `service/BillingService` — Multi-gateway subscription handling (Razorpay + Stripe)
- `service/RunTopupService` — Run credit purchases (FIFO)
- `service/UsageTrackingService` — Usage tracking per org
- `service/InvoiceService` — Invoice generation + GST calculation
- `service/InvoicePdfService` — Invoice PDF generation
- `service/InvoiceEmailService` — Invoice email delivery
- `service/AnalyticsService` — Usage analytics (run costs, token usage, provider breakdown)
- `service/AccountingService` — Accounting entries & reconciliation
- `service/BillingNotificationService` — Usage alerts, upgrade prompts
- `service/PaymentAuditService` — Payment audit trail + CSV export
- `service/GstCalculationService` — GST tax calculation
- `service/PlanSeederService` — Seeds default billing plans
- `service/RunQuotaRefundListener` — Refunds run quota on failure
- `gateway/PaymentGateway` — Payment gateway interface
- `gateway/PaymentGatewayRouter` — Routes to Razorpay or Stripe based on subscription
- `gateway/RazorpayPaymentGateway` — Razorpay implementation
- `gateway/StripePaymentGateway` — Stripe implementation
- `gateway/BillingEntity` — Billing entity model
- `gateway/BillingEntityResolver` — Resolves user vs org billing entity
- `gateway/BillingGateway` — Enum: RAZORPAY/STRIPE
- `gateway/CheckoutResult` — Checkout session result
- `gateway/WebhookResult` — Webhook processing result
- `security/WebhookSignatureVerifier` — Webhook HMAC/signature verification
- `controller/BillingController` — Subscription/payment endpoints
- `controller/BillingWebhookController` — Razorpay + Stripe webhook handlers
- `controller/PaymentAuditController` — Payment audit log + CSV export (SUPER_ADMIN)
- `controller/AccountingController` — Accounting endpoints
- `scheduler/DunningScheduler` — Failed payment retry
- `scheduler/PeriodEndDowngradeScheduler` — Auto-downgrade on subscription end
- `entity/BillingPlan` — Available plans
- `entity/BillingSubscription` — User/org subscription
- `entity/BillingPlanLimit` — Tier-specific limits
- `entity/Invoice` — Generated invoices with line items
- `entity/RunTopup` — Run credit top-ups
- `entity/TopupPackage` — Predefined top-up packages
- `entity/WebhookEventLog` — Webhook event audit trail
- `entity/AccountingEntry` — Accounting line items
- `entity/BillingProfile` — Billing profile (name, address, GST)
- `entity/SellerConfig` — Seller details for invoices
- `entity/InvoiceSequence` — Invoice number sequence
- `entity/PaymentAudit` — Payment transaction audit
- `config/*` — Razorpay/Stripe config

### integration/ — External systems
- `IntegrationManager` — Routes webhooks, listens for MergeCompleted to fire outbound (both PROTOTYPE and PRODUCTION)
- `InboundAdapter/OutboundAdapter` — Adapter interfaces
- `jira/JiraAdapter` — JIRA inbound webhooks + outbound comments/transitions (null-review safe)
- `confluence/ConfluenceAdapter` — Confluence inbound webhooks + outbound child pages (null-review safe)
- `slack/SlackNotificationService` — Slack Block Kit messages
- `servicenow/ServiceNowAdapter` — ServiceNow integration
- `pagerduty/PagerDutyAdapter` — PagerDuty integration
- `github/GitHubRepoService` — GitHub API: `createRepo()`, `pushFiles()`, `getDefaultBranch()`, `createPullRequest()`
- `IntegrationEventLog` — Deduplication entity

### tool/ — Worker tools
- `ShellTool` — Shell command execution (3-layer sandbox, 300s timeout)
- `GitTool` — Git operations. `pushToBranch()` creates branch and pushes for existing-repo runs
- `FileSystemTool` — File read/write/search (`safeResolve()` path boundary check)
- `ToolRegistry` — Available tool registration
- `ToolResult` — Tool execution result

### context/ — External data enrichment
- `ContextAgent` — Extracts JIRA/Confluence context (LIGHTWEIGHT tier)
- `ContextAgentScanner` — Scans for context keywords

### optimiser/ — Prompt optimization
- `PromptOptimiserAgent` — Improves prompts from feedback (WORKHORSE tier)
- `PromptOptimiserProperties` — Config

### config/ — App configuration
- `ModelConstants` — Model name constants
- `ModelPricingInitializer` — Loads model pricing on startup
- `RoutingProperties` — Provider weights
- `AgentProperties` — Agent timeouts/prompts config
- `ObservabilityConfig` — OTel exporter setup
- `CustomMetricsConfig` — Micrometer meters (30+ gauges/timers)
- `WebConfig` — CORS & HTTP config
- `SpaFallbackController` — SPA index.html fallback
- `SecurityProperties` — Configurable CSP, HSTS, X-Frame-Options per environment
- `RedisListenerConfig` — Redis pub/sub listener setup (feature flag invalidation)

### config/featureflag/ — Feature flags
- `FeatureFlag` — JPA entity (flagKey, flagValue, valueType, scope)
- `FeatureFlagService` — DB-backed flags with Redis pub/sub cache invalidation + 60s scheduled reload
- `FeatureFlagController` — Admin CRUD endpoints (SUPER_ADMIN)
- `FeatureFlagRepository` — JPA repository

### ratelimit/ — Rate limiting
- `RateLimitFilter` — Redis-backed per-tenant rate limiting
- `RateLimitPolicy` — Rate limit policy definitions
- `RateLimitProperties` — Config

### security/ — Input security
- `InputSanitizer` — Content-level sanitization for run submissions
- `CodeAbuseScanner` — Scans for prompt injection and malicious patterns

### eval/ — Model evaluation
- `EvalController` — REST: trigger evaluations
- `ModelEvaluator` — Runs evaluation test cases against models
- `EvalScorer` — Scores evaluation results
- `EvalTestCase` — Single evaluation test case
- `EvalTestCaseRegistry` — Registry of test cases
- `EvalReport` — Evaluation report
- `EvalResult` — Individual evaluation result

### api/ — REST controllers
- `controller/RunController` — Run lifecycle CRUD + archive
- `controller/AuthController` — Auth endpoints (login/register/OAuth2/password-reset)
- `controller/OrgController` — Org management
- `controller/BillingController` — Billing endpoints
- `controller/AdminController` — Super-admin endpoints
- `controller/AdminAnalyticsController` — Admin analytics dashboard
- `controller/ModelController` — Model matrix + provider tags
- `controller/PreviewController` — Preview lifecycle
- `controller/RunConversationController` — Chat copilot endpoints
- `RunMapper` — Entity→DTO mapping
- `dto/*` — Request/response DTOs

### sse/ — Server-sent events
- `SseBroadcaster` — Broadcasts events to clients
- `RunEvent` — Event payload model

### tenant/ — Multi-tenancy
- `TenantContext` — ThreadLocal org/user context
- `TenantFilter` — HTTP filter for tenant
- `TenantInterceptor` — MVC interceptor

### pricing/
- `ModelPricing` — Per-model pricing entity

### Root
- `ExecutespecApplication` — Spring Boot entry point

---

## Frontend (frontend/src/)

### pages/
- `DashboardPage` — Org dashboard with metrics
- `RunsPage` — Run list & filters (paginated)
- `BillingPage` — Subscription management (Razorpay + Stripe)
- `SettingsPage` — User settings
- `DeploymentsPage` — Active deployments
- `IntegrationsPage` — JIRA/Confluence/Slack config
- `SharedReportPage` — Public report view (no auth)
- `PricingPage` — Public pricing display
- `PlansPage` — Plan comparison
- `InvoicesPage` — Invoice history
- `AccountingPage` — Accounting entries
- `AuditPage` — Audit trail
- `PrivacyPage` — Privacy policy
- `TermsPage` — Terms of service
- `DpaPage` — Data processing agreement

### pages/admin/
- `AdminPage` — Super-admin panel
- `OrgAdminDashboard` — Org admin dashboard
- `FeatureFlagsPage` — Feature flag management
- `PricingManager` — Plan pricing CRUD
- `GrantRunsModal` — Grant run credits to orgs
- `AdminInviteModal` — Invite admin users

### pages/auth/
- `LoginPage` — Email/password login
- `RegisterPage` — Self-registration
- `AcceptInvitePage` — Accept org invitation
- `ForgotPasswordPage` — Password reset request
- `ResetPasswordPage` — Set new password
- `VerifyEmailPage` — Email verification
- `AuthCallbackPage` — OAuth2 callback handler (Google/GitHub)

### pages/org/
- `OrgProjectsPage` — Org project list
- `OrgSettingsPage` — Org settings & members

### components/runs/
- `RunDetail` — Main run view (gates, tasks, review, report, cost, vibe toggle)
- `RunCard` — Run summary card (includes batch badge for BATCH runs)
- `RunList` — Paginated run list
- `NewRunModal` — Create run form (execution mode, AI provider selection)
- `TaskCard` — Task status card
- `TaskGraph` — Task dependency DAG (Kahn's algorithm, subtask grouping). Subtask nodes (with `parentTaskId`) render as smaller nodes (164×36) grouped under parent with dashed border, expand/collapse chevron, progress bar, aggregate status. Backward compatible
- `TaskGraphGroups` — Groups nodes by source prefix (`_cop{N}_`, `_rev{N}_`) for copilot/review splitting
- `RunDetailTabs` — Tabbed detail view. Overview: PR link, per-copilot token breakdown. Graph: grouped DAGs
- `RunTimeline` — Pipeline status bar (maps COMPILING, MERGING, AWAITING_MERGE)
- `RunConversation` — Chat copilot interface
- `PreviewTab` — Cloud Run/Docker preview + countdown + "Run Tests" button (Beta, visible when preview RUNNING)
- `ValidationReportPanel` — Collapsible validation report
- `TestReportPanel` — Test results visualization
- `TestProgressTracker` — Test gen/exec progress display
- `LiveLog` — Real-time SSE log stream (SCREAMING_SNAKE_CASE event types)
- `VibeRunSubmit` — Simplified run submission (vibe mode)
- `VibeRunSummary` — Vibe mode run summary
- `VibeProgressNarrative` — Mission Control narrative generation
- `FirstRunSuggestions` — Onboarding suggestions
- `TechBadges` — Technology detection badges
- `ProviderModelSelector` — Dynamic AI provider/model picker

### components/billing/
- `PlanCard` — Subscription plan display
- `RazorpayCheckout` — Razorpay payment modal
- `RazorpayOrderCheckout` — Razorpay order checkout
- `UpgradeNudge` — Tier upgrade prompt
- `DisclaimerModal` — Payment disclaimer
- `BillingProfileForm` — Billing profile (name, address, GST)
- `UsageBadge` — Usage indicator
- `PricingTable` — Pricing comparison (with model role display)
- `FeasibilityEstimator` — Run cost estimation

### components/dashboard/
- `MetricCard` — Generic metric card
- `AgentCommGraph` — Agent execution timeline
- `TokenBubbleChart` — Token usage visualization
- `ModelComparisonChart` — Provider/model comparison
- `UserRunsChart` — User run history
- `ActivityFeed` — Recent activity stream
- `DateRangeFilter` — Date range picker

### components/layout/
- `AppLayout` — Main app layout
- `Header` — Top navigation
- `Sidebar` — Side navigation
- `CommandPalette` — Cmd+K command palette

### components/deploy/
- `DeploymentCard` — Deployment status card
- `DeployModal` — Simple deploy dialog
- `DeployWizardModal` — Multi-step deployment wizard

### components/files/
- `FileViewer` — Code file viewer (Monaco)
- `FileTree` — File tree browser

### components/shared/
- `Toast` — Notification toasts
- `SkeletonLoader` — Loading placeholders
- `EmptyState` — Empty state display
- `StatusBadge` — Status indicator
- `MarkdownContent` — Markdown renderer
- `GlassPanel` — Frosted glass UI panel
- `GlowDot` — Animated status indicator
- `ViewModeToggle` — Vibe/standard view switch
- `AnimatedCounter` — Number animation
- `LockedFeature` — Tier-gated feature indicator
- `UpgradeModal` — Upgrade prompt dialog

### components/auth/
- `ProtectedRoute` — Auth-required route wrapper
- `RoleGuard` — Role-based access guard
- `ProjectSelector` — Project context picker
- `UserMenu` — User dropdown menu
- `ChangePasswordModal` — Password change form
- `TierBadge` — Subscription tier badge
- `QuotaUsageBar` — Quota usage progress bar

### components/integrations/
- `IntegrationCard` — Integration status card
- `IntegrationLogo` — Integration icon/logo

### lib/
- `api.ts` — REST API client (~1560 lines, all endpoints). `TaskGraphNode` type includes `parentTaskId`/`subtaskIndex`/`subtaskTotal`. `RunSummary` includes `approvedExecutionMode`. `runTests()` for manual test execution
- `billing-api.ts` — Billing API helpers (Razorpay + Stripe)
- `invoice-api.ts` — Invoice endpoints
- `utils.ts` — Formatting (USD, percentages, dates, status colors)
- `constants.ts` — App constants
- `techDetect.ts` — Language/framework detection
- `telemetry.ts` — OpenTelemetry client initialization
- `vibeNarrative.ts` — Mission Control narrative generation
- `monacoSetup.ts` — Monaco editor setup
- `monacoGeistTheme.ts` — Monaco Geist theme

### hooks/
- `useSse.ts` — SSE connection hook (all event types)
- `useApi.ts` — HTTP request hook with loading/error states
- `useTierPermissions.ts` — Tier feature check
- `useViewMode.ts` — Vibe View toggle state
- `useKeyboardShortcuts.ts` — Global hotkey bindings
- `useKeyboardScroll.ts` — Keyboard scroll navigation
- `useIsMobile.ts` — Mobile detection

### stores/ (Zustand)
- `useAuthStore.ts` — Auth state (user, org, JWT)
- `useRunStore.ts` — Active run state
- `useUiStore.ts` — UI flags (modals, panels)
- `useSseStore.ts` — SSE event buffering
- `useMetricsStore.ts` — Dashboard metrics cache

---

**Total: ~513 Java classes, ~130+ frontend files**
