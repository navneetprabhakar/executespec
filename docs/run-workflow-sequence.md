# ExecuteSpec — Run Workflow Sequence

> Complete lifecycle of a user run from submission to completion.
> Last updated: 2026-04-04

---

## State Transitions Overview

```
QUEUED ─(slot available)─> PLANNING
                              |
                      AWAITING_APPROVAL
                        |           |
                    (rejected)   (approved)
                      FAILED      WORKING
                                    |
                              +-----+------+
                          (all ok)    (partial)
                              |           |
                          COMPILING     PARTIAL --(retry tasks)--> WORKING
                              |
                          MERGING
                              |
                          COMPLETE
                              |
                     +--------+--------+
                     |                 |
              Auto-preview      Background validation
             (5-min TTL)        (review -> test gen ->
                                  test exec -> report ->
                                  MergeCompleted event)

Terminal states:
  COMPLETE   — run succeeded, preview + validation may still be running
  PARTIAL    — some tasks failed, user can retry
  FAILED     — rejected, or unrecoverable error
  ARCHIVED   — user archived a completed or failed run

Side paths:
  AWAITING_APPROVAL --(paused)--> PAUSED --(resume)--> PLANNING / WORKING
  AWAITING_CLARIFICATION --(user replies)--> PLANNING
```

---

## Stage 0 — Run Submission

| | |
|---|---|
| **Trigger** | `POST /api/runs` |
| **Handler** | `RunController.submit()` -> `PipelineHarness.submitRun()` |
| **State** | -> `PLANNING` (or `QUEUED` if no compute slot) |

1. **Pre-flight checks** — tier enforcement, usage quota, API key validation (OWN_KEYS mode).
2. **Create `PipelineRun` entity** via `StateStore.createRun()`.
3. **Stamp ownership** (createdByUserId, orgId, projectId) — **must happen before async thread starts** (race condition guard).
4. Apply run options: `skipApproval`, `autoMerge`, `modelName`.
5. **Concurrency control** — if max concurrent slots full -> `QUEUED` at position N; otherwise -> `PLANNING`.
6. Return **HTTP 202** with `RunSummary`.

---

## Stage 1 — Workspace Initialization (async)

| | |
|---|---|
| **Trigger** | Compute slot acquired |
| **Handler** | `PipelineHarness.executeRunAsync()` |
| **State** | `PLANNING` |

1. **Workspace setup** (priority order):
   - *Iteration run* (sourceRunId set) -> copy tracked files from source workspace
   - *Existing repo* (repoUrl set) -> `git clone` with user's PAT
   - *Default* -> fresh empty git workspace
2. **Language validation** — detect supported languages; if none found -> `FAILED`.
3. **Reference extraction** — `ReferenceExtractorService` scans for JIRA/Confluence links.
   - If vague mention found -> enter `AWAITING_CLARIFICATION` gate (release slot, wait for user reply, re-extract, continue).
   - Otherwise -> enrich request with fetched context.

---

## Stage 2 — Planning

| | |
|---|---|
| **Trigger** | Workspace ready |
| **Handler** | `PlannerAgent.plan()` |
| **Model** | Opus (PLANNING tier) via `LlmRouter` |
| **State** | `PLANNING` -> `AWAITING_APPROVAL` |

1. **Prompt assembly** — load `prompts/planner.txt` + workspace listing + CODEBASE_CONTEXT.md.
2. **LLM call** via `LlmRouter` (IMMEDIATE fallback, up to 3 retry attempts for invalid JSON).
3. **Output** — `TaskPlan`: title, summary, `project_type`, up to 35 tasks (id, title, description, complexity, dependencies).
4. **Project type** — `project_type` written to `.agent-context/project.json` for downstream Dockerfile generators.
5. **Persist** — `StateStore.savePlan()`: rewrite task IDs with run prefix, create `AgentTask` entities (`PENDING`).
6. **Cost estimate** — `CostEstimator.estimate(plan)`.
7. Set `currentGate = PLAN_APPROVAL`, release compute slot.

