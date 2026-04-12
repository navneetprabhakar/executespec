# Harness V2 — Execution Plan

> Generated: 2026-04-12
> Context: Post exhaustive eval (mean accuracy 56.5%). V2 is a full replacement of V1, built in a separate package tree for clean separation.
> Guiding principle: **Accuracy first. Parallelism only after 90%+ baseline.**

---

## 0. Branch & Workflow

**Branch:** All V2 work happens on a dedicated feature branch `feature/harness-v2` cut from `dev`.

- Branch created from latest `dev` at phase start
- Sub-branches per phase: `feature/harness-v2-p1-tools`, `feature/harness-v2-p2-router`, etc., merged into `feature/harness-v2` via PR
- No direct commits to `feature/harness-v2` — all changes via sub-branch PRs with review
- `feature/harness-v2` merged to `dev` only after:
  - All 10 phases complete
  - Eval suite passes success criteria (Section 7)
  - Documentation updates (Section 11) complete
- `dev` → `main` follows standard release process

**No commits to `dev` or `main` during V2 development** except for critical V1 bug fixes (cherry-picked into `feature/harness-v2` to stay current).

---

## 1. Design Principles

Derived from Part 3 competitor analysis:

1. **Sequential-first execution** — one agent, one context, full codebase visibility. Parallelism deferred until accuracy baseline is established.
2. **Native tool calling everywhere** — no XML parsing, no PATH B, no self-play defense. Structured tool schemas for all three providers.
3. **Ground truth during execution** — smoke tests after every task merge. Don't wait for post-hoc validation to learn the build is broken.
4. **Minimal coordination surface** — no SubtaskDecomposer, no FileLockTool, no ownership walls. Sequential execution removes the need.
5. **Checkpointed context** — compacted agent messages persisted to Redis with 24h TTL. Crash recovery + retry-with-context.
6. **Diff-first edits** — `editFile` becomes the primary write tool; `writeFile` reserved for new files only.

---

## 2. Package Structure

```
com.navneet.executespec.v2.harness/       — PipelineHarnessV2, SequentialExecutorV2, TaskSupervisorV2
com.navneet.executespec.v2.agent/         — WorkerAgentV2, PlannerAgentV2, LlmRouterV2, PromptAssemblerV2
com.navneet.executespec.v2.tool/          — ToolRegistryV2, FileSystemToolV2, ShellToolV2, GitToolV2, BuildToolV2
com.navneet.executespec.v2.compiler/      — CompilerAgentV2
com.navneet.executespec.v2.reviewer/      — ReviewerAgentV2Impl (new impl; interface stays)
com.navneet.executespec.v2.batch/         — BatchClientRouterV2, BatchResumeServiceV2
com.navneet.executespec.v2.context/       — ContextStoreV2 (Redis-backed message persistence)
com.navneet.executespec.v2.smoketest/     — SmokeTestRunner, BuildCommandResolver
```

**Shared with V1 (imported, not duplicated):**
- Entities (`AgentTask`, `Run`, `PromptAudit`, etc.)
- Repositories (`RunRepository`, `TaskRepository`, etc.)
- DTOs (requests/responses, SSE event types)
- Auth, billing, rate limiting, feature flags, observability
- Deployment, preview, testgen (V2 reuses existing preview lifecycle)
- Security layer (`InputSanitizer`, `CodeAbuseScanner`, `ShellSandboxPolicy`)

**V1 remains untouched.** Feature flag `harness.version=v2` routes runs to V2. V1 deprecated once V2 reaches accuracy parity, removed once V2 exceeds it.

---

## 3. Phases

### Phase 0 — Prerequisites

**P0.1 Spring AI upgrade**
- Upgrade from 1.1.0 to the version that supports `GoogleGenAiChatOptions.toolCallbacks()` (target: latest 1.x or 2.x that has Gemini native tool calling)
- Verify Anthropic and OpenAI tool calling still work on new version
- Upgrade `AgentModelConfig`, `LlmRouter` (V1 stays working)
- Test with a standalone API at `/api/v2/test/native-tools?provider={anthropic|openai|gemini}` that calls each provider with a single tool definition and asserts a structured response

