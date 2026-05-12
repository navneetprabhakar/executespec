# ExecuteSpec — Codemap

> Structural index of `src/main/java/com/navneet/executespec/` as of 2026-05-12.
> Companion to [ARCHITECTURE.md](ARCHITECTURE.md) (deep-dive) and [document_map.md](document_map.md) (which doc to update for which change).
>
> **Versioning convention (since the 2026-05-07 unification pass):** no `v2.*` or `v3.*` markers in Java paths or class names. Everything lives flat under `com.navneet.executespec.*`. The legacy router (`agent/FallbackLlmRouter`) survives for non-tool-calling agents only; the canonical `agent/LlmRouter` covers every native-tool-calling agent.
>
> **Style enforcement:** `spotless-maven-plugin` 2.43.0 + `googleJavaFormat` 1.22.0 bound to `mvn verify`. CI runs `mvn -B spotless:check` on every PR (`.github/workflows/lint.yml`). Documented deviations in [`design/code-style.md`](design/code-style.md).
>
> 72 top-level packages, 98 REST controllers, 78 entities, 186 Flyway migrations.

## Top-level layout

```
src/main/java/com/navneet/executespec/
├── ExecutespecApplication.java          # Spring Boot entry point
├── activity/                            # Customer-facing project activity feed (E1, partitioned by month)
├── adr/                                 # ADRs as first-class — frontmatter cache (E3.12)
├── agent/                               # Hierarchical planner pipeline + worker + native-tool LLM router
├── analytics/                           # PostHog activation funnel (6-event ledger)
├── anonrun/                             # Anonymous (no-auth) run flow
├── api/                                 # REST surface — controllers, DTOs, advice
├── apitests/                            # API test suite agent
├── arch/                                #   .invariants/ — architectural invariant checker (E3.14)
├── assessment/                          # Pre-plan feature assessment (heuristic + LLM-gated)
├── auth/                                # JWT + OAuth2 + RBAC + tier permissions (+ dsar/ sso/ data-export)
├── batch/                               # Frozen V1 batch (V2 batch scoped separately — see docs/plans/pending.md §3)
├── billing/                             # Razorpay + Stripe + tier lifecycle + founder pricing
├── chat/                                # Apply-suggestion service (chat → patch dispatch)
├── codeintel/                           # Codemaps + 3-step architecture pipeline + Generate Docs
├── codereview/                          # Whole-repo Code Review agent (semgrep + LLM augmentation)
├── compliance/                          # Data retention / GDPR soft-delete
├── composer/                            # Migration recipe slot validator + spec composer
├── config/                              # Feature flags, secrets, observability config
├── contact/                             # Public contact form
├── context/                             # ContextAgent (legacy non-tool helper) + V2 ContextStore
├── contracts/                           # Cross-repo contract registry — drift, consumers, coverage (E2)
├── cost/                                # RunCostTracker (per-call token + $ accumulator, cache-aware)
├── credit/                              # Credit grant + ledger
├── crypto/                              # KMS / encryption-at-rest
├── deployment/                          # Cloud Run + Docker preview/deploy
├── entitlements/                        # EntitlementResolver (tier rank + project scope)
├── eval/                                # Model evaluation framework
├── execution/                           # Execution View (multi-repo, multi-solution)
├── export/                              # Project export bundle — zip of .executespec/ (E4.7)
├── finding/                             # FindingPatchSubmitter — chokepoint for the 4 autofix surfaces (V183)
├── folder/                              # .executespec/ folder write chokepoint + bootstrap (E1)
├── governance/                          # Plan-approval policy + project spend cap + repo allowlist
├── harness/                             # SequentialExecutor + V1 primitives reused by V2 (workspace, gates, design system)
├── integration/                         # RunOrchestrator + Slack/JIRA/Confluence/Bitbucket/PagerDuty/ServiceNow + ScmPushService
├── landing/                             # Landing page state (legacy v3 surface)
├── lint/                                # Lint autofix flag (V180)
├── memory/                              # Project memory + project chat + chat tools
├── migration/                           # Migration recipe orchestrator (sibling to readymade/)
├── notification/                        # In-app notifications (+ pref/ per-user channel matrix, E4.5)
├── observability/                       # Metrics — executespec.v2.* Micrometer meters
├── onboarding/                          # Sandbox + sample specs (3 starter projects)
├── optimiser/                           # PromptOptimiserAgent
├── pricing/                             # Model pricing matrix DTOs (YAML-derived)
├── programbench/                        # ProgramBench cleanroom-benchmark adapter
├── project/                             # Project workspace (dashboard, estimation, pr, rag, scores, techdebt, template, workspace)
├── prreview/                            # PR Review agent — single-call diff review (PLANNING tier)
├── ratelimit/                           # Per-tenant Redis-backed rate limiter
├── readymade/                           # Readymade Solutions library (W1-W5 shipped on feature/readymades-and-migrations)
├── recovery/                            # StartupRunResumer — boot-time crash recovery
├── referral/                            # Referral codes + lifetime credit grants (legacy v3 surface)
├── repo/                                # GitHubRepoService + BitbucketRepoService + ScmProvider
├── report/                              # ValidationReport entity + PDF + share
├── reviewer/                            # ReviewerAgent (general + wiring) + AccuracyAssessmentService
├── rollout/                             # RolloutRunController (gradual-rollout surface)
├── router/                              # LlmProvider enum + ModelRegistry + ProviderHealthTracker + ProviderRecommendationService
├── run/                                 # Run sync helpers + run-aux services
├── saga/                                # Side-effect outbox (compensation)
├── scoring/                             # 6-dimension scoring + verifiedScore
├── security/                            # Sanitizers, abuse scanner, guardrail levels (+ guardrail/ sub)
├── sharing/                             # External project sharing — cross-org ProjectMember + public share links (V175)
├── smoketest/                           # CliSmokeRunner
├── sse/                                 # SSE channel — RunEvent, broadcaster
├── standards/                           # Engineering standards as binding policy — AST/LLM checks + override (E3)
├── state/                               # StateStore + JPA entities for pipeline_run / agent_task
├── swebench/                            # SWE-bench Lite adapter
├── tenant/                              # Tenant filter + interceptor + thread context
├── test/                                # Test controllers (V2 dev-only)
├── testgen/                             # Test generation orchestrator + agents
├── tool/                                # Worker tools (11 + CompleteTaskTool) + ToolRegistry + ShellSandboxPolicy
├── util/                                # PoolSizing.coreMultiple + small shared helpers
└── validator/                           # Code validator + execution/gate/language/prompt subpackages
```

