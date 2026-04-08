# Core Pipeline — Requirements

> Last updated: 2026-04-03

## Overview

The core pipeline orchestrates multi-agent AI execution from natural language input to merged, validated code. It manages the full lifecycle: request triage, planning, approval, worker execution, compilation, progressive merge, and post-merge background validation.

---

## Features

### 1. Pipeline Orchestration (PipelineHarness)

**Purpose:** Central coordinator managing the entire run lifecycle.

**Requirements:**
- Accept natural language user requests via REST API (`POST /api/runs`)
- Orchestrate sequential phases: Triage → Plan → Approve → Work (progressive merge) → Compile → Auto-merge → **COMPLETE** → Auto-preview → Background validation (Review + auto-fix + Test gen + Test exec + Report)
- Limit concurrent active runs via semaphore (`max-runs: 50`)
- Release compute slot while waiting at approval gates (gates don't consume compute)
- Support both REST and CLI execution paths
- Handle run cancellation, pause, and restart
- Carry forward credentials (GitHub PAT, API keys) across restarts
- Stamp run ownership fields (`createdByUserId`, `orgId`, `projectId`) BEFORE async pipeline thread starts (race condition fix)
- Virtual threads wrapped with `ContextSnapshotFactory.captureAll().wrap(runnable)` for trace propagation

**Run States:**
```
PLANNING → AWAITING_APPROVAL → WORKING → COMPILING → MERGING → COMPLETE
    ↓              ↓                ↓                              ↓
  FAILED        FAILED          PARTIAL                    Background validation
  QUEUED        (rejected)                                 (review + tests + report)
  PAUSED
```

**Run Archive:**
- Completed or failed runs can be archived by the user
- Archived runs are hidden from the default run list but remain queryable
- Archive/unarchive via `POST /api/runs/{id}/archive` and `POST /api/runs/{id}/unarchive`

### 2. Request Triage

**Purpose:** Classify incoming requests to determine the appropriate execution path.

**Requirements:**
- Analyze the user request before entering the full planning phase
- **Single-shot fast path:** Simple requests (e.g., a single file change, typo fix, config tweak) bypass the multi-task planner and execute directly as a single worker task
- Complex requests proceed to the full PlannerAgent decomposition
- Triage decision is transparent in the run event stream

### 3. PlannerAgent

**Purpose:** Decomposes user request into ordered tasks with complexity ratings.

**Requirements:**
- Use PLANNING tier model (Opus-class) for planning
- Generate a TaskPlan with 1-6 tasks in dependency order
- Assign complexity per task: LOW, MEDIUM, HIGH
- Include `dependsOn` relationships for topological ordering
- Provide acceptance criteria per task
- Specify files to create/modify for file ownership
- Declare `project_type` in plan JSON (written to `.agent-context/project.json`, read by preview/deploy Dockerfile generators)
- Inject workspace context (file tree, CODEBASE_CONTEXT.md) into prompt
- Inject historical merge conflict data to avoid conflict-prone patterns
- Retry up to 3 times on invalid JSON
- Support feasibility mode (cost estimation without creating a run)

### 4. WorkerAgent

**Purpose:** Execute a single task via agentic tool-use loop.

**Requirements:**
- Run in isolated git worktree per task
- Use XML tool-call format: `<tool_call>{"name":"...", "args":{...}}</tool_call>`
- Dispatch tools manually (not Spring AI native) with runId injection
- Support 9 tools: readFile, writeFile, editFile, listFiles, searchFiles, run, status, diff, commit
- Max 80 iterations per task
- **Max 25-minute wall-clock time limit per task** with stall detection
- **Model escalation on stall:** WORKHORSE tier escalates to PLANNING tier when worker is stuck (e.g., looping without progress)
- Progress checkpoints every 15 iterations (re-inject task context)
- Completion urgency at iteration 75 (force JSON output)
- Pre-call token estimation for proactive compaction (>30 messages)
- Emergency compaction before hard-stop
- Strict completion detection: response must start with `{`
- Spring singleton — all state passed as method params
- Sanitize LLM-emitted control characters in JSON strings
- Support model override via `executeWithModel()`
- Token truncation limits: `readFile` 8k chars, `shell` 6k chars, `diff` 5k chars
- Sliding window for LLM context: first 2 + last 18 messages
- Non-JSON escalation handling for malformed worker responses

### 5. Approval Gates

**Purpose:** Human-in-the-loop oversight before committing to execution.

**Requirements:**
- **Plan Approval Gate:** Review task breakdown before workers start
  - Endpoint: `POST /api/runs/{id}/approve` with `{approved: boolean, comment: string}`
  - Display cost estimate (low/high) for informed decision
  - Support `skipApproval` flag (auto-approve for CI/integration runs)
- **Automatic Merge:** After compilation, all task branches are auto-merged (no manual gate)
  - Legacy endpoint: `POST /api/runs/{id}/merge`
  - Support decisions: MERGE, DECLINE, IMPLEMENT_SUGGESTIONS, REWORK
  - IMPLEMENT_SUGGESTIONS: re-run affected workers
  - REWORK: re-run flagged tasks with reviewer feedback
  - Support `autoMerge` flag (auto-approve merge for CI/integration runs)
- **Bypass Flags:**
  - `skipApproval`: bypasses the plan approval gate (auto-approves task breakdown)
  - `autoMerge`: legacy flag (merge is now always automatic after review)
  - Both flags stored on PipelineRun entity; set at run submission via RunRequest
- Gates are Redis-backed (`gate:plan:{runId}`, `gate:merge:{runId}`, TTL 2h)
- Pipeline thread polls Redis every 500ms
- Any MIG instance can handle the approval REST call
- Gate-loss handling: if gate missing on approve (server restart), auto-fail with message

### 6. SequentialTaskExecutor

**Purpose:** Execute tasks in order with progressive merge and model routing.

**Requirements:**
- Topological sort via Kahn's algorithm
- Skip tasks if dependency failed (mark BLOCKED)
- **Subtask decomposition:** `SubtaskDecomposer` splits multi-file tasks (2+ owned files) into per-file subtasks at execution time. Each subtask produces exactly one `writeFile` call, eliminating fabrication. Rework tasks excluded from decomposition. Tracked via `parentTaskId`/`subtaskIndex`/`subtaskTotal` on `AgentTask`.
- Per task (or subtask):
  1. Select model via ModelResolver based on agent tier and user-selected provider
  2. Create isolated worktree
  3. Run WorkerAgent (with self-play prevention: strips fabricated `<tool_result>` blocks, XML-first parsing for PATH B)
  4. On failure: retry with error feedback + model tier escalation (up to 3 attempts)
  5. On success: commit and **progressive merge to main immediately**
- Per-task validation deferred to review phase (CompilerAgent + ReviewerAgentV2)
- Track metrics per task execution (model, provider, tier, pass/fail, duration)
- Emit SSE events: VALIDATION_STARTED, VALIDATION_PASSED, VALIDATION_FAILED, MODEL_ESCALATED
- Conditional test step: test generation only runs when configured

### 7. CompilerAgent

**Purpose:** Post-worker build validation.

**Requirements:**
- Detect build tool: Maven, Gradle, or npm
- Run compilation command
- On failure: extract errors + affected source, call LLM for JSON-patch fixes
- Up to 3 compile-fix rounds
- Non-fatal: always returns (doesn't block pipeline)
- Use WORKHORSE tier model

### 8. Background Validation

**Purpose:** Quality assurance that runs asynchronously after the run is marked COMPLETE, allowing users to preview and interact immediately.

**Requirements:**
- Run is marked **COMPLETE** immediately after compile + auto-merge
- Auto-preview starts (5-min TTL) for PROTOTYPE and real-time PRODUCTION modes. Batch runs skip auto-preview; `TestPreviewCoordinator` publishes empty `TestExecutionCompletedEvent` to unblock validation report
- **Batch auto-retry:** After `BatchResumeService` processes batch results, `PipelineHarness.onBatchComplete()` queries for FAILED tasks, resets them via `StateStore.resetTaskForRetry()`, and retries in real-time via `SequentialTaskExecutor` before compile. `BatchResumeService` also resolves WORKHORSE model/provider via `ModelResolver` and sets on tasks via `StateStore.setTaskModelProvider()`
- Background validation runs in a dedicated virtual thread (`bg-validation-{runId}`)
- Validation phases (sequential):
  1. **ReviewerAgentV2:** Two-phase agentic review with tool-use inspection and auto-fix
  2. **TestGenOrchestrator:** Generate tests based on plan and code
  3. **TestPreviewCoordinator:** Reuse existing preview for test execution (batch runs: empty event, manual execution via `POST /runs/{runId}/run-tests`)
  4. **ValidationReportGenerator:** Aggregate findings into user-facing report
- SSE `broadcaster.complete()`, `runKeyStore.remove()`, and `runBudgetTracker.remove()` deferred to background thread's `finally` block
- `backgroundValidationRuns` ConcurrentHashSet tracks active background threads
- Non-blocking: user can chat, submit changes, and use preview while validation runs
- Fire-and-forget: no retry/recovery if background thread fails silently (known tech debt)

### 8a. ReviewerAgentV2

**Purpose:** Automated code review with inspection and auto-fix.

**Requirements:**
- Gather diffs from all task branches (cap at 80k chars)
- Two-phase agentic review with tool-use inspection
- Produce ReviewReport: recommendation (MERGE/REWORK), confidence, per-task issues
- Issues classified as CRITICAL, MEDIUM, LOW
- Auto-fix capability for identified issues
- Single LLM call with IMMEDIATE fallback

### 8b. ValidationReportAgent

**Purpose:** Generate a user-facing quality summary of pipeline findings.

**Requirements:**
- Aggregate existing pipeline data: WorkerReports, CompileResult, ReviewReport, task list
- Single LLM call (LIGHTWEIGHT tier) to generate markdown report
- CRITICAL: Frame as CI/CD quality gate — never mention AI, models, LLMs, or agents
- Extract issues from: ReviewReport.taskReviews[].issues[], CompileResult errors, boundary violations, acceptance criteria failures
- Classify pipeline result: PASSED, PASSED_WITH_FIXES, FAILED
- Persist to `validation_report` table (one per run, replace on rework)
- Non-fatal: on failure, log warning and continue
- Share: generate UUID token for public URL (no auth required, whitelisted in SecurityConfig)
- PDF export: server-side PDF generation via `ValidationReportPdfService`
- Frontend: collapsible panel in RunDetail with severity badges, markdown report, all individual issues with scroll
- OTel span: `executespec.run.phase.report`
- Metrics: `executespec.report.generated`, `executespec.report.generation_duration`, `executespec.report.errors`, `executespec.report.issues_caught`, `executespec.report.shared`
- API endpoints: `GET /api/runs/{id}/report`, `GET /api/runs/{id}/report/pdf`, `POST /api/runs/{id}/report/share`, `GET /api/reports/shared/{token}`

### 9. GitMergeService

**Purpose:** Merge task branches to main with conflict resolution.

**Requirements:**
- Topological merge order
- 4-tier conflict resolution cascade:
  1. SpecializedMerger (pom.xml, YAML, package.json) — deterministic
  2. ConflictResolverAgent (WORKHORSE tier) — cheap LLM
  3. ConflictResolverAgent (PLANNING tier) — escalation
  4. FAILED — human intervention
- **Progressive merge:** each task merged to main immediately after completion (validation deferred to review phase)
- Auto-retry transient merge failures (up to 2 times, 500ms delay)
- Track all conflicts in DB for planner context injection
- Dependency branch merge failure throws (no silent continue)

### 10. Post-Merge Processing

**Purpose:** Automated documentation, prompt optimization, API spec generation, and GitHub integration.

**Requirements:**
- All non-fatal, run in virtual threads
- **ContextAgent:** Update CODEBASE_CONTEXT.md and decisions.md from diff
- **PromptOptimiserAgent:** Analyze failure patterns, surgically edit prompts (every 10 runs)
- **ApiTestSuiteAgent:** Generate OpenAPI 3.0 YAML for backend-only runs
- Publish **MergeCompleted** event for downstream listeners (integrations, notifications)
  - Note: MergeCompleted (not RunCompleted) is the correct event — RunCompleted fires before background review saves, causing race conditions
- **GitHub branch + PR creation** for existing-repo runs:
  - Creates branch `executespec/{runId-short}` and pushes merged code
  - Opens a PR automatically via `GitHubRepoService.createPullRequest()`
  - PR URL stored in `PipelineRun.githubPrUrl` and surfaced in RunDetail API response and frontend overview tab
  - Methods: `GitHubRepoService.getDefaultBranch()`, `GitHubRepoService.createPullRequest()`, `GitTool.pushToBranch()`

---

## Key Files

| File | Purpose |
|------|---------|
| `harness/PipelineHarness.java` | Main orchestrator |
| `harness/SequentialTaskExecutor.java` | Task execution with progressive merge |
| `harness/SubtaskDecomposer.java` | Multi-file task → per-file subtask decomposition |
| `harness/GitMergeService.java` | Merge with conflict resolution |
| `harness/GateStore.java` | Redis-backed approval gates |
| `agent/PlannerAgent.java` | Task decomposition |
| `agent/WorkerAgent.java` | Code generation loop |
| `reviewer/ReviewerAgentV2.java` | Two-phase agentic review + auto-fix |
| `compiler/CompilerAgent.java` | Build validation |
| `report/ValidationReportService.java` | Report orchestration |
| `report/ValidationReportGenerator.java` | LLM-based report generation |
| `report/ValidationReportPdfService.java` | PDF export |
| `testgen/TestGenOrchestrator.java` | Test generation |
| `testgen/TestPreviewCoordinator.java` | Preview lifecycle for test execution |
| `integration/github/GitHubRepoService.java` | Branch, PR creation, default branch lookup |
| `tool/GitTool.java` | Workspace git ops, pushToBranch() |
| `harness/event/PostMergeOrchestrator.java` | Post-merge agent dispatch |
