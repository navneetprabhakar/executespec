# ExecuteSpec Codebase Map

> Quick reference: every class → one-line purpose. Auto-generated, keep updated.

## Java (com.navneet.executespec.*)

### agent/ — LLM agents & routing
- `PlannerAgent` — Generates task plans from specs (Sonnet)
- `WorkerAgent` — Executes tasks, dispatches tools, 15-min limit
- `ConversationAgent` — Chat copilot (CAPABLE tier via ModelRouter)
- `LlmRouter` — Multi-provider fallback (Anthropic→Google→OpenAI)
- `LlmOutputParser` — Parses structured JSON from LLM responses
- `ApiKeyResolver` — Resolves API keys: user→org→system
- `ResolvedKeys` — Container for resolved credentials per provider
- `TokenBudgetMonitor` — Tracks per-run token budget (200k floor)
- `TokenBudgetPolicy` — Token limits & escalation thresholds
- `RunKeyStore` — Caches resolved keys per run
- `CostEstimator` — Estimates run cost from plan
- `ConflictResolverAgent` — Resolves merge conflicts
- `ApplySuggestionTooLargeException` — Exception for oversized suggestions
- `model/TaskDefinition` — Single task in plan
- `model/TaskPlan` — Multi-task plan container (includes `projectType` field declared by planner)
- `model/WorkerReport` — Task execution report
- `model/TaskReview` — Task review verdict
- `model/ReviewReport` — Legacy V1 review (deprecated)
- `model/ReviewIssue` — Review issue detail
- `model/IterationEstimate` — Time/token estimate for retry

### router/ — Model selection
- `ModelResolver` — Resolves agentTier × provider → model from YAML matrix
- `ModelMatrixProperties` — ConfigurationProperties for YAML model matrix
- `AgentTier` — Enum: PLANNING/WORKHORSE/LIGHTWEIGHT
- `ResolvedModel` — DTO: requestedProvider, actualProvider, model, agentTier, fallback
- `ProviderHealthTracker` — Redis-backed circuit breaker (5 failures → open, 60s TTL recovery)
- `LlmProvider` — Enum: ANTHROPIC/OPENAI/GOOGLE
- `AvailableProviderResolver` — Resolves available providers per run

### harness/ — Pipeline orchestration
- `PipelineHarness` — Main orchestrator: plan→work→compile→merge→COMPLETE→preview→background validation (review+test+report). Publishes MergeCompleted event for integration outbound. Creates branch+PR for existing repos via GitTool.pushToBranch()
- `SequentialTaskExecutor` — Executes tasks in tiered parallel groups. Validation deferred to review phase
- `ApprovalGate` — Plan approval gate interface
- `GateStore` — Redis-backed gate storage (500ms poll, 2h TTL)
- `GitMergeService` — Merges task branches, progressive merge
- `LanguageValidator` — Blocks unsupported languages at submission
- `StartupRunResumer` — (DISABLED) Auto-resumes stuck runs
- `MergeAnalysis/MergeDecision/MergeOutcome` — Merge decision models
- `MergeBlockedException` — Merge conflict exception
- `event/` — Pipeline event models

### compiler/ — Compilation
- `CompilerAgent` — Compiles code (3 rounds), fixes errors
- `CompileResult` — Compilation output model
- `CompilerProperties` — Config
- `DevDockerfileGenerator` — Generates preview Dockerfile (reads `.agent-context/project.json` for planner-declared project type)

### reviewer/ — Code review
- `ReviewerAgentV2` — Inspects merged code, auto-fixes (FAST→CAPABLE)
- `model/ReviewReportV2` — V2 review with flat issues[]
- `model/ReviewVerdict` — Enum: PASSED/PASSED_WITH_FIXES/NEEDS_ATTENTION/FAILED
- `model/IssueSeverity` — Enum: CRITICAL/MINOR
- `model/IssueCategory` — Enum: LOGIC/STYLE/SECURITY/PERFORMANCE/COMPLETENESS
- `model/FixInstruction` — Per-issue fix instruction

