# ExecuteSpec — Complete Code Generation Architecture

> Comprehensive technical reference for the multi-agent code generation pipeline.
> Last updated: 2026-04-04

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Pipeline Orchestration](#2-pipeline-orchestration)
3. [Planning Phase](#3-planning-phase)
4. [Model Routing & Resolution](#4-model-routing--resolution)
5. [Worker Agent System](#5-worker-agent-system)
6. [Token Budget & Complexity Management](#6-token-budget--complexity-management)
7. [Tool System & Dispatch](#7-tool-system--dispatch)
8. [Git Worktree Isolation](#8-git-worktree-isolation)
9. [Progressive Merge & Conflict Resolution](#9-progressive-merge--conflict-resolution)
10. [Compilation & Auto-Fix](#10-compilation--auto-fix)
11. [Code Review & Auto-Fix](#11-code-review--auto-fix)
12. [Test Generation & Execution](#12-test-generation--execution)
13. [Validation Report](#13-validation-report)
14. [API Key Resolution & Multi-Tenancy](#14-api-key-resolution--multi-tenancy)
15. [Cost Estimation](#15-cost-estimation)
16. [Observability & Metrics](#16-observability--metrics)
17. [Security & Sandboxing](#17-security--sandboxing)
18. [Key Thresholds & Constants](#18-key-thresholds--constants)

---

## 1. System Overview

ExecuteSpec is a multi-agent code generation platform that transforms natural-language specifications into working, tested, deployable code. The system decomposes user requests into parallelizable tasks, executes them with LLM-powered worker agents in isolated git worktrees, progressively merges results, reviews the output, generates tests, and produces a validation report.

### High-Level Flow

```
User Request
    │
    ▼
┌──────────────────┐
│  PlannerAgent     │  PLANNING tier model decomposes request into tasks
│  (PLANNING tier)  │  Assigns complexity, dependencies, file ownership
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│  Plan Approval    │  Redis-backed gate; user reviews plan + cost estimate
│  Gate             │  Options: approve, reject, edit
└────────┬─────────┘
         │
         ▼
┌──────────────────────────────────────────────────────┐
│  SequentialTaskExecutor                               │
│                                                       │
│  Tier 0: ┌─────┐ ┌─────┐ ┌─────┐  (parallel)       │
│          │ W-A │ │ W-B │ │ W-C │                     │
│          └──┬──┘ └──┬──┘ └──┬──┘                     │
│             └───────┼───────┘                         │
│                     ▼                                 │
│          Progressive merge to main                    │
│                     │                                 │
│  Tier 1: ┌─────┐ ┌─────┐          (parallel)        │
│          │ W-D │ │ W-E │                              │
│          └──┬──┘ └──┬──┘                              │
│             └───────┘                                 │
│                     ▼                                 │
│          Progressive merge to main                    │
└────────────────────┬─────────────────────────────────┘
                     │
                     ▼
┌──────────────────┐
│  CompilerAgent   │
│  compile + fix   │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│  Auto-Merge All  │
│  Task Branches   │
└────────┬─────────┘
         │
         ▼
     ★ COMPLETE ★  (user sees this immediately)
         │
    ┌────┴────┐
    ▼         ▼
┌────────┐  ┌──────────────────────────────────┐
│ Auto   │  │  Background Validation Thread     │
│ Preview│  │                                    │
│ (5min) │  │  ReviewerAgentV2 → Auto-Fix       │
└────────┘  │  TestGenOrchestrator → Compile     │
            │  TestPreviewCoordinator (reuses    │
            │    existing preview)                │
            │  Test Execution Against Preview     │
            │  Validation Report (markdown + PDF) │
            └──────────────────────────────────┘
```

### Key Architectural Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Task isolation | Git worktrees (not branches) | Enables true parallel file system access |
| Merge strategy | Progressive (after each task) | Early conflict detection, clear task attribution |
| LLM routing | Multi-provider with circuit breaker | Resilience; no single-provider dependency |
| Tool format | XML `<tool_call>` (Anthropic/Google), native (OpenAI) | Provider-specific optimization |
| Token control | Fixed output ceilings per complexity | Caps latency; auto-continue handles overflow |
| Review | Agentic with auto-fix | Zero human intervention for fixable issues |
| Completion before review | COMPLETE fires pre-review | User gets preview/chat immediately; validation runs in background |
| Integration events | MergeCompleted (not RunCompleted) | Avoids race where review report is null at RunCompleted time |

---

## 2. Pipeline Orchestration

### PipelineHarness — The State Machine

**File:** `harness/PipelineHarness.java`

PipelineHarness is the top-level orchestrator. It manages the full run lifecycle as a linear state machine with gates.

### State Machine

```
QUEUED ──► PLANNING ──► AWAITING_APPROVAL ──► WORKING ──► REVIEWING ──► COMPLETE
  │            │               │                  │            │            │
  │            ▼               ▼                  ▼            ▼            ▼
  │         FAILED          FAILED             PARTIAL      FAILED      PARTIAL
  │                        (rejected)          (some fail)              (issues)
  │
  └──► (slot acquired) ──► continues
```

### Status Transitions

| Status | Entry Condition | Exit Condition |
|--------|-----------------|----------------|
| `QUEUED` | No compute slot available | Semaphore slot acquired |
| `PLANNING` | Run submitted | Plan generated |
| `AWAITING_APPROVAL` | Plan ready | User approves/rejects |
| `AWAITING_CLARIFICATION` | Ambiguous JIRA/Confluence refs | User clarifies |
| `WORKING` | Plan approved | All tasks complete/fail |
| `REVIEWING` | All tasks merged | Review verdict issued |
| `PAUSED` | User pauses run | User edits goal and resumes |
| `COMPLETE` | Compile + merge pass | Terminal (background validation continues) |
| `PARTIAL` | Some tasks incomplete | User can retry individual tasks |
| `FAILED` | Unrecoverable error | Terminal |
| `CANCELLED` | User cancels | Terminal |
| `ARCHIVED` | User archives run | Terminal |

### Execution Modes

| Mode | Review | Tests | Preview | Use Case |
|------|--------|-------|---------|----------|
| **PRODUCTION** | Yes (ReviewerAgentV2, background) | Yes (TestGenOrchestrator, background) | Auto-start at COMPLETE | Full quality pipeline |
| **PROTOTYPE** | Skipped | Skipped | Auto-start at COMPLETE | Fast iteration |

### Concurrency Control

- **Semaphore:** `maxRuns` (default 50) limits concurrent pipeline executions
- **Gate polling:** 500ms interval on Redis for approval/merge decisions
- **Gate TTL:** 2 hours — auto-expires if user doesn't respond
- **Slot release during gates:** Compute slot released while waiting for user approval, re-acquired after

### Phase-by-Phase Flow

#### Phase 0: Pre-Pipeline Setup
1. Workspace initialization (clone repo, copy from iteration source, or fresh)
2. Language validation for existing repos
3. JIRA/Confluence context extraction and enrichment
4. If clarification needed → `AWAITING_CLARIFICATION` gate

#### Phase 1: Planning
1. `PlannerAgent.plan()` generates task plan
2. UI style detection from request
3. Cost estimate computation (low/high USD range)
4. Plan JSON saved to DB with UUID-prefixed task IDs
5. Status: `PLANNING` → `AWAITING_APPROVAL`

#### Phase 2: Plan Approval Gate
- `skipApproval` flag bypasses gate
- User approves → `WORKING`
- User rejects → `FAILED`
- Run paused during gate → clean exit

#### Phase 3: Worker Execution
1. Init agent context (`.agent-context/project.json`)
2. Validate file ownership (no overlaps between same-tier tasks)
3. Filter pending tasks (skip legacy system tasks)
4. Execute via tiered parallel dispatch (virtual threads)
5. All tasks failed → `FAILED`; some incomplete → `PARTIAL`; all complete → proceed

#### Phase 4: Compilation
- Pre-merge compile (catches early errors)
- Post-merge compile (catches semantic conflicts)
- Post-fix re-validation (after reviewer auto-fixes)

#### Phase 5: Auto-Merge
- Prune stale worktrees
- Merge all task branches to main
- Handle merge conflicts → mark affected tasks `FAILED`
- Conflicts → `PARTIAL`; all merged → proceed

#### Phase 6: COMPLETE + Auto-Preview
- Mark run `COMPLETE` immediately after compile + merge
- Auto-start preview (5-min TTL) for both PROTOTYPE and PRODUCTION modes
- Push to GitHub if configured (creates branch `executespec/{runIdShort}` + opens PR)
- Save actual cost, publish completion events
- User can chat, submit changes, and use preview immediately

#### Phase 7: Background Validation (PRODUCTION only)
Runs on a dedicated virtual thread (`bg-validation-{runId}`), via the shared `runBackgroundValidation()` method (extracted from inline code, reused by iteration and resume paths):
- ReviewerAgentV2 inspects merged code on main
- Returns ReviewVerdict: `APPROVED`, `APPROVED_WITH_NOTES`, `NEEDS_FIXES`, `REJECTED`
- Auto-fix critical issues (WORKHORSE tier → PLANNING tier on retry)
- Re-validate after auto-fixes
- TestGenOrchestrator runs in parallel with review (language-agnostic test generation)
- TestPreviewCoordinator reuses existing preview for test execution

#### Phase 8: Validation Report & Test Execution
- Wait for test execution completion (CompletableFuture, 10-min timeout)
- Generate ValidationReport with merged results
- Include worker reports, review findings, test results
- SSE `broadcaster.complete()`, `runKeyStore.remove()`, and `runBudgetTracker.remove()` deferred to background thread's `finally` block

### Error Handling

| Scenario | Response |
|----------|----------|
| Task execution exception | `failTask()`, emit SSE, mark run `PARTIAL` |
| Dependency failure | Block dependent tasks → mark `BLOCKED` |
| Merge conflict (unresolvable) | Mark affected tasks `FAILED`, run `PARTIAL` |
| Redis connection loss | 500ms poll retry (gates) |
| Gate lost on restart | `GateFallback` thread handles orphaned gates |
| All workers fail | `FAILED` (nothing to merge) |
| Background validation failure | Emits `VALIDATION_FAILED` SSE event; no retry/recovery |

---

## 3. Planning Phase

### PlannerAgent

**File:** `agent/PlannerAgent.java`

The PlannerAgent uses a PLANNING-tier model to decompose the user's request into an executable task plan.

### Planning Flow

1. Build workspace context (directory listing + `CODEBASE_CONTEXT.md`)
2. Load system prompt from `prompts/planner.txt` (edit mode uses `{{#if editMode}}` conditional block — `planner_edit.txt` was merged into `planner.txt`)
3. Append conflict history context (last 5 merges, up to 20 conflicts)
4. If existing repo / edit mode: activate `{{#if editMode}}` block with iteration variables
5. Call LLM with 3-attempt retry for valid JSON
6. Parse response into `TaskPlan` via `LlmOutputParser`
7. Validate: non-empty task list, truncate to 35 tasks max
8. Track tokens: `stateStore.addTokensToRun()`

### Planner System Prompt Key Directives

**Task Sizing Constraints:**

| Complexity | Files | Lines | Expected Iterations |
|------------|-------|-------|---------------------|
| LOW | 1-2 | ≤80 | ~10 |
| MEDIUM | 2-3 | 80-200 | ~15-20 |
| HIGH | 3-5 | 200-350 | ~20-25 |

**Absolute rule:** No task touches >5 files or writes >350 lines.

**Expected Complexity Distribution:**
- 60-70% LOW
- 25-35% MEDIUM
- 0-10% HIGH (often zero)
- If >15% HIGH → re-examine and split

**Parallelism Architecture:**
- Tasks grouped into **tiers** by dependency depth
- Tier 0: no dependencies (all run in parallel)
- Tier N: depends on tier N-1 (parallel within tier, sequential across tiers)
- After each tier: branches merge to main before next tier starts
- **Same-tier tasks CANNOT see each other's work** (concurrent execution)
- **Same-tier tasks MUST NOT share files** (write conflicts)

### Task Plan Output Format

```json
{
  "project_type": "SPRING_BOOT",
  "plan_title": "Short project name, 3-8 words",
  "plan_summary": "One sentence describing what plan builds",
  "tasks": [
    {
      "task_id": "snake_case_max_30_chars",
      "title": "Imperative: Create X / Add Y",
      "objective": "Precise description with exact file paths",
      "boundaries": "What NOT to touch",
      "output_format": "Exact deliverable description",
      "acceptance_criteria": "Verifiable commands + expected outcomes",
      "depends_on": ["prior_task_id"],
      "complexity": "LOW|MEDIUM|HIGH",
      "assigned_model": "claude-haiku-4-5-20251001",
      "owned_files": ["relative/path/to/file.java"],
      "shared_files": []
    }
  ]
}
```

### Project Type Detection

Planner declares `project_type` (exactly one): `SPRING_BOOT`, `JAVA`, `NODE_FRONTEND`, `NODE_BACKEND`, `PYTHON`, `GO`, `RUST`, `RUBY`, `PHP`, `FULL_STACK`, `STATIC_HTML`

Written to `.agent-context/project.json` and read by preview/deploy Dockerfile generators.

### Dependency Rules (Enforced)

Dependencies are **mandatory** when:
- Task B writes tests importing Task A's classes
- Task B imports any type/interface/class Task A creates
- Task B modifies/extends a file Task A creates
- Task B is an integration test using other tasks' code

Missing dependencies → file lock conflicts → task `FAILED`.

### Complex Service Split Guidance

Services with >3 public methods should be split into 2-3 MEDIUM tasks by workflow instead of one HIGH task. Example: `OrderService` → lifecycle tasks, payment tasks, query tasks.

---

## 4. Model Routing & Resolution

### Architecture Overview

```
Agent Request
    │
    ▼
┌──────────────┐     ┌──────────────────┐     ┌─────────────────────┐
│ ModelResolver │────►│ ModelMatrix      │     │ ProviderHealthTracker│
│              │     │ (application.yml)│     │ (Redis circuit       │
│ Agent name   │     │                  │     │  breaker)            │
│ + Provider   │     │ Tier × Provider  │     │                     │
│ → Model ID   │     │ → Model string   │     │ 5-failure threshold  │
└──────┬───────┘     └──────────────────┘     │ 60s auto-recovery   │
       │                                       └──────────┬──────────┘
       ▼                                                  │
┌──────────────────────────────────────────────────────────┤
│                    LlmRouter                             │
│                                                          │
│  ┌─────────────────────────────────────────────────┐    │
│  │ Fallback Chain (per provider)                    │    │
│  │                                                  │    │
│  │ Anthropic→[OpenAI, Google]                      │    │
│  │ Google→[OpenAI, Anthropic]                      │    │
│  │ OpenAI→[Google, Anthropic]                      │    │
│  └─────────────────────────────────────────────────┘    │
│                                                          │
│  For each provider in chain:                             │
│    1. Check health (circuit breaker) ◄───────────────────┘
│    2. Check org policy (enabled providers)
│    3. Resolve API key (user → org → system)
│    4. Build provider-specific ChatModel
│    5. Execute call with retry for rate-limits
│    6. Record success/failure to circuit breaker
│                                                          │
└──────────────────────────────────────────────────────────┘
```

### Model Matrix Configuration

**File:** `application.yml` → `model-matrix:`

```yaml
model-matrix:
  default-provider: openai

  providers:
    anthropic:
      planning:    claude-sonnet-4-6
      workhorse:   claude-haiku-4-5-20251001
      lightweight: claude-haiku-4-5-20251001
    google:
      planning:    gemini-2.5-flash
      workhorse:   gemini-2.5-flash
      lightweight: gemini-2.5-flash
    openai:
      planning:    gpt-5.4
      workhorse:   gpt-5.4-mini
      lightweight: gpt-5.4-mini

  agent-tier-map:
    PlannerAgent:             PLANNING
    ReviewerAgentV2:          PLANNING
    WorkerAgent:              WORKHORSE
    CompilerAgent:            WORKHORSE
    TestPlannerAgent:         WORKHORSE
    TestWorkerAgent:          WORKHORSE
    ApiTestSuiteAgent:        WORKHORSE
    ConflictResolverAgent:    WORKHORSE
    PromptOptimiserAgent:     WORKHORSE
    ValidationReportGenerator: WORKHORSE
    ConversationAgent:        LIGHTWEIGHT
    ContextAgent:             LIGHTWEIGHT
    TestReportGenerator:      LIGHTWEIGHT
    RequestTriageService:     LIGHTWEIGHT

  failover-order:
    anthropic: [openai, google]
    google:    [openai, anthropic]
    openai:    [google, anthropic]
```

### ModelResolver

**File:** `router/ModelResolver.java`

**Resolution Algorithm:**

```
resolve(agentName, userProvider):
  1. Map agentName → AgentTier (from agent-tier-map; default WORKHORSE)
  2. Check if userProvider is healthy (ProviderHealthTracker)
  3. If healthy → return matrix[provider][tier]
  4. If unhealthy → try failover-order providers
  5. If all unhealthy → force primary provider anyway
  6. Return ResolvedModel { requestedProvider, actualProvider, model, tier, fallback }
```

**Tier Override:** `resolve(agentName, provider, tierOverride)` — used for stall escalation (WORKHORSE → PLANNING).

### LlmRouter — Central Dispatcher

**File:** `agent/LlmRouter.java`

#### FallbackMode Enum

| Mode | When Used | Behavior |
|------|-----------|----------|
| `IMMEDIATE` | Planner, Reviewer | Always allow fallback on any error |
| `TASK_BOUNDARY` | Worker (iteration 0 or just compacted) | Fallback allowed at task boundary |
| `TASK_BOUNDARY` (mid-task) | Worker (mid-execution) | NO fallback — throw `MidTaskProviderFailureException` (WorkerAgent catches, compacts, and switches) |
| `NO_FALLBACK` | OpenAI worker (has ToolResponseMessages) | Single attempt only |

**Rationale for NO_FALLBACK on OpenAI:** ToolResponseMessage objects in the conversation history are provider-specific. Switching providers mid-conversation would produce invalid message sequences.

**Mid-Task Provider Failure Recovery:** When `MidTaskProviderFailureException` is thrown mid-task, WorkerAgent catches it and:
1. Compacts the conversation (summarizes progress, creates fresh message list)
2. Calls `ModelResolver.resolveFallbackExcluding()` to pick a different provider, excluding the failed one
3. Resumes the agentic loop with the fallback provider and compacted context

#### Call Flow: `callWithFallbackTracked()`

```
For each provider in chain (max 3 attempts):
  1. Skip if circuit breaker OPEN
  2. Skip if org policy disables provider
  3. Build provider-specific ChatOptions (temperature=0.0, maxTokens, cache)
  4. Resolve ChatModel (user key → org key → system singleton)
  5. Execute model.call(prompt)
  6. On success:
     → healthTracker.recordSuccess()
     → Record metrics (tokens, duration)
     → Return LlmResponse { response, provider, model }
  7. On rate-limit (per-minute):
     → Sleep 30s → retry same provider
     → If retry fails → recordFailure() → next provider
  8. On overload (529):
     → Sleep 30s → retry same provider
     → If retry fails → recordFailure() → next provider
  9. On other error:
     → healthTracker.recordFailure()
     → Next provider in chain

All providers exhausted:
  → throw ProviderUnavailableException
```

### ProviderHealthTracker — Redis Circuit Breaker

**File:** `router/ProviderHealthTracker.java`

**Redis Keys:**
```
provider:health:{ANTHROPIC|OPENAI|GOOGLE}    → "UNHEALTHY" (TTL: 60s)
provider:failures:{ANTHROPIC|OPENAI|GOOGLE}  → consecutive count (TTL: 120s)
```

**Thresholds:**

| Parameter | Value |
|-----------|-------|
| Unhealthy threshold | 5 consecutive failures |
| Recovery window | 60s (auto-recovery via TTL expiry) |
| Failure counter TTL | 120s |

**Behavior:**
- `recordSuccess()` → delete health key + failure counter (immediate recovery)
- `recordFailure()` → increment counter; if ≥5 → set UNHEALTHY with 60s TTL
- `isHealthy()` → check health key; Redis errors degrade to `true` (fail-open)

**Auto-Recovery:** After 60s with no calls, the UNHEALTHY key expires. Provider is assumed healthy on next check.

### Provider-Specific Call Configuration

| Provider | Options Class | Max Tokens Field | Cache | Tool Calls |
|----------|--------------|------------------|-------|------------|
| Anthropic | `AnthropicChatOptions` | `maxTokens` | `SYSTEM_ONLY` | Prompt-based XML |
| OpenAI | `OpenAiChatOptions` | `maxCompletionTokens` | N/A | Native function calling |
| Google | `GoogleGenAiChatOptions` | `maxOutputTokens` | N/A | Prompt-based XML |

**Temperature:** 0.0 across all providers (deterministic).

---

## 5. Worker Agent System

### WorkerAgent — The Agentic Loop

**File:** `agent/WorkerAgent.java`

Each worker executes a single task in an isolated git worktree. The worker runs an agentic loop: call LLM → parse tool calls → dispatch tools → check completion → repeat.

### Entry Flow

```
WorkerAgent.execute(task, runId):
  1. Parse TaskDefinition from task JSON
  2. Build dependency branch list
  3. Create isolated git worktree
  4. Apply complexity overrides (TokenBudgetMonitor)
  5. Initialize message history with task assignment
  6. Calculate deadline = now + maxTaskDurationMinutes (25 min)
  7. Enter agentic loop
```

### Agentic Loop

```
while (not timed out AND iterations < safetyIterationCap):
  ┌─ Pre-call checks ─────────────────────────────────────┐
  │  □ Progress checkpoint (every 5 min)                   │
  │  □ Stall detection (warn at 8 min, act at 15 min)     │
  │  □ Time urgency injection (< 2 min remaining)          │
  │  □ Pre-call compaction (messages > 30, context > 80%)  │
  └────────────────────────────────────────────────────────┘
                         │
                         ▼
  ┌─ LLM Call ─────────────────────────────────────────────┐
  │  callModel(messages, provider, model, complexity)       │
  │  → Sliding window: first 2 + last 18 messages          │
  │  → SystemPrompt from worker-{provider}.txt              │
  │  → maxOutput from TokenBudgetMonitor                    │
  └────────────────────────────────────────────────────────┘
                         │
                         ▼
  ┌─ Response Processing ──────────────────────────────────┐
  │                                                         │
  │  Path A: OpenAI Native Tool Calls                       │
  │    → Structured ToolCall objects                         │
  │    → Dispatch each, wrap in ToolResponseMessage          │
  │                                                         │
  │  Path B: Prompt-Based Tool Calls (Anthropic/Google)     │
  │    → Parse XML: <tool_call>{JSON}</tool_call>            │
  │    → Structural fallback for unescaped file content      │
  │    → Dispatch each, wrap results in UserMessage          │
  │                                                         │
  │  Path C: Completion Report                               │
  │    → Response starts with { and contains task_id+status  │
  │    → Anti-fabrication check: claimed files vs actual      │
  │    → Return WorkerReport                                 │
  │                                                         │
  │  Path D: No Tool Calls / No Completion                   │
  │    → Inject instructional prompt to refocus model         │
  └────────────────────────────────────────────────────────┘
                         │
                         ▼
  ┌─ Post-call checks ────────────────────────────────────┐
  │  □ Truncation detection → auto-continue (max 2)        │
  │  □ Skeleton recovery → if continuations exhausted       │
  │  □ Budget status check → COMPACT or HARD_STOP           │
  │  □ Token tracking (input, output, cache)                │
  │  □ Tool call count check (hard limit: 25)               │
  └────────────────────────────────────────────────────────┘
```

### Loop Exit Conditions

| Condition | Status | Trigger |
|-----------|--------|---------|
| Valid completion report JSON | `complete` / `partial` / `blocked` | Response starts with `{`, has `task_id` + `status` |
| Time limit exceeded | `partial` | `Instant.now() > deadline` (25 min) |
| Safety iteration cap | `partial` | Loop counter exceeds config |
| Tool call limit (25) | `partial` | `toolCallCount >= HARD_ITERATION_LIMIT` |
| Context HARD_STOP | `partial` | Token usage ≥ hard-stop threshold after compaction |
| 3 consecutive null responses | `blocked` | LLM returns null 3 times |
| Provider exhaustion | `blocked` | All fallback providers failed |

### Decision Systems

#### A. Progress Checkpointing
- **Trigger:** Every `CHECKPOINT_INTERVAL_MINUTES = 5` minutes
- **Action:** Inject UserMessage with task summary, elapsed time, remaining time
- **Purpose:** Refocus worker on original objective, prevent drift

#### B. Stall Detection & Model Escalation
- **Warn threshold:** 8 minutes with no file writes
- **Act threshold:** 15 minutes with no file writes
- **Stall condition:** `fileWritesSinceCheck == 0` (no write-operations in check period)
- **Decision tree:**

```
IF stalled AND continuations > 0 AND skeletonRecoveries < 2:
  → Inject SKELETON_RECOVERY_PROMPT
ELSE IF stalled:
  → Escalate model to PLANNING tier
```

#### C. Truncation Handling

**Detection:** Check `finishReason` from LLM response metadata:
- Anthropic: `"max_tokens"` → truncated
- OpenAI: `"length"` → truncated
- Gemini: `"MAX_TOKENS"` → truncated

**Recovery cascade:**

```
Truncated?
  ├─ continuations < MAX_CONTINUATIONS (2)
  │   → "Continue EXACTLY where you left off..."
  │   → Retry
  │
  ├─ skeletonRecoveries < MAX_SKELETON_RECOVERIES (2)
  │   → Inject SKELETON_RECOVERY_PROMPT
  │   → "Write MINIMAL SKELETON, then editFile to fill each section"
  │   → Retry with skeleton strategy
  │
  └─ All retries exhausted
      → Force completion as "partial"
```

#### D. Context Compaction
- **Trigger:** Messages > 30 AND estimated input > compact threshold (~80%)
- **Process:**
  1. LLM summarizes: accomplished work, remaining tasks, decisions, stop point
  2. Write checkpoint to `.agent-context/worker-{taskId}-checkpoint.md`
  3. Fresh message list: task definition + summary + current file context
  4. Record compaction event in StateStore

#### E. Completion Report Validation
- Report JSON must start with `{`
- Must contain `"task_id"` and `"status"` fields
- **Anti-fabrication:** If report claims `files_created`/`files_modified` but `totalFileWrites == 0` → reject and force model to use `writeFile`

### Sliding Window Context

```
IF OpenAI OR messages.size() ≤ 20:
  → Send all messages (OpenAI requires contiguous ToolCall ↔ ToolResponse pairs)
ELSE:
  → Send: first 2 messages + last 18 messages (HISTORY_WINDOW = 18)
  → Boundary guard: don't split ToolResponseMessage from prior AssistantMessage
```

### Worker Prompt Key Directives

All 3 worker prompt variants (`worker.txt`, `worker-openai.txt`, `worker-gemini.txt`) are now aligned with shared rules including ROUTER NAMESPACE ISOLATION (avoid route conflicts between tasks) and FULL-STACK START SCRIPT (generate unified start scripts for preview).

**Working Sequence (strict order):**
1. **READ FIRST** — batch all reads; read context files + relevant existing files
2. **WRITE CODE** — complete production-quality code, one file per response if >100 lines
3. **BUILD** — run build command; if fails 3 times → report PARTIAL
4. **TEST** — run tests; fix root cause not symptom; same test fails 3 times → stop
5. **SELF-REVIEW** — verify acceptance criteria, file completeness, DOM consistency
6. **REPORT DONE** — output completion JSON

**Iteration budget:** Target 20 tool calls, hard limit 25 (enforced in all 3 worker prompt variants).

---

## 6. Token Budget & Complexity Management

### TokenBudgetMonitor

**File:** `agent/TokenBudgetMonitor.java`

### Output Token Ceilings

| Complexity | Max Output Tokens | Approximate Lines | Latency at 100 tok/s |
|------------|-------------------|--------------------|-----------------------|
| LOW | 6,144 | ~150 lines | ~60s |
| MEDIUM | 8,192 | ~200 lines | ~80s |
| HIGH | 12,288 | ~300 lines | ~2 min |

These are **soft ceilings** — decoupled from the model's actual max output. If a task needs more, the auto-continue mechanism handles overflow. Ceilings are capped to the model's physical max output via `ModelRegistry.maxOutputForModel()` — prevents silently exceeding what the model can produce (e.g., Haiku max=8192 but HIGH ceiling=12288 would be capped to 8192).

### Complexity Override Rules

**`resolveEffectiveComplexity()`** applies defensive overrides:

```
Input: planner-assigned complexity + model + project type + owned file count

Override 1: Project Type
  IF (STATIC_HTML OR NODE_FRONTEND):
    LOW → upgrade to MEDIUM (4K too small for full-page HTML/CSS/JS)
    HIGH → downgrade to MEDIUM (unnecessary for single-file tasks)

Override 2: Model Max Output
  IF complexity == HIGH AND model max output ≤ 8,192:
    → Downgrade to MEDIUM (ceiling 12,288 exceeds model capacity)

Override 3: File Count
  IF complexity == HIGH AND ownedFilesCount ≤ 2:
    → Downgrade to MEDIUM

Otherwise: keep original
```

**Rationale:** Prevents budget exhaustion on simple tasks misclassified as HIGH, and avoids truncation loops when model max output is below the complexity ceiling.

### Context Window Budget Status

| Status | Threshold | Action |
|--------|-----------|--------|
| `OK` | < ~70% | Continue normally |
| `WARN` | ≥ 70%, < ~80% | Log warning, continue |
| `COMPACT` | ≥ 80%, < ~95% | Trigger context compaction |
| `HARD_STOP` | ≥ 95% | Emergency compaction or forced `partial` exit |

**Headroom bonus:** HIGH complexity tasks get +5% on all thresholds.

### Context Window Limits

| Provider | Context Limit (Floor) |
|----------|----------------------|
| Anthropic | 200,000 tokens |
| OpenAI | 200,000 tokens |
| Google | 200,000 tokens |

All floored to 200K for consistency across providers, even though some support larger windows.

### Model Max Output Limits

| Model | Max Output |
|-------|-----------|
| Claude Sonnet 4 | 64,000 |
| Claude Haiku 4.5 | 8,192 |
| GPT-5.4 | 16,384 |
| GPT-5.4-mini | 16,384 |
| Gemini 2.5 Flash | 24,576 |

---

## 7. Tool System & Dispatch

### Available Tools (9)

| Tool | Aliases | Arguments | Result Truncation |
|------|---------|-----------|-------------------|
| `readFile` | `read_file` | `path` | 8,000 chars |
| `writeFile` | `write_file` | `path`, `content` | Unlimited |
| `editFile` | `edit_file` | `path`, `oldStr`, `newStr` | Unlimited |
| `listFiles` | `list_files` | `path` (default ".") | 8,000 chars |
| `searchFiles` | `search_files` | `pattern`, `directory` | 8,000 chars |
| `run` | `run_command` | `command`, `timeoutSeconds` (default 120) | 6,000 chars |
| `status` | `git_status` | (none) | Unlimited |
| `diff` | `git_diff` | (none) | 5,000 chars |
| `commit` | `git_commit` | `message` | Unlimited |

### Tool Call Format

**Anthropic/Google (XML):**
```xml
<tool_call>{"name": "writeFile", "args": {"path": "src/main.js", "content": "..."}}</tool_call>
```

**OpenAI (Native):**
Structured `AssistantMessage.ToolCall` objects via Spring AI function calling.

### XML Parsing with Structural Fallback

Standard path: regex `<tool_call>(.*?)</tool_call>` → JSON parse

**Structural fallback** (when JSON fails due to unescaped file content in writeFile/editFile):
1. Extract `"name"` via regex
2. For writeFile: extract `path` (simple string), `content` (content-aware scanner)
3. For editFile: extract `path`, `oldStr`, `newStr` using boundary detection
4. Rebuild proper JSON args

### Result Truncation Strategy

```
IF result.length() > limit:
  head = result[0 .. limit - tail_size]
  tail = result[result.length - tail_size .. end]    (tail_size = min(1500, limit/4))
  result = head + "\n[...N chars omitted...]\n" + tail
```

### Tool Suggestion for Unknown Tools

When LLM hallucinates tool names, suggest correct tool:
- `gitcommit`, `git_add` → `commit`
- `runbuild`, `build` → `run`
- `createfile`, `touch` → `writeFile`
- `cat`, `viewfile` → `readFile`
- `ls`, `dir` → `listFiles`

### File Ownership & Locking

- Planner declares `owned_files` per task
- Harness pre-claims files before workers start
- `writeFile`/`editFile` auto-check lock before writing
- If locked by another task → return error message (tool doesn't throw)
- Lock state persisted in `.agent-context/file-locks.json` (readable by agents)
- Max 3 automatic retries on lock failure; then report `BLOCKED`

---

## 8. Git Worktree Isolation

### Problem

Multiple worker tasks execute concurrently. Without isolation, concurrent `git checkout` operations on different branches would corrupt the working tree.

### Solution: Per-Task Git Worktrees

**File:** `tool/GitTool.java`

Each task gets a dedicated git worktree — a separate directory with its own working tree, linked to the main repository's `.git` directory.

```
{workspace-root}/{runId}/
  ├── .git/                          ← main repo
  ├── worktrees/
  │   ├── task_a/                    ← worktree for task A
  │   │   ├── .git (file, not dir)   ← points to main .git/worktrees/task_a
  │   │   ├── src/
  │   │   └── ...
  │   ├── task_b/                    ← worktree for task B
  │   └── task_c/                    ← worktree for task C
  └── src/                           ← main working tree (merge target)
```

### Worktree Creation Flow

```
WorkerAgent.execute()
  → GitTool.createWorktree(runId, taskId, branchName, depBranches)
    1. Create directory: {repoRoot}/worktrees/{taskId}/
    2. Run: git worktree add {path} [-b {branchName}] {baseRef}
    3. Merge dependency branches: git merge --no-edit -X theirs {depBranch}
    4. Return absolute path
  → WorkspaceContext.set(runId, worktreePath)
    └─ ThreadLocal override for all file/git operations
```

### Branch Naming Convention

```
agent/{runId-prefix}/{taskId}

Examples:
  agent/a1b2c3d4/fetch_user_service
  agent/a1b2c3d4/update_database_schema
```

### GitHub Integration

For existing-repo runs, after COMPLETE:
- `PipelineHarness.pushToGitHubIfConfigured()` creates branch `executespec/{runIdShort}`
- Pushes to the branch (not main) via `GitTool.pushToBranch()`
- Opens a PR via `GitHubRepoService.createPullRequest()` against the default branch
- PR URL stored in `PipelineRun.githubPrUrl` and surfaced in RunDetail API response and frontend overview tab

### Thread-Local Context Propagation

**File:** `tool/WorkspaceContext.java`

All file/git operations are redirected to the worktree via ThreadLocal:

```java
// Before worker loop
WorkspaceContext.set(runId, "/absolute/path/to/worktree");

// During operations — all tools resolve paths against worktree
String workDir = WorkspaceContext.getWorkDir(runId);

// After worker completes (finally block)
WorkspaceContext.clear();
```

Micrometer `ContextSnapshotFactory` ensures propagation across virtual thread boundaries.

### Why Shell Git for Worktrees

JGit's `Repository.getWorkTree()` on a linked worktree resolves to the **main repo root**, not the worktree directory. This causes `git add`, `git status`, and `git diff` to operate on the wrong tree. Shell git handles worktree `.git` files correctly.

### Worktree Cleanup

- After each task merges → `GitTool.removeWorktree(runId, taskId)` (non-fatal)
- After all workers complete → `GitTool.pruneAllWorktrees(runId)` + `git worktree prune`

---

## 9. Progressive Merge & Conflict Resolution

### Progressive Merge Strategy

Each task branch merges to main **immediately after the worker completes**, not in batch. Benefits:
- Early conflict detection
- Dependencies see merged work from previous tasks
- Clear per-task merge attribution

### Tiered Parallel Execution with Sequential Merge

```
Tier 0: Tasks A, B, C run in parallel
         ↓ (all complete)
         Merge A → main (under lock)
         Merge B → main (under lock)
         Merge C → main (under lock)

Tier 1: Tasks D, E run in parallel (can see tier 0's merged work)
         ↓ (all complete)
         Merge D → main (under lock)
         Merge E → main (under lock)
```

**Lock:** `synchronized (mergeLock)` — only one thread merges at a time. Lock held only for merge duration (~100ms-1s).

### Pre-Merge Worktree Commit

Before merging, auto-commit any uncommitted files in the worktree:

```
1. git status --porcelain (detect dirty state)
2. If dirty: git add -A && git commit -m "Task {taskId} — auto-commit"
3. If clean: skip
```

### Four-Tier Conflict Resolution Cascade

When `git merge` detects conflicts:

```
Tier 1: SpecializedMerger (deterministic, $0)
  ├─ PomXmlMerger    — union dependencies by groupId:artifactId
  ├─ YamlMerger      — deep-merge maps, union lists
  └─ PackageJsonMerger — union deps/devDeps/scripts

  If resolved → apply fix, continue merge
  If conflict in values → fall through to Tier 2

Tier 2: ConflictResolverAgent (WORKHORSE tier, low cost)
  Input: base version + main version + branch version + task descriptions
  Output: merged file content
  Temperature: 0.0, maxTokens: 8192

  If resolved → apply, continue
  If null/blank → fall through to Tier 3

Tier 3: ConflictResolverAgent (PLANNING tier escalation)
  Same input, PLANNING tier model

  If resolved → apply, continue
  If failed → fall through to Tier 4

Tier 4: FAILED
  → Task marked FAILED
  → User retries task via UI
```

### Conflict Analytics

Every conflict (resolved or failed) is recorded in `MergeConflict` entity:

```java
MergeConflict {
  runId, taskId, filePath,
  conflictType: "TEXTUAL",
  resolution: "DETERMINISTIC" | "AUTO_WORKHORSE" | "AUTO_PLANNING" | "FAILED",
  resolverModel, resolvedAt
}
```

`MergeConflictAnalyser` builds historical reports and feeds conflict patterns back to the planner prompt — teaching it to avoid problematic file ownership assignments.

---

## 10. Compilation & Auto-Fix

### CompilerAgent

**File:** `compiler/CompilerAgent.java`

Runs after workers complete. Non-blocking: always returns a result, never throws.

### Flow

```
1. Detect build tool:
   pom.xml → Maven | build.gradle → Gradle | package.json → npm
   Not found → skip compilation

2. Compilation loop (up to maxAttempts):
   Run build command
   If passes → return CompileResult.passed()
   If fails:
     a. Truncate errors dynamically based on file count (`maxErrorCharsForSse`: ≤3 files→2000, ≤6→1200, else 800 chars)
     b. Scope to MAX_COMPILER_FILES (10) per pass
        Priority: models → repos → services → controllers → configs → tests
     c. Extract relevant source files
     d. Call LLM with errors + source context
     e. Parse JSON fix array: [{"path": "...", "content": "..."}]
     f. Apply fixes to filesystem
     g. Retry compilation

3. All attempts exhausted → return CompileResult.failed()
```

### Error Scoping

When many files have errors, the compiler limits context to 10 files per pass, prioritized by:
1. Models/Entities
2. Repositories
3. Services
4. Controllers
5. Configs
6. Tests
7. Other

Remaining files fixed in subsequent retry passes.

---

## 11. Code Review & Auto-Fix

### ReviewerAgentV2

**File:** `reviewer/ReviewerAgentV2.java`

Two-phase agentic reviewer. PRODUCTION mode only. Runs in background validation thread after COMPLETE.

### Phase 1: Inspection

- Model tier: PLANNING
- Max tool calls: 50 (generous — reviewer reads many files)
- Tool set: read-only (`readFile`, `listFiles` only)
- Timeout: 15 minutes
- Output: JSON array of `ReviewIssue` objects (severity, file, problem, expected)

**README Gate:** If `README.md` doesn't exist or is <50 bytes → issue raised. Severity is softened for small runs: taskCount ≤ 5 → MINOR severity (instead of CRITICAL).

### Phase 2: Auto-Fix

For each issue (CRITICAL first, then MINOR):
1. Create transient `AgentTask` with fix instruction
2. Delegate to `WorkerAgent.execute()` for implementation
3. Validate fix via `CodeValidator.validateFile()` (TYPE level)
4. If validation passes → commit: `"Auto-fix: {ID} — {description}"`
5. Max 2 attempts per issue
6. Always fix CRITICAL issues even past timeout; skip MINOR if time is up

### Verdict Determination

```
if (unfixedCritical > 0)    → NEEDS_FIXES
if (unfixedMinor > 0)       → APPROVED_WITH_NOTES
if (no issues OR all fixed)  → APPROVED
```

Note: Missing README is tracked as a review issue (CRITICAL for large runs, MINOR for ≤5 tasks) and goes through auto-fix like any other issue. It is no longer a hard REJECTED gate.

### Code Validator

**File:** `validator/CodeValidatorImpl.java`

Pluggable validation via language-specific toolchains. Gate chain pattern: SYNTAX → TYPE → LINT → TEST (fails fast on first failure).

If language toolchain unavailable → graceful skip (returns pass).

---

## 12. Test Generation & Execution

### TestGenOrchestrator

**File:** `testgen/TestGenOrchestrator.java`

Runs on virtual thread in the background validation thread, parallel with code review. Generates language-agnostic tests (not limited to Java/JUnit).

### Pipeline

```
1. Detect project type (CLI, Static HTML, REST API, frontend, etc.)
2. Scaffold test project appropriate for detected language/framework
3. Generate API conventions document
4. Plan tests (TestPlannerAgent → TestPlan with TestTasks)
5. Generate test classes sequentially
6. Compilation gate:
   First attempt → compile
   If fails → retry failed tasks with error context
   If still fails → mark surviving tests as COMPILED
7. Publish TestsReadyEvent
```

### TestPreviewCoordinator

**File:** `testgen/TestPreviewCoordinator.java`

Coordinates convergence of two independent signals:
1. **Tests compiled** (from TestGenOrchestrator)
2. **Preview ready** (from PreviewService)

Tests execute only when BOTH signals arrive. Reuses the existing preview started at COMPLETE (does not start a new one).

```
Signal 1: onTestsReady(event)
  → Check for existing running preview (reuse if available)
  → Start preview on virtual thread only if needed
  → Mark testsReady = true

Signal 2: onPreviewReady(runId, url)
  → Mark previewReady = true
  → tryExecute(): if both ready → launch test execution

tryExecute():
  → testExecutionService.executeTests(runId, testPlanId, previewUrl)
  → Publish TestExecutionCompletedEvent
```

**Timeout safety:** PipelineHarness uses 10-minute CompletableFuture timeout as safety net.

### TestExecutionService

**File:** `testgen/TestExecutionService.java`

```
1. Build test command appropriate for detected framework
2. Execute with 120s timeout
3. Parse test reports (format depends on framework)
4. Detect connection-refused pattern (preview crashed)
5. Return TestExecutionSummary { total, passed, failed, skipped, errors }
```

---

## 13. Validation Report

### ValidationReportService

**File:** `report/ValidationReportService.java`

Aggregates all pipeline outputs into a comprehensive report.

### Input Sources

| Source | Data |
|--------|------|
| WorkerAgent reports | Files created/modified per task |
| CompilerAgent | Pass/fail + attempt count |
| ReviewerAgentV2 | Issues + auto-fix status + verdict |
| TestExecutionService | Test results + summary |

### Pipeline Result Computation

```
compilation failed                     → FAILED
no issues at all                       → PASSED
all review issues auto-fixed           → PASSED_WITH_FIXES
unresolved CRITICAL issues             → FAILED
MINOR unresolved or test failures      → NEEDS_ATTENTION
```

### Issue Extraction

1. **From Reviewer:** Severity mapping (CRITICAL → WOULD_CAUSE_BUG, MINOR → WOULD_FAIL_REVIEW)
2. **From Compiler:** WOULD_BREAK_BUILD issues
3. **From Tests:** Group failures by root cause signature:
   - HTTP status codes (`HTTP 500`)
   - Connection errors (`connection refused`, `timeout`)
   - Java exceptions (`NullPointerException`)
   - Assertion mismatches
   - If ≥3 tests share same signature → collapse into single root-cause issue

### Report Content

- LLM-generated markdown summary (WORKHORSE tier — ValidationReportGenerator)
- Structured issues with severity and fix status
- Checks passed list (COMPILATION, CODE_REVIEW, INTEGRATION_TESTS)
- Files generated count
- Languages detected
- Shareable via unique token (32-byte secure random, Base64-URL encoded)

---

## 14. API Key Resolution & Multi-Tenancy

### Three-Level Key Hierarchy

```
Priority 1: User-level keys (app_user table)
Priority 2: Organization-level keys (org table)
Priority 3: System keys (application.yml singletons)
```

### Key Source Modes

| Mode | Behavior |
|------|----------|
| `SYSTEM` | Force all keys to null; always use system singletons |
| `OWN_KEYS` | User/org keys only; null = provider unavailable (no system fallback) |
| `HYBRID` (default) | Try user/org keys first; fall back to system singleton if null |

### Per-Run Key Caching

**File:** `agent/RunKeyStore.java`

```
Run start:  apiKeyResolver.resolve(userId) → runKeyStore.put(runId, keys)
During run: llmRouter → runKeyStore.get(runId) → resolve ChatModel
Run end:    runKeyStore.remove(runId)
```

`ConcurrentHashMap` — safe for concurrent worker tasks and multiple runs.

### Provider Enablement

Org admins can whitelist providers: `enabledProviders = {"anthropic", "google"}`
- Only whitelisted providers are attempted
- null = all providers allowed

---

## 15. Cost Estimation

### CostEstimator

**File:** `agent/CostEstimator.java`

### Pre-Run Estimate

Computed after planning, shown at approval gate:

```
Total = Worker cost + Planner cost + Reviewer cost + Compiler cost + Test-gen cost

Per-task worker cost:
  LOW:    10K input, 2K output, 1-3 iterations
  MEDIUM: 25K input, 4K output, 2-4 iterations
  HIGH:   60K input, 8K output, 3-6 iterations

Per-provider pricing from model_pricing DB table
```

### Post-Run Actual Cost

```
cost = (normalInputTokens / 1M) × inputRate
     + (cacheWriteTokens / 1M)  × inputRate × 1.25
     + (cacheReadTokens / 1M)   × inputRate × 0.10
     + (outputTokens / 1M)      × outputRate
```

Anthropic cache pricing:
- Cache writes: 1.25x input rate (storage charge)
- Cache reads: 0.10x input rate (90% discount)

---

## 16. Observability & Metrics

### LLM Call Metrics

```
executespec.llm.calls          (counter: provider, model)
executespec.llm.duration       (timer: provider, model)
executespec.llm.tokens.input   (counter: provider, model)
executespec.llm.tokens.output  (counter: provider, model)
executespec.llm.retry.count    (counter: provider, model)
executespec.llm.errors         (counter: provider, error_type)
executespec.llm.fallback       (counter: from, to)
```

### Provider Health Metrics

```
executespec.provider.circuit_opened  (counter: provider)
executespec.provider.circuit_closed  (counter: provider)
executespec.routing.failover         (counter: from, to, agent)
```

### Pipeline Metrics

```
executespec.tier               (observation span: run.id, tier.index)
executespec.task               (observation span: task.id, complexity, outcome)
executespec.report.generated   (counter: result)
executespec.report.issues_caught (counter)
executespec.report.generation_duration (timer)
validation.total               (counter: result, gate)
```

### Calibration Tracking (per task)

| Column | Purpose |
|--------|---------|
| `was_truncated` | Whether task hit output token truncation |
| `max_output_tokens` | Configured maxOutput from TokenBudgetMonitor |
| `actual_output_tokens` | Total output tokens actually produced |
| `iteration_count` | Number of agentic loop iterations |

Stored in `ModelTaskMetric` entity for future data-driven routing optimization.

### Real-Time UI Updates

SSE (Server-Sent Events) via `SseBroadcaster`:
- `taskStatus` — per-task progress
- `log` — pipeline phase logs
- `gateOpened` / `gateResolved` — approval gate events

---

## 17. Security & Sandboxing

> Full security requirements: [docs/requirements/security-guardrails.md](requirements/security-guardrails.md)

ExecuteSpec uses 8 independent security layers. No single layer failing results in full compromise.

### Layer 1: Request Validation

Rate limiting via Redis-backed per-tenant `RateLimitFilter`. Feature flags via DB-backed `FeatureFlagService` with Redis pub/sub cache invalidation.

### Layer 2: Input Scanning (InputSanitizer)

Called in `RunController.submit()` before pipeline submission:
- **26 regex patterns** across 3 categories: prompt injection (11), shell embedding (6), abuse (9)
- **Unicode NFKC normalization** before pattern matching — collapses homoglyphs (Cyrillic `a` → Latin `a`)
- **Obfuscation detection:** >60% non-printable chars → reject; 50-char substring repeated >20 times → reject
- Returns HTTP 400 on violation

### Layer 3: LLM Prompt Defense

- **Planner:** User spec wrapped in `<user_request>` tags + explicit "untrusted input" warning
- **Worker (all 3 prompts):** Mandatory SECURITY section listing blocked categories; report `BLOCKED` on violation
- **Tool result tagging:** All results wrapped in `<tool_result tool="..." untrusted="true">` — workers instructed to never follow instructions inside tool results (second-order injection defense)

### Layer 4: Code Abuse Scanning (BLOCKING)

`CodeAbuseScanner` runs on every `writeFile()` — **rejects** files matching malware patterns:
- Crypto mining: `stratum+tcp://`, `xmrig`, `coinhive`, `cryptonight`, `minergate`, `ethminer`
- Reverse shells: `bash -i >& /dev/tcp/`, `nc -e /bin/`, `socat exec`, python socket+dup2, `mkfifo+nc`
- Data exfiltration: `curl -d @`, `curl --data-binary @`, `wget --post-file`, `fetch(document.cookie)`
- Obfuscated payloads: `eval(atob(...))`, `eval(Buffer.from(...))`, base64 strings >500 chars

### Layer 5: Shell Command Sandbox

Three-layer validation + chained-command splitting:

1. **Subshell blocking:** `$(...)` and backtick expansion rejected outright
2. **Explicit blocklist (23 commands):** `rm -rf /`, `mkfs`, `reboot`, `chmod -R 777`, `useradd`, `nc -l`, fork bombs, etc.
3. **Pattern blocklist (43 regexes):** `curl | bash`, Docker escape (`docker run/exec`, `--privileged`), mining pools, reverse shells, data exfil uploads
4. **Chained-command splitting:** Commands split on `&&`, `||`, `;`, `|` (respecting quotes). Each segment validated against the allowlist independently.
5. **Allowlist (32 prefixes):** `mvn`, `npm`, `node`, `git`, `cat`, `ls`, `python`, `docker` (6 safe subcommands only), etc.
6. **Absolute path block:** `cd /` blocked; `cd ..` allowed for worktree navigation

### Layer 6: Path Sandboxing

- All file paths sandboxed to run's workspace directory
- `safeResolve()` normalizes and validates paths don't escape workspace
- Hidden directories: `worktrees/`, `target/`, `build/`, `node_modules/`, `.git/`
- Sensitive files (`.env`, `credentials.json`, `key.json`) → log warning (not blocked — agents legitimately create these)

### Layer 7: Environment Variable Stripping

Child processes have secrets removed:
- `GITHUB_PAT`, `POSTGRES_PASSWORD`
- `SPRING_AI_ANTHROPIC_API_KEY`, `ANTHROPIC_API_KEY`
- `SPRING_AI_OPENAI_API_KEY`, `OPENAI_API_KEY`
- `GOOGLE_API_KEY`
- `JIRA_API_TOKEN`, `CONFLUENCE_API_TOKEN`
- `SLACK_CLIENT_SECRET`, `SLACK_SIGNING_SECRET`
- `RAZORPAY_KEY_SECRET`, `STRIPE_SECRET_KEY`

### Layer 8: Container Image Hardening

`.dockerignore` excludes secrets from both preview and deploy images:
`.env`, `.env.*`, `credentials.json`, `service-account.json`, `key.json`, `keyfile.json`, `secrets.yml`, `secrets.yaml`

### Provider Policy Enforcement

`LlmRouter` respects org provider restrictions on ALL paths — including the forced-primary fallback when all providers are circuit-open. Throws `ProviderUnavailableException` if the primary is policy-blocked.

---

## 18. Key Thresholds & Constants

### Time-Based

| Constant | Value | Purpose |
|----------|-------|---------|
| Task duration | 25 min (configurable) | Hard deadline per task |
| Checkpoint interval | 5 min | Re-inject task context |
| Urgency threshold | 2 min | Force completion prompt |
| Stall warn | 8 min | Warn about no file writes |
| Stall act | 15 min | Escalate model on no file writes |
| Gate TTL | 2 hours | Redis expiration for approval gates |
| Gate poll interval | 500ms | Redis polling frequency |
| Preview TTL | 5 min | Auto-stop preview after TTL |
| Test execution timeout | 120s | Test process timeout |
| Validation report timeout | 10 min | CompletableFuture safety net |

### Iteration-Based

| Constant | Value | Purpose |
|----------|-------|---------|
| Safety iteration cap | configurable | Max agentic loop iterations |
| Hard tool limit | 25 | Max tool calls before forced completion |
| Truncation continuations | 2 | Auto-continue attempts |
| Skeleton recoveries | 2 | Skeleton-then-fill strategy attempts |
| Sliding window | 18 | Context tail (non-OpenAI) |
| Consecutive null max | 3 | Null responses before abort |
| Planner retry | 3 | JSON parse retry attempts |
| Planner max tasks | 35 | Truncation limit |
| Compiler max files | 10 | Per-pass error scoping |
| Reviewer max tool calls | 50 | Inspection loop limit |
| Reviewer fix attempts | 2 | Per-issue retry limit |

### Token Budgets

| Complexity | Max Output | Headroom Bonus |
|------------|-----------|----------------|
| LOW | 6,144 | — |
| MEDIUM | 8,192 | — |
| HIGH | 12,288 | +5% on context thresholds |

### Circuit Breaker

| Parameter | Value |
|-----------|-------|
| Failure threshold | 5 consecutive |
| Recovery window | 60s (Redis TTL) |
| Failure counter TTL | 120s |

### Result Truncation

| Tool | Max Chars |
|------|-----------|
| readFile, listFiles, searchFiles | 8,000 |
| run, run_command | 6,000 |
| git_diff | 5,000 |
| writeFile, editFile, commit | Unlimited |

### Concurrency

| Parameter | Default | Purpose |
|-----------|---------|---------|
| maxRuns | 50 | Semaphore slots for concurrent pipelines |
| maxTaskDurationMinutes | 25 | VT join timeout + 2-min grace |
| mergeLock | synchronized | Sequential merges within tier |

---

## Appendix A: File Locations

| Component | Path |
|-----------|------|
| Pipeline orchestrator | `harness/PipelineHarness.java` |
| Task executor | `harness/SequentialTaskExecutor.java` |
| Run cost budget | `harness/RunCostBudgetService.java` |
| Merge service | `harness/GitMergeService.java` |
| Specialized mergers | `harness/merge/{PomXml,Yaml,PackageJson}Merger.java` |
| Conflict analyser | `harness/merge/MergeConflictAnalyser.java` |
| Worker agent | `agent/WorkerAgent.java` |
| Planner agent | `agent/PlannerAgent.java` |
| LLM router | `agent/LlmRouter.java` |
| Token budget | `agent/TokenBudgetMonitor.java` |
| Output parser | `agent/LlmOutputParser.java` |
| API key resolver | `agent/ApiKeyResolver.java` |
| Run key store | `agent/RunKeyStore.java` |
| Cost estimator | `agent/CostEstimator.java` |
| Model resolver | `router/ModelResolver.java` |
| Model matrix config | `router/ModelMatrixProperties.java` |
| Health tracker | `router/ProviderHealthTracker.java` |
| Task metric entity | `router/ModelTaskMetric.java` |
| Compiler agent | `compiler/CompilerAgent.java` |
| Reviewer agent V2 | `reviewer/ReviewerAgentV2.java` |
| Code validator | `validator/CodeValidatorImpl.java` |
| Test orchestrator | `testgen/TestGenOrchestrator.java` |
| Test coordinator | `testgen/TestPreviewCoordinator.java` |
| Test execution | `testgen/TestExecutionService.java` |
| Validation report | `report/ValidationReportService.java` |
| Report generator | `report/ValidationReportGenerator.java` |
| Git tool | `tool/GitTool.java` |
| Workspace context | `tool/WorkspaceContext.java` |
| File system tool | `tool/FileSystemTool.java` |
| Shell tool | `tool/ShellTool.java` |
| State store | `state/StateStore.java` |
| SSE broadcaster | `state/SseBroadcaster.java` |
| Security config | `auth/security/SecurityConfig.java` |
| Security properties | `config/SecurityProperties.java` |
| JWT utility | `auth/security/JwtUtil.java` |
| OAuth2 handler | `auth/oauth/OAuthSuccessHandler.java` |
| Feature flags | `config/featureflag/FeatureFlagService.java` |
| Rate limiting | `ratelimit/RateLimitFilter.java` |
| Input sanitizer | `security/InputSanitizer.java` |
| Code abuse scanner | `security/CodeAbuseScanner.java` |
| Request triage | `agent/RequestTriageService.java` |
| Billing service | `billing/service/BillingService.java` |
| Payment gateway router | `billing/gateway/PaymentGatewayRouter.java` |
| GitHub repo service | `integration/github/GitHubRepoService.java` |
| Integration manager | `integration/IntegrationManager.java` (listens for MergeCompleted) |
| Planner prompt | `resources/prompts/planner.txt` |
| Worker prompt (Anthropic) | `resources/prompts/worker.txt` |
| Worker prompt (OpenAI) | `resources/prompts/worker-openai.txt` |
| Worker prompt (Gemini) | `resources/prompts/worker-gemini.txt` |
| DB migrations | `resources/db/migration/V*.sql` (V1-V56) |
| App config | `resources/application.yml` |

## Appendix B: Data Flow Diagram

```
                    ┌─────────────┐
                    │  User       │
                    │  Request    │
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │ InputSanitizer│──► RateLimitFilter (Redis per-tenant)
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │ ApiKeyResolver│──► RunKeyStore (ConcurrentHashMap)
                    └──────┬──────┘           │
                           │                  │ (per-run key lookup)
                    ┌──────▼──────┐           │
                    │ PlannerAgent │──► ModelResolver ──► ModelMatrix
                    │ (PLANNING)   │           │              │
                    └──────┬──────┘    ProviderHealth    application.yml
                           │           Tracker (Redis)
                    ┌──────▼──────┐
                    │ CostEstimator│──► Plan Approval Gate (Redis)
                    └──────┬──────┘
                           │
              ┌────────────▼────────────┐
              │  SequentialTaskExecutor  │
              │                         │
              │  ┌───────┐ ┌───────┐   │
              │  │Worker │ │Worker │   │  Each worker:
              │  │Agent A│ │Agent B│   │    → GitTool.createWorktree()
              │  └───┬───┘ └───┬───┘   │    → WorkspaceContext (ThreadLocal)
              │      │         │        │    → LlmRouter.callWithFallbackTracked()
              │      ▼         ▼        │    → Tool dispatch (9 tools)
              │  mergeLock (sequential) │    → TokenBudgetMonitor checks
              │      │                  │    → Truncation/stall handling
              └──────┼──────────────────┘
                     │
                     ▼
               CompilerAgent + GitMerge
                     │
                     ▼
               ★ COMPLETE ★
                     │
         ┌───────────┼───────────────────┐
         ▼           ▼                   ▼
   Auto-Preview   GitHub PR      Background Validation
   (5-min TTL)   (if configured)  (virtual thread)
                                         │
                              ┌──────────┼──────────┐
                              ▼          ▼          ▼
                        Reviewer    TestGen    MergeCompleted
                        AgentV2    Orchestrator   Event
                              │          │          │
                              ▼          ▼          ▼
                        Auto-Fix   TestPreview   JIRA/Confluence
                              │    Coordinator   Slack notifications
                              │          │
                              ▼          ▼
                   ┌─────────────────────────────────┐
                   │    ValidationReportService       │
                   │    (aggregates all outputs)      │
                   └─────────────────────────────────┘
```