---

## Agents — `agent/`, `reviewer/`, `testgen/`, `codereview/`, `prreview/`

| Subpackage | Key files | Purpose |
|------------|-----------|---------|
| `agent/` | `LlmRouter`, `FallbackLlmRouter`, `OpenAiNativeToolClient`, `GeminiNativeToolClient`, `SmartClassifierAgent`, `OutlinePlannerAgent`, `TaskExpanderAgent`, `CoherencePlannerAgent`, `PatchAgent`, `WorkerAgent`, `CompleteTaskTool`, `PlanStrategy`, `PlanStrategyCircuitBreaker`, `ClassificationResult`, `PromptAssembler`, `PromptAudit`, `RunCostEstimate`, `CostEstimator`, `PlannerOutputUtils` | The full V2 pipeline brain. `LlmRouter` for native tool-calling agents; `FallbackLlmRouter` for non-tool-calling agents (TestPlannerAgent, ApiTestSuiteAgent, AccuracyAssessmentService, ConversationAgent). Classification fast-path is heuristic; verifier is LLM. Hierarchical planning: full plan for SMALL/MEDIUM, skeleton + expand + coherence for LARGE, recursive depth-2 for LEGACY. `PatchAgent` is the patch-mode single-shot agent. |
| `reviewer/` | `ReviewerAgent`, `AccuracyAssessmentService`, `ReviewIssue`, `ReviewReport` | General + project-type-aware wiring pass over the workspace. Two `WorkerAgent`-backed passes, 30 iter cap each. AccuracyAssessmentService is the LLM read-budget judge (10k char/file, 100k char ceiling). |
| `testgen/` | `TestGenOrchestrator`, `TestPlannerAgent`, `TestWorkerAgent`, `TestExecutionService`, `TestPreviewCoordinator`, `ApiProber`, `ApiSurfaceExtractor`, `TestResultController` | Background test generation pipeline. Plan → generate → compile → execute against live preview. `ApiProber` hits live endpoints first; `ApiSurfaceExtractor` regex-extracts routes for the planner. |
| `codereview/` | `CodeReviewAgent`, `CodeReviewService`, `CodeReviewController`, `CodeReviewAutofixService`, `SemgrepRunner`, `CodeReviewMarkdownRenderer`, entities + repos for `code_review_scan` + `code_review_issue` | Whole-repo Code Review agent. Stage 1 semgrep + Stage 2 LLM triage + markdown report download. Phase-4 autofix dispatch through `FindingPatchSubmitter`. |
| `prreview/` | `PrReviewAgent`, `PrReviewService`, `PrReviewController`, `PrReviewAutofixService`, entity + repos for `pr_review` + `pr_review_comment` | Single-call PR review (PLANNING tier) emits verdict + line-anchored comments. Phase-1 autofix dispatch through `FindingPatchSubmitter`. |
| `apitests/` | `ApiTestSuiteAgent` | API test suite generator (legacy V1 surface, still wired). |

Tier mapping (`application.yml`):

