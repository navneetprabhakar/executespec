# ExecuteSpec — System Reference

> **Version:** 1.0.0-M1 (refreshed 2026-05-12).
>
> **What this doc is now:** a navigable index to the actual code, so contributors can jump from a concept to its concrete location. The previous incarnation of this file (3253 lines, last comprehensively refreshed at v2.4.0) tried to be a class-by-class Javadoc index — it drifted faster than it was useful. The authoritative class-level reference is **the source code itself**; this doc lists where to look.
>
> **Companions:** [`ARCHITECTURE.md`](ARCHITECTURE.md) (deep-dive system design), [`CODEMAP.md`](CODEMAP.md) (per-package structural index), root [`CLAUDE.md`](../CLAUDE.md) (operational snapshot).

## 1. How to find anything

| You want to find… | Look at |
|-------------------|---------|
| **A controller for an endpoint** | `grep -r "@PostMapping\|@GetMapping" src/main/java | grep '<path>'` or check [`openapi.yaml`](openapi.yaml). |
| **The service backing an endpoint** | Follow the controller import to its `*Service.java` neighbour. |
| **An agent's prompt** | `src/main/resources/prompts/<agent-slug>.md`. Fragments live in `prompts/fragments/`. |
| **A scaffold template for a project type** | `src/main/resources/prompts/scaffolds/<type>.md`. |
| **An entity / table** | `src/main/java/com/navneet/executespec/<package>/entity/`. Repository in the sibling `repository/`. |
| **A migration** | `src/main/resources/db/migration/V*.sql`. Head V186. |
| **A feature flag** | Seeded in a Flyway migration; read by `FeatureFlagService.isEnabled(key)`. Admin-managed via `/api/feature-flags/{key}`. |
| **An API client (frontend)** | `frontend/src/lib/api.ts`. |
| **A UI store** | `frontend/src/stores/use*Store.ts`. |
| **A UI component** | `frontend/src/components/<area>/*.tsx`. V3 surface under `frontend/src/v3/`. |

## 2. Pipeline entry points

| Layer | File |
|-------|------|
| Run submission HTTP | `api/controller/RunController.java` |
| Pipeline orchestrator | `integration/RunOrchestrator.java` |
| Classifier | `agent/SmartClassifierAgent.java` |
| Outline planner (FULL_PLAN / SKELETON_ONLY) | `agent/OutlinePlannerAgent.java` |
| Task expander (parallel for LARGE/LEGACY) | `agent/TaskExpanderAgent.java` |
| Coherence patcher | `agent/CoherencePlannerAgent.java` |
| Plan strategy circuit breaker | `agent/PlanStrategyCircuitBreaker.java` |
| Plan gate | `harness/GateStore.java` |
| Sequential executor | `harness/SequentialExecutor.java` |
| Worker | `agent/WorkerAgent.java` |
| Patch-mode agent | `agent/PatchAgent.java` |
| Reviewer (general + wiring) | `reviewer/ReviewerAgent.java` |
| Accuracy assessment | `reviewer/AccuracyAssessmentService.java` |
| Validation report | `report/ValidationReportService.java` (+ `ValidationReportGenerator`, `ValidationReportPdfService`) |
| Scoring | `scoring/ReviewScoringService.java` |
| Test generation | `testgen/TestGenOrchestrator.java` (+ `TestPlannerAgent`, `TestWorkerAgent`) |
| Preview | `deployment/preview/PreviewService.java` |
| Multi-repo push | `integration/scm/ScmPushService.java` |
| Recovery | `recovery/StartupRunResumer.java` |
| Finding chokepoint | `finding/FindingPatchSubmitter.java` |

## 3. LLM routing + cost

| Concern | File |
|---------|------|
| Canonical provider enum (ANTHROPIC / OPENAI / GEMINI) | `router/LlmProvider.java` |
| Model registry (YAML-driven single source of truth) | `router/ModelRegistry.java` |
| YAML binding | `router/ModelMatrixProperties.java` |
| Native-tool LLM router (V2) | `agent/LlmRouter.java` |
| OpenAI native-tool client | `agent/OpenAiNativeToolClient.java` |
| Gemini native-tool client | `agent/GeminiNativeToolClient.java` |
| Fallback router (non-tool agents) | `agent/FallbackLlmRouter.java` |
| Per-call cost tracker | `cost/RunCostTracker.java` |
| Cost estimator | `agent/CostEstimator.java` |
| Provider health (circuit breaker) | `router/ProviderHealthTracker.java` |
| Smart routing (heuristic) | `router/ProviderRecommendationService.java` |

