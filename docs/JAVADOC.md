# ExecuteSpec -- Complete System Documentation

**Version:** v2.4.0 (2026-04-14)

> **Project Shell + Launch (v2.4.0)** — adds `ProjectLaunchController` + `ScaffoldController` + `ExecutionSolutionRun` entity + `ProjectRepoService.createAndLinkNewRepo`. ExecutionView gate widened to ENTERPRISE + MAX. New-repo creation supported as part of a Launch via `NewRepoSpec`. Multi-run solutions persist per-attempt history. Migration V90.

> **Execution View (v2.3.0)** — new package `execution/` hosts `ExecutionController`, orchestrator / planner / solution-executor / branch / cross-repo-context / incremental-codemap services, pure-logic `SolutionDependencyResolver` + `FileConflictDetector`, entities `ExecutionRun` / `ExecutionSolution` / `ExecutionSolutionRepo` / `ExecutionContextHandover`, and `ExecutionEventTypes`. Bridges to `V2RunOrchestrator` per solution. Feature-flag + ENTERPRISE-tier gated. Migration V89.
>
> **Project memory (v2.2.0)** — package `memory/` hosts `RunLedgerAgent`, `MemoryCompactionService`, `ProjectMemoryProvider`, `ProjectMemoryController` plus entities `ProjectRunLedger` / `ProjectMemorySnapshot`. Wired into `V2RunOrchestrator` post-complete and into `PlannerAgentV2` via `PlannerRequestV2.projectMemory`. Migration V88.
**Last Updated:** 2026-04-14
**Stack:** Java 21, Spring Boot 3.3, Spring AI 1.1.4, PostgreSQL, Redis, JGit 6.9.0

> **Pilot v3.0 additions (v2.1.1)** — the package list below predates V2 and
> the pilot-v3 layer. Current canonical architecture lives in
> [CLAUDE.md](../CLAUDE.md); new packages to be aware of:
> - `com.navneet.executespec.project` — dual-scope `ProjectRepo` registry,
>   `ProjectRepoLink` join, project dashboard, PR records + GitHub webhook,
>   stack detection + scheduled refresh.
> - `com.navneet.executespec.codeintel` — `ProjectCodeMap`,
>   `ProjectArchitectureDoc`, `StalenessService`, `RunCodeMapService`
>   (paid-tier codemap-on-clone).
> - `com.navneet.executespec.assessment` — pre-plan `FeatureAssessmentService`
>   (heuristic + LLM-gated via `assessment.llm.enabled` flag).
> - `com.navneet.executespec.scoring` — `ReviewScoringService`
>   (six-dimension deterministic scoring), `TestCoverageRescorer`.
> - `com.navneet.executespec.v2.agent.SpecClassifier` — deterministic
>   SINGLE_SHOT / MEDIUM / MULTI_TASK picker from spec text.
> - New migrations V81–V90 documented in CHANGELOG [2.1.1] + [2.2.0] + [2.3.0] + [2.4.0].

