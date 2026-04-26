# ExecuteSpec — Codemap

> Structural index of `src/main/java/com/navneet/executespec/` as of 2026-04-25.
> Companion to [ARCHITECTURE.md](ARCHITECTURE.md) (deep-dive) and [JAVADOC.md](JAVADOC.md) (class-level reference).
> When a new top-level package is added, update this file and `document_map.md`.
>
> **v2.6.3-track** is on `feature/enterprise-readiness-eval` (49+ commits ahead of `dev`,
> not yet merged). 8 new top-level packages — `folder/`, `activity/`, `contracts/`,
> `standards/`, `adr/`, `arch/invariants/`, `export/`, `notification/pref/` — wired
> by the `ExecutespecFolderWriter` chokepoint with PR vs DIRECT_PUSH dispatch and
> pinned by an ArchUnit caller-allowlist. Schema V131-V139. Server-up sweep
> 2026-04-25 landed clean (3-provider low-band v2-eval all CLEAN, 23/23 enterprise
> endpoints behaving correctly); per-session report archived in the 2026-04-26 cleanup pass.
>
> **v2.6.2-track** is on `feature/hierarchical-planner` (8 commits ahead of `dev`,
> not yet merged). Phase 0 classifier + Phase 1 outline planner + Phase 2
> parallel expander + Phase 3 coherence patcher all shipped on the branch
> along with the legacy `PlannerAgentV2` deletion. The pre-existing root-level
> `CODEBASE_MAP.md` was deleted on the branch as a duplicate of this file —
> this is the only codemap going forward.

## Top-level layout

