# ExecuteSpec — Codemap

> Structural index of `src/main/java/com/navneet/executespec/` as of 2026-04-25.
> Companion to [ARCHITECTURE.md](ARCHITECTURE.md) (deep-dive) and [JAVADOC.md](JAVADOC.md) (class-level reference).
> When a new top-level package is added, update this file and `document_map.md`.

## Top-level layout

```
src/main/java/com/navneet/executespec/
├── ExecutespecApplication.java          # Spring Boot entry point
├── v2/                                  # V2 harness — the only active pipeline
├── agent/                               # V1 surface still used by chat/test-gen
├── analytics/                           # PostHog activation funnel (Wave 2)
├── api/                                 # Controllers + DTOs + advice (REST surface)
├── apitests/                            # API test suite agent
├── assessment/                          # Pre-plan feature assessment
├── auth/                                # JWT + OAuth2 + RBAC + tier permissions
├── billing/                             # Razorpay + Stripe + lifecycle (Wave 1)
├── codeintel/                           # Codemaps + architecture docs + Generate Docs
├── compliance/                          # Data retention / GDPR
├── config/                              # Feature flags, secrets, observability
├── contact/                             # Public contact form
├── context/                             # ContextAgent (legacy V1 helper)
├── crypto/                              # KMS / encryption-at-rest
├── deployment/                          # Cloud Run + Docker preview/deploy
├── eval/                                # Model evaluation framework
├── execution/                           # Execution View (multi-repo, multi-solution)
├── harness/                             # V1 primitives reused by V2 (workspace, gates)
├── integration/                         # Slack, JIRA, Confluence, GitHub, Bitbucket, ScmProvider
├── memory/                              # Project memory + chat
├── notification/                        # In-app notifications
├── onboarding/                          # Sandbox + sample specs (Wave 2)
├── optimiser/                           # Prompt optimiser
├── pricing/                             # Model pricing matrix
├── project/                             # Project workspace (Pilot v3.0)
├── ratelimit/                           # Per-tenant Redis-backed rate limiter
├── repo/                                # GitHub + Bitbucket repo APIs
├── report/                              # Validation report (entity, PDF, share)
├── reviewer/                            # Accuracy assessment (V1 surface)
├── router/                              # LlmProvider, ModelResolver, smart routing
├── run/                                 # Run sync helpers
├── saga/                                # Side-effect outbox (compensation)
├── scoring/                             # 6-dimension scoring + test-coverage rescorer
├── security/                            # Sanitizers, abuse scanner, guardrail levels
├── sse/                                 # SSE channel — RunEvent, broadcaster
├── state/                               # StateStore + JPA entities for runs/tasks
├── tenant/                              # Tenant filter + interceptor + thread context
├── testgen/                             # Test generation orchestrator + agents
├── tool/                                # V1 tools (GitTool, FileSystemTool, etc.)
└── validator/                           # Code validator + execution gate
```

---

## V2 harness — `v2/`

The V2 pipeline is the only active path. V1 was deleted in deprecation P1–P6 (2026-04-13).

| Subpackage | Key files | Purpose |
|------------|-----------|---------|
| `v2/agent/` | `PlannerAgentV2`, `WorkerAgentV2`, `LlmRouterV2`, `OpenAiNativeToolClient`, `GeminiNativeToolClient` | Planning + per-task worker loop + native-tool LLM dispatch |
| `v2/tool/` | `ToolRegistryV2`, `ReadFileTool`, `WriteFileTool`, `EditFileTool`, `ListFilesTool`, `SearchFilesTool`, `ShellTool`, `GitStatusTool`, `GitDiffTool`, `GitCommitTool`, `BuildProjectTool`, `CompleteTaskTool` | 10 tools + native termination |
| `v2/harness/` | `SequentialExecutorV2` | Per-task tier-routed worker dispatch + smoke + retry |
| `v2/reviewer/` | `ReviewerAgentV3` | General + wiring passes (project-type-aware) |
| `v2/integration/` | `V2RunOrchestrator` | Entry point: planner → gate → executor → reviewer → terminal |
| `v2/context/` | `ContextStoreV2` | Redis snapshot per task (24h TTL, every 5 iters) |
| `v2/cost/` | `V2RunCostTracker` | Per-call token + $ accumulator (Anthropic cache-aware) |
| `v2/observability/` | `V2Metrics` | `executespec.v2.*` Micrometer meters |
| `v2/recovery/` | `StartupRunResumerV2` | Boot-time fail-cleanly for non-terminal V2 runs |
| `v2/smoketest/` | `CliSmokeRunner` | CLI smoke runner |
| `v2/test/` | (V2 test scaffolding) | V2 test plumbing |
| `v2/batch/` | (skeleton; see `pending-v2-batch-execution-plan.md`) | Batch execution — not yet implemented |
| `v2/chat/`, `v2/compiler/`, `v2/rollout/` | (auxiliary) | Internal helpers |