> **Naming convention:** V2 uses `gemini` consistently as the provider name (instead of V1's `google`). The Spring AI library classes are still named `GoogleGenAi*` (we can't rename library code), but every V2-authored identifier — variable, switch case, config key, log tag, metric tag, REST parameter — uses `gemini`.

**P0.2 Redis schema for context persistence**
- Define key pattern: `v2:agent-ctx:{runId}:{taskId}` → JSON blob
- Define retention: `EXPIRE` set to 86400 (24h) on every write
- No Flyway migration needed (Redis-only)

**Deliverable:** Spring AI upgraded, all three providers confirmed native-tool-capable via test API.

---

### Phase 1 — V2 Tool Layer

**P1.1 ToolRegistryV2**
- Central registry of tool definitions with JSON Schema
- Each tool implements a `Tool` interface: `name()`, `schema()`, `execute(JsonNode args, ExecutionContext ctx)`
- Schema uses `additionalProperties: false`, all required fields declared (strict mode compliant)

**P1.2 Tool implementations**

| Tool | Purpose | Key Parameters |
|------|---------|----------------|
| `readFile` | Read file with optional line range | `path`, `startLine?`, `endLine?` |
| `writeFile` | Create NEW file only (errors if file exists) | `path`, `content` |
| `editFile` | Multi-edit existing file | `path`, `edits: [{oldStr, newStr}]` |
| `listFiles` | List directory, supports recursive + glob | `path`, `recursive?`, `pattern?` |
| `searchFiles` | Native content search (no shell subprocess) | `pattern`, `directory?`, `filePattern?` |
| `shell` | Sandboxed shell (same as V1) | `command`, `timeoutSeconds?` |
| `gitStatus` | Git status | — |
| `gitDiff` | Git diff | — |
| `gitCommit` | Stage + commit | `message` |
| `buildProject` | **NEW** — runs detected build command, returns pass/fail + structured errors | `scope?` (full/changed-only) |

**P1.3 Tool invariants**
- No FileLockTool (sequential execution = no contention)
- Result truncation: same limits as V1 (5k file, 4k shell, 3k diff)
- Dedup cache per-task (keyed by `tool+args`)
- All results typed as `ToolResult.Ok(content) | ToolResult.Err(message)` — errors are first-class, forcing model to handle them

**P1.4 BuildCommandResolver**
- Resolution order: (a) planner-declared build command in plan JSON, (b) inferred from `project.json` / detected frameworks (npm → `npm run build`, maven → `mvn compile -q`, gradle → `./gradlew build`, cargo → `cargo build`, etc.)
- Returns `BuildCommand { command, timeoutSeconds, successPattern?, failurePattern? }`

**Deliverable:** Tool layer with 10 tools, all with strict JSON schemas, all returning typed results.

---

### Phase 2 — V2 LLM Router (Native-Only)

**P2.1 LlmRouterV2** (from scratch)
- No PATH B. No XML parsing. No `FABRICATED_TOOL_RESULT_PATTERN`. No `worker-gemini.txt`/`worker-openai.txt`/`worker.txt` divergence.
- Single prompt file: `prompts/v2/worker.md` (provider-agnostic)
- Native tool calling for all three providers via Spring AI's `ToolCallback` + `ToolCallingChatOptions`
- Retains V1 features: multi-provider fallback, per-run API key resolution, tier-based routing, `ProviderHealthTracker` (Redis circuit breaker), Caffeine caches
- `internalToolExecutionEnabled(false)` — harness keeps loop control (needed for checkpointing and smoke tests)

**P2.2 Failover strategy**
- Same as V1 (Anthropic→[OpenAI, Gemini], etc.)
- Stall escalation: WORKHORSE → PLANNING tier on iteration stall

**Deliverable:** LlmRouterV2 with verified native tool calling on all three providers.

