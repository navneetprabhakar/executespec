# ExecuteSpec — CODEMAP

Mechanical index of `src/main/java/com/navneet/executespec/`, `frontend/src/`, prompts, migrations, controllers, agents, tools, entities, and feature flags. Generated from the repo at version `1.0.0-M1` (Flyway head V199).

Sections:
1. [Top-level Java packages](#1-top-level-java-packages)
2. [REST controllers](#2-rest-controllers-98-files)
3. [Agents](#3-agents-17-agentjava-files--auxiliaries)
4. [Tools](#4-tools-tooljava--16-files-in-tool)
5. [Entities and table mapping](#5-entities-and-table-mapping)
6. [Flyway migrations](#6-flyway-migrations-199-files-head-v199)
7. [LLM prompts](#7-llm-prompts)
8. [Frontend pages](#8-frontend-pages)
9. [Feature flags](#9-feature-flags)

---

## 1. Top-level Java packages

72 packages directly under `com.navneet.executespec`. Counts via `find <pkg> -name '*.java'`.

| Package | Purpose (one line) | Ctrl | Svc | Agent | Tool | Total |
|---|---|---:|---:|---:|---:|---:|
| `activity` | Org/project audit-trail activity feed with month-partitioned reads. | 1 | 0 | 0 | 0 | 8 |
| `adr` | Architecture Decision Records for projects (CRUD + render). | 1 | 1 | 0 | 0 | 5 |
| `agent` | LLM agents (worker / planner / classifier) + `LlmRouter`. | 0 | 0 | 7 | 0 | 45 |
| `analytics` | PostHog event tracking + activation funnel admin. | 1 | 1 | 0 | 0 | 6 |
| `anonrun` | Anonymous-trial run support (Redis-throttled, no auth). | 0 | 1 | 0 | 0 | 7 |
| `api` | REST controllers, DTOs, exception advice (`RestControllerAdvice`). | 21 | 0 | 0 | 0 | 47 |
| `apitests` | API-test-suite agent (generates OpenAPI + smoke tests). | 0 | 0 | 1 | 0 | 2 |
| `arch` | ArchUnit tests + architectural-invariant detection (also under `arch/invariants/`). | 1 | 0 | 0 | 0 | 6 |
| `assessment` | Feature-assessment heuristic + LLM augmentation. | 0 | 1 | 0 | 0 | 2 |
| `auth` | JWT auth, OAuth2 login, RBAC, users, orgs, GitHub-app, GDPR data export. | 7 | 11 | 0 | 0 | 72 |
| `batch` | Batch-execution coordinator (Anthropic batch + Gemini inline ≥10 tasks). | 0 | 2 | 0 | 0 | 8 |
| `billing` | Stripe + Razorpay gateways, plans, subscriptions, invoices, founder pricing. | 7 | 13 | 0 | 0 | 84 |
| `chat` | Shared chat service primitives (kept thin). | 0 | 2 | 0 | 0 | 3 |
| `codeintel` | Per-repo codemaps + per-repo / cross-repo architecture docs. | 2 | 8 | 0 | 0 | 26 |
| `codereview` | Repo-wide code-review agent (Phase 4 autofix surface). | 1 | 2 | 1 | 0 | 10 |
| `compliance` | Sunsetting / compliance-policy enforcement around tier lifecycle. | 0 | 1 | 0 | 0 | 3 |
| `composer` | Spec composer support utilities. | 0 | 0 | 0 | 0 | 2 |
| `config` | `@ConfigurationProperties` types + `FeatureFlagService` + bean wiring. | 3 | 3 | 0 | 0 | 24 |
| `contact` | Contact-form intake routed to Jira / fallback email. | 1 | 1 | 0 | 0 | 4 |
| `context` | Per-run conversation snapshot store (Redis-backed, 24h TTL). | 0 | 0 | 1 | 0 | 7 |
| `contracts` | Cross-repo contract drift detection + consumer pinning + coverage. | 3 | 4 | 0 | 0 | 32 |
| `cost` | `RunCostTracker`, cache-aware pricing, run-cost history. | 0 | 1 | 0 | 0 | 5 |
| `credit` | Lifetime run credit balance accounting. | 0 | 1 | 0 | 0 | 1 |
| `crypto` | Key encryption helpers (GCP Secret Manager + local fallback). | 0 | 1 | 0 | 0 | 4 |
| `deployment` | Cloud Run + Cloud Build + Artifact Registry deploys + per-run preview engine. | 2 | 10 | 1 | 0 | 77 |
| `entitlements` | Tier-based feature entitlements DTO. | 0 | 0 | 0 | 0 | 2 |
| `eval` | Model-evaluation framework (planner / worker / reviewer eval admin). | 1 | 0 | 0 | 0 | 10 |
| `execution` | V3 `ExecutionRun` orchestration + dependency graph + spec template. | 3 | 6 | 0 | 0 | 38 |
| `export` | Project-scoped artefact ZIP export (`.executespec/` bundle). | 1 | 1 | 0 | 0 | 2 |
| `finding` | Common patch-submitter chokepoint for the 4 autofix surfaces. | 0 | 0 | 0 | 0 | 3 |
| `folder` | Writes the `.executespec/` folder layout into customer repos. | 1 | 1 | 0 | 0 | 7 |
| `governance` | Activity-retention + audit-chain policy. | 0 | 0 | 0 | 0 | 1 |
| `harness` | `SequentialExecutor`, `GateStore`, `UiStyleResolver`, `WorkspaceInitService`. | 0 | 1 | 0 | 0 | 15 |
| `integration` | `RunOrchestrator`, SCM push service, multi-repo Git ops. | 0 | 4 | 0 | 0 | 42 |
| `landing` | Landing-page / marketing API support. | 1 | 0 | 0 | 0 | 4 |
| `lint` | Deterministic auto-formatter pass between executor and reviewer (V180). | 0 | 0 | 0 | 0 | 1 |
| `memory` | Project memory snapshots + ledger + project chat (compaction). | 2 | 2 | 1 | 0 | 13 |
| `migration` | Customer-side migration recipe catalogue + dry-run cache. | 2 | 2 | 0 | 0 | 24 |
| `notification` | In-app notifications + per-user preferences. | 2 | 2 | 0 | 0 | 10 |
| `observability` | `Metrics` constants + counter / timer helpers. | 0 | 0 | 0 | 0 | 1 |
| `onboarding` | Welcome flow + new-user provisioning. | 1 | 1 | 0 | 0 | 5 |
| `optimiser` | `PromptOptimiserAgent` + offline prompt-A/B suggestion loop. | 0 | 1 | 1 | 0 | 5 |
| `pricing` | Pricing-display API (reads YAML model-matrix). | 0 | 0 | 0 | 0 | 3 |
| `programbench` | ProgramBench cleanroom adapter (Docker-bound execution). | 1 | 1 | 0 | 0 | 5 |
| `project` | Project workbench: dashboard, repos, tech debt, PR refresh, briefs. | 7 | 15 | 0 | 0 | 42 |
| `prreview` | PR Review agent (Phase 1 autofix surface) + GitHub PR comment ingestion. | 2 | 3 | 1 | 0 | 12 |
| `ratelimit` | Sliding-window rate limit + per-tier bucket (Redis). | 0 | 0 | 0 | 0 | 3 |
| `readymade` | Readymade solution catalogue (vetted starter solutions). | 2 | 1 | 0 | 0 | 24 |
| `recovery` | `StartupRunResumer` + crash-recovery wiring. | 0 | 0 | 0 | 0 | 1 |
| `referral` | Referral-invite tracking. | 0 | 1 | 0 | 0 | 4 |
| `repo` | GitHub + Bitbucket repo services. | 1 | 2 | 0 | 0 | 5 |
| `report` | `ValidationReport` build + persistence + PDF + share link. | 1 | 4 | 0 | 0 | 14 |
| `reviewer` | `ReviewerAgent`, `AccuracyAssessmentService`, `PostEditFormatter`. | 0 | 1 | 1 | 0 | 12 |
| `rollout` | Phased-rollout admin endpoints. | 1 | 1 | 0 | 0 | 4 |
| `router` | `ModelRegistry`, `ModelResolver`, provider health tracker. | 0 | 2 | 0 | 0 | 14 |
| `run` | Per-run state utilities (`RunSkipPushFlag` etc.). | 0 | 1 | 0 | 0 | 2 |
| `saga` | Compensation framework for partial-fail recovery (`side_effect_outbox`). | 0 | 0 | 0 | 0 | 4 |
| `scoring` | Multiplicative verified-score calculator. | 0 | 1 | 0 | 0 | 5 |
| `security` | `InputSanitizer`, `CodeAbuseScanner`, dependency policy, guardrail level. | 0 | 0 | 0 | 0 | 9 |
| `sharing` | Cross-org `ProjectMember` invites + public share-link service. | 3 | 2 | 0 | 0 | 9 |
| `smoketest` | Per-task post-write smoke check. | 0 | 0 | 0 | 0 | 3 |
| `sse` | Server-sent events broadcaster + replay buffer. | 0 | 1 | 0 | 0 | 3 |
| `standards` | Org standards + violations + overrides (compliance report). | 3 | 0 | 0 | 0 | 26 |
| `state` | `StateStore`, `PipelineRun`, `AgentTask`, `PlanVersion` (state management). | 0 | 2 | 0 | 0 | 37 |
| `swebench` | SWE-bench Lite cleanroom adapter (Docker-bound execution). | 1 | 1 | 0 | 0 | 6 |
| `tenant` | Hibernate per-tenant connection interceptor (AOP). | 0 | 0 | 0 | 0 | 4 |
| `test` | Internal test-only / diagnostic controllers (gated). | 9 | 0 | 0 | 0 | 9 |
| `testgen` | Test plan + test worker + report generator (non-tool-calling agents). | 2 | 1 | 2 | 0 | 29 |
| `tool` | The `Tool` contract + 11 concrete tools + facades + `CompleteTaskTool`. | 0 | 0 | 0 | 16 | 28 |
| `util` | Misc utility helpers. | 0 | 0 | 0 | 0 | 3 |
| `validator` | Validation v3 strict-mode requirement graph checks. | 0 | 0 | 0 | 0 | 27 |

Counts above reflect class-name suffixes only (`*Controller.java`, `*Service.java`, `*Agent.java`, `*Tool.java`); composite / supporting classes inflate the `Total` column.

Per-package code-adjacent CLAUDE.md context exists in: `agent/`, `codereview/`, `deployment/`, `harness/`, `prreview/`, `router/`, `sharing/`, `state/`, `validator/`.

---

## 2. REST controllers (98 files)

For each controller: class name + (class-level `@RequestMapping` base path | first method-level mapping if no class-level).

| Controller | Base path |
|---|---|
| `AccountingController` | `/api/accounting` |
| `ActivationFunnelController` | `/api/admin/activation-funnel` |
| `ActivityFeedController` | `/api/projects/{projectId}/activity` |
| `AdminAnalyticsController` | `/api/admin/analytics` |
| `AdminBillingController` | `/api/admin/billing` |
| `AdminReminderController` | `/api/internal/reminder` |
| `AdrController` | `/api/projects/{projectId}/adrs` |
| `AnonymousRunController` | `/api/v3` |
| `AuthController` | `/api/auth` |
| `BillingController` | `/api/billing` |
| `BillingWebhookController` | `/api/webhooks` |
| `CodeReviewController` | `/api/code-reviews` (method-level) |
| `ConfigAdminController` | `/api/admin/config` |
| `ContactController` | `/api/contact` |
| `ContextStoreTestController` | `/api/v2/test` |
| `ContractConsumerController` | `/api/projects/{projectId}/contracts/{contractId}/consumers` |
| `ContractCoverageController` | `/api/projects/{projectId}/repos/{repoId}/contract-coverage` |
| `ContractDriftController` | `/api/projects/{projectId}` |
| `ConversationController` | `/api/conversation` |
| `CredentialController` | `/api/credentials/gcp` |
| `DeploymentController` | `/api/deployments` |
| `DsarController` | `/api/dsar` |
| `EntitlementsController` | `/api/v3` |
| `EvalController` | `/api/admin/eval` |
| `ExecutespecMigrationController` | `/api/admin/projects` |
| `ExecutionController` | `/api/projects/{projectId}/executions` (method-level) |
| `ExecutorTestController` | `/api/v2/test` |
| `FeasibilityController` | `/api/feasibility` |
| `FeatureFlagController` | `/api/admin/feature-flags` |
| `GitHubAppController` | `/api/auth/github-app` |
| `GitHubWebhookController` | `/webhooks/github` (method-level) |
| `HealthController` | `/api/health` |
| `IntegrationController` | `/api/integrations` |
| `IntegrationStatusController` | `/api/integrations` |
| `InvariantController` | `/api/projects/{projectId}/invariants` |
| `InvoiceController` | `/api/billing/profile` (method-level) |
| `LandingController` | `/api/v3` |
| `MetricsController` | `/api/metrics` |
| `MetricsTestController` | `/api/v2/test` |
| `MigrationAdminController` | `/api/admin/migrations` |
| `MigrationController` | `/api/migrations` |
| `ModelController` | `/api/models` |
| `NativeToolsTestController` | `/api/v2/test` |
| `NotificationController` | `/api/notifications` |
| `NotificationPrefController` | `/api/notifications/preferences` |
| `OnboardingController` | `/api/onboarding` |
| `OrgController` | `/api/org` |
| `OrgStandardController` | `/api/orgs/{orgId}/standards` |
| `PaymentAuditController` | `/api/admin/payment-audit` (method-level) |
| `PlannerTestController` | `/api/v2/test` |
| `PrCommentInboxController` | `/api/runs/{runId}/pr-comments` |
| `PrReviewController` | `/api/pr-reviews` (method-level) |
| `PreviewController` | `/api/runs` |
| `PreviewVmController` | `/api/v1/previews` |
| `PricingController` | `/api/pricing` |
| `ProgramBenchController` | `/api/admin/programbench` |
| `ProjectArchitectureController` | `/api/projects/{projectId}/architecture/generate` (method-level) |
| `ProjectChatController` | `/api/projects/{projectId}/chat` |
| `ProjectController` | `/api/projects` |
| `ProjectDashboardController` | `/api/projects/{projectId}/dashboard` (method-level) |
| `ProjectDocGeneratorController` | `/api/projects/{projectId}/docs/{docType}/generate` (method-level) |
| `ProjectDocumentController` | `/api/projects/{projectId}/documents` |
| `ProjectExportController` | `/api/projects/{projectId}/export` |
| `ProjectInviteController` | `/api/projects/{projectId}/invites` (method-level) |
| `ProjectLaunchController` | `/api/projects/{projectId}/launches` (method-level) |
| `ProjectMemoryController` | `/api/projects/{projectId}/memory` |
| `ProjectRepoController` | `/api/repos/github` (method-level) |
| `ProjectScoresController` | `/api/projects/{projectId}/scores` (method-level) |
| `ProjectShareLinkController` | `/api/projects/{projectId}/share-links` (method-level) |
| `ProjectSharePublicController` | `/api/shared/{token}/meta` (method-level) |
| `PullRequestController` | `/api/runs/{runId}/prs` (method-level) |
| `ReadymadeAdminController` | `/api/admin/readymades` |
| `ReadymadeController` | `/api/library` |
| `ReferralController` | `/api/v3/referrals` |
| `ReviewerTestController` | `/api/v2/test` |
| `RolloutRunController` | `/api/v2/runs` |
| `RouterTestController` | `/api/v2/test` |
| `RunController` | `/api/runs` |
| `RunConversationController` | `/api/runs` |
| `ScaffoldController` | `/api/scaffolds` (method-level) |
| `SignupClaimController` | `/api/v3/auth` |
| `SlackIntegrationController` | `/api/integrations/slack` |
| `SpaFallbackController` | `/favicon.ico` (method-level — SPA fallback) |
| `SpecTemplateController` | `/api/projects/{projectId}/templates` (method-level) |
| `SseController` | `/api/runs` |
| `SsoConfigController` | `/api/auth/sso` |
| `StandardsComplianceController` | `/api/projects/{projectId}/standards` |
| `StandardsOverrideController` | `/api/projects/{projectId}/standards` |
| `SuperAdminController` | `/api/admin` |
| `SwebenchController` | `/api/admin/swebench` |
| `TestGenController` | `/api` |
| `TestResultController` | `/api` |
| `TierLifecycleController` | `/api/admin` |
| `TimelineController` | `/api` |
| `ToolRegistryTestController` | `/api/v2/test` |
| `UserRunsController` | `/api` |
| `ValidationReportController` | `/api` |
| `WorkerTestController` | `/api/v2/test` |

Endpoint exception advice: `src/main/java/com/navneet/executespec/api/advice/` — `RestExceptionAdvice`, `ValidationAdvice`, `ResponseStatusExceptionAdvice`.

Public read paths whitelisted in `SecurityConfig`: `/api/shared/**`, `/api/invites/preview`, `/api/health`, `/api/landing/**`, `/webhooks/**`, `/api/webhooks/**`, `/favicon.ico`.

---

## 3. Agents (17 `*Agent.java` files + auxiliaries)

Tier resolves from `application.yml model-matrix.agent-tier-map`.

| Agent | Tier | Source file |
|---|---|---|
| `ApiTestSuiteAgent` | WORKHORSE | `apitests/ApiTestSuiteAgent.java` |
| `CodeReviewAgent` | PLANNING | `codereview/CodeReviewAgent.java` |
| `CoherencePlannerAgent` | LIGHTWEIGHT | `agent/CoherencePlannerAgent.java` |
| `ContextAgent` | LIGHTWEIGHT | `context/ContextAgent.java` |
| `ConversationAgent` | LIGHTWEIGHT | `agent/ConversationAgent.java` |
| `OutlinePlannerAgent` | PLANNING | `agent/OutlinePlannerAgent.java` |
| `PatchAgent` | WORKHORSE (Patch-mode loop; tier source same as WorkerAgent) | `agent/PatchAgent.java` |
| `PrReviewAgent` | PLANNING | `prreview/PrReviewAgent.java` |
| `PreviewAgent` | (preview-engine specific) | `deployment/preview/PreviewAgent.java` |
| `PromptOptimiserAgent` | WORKHORSE | `optimiser/PromptOptimiserAgent.java` |
| `ReviewerAgent` | PLANNING | `reviewer/ReviewerAgent.java` |
| `RunLedgerAgent` | LIGHTWEIGHT | `memory/RunLedgerAgent.java` |
| `SmartClassifierAgent` | LIGHTWEIGHT | `agent/SmartClassifierAgent.java` |
| `TaskExpanderAgent` | WORKHORSE | `agent/TaskExpanderAgent.java` |
| `TestPlannerAgent` | WORKHORSE | `testgen/TestPlannerAgent.java` |
| `TestWorkerAgent` | WORKHORSE | `testgen/TestWorkerAgent.java` |
| `WorkerAgent` | per-task: LOW=LIGHTWEIGHT / MEDIUM=WORKHORSE / HIGH=PLANNING | `agent/WorkerAgent.java` |

Non-agent classes also referenced in `agent-tier-map` (services that hit the LLM directly): `RepoArchitectureGenerator` (PLANNING), `ArchitectureDocGenerator` (PLANNING), `ProjectChatCompiler` (PLANNING), `ProjectDocGeneratorService` (WORKHORSE), `ValidationReportGenerator` (WORKHORSE), `RunTitleSummarizer` (LIGHTWEIGHT), `ProjectChatCompactor` (LIGHTWEIGHT), `ProjectBriefService` (LIGHTWEIGHT), `TestReportGenerator` (LIGHTWEIGHT).

Routers + provider clients (also in `agent/`):
- `LlmRouter.java` — canonical native-tool dispatcher.
- `FallbackLlmRouter.java` — legacy non-tool router with `FallbackMode` enum.
- `OpenAiNativeToolClient.java`, `GeminiNativeToolClient.java` — direct provider clients (bypass Spring AI quirks).
- `PromptAssembler.java`, `PromptAudit.java`, `PromptAuditService.java` — composable prompt fragment + audit pipeline.
- `ApiKeyResolver.java`, `RunKeyStore.java` — API-key resolution (user → org → system) + BYOK awareness.
- `LlmRouterModes.java` — `FallbackMode { ALLOW, NO_FALLBACK }`.

Plan types: `Plan`, `PlannedTask`, `PlanStrategy`, `PlanStrategyCircuitBreaker`, `ClassificationResult`, `Classification`, `RequirementSpec`, `SpecClassifier`, `PlannerOutputUtils`, `PlannerRequest`, `WorkerReport` (under `agent/model/`).

---

## 4. Tools (`Tool.java` + 16 files in `tool/`)

The `Tool` interface lives at [`tool/Tool.java`](../src/main/java/com/navneet/executespec/tool/Tool.java) — every implementation declares `name()`, `description()`, `inputSchema()`, and `execute(args, ctx)`. Strict JSON schemas with `additionalProperties: false`.

Concrete tools registered by `ToolRegistry`:

| Class | `name()` returned | Purpose |
|---|---|---|
| `ReadFileTool` | `readFile` | Read file relative to workspace; 8k-char truncation cap. |
| `WriteFileTool` | `writeFile` | Create/overwrite file. |
| `EditFileTool` | `editFile` | Find-and-replace edit; fails if `oldString` not unique. |
| `ListFilesTool` | `listFiles` | List directory contents. |
| `SearchFilesTool` | `searchFiles` | Recursive grep with regex + glob. |
| `ShellTool` | `shell` | Allowlisted shell command; 6k-char output cap. Cleanroom-aware via `ProgramBenchContainerStore`. |
| `GitStatusTool` | `gitStatus` | Read-only `git status`. |
| `GitDiffTool` | `gitDiff` | `git diff`; 5k-char cap. |
| `GitCommitTool` | `gitCommit` | Stage + commit. Filtered out for `WorkerAgent` via `ToolRegistry.bindForWorker(ctx)`. |
| `BuildProjectTool` | `buildProject` | Run `mvn verify` / `npm run build` / detected build step. Cleanroom-aware. |
| `RunTestsTool` | `runTests` | Patch-mode only — runs the targeted test command. |
| `CompleteTaskTool` | `completeTask` | Native termination signal — present in every bind list. |

Higher-level wrappers (composite tools used by services, not exposed to the LLM directly):
- `FileLockTool`, `FileSystemTool`, `GitTool` — convenience aggregators.

`ToolRegistry.WORKER_DENY_TOOLS` is the per-worker denylist; currently `{"gitCommit"}` only.

`ExecutionContext` (in `tool/`) carries per-task state: workspace root, run id, task id, project id, branch, GitHub creds, container id (if cleanroom), token budget.

---

## 5. Entities and table mapping

115 `@Entity` / `@Table` classes total. Selected mappings (entity class → `@Table(name=...)`):

| Entity class | Table |
|---|---|
| `AccountingEntry` | `accounting_entry` |
| `ActivityAudit` | `activity_audit` |
| `ActivityFeedEntry` | `activity_feed` |
| `AgentInbox` | `agent_inbox` |
| `AgentMessage` | `agent_message` |
| `AgentTask` | `agent_task` |
| `AppUser` | `app_user` |
| `ArchInvariantViolation` | `arch_invariant_violation` |
| `BillingPlan` | `billing_plan` |
| `BillingPlanLimit` | `billing_plan_limit` |
| `BillingProfile` | `billing_profile` |
| `BillingSubscription` | `billing_subscription` |
| `BillingUsage` | `billing_usage` |
| `BillingWebhookEvent` | `billing_webhook_event` |
| `CodeReviewIssue` | `code_review_issue` |
| `CodeReviewScan` | `code_review_scan` |
| `CompactionEvent` | `compaction_event` |
| `ContractDriftEvent` | `contract_drift_event` |
| `Deployment` | `deployment` |
| `DeploymentCredential` | `deployment_credential` |
| `EmailToken` | `email_token` |
| `ExecutionContextHandover` | `execution_context_handover` |
| `ExecutionRun` | `execution_run` |
| `ExecutionSolution` | `execution_solution` |
| `ExecutionSolutionRepo` | `execution_solution_repo` |
| `ExecutionSolutionRun` | `execution_solution_run` |
| `FeatureFlag` | `feature_flag` |
| `FileLock` | `file_lock` |
| `FilePurposeSummary` | `file_purpose_summary` |
| `FounderPricingAllocation` | `founder_pricing_allocation` |
| `FounderPricingDiscount` | `founder_pricing_discount` |
| `GateEvent` | `gate_event` |
| `GateRecord` | `gate_record` |
| `Invoice` | `invoice` |
| `InvoiceLineItem` | `invoice_line_item` |
| `InvoiceSequence` | `invoice_sequence` |
| `MergeConflict` | `merge_conflict` |
| `MigrationDependency` | `migration_dependency` |
| `MigrationDryRunCache` | `migration_dry_run_cache` |
| `MigrationRecipe` | `migration_recipe` |
| `ModelPricing` | `model_pricing` |
| `ModelTaskMetric` | `model_task_metric` |
| `Notification` | `notification` |
| `Org` | `org` |
| `OrgConfigRepo` | `org_config_repo` |
| `OrgInvitation` | `org_invitation` |
| `OrgSlackIntegration` | `org_slack_integration` |
| `PaymentAudit` | `payment_audit` |
| `PipelineRun` | `pipeline_run` |
| `PlanVersion` | `plan_version` |
| `PrCommentInbox` | `pr_comment_inbox` |
| `PrReview` | `pr_review` |
| `PrReviewComment` | `pr_review_comment` |
| `PreviewMetric` | `preview_metrics` |
| `PreviewRegistry` | `preview_registry` |
| `Project` | `project` |
| `ProjectArchitectureDoc` | `project_architecture_doc` |
| `ProjectChatMessage` | `project_chat_message` |
| `ProjectCodeMap` | `project_code_map` |
| `ProjectDocument` | `project_document` |
| `ProjectGeneratedDoc` | `project_generated_doc` |
| `ProjectInvite` | `project_invite` |
| `ProjectMemorySnapshot` | `project_memory_snapshot` |
| `ProjectRepo` | `project_repo` |
| `ProjectRepoLink` | `project_repo_link` |
| `ProjectRunLedger` | `project_run_ledger` |
| `ProjectShareLink` | `project_share_link` |
| `ProjectSlackChannel` | `project_slack_channel` |
| `ProjectSpecTemplate` | `project_spec_template` |
| `PromptAudit` | `prompt_audit` |
| `PullRequestRecord` | `pull_request_record` |
| `ReadymadeDependency` | `readymade_dependency` |
| `ReadymadeFavourite` | `readymade_favourite` |
| `ReadymadeSolution` | `readymade_solution` |
| `ReadymadeVariant` | `readymade_variant` |
| `ReferralInvite` | `referral_invite` |
| `RefreshTokenBlocklist` | `refresh_token_blocklist` |
| `RepoArchitectureDoc` | `repo_architecture_doc` |
| `RunCodeMap` | `run_code_map` |
| `RunCostHistory` | `run_cost_history` |
| `RunCostLog` | `run_cost_log` |
| `RunRepo` | `run_repo` |
| `RunRepoSnapshot` | `run_repo_snapshot` |
| `RunRubricRow` | `run_rubric_row` |
| `RunTopup` | `run_topup` |
| `SellerConfig` | `seller_config` |
| `SideEffectOutboxEntry` | `side_effect_outbox` |
| `SlackNotificationLog` | `slack_notification_log` |
| `StandardsViolation` | `standards_violation` |
| `TestPlan` | `test_plan` |
| `TestResult` | `test_result` |
| `TestTask` | `test_task` |
| `TierConfig` | `tier_config` |
| `TopupPackage` | `topup_package` |
| `UserActivationEvent` | `user_activation_event` |
| `ValidationReport` | `validation_report` |
| `WebhookEventLog` | `webhook_event_log` |

Embeddables and entities without an explicit `@Table` (Hibernate auto-derives): `Adr`, `ArchInvariant`, `ContractConsumer`, `ContractRegistry`, `ContractVersion`, `IntegrationEventLog`, `IntegrationRun`, `MigrationSlot`, `MigrationVariant`, `OrgMember`, `OrgStandard`, `ProjectConfig`, `ProjectMember`, `ProjectStandardOverride`, `PromptAuditEntry`, `ReadymadeSlot`, `RunRecovery`, `UserNotificationPref`.

---

## 6. Flyway migrations (199 files, head V199)

Location: `src/main/resources/db/migration/V*.sql`. Head: `V199__sync_org_tier_with_subscription_tier.sql`.

Last 30 migrations (chronological, one-line purpose):

| # | File | Purpose |
|---|---|---|
| V170 | `pr_record_ci_auto_fix_attempts.sql` | Counter on `pull_request_record` for CI-failure autofix attempts (cap 3). |
| V171 | `r09_reconcile_pipeline_result_with_status_reason.sql` | Backfill `pipeline_run.status_reason` consistent with PR-record status. |
| V172 | `code_review_and_pr_review_agents.sql` | Tables for `code_review_scan`, `code_review_issue`, `pr_review`, `pr_review_comment`. |
| V173 | `widen_review_user_id_columns.sql` | Widen review reviewer-id columns to `VARCHAR(255)`. |
| V174 | `add_cost_to_arch_doc_artefacts.sql` | Cost columns on `project_architecture_doc` and `repo_architecture_doc`. |
| V175 | `external_project_sharing.sql` | `project_invite` + `project_member` (cross-org sharing) + `project_share_link` (public links). |
| V176 | `unhide_pro_as_solo_tier.sql` | Re-list PRO as "Solo" (later retired by V185). |
| V177 | `solo_annual_and_founder_pricing.sql` | Annual plan + founder-pricing seed (later retired by V185). |
| V178 | `user_cost_cap_on_pipeline_run.sql` | Voluntary `user_cost_cap` column. |
| V179 | `drop_product_split.sql` | Reverse V143 — drop `product_id` columns + `app_user_product_access`. |
| V180 | `lint_autofix_flag.sql` | Deterministic auto-formatter pass toggle (`lint.autofix.enabled`). |
| V181 | `pr_comment_inbox.sql` | `pr_comment_inbox` table for GitHub PR review comment ingestion. |
| V182 | `run_mode.sql` | `pipeline_run.run_mode` (BUILD vs PATCH). |
| V183 | `triggered_by_finding.sql` | `pipeline_run.triggered_by_finding` stamp for the 4 autofix surfaces. |
| V184 | `widen_agent_task_last_llm_failure.sql` | Widen `agent_task.last_llm_failure` to `TEXT`. |
| V185 | `retire_solo_branding.sql` | Drop Solo monthly/yearly + founder-pricing seed; rename PRO back to "Pro" (HIDDEN). |
| V186 | `pull_request_ci_status.sql` | CI-state columns on `pull_request_record` + `head_sha` for check-runs polling. |
| V187 | `cost_history.sql` | `run_cost_history` table. |
| V188 | `execution_run_pipeline_run_id.sql` | Unified `execution_run.pipeline_run_id` link. |
| V189 | `execution_run_cost_backfill.sql` | One-shot backfill of `execution_run.{actual_cost_usd, total_actual_tokens}`. |
| V190 | `agent_task_failure_diagnostic.sql` | `agent_task.failure_diagnostic_json TEXT` for worker diagnostics. |
| V191 | `project_archive_delete_timestamps.sql` | `project.{archived_at, deleted_at}` + soft-delete + restore. |
| V192 | `arch_doc_generated_from_branches.sql` | `project_architecture_doc.generated_from_branches jsonb`. |
| V193 | `execution_run_failure_reason.sql` | `execution_run.failure_reason TEXT`. |
| V194 | `validation_report_rubric_score.sql` | Rubric-score column on `validation_report`. |
| V195 | `generated_doc_source_fingerprint.sql` | Source fingerprint on `project_generated_doc` for staleness detection. |
| V196 | `file_purpose_summary.sql` | `file_purpose_summary` table for codemap purpose annotations. |
| V197 | `run_rubric_row.sql` | `run_rubric_row` table for per-rubric line items. |
| V198 | `org_external_sharing_flag.sql` | `org.external_sharing_enabled` flag (default false). |
| V199 | `sync_org_tier_with_subscription_tier.sql` | One-shot sync `org.tier` to active subscription tier. |

Flyway tolerates `*:missing` to cover the V119 orphan from the preview-engine repo split (see the long comment in `application.yml`).

---

## 7. LLM prompts

Location: `src/main/resources/prompts/`.

**Active prompts (top level):**
- `classifier.md` — `SmartClassifierAgent`
- `outline-planner.md` + `outline-planner-skeleton.md` — `OutlinePlannerAgent` (FULL_PLAN + SKELETON_ONLY modes)
- `coherence-planner.md` — `CoherencePlannerAgent`
- `task-expander.md` — `TaskExpanderAgent`
- `worker.txt` + `worker-openai.txt` + `worker-gemini.txt` — provider-specific worker system prompts
- `patch-agent.md` — `PatchAgent` (PATCH-mode runs, bench-mode + autofix surfaces)
- `reviewer-general.md` + `reviewer-wiring.md` — `ReviewerAgent` two-pass review
- `validation-report.txt` — `ValidationReportGenerator`
- `triage.txt` — incoming-request fast triage
- `conversation-system.md` — `ConversationAgent` (project chat)
- `memory-compaction.txt` — `ProjectChatCompactor`
- `run-ledger.txt` — `RunLedgerAgent`
- `context-agent.txt` — `ContextAgent`
- `api-test-suite.txt` — `ApiTestSuiteAgent`
- `test-planner.txt`, `test-worker.txt`, `test-fix-suggestions.txt` — test-gen agents
- `compiler.txt` — compile agent
- `conflict-resolver.txt` — merge conflict resolver
- `execution_planner.txt` — V3 execution planner
- `task-compile-supervisor.txt` — per-task build supervisor

**Subdirectories:**

`prompts/codereview/`:
- `code-review-agent.md` — `CodeReviewAgent`
- `pr-review-agent.md` — `PrReviewAgent`

`prompts/scaffolds/` (project-type scaffolds, used by `OutlinePlannerAgent`):
- `all.md`, `fullstack.md`, `go.md`, `java.md`, `node-backend.md`, `node-frontend.md`, `python.md`, `react-native-expo.md`, `rust.md`, `spring-boot.md`, `static-html.md`

`prompts/fragments/` (composable `{{include:<name>}}` fragments):
- `api-url-rules.md`, `backend-robustness.md`, `compilation-checklist.md`, `cors-config.md`, `format-after-edit.md`, `fullstack-directory.md`, `module-system.md`, `preview-defaults.md`, `router-isolation.md`, `security-rules-planner.md`, `security-rules-worker.md`, `security-rules.md`

`prompts/eval/` (eval-matrix specs):
- `cost-*.txt` (11 files) — cost-band evaluations (AI PDF summariser, booking system, e-commerce, fitness, kanban, landing, portfolio, chat, REST API recipes, SaaS dashboard, URL shortener)
- `lightweight-*.txt` (3 files) — request triage, review summary, validation report
- `planning-*.txt` (7 files) — architecture decision, code review, conflicting requirements, plan generation, saga compliance/consistency/diagnosis
- `workhorse-*.txt` (4 files) — compilation fix, optimistic locking, React component, WebSocket notification

**Rollback archives (retained for `PromptVersioningService`):**
- `planner.v1.txt` ... `planner.v14.txt` + `planner.txt` (current symlink-equivalent)
- `reviewer-v2.txt`

Composition mechanics: `agent/PromptAssembler.java` resolves `{{include:fragment-name}}` markers (looks up `prompts/fragments/<name>.md`) and `{{VAR}}` substitutions. SHA-256 of the assembled prompt persisted to `prompt_audit`.

---

## 8. Frontend pages

The Signal chrome is the only production layout (see `frontend/src/App.tsx` header comment). `v3/` and `v4/` page folders remain on disk for legacy URL catching plus a few demo pages still exposed by `App.tsx` redirects.

### `frontend/src/signal/pages/` (production)

Top-level:
- `AllRunsPage.tsx`
- `AuthCallbackPage.tsx`
- `AuthPages.tsx` (re-exports `LoginPage`, `SignupPage`, `VerifyPage`, `WelcomePage`)
- `ComposePage.tsx`
- `ExtraPages.tsx` (re-exports `InsightsPage`, `LibraryPage`, `LibraryDetailPage`, `MigrationsPage`, `MigrationDetailPage`, `TrustPage`, `EmptyProjectPage`, `PublicPricingPage`, `ForgotPasswordPage`, `ResetPasswordPage`, `AcceptInvitePage`, `AuditLogPage`, `DeploymentsPage`, `SpecTemplatesPage`)
- `HomePage.tsx`
- `LegalPages.tsx` (re-exports `TermsPage`, `PrivacyPage`, `DpaPage`, `OrgSettingsPage`)
- `LivePage.tsx`
- `MiscPages.tsx` (re-exports `NotificationsPage`, `SearchPage`, `MorePage`, `AdminPage`, `SharedPage`)
- `PlanPage.tsx`
- `ProjectsPage.tsx`
- `ResultPage.tsx`

`signal/pages/account/`:
- `AccountPage.tsx`
- `BillingPage.tsx`
- `MiscAccountPages.tsx` (re-exports `TopupPage`, `IntegrationsPage`, `ApiKeysPage`, `OrgMembersPage`)
- `PlansPage.tsx`

`signal/pages/project/`:
- `ProjectActivityPage.tsx`
- `ProjectArchitecturePage.tsx`
- `ProjectChatPage.tsx`
- `ProjectCodePage.tsx`
- `ProjectComposePage.tsx`
- `ProjectMembersPage.tsx`
- `ProjectPulsePage.tsx`
- `ProjectReposPage.tsx`
- `ProjectRunsPage.tsx`
- `ProjectSettingsPage.tsx`
- `ProjectTabs.tsx` (tab navigation primitive)

### Retired chromes (kept for legacy redirects)

`frontend/src/v3/pages/`:
- `AuthPage.tsx`, `BillingPage.tsx`, `EmptyProjectPage.tsx`, `ExecDemoPage.tsx`, `HomeRedirectPage.tsx`, `InsightsPage.tsx`, `LandingPage.tsx`, `LibraryDetailPage.tsx`, `LibraryPage.tsx`, `MigrationDetailPage.tsx`, `MigrationsPage.tsx`, `RunDetailPage.tsx`, `RunsListPage.tsx`, `RunsNewPage.tsx`, `TeamPage.tsx`, `TrustPage.tsx`

`frontend/src/v4/pages/`:
- `ActivityPage.tsx`, `EmbedPage.tsx`, `HomePage.tsx`, `LibraryPage.tsx`, `ProjectInspectPage.tsx`, `ProjectPage.tsx`, `ProjectReposPage.tsx`, `RunPage.tsx`, `SettingsPage.tsx`

### Pre-Signal pages still imported

`frontend/src/pages/`:
- Top-level: `AccountingPage.tsx`, `AuditPage.tsx`, `DeploymentsPage.tsx`, `DpaPage.tsx`, `ExecSummaryPage.tsx`, `IntegrationsPage.tsx`, `InvoicesPage.tsx`, `PricingPage.tsx`, `PrivacyPage.tsx`, `SettingsPage.tsx`, `SharedProjectPage.tsx`, `SharedReportPage.tsx`, `TermsPage.tsx`
- `pages/admin/`: `AdminInviteModal.tsx`, `AdminPage.tsx`, `FeatureFlagsPage.tsx`, `GrantRunsModal.tsx`, `OrgAdminDashboard.tsx`, `PricingManager.tsx`, `TeamMemberDashboard.tsx`, `TierLifecyclePage.tsx`
- `pages/auth/`: `AcceptInvitePage.tsx`, `AuthCallbackPage.tsx`, `ForgotPasswordPage.tsx`, `LoginPage.tsx`, `RegisterPage.tsx`, `ResetPasswordPage.tsx`, `VerifyEmailPage.tsx`
- `pages/execution/`: `ExecutionViewPage.tsx`
- `pages/onboarding/`: `RunsNewLanding.tsx`, `WelcomePage.tsx`
- `pages/org/`: `OrgProjectsPage.tsx`, `OrgSettingsPage.tsx`
- `pages/project/`: `ProjectDashboardPage.tsx`, `ProjectsListPage.tsx`

---

## 9. Feature flags

Resolved via `config/featureflag/FeatureFlagService.java`. Active keys observed by grepping `isEnabled(...)` across the backend:

| Key | Default | Origin |
|---|---|---|
| `submissions.paused` | OFF | Maintenance gate at `RunController.submit` (returns 503). |
| `v2.pause.enabled` | OFF | `POST /api/runs/{id}/pause`, V168. |
| `scoring.v2-verified` | ON | `ReportInput` / score-builder path. Seeded by V106. |
| `project.runs.auto-push` | ON | Toggles auto-push after BUILD-mode terminal. |
| `lint.autofix.enabled` | OFF | Deterministic formatter pass before reviewer (V180). |
| `validation.v3-strict` | OFF | `PlannerOutputUtils.validatePlan` strict requirement-graph check. Seeded V109. |
| `assessment.llm.enabled` | OFF | LLM augmentation on `FeatureAssessmentService` heuristic. |
| `codemap.purpose-summaries` | OFF | Per-file purpose summaries during codemap generation. |
| `provider.smart-routing.enabled` | OFF | Smart-routing entry point (`router/`). |
| `routing.adaptive-routing.enabled` | OFF | Redis-backed success-rate adaptive routing. |
| `ci.autofix.enabled` | OFF | GitHub `check_run` webhook auto-fire for PR CI failures (Phase 3 autofix surface). |
| `harness.v2.batch_enabled` | OFF | Batch coordinator (`batch/BatchCoordinator`). |
| `security.guardrail_level` | LOW | `GuardrailLevelResolver` (3 levels: LOW / MEDIUM / HIGH). |
| `execution.view.enabled` | OFF | V3 execution view UI surface. |
| `v3.anonymous-trial.real-dispatch` | OFF | Anonymous-trial real-dispatch toggle (`anonrun/`). |
| `billing.lifecycle.sunsetting.enabled` | OFF | Tier-lifecycle SUNSETTING admin transitions. |

Flag rows live in the `feature_flag` table. Admin surface: `POST /api/feature-flags/{key}` (SUPER_ADMIN only).