### report/ — Validation reports
- `ValidationReportService` — Aggregates data, generates report
- `ValidationReportGenerator` — LLM-powered report text (Haiku)
- `ValidationReportPdfService` — Report → PDF
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
- `RunRecoveryService` — Detects stuck runs, triggers recovery
- `StuckRunEvaluator` — Classifies stuck run root causes
- `EscalationService` — Model escalation on failures
- `LlmCallGuard` — Protects LLM calls (timeouts/retries)
- `RecoveryProperties` — Recovery config

### state/ — Run state persistence
- `StateStore` — Central state manager (~1500 lines)
- `InboxService` — User inbox notifications
- `RunTitleBackfillService` — Backfills run titles
- `ApplySource` — Enum: COPILOT/REVIEW
- `entity/PipelineRun` — Run JPA entity (root aggregate). Includes `githubPrUrl` (V52)
- `entity/AgentTask` — Task JPA entity
- `entity/AgentMessage` — Chat message entity
- `repository/*Repository` — JPA repositories

### deployment/ — Preview & deploy
- `preview/PreviewService` — Dual-mode preview (docker/cloud-run, 5-min TTL). Stores `cloudRunServiceName` upfront to prevent orphan cleanup race
- `DeploymentService` — Full Cloud Run deployment
- `CloudRunClient` — GCP Cloud Run API wrapper
- `GcsWorkspaceStore` — GCS workspace storage
- `DeploymentGitHubService` — GitHub push integration
- `DockerfileGenerator` — Per-language Dockerfile generation
- `PreviewAgent` — Auto-preview after workers
- `WorkspaceArchiveJob` — Archives workspaces after 24h
- `DeploymentCleanupJob` — Cleans expired deployments
- `analyzer/` — Project analysis for deployment strategy
- `credential/` — Cloud credential management
- `engine/` — Deployment engine implementations
- `strategy/` — Deployment strategy selection

### testgen/ — Test generation
- `TestGenOrchestrator` — Orchestrates full test gen pipeline (sequential after review)
- `TestPreviewCoordinator` — Coordinates preview readiness + test compilation, starts preview after tests compile, owns preview lifecycle for test execution
- `TestPlannerAgent` — Creates test plan from user spec
- `TestWorkerAgent` — Generates test code per test task
- `TestReportGenerator` — Generates fix suggestions for failed tests
- `TestGenerationAgent` — Generates tests parallel with workers
- `TestExecutionOrchestrator` — Runs test suites
- `TestResultController` — REST: apply-test-fixes
- `model/*` — Test plan/result/summary models
- `repository/*` — Test JPA repositories

### auth/ — Authentication & RBAC
- `security/SecurityConfig` — Spring Security config
- `security/JwtUtil` — JWT gen/validation (24h/7d)
- `security/JwtAuthenticationFilter` — JWT HTTP filter
- `security/RunAccessGuard` — Run visibility RBAC
- `service/TierEnforcementService` — Tier limit enforcement
- `service/TierPermissionService` — Tier feature permissions
- `entity/AppUser` — User entity
- `entity/Org` — Organization entity
- `entity/Project` — Project entity
- `controller/AuthController` — Login/register/logout
- `dto/*` — Auth DTOs

### billing/ — Payments & subscriptions
- `service/BillingService` — Razorpay subscription handling
- `service/RunTopupService` — Run credit purchases (FIFO)
- `service/UsageTrackingService` — Usage tracking per org
- `service/InvoiceService` — Invoice generation + GST
- `entity/*` — Subscription/topup/invoice/usage entities
- `gateway/` — Payment gateway integration
- `scheduler/` — Billing scheduled jobs
- `config/` — Razorpay config

### integration/ — External systems
- `IntegrationManager` — Routes webhooks, listens for MergeCompleted to fire outbound comments (both PROTOTYPE and PRODUCTION)
- `InboundAdapter/OutboundAdapter` — Adapter interfaces
- `jira/JiraAdapter` — JIRA inbound webhooks + outbound comments/transitions (null-review safe)
- `confluence/ConfluenceAdapter` — Confluence inbound webhooks + outbound child pages (null-review safe)
- `slack/SlackNotificationService` — Slack Block Kit messages
- `servicenow/ServiceNowAdapter` — ServiceNow integration
- `pagerduty/PagerDutyAdapter` — PagerDuty integration