Entry point: `V2RunOrchestrator.submitRun(...)` — see [ARCHITECTURE.md §3 V2 pipeline](ARCHITECTURE.md).

---

## Auth + tenancy + security — `auth/`, `tenant/`, `security/`, `crypto/`, `ratelimit/`

| Package | Highlights |
|---------|------------|
| `auth/controller/` | `AuthController`, `OrgController`, `ProjectController`, `OAuth2*Controller` |
| `auth/service/` | `AuthService`, `OrgService`, `ProjectService`, `InvitationService`, `TierEnforcementService`, `TierPermissionService`, `JwtService` |
| `auth/security/` | `SecurityConfig`, `JwtUtil`, `JwtSecretValidator`, `RunAccessGuard` |
| `auth/oauth/` | `OAuthSuccessHandler`, provider clients |
| `auth/entity/` | `AppUser`, `Org`, `OrgMember`, `Project`, `ProjectMember`, `Invitation`, `TierConfig`, `BillingPlan` |
| `tenant/` | `TenantContext`, `TenantFilter`, `TenantInterceptor`, `TenantContextThreadLocalAccessor` |
| `security/` | `InputSanitizer`, `CodeAbuseScanner`, `ContextSanitizer`, `KeyEncryptor`, `guardrail/` (P1–P5 guardrail levels) |
| `crypto/` | `SimpleKeyEncryptionService`, `EncryptedValueConverter`, `KeyMigrationRunner` |
| `ratelimit/` | `RateLimitFilter`, `RateLimitPolicy`, `RateLimitProperties` |

---

## Billing + pricing — `billing/`, `pricing/`

| Subpackage | Key files |
|------------|-----------|
| `billing/controller/` | `BillingController`, `WebhookController`, `TierLifecycleController` (Wave 1 — `/api/admin/tiers`) |
| `billing/service/` | `BillingService`, `InvoiceService`, `PaymentAuditService`, `AnalyticsService` |
| `billing/gateway/` | `PaymentGateway` (interface), `StripePaymentGateway`, `RazorpayPaymentGateway`, `PaymentGatewayRouter` |
| `billing/lifecycle/` | `LifecycleState` enum (LISTED/HIDDEN/SUNSETTING), `TierLifecycleService` |
| `billing/cost/` | `RunCostLog` audit table |
| `billing/scheduler/` | Scheduled billing jobs |
| `billing/entity/` | `BillingProfile`, `BillingSubscription`, `Invoice`, `Payment`, `RunTopupCredit`, `WebhookEvent` |
| `pricing/` | `ModelPricing`, `ModelPricingRepository` (per-provider per-tier $ rates) |

Wave 1 additions (v2.6.1): seat-aware Stripe checkout, `cancelAtPeriodEnd` interface method, `POST /api/org/me/seats/adjust`, TEAM tier ($49/seat, 2-20 seats).

---

## Project workspace — `project/`, `codeintel/`, `assessment/`, `scoring/`, `memory/`