> **⚠️ Historical reference — V1 architecture.**
> This document describes the original V1 harness (`PipelineHarness`, `WorkerAgent`,
> `CompilerAgent`, `ReviewerAgentV2`, `batch/*`). V1 is frozen pending deletion
> per [docs/plans/deprecation-removal-plan.md](plans/deprecation-removal-plan.md).
>
> **Canonical architecture lives in [CLAUDE.md](../CLAUDE.md).** Start there for
> current V2 internals (`V2RunOrchestrator`, `PlannerAgentV2`, `WorkerAgentV2`,
> `SequentialExecutorV2`, `ReviewerAgentV3`, `LlmRouterV2`, `ToolRegistryV2`).
>
> A full rewrite of this file will happen as part of the deprecation plan.

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Architecture Diagram](#2-architecture-diagram)
3. [Package Structure](#3-package-structure)
4. [Core Pipeline Flow](#4-core-pipeline-flow)
5. [Agent System](#5-agent-system)
6. [Tool System](#6-tool-system)
7. [State Management](#7-state-management)
8. [Pipeline Harness](#8-pipeline-harness)
9. [Model Routing](#9-model-routing)
10. [Auth and Multi-Tenancy](#10-auth-and-multi-tenancy)
11. [OAuth2 Authentication](#11-oauth2-authentication)
12. [Feature Flags](#12-feature-flags)
13. [Rate Limiting](#13-rate-limiting)
14. [Billing and Payment Gateway](#14-billing-and-payment-gateway)
15. [Payment Audit](#15-payment-audit)
16. [Deployment and Preview](#16-deployment-and-preview)
17. [REST API Reference](#17-rest-api-reference)
18. [Server-Sent Events](#18-server-sent-events)
19. [Recovery System](#19-recovery-system)
20. [Configuration Reference](#20-configuration-reference)
21. [Database Schema](#21-database-schema)
22. [Validation and Code Quality](#22-validation-and-code-quality)
23. [Merge Conflict Resolution](#23-merge-conflict-resolution)
24. [Eval Framework](#24-eval-framework)
25. [Security Architecture](#25-security-architecture)

---

## 1. Project Overview

ExecuteSpec is a multi-agent autonomous coding system that transforms natural language specifications into working software. The system decomposes user requests into atomic tasks, executes them via AI agents operating in isolated git worktrees, validates the output, reviews the generated code, and merges the results.

### Core Capabilities

- **Natural language to code:** Users submit a goal in plain English; the system produces a complete, working codebase.
- **Multi-agent pipeline:** Planner, Worker, Compiler, Reviewer, Validation Report, and post-merge agents each handle a distinct phase.
- **Human-in-the-loop gates:** Plan approval gate gives humans oversight before code generation begins.
- **Background validation:** Run is marked COMPLETE immediately after compile and merge. Review, test generation, test execution, and validation report run in a background thread.
- **Progressive merge:** Each task's branch is merged to main immediately after completion, enabling early conflict detection.
- **GitHub integration:** Automatic branch creation (`executespec/{runId-short}`) and PR opening for existing-repo runs.
- **Multi-tenant:** Organizations, projects, tiered access, and per-user/org API key management.
- **Provider failover:** 3-provider fallback chains (Anthropic, Gemini, OpenAI) with health-based circuit breaker (Redis-backed, 5-failure threshold, 60s TTL recovery).
- **Recovery:** Automatic detection and recovery of stuck tasks with configurable retry and model escalation.
- **Multi-gateway billing:** Razorpay + Stripe via PaymentGatewayRouter with full payment audit trail.
- **OAuth2 login:** Google and GitHub social login alongside JWT email/password authentication.
- **Feature flags:** DB-backed feature flag system with Redis pub/sub cross-instance invalidation.
- **Rate limiting:** Redis-backed per-tenant rate limiting via RateLimitFilter.
- **Eval framework:** Admin-only model evaluation with per-tier recommendations.

### Technology Stack

| Component | Technology |
|-----------|-----------|
| Runtime | Java 21 (virtual threads) |
| Framework | Spring Boot 3.3.0 |
| AI Framework | Spring AI 1.1.0 |
| Database | PostgreSQL (Flyway migrations V1-V58) |
| Cache/Gate Store | Redis (Lettuce) |
| Git Operations | JGit 6.9.0 + shell git (worktrees) |
| Build | Maven |
| Frontend | React 18 + TypeScript 5.3 (Vite 5, Tailwind CSS 3.4) |
| Logging | SLF4J via Lombok `@Slf4j` |
| Observability | Micrometer + OTel, Prometheus + Tempo + Grafana (local), Cloud Trace + Cloud Monitoring (GCP) |

---

## 2. Architecture Diagram

```
                         +------------------+
                         |   REST API /      |
                         |   Frontend UI     |
                         +--------+---------+
                                  |
                         +--------v---------+
                         |  RunController    |
                         |  SseController    |
                         |  Other Controllers|
                         +--------+---------+
                                  |
                    +-------------v--------------+
                    |     PipelineHarness         |
                    |   (orchestrates full run)   |
                    +--+------+------+------+----+
                       |      |      |      |
              +--------v+  +--v---+ +v----+ +v-----------+
              | Planner  |  |Worker| |Comp | | Reviewer   |
              | Agent    |  |Agent | |iler | | Agent      |
              +----+-----+  +--+--+ +--+--+ +-----+------+
                   |            |       |          |
              +----v-----+ +---v---+ +-v--+ +-----v------+
              | LlmRouter| |Tools  | |Shell| |GitTool     |
              | (fallback| |FS/Git | |Tool | |(worktrees) |
              |  chains) | |Shell  | |     | |            |
              +----+-----+ +---+---+ +--+--+ +-----+------+
                   |            |        |          |
              +----v-----+ +---v--------v----------v------+
              | Anthropic| |      Workspace (filesystem)   |
              | OpenAI   | |   workspace/{runId}/           |
              | Gemini   | |     worktrees/{taskId}/        |
              +----------+ +-------------------------------+
                                    |
                           +--------v---------+
                           |   PostgreSQL      |
                           |   (StateStore)    |
                           +------------------+
```

### Data Flow

```
User Request
    |
    v
[1. PlannerAgent]  -->  TaskPlan (JSON) with project_type
    |
    v
[2. Plan Approval Gate]  (human or auto-approve)
    |
    v
[3. SequentialTaskExecutor]
    |-- For each task (sequential, topological order):
    |     |-- ModelResolver selects model (tier-based via model-matrix)
    |     |-- WorkerAgent.execute() in isolated worktree
    |     |     |-- Agentic loop (up to 80 iterations)
    |     |     |-- XML tool calls: read/write/edit/run/git
    |     |     |-- Budget monitoring + compaction
    |     |     \-- Completion report (JSON)
    |     \-- Progressive merge to main
    |
    v
[4. CompilerAgent]  -->  compile & fix (up to 3 attempts)
    |
    v
[5. GitMergeService]  -->  merge all task branches to main (automatic, no gate)
    |
    v
[6. Mark COMPLETE]  -->  GitHub push (branch + PR for existing repos)
    |
    v
[7. Auto-preview]  (5-min TTL, both PROTOTYPE and PRODUCTION modes)
    |
    v
[8. Background Validation Thread (bg-validation-{runId})]
    |-- ReviewerAgentV2 (inspect + auto-fix critical issues)
    |-- TestGenOrchestrator (language-agnostic test generation)
    |-- TestPreviewCoordinator (reuses preview for test execution)
    \-- ValidationReport + TestReport (LLM summary)
    |
    v
[9. Post-Merge Orchestrator]
    |-- ContextAgent (update CODEBASE_CONTEXT.md)
    |-- PromptOptimiserAgent (improve prompts)
    \-- ApiTestSuiteAgent (generate OpenAPI spec)
```

---

## 3. Package Structure

All packages are under `com.navneet.executespec`.

### Core Packages

| Package | Description |
|---------|-------------|
| `agent/` | AI agent implementations: PlannerAgent, WorkerAgent, ReviewerAgent, ConversationAgent, LlmRouter, TokenBudgetMonitor, CostEstimator, ApiKeyResolver, LlmOutputParser |
| `agent/model/` | Data models: TaskPlan, TaskDefinition, WorkerReport, ReviewReport, TaskReview, ReviewIssue |
| `harness/` | Pipeline orchestration: PipelineHarness, WorkerPool, SequentialTaskExecutor, ApprovalGate, GitMergeService, GateStore, WorkspaceInitService |
| `harness/event/` | Spring events: PipelineEvent (RunStarted, MergeCompleted, RunFailed, RestartRun, RunCompleted), PostMergeOrchestrator |
| `harness/merge/` | Merge conflict resolution: FileMergerRegistry, specialized mergers (POM, YAML, package.json) |
| `state/` | Database access: StateStore, InboxService |
| `state/entity/` | JPA entities: PipelineRun, AgentTask, AgentMessage, FileLock, AgentInbox, GateEvent, CompactionEvent, RunRecovery, PromptAuditEntry, MergeConflict |
| `state/repository/` | Spring Data JPA repositories for all entities |
| `tool/` | Workspace tools: FileSystemTool, ShellTool, GitTool, FileLockTool, ShellSandboxPolicy, WorkspaceContext, ToolResult |
| `config/` | Spring configuration: AgentProperties, AgentModelConfig, ModelPricingInitializer, WebConfig, SecurityProperties |
| `config/featureflag/` | Feature flag system: FeatureFlagService, FeatureFlagController, FeatureFlag entity, FeatureFlagRepository |

### Feature Packages

| Package | Description |
|---------|-------------|
| `router/` | Model routing: ModelResolver (matrix-based tier x provider routing), AgentTier, LlmProvider, ResolvedModel, ProviderHealthTracker (Redis-backed circuit breaker), AvailableProviderResolver, ModelTaskMetric, ModelMatrixProperties |
| `reviewer/` | Agentic reviewer V2: ReviewerAgentV2 (three-phase: inspect + fix + accuracy assessment), AccuracyAssessmentService, ReviewReportV2 |
| `reviewer/model/` | AccuracyReport (spec adherence scoring record with EndpointCoverage nested type), ReviewIssue, IssueSeverity |
| `batch/` | Batch LLM execution: LlmBatchClient (interface), BatchClientRouter, AnthropicBatchClient, OpenAiBatchClient, GoogleBatchClient, BatchRequestBuilder, BatchPollService (`@Scheduled` 30s + `@SchedulerLock`), BatchResumeService (result parsing + event publish), BatchCompleteEvent |
| `validator/` | Code validation: CodeValidator, CodeValidatorImpl, ValidationLevel, ValidationResult, ValidationVerdict, Language |
| `validator/model/` | Validation data models |
| `validator/prompt/` | RetryPromptBuilder (builds error feedback for retries) |
| `compiler/` | CompilerAgent (post-worker build validation + LLM-powered fix) |
| `report/` | ValidationReportService, ValidationReportGenerator, ValidationReportPdfService, ValidationReportController |
| `report/entity/` | ValidationReport (JPA entity), PipelineResult enum |
| `report/model/` | ReportInput, ValidationReportDTO, ValidationReportShareDTO, ReportIssueDTO |
| `context/` | ContextAgent (post-merge codebase context updater), ContextAgentScanner, ContextAgentProperties |
| `optimiser/` | PromptOptimiserAgent (self-improving prompt system), PromptPatternAnalyser, PromptAuditService |
| `apitests/` | ApiTestSuiteAgent (post-merge OpenAPI spec generation) |
| `testgen/` | Test generation: TestGenOrchestrator, TestPlannerAgent, TestWorkerAgent, TestPreviewCoordinator, TestExecutionService, TestResultController, TestReportGenerator |
| `recovery/` | StuckRunMonitor, RunRecoveryService, StuckRunEvaluator, StuckClassification, LlmCallGuard, EscalationService, RecoveryProperties |
| `deployment/` | DeploymentService, CloudRunClient, ArtifactRegistryClient, DeploymentStrategy, DockerfileGenerator, WorkspaceExportService, WorkspaceFileService, GcsWorkspaceStore |
| `deployment/preview/` | PreviewService (dual-mode: docker/cloud-run), PreviewProperties, PreviewSession, CaddyProxyManager, DevDockerfileGenerator, ComposeFileGenerator, ComposeExecutor, DatabaseDetector, EnvVarDetector |
| `sse/` | Server-Sent Events: RunEvent (record + factory methods), SseBroadcaster |
| `api/controller/` | REST controllers (see REST API Reference section) |
| `api/dto/` | Request/response DTOs: RunSummary, RunDetail, TaskSummary, RunRequest, ApprovalRequest |
| `auth/` | Authentication: AppUser, Org, OrgMember, Project, ProjectMember, TierConfig entities; AuthService, OrgService, ProjectService, TierEnforcementService, InvitationService, PaymentAuditService |
| `auth/security/` | JWT filter, RunVisibilitySpec, AppUserDetails, SecurityConfig |
| `auth/oauth/` | OAuth2 login: OAuthSuccessHandler, OAuthUserMapper, OAuthProfile, OAuthEmailConflictException |
| `billing/` | Billing: BillingService, RunTopupService |
| `billing/gateway/` | Multi-gateway: PaymentGatewayRouter, PaymentGateway interface, StripePaymentGateway, RazorpayPaymentGateway, BillingEntity, WebhookResult |
| `billing/controller/` | BillingController, BillingWebhookController, PaymentAuditController |
| `billing/entity/` | WebhookEventLog |
| `billing/repository/` | WebhookEventLogRepository |
| `ratelimit/` | Rate limiting: RateLimitFilter, RateLimitProperties, RateLimitPolicy |
| `eval/` | Model evaluation: EvalController, ModelEvaluator, EvalScorer, EvalTestCaseRegistry, EvalReport |
| `security/` | Security scanning: InputSanitizer, CodeAbuseScanner |
| `tenant/` | TenantInterceptor (automatic tenant_id injection on JPA entities) |
| `integration/` | External system adapters: Jira, Confluence, ServiceNow, PagerDuty + IntegrationManager (listens to MergeCompleted event) |
| `integration/slack/` | Slack push notifications: SlackClient, SlackOAuthService, SlackNotificationService, SlackMessageBuilder, SlackRateLimiter |
| `integration/slack/entity/` | OrgSlackIntegration, ProjectSlackChannel, SlackNotificationLog |
| `integration/context/` | JIRA/Confluence context extraction: ReferenceExtractorService, ExtractionResult |
| `integration/github/` | GitHubRepoService (create repos, push branches, create PRs, default branch lookup) |
| `pricing/` | ModelPricing entity + repository (DB-backed pricing for cost estimation) |

---

## 4. Core Pipeline Flow

### 4.1 Run Lifecycle States

```
PLANNING --> AWAITING_CLARIFICATION --> AWAITING_APPROVAL --> WORKING --> COMPILING --> MERGING --> COMPLETE
    |               |                      |                    |            |                       |
    v               v                      v                    v            v                       v
  FAILED      (user provides ref        FAILED              PARTIAL      FAILED               Background
               or skips)               (rejected)           (some tasks                       Validation
                                                             failed)                          (review +
    |                                                                                         test gen +
    v                                                                                         test exec +
  QUEUED  (waiting for compute slot)                                                          report)
    |
    v
  PAUSED  (user paused, can edit goal and restart)
```

### 4.2 Detailed Pipeline Steps

**Step 1: Run Submission**
```
RunController.submit(RunRequest)
  --> RequestTriageService (optional: classify and enrich request)
  --> PipelineHarness.submitRun(userRequest, source, repoUrl, ...)
      --> StateStore.createRun() --> PipelineRun (status=PLANNING)
      --> Semaphore.tryAcquire() (max 50 concurrent runs)
          --> If no slot: status=QUEUED, block until slot available
          --> If slot available: executeRunAsync()
```

**Step 2: Workspace Initialization**
```
executeRunAsync(runId, userRequest)
  --> ApiKeyResolver.resolve(userId) --> ResolvedKeys
  --> RunKeyStore.put(runId, resolvedKeys)
  --> WorkspaceInitService:
      --> ITERATION mode: copyFromRun(sourceRunId, runId)
      --> EXISTING mode: cloneFromRepo(runId, repoUrl, pat)
      --> Default: ensureRunWorkspace(runId) (fresh git init)
```

**Step 3: Planning**
```
PlannerAgent.plan(userRequest, runId, modelOverride)
  --> Load planner.txt system prompt
  --> Build workspace context (file listing + CODEBASE_CONTEXT.md)
  --> LlmRouter.callWithFallback(provider, model, prompt, ...)
  --> LlmOutputParser.parse(response, TaskPlan.class)
  --> Retry up to 3 times on invalid JSON
  --> Returns TaskPlan {planSummary, tasks: [TaskDefinition...], project_type}
  --> Writes project_type to .agent-context/project.json
```

**Step 4: Plan Approval Gate**
```
StateStore.savePlan(runId, plan) --> status=AWAITING_APPROVAL
  --> GateStore.awaitPlanGate(runId) (polls Redis every 500ms, 2h TTL)
  --> Release compute slot while waiting
  --> REST: POST /api/runs/{id}/approve resolves gate via Redis
  --> Reacquire compute slot
  --> StateStore.markApproved(runId) --> status=WORKING
```

**Step 5: Task Execution (Sequential)**
```
SequentialTaskExecutor.execute(run, tasks)
  --> topologicalSort(tasks) (Kahn's algorithm)
  --> For each task in order:
      |-- Check dependency failures (skip if dep failed)
      |-- ModelResolver.resolve(agentTier, provider) via model-matrix
      |-- StateStore.lockTask(taskId, model, branchName)
      |-- WorkerAgent.execute(task, runId) in worktree
      |     |-- Max task duration: 25 minutes
      |     |-- Stall detection: warn=8min, act=15min
      |-- commitWorktreeIfDirty(runId, taskId)
      \-- tryMergeToMain(runId, task, branchName)
          --> GitMergeService.mergeTaskBranchWithResolution()
```

**Step 6: Compilation**
```
CompilerAgent.runCompileAndFix(runId)
  --> Detect build tool (mvn/gradle/npm)
  --> Run compile command
  --> On error: extract affected files, call LLM for fixes
  --> Apply JSON-patch fixes
  --> Retry up to 3 times
  --> Always returns (non-fatal)
```

**Step 7: Merge (automatic, no gate)**
```
GitMergeService.mergeAll(runId, tasks)
  --> Merge all task branches to main in topological order
  --> Conflict resolution cascade (specialized mergers then LLM)
  --> Post-merge compile validation
```

**Step 8: Mark COMPLETE + GitHub Push**
```
stateStore.markComplete(runId)
  --> pushToGitHubIfConfigured(runId):
      --> Creates branch: executespec/{runId-short}
      --> Pushes to branch (not main)
      --> Opens PR via GitHubRepoService.createPullRequest()
      --> Stores PR URL in PipelineRun.githubPrUrl
  --> SSE event: COMPLETE
```

**Step 9: Auto-Preview**
```
previewService.start(runId) -- 5-minute TTL
  --> Both PROTOTYPE and PRODUCTION modes
  --> DevDockerfileGenerator reads .agent-context/project.json for project type
```

**Step 10: Background Validation (bg-validation-{runId})**
```
Virtual thread runs independently after COMPLETE:
  --> ReviewerAgentV2.review(run, workerReports) -- inspect + auto-fix
  --> TestGenOrchestrator.generate(runId) -- language-agnostic test generation
  --> TestPreviewCoordinator.execute(runId) -- reuses preview for test execution
  --> ValidationReportService.generateAndPersist(runId)
  --> SSE cleanup deferred to background thread's finally block
```

**Step 11: Post-Merge**
```
PostMergeOrchestrator.onMergeCompleted(event)
  --> Virtual thread: ContextAgent.runAsync() (update CODEBASE_CONTEXT.md)
  --> Virtual thread: PromptOptimiserAgent.runIfDue() (self-improve prompts)
  --> Virtual thread: ApiTestSuiteAgent.generateAsync() (OpenAPI spec)
  --> All non-fatal -- failures logged but do not affect run status
```

---

## 5. Agent System

### 5.1 PlannerAgent

**File:** `agent/PlannerAgent.java`
**Tier:** PLANNING
**Model (Anthropic):** `claude-sonnet-4-6`
**Mode:** Single LLM call (IMMEDIATE fallback)

**Purpose:** Decomposes a natural language user request into a structured task plan with dependency ordering. Declares `project_type` in the plan JSON which is written to `.agent-context/project.json` for use by preview and deploy Dockerfile generators.

**Key Methods:**

| Method | Signature | Description |
|--------|-----------|-------------|
| `plan` | `TaskPlan plan(String userRequest, String runId, String modelOverride)` | Main entry point. Loads the planner system prompt, builds workspace context, calls LLM, parses JSON response. Retries up to 3 times on invalid JSON. |
| `replanWithFeedback` | `TaskPlan replanWithFeedback(String originalGoal, String existingPlanJson, String feedback, String runId)` | Re-plans with user feedback at the approval gate. Uses the same `planner.txt` prompt with `{{#if editMode}}` block activated (injects existing plan + feedback as template variables). |
| `planWithEstimates` | `TaskPlan planWithEstimates(String goal)` | Feasibility mode -- adds iteration and token estimates per task for cost preview without creating a run. |
| `buildWorkspaceContext` | `String buildWorkspaceContext(String runId)` | Reads file listing and CODEBASE_CONTEXT.md from the workspace to give the LLM context about existing code. |
| `buildConflictHistoryContext` | `String buildConflictHistoryContext()` | Appends historical merge conflict data so the planner can avoid patterns that frequently cause conflicts. |

**Prompt Template:** `planner_edit.txt` has been merged into `planner.txt`. The single prompt uses `{{#if editMode}}...{{/if}}` blocks: fresh plans strip the edit block, while `replanWithFeedback()` activates it and substitutes the existing plan JSON and user feedback.

**Async Python Stack Detection:**

Before calling the LLM, `PlannerAgent` inspects the user goal for async Python dependency patterns (`asyncpg`, `sqlalchemy[asyncio]`, `sqlalchemy.ext.asyncio`, `redis.asyncio`, `aioredis`). When detected, it injects a `<complexity_signal>` XML block into the planner prompt that:
- Guides task decomposition: dedicated setup task for async DB engine + session factory, separate task for async Redis client
- Warns about Gemini's weaker async Python support and recommends sync drivers if Gemini provider is selected

This prevents the planner from under-scoping async Python setups, which were a common source of PARTIAL runs before this fix.

**Output:** `TaskPlan` containing:
- `planSummary` -- high-level description
- `project_type` -- declared project type (e.g., STATIC_HTML, NODE_BACKEND, FULL_STACK)
- `tasks` -- list of `TaskDefinition` objects, each with:
  - `taskId` (snake_case identifier)
  - `title` (human-readable description)
  - `complexity` (LOW / MEDIUM / HIGH)
  - `dependsOn` (list of taskIds this task depends on)
  - `acceptanceCriteria` (list of conditions for completion)
  - `filesToCreate` / `filesToModify` (for file ownership validation)
  - `assignedModel` (planner can route: Sonnet for HIGH/MEDIUM, Haiku for LOW)

### 5.2 WorkerAgent

**File:** `agent/WorkerAgent.java`
**Tier:** WORKHORSE
**Model (Anthropic):** `claude-haiku-4-5-20251001`
**Mode:** Agentic loop (up to 80 iterations)

**Purpose:** Executes a single task by operating in an isolated git worktree, using XML-format tool calls to read, write, edit files, run commands, and manage git.

**Key Methods:**

| Method | Signature | Description |
|--------|-----------|-------------|
| `execute` | `WorkerReport execute(AgentTask task, String runId)` | Main entry point. Creates a worktree, runs the agentic loop, returns a WorkerReport. |
| `executeWithModel` | `WorkerReport executeWithModel(AgentTask task, String runId, ResolvedModel model)` | Convenience wrapper that sets the model before calling execute(). |
| `parseXmlToolCalls` | `List<ParsedToolCall> parseXmlToolCalls(String text)` | Extracts `<tool_call>{"name":"...", "args":{...}}</tool_call>` blocks from assistant text. |
| `dispatchTool` | `String dispatchTool(ParsedToolCall tc, String runId, String taskId)` | Routes parsed tool calls to the appropriate Java tool method. |
| `compact` | `List<Message> compact(messages, task, ...)` | Compresses conversation history via LLM summarization when context window is filling up. |

**Agentic Loop Details:**

```
1. Create worktree: gitTool.createWorktree(runId, taskId, branchName, depBranches)
2. Set WorkspaceContext for thread-local worktree routing
3. Build initial user message with task definition + acceptance criteria
4. LOOP (up to maxIterations=80):
   a. Drain inbox (provider fallback notifications, etc.)
   b. Progress checkpoint every 15 iterations (re-inject task context)
   c. Completion urgency at iteration 75 (force JSON output)
   d. Pre-call token estimation (compact proactively if >30 messages)
   e. LlmRouter.callWithFallbackTracked()
   f. Parse XML tool calls --> dispatchTool() --> inject results as UserMessage
   g. Check for completion report (JSON starting with '{')
   h. Budget monitoring: OK / WARN / COMPACT / HARD_STOP
5. Clear WorkspaceContext in finally block
```

**Tool Dispatch Map:**

| Tool Call Name | Java Method |
|---------------|-------------|
| `readFile` / `read_file` | `fsTool.readFile(runId, path)` |
| `writeFile` / `write_file` | `fsTool.writeFile(runId, path, content)` |
| `editFile` / `edit_file` | `fsTool.editFile(runId, path, oldStr, newStr)` |
| `listFiles` / `list_files` | `fsTool.listFiles(runId, path)` |
| `searchFiles` / `search_files` | `fsTool.searchFiles(runId, pattern, directory)` |
| `run` / `run_command` / `run_build` / `run_tests` | `shellTool.run(runId, command, timeoutSeconds)` |
| `status` / `git_status` | `gitTool.status(runId)` |
| `diff` / `git_diff` | `gitTool.diff(runId)` |
| `commit` / `git_commit` | `gitTool.commit(runId, message)` |

**Design Notes:**
- WorkerAgent is a Spring **singleton** -- all per-task state is passed as method parameters.
- Tool calls use XML format (`<tool_call>`) NOT Spring AI native tool calling, because `@Tool` methods require `runId` which the model does not know.
- Completion detection is **strict**: `looksLikeCompletionReport(text, expectedTaskId)` checks that the response starts with `{`, contains `"task_id"` and `"status"`, and that `task_id` matches the `expectedTaskId` parameter (prevents cross-task confusion).
- `escapeControlCharsInJsonStrings()` sanitizes LLM-emitted raw control characters inside JSON string values.
- Max task duration: 25 minutes (wall-clock). Stall detection warns at 8 minutes, recovers at 15 minutes.
- **Mid-task provider failure recovery:** If the LLM provider fails during the agentic loop, WorkerAgent catches the error and uses `ModelResolver.resolveFallbackExcluding()` to switch to an alternative provider, then continues the loop. The task is marked BLOCKED only if no fallback is available.

### 5.3 ReviewerAgent (Legacy -- removed from pipeline)

**Note:** The original single-call ReviewerAgent has been fully replaced by ReviewerAgentV2 in the pipeline. See section 5.4.

### 5.4 ReviewerAgentV2

**File:** `reviewer/ReviewerAgentV2.java`
**Tier:** PLANNING
**Model (Anthropic):** `claude-sonnet-4-6`
**Mode:** Three-phase agentic review

**Purpose:** Reviews merged code on main with tool-use inspection, automatic fix application for critical issues, and spec adherence accuracy assessment. Runs in the background validation thread AFTER the run is marked COMPLETE.

**Phases:**
1. **Inspect Phase:** Reads file tree and selected source files via tool-use loop (max 10 tool calls). Produces a list of ReviewIssues classified as CRITICAL or MINOR.
2. **Fix Phase:** For each CRITICAL issue (max 8), sends a targeted fix instruction to WorkerAgent, then validates the fix via CodeValidator.
3. **Accuracy Assessment Phase:** Calls `AccuracyAssessmentService.assess()` to measure spec adherence. Returns `AccuracyReport` with score 0-100, grade A-F, endpoint coverage, schema accuracy, and missing requirements. Score + grade are persisted to the `validation_report` table via V74 migration.

**Verdict Enum:** `APPROVED`, `APPROVED_WITH_NOTES`, `NEEDS_FIXES`, `REJECTED`

**Output:** `ReviewReportV2` containing:
- `verdict` -- ReviewVerdict enum (APPROVED / APPROVED_WITH_NOTES / NEEDS_FIXES / REJECTED)
- `summary` -- text summary
- `issues` -- flat list of issues, each with `IssueSeverity` (CRITICAL / MINOR)
- `recommendationCompat` -- maps V2 verdicts to V1-style strings for JSON compatibility

**Auto-fix Details:**
- 2 attempts per issue: FAST tier first, escalates to CAPABLE tier on retry
- `MAX_FIX_ATTEMPTS = 2`, escalation tiers: `{ FAST, CAPABLE }`
- Re-validates after all fixes (compile + type check)

**Guardrails:**
- Max 10 read_file/list_files calls during inspection
- Max 8 critical issues trigger auto-fix
- 2 fix attempts per critical issue (FAST -> CAPABLE escalation)
- Total phase timeout: 3 minutes

### 5.4.1 AccuracyAssessmentService

**File:** `reviewer/AccuracyAssessmentService.java`
**Tier:** PLANNING (same tier as ReviewerAgentV2)

**Purpose:** Phase 3 of the review pipeline. Makes a focused LLM call that scores the generated code against the original user specification, returning a structured `AccuracyReport`.

**Public API:**

| Method | Signature | Description |
|--------|-----------|-------------|
| `assess` | `AccuracyReport assess(String runId, String provider, ResolvedModel model, String userGoal, List<ReviewIssue> issues)` | Main entry. Builds prompt with file tree + issues summary, calls LLM, parses `AccuracyReport` JSON. Falls back to `defaultReport()` on any failure. |

**Accuracy Dimensions:**

| Dimension | Type | Description |
|-----------|------|-------------|
| `specAdherenceScore` | `int` (0-100) | Percentage of explicitly stated requirements implemented |
| `overallAccuracyGrade` | `String` (A-F) | Letter grade derived from score (A ≥ 90, B ≥ 80, C ≥ 70, D ≥ 60, F < 60) |
| `endpointCoverage` | `EndpointCoverage` | For API specs: required, implemented, missing HTTP endpoints |
| `schemaAccuracy` | `String` (HIGH/MEDIUM/LOW) | How closely request/response payloads match the spec |
| `testCoverage` | `String` (NONE/PARTIAL/GOOD/COMPREHENSIVE) | Test coverage level relative to spec expectations |
| `nonFunctionalCoverage` | `List<String>` | Which non-functional requirements are covered (auth, pagination, rate limiting, error handling) |
| `missingRequirements` | `List<String>` | Specific requirements from the spec that are absent or incomplete |
| `partialRequirements` | `List<String>` | Requirements partially implemented |

**AccuracyReport helper methods:**
- `isHighAccuracy()` — true if `specAdherenceScore >= 80`
- `hasSignificantGaps()` — true if `missingRequirements.size() > 2 || specAdherenceScore < 70`
- `incompleteRequirementsCount()` — `missingRequirements.size() + partialRequirements.size()`
- `toOneLiner()` — compact single-line summary for logging / SSE events

**Failure handling:** Any LLM call failure or JSON parse error returns a conservative default report (score = 50, grade = C) rather than propagating the exception, so Phase 3 failures never block the validation report from being generated.

**DB persistence:** `ValidationReportService` reads `accuracyScore` and `accuracyGrade` from the returned report and persists them to the `validation_report` table (columns added by V74 migration). The full `AccuracyReport` JSON is not stored — only the scalar fields that the frontend needs.

### 5.5 CompilerAgent

**File:** `compiler/CompilerAgent.java`
**Tier:** WORKHORSE
**Model (Anthropic):** `claude-haiku-4-5-20251001`
**Mode:** Compile-and-fix loop (up to 3 attempts)

**Purpose:** Runs the project build after all workers complete (before the reviewer sees the code). If compilation fails, extracts error messages and affected source files, sends them to an LLM for JSON-patch fixes, and retries.

**Key Methods:**

| Method | Signature | Description |
|--------|-----------|-------------|
| `runCompileAndFix` | `CompileResult runCompileAndFix(String runId)` | Entry point called by PipelineHarness. Non-fatal -- always returns. |
| `detectBuildTool` | `String detectBuildTool(String runId)` | Checks for pom.xml (mvn), build.gradle (gradle), package.json (npm). |
| `applyLlmFixes` | `void applyLlmFixes(String runId, String errors, String buildTool)` | Sends compile errors + affected source to LLM, applies JSON array fixes `[{path, content}]`. |

**Internal Helpers:**

| Method | Signature | Description |
|--------|-----------|-------------|
| `maxErrorCharsForSse` | `int maxErrorCharsForSse(String errors)` | Adaptive SSE truncation limit based on error file count: <=3 files -> 2000 chars, <=6 -> 1200, >6 -> 800. Keeps SSE events manageable. |
| `countErrorFiles` | `int countErrorFiles(String errors)` | Counts unique source file paths (`.java`, `.ts`, `.js`, `.tsx`, `.jsx`, `.py`, `.go`, `.rs`) mentioned in compile error output. |

**Build Commands:**
- Maven: `mvn compile -q 2>&1`
- Gradle: `gradle compileJava -q 2>&1`
- npm: `npm run build 2>&1`

### 5.6 ConversationAgent

**File:** `agent/ConversationAgent.java`
**Tier:** LIGHTWEIGHT
**Model (Anthropic):** `claude-haiku-4-5-20251001`
**Mode:** Stateful conversation (up to 10 user messages)

**Purpose:** Post-run conversational AI. After a run reaches COMPLETE, users can ask questions about the generated code. The agent has access to workspace file trees, README.md, CODEBASE_CONTEXT.md, plan JSON, and review JSON.

**Key Methods:**

| Method | Signature | Description |
|--------|-----------|-------------|
| `chat` | `String chat(PipelineRun run, List<ChatMessageRecord> history, String newMessage)` | Builds a system prompt with project context, replays conversation history, sends the new message, returns assistant reply. |

**Context Window Includes:**
- File tree of the workspace
- README.md (up to 4k chars)
- CODEBASE_CONTEXT.md (up to 3k chars)
- Plan JSON (up to 2k chars)
- Review JSON (up to 1k chars)

### 5.7 ContextAgent

**File:** `context/ContextAgent.java`
**Tier:** LIGHTWEIGHT
**Model (Anthropic):** `claude-haiku-4-5-20251001`

**Purpose:** Post-merge agent that updates `CODEBASE_CONTEXT.md` and `decisions.md` in the project workspace. Runs asynchronously in a virtual thread.

**Flow:**
1. `ContextAgentScanner.scan()` -- collects git diff summary, new packages, new files, worker reports, reviewer summary
2. If anything changed, calls LLM with current context doc + changes
3. Writes updated `CODEBASE_CONTEXT.md` to `.agent-context/`
4. Appends entry to `decisions.md`
5. Commits to main via JGit

### 5.8 PromptOptimiserAgent

**File:** `optimiser/PromptOptimiserAgent.java`
**Tier:** WORKHORSE
**Model (Anthropic):** `claude-haiku-4-5-20251001`

**Purpose:** Self-improving prompt system. Analyses failure patterns across recent runs and surgically updates system prompts to address recurring issues.

**Trigger Conditions:**
- Every N completed runs (`runInterval`, default: 10)
- When failure rate exceeds threshold (`failureRateThreshold`, default: 20%)

**Flow:**
1. `PromptPatternAnalyser.analyse()` -- scans recent runs for failure patterns
2. For each affected prompt file: reads current content, calls LLM with patterns
3. LLM returns minimally edited prompt (surgical changes only)
4. Writes updated prompt, records audit entry
5. Commits to main

### 5.9 ApiTestSuiteAgent

**File:** `apitests/ApiTestSuiteAgent.java`
**Tier:** WORKHORSE
**Model (Anthropic):** `claude-haiku-4-5-20251001`

**Purpose:** Generates an OpenAPI 3.0 YAML specification for backend-only runs post-merge. Writes `api-docs/openapi.yaml` to the workspace.

**Skip Conditions:**
- Frontend files detected (.html, .jsx, .tsx, .vue, .svelte)
- No controller files found (*Controller.java / *Resource.java)

### 5.10 Test Generation Agents

**TestGenOrchestrator** (`testgen/TestGenOrchestrator.java`): Orchestrates language-agnostic test generation. Coordinates TestPlannerAgent and TestWorkerAgent.

**TestPlannerAgent** (`testgen/TestPlannerAgent.java`):
- **Tier:** WORKHORSE
- Plans test cases based on the generated code, producing a language-agnostic test plan.

**TestWorkerAgent** (`testgen/TestWorkerAgent.java`):
- **Tier:** WORKHORSE
- Generates actual test code from the test plan. Supports multiple languages and frameworks.

**TestPreviewCoordinator** (`testgen/TestPreviewCoordinator.java`):
- Manages preview lifecycle for test execution. Reuses existing running preview if available.
- Preview is NOT stopped after test execution (stays up for user).

**TestReportGenerator** (`testgen/TestReportGenerator.java`):
- **Tier:** LIGHTWEIGHT
- Generates a human-readable test report from test execution results.

### 5.11 RequestTriageService

**File:** `api/controller/RunController.java` (invoked during submission)
**Tier:** LIGHTWEIGHT

**Purpose:** Classifies and enriches incoming run requests before they enter the pipeline. Lightweight triage step.

---

## 6. Tool System

All tools operate within a sandboxed workspace directory (`workspace/{runId}/`). Thread-local `WorkspaceContext` redirects operations to task-specific worktrees when active.

### 6.1 FileSystemTool

**File:** `tool/FileSystemTool.java`

Provides file I/O operations for agents. All paths are resolved relative to the run's workspace and validated by `safeResolve()` to prevent directory traversal.

| Method | Signature | Description |
|--------|-----------|-------------|
| `readFile` | `String readFile(String runId, String path)` | Read file content (truncated at 8k chars). |
| `writeFile` | `String writeFile(String runId, String path, String content)` | Create or fully overwrite a file. Logs WARN for sensitive filenames (.env, credentials.json, etc.) but does NOT block. Scanned by CodeAbuseScanner before write. |
| `editFile` | `String editFile(String runId, String path, String oldStr, String newStr)` | Replace an exact string in a file. |
| `listFiles` | `String listFiles(String runId, String path)` | List directory contents. Hides `worktrees/`, `target/`, `build/`, `node_modules/`, `.git/`. |
| `searchFiles` | `String searchFiles(String runId, String pattern, String directory)` | Recursive grep with the same directory exclusions. |

**Security Model:**
- `safeResolve(runId, relativePath)` -- normalizes the path and verifies it starts with the workspace root. Throws `SecurityException` on escape attempts.
- `checkFileLock(path)` -- checks file locks from `FileLockTool`. Blocks writes to files owned by another task.
- `SENSITIVE_FILE_NAMES` -- WARN log only (agents legitimately write these for generated projects).
- `CodeAbuseScanner` integration -- scans file content for crypto mining, reverse shells, and other abuse patterns before write.

**Truncation Limits:**
- `readFile`: 8k chars
- `shell`: 6k chars
- `diff`: 5k chars

### 6.2 ShellTool

**File:** `tool/ShellTool.java`

Executes shell commands inside the run's workspace directory.

| Method | Signature | Description |
|--------|-----------|-------------|
| `run` | `String run(String runId, String command, int timeoutSeconds)` | Validates via `ShellSandboxPolicy`, strips secret env vars, runs with hard 300s timeout ceiling. Output truncated at 6k chars. |

**Environment Stripping:** Removes `GITHUB_PAT`, `POSTGRES_PASSWORD`, `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `JIRA_API_TOKEN`, etc. from child process environment.

### 6.3 ShellSandboxPolicy

**File:** `tool/ShellSandboxPolicy.java`

Three-layer command validation:

1. **Blocked Commands (explicit):** `rm -rf /`, `mkfs`, `reboot`, `iptables`, `passwd`, `ncat`, etc.
2. **Blocked Patterns (regex):** `curl | bash`, `wget | bash`, SSH/SCP/rsync connections, `base64 -d | bash`, writes to `/etc/`, `/usr/`, `/var/`.
3. **Allowed Prefixes (whitelist):** `mvn`, `gradle`, `npm`, `node`, `java`, `cat`, `ls`, `find`, `grep`, `git`, `python`, `docker`, etc.

Commands must start with an allowed prefix. Absolute directory navigation (`cd /`) is blocked.

### 6.4 GitTool

**File:** `tool/GitTool.java`

Git operations via JGit library and shell git (for worktree management).

**Agent-facing methods (dispatched via WorkerAgent):**

| Method | Signature | Description |
|--------|-----------|-------------|
| `status` | `String status(String runId)` | Git status output. |
| `diff` | `String diff(String runId)` | Full diff of working tree (truncated at 5k chars). |
| `commit` | `String commit(String runId, String message)` | Stage all + commit. |
| `commitIfDirty` | `void commitIfDirty(String runId)` | No-op if clean; stages and commits if dirty. |

**Worktree management (internal):**

| Method | Signature | Description |
|--------|-----------|-------------|
| `createWorktree` | `String createWorktree(String runId, String taskId, String branchName, List<String> depBranches)` | Creates an isolated git worktree at `workspace/{runId}/worktrees/{taskId}/`. Merges dependency branches with `-X theirs`. Throws on dep branch merge failure. |
| `removeWorktree` | `void removeWorktree(String runId, String taskId)` | Force-removes a task's worktree. |
| `pruneAllWorktrees` | `void pruneAllWorktrees(String runId)` | Removes ALL worktrees for a run and prunes stale metadata. |

**Merge methods (internal):**

| Method | Signature | Description |
|--------|-----------|-------------|
| `mergeBranchNoCommit` | `MergeResult mergeBranchNoCommit(String runId, String branchName)` | JGit merge with no auto-commit (for conflict inspection). |
| `commitMerge` | `void commitMerge(String runId, String message)` | Commits a no-commit merge. |
| `abortMerge` | `void abortMerge(String runId)` | Aborts an in-progress merge. |
| `getFullDiff` | `String getFullDiff(String runId, String fromRef, String toRef)` | Full diff between two refs. |
| `tagRun` | `void tagRun(String runId)` | Creates a git tag for the completed run. |
| `pushToBranch` | `void pushToBranch(String runId, String branchName)` | Pushes workspace to a specific branch on the remote (for existing-repo GitHub PR flow). |

### 6.5 WorkspaceContext

**File:** `tool/WorkspaceContext.java`

Thread-local context that redirects file/git/shell operations to a task's isolated worktree instead of the shared run workspace root.

| Method | Signature | Description |
|--------|-----------|-------------|
| `set` | `static void set(String runId, String absoluteWorktreePath)` | Set worktree override for current thread. |
| `getWorkDir` | `static String getWorkDir(String runId)` | Get worktree path, or null for default. |
| `setTaskId` | `static void setTaskId(String taskId)` | Set current task ID for file-lock enforcement. |
| `getTaskId` | `static String getTaskId()` | Get current task ID. |
| `clear` | `static void clear()` | Clear all context for current thread. |

**Lifecycle:**
1. `WorkerAgent.execute()` calls `WorkspaceContext.set(runId, worktreePath)` before the agentic loop.
2. All tool operations read the override via `WorkspaceContext.getWorkDir(runId)`.
3. `WorkspaceContext.clear()` is called in the `finally` block of `WorkerAgent.execute()`.

### 6.6 FileLockTool

Manages file ownership between concurrent tasks. When a task first writes to a file, it claims ownership. Other tasks attempting to write to the same file are blocked with an error message.

---

## 7. State Management

### 7.1 StateStore

**File:** `state/StateStore.java`

Central database access layer. All run and task lifecycle operations go through StateStore. Annotated with `@Transactional`.

**Run Lifecycle Methods:**

| Method | Signature | Description |
|--------|-----------|-------------|
| `createRun` | `PipelineRun createRun(String request, String source, String repoUrl, String repoMode, String plannerModel, String workerModel, String reviewerModel)` | Creates a new run with status=PLANNING. |
| `savePlan` | `void savePlan(String runId, TaskPlan plan)` | Saves plan JSON, creates AgentTask rows, sets status=AWAITING_APPROVAL. |
| `markApproved` | `void markApproved(String runId)` | Sets status=WORKING, clears currentGate. |
| `markRejected` | `void markRejected(String runId, String reason)` | Sets status=FAILED with rejection reason. |
| `markReviewing` | `void markReviewing(String runId)` | Sets status=REVIEWING. |
| `saveReview` | `void saveReview(String runId, ReviewReport review)` | Saves review JSON. |
| `markComplete` | `void markComplete(String runId)` | If any tasks BLOCKED/PARTIAL, marks PARTIAL; otherwise COMPLETE. |
| `markFailed` | `void markFailed(String runId, String reason)` | Fails run + all non-terminal tasks. |
| `markPartial` | `void markPartial(String runId, String reason)` | Marks run PARTIAL (workers ran but some tasks incomplete). |
| `markRetryingWithFeedback` | `void markRetryingWithFeedback(String runId, String taskId, String reworkFeedback)` | Resets task to PENDING, injects reviewer feedback into TaskDefinition JSON, subtracts old token counts. |

**Task Lifecycle Methods:**

| Method | Signature | Description |
|--------|-----------|-------------|
| `lockTask` | `boolean lockTask(String taskId, String model, String branchName)` | Atomically locks a task (CAS via `lockAndStart` query). Returns false if already locked. |
| `completeTask` | `void completeTask(String taskId, WorkerReport report)` | Sets status, saves report, accumulates token totals on run. |
| `failTask` | `void failTask(String taskId, String reason)` | Marks task FAILED with failure reason. |
| `blockTask` | `void blockTask(String taskId, String reason)` | Marks task BLOCKED (dependency failure). |
| `resetTaskForRetry` | `void resetTaskForRetry(String taskId)` | Resets task to PENDING with cleared failure fields. Used by batch auto-retry in `onBatchComplete()`. |
| `setTaskModelProvider` | `void setTaskModelProvider(String taskId, String model, String provider)` | Sets resolved model/provider on a task. Used by `BatchResumeService` for WORKHORSE model attribution after batch resume. |

**Query Methods:**

| Method | Returns | Description |
|--------|---------|-------------|
| `findRun(runId)` | `Optional<PipelineRun>` | Look up by ID. |
| `listRuns(status, projectId, pageable, visSpec)` | `Page<PipelineRun>` | Paginated listing with optional filters and RBAC visibility. |
| `getAllTasks(runId)` | `List<AgentTask>` | All tasks for a run. |
| `getPendingTasks(runId)` | `List<AgentTask>` | Tasks with status=PENDING. |
| `getWorkingTasks()` | `List<AgentTask>` | All tasks with status=WORKING/LOCKED (for stuck detection). |

### 7.2 PipelineRun Entity

**File:** `state/entity/PipelineRun.java`
**Table:** `pipeline_run`

| Field | Type | Description |
|-------|------|-------------|
| `runId` | `String` (PK) | UUID assigned at creation. |
| `userRequest` | `TEXT` | Original natural language request. |
| `status` | `String` | PLANNING, AWAITING_APPROVAL, AWAITING_CLARIFICATION, WORKING, COMPILING, MERGING, REVIEWING, COMPLETE, FAILED, PARTIAL, QUEUED, PAUSED. |
| `planJson` | `TEXT` | Full TaskPlan JSON from planner. |
| `reviewJson` | `TEXT` | ReviewReport JSON from reviewer. |
| `plannerModel` | `String` | Model used for planning (e.g., `claude-sonnet-4-6`). |
| `workerModel` | `String` | Default model for workers. |
| `reviewerModel` | `String` | Model used for review. |
| `sourceType` | `String` | MANUAL, JIRA, CONFLUENCE, SERVICENOW, PAGERDUTY. |
| `repoUrl` | `String` | HTTPS clone URL (null if no repo). |
| `repoMode` | `String` | NEW, EXISTING, or null. |
| `totalInputTokens` | `Integer` | Accumulated input tokens across all agents. |
| `totalOutputTokens` | `Integer` | Accumulated output tokens. |
| `actualCostUsd` | `Double` | Cache-aware actual cost computed post-run. |
| `currentGate` | `String` | PLAN_APPROVAL or null (MERGE_APPROVAL removed). |
| `skipApproval` | `boolean` | Auto-approve plan gate (integration runs). |
| `autoRestartCount` | `int` | Times auto-restarted due to transient failures. |
| `hasFrontend` | `boolean` | True when preview is available. |
| `hasApiSpec` | `boolean` | True when OpenAPI spec generated. |
| `keySource` | `String` | SYSTEM, OWN_KEYS, or HYBRID. |
| `createdByUserId` | `String` | User who submitted the run. |
| `orgId` | `String` | Organization the run belongs to. |
| `projectId` | `String` | Project within the org (nullable). |
| `githubPat` | `String` | GitHub PAT captured at run creation. |
| `githubOwner` | `String` | GitHub owner for push. |
| `githubPrUrl` | `String` | URL of the GitHub PR created for existing-repo runs. |
| `chatHistoryJson` | `TEXT` | Conversation history as JSON array. |
| `chatMessageCount` | `int` | Total messages exchanged (capped at 20). |
| `sourceRunId` | `String` | For iteration runs, the source run's ID. |
| `gcsWorkspaceKey` | `String` | GCS object key for archived workspace. |
| `gcsArchivedAt` | `Instant` | When workspace was archived to GCS. |
| `archivedAt` | `Instant` | When the run was archived (V53). |
| `tenantId` | `String` | Multi-tenant discriminator (default: "default"). |

### 7.3 AgentTask Entity

**File:** `state/entity/AgentTask.java`
**Table:** `agent_task`

| Field | Type | Description |
|-------|------|-------------|
| `taskId` | `String` (PK) | From planner JSON (snake_case). |
| `runId` | `String` | FK to pipeline_run. |
| `title` | `String` | Human-readable description. |
| `complexity` | `String` | LOW, MEDIUM, or HIGH. |
| `status` | `String` | PENDING, LOCKED, WORKING, COMPLETE, PARTIAL, FAILED, BLOCKED. |
| `taskJson` | `TEXT` | Full TaskDefinition JSON. |
| `workerReportJson` | `TEXT` | WorkerReport JSON after completion. |
| `assignedModel` | `String` | Model assigned by planner or ModelResolver. |
| `branchName` | `String` | Git branch: `agent/{runId_8chars}/{taskId}`. |
| `inputTokens` | `Integer` | Total input tokens consumed. |
| `outputTokens` | `Integer` | Total output tokens consumed. |
| `cacheReadTokens` | `Integer` | Anthropic cache read tokens (billed at 0.10x). |
| `cacheWriteTokens` | `Integer` | Anthropic cache write tokens (billed at 1.25x). |
| `compactionCount` | `Integer` | How many times context was compacted. |
| `lastActivityAt` | `Instant` | Last LLM call timestamp (for stuck detection). |
| `stuckDetectedAt` | `Instant` | When stuck was first detected. |
| `recoveryAttempt` | `Integer` | Number of recovery attempts. |

---

## 8. Pipeline Harness

### 8.1 PipelineHarness

**File:** `harness/PipelineHarness.java`

Central orchestrator that manages the entire pipeline lifecycle. Handles both CLI and REST execution paths.

**Key Components:**
- `Semaphore runSlots` -- limits concurrent active runs (configurable, default: 50)
- `ExecutorService executor` -- cached thread pool for async pipeline execution
- `GateStore gateStore` -- Redis-backed gate store for cross-instance approval handling

**Public Methods:**

| Method | Signature | Description |
|--------|-----------|-------------|
| `submitRun` | `PipelineRun submitRun(String userRequest, String source, String repoUrl, String repoMode, String githubPat, String githubOwner)` | Creates a run, acquires a compute slot (or queues), and starts async execution. |
| `resolvePlanGate` | `boolean resolvePlanGate(String runId, boolean approved, String comment)` | Signals the plan approval gate via Redis. Returns false if run not in AWAITING_APPROVAL. |
| `resolveMergeGate` | `boolean resolveMergeGate(String runId, boolean approved, String comment)` | Legacy merge gate method (merge gate removed -- pipeline auto-merges after review). |
| `cancelRun` | `boolean cancelRun(String runId)` | Signals open gates as rejected, marks run cancelled. |
| `pauseRun` | `boolean pauseRun(String runId)` | Pauses a run: signals gates, cancels workers, marks PAUSED. |
| `restartWithNewGoal` | `PipelineRun restartWithNewGoal(PipelineRun pausedRun, String newGoal)` | Creates a fresh run from a paused run with an edited goal. Carries forward credentials and settings. |
| `implementSuggestions` | `void implementSuggestions(String runId)` | Implements post-merge suggestions (MEDIUM/LOW issues). |
| `reworkRun` | `void reworkRun(String runId)` | Re-runs flagged tasks with reviewer feedback. |
| `retryPartialTask` | `void retryPartialTask(String runId, String taskId)` | Retries a single failed/partial/blocked task. |
| `startIterationRun` | `void startIterationRun(String runId, String newRequest)` | Starts a run that iterates on a completed run's workspace. |
| `pushToGitHubIfConfigured` | `void pushToGitHubIfConfigured(String runId)` | Creates branch `executespec/{runId-short}`, pushes, and opens PR via GitHubRepoService. |

**Private Methods of Note:**

| Method | Signature | Description |
|--------|-----------|-------------|
| `runBackgroundValidation` | `void runBackgroundValidation(String runId, PipelineRun run, List<WorkerReport> reports, String planJson, String userRequest, CompileResult compileResult, boolean computeHadFailures)` | Shared method that runs ReviewerAgentV2, TestGenOrchestrator, TestPreviewCoordinator, and ValidationReport generation in a background virtual thread (`bg-validation-{runId}`). Called from both the main pipeline flow and the iteration/resume paths. |

**PlannerAgent Retry (transient LLM failure hardening):**

`executeRunAsync()` wraps `plannerAgent.plan()` in an inline retry loop before propagating the exception to the outer `markFailed` handler:

```java
int planAttempt = 0;
long[] planBackoffs = {5_000L, 15_000L};  // 5s then 15s
while (true) {
    try {
        plan = plannerAgent.plan(effectiveRequest, runId, run.getPlannerModel());
        break;
    } catch (Exception planEx) {
        if (planAttempt >= planBackoffs.length) throw planEx;
        log.warn("[PipelineHarness][{}] PlannerAgent attempt {} failed...", ...);
        Thread.sleep(planBackoffs[planAttempt++]);
    }
}
```

- Up to **3 total attempts** (2 retries) with 5 s / 15 s backoff
- Only runs inside the existing observation scope — the outer catch still calls `markFailed` if all attempts are exhausted
- Eliminates "0/0 tasks" failures caused by transient LLM provider errors during planning

**Gate Architecture (Redis-backed):**

Gates use Redis polling (500ms interval, 2h TTL) so that HTTP approve/reject calls work regardless of which server instance receives them:

```
Pipeline Thread                    REST Handler
     |                                  |
     |  gateStore.awaitPlanGate(runId)  |
     |  (polls Redis every 500ms)       |
     |         <--------------------    |  gateStore.setPlanGate(runId, approved)
     |  gate resolved!                  |
     v                                  |
```

The pipeline thread releases its compute semaphore slot while waiting at a gate, so gate-waiting runs do not block new submissions.

**Gate-loss fallback:** If a server restarts while a run is at a gate, `approve()` detects the missing gate and auto-fails the run with a message.

### 8.2 WorkerPool

**File:** `harness/WorkerPool.java`

Executes worker tasks sequentially in topological order with progressive merge after each task.

**Key Methods:**

| Method | Signature | Description |
|--------|-----------|-------------|
| `runAll` | `WorkerPoolResult runAll(List<AgentTask> tasks, String runId, String model)` | Main entry: topological sort, sequential execution, progressive merge. Returns reports + merge tracking. |
| `executeTask` | `WorkerReport executeTask(AgentTask task, String runId)` | Synchronous single-task execution (used by retry flow). |
| `resubmitTask` | `void resubmitTask(AgentTask task, String runId)` | Async single-task execution in a virtual thread (used by recovery). |
| `cancelTask` | `void cancelTask(String taskId)` | Interrupts the thread running a task. |
| `cancelAllTasksForRun` | `void cancelAllTasksForRun(String runId)` | Cancels all tasks for a run. |

**Topological Sort:** Uses Kahn's algorithm. Tasks with no dependencies come first. Circular dependencies are appended at the end with a warning.

### 8.3 SequentialTaskExecutor

**File:** `harness/SequentialTaskExecutor.java`

Enhanced task executor with model routing via the model matrix. Replaces direct `WorkerPool.runAll()` calls in PipelineHarness.

**Flow per task:**
1. Check dependency failures (skip if any dep failed, mark BLOCKED)
2. Select model via `ModelResolver.resolve(agentTier, provider)` using the model-matrix
3. Lock task in DB + set branch name
4. Execute worker: `WorkerAgent.execute(task, runId)` in isolated worktree
5. Auto-commit worktree if dirty
6. Progressive merge via `GitMergeService.mergeTaskBranchWithResolution()`
7. Track metrics via `ModelTaskMetricRepository`

**Note:** Per-task validation has been removed. Validation is deferred to the review phase (CompilerAgent + ReviewerAgentV2).

**Result:** `SequentialExecutionResult(reports, completedTasks, failedTasks)`

### 8.4 ApprovalGate

**File:** `harness/ApprovalGate.java`

CLI-only approval gate (interactive stdin). The REST path uses Redis-backed `GateStore` instead.

| Method | Signature | Description |
|--------|-----------|-------------|
| `requestApproval` | `boolean requestApproval(TaskPlan plan, PipelineRun run)` | Displays plan details and cost estimate, prompts for y/n. |
| `requestMerge` | `boolean requestMerge(ReviewReport review)` | Displays review summary, blockers, and suggestions, prompts for y/n. |

### 8.5 GitMergeService

**File:** `harness/GitMergeService.java`

Merges task branches into main with a multi-tier conflict resolution cascade.

**Resolution Cascade:**
1. **SpecializedMerger** (pom.xml, YAML, package.json) -- deterministic, zero LLM cost
2. **ConflictResolverAgent (Haiku)** -- LLM-powered, cheap
3. **ConflictResolverAgent (Sonnet)** -- escalation for complex conflicts
4. **FAILED** -- human intervention required

**Key Methods:**

| Method | Signature | Description |
|--------|-----------|-------------|
| `mergeAll` | `List<String> mergeAll(String runId, List<AgentTask> tasks)` | Merges all branches in topological order. Returns list of conflicted branch names. |
| `mergeTaskBranchWithResolution` | `TaskMergeResult mergeTaskBranchWithResolution(String runId, AgentTask task, List<AgentTask> previouslyMerged)` | Public entry for progressive merge. Retries up to 2 times on transient failures. |

**MergeOutcome enum:** `CLEAN`, `RESOLVED_DETERMINISTIC`, `RESOLVED_LLM`, `ESCALATED`, `FAILED`

### 8.6 PostMergeOrchestrator

**File:** `harness/event/PostMergeOrchestrator.java`

Listens for `PipelineEvent.MergeCompleted` and launches all post-merge agents in parallel virtual threads:

```java
@Async @EventListener
public void onMergeCompleted(PipelineEvent.MergeCompleted event) {
    promptAuditService.recordRunOutcome(event.hadFailures());
    Thread.startVirtualThread(() -> contextAgent.runAsync(...));
    Thread.startVirtualThread(() -> promptOptimiserAgent.runIfDue(...));
    Thread.startVirtualThread(() -> apiTestSuiteAgent.generateAsync(...));
}
```

All agents are non-fatal. Failures are logged but do NOT affect run status.

**Integration events:** `IntegrationManager` also listens to `MergeCompleted` (not `RunCompleted`) to avoid a race condition where the review report was null because `RunCompleted` fires before background review saves. Both PROTOTYPE and PRODUCTION modes now publish `MergeCompleted`.

---

## 9. Model Routing

### 9.1 Model Matrix (application.yml)

The system uses a YAML-configured model matrix that maps `AgentTier x Provider -> Model`. The user selects a provider per project; the model is determined automatically based on the agent's tier.

**Agent Tier Assignments:**

| Tier | Agents |
|------|--------|
| PLANNING | PlannerAgent, ReviewerAgentV2 |
| WORKHORSE | WorkerAgent, CompilerAgent, TestPlannerAgent, TestWorkerAgent, ApiTestSuiteAgent, ConflictResolverAgent, PromptOptimiserAgent, ValidationReportGenerator |
| LIGHTWEIGHT | ConversationAgent, ContextAgent, TestReportGenerator, RequestTriageService |

**Model Matrix:**

| Tier | Anthropic | Google | OpenAI |
|------|----------|--------|--------|
| PLANNING | `claude-sonnet-4-6` | `gemini-3-flash-preview` | `gpt-5.4` |
| WORKHORSE | `claude-haiku-4-5-20251001` | `gemini-3-flash-preview` | `gpt-5.4` |
| LIGHTWEIGHT | `claude-haiku-4-5-20251001` | `gemini-3-flash-preview` | `gpt-5.4` |

**Default Provider:** OpenAI

**Failover Order:**
- Anthropic -> OpenAI -> Google
- Google -> OpenAI -> Anthropic
- OpenAI -> Google -> Anthropic

### 9.2 ModelResolver

**File:** `router/ModelResolver.java`

Resolves the model for a given agent tier and provider using the model-matrix configuration from `ModelMatrixProperties`.

```java
ResolvedModel resolve(AgentTier tier, LlmProvider provider)
  --> Reads model-matrix.providers.{provider}.{tier} from application.yml
  --> Returns ResolvedModel(provider, model)

ResolvedModel resolveFallbackExcluding(String agentName, LlmProvider userProvider, String excludeProvider)
  --> Looks up the agent's tier from agent-tier-map
  --> Iterates the failover chain for userProvider, skipping excludeProvider
  --> Returns the first healthy fallback ResolvedModel
  --> Throws IllegalStateException if no fallback is available
```

Used by WorkerAgent for mid-task provider failure recovery: when the primary provider fails during an agentic loop, this method selects an alternative provider while excluding the one that just failed.

### 9.3 LlmRouter

**File:** `agent/LlmRouter.java`

Routes LLM calls with provider fallback chains and per-run API key injection.

**Fallback Modes:**
- `IMMEDIATE` -- planner/reviewer: always fallback on any error
- `TASK_BOUNDARY` -- workers: fallback only at iteration 0 or after compaction

**Rate-Limit Handling:**
- Per-minute rate limit: sleep 15s, retry once
- Anthropic overload (529): sleep 30s, retry once
- Other errors: immediate fallback to next provider

**Per-Run Key Injection:**
Keys are resolved via `ApiKeyResolver` (user -> org -> system) and stored in `RunKeyStore` at run-start. The router looks them up by runId for every call.

**Key Methods:**

| Method | Signature | Description |
|--------|-----------|-------------|
| `callWithFallbackTracked` | `LlmResponse callWithFallbackTracked(provider, model, prompt, runId, agentId, mode, iteration, justCompacted)` | Full fallback with provider tracking. Returns `LlmResponse(response, provider, model)`. |
| `callWithFallback` | `ChatResponse callWithFallback(...)` | Convenience wrapper returning only the ChatResponse. |
| `callWithModelOptions` | `ChatResponse callWithModelOptions(provider, model, prompt, runId)` | Builds provider-specific Prompt with model options and calls it. Resolves per-run keys. |

### 9.4 ProviderHealthTracker

**File:** `router/ProviderHealthTracker.java`

Redis-backed circuit breaker that tracks LLM provider health. Used by LlmRouter to skip unhealthy providers.

**Configuration:**

| Parameter | Value | Description |
|-----------|-------|-------------|
| Failure threshold | 5 | Consecutive failures before circuit opens |
| Recovery TTL | 60 seconds | Auto-recovery cooldown via Redis key expiry |

**Behavior:**

```
recordSuccess(provider)  -->  reset failure count, mark healthy (Redis)
recordFailure(provider)  -->  increment count; mark unhealthy at threshold (Redis)
isHealthy(provider)      -->  check Redis key; auto-recover after TTL expiry
```

### 9.5 AvailableProviderResolver

**File:** `router/AvailableProviderResolver.java`

Bridges the `ResolvedKeys` / `RunKeyStore` system with ModelResolver's `Set<LlmProvider>` filter.

**Resolution Rules:**
- `SYSTEM` mode: providers with system-level keys configured
- `OWN_KEYS` mode: only providers with user/org keys (no system fallback)
- `HYBRID` mode: union of system + user/org keys

Also respects org-level `enabledProviders` policy.

### 9.6 ApiKeyResolver

**File:** `agent/ApiKeyResolver.java`

Resolves per-run AI provider API keys using the multi-tenant hierarchy:

```
1. User-level key  (app_user.anthropic_api_key)
2. Org-level key   (org.anthropic_api_key)
3. System key      (application.yml -- null in ResolvedKeys)
```

**Key Source Modes:**
- `SYSTEM` -- force platform keys; ignore user/org keys
- `OWN_KEYS` -- user/org keys only; no system fallback
- `HYBRID` (default) -- user/org first, system fallback

### 9.7 TokenBudgetMonitor

**File:** `agent/TokenBudgetMonitor.java`

Monitors context window usage and triggers compaction or hard-stop.

**Thresholds (from application.yml):**

| Status | Threshold | Action |
|--------|-----------|--------|
| OK | < 25% | Continue normally |
| WARN | >= 25% | Log warning |
| COMPACT | >= 35% | Trigger context compaction |
| HARD_STOP | >= 55% | Emergency compact or force report |

HIGH complexity tasks get +5% extra headroom on all thresholds.

**Key Methods:**

| Method | Signature | Description |
|--------|-----------|-------------|
| `resolveEffectiveComplexity` | `String resolveEffectiveComplexity(String originalComplexity, String model, String projectType, int ownedFilesCount, String taskId)` | Resolves effective complexity considering model capabilities, project type, and file ownership count. The overload without `model` parameter is deprecated. |
| `maxOutputTokens` | `int maxOutputTokens(String model, String complexity)` | Returns the max output token cap for the given model and complexity. Caps to model max to avoid wasteful over-generation. The overload without `model` parameter is deprecated. |

**Context Limits:**
- Anthropic models: 200,000 tokens
- OpenAI models: 128,000 tokens

**Sliding Window:** First 2 + last 18 messages retained during compaction.

### 9.8 CostEstimator

**File:** `agent/CostEstimator.java`

Estimates pipeline cost before plan approval and computes actual cost post-run.

**Pre-run Estimation:**
- Low estimate = 1 iteration per worker
- High estimate = 3 iterations per worker (accounts for retries / compaction)
- Adds fixed planner + reviewer costs

**Post-run Actual Cost (cache-aware):**
- Normal input tokens: 1.00x input rate
- Cache-write tokens: 1.25x input rate
- Cache-read tokens: 0.10x input rate (90% discount)
- Output tokens: 1.00x output rate

Pricing is DB-backed (`model_pricing` table), editable via UI/REST.

---

## 10. Auth and Multi-Tenancy

### 10.1 Tiers

| Tier | Monthly Run Limit | Concurrent Runs | Notes |
|------|-------------------|-----------------|-------|
| FREE | 10 | 1 | Fixed, no org/project access |
| PRO | 100 | 3 | Org-overridable via `monthlyRunLimitOverride` |
| ENTERPRISE | Unlimited* | 10 | *Unlimited if `apiKeysProvided=true`, else 5000/month |

### 10.2 Roles

**Organization Roles:**

| Role | Permissions |
|------|------------|
| SUPER_ADMIN | Global access to all orgs and runs |
| ORG_ADMIN | Full access within their org; manage projects and members |
| BILLING_ADMIN | View all runs in org; manage billing |
| DEVELOPER | Access only to projects they are members of |

**Project Roles:**

| Role | Permissions |
|------|------------|
| PROJECT_ADMIN | Manage project settings and members |
| PROJECT_DEVELOPER | View and create runs in the project |

### 10.3 Run Visibility (RunVisibilitySpec)

- SUPER_ADMIN: all runs
- ORG_ADMIN / BILLING_ADMIN: all runs in their org
- DEVELOPER: runs in projects they are a member of
- FREE users: only their own runs

### 10.4 API Key Source Modes

| Mode | Behavior |
|------|----------|
| `SYSTEM` | Force platform keys for all calls; user/org keys ignored |
| `OWN_KEYS` | User/org keys only; provider unavailable if no key configured (no system fallback) |
| `HYBRID` (default) | User/org keys first; system fallback if no user/org key |

**Validation:** In OWN_KEYS mode, at least one enabled provider must have a key. Validated pre-flight before run submission (returns 400 immediately).

### 10.5 JWT Configuration

- Access token expiry: 1440 minutes (24 hours)
- Refresh token expiry: 7 days
- Admin email: `admin@executespec.ai` (password set during deployment)
- Force-logout via feature flag: When `force_logout` feature flag is enabled, `force_logout_epoch` is set to the current timestamp. JWTs issued before this epoch are rejected by the JWT filter, effectively invalidating all existing sessions.

---

## 11. OAuth2 Authentication

### 11.1 Overview

ExecuteSpec supports social login via Google and GitHub OAuth2 alongside traditional email/password JWT authentication.

**Files:**
- `auth/oauth/OAuthSuccessHandler.java` -- handles successful OAuth2 login
- `auth/oauth/OAuthUserMapper.java` -- maps provider-specific attributes to unified profile
- `auth/oauth/OAuthProfile.java` -- unified profile record (provider, oauthId, email, name, avatarUrl)

### 11.2 OAuth2 Flow

```
1. User clicks "Login with Google" or "Login with GitHub"
2. Browser redirects to provider's OAuth consent page
3. Provider redirects back to /login/oauth2/code/{provider}
4. Spring Security exchanges code for access token
5. OAuthSuccessHandler.onAuthenticationSuccess():
   a. OAuthUserMapper.toProfile() extracts email, name, avatar
   b. AuthService.findOrCreateFromOAuth() finds or creates AppUser
   c. JwtUtil.generateAccessToken() + generateRefreshToken()
   d. Redirects to frontend: /auth/callback?token=...&refresh=...
6. Frontend stores JWT tokens and completes login
```

### 11.3 Provider Configuration

OAuth2 providers are conditionally activated. If no client-id is configured for a provider, the `OAuthSuccessHandler` bean is not created:

```java
@ConditionalOnExpression(
  "!'none'.equals('${spring.security.oauth2.client.registration.google.client-id:none}') " +
  "or !'none'.equals('${spring.security.oauth2.client.registration.github.client-id:none}')")
```

**GitHub Email Fallback:** GitHub may not return an email in the OAuth profile. The handler retrieves the `OAuth2AuthorizedClient` to access the access token and fetch the email via GitHub's API.

### 11.4 Error Handling

| Error | Redirect |
|-------|----------|
| No email from provider | `/login?error=oauth_no_email` |
| Email conflict (existing account) | `/login?error=oauth_email_conflict` |
| Generic OAuth failure | `/login?error=oauth_failed` |

---

## 12. Feature Flags

### 12.1 FeatureFlagService

**File:** `config/featureflag/FeatureFlagService.java`

DB-backed feature flag system with Redis pub/sub for cross-instance cache invalidation.

**Architecture:**
- Feature flags stored in `feature_flag` DB table
- In-memory `ConcurrentHashMap` cache for fast reads
- Redis pub/sub channel `executespec:feature-flag-update` for cross-instance invalidation
- Scheduled full reload every 60 seconds as fallback

**Read API:**

| Method | Signature | Description |
|--------|-----------|-------------|
| `isEnabled` | `boolean isEnabled(String key)` | Check if flag is `"true"` |
| `getString` | `String getString(String key, String defaultValue)` | Get flag value as string |
| `getInt` | `int getInt(String key, int defaultValue)` | Get flag value as integer |

**Write API (admin):**

| Method | Signature | Description |
|--------|-----------|-------------|
| `update` | `FeatureFlag update(String key, String value, String userId)` | Update flag value, publish to Redis, record updater |

**Special Flags:**
- `force_logout` -- When set to `"true"`, stamps `force_logout_epoch` to the current timestamp, invalidating all existing JWTs. Used for emergency session invalidation.

### 12.2 FeatureFlagController

**File:** `config/featureflag/FeatureFlagController.java`
**Base Path:** `/api/admin/feature-flags`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/admin/feature-flags` | List all feature flags |
| `PUT` | `/api/admin/feature-flags/{key}` | Update a feature flag value (body: `{"value": "..."}`) |

**FeatureFlag Entity:**
- `flagKey` -- unique key string
- `flagValue` -- string value
- `valueType` -- BOOLEAN, STRING, INTEGER
- `scope` -- PLATFORM, ORG, PROJECT
- `description` -- human-readable description
- `updatedBy` -- userId of last updater
- `updatedAt` -- timestamp of last update

---

## 13. Rate Limiting

### 13.1 RateLimitFilter

**File:** `ratelimit/RateLimitFilter.java`

Redis-backed per-tenant rate limiting filter. Registered after `JwtAuthenticationFilter` in the Spring Security filter chain.

**Keying Strategy:**
- Authenticated requests: keyed by userId
- Unauthenticated (auth endpoints, webhooks): keyed by IP address

**Implementation:**
- Uses Redis `INCR` with 60-second TTL per sliding window bucket
- In-memory `ConcurrentHashMap` fallback counters when Redis is unavailable
- Local counters cleared every 60s by bucket rotation

**Request Classification:**

| Group | Endpoints | Rate Limited |
|-------|-----------|-------------|
| AUTH | `/api/auth/*` | Yes (prevents brute force) |
| RUNS | `/api/runs` (POST) | Yes (prevents run spam) |
| WEBHOOK | `/api/webhooks/*` | Yes (prevents webhook abuse) |
| READ | GET endpoints | No (internal app traffic) |
| WRITE | Other write endpoints | No (internal app traffic) |

**Skipped Paths:**
- `/api/health` -- health check endpoint
- Non-API paths (static resources, etc.)

**Configuration:**
- `RateLimitProperties` -- enabled flag, default limits
- `RateLimitPolicy` -- classifies request group, resolves per-tier limits

---

## 14. Billing and Payment Gateway

### 14.1 PaymentGatewayRouter

**File:** `billing/gateway/PaymentGatewayRouter.java`

Multi-gateway payment routing that supports both Razorpay and Stripe.

**Resolution Order:**
1. Entity's preferred gateway (if set and enabled)
2. System default gateway (`billing.default-gateway`, default: `razorpay`)
3. Razorpay fallback

**Key Methods:**

| Method | Signature | Description |
|--------|-----------|-------------|
| `resolve` | `PaymentGateway resolve(BillingEntity entity)` | Resolve gateway for billing entity |
| `resolveByName` | `PaymentGateway resolveByName(String gatewayName)` | Resolve by name ("stripe" or "razorpay") |
| `stripe()` | `PaymentGateway stripe()` | Direct Stripe gateway access (for webhooks) |
| `razorpay()` | `PaymentGateway razorpay()` | Direct Razorpay gateway access (for webhooks) |
| `isStripeEnabled` | `boolean isStripeEnabled()` | Check if Stripe is enabled |
| `isRazorpayEnabled` | `boolean isRazorpayEnabled()` | Check if Razorpay is enabled |

**Configuration:**
```yaml
billing:
  default-gateway: razorpay
  stripe:
    enabled: false
  razorpay:
    enabled: true
```

### 14.2 PaymentGateway Interface

**File:** `billing/gateway/PaymentGateway.java`

Common interface implemented by both `StripePaymentGateway` and `RazorpayPaymentGateway`. Methods include subscription creation, cancellation, webhook handling, and payment verification.

### 14.3 Webhook Handling

**File:** `billing/controller/BillingWebhookController.java`

Both webhook endpoints are NOT behind JWT auth -- authenticated via provider-specific signatures.

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/webhooks/razorpay` | HMAC signature (`X-Razorpay-Signature`) | Razorpay webhook handler |
| `POST` | `/api/webhooks/stripe` | Stripe signature (`Stripe-Signature`) | Stripe webhook handler |

**Idempotency:** Webhook events are logged in `webhook_event_log` table with deduplication by `(gateway, event_id)`. Duplicate events are marked `IGNORED`.

**Reliability:** Both endpoints always return 200 to prevent provider retries. Raw payload is stored for audit and reprocessing.

### 14.4 Multi-Currency Support (V54)

- Billing plans support USD, EUR, GBP pricing alongside INR
- Organizations have `country` and `default_currency` fields
- Subscriptions track `currency` and `amount_minor`
- Topup packages support Stripe price IDs

---

## 15. Payment Audit

### 15.1 PaymentAuditService

**File:** `auth/service/PaymentAuditService.java`

Records all payment-related events for audit compliance.

**Fields per audit entry:**
- `entityType` / `entityId` -- what was affected (subscription, topup, etc.)
- `eventType` -- what happened (SUBSCRIPTION_CREATED, PAYMENT_RECEIVED, etc.)
- `gatewayProvider` -- STRIPE or RAZORPAY
- `gatewayEventId` / `gatewayPaymentId` -- provider-side identifiers
- `currency` / `amountMinor` -- payment amount in minor units
- `metadata` -- JSONB for additional context
- `idempotencyKey` -- prevents duplicate processing
- `userId` / `orgId` -- who was affected

### 15.2 PaymentAuditController

**File:** `billing/controller/PaymentAuditController.java`

| Method | Path | Role | Description |
|--------|------|------|-------------|
| `GET` | `/api/admin/payment-audit` | SUPER_ADMIN | List audit entries (paginated, filterable by orgId or userId) |
| `GET` | `/api/admin/payment-audit/export` | SUPER_ADMIN | Export audit as CSV download |
| `GET` | `/api/admin/webhook-events` | SUPER_ADMIN | List webhook event log (filterable by gateway) |
| `GET` | `/api/billing/payment-history` | ORG_ADMIN, BILLING_ADMIN | Payment history scoped to org |

---

## 16. Deployment and Preview

### 16.1 PreviewService

**File:** `deployment/preview/PreviewService.java`

Dual-mode preview service that builds and runs ephemeral dev servers for completed runs. Mode is selected via the `preview.mode` property.

**Dual-Mode Architecture:**

| Mode | Config Value | Infrastructure | HTTPS | URL Pattern |
|------|-------------|----------------|-------|-------------|
| Docker (default) | `docker` | Docker Compose on local/VM, Caddy reverse proxy | Cloudflare Origin Certificate via Caddy | `https://{runId}.preview.executespec.ai` |
| Cloud Run | `cloud-run` | Build image, push to Artifact Registry, deploy to GCP Cloud Run | Managed by Cloud Run (automatic) | `https://es-preview-{runId8}.run.app` |

**Lifecycle:** `PreviewStatus` enum: IDLE -> ANALYZING -> BUILDING -> STARTING -> RUNNING / FAILED / TIMED_OUT / STOPPED

**Public API:**
- `start(runId, user, envVars)` -- starts preview on a context-wrapped virtual thread; returns immediately with ANALYZING status
- `stop(runId)` -- tears down Docker Compose or Cloud Run service (idempotent); decrements `ACTIVE_PREVIEWS` gauge
- `getStatus(runId)` -- returns `PreviewSession` (null if no active preview)
- `getLogs(runId, tailLines, filter)` -- returns logs from Docker Compose (`docker compose logs`) or Cloud Run (`gcloud logging read`)
- `analyze(runId)` -- detects database type and required env vars (pre-start step)

**Key Methods:**

- **`buildAndStartCloudRun()`** -- Full Cloud Run preview lifecycle:
  1. Generate Cloud Run-compatible Dockerfile (normalizes port to 8080, no ttyd for CLI projects)
  2. Build Docker image via `ArtifactRegistryClient.buildImage()`
  3. Push image to Artifact Registry via `ArtifactRegistryClient.pushImage()`
  4. Deploy to Cloud Run via `CloudRunClient.deployPreview()` (1 vCPU, 512Mi, min-instances=0)
  5. Health check via `CloudRunClient.healthCheck()` (3 retries, 5s delay)
  6. On success: update session to RUNNING with public URL; notify `TestPreviewCoordinator`
  7. On failure: clean up both Cloud Run service and Artifact Registry image

- **`getCloudRunLogs()`** -- Fetches logs from Cloud Run via `gcloud logging read` with a 30-second timeout. Queries `resource.type=cloud_run_revision` filtered by service name, limited to the last 10 minutes of logs in ascending order.

- **`cleanupCloudRun()`** -- Best-effort cleanup that deletes the Cloud Run service via `CloudRunClient.deleteService()` and the Artifact Registry image via `CloudRunClient.deleteImage()`. Exceptions are logged but not rethrown.

- **`fail()`** -- Marks a preview as FAILED: increments `PREVIEW_FAILURES` counter, decrements `ACTIVE_PREVIEWS` gauge, runs cleanup (Cloud Run service + image deletion for cloud-run mode, or Docker Compose teardown + port release for docker mode), updates session status, and emits `PREVIEW_FAILED` SSE event.

**Cloud Run Orphan Fix:** `PreviewService` stores `cloudRunServiceName` upfront in the initial session (predictable `es-preview-{runIdShort}`). Prevents orphan cleanup job from deleting services mid-deploy.

**Virtual Thread Context Propagation:**

All virtual threads are wrapped with `ContextSnapshotFactory.captureAll().wrap()` to propagate Micrometer observation context (traceId/spanId) from the calling thread. This ensures distributed traces are connected across the async preview lifecycle:

```java
var snapshot = ContextSnapshotFactory.builder().build().captureAll();
Thread.ofVirtual().name("preview-cr-" + runId.substring(0, 8))
    .start(snapshot.wrap(() -> buildAndStartCloudRun(...)));
```

**Guards:**
- Preview must be enabled (`preview.enabled=true`)
- Run must be in an allowed status: COMPLETE, PARTIAL, WORKING, REVIEWING, or AWAITING_MERGE
- Concurrent preview limit per user (tier-based)
- Per-run preview start limit (tier-based, from `TierConfig.maxPreviewStarts`)
- Idempotent: returns existing session if already ANALYZING/BUILDING/STARTING/RUNNING; clears and retries if FAILED/TIMED_OUT

**Container Console (Docker mode only):**

`execInContainer(runId, cmd, args)` allows allowlisted commands to be run inside the live app container via `docker compose exec -T`:

- **Server-side allowlist:** `ls`, `ps`, `cat`, `curl`, `env`, `df`, `free`, `netstat`, `ss`, `ping`, `whoami`, `hostname`, `date`, `uptime`, `pwd`
- **Shell metacharacter rejection:** `|`, `&`, `;`, `` ` ``, `>`, `<`, `..`, `\n` — throws `IllegalArgumentException` before exec
- **State guard:** throws `IllegalStateException` (→ HTTP 503) if preview is not `RUNNING`
- **Docker-only:** Cloud Run mode returns `IllegalStateException` ("exec not supported in cloud-run mode")
- Delegates to `ComposeExecutor.execInService(projectName, "app", cmd, Duration.ofSeconds(10))`
- Returns `ExecResult(stdout, stderr, exitCode)` — exposed via `POST /api/runs/{runId}/preview/exec`

**TTL Enforcement:**

A `@Scheduled` method runs every 30 seconds to expire previews past their TTL (**30 minutes**, configurable via `preview.preview-ttl-minutes`). Expired Docker previews are torn down via `docker compose down`; expired Cloud Run previews trigger `cleanupCloudRun()`.

**Project Type Detection:**

`DevDockerfileGenerator` reads `.agent-context/project.json` (written by PlannerAgent) to determine the project type before falling back to file-based heuristics. Detects:
- Single-package full-stack: both backend deps (express/fastify/etc) AND frontend deps (react/vue/vite/etc) in the same `package.json` -> FULL_STACK
- NODE_BACKEND: includes `ENV PORT=3000` to match Docker exposed port
- STATIC_HTML: checked before `hasJsOrTsFiles()` to prevent misdetection

### 16.2 CloudRunClient

**File:** `deployment/CloudRunClient.java`

GCP Cloud Run CLI wrapper. All subprocess calls use bounded timeouts to prevent hung processes.

**Methods and Timeouts:**

| Method | Purpose | Timeout |
|--------|---------|---------|
| `deploy()` | Deploy/update a Cloud Run service | 10 minutes |
| `deployPreview()` | Deploy ephemeral preview with min-instances=0 and labels | 10 minutes |
| `healthCheck()` | GET service URL, expect status < 500 (3 retries, 5s delay) | 15s per request (10s connect) |
| `deleteService()` | Delete a Cloud Run service | 5 minutes (via `exec()`) |
| `deleteImage()` | Delete Artifact Registry image | 5 minutes (via `exec()`) |
| `listPreviewServices()` | List services with `managed-by=executespec-preview` label | 2 minutes |
| `rollbackToRevision()` | Route 100% traffic to a specific revision | 5 minutes |
| `ensurePublicAccess()` | Set `allUsers -> roles/run.invoker` IAM binding | 2 minutes |
| `exec()` | Generic subprocess helper | 5 minutes |

**Pipe Deadlock Fix:** `deploy()` and `deployPreview()` read stdout and stderr in parallel using `CompletableFuture.supplyAsync()` for the stderr stream. This prevents the OS pipe buffer from filling up and blocking the subprocess when gcloud writes large amounts of progress output to stderr.

**Health Check Configuration:**
- `HttpClient` has a 10-second connect timeout (set at construction)
- Each request has a 15-second per-request timeout
- 3 attempts with 5-second sleep between retries (cold-start tolerance)
- Success: any HTTP status < 500

**Preview Deploy Features:**
- `--min-instances=0` for cost savings (scale to zero when idle)
- `--max-instances=1` (ephemeral, single instance)
- `--cpu-boost` for faster cold starts
- Startup probe: `httpGet` with 5s initial delay, 10s period, 30 retries (up to 5 minutes for heavy apps)
- Labels: `managed-by=executespec-preview` for orphan cleanup
- Environment variables injected via temporary YAML file (avoids shell escaping issues)
- `PORT` env var removed from user input (Cloud Run sets it automatically via `--port`)
- Post-deploy `ensurePublicAccess()` as fallback if `--allow-unauthenticated` silently failed

### 16.3 ArtifactRegistryClient

**File:** `deployment/ArtifactRegistryClient.java`

Docker image build and push client for GCP Artifact Registry.

**Methods:**

| Method | Purpose | Timeout |
|--------|---------|---------|
| `buildImage()` | `docker build --platform linux/amd64` in workspace | Configurable via `props.docker().buildTimeoutSeconds()` |
| `pushImage()` | `docker push` to Artifact Registry | 10 minutes |
| `fullImageUri()` | Returns `{host}/{project}/{repo}/{tag}` |  |

**Build Output Handling:** Build logs are captured with a rolling window (max 8000 chars) to prevent OOM on large build outputs. If the build fails, the last 500 chars of output are included in the error message.

**Error Detection:** Docker build checks for "Cannot connect to the Docker daemon" and provides a user-friendly message to start Docker Desktop.

### 16.4 DeploymentService

**File:** `deployment/DeploymentService.java`

Full (permanent) deployment pipeline for completed runs. Deploys to Cloud Run with GitHub integration and multi-phase lifecycle.

**Deploy Pipeline Phases:**
1. GitHub (optional): create/push repo based on `RepoMode` (NEW, EXISTING, NONE)
2. Docker build via `ArtifactRegistryClient.buildImage()`
3. Push to Artifact Registry via `ArtifactRegistryClient.pushImage()`
4. Deploy to Cloud Run via `CloudRunClient.deploy()`
5. Health check via `CloudRunClient.healthCheck()`

**Virtual Thread Context Propagation:**

The async deploy pipeline runs on a virtual thread wrapped with `ContextSnapshotFactory` for trace propagation:

```java
var snapshot = ContextSnapshotFactory.builder().build().captureAll();
Thread.ofVirtual().name("deploy-" + runId.substring(0, 8))
    .start(snapshot.wrap(() -> executeDeployPipeline(...)));
```

**Deployment Status Flow:** PENDING -> BUILDING -> PUSHING -> DEPLOYING -> LIVE / FAILED

**Observability:** Records metrics for total deploy duration, build phase duration, health check duration, deploy failures (tagged by reason), and active deploy count.

**Additional Features:**
- `rollback(deploymentId)` -- instant traffic shift to a previous revision (no rebuild)
- `deleteDeployment(deploymentId)` -- manual cleanup (deletes Cloud Run service + AR image)
- `validatePrerequisites()` -- `@PostConstruct` check for Docker, gcloud auth, and GitHub PAT availability

### 16.5 PreviewController

**File:** `api/controller/PreviewController.java`

REST controller for preview management. All endpoints require run access via `RunAccessGuard`.

**Endpoints:**

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/runs/{runId}/preview/analyze` | Detect database type and required env vars |
| `GET` | `/api/runs/{runId}/preview/status` | Get current preview session status |
| `POST` | `/api/runs/{runId}/preview/start` | Start preview (accepts optional `envVars` map) |
| `DELETE` | `/api/runs/{runId}/preview/stop` | Stop and tear down preview |
| `GET` | `/api/runs/{runId}/preview/logs` | Get preview logs (supports `lines` and `filter` params) |
| `GET` | `/api/runs/{runId}/preview/probe-api-docs` | Probe for OpenAPI/Swagger endpoints |

**Dual-Mode API Docs Probe (`probeApiDocs`):**

The probe endpoint works for both Docker and Cloud Run previews:
- **Docker mode:** probes `http://localhost:{hostPort}` using the session's assigned host port
- **Cloud Run mode:** probes the Cloud Run service URL directly (e.g., `https://es-preview-xxx.run.app`)

Probes the following paths in order: `/v3/api-docs`, `/api-docs`, `/swagger.json`, `/openapi.json`, `/docs`, `/swagger-ui.html`, `/swagger-ui/index.html`. Returns the first path that responds with HTTP 2xx/3xx.

**Dual-Mode Log Retrieval (`getLogs`):**

The logs endpoint delegates to `PreviewService.getLogs()` which routes based on preview mode:
- **Docker mode:** fetches via `docker compose logs` for the compose project
- **Cloud Run mode:** fetches via `gcloud logging read` filtered by service name (last 10 minutes, max 200 lines)

### 16.6 GCS Workspace Archiving

- Completed run workspaces are archived to GCS after `archiveAfterHours` (default: 24)
- Archived as zip files: `workspaces/{runId}.zip`
- Local directory deleted after successful upload
- Download endpoint generates a 5-minute signed URL for direct GCS download

### 16.7 GitHub Integration

**File:** `integration/github/GitHubRepoService.java`

For existing-repo runs, the pipeline automatically:
1. Creates a branch `executespec/{runId-short}` via `GitTool.pushToBranch()`
2. Pushes all generated code to the branch (not main)
3. Opens a PR via `GitHubRepoService.createPullRequest()`
4. Stores PR URL in `PipelineRun.githubPrUrl` (surfaced in RunDetail API and frontend overview tab)

**Key methods:**
- `getDefaultBranch(owner, repo)` -- looks up the default branch of the target repository
- `createPullRequest(owner, repo, head, base, title, body)` -- creates a GitHub PR

---

## 17. REST API Reference

### 17.1 Run Endpoints (`RunController`)

| Method | Path | Description | Status Code |
|--------|------|-------------|-------------|
| `POST` | `/api/runs` | Submit a new run | 202 Accepted |
| `GET` | `/api/runs` | List runs (filtered by status, projectId; paginated) | 200 |
| `GET` | `/api/runs/{runId}` | Get run detail with tasks | 200 / 404 |
| `POST` | `/api/runs/{runId}/approve` | Approve/reject plan gate | 200 / 404 / 409 |
| `POST` | `/api/runs/{runId}/merge` | Legacy merge gate endpoint (merge is now automatic) | 200 / 404 / 409 |
| `POST` | `/api/runs/{runId}/retry` | Retry a failed run | 202 |
| `POST` | `/api/runs/{runId}/pause` | Pause a running pipeline | 200 / 409 |
| `POST` | `/api/runs/{runId}/edit-and-restart` | Edit goal of paused run and restart | 201 |
| `DELETE` | `/api/runs/{runId}` | Cancel a run | 200 / 404 |
| `POST` | `/api/runs/{runId}/tasks/{taskId}/retry` | Retry a single task | 202 / 404 / 409 |
| `POST` | `/api/runs/{runId}/implement-suggestions` | Implement reviewer suggestions | 202 / 409 |
| `POST` | `/api/runs/{runId}/rework` | Rework flagged tasks | 202 / 409 |
| `POST` | `/api/runs/{runId}/reopen-merge` | Legacy endpoint (merge gate removed) | 202 / 409 |
| `GET` | `/api/runs/{runId}/recovery` | Get recovery history | 200 |
| `GET` | `/api/runs/{runId}/messages` | Get agent message log (last 200) | 200 |
| `GET` | `/api/runs/{runId}/download` | Download workspace as zip | 200 / 302 (GCS) |
| `GET` | `/api/runs/{runId}/files` | List workspace files | 200 |
| `GET` | `/api/runs/{runId}/file?path=` | Read a single workspace file | 200 |

**Run Submission Request Body (`RunRequest`):**
```json
{
  "userRequest": "Build a REST API for user management",
  "source": "REST",
  "repoUrl": "https://github.com/user/repo.git",
  "repoMode": "EXISTING",
  "projectId": "proj-123",
  "skipApproval": false,
  "modelName": "claude-sonnet-4-6"
}
```

**Plan Approval Request Body (`ApprovalRequest`):**
```json
{
  "approved": true,
  "comment": "Looks good, proceed."
}
```

### 17.2 SSE Endpoint (`SseController`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/runs/{runId}/events` | SSE stream of run events |

### 17.3 Timeline Endpoint (`TimelineController`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/runs/{runId}/timeline` | Timeline of events for a run |

### 17.4 Feasibility Endpoint (`FeasibilityController`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/feasibility` | Pre-run cost and plan estimate (no run created) |

### 17.5 Pricing Endpoint (`PricingController`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/pricing` | Get all model pricing |
| `PUT` | `/api/pricing` | Update model pricing |

### 17.6 Model Endpoint (`ModelController`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/models/available` | Get available models per provider (from model-matrix config) |

### 17.7 Preview Endpoints (`PreviewController`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/runs/{runId}/preview/analyze` | Detect database type and required env vars (pre-start) |
| `GET` | `/api/runs/{runId}/preview/status` | Get preview session status |
| `POST` | `/api/runs/{runId}/preview/start` | Start preview (accepts optional `envVars` map in body) |
| `DELETE` | `/api/runs/{runId}/preview/stop` | Stop and tear down preview |
| `GET` | `/api/runs/{runId}/preview/logs` | Get logs (Docker Compose or Cloud Run via `gcloud logging read`) |
| `GET` | `/api/runs/{runId}/preview/probe-api-docs` | Probe for OpenAPI/Swagger endpoints (works for both Docker and Cloud Run) |
| `POST` | `/api/runs/{runId}/preview/exec` | Execute an allowlisted diagnostic command inside the running preview container (Docker mode only). Body: `{"cmd":"ls","args":["-la"]}`. Returns `{stdout, stderr, exitCode}`. 400 on blocked command, 503 if preview not RUNNING or in Cloud Run mode. |

### 17.8 Deployment Endpoints (`DeploymentController`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/runs/{runId}/deploy` | Deploy to Cloud Run |
| `GET` | `/api/runs/{runId}/deployments` | List deployments |
| `DELETE` | `/api/runs/{runId}/deployments/{version}` | Delete a deployment |

### 17.9 Conversation Endpoints (`RunConversationController`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/runs/{runId}/conversation` | Send a chat message about a completed run |
| `GET` | `/api/runs/{runId}/conversation` | Get conversation history |

### 17.10 Validation Report Endpoints (`ValidationReportController`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/runs/{runId}/report` | Get validation report |
| `GET` | `/api/runs/{runId}/report/pdf` | Download report as PDF |
| `POST` | `/api/runs/{runId}/report/share` | Generate share link |
| `GET` | `/api/reports/shared/{token}` | Public shared report (no auth) |

**`ValidationReportDTO` response fields** (`report/model/ValidationReportDTO.java`):

| Field | Type | Description |
|-------|------|-------------|
| `runId` | String | |
| `verdict` | String | APPROVED / APPROVED_WITH_NOTES / NEEDS_FIXES / REJECTED |
| `summary` | String | Text summary from reviewer |
| `issues` | List | List of `ReportIssueDTO` (severity, description, file) |
| `pipelineResult` | String | PASS / FAIL / PARTIAL |
| `testReport` | String | Test execution summary |
| `accuracyScore` | Integer | 0-100 spec adherence score (nullable — null if assessment failed or batch run) |
| `accuracyGrade` | String | A/B/C/D/F letter grade (nullable) |
| `previewUrl` | String | URL to the running preview (nullable — null for expired or batch runs) |
| `createdAt` | String | ISO timestamp |

### 17.11 Integration Endpoints (`IntegrationController`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/integrations/jira/webhook` | Jira webhook handler |
| `POST` | `/api/integrations/confluence/webhook` | Confluence webhook handler |
| `POST` | `/api/integrations/servicenow/webhook` | ServiceNow webhook handler |
| `POST` | `/api/integrations/pagerduty/webhook` | PagerDuty webhook handler |

### 17.12 Slack Endpoints (`SlackIntegrationController`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/integrations/slack/install` | Returns Slack OAuth install URL (HMAC-signed state) |
| `GET` | `/api/integrations/slack/oauth/callback` | Handles OAuth redirect, exchanges code for token |
| `GET` | `/api/integrations/slack/status` | Connection status for current org |
| `DELETE` | `/api/integrations/slack/disconnect` | Removes Slack connection |
| `POST` | `/api/integrations/slack/test` | Sends test Block Kit message to a channel |
| `GET` | `/api/integrations/slack/channels` | Lists available Slack channels via conversations.list |
| `GET` | `/api/integrations/slack/channels/project/{projectId}` | Get project -> channel mapping |
| `PUT` | `/api/integrations/slack/channels/project/{projectId}` | Set project -> channel mapping |

### 17.13 Clarification Endpoint (`RunController`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/runs/{runId}/clarify` | Resolve clarification gate (accepts `{response: string}`) |

### 17.14 Metrics Endpoint (`MetricsController`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/metrics` | Aggregate metrics (runs, costs, success rates) |

### 17.15 Auth Endpoints (`AuthController`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/auth/register` | Register a new user |
| `POST` | `/api/auth/login` | Login (returns JWT) |
| `POST` | `/api/auth/refresh` | Refresh access token |
| `GET` | `/api/auth/me` | Get current user profile |
| `GET` | `/api/auth/me/permissions` | Tier-based permissions |

### 17.16 Organization Endpoints (`OrgController`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/orgs` | Create organization (requires name, domains, adminEmail) |
| `GET` | `/api/orgs/{orgId}` | Get organization details |
| `PUT` | `/api/orgs/{orgId}` | Update organization settings |
| `GET` | `/api/orgs/{orgId}/members` | List org members |
| `POST` | `/api/orgs/{orgId}/members` | Invite member |
| `PUT` | `/api/org/me/settings/jira` | Save org JIRA credentials (ORG_ADMIN only) |
| `PUT` | `/api/org/me/settings/confluence` | Save org Confluence credentials (ORG_ADMIN only) |
| `POST` | `/api/org/me/settings/jira/validate` | Test JIRA connection |
| `POST` | `/api/org/me/settings/confluence/validate` | Test Confluence connection |

### 17.17 Project Endpoints (`ProjectController`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/orgs/{orgId}/projects` | Create project |
| `GET` | `/api/orgs/{orgId}/projects` | List projects |
| `GET` | `/api/projects/{projectId}` | Get project details |
| `PUT` | `/api/projects/{projectId}` | Update project |
| `GET` | `/api/projects/{projectId}/members` | List project members |

### 17.18 Billing Endpoints (`BillingController`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/billing/plans` | List billing plans |
| `POST` | `/api/billing/subscribe` | Initiate subscription (routed via PaymentGatewayRouter) |
| `POST` | `/api/billing/topup/purchase` | Buy run top-up credits |
| `GET` | `/api/billing/payment-history` | Payment history for org (ORG_ADMIN / BILLING_ADMIN) |

### 17.19 Webhook Endpoints (`BillingWebhookController`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/webhooks/razorpay` | Razorpay webhook (HMAC auth, no JWT) |
| `POST` | `/api/webhooks/stripe` | Stripe webhook (Stripe-Signature auth, no JWT) |

### 17.20 Feature Flag Endpoints (`FeatureFlagController`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/admin/feature-flags` | List all feature flags (SUPER_ADMIN) |
| `PUT` | `/api/admin/feature-flags/{key}` | Update feature flag (SUPER_ADMIN) |

### 17.21 Payment Audit Endpoints (`PaymentAuditController`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/admin/payment-audit` | List audit entries (SUPER_ADMIN, filterable by orgId/userId) |
| `GET` | `/api/admin/payment-audit/export` | Export audit as CSV (SUPER_ADMIN) |
| `GET` | `/api/admin/webhook-events` | List webhook events (SUPER_ADMIN, filterable by gateway) |

### 17.22 Eval Endpoint (`EvalController`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/admin/eval/run` | Trigger model evaluation (SUPER_ADMIN, optional `providers` param) |

### 17.23 Test Result Endpoints (`TestResultController`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/runs/{runId}/apply-test-fixes` | Apply test-driven fixes to run workspace |
| `POST` | `/api/runs/{runId}/run-tests` | Manually trigger test execution (Beta). Requires preview RUNNING and compiled test plan. Calls `TestPreviewCoordinator.triggerManualExecution()`. Returns 202 Accepted. |

---

## 18. Server-Sent Events

### 18.1 RunEvent

**File:** `sse/RunEvent.java`

Record type representing all real-time events emitted during a pipeline run.

```java
public record RunEvent(
    String type,       // event type
    String runId,      // the run this event belongs to
    String taskId,     // optional task context
    String payload,    // event-specific data
    Instant timestamp  // when the event occurred
)
```

**Event Types:**

| Type | Description | Payload |
|------|-------------|---------|
| `RUN_STATUS` | Run lifecycle state change | Status string (PLANNING, WORKING, COMPILING, MERGING, etc.) |
| `TASK_STATUS` | Task status change | Status string (WORKING, COMPLETE, FAILED, etc.) |
| `LOG` | General log message | Log line text |
| `GATE_OPENED` | Approval gate opened | Gate type (PLAN_APPROVAL) |
| `GATE_RESOLVED` | Gate approved/rejected | "APPROVED" or "REJECTED" |
| `COST_UPDATE` | Cost estimate update | Cost in USD |
| `QUEUED` | Run queued for execution | Queue position |
| `COMPLETE` | Run finished | Final status |
| `RECOVERY_ATTEMPTED` | Recovery triggered | "classification\|action\|locks=N" |
| `RUN_RESTARTED` | Auto-restart scheduled | New run ID |
| `DEPLOY_STARTED` | Deployment started | "version\|strategy" |
| `DEPLOY_LIVE` | Deployment live | "version\|serviceUrl" |
| `DEPLOY_FAILED` | Deployment failed | "version\|error" |
| `API_SPEC_READY` | OpenAPI spec generated | "openapi.yaml" |
| `PREVIEW_READY` | Frontend preview available | Serve directory |
| `PREVIEW_FAILED` | Preview failed | Error message |
| `VALIDATION_STARTED` | File validation started | File name |
| `VALIDATION_PASSED` | Validation passed | null |
| `VALIDATION_FAILED` | Validation failed | Error summary |
| `MODEL_ESCALATED` | Model tier escalated | "fromModel\|toModel" |
| `REVIEW_STARTED` | Review phase started | Model name |
| `REVIEW_ISSUE_FOUND` | Review issue detected | "issueId\|severity\|description" |
| `REVIEW_AUTO_FIX_STARTED` | Auto-fix attempt started | Issue ID |
| `REVIEW_AUTO_FIX_RESULT` | Auto-fix result | "issueId\|PASS/FAIL" |
| `REVIEW_COMPLETE` | Review complete | "verdict\|summary" |
| `CLARIFICATION_NEEDED` | Context extraction needs user input | Clarification prompt text |
| `CLARIFICATION_RESOLVED` | User provided clarification | null |
| `CONTEXT_EXTRACTED` | JIRA/Confluence context fetched | Summary of extracted refs |

### 18.2 SseBroadcaster

**File:** `sse/SseBroadcaster.java`

Manages SSE emitter subscriptions per run ID. Uses `ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>>` for thread-safe multi-client broadcasting.

| Method | Signature | Description |
|--------|-----------|-------------|
| `subscribe` | `SseEmitter subscribe(String runId)` | Creates a no-timeout SSE emitter, registers cleanup handlers. |
| `emit` | `void emit(String runId, RunEvent event)` | Serializes event to JSON and sends to all clients. Prunes dead emitters. |
| `complete` | `void complete(String runId)` | Completes all emitters for a run and removes the registration. Deferred to background validation thread's `finally` block for runs with background validation. |

---

## 19. Recovery System

### 19.1 StuckRunMonitor

**File:** `recovery/StuckRunMonitor.java`

Scheduled task that checks for stuck worker tasks every 2 minutes.

**Detection Logic:**
- Finds all tasks with status=WORKING/LOCKED
- Measures idle time (since `lastActivityAt`)
- Warning at `warnThresholdMinutes` (default: 8 min)
- Recovery at `actThresholdMinutes` (default: 15 min)

### 19.2 StuckRunEvaluator

**File:** `recovery/StuckRunEvaluator.java`

Classifies stuck tasks based on their event log:

| Classification | Description |
|---------------|-------------|
| `LLM_TIMEOUT` | Last event was an LLM request with no response |
| `LLM_LOOP` | Repeated identical tool calls (loop-repeat-threshold: 3) |
| `FILE_LOCK_BLOCK` | Task blocked waiting for a file lock |
| `BUILD_FAILURE_STALL` | Build failures without progress |
| `UNKNOWN` | Cannot determine cause |

**Classification Logic:**

```
1. Check last event: contains "LLM_REQUEST_SENT" or "Calling LLM"?
   --> LLM_TIMEOUT (outbound call with no response)

2. Check for FILE_LOCK_DENIED in recent events:
   --> Last 5 events all FILE_LOCK_DENIED or WAITING?
   --> FILE_LOCK_BLOCK

3. Check for tool call loops in log window:
   --> Any tool called >= loopRepeatThreshold times?
   --> LLM_LOOP

4. Check for build/test failures without subsequent commits:
   --> BUILD_FAILED/TEST_FAILED in events but no commit in last 8?
   --> BUILD_FAILURE_STALL

5. Fallback:
   --> UNKNOWN
```

**Configuration:**

| Property | Default | Description |
|----------|---------|-------------|
| `evaluator-log-window` | 20 | Number of recent events to analyze |
| `loop-repeat-threshold` | 3 | Tool calls before declaring a loop |

### 19.3 RunRecoveryService

**File:** `recovery/RunRecoveryService.java`

Handles recovery actions based on stuck classification:

**Recovery Actions:**

| Classification | Action |
|---------------|--------|
| `LLM_TIMEOUT`, `FILE_LOCK_BLOCK` | Retry task (up to `maxTaskRetries`, default: 1) |
| `LLM_LOOP`, `BUILD_FAILURE_STALL`, `UNKNOWN` | Fresh restart (cancel all tasks, clean workspace, submit new run) |
| Retry limit reached | Escalate to fresh restart |

**Auto-Restart for Transient Failures:**

The `autoRestartIfTransient()` method detects transient failures (rate limits, provider outages, etc.) and schedules automatic restarts with configurable backoff:

- **Transient keywords:** "rate", "429", "overloaded", "503", "timeout", etc.
- **Backoff:** `autoRestartBackoffSeconds` (default: 60s)
- **Max restarts:** `maxAutoRestarts` (default: 3)

**Note:** Auto-restart calls have been removed from PipelineHarness. Users resume stuck runs manually via UI.

Recovery actions are recorded in the `run_recovery` table for audit.

### 19.4 LlmCallGuard

**File:** `recovery/LlmCallGuard.java`

Wraps LLM calls with error classification and automatic retry for transient failures. Used by Scout-role agents (ContextAgent, PromptOptimiserAgent) that need reliable LLM access but do not participate in the LlmRouter fallback chain.

**Key Methods:**

| Method | Signature | Description |
|--------|-----------|-------------|
| `call` | `ChatResponse call(Prompt prompt, String taskId, String runId)` | Full error classification with retry loop. |
| `call` | `ChatResponse call(Prompt prompt, String modelOverride, String taskId, String runId)` | Overloaded version that injects a specific model (e.g., Haiku). |

**Retry Behavior:**

```
attempt = 0
WHILE attempt <= maxTransientRetries (default: 3):
  TRY:
    chatModel.call(prompt)   -- success path
  CATCH HttpClientErrorException:
    classify -> RateLimited?  sleep(retryAfterSeconds or rateLimitBackoffSeconds)
    classify -> Other?        throw LlmCallException immediately
  CATCH HttpServerErrorException:
    exponential backoff -> serverErrorBackoffSeconds * (attempt + 1)
  CATCH Exception:
    exponential backoff -> serverErrorBackoffSeconds * (attempt + 1)
```

### 19.5 LlmFailure (Sealed Interface)

**File:** `recovery/LlmFailure.java`

Sealed interface hierarchy for typed LLM error classification:

| Variant | HTTP Status | Description | Action |
|---------|-------------|-------------|--------|
| `RateLimited` | 429 | Rate limit hit | Sleep and retry |
| `BudgetExhausted` | 429 (quota) or 402 | Billing quota exhausted | Escalate provider immediately |
| `ContextTooLong` | 400 | Context exceeds model limit | Force compaction then retry |
| `AuthFailure` | 401 | Key invalid or expired | Throw (needs human action) |
| `ServerError` | 500/503 | Provider-side error | Exponential backoff |
| `Unknown` | Other | Unclassified error | Throw as LlmCallException |

**429 Disambiguation:** A 429 response is classified as `BudgetExhausted` (not `RateLimited`) if the response body contains "quota", "billing", or "insufficient_quota". This distinction matters because budget exhaustion requires provider escalation, not simple retry.

### 19.6 EscalationService

**File:** `recovery/EscalationService.java`

Manages the model/provider escalation ladder when a task is stuck or failing.

**Escalation Ladder:**

```
Level 0-1: Upgrade to capable model (same provider)
   |-- anthropic: claude-haiku-4-5 -> capable model from props
   |-- openai: gpt-5.4 -> gpt-5.4 (no escalation — same model)
   |
Level 2: Switch to alternate provider entirely
   |-- anthropic -> openai
   |-- openai -> anthropic
   |
Level 3+: Terminal -- trigger fresh run restart
```

**Key Methods:**

| Method | Signature | Description |
|--------|-----------|-------------|
| `escalate` | `void escalate(AgentTask task)` | Main entry: determines escalation level, selects target, cancels current task, resubmits. |
| `escalateProvider` | `void escalateProvider(AgentTask task)` | Jumps directly to level 2 (provider switch). |
| `escalateAfterContextOverflow` | `void escalateAfterContextOverflow(AgentTask task)` | Sets compaction flag and resubmits without changing model/provider. |

**Escalation Flow:**

```
EscalationService.escalate(task)
  |-- Determine level from task.recoveryAttempt
  |-- Level 0-1: resolveCapableModel() -> same provider, better model
  |-- Level 2:   resolveAlternateProvider() -> different provider
  |-- Level 3+:  null -> delegate to RunRecoveryService for fresh restart
  |
  |-- SSE event: MODEL_ESCALATED "fromModel|toModel"
  |-- stateStore.escalateTask(taskId, provider, model)
  |-- workerPool.cancelTask(taskId)
  |-- workerPool.resubmitTask(task, runId)
```

### 19.7 PartialRunResumeService

**File:** `harness/PartialRunResumeService.java`

Scheduled job that automatically resumes `PARTIAL` runs — runs where some tasks completed but at least one failed or got stuck before the pipeline could finish.

**Trigger:** `@Scheduled(fixedDelay = 5 * 60 * 1000)` — every 5 minutes
**Lock:** `@SchedulerLock(name = "partial-run-resume", lockAtMostFor = "4m")` — prevents concurrent sweeps in multi-instance deployments

**Detection Logic:**
- Queries `PipelineRunRepository.findByStatusIn(List.of("PARTIAL"))`
- Filters to runs where `completedAt < Instant.now().minusSeconds(600)` (10-minute debounce — ensures runs that are still finishing don't get swept prematurely)
- Skips runs where `harness.isRunActive(runId)` returns true (already being processed by a pipeline thread)

**Recovery Flow per run:**
1. Find tasks with status `FAILED` or `STUCK` (up to 3 tasks per sweep per run)
2. Call `stateStore.markWorking(runId)` — sets run status back to `WORKING`
3. Call `harness.retryPartialTask(runId, taskId)` for each — same codepath as the manual retry button in the UI

**Safety Limits:**
- Max 3 tasks retried per run per invocation (prevents thundering herd)
- Tasks already at `task-max-retries` (default: 3) are skipped by the existing retry guard and bubble up as permanently failed

**Note:** This service is a companion to `StuckRunMonitor` (detects individual stuck tasks mid-run) and handles the terminal-status gap where a run reached `PARTIAL` and would otherwise require manual intervention.

### 19.8 Worker Stall Detection

The 25-minute wall-clock worker time limit includes stall detection with model escalation. WorkerAgent uses non-JSON escalation when a worker response does not start with `{` (not a completion report).

---

## 20. Configuration Reference

### 20.1 application.yml Structure

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      lettuce:
        pool:
          max-active: 50
          min-idle: 5
          test-on-borrow: true      # validates connections before use (P0 fix — prevents STOPPED conn errors)
          test-while-idle: true     # evicts dead connections from pool proactively
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST:localhost}:${POSTGRES_PORT:5432}/${POSTGRES_DB:executespec}
    username: ${POSTGRES_USER:postgres}
    password: ${POSTGRES_PASSWORD:postgres}
  jpa:
    hibernate:
      ddl-auto: validate           # Flyway manages schema
  flyway:
    enabled: true
    baseline-on-migrate: true
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY:}
      chat:
        options:
          model: claude-haiku-4-5-20251001
          max-tokens: 16000
    openai:
      api-key: ${OPENAI_API_KEY:}
      chat:
        options:
          model: gpt-5.4
          max-tokens: 16000
    google:
      genai:
        api-key: ${GOOGLE_API_KEY:}
        chat:
          options:
            model: gemini-3-flash-preview

# Model Matrix (user-selected provider x agent tier -> model)
model-matrix:
  providers:
    anthropic:
      planning: claude-sonnet-4-6
      workhorse: claude-haiku-4-5-20251001
      lightweight: claude-haiku-4-5-20251001
    google:
      planning: gemini-3-flash-preview
      workhorse: gemini-3-flash-preview
      lightweight: gemini-3-flash-preview
    openai:
      planning: gpt-5.4
      workhorse: gpt-5.4
      lightweight: gpt-5.4
  agent-tier-map:
    PlannerAgent: PLANNING
    ReviewerAgentV2: PLANNING
    TestPlannerAgent: WORKHORSE
    WorkerAgent: WORKHORSE
    TestWorkerAgent: WORKHORSE
    CompilerAgent: WORKHORSE
    ApiTestSuiteAgent: WORKHORSE
    ConflictResolverAgent: WORKHORSE
    ContextAgent: LIGHTWEIGHT
    ConversationAgent: LIGHTWEIGHT
    PromptOptimiserAgent: WORKHORSE
    TestReportGenerator: LIGHTWEIGHT
    ValidationReportGenerator: WORKHORSE
    RequestTriageService: LIGHTWEIGHT
  failover-order:
    anthropic: [openai, google]
    google: [openai, anthropic]
    openai: [google, anthropic]

agent:
  workspace: ./workspace
  roles:
    planner: anthropic
    worker: anthropic
    reviewer: anthropic
  token-budget:
    max-tokens: 16000
    low: 2048
    medium: 4096
    high: 8192
    hard-ceiling-pct: 0.90
  compaction:
    warn-pct: 0.25
    compact-pct: 0.35
    hard-stop-pct: 0.55
  concurrency:
    max-runs: 50
    max-task-duration-minutes: 25
    max-iterations: 80
    safety-iteration-cap: 500
  github:
    pat: ${GITHUB_PAT:}
    default-owner: ${GITHUB_OWNER:}
  shell:
    sandbox-enabled: true
    timeout-seconds: 300
  recovery:
    enabled: true
    warn-threshold-minutes: 8
    act-threshold-minutes: 15
    max-task-retries: 1
    evaluator-log-window: 20
    loop-repeat-threshold: 3
    max-auto-restarts: 3
    auto-restart-backoff-seconds: 60
    default-provider: openai
  context:
    enabled: true
    model: claude-haiku-4-5-20251001
    run-interval: 1
  optimiser:
    enabled: true
    run-interval: 10
    analysis-window-runs: 20
    failure-rate-threshold: 0.20
  compiler:
    enabled: true
    max-attempts: 3
    timeout-seconds: 300
    model: claude-haiku-4-5-20251001
  api-test-suite:
    enabled: true
    model: claude-haiku-4-5-20251001

preview:
  enabled: true
  mode: docker
  total-startup-timeout-seconds: 480
  preview-ttl-minutes: 30           # increased from 5 min — ensures preview outlives background validation (10-25 min)
  port-range-start: 10000
  port-range-end: 10100
  preview-domain: executespec.ai
  proxy:
    enabled: false
  concurrency:
    free-max: 1
    pro-max: 3
    enterprise-max: 10

executespec:
  validator:
    enabled: true
    per-task-validation: true
    per-task-level: TYPE
    project-validation-level: FULL
    max-retries: 3
    default-timeout: 30s

app:
  jwt:
    secret: ${JWT_SECRET:...}
    access-token-expiry-minutes: 1440
    refresh-token-expiry-days: 7
  tiers:
    free:
      monthly-run-limit: 10
      concurrent-run-limit: 1
    pro:
      monthly-run-limit: 100
      concurrent-run-limit: 3
    enterprise:
      monthly-run-limit: -1
      concurrent-run-limit: 10

billing:
  default-gateway: razorpay
  stripe:
    enabled: false
  razorpay:
    enabled: true
```

### 20.2 Key Configuration Properties

**AgentProperties** (`@ConfigurationProperties(prefix = "agent")`):

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `workspace` | `String` | `./workspace` | Root directory for all run workspaces |
| `roles.planner` | `String` | `anthropic` | Default provider for planner |
| `roles.worker` | `String` | `anthropic` | Default provider for workers |
| `roles.reviewer` | `String` | `anthropic` | Default provider for reviewer |
| `token-budget.max-tokens` | `int` | 16000 | Max output tokens per LLM call |
| `token-budget.low` | `int` | 2048 | Output limit for LOW complexity |
| `token-budget.medium` | `int` | 4096 | Output limit for MEDIUM complexity |
| `token-budget.high` | `int` | 8192 | Output limit for HIGH complexity |
| `token-budget.hard-ceiling-pct` | `double` | 0.90 | Force report threshold |
| `compaction.warn-pct` | `double` | 0.25 | Context warning threshold |
| `compaction.compact-pct` | `double` | 0.35 | Context compaction threshold |
| `compaction.hard-stop-pct` | `double` | 0.55 | Context hard-stop threshold |
| `concurrency.max-runs` | `int` | 50 | Max simultaneous active runs |
| `concurrency.max-task-duration-minutes` | `int` | 25 | Max wall-clock time per task |
| `concurrency.max-iterations` | `int` | 80 | Max agentic loop iterations per task |
| `concurrency.safety-iteration-cap` | `int` | 500 | Hard iteration cap (safety net) |
| `concurrency.task-max-retries` | `int` | 3 | Max per-task retry attempts before marking permanently failed (increased from 2 to improve pipeline success rate) |
| `shell.sandbox-enabled` | `boolean` | true | Enable shell command sandboxing |
| `shell.timeout-seconds` | `int` | 300 | Max shell command execution time |
| `compiler.enabled` | `boolean` | true | Enable post-worker compilation |
| `compiler.max-attempts` | `int` | 3 | Max compile-fix attempts |
| `recovery.warn-threshold-minutes` | `int` | 8 | Warn after N minutes idle |
| `recovery.act-threshold-minutes` | `int` | 15 | Recover after N minutes idle |
| `recovery.default-provider` | `String` | `openai` | Default provider for recovery |

**HTTP Client Configuration (`AgentModelConfig`):**
- HTTP/1.1 (avoids HTTP/2 ALPN stalls)
- Connect timeout: 30 seconds
- Read timeout: 300 seconds (5 minutes)

---

## 21. Database Schema

### 21.1 Flyway Migrations

| Version | Description |
|---------|-------------|
| V1 | Core schema: pipeline_run, agent_task, agent_message, file_lock, agent_inbox, gate_event, compaction_event |
| V2 | Add tenant_id column to all tables |
| V3 | Cost and cache fields (actual_cost_usd, cache_read_tokens, cache_write_tokens) |
| V4 | Auth schema: app_user, org, project, org_member, project_member |
| V5 | Seed admin user |
| V6 | Tier config + org run-limit overrides |
| V7 | Fix admin password hash |
| V8 | Per-user GitHub settings (github_pat, github_owner) |
| V9 | Post-run conversation history + iteration linkage |
| V10 | Per-user AI provider keys (anthropic_api_key, openai_api_key, gemini_api_key) |
| V11 | Org-level AI provider keys |
| V12 | GCS workspace archive columns |
| V13 | Merge conflict tracking table |
| V14 | Key source mode + provider restrictions |
| V15 | Task complexity and model metrics |
| V17 | Auto-merge flag (pipeline_run.auto_merge) |
| V18 | Org-level integration settings (admin_email, JIRA/Confluence credentials on org table) |
| V19 | Agent token breakdown (per-task input/output token tracking) |
| V20 | Slack integration (org_slack_integration, project_slack_channel, slack_notification_log) |
| V21 | Tier permissions |
| V22 | Audit |
| V23 | Billing (Razorpay subscriptions) |
| V24 | Usage alerts |
| V25 | Usage backfill |
| V26 | Top-up credits |
| V27 | Apply suggestion |
| V28 | Invoicing/GST |
| V29 | Preview limits |
| V32 | plan_version |
| V34 | Validation report (validation_report table) |
| V37 | Test schema (test_plan, test_task, test_result) |
| V45 | Widen plan_version VARCHAR columns (VARCHAR(255) for IDs, VARCHAR(64) for source) |
| V50 | Free tier restrictions |
| V51 | Force logout flag (feature_flag table support) |
| V52 | GitHub PR URL (pipeline_run.github_pr_url VARCHAR(512)) |
| V53 | Run archive (pipeline_run.archived_at TIMESTAMPTZ + index) |
| V54 | Stripe multi-currency (multi-currency pricing, org country/currency, tax IDs, gateway tracking) |
| V55 | Payment audit expansion (gateway tracking, webhook_event_log table for idempotent webhook processing) |
| V56 | Task completion notes (`agent_task.completion_notes` TEXT column) |
| V57 | Tier display name (`tier_config.display_name` column) |
| V58 | Subtask support (`agent_task.parent_task_id`, `subtask_index`, `subtask_total` columns) |
| V59 | Bitbucket support (repository provider enum expansion) |
| V60 | Preview registry (`preview_session` table for persistent session tracking) |
| V60_1 | Cost credits and pricing (`pipeline_run.credits_charged`, `credits_estimated`, `hard_cap_usd`; `run_cost_log` table) |
| V61 | Batch execution and notifications (`pipeline_run.batch_id`, `approved_execution_mode`; `notification` table) |
| V62 | Billing plan restructure (billing plan table schema changes) |
| V63 | Top-up package restructure (top-up package schema normalization) |
| V64 | Background validation tracking (`background_validation` table — stage, attempt count, per-stage timestamps) |
| V65 | Gate record DB fallback (`gate_record` table — dual-write with Redis for cross-restart gate survival) |
| V66 | User token invalidation (`app_user.token_invalidated_at` — epoch-based force-logout support) |
| V67 | RLS tenant isolation (PostgreSQL row-level security policies on core tables) |
| V68 | Event log and instance tracking (`run_event_log` table; `server_instance` table for instance-aware SSE routing) |
| V69 | Side-effect outbox (`side_effect_outbox` table — transactional outbox for external integration calls) |
| V70 | Data retention (`pipeline_run.retain_until` column; retention policy configuration) |
| V71 | Triage outcome tracking (`triage_outcome` table — stores classification + confidence for ML feedback loop) |
| V72 | Prompt audit (`prompt_audit` table — SHA-256 hash, prompt path, substitution keys, LLM response metadata) |
| V73 | Plan effectiveness tracking (`plan_effectiveness` table — per-run planner model + provider + task outcome rates) |
| V74 | Validation report accuracy (`validation_report.accuracy_score` INTEGER, `accuracy_grade` TEXT, `accuracy_json` TEXT — persists Phase 3 accuracy assessment results) |
| V75 | Validation report preview URL (`validation_report.preview_url` TEXT — stores the preview URL at time of report generation for persistent access) |

### 21.2 Entity Relationship Diagram

```
+------------------+       +----------------------+
|   app_user       |       |     org              |
|------------------|       |----------------------|
| id (PK)          |------>| id (PK)              |
| email            |       | name                 |
| password_hash    |       | tier                 |
| org_id (FK)      |       | key_source_mode      |
| anthropic_api_key|       | enabled_providers    |
| openai_api_key   |       | anthropic_api_key    |
| gemini_api_key   |       | openai_api_key       |
| github_pat       |       | gemini_api_key       |
+------------------+       | admin_email          |
                           | jira_base_url        |
                           | jira_email           |
                           | jira_api_token       |
                           | confluence_base_url  |
                           | confluence_email     |
                           | confluence_api_token |
                           | country              |
                           | default_currency     |
                           +----------------------+
        |                           |
        |                  +--------v---------+
        |                  |    project       |
        |                  |------------------|
        |                  | id (PK)          |
        |                  | org_id (FK)      |
        |                  | name             |
        |                  | status           |
        |                  +------------------+
        |                           |
+-------v----------+      +--------v---------+
|   pipeline_run   |      | project_member   |
|------------------|      |------------------|
| run_id (PK)      |      | project_id (FK)  |
| user_request     |      | user_id (FK)     |
| status           |      | project_role     |
| plan_json        |      +------------------+
| review_json      |
| planner_model    |
| created_by_user_id (FK)
| org_id (FK)      |
| project_id (FK)  |
| key_source       |
| github_pr_url    |
| archived_at      |
+--------+---------+
         |
+--------v---------+       +------------------+
|   agent_task     |       |  agent_message   |
|------------------|       |------------------|
| task_id (PK)     |       | id (PK)          |
| run_id (FK)      |       | run_id (FK)      |
| title            |       | from_agent       |
| complexity       |       | to_agent         |
| status           |       | type             |
| assigned_model   |       | content_summary  |
| branch_name      |       | sent_at          |
| input_tokens     |       | token_count      |
| output_tokens    |       +------------------+
| cache_read_tokens|
| cache_write_tokens|
+------------------+

+------------------+       +------------------+
|  merge_conflict  |       |   run_recovery   |
|------------------|       |------------------|
| id (PK)          |       | id (PK)          |
| run_id           |       | original_run_id  |
| task_id          |       | restart_run_id   |
| file_path        |       | stuck_task_id    |
| resolution_type  |       | classification   |
| resolved_at      |       | action_taken     |
+------------------+       | recovered_at     |
                           +------------------+

+------------------+       +------------------+
|    gate_event    |       | compaction_event  |
|------------------|       |------------------|
| id (PK)          |       | id (PK)          |
| run_id           |       | run_id           |
| gate_type        |       | task_id          |
| opened_at        |       | occurred_at      |
| resolved_at      |       +------------------+
| approved         |
+------------------+

+------------------+       +------------------+
| payment_audit    |       | webhook_event_log|
|------------------|       |------------------|
| id (PK)          |       | id (PK)          |
| entity_type      |       | gateway          |
| entity_id        |       | event_id         |
| event_type       |       | event_type       |
| gateway_provider |       | payload_hash     |
| gateway_event_id |       | raw_payload      |
| gateway_payment_id|      | processing_status|
| currency         |       | error_message    |
| amount_minor     |       | attempts         |
| metadata (JSONB) |       | created_at       |
| idempotency_key  |       | processed_at     |
| user_id          |       +------------------+
| org_id           |
| created_at       |
+------------------+

+------------------+
| feature_flag     |
|------------------|
| id (PK)          |
| flag_key (UNIQUE)|
| flag_value       |
| value_type       |
| scope            |
| description      |
| updated_by       |
| updated_at       |
+------------------+
```

---

## 22. Validation and Code Quality

### 22.1 CodeValidator

**File:** `validator/CodeValidator.java` (interface), `validator/CodeValidatorImpl.java` (implementation)

Code validation that runs during the review phase (CompilerAgent + ReviewerAgentV2). Per-task validation has been removed from SequentialTaskExecutor. The validator uses a pluggable architecture: `CodeValidatorImpl` routes requests to language-specific `LanguageToolchain` implementations through a `GateChain` that executes each validation gate in sequence.

**Configuration (`application.yml`):**

```yaml
executespec:
  validator:
    enabled: true
    per-task-validation: true
    per-task-level: TYPE           # SYNTAX | TYPE | LINT | FULL
    project-validation-level: FULL # level for project-wide validation
    max-retries: 3                 # max validation-driven retries per task
    default-timeout: 30s           # per-gate timeout
```

**Validation Levels:**

| Level | Description |
|-------|-------------|
| `SYNTAX` | Parse/syntax check only |
| `TYPE` | Type checking (default per-task level) |
| `LINT` | Linting rules (React hook violations are blocking; other lint issues are warnings) |
| `FULL` | Full validation including unit tests |

**Language Detection:** `Language.fromPath(file)` detects language from file extension:

| Extension | Language |
|-----------|----------|
| `.java` | JAVA |
| `.ts` | TYPESCRIPT |
| `.tsx`, `.jsx` | REACT |
| `.py` | PYTHON |
| Others | `null` (skip validation) |

### 22.2 GateChain

**File:** `validator/gate/GateChain.java`

The GateChain implements a sequential fail-fast validation pipeline. Each gate is checked in order and processing stops at the first failure:

```
Gate 1: SYNTAX CHECK
  |-- Pass? Continue to Gate 2
  |-- Fail? Return FAIL immediately
  |-- Level == SYNTAX? Return PASS
  |
Gate 2: TYPE CHECK
  |-- Pass? Continue to Gate 3
  |-- Fail? Return FAIL immediately
  |-- Level == TYPE? Return PASS
  |
Gate 3: LINT CHECK
  |-- React hook rule violations? FAIL (blocking)
  |-- Other lint errors? Accumulate as WARNINGS
  |-- Level == LINT? Return PASS or WARN
  |
Gate 4: UNIT TEST
  |-- Pass? Return PASS or WARN
  |-- Fail? Return FAIL
```

**Lint Gate Special Behavior:** Lint errors are non-blocking by default, EXCEPT for React hook violations (`rules-of-hooks`, `exhaustive-deps`) which are treated as hard errors. All other lint issues are downgraded to warnings and the result verdict is WARN instead of FAIL.

**ValidationResult Model:**

```java
ValidationResult {
    ValidationVerdict verdict;     // PASS | WARN | FAIL
    List<ValidationError> errors;  // blocking errors
    List<ValidationError> warnings; // non-blocking warnings
    Map<ValidationGate, GateResult> gateResults; // per-gate results
    Duration totalTime;            // total validation duration
    String rawOutput;              // combined raw output (for retry prompt)
}
```

### 22.3 Language Toolchains

Each language has a dedicated `LanguageToolchain` implementation that provides the four gate operations.

**LanguageToolchain Interface:**

```java
public interface LanguageToolchain {
    Language getLanguage();
    GateResult syntaxCheck(Path projectRoot, Path filePath, Duration timeout);
    GateResult typeCheck(Path projectRoot, Path filePath, Duration timeout);
    GateResult lintCheck(Path projectRoot, Path filePath, Duration timeout);
    GateResult unitTest(Path projectRoot, Path filePath, Duration timeout);
    boolean isAvailable();  // checks if required tools are installed
}
```

**JavaToolchain (`validator/language/JavaToolchain.java`):**

| Gate | Command | Notes |
|------|---------|-------|
| SYNTAX | (delegated to TYPE) | Java cannot compile single files in isolation |
| TYPE | `mvn compile -q -B` or `gradle compileJava -q` | Full project compilation |
| LINT | (skipped in v1) | Checkstyle/SpotBugs planned for future |
| UNIT_TEST | `mvn test -B -q -Dtest={TestClassName}` | Only runs if matching test file exists |

**TypeScriptToolchain (`validator/language/TypeScriptToolchain.java`):**

| Gate | Command | Notes |
|------|---------|-------|
| SYNTAX | (delegated to TYPE) | tsc handles both |
| TYPE | `npx tsc --noEmit --pretty false` | Requires `tsconfig.json` |
| LINT | `npx eslint --format compact {file}` | Requires ESLint config file |
| UNIT_TEST | `npx vitest --run {test}` or `npx jest --run {test}` | Auto-detects vitest vs jest; checks for `.test.ts` or `.spec.ts` |

**ReactToolchain (`validator/language/ReactToolchain.java`):**

Extends TypeScript toolchain behavior for `.tsx`/`.jsx` files with additional React-specific lint rules.

**PythonToolchain (`validator/language/PythonToolchain.java`):**

| Gate | Command | Notes |
|------|---------|-------|
| SYNTAX | `python -m py_compile {file}` | Single-file syntax check |
| TYPE | `mypy {file}` | Requires mypy installed |
| LINT | `ruff check {file}` | Requires ruff installed |
| UNIT_TEST | `pytest {test_file}` | Checks for `test_{name}.py` or `{name}_test.py` |

**Toolchain Availability:** At startup, `CodeValidatorImpl` checks each toolchain's `isAvailable()` method and logs which ones are functional. Missing toolchains cause validation to be skipped (not failed) for that language.

### 22.4 RetryPromptBuilder

**File:** `validator/prompt/RetryPromptBuilder.java`

Builds structured error feedback messages that are injected into the worker's conversation history when validation fails.

**Output Format:**

```
=== VALIDATION FAILED -- FIX REQUIRED ===

The code you generated failed validation. Fix ONLY the errors listed below.
Do NOT rewrite unaffected files.

--- ERRORS ---
File: src/Main.java | Line 15, Col 8 | [TYPE_CHECK] TS2322: Type 'string' is not assignable...
  Suggestion: Use number type instead

--- RAW TOOL OUTPUT ---
[First 3000 chars of raw compiler/lint/test output]

=== END VALIDATION ERRORS ===
Fix the errors above. Return COMPLETE corrected files using writeFile / write_file tool calls.
```

**Design Decisions:**
- Raw tool output is truncated to 3000 chars to avoid overwhelming the context window.
- The prompt instructs the worker to fix ONLY the reported errors (prevents cascading rewrites).
- `buildSummary()` provides a one-line summary for logging and SSE events.

### 22.5 Model Task Metrics

Performance metrics are tracked per task execution for data-driven routing improvements:

```java
ModelTaskMetric {
    runId, taskId, modelName, provider, tier,
    complexity, attempt, validationPassed,
    errorCount, firstErrorGate, durationMs
}
```

Stored in `model_task_metric` table via `ModelTaskMetricRepository`. This data enables future optimizations:
- Identifying which models struggle with specific complexity levels
- Tracking validation pass rates per model/provider combination
- Measuring average task duration for cost estimation refinement

---

## 23. Merge Conflict Resolution

### 23.1 Resolution Cascade

GitMergeService uses a 4-tier cascade to resolve merge conflicts:

```
Tier 1: SpecializedMerger (deterministic, zero LLM cost)
  |-- PomXmlMerger: merges pom.xml dependencies and plugins
  |-- YamlMerger: merges YAML config files
  |-- PackageJsonMerger: merges package.json dependencies and scripts
  |-- UnionMerger: .gitattributes merge=union (README.md, CHANGELOG.md)
  |
  v (if unresolved)
Tier 2: ConflictResolverAgent (Haiku) -- LLM-powered, cheap
  |
  v (if unresolved)
Tier 3: ConflictResolverAgent (Sonnet) -- escalation for complex conflicts
  |
  v (if unresolved)
Tier 4: FAILED -- human intervention required
```

**MergeOutcome enum:** `CLEAN`, `RESOLVED_DETERMINISTIC`, `RESOLVED_LLM`, `ESCALATED`, `FAILED`

### 23.2 FileMergerRegistry

**File:** `harness/merge/FileMergerRegistry.java`

Routes conflicted files to the appropriate `SpecializedMerger` implementation. If no merger matches, returns `Optional.empty()` and the caller falls through to the LLM-powered resolver.

```java
public Optional<String> tryMerge(String filePath, String base, String ours, String theirs)
```

Mergers are checked in Spring `@Order` sequence. First matching merger wins.

### 23.3 SpecializedMerger Interface

**File:** `harness/merge/SpecializedMerger.java`

All specialized mergers implement this interface:

```java
public interface SpecializedMerger {
    boolean canHandle(String filePath);
    Optional<String> merge(String base, String ours, String theirs);
}
```

**Three-way merge parameters:**
- `base` -- common ancestor content (before any task modified the file)
- `ours` -- main branch content (already-merged tasks)
- `theirs` -- incoming branch content (new task being merged)

Returns `Optional.empty()` when the merger detects a conflict it cannot resolve deterministically (e.g., version conflicts in pom.xml). This causes fallthrough to the LLM resolver.

### 23.4 PomXmlMerger

**File:** `harness/merge/PomXmlMerger.java`

Deterministic merger for Maven `pom.xml` files.

**Strategy:**
1. Parse all three versions as XML DOM
2. Union dependencies from both sides, deduplicate by `groupId:artifactId`
3. Union plugins from both sides, deduplicate by `groupId:artifactId`
4. If same GA pair has **different versions**: return empty (fallthrough to LLM)
5. If same GA pair has **same version**: keep one copy (no conflict)
6. New GA from either side: add to merged document

**Security:** Uses `disallow-doctype-decl` to prevent XXE attacks when parsing XML.

### 23.5 YamlMerger

**File:** `harness/merge/YamlMerger.java`

Handles YAML config files (application.yml, docker-compose.yml, etc.). Deep-merges YAML maps. Conflicts on scalar values fall through to LLM.

### 23.6 PackageJsonMerger

**File:** `harness/merge/PackageJsonMerger.java`

Handles `package.json` files. Unions `dependencies`, `devDependencies`, and `scripts` sections. Version conflicts fall through to LLM.

### 23.7 Progressive Merge

Tasks are merged to main **immediately** after completion (not at end of run):

**Benefits:**
- Subsequent tasks see all prior work on main
- Code tab in UI shows files as each task finishes
- Merge conflicts detected early (per-task, not end-of-run)

**Auto-retry:** Transient merge failures (JGit errors, lock contention) are retried up to 2 times with 500ms delay before being marked FAILED.

**Progressive Merge Flow:**

```
SequentialTaskExecutor.tryMergeToMain(runId, task, branchName)
  |
  v
GitMergeService.mergeTaskBranchWithResolution(runId, task, previouslyMerged)
  |-- GitTool.mergeBranchNoCommit(runId, branchName)
  |     |-- CLEAN: commit merge, return CLEAN
  |     |-- CONFLICT: attempt resolution
  |           |
  |           v
  |-- For each conflicted file:
  |     |-- FileMergerRegistry.tryMerge(path, base, ours, theirs)
  |     |     |-- Resolved? Write merged content, continue
  |     |     |-- Unresolved? Fallthrough to LLM
  |     |
  |     |-- ConflictResolverAgent (Haiku): call with conflict context
  |     |     |-- Resolved? Write content, continue
  |     |     |-- Unresolved? Escalate
  |     |
  |     |-- ConflictResolverAgent (Sonnet): call with conflict context
  |     |     |-- Resolved? Write content, continue
  |     |     |-- Unresolved? FAILED
  |
  |-- All files resolved? Commit merge, return outcome
  |-- Any file unresolved? Abort merge, mark task FAILED
```

### 23.8 Merge Conflict Tracking

**File:** `harness/merge/MergeConflictAnalyser.java`

All merge conflicts are recorded in the `merge_conflict` table for analysis. The `MergeConflictAnalyser` produces reports for two audiences:

**Planner Context Injection:**

`buildPlannerContext(topFileLimit, runLimit)` generates a report injected into the planner system prompt:

```
## Historical Merge Conflict Data

The following files have caused merge conflicts in recent runs:
- pom.xml: 5 conflicts (80% auto-resolved)
- src/main/resources/application.yml: 3 conflicts (100% auto-resolved)

Overall auto-resolution rate: 7/8 (87%)

RECOMMENDATION: Assign files that frequently conflict to a single task,
or create explicit depends_on relationships between tasks that share these files.
```

**Programmatic Access:**

`getTopConflictedFilePaths(limit)` returns a list of file paths for programmatic use (e.g., by the ModelResolver to adjust complexity ratings for conflict-prone tasks).

---

## 24. Eval Framework

### 24.1 Overview

**File:** `eval/EvalController.java`, `eval/ModelEvaluator.java`

Admin-only model evaluation framework that runs all configured models against a test case suite, scores results, normalizes costs, and generates per-tier recommendations.

### 24.2 ModelEvaluator

**File:** `eval/ModelEvaluator.java`

Orchestrates model evaluation: runs all models against all test cases, scores results, and generates the final report.

**Scope:** 6 models x 12 test cases = 72 LLM calls (includes 3 boss-level saga tests: diagnosis, consistency, compliance).

**Configuration:**
- Per-call timeout: 180 seconds
- Models tested sequentially to avoid rate limits

**Components:**
- `EvalScorer` -- scores LLM responses against expected outcomes
- `EvalTestCaseRegistry` -- provides the test case suite
- `ModelMatrixProperties` -- supplies the model list from config

### 24.3 EvalController

**File:** `eval/EvalController.java`
**Base Path:** `/api/admin/eval`

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/admin/eval/run` | Trigger evaluation (optional `providers` filter). Returns EvalReport with per-tier recommendations. |

**Security:** Requires SUPER_ADMIN role (secured via `/api/admin/**` SecurityConfig rule).

**Response:**
```json
{
  "evaluationId": "uuid",
  "durationMs": 120000,
  "modelsEvaluated": 6,
  "recommendations": { ... },
  "report": { ... }
}
```

---

## 25. Security Architecture

### 25.1 Security Layers

ExecuteSpec implements 8 security layers:

| Layer | Component | Description |
|-------|-----------|-------------|
| 1. Input Sanitization | `InputSanitizer` | Scans user-submitted specs for prompt injection, shell embedding, and abuse patterns before they reach the LLM pipeline |
| 2. JWT Authentication | `JwtAuthenticationFilter` | Validates JWT tokens on all API requests (except auth, webhooks, public reports) |
| 3. OAuth2 Authentication | `OAuthSuccessHandler` | Google and GitHub social login |
| 4. Force-Logout | `FeatureFlagService` | Feature flag-based session invalidation via `force_logout_epoch` |
| 5. Rate Limiting | `RateLimitFilter` | Redis-backed per-tenant rate limiting on auth, run submission, and webhook endpoints |
| 6. RBAC | `RunVisibilitySpec` | Role-based access control for runs, projects, and org resources |
| 7. Workspace Sandbox | `FileSystemTool` + `ShellSandboxPolicy` | Directory traversal prevention, command whitelisting, environment stripping |
| 8. Code Abuse Scanning | `CodeAbuseScanner` | Scans generated code for crypto mining, reverse shells, and other abuse patterns before file write |

### 25.2 InputSanitizer

**File:** `security/InputSanitizer.java`

Scans user-submitted spec content for:
- Prompt injection patterns ("ignore all previous instructions", etc.)
- Shell command embedding
- Other abuse patterns

Integrated into the run submission flow before the request reaches the planner.

### 25.3 CodeAbuseScanner

**File:** `security/CodeAbuseScanner.java`

Scans file content before write for:
- Crypto mining activity (stratum+tcp://, xmrig patterns)
- Reverse shell patterns
- Other dangerous code patterns

Integrated into `FileSystemTool.writeFile()` to flag (and optionally block) dangerous content before it is written to disk.

### 25.4 SecurityProperties

**File:** `config/SecurityProperties.java`

Centralized security configuration via `@ConfigurationProperties(prefix = "app.security")`:
- CSP (Content Security Policy) configuration
- HSTS configuration
- Frame options
- Per-environment overrides via application.yml

---

## Appendix A: Prompt Caching Strategy

All agents use Anthropic's `SYSTEM_ONLY` cache strategy:
- System prompts are cached (stable per provider/complexity)
- Cache TTL: 5 minutes (Anthropic)
- Cost savings: cached reads billed at 0.10x, cache writes at 1.25x

**Worker compaction preserves cache efficiency:**
When context is compacted, only the conversation history is summarized. The system prompt remains cached.

## Appendix B: Virtual Thread Usage

The system uses Java 21 virtual threads extensively:
- `Thread.ofVirtual().name("preview-" + runId).start(...)` -- preview processes
- `Thread.ofVirtual().name("bg-validation-" + runId).start(...)` -- background validation
- `Thread.ofVirtual().name("context-agent-" + runId).start(...)` -- ContextAgent
- `Thread.ofVirtual().name("prompt-optimiser-" + runId).start(...)` -- PromptOptimiserAgent
- `Thread.ofVirtual().name("api-spec-" + runId).start(...)` -- ApiTestSuiteAgent
- `Thread.ofVirtual().name("worker-retry-" + taskId).start(...)` -- WorkerPool task retry
- `Thread.ofVirtual().name("deploy-" + runId).start(...)` -- deployment pipeline

All virtual threads are wrapped with `ContextSnapshotFactory.captureAll().wrap(runnable)` for Micrometer observation context propagation (traceId/spanId).

## Appendix C: Error Handling Philosophy

1. **Non-fatal agents:** CompilerAgent, ContextAgent, PromptOptimiserAgent, ApiTestSuiteAgent -- failures are logged but do not affect run status.
2. **Background validation:** Review, test gen, test execution, and report run in background after COMPLETE. No retry/recovery if the background thread fails (fire-and-forget).
3. **Provider fallback:** LlmRouter exhausts all providers in the chain before throwing `ProviderUnavailableException`.
4. **Graceful degradation:** Merge conflicts are auto-resolved when possible; unresolvable conflicts mark individual tasks FAILED rather than the entire run.
5. **Gate loss recovery:** If a server restarts while a run is at a gate, the gate auto-fails with a message. Startup auto-resume is DISABLED -- users resume stuck runs manually via UI.
6. **Integration null-safety:** `JiraAdapter.onRunComplete()` and `ConfluenceAdapter.onRunComplete()` handle null review gracefully (PROTOTYPE mode has no review).

## Appendix D: Security Model

1. **Input sanitization:** `InputSanitizer` scans user-submitted specs for prompt injection and shell embedding before they reach the LLM pipeline.
2. **Workspace sandbox:** `FileSystemTool.safeResolve()` prevents directory traversal. All paths must stay within `workspace/{runId}/`.
3. **Shell sandbox:** `ShellSandboxPolicy` blocks dangerous commands (rm -rf /, reboot, etc.) and validates against an allowed prefix whitelist.
4. **Environment stripping:** `ShellTool` removes all secret environment variables from child process environments.
5. **Code abuse scanning:** `CodeAbuseScanner` scans generated file content for crypto mining, reverse shells, and other abuse before write.
6. **Sensitive file logging:** Writes to `.env`, `credentials.json`, etc. generate WARN logs but are NOT blocked (agents need to create these for generated projects).
7. **JWT authentication:** All REST endpoints require valid JWT (except auth endpoints, webhooks, and public shared reports).
8. **OAuth2 authentication:** Google and GitHub social login with automatic user creation.
9. **Force-logout:** Feature flag-based session invalidation via `force_logout_epoch` timestamp in JWT validation.
10. **Rate limiting:** Redis-backed per-tenant rate limiting on security-sensitive endpoints (auth, run submission, webhooks).
11. **Run visibility:** `RunVisibilitySpec` enforces RBAC based on user tier, org role, and project membership.
12. **API key isolation:** Per-run key resolution ensures each run uses the correct keys based on the submitting user's org policy.
