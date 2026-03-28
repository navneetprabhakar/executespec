# ExecuteSpec — Run Workflow Sequence

> Complete lifecycle of a user run from submission to completion.

---

## State Transitions Overview

```
QUEUED ─(slot available)─→ PLANNING
                              ↓
                      AWAITING_APPROVAL
                        ↓           ↓
                    (rejected)   (approved)
                      FAILED      WORKING
                                    ↓
                              ┌─────┴──────┐
                          (all ok)    (partial)
                              ↓           ↓
                          REVIEWING     PARTIAL ──(retry tasks)──→ WORKING
                              ↓
                        (review done)
                              ↓
                        TEST_GENERATION
                              ↓
                        TEST_EXECUTION
                              ↓
                           COMPLETE

Side paths:
  AWAITING_APPROVAL ──(paused)──→ PAUSED ──(resume)──→ PLANNING / WORKING
  AWAITING_CLARIFICATION ──(user replies)──→ PLANNING
```

---

## Stage 0 — Run Submission

| | |
|---|---|
| **Trigger** | `POST /api/runs` |
| **Handler** | `RunController.submit()` → `PipelineHarness.submitRun()` |
| **State** | → `PLANNING` (or `QUEUED` if no compute slot) |

1. **Pre-flight checks** — tier enforcement, usage quota, API key validation (OWN_KEYS mode).
2. **Create `PipelineRun` entity** via `StateStore.createRun()`.
3. **Stamp ownership** (createdByUserId, orgId, projectId) — **must happen before async thread starts** (race condition guard).
4. Apply run options: `skipApproval`, `autoMerge`, `modelName`.
5. **Concurrency control** — if max concurrent slots full → `QUEUED` at position N; otherwise → `PLANNING`.
6. Return **HTTP 202** with `RunSummary`.

---

## Stage 1 — Workspace Initialization (async)

| | |
|---|---|
| **Trigger** | Compute slot acquired |
| **Handler** | `PipelineHarness.executeRunAsync()` |
| **State** | `PLANNING` |

1. **Workspace setup** (priority order):
   - *Iteration run* (sourceRunId set) → copy tracked files from source workspace
   - *Existing repo* (repoUrl set) → `git clone` with user's PAT
   - *Default* → fresh empty git workspace
2. **Language validation** — detect supported languages; if none found → `FAILED`.
3. **Reference extraction** — `ReferenceExtractorService` scans for JIRA/Confluence links.
   - If vague mention found → enter `AWAITING_CLARIFICATION` gate (release slot, wait for user reply, re-extract, continue).
   - Otherwise → enrich request with fetched context.

---

## Stage 2 — Planning

| | |
|---|---|
| **Trigger** | Workspace ready |
| **Handler** | `PlannerAgent.plan()` |
| **Model** | Sonnet (CAPABLE tier) via `ModelRouter` |
| **State** | `PLANNING` → `AWAITING_APPROVAL` |

1. **Prompt assembly** — load `prompts/planner.txt` + workspace listing + CODEBASE_CONTEXT.md.
2. **LLM call** via `LlmRouter` (IMMEDIATE fallback, up to 3 retry attempts for invalid JSON).
3. **Output** — `TaskPlan`: title, summary, up to 35 tasks (id, title, description, complexity, dependencies).
4. **Persist** — `StateStore.savePlan()`: rewrite task IDs with run prefix, create `AgentTask` entities (`PENDING`).
5. **Cost estimate** — `CostEstimator.estimate(plan)`.
6. Set `currentGate = PLAN_APPROVAL`, release compute slot.

---

## Stage 3 — Plan Approval Gate

| | |
|---|---|
| **Trigger** | Plan persisted |
| **Handler** | `GateStore` (Redis, 500ms polling, 2h TTL) |
| **State** | `AWAITING_APPROVAL` → `WORKING` or `FAILED` |