## 4. State + persistence

| Concern | File |
|---------|------|
| State store (single chokepoint) | `state/StateStore.java` |
| `PipelineRun` entity | `state/entity/PipelineRun.java` |
| `AgentTask` entity | `state/entity/AgentTask.java` |
| Run-repo snapshot (reset-to-original) | `state/entity/RunRepoSnapshot.java` |
| Worker context snapshot (Redis) | `context/ContextStore.java` |
| Validation report entity | `report/entity/ValidationReport.java` |
| Pull request record | `project/pr/PullRequestRecord.java` |
| Project memory entities | `memory/entity/ProjectRunLedger.java`, `memory/entity/ProjectMemorySnapshot.java`, `memory/entity/ProjectChatMessage.java` |
| Activity feed | `activity/entity/ActivityFeedEvent.java` |
| Standards / ADR / Invariants | `standards/entity/*`, `adr/Adr.java`, `arch/invariants/entity/*` |
| Contracts | `contracts/entity/*` |

## 5. Auth + tenancy

| Concern | File |
|---------|------|
| Spring Security config | `auth/security/SecurityConfig.java` |
| JWT utility (incl. scoped SSE tokens) | `auth/security/JwtUtil.java` |
| JWT secret validator (prod startup) | `auth/security/JwtSecretValidator.java` |
| OAuth2 success handler | `auth/oauth/OAuthSuccessHandler.java` |
| OAuth surface resolver | `auth/oauth/OAuthSurfaceResolver.java` + `OAuthInitController.java` |
| Product-surface filter (dormant post-V179) | `auth/security/ProductSurfaceFilter.java` |
| Access guards | `auth/security/RunAccessGuard.java`, `auth/security/ProjectAccessGuard.java` |
| Tier resolver | `auth/service/EntitlementResolver.java` |
| Tier enforcement | `auth/service/TierEnforcementService.java` |
| Tier permissions | `auth/service/TierPermissionService.java` |
| Personal-org bootstrap | `auth/service/OrgService.java#bootstrapPersonalOrg` |
| GDPR data export | `auth/service/UserDataExportService.java` |
| Tenant filter / interceptor | `tenant/TenantFilter.java`, `tenant/TenantInterceptor.java` |

ArchUnit pins:
- `arch/AccessGuardCoverageTest.java` — every controller method whose URL touches `/runs/{` / `/projects/{` / `/executions/{` / `/launches/{` / `/repos/{` calls a tenant access guard. 10 sites in `KNOWN_LEGACY_DRIFT`.
- `arch/ApiContractTest.java` — every URL in `frontend/src/lib/api.ts` resolves to a registered backend `@*Mapping`.
- `arch/ExecutespecFolderWriterCallerAllowlistTest.java` — `ExecutespecFolderWriter#writeFiles` callable from 4 approved sites only.
- `arch/AsyncAllowlistTest.java` — `@Async` methods are on a curated allowlist (regression guard against the `@Async` + lazy-JPA class of bugs).
- `arch/FailoverOrderEnumAlignmentTest.java` — `failover-order` YAML entries resolve to valid `LlmProvider` enum constants.

## 6. Billing

| Concern | File |
|---------|------|
| Service | `billing/service/BillingService.java` |
| Gateway router | `billing/gateway/PaymentGatewayRouter.java` |
| Razorpay gateway | `billing/gateway/RazorpayPaymentGateway.java` |
| Stripe gateway | `billing/gateway/StripePaymentGateway.java` |
| Invoice + GST | `billing/service/InvoiceService.java` |
| Payment audit | `billing/service/PaymentAuditService.java` |
| Tier lifecycle | `billing/lifecycle/TierLifecycleService.java` + `TierLifecycleController.java` |
| Founder pricing | `billing/founder/FounderPricingService.java` |
| Run cost log | `billing/cost/RunCostLog.java` |
| Webhook handlers | `billing/webhook/RazorpayWebhookController.java`, `StripeWebhookController.java` |

