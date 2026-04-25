# Hierarchical Planner — design plan

> Status: in-flight on `feature/hierarchical-planner`
> Author: 2026-04-25 working session
> Replaces: single-shot `PlannerAgentV2` truncation-prone path

## Why

Single-shot planning emits an entire `submitPlan` tool-call as one LLM
response. Output tokens are bounded; the cap was 4096 (v2.5.13) bumped to
8192. For multi-repo / legacy specs (e.g. "AI business-card scanning across
api + web + mobile" — 2957 chars, 3 repos) the JSON exceeds 8192 tokens →
truncated mid-string → JSON parse fails → `planner returned empty plan` →
run fails on phase 1 with no recovery path.

Bumping the cap kicks the wall further out, doesn't remove it. ENTERPRISE
+ legacy monorepo customers will saturate any fixed budget. We need a
planner shape that scales by **decomposition + parallelism**, not by
buying bigger LLM output windows.

## Architecture

Four phases. Phases 1-3 are gated by the strategy chosen in phase 0; small
specs collapse to phase 1 only or skip planning entirely.

```
PHASE 0 — Smart Classification          (≤1 LIGHTWEIGHT call, ≤500 tokens out)
PHASE 1 — Outline planner                (1 PLANNING call, ≤6k out)
PHASE 2 — Detail expansion               (N parallel WORKHORSE calls, ≤3k each)
PHASE 3 — Coherence pass                 (1 LIGHTWEIGHT call, ≤1k out)
```

### Phase 0 — Smart Classification (always-on, LLM-augmented from day 1)

Inputs:
- `spec` (the user's `RunRequest.userRequest`)
- `selectedRepos` (count + githubRepoFullName values)
- `projectMetadata` (project type, last 3 ledger entries, file count from
  codemap if available)

Logic:
1. **Heuristic fast path.** If the spec is unambiguously tiny —
   `len ≤ 150 chars` AND `repos.length == 1` AND no multi-feature
   keywords (`across`, `for each`, `all repos`, `system-wide`) AND no
   domain-explosive verbs (`stripe`, `auth`, `multi-tenant`, `rbac`,
   `real-time`, `migrate`, `refactor`) — return `SINGLE_SHOT` with no LLM
   call. Threshold tunable via runtime config
   `planner.classifier.heuristic-fast-path-char-cap` (default 150).
2. **LLM verifier.** Otherwise invoke a LIGHTWEIGHT-tier
   `SmartClassifierAgent` with the heuristic verdict as a hint and the
   project context. The LLM returns `{ strategy, taskCount,
   repoTouchCount, requiresEpics, reasoning }`.
3. **Cache.** Hash `(spec + sorted-repoIds + projectId)` with SHA-256, key
   `planner:classify:{hash}` in Redis, 1h TTL. Re-submission within the
   window reuses the verdict.
4. **Fallback.** If LLM call times out (8s), JSON parse fails, or strategy
   isn't in enum — fall back to heuristic verdict. Never block a run on
   classifier outage.

Strategy values:

| Strategy | When | Phases run |
|---|---|---|
| `SINGLE_SHOT` | already a single coherent task | synthesised plan, no LLM |
| `SMALL` | ≤6 cohesive tasks, 1 repo | phase 1 only (32k cap) |
| `MEDIUM` | 7-15 tasks, 1-2 repos, no cross-cutting | phase 1 + phase 3 |
| `LARGE` | 15-50 tasks OR ≥3 repos OR cross-cutting | phase 1 + phase 2 (parallel) + phase 3 |
| `LEGACY` | >50 tasks expected OR domain-explosive scope | phase 1 + phase 2 (recursive depth 2) + phase 3 (strict) |

Persistence:
- `pipeline_run.plan_strategy` — final verdict
- `pipeline_run.classifier_reasoning` — LLM's one-liner reason (for offline
  calibration: heuristic v LLM v outcome)
- `pipeline_run.classifier_input_tokens / classifier_output_tokens / classifier_cost_usd`

Cost: ~$0.001-0.003 per call on Haiku/Flash. Latency: 0.7-3s p50/p95.
Heuristic fast path: 0ms, $0.

### Phase 1 — Outline planner

`OutlinePlannerAgent` (PLANNING tier) emits task **skeletons** via a
`submitOutline` tool call. Skeleton shape:

```json
{
  "id": "T1",
  "title": "Implement Google Vision integration in pplai-api",
  "complexity": "HIGH",
  "repos": ["pplai-api"],
  "dependsOn": [],
  "type": "LEAF",
  "requirementsCovered": ["R1", "R2"]
}
```

Plus `requirements[]` graph (R1..Rn) and global `hardConstraints[]`.

Phase 1 output is bounded — no full descriptions, no `touchesFiles[]`, no
`hardConstraints[]` per task. The whole outline for a 50-task plan is
typically ≤4k tokens.

Persistence:
- `pipeline_run.plan_outline_json` — JSONB
- `pipeline_run.outline_input_tokens / outline_output_tokens / outline_cost_usd`
- `agent_task` rows created with `status = PENDING`,
  `task_type = LEAF | EPIC`, `expansion_status = PENDING`

For `SMALL` strategy, this phase emits a **full plan** in one call (32k
cap, includes descriptions and constraints) — collapsing phases 1 + 2.
Skips phase 3.

### Phase 2 — Detail expansion (parallel)

For each `LEAF` task in the outline, fire a WORKHORSE-tier
`TaskExpanderAgent` call returning the task body:

```json
{
  "taskId": "T1",
  "description": "...",
  "touchesFiles": [...],
  "hardConstraints": [...],
  "meetsRequirements": [...],
  "smokeTestStrategy": "BUILD"
}
```

For each `EPIC` task: fire a recursive outline planner call (depth ≤ 2)
that returns child tasks. Child tasks get expanded the same way.

Concurrency: bounded via existing pipeline executor pool (4 threads).
For a 50-task plan, runs in ~12-15s wall-clock vs 90s+ serial.

Persistence:
- Per-task tokens written to `agent_task.input_tokens / output_tokens /
  cost_usd` (existing columns)
- `agent_task.expansion_status = EXPANDED | FAILED`
- `agent_task.expanded_at = now()`
- Aggregate roll-up to `pipeline_run.expansion_input_tokens / output / cost`

If a single task expansion fails, mark just that task `expansion_status =
FAILED` and continue with the rest. Coherence pass detects + recovers.

### Phase 3 — Coherence pass

`CoherencePlannerAgent` (LIGHTWEIGHT tier) reads the full assembled plan
and emits a `patchPlan` tool call if it finds violations:

- `dependsOn` referencing a non-existent task ID
- Two tasks with conflicting `hardConstraints` (e.g. one says "use cannon-es",
  another says "use ammo.js" for the same physics surface)
- Requirement coverage gaps — a requirement with no task citing it
- Build commands incompatible across the project

Patches are minimal (a delta, not a rewrite). If `patchPlan` itself fails
JSON validation, log + use unpatched plan (degraded but functional).

Persistence:
- `pipeline_run.coherence_input_tokens / output / cost`

## Cost rollup

**Every** phase's LLM call goes through `LlmRouterV2.call(...)` →
`V2RunCostTracker.record(...)` (this already exists, no rewiring needed).
The tracker's `byRun` accumulator captures all tokens regardless of which
agent fired — so `pipeline_run.totalInputTokens / totalOutputTokens /
actualCostUsd` automatically reflect the sum of all four phases.

Per-phase columns (new) give the dashboard a breakdown:

```
Run ce69750d-d68b-4ca8-b8f8-1311b4381639
  Strategy   LARGE
  Classifier $0.002  (1.2s, 412 in / 287 out)
  Outline    $0.082  (15.3s, 2k in / 4.2k out)
  Expansion  $0.451  (12.8s wall, 11 parallel calls, 18k in / 24k out)
  Coherence  $0.011  (2.1s, 5.1k in / 580 out)
  Total      $0.546  (run total = sum + worker phase later)
```

Live `/runs/{id}/cost` endpoint already reads the in-memory snapshot, so
mid-classification cost appears in the UI immediately.

## Adaptive collapse — what each strategy actually runs

| Strategy | Phase 0 | Phase 1 | Phase 2 | Phase 3 |
|---|---|---|---|---|
| `SINGLE_SHOT` | heuristic only | synthesised | — | — |
| `SMALL` | LLM verifier | full plan in 1 call (32k cap) | — | — |
| `MEDIUM` | LLM verifier | outline | — | coherence |
| `LARGE` | LLM verifier | outline | parallel expansion | coherence |
| `LEGACY` | LLM verifier | outline (deeper) | recursive expansion (depth ≤ 2) | coherence (strict) |

## Schema migration (Day 1)

Single Flyway migration `V130__hierarchical_planner.sql`. Additive only —
all new columns nullable. Existing in-flight runs keep working.

```sql
ALTER TABLE pipeline_run
  ADD COLUMN plan_strategy VARCHAR(32),
  ADD COLUMN plan_outline_json JSONB,
  ADD COLUMN classifier_reasoning TEXT,
  ADD COLUMN classifier_input_tokens BIGINT,
  ADD COLUMN classifier_output_tokens BIGINT,
  ADD COLUMN classifier_cost_usd DOUBLE PRECISION,
  ADD COLUMN outline_input_tokens BIGINT,
  ADD COLUMN outline_output_tokens BIGINT,
  ADD COLUMN outline_cost_usd DOUBLE PRECISION,
  ADD COLUMN expansion_input_tokens BIGINT,
  ADD COLUMN expansion_output_tokens BIGINT,
  ADD COLUMN expansion_cost_usd DOUBLE PRECISION,
  ADD COLUMN coherence_input_tokens BIGINT,
  ADD COLUMN coherence_output_tokens BIGINT,
  ADD COLUMN coherence_cost_usd DOUBLE PRECISION;

ALTER TABLE agent_task
  ADD COLUMN task_type VARCHAR(16),
  ADD COLUMN expansion_status VARCHAR(16),
  ADD COLUMN expanded_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_pipeline_run_plan_strategy
  ON pipeline_run(plan_strategy);
```

## Slice plan (5 days, all ship live)

No feature flag — each slice goes live on dev as it's pushed.
Backward-compat fallbacks inside agents keep existing runs working
through the rollout. Old single-phase planner stays as fallback through
slice 3, removed in slice 4.

| Day | Ships live on the feature branch | Status |
|---|---|---|
| **1** | Migration V130 + `SmartClassifierAgent` (heuristic + LLM verifier + Redis cache) + classification persisted on every run + `pipeline_run.plan_strategy` populated. Legacy single-phase planner still runs downstream — classifier verdict recorded but doesn't change behaviour yet. | **Shipped** on branch — commits `07ce5cc1` (design) + `d2fe73f8` (slice 1) + `6adce0c4` (PreviewServiceTest Mockito fix) |
| **2** | `OutlinePlannerAgent` + `submitOutline` tool + new plan-shape persistence. Strategies `SMALL` and `MEDIUM` route through outline planner with 32k cap. `LARGE` / `LEGACY` / `SINGLE_SHOT` still on legacy planner. Outline-planner failure falls back to legacy planner so runs always progress. | **Shipped** on branch — outline cost diff'd into `pipeline_run.outline_*` columns, full plan JSON to `plan_outline_json`, all 298 tests green |
| **3** | `TaskExpanderAgent` + parallel expansion + per-strategy circuit breaker + `LARGE` strategy wired. `CoherencePlannerAgent` + `patchPlan` tool. | **Shipped** on branch — TaskExpander (WORKHORSE, parallel via `CompletableFuture.allOf` on the 4-thread pool, 15-min wall timeout, per-task fallback), CoherencePlanner (LIGHTWEIGHT, 5 patch kinds), `PlanStrategyCircuitBreaker` (5 failures → 1h open → ladder degrade), OutlinePlanner SKELETON_ONLY mode, `PlannedTaskV2.taskType`. LEGACY degrades to LARGE for now; recursive depth-2 in slice 4. All 298 tests green. |
| **4** | `LEGACY` strategy with recursive depth-2 expansion + outline-checkpoint resumability. **Legacy single-phase planner deleted.** | Pending |
| **5** | `V2RunCostTracker.estimateRun` re-tune + dashboard surfacing of phase progress + 50-run calibration analysis on classifier accuracy + heuristic threshold tuning. | Pending |

## Risk + mitigation

| Risk | Mitigation |
|---|---|
| LLM classifier hallucinates a strategy | Heuristic verdict as override-safe fallback; every misclassification logged for offline calibration; classifier output is JSON-validated before use |
| Phase 2 parallel expansion thrashes the LLM provider | Bound concurrency via existing pipeline executor pool (4); classifier outputs `estimatedTaskCount` so we know parallelism upfront |
| Classifier latency degrades VIBE/PRO simple-spec UX | Heuristic-only fast path for obvious SINGLE_SHOT specs — never hits the LLM. Plus 1h Redis cache. |
| Classifier failure breaks all runs | Heuristic fallback ALWAYS available; LLM call is wrapped in 8s timeout + try/catch |
| Storage growth from outline JSON | Columns are JSONB; outline avg ~3-5k bytes; same order as existing `plan_json` |
| Coherence pass produces a patch we can't apply | Patches are validated against schema; on failure, log + use unpatched outline |
| Slice 4 removes single-phase planner — no rollback path | By slice 4 we've had 3 days of dev traffic on hierarchical. If misclassification rate >2% in calibration logs from slice 1-3, push slice 4 timeline and tune the classifier first. |

## Cost shape comparison

| Spec | Today (single-shot Sonnet) | Hierarchical |
|---|---|---|
| TINY (typo fix) | $0 (synthesised) | **$0** (heuristic fast path, no LLM) |
| SMALL (TODO app) | $0.05-0.20 | **$0.04-0.18** (classifier $0.002 + outline $0.04-0.18) |
| MEDIUM (Stripe checkout) | $0.50-2.00, sometimes truncates | **$0.42** (classifier + outline + coherence) |
| LARGE (Uber clone) | $1-3, **often truncates / fails** | **$0.95** (all 4 phases, ~12s wall) |
| LEGACY (multi-tenant migration) | **TRUNCATES, FAILS** | **$1.75** (recursive depth 2) |

VIBE/PRO simple traffic: 60-70% see zero impact. 20-30% see +1-3s latency
(often hidden behind parallel infra work) and sub-cent cost.

## Cost accounting

Every LLM call routes through `LlmRouterV2.call(...)` which already calls
`V2RunCostTracker.record(...)`. The tracker's per-run accumulator captures
all tokens + cost regardless of which agent fired — so:

- `PipelineRun.totalInputTokens / totalOutputTokens / actualCostUsd`
  reflect the sum of all four phases automatically (no caller needs to
  know about phases)
- `/api/runs/{id}/cost` endpoint reads the live in-memory snapshot, so
  mid-classification cost appears immediately
- Per-phase breakdown surfaced via new `pipeline_run.{phase}_*` columns
  for the dashboard
- `V2RunCostTracker.estimateRun` re-tuned in slice 5 with per-strategy
  estimates so plan-approval shows realistic ranges

## What we explicitly preserve

1. The `SINGLE_SHOT` synthesised path stays exactly as today — same code,
   same outcome. Heuristic fast path is the gatekeeper.
2. No new fields required at submit time. `RunRequest` body unchanged.
   Strategy is decided server-side.
3. No UI change at submit time. Plan-approval modal looks the same.
4. No tier gating. Spec shape decides strategy, not subscription level.
5. No regressions for in-flight runs at deploy time. Schema migration is
   additive; agents have heuristic fallbacks; old single-phase planner
   stays alive through slice 3 as a safety net.

## Files touched (final, end of slice 5)

| Path | Status |
|---|---|
| `src/main/resources/db/migration/V130__hierarchical_planner.sql` | new |
| `src/main/java/com/navneet/executespec/v2/agent/SmartClassifierAgent.java` | new |
| `src/main/java/com/navneet/executespec/v2/agent/OutlinePlannerAgent.java` | new |
| `src/main/java/com/navneet/executespec/v2/agent/TaskExpanderAgent.java` | new |
| `src/main/java/com/navneet/executespec/v2/agent/CoherencePlannerAgent.java` | new |
| `src/main/java/com/navneet/executespec/v2/agent/PlanV2.java` | edit (add `requirementsCovered`, `taskType`) |
| `src/main/java/com/navneet/executespec/v2/agent/PlannerAgentV2.java` | delete (slice 4) |
| `src/main/java/com/navneet/executespec/v2/integration/V2RunOrchestrator.java` | edit (4-phase drive, parallel expansion) |
| `src/main/java/com/navneet/executespec/state/entity/PipelineRun.java` | edit (per-phase cost columns) |
| `src/main/java/com/navneet/executespec/state/entity/AgentTask.java` | edit (taskType, expansionStatus, expandedAt) |
| `src/main/java/com/navneet/executespec/v2/cost/V2RunCostTracker.java` | edit (estimateRun re-tune, slice 5) |
| `src/main/resources/application.yml` | edit (4 new agent tier mappings) |
| `src/main/resources/prompts/v2/classifier.md` | new |
| `src/main/resources/prompts/v2/outline-planner.md` | new |
| `src/main/resources/prompts/v2/task-expander.md` | new |
| `src/main/resources/prompts/v2/coherence-planner.md` | new |
| `frontend/src/lib/api.ts` | edit (RunSummary fields, slice 5) |
| `frontend/src/components/runs/RunDetailTabs.tsx` | edit (per-phase cost breakdown, slice 5) |