---

## Stage 3 — Plan Approval Gate

| | |
|---|---|
| **Trigger** | Plan persisted |
| **Handler** | `GateStore` (Redis, 500ms polling, 2h TTL) |
| **State** | `AWAITING_APPROVAL` -> `WORKING` or `FAILED` |

- **Human approval** — `POST /api/runs/{id}/approve` resolves gate.
  - *Approved* -> `StateStore.markApproved()` -> `WORKING`, re-acquire compute slot.
  - *Rejected* -> `markRejected()` -> `FAILED`.
- **Auto-approve** — if `skipApproval=true`, gate resolves immediately.
- **Gate-loss fallback** — on server restart, Redis gate is re-polled.

---

## Stage 4 — Worker Execution (Sequential with Dependencies)

| | |
|---|---|
| **Trigger** | Plan approved |
| **Handler** | `SequentialTaskExecutor.execute()` -> `WorkerAgent.execute()` per task |
| **Model** | Sonnet (WORKHORSE) / Opus (PLANNING) based on task complexity |
| **State** | `WORKING` |
| **Max task duration** | 25 minutes wall-clock per task |

### Dependency Resolution
- Compute execution tiers from dependency graph: tier 0 = no deps, tier N = max depth.
- Tasks in the same tier run **concurrently** (virtual threads with context wrapping).
- Tiers execute sequentially.

### Per-Task Loop

1. **Lock & branch** — `StateStore.lockTask()`, create branch `agent/{runId}/{taskId}`.
2. **Model routing** — `LlmRouter.call()` with tier-based model selection.
3. **WorkerAgent agentic loop** (25-min wall-clock limit, target 20 iterations, hard limit 25 in prompts, `HARD_ITERATION_LIMIT=35` in code):
   - Create isolated git worktree, merge dependency branches.
   - While within deadline and iteration cap:
     - Inject inbox messages (user feedback).
     - **Progress checkpoint** every 5 min (re-inject task context).
     - **Stall detection** — no file writes in 5 min -> escalate model tier.
     - **Recovery warning** — 8 min before deadline.
     - **Recovery action** — 15 min: force context compaction + urgency injection.
     - **Context compaction** — compact if >80% token threshold.
     - **LLM call** via `LlmRouter` (XML `<tool_call>` format).
     - **Tool dispatch** — execute tools with truncation limits:
       - `readFile`: 8k chars
       - `shell`: 6k chars
       - `diff`: 5k chars
     - **Completion detection** — response starts with `{` -> parse `WorkerReport` (status: COMPLETE/PARTIAL/BLOCKED).
4. **Progressive merge** — `GitMergeService.mergeToMain()` under lock.
   - Conflict -> task `FAILED`.
   - Clean -> task `COMPLETE`, branch merged.

### Post-Execution
- All tasks blocked/failed -> run `FAILED`.
- Some incomplete -> run `PARTIAL` (user retries via UI).
- All complete -> continue to Stage 5.

---

## Stage 5 — Compilation & Fix

| | |
|---|---|
| **Trigger** | All tasks merged |
| **Handler** | `CompilerAgent.runCompileAndFix()` |
| **Model** | Sonnet (WORKHORSE tier) |
| **State** | `COMPILING` |

1. **Detect build tool** — Maven / Gradle / npm from sentinel files.
2. **Compile attempt** (up to N retries):
   - Run build command.
   - If passes -> done.
   - If fails -> send errors to LLM -> apply fixes -> retry.
   - **Dynamic error truncation** — SSE error display adapts to file count: <=3 files -> 2000 chars, <=6 files -> 1200 chars, >6 files -> 800 chars. LLM fix prompt capped at 2000 chars.
3. **Non-fatal** — compilation failure doesn't block the pipeline.

---