| Tier | Agents |
|------|--------|
| PLANNING | OutlinePlannerAgent, ReviewerAgent, RepoArchitectureGenerator, ArchitectureDocGenerator, ProjectChatCompiler, PrReviewAgent, CodeReviewAgent |
| WORKHORSE | WorkerAgent, TaskExpanderAgent, TestPlannerAgent, TestWorkerAgent, ApiTestSuiteAgent, ProjectDocGeneratorService, ValidationReportGenerator, PromptOptimiserAgent |
| LIGHTWEIGHT | SmartClassifierAgent, CoherencePlannerAgent, ContextAgent, RunLedgerAgent, RunTitleSummarizer, ProjectChatCompactor, ProjectBriefService, ConversationAgent, TestReportGenerator |

---

## Run pipeline — `harness/`, `integration/`, `state/`, `recovery/`, `cost/`, `observability/`, `sse/`, `tool/`

| Subpackage | Key files | Purpose |
|------------|-----------|---------|
| `integration/` | `RunOrchestrator` | Pipeline entry point + finding/PR-mode dispatch + crash-resume entry. Also hosts `scm/ScmPushService` (multi-repo push), `slack/SlackNotificationService`, `jira/JiraAdapter`, `confluence/ConfluenceAdapter`, `pagerduty/PagerDutyAdapter`, `servicenow/ServiceNowAdapter`, `bitbucket/BitbucketAdapter`, `IntegrationManager`. |
| `harness/` | `SequentialExecutor`, `WorkspaceInitService`, `GateStore`, `UiStyleResolver`, `DesignSystemProperties` | Per-task tier-routed dispatch + buildProject smoke + one-retry on smoke fail + soft-completion gate. Workspace + plan-gate primitives reused from the V1 era. |
| `state/` | `StateStore`, `PipelineRun`, `AgentTask`, `RunRepoSnapshot`, repository interfaces | Single source of truth for run + task state. `StateStore.setUserCostCap` / `setHardCapUsd` / `setStatusReason` are the chokepoint mutators. |
| `recovery/` | `StartupRunResumer` | Boot-time crash recovery — AWAITING_APPROVAL stays, salvageable flips to COMPLETE, others call `RunOrchestrator.resumeAfterCrash(runId)`. 3-attempt cap. |
| `cost/` | `RunCostTracker` | Per-call token + $ accumulator. Cache-aware pricing reads YAML `model-pricing[].cached-input-per-1m`. Per-task accumulator keyed by `runId|taskId`. |
| `observability/` | `Metrics`, `CustomMetricsConfig` | `executespec.v2.*` Micrometer meters — `run.{outcome,latency}`, `planner.{latency,tasks}`, `task.{status,latency,smoketest}`, `worker.{iterations,outcome,toolcall}`, `llm.latency`, `reviewer.verdict`, `context.{save,save.bytes}`. |
| `sse/` | `RunEvent`, `SseBroadcaster`, `ExecutionEventTypes` | SSE channel — terminal `RunEvent.complete(runId, status, statusReason)` overload threads the reason for status badge tooltips. |
| `tool/` | `ToolRegistry`, `Tool` interface, all 11 worker tools (`ReadFileTool`, `WriteFileTool`, `EditFileTool`, `ListFilesTool`, `SearchFilesTool`, `ShellTool`, `GitStatusTool`, `GitDiffTool`, `GitCommitTool`, `BuildProjectTool`, `RunTestsTool` — patch-mode only), `ShellSandboxPolicy`, `FileSandboxPolicy`, `ProcessExecutor` | Worker tool bindings. `ToolRegistry.bindForWorker(ctx)` filters `gitCommit` (auto-push at run-end). `ShellTool` + `BuildProjectTool` consult `programbench/ProgramBenchContainerStore` and dispatch via `docker exec` when a container is registered for the run. |
| `smoketest/` | `CliSmokeRunner` | CLI smoke runner. |
| `context/` | `ContextStore` | Redis snapshot per task (24h TTL, every 5 iters). Persists conversation history for replay on crash. |
| `finding/` | `FindingPatchSubmitter`, `Finding` | Chokepoint that the 4 autofix surfaces dispatch through. Dedupes per finding key, sets `runMode=PATCH` + `source="finding:<TYPE>"`. |

---

## Run modes + bench adapters

| Package | Purpose |
|---------|---------|
| `swebench/` | SWE-bench Lite adapter — submits SWE-bench instances as PipelineRuns, captures `git diff HEAD` from the cleanroom container, emits `preds.json`. SUPER_ADMIN-gated `POST /api/admin/swebench/run`. |
| `programbench/` | ProgramBench (Meta cleanroom-rebuild benchmark) adapter. `ProgramBenchContainerStore` (Redis-backed runId→containerId map, 4h TTL). `ProgramBenchBootstrap` creates synthetic Bench Org/User/Project on first run. `ProgramBenchTaskService` does per-task docker create/cp/run + `RunOrchestrator.submitRun`, captures workspace tar at terminal. SUPER_ADMIN endpoint `POST /api/admin/programbench/run`. |

