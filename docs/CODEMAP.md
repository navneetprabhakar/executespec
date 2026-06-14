# CODEMAP

Mechanical index of every top-level package, REST controller, agent, tool, JPA entity, Flyway migration, LLM prompt, frontend page, frontend component, and feature flag in this repository.

Base Java package: `com.navneet.executespec` (all paths under `src/main/java/com/navneet/executespec/`).

Migration head: **V242** in the current worktree (V240 adds the CLI/VS Code agent-session protocol tables; V241 seeds the CLI local-hands feature flag; V242 adds the account-level provider preference used by web, CLI, and VS Code). Frontend chrome: the **Console** control plane (`frontend/src/console/`) is the only protected web shell since 2026-06-12; `frontend/src/ide/` remains the route-level protected surface layer, and `frontend/src/signal/` holds only public + admin pages + shared components.

---

## 1. Top-level package table

76 Java package roots / major package groups under `src/main/java/com/navneet/executespec/` in the current worktree. Counts are approximate (file-count proxies; an entity in a sub-package counts under its parent).

| Package | Ctrl | Svc | Agent | Tool | Purpose |
| --- | --- | --- | --- | --- | --- |
| `activity` | 1 | - | - | - | M-019 hash-chained activity feed + retention/partition jobs + `RunActivityFeedListener` (run lifecycle → feed, RL-2026-05-042) |
| `adr` | 1 | 1 | - | - | Architecture Decision Records — frontmatter parser + planner-injection |
| `agent` | - | 17 | 12 | - | V2 LLM pipeline: classifier/planner/worker/coherence/expander + LlmRouter |
| `agentsession` | 1 | 3 | - | - | Durable CLI/VS Code agent-session protocol: sessions, turns, typed intent/action catalog, repo→project resolution, gate decision cards, and separate session usage ledger (V240) |
| `analytics` | 1 | 1 | - | - | PostHog client + activation funnel event tracking |
| `anonrun` | - | 4 | - | - | R-111 anonymous-trial sessions (abuse heuristic + rate limiter + dispatcher) |
| `api` | 17 | 2 | - | - | REST controllers + DTOs + global exception advisors |
| `arch/invariants` | 1 | 1 | - | - | Org architectural invariants + violation tracking |
| `auth` | 7 | 12 | - | - | JWT/OAuth2 auth, RBAC, org/user/project lifecycle, GDPR export |
| `batch` | - | 9 | - | - | Provider batch-mode job orchestration; the 3 concrete `BatchClient`s (`Anthropic`/`OpenAi`/`Gemini`, B1 2026-05-30) landed unit-tested but **inert** — coordinator/poll/resume wiring (P4) + the human submit-gate still pending; see `docs/plans/v2-batch-execution-plan.md` |
| `benchmark` | 2 | 3 | - | - | **Two concerns.** (1) SWE-bench *execution* harness — blind real-production PATCH pipeline (supersedes oracle `swebench/`); `BenchmarkController` (`/api/admin/benchmark`) + `BenchmarkRunService` (async via submitRun) + `BenchmarkGenerationService` (lean sync `/generate`); branch `feature/swebench-harness`. (2) Common multi-suite *tracking* layer (V236, RL-2026-06, branch `benchmark`) — `BenchmarkTrackingController` (`/api/v2/test/benchmark`) + `BenchmarkTrackingService` + `benchmark_run`/`benchmark_task_result`/`benchmark_event` entities + `BenchmarkSuite`; ingest+SSE for events an external verifier publishes (TB first, SWE/ProgramBench next), reuses `sse/SseBroadcaster` |
| `billing` | 5 | 16 | - | - | Razorpay + Stripe billing, invoices, GST, top-ups, tier lifecycle |
| `chat` | - | 4 | - | - | Post-run conversation: ChatService (+ read-only RunChatTools) + ApplyService + ApplyResultStore |
| `codeintel` | 2 | 7 | - | - | Codemap + per-repo arch + cross-repo arch + RAG-backed docs |
| `codereview` | 1 | 3 | 1 | - | Whole-repo code review (Semgrep + LLM triage) |
| `compliance` | - | 3 | - | - | Workspace + SCM cleanup jobs + data retention service |
| `config` | - | - | - | - | Spring `@Configuration` beans (validation, async, web, etc.) |
| `contact` | 1 | 1 | - | - | Contact form / JIRA ticket creation |
| `context` | - | 3 | 1 | - | Per-run conversation snapshots + ContextAgent for CODEBASE_CONTEXT.md |
| `contracts` | 3 | 4 | - | - | Cross-repo API contracts: detection, drift, breaking-change, planner-injection |
| `cost` | - | 4 | - | - | RunCostTracker + RunCostHistory + token attribution |
| `credit` | - | 1 | - | - | LifetimeCreditService — R-161 5+5 referral lifetime credits |
| `crypto` | - | 3 | - | - | EncryptedValueConverter + KeyMigrationRunner + crypto props |
| `deployment` | 2 | 14 | - | - | Cloud Run deploy + preview engine + workspace archival to GCS + `GcsTaskCheckpointService` (mid-run task-boundary checkpoints, RL-2026-05-042) |
| `engine` | - | 12 | - | - | Code-gen engine seam — `CodeGenEngine.solve(EngineRequest)→EngineResult` + budget/cost/verification/outcome records. Strangler-fig extraction (RL-2026-05-052): Phase 1 contract + Phase 2 `CodeGenEngineImpl` wrap-facade + Phase 3 benchmark routes through it + Phase 4 `WorkerAgent`/`WorkerResult`/`WorkerTaskRequest` relocated here from `agent/`. Production runs still byte-identical (facade unused on the `RunOrchestrator` path). |
| `eval` | 2 | 16 | - | - | Model evaluation framework (LLM-judge + planner-scaffold tests) + `eval/v2/` tier-eval-v2 (production-shaped per-tier × provider eval driving live agent prompts) |
| `execution` | 3 | 11 | - | - | V3 ExecutionRun "Launches" (multi-solution, multi-repo orchestration) |
| `export` | 1 | 1 | - | - | Project-level ZIP export of `.executespec/` artefacts |
| `finding` | - | 1 | - | - | FindingPatchSubmitter chokepoint — 4 autofix surfaces |
| `folder` | 1 | 4 | - | - | `.executespec/` folder writer + bootstrap + project config |
| `governance` | - | 1 | - | - | GovernanceGuard — M-032/M-033 spend caps + GitHub owner allowlist |
| `harness` | - | 7 | - | - | V2 SequentialExecutor + GateStore + WorkspaceInitService + VendoredPathGuard |
| `integration` | 8 | 14 | - | - | Inbound + outbound integrations: Jira/Confluence/ServiceNow/PagerDuty/Bitbucket/Slack + RunOrchestrator (god-class split COMPLETE, audit #43 — 8 collaborators: `RunPipelineSupport`/`RunPersistenceWriter`/`RunContextComposer`/`RunPlanningCoordinator`/`PipelineExecutorPool`/`RunStandardsEnforcer`/`RunValidationKickoff`/`RunLifecycleService` via `RunControlPort`; all 6 increments done, 5,288→2,549 LOC, −52%) |
| `landing` | 1 | - | - | - | Public landing-page stats + sample-run endpoints |
| `lint` | - | 1 | - | - | LintPass — deterministic auto-formatter pass (Phase L1) |
| `memory` | 2 | 5 | 1 | - | Project memory: chat + run ledger + compaction snapshots |
| `migration` | 2 | 5 | - | - | Migration recipes (Wave 1) — Express→Fastify, JS→TS, etc. |
| `notification` | 2 | 3 | - | - | In-app notifications + per-user preference matrix |
| `onboarding` | 1 | 3 | - | - | Wave-2.2 sandbox provisioning + sample specs + cleanup job |
| `optimiser` | - | 3 | 1 | - | PromptOptimiserAgent — post-merge prompt pattern analysis |
| `pricing` | - | - | - | - | ModelPricing JPA entity + repo (read by ModelRegistry) |
| `programbench` | 1 | 3 | - | - | ProgramBench adapter (cleanroom container exec) |
| `project` | 4 | 9 | - | - | Project CRUD + repo registry + RAG documents + dashboard + workspace |
| `prreview` | 2 | 4 | 1 | - | PR Review agent + PrCommentInbox webhook handler |
| `ratelimit` | - | 2 | - | - | RateLimitFilter + RateLimitPolicy (Redis token bucket) |
| `readymade` | 2 | 4 | - | - | Readymade Solutions (W1) — pre-built building blocks |
| `recovery` | - | 1 | - | - | StartupRunResumer — resume in-flight runs after JVM restart |
| `referral` | - | 2 | - | - | R-162 persistent referral invite ledger + grant hook |
| `repo` | 1 | 3 | - | - | GitHubRepoService + BitbucketRepoService + MonorepoDetector |
| `report` | 1 | 4 | - | - | ValidationReport generation + PDF + sharing |
| `reviewer` | - | 3 | 1 | - | ReviewerAgent (general + wiring passes) + PostEditFormatter |
| `rollout` | 1 | 1 | - | - | Rollout-canary RunRegistry |
| `router` | - | 9 | - | - | ModelRegistry + ModelResolver + ProviderHealthTracker plus OpenRouter/Fireworks slug mappers (LLM model and route selection) |
| `run` | - | 2 | - | - | One-shot run markers: `RunSkipPushFlag` and `RunLocalExecutionFlag` (CLI local-hands) |
| `run/sync` | - | 1 | - | - | RunSyncService — reset workspace to current/original HEAD |
| `saga` | - | 2 | - | - | SideEffectOutbox — retry persistence for failed async effects |
| `scoring` | - | 3 | - | - | RunRubricVerifier + TestCoverageRescorer + ReviewScoringService |
| `security` | - | 4 | - | - | InputSanitizer + CodeAbuseScanner + KeyEncryptor + ContextSanitizer |
| `security/dependency` | - | 1 | - | - | DependencySecurityPolicy (consumed by ShellSandboxPolicy) — SupplyChainScanner + NoopSupplyChainScanner deleted 2026-05-26, RL-2026-05-041 (zero callers) |
| `security/guardrail` | - | 1 | - | - | GuardrailLevel enum + resolver (LOW/MEDIUM/HIGH) |
| `sharing` | 3 | 2 | - | - | V175 cross-org project invites + public share links |
| `smoketest` | - | 5 | - | - | CliSmokeRunner + BuildCommandResolver + TestCommandResolver + TestOutputParser + WorkspaceTestRunner (RL-2026-05-001 real test exec) |
| `sse` | - | 2 | - | - | SseBroadcaster + RunDetailSseService (live event stream) |
| `standards` | 4 | 3 | - | - | Org standards (lint/AST/LLM-review/file-pattern enforcement) |
| `state` | - | 2 | - | - | StateStore + InboxService — central persistence facade |
| `swebench` | 1 | 4 | - | - | SWE-bench Lite adapter (cleanroom container) |
| `tenant` | - | 2 | - | - | TenantContext + TenantFilter (multi-tenancy plumbing) |
| `terminalbench` | 1 | 1 | - | - | Operator-only Terminal-Bench 2.0 adapter: runs ExecuteSpec's own `WorkerAgent` in remote-brain/local-hands mode via Harbor; DeepSeek first-20 moved 7/20 -> 12/20 with 80-floor tuning and held 12/20 on the 100-floor/finalizer run, so current policy adds `/tests`/golden-benchmark prompt guidance plus detection-first clean-input no-op handling on top of the 120-iter floor, artifact nudges, restricted finalizers at iteration 64 / wall-clock, and a 900s server wall with buffer under Harbor's 1350s agent timeout (RL-2026-06-004) |
| `test` | 9 | - | - | - | Test-only `/api/v2/test/**` controllers (probe endpoints) |
| `testgen` | 2 | 13 | 3 | - | Test generation pipeline (planner + worker + executor + report) |
| `tool` | - | - | - | 11 | V2 tool layer (readFile/writeFile/editFile/listFiles/searchFiles/shell/git/build/runTests/completeTask) + GuardrailLevelResolver |
| `util` | - | 3 | - | - | SafeYamlLoader + PoolSizing + HmacUtil |
| `validator` | - | 5 | - | - | CodeValidator (per-language toolchain pre-LLM gate) |
| (root) | - | 1 | - | - | `ExecutespecApplication.java` (Spring Boot entry) |

---

## 2. REST controllers

107 controller classes in the current worktree. Grouped by domain.

### Runs / pipeline (V2)

| Controller | Base path | Purpose |
| --- | --- | --- |
| `api/controller/RunController` | `/api/runs` | Submit run, list, get, approve, cancel, archive, fork, pause/resume, retry-task, push-scm, workspace download, plan-versions, task-graph |
| `api/controller/RunConversationController` | `/api/runs` | Post-run chat + apply-suggestion (async via dedicated executor) |
| `agentsession/AgentSessionController` | `/api/agent` | CLI/VS Code agent-session protocol: action catalog, sessions, streaming/recorded turns, context resolution, and gate decision cards |
| `api/controller/PreviewController` | `/api/runs` | Preview analyze/start/stop/logs/rebuild + exec-in-container |
| `api/controller/SseController` | `/api/runs` | SSE token issuance + event stream subscription |
| `api/controller/TimelineController` | `/api` | Per-run timeline + per-model task statistics |
| `api/controller/FeasibilityController` | `/api/feasibility` | Retired — always returns 410 Gone |
| `api/controller/ConversationController` | `/api/conversation` | Pre-flight requirement-refinement chat |
| `api/controller/UserRunsController` | `/api` | Per-user runs summary |
| `api/controller/MetricsController` | `/api/metrics` | Top-line run/token metrics |
| `api/controller/HealthController` | `/api/health` | Liveness (cached 30s) + readiness (inline DB) probes |
| `api/controller/ModelController` | `/api/models` | Available model matrix (YAML-derived) |
| `api/controller/AdminAnalyticsController` | `/api/admin/analytics` | Usage / members / billing / cost-breakdown (RBAC-gated) |
| `api/controller/PricingController` | `/api/pricing` | Pricing list/get/upsert (SUPER_ADMIN) |
| `api/controller/DeploymentController` | `/api/deployments` | Cloud Run deploy + analyze/confirm + rollback/destroy |
| `api/controller/IntegrationController` | `/api/integrations` | Generic webhook receiver + Jira/Confluence triggers + integration-runs |
| `api/controller/IntegrationStatusController` | `/api/integrations` | Always-available status (not gated by feature flag) |
| `api/controller/SlackIntegrationController` | `/api/integrations/slack` | Slack OAuth install + channel CRUD + verbosity + test send |
| `api/EntitlementsController` | `/api/v3` | R-142 single-source entitlements |
| `api/ReferralController` | `/api/v3/referrals` | R-162 referral invite/state/lookup |
| `api/SignupClaimController` | `/api/v3/auth` | R-111 + R-162 — claim anonymous-trial session post-signup |
| `api/AnonymousRunController` | `/api/v3` | R-111 anonymous-trial run submission |

### Auth / org / projects

| Controller | Base path | Purpose |
| --- | --- | --- |
| `auth/controller/AuthController` | `/api/auth` | Register/login/refresh/logout, email verify, password reset, OAuth, GDPR export |
| `auth/controller/OrgController` | `/api/org` | Org CRUD, members, invitations, key-source, AI keys, JIRA, Confluence, V198 external-sharing toggle, TEAM seats |
| `auth/controller/ProjectController` | `/api/projects` | Project CRUD + member management (archive/unarchive/soft-delete/restore from V191) |
| `auth/controller/SuperAdminController` | `/api/admin` | SUPER_ADMIN: tier/user/org admin, executions reconcile, halt-all, audit-chain verify, top-up grants |
| `auth/controller/AdminReminderController` | `/api/internal/reminder` | Shared-secret-gated reminder forwarder (claude.ai scheduler) |
| `auth/sso/SsoConfigController` | `/api/auth/sso` | SSO/SAML config CRUD (ORG_ADMIN) |
| `auth/dsar/DsarController` | `/api/dsar` | GDPR/CCPA/DPDP Data Subject Access Request JSON export |

### Billing

| Controller | Base path | Purpose |
| --- | --- | --- |
| `billing/controller/BillingController` | `/api/billing` | Plans, status, subscribe, verify, cancel, seats, top-up purchase, founder pricing |
| `billing/controller/BillingWebhookController` | `/api/webhooks` | Razorpay + Stripe webhook endpoints (HMAC verified) |
| `billing/controller/InvoiceController` | `/api/billing`, `/api/admin` | Invoice list/detail/PDF/credit-note/void + seller-config admin |
| `billing/controller/PaymentAuditController` | `/api/admin/payment-audit`, `/api/billing/payment-history` | Payment audit + webhook event log + per-org payment history |
| `billing/controller/AccountingController` | `/api/accounting` | Revenue metrics, GST summary, GSTR-1 export, trial balance (FINANCE) |
| `billing/controller/AdminBillingController` | `/api/admin/billing` | Seed Razorpay plans |
| `billing/lifecycle/TierLifecycleController` | `/api/admin` | Tier/plan lifecycle (LISTED/HIDDEN/SUNSETTING) |

### Project workspace

| Controller | Base path | Purpose |
| --- | --- | --- |
| `project/controller/ProjectRepoController` | `/api/repos`, `/api/projects/{id}/repos` | Repo lifecycle (create/list/sync/delete) + project↔repo linking |
| `project/dashboard/ProjectDashboardController` | `/api/projects/{id}` | Dashboard aggregator + workspace-health + estimate + tech-debt + post-merge-bar |
| `project/pr/PullRequestController` | `/api/runs/{id}/prs`, `/api/projects/{id}/prs/refresh`, `/api/prs/snapshot` | PR-per-run listings + project PR refresh |
| `project/pr/GitHubWebhookController` | `/webhooks/github` | GitHub webhook receiver (pull_request, check_run, pr_review_comment) |
| `project/scores/ProjectScoresController` | `/api/projects/{id}/scores` | Score-trend feed |
| `project/template/SpecTemplateController` | `/api/projects/{id}/templates`, `/api/orgs/{id}/templates` | Spec templates (project + org-scoped) |
| `project/rag/ProjectDocumentController` | `/api/projects/{id}/documents` | RAG document upload/list/delete |
| `codeintel/ProjectArchitectureController` | `/api/projects/{id}/architecture` + related | Generate/analyse arch, summary, staleness, versions, diff, codemap, file-browse |
| `codeintel/ProjectDocGeneratorController` | `/api/projects/{id}/docs/{type}` | EXPLANATION + SETUP doc generation/get/download |
| `execution/controller/ExecutionController` | `/api/projects/{id}/executions`, `/api/executions` | Execution View (Launches) — create, get, list, analyse, approve, decide, abort, report, SSE |
| `execution/controller/ProjectLaunchController` | `/api/projects/{id}/launches`, `/prs`, `/overview` | Project-level aggregator: launches + PRs + overview + validate-repo-access |
| `execution/controller/ScaffoldController` | `/api/scaffolds` | Static scaffold-key list (Java/Node/Python/Go/Rust/etc.) |
| `memory/ProjectMemoryController` | `/api/projects/{id}/memory` | Project memory snapshot + ledger |
| `memory/ProjectChatController` | `/api/projects/{id}/chat` | Project-scoped chat (compaction + apply + clear) |

### Code review / PR review / standards / contracts

| Controller | Base path | Purpose |
| --- | --- | --- |
| `codereview/CodeReviewController` | `/api/code-reviews`, `/api/projects/{id}/code-reviews` | Whole-repo scan submit/get/report + per-severity autofix |
| `prreview/PrReviewController` | `/api/pr-reviews`, `/api/projects/{id}/pr-reviews` | PR review submit + status + by-PR query |
| `prreview/inbox/PrCommentInboxController` | `/api/runs/{id}/pr-comment-inbox` | Incoming PR review comments (PENDING/ADDRESSED/DISMISSED) |
| `standards/OrgStandardController` | `/api/orgs/{id}/standards` | Org standards CRUD |
| `standards/override/StandardsOverrideController` | `/api/projects/{id}/standards` | Standards override + standing exception |
| `standards/report/StandardsComplianceController` | `/api/projects/{id}/standards/report` | Compliance report |
| `contracts/coverage/ContractCoverageController` | `/api/projects/{id}/repos/{repoId}/contract-coverage` | Coverage panel + rescan |
| `contracts/drift/ContractDriftController` | `/api/projects/{id}/contracts/{cId}/check-drift` + related | Drift checks + refresh-from-source + dismiss |
| `contracts/consumers/ContractConsumerController` | `/api/projects/{id}/contracts/{cId}/consumers` | Consumer declarations |
| `arch/invariants/InvariantController` | `/api/projects/{id}/invariants` | Project arch invariants + violations |
| `adr/AdrController` | `/api/projects/{id}/adrs` | Architecture Decision Records |

### Library / Migrations (Wave 1)

| Controller | Base path | Purpose |
| --- | --- | --- |
| `readymade/controller/ReadymadeController` | `/api/readymades` | List/get readymade solutions + favourites + variant resolve |
| `readymade/controller/ReadymadeAdminController` | `/api/admin/readymades` | SUPER_ADMIN seed/admin |
| `migration/controller/MigrationController` | `/api/migrations` | List/get migration recipes + dry-run cache |
| `migration/controller/MigrationAdminController` | `/api/admin/migrations` | SUPER_ADMIN seed/admin |

### Sharing / activity / folder / export

| Controller | Base path | Purpose |
| --- | --- | --- |
| `sharing/controller/ProjectInviteController` | `/api/projects/{id}/invites` | V175 cross-org `ProjectMember` invites |
| `sharing/controller/ProjectShareLinkController` | `/api/projects/{id}/share-links` | Public read-only share links |
| `sharing/controller/ProjectSharePublicController` | `/api/shared/{token}/*` | Public read endpoints (no auth) |
| `activity/ActivityFeedController` | `/api/projects/{id}/activity` | Project activity feed (partitioned-by-month) |
| `folder/ExecutespecMigrationController` | `/api/admin/projects/{id}/migrate-codemaps` | Migrate legacy codemaps into `.executespec/` |
| `export/ProjectExportController` | `/api/projects/{id}/export` | ZIP bundle of `.executespec/` artefacts + DB counterparts |

### SWE-bench / ProgramBench / Eval

| Controller | Base path | Purpose |
| --- | --- | --- |
| `benchmark/BenchmarkController` | `/api/admin/benchmark` | Clean blind SWE-bench harness — `/run` (async via submitRun) + `/generate` (lean synchronous code-gen); the supported path (the oracle `swebench/SwebenchController` was removed, RL-2026-05-062) |
| `programbench/ProgramBenchController` | `/api/admin/programbench` | ProgramBench bench-mode submission |
| `terminalbench/TerminalBenchController` | `/api/v2/test/terminal-bench` | Operator-only Terminal-Bench Harbor adapter launcher; `local|dev` profile + SUPER_ADMIN; starts remote-tool `WorkerAgent` runs and returns `{runId,sseToken}` (RL-2026-06-004) |
| `eval/EvalController` | `/api/admin/eval` | Model evaluation runs (legacy text-quality benchmark) |
| `eval/v2/TierEvalController` | `/api/admin/eval/v2` | Tier-eval-v2 — production-shaped per-tier × provider eval (`tiers`, `providers` params) |

### Repo / deployment / preview

| Controller | Base path | Purpose |
| --- | --- | --- |
| `repo/GitHubAppController` | `/api/github` | GitHub App install URL + uninstall + status |
| `deployment/credential/CredentialController` | `/api/credentials/gcp` | GCP service-account credentials lifecycle |
| `deployment/preview/vm/PreviewVmController` | `/api/preview/vm` | Preview VM management (flag-gated) |

### Onboarding / contact / landing / rollout

| Controller | Base path | Purpose |
| --- | --- | --- |
| `onboarding/OnboardingController` | `/api/onboarding` | Sandbox provisioning + sample-spec picker |
| `contact/ContactController` | `/api/contact` | Contact form → JIRA ticket |
| `landing/LandingController` | `/api/v3/landing` | Public landing stats + sample runs (no auth) |
| `rollout/RolloutRunController` | `/api/admin/rollout-runs` | Canary rollout registry |

### Notifications / analytics / testgen

| Controller | Base path | Purpose |
| --- | --- | --- |
| `notification/NotificationController` | `/api/notifications` | In-app notifications list + read + unread count |
| `notification/pref/NotificationPrefController` | `/api/notifications/preferences` | Per-user notification preference matrix |
| `analytics/ActivationFunnelController` | `/api/admin/activation-funnel` | Wave-2.1 activation funnel rollup |
| `testgen/TestGenController` | `/api/runs/{id}/run-tests` | Manually trigger test execution (Beta) |
| `testgen/TestResultController` | `/api/runs/{id}/test-report` | Test report fetch |

### Test-only `/api/v2/test/**` (SUPER_ADMIN-gated)

`ContextStoreTestController`, `ToolRegistryTestController`, `ExecutorTestController`, `PlannerTestController`, `ReviewerTestController`, `MetricsTestController`, `NativeToolsTestController`, `RouterTestController`, `WorkerTestController` — under `test/` package. These were formerly `permitAll`; RL-2026-05-027 locked `/api/v2/test/**` to SUPER_ADMIN. `terminalbench/TerminalBenchController` also lives under that path but is in the `terminalbench` package and is additionally `local|dev` profile-gated.

---

## 3. Agents

`*Agent` classes plus the V2 worker stack. Tier mapping comes from `application.yml` `model-matrix.agent-tier-map`.

| Agent | Package | Tier | Purpose |
| --- | --- | --- | --- |
| `SmartClassifierAgent` | `agent` | LIGHTWEIGHT | Phase-0 classifier — heuristic fast path + LLM verifier; emits `PlanStrategy` (SINGLE_SHOT/SMALL/MEDIUM/LARGE/LEGACY) |
| `OutlinePlannerAgent` | `agent` | PLANNING | Phase-1 planner — emits `Plan` via `submitOutline` tool call; FULL_PLAN (32k) or SKELETON_ONLY (8k) |
| `TaskExpanderAgent` | `agent` | WORKHORSE | Phase-2 — fills body of one skeleton task; parallel for LARGE/LEGACY |
| `CoherencePlannerAgent` | `agent` | LIGHTWEIGHT | Phase-3 — emits zero-or-more `patchPlan` minimal-delta patches |
| `WorkerAgent` | `engine` | WORKHORSE | V2 sequential worker — one task, one ExecutionContext; per-complexity caps; one progress signal + Lever B salvage; build/test verification gates. Relocated `agent/`→`engine/` in Phase 4 (RL-2026-05-052). The manual dispatch path now honors remote-tool sessions for Terminal-Bench/local-hands runs (RL-2026-06-004). |
| `CodeGenEngineImpl` | `engine` | WORKHORSE | PATCH-mode codegen entry — SWE-bench / benchmark / autofix; direct `WorkerAgent` request construction with raised iter caps (Anthropic/DeepSeek/OpenRouter/Fireworks 220 / OpenAI 150 / Gemini 180) |
| `PatchAgent` | `agent` | WORKHORSE | Deprecated compatibility shell; no active call sites |
| `ConversationAgent` | `agent` | LIGHTWEIGHT | Post-run chat agent — generates ≤6-task suggestion plans |
| `RunTitleSummarizer` | `agent` | LIGHTWEIGHT | Distils user request into 5-9 word imperative title |
| `ReviewerAgent` | `reviewer` | PLANNING | General + wiring review passes; emits VERDICT: CLEAN / FIXED_ISSUES / UNRESOLVED |
| `CodeReviewAgent` | `codereview` | PLANNING | Whole-repo code review — triages Semgrep findings + adds arch/logic/security/perf issues |
| `PrReviewAgent` | `prreview` | PLANNING | PR review — verdict (APPROVE/REQUEST_CHANGES/COMMENT) + per-comment line annotations |
| `ContextAgent` | `context` | LIGHTWEIGHT | Maintains `CODEBASE_CONTEXT.md` via KEEP/UPDATE/ADD/REMOVE JSON patches |
| `RunLedgerAgent` | `memory` | LIGHTWEIGHT | Appends per-run digest to `project_run_ledger` |
| `PromptOptimiserAgent` | `optimiser` | WORKHORSE | Post-merge prompt pattern analyser (failure-rate ≥0.20 triggers) |
| `TestPlannerAgent` | `testgen` | WORKHORSE | Test generation planner — emits TestPlan |
| `TestWorkerAgent` | `testgen` | WORKHORSE | Test generation worker — writes integration tests |
| `ApiTestSuiteAgent` | `testgen` (post-merge) | WORKHORSE | API test suite generator |
| `RepoArchitectureGenerator` | `codeintel` | PLANNING | Step-2 per-repo HLD/LLD doc |
| `ArchitectureDocGenerator` | `codeintel` | PLANNING | Step-3 combined project arch doc |
| `ProjectDocGeneratorService` | `codeintel` | WORKHORSE | Generated docs (EXPLANATION + SETUP) |
| `ProjectBriefService` | `codeintel` | LIGHTWEIGHT | Architecture → 2-4-sentence summary |
| `SpecChatService` | `specchat` | LIGHTWEIGHT | CLI/VS Code common chat loop; composes run-control tools plus project/code tools when a project is linked |
| `FilePurposeSummaryService` | `codeintel` | LIGHTWEIGHT | Per-file 2-3 sentence purpose summaries (V196 cache) |
| `MemoryCompactionService` | `memory` | LIGHTWEIGHT | Rewrites `project_memory_snapshot` every 10 runs |
| `ProjectChatCompiler` | `memory` | PLANNING | Compile chat history → spec |
| `ProjectChatCompactor` | `memory` | LIGHTWEIGHT | Summarise older N messages, keep last 6 verbatim |
| `ToolOutputCompactor` | `tool` | LIGHTWEIGHT | Engine Phase 5+6 — summarise bulky worker tool output (>3 KB) before WorkerAgent appends to history (RL-2026-05-052). Default OFF (V216). |
| `TestReportGenerator` | `testgen` | LIGHTWEIGHT | Test execution report generation |
| `ValidationReportGenerator` | `report` | WORKHORSE | Validation report assembly |
| `AccuracyAssessmentService` | `reviewer` | (LLM judge) | Legacy `accuracyScore` floor for pre-V106 runs |

**LLM routers**: `agent/LlmRouter.java` (V2 native-tool-only; 4-attempt same-provider transient retry + BYOK-safe multi-provider failover behind `router.failover.enabled`, RL-2026-05-062 — closes audit M#68), `agent/FallbackLlmRouter.java` (legacy V1 with 4-attempt exp backoff, provider failover, circuit breaker). Direct provider clients: `agent/OpenAiNativeToolClient.java`, `agent/GeminiNativeToolClient.java`. Spring AI bean `AnthropicChatModel` for Anthropic.

---

## 4. Worker-facing tools

11 tools under `tool/` plus `CompleteTaskTool`. All implement `Tool` (name + description + JSON schema + execute). Worker has `gitCommit` filtered out via `ToolRegistry.WORKER_DENY_TOOLS`.

| Tool | Schema | Purpose |
| --- | --- | --- |
| `ReadFileTool` | `{path, startLine?, endLine?}` | Read file (1-indexed range); `project:` prefix reads project workspace with DB fallback |
| `WriteFileTool` | `{path, content}` | Create new file (errors if exists); FileSandboxPolicy + SSE `fileWritten` emit |
| `EditFileTool` | `{path, edits[{oldStr, newStr}]}` | Multi-edit string replacement; each oldStr must occur exactly once |
| `ListFilesTool` | `{path, recursive?}` | Directory listing; hides node_modules/target/build/dist/.git/.next/.venv/__pycache__/.gradle |
| `SearchFilesTool` | `{pattern, directory?, filePattern?, maxResults?, offset?}` | Recursive content search — ripgrep fast path (gitignore-aware) with a Java walk+regex fallback (RL-2026-05-062); paged; 240-char preview |
| `ShellTool` | `{command, timeoutSeconds?}` | Sandboxed shell; ShellSandboxPolicy allowlist; container routes to ProgramBench/SWE-bench |
| `GitStatusTool` | `{}` | `git status --short` of run workspace |
| `GitDiffTool` | `{}` | Working-tree diff (staged + unstaged), truncated to 3 KB |
| `GitCommitTool` | `{message}` | `git add -A` + `git commit`; VendoredPathGuard purge pre-commit; excluded from worker |
| `BuildProjectTool` | `{plannerCommand?}` | Run build command; MavenDependencyValidator pre-flight; container-aware; 4000-char output |
| `RunTestsTool` | `{test_ids, timeoutSeconds?}` | Structured pytest (SWE-bench/Python); typed `{summary, passed[], failed[], errored[], exit_code, raw_tail}` |
| `CompleteTaskTool` | `{summary}` | Structured task-complete signal — WorkerAgent intercepts to exit the loop |

Legacy V1-style facades coexist: `FileSystemTool` (Spring-AI `@Tool`-annotated, includes file-lock + CodeAbuseScanner), `GitTool` (worktree lifecycle + multi-repo push + JGit-backed merge/diff), `FileLockTool` (V1 multi-task contention).

Tool-output post-processing (RL-2026-05-052 Phase 5+6, default OFF behind `worker.tool-compactor.enabled` V216): `tool/ToolOutputCompactor` — LIGHTWEIGHT-tier (Haiku / Flash) service that summarises bulky tool output (>3 KB; not in `{writeFile, editFile, gitCommit, completeTask, gitDiff, gitStatus}`; not the most-recent call in the batch) before WorkerAgent appends to the worker's history. Fail-safe (any LLM error → raw). Not a worker tool itself — wraps tool output post-execute. Mirrors Anthropic's Claude Code CLI internal pattern.

Pre-dispatch arg validation (RL-2026-06-008, vix-leverage W1.3): `tool/ToolArgValidator` — a static, conservative, fail-open, **absent-only** validator run before `tool.execute` at both dispatch sites (`WorkerAgent.dispatchOne` + `ToolRegistry.ContextBoundToolCallback`); rejects a call omitting a schema-declared `required` field with `INVALID_ARGS`. Not a worker tool. Also (W1.5/B5) `ShellSandboxPolicy.BLOCKED_PATTERNS` now blocks in-place stream edits (`sed -i` / `sed --in-place` / `awk -i inplace`) that bypassed `EditFileTool`'s read-before-edit gate.

Post-edit diagnostics (RL-2026-06-009, vix-leverage W1.1 = "B1", flag `worker.edit-diagnostics.enabled` V232, default OFF): `tool/EditDiagnosticsService` — after a successful `editFile`/`writeFile` on a `.py` file, runs `python3 -m py_compile` (in the run's SWE-bench/ProgramBench container when registered, else host) and appends the first syntax error to the tool result, closing the edit→"what you broke" loop for the SWE-bench wrong-half-fix class. Java deferred (single-file `javac` is classpath-noisy). Null-guarded into both write tools; bounded (15s/6 lines) + fail-open. Not a worker tool.

Background command jobs (RL-2026-06-012, vix-leverage W4.2 = P6, flag `worker.background-jobs.enabled` V233, default OFF): `tool/BackgroundJobTool` (worker tool, `action=start|status|logs|kill`) + `tool/JobRegistry` (engine: start→jobId, status/tail/kill process-tree, `cleanup(runId)` run-end hygiene; sandbox-validated, env-sanitized, combined stdout+stderr to a temp log). Lets a worker run a server / slow build without blocking a foreground `shell` (300s cap). **Conditionally bound** — `ToolRegistry.isToolBound` keeps it ABSENT from the model schema when OFF (not bound-but-inert), so a schema-sensitive provider (DeepSeek) is unaffected (RL-2026-05-061). Host-side first cut; pre-flip TODOs: run-end `JobRegistry.cleanup` wiring + container/remote routing.

Code outline (RL-2026-06-016, vix-leverage W3.1, flag `worker.code-outline.enabled` V234, default OFF): `tool/ReadCodeOutlineTool` (worker tool) — JavaParser structural outline of a `.java` file (package/imports/types/methods/fields + line ranges, bodies omitted) so the worker localizes a symbol in a large file then `readFile`s only that range. Read-only + fail-safe (syntax error → "readFile it directly"). **Conditionally bound** via the new `tool/FlagGatedTool` marker interface + `ToolRegistry.isToolBound` (generalized from V233) — absent from the model schema when OFF (RL-2026-05-061 schema-safety).

Verify-before-complete (RL-2026-06-016, VBC, flag `worker.verify-before-complete.enabled` V235, default OFF): a 4th `completeTask` stage in `engine/WorkerAgent` that SERVER-RUNS the project's test suite (`smoketest/WorkspaceTestRunner` DIRECTLY — decoupled from V217's `runWorkspaceTests` tool) and refuses if red (`WorkerTaskRequest.verifyBeforeComplete`; bounded `VBC_GATE_MAX_REFUSALS=3` + fail-open). A *server gate, not a model tool* (no schema change → immune to the RL-2026-05-061 stall); distinct from the model-dependent `requireTestVerification`/`requireBuildVerification` SOFT gates. `CodeGenEngineImpl` for PATCH runs; `SequentialExecutor` BUILD final-task only; remote/TB fail-open. Not a worker tool.

---

## 5. JPA entities

127 entity/table classes in the current worktree. Grouped by domain.

### Auth + RBAC (`auth/entity/`)

| Entity | Table | Cols |
| --- | --- | --- |
| `AppUser` | `app_user` | ~36 (creds, tier, status, OAuth, encrypted per-user keys, V152 lifetime credits, V125 signup attribution, Wave 2.2 sandbox_project_id) |
| `Org` | `org` | ~40 (tier, status, monthly_run_count, encrypted per-org keys, V198 external_sharing_enabled, V162 is_personal, SSO, GitHub App install) |
| `OrgMember` | `org_member` | 5 (org/user/role/joined_at) |
| `OrgInvitation` | `org_invitation` | 11 (token, status, escalation_note, project_ids, expires) |
| `PersonalAccessToken` | `personal_access_token` | 9 (V215 — SHA-256 token_hash, prefix, scopes, expires_at, revoked_at, last_used_at) — programmatic auth for CLI/VS Code/CI (RL-2026-05-043) |
| `Project` | `project` | ~18 (org, name, status, ai_provider, branch_naming, default_reviewers, V140 spend_cap, V191 archived_at/deleted_at) |
| `ProjectMember` | `project_member` | 6 (V175 is_external) |
| `TierConfig` | `tier_config` | ~20 (per-tier quotas + V172 pr_review/code_review + V175 share_link_active_cap + V140 max_projects/repos) |
| `ActivityAudit` | `activity_audit` | 11 (M-019 SHA-256 hash chain prev/row/chain_seq) |
| `PaymentAudit` | `payment_audit` | ~15 (gateway, event_id, payment_id, amount_minor, metadata jsonb) |
| `RefreshTokenBlocklist` | `refresh_token_blocklist` | 4 (jti, user_id, blocked_at, expires_at) |
| `EmailToken` | `email_token` | 7 (type EMAIL_VERIFICATION / PASSWORD_RESET) |

### Billing (`billing/entity/`, `billing/cost/`)

| Entity | Table | Notes |
| --- | --- | --- |
| `BillingPlan` | `billing_plan` | Tier + price (INR + USD) + period + lifecycle_state |
| `BillingPlanLimit` | `billing_plan_limit` | Per-(plan, metric) max_value |
| `BillingSubscription` | `billing_subscription` | Razorpay + Stripe IDs, seats, status, billing_entity_type (USER/ORG) |
| `BillingUsage` | `billing_usage` | Per-(user, metric, period) counter |
| `BillingWebhookEvent` | `billing_webhook_event` | Webhook idempotency marker (gateway+event_id) |
| `BillingProfile` | `billing_profile` | GST profile (GSTIN, PAN, address) |
| `Invoice` | `invoice` | India-GST-compliant; CGST/SGST/IGST splits + seller/buyer snapshot + PDF path |
| `InvoiceLineItem` | `invoice_line_item` | SAC code 998314 default |
| `InvoiceSequence` | `invoice_sequence` | Per-FY counter (pessimistic locked) |
| `AccountingEntry` | `accounting_entry` | Double-entry journal (BANK/REVENUE/GST_OUTPUT/AR/REFUND × DEBIT/CREDIT) |
| `SellerConfig` | `seller_config` | Singleton (PK="DEFAULT") |
| `WebhookEventLog` | `webhook_event_log` | Audit log of every webhook hit |
| `TopupPackage` | `topup_package` | Purchasable credit packages |
| `RunTopup` | `run_topup` | Purchased / admin-granted credit bucket; FIFO consumed |
| `FounderPricingDiscount` | `founder_pricing_discount` | V177 — singleton offer (e.g. FOUNDER50_SOLO_LIFETIME) |
| `FounderPricingAllocation` | `founder_pricing_allocation` | V177 — per-user allocation row |
| `RunCostLog` | `run_cost_log` | Per-run cost vs estimate audit |
| `RunCostHistory` | `run_cost_history` | V187 — k-NN similarity rows for cost estimation Stage 2 |

### Run / pipeline (`state/entity/`, `agent/`, `harness/entity/`)

| Entity | Table | Notes |
| --- | --- | --- |
| `PipelineRun` | `pipeline_run` | ~80 cols — userRequest, status, plan_json, review_json, tokens, V168 pause cols, V178 user_cost_cap_usd, V182 run_mode, V183 triggered_by_finding |
| `AgentTask` | `agent_task` | Per-task row (LOCKED/PENDING/WORKING/COMPLETE/PARTIAL/FAILED) + V184 last_llm_failure TEXT + V190 failure_diagnostic_json |
| `AgentMessage` | `agent_message` | Inter-agent messages |
| `AgentInbox` | `agent_inbox` | Per-agent message queue |
| `RunRepo` | `run_repo` | Per-run repo metadata (mode, url, pushed_at) |
| `RunRepoSnapshot` | `run_repo_snapshot` | SHA captured at run start |
| `RunRecovery` | `run_recovery` | Recovery / restart trail |
| `IntegrationRun` | `integration_run` | Inbound integration run record |
| `IntegrationEventLog` | `integration_event_log` | Webhook idempotency (source+event_id) |
| `MergeConflict` | `merge_conflict` | Recorded merge conflicts (legacy) |
| `FileLock` | `file_lock` | V1 multi-task file ownership |
| `GateEvent` | `gate_event` | Approval gate event log |
| `CompactionEvent` | `compaction_event` | Token-budget compaction event log |
| `PlanVersion` | `plan_version` | Per-run plan version history |
| `PromptAuditEntry` | `prompt_audit_entry` | Prompt-file version history |
| `Deployment` | `deployment` | Cloud Run deployment row |
| `GateRecord` | `gate_record` | Durable approval-gate fallback (`harness/entity/`) |
| `PromptAudit` | `prompt_audit` | Per-assembled-prompt SHA-256 fingerprint (`agent/`) |

### Project workspace (`project/entity/`, `codeintel/entity/`, `memory/entity/`, `project/rag/entity/`)

| Entity | Table | Notes |
| --- | --- | --- |
| `ProjectRepo` | `project_repo` | Dual-scoped (ORG\|USER) repo registry + V154 subdir + V156 size_kb/attached_anyway + v2.6.2 executespec_folder_* |
| `ProjectRepoLink` | `project_repo_link` | Project↔repo join + relationships jsonb |
| `ProjectCodeMap` | `project_code_map` | Per-(project, repo) markdown codemap snapshot |
| `RepoArchitectureDoc` | `repo_architecture_doc` | Per-repo HLD/LLD + V174 cost_usd |
| `ProjectArchitectureDoc` | `project_architecture_doc` | Combined arch doc + V174 cost_usd + V192 generated_from_branches jsonb |
| `ProjectGeneratedDoc` | `project_generated_doc` | EXPLANATION/SETUP md + V174 cost_usd + V195 source_arch_version/fingerprint |
| `FilePurposeSummary` | `file_purpose_summary` | V196 per-file SHA-keyed 2-3 sentence summary cache |
| `RunCodeMap` | `run_code_map` | Paid-tier per-run codemap |
| `ProjectSpecTemplate` | `project_spec_template` | Reusable requirement skeleton (project + org scoped) |
| `ProjectChatMessage` | `project_chat_message` | Per-project chat history |
| `ProjectRunLedger` | `project_run_ledger` | Per-run digest |
| `ProjectMemorySnapshot` | `project_memory_snapshot` | Versioned compacted snapshot |
| `ProjectDocument` | `project_document` | RAG-indexed document; lifecycle UPLOADED→EXTRACTING→EMBEDDING→READY/FAILED |
| `ProjectConfig` | `project_config` | v2.6.2 E1.7 DATABASE-mode project config |

### PRs / code review / PR review

| Entity | Table | Notes |
| --- | --- | --- |
| `PullRequestRecord` | `pull_request_record` | V169 project_id + kind (RUN/BOOTSTRAP/ARCHITECTURE/CONTRACT/CHAT_TOOL) + V170 ci_auto_fix_attempts + V186 ci_status |
| `CodeReviewScan` | `code_review_scan` | Whole-repo scan (QUEUED→CLONING→STATIC_RUNNING→LLM_RUNNING→COMPLETE/FAILED) |
| `CodeReviewIssue` | `code_review_issue` | Per-issue rows (CRITICAL/MAJOR/MINOR/INFO × OPEN/RESOLVED/DISMISSED/FALSE_POSITIVE) |
| `PrReview` | `pr_review` | PR review (QUEUED/RUNNING/COMPLETE/FAILED; APPROVE/REQUEST_CHANGES/COMMENT) |
| `PrReviewComment` | `pr_review_comment` | Per-comment line annotation |
| `PrCommentInbox` | `pr_comment_inbox` | Incoming human PR comments (PENDING/ADDRESSED/DISMISSED) |

### V3 Execution

| Entity | Table | Notes |
| --- | --- | --- |
| `ExecutionRun` | `execution_run` | Top-level Launch + V155 selected_repo_ids + V159 migration_recipe + V160 readymade + V188 pipeline_run_id (unified-dispatch) |
| `ExecutionSolution` | `execution_solution` | One solution within a Launch (sequence + deps + repo_mappings EAGER) |
| `ExecutionSolutionRun` | `execution_solution_run` | Per-attempt V2 PipelineRun bridge |
| `ExecutionSolutionRepo` | `execution_solution_repo` | Per-solution × repo mapping |
| `ExecutionContextHandover` | `execution_context_handover` | INTERFACE/DIFF_CODEMAP/TEST_ANCHOR contracts |

### Cross-repo contracts (`contracts/`)

| Entity | Table | Notes |
| --- | --- | --- |
| `ContractRegistry` | `contract_registry` | Per-repo detected contract (OPENAPI/PROTO/GRAPHQL/JSON_SCHEMA/ASYNCAPI/EVENT_SCHEMA) |
| `ContractVersion` | `contract_version` | Versioned content snapshot (inline jsonb or GCS pointer) |
| `ContractConsumer` | `contract_consumer` | Consumer declaration per-(contract, consumer_repo, project) |
| `ContractDriftEvent` | `contract_drift_event` | Drift event (REFRESHED/DISMISSED resolution) |

### Standards / ADR / Invariants

| Entity | Table | Notes |
| --- | --- | --- |
| `OrgStandard` | `org_standard` | Org-level coding standard |
| `ProjectStandardOverride` | `project_standard_override` | Per-project override + exception |
| `StandardsViolation` | `standards_violation` | Recorded violation |
| `OrgConfigRepo` | `org_config_repo` | Org-level config repo binding |
| `Adr` | `adr` | Architecture Decision Record (frontmatter + body) |
| `ArchInvariant` | `arch_invariant` | Project arch invariant |
| `ArchInvariantViolation` | `arch_invariant_violation` | Recorded invariant violation |

### Other domains

| Entity | Table | Notes |
| --- | --- | --- |
| `Notification` | `notification` | In-app notification |
| `UserNotificationPref` | `user_notification_pref` | Per-(user, event_type, channel) preference |
| `OrgSlackIntegration` | `org_slack_integration` | OAuth bot token per workspace |
| `ProjectSlackChannel` | `project_slack_channel` | Per-(project, channel) mapping + verbosity |
| `SlackNotificationLog` | `slack_notification_log` | Sent notification audit |
| `ProjectInvite` | `project_invite` | V175 email-addressed invite |
| `ProjectShareLink` | `project_share_link` | V175 public read-only signed token |
| `DeploymentCredential` | `deployment_credential` | Encrypted GCP service-account key |
| `ActivityFeedEntry` | `activity_feed_entry` | Per-(project, month-partition) activity row |
| `ReferralInvite` | `referral_invite` | V153 persistent referral ledger |
| `UserActivationEvent` | `user_activation_event` | Activation funnel events (REGISTER/VERIFY/RUN_SUBMIT/RUN_COMPLETE/PR_MERGED) |
| `SideEffectOutboxEntry` | `side_effect_outbox` | Async retry persistence (GCS_ARCHIVE / etc.) |
| `ModelPricing` | `model_pricing` | YAML-overrideable per-(provider, model) pricing |
| `ModelTaskMetric` | `model_task_metric` | Per-(model, task) performance metric |
| `MigrationRecipe` | `migration_recipe` | V158 — migration recipe (Wave 1) |
| `MigrationVariant` | `migration_variant` | Per-recipe variant (options_signature) |
| `MigrationSlot` | `migration_slot` | Per-recipe slot definition |
| `MigrationDependency` | `migration_dependency` | Recipe→recipe dependency |
| `MigrationDryRunCache` | `migration_dry_run_cache` | Dry-run sample diff cache |
| `ReadymadeSolution` | `readymade_solution` | V157 — readymade building block |
| `ReadymadeVariant` | `readymade_variant` | Per-solution (language, framework, options) variant |
| `ReadymadeSlot` | `readymade_slot` | Per-solution slot definition |
| `ReadymadeDependency` | `readymade_dependency` | Solution→solution dependency |
| `ReadymadeFavourite` | `readymade_favourite` | Per-user favourite |
| `ValidationReport` | `validation_report` | Per-run report + V194 rubric_score; report DTO also exposes `verificationLedger[]` so deterministic checks, LLM assessments, and internal score summaries are distinguishable |
| `AgentSession` | `agent_session` | V240 - durable CLI/VS Code open-ended agent session, separate from `pipeline_run` |
| `AgentTurn` | `agent_turn` | V240 - per-session conversational turn with typed intent/action proposal and optional linked run |
| `AgentActionEvent` | `agent_action_event` | V240 - append-only event stream for intent/action/session events |
| `AgentUsageLedger` | `agent_usage_ledger` | V240 - separate meter for open-ended session usage; run credits remain tied to `pipeline_run` approval |
| `TestPlan` | `test_plan` | Test generation plan |
| `TestTask` | `test_task` | Per-test task |
| `TestResult` | `test_result` | Per-test result |
| `RunRubricRow` | `run_rubric_row` | V197 — planner-emitted rubric row |
| `PreviewMetric` | `preview_metric` | Preview VM metrics (gated) |
| `PreviewRegistry` | `preview_registry` | Active preview registry (gated) |

---

## 6. Flyway migrations — V170..V242

Head: **V242** in the current worktree. Full chronology in `src/main/resources/db/migration/V*.sql`.

| Version | Summary |
| --- | --- |
| V170 | `pull_request_record.ci_auto_fix_attempts` counter (CI autofix loop cap) |
| V171 | One-time backfill of `validation_report.pipeline_result` against `pipeline_run.status_reason` |
| V172 | Create `pr_review` / `pr_review_comment` / `code_review_scan` / `code_review_issue` + tier-gate cols on `tier_config` |
| V173 | Widen `*_by_user_id` columns from VARCHAR(36) to VARCHAR(255) in PR-Review / Code-Review |
| V174 | Add `cost_usd` to `repo_architecture_doc` + `project_architecture_doc` + `project_generated_doc` |
| V175 | External project sharing — `project_member.is_external` + `project_invite` + `project_share_link` + `tier_config.share_link_active_cap` |
| V176 | Un-hide PRO tier as "Solo" — $29/mo Solo Monthly USD/INR rows |
| V177 | Add Solo Yearly USD/INR + 2 founder-pricing tables; seed `FOUNDER50_SOLO_LIFETIME` |
| V178 | `pipeline_run.user_cost_cap_usd` (customer-tightened ceiling) |
| V179 | Drop V143 product split — drops `product_id` from 6 tables + `app_user_product_access` + `product_surface` ENUM |
| V180 | Seed `lint.autofix.enabled` PLATFORM feature flag (default true) |
| V181 | `pr_comment_inbox` for incoming PR review comments |
| V182 | `pipeline_run.run_mode` (BUILD\|PATCH) + partial index on PATCH |
| V183 | `pipeline_run.triggered_by_finding` for 4 customer-facing autofix surfaces |
| V184 | Widen `agent_task.last_llm_failure` from VARCHAR(255) to TEXT |
| V185 | Revert V176 Solo branding back to "Pro" — renames `tier_config.PRO.display_name` + 4 billing_plan rows |
| V186 | `pull_request_record.ci_status` / `ci_failing_count` / `ci_checked_at` + CHECK constraint |
| V187 | `run_cost_history` for k-NN cost similarity (Stage 2) |
| V188 | `execution_run.pipeline_run_id` — V3 ExecutionRun → V2 PipelineRun (unified-dispatch) |
| V189 | One-time backfill of `execution_run.total_cost_usd` + `total_actual_tokens` |
| V190 | `agent_task.failure_diagnostic_json` (structured worker diagnostics on non-COMPLETE) |
| V191 | `project.archived_at` + `project.deleted_at` + partial indexes (reversible archive + 30-day soft-delete) |
| V192 | `project_architecture_doc.generated_from_branches` jsonb (StalenessService same-branch probe) |
| V193 | `execution_run.failure_reason` TEXT |
| V194 | `validation_report.rubric_score` INTEGER (additive companion to multiplicative verifiedScore) |
| V195 | `project_generated_doc.source_arch_version` + `source_shas_fingerprint` (PR-Q short-circuit cache) |
| V196 | `file_purpose_summary` table (V196 SHA-keyed cache for codemap purpose summaries) |
| V197 | `run_rubric_row` (Scoring Revamp Phase A — planner-emitted rubric rows) |
| V198 | `org.external_sharing_enabled` BOOLEAN (V175 invite gate; default false) |
| V199 | One-time backfill — resolve `org.tier` vs `app_user.tier` / `billing_subscription` drift (BUG-009) |
| V200 | Bump FREE tier 5 → 15 runs/month (reverses V167) across `tier_config` / `billing_plan` / `billing_plan_limit` |
| V201 | Seed `scoring.additive-v2` PLATFORM flag (default true) — additive verifiedScore rebuild |
| V202 | Seed `reviewer.third-pass.enabled` GLOBAL flag (default true) — fix-the-red-build reviewer pass |
| V203 | Seed `scoring.real-test-exec` GLOBAL flag (default true) — run worker tests in BUILD mode → real passed/total into TESTS slot (RL-2026-05-001) |
| V204 | Add `org.openrouter_api_key` — org-level OpenRouter BYOK key (Enterprise gateway, F1) |
| V205 | Add `org.model_overrides_json` — per-org model-matrix overrides (F3, Enterprise) |
| V206 | INR pricing kept ~10% below USD as USD strengthened — TEAM monthly INR `per_seat_inr` ₹3,199 → ₹3,399/seat; Pro/Solo INR unchanged |
| V207 | Deactivate stale duplicate active Pro-monthly plans (₹1,699 INR + $20 USD) left from the pre-V176 Solo launch |
| V208 | Widen `github_pat` VARCHAR(255) → TEXT on `app_user` + `pipeline_run` (longer GitHub tokens; RL-2026-05-024) |
| V208 | Widen `github_pat` VARCHAR(255) → TEXT (`app_user` + `pipeline_run`) — drop the brittle length cap ahead of longer GitHub tokens (`ghs_…` ~520 chars); metadata-only ALTER (RL-2026-05-024) |
| V215 | Create `personal_access_token` — programmatic auth for the CLI / VS Code / CI clients (SHA-256 hash, never plaintext; RL-2026-05-043). _(V209–V214 are logged in root `CLAUDE.md` + reason-log; this index wasn't backfilled for them.)_ |
| V216 | Seed `worker.tool-compactor.enabled = false` (DEFAULT OFF) — engine extraction Phase 5+6 prompt-cache discipline; routes bulk worker tool output (> 3 KB) through the LIGHTWEIGHT tier before appending to the WORKHORSE worker history. Mirrors Claude Code CLI pattern (RL-2026-05-052). |
| V229 | Seed `spec.chat.enabled = false` — gates the real `/api/spec/chat` agentic loop for the unified spec client Phase 1. |
| V230 | Seed `codeintel.tiered.enabled = false` — tiered codemap injection: T0 skeleton + spec-relevant per-file symbol blocks instead of head truncation (RL-2026-06-003). |
| V236 | Create `benchmark_run`, `benchmark_task_result`, `benchmark_event` — common in-app benchmark tracking/event sink. |
| V237 | Add `deepseek_api_key` + `fireworks_api_key` to `org` and `app_user` — provider key lanes for native DeepSeek and Fireworks BYOK. |
| V238 | Add benchmark token-tracking columns for benchmark run/task accounting. |
| V239 | Default on evidence-safe worker quality gates. |
| V240 | Create `agent_session`, `agent_turn`, `agent_action_event`, and `agent_usage_ledger` - CLI/VS Code agent-session protocol separate from run billing. |
| V241 | Seed `worker.local-hands.enabled = false` and make localExecution submit fail closed unless enabled. |
| V242 | Add `app_user.preferred_ai_provider` for cross-surface provider consistency plus `/api/auth/me/provider-preference`. |

Earlier waves (V1..V169) span: V1 baseline, V20 Slack integration, V21 tier permissions, V22 activity audit, V23 billing system, V26 run_topup, V28 invoicing/GST, V30 deploy engine, V109 plan_requirements_json, V112 ExecutionRun `@Version`, V125 signup attribution, V135 contract_registry, V140 tier caps, V143 product_id (later dropped by V179), V145 FREE→10 (reverted by V167), V147 activity_audit hash chain, V149 BYOK plans, V152 lifetime credits, V155 selected_repo_ids, V156 size_kb / attached_anyway, V157 readymade tables, V158 migration_recipe tables, V162 org.is_personal, V165 drop MAX tier flatten TEAM to $39, V168 V2 pause/resume cols, V169 PR record project_id + kind.

---

## 7. LLM prompts

Files under `src/main/resources/prompts/`. Variables: `{{VAR}}`. Fragments: `{{include:fragment-name}}` resolved from `prompts/fragments/`.

### Top-level prompts

| File | Agent | Notes |
| --- | --- | --- |
| `classifier.md` | SmartClassifierAgent | Emits SINGLE_SHOT/SMALL/MEDIUM/LARGE/LEGACY |
| `outline-planner.md` | OutlinePlannerAgent (FULL_PLAN) | 32k tokens; emits `submitOutline` tool call |
| `outline-planner-skeleton.md` | OutlinePlannerAgent (SKELETON_ONLY) | 8k tokens; LARGE/LEGACY |
| `task-expander.md` | TaskExpanderAgent | Fills skeleton task body |
| `coherence-planner.md` | CoherencePlannerAgent | Emits `patchPlan` minimal-delta |
| `reviewer-general.md` | ReviewerAgent (general pass) | Emits VERDICT |
| `reviewer-wiring.md` | ReviewerAgent (wiring pass) | Project-type-aware (Rust mods, TS barrels, Spring scan) |
| `reviewer-v2.txt` | ReviewerAgent (V1 legacy) | Pre-2026-05-07 reviewer |
| `worker.txt` | WorkerAgent (Anthropic default) | Native tool-calling |
| `worker-openai.txt` | WorkerAgent (OpenAI variant) | Uses `readFile`/`run`/`commit` etc. |
| `worker-gemini.txt` | WorkerAgent (Gemini variant) | Snake_case tool names; per-response 10 tool-call cap |
| `patch-agent.md` | CodeGenEngineImpl | Patch-mode worker prompt for SWE-bench / benchmark / autofix |
| `compiler.txt` | (compile supervisor) | Legacy |
| `task-compile-supervisor.txt` | (compile supervisor) | Legacy |
| `triage.txt` | (triage agent) | Run-recovery loop classification |
| `conversation-system.md` | ConversationAgent | Pre-pipeline clarification chat |
| `context-agent.txt` | ContextAgent | CODEBASE_CONTEXT.md patches |
| `run-ledger.txt` | RunLedgerAgent | Per-run digest |
| `memory-compaction.txt` | MemoryCompactionService | 10-run snapshot rewrite |
| `validation-report.txt` | ValidationReportGenerator | CI-style summary |
| `test-planner.txt` | TestPlannerAgent | Integration test plan |
| `test-worker.txt` | TestWorkerAgent | Writes integration tests |
| `test-fix-suggestions.txt` | (testgen fixer) | Failure classification |
| `api-test-suite.txt` | ApiTestSuiteAgent | API test generation |
| `execution_planner.txt` | ExecutionPlannerService (legacy V3 split) | Multi-solution decomposition |
| `conflict-resolver.txt` | (merge conflict resolver) | Legacy |
| `planner.txt` | (current V1 planner) | Legacy main |
| `planner.v1.txt` .. `planner.v14.txt` | (archived planner versions) | PromptOptimiserAgent rollback targets |

### Subdirectories

**`prompts/scaffolds/`** (project-type-specific worker scaffolds):

`spring-boot.md`, `java.md`, `node-backend.md`, `node-frontend.md`, `python.md`, `go.md`, `rust.md`, `static-html.md`, `fullstack.md`, `react-native-expo.md`, `all.md`

**`prompts/fragments/`** (composable prompt fragments — resolved by `PromptAssembler`):

`preview-defaults.md`, `cors-config.md`, `security-rules.md`, `security-rules-planner.md`, `security-rules-worker.md`, `compilation-checklist.md`, `fullstack-directory.md`, `router-isolation.md`, `backend-robustness.md`, `module-system.md`, `api-url-rules.md`, `format-after-edit.md`

**`prompts/codereview/`**:

`code-review-agent.md` (CodeReviewAgent), `pr-review-agent.md` (PrReviewAgent)

**`prompts/eval/`** (model evaluation framework — fixed test prompts):

28 files spanning `workhorse-*` / `lightweight-*` / `planning-*` / `cost-*` test cases (workhorse-websocket-notification, lightweight-validation-report, cost-booking-system, lightweight-request-triage, cost-landing-page, planning-saga-diagnosis, workhorse-optimistic-locking, planning-saga-compliance, cost-rest-api-recipes, cost-ecommerce-store, workhorse-compilation-fix, cost-url-shortener, cost-ai-pdf-summarizer, planning-saga-consistency, cost-portfolio-blog, cost-saas-dashboard, planning-conflicting-requirements, planning-code-review, cost-fitness-dashboard, planning-plan-generation, lightweight-review-summary, planning-architecture-decision, cost-kanban-board, workhorse-react-component, cost-realtime-chat, etc.)

**`prompts/migrations/`** (referenced as `ai_fallback_prompt_path` from migration recipe YAMLs):

e.g. `prompts/migrations/js-to-typescript-fallback.md`

Total: ~88 prompt files. Prompt-resource verification at boot (`PromptResourceVerifier`) fails fast if any of 12 critical paths are missing.

---

## 8. Frontend — chrome + pages

The **Console shell** (`frontend/src/console/`) is the only protected web chrome since 2026-06-12; `App.tsx` mounts `ConsoleShell` for every protected route and `IdeWorkspace` is deleted. **The live protected surfaces remain route-level React modules** — see [`frontend/CLAUDE.md`](../frontend/CLAUDE.md): `ConsoleShell` / `ConsoleHome` / `ConsoleDecisions`; `IdeSurfaces` (Compose / Live) · `IdeRunResult` (flagship run detail) · `IdeProject(s|Tabs|More)` (project workbench, 10 sub-tabs) · `IdePlan` · `IdeMore` (Runs / Account / Deployments) · `IdeSettings` (Integrations / Admin) · `IdeOrg` (api-keys / org-members / org-settings + F3 model matrix) · `IdeBilling` (billing / plans) · `IdeSystem` (notifications / search / more / audit / empty-project) · `IdeDiscovery` (insights / library / migrations / templates) · `IdeKit` (code viewer + tokenizer).

The **2026-05-23 cleanup deleted 22 orphaned `signal/pages` files** (run-loop + `project/` + `account/`, incl. Billing/Plans) plus the dead `Shell.tsx`. The only `signal/pages/` that survive are the **standalone public pages + admin**, routed directly with no IDE chrome (by design):

| File | Exports / routes |
| --- | --- |
| `AuthPages.tsx` | `LoginPage` (`/login`), `SignupPage` (`/register`, `/signup`), `VerifyPage` (`/verify`, `/verify-email`), `WelcomePage` (`/welcome`) |
| `AuthCallbackPage.tsx` | OAuth2 callback (`/auth/callback`) |
| `ExtraPages.tsx` | `ForgotPasswordPage` (`/forgot-password`), `ResetPasswordPage` (`/reset-password`), `AcceptInvitePage` (`/accept-invite`), `PublicPricingPage` (`/pricing`), `TrustPage` (`/trust`) |
| `LegalPages.tsx` | `TermsPage` (`/terms`), `PrivacyPage` (`/privacy`), `DpaPage` (`/dpa`) |
| `MiscPages.tsx` | `AdminPage` (`/admin/*` deep links), `SharedPage` (`/shared/:token`, `/reports/shared/:token`, `/projects/snapshot/:token`) |

> The multi-export bundles (`ExtraPages`, `MiscPages`, `LegalPages`) still carry a few now-dead exports (e.g. `InsightsPage`, `NotificationsPage`, `OrgSettingsPage`, `DeploymentsPage`) whose routes moved to `ide/`. They're unimported + tree-shaken from the bundle; slated for removal in a follow-up.

---

## 9. Frontend Signal components

Under `frontend/src/signal/components/` — shared primitives still reused by the IDE chrome + the surviving public pages. CSS lives at `frontend/src/signal/signal.css` (re-mapped onto the `--ide-*` tokens by `ide/ide.css`). The Signal shell (`Shell.tsx`) was deleted in the 2026-05-23 cleanup; `Chrome.tsx` (TopBar / SectionHeader / …) survives because the public pages use it.

| Component | Purpose |
| --- | --- |
| `AuthLayout.tsx` | 2-column auth shell (form left, brand panel right ≥920px) |
| `Compose.tsx` | Compose primitives — `LiveHero`, `LiveHeroAction`, `ScoreCard`, `StreamCard`, `PrCard`, `ComposerCard`, `TaskLine` |
| `Primitives.tsx` | Base controls — `Feed`, `Card`, `Row`, `Chip`, `StatusPill`, `Button`, `KvList`, `TabsStrip` |
| `Chrome.tsx` | Legacy chrome primitives — `TopBar`, `TabBar`, `SectionHeader`, `IconButton` |
| `Modal.tsx` | Focus-trapping modal + `ConfirmModal` + `PromptModal` (replaces `window.confirm`/`window.prompt`) |
| `CostProgressBar.tsx` | Cost-vs-cap progress bar (green/amber/red) with ARIA progressbar role |
| `Loaders.tsx` | Loading primitives — `Spinner`, `LoadingState`, `SkeletonRow`, `InlineLoader` |
| `Markdown.tsx` | ~3KB custom GFM-subset markdown renderer (replaces ~80KB react-markdown) |
| `FileTree.tsx` | Folder-tree file browser (Result-page Code tab) |
| `FileIcon.tsx` | 20 inline-SVG language-aware file icons + folder icon (VS Code style) |
| `CodeView.tsx` | Lightweight per-line regex-tokenised code viewer + unified-diff renderer |
| `Glossary.tsx` | Hover/focus tooltip for ExecuteSpec jargon (5 terms: codemap, wiring pass, verified score, status reason, classifier) |
| `SpecChat.tsx` | Collapsible "Build my spec" pre-submit chat panel |
| `BrandLogos.tsx` | Inline-SVG brand marks — Google, GitHub, Slack, Jira, Confluence, Bitbucket, Stripe, Razorpay |

---

## 10. Feature flags

Flags read via `FeatureFlagService.isEnabled(key)` (DB-backed `feature_flag` table; seeded by Flyway). Known flag keys extracted from research notes:

| Flag | Default | Purpose |
| --- | --- | --- |
| `assessment.llm.enabled` | true | AccuracyAssessmentService LLM-judged scoring |
| `billing.lifecycle.sunsetting.enabled` | (cfg) | Enables tier SUNSETTING gateway cascade (cancel-at-period-end) |
| `build.test-exec-gate.enabled` | **false** (V209) | In-loop worker test-exec gate in BUILD per-task smoke (RL-2026-05-034); off ⇒ compile-only smoke |
| `ci.autofix.enabled` | **false** | Autofix Phase 3 — auto-respond to GitHub `check_run.failure` webhook |
| `codeintel.inject.enabled` | **false** (V210) | Inject codemap symbol-map into planner + PATCH prompt on project runs (RL-2026-05-035) |
| `codemap.purpose-summaries` | (cfg) | PR-N2 per-file LLM purpose summaries in codemaps |
| `contracts.breaking-change.enabled` | **false** (V213) | Classify touched-contract breaking changes on project runs → report-only run issues (RL-2026-05-037) |
| `codeintel.tiered.enabled` | **false** (V230) | Tiered codemap injection — T0 skeleton + spec-relevant per-file symbol blocks within the same budget instead of paths-only head truncation (RL-2026-06-003) |
| `scoring.gaming-guards` | **true** (V214) | Anti-gaming guards on the additive verifiedScore — CODE_DELIVERED↔endpoint-intent + TESTS↔coverage-quality blend; tighten-only (RL-2026-05-042) |
| `worker.tool-compactor.enabled` | **false** (V216) | Phase 5+6 engine extraction — summarise bulk worker tool output (>3 KB) through the LIGHTWEIGHT tier (`ToolOutputCompactor` → Haiku/Flash) before appending to the WORKHORSE worker context. Mirrors Claude Code CLI prompt-cache discipline (RL-2026-05-052) |
| `durability.task-checkpoint.enabled` | **true** (yaml) | GCS workspace checkpoint at each task boundary for mid-run durability; no-op when GCS unconfigured (RL-2026-05-042) |
| `enterprise.inject.enabled` | **false** (V211) | Inject `{{CONTRACTS}}` + `{{PROJECT_ADRS}}` into the planner prompt (RL-2026-05-035) |
| `executespec.strict-provider` | (`-D` flag) | Strict-provider mode (prevents ModelResolver silent provider switch) |
| `harness.v2.batch_enabled` | (cfg) | V2 batch-mode execution |
| `lint.autofix.enabled` | true (V180) | Phase L1 deterministic auto-formatter pass between executor and reviewer |
| `reviewer.third-pass.enabled` | **true** (V202) | Fix-the-red-build reviewer third pass (25-iter, 360s) when final build red after general+wiring |
| `routing.adaptive-routing.enabled` | (cfg) | Adaptive provider selection |
| `scoring.additive-v2` | **true** (V201) | Additive verifiedScore (CODE_DELIVERED+BUILD+TESTS+REVIEWER+CRITICALS), plus RL-2026-06 trust caps in code; off ⇒ legacy multiplicative |
| `scoring.real-test-exec` | **true** (V203) | BUILD mode runs worker tests in workspace → real passed/total into TESTS slot; off ⇒ LLM-coverage fallback (RL-2026-05-001) |
| `scoring.v2-verified` | (cfg) | Enables verifiedScore (vs legacy accuracyScore fallback) |
| `security.guardrail_level` | MEDIUM | LOW / MEDIUM / HIGH guardrail policy |
| `v2.pause.enabled` | **false** (V168) | V2 run pause/resume |
| `v3.anonymous-trial.real-dispatch` | false | R-111 real LLM dispatch for anonymous trial (vs stub) |
| `validation.v3-strict` | (cfg) | Strict requirement-graph validation in OutlinePlannerAgent |

Per-org flags (DB columns, not feature_flag rows):

| Org column | Default | Purpose |
| --- | --- | --- |
| `org.external_sharing_enabled` | **false** (V198) | Gates V175 cross-org `ProjectMember` invites; ORG_ADMIN/SUPER_ADMIN may toggle |
| `org.key_source_mode` | HYBRID | SYSTEM / OWN_KEYS / HYBRID (BYOK cost-cap exemption) |
| `org.domain_enforcement_mode` | OPEN | OPEN / ENFORCED — invite domain whitelist |

JWT epoch-based invalidation: per-user `app_user.token_invalidated_at` (set via `POST /api/auth/invalidate-sessions`); platform-wide JWT epoch managed via dedicated FeatureFlagService key.

---

## Cross-cutting key file locations

| Purpose | Path |
| --- | --- |
| Run orchestrator | `integration/RunOrchestrator.java` |
| V2 sequential executor | `harness/SequentialExecutor.java` |
| LLM router (V2 native) | `agent/LlmRouter.java` + `OpenAiNativeToolClient` + `GeminiNativeToolClient` |
| LLM router (legacy fallback) | `agent/FallbackLlmRouter.java` |
| Tool registry | `tool/ToolRegistry.java` |
| Context store | `context/ContextStore.java` |
| Cost tracker | `cost/RunCostTracker.java` |
| State store | `state/StateStore.java` |
| Startup resumer | `recovery/StartupRunResumer.java` |
| Finding chokepoint | `finding/FindingPatchSubmitter.java` |
| Model registry | `router/ModelRegistry.java` |
| Validation report | `report/ValidationReportService.java` |
| Project arch pipeline | `codeintel/ProjectArchitectureService.java` |
| Project memory | `memory/RunLedgerAgent.java` + `MemoryCompactionService` + `ProjectMemoryProvider` |
| GitHub integration | `repo/GitHubRepoService.java` |
| Multi-repo push | `integration/scm/ScmPushService.java` |
| SWE-bench adapter | `swebench/` |
| ProgramBench adapter | `programbench/` |
| Terminal-Bench adapter | `terminalbench/` — operator-only Harbor launcher that runs `WorkerAgent` in remote-brain/local-hands mode (`POST /api/v2/test/terminal-bench/start`) |
| Security config | `auth/security/SecurityConfig.java` |
| Billing service | `billing/service/BillingService.java` |
| Feature flags | `config/featureflag/FeatureFlagService.java` |
| Rate limiting | `ratelimit/RateLimitFilter.java` |
| PR Review agent | `prreview/` |
| Code Review agent | `codereview/` |
| `.executespec/` writer | `folder/ExecutespecFolderWriter.java` |
| Standards / ADR / Invariants | `standards/`, `adr/`, `arch/invariants/` |
| Cross-repo contracts | `contracts/` |
| Notification prefs | `notification/pref/` |
| GDPR export | `auth/service/UserDataExportService.java` |
| Prompt assembler | `agent/PromptAssembler.java` |
| App config | `src/main/resources/application.yml` |
| DB migrations | `src/main/resources/db/migration/V*.sql` (head V242 in the current worktree) |
| Frontend entry | `frontend/src/main.tsx` |
| Frontend API client | `frontend/src/lib/api/` (`core.ts` + domain modules) |
| CLI + VS Code clients | **Moved out 2026-06-07 → the standalone `executespec-cli` repo** (RL-2026-06-014). Was `clients/` (`@executespec/core` + `cli` + `vscode`, an npm workspace). Only the server-side OAuth device-login fix + the API endpoints they consume remain in this repo. |
