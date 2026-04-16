# Deep-Dive RCA — 4 Bugs Exposed by Provider Evaluation

> Evaluation date: 2026-04-16
> Context: cross-provider evaluation (6 runs × 3 providers × 2 modes) revealed 4 platform bugs. 2 killed 2 Project runs outright. 1 killed a Pro run. 1 silently violates the provider promise for every Project-mode Launch.

---

## Bug #1 — Planner silently chooses provider regardless of user's request

### Symptom

A user creates a Project with `aiProvider=GEMINI`, submits a Launch. The platform planner:
1. Calls OpenAI first
2. Falls through to Gemini if OpenAI fails
3. Never calls Anthropic or honors the user's selection

Observed across all 3 Project-mode executions in the eval (`bb30973e`, `85ec86fa`, `731a9dfb`) regardless of the provider the user selected.

### Root cause

**Two compounding issues:**

**1a. Hardcoded fallback seed** — `ExecutionPlannerService.java:91`:
```java
ResolvedModel resolved = modelResolver.resolve("PlannerAgentV2", LlmProvider.OPENAI);
```
The second argument is the **initial** provider to try. It is hardcoded to `OPENAI`, ignoring `ExecutionRun.aiProvider`. This is the bug — it should be:
```java
LlmProvider requested = LlmProvider.valueOf(run.getAiProvider() != null ? run.getAiProvider() : "ANTHROPIC");
ResolvedModel resolved = modelResolver.resolve("PlannerAgentV2", requested);
```

**1b. `FallbackMode.IMMEDIATE` means "on any error, switch providers"** — `ExecutionPlannerService.java:97-100`:
```java
llmRouter.callWithFallback(
    resolved.providerName(), resolved.getModel(), prompt,
    "exec-plan-" + executionRunId, "execution-planner",
    LlmRouter.FallbackMode.IMMEDIATE, 0, false);
```
The router walks a **chain** defined in `model-matrix:` (`application.yml`). For CAPABLE tier (planner), the chain is `[anthropic, openai, google]`. Once a provider fails, the router tries the next one silently. The user has no signal that the run switched providers mid-call.

### Impact

- **Correctness**: Every Project Launch the user thinks is using Anthropic or Gemini is actually being planned by OpenAI (if available). This affects:
  - Cost accounting (OpenAI is more expensive than Gemini)
  - Regulatory / data-residency posture (a customer on a Gemini-only-approved stack is silently calling OpenAI)
  - A/B testing invalidation — any cross-provider eval using this flow is meaningless
- **Observability**: No SSE event, no UI warning, no log at WARN level tells the user their provider was overridden

### Recommended action

**Priority: P0** (breaks a core user promise)