## Stage 6 — Auto-Merge & Completion

| | |
|---|---|
| **Trigger** | Compilation pass (or non-fatal failure) |
| **Handler** | `PipelineHarness` -> `StateStore.markComplete()` |
| **State** | `MERGING` -> `COMPLETE` |

1. **Auto-merge all tasks** — merge is automatic (no manual gate).
2. **Mark complete** — set status, record `completedAt` — **before review/tests**.
3. **Auto-start preview** — `autoStartPreview()` for both PROTOTYPE and PRODUCTION modes (5-min TTL).
4. **GitHub push** (if configured):
   - *New repos* -> push main branch.
   - *Existing repos* -> create branch `executespec/{runId-short}`, push to branch, open PR via `GitHubRepoService.createPullRequest()`, store PR URL in `PipelineRun.githubPrUrl`.
   - Failure is non-fatal.

---

## Stage 7 — Review (Background)

| | |
|---|---|
| **Trigger** | Runs in `runBackgroundValidation()` thread after COMPLETE |
| **Handler** | `ReviewerAgentV2.review()` |
| **Model** | Opus (PLANNING tier) |
| **State** | `COMPLETE` (run already marked complete) |

### Phase 1 — Inspection (max 10 tool calls)
- Read file tree, selected files, task summaries.
- Produce `ReviewIssue` list: id, severity (CRITICAL/MINOR), category, description, fixInstruction.
- README gate: softened for small runs — `taskCount <= 5` -> MINOR, `taskCount > 5` -> CRITICAL.

### Phase 2 — Auto-Fix (max 5 issues, 3-min timeout)
- For each issue (CRITICAL first):
  - Send fix instruction to `WorkerAgent`.
  - Validate fix via `CodeValidator`.
  - Mark `autoFixed = true/false`.

**Output** — `ReviewReportV2`: verdict (APPROVED / NEEDS_FIXES / REJECTED), issues list, auto-fix count.

---

## Stage 8 — Test Generation (Background)

| | |
|---|---|
| **Trigger** | Review complete (runs in background validation thread) |
| **Handler** | `TestGenOrchestrator.runTestGenPipeline()` |
| **Model** | Sonnet (WORKHORSE tier) for TestWorkerAgent, Haiku (LIGHTWEIGHT) for TestReportGenerator |
| **State** | `COMPLETE` (run already marked complete) |

1. **Test planning** — `TestPlannerAgent` creates a test plan from the user spec and generated code.
2. **Test code generation** — `TestWorkerAgent` generates test code for each test task.
3. **Test compilation** — compile generated tests; failures are fed back for retry.

---

## Stage 9 — Test Execution (Background)

| | |
|---|---|
| **Trigger** | Tests compile successfully in Stage 8 |
| **Handler** | `TestPreviewCoordinator` |
| **State** | `COMPLETE` (run already marked complete) |