---

### Phase 3 — V2 Worker Agent

**P3.1 WorkerAgentV2**
- Sequential single-agent execution loop
- Native tool calling only (no XML parsing code path exists)
- Iteration cap: 40 (was 28 for MEDIUM, compensates for no SubtaskDecomposer)
- Completion detection: worker outputs terminal JSON `{"status": "complete", "summary": "..."}` via a structured tool call (`completeTask` tool), not raw JSON heuristics
- Per-iteration: dispatch tool call → execute → truncate → append result → check budget → continue or complete

**P3.2 Context strategy (hybrid)**
- **Upfront fill:** at task start, inject file tree + planner-declared relevant files (ownedFiles + dependencyFiles) into initial context. Eliminates cold-start readFile bursts.
- **Selective reads during execution:** agent can still readFile for files it discovers.
- **Compaction:** when approaching 75% of context window, compact older tool results — replace raw file content with summary ("read `foo.ts` (420 lines, contains: class X, function Y, ...)"), keep all write/edit results intact.
- **Message persistence:** every 5 iterations or on compaction event, write current compacted context to Redis (`v2:agent-ctx:{runId}:{taskId}`, TTL 86400s).

**P3.3 Prompt guidance (replaces SubtaskDecomposer)**
- Worker prompt includes: "Implement one file at a time. After writing each file, verify it's complete and correct before moving to the next. For multi-file tasks, finish the foundational file first (types, interfaces, schema) before files that depend on it."

**P3.4 Smoke test integration**
- Worker can proactively call `buildProject` during execution (to self-verify)
- Harness calls `buildProject` automatically after task merge (mandatory)

**Deliverable:** WorkerAgentV2 that runs a task end-to-end with native tools, checkpointed context, and no XML parsing.

---

### Phase 4 — V2 Planner

**P4.1 PlannerAgentV2**
- Same conceptual output (task list with ownedFiles, dependencies, complexity)
- New additions to plan JSON:
  - `buildCommand?: string` — explicit build command declaration
  - `smokeTestStrategy: "build" | "preview-health" | "test-suite"` — how to verify each task
  - `projectType` (already exists, moved into plan)
- Task cap per classification:
  - SINGLE_SHOT: 1 synthetic task (auto-generated, no planner call)
  - MEDIUM: ≤ 4 tasks
  - MULTI_TASK: no cap
- Per-task HARD_CONSTRAINTS block injected into worker prompt (e.g., "Do NOT use JPA; use JDBC directly")
- No subtask decomposition at any layer

**P4.2 Plan gate (unchanged)**
- User still approves plan. Billing charged at approval (credits calculated from `CostEstimator`).
- Gate UI shows: task list, cost estimate, batch vs real-time option, smoke test strategy
- Real-time vs batch choice preserved

**Deliverable:** PlannerAgentV2 producing plans with build commands, smoke test strategy, and per-task constraints.

---

### Phase 5 — V2 Sequential Executor

**P5.1 SequentialExecutorV2**
- Executes tasks one at a time on a single worktree (main workspace, no per-task worktrees — no parallelism means no isolation needed)
- For each task:
  1. Load prior agent context from Redis if exists (retry path)
  2. Invoke WorkerAgentV2 with upfront context fill
  3. On completion: `gitCommit` → merge to main (trivial — no branch)
  4. Run `buildProject` smoke test
  5. If smoke test fails:
     - **Attempt 1:** retry task with build error injected into worker prompt
     - **Attempt 2 (if retry fails):** mark task `COMPLETED_WITH_WARNING`, continue to next task (deferred to reviewer phase)
  6. Publish `TaskMergedEvent` (SSE)
- No file locking, no dependency graph execution, no parallelism

**P5.2 TaskSupervisorV2** (replaces `TaskCompileSupervisor`)
- Triages smoke test failures: `CASCADING | FIXABLE | STRUCTURAL`
- Actions: `RETRY_TASK | CONTINUE_WITH_WARNING | ABORT_RUN`
- Rule-based fast path + PLANNING-tier LLM fallback (same pattern as V1)
- `ABORT_RUN` only for catastrophic failures (e.g., `package.json` syntax error after 2 retries)