- **Human approval** — `POST /api/runs/{id}/approve` resolves gate.
  - *Approved* → `StateStore.markApproved()` → `WORKING`, re-acquire compute slot.
  - *Rejected* → `markRejected()` → `FAILED`.
- **Auto-approve** — if `skipApproval=true`, gate resolves immediately.
- **Gate-loss fallback** — on server restart, Redis gate is re-polled.

---

## Stage 4 — Worker Execution (Tiered Parallel)

| | |
|---|---|
| **Trigger** | Plan approved |
| **Handler** | `SequentialTaskExecutor.execute()` → `WorkerAgent.execute()` per task |
| **Model** | Haiku (FAST) / Sonnet (CAPABLE) / Claude 3.5 (POWER) based on task complexity |
| **State** | `WORKING` |

### Dependency Resolution
- Compute execution tiers from dependency graph: tier 0 = no deps, tier N = max depth.
- Tasks in the same tier run **concurrently** (virtual threads with context wrapping).
- Tiers execute sequentially.

### Per-Task Loop

1. **Lock & branch** — `StateStore.lockTask()`, create branch `agent/{runId}/{taskId}`.
2. **Model routing** — `ModelRouter.selectFromTier(complexity)` with weighted round-robin (4:3:3).
3. **WorkerAgent agentic loop** (15-min wall-clock limit):
   - Create isolated git worktree, merge dependency branches.
   - While within deadline and iteration cap:
     - Inject inbox messages (user feedback).
     - **Progress checkpoint** every 5 min (re-inject task context).
     - **Stall detection** — no file writes in 5 min → escalate model tier.
     - **Urgency injection** — 2 min before deadline.
     - **Context compaction** — compact if >80% token threshold.
     - **LLM call** via `LlmRouter` (XML `<tool_call>` format).
     - **Tool dispatch** — execute tools with truncation limits:
       - `readFile`: 8k chars
       - `shell`: 6k chars
       - `diff`: 5k chars
     - **Completion detection** — response starts with `{` → parse `WorkerReport` (status: COMPLETE/PARTIAL/BLOCKED).
4. **Code validation** — `CodeValidator.validate()` runs gate chain: SYNTAX → TYPE → LINT → TEST.
   - Failure → retry up to 3 times with error feedback + model escalation.
5. **Progressive merge** — `GitMergeService.mergeToMain()` under lock.
   - Conflict → task `FAILED`.
   - Clean → task `COMPLETE`, branch merged.

### Post-Execution
- All tasks blocked/failed → run `FAILED`.
- Some incomplete → run `PARTIAL` (user retries via UI).
- All complete → continue to Stage 5.

---

## Stage 5 — Compilation & Fix

| | |
|---|---|
| **Trigger** | All tasks merged |
| **Handler** | `CompilerAgent.runCompileAndFix()` |
| **Model** | Haiku (FAST tier) |
| **State** | `WORKING` (continued) |

1. **Detect build tool** — Maven / Gradle / npm from sentinel files.
2. **Compile attempt** (up to N retries):
   - Run build command.
   - If passes → done.
   - If fails → send errors to LLM → apply fixes → retry.
3. **Non-fatal** — compilation failure doesn't block the pipeline.

> Runs multiple times: post-worker, post-merge, and post-auto-fix.

---

## Stage 6 — Review

| | |
|---|---|
| **Trigger** | Compilation pass (or non-fatal failure) |
| **Handler** | `ReviewerAgentV2.review()` |
| **Model** | Haiku (CAPABLE tier) |
| **State** | → `REVIEWING` |

### Phase 1 — Inspection (max 10 tool calls)
- Read file tree, selected files, task summaries.
- Produce `ReviewIssue` list: id, severity (CRITICAL/MINOR), category, description, fixInstruction.
- Hard gate: README not updated → CRITICAL issue.

### Phase 2 — Auto-Fix (max 5 issues, 3-min timeout)
- For each issue (CRITICAL first):
  - Send fix instruction to `WorkerAgent`.
  - Validate fix via `CodeValidator`.
  - Mark `autoFixed = true/false`.