```
src/main/java/com/navneet/executespec/
├── ExecutespecApplication.java          # Spring Boot entry point
├── v2/                                  # V2 harness — the only active pipeline
├── activity/                            # Customer-facing project activity feed (E1, v2.6.3-track)
├── adr/                                 # ADRs as first-class — frontmatter cache (E3.12, v2.6.3-track)
├── agent/                               # V1 surface still used by chat/test-gen
├── analytics/                           # PostHog activation funnel (Wave 2)
├── api/                                 # Controllers + DTOs + advice (REST surface)
├── apitests/                            # API test suite agent
├── arch/                                #   .invariants/ — architectural invariant checker (E3.14, v2.6.3-track)
├── assessment/                          # Pre-plan feature assessment
├── auth/                                # JWT + OAuth2 + RBAC + tier permissions
├── billing/                             # Razorpay + Stripe + lifecycle (Wave 1)
├── codeintel/                           # Codemaps + architecture docs + Generate Docs
├── compliance/                          # Data retention / GDPR
├── config/                              # Feature flags, secrets, observability
├── contact/                             # Public contact form
├── context/                             # ContextAgent (legacy V1 helper)
├── contracts/                           # Cross-repo contract registry — drift, consumers, coverage (E2, v2.6.3-track)
├── crypto/                              # KMS / encryption-at-rest
├── deployment/                          # Cloud Run + Docker preview/deploy
├── export/                              # Project export bundle — zip of .executespec/ (E4.7, v2.6.3-track)
├── eval/                                # Model evaluation framework
├── execution/                           # Execution View (multi-repo, multi-solution)
├── folder/                              # .executespec/ folder write chokepoint + bootstrap (E1, v2.6.3-track)
├── harness/                             # V1 primitives reused by V2 (workspace, gates)
├── integration/                         # Slack, JIRA, Confluence, GitHub, Bitbucket, ScmProvider
├── memory/                              # Project memory + chat
├── notification/                        # In-app notifications  (subpkg pref/ — per-user pref matrix, E4.5, v2.6.3-track)
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
├── standards/                           # Engineering standards as binding policy — AST/LLM checks + override (E3, v2.6.3-track)
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
| `v2/agent/` | `SmartClassifierAgent`, `OutlinePlannerAgent`, `TaskExpanderAgent`, `CoherencePlannerAgent`, `PlannerOutputUtils`, `PlanStrategy`, `PlanStrategyCircuitBreaker`, `ClassificationResult`, `WorkerAgentV2`, `LlmRouterV2`, `OpenAiNativeToolClient`, `GeminiNativeToolClient` | Hierarchical planner pipeline (classify → outline → expand → coherence) + per-task worker loop + native-tool LLM dispatch. (v2.6.2-track on `feature/hierarchical-planner`; legacy `PlannerAgentV2` + `prompts/v2/planner.md` were deleted in slice 5b. The `hierarchical-planner-plan.md` design doc was archived in the 2026-04-26 cleanup pass now that all 5 slices are shipped on the branch.) |
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

## Enterprise readiness (v2.6.3-track) — `folder/`, `activity/`, `contracts/`, `standards/`, `adr/`, `arch/invariants/`, `export/`, `notification/pref/`

> Entire stripe lives on `feature/enterprise-readiness-eval` (NOT merged to dev/main). Schema V131-V139.
> Plan + slice list + server-up sweep results were captured in `docs/plans/pending-enterprise-readiness-execution-eval.md` and `docs/reports/enterprise-readiness-eval-2026-04-25.md`; both archived in the 2026-04-26 cleanup pass now that all 4 phases are shipped on the branch.

| Package | Key files | Purpose |
|---------|-----------|---------|
| `folder/` | `ExecutespecFolderWriter`, `BootstrapService`, `FolderReadmeGenerator`, `ProjectConfigPathResolver`, `ExecutespecMigrationController` | Single chokepoint for every `.executespec/` mutation in customer git. PR vs DIRECT_PUSH dispatch via `ProjectRepo.prModeEnum()`. 4-state status machine on `project_repo` (MISSING/BOOTSTRAP_PENDING/PR_PENDING/READY). FOLDER_FLAT vs FOLDER_NAMESPACED vs DATABASE decision tree per (project, repo). ArchUnit-pinned to 4 callers. |
| `activity/` | `ActivityEventEmitter`, `ActivityFeedController`, `ActivityFeedRetentionJob`, `ActivityFeedPartitionJob` | Customer-facing project activity feed. Dual-writes to `activity_audit` for AUDIT-tagged events. `activity_feed` partitioned-by-month + 12 partitions seeded 2026-04 → 2027-03. `@Scheduled` + `@SchedulerLock` jobs. |
| `contracts/` | `breaking/BreakingChangeService` + per-kind classifiers (`ProtoBreakingChangeClassifier`, etc.), `consumers/ContractConsumerController`, `coverage/ContractCoverageController`, `detection/` (per-kind detectors), `drift/ContractDriftController`, `injection/` (planner-side hooks) | Cross-repo contract registry — 4 contract kinds (OPENAPI/GRAPHQL/JSON_SCHEMA/PROTO), drift detection, consumer pinning, per-repo coverage rescan. Materialised paths: `.executespec/contracts/<contract>.{yaml,json,proto,graphql}` per producer + `.executespec/contracts/consumed/<contract>.json` per consumer. |
| `standards/` | `EffectiveStandardsResolver`, `OrgStandardRepository`, `ProjectStandardOverrideRepository`, `StandardsViolationRepository`, `ast/` (AST-based checks), `llmreview/` (LLM-augmented review for non-AST), `override/StandardsOverrideController`, `report/StandardsComplianceController` | Engineering standards as binding policy. Org-level + project-level merge with override semantics. Single-violation overrides + standing exceptions, both require ≥30 char rationale. |
| `adr/` | `Adr`, `AdrRepository`, `AdrFrontmatterParser`, `AdrInjectionService` | ADRs as first-class — `.executespec/arch/adrs/<NNNN>-<slug>.md` parsed by frontmatter parser, cached in `adr` table for query speed (status: PROPOSED/ACCEPTED/SUPERSEDED/DEPRECATED). |
| `arch/invariants/` | `ArchInvariant`, `ArchInvariantViolation`, `InvariantChecker`, `ArchInvariantRepository`, `ArchInvariantViolationRepository` | Continuous architectural properties (e.g. "only payment-service writes to the payments table") defined in customer git as `.executespec/arch/invariants.yaml` per repo. `InvariantChecker` runs per-codemap-version. |
| `export/` | `ProjectExportController` | `POST /api/projects/{id}/export` returns zip of every `.executespec/` artefact + DB-only counterparts when `config_storage=DATABASE`. |
| `notification/pref/` | `UserNotificationPref`, `NotificationPrefService`, `UserNotificationPrefRepository` | Per-user × event-type × channel opt-in/out matrix with sensible defaults. Replaces the implicit "everything goes everywhere" fan-out. |

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

Wave 1 additions (v2.6.1): seat-aware Stripe checkout, `cancelAtPeriodEnd` interface method, TEAM tier ($49/seat, 2-20 seats — superseded by V141 ladder consolidation that renamed MAX → TEAM as $29/mo BYOK unlimited).

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
| `agent/` | `LlmRouter` (V1 — still used by `AccuracyAssessmentService`, `ConversationAgent`, test-gen), `PromptAssembler`, `ApiKeyResolver`, `RunKeyStore`, `CostEstimator`, `TokenBudgetMonitor`, `TokenBudgetPolicy`, `PromptAudit` |

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
| `src/main/resources/db/migration/` | Flyway V1–V130 (V130 `hierarchical_planner` committed on `feature/hierarchical-planner`; not yet merged to `dev`/`main`) |
| `src/main/resources/prompts/` | All LLM prompt files. `prompts/v2/{classifier.md, outline-planner.md, outline-planner-skeleton.md, task-expander.md, coherence-planner.md, worker.md}`, `prompts/fragments/`, `prompts/scaffolds/` (legacy `prompts/v2/planner.md` deleted in slice 5b of the hierarchical-planner work) |
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

V1–V143 across the lifetime of the project. Recent ranges:

| Range | Theme |
|-------|-------|
| V120 | LlmProvider GOOGLE → GEMINI rename |
| V121–V124 | TEAM tier + 3-state lifecycle (Wave 1) |
| V125–V126 | Activation funnel + sandbox onboarding (Wave 2) |
| V127 | SSO/SAML + GitHub App schema (Wave 3) |
| V128 | Smart provider routing scaffold (Wave 4) |
| V129 | Startup program + 14-day trial (Wave 5) |
| V130 | Hierarchical planner — `pipeline_run.{plan_strategy, plan_outline_json, classifier_reasoning, {classifier,outline,expansion,coherence}_{input,output}_tokens, _cost_usd}` + `agent_task.{task_type, expansion_status, expanded_at}` + partial index on `plan_strategy`. Committed on `feature/hierarchical-planner`; not yet merged to `dev`/`main`. |
| V142 | Drop dead feature flags `codegen.react_native.enabled` + `pricing.persona-first` (cleanup pass 2026-04-26). |
| V143 | **Product-split discriminator** (Plan 1 #1, on `feature/product-split-prep`, not yet merged to `dev`/`main`). Adds `product_id` ENUM ('vibe', 'executespec') to `pipeline_run`, `project`, `org`, `billing_subscription`, `invoice`, `notification` (backfilled to 'executespec'); adds `app_user_product_access` join table for cross-surface entitlements. Hot-path indexes mirror existing per-table query patterns. Phase 1.0 groundwork — call sites not yet wired (Phase 2). |
| V131–V139 | **Enterprise readiness (v2.6.3-track, on `feature/enterprise-readiness-eval`, NOT yet merged to dev/main)** — V131 4-state `.executespec/` folder status + bootstrap PR url + last synced at on `project_repo` (E1.1) · V132 `executespec_pr_mode` enum (PR / DIRECT_PUSH) on `project_repo` (E1.4) · V133 `activity_feed` partitioned-by-month + 12 partitions seeded 2026-04 → 2027-03 + 4 hot-path indexes (E1.8/9/10) · V134 `project.config_storage` (FOLDER / DATABASE) + `project_config` table for confidential-project DATABASE opt-out (E1.7) · V135 `contract_registry` + `contract_version` + `contract_consumer` + `contract_drift_event` + 3 hot-path indexes including unresolved-drift partial (E2.1) · V136 `org_standard` + `project_standard_override` + `standards_violation` four-table model + ≥30-char rationale validators (E3.1) · V137 `adr` table caches `.executespec/arch/adrs/<NNNN>-<slug>.md` frontmatter for query speed (E3.12) · V138 `arch_invariant` + `arch_invariant_violation` for continuous architectural properties (E3.14) · V139 `user_notification_pref` (per-user × event × channel opt-in/out matrix) (E4.5). |

Older ranges are documented in `JAVADOC.md §21.1 Flyway Migrations` and `DEPLOYMENT.md`.

---

## Where to start reading

| If you're trying to… | Start at |
|----------------------|---------|
| Understand the V2 run pipeline | `v2/integration/V2RunOrchestrator.java` |
| Debug a planner issue | `v2/agent/OutlinePlannerAgent.java` (FULL_PLAN / SKELETON_ONLY modes) + `v2/agent/SmartClassifierAgent.java` (Phase 0) + `v2/agent/TaskExpanderAgent.java` (Phase 2 parallel) + `v2/agent/CoherencePlannerAgent.java` (Phase 3) + `prompts/v2/{classifier, outline-planner, outline-planner-skeleton, task-expander, coherence-planner}.md` |
| Add or modify an LLM tool | `v2/tool/` (subclass `Tool`) + register in `ToolRegistryV2` |
| Add a new REST endpoint | `api/controller/` + register in OpenAPI spec |
| Add a new feature flag | `config/featureflag/FeatureFlagService` + seed migration |
| Trace billing flow | `billing/service/BillingService` → `billing/gateway/PaymentGatewayRouter` |
| Understand multi-repo project | `project/service/ProjectRepoService` + `codeintel/ProjectArchitectureService` |
| Wire a new integration | extend `OutboundAdapter`/`InboundAdapter` under `integration/<name>/` |
| Touch run state | `state/StateStore` + entities under `state/entity/` |