### tool/ — Worker tools
- `ShellTool` — Shell command execution
- `GitTool` — Git operations. `pushToBranch()` creates branch and pushes to remote for existing-repo runs
- `FileSystemTool` — File read/write/search
- `ToolRegistry` — Available tool registration
- `ToolResult` — Tool execution result

### context/ — External data enrichment
- `ContextAgent` — Extracts JIRA/Confluence context
- `ContextAgentScanner` — Scans for context keywords

### optimiser/ — Prompt optimization
- `PromptOptimiserAgent` — Improves prompts from feedback
- `PromptOptimiserProperties` — Config (CAPABLE tier)

### config/ — App configuration
- `ModelConstants` — Model name constants
- `ModelPricingInitializer` — Loads model pricing
- `RoutingProperties` — Provider weights (4:3:3)
- `AgentProperties` — Agent timeouts/prompts config
- `ObservabilityConfig` — OTel exporter setup
- `CustomMetricsConfig` — Micrometer meters
- `WebConfig` — CORS & HTTP config
- `SpaFallbackController` — SPA index.html fallback

### api/ — REST controllers
- `controller/RunController` — Run lifecycle CRUD
- `controller/AuthController` — Auth endpoints
- `controller/OrgController` — Org management
- `controller/BillingController` — Billing endpoints
- `controller/AdminController` — Super-admin endpoints
- `RunMapper` — Entity→DTO mapping
- `dto/*` — Request/response DTOs

### sse/ — Server-sent events
- `SseBroadcaster` — Broadcasts events to clients
- `RunEvent` — Event payload model

### tenant/ — Multi-tenancy
- `TenantContext` — ThreadLocal org/user context
- `TenantFilter` — HTTP filter for tenant
- `TenantInterceptor` — MVC interceptor

### repo/ — GitHub
- `GitHubRepoService` — GitHub API: `createRepo()`, `pushFiles()`, `getDefaultBranch()`, `createPullRequest()`

### pricing/
- `ModelPricing` — Per-model pricing entity

### Root
- `ExecutespecApplication` — Spring Boot entry point

---

## Frontend (frontend/src/)

### pages/
- `DashboardPage` — Org dashboard with metrics
- `RunsPage` — Run list & filters
- `BillingPage` — Subscription management
- `SettingsPage` — User settings
- `DeploymentsPage` — Active deployments
- `IntegrationsPage` — JIRA/Confluence/Slack config
- `SharedReportPage` — Public report view (no auth)
- `admin/AdminPage` — Super-admin panel

### components/runs/
- `RunDetail` — Main run view (gates, tasks, review, report, cost)
- `RunCard` — Run summary card
- `RunList` — Paginated run list
- `NewRunModal` — Create run form
- `TaskCard` — Task status card
- `TaskGraph` — Task dependency DAG (accepts optional `nodes` prop for subgraph rendering, Kahn's algorithm for level recomputation)
- `RunDetailTabs` — Tabbed detail view. Overview: PR link, per-copilot token breakdown. Graph: `TaskGraphGroups` with copilot/review splitting
- `RunTimeline` — Pipeline status bar (maps COMPILING, MERGING, AWAITING_MERGE statuses)
- `RunConversation` — Chat copilot
- `PreviewTab` — Cloud Run preview + countdown
- `ValidationReportPanel` — Validation report display
- `TestReportPanel` — Test results
- `LiveLog` — Real-time log stream

### components/billing/
- `PlanCard` — Subscription plan display
- `RazorpayCheckout` — Payment modal
- `UpgradeNudge` — Tier upgrade prompt

### components/dashboard/
- `MetricCard` — Generic metric card
- `AgentCommGraph` — Agent execution timeline
- `TokenBubbleChart` — Token usage visualization

### lib/
- `api.ts` — REST API client (all endpoints)
- `billing-api.ts` — Billing API helpers
- `utils.ts` — Formatting/parsing utilities
- `constants.ts` — App constants

### hooks/
- `useSse.ts` — SSE connection hook
- `useApi.ts` — HTTP request hook
- `useTierPermissions.ts` — Tier feature check

### stores/
- `useAuthStore.ts` — Auth state (user/org/jwt)
- `useRunStore.ts` — Run state
- `useUiStore.ts` — UI state

---

**Total: ~462 Java classes, ~100 frontend files**