Workspace contract: cleanroom container runs with `--rm --network none -v <hostDir>:/workspace`. The hostDir is the same as the V2 worker's workspace dir, so `Read/Write/Edit/ListFiles` (host fs) and `Shell/BuildProject` (container exec) see identical files.

---

## Project workbench — `project/`, `codeintel/`, `assessment/`, `scoring/`, `memory/`

| Package | Key files | Purpose |
|---------|-----------|---------|
| `project/` | `ProjectService`, `ProjectController`, `project/repo/{ProjectRepoService,…}` (ORG + USER scoped registry), `project/dashboard/ProjectDashboardController`, `project/estimation/EstimationService` (heuristic from last 20 runs), `project/pr/{PullRequestController,GitHubWebhookController,PullRequestRecordService,CiFailureAutoFixService}` (V169-V170 + Phase 3 autofix), `project/rag/{DocumentExtractor,ChunkingService,EmbeddingService,RetrievalService,ProjectDocumentService}` (always-on RAG over pgvector), `project/scores/ProjectScoresController`, `project/techdebt/{TechDebtService,TechDebtFixService}` (Phase 2 autofix), `project/template/{ProjectSpecTemplate,SpecTemplateService}` (V102), `project/workspace/{ProjectWorkspaceService,ProjectWorkspaceWarmer,WorkspaceHealthService}` | Project workspace, repo linking, dashboard aggregator, multi-repo PR + webhook handling, RAG corpus, tech-debt rollup + autofix, spec templates, workspace warmer (DB → disk mirror), workspace-health rollup. |
| `codeintel/` | `CodeMapGenerator`, `RepoArchitectureGenerator` (PLANNING tier — per-repo HLD/LLD), `ArchitectureDocGenerator` (PLANNING tier — cross-repo synthesis), `ProjectArchitectureService.generate()` + `regenerateForRepo()`, `ProjectArchitectureController`, `ProjectDocGeneratorService` + Controller (EXPLANATION + SETUP), `ArchitectureDiffService`, `StalenessService` (Redis-backed cache, project + run-aware overrides) | 3-step architecture pipeline + on-demand Generate Docs + LCS arch diff + per-repo SHA staleness. |
| `assessment/` | `FeatureAssessmentService`, `AssessmentResult` | Pre-plan feature assessment. Heuristic by default; LLM-backed when `assessment.llm.enabled` flag is on. Surfaces complexity + risks + cross-repo deps above the plan-approval gate. |
| `scoring/` | `ReviewScoringService` | 6-dimension scoring (code quality / test coverage / architecture / security / performance / design compliance). Anchors on `verifiedScore` when present; falls back to `accuracyScore` for legacy rows. |
| `memory/` | `RunLedgerAgent` (LIGHTWEIGHT), `MemoryCompactionService`, `ProjectMemoryProvider`, `ProjectMemoryController`, `ProjectChatService` (clear + compact + per-bubble copy), `ProjectChatCompactor` (LIGHTWEIGHT), `ProjectChatCompiler` (PLANNING), `ProjectChatTools` (chat tool surface incl. `run_code_review`, `get_latest_code_review`), entities `ProjectRunLedger` + `ProjectMemorySnapshot` + `ProjectChatMessage` | Append-only run digest ledger → rewritten into versioned snapshot every 10 runs. Planner reads it as `{{PROJECT_MEMORY}}`. Chat compaction summarises older N messages, keeps last 6 verbatim. |

---

## Cross-repo contracts + standards + ADRs + invariants — `contracts/`, `standards/`, `adr/`, `arch/`