| Subpackage | Key files |
|------------|-----------|
| `project/service/` | `ProjectRepoService`, `WorkspaceHealthService`, `StackDetector`, `StackRefreshJob` |
| `project/entity/` | `ProjectRepo` (ORG/USER scope), `ProjectRepoLink` |
| `project/dashboard/` | `ProjectDashboardController` (aggregated metrics) |
| `project/pr/` | `PullRequestRecord`, `PullRequestRecordService`, `GitHubWebhookController`, `PostMergeCascadeService` |
| `project/rag/` | RAG retrieval + document chunking |
| `project/scores/` | `ProjectScoresController` (score trend feed) |
| `project/estimation/` | `EstimationService` (heuristic per-project bands) |
| `project/techdebt/` | `TechDebtService` |
| `project/template/` | `ProjectSpecTemplate`, `SpecTemplateService` |
| `project/workspace/` | `ProjectWorkspaceService`, `ProjectWorkspaceWarmer` |
| `codeintel/` | `CodeMapGenerator`, `RepoArchitectureGenerator`, `ArchitectureDocGenerator`, `ProjectArchitectureService` (3-step pipeline), `StalenessService`, `ProjectDocGeneratorService` (Generate Docs), `ArchitectureDiffService` |
| `assessment/` | `FeatureAssessmentService` (heuristic + LLM via `assessment.llm.enabled`) |
| `scoring/` | `ReviewScoringService` (6 dimensions), `TestCoverageRescorer` |
| `memory/` | `RunLedgerAgent`, `MemoryCompactionService`, `ProjectMemoryProvider`, `ProjectChatService` |

---

## Run lifecycle — `state/`, `report/`, `sse/`, `notification/`

| Package | Key files |
|---------|-----------|
| `state/` | `StateStore`, entities `PipelineRun`, `AgentTask`, `RunRepoSnapshot`; `InboxService` |
| `report/` | `ValidationReportService`, `ValidationReportGenerator`, `ValidationReportPdfService`, `ValidationReportController` |
| `sse/` | `RunEvent`, `SseBroadcaster`, `RunDetailSseService` |
| `notification/` | `Notification`, `InAppNotificationService`, `NotificationController`, `RunCompletionNotifier` |

---

## Execution View — `execution/`

ENTERPRISE + MAX project-level workbench for multi-repo, multi-solution work.

| Subpackage | Key files |
|------------|-----------|
| `execution/controller/` | `ExecutionController` |
| `execution/service/` | `ExecutionOrchestrator`, `ExecutionPlannerService`, `SolutionExecutionService`, `ExecutionBranchService`, `CrossRepoContextService`, `IncrementalCodeMapService`, `SolutionDependencyResolver`, `FileConflictDetector`, `AdminReconciliationService` |
| `execution/entity/` | `ExecutionRun`, `ExecutionSolution`, `ExecutionSolutionRepo`, `ExecutionContextHandover`, `ExecutionSolutionRun` |
| `execution/event/` | `ExecutionEventTypes` (`EXEC_*` SSE events) |

---

## Integrations + SCM — `integration/`, `repo/`

| Subpackage | Key files |
|------------|-----------|
| `integration/slack/` | `SlackOAuthService`, `SlackNotificationService` |
| `integration/jira/` | `JiraAdapter` (inbound webhook + outbound comments) |
| `integration/confluence/` | `ConfluenceAdapter` |
| `integration/bitbucket/` | Bitbucket adapter |
| `integration/pagerduty/`, `integration/servicenow/` | Outbound alerts + webhook with HMAC |
| `integration/scm/` | `ScmPushService.pushMultiRepo` |
| `integration/context/` | Integration context shared types |
| `integration/IntegrationManager` | Webhook router + listens for `MergeCompleted` |
| `repo/` | `GitHubRepoService`, `BitbucketRepoService`, `ScmProvider` |

---

## Deployment + preview — `deployment/`

| Subpackage | Key files |
|------------|-----------|
| `deployment/preview/` | `PreviewService` (dual-mode: docker/cloud-run), `CaddyProxyManager`, `DevDockerfileGenerator`, `PreviewProperties` |
| `deployment/strategy/` | Per-project-type Dockerfile strategies |
| `deployment/engine/` | Deploy execution engines |
| `deployment/credential/` | Cloud Run credential management |
| `deployment/analyzer/` | Workspace analyzers |
| Top-level | `DeploymentService`, `CloudRunClient`, `ArtifactRegistryClient`, `DockerfileGenerator`, `WorkspaceArchiveJob`, `WorkspaceExportService`, `WorkspaceLockService`, `WorkspaceRestoreService`, `WorkspaceGcsUploadListener`, `GcsWorkspaceStore`, `PreviewAgent`, `DeploymentCleanupJob`, `DeploymentGitHubService` |

