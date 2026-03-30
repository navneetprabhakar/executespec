# Core Pipeline — Requirements

## Overview

The core pipeline orchestrates multi-agent AI execution from natural language input to merged, validated code. It manages the full lifecycle: planning, approval, worker execution, compilation, review, merge, and post-merge processing.

---

## Features

### 1. Pipeline Orchestration (PipelineHarness)

**Purpose:** Central coordinator managing the entire run lifecycle.

**Requirements:**
- Accept natural language user requests via REST API (`POST /api/runs`)
- Orchestrate sequential phases: Plan → Approve → Work → Compile → Auto-merge → **COMPLETE** → Auto-preview → Background validation (Review + auto-fix + Test gen + Test exec + Report)
- Limit concurrent active runs via semaphore (`max-runs: 10`)
- Release compute slot while waiting at approval gates (gates don't consume compute)
- Support both REST and CLI execution paths
- Handle run cancellation, pause, and restart
- Carry forward credentials (GitHub PAT, API keys) across restarts

**Run States:**
```
PLANNING → AWAITING_APPROVAL → WORKING → COMPLETE
    ↓              ↓                ↓         ↓
  FAILED        FAILED          PARTIAL   Background validation
  QUEUED        (rejected)                (review + tests + report)
  PAUSED
```

### 2. PlannerAgent

**Purpose:** Decomposes user request into ordered tasks with complexity ratings.

**Requirements:**
- Use `claude-sonnet-4-6` (CAPABLE_CHAIN) for planning
- Generate a TaskPlan with 1–6 tasks in dependency order
- Assign complexity per task: LOW, MEDIUM, HIGH
- Include `dependsOn` relationships for topological ordering
- Provide acceptance criteria per task
- Specify files to create/modify for file ownership
- Inject workspace context (file tree, CODEBASE_CONTEXT.md) into prompt
- Inject historical merge conflict data to avoid conflict-prone patterns
- Retry up to 3 times on invalid JSON
- Support feasibility mode (cost estimation without creating a run)

### 3. WorkerAgent

**Purpose:** Execute a single task via agentic tool-use loop.

**Requirements:**
- Run in isolated git worktree per task
- Use XML tool-call format: `<tool_call>{"name":"...", "args":{...}}</tool_call>`
- Dispatch tools manually (not Spring AI native) with runId injection
- Support 9 tools: readFile, writeFile, editFile, listFiles, searchFiles, run, status, diff, commit
- Max 80 iterations per task
- Progress checkpoints every 15 iterations (re-inject task context)
- Completion urgency at iteration 75 (force JSON output)
- Pre-call token estimation for proactive compaction (>30 messages)
- Emergency compaction before hard-stop
- Strict completion detection: response must start with `{`
- Spring singleton — all state passed as method params
- Sanitize LLM-emitted control characters in JSON strings
- Support model override via `executeWithModel()`

### 4. Approval Gates

**Purpose:** Human-in-the-loop oversight before committing to execution and merge.

**Requirements:**
- **Plan Approval Gate:** Review task breakdown before workers start
  - Endpoint: `POST /api/runs/{id}/approve` with `{approved: boolean, comment: string}`
  - Display cost estimate (low/high) for informed decision
  - Support `skipApproval` flag (auto-approve for CI/integration runs)
- **Automatic Merge:** After review, all task branches are auto-merged (no manual gate)
  - Endpoint: `POST /api/runs/{id}/merge`
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

### 5. SequentialTaskExecutor

**Purpose:** Execute tasks in order with per-task validation and model routing.

**Requirements:**
- Topological sort via Kahn's algorithm
- Skip tasks if dependency failed (mark BLOCKED)
- Per task:
  1. Select model via ModelRouter based on complexity
  2. Create isolated worktree
  3. Run WorkerAgent
  4. Validate code via CodeValidator (configurable level: SYNTAX/TYPE/LINT/FULL)
  5. On failure: retry with error feedback + model tier escalation (up to 3 attempts)
  6. On success: commit and progressive merge to main
- Track metrics per task execution (model, provider, tier, pass/fail, duration)
- Emit SSE events: VALIDATION_STARTED, VALIDATION_PASSED, VALIDATION_FAILED, MODEL_ESCALATED

### 6. CompilerAgent

**Purpose:** Post-worker build validation.

**Requirements:**
- Detect build tool: Maven, Gradle, or npm
- Run compilation command
- On failure: extract errors + affected source, call LLM for JSON-patch fixes
- Up to 3 compile-fix rounds
- Non-fatal: always returns (doesn't block pipeline)
- Use `claude-haiku-4-5-20251001`

### 7. ReviewerAgent

**Purpose:** Automated code review.

**Requirements:**
- Gather diffs from all task branches (cap at 80k chars)
- Produce ReviewReport: recommendation (MERGE/REWORK), confidence, per-task issues
- Issues classified as CRITICAL, MEDIUM, LOW
- README update check is a hard gate
- Single LLM call with IMMEDIATE fallback
- ReviewerAgentV2 (optional): two-phase agentic review with tool-use inspection and auto-fix

### 7b. ValidationReportAgent

**Purpose:** Generate a user-facing quality summary of pipeline findings. Runs after ReviewerAgent, before COMPLETE.

**Requirements:**
- Aggregate existing pipeline data: WorkerReports, CompileResult, ReviewReport, task list
- Single LLM call (Haiku via LlmCallGuard) to generate markdown report
- CRITICAL: Frame as CI/CD quality gate — never mention AI, models, LLMs, or agents
- Extract issues from: ReviewReport.taskReviews[].issues[], CompileResult errors, boundary violations, acceptance criteria failures
- Classify pipeline result: PASSED, PASSED_WITH_FIXES, FAILED
- Persist to `validation_report` table (one per run, replace on rework)
- Non-fatal: on failure, log warning and continue to COMPLETE
- Share: generate UUID token for public URL (no auth required, whitelisted in SecurityConfig)
- PDF export: server-side PDF generation via `ValidationReportPdfService`
- Frontend: collapsible panel in RunDetail with severity badges, markdown report, all individual issues with scroll
- OTel span: `executespec.run.phase.report`
- Metrics: `executespec.report.generated`, `executespec.report.generation_duration`, `executespec.report.errors`, `executespec.report.issues_caught`, `executespec.report.shared`
- API endpoints: `GET /api/runs/{id}/report`, `GET /api/runs/{id}/report/pdf`, `POST /api/runs/{id}/report/share`, `GET /api/reports/shared/{token}`

### 8. GitMergeService

**Purpose:** Merge task branches to main with conflict resolution.

**Requirements:**
- Topological merge order
- 4-tier conflict resolution cascade:
  1. SpecializedMerger (pom.xml, YAML, package.json) — deterministic
  2. ConflictResolverAgent (Haiku) — cheap LLM
  3. ConflictResolverAgent (Sonnet) — escalation
  4. FAILED — human intervention
- Progressive merge: merge each task immediately after completion
- Auto-retry transient merge failures (up to 2 times, 500ms delay)
- Track all conflicts in DB for planner context injection
- README update enforcement as hard gate

### 9. Post-Merge Processing

**Purpose:** Automated documentation, prompt optimization, and API spec generation.

**Requirements:**
- All non-fatal, run in virtual threads
- **ContextAgent:** Update CODEBASE_CONTEXT.md and decisions.md from diff
- **PromptOptimiserAgent:** Analyze failure patterns, surgically edit prompts (every 10 runs)
- **ApiTestSuiteAgent:** Generate OpenAPI 3.0 YAML for backend-only runs
- Publish MergeCompleted event for listener

---

## Key Files

| File | Purpose |
|------|---------|
| `harness/PipelineHarness.java` | Main orchestrator |
| `harness/SequentialTaskExecutor.java` | Task execution with validation |
| `harness/WorkerPool.java` | Legacy parallel execution + retry |
| `harness/GitMergeService.java` | Merge with conflict resolution |
| `harness/GateStore.java` | Redis-backed approval gates |
| `agent/PlannerAgent.java` | Task decomposition |
| `agent/WorkerAgent.java` | Code generation loop |
| `agent/ReviewerAgent.java` | Code review |
| `compiler/CompilerAgent.java` | Build validation |
| `harness/event/PostMergeOrchestrator.java` | Post-merge agent dispatch |