1. **Preview reuse** — `TestPreviewCoordinator` checks for existing running preview via `previewService.getStatus(runId)`. Reuses the auto-started preview (started at COMPLETE). Only starts a new preview if none is running.
2. **Test execution** — execute compiled tests against the running preview.
3. **Report generation** — `TestReportGenerator` generates fix suggestions for any failed tests.
4. **Preview stays running** — preview is NOT stopped after test execution (stays up for user's full 5-min TTL).

**Non-fatal** — test execution failure doesn't block completion (run is already COMPLETE).

---

## Stage 10 — Validation Report

| | |
|---|---|
| **Trigger** | Test execution complete (or skipped) |
| **Handler** | `ValidationReportService.generateAndPersist()` -> `ValidationReportGenerator` |
| **Model** | Haiku (LIGHTWEIGHT tier) |
| **State** | `COMPLETE` (run already marked complete) |

1. Extract structured data: issues, languages, checks passed, files generated, test results.
2. LLM generates markdown report.
3. Persist `ValidationReport` entity (JSON + markdown).
4. Record metrics: result (PASSED/NEEDS_REVIEW/REJECTED), issue count, generation time.

**Non-fatal** — report failure doesn't block completion.

---

## Stage 11 — Background Validation Cleanup

| | |
|---|---|
| **Trigger** | Validation report generated (or all background stages done) |
| **Handler** | `PipelineHarness.runBackgroundValidation()` `finally` block |

1. **Publish `MergeCompleted` event** — triggers outbound integrations (JIRA comments, Confluence pages, Slack notifications). Note: uses `MergeCompleted` not `RunCompleted` to avoid race condition where review report is null.
2. **Cost calculation** — `CostEstimator.computeCostForRun()`.
3. **Deferred cleanup**:
   - `backgroundValidationRuns.remove(runId)`
   - `runKeyStore.remove(runId)`
   - `runBudgetTracker.remove(runId)`
   - `broadcaster.complete(runId)` — SSE stream closed only after background validation finishes

---

## Failure & Recovery Paths

| Scenario | Behavior |
|----------|----------|
| Unsupported language | `FAILED` immediately, no retry |
| No API keys | Fail pre-flight |
| Plan generation fails 3x | `FAILED`, eligible for auto-retry |
| Plan rejected | `FAILED` |
| Task fails after 3 validation retries | Task `FAILED`, run may be `PARTIAL` |
| Merge conflict | Affected task `FAILED`, run `PARTIAL` |
| Partial completion | User retries failed tasks via UI -> re-merge -> re-review -> `COMPLETE` |
| Auto-retry eligible | New run created with `runRetryCount++`, linked to failed run |
| Background validation fails | Emits `VALIDATION_FAILED` SSE event with error summary; run stays COMPLETE, no retry/recovery |

---

## Agent & Service Map

| Agent / Service | Stage | Model | Responsibility |
|-----------------|-------|-------|----------------|
| `RunController` | 0 | -- | REST entry, tier/quota checks |
| `PipelineHarness` | 1-11 | -- | Orchestrator, async thread, gate polling, `runBackgroundValidation()` (shared method) |
| `PlannerAgent` | 2 | Opus | Task decomposition, project type, cost estimation |
| `SequentialTaskExecutor` | 4 | -- | Dependency graph, tiered parallel dispatch |
| `WorkerAgent` | 4, 7 | Sonnet/Opus | Agentic tool-use loop (25 min), code generation |
| `CompilerAgent` | 5 | Sonnet | Build detection, LLM-guided compile fixes |
| `GitMergeService` | 4, 6 | -- | Branch merging, conflict detection |
| `ReviewerAgentV2` | 7 | Opus | Code inspection, auto-fix orchestration |
| `TestGenOrchestrator` | 8 | -- | Orchestrates test gen pipeline (sequential after review) |
| `TestPlannerAgent` | 8 | Opus | Creates test plan from user spec |
| `TestWorkerAgent` | 8 | Sonnet | Generates test code per test task |
| `TestPreviewCoordinator` | 9 | -- | Reuses or starts preview, owns preview lifecycle for test execution |
| `TestReportGenerator` | 9 | Haiku | Generates fix suggestions for failed tests |
| `ValidationReportService` | 10 | Haiku | Report generation & persistence |
| `GitHubRepoService` | 6 | -- | Branch creation, PR creation, default branch lookup |
| `LlmRouter` | 2,4,5,7-10 | -- | Multi-provider fallback, per-run key resolution |
| `GateStore` | 3 | -- | Redis-backed gates (500ms poll, 2h TTL) |
| `StateStore` | All | -- | Central state persistence |
| `PreviewService` | 6,9 | -- | Dual-mode preview lifecycle (Cloud Run / Docker) |

---

## Key Design Patterns

1. **Ownership before async** — all user/org/project fields stamped before async thread starts.
2. **Compute slot management** — slots released during gate waits, reacquired after.
3. **Progressive merge** — each task merged immediately after completion (not batched).
4. **COMPLETE before review** — run marked COMPLETE after compile+merge; review/tests run in background thread.
5. **Non-fatal stages** — compilation, review, test gen, report, GitHub push failures don't revert `COMPLETE`.
6. **Model escalation** — stall detection (5 min no writes) and validation retries trigger tier upgrade.
7. **Context compaction** — sliding window (first 2 + last 18 messages) + emergency compact at 80% threshold.
8. **Redis gate resilience** — gates survive server restarts via Redis persistence.
9. **Token-aware billing** — per-phase accumulation with cache multipliers (read x0.1, write x1.25).
10. **MergeCompleted for outbound** — integrations (JIRA, Confluence, Slack) fire on `MergeCompleted` event, not `RunCompleted`, so review report is available.
11. **Extracted `runBackgroundValidation()`** — shared method called by both main pipeline path and resume/retry paths; emits `VALIDATION_FAILED` SSE on thread failure.
12. **Mid-task provider recovery** — WorkerAgent compacts conversation + switches provider via `ModelResolver.resolveFallbackExcluding()` (1 attempt per task).
13. **Model-aware output budgets** — `TokenBudgetMonitor` caps output to model's physical max; downgrades HIGH->MEDIUM when model max <= 8192.

---

## Model Escalation Matrix

### Tier -> Model Mapping

| Tier | Anthropic | OpenAI | Google |
|------|-----------|--------|--------|
| **LIGHTWEIGHT** | `claude-haiku-4-5-20251001` | `gpt-5.4-mini` | `gemini-2.5-flash` |
| **WORKHORSE** | `claude-haiku-4-5-20251001` | `gpt-5.4-mini` | `gemini-2.5-flash` |
| **PLANNING** | `claude-sonnet-4-6` | `gpt-5.4` | `gemini-2.5-flash` |

### Provider Selection

Provider is **user-selected per project** (not weighted random). The user chooses Anthropic, Google, or OpenAI when creating a run. Health-aware — unhealthy providers (5 consecutive failures, Redis-backed circuit breaker) trigger failover to the next healthy provider for 60s.

### Per-Agent Model Assignments

| Agent | Default Tier | Model Selection |
|-------|-------------|-----------------|
| `PlannerAgent` | PLANNING | `LlmRouter` with PLANNING tier |
| `WorkerAgent` | Task-based | Complexity -> tier (see below) |
| `CompilerAgent` | WORKHORSE | `LlmRouter` with WORKHORSE tier |
| `ReviewerAgentV2` | PLANNING | `LlmRouter` with PLANNING tier |
| `ConversationAgent` | LIGHTWEIGHT | `LlmRouter` with LIGHTWEIGHT tier |
| `TestPlannerAgent` | WORKHORSE | `LlmRouter` with WORKHORSE tier |
| `TestWorkerAgent` | WORKHORSE | `LlmRouter` with WORKHORSE tier |
| `TestReportGenerator` | LIGHTWEIGHT | `LlmRouter` with LIGHTWEIGHT tier |
| `ContextAgent` (Scout) | LIGHTWEIGHT | Direct Haiku assignment |
| `PromptOptimiserAgent` | WORKHORSE | `LlmRouter` with WORKHORSE tier |
| `ValidationReportGenerator` | WORKHORSE | `LlmRouter` with WORKHORSE tier |

### Task Complexity -> Initial Tier

| Planner Complexity | Initial Tier | Typical Model |
|--------------------|-------------|---------------|
| LOW | LIGHTWEIGHT | Haiku |
| MEDIUM | WORKHORSE | Sonnet |
| HIGH | WORKHORSE | Sonnet |

### Escalation Triggers

#### 1. Validation Retry Escalation

When a task fails code validation (SYNTAX -> TYPE -> LINT -> TEST), the executor retries with model escalation:

| Attempt | Current Tier | Escalated To | Notes |
|---------|-------------|-------------|-------|
| 1-2 | LIGHTWEIGHT | LIGHTWEIGHT | No escalation, give model a second chance |
| 1-2 | WORKHORSE | WORKHORSE | No escalation |
| 3+ | LIGHTWEIGHT | **WORKHORSE** | Auto-escalate |
| 3+ | WORKHORSE | **WORKHORSE** | **Capped** -- never auto-escalates to PLANNING |
| Any | PLANNING | PLANNING | Manual trigger only |

> PLANNING tier is **never reached automatically**. It requires manual retry via `POST /api/runs/{id}/tasks/{taskId}/retry` or admin override.

#### 2. Stall Detection Escalation (Mid-Execution)

If a worker produces no file writes for 5 minutes, the model is escalated **within the same provider**:

```
claude-haiku-4-5-20251001  ->  claude-sonnet-4-6
claude-haiku-4-5-20251001  ->  claude-sonnet-4-6
gpt-5.4-mini               ->  gpt-5.4
```

Check interval: every 5 minutes during the agentic loop.

#### 3. Cross-Provider Fallback

When a provider fails, the system falls back to the **same tier at the next provider** in priority order:

```
Anthropic (primary) -> Google -> OpenAI
```

Three fixed fallback chains:

| Chain | Anthropic | Google | OpenAI |
|-------|-----------|--------|--------|
| PLANNING | `claude-sonnet-4-6` | `gemini-2.5-flash` | `gpt-5.4` |
| WORKHORSE | `claude-haiku-4-5-20251001` | `gemini-2.5-flash` | `gpt-5.4-mini` |
| LIGHTWEIGHT | `claude-haiku-4-5-20251001` | `gemini-2.5-flash` | `gpt-5.4-mini` |

**Mid-task recovery**: On provider failure mid-execution, WorkerAgent compacts the conversation to a provider-agnostic checkpoint and switches to a fallback provider via `ModelResolver.resolveFallbackExcluding()` (limited to 1 recovery attempt per task). Emits `mid_task_recovery` metric and SSE log event.

### Provider Health Tracking

| Parameter | Value |
|-----------|-------|
| Backend | Redis-backed circuit breaker |
| Unhealthy threshold | 5 consecutive failures |
| Recovery window | 60 seconds (auto, TTL-based) |
| Last-resort | If all unhealthy, attempt random pick anyway |

### Context Window Management

| Threshold | Action |
|-----------|--------|
| 25% input tokens (~50k) | Warn |
| 35% input tokens (~70k) | Compact (sliding window: first 2 + last 18 messages) |
| 55% input tokens (~110k) | Hard stop |

**Output token limits by complexity**: LOW=6,144 / MEDIUM=8,192 / HIGH=12,288. Capped to model's physical max output (e.g., Haiku max=8192 caps HIGH to 8192). `resolveEffectiveComplexity()` downgrades HIGH->MEDIUM when model max output <= 8192, preventing truncation loops.

### Escalation Flow Diagram

```
Task starts
  |
  +-- Complexity LOW -> LIGHTWEIGHT tier (Haiku)
  +-- Complexity MEDIUM/HIGH -> WORKHORSE tier (Sonnet)
  |
  v
Worker agentic loop (25-min wall clock)
  |
  +-- No file writes for 5 min?
  |     +-- YES -> Escalate within provider (Haiku->Sonnet->Opus)
  |
  +-- Recovery warning at 8 min remaining
  +-- Recovery action at 15 min remaining (compact + urgency)
  |
  +-- Provider fails?
  |     +-- At task start -> Cross-provider fallback (same tier)
  |     +-- Mid-task -> Compact conversation + switch to fallback via resolveFallbackExcluding() (1 attempt)
  |
  v
Progressive merge -> next task
  |
  +-- PASS -> merge to main
  +-- FAIL -> Task FAILED
```
