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
├── auth/                                # JWT + OAuth2 + RBAC + tier permissions  (subpkg dsar/ — DSAR data-export endpoint, M-017, Wave 3)
│                                       #   sso/ — SsoConfigController + lockout-prevention guard (M-016, Wave 3)
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
├── governance/                          # Plan-approval policy + project spend cap + repo allowlist (M-032/033/039, Wave 3)
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

## v2.7 Foundation new packages (on `feature/v2.7-foundation`, NOT yet merged)

| Package | Key files | Purpose |
|---------|-----------|---------|
| `governance/` | `GovernanceGuard` | M-032 + M-033 + M-039 — plan-approval cost-policy, per-project monthly spend cap, GitHub repo allowlist. Single service called from `RunController.approvePlan` + `ProjectRepoService.{linkToProject, createAndLinkNewRepo}`. Static helper `ownerAllowedBy(csv, owner)` for tests + direct callers. |
| `auth/dsar/` | `DsarController` | M-017 — `GET /api/dsar/me` (caller self-service) + `GET /api/dsar/users/{userId}` (SUPER_ADMIN act-on-behalf). JSON export with sections `identity` / `payments` / `runs` / `notifications`. Live credentials redacted (we surface `has*Key` booleans instead). Right-to-be-forgotten deletion is a separate workflow. |
| `analytics/` *(extended)* | `ActivationFunnelController` (existing: `EventTrackingService`, `UserActivationEvent`, `EventName`, `PostHogClient`) | M-049 — SUPER_ADMIN-gated `GET /api/admin/activation-funnel?sinceDays=N` returns total + windowed counts per stage in declaration order. Drives the AdminPage Reports-tab funnel panel. |
| `notification/` *(extended)* | `RunCompletionNotifier` (rich-failure-body refresh) | M-053 + M-054 — failure notifications now mirror the completion path: `$0.42 · 4m12s` cost/duration prefix before the failure reason. Skips the prefix on early-stage failures so we don't render `$0.00 · 0s` noise. |
| `auth/sso/` *(extended)* | `SsoConfigController` (lockout-prevention guard) | M-016 — `OFF→ENFORCED` transition blocked with 409 unless the calling admin's session is itself SSO-minted. Prevents the "first ENT customer locks the entire org out" foot-gun. Full SAML2 wire-in spec at `docs/sso-saml-wiring.md`. |
| `adr/` *(extended)* | `AdrController` | M-014 — `GET /api/projects/{projectId}/adrs` + `/{id}` for the existing V137 frontmatter cache. Filters via `?acceptedOnly=true` to mirror the planner-injection set. |
| `arch/invariants/` *(extended)* | `InvariantController` | M-015 — `GET /api/projects/{projectId}/invariants` + `/violations`. Project-scoped joins on the V138 violation rows. |
| `standards/` *(extended)* | `OrgStandardController` | M-024 — full org-level standards CRUD. Was DB-only; now manageable from `/org/settings`. |
| `activity/` *(extended)* | `ActivityFeedRetentionJob` (flag-tunable) | M-038 — `activity.retention.days` feature flag overrides the yml default; clamped [30, 3650]. |
| `auth/service/` *(extended)* | `OrgService.changeOrgTier` (product-id stamp) + `ActivityAuditService.verifyChain` | M-030 — admin tier changes write `org.product_id` via `TierProductSurfaceMapper`. M-019 — append-only hash-chain writer + verifier (V147). |

### v2.7 admin endpoints

| Endpoint | M-ID | Purpose |
|---|---|---|
| `POST /api/admin/runs/halt-all` | M-010 | Cancel every non-terminal run (incident response). |
| `GET  /api/admin/audit-chain/verify` | M-019 + M-042 | Walk the V147 hash chain + report first break. |
| `GET  /api/admin/activation-funnel?sinceDays=N` | M-049 | Headline counts of the V125 6-step funnel. |
| `GET  /api/dsar/me` / `GET /api/dsar/users/{userId}` | M-017 | DSAR JSON export. |
| `GET/POST/PUT/DELETE /api/orgs/{orgId}/standards*` | M-024 | Org-level engineering standards CRUD. |
| `GET  /api/projects/{id}/adrs` + `/{id}` | M-014 | ADR list/detail. |
| `GET  /api/projects/{id}/invariants` + `/violations` | M-015 | Architectural invariants + recent violations. |

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

Wave 1 additions (v2.6.1): seat-aware Stripe checkout, `cancelAtPeriodEnd` interface method, TEAM tier ($49/seat, 2-20 seats). V141 (2026-04-26) consolidated to a 4-tier ladder; V144 (on `feature/product-split-prep`) re-expanded back to 5 tiers — TEAM (per-seat $49) and MAX ($29 BYOK) coexist, with the new `TierProductSurfaceMapper` mapping TEAM → executespec / MAX → vibe.

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