## 7. Project workbench

| Concern | File |
|---------|------|
| Project service | `auth/service/ProjectService.java` |
| Project repo (ORG/USER scope) | `project/repo/ProjectRepoService.java`, `project/repo/ProjectRepoController.java` |
| Project workspace (disk mirror) | `project/workspace/ProjectWorkspaceService.java` + `ProjectWorkspaceWarmer.java` |
| Workspace health | `project/workspace/WorkspaceHealthService.java` |
| 3-step architecture | `codeintel/CodeMapGenerator.java`, `codeintel/RepoArchitectureGenerator.java`, `codeintel/ArchitectureDocGenerator.java`, `codeintel/ProjectArchitectureService.java` |
| Architecture diff | `codeintel/ArchitectureDiffService.java` |
| Generate Docs | `codeintel/ProjectDocGeneratorService.java` |
| Staleness | `codeintel/StalenessService.java` |
| Feature assessment | `assessment/FeatureAssessmentService.java` |
| Tech debt | `project/techdebt/TechDebtService.java` + `TechDebtFixService.java` |
| RAG | `project/rag/RetrievalService.java`, `project/rag/EmbeddingService.java`, `project/rag/ChunkStore.java`, `project/rag/DocumentExtractor.java`, `project/rag/ChunkingService.java` |
| Project memory | `memory/RunLedgerAgent.java`, `memory/MemoryCompactionService.java`, `memory/ProjectMemoryProvider.java` |
| Project chat | `memory/ProjectChatService.java`, `memory/ProjectChatCompactor.java`, `memory/ProjectChatCompiler.java`, `memory/chattools/ProjectChatTools.java` |
| Estimation | `project/estimation/EstimationService.java` |
| Spec templates | `project/template/SpecTemplateService.java` |
| PR record + webhook | `project/pr/PullRequestRecordService.java`, `project/pr/GitHubWebhookController.java`, `project/pr/CiFailureAutoFixService.java` |

## 8. Customer git surface — `.executespec/` folder

| Concern | File |
|---------|------|
| Write chokepoint | `folder/ExecutespecFolderWriter.java` |
| Bootstrap | `folder/BootstrapService.java` |
| README generator | `folder/FolderReadmeGenerator.java` |
| Config path resolver | `folder/ProjectConfigPathResolver.java` |
| Admin migration | `folder/ExecutespecMigrationController.java` |
| Activity feed | `activity/ActivityEventEmitter.java`, `activity/ActivityFeedController.java`, `activity/ActivityFeedRetentionJob.java`, `activity/ActivityFeedPartitionJob.java` |
| Contracts (cross-repo registry) | `contracts/breaking/`, `contracts/consumers/`, `contracts/coverage/`, `contracts/detection/`, `contracts/drift/`, `contracts/injection/` |
| Standards | `standards/EffectiveStandardsResolver.java` + `standards/ast/`, `standards/llmreview/`, `standards/override/StandardsOverrideController.java`, `standards/report/StandardsComplianceController.java` |
| ADRs | `adr/AdrFrontmatterParser.java`, `adr/AdrInjectionService.java` |
| Invariants | `arch/invariants/InvariantChecker.java` |
| Notification prefs | `notification/pref/NotificationPrefService.java` |
| Export | `export/ProjectExportController.java` |

## 9. PR Review + Code Review (V172)

| Concern | File |
|---------|------|
| PR Review service | `prreview/PrReviewService.java` |
| PR Review agent | `prreview/PrReviewAgent.java` |
| PR Review controller | `prreview/PrReviewController.java` |
| PR Review autofix | `prreview/PrReviewAutofixService.java` |
| Code Review service | `codereview/CodeReviewService.java` |
| Code Review agent | `codereview/CodeReviewAgent.java` |
| Code Review controller | `codereview/CodeReviewController.java` |
| Code Review autofix | `codereview/CodeReviewAutofixService.java` |
| Semgrep runner | `codereview/SemgrepRunner.java` |
| Markdown renderer | `codereview/CodeReviewMarkdownRenderer.java` |

## 10. External sharing (V175)