| Package | Purpose |
|---------|---------|
| `contracts/` | 4-subpackage cross-repo contract registry — `breaking/` (per-kind classifiers + service), `consumers/` (consumer pinning), `coverage/` (per-repo rollup + rescan), `detection/` (per-kind detectors for OPENAPI/GRAPHQL/JSON_SCHEMA/PROTO), `drift/` (drift event triage controller), `injection/` (planner-side hooks — bounded). V135 schema. |
| `standards/` | Engineering standards as binding policy. `EffectiveStandardsResolver` merges org-level + project-level. `ast/` (AST-based checks), `llmreview/` (LLM-augmented checks for non-AST rules), `override/` (single-violation + standing exception, both require ≥30 char rationale), `report/StandardsComplianceController`. V136 schema. |
| `adr/` | ADRs as first-class — `Adr` entity caches frontmatter parsed from `.executespec/arch/adrs/<NNNN>-<slug>.md`. `AdrFrontmatterParser` is the single parser; `AdrInjectionService` is the planner-side injection point. V137. |
| `arch/invariants/` | Architectural invariants — `ArchInvariant` + `ArchInvariantViolation`. `InvariantChecker` runs per-codemap-version; definitions live in customer git as `.executespec/arch/invariants.yaml` per repo. V138. |
| `folder/` | `.executespec/` folder write chokepoint. `ExecutespecFolderWriter.writeFiles` dispatches PR vs DIRECT_PUSH via `ProjectRepo.prModeEnum()`. `BootstrapService` creates initial scaffold + bootstrap PR + flips 4-state status machine to READY. `FolderReadmeGenerator` auto-generates the README inside `.executespec/`. `ProjectConfigPathResolver` returns FOLDER_FLAT / FOLDER_NAMESPACED / DATABASE per (project, repo). `ExecutespecMigrationController` exposes the SUPER_ADMIN legacy-codemap migration endpoint. V131-V134. **Caller-allowlist pinned** by `arch/ExecutespecFolderWriterCallerAllowlistTest` (4 approved callers). |
| `activity/` | Customer-facing project activity feed. `ActivityEventEmitter` chokepoint dual-writes to `activity_audit` for AUDIT-tagged events. `ActivityFeedController` exposes paginated GET with limit/eventType/from/to. `ActivityFeedRetentionJob` + `ActivityFeedPartitionJob` are `@Scheduled + @SchedulerLock`. V133 (12 monthly partitions seeded 2026-04 → 2027-03). |
| `export/` | Project export bundle — `ProjectExportController.export` returns a zip of every `.executespec/` artefact + DB-only counterparts when `config_storage=DATABASE`. |
| `notification/pref/` | Per-user × event-type × channel opt-in/out matrix. `NotificationPrefService` applies sensible defaults. V139. |

---

## Auth + tenancy + security — `auth/`, `tenant/`, `security/`, `crypto/`, `ratelimit/`

| Package | Purpose |
|---------|---------|
| `auth/` | `controller/` (AuthController, OrgController, UserController), `dto/`, `entity/` (AppUser, Org, TierConfig), `service/` (AuthService, OrgService, ProjectService, TierEnforcementService, TierPermissionService, EntitlementResolver, UserDataExportService — GDPR ZIP, `TierProductSurfaceMapper` — dormant post-V179), `oauth/` (Google + GitHub success handler + `OAuthSurfaceResolver` + `OAuthInitController`), `security/` (SecurityConfig, JwtUtil with scoped-SSE tokens, JwtSecretValidator, ProductSurfaceFilter, RoleGuard, AccessGuard), `dsar/` (DSAR data-export endpoint), `sso/` (`SsoConfigController` + lockout-prevention guard). |
| `tenant/` | TenantFilter + TenantInterceptor + ThreadContextHolder. |
| `security/` | InputSanitizer (NFKC + zero-width strip + base64 decode + 26 regex patterns + abuse markers), CodeAbuseScanner (file-write blocker), guardrail level enum + LOW/MEDIUM/HIGH per-layer behaviour. |
| `crypto/` | KMS / encryption-at-rest helpers. |
| `ratelimit/` | RateLimitFilter (Redis sliding window, per-tenant groups AUTH/RUNS/WEBHOOK/READ/WRITE/PREVIEW/DEPLOY). |

---

## Billing — `billing/`, `pricing/`, `credit/`, `entitlements/`

| Package | Purpose |
|---------|---------|
| `billing/` | `service/BillingService`, `gateway/{PaymentGatewayRouter, RazorpayPaymentGateway, StripePaymentGateway}`, `service/InvoiceService`, `service/PaymentAuditService`, `service/AnalyticsService`, `lifecycle/{LifecycleState, TierLifecycleService, TierLifecycleController}` (3-state machine), `entity/{BillingPlan, BillingSubscription, Invoice}`, `cost/{RunCostLog, RunCostLogRepository}` (audit table), `webhook/` (Razorpay + Stripe HMAC-verified handlers), `founder/FounderPricingService` (lifetime founder discount allocator with row-level lock). |
| `pricing/` | `PricingController` — yields YAML-driven `PricingRow[]` (incl. `cachedInputCostPer1m`, `chainTier`, `displayLabel`). |
| `credit/` | Credit grant + ledger (legacy v3 surface). |
| `entitlements/` | `EntitlementResolver.resolveTier` — picks higher of (user.tier, org.tier). |

---

## Deployment + preview — `deployment/`

| Subpackage | Purpose |
|------------|---------|
| `preview/` | `PreviewService` (dual-mode: docker / cloud-run), `PreviewProperties` (mode, TTL, concurrency), `CaddyProxyManager` (Docker-mode only), `DevDockerfileGenerator` (reads `.agent-context/project.json`). |
| (root) | `DeploymentService`, `DockerfileGenerator` (strategy-pattern per project type), `CloudRunClient`, `ArtifactRegistryClient`. |

---

## LLM routing — `router/`

