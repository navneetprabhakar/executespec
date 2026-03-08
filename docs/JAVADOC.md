# ExecuteSpec -- Complete System Documentation

**Version:** 2c69462 (main)
**Last Updated:** 2026-03-07
**Stack:** Java 21, Spring Boot 3.3.0, Spring AI 1.1.0, PostgreSQL, Redis, JGit 6.9.0

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
11. [Deployment and Preview](#11-deployment-and-preview)
12. [REST API Reference](#12-rest-api-reference)
13. [Server-Sent Events](#13-server-sent-events)
14. [Recovery System](#14-recovery-system)
15. [Configuration Reference](#15-configuration-reference)
16. [Database Schema](#16-database-schema)
17. [Validation and Code Quality](#17-validation-and-code-quality)
18. [Merge Conflict Resolution](#18-merge-conflict-resolution)

---

## 1. Project Overview

ExecuteSpec is a multi-agent autonomous coding system that transforms natural language specifications into working software. The system decomposes user requests into atomic tasks, executes them via AI agents operating in isolated git worktrees, validates the output, reviews the generated code, and merges the results.

### Core Capabilities

- **Natural language to code:** Users submit a goal in plain English; the system produces a complete, working codebase.
- **Multi-agent pipeline:** Planner, Worker, Compiler, Reviewer, and post-merge agents each handle a distinct phase.
- **Human-in-the-loop gates:** Plan approval and merge approval gates give humans oversight before code is committed.
- **Automatic validation:** Per-task code validation with model escalation on failure.
- **Progressive merge:** Each task's branch is merged to main immediately after completion, enabling early conflict detection.
- **Multi-tenant:** Organizations, projects, tiered access, and per-user/org API key management.
- **Provider failover:** 3-provider fallback chains (Anthropic, Google Gemini, OpenAI) with rate-limit and overload handling.
- **Recovery:** Automatic detection and recovery of stuck tasks with configurable retry and restart strategies.

### Technology Stack

| Component | Technology |
|-----------|-----------|
| Runtime | Java 21 (virtual threads) |
| Framework | Spring Boot 3.3.0 |
| AI Framework | Spring AI 1.1.0 |
| Database | PostgreSQL (Flyway migrations) |
| Cache/Gate Store | Redis (Lettuce) |
| Git Operations | JGit 6.9.0 + shell git (worktrees) |
| Build | Maven |
| Frontend | React + TypeScript (Vite) |
| Logging | SLF4J via Lombok `@Slf4j` |

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
[1. PlannerAgent]  -->  TaskPlan (JSON)
    |
    v
[2. Plan Approval Gate]  (human or auto-approve)
    |
    v
[3. SequentialTaskExecutor]
    |-- For each task (topological order):
    |     |-- ModelRouter selects model (complexity-based)
    |     |-- WorkerAgent.execute() in isolated worktree
    |     |     |-- Agentic loop (up to 80 iterations)
    |     |     |-- XML tool calls: read/write/edit/run/git
    |     |     |-- Budget monitoring + compaction
    |     |     \-- Completion report (JSON)
    |     |-- CodeValidator validates output
    |     |-- On failure: retry with error feedback + model escalation
    |     \-- Progressive merge to main
    |
    v
[4. CompilerAgent]  -->  compile & fix (up to 3 attempts)
    |
    v
[5. ReviewerAgent]  -->  ReviewReport (JSON)
    |
    v
[6. Merge Approval Gate]  (MERGE / DECLINE / REWORK / IMPLEMENT_SUGGESTIONS)
    |
    v
[7. GitMergeService]  -->  merge all branches to main
    |
    v
[8. Post-Merge Orchestrator]
    |-- ContextAgent (update CODEBASE_CONTEXT.md)
    |-- PromptOptimiserAgent (improve prompts)
    \-- ApiTestSuiteAgent (generate OpenAPI spec)
```

---

## 3. Package Structure

All packages are under `com.navneet.agentteam`.

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
| `config/` | Spring configuration: AgentProperties, AgentModelConfig, ModelPricingInitializer, WebConfig |

### Feature Packages

| Package | Description |
|---------|-------------|
| `router/` | Model routing: ModelRouter (2-axis complexity/provider routing), ModelTier, TaskComplexity, LlmProvider, ModelSelection, ProviderHealthTracker, AvailableProviderResolver, ModelTaskMetric |
| `reviewer/` | Agentic reviewer V2: ReviewerAgentV2 (two-phase inspect + fix), ReviewReportV2 |
| `validator/` | Code validation: CodeValidator, CodeValidatorImpl, ValidationLevel, ValidationResult, ValidationVerdict, Language |
| `validator/model/` | Validation data models |
| `validator/prompt/` | RetryPromptBuilder (builds error feedback for retries) |
| `compiler/` | CompilerAgent (post-worker build validation + LLM-powered fix) |
| `context/` | ContextAgent (post-merge codebase context updater), ContextAgentScanner, ContextAgentProperties |
| `optimiser/` | PromptOptimiserAgent (self-improving prompt system), PromptPatternAnalyser, PromptAuditService |
| `apitests/` | ApiTestSuiteAgent (post-merge OpenAPI spec generation) |
| `recovery/` | StuckRunMonitor, RunRecoveryService, StuckRunEvaluator, StuckClassification, LlmCallGuard, EscalationService, RecoveryProperties |
| `deployment/` | LocalPreviewService, DeploymentService, DeploymentStrategy, DockerfileGenerator, WorkspaceExportService, WorkspaceFileService, GcsWorkspaceStore |
| `sse/` | Server-Sent Events: RunEvent (record + factory methods), SseBroadcaster |
| `api/controller/` | REST controllers (see REST API Reference section) |
| `api/dto/` | Request/response DTOs: RunSummary, RunDetail, TaskSummary, RunRequest, ApprovalRequest |
| `auth/` | Authentication: AppUser, Org, OrgMember, Project, ProjectMember, TierConfig entities; AuthService, OrgService, ProjectService, TierEnforcementService, InvitationService |
| `auth/security/` | JWT filter, RunVisibilitySpec, AppUserDetails |
| `tenant/` | TenantInterceptor (automatic tenant_id injection on JPA entities) |
| `repo/` | GitHubRepoService (create repos, push branches) |
| `pricing/` | ModelPricing entity + repository (DB-backed pricing for cost estimation) |
| `integration/` | External system adapters: Jira, Confluence, ServiceNow, PagerDuty + IntegrationManager |

---

## 4. Core Pipeline Flow

### 4.1 Run Lifecycle States

```
PLANNING --> AWAITING_APPROVAL --> WORKING --> REVIEWING --> AWAITING_MERGE --> COMPLETE
    |               |                |            |               |
    v               v                v            v               v
  FAILED         FAILED           PARTIAL      FAILED          PARTIAL
                (rejected)       (some tasks               (merge declined
                                  failed)                   or conflict)
    |
    v
  QUEUED  (waiting for compute slot)
    |
    v
  PAUSED  (user paused, can edit goal and restart)
```

### 4.2 Detailed Pipeline Steps

**Step 1: Run Submission**
```
RunController.submit(RunRequest)
  --> PipelineHarness.submitRun(userRequest, source, repoUrl, ...)
      --> StateStore.createRun() --> PipelineRun (status=PLANNING)
      --> Semaphore.tryAcquire()
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
  --> Returns TaskPlan {planSummary, tasks: [TaskDefinition...]}
```

**Step 4: Plan Approval Gate**
```
StateStore.savePlan(runId, plan) --> status=AWAITING_APPROVAL
  --> GateStore.awaitPlanGate(runId) (polls Redis every 500ms)
  --> Release compute slot while waiting
  --> REST: POST /api/runs/{id}/approve resolves gate via Redis
  --> Reacquire compute slot
  --> StateStore.markApproved(runId) --> status=WORKING
```

**Step 5: Task Execution (Sequential with Validation)**
```
SequentialTaskExecutor.execute(run, tasks)
  --> topologicalSort(tasks) (Kahn's algorithm)
  --> For each task in order:
      |-- Check dependency failures (skip if dep failed)
      |-- ModelRouter.selectForTask(complexity, availableProviders)
      |-- StateStore.lockTask(taskId, model, branchName)
      |-- executeWithValidation(run, task, level, providers)
      |   |-- WorkerAgent.execute(task, runId) in worktree
      |   |-- CodeValidator.validateFile() on modified files
      |   |-- On FAIL: RetryPromptBuilder.build(validation)
      |   |             ModelRouter.selectForRetry() (escalation)
      |   |             Loop up to maxRetries times
      |   \-- On PASS: stateStore.completeTask()
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

**Step 7: Review**
```
ReviewerAgent.review(run, workerReports)
  --> Gather diffs from all task branches (capped at 80k chars)
  --> Build user message: plan + reports + diffs + fallback notes
  --> LlmRouter.callWithFallback()
  --> Parse ReviewReport JSON
  --> Retry up to 3 times on invalid JSON
```

**Step 8: Merge Gate**
```
StateStore.saveReview(runId, review) --> status=AWAITING_MERGE
  --> GateStore.awaitMergeGate(runId) (polls Redis every 500ms)
  --> MergeDecision: MERGE | DECLINE | IMPLEMENT_SUGGESTIONS | REWORK
  --> On MERGE: GitMergeService.mergeAll()
  --> On DECLINE: status=PARTIAL (preserved for reopen)
  --> On IMPLEMENT_SUGGESTIONS: re-run affected workers, re-review, loop
  --> On REWORK: re-run flagged tasks with feedback, re-review, loop
```

**Step 9: Post-Merge**
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
**Model:** `claude-sonnet-4-6` (CAPABLE_CHAIN)
**Mode:** Single LLM call (IMMEDIATE fallback)

**Purpose:** Decomposes a natural language user request into a structured task plan with dependency ordering.

**Key Methods:**

| Method | Signature | Description |
|--------|-----------|-------------|
| `plan` | `TaskPlan plan(String userRequest, String runId, String modelOverride)` | Main entry point. Loads the planner system prompt, builds workspace context, calls LLM, parses JSON response. Retries up to 3 times on invalid JSON. |
| `planWithEstimates` | `TaskPlan planWithEstimates(String goal)` | Feasibility mode -- adds iteration and token estimates per task for cost preview without creating a run. |
| `buildWorkspaceContext` | `String buildWorkspaceContext(String runId)` | Reads file listing and CODEBASE_CONTEXT.md from the workspace to give the LLM context about existing code. |
| `buildConflictHistoryContext` | `String buildConflictHistoryContext()` | Appends historical merge conflict data so the planner can avoid patterns that frequently cause conflicts. |

**Output:** `TaskPlan` containing:
- `planSummary` -- high-level description
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
**Model:** `claude-haiku-4-5-20251001` (default, overridable per task)
**Mode:** Agentic loop (up to 80 iterations)

**Purpose:** Executes a single task by operating in an isolated git worktree, using XML-format tool calls to read, write, edit files, run commands, and manage git.

**Key Methods:**

| Method | Signature | Description |
|--------|-----------|-------------|
| `execute` | `WorkerReport execute(AgentTask task, String runId)` | Main entry point. Creates a worktree, runs the agentic loop, returns a WorkerReport. |
| `executeWithModel` | `WorkerReport executeWithModel(AgentTask task, String runId, ModelSelection model)` | Convenience wrapper that sets the model before calling execute(). |
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
- Completion detection is **strict**: response must START with `{` and contain `"task_id"` and `"status"`.
- `escapeControlCharsInJsonStrings()` sanitizes LLM-emitted raw control characters inside JSON string values.

### 5.3 ReviewerAgent

**File:** `agent/ReviewerAgent.java`
**Model:** `claude-haiku-4-5-20251001` (UTILITY_CHAIN)
**Mode:** Single LLM call (IMMEDIATE fallback)

**Purpose:** Reviews all worker output by examining git diffs, worker reports, and the original plan. Produces a structured review with merge recommendation.

**Key Methods:**

| Method | Signature | Description |
|--------|-----------|-------------|
| `review` | `ReviewReport review(PipelineRun run, List<WorkerReport> workerReports)` | Gathers diffs from all task branches (capped at 80k chars), sends to LLM with plan and reports, parses ReviewReport JSON. |

**Output:** `ReviewReport` containing:
- `recommendation` -- MERGE / REWORK
- `overallConfidence` -- HIGH / MEDIUM / LOW
- `summary` -- text summary
- `mergeBlockers` -- list of critical issues that block merge
- `postMergeSuggestions` -- non-blocking improvements
- `taskReviews` -- per-task reviews with issues classified as CRITICAL / MEDIUM / LOW

### 5.4 ReviewerAgentV2

**File:** `reviewer/ReviewerAgentV2.java`
**Model:** CAPABLE tier (Sonnet-class)
**Mode:** Two-phase agentic review

**Purpose:** Enhanced reviewer with tool-use inspection and automatic fix application for critical issues.

**Phases:**
1. **Inspect Phase:** Reads file tree and selected source files via tool-use loop (max 10 tool calls). Produces a list of ReviewIssues classified as CRITICAL or MINOR.
2. **Fix Phase:** For each CRITICAL issue (max 5), sends a fix instruction to WorkerAgent, then validates the fix via CodeValidator.

**Guardrails:**
- Max 10 read_file/list_files calls during inspection
- Max 5 critical issues trigger auto-fix
- Max 1 fix attempt per critical issue
- Total phase timeout: 3 minutes

### 5.5 CompilerAgent

**File:** `compiler/CompilerAgent.java`
**Model:** `claude-haiku-4-5-20251001`
**Mode:** Compile-and-fix loop (up to 3 attempts)

**Purpose:** Runs the project build after all workers complete (before the reviewer sees the code). If compilation fails, extracts error messages and affected source files, sends them to an LLM for JSON-patch fixes, and retries.

**Key Methods:**

| Method | Signature | Description |
|--------|-----------|-------------|
| `runCompileAndFix` | `CompileResult runCompileAndFix(String runId)` | Entry point called by PipelineHarness. Non-fatal -- always returns. |
| `detectBuildTool` | `String detectBuildTool(String runId)` | Checks for pom.xml (mvn), build.gradle (gradle), package.json (npm). |
| `applyLlmFixes` | `void applyLlmFixes(String runId, String errors, String buildTool)` | Sends compile errors + affected source to LLM, applies JSON array fixes `[{path, content}]`. |

**Build Commands:**
- Maven: `mvn compile -q 2>&1`
- Gradle: `gradle compileJava -q 2>&1`
- npm: `npm run build 2>&1`

### 5.6 ConversationAgent

**File:** `agent/ConversationAgent.java`
**Model:** `claude-haiku-4-5-20251001` (UTILITY_CHAIN)
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
**Model:** `claude-haiku-4-5-20251001`

**Purpose:** Post-merge agent that updates `CODEBASE_CONTEXT.md` and `decisions.md` in the project workspace. Runs asynchronously in a virtual thread.

**Flow:**
1. `ContextAgentScanner.scan()` -- collects git diff summary, new packages, new files, worker reports, reviewer summary
2. If anything changed, calls LLM with current context doc + changes
3. Writes updated `CODEBASE_CONTEXT.md` to `.agent-context/`
4. Appends entry to `decisions.md`
5. Commits to main via JGit

### 5.8 PromptOptimiserAgent

**File:** `optimiser/PromptOptimiserAgent.java`
**Model:** `claude-haiku-4-5-20251001`

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
**Model:** `claude-haiku-4-5-20251001`

**Purpose:** Generates an OpenAPI 3.0 YAML specification for backend-only runs post-merge. Writes `api-docs/openapi.yaml` to the workspace.

**Skip Conditions:**
- Frontend files detected (.html, .jsx, .tsx, .vue, .svelte)
- No controller files found (*Controller.java / *Resource.java)

---

## 6. Tool System

All tools operate within a sandboxed workspace directory (`workspace/{runId}/`). Thread-local `WorkspaceContext` redirects operations to task-specific worktrees when active.

### 6.1 FileSystemTool

**File:** `tool/FileSystemTool.java`

Provides file I/O operations for agents. All paths are resolved relative to the run's workspace and validated by `safeResolve()` to prevent directory traversal.

| Method | Signature | Description |
|--------|-----------|-------------|
| `readFile` | `String readFile(String runId, String path)` | Read the full content of a file. |
| `writeFile` | `String writeFile(String runId, String path, String content)` | Create or fully overwrite a file. Logs WARN for sensitive filenames (.env, credentials.json, etc.) but does NOT block. |
| `editFile` | `String editFile(String runId, String path, String oldStr, String newStr)` | Replace an exact string in a file. |
| `listFiles` | `String listFiles(String runId, String path)` | List directory contents. Hides `worktrees/`, `target/`, `build/`, `node_modules/`, `.git/`. |
| `searchFiles` | `String searchFiles(String runId, String pattern, String directory)` | Recursive grep with the same directory exclusions. |

**Security Model:**
- `safeResolve(runId, relativePath)` -- normalizes the path and verifies it starts with the workspace root. Throws `SecurityException` on escape attempts.
- `checkFileLock(path)` -- checks file locks from `FileLockTool`. Blocks writes to files owned by another task.
- `SENSITIVE_FILE_NAMES` -- WARN log only (agents legitimately write these for generated projects).

### 6.2 ShellTool

**File:** `tool/ShellTool.java`

Executes shell commands inside the run's workspace directory.

| Method | Signature | Description |
|--------|-----------|-------------|
| `run` | `String run(String runId, String command, int timeoutSeconds)` | Validates via `ShellSandboxPolicy`, strips secret env vars, runs with hard 300s timeout ceiling. |

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
| `diff` | `String diff(String runId)` | Full diff of working tree. |
| `commit` | `String commit(String runId, String message)` | Stage all + commit. |
| `commitIfDirty` | `void commitIfDirty(String runId)` | No-op if clean; stages and commits if dirty. |

**Worktree management (internal):**

| Method | Signature | Description |
|--------|-----------|-------------|
| `createWorktree` | `String createWorktree(String runId, String taskId, String branchName, List<String> depBranches)` | Creates an isolated git worktree at `workspace/{runId}/worktrees/{taskId}/`. Merges dependency branches with `-X theirs`. Handles retry by resetting existing worktrees. |
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
| `saveReview` | `void saveReview(String runId, ReviewReport review)` | Saves review JSON, sets status=AWAITING_MERGE. |
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
| `status` | `String` | PLANNING, AWAITING_APPROVAL, WORKING, REVIEWING, AWAITING_MERGE, COMPLETE, FAILED, PARTIAL, QUEUED, PAUSED. |
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
| `currentGate` | `String` | PLAN_APPROVAL, MERGE_APPROVAL, or null. |
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
| `chatHistoryJson` | `TEXT` | Conversation history as JSON array. |
| `chatMessageCount` | `int` | Total messages exchanged (capped at 20). |
| `sourceRunId` | `String` | For iteration runs, the source run's ID. |
| `gcsWorkspaceKey` | `String` | GCS object key for archived workspace. |
| `gcsArchivedAt` | `Instant` | When workspace was archived to GCS. |
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
| `assignedModel` | `String` | Model assigned by planner or ModelRouter. |
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
- `Semaphore runSlots` -- limits concurrent active runs (configurable, default: 10)
- `ExecutorService executor` -- cached thread pool for async pipeline execution
- `GateStore gateStore` -- Redis-backed gate store for cross-instance approval handling

**Public Methods:**

| Method | Signature | Description |
|--------|-----------|-------------|
| `submitRun` | `PipelineRun submitRun(String userRequest, String source, String repoUrl, String repoMode, String githubPat, String githubOwner)` | Creates a run, acquires a compute slot (or queues), and starts async execution. |
| `resolvePlanGate` | `boolean resolvePlanGate(String runId, boolean approved, String comment)` | Signals the plan approval gate via Redis. Returns false if run not in AWAITING_APPROVAL. |
| `resolveMergeGate` | `boolean resolveMergeGate(String runId, boolean approved, String comment)` | Signals the merge gate via Redis. Supports MERGE/DECLINE decisions. |
| `cancelRun` | `boolean cancelRun(String runId)` | Signals open gates as rejected, marks run cancelled. |
| `pauseRun` | `boolean pauseRun(String runId)` | Pauses a run: signals gates, cancels workers, marks PAUSED. |
| `restartWithNewGoal` | `PipelineRun restartWithNewGoal(PipelineRun pausedRun, String newGoal)` | Creates a fresh run from a paused run with an edited goal. Carries forward credentials and settings. |
| `reopenMergeGate` | `void reopenMergeGate(String runId)` | Re-runs reviewer and re-opens merge gate for a PARTIAL run. |
| `implementSuggestions` | `void implementSuggestions(String runId)` | Implements post-merge suggestions (MEDIUM/LOW issues). |
| `reworkRun` | `void reworkRun(String runId)` | Re-runs flagged tasks with reviewer feedback. |
| `retryPartialTask` | `void retryPartialTask(String runId, String taskId)` | Retries a single failed/partial/blocked task. |
| `startIterationRun` | `void startIterationRun(String runId, String newRequest)` | Starts a run that iterates on a completed run's workspace. |

**Gate Architecture (Redis-backed):**

Gates use Redis polling (500ms interval) so that HTTP approve/reject calls work regardless of which server instance receives them:

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

Enhanced task executor with per-task validation and model routing. Replaces direct `WorkerPool.runAll()` calls in PipelineHarness.

**Flow per task:**
1. Check dependency failures (skip if any dep failed, mark BLOCKED)
2. Select model via `ModelRouter.selectForTask(complexity, availableProviders)`
3. Lock task in DB + set branch name
4. Execute with validation loop:
   - `WorkerAgent.execute(task, runId)` in isolated worktree
   - `CodeValidator.validateFile()` on all modified files
   - On validation FAIL: build retry prompt, escalate model, re-execute (up to `maxRetries`)
5. Auto-commit worktree if dirty
6. Progressive merge via `GitMergeService.mergeTaskBranchWithResolution()`
7. Track metrics via `ModelTaskMetricRepository`

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
4. **FAILED** -- human retry via merge gate

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

---

## 9. Model Routing

### 9.1 LlmRouter

**File:** `agent/LlmRouter.java`

Routes LLM calls with 3-provider fallback chains and per-run API key injection.

**Provider Fallback Chains:**

| Chain | Position 1 | Position 2 | Position 3 |
|-------|-----------|-----------|-----------|
| ARCHITECT | `claude-opus-4-6` | `gemini-3.1-pro-preview-customtools` | `gpt-4o` |
| CAPABLE | `claude-sonnet-4-6` | `gemini-3.1-flash-lite-preview` | `gpt-4o` |
| UTILITY | `claude-haiku-4-5-20251001` | `gemini-3.1-flash-lite-preview` | `gpt-4o-mini` |

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

### 9.2 ModelRouter

**File:** `router/ModelRouter.java`

Two-axis model selection for task execution:
- **Axis 1 (Tier):** FAST -> CAPABLE -> POWER based on task complexity + escalation
- **Axis 2 (Provider):** ANTHROPIC -> OPENAI -> GEMINI based on availability

**Model Matrix:**

| Tier | Anthropic | OpenAI | Gemini |
|------|----------|--------|--------|
| FAST | `claude-haiku-4-5-20251001` | `gpt-4o-mini` | `gemini-3.1-flash-lite-preview` |
| CAPABLE | `claude-sonnet-4-6` | `gpt-4o` | `gemini-3.1-pro-preview-customtools` |
| POWER | `claude-opus-4-6` | `gpt-4o` | `gemini-3.1-pro-preview-customtools` |

**Complexity to Tier Mapping:**
- LOW -> FAST
- MEDIUM -> FAST (escalates if needed)
- HIGH -> CAPABLE

**Escalation Rules:**
- Attempts 1-2: stay at current tier
- Attempt 3: escalate one tier (FAST -> CAPABLE)
- Never auto-escalate to POWER (too expensive)

### 9.3 AvailableProviderResolver

**File:** `router/AvailableProviderResolver.java`

Bridges the `ResolvedKeys` / `RunKeyStore` system with ModelRouter's `Set<LlmProvider>` filter.

**Resolution Rules:**
- `SYSTEM` mode: providers with system-level keys configured
- `OWN_KEYS` mode: only providers with user/org keys (no system fallback)
- `HYBRID` mode: union of system + user/org keys

Also respects org-level `enabledProviders` policy.

### 9.4 ApiKeyResolver

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

### 9.5 TokenBudgetMonitor

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

**Context Limits:**
- Anthropic models: 200,000 tokens
- OpenAI models: 128,000 tokens

### 9.6 CostEstimator

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
- Admin credentials: `admin@executespec.ai` / `Admin@123`

---

## 11. Deployment and Preview

### 11.1 LocalPreviewService

**File:** `deployment/LocalPreviewService.java`

Process-based local preview (NOT Docker). Starts the appropriate dev server directly in the workspace on a free local port.

**Project Type Detection:**

| Type | Detection | Command |
|------|-----------|---------|
| SPRING_BOOT | `pom.xml` exists | `mvn spring-boot:run -Dserver.port={port}` |
| GRADLE | `build.gradle` or `build.gradle.kts` | `gradle bootRun --args='--server.port={port}'` |
| NODE | `package.json` | `npm run dev` (with PORT env var) |
| FULLSTACK | pom.xml + package.json in `frontend/` | Backend + frontend in parallel |
| PYTHON | `main.py` / `app.py` | `uvicorn` or `flask run` |
| GO | `go.mod` | `go run .` |
| RUBY | `Gemfile` | `rails server` |
| PHP | `composer.json` | `php -S` |
| RUST | `Cargo.toml` | `cargo run` |
| DOTNET | `*.csproj` | `dotnet run` |
| STATIC | `index.html` | `python -m http.server` |

**API:**
- `startAsync(runId)` -- starts in a virtual thread, poll `getStatus()` for progress
- `stop(runId)` -- kills all processes for a run
- `getStatus(runId)` -- returns `PreviewState` (status, URL, projectType, processes)
- `getLogs(runId, maxLines)` -- returns last N lines of process output from `CircularBuffer`

**Lifecycle:** `PreviewStatus` enum: IDLE -> STARTING -> RUNNING / FAILED / STOPPED

### 11.2 GCS Workspace Archiving

- Completed run workspaces are archived to GCS after `archiveAfterHours` (default: 24)
- Archived as zip files: `workspaces/{runId}.zip`
- Local directory deleted after successful upload
- Download endpoint generates a 5-minute signed URL for direct GCS download

---

## 12. REST API Reference

### 12.1 Run Endpoints (`RunController`)

| Method | Path | Description | Status Code |
|--------|------|-------------|-------------|
| `POST` | `/api/runs` | Submit a new run | 202 Accepted |
| `GET` | `/api/runs` | List runs (filtered by status, projectId; paginated) | 200 |
| `GET` | `/api/runs/{runId}` | Get run detail with tasks | 200 / 404 |
| `POST` | `/api/runs/{runId}/approve` | Approve/reject plan gate | 200 / 404 / 409 |
| `POST` | `/api/runs/{runId}/merge` | Approve/reject merge gate | 200 / 404 / 409 |
| `POST` | `/api/runs/{runId}/retry` | Retry a failed run | 202 |
| `POST` | `/api/runs/{runId}/pause` | Pause a running pipeline | 200 / 409 |
| `POST` | `/api/runs/{runId}/edit-and-restart` | Edit goal of paused run and restart | 201 |
| `DELETE` | `/api/runs/{runId}` | Cancel a run | 200 / 404 |
| `POST` | `/api/runs/{runId}/tasks/{taskId}/retry` | Retry a single task | 202 / 404 / 409 |
| `POST` | `/api/runs/{runId}/implement-suggestions` | Implement reviewer suggestions | 202 / 409 |
| `POST` | `/api/runs/{runId}/rework` | Rework flagged tasks | 202 / 409 |
| `POST` | `/api/runs/{runId}/reopen-merge` | Reopen merge gate for PARTIAL run | 202 / 409 |
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

### 12.2 SSE Endpoint (`SseController`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/runs/{runId}/events` | SSE stream of run events |

### 12.3 Timeline Endpoint (`TimelineController`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/runs/{runId}/timeline` | Timeline of events for a run |

### 12.4 Feasibility Endpoint (`FeasibilityController`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/feasibility` | Pre-run cost and plan estimate (no run created) |

### 12.5 Pricing Endpoint (`PricingController`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/pricing` | Get all model pricing |
| `PUT` | `/api/pricing` | Update model pricing |

### 12.6 Preview Endpoints (`LocalPreviewController`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/runs/{runId}/preview` | Get preview status |
| `POST` | `/api/runs/{runId}/preview/start` | Start preview |
| `DELETE` | `/api/runs/{runId}/preview/stop` | Stop preview |

### 12.7 Deployment Endpoints (`DeploymentController`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/runs/{runId}/deploy` | Deploy to Cloud Run |
| `GET` | `/api/runs/{runId}/deployments` | List deployments |
| `DELETE` | `/api/runs/{runId}/deployments/{version}` | Delete a deployment |

### 12.8 Conversation Endpoints (`RunConversationController`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/runs/{runId}/conversation` | Send a chat message about a completed run |
| `GET` | `/api/runs/{runId}/conversation` | Get conversation history |

### 12.9 Integration Endpoints (`IntegrationController`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/integrations/jira/webhook` | Jira webhook handler |
| `POST` | `/api/integrations/confluence/webhook` | Confluence webhook handler |
| `POST` | `/api/integrations/servicenow/webhook` | ServiceNow webhook handler |
| `POST` | `/api/integrations/pagerduty/webhook` | PagerDuty webhook handler |

### 12.10 Metrics Endpoint (`MetricsController`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/metrics` | Aggregate metrics (runs, costs, success rates) |

### 12.11 Auth Endpoints (`AuthController`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/auth/register` | Register a new user |
| `POST` | `/api/auth/login` | Login (returns JWT) |
| `POST` | `/api/auth/refresh` | Refresh access token |
| `GET` | `/api/auth/me` | Get current user profile |

### 12.12 Organization Endpoints (`OrgController`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/orgs` | Create organization |
| `GET` | `/api/orgs/{orgId}` | Get organization details |
| `PUT` | `/api/orgs/{orgId}` | Update organization settings |
| `GET` | `/api/orgs/{orgId}/members` | List org members |
| `POST` | `/api/orgs/{orgId}/members` | Invite member |

### 12.13 Project Endpoints (`ProjectController`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/orgs/{orgId}/projects` | Create project |
| `GET` | `/api/orgs/{orgId}/projects` | List projects |
| `GET` | `/api/projects/{projectId}` | Get project details |
| `PUT` | `/api/projects/{projectId}` | Update project |
| `GET` | `/api/projects/{projectId}/members` | List project members |

---

## 13. Server-Sent Events

### 13.1 RunEvent

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
| `RUN_STATUS` | Run lifecycle state change | Status string (PLANNING, WORKING, etc.) |
| `TASK_STATUS` | Task status change | Status string (WORKING, COMPLETE, FAILED, etc.) |
| `LOG` | General log message | Log line text |
| `GATE_OPENED` | Approval gate opened | Gate type (PLAN_APPROVAL, MERGE_APPROVAL) |
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
| `VALIDATION_STARTED` | File validation started | File name |
| `VALIDATION_PASSED` | Validation passed | null |
| `VALIDATION_FAILED` | Validation failed | Error summary |
| `MODEL_ESCALATED` | Model tier escalated | "fromModel\|toModel" |
| `REVIEW_STARTED` | Review phase started | Model name |
| `REVIEW_ISSUE_FOUND` | Review issue detected | "issueId\|severity\|description" |
| `REVIEW_AUTO_FIX_STARTED` | Auto-fix attempt started | Issue ID |
| `REVIEW_AUTO_FIX_RESULT` | Auto-fix result | "issueId\|PASS/FAIL" |
| `REVIEW_COMPLETE` | Review complete | "verdict\|summary" |

### 13.2 SseBroadcaster

**File:** `sse/SseBroadcaster.java`

Manages SSE emitter subscriptions per run ID. Uses `ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>>` for thread-safe multi-client broadcasting.

| Method | Signature | Description |
|--------|-----------|-------------|
| `subscribe` | `SseEmitter subscribe(String runId)` | Creates a no-timeout SSE emitter, registers cleanup handlers. |
| `emit` | `void emit(String runId, RunEvent event)` | Serializes event to JSON and sends to all clients. Prunes dead emitters. |
| `complete` | `void complete(String runId)` | Completes all emitters for a run and removes the registration. |

---

## 14. Recovery System

### 14.1 StuckRunMonitor

**File:** `recovery/StuckRunMonitor.java`

Scheduled task that checks for stuck worker tasks every 2 minutes.

**Detection Logic:**
- Finds all tasks with status=WORKING/LOCKED
- Measures idle time (since `lastActivityAt`)
- Warning at `warnThresholdMinutes` (default: 15 min)
- Recovery at `actThresholdMinutes` (default: 30 min)

### 14.2 StuckRunEvaluator

Classifies stuck tasks based on their event log:

| Classification | Description |
|---------------|-------------|
| `LLM_TIMEOUT` | Last event was an LLM request with no response |
| `LLM_LOOP` | Repeated identical tool calls (loop-repeat-threshold: 3) |
| `FILE_LOCK_BLOCK` | Task blocked waiting for a file lock |
| `BUILD_FAILURE_STALL` | Build failures without progress |
| `UNKNOWN` | Cannot determine cause |

### 14.3 RunRecoveryService

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

Recovery actions are recorded in the `run_recovery` table for audit.

### 14.4 StuckRunEvaluator

**File:** `recovery/StuckRunEvaluator.java`

Classifies stuck tasks by analyzing their recent event log. Uses pattern matching on log entries to determine the root cause.

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

### 14.5 LlmCallGuard

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
    classify → RateLimited?  sleep(retryAfterSeconds or rateLimitBackoffSeconds)
    classify → Other?        throw LlmCallException immediately
  CATCH HttpServerErrorException:
    exponential backoff → serverErrorBackoffSeconds * (attempt + 1)
  CATCH Exception:
    exponential backoff → serverErrorBackoffSeconds * (attempt + 1)
```

### 14.6 LlmFailure (Sealed Interface)

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

### 14.7 EscalationService

**File:** `recovery/EscalationService.java`

Manages the model/provider escalation ladder when a task is stuck or failing.

**Escalation Ladder:**

```
Level 0-1: Upgrade to capable model (same provider)
   |-- anthropic: claude-haiku-4-5 → capable model from props
   |-- openai: gpt-4o-mini → gpt-4o
   |
Level 2: Switch to alternate provider entirely
   |-- anthropic → openai
   |-- openai → anthropic
   |
Level 3+: Terminal — trigger fresh run restart
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
  |-- Level 0-1: resolveCapableModel() → same provider, better model
  |-- Level 2:   resolveAlternateProvider() → different provider
  |-- Level 3+:  null → delegate to RunRecoveryService for fresh restart
  |
  |-- SSE event: MODEL_ESCALATED "fromModel|toModel"
  |-- stateStore.escalateTask(taskId, provider, model)
  |-- workerPool.cancelTask(taskId)
  |-- workerPool.resubmitTask(task, runId)
```

### 14.8 ProviderHealthTracker

**File:** `router/ProviderHealthTracker.java`

Tracks LLM provider health based on consecutive call outcomes. Used by ModelRouter to skip unhealthy providers during model selection.

**Configuration:**

| Constant | Value | Description |
|----------|-------|-------------|
| `UNHEALTHY_THRESHOLD` | 3 | Consecutive failures before marking unhealthy |
| `RECOVERY_WINDOW` | 5 minutes | Auto-recovery cooldown period |

**Behavior:**

```
recordSuccess(provider)  →  reset failure count, mark healthy
recordFailure(provider)  →  increment count; mark unhealthy at threshold
isHealthy(provider)      →  check status; auto-recover after RECOVERY_WINDOW
```

A provider is considered healthy when:
1. No failure data exists (assume healthy)
2. Consecutive failures < 3
3. Was unhealthy but RECOVERY_WINDOW has elapsed (auto-recovery)

---

## 15. Configuration Reference

### 15.1 application.yml Structure

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST:localhost}:${POSTGRES_PORT:5432}/${POSTGRES_DB:agentteam}
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
          model: gpt-4o-mini
          max-tokens: 16000
    google:
      genai:
        api-key: ${GOOGLE_API_KEY:}
        chat:
          options:
            model: gemini-3.1-flash-lite-preview

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
    max-runs: 10
    max-iterations: 80
  github:
    pat: ${GITHUB_PAT:}
    default-owner: ${GITHUB_OWNER:}
  shell:
    sandbox-enabled: true
    timeout-seconds: 300
  recovery:
    enabled: true
    warn-threshold-minutes: 15
    act-threshold-minutes: 30
    max-task-retries: 1
    max-auto-restarts: 3
    auto-restart-backoff-seconds: 60
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
    startup-timeout-seconds: 120
    log-buffer-lines: 200

executespec:
  validator:
    enabled: true
    per-task-validation: true
    per-task-level: TYPE
    project-validation-level: FULL
    max-retries: 3

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
```

### 15.2 Key Configuration Properties

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
| `concurrency.max-runs` | `int` | 10 | Max simultaneous active runs |
| `concurrency.max-iterations` | `int` | 80 | Max agentic loop iterations per task |
| `shell.sandbox-enabled` | `boolean` | true | Enable shell command sandboxing |
| `shell.timeout-seconds` | `int` | 300 | Max shell command execution time |
| `compiler.enabled` | `boolean` | true | Enable post-worker compilation |
| `compiler.max-attempts` | `int` | 3 | Max compile-fix attempts |

**HTTP Client Configuration (`AgentModelConfig`):**
- HTTP/1.1 (avoids HTTP/2 ALPN stalls)
- Connect timeout: 30 seconds
- Read timeout: 300 seconds (5 minutes)

---

## 16. Database Schema

### 16.1 Flyway Migrations

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

### 16.2 Entity Relationship Diagram

```
+------------------+       +------------------+
|   app_user       |       |     org          |
|------------------|       |------------------|
| id (PK)          |------>| id (PK)          |
| email            |       | name             |
| password_hash    |       | tier             |
| org_id (FK)      |       | key_source_mode  |
| anthropic_api_key|       | enabled_providers|
| openai_api_key   |       | anthropic_api_key|
| gemini_api_key   |       | openai_api_key   |
| github_pat       |       | gemini_api_key   |
+------------------+       +------------------+
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
```

---

## 17. Validation and Code Quality

### 17.1 CodeValidator

**File:** `validator/CodeValidator.java` (interface), `validator/CodeValidatorImpl.java` (implementation)

Per-task code validation that runs after each worker completes, before the progressive merge. The validator uses a pluggable architecture: `CodeValidatorImpl` routes requests to language-specific `LanguageToolchain` implementations through a `GateChain` that executes each validation gate in sequence.

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

### 17.2 GateChain

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

### 17.3 Language Toolchains

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

### 17.4 RetryPromptBuilder

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

### 17.5 Validation + Retry Flow

```
1. WorkerAgent.execute(task, runId) -- runs in worktree
2. Get list of modified files (git diff + untracked)
3. For each modified code file:
   a. Language.fromPath(file) -- detect language
   b. CodeValidator.validateFile(projectRoot, filePath, language, level)
   c. GateChain.execute() runs gates sequentially
   d. On FAIL: return immediately with validation result
4. On PASS: completeTask()
5. On FAIL:
   a. RetryPromptBuilder.build(validationResult) -- generates structured error feedback
   b. ModelRouter.selectForRetry(complexity, attempt) -- may escalate tier
   c. stateStore.markRetryingWithFeedback(runId, taskId, retryPrompt)
   d. SSE event: VALIDATION_FAILED with error summary
   e. Re-execute (back to step 1)
6. After maxRetries exhausted: failTask()
```

**Model Escalation on Retry:**
- Attempts 1-2: same model tier
- Attempt 3: escalate one tier (FAST -> CAPABLE)
- SSE event: MODEL_ESCALATED with "fromModel|toModel"

### 17.6 Model Task Metrics

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

## 18. Merge Conflict Resolution

### 18.1 Resolution Cascade

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
Tier 4: FAILED -- human intervention required (retry via merge gate)
```

**MergeOutcome enum:** `CLEAN`, `RESOLVED_DETERMINISTIC`, `RESOLVED_LLM`, `ESCALATED`, `FAILED`

### 18.2 FileMergerRegistry

**File:** `harness/merge/FileMergerRegistry.java`

Routes conflicted files to the appropriate `SpecializedMerger` implementation. If no merger matches, returns `Optional.empty()` and the caller falls through to the LLM-powered resolver.

```java
public Optional<String> tryMerge(String filePath, String base, String ours, String theirs)
```

Mergers are checked in Spring `@Order` sequence. First matching merger wins.

### 18.3 SpecializedMerger Interface

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

### 18.4 PomXmlMerger

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

### 18.5 YamlMerger

**File:** `harness/merge/YamlMerger.java`

Handles YAML config files (application.yml, docker-compose.yml, etc.). Deep-merges YAML maps. Conflicts on scalar values fall through to LLM.

### 18.6 PackageJsonMerger

**File:** `harness/merge/PackageJsonMerger.java`

Handles `package.json` files. Unions `dependencies`, `devDependencies`, and `scripts` sections. Version conflicts fall through to LLM.

### 18.7 Progressive Merge

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

### 18.8 Merge Conflict Tracking

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

`getTopConflictedFilePaths(limit)` returns a list of file paths for programmatic use (e.g., by the ModelRouter to adjust complexity ratings for conflict-prone tasks).

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
- `Thread.ofVirtual().name("context-agent-" + runId).start(...)` -- ContextAgent
- `Thread.ofVirtual().name("prompt-optimiser-" + runId).start(...)` -- PromptOptimiserAgent
- `Thread.ofVirtual().name("api-spec-" + runId).start(...)` -- ApiTestSuiteAgent
- `Thread.ofVirtual().name("worker-retry-" + taskId).start(...)` -- WorkerPool task retry
- `Thread.ofVirtual().name("auto-restart-" + runId).start(...)` -- Recovery auto-restart

## Appendix C: Error Handling Philosophy

1. **Non-fatal agents:** CompilerAgent, ContextAgent, PromptOptimiserAgent, ApiTestSuiteAgent -- failures are logged but do not affect run status.
2. **Provider fallback:** LlmRouter exhausts all providers in the chain before throwing `ProviderUnavailableException`.
3. **Graceful degradation:** Merge conflicts are auto-resolved when possible; unresolvable conflicts mark individual tasks FAILED rather than the entire run.
4. **Gate loss recovery:** If a server restarts while a run is at a gate, `performMergeOnRestart()` reconstructs everything from the database.
5. **Auto-restart:** Transient provider failures trigger automatic restarts with configurable backoff and limits.

## Appendix D: Security Model

1. **Workspace sandbox:** `FileSystemTool.safeResolve()` prevents directory traversal. All paths must stay within `workspace/{runId}/`.
2. **Shell sandbox:** `ShellSandboxPolicy` blocks dangerous commands (rm -rf /, reboot, etc.) and validates against an allowed prefix whitelist.
3. **Environment stripping:** `ShellTool` removes all secret environment variables from child process environments.
4. **Sensitive file logging:** Writes to `.env`, `credentials.json`, etc. generate WARN logs but are NOT blocked (agents need to create these for generated projects).
5. **JWT authentication:** All REST endpoints require valid JWT (except auth endpoints).
6. **Run visibility:** `RunVisibilitySpec` enforces RBAC based on user tier, org role, and project membership.
7. **API key isolation:** Per-run key resolution ensures each run uses the correct keys based on the submitting user's org policy.