**P5.3 CompilerAgentV2**
- Runs after all tasks complete
- Full-project compile + static analysis
- `applyTargetedFix()` pattern retained (owned-file-scoped fixes)

**Deliverable:** End-to-end sequential execution with smoke tests, retry logic, and no parallel coordination.

---

### Phase 6 — V2 Batch Execution

**P6.1 BatchClientRouterV2**
- Same three providers (Anthropic, OpenAI, Gemini) with native batch APIs
- Reuses existing `LlmBatchClient` implementations (minor refactor to consume V2 tool schemas)

**P6.2 BatchResumeServiceV2**
- Key difference from V1: V2 processes batch results **sequentially** (not parallel)
- For each task result in plan order:
  1. Parse result, write files (via FileSystemToolV2)
  2. Merge to main
  3. Run smoke test (synchronous — blocks next task if fails)
  4. On smoke test failure: retry failed task in real-time (same as V1 but now with smoke test validation)
- Publishes `BatchCompleteEvent` only after all tasks sequentially validated

**P6.3 Batch polling**
- Reuse existing `BatchPollService` infrastructure (`@SchedulerLock`, 30s poll)
- New V2-specific event types to avoid V1 listener interference

**Deliverable:** Batch execution with sequential result processing and smoke test validation.

---

### Phase 7 — V2 Reviewer & Validation Report

**P7.1 ReviewerAgentV2Impl**
- Same interface contract, new implementation
- Inspection phase: full-project review (the sequential execution already caught most issues via smoke tests)
- **New: wiring-specific sub-phase** — explicit checks for `mod.rs`, barrel imports, route registration, DI wiring
- Auto-fix phase: identical pattern to V1