**Output** — `ReviewReportV2`: verdict (APPROVED / NEEDS_FIXES / REJECTED), issues list, auto-fix count.

---

## Stage 7 — Test Generation

| | |
|---|---|
| **Trigger** | Review complete (runs sequentially AFTER review, not in parallel) |
| **Handler** | `TestGenOrchestrator.runTestGenPipeline()` |
| **Model** | Haiku (WORKHORSE tier) for TestWorkerAgent, LIGHTWEIGHT for TestReportGenerator |
| **State** | `REVIEWING` (continued) |

1. **Test planning** — `TestPlannerAgent` creates a test plan from the user spec and generated code.
2. **Test code generation** — `TestWorkerAgent` generates test code for each test task.
3. **Test compilation** — compile generated tests; failures are fed back for retry.

---

## Stage 8 — Test Execution

| | |
|---|---|
| **Trigger** | Tests compile successfully in Stage 7 |
| **Handler** | `TestPreviewCoordinator` |
| **State** | `REVIEWING` (continued) |

1. **Preview startup** — `TestPreviewCoordinator` starts a preview AFTER tests compile. It owns the preview lifecycle for test execution (10-min timeout).
2. **Test execution** — execute compiled tests against the running preview.
3. **Report generation** — `TestReportGenerator` generates fix suggestions for any failed tests.

**Non-fatal** — test execution failure doesn't block completion.

---

## Stage 9 — Validation Report

| | |
|---|---|
| **Trigger** | Test execution complete (or skipped) |
| **Handler** | `ValidationReportService.generateAndPersist()` → `ValidationReportGenerator` |
| **Model** | Haiku (FAST tier) |
| **State** | `REVIEWING` (continued) |

1. Extract structured data: issues, languages, checks passed, files generated, test results.
2. LLM generates markdown report.
3. Persist `ValidationReport` entity (JSON + markdown).
4. Record metrics: result (PASSED/NEEDS_REVIEW/REJECTED), issue count, generation time.

**Non-fatal** — report failure doesn't block completion.

---

## Stage 10 — Completion & Cleanup

| | |
|---|---|
| **Trigger** | All stages complete |
| **Handler** | `PipelineHarness` → `StateStore.markComplete()` |
| **State** | → `COMPLETE` |

1. **Mark complete** — set status, record `completedAt`.
2. **Cost calculation** — `CostEstimator.computeCostForRun()` (per-task: provider × model × tokens × rate).
3. **GitHub push** (if configured) — push main branch; failure is non-fatal.
4. **Events** — `RunCompleted`, SSE broadcast, close SSE connection.
5. **Cleanup** — release compute slot, remove API keys from `RunKeyStore`, record duration metrics.

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
| Partial completion | User retries failed tasks via UI → re-merge → re-review → `COMPLETE` |
| Auto-retry eligible | New run created with `runRetryCount++`, linked to failed run |

---

## Agent & Service Map