| Concern | File |
|---------|------|
| Cross-org member service | `sharing/ProjectMemberService.java` |
| Public share-link service | `sharing/ProjectShareLinkService.java` |
| Invite service | `sharing/ProjectInviteService.java` |
| Controllers | `sharing/ProjectInviteController.java`, `sharing/ProjectShareLinkController.java`, `sharing/PublicSharedController.java` |

## 11. Bench-mode adapters

| Concern | File |
|---------|------|
| SWE-bench adapter | `swebench/SwebenchTaskService.java`, `swebench/SwebenchController.java` |
| ProgramBench adapter | `programbench/ProgramBenchTaskService.java`, `programbench/ProgramBenchController.java`, `programbench/ProgramBenchContainerStore.java`, `programbench/ProgramBenchBootstrap.java`, `programbench/ProgramBenchProperties.java` |

## 12. Integrations

| Integration | File(s) |
|-------------|---------|
| Slack | `integration/slack/SlackNotificationService.java`, `integration/slack/SlackOAuthController.java` |
| JIRA | `integration/jira/JiraAdapter.java`, `integration/jira/JiraWebhookController.java` |
| Confluence | `integration/confluence/ConfluenceAdapter.java` |
| Bitbucket | `integration/bitbucket/BitbucketAdapter.java` + `repo/BitbucketRepoService.java` |
| PagerDuty | `integration/pagerduty/PagerDutyAdapter.java` |
| ServiceNow | `integration/servicenow/ServiceNowAdapter.java` |
| GitHub | `repo/GitHubRepoService.java` + `project/pr/GitHubWebhookController.java` |
| Integration manager | `integration/IntegrationManager.java` |

## 13. Tools (worker)

| Tool | File |
|------|------|
| Registry | `tool/ToolRegistry.java` |
| Read file | `tool/ReadFileTool.java` |
| Write file | `tool/WriteFileTool.java` |
| Edit file | `tool/EditFileTool.java` |
| List files | `tool/ListFilesTool.java` |
| Search files | `tool/SearchFilesTool.java` |
| Shell | `tool/ShellTool.java` |
| Git status | `tool/GitStatusTool.java` |
| Git diff | `tool/GitDiffTool.java` |
| Git commit | `tool/GitCommitTool.java` |
| Build project | `tool/BuildProjectTool.java` |
| Run tests (patch-mode only) | `tool/RunTestsTool.java` |
| Complete task (native termination) | `agent/CompleteTaskTool.java` |
| Shell sandbox | `tool/ShellSandboxPolicy.java` |
| File sandbox | `tool/FileSandboxPolicy.java` |
| Process executor | `tool/ProcessExecutor.java` |

`ToolRegistry.bindForWorker(ctx)` filters `gitCommit` from worker authority (auto-push happens at run-end via `ScmPushService.pushMultiRepo`).

## 14. Security

| Concern | File |
|---------|------|
| Input sanitiser | `security/InputSanitizer.java` |
| Code abuse scanner | `security/CodeAbuseScanner.java` |
| Guardrail config | `security/guardrail/GuardrailLevel.java` + `GuardrailService.java` |
| Crypto / KMS | `crypto/` |
| Rate limit filter | `ratelimit/RateLimitFilter.java` |
| Security headers | `config/SecurityProperties.java` |

## 15. Observability

| Concern | File |
|---------|------|
| Custom V2 metrics | `observability/Metrics.java` |
| OTel config | `config/ObservabilityConfig.java` |
| Custom metrics config | `config/CustomMetricsConfig.java` |
| SSE broadcaster | `sse/SseBroadcaster.java` + `sse/RunEvent.java` + `sse/ExecutionEventTypes.java` |

## 16. Feature flags + rate limiting

| Concern | File |
|---------|------|
| Feature flag service | `config/featureflag/FeatureFlagService.java` |
| Flag admin controller | `config/featureflag/FeatureFlagController.java` |
| Rate limit groups | `ratelimit/RateLimitPolicy.java` |

## 17. REST API reference

Every controller lives under `com.navneet.executespec.*.controller` (or `api/controller/`). The canonical endpoint inventory is [`openapi.yaml`](openapi.yaml); root [`CLAUDE.md`](../CLAUDE.md) § API Contracts has the human-readable tables grouped by capability.