| Key files | Purpose |
|-----------|---------|
| `LlmProvider` (enum: ANTHROPIC / OPENAI / GEMINI; GOOGLE alias rewritten to GEMINI on input) | Canonical provider enum. |
| `ModelRegistry` | Reads `application.yml model-matrix.*` — `resolve(agentName, provider)` returns tier-routed model, `workhouseModelFor(provider)` returns default workhorse, `contextLimitForOrPrefixFallback(model)` returns context window. **Single source of truth** — `ModelContextWindowMap` was deleted; `ModelController.MODEL_LABELS` reads `display-label` from YAML. |
| `ModelMatrixProperties` | YAML binding. |
| `ProviderHealthTracker` | Redis-backed circuit breaker (5 failures → open 60s) on the legacy router. |
| `ProviderRecommendationService` | Heuristic project-type → provider routing (gated by `provider.smart-routing.enabled`). |
| `AvailableProviderResolver`, `ResolvedModel`, `ProviderStatusService` | Provider availability + resolved-model DTO. |

---

## Execution View — `execution/`

Project-level workbench for multi-repo, multi-solution work.

| Key files | Purpose |
|-----------|---------|
| `service/ExecutionOrchestrator` | Drives `ExecutionRun` through SETUP → ANALYSING → ARCH_REVIEW → PLANNING → EXECUTING → COMPLETE. Boot-time `@EventListener(ApplicationReadyEvent)` resumes EXECUTING runs. |
| `service/ExecutionPlannerService` | Decomposes the requirement (with `{{PROJECT_MEMORY}}`) into ordered `ExecutionSolution`s — each targeting one repo, dispatched through `RunOrchestrator.submitRun(...)` by `SolutionExecutionService`. |
| `service/SolutionExecutionService`, `service/ExecutionBranchService`, `service/CrossRepoContextService`, `service/IncrementalCodeMapService`, `service/SolutionDependencyResolver`, `service/FileConflictDetector`, `service/ExecutionRunCasUpdater` (CAS-with-retry chokepoint for every async @Version write site) | Solution dispatch + branching + handover + dependency + conflict + CAS-with-retry. |
| `entity/{ExecutionRun, ExecutionSolution, ExecutionSolutionRepo, ExecutionContextHandover}` | Persistent state. |
| `event/ExecutionEventTypes` | SSE channel `sse:channel:exec:{id}` with `EXEC_*` events. |
| `controller/{ExecutionController, ProjectLaunchController}` | REST surface. |

Feature flag: `execution.view.enabled` (default OFF). ENTERPRISE tier gate (`tier_config.execution_view_enabled`).

---

## Readymade Solutions + Migration Recipes — `readymade/`, `migration/`, `composer/`

| Package | Purpose |
|---------|---------|
| `readymade/` (V157, W1-W5 shipped on `feature/readymades-and-migrations`) | `entity/ReadymadeSolution`, `service/{ReadymadeService, ReadymadeOrchestrator, StackNormalizer, VariantResolver, ReadymadeSeeder}`, `controller/ReadymadeController` + `ReadymadeAdminController`. Library of pluggable parametric solutions with stack-aware variants → instantiates via Execution View. |
| `migration/` (V158-V159, W1-W7 shipped on same branch) | `entity/MigrationRecipe`, `service/{MigrationRecipeSeeder, MigrationStackMatcher, MigrationDryRunService, MigrationOrchestrator}`, `controller/MigrationController` + `MigrationAdminController`. Source-stack → target-stack codemod recipes with halt-and-inspect Phase 1. |
| `composer/` | `SlotValidator` + `SpecComposer` — cross-product helpers shared between readymade + migration. |

Full plans + remaining waves W6-W9 / W8-W10 in [`docs/plans/pending.md`](plans/pending.md) §7 + §8.

---

## Integrations + SCM — `integration/`, `repo/`

| Subpackage | Purpose |
|------------|---------|
| `integration/slack/` | `SlackNotificationService` — org-level OAuth, per-project channel routing, Block Kit messages. |
| `integration/jira/` | `JiraAdapter` — inbound webhook + outbound comments on MergeCompleted. |
| `integration/confluence/` | `ConfluenceAdapter` — inbound + outbound child pages. |
| `integration/bitbucket/` | `BitbucketAdapter` (sibling to repo/BitbucketRepoService). |
| `integration/pagerduty/` | `PagerDutyAdapter`. |
| `integration/servicenow/` | `ServiceNowAdapter` — HMAC-verified inbound webhook. |
| `integration/scm/` | `ScmPushService.pushMultiRepo` + `ScmPushService.push`. |
| `integration/IntegrationManager` | Routes webhooks; listens for `MergeCompleted`. |
| `repo/` | `GitHubRepoService` (branch, PR creation, default branch lookup, webhook install, `compareCommits` for arch diff, `getPullRequestSnapshot` with `headSha`), `BitbucketRepoService`, `ScmProvider` abstraction. |