1. Change `ExecutionPlannerService.java:91` to read `run.getAiProvider()` instead of hardcoding `LlmProvider.OPENAI`.
2. Change `FallbackMode.IMMEDIATE` → `FallbackMode.NO_FALLBACK` for the planner path (the planner is a single-step call; fallback doesn't save the run, it misleads the user). If fallback is desired, emit an SSE `EXEC_PROVIDER_SWITCHED` event so the UI can show a banner.
3. Add an integration test: submit an ExecutionRun with `aiProvider=GEMINI`, stub OpenAI to throw, assert that the failure propagates (NOT silent fallback).

**Estimated effort**: ~3 hours (including the test).

---

## Bug #2 — DB constraint violation aborts ~50% of Project Launches

### Symptom

```
ExecutionPlannerService run=85ec86fa-... failed: could not execute statement
[ERROR: duplicate key value violates unique constraint "execution_solution_dependency_pkey"
```

Observed on 2 of 3 Project runs (66% abort rate during this eval).

### Root cause

`ExecutionPlannerService.java:220-227`:
```java
for (DependencyEdge edge : plan.dependencyEdges()) {
    ExecutionSolution to = bySeq.get(edge.to());
    ExecutionSolution from = bySeq.get(edge.from());
    if (to == null || from == null) continue;
    if (to.getDependencies() == null) to.setDependencies(new HashSet<>());
    to.getDependencies().add(from);       // ← in-memory dedup via Set
    solutionRepo.save(to);                // ← but each save flushes to DB
}
```

When the LLM returns a duplicate edge (e.g. `[{from:1, to:2}, {from:1, to:2}]`), the in-memory `HashSet` silently drops the duplicate — but **each call to `solutionRepo.save(to)`** triggers a fresh Hibernate flush that tries to INSERT the same row twice. The composite PK `(solution_id, depends_on_id)` on `execution_solution_dependency` (V89 migration) rejects the second insert and aborts the transaction, which aborts the entire planning step.

### Why LLMs emit duplicate edges

The planner prompt asks for a JSON list of dependency edges. LLMs routinely return the same edge twice, especially when multiple solutions share a common ancestor (e.g., a BE solution has FE solutions both depending on it — the LLM may list the BE→FE edge twice, once under each FE context). No downstream code defends against this.

### Impact

- **Availability**: 2 of 3 Project Launches submitted during this eval aborted at planning. If this repros at scale, ~50-66% of Project Launches fail silently before workers even start.
- **Cost**: zero cost (abort happens before workers), but user experience is terrible — they get ABORTED status with a generic `failureReason` and no way to retry without the bug hitting again.

### Recommended action

**Priority: P0** (availability bug)

Dedupe edges before the loop:
```java
Set<String> seen = new HashSet<>();
for (DependencyEdge edge : plan.dependencyEdges()) {
    String key = edge.from() + "->" + edge.to();
    if (!seen.add(key)) continue;   // skip duplicate edge
    ExecutionSolution to = bySeq.get(edge.to());
    // ...
}
```
Also batch the saves at the end instead of per-edge:
```java
for (DependencyEdge edge : dedupedEdges) { ... }
bySeq.values().forEach(solutionRepo::save);   // single flush at the end
```

**Estimated effort**: ~1 hour. Add a unit test that feeds `[{1,2},{1,2},{2,3}]` and asserts the final dependency set is `{(1,2),(2,3)}`.

---

## Bug #3 — Gemini 2.5-flash emits Python-style tool calls

### Symptom

Pro-Gemini FAILED immediately after planning:
```
IllegalStateException: Planner LLM did not call submitPlan. Finish reason: MALFORMED_FUNCTION_CALL;
text preview: [Gemini terminated with finishReason=MALFORMED_FUNCTION_CALL;
detail: Malformed function call: print(default_api.submitPlan(planTitle="..."))
```

### Root cause

Gemini 2.5-flash is a known offender — under some prompt conditions it emits the tool call as a Python snippet (`print(default_api.submitPlan(...))`) instead of a proper `functionCall` object in the JSON response. Google's `MALFORMED_FUNCTION_CALL` finish reason is the API's way of saying "I tried to call a function but the SDK couldn't parse it."

The codebase already implements a mitigation:
- `GeminiNativeToolClient.java:247-267` — sets `toolConfig.functionCallingConfig.mode=ANY` + `allowedFunctionNames=[...]` when `request.forceToolCall()==true`
- This should force Gemini to emit a well-formed tool call

The gate for `forceToolCall` is at `LlmRouterV2.java:162`:
```java
boolean forceToolCall = r.toolsOverride() != null && !r.toolsOverride().isEmpty();
```

And `PlannerAgentV2` DOES pass `toolsOverride(List.of(submitPlan))`. So `forceToolCall` should be `true` — but the observed behavior says it's NOT taking effect. Possible causes:
- The tool array is being cleared somewhere in the router before reaching the Gemini client
- The `mode=ANY` directive reaches Gemini but Gemini 2.5-flash ignores it (known flakiness)
- The `allowedFunctionNames` list is incorrectly populated

### Impact

- Gemini is effectively unusable as a planner for ExecuteSpec. Every Gemini run fails at step 1.
- Wider scope: any single-tool-forced agent (planner, reviewer's submitReview, test gen's submitTestPlan) on Gemini 2.5-flash hits the same risk.
- Users can't price-optimize by picking Gemini (the cheapest provider) because the path is broken.

### Recommended action

**Priority: P1** (user-selectable provider is partially broken)

Three-layer fix:
1. **Short-term** — switch the Gemini planner model from `gemini-2.5-flash` to `gemini-3-flash-preview` (the V2 matrix in CLAUDE.md already lists this as the PLANNING tier Gemini model; verify it's actually being used).
2. **Medium-term** — add a text-salvage fallback in `PlannerAgentV2.parsePlanFromArgs()`: if `MALFORMED_FUNCTION_CALL` and the `textPreview` contains `submitPlan(`, regex-extract the Python-style args and JSON-parse them.
3. **Long-term** — audit all `forceToolCall` sites. Verify in a test that a round-trip from PlannerAgentV2 through LlmRouterV2 to GeminiNativeToolClient preserves `toolsOverride` and emits `mode=ANY`.

**Estimated effort**: ~6 hours (1 short-term + 2 medium + 3 audit & test).

---

## Bug #4 — Silent fallback masks provider failures

### Symptom

A user submits a run with `aiProvider=ANTHROPIC`. Anthropic is down (circuit breaker opens, or a transient error fires). The run completes successfully using OpenAI instead. **The user is never told.**

### Root cause

`LlmRouter.callWithFallback(mode=IMMEDIATE)` walks a provider chain on any error. Fallback is:
- **Silent** — no SSE event, no ERROR log at the user's run level
- **Cached** — `ProviderHealthTracker` (Redis-backed, 10-failure threshold, 180s recovery) can hold a provider "unhealthy" for minutes, affecting runs the user never consented to

This is a broader architectural issue that Bug #1 revealed — the fallback mechanism is a *convenience* from the platform's perspective but a *correctness violation* from the user's perspective.

### Impact

- **Cost attribution**: a run tagged `aiProvider=gemini` that actually ran on OpenAI burns OpenAI tokens but attributes them to the user's Gemini budget
- **Audit / compliance**: regulated customers who need "this run used only provider X" cannot trust the metadata
- **Experimentation**: A/B tests across providers are invalid — the platform may have silently substituted a different provider

### Recommended action

**Priority: P1** (part architecture, part policy)

Three parts:
1. **Immediate** (aligned with Bug #1) — for the Project planner path, use `FallbackMode.NO_FALLBACK`. The planner is fast (one call) and failure there is recoverable by the user (retry with a different provider).
2. **Short-term** — add SSE event `RUN_PROVIDER_SWITCHED` with `{requested, actual, reason}` so the UI can render a "⚠ fell back to OpenAI" banner.
3. **Medium-term** — add a tenant-level setting `strict_provider_required: true` that makes ALL LlmRouter calls treat that org's runs as `NO_FALLBACK`. Trips a 402/500 if the requested provider isn't available. Default OFF for backwards compat.

**Estimated effort**: ~8 hours (1 immediate + 3 SSE + 4 tenant setting + tests).

---

## Cross-cutting action plan (priority order)

| # | Bug | Priority | Effort | Ship |
|---|---|---|---|---|
| 1 | Dedupe dependency edges (Bug #2) | P0 | 1 hr | Week 1 |
| 2 | Honour requested provider in ExecutionPlannerService (Bug #1a) | P0 | 2 hr | Week 1 |
| 3 | Switch planner to `FallbackMode.NO_FALLBACK` (Bug #1b) | P0 | 30 min | Week 1 |
| 4 | Gemini planner: switch to `gemini-3-flash-preview` + verify toolsOverride reaches client (Bug #3) | P1 | 6 hr | Week 2 |
| 5 | Add `RUN_PROVIDER_SWITCHED` SSE event + UI banner (Bug #4) | P1 | 3 hr | Week 2 |
| 6 | Text-salvage fallback for Gemini MALFORMED_FUNCTION_CALL (Bug #3 layer 2) | P2 | 2 hr | Week 3 |
| 7 | Tenant-level `strict_provider_required` setting (Bug #4 layer 2) | P2 | 4 hr | Week 3 |
| 8 | Integration tests across provider matrix with forced failures | P2 | 8 hr | Week 3 |

**Total P0 effort: ~3.5 hours.** Can ship same-day to unblock cross-provider correctness.

**Total P0+P1+P2 effort: ~26.5 hours** = one focused sprint.

---

## Test strategy for the fixes

After each fix lands, re-run this same 6-run evaluation matrix (3 providers × 2 modes) and verify:
- All 6 runs reach terminal status (no aborts)
- Per-run `aiProvider` exactly matches the provider that made the LLM calls (via log grep)
- Cost + token accounting is consistent with the logged provider
- If a provider is forced to fail (inject error), the error propagates to the user — no silent substitution