---

## LLM router — `router/`, `agent/`

| Package | Key files |
|---------|-----------|
| `router/` | `LlmProvider` enum (ANTHROPIC, OPENAI, GEMINI), `ModelResolver`, `ModelMatrixProperties`, `ProviderHealthTracker` (Redis circuit breaker), `ProviderRecommendationService` (smart routing scaffold, Wave 4), `ModelRegistry`, `ResolvedModel`, `RunProviderPinStore` |
| `agent/` | `LlmRouter` (V1 — still used by `AccuracyAssessmentService`, `ConversationAgent`, test-gen), `PromptAssembler`, `ApiKeyResolver`, `RunKeyStore`, `CostEstimator`, `FailurePatternService`, `TokenBudgetMonitor`, `TokenBudgetPolicy`, `PromptAudit`, `ConflictResolverAgent` |

---

## Test generation — `testgen/`, `apitests/`

| Package | Key files |
|---------|-----------|
| `testgen/` | `TestGenOrchestrator`, `TestPlannerAgent`, `TestWorkerAgent`, `TestExecutionService`, `TestPreviewCoordinator`, `TestProjectScaffold`, `TestCompilationGate`, `TestReportGenerator`, `TestResultController`, `HeadlessTestExecutor`, `SurefireReportParser`, `ApiProber`, `ApiSurfaceExtractor`, `ApiConventionsGenerator` |
| `apitests/` | `ApiTestSuiteAgent`, `ApiTestSuiteProperties` |

---

## Activation + onboarding — `analytics/`, `onboarding/`, `contact/`

| Package | Key files |
|---------|-----------|
| `analytics/` | `EventName` (6-event funnel), `UserActivationEvent`, `EventTrackingService`, `PostHogClient` |
| `onboarding/` | `OnboardingController`, `SampleSpec`, `SampleSpecRegistry`, `SandboxProvisioningService`, `SandboxCleanupJob` |
| `contact/` | `ContactController`, `ContactService` (public contact form) |

---

## Cross-cutting — `config/`, `optimiser/`, `eval/`, `compliance/`, `saga/`, `validator/`

| Package | Key files |
|---------|-----------|
| `config/` | `AgentModelConfig`, `AgentProperties`, `ConfigAdminController`, `SecurityProperties`, `ObservabilityConfig`, `CustomMetricsConfig`, `GcpOtelExporterConfig`, `GcsRuntimeConfig`, `PromptVersioningService`, `featureflag/` (DB-backed flags + Redis pub/sub), `datasource/` |
| `optimiser/` | `PromptOptimiserAgent`, `PromptAuditService`, `PromptPatternAnalyser`, `PromptPatterns` |
| `eval/` | `EvalController`, `ModelEvaluator`, `EvalReport`, `EvalScorer`, `LlmJudgeValidator`, `PlannerScaffoldValidator`, `EvalTestCase`, `EvalTestCaseRegistry`, `PlannerScaffoldTestCase`, `EvalResult` |
| `compliance/` | `DataRetentionService` (GDPR soft-delete + retention policy) |
| `saga/` | `SideEffectExecutor`, `SideEffectOutbox`, `SideEffectOutboxEntry`, `SideEffectOutboxRepository` |
| `validator/` | `CodeValidator` + `CodeValidatorImpl`, `execution/`, `gate/`, `language/`, `prompt/`, `model/` |

---

## Resources