---

## Resources

| Path | Purpose |
|------|---------|
| `src/main/resources/application.yml` | Single config file. Model matrix (per-tier per-provider + pricing + failover + agent-tier-map + display-label), Flyway settings (out-of-order true, ignore-migration-patterns), Spring AI bean defaults, billing routing, feature flags seed values. |
| `src/main/resources/db/migration/V*.sql` | 186 Flyway migrations. Filenames immutable post-apply. |
| `src/main/resources/prompts/*.md` | Current prompts (planner, outline-planner, outline-planner-skeleton, task-expander, coherence-planner, classifier, worker, reviewer, etc.). |
| `src/main/resources/prompts/fragments/*.md` | Composable fragments resolved via `{{include:name}}` markers (security-rules, preview-defaults, cors-config, etc.). |
| `src/main/resources/prompts/scaffolds/*.md` | Per-project-type scaffold templates injected via `{{SCAFFOLD_TEMPLATE}}` (spring-boot, node-backend, node-frontend, python, go, react-native-expo, …). |
| `src/main/resources/prompts/planner.v*.txt` + `reviewer-v2.txt` | Frozen rollback archives for `PromptVersioningService` — deliberately retained. |
| `src/main/resources/sample-specs/*.yml` | 3 onboarding sample specs (web-api, frontend, cli). |
| `src/main/resources/readymades/*.yml` | Readymade catalog files (per-solution) — see [`docs/readymades-catalog-format.md`](readymades-catalog-format.md). |
| `src/main/resources/migration-recipes/*.yml` | Migration recipe catalog files — see [`docs/migration-recipes-catalog-format.md`](migration-recipes-catalog-format.md). |
| `src/main/resources/templates/email/*.html` | 21 HTML email templates (palette unified in v2.6.0 Graphite+Signal). |
| `src/main/resources/templates/report/validation-report-pdf.html` | Validation-report PDF template (Signal Blue brand accent). |

---

## Frontend — `frontend/`

| Path | Purpose |
|------|---------|
| `frontend/src/main.tsx` | App entry, theme pre-paint, PostHog init. |
| `frontend/src/lib/api.ts` | Single API client; every backend endpoint consumed here. |
| `frontend/src/stores/` | Zustand stores — `useAuthStore`, `useRunStore`, `useUiStore` (theme), `useProjectStore`, `useV3ProjectStore`, `useExecutionStore`. |
| `frontend/src/components/layout/` | V2 sidebar shell (`AppLayout`, `Sidebar`, `Header`). |
| `frontend/src/v3/` | V3 top-bar shell (`Shell`, `V3TopBar`, `V3HomeRedirect`, V3 pages). |
| `frontend/src/components/runs/` | Run cards / `RunDetailTabs` / `InlineRunDetail` / `ValidationReportPanel` / `ScoreCard` / `ScoreRing` / `VerdictPill` / `SignalBreakdown` / `CostProgressBar` / `RunSpecBlock` / `FirstRunCelebration` / `PrReviewButton`. |
| `frontend/src/components/project/` | Project dashboard / `PulseTab` / `RepoCard` / `TechDebtPanel` / `WorkspaceHealthPanel` / `PostMergeBar` / `CodeQualityPanel` / `GeneratedDocsPanel` / `ProjectMemoryPanel` / `ProjectChatPanel` / `ArchitectureViewer`. |
| `frontend/src/components/shared/` | `Button`, `Checkbox`, `Radio`, `ResponsiveModal`, `Tooltip`, `Glossary` (5-term dict), `Drawer`, `EstimateChip`, `MarkdownContent`. |
| `frontend/src/tokens.css` + `tailwind.config.ts` | UI tokens (Graphite + Signal palette, 4-band score colours, semantic + status palette, theme overrides for `[data-theme='light']`). See [`docs/design/ui-tokens.md`](design/ui-tokens.md). |
| `frontend/.prettierrc.json` + `.eslintrc.cjs` + `.prettierignore` | Style config; gated by `npm run format:check` + `npm run lint` in CI. |
| `frontend/.pa11yci.json` | pa11y-ci a11y gate config (public surfaces). |

---

## Migrations index (highlights)

Full filenames in `src/main/resources/db/migration/`. Head: **V186** (2026-05-11).