| Agent / Service | Stage | Model | Responsibility |
|-----------------|-------|-------|----------------|
| `RunController` | 0 | — | REST entry, tier/quota checks |
| `PipelineHarness` | 1–10 | — | Orchestrator, async thread, gate polling |
| `PlannerAgent` | 2 | Sonnet | Task decomposition, cost estimation |
| `SequentialTaskExecutor` | 4 | — | Dependency graph, tiered parallel dispatch |
| `WorkerAgent` | 4, 6 | Haiku/Sonnet/Power | Agentic tool-use loop (15 min), code generation |
| `CodeValidator` | 4 | — | SYNTAX → TYPE → LINT → TEST gate chain |
| `CompilerAgent` | 5, 8 | Haiku | Build detection, LLM-guided compile fixes |
| `GitMergeService` | 4, 6 | — | Branch merging, conflict detection |
| `ReviewerAgentV2` | 6 | Haiku | Code inspection, auto-fix orchestration |
| `TestGenOrchestrator` | 7 | — | Orchestrates test gen pipeline (sequential after review) |
| `TestPlannerAgent` | 7 | Haiku | Creates test plan from user spec |
| `TestWorkerAgent` | 7 | Haiku | Generates test code per test task |
| `TestPreviewCoordinator` | 8 | — | Starts preview after tests compile, owns preview lifecycle for test execution |
| `TestReportGenerator` | 8 | Haiku | Generates fix suggestions for failed tests |
| `ValidationReportService` | 9 | Haiku | Report generation & persistence |
| `LlmRouter` | 2,4,5,6–9 | — | Multi-provider fallback, per-run key resolution |
| `ModelRouter` | 2,4,5,6–9 | — | Tier-based model selection, weighted round-robin |
| `GateStore` | 3 | — | Redis-backed gates (500ms poll, 2h TTL) |
| `StateStore` | All | — | Central state persistence |
| `PreviewService` | 8 | — | Cloud Run preview lifecycle (used by TestPreviewCoordinator) |

---

## Key Design Patterns

1. **Ownership before async** — all user/org/project fields stamped before async thread starts.
2. **Compute slot management** — slots released during gate waits, reacquired after.
3. **Progressive merge** — each task merged immediately after validation (not batched).
4. **Non-fatal stages** — compilation, report, GitHub push failures don't revert `COMPLETE`.
5. **Model escalation** — stall detection (5 min no writes) and validation retries trigger tier upgrade.
6. **Context compaction** — sliding window (first 2 + last 18 messages) + emergency compact at 80% threshold.
7. **Redis gate resilience** — gates survive server restarts via Redis persistence.
8. **Token-aware billing** — per-phase accumulation with cache multipliers (read×0.1, write×1.25).

---

## Model Escalation Matrix

### Tier → Model Mapping

| Tier | Anthropic | OpenAI | Google |
|------|-----------|--------|--------|
| **FAST** | `claude-haiku-4-5-20251001` | `gpt-5.4-mini` | `gemini-3-flash-preview` |
| **CAPABLE** | `claude-sonnet-4-6` | `gpt-5.4` | `gemini-3.1-pro-preview` |
| **POWER** | `claude-opus-4-6` | `gpt-5.4` | `gemini-3.1-pro-preview` |

### Provider Weight Distribution

| Provider | Weight | Share |
|----------|--------|-------|
| Anthropic | 4 | 40% |
| Google | 3 | 30% |
| OpenAI | 3 | 30% |

Selection is **weighted random** (not round-robin). Health-aware — unhealthy providers (3 consecutive failures) are skipped for 60s.

### Per-Agent Model Assignments

| Agent | Default Tier | Model Selection |
|-------|-------------|-----------------|
| `PlannerAgent` | CAPABLE | `modelRouter.selectFromTier(CAPABLE)` |
| `WorkerAgent` | Task-based | Complexity → tier (see below) |
| `CompilerAgent` | FAST | Direct Haiku assignment |
| `ReviewerAgentV2` | CAPABLE | `modelRouter.selectFromTier(CAPABLE)` |
| `ConversationAgent` | CAPABLE | `modelRouter.selectFromTier(CAPABLE)` |
| `TestWorkerAgent` | CAPABLE | `modelRouter.selectFromTier(CAPABLE)` |
| `TestReportGenerator` | FAST | `modelRouter.selectFromTier(FAST)` |
| `ContextAgent` (Scout) | FAST | Direct Haiku assignment |
| `PromptOptimiserAgent` | FAST | Direct Haiku assignment |
| `ValidationReportGenerator` | FAST | Direct Haiku assignment |

### Task Complexity → Initial Tier

| Planner Complexity | Initial Tier | Typical Model |
|--------------------|-------------|---------------|
| LOW | FAST | Haiku |
| MEDIUM | CAPABLE | Sonnet |
| HIGH | CAPABLE | Sonnet |