| Path | What's there |
|------|--------------|
| `src/main/resources/application.yml` | All Spring config: model matrix, failover order, model pricing, provider pool, feature flags, billing gateway, security |
| `src/main/resources/application-prod.yml` | Prod overrides — Stripe default gateway, env-gated billing flags |
| `src/main/resources/db/migration/` | Flyway V1–V130 (V130 hierarchical_planner uncommitted on `feature/hierarchical-planner`) |
| `src/main/resources/prompts/` | All LLM prompt files. `prompts/v2/planner.md`, `prompts/v2/worker.md`, `prompts/fragments/`, `prompts/scaffolds/` |
| `src/main/resources/sample-specs/` | 3 onboarding samples (web-api, frontend, cli) loaded by `SampleSpecRegistry` |
| `src/main/resources/static/` | Frontend bundle (built by `npm --prefix frontend run build`) |
| `src/main/resources/templates/email/` | 17 email templates |
| `src/main/resources/templates/report/` | `validation-report-pdf.html` |

---

## Frontend

| Path | What's there |
|------|--------------|
| `frontend/src/main.tsx` | Vite entry |
| `frontend/src/App.tsx` | Routing + PostHog init + theme bootstrap |
| `frontend/src/lib/api.ts` | Single API client — all REST calls live here |
| `frontend/src/lib/analytics.ts` | posthog-js wrapper + signup attribution |
| `frontend/src/tokens.css` | Design tokens (Graphite+Signal palette, 4-band score colors, semantic palette) |
| `frontend/tailwind.config.ts` | Tailwind extension reading the tokens |
| `frontend/src/stores/useUiStore.ts` | Theme slice, sidebar state |
| `frontend/src/components/runs/` | Run-detail panels (`ValidationReportPanel`, `ScoreCard`, `ScoreRing`, `SignalBreakdown`, `MobilePreviewPanel`, `FailureDecisionModal`) |
| `frontend/src/components/project/` | Project-workbench panels (`ProjectMemoryPanel`, `WorkspaceHealthPanel`, `TechDebtPanel`, `GeneratedDocsPanel`, `PostMergeBar`) |
| `frontend/src/components/billing/` | `PlanCard`, seat-picker UI |
| `frontend/src/components/shared/` | Design-system primitives (`Button`, `Checkbox`, `Radio`, `ResponsiveModal`, `EstimateChip`, `MarkdownContent`) |
| `frontend/src/pages/` | Top-level routes — `RunsPage`, `RunDetailPage`, `ExecSummaryPage`, `PlansPage`, `IntegrationsPage`, `SharedReportPage`, `onboarding/WelcomePage`, `admin/TierLifecyclePage` |

---

## Migrations index

V1–V130 across the lifetime of the project. Recent ranges:

| Range | Theme |
|-------|-------|
| V120 | LlmProvider GOOGLE → GEMINI rename |
| V121–V124 | TEAM tier + 3-state lifecycle (Wave 1) |
| V125–V126 | Activation funnel + sandbox onboarding (Wave 2) |
| V127 | SSO/SAML + GitHub App schema (Wave 3) |
| V128 | Smart provider routing scaffold (Wave 4) |
| V129 | Startup program + 14-day trial (Wave 5) |
| V130 | Hierarchical planner (in progress, not yet committed) |

Older ranges are documented in `JAVADOC.md §21.1 Flyway Migrations` and `DEPLOYMENT.md`.

---

## Where to start reading

| If you're trying to… | Start at |
|----------------------|---------|
| Understand the V2 run pipeline | `v2/integration/V2RunOrchestrator.java` |
| Debug a planner issue | `v2/agent/PlannerAgentV2.java` + `prompts/v2/planner.md` |
| Add or modify an LLM tool | `v2/tool/` (subclass `Tool`) + register in `ToolRegistryV2` |
| Add a new REST endpoint | `api/controller/` + register in OpenAPI spec |
| Add a new feature flag | `config/featureflag/FeatureFlagService` + seed migration |
| Trace billing flow | `billing/service/BillingService` → `billing/gateway/PaymentGatewayRouter` |
| Understand multi-repo project | `project/service/ProjectRepoService` + `codeintel/ProjectArchitectureService` |
| Wire a new integration | extend `OutboundAdapter`/`InboundAdapter` under `integration/<name>/` |
| Touch run state | `state/StateStore` + entities under `state/entity/` |