| Range | Theme |
|-------|-------|
| V1–V44 | Core schema (users, orgs, projects, runs, agent_task, design system, payment_audit, JWT epoch). |
| V45–V60 | Stripe gateway + OAuth tables + force-logout epoch + cost-proportional credits + run_cost_log. |
| V61–V90 | Frozen V1 batch tables (now unused), preview registry, accuracy reports, Pilot v3.0 project workspace (project_repo, project_repo_link, project_architecture_doc, pull_request_record), execution view. |
| V91–V102 | Project chat (V93-V94), pgvector + project documents (V95), auto-push flag (V96-V97), execution_relax_id_lengths (V98), backfill_execution_run_ai_provider (V99), run_repo_snapshot (V100), project_repo.webhook_installed (V101), project_spec_template (V102). |
| V103–V115 | Execution branch (V103), project.github_owner (V104), execution_run.continue_pr (V105), validation_report.verified_score + scoring.v2-verified flag (V106), pipeline_run.status_reason (V107), historical PARTIAL backfill (V108), plan_requirements_json + meets_requirements_json + validation.v3-strict flag (V109), enable scoring.v2-verified (V110), execution_solution failure-signature dedup (V111), execution_run.version @Version (V112), project_generated_doc (V113-V114), repo_architecture_doc (V115). |
| V116–V120 | Memory + assessment polish + plan-version tooling + LlmProvider GEMINI rename (V120). |
| V121–V129 | v2.6.1 persona-roadmap waves — tier lifecycle (V121-V124), activation funnel + sandbox (V125-V126), SSO + GitHub-app schema + trial queue (V127), provider routing log (V128), startup-program (V129). |
| V130 | Hierarchical planner schema (`plan_strategy`, `plan_outline_json`, per-phase token/cost columns, `agent_task.{task_type, expansion_status, expanded_at}`). |
| V131–V139 | Enterprise readiness — `.executespec/` folder status (V131-V132), `project.config_storage` + `project_config` (V134), `activity_feed` partitioned-by-month (V133), `contract_registry` (V135), `org_standard` + `project_standard_override` + `standards_violation` (V136), `adr` (V137), `arch_invariant` + violation (V138), `user_notification_pref` (V139). |
| V140–V146 | Tier-config alignment (V140), ladder consolidations (V141, MAX→TEAM rename later reverted), dead-flag drops (V142), product split (V143, retired by V179), per-seat TEAM reinstatement (V144), FREE 5→10 (V145, reverted V167), annual billing plans (V146). |
| V147–V164 | v2.7 Foundation — audit-chain (V147), governance caps (V148), TEAM-BYOK plan (V149), retention seed (V150), audit chain widen (V151), referral + lifetime credits (V152-V153), personal org bootstrap (V162-V163), tier-config caps polish. |
| V165–V168 | Drop MAX tier, rename to TEAM @ $39 (V165), align FREE billing plan run limit (V166), revert FREE to 5 (V167), pause/resume columns (V168). |
| V169–V178 | PR record project_id + kind (V169), CI auto-fix attempts (V170), R09 reconcile pipeline_result with status_reason (V171), PR Review + Code Review agent schema (V172), widen review user_id columns (V173), arch-doc cost columns (V174), external project sharing (V175), un-hide PRO as Solo (V176, retired by V185), Solo annual + founder pricing (V177), user_cost_cap on pipeline_run (V178). |
| V179–V186 | Drop product split (V179), lint autofix flag (V180), PR comment inbox (V181), `pipeline_run.run_mode = BUILD \| PATCH` (V182), `pipeline_run.triggered_by_finding` (V183), widen `agent_task.last_llm_failure` VARCHAR(255) → TEXT (V184), retire Solo branding (V185), pull_request CI status columns (V186). |

---

## Where to start reading

- **Pipeline shape:** `integration/RunOrchestrator.java` → `agent/SmartClassifierAgent.java` → `agent/OutlinePlannerAgent.java` → `harness/SequentialExecutor.java` → `agent/WorkerAgent.java` → `reviewer/ReviewerAgent.java`.
- **Patch mode + autofix:** `agent/PatchAgent.java` → `finding/FindingPatchSubmitter.java` → `prreview/PrReviewAutofixService.java` + `project/techdebt/TechDebtFixService.java` + `project/pr/CiFailureAutoFixService.java` + `codereview/CodeReviewAutofixService.java`.
- **Cost + scoring:** `cost/RunCostTracker.java` → `report/ValidationReportService.java` (`computeVerifiedScore`) → `scoring/ReviewScoringService.java`.
- **Multi-repo PR:** `project/pr/PullRequestRecordService.java` → `integration/scm/ScmPushService.java` → `repo/GitHubRepoService.java`.
- **Project memory + chat:** `memory/ProjectMemoryProvider.java` → `memory/RunLedgerAgent.java` → `memory/MemoryCompactionService.java` → `memory/ProjectChatService.java`.
- **RAG:** `project/rag/RetrievalService.java` → `project/rag/EmbeddingService.java` → `project/rag/ChunkStore.java`.
- **External sharing:** `sharing/ProjectShareLinkService.java` → `sharing/ProjectMemberService.java`.
- **Cleanroom adapters:** `swebench/SwebenchTaskService.java` + `programbench/ProgramBenchTaskService.java` → `tool/ShellTool.java` + `tool/BuildProjectTool.java` (`ProgramBenchContainerStore` dispatch).