V1–V146 across the lifetime of the project. Recent ranges:

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
| V146 | **Annual billing plans** (Phase 1.3 #32, on `feature/v2.7-foundation`). Adds TEAM/MAX Yearly INR + USD `billing_plan` rows with 17% discount; stamps `annual_discount_pct=17` on every TEAM/MAX row so /plans toggle can show savings on either toggle position. Stripe annual price IDs deferred to ops. |
| V145 | **Loosen FREE tier** (Phase 1.3 #33, on `feature/v2.7-foundation`). `tier_config.FREE.monthly_run_limit` 5 → 10 + description rewrite. `TierEnforcementService.FALLBACK_FREE` constant updated to match. Rationale: matches emergent.sh's free-tier ceiling so we can measure activation honestly without "credit card to try" friction. |
| V144 | **Reinstate per-seat TEAM tier** (on `feature/v2.7-foundation`, not yet merged to `dev`/`main`). Reverses V141's tier consolidation paired with the code-side revert: un-renames TEAM → MAX in `tier_config` / `billing_plan` / `app_user.tier` / `org.tier` / `pipeline_run.user_tier` / `app_user.trial_tier`; re-inserts the per-seat TEAM tier_config (50 credits/seat, 20 max, LISTED) + 2 billing_plan rows (Team Monthly INR ₹3,999/seat + USD $49/seat, included_seats=2) + billing_plan_limit rows. `org.seat_count` (V123) untouched — V141 didn't drop it. Net post-V144 ladder: FREE / PRO (HIDDEN) / TEAM ($49/seat 2-20 seats, → Build surface) / MAX ($29/mo BYOK unlimited, → Vibe surface) / ENTERPRISE. Tier→surface policy enforced by new `TierProductSurfaceMapper`. |
| V131–V139 | **Enterprise readiness (v2.6.3-track, on `feature/enterprise-readiness-eval`, NOT yet merged to dev/main)** — V131 4-state `.executespec/` folder status + bootstrap PR url + last synced at on `project_repo` (E1.1) · V132 `executespec_pr_mode` enum (PR / DIRECT_PUSH) on `project_repo` (E1.4) · V133 `activity_feed` partitioned-by-month + 12 partitions seeded 2026-04 → 2027-03 + 4 hot-path indexes (E1.8/9/10) · V134 `project.config_storage` (FOLDER / DATABASE) + `project_config` table for confidential-project DATABASE opt-out (E1.7) · V135 `contract_registry` + `contract_version` + `contract_consumer` + `contract_drift_event` + 3 hot-path indexes including unresolved-drift partial (E2.1) · V136 `org_standard` + `project_standard_override` + `standards_violation` four-table model + ≥30-char rationale validators (E3.1) · V137 `adr` table caches `.executespec/arch/adrs/<NNNN>-<slug>.md` frontmatter for query speed (E3.12) · V138 `arch_invariant` + `arch_invariant_violation` for continuous architectural properties (E3.14) · V139 `user_notification_pref` (per-user × event × channel opt-in/out matrix) (E4.5). |
| V147 | **Activity-audit hash chain** (M-019, Wave 3, on `feature/v2.7-foundation`). 3 nullable columns on `activity_audit` (`prev_hash CHAR(64)` / `row_hash CHAR(64)` / `chain_seq BIGINT`) + Postgres sequence `activity_audit_chain_seq` + 2 partial indexes for the verifier walk. Each row hashes (prev_hash || canonical row contents); `ActivityAuditService.verifyChain()` walks the chain in seq order and returns the first break for SUPER_ADMIN forensic checks. Pre-V147 rows stay nullable (treated as "pre-chain prefix"). |
| V148 | **Governance caps** (M-032 + M-033 + M-039, Wave 3). 3 nullable columns: `project.spend_cap_usd_monthly NUMERIC(10,2)` (per-project monthly LLM cap), `org.require_admin_approval_above_usd NUMERIC(10,2)` (USD threshold above which only ORG_ADMIN/BILLING_ADMIN/SUPER_ADMIN can approve a plan), `org.allowed_github_owners VARCHAR(1024)` (CSV repo-owner allowlist). Enforced by new `GovernanceGuard` service called from `RunController.approvePlan` + `ProjectRepoService.{linkToProject, createAndLinkNewRepo}`. |
| V149 | **TEAM-BYOK plan** (M-051, Wave 3, parked decision #2). 2 new `billing_plan` rows ("Team Monthly INR (BYOK)" ₹3,199/seat + "Team Monthly USD (BYOK)" $39/seat) with `key_mode='BYOK'`. Same TEAM tier + same product_access set as the managed-key plans; the only differences are the BYOK flag + the lower per-seat price (closes margin risk on heavy users). 3 `billing_plan_limit` rows (RUNS unlimited, PROJECTS=5, STORAGE_MB=5120). Idempotent (NOT EXISTS guards). Annual TEAM-BYOK rows deferred until demand signal. |
| V150 | **Activity retention flag seed** (M-038, Backlog). 1 `feature_flag` row: `activity.retention.days = 365`, PLATFORM scope. Read by `ActivityFeedRetentionJob.effectiveRetentionDays()` first; falls back to `activity.feed.retention.days` from application.yml. Clamped to [30, 3650] in code so a typo can't drop everything or hoard forever. SOC 2 / ISO 27001 customers can adjust without a deploy via the existing `/admin/feature-flags` UI. |

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