Quick command:
```bash
grep -r "@RequestMapping\|@PostMapping\|@GetMapping\|@PutMapping\|@DeleteMapping" \
  --include "*Controller.java" src/main/java | wc -l   # currently 98 controllers
```

## 18. Recovery system

| Concern | File |
|---------|------|
| Startup resumer | `recovery/StartupRunResumer.java` |
| Crash resume entry point | `integration/RunOrchestrator.java#resumeAfterCrash(runId)` |
| Worker resume entry | `agent/WorkerAgent.java#run(req, SavedContext resumeFrom)` |
| Saved context | `context/ContextStore.java#save / load` |

3-attempt cap; AWAITING_APPROVAL untouched on boot; full replay reloads `v2:agent-ctx:{runId}:{taskId}` snapshot and resumes worker at saved iteration.

## 19. Configuration reference

Every config knob lives in `src/main/resources/application.yml` (with `application-dev.yml`, `application-prod.yml`, `application-local.yml` overrides). Highlights:

| Section | Purpose |
|---------|---------|
| `model-matrix.*` | Per-provider per-tier models + pricing + failover + display labels + context windows. `ModelRegistry` reads this. |
| `model-pricing[]` | Per-model `input-per-1m` / `output-per-1m` / `cached-input-per-1m`. Read by `RunCostTracker.priceCall`. |
| `agent-tier-map` | Agent class name → tier mapping (PLANNING / WORKHORSE / LIGHTWEIGHT). |
| `failover-order` | Per-provider fallback chain when one fails. |
| `agent.workspace` | Workspace root dir (default `./workspace`). |
| `preview` | Mode (cloud-run / docker), TTL, concurrency. |
| `billing.default-gateway` | `razorpay` / `stripe`. Prod defaults to stripe. |
| `billing.stripe.enabled` | Hard-off switch. |
| `security.guardrail_level` | Default LOW; can be set MEDIUM / HIGH per org via feature flag. |
| `feature-flag.seeds` | Bootstrap values applied on first boot. |
| `spring.flyway.out-of-order` | True (tolerate V119 orphan from preview-engine split). |
| `spring.flyway.ignore-migration-patterns` | `*:missing`. |
| `spring.jpa.open-in-view` | False. |
| `spring.jpa.properties.hibernate.connection.handling_mode` | `DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION`. |
| `spring.datasource.hikari.leak-detection-threshold` | 90000ms. |
| `spring.mvc.async.request-timeout` | 900000ms (for apply-suggestion + long handlers). |
| `agent.deployment.enabled` | Gates `POST /api/deployments`. |
| `github.webhook.secret` | HMAC SHA-256 secret for `/webhooks/github`. |
| `telegram.bot-token` + `telegram.chat-id` | Local-only operator bridge. |

## 20. Resource tree

```
src/main/resources/
├── application.yml + application-{dev,prod,local}.yml
├── db/migration/V*.sql                  # 186 Flyway migrations (immutable post-apply)
├── prompts/
│   ├── classifier.md
│   ├── outline-planner.md
│   ├── outline-planner-skeleton.md
│   ├── task-expander.md
│   ├── coherence-planner.md
│   ├── worker.md
│   ├── reviewer.md
│   ├── accuracy-assessment.md
│   ├── pr-review.md
│   ├── code-review.md
│   ├── fragments/                       # composable {{include:name}} fragments
│   └── scaffolds/                       # per-project-type templates
│       ├── spring-boot.md
│       ├── node-backend.md
│       ├── node-frontend.md
│       ├── python.md
│       ├── go.md
│       ├── rust.md
│       └── react-native-expo.md
├── sample-specs/{web-api,frontend,cli}.yml   # 3 onboarding sample specs
├── readymades/*.yml                     # Readymade Solutions catalog
├── migration-recipes/*.yml              # Migration recipes catalog
├── templates/
│   ├── email/*.html                     # 21 templates (palette unified v2.6.0)
│   └── report/validation-report-pdf.html
└── static/                              # frontend bundle (built into JAR)
```

## 21. Migration history (highlights)