### Escalation Triggers

#### 1. Validation Retry Escalation

When a task fails code validation (SYNTAX → TYPE → LINT → TEST), the executor retries with model escalation:

| Attempt | Current Tier | Escalated To | Notes |
|---------|-------------|-------------|-------|
| 1–2 | FAST | FAST | No escalation, give model a second chance |
| 1–2 | CAPABLE | CAPABLE | No escalation |
| 3+ | FAST | **CAPABLE** | Auto-escalate |
| 3+ | CAPABLE | **CAPABLE** | **Capped** — never auto-escalates to POWER |
| Any | POWER | POWER | Manual trigger only |

> POWER tier is **never reached automatically**. It requires manual retry via `POST /api/runs/{id}/tasks/{taskId}/retry` or admin override.

#### 2. Stall Detection Escalation (Mid-Execution)

If a worker produces no file writes for 5 minutes, the model is escalated **within the same provider**:

```
claude-haiku-4-5-20251001  →  claude-sonnet-4-6
claude-sonnet-4-6          →  claude-opus-4-6
gpt-5.4-mini               →  gpt-5.4
```

Check interval: every 5 minutes during the agentic loop.

#### 3. Cross-Provider Fallback

When a provider fails, the system falls back to the **same tier at the next provider** in priority order:

```
Anthropic (primary) → Google → OpenAI
```

Three fixed fallback chains:

| Chain | Anthropic | Google | OpenAI |
|-------|-----------|--------|--------|
| ARCHITECT (POWER) | `claude-opus-4-6` | `gemini-3.1-pro-preview` | `gpt-5.4` |
| CAPABLE | `claude-sonnet-4-6` | `gemini-3.1-pro-preview` | `gpt-5.4` |
| UTILITY (FAST) | `claude-haiku-4-5-20251001` | `gemini-3-flash-preview` | `gpt-5.4-mini` |

**Mid-task guard**: Fallback is **blocked mid-execution** (iteration > 0) to prevent state loss. Allowed only at task start or after context compaction.

### Provider Health Tracking

| Parameter | Value |
|-----------|-------|
| Unhealthy threshold | 3 consecutive failures |
| Recovery window | 60 seconds (auto) |
| Last-resort | If all unhealthy, attempt random pick anyway |

### Circuit Breaker

| Parameter | Value |
|-----------|-------|
| Max LLM calls per run | 100 |
| Rate-limit sleep | 30 seconds, then retry once |
| Overload (529) handling | 30-second sleep, retry once |

### Context Window Management

| Threshold | Action |
|-----------|--------|
| 25% input tokens (~50k) | Warn |
| 35% input tokens (~70k) | Compact (sliding window: first 2 + last 18 messages) |
| 55% input tokens (~110k) | Hard stop |

**Output token limits by complexity**: LOW=2,048 · MEDIUM=4,096 · HIGH=8,192 · Ceiling=16,000

### Escalation Flow Diagram

```
Task starts
  │
  ├─ Complexity LOW → FAST tier (Haiku)
  ├─ Complexity MEDIUM/HIGH → CAPABLE tier (Sonnet)
  │
  ▼
Worker agentic loop (15-min wall clock)
  │
  ├─ No file writes for 5 min?
  │     └─ YES → Escalate within provider (Haiku→Sonnet→Opus)
  │
  ├─ Provider fails?
  │     ├─ At task start → Cross-provider fallback (same tier)
  │     └─ Mid-task → Block fallback, throw MidTaskProviderFailureException
  │
  ▼
Code validation (SYNTAX → TYPE → LINT → TEST)
  │
  ├─ PASS → Progressive merge → next task
  │
  └─ FAIL → Retry (up to 3x)
        ├─ Attempt 1-2 → Same tier, inject error feedback
        └─ Attempt 3+  → Escalate tier (FAST→CAPABLE, CAPABLE stays CAPABLE)
                          └─ Still fails → Task FAILED
```