**P7.2 TestGen & TestPreviewCoordinator**
- No changes needed — V2 reuses existing implementations (they're decoupled from pipeline internals)

**P7.3 Validation report**
- No structural changes
- Add field: `smokeTestHistory: [{taskId, attempt, passed, errorSummary?}]` — visibility into smoke test outcomes per task

**Deliverable:** Reviewer catches residual issues with a dedicated wiring phase; validation report shows smoke test history.

---

### Phase 8 — Context Persistence & Recovery

**P8.1 ContextStoreV2**
- Redis-backed
- Keys: `v2:agent-ctx:{runId}:{taskId}` (compacted message list, 24h TTL)
- Writes: every 5 iterations + on compaction + on crash-prone ops (before shell, before build)
- Reads: on task retry, on run resume after crash

**P8.2 Crash recovery**
- `StartupRunResumerV2` (re-enabled from V1's disabled state)
- On boot: find runs in IN_PROGRESS state, load context from Redis, resume sequential execution from last checkpointed task
- If context missing (TTL expired): mark run `FAILED_UNRECOVERABLE`, refund credits

**Deliverable:** Runs survive harness crashes and task retries without restarting from iteration 0.

---

### Phase 9 — Routing & Feature Flag

**P9.1 Feature flag**
- `harness.version` (values: `v1` | `v2` | `v2-canary`)
- `v2-canary`: route a % of runs to V2 based on `FeatureFlagService.getPercentage()`
- Flag check happens in `/api/runs` POST before any pipeline dispatch

**P9.2 Migration pattern**
- **Day 1:** V2 behind flag, 0% rollout. Internal testing only.
- **Day N:** Canary 5% → 25% → 50% → 100% based on accuracy metrics.
- **Day N+M:** V1 code deleted once V2 is 100% and stable for 2 weeks.

**Deliverable:** Safe rollout mechanism with instant rollback to V1.

---

### Phase 10 — Observability

**P10.1 Metrics (V2-specific)**
- `executespec.v2.task.smoketest.outcome` tagged `{pass, fail, retry_success, warning}`
- `executespec.v2.task.iterations` (histogram)
- `executespec.v2.context.compaction.count`
- `executespec.v2.context.persisted.bytes`
- `executespec.v2.worker.tool.calls` tagged by tool name
- `executespec.v2.recovery.resumed` count

**P10.2 Comparison dashboard**
- Grafana: side-by-side V1 vs V2 accuracy, cost, time, token usage
- SQL views: `run_v1_stats` vs `run_v2_stats` for pricing analytics

**Deliverable:** V2 observability stack + V1/V2 comparison dashboard.

---

## 4. Testing Strategy

### 4.1 Unit tests
- ToolRegistryV2 schema validation
- BuildCommandResolver inference for each framework
- ContextStoreV2 serialization/deserialization
- TaskSupervisorV2 triage rules

### 4.2 Integration tests
- `/api/v2/test/native-tools` — per-provider native tool calling smoke test
- End-to-end: submit → plan → approve → execute → smoke test → complete for each classification (SINGLE_SHOT, MEDIUM, MULTI_TASK)
- Batch execution end-to-end for each provider
- Crash recovery: kill process mid-task, restart, verify run resumes

### 4.3 Eval suite
- Re-run the exhaustive eval corpus on V2
- Gate V1→V2 cutover on: accuracy ≥ V1 baseline + 10pp, cost ≤ V1 baseline, time ≤ V1 baseline × 1.3
- Publish comparative report

---

## 5. Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Spring AI upgrade breaks OpenAI/Anthropic tool calling | Run native-tools test API on all three providers before proceeding |
| Sequential execution too slow for MULTI_TASK | Measure. If problematic, revisit parallel for verified-independent tasks in a later phase (post-90% accuracy) |
| Redis context storage grows unbounded | 24h TTL enforced; monitor `redis_memory_used_bytes`; add eviction policy if needed |
| Smoke test false positives halt valid runs | RETRY + CONTINUE_WITH_WARNING pattern; never ABORT on first smoke test failure |
| Plan gate UX confusing with smoke test strategy field | Default to "build"; only surface in UI if user clicks advanced |
| SubtaskDecomposer removal causes iteration exhaustion | Iteration cap raised to 40; prompt guidance ("one file at a time"); monitor `task.iterations` p95 |

---

## 6. Out of Scope for V2 (Deferred)

- Vector store for file retrieval (OpenAI Assistants pattern) — `readFile` is more accurate for code
- Gemini-style full-context-fill for large projects — hybrid approach is sufficient
- Parallel execution for verified-independent MULTI_TASK specs — revisit after accuracy baseline
- Changes to auth, billing, rate limiting, security layers
- Changes to preview/deploy infrastructure
- Changes to existing-repo run handling (same as V1)

---

## 7. Success Criteria

V2 replaces V1 when:
1. Accuracy ≥ 85% on exhaustive eval corpus (vs V1's 56.5% baseline)
2. Cost per run ≤ V1 (target: −30% from reduced readFile redundancy and editFile diffs)
3. Time to completion ≤ V1 × 1.3 (sequential is slower; 30% overhead acceptable for accuracy gain)
4. Zero PATH B code paths, zero XML parsing, zero FABRICATED_TOOL_RESULT_PATTERN
5. Crash recovery verified: kill mid-run, resume, complete successfully
6. All three providers verified native-tool-compliant

---

## 8. Documentation Updates

Documentation is a first-class deliverable of V2. The following must be updated before `feature/harness-v2` merges to `dev`:

### 8.1 CLAUDE.md (project root)
- Update **Architecture** section: multi-agent pipeline → sequential pipeline (with V2-specific flow diagram)
- Update **Key File Locations**: add V2 package entries, mark V1 entries as `(deprecated, removed post-V2 cutover)`
- Update **Patterns: MUST Follow**: remove "Sliding window for LLM context" (replaced by hybrid upfront fill + compaction), add "Message persistence: every 5 iterations to Redis with 24h TTL"
- Update **Patterns: MUST NOT Use**: add "XML `<tool_call>` parsing (V2 is native-only)", "FileLockTool (V2 is sequential)", "SubtaskDecomposer (V2 writes sequentially in-task)"
- Update **Current State → Working**: add V2 entries. Update **Known Tech Debt**: remove entries resolved by V2 (PATH B, FABRICATED_TOOL_RESULT_PATTERN, etc.)
- Update the `Last updated` header date

### 8.2 New documentation files
- `docs/harness-v2-architecture.md` — architecture reference for V2 (sequential flow, tool layer, context strategy, smoke test lifecycle)
- `docs/harness-v2-migration-guide.md` — V1→V2 routing, feature flag usage, rollback procedure
- `docs/harness-v2-tool-reference.md` — detailed reference for each V2 tool (name, schema, behavior, errors, examples)
- `docs/harness-v2-observability.md` — V2-specific metrics, Grafana dashboard pointers, log query examples

### 8.3 Existing docs to update
- `docs/harness-analysis-part3-competitors.md` — add "Resolution" section at the bottom referencing which V2 phase addresses each competitor gap
- `README.md` (if present at repo root) — update harness version references
- Prompt fragment docs in `resources/prompts/fragments/` — add V2-specific fragments (if any), flag V1-only fragments

### 8.4 API documentation
- Add OpenAPI/Swagger entries for `/api/v2/test/native-tools` (provider validation API)
- Document new plan JSON fields (`buildCommand`, `smokeTestStrategy`, `hardConstraints`)
- Document validation report additions (`smokeTestHistory`)

### 8.5 Runbook additions
- Crash recovery playbook: how to check `StartupRunResumerV2` behavior, how to inspect Redis context store, how to manually resume a stuck run
- Rollback runbook: flipping `harness.version` flag to roll back from V2 to V1 mid-incident

### 8.6 Code-level documentation
- Javadoc on every new V2 public class (class-level purpose + invariants)
- Package-info.java for each `com.navneet.executespec.v2.*` package summarizing responsibility
- Inline comments on non-obvious logic (context compaction algorithm, smoke test retry decision tree)

**Documentation phase scheduling:** documentation updates happen in-phase (not as a final catch-up) — each phase's PR must include the relevant doc updates before merge.

### 8.7 Post-completion plan reconciliation

Once V2 execution completes, this execution plan document (`docs/harness-v2-execution-plan.md`) itself must be updated to reflect actual outcomes:

- Mark each phase as `✅ Completed` / `⚠️ Partially completed (with notes)` / `❌ Descoped` with commit/PR references
- Capture deviations from the original plan: tasks that were added, removed, re-scoped, or re-sequenced mid-execution
- Update Section 7 (Success Criteria) with actual measured numbers (accuracy, cost, time) from the post-cutover eval
- Update Section 5 (Risks & Mitigations) — mark which risks materialized and how they were handled; add any unforeseen risks encountered
- Rename document to `docs/harness-v2-completed.md` (or similar) and add a forward-pointing note in any remaining references
- Archive the document as a historical record — future harness redesigns (V3+) reference it for lessons learned

---

## 9. Phase Ordering Summary

```
Phase 0  Prerequisites (Spring AI upgrade + Redis schema)
Phase 1  Tool layer (ToolRegistryV2, BuildCommandResolver)
Phase 2  LLM router (native-only, all 3 providers)
Phase 3  Worker agent (sequential, checkpointed, hybrid context)
Phase 4  Planner (build command + smoke test strategy + hard constraints)
Phase 5  Sequential executor (+ TaskSupervisorV2 + CompilerAgentV2)
Phase 6  Batch execution (sequential result processing)
Phase 7  Reviewer (with wiring phase) + report updates
Phase 8  Context persistence + crash recovery
Phase 9  Feature flag rollout
Phase 10 Observability + V1/V2 comparison
```

Each phase is independently testable. Phases 1–4 have no external dependencies. Phases 5–8 depend on 1–4. Phases 9–10 are cross-cutting.