Full per-migration index in [`CODEMAP.md`](CODEMAP.md) §Migrations. Range summaries in [`DEPLOYMENT.md`](DEPLOYMENT.md) §1.

Notable shapes:
- V120 — LlmProvider GOOGLE → GEMINI rename. Rewrites legacy rows.
- V130 — hierarchical planner schema.
- V131–V139 — enterprise readiness (`.executespec/` folder, activity feed, contracts, standards, ADR, invariants, notification prefs).
- V165 — drop MAX, rename to TEAM @ $39.
- V172 — PR + Code review agent tables.
- V175 — external project sharing.
- V178 — `pipeline_run.user_cost_cap_usd`.
- V179 + V185 — drop product split + retire Solo branding.
- V182 — `pipeline_run.run_mode = BUILD | PATCH`.
- V183 — `pipeline_run.triggered_by_finding`.
- V184 — widen `agent_task.last_llm_failure` VARCHAR(255) → TEXT.
- V186 — pull_request CI status columns (PR refresh polls check-runs).

## 22. Frontend reference

| Concern | File |
|---------|------|
| App entry | `frontend/src/main.tsx` |
| API client (single file) | `frontend/src/lib/api.ts` |
| Auth store | `frontend/src/stores/useAuthStore.ts` |
| UI store (theme) | `frontend/src/stores/useUiStore.ts` |
| Run store | `frontend/src/stores/useRunStore.ts` |
| Project store | `frontend/src/stores/useProjectStore.ts` |
| V3 project store | `frontend/src/v3/stores/useV3ProjectStore.ts` |
| V2 shell | `frontend/src/components/layout/AppLayout.tsx` |
| V3 shell | `frontend/src/v3/Shell.tsx` |
| Tokens | `frontend/src/tokens.css` + `tailwind.config.ts` |
| Style config | `frontend/.prettierrc.json`, `.eslintrc.cjs`, `.prettierignore` |
| A11y gate | `frontend/.pa11yci.json` |

## 23. Tests

- Unit tests live alongside source under `src/test/java/.../`.
- ArchUnit tests at `src/test/java/com/navneet/executespec/arch/`.
- Integration tests tagged `@integration` (run via `mvn test -Pintegration`).
- E2E + chaos excluded from the unit suite via surefire excludes.
- Frontend tests use vitest-shape signatures but vitest is not wired into CI today (see [`design/code-style.md`](design/code-style.md) deviations).

## 24. Eval framework

- `scripts/v2-eval.sh` — LOW/MEDIUM/HIGH × Anthropic/OpenAI/Gemini matrix. Outputs JSON + markdown to `scripts/v2-eval-results/`.
- `scripts/v2-eval-specs/{low,medium,high}.txt` — eval specs.
- `eval/EvalController.java` + `eval/ModelEvaluator.java` — backend driver.

## 25. Where the source-of-truth lives

| For | Look at |
|-----|---------|
| Pipeline shape | `integration/RunOrchestrator.java` + `harness/SequentialExecutor.java` |
| Models / pricing / tiers | `application.yml` `model-matrix:` |
| API contract | `openapi.yaml` + `frontend/src/lib/api.ts` (cross-checked by `ApiContractTest`) |
| Schema | `db/migration/V*.sql` + per-package `entity/` |
| Prompt templates | `prompts/*.md` + `prompts/fragments/` + `prompts/scaffolds/` |
| Tier limits | `tier_config` table (DB-driven, seeded by migrations) |
| Feature flags | `feature_flag` table + seeded by migrations |
| Roles | `auth/service/TierPermissionService.java` + `application.yml` role-permission mapping |
| Migrations index | [`CODEMAP.md`](CODEMAP.md) §Migrations |
| Current state | root [`CLAUDE.md`](../CLAUDE.md) |
| Deep-dive design | [`ARCHITECTURE.md`](ARCHITECTURE.md) |
| User flows | [`USER_MANUAL.md`](USER_MANUAL.md) + [`WORKFLOWS.md`](WORKFLOWS.md) + [`user-journeys.md`](user-journeys.md) + [`project-user-journeys.md`](project-user-journeys.md) |
| RBAC | [`RBAC.md`](RBAC.md) |
| Pending work | [`plans/pending.md`](plans/pending.md) |
