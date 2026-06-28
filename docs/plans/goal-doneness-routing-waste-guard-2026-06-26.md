# Goal Doneness, Routing, and Waste Guard

> Status: incremental implementation in progress; task-stop enforcement remains default-off, selected-candidate continuation acting is default-on, and final run-level proof is persisted. Date: 2026-06-27.

## Problem

The run loop already has several local guardrails, but it does not have one owner for the question "should this task continue, stop, step up, step down, or switch providers?"

Today that decision is spread across:

- `engine/WorkerAgent`: iteration caps, wall-clock caps, stagnation nudges, `completeTask` refusal gates, VBC, critic pass, deliverable existence checks, budget stop, salvage, and read-only parallel dispatch.
- `harness/SequentialExecutor`: task complexity to tier/model/cap mapping, smoke-test retry, high-complexity second retry, and optional cross-provider retry.
- `router/ModelResolver`: YAML/org/run-pin model resolution, opt-in adaptive routing, health failover, and strict-provider handling.
- `agent/LlmRouter`: bounded same-provider transient retry and optional router-level provider failover.
- `benchmark/`, `eval/v2/`, `cost/`, and `report/`: benchmark resolved rate, tier-eval correctness, per-call token/cost/latency, validation report evidence, and verified-score trust caps.

Those are useful ingredients. The missing layer is a policy and evidence model that turns them into auditable continuation decisions.

## Non-goals

- Do not replace `WorkerAgent`, `SequentialExecutor`, `ModelResolver`, or `LlmRouter`.
- Do not let a benchmark result directly pick the provider for a customer task.
- Do not treat `completeTask` or a model's textual "done" as proof.
- Do not re-enable broad adaptive routing as the solution. The current `ModelPerformanceTracker` is only success-rate plus latency over a 24-hour Redis window; it is not rich enough for quality-first routing.
- Do not add unbounded retry, model escalation, or provider cascade behavior.
- Do not parallelize mutating worker loops. Parallelism belongs at dependency-safe task waves and read-only tool batches only.

## Decision Model

Introduce first-class states and decisions:

```java
enum GoalDonenessState {
  DONE,
  NOT_DONE_BUT_PROGRESSING,
  BLOCKED,
  FAILED,
  UNCERTAIN,
  WASTE_RISK
}

enum RunContinuationDecision {
  CONTINUE_SAME_MODEL_PROVIDER,
  STEP_UP_MODEL,
  STEP_DOWN_MODEL,
  CHANGE_PROVIDER,
  STOP_DONE,
  STOP_BLOCKED,
  STOP_FAILED,
  STOP_WASTE_GUARD
}
```

The important rule: doneness and continuation are separate. Doneness answers "what is the current evidence?" Continuation answers "what should the system do next within policy?"

## Evidence Model

Add a small immutable evidence carrier at the task/executor boundary, not inside the provider client:

```java
record GoalEvidence(
    String runId,
    String taskId,
    String taskCategory,
    String provider,
    String model,
    WorkerResult.Outcome workerOutcome,
    int iterations,
    int toolCalls,
    int fileWrites,
    int noProgressIterations,
    int completionRefusals,
    boolean completeTaskCalled,
    Boolean smokePassed,
    String smokeFailure,
    Boolean testsPassed,
    String testsFailure,
    Boolean reviewerPassed,
    String reviewerFailure,
    double costUsd,
    long inputTokens,
    long outputTokens,
    long reasoningTokens,
    long wallClockMs,
    List<String> deterministicEvidence,
    List<String> modelJudgedEvidence)
```

Initial sources are already available:

- Worker outcome, iterations, tool calls, and file writes from `WorkerResult`.
- Stagnation and refusal signals from `WorkerAgent`.
- Build/test smoke from `SequentialExecutor`.
- Per-call cost/tokens/reasoning from `RunCostTracker` and `run_step_usage`.
- Benchmark priors from `benchmark_task_result` and `eval/v2`.
- Verification ledger and trust caps from `ValidationReportService`.

The first implementation can populate only the fields already available in `SequentialExecutor`; the schema can grow as VBC/reviewer/report evidence is wired in.

## Doneness Evaluator

Add `GoalDonenessEvaluator` as a pure service. It consumes `GoalEvidence` plus the goal constraints and returns:

```java
record GoalDonenessDecision(
    GoalDonenessState state,
    double confidence,
    List<String> satisfiedEvidence,
    List<String> missingEvidence,
    List<String> risks,
    String reason)
```

Decision rules should be deterministic first:

- `DONE`: required deterministic checks pass, required artifacts exist, no unresolved critical blocker, and the worker produced relevant output or the task is explicitly verify-only.
- `NOT_DONE_BUT_PROGRESSING`: writes or verified tool progress are happening, latest failure is actionable, and guard budgets remain.
- `BLOCKED`: environment/tooling/auth/data missing, or the same external dependency failure repeats after bounded retries.
- `FAILED`: deterministic verification failed after the allowed fix loop, or the worker produced no useful output and no alternate path remains.
- `UNCERTAIN`: evidence is incomplete or mixed, but not yet wasteful.
- `WASTE_RISK`: no progress, repeated false completion, repeated same failure, budget ceiling near/exceeded, or diminishing return after bounded retries/escalations.

This evaluator should sit above `completeTask`. `WorkerAgent` can keep refusing obviously premature completion, but final task/run truth belongs to the evaluator plus downstream smoke/reviewer/report evidence.

## Continuation Policy

Add `ContinuationPolicy` and `WasteGuardPolicy` with explicit ceilings:

```yaml
execution:
  continuation:
    enabled: true
    required-quality-threshold: 70.0
    quality-tie-band: 1.0
    required-quality-thresholds:
      task-low: -1
      task-medium: -1
      task-high: -1
      greenfield-build: -1
      project-patch: -1
      bug-fix: -1
      test-fix: -1
      refactor: -1
      benchmark-patch: -1
      terminal-task: -1
      read-only-help: -1
    max-no-progress-iterations: 28
    max-task-retries: 1
    max-high-complexity-retries: 2
    max-false-done-claims: 2
    max-repeated-failures: 3
    max-edit-churn: 6
    max-stale-explanations: 2
    max-provider-switches-per-run: 1
    max-model-step-ups-per-task: 1
    max-model-step-downs-per-task: 1
    max-total-cost-usd: -1
    max-total-tokens: -1
    max-task-wall-clock-ms: -1
    max-task-wall-clock-seconds-low: 1800
    max-task-wall-clock-seconds-medium: 2700
    max-task-wall-clock-seconds-high: 4500
    require-confirmation-before-expensive-continuation: false
    require-benchmark-prior-for-automatic-continuation: false
    acting:
      enabled: true
    max-reruns-per-task: 1
```

The first wired subset is policy-visible: `required-quality-threshold`, the semantic and complexity
overrides under `required-quality-thresholds`, `max-provider-switches-per-run`,
`max-model-step-ups-per-task`, `max-model-step-downs-per-task`, and
`require-confirmation-before-expensive-continuation`. The first evidence-ceiling subset is
disabled unless positive: `max-total-cost-usd`, `max-total-tokens`, and
`max-task-wall-clock-ms` feed `GOAL_DECISION` budget evidence and can stop as
`STOP_WASTE_GUARD` when crossed before proof of completion. The acting subset is enabled by default via
`acting.enabled` and currently permits at most one same-task rerun with an advisor-selected
provider/model.

Hard exits:

- Goal already satisfied with deterministic evidence: `STOP_DONE`.
- No progress after warning/force thresholds and no useful writes: `STOP_WASTE_GUARD`.
- Repeated false done claims: `STOP_WASTE_GUARD`.
- User/platform budget ceiling reached: `STOP_WASTE_GUARD`.
- Environment block: `STOP_BLOCKED`.
- Verification failed after bounded retry/escalation: `STOP_FAILED`.
- Unsafe/risky continuation: `STOP_FAILED` or `STOP_BLOCKED` depending on cause.
- Low improvement confidence after allowed retry/switch: `STOP_WASTE_GUARD`.
- Diminishing returns: repeated same failure signature or same files churned without verification improvement.

Every stop must emit an explanation with:

- attempted goal,
- final state,
- evidence used,
- completed and incomplete items,
- why the selected stop condition fired,
- whether another model/provider might plausibly help,
- user input needed, if any,
- recommended next step.

## Model/Provider Ranking

Add a policy service instead of extending `ModelPerformanceTracker` directly:

```java
record ModelProviderScore(
    String provider,
    String model,
    String taskCategory,
    double qualityScore,
    double costPerMillionTokens,
    long latencyP50Ms,
    long latencyP95Ms,
    double requiredQualityThreshold,
    List<String> reasons)
```

Ranking rule:

1. Filter out every candidate whose quality score is below the required threshold.
2. Rank remaining candidates by quality descending.
3. Break near-quality ties by cost ascending.
4. Break remaining ties by latency ascending.
5. If no candidate meets the threshold, prefer `STOP_BLOCKED` / `require user confirmation` over silently running a cheaper weaker model.

This is intentionally lexicographic, not a blended weighted average. Quality dominates cost and latency. Cheaper/faster below threshold is invalid.
The near-quality band is intentionally narrow and anchored from the best remaining quality score: it
prevents a 0.1-point measurement artifact from forcing a materially more expensive/slower model,
while still letting clear quality gaps win.

Quality score should be category-specific and explainable:

- benchmark resolved rate by suite/task family,
- tier-eval correctness/tool-call validity/cache behavior,
- production verified-score and trust-cap outcomes,
- test/build pass rate,
- reviewer issue rate,
- false-completion rate,
- tool/schema error rate,
- recent provider health only as an availability modifier, not as a quality substitute.

Cost comes from YAML pricing plus `run_step_usage`. Latency comes from `run_step_usage`, tier eval, and metrics. Benchmark data is a prior, not the final decision-maker; live task evidence can override it.

## Integration Plan

### Phase 0 - Design and audit

Land this document only. No behavior change.

### Phase 1 - Contracts and telemetry, flag off

Add:

- `GoalDonenessState`
- `RunContinuationDecision`
- `GoalEvidence`
- `GoalDonenessDecision`
- `ContinuationDecision`
- `WasteGuardPolicy`
- `ModelProviderScore`
- `ModelProviderRankingPolicy`

Keep them pure and unit-tested. Populate explanations in memory/logs only. No retry/routing behavior changes.

### Phase 2 - Doneness evaluation at task boundary

Wire `GoalDonenessEvaluator` into `SequentialExecutor` after each worker attempt and smoke result. Keep existing retry behavior, but emit the evaluator's state and reason into task diagnostics/SSE logs.

Do not move this into `LlmRouter`. The router knows provider calls, not task goals.

### Phase 3 - Bounded continuation decider

Replace the hardcoded retry/cross-provider branches in `SequentialExecutor` with `ContinuationDecisionService`, initially preserving identical outcomes under the flag-off default.

When enabled, allow at most:

- one same-provider retry,
- one model step-up per task,
- one provider switch per run,
- one HIGH-complexity second retry,
- explicit stop on waste risk.

### Phase 4 - Evidence-backed model/provider ranking

Build `ModelProviderCandidateService` / evidence readers that read:

- YAML model matrix and pricing,
- Redis-backed local model performance,
- `run_step_usage`,
- `TierEvalResult` outputs,
- benchmark tracking tables,
- validation report outcomes.

Then use `ModelProviderRankingPolicy` only at controlled decision points: initial worker tier choice, step-up, step-down, and provider switch. Keep run/org/user pins and strict-provider semantics as hard constraints.

### Phase 5 - Dependency-safe parallelism

Use the existing latency plan's `TaskWaveScheduler` idea. Parallelize only when the plan can prove independent tasks and enforce a low global concurrency cap. Keep worker mutation within one task serial. Keep `WorkerAgent` read-only batch parallelism gated by `execution.continuation.parallel-read-tools-enabled`, with server/SSE logs carrying call labels and per-call results.

## Minimal First Code Slice

The safest first code slice after review:

1. Add the two enums and pure ranking policy:
   - `GoalDonenessState`
   - `RunContinuationDecision`
   - `ModelProviderScore`
   - `ModelProviderRankingPolicy`
2. Unit-test the ranking rule:
   - below-threshold candidate is filtered even when cheapest,
   - higher quality beats lower cost,
   - cost breaks quality ties,
   - latency is last tie-break,
   - no qualifying candidate returns an explicit no-candidate decision.
3. Add no runtime wiring yet.

That gives the repository a pinned, reviewable policy before touching `WorkerAgent` or `SequentialExecutor`.

Status 2026-06-26: this first slice has landed. `GoalDonenessState` and
`RunContinuationDecision` live under `execution/model/`; `ModelProviderScore`,
`ModelProviderRankingDecision`, and `ModelProviderRankingPolicy` live under `router/`.
`ModelProviderRankingPolicyTest` pins the quality-threshold and quality/cost/latency ordering.
No runtime routing, worker, or executor behavior calls the policy yet.

Second slice 2026-06-26: `GoalEvidence`, `GoalDonenessDecision`, and the pure
`GoalDonenessEvaluator` landed under `execution/`. `GoalDonenessEvaluatorTest` pins the
conservative state mapping: proven done, false done, environment blocked, uncertain validation,
no-progress waste guard, repeated false completion, budget ceiling, and failed validation with no
useful progress. Runtime execution is still unchanged until `SequentialExecutor` starts producing
`GoalEvidence` at the task boundary.

Third slice 2026-06-26: `SequentialExecutor` now builds conservative `GoalEvidence` at terminal
task branches and emits a `Goal decision: STATE -> DECISION (...)` `LOG` event. This gives the
UI/CLI event stream visible doneness/exit-policy state without changing persisted task status,
retry behavior, provider choice, or run termination. `SequentialExecutorSoftCompletionTest` pins
both `DONE -> STOP_DONE` on completeTask+passing smoke and `WASTE_RISK -> STOP_WASTE_GUARD` on
iteration-limit/no-write failure. Enforcement is still pending.

Fourth slice 2026-06-26: V249 seeds `execution.goal-decision.enforcement.enabled` default OFF.
`TaskOutcome` now carries the computed `GoalDonenessDecision`; when the flag is enabled,
`SequentialExecutor` aborts remaining sequential tasks after `STOP_BLOCKED`, `STOP_FAILED`, or
`STOP_WASTE_GUARD`, emits `Goal stop enforced: ...`, appends synthetic `SKIPPED` task outcomes, and
returns aggregate `ABORTED`. Task-boundary `STOP_DONE` intentionally remains informational even
after V251's final run-level proof, because one task proving done does not prove a multi-task run is
done; final proof is persisted on `pipeline_run.run_goal_doneness_json` after validation-report
generation. Provider/model step-up, step-down, and change-provider decisions are selected by the
advisor and executed automatically when selected-candidate acting remains enabled.

Fifth slice 2026-06-26: `GoalContinuationRequest`, `GoalContinuationPolicyDecision`, and
`GoalContinuationPolicy` now define the pure escalation/exit policy that consumes
`GoalDonenessDecision` plus ranked `ModelProviderScore` candidates. It stops done/blocked
immediately, blocks expensive continuation until user confirmation, escalates waste/failure only
when another model/provider may help and configured limits remain, changes provider for poor
provider behavior, steps up for insufficient quality, and steps down only to cheaper candidates that
still clear the quality threshold. `GoalContinuationPolicyTest` pins those cases. Runtime provider
switching and live candidate metric collection are still pending.

Sixth slice 2026-06-26: `ModelProviderCapabilitySignal` and `ModelProviderCapabilityRegistry` now
convert benchmark, local success, validation pass-rate, code-review, tool-reliability, recent
failure, provider availability, fallback-order, context, cost, and latency signals into
explainable `ModelProviderScore` candidates. The existing `ModelProviderRankingPolicy` remains the
selection step, so quality-threshold filtering and quality/cost/latency ordering stay centralized.
`ModelProviderCapabilityRegistryTest` pins combined quality scoring, unavailable-provider
rejection, missing-benchmark fallback, and cheap-below-threshold rejection through the ranking
policy. Runtime wiring from Redis, tier-eval rows, benchmark tracking, and YAML model metadata is
still pending.

Seventh slice 2026-06-26: `ModelProviderCandidateRequest` and `ModelProviderCandidateService` now
bridge live runtime/config sources into the pure capability seam. The service iterates YAML
`model-matrix.models`, preserves pricing and context metadata from `ModelRegistry`, merges Redis
`ModelPerformanceTracker` success/latency when enough local samples exist, accepts external
benchmark/validation signals, applies explicit provider/model allow-lists, provider availability,
and provider preference order, then returns scored `ModelProviderScore` candidates. This closes the
first live-source collection gap without enabling automatic provider switching. Later slice 11 wires
`SequentialExecutor` to this collector through `GoalContinuationAdvisor` for advisory logs only;
`ModelResolver` and `LlmRouter` still do not use it for dispatch decisions.
`ModelProviderCandidateServiceTest` pins YAML fallback quality, local+external signal merge,
allow-list filtering, unavailable-provider rejection, and fallback-order penalty behavior.

Eighth slice 2026-06-26: `SequentialExecutor` now passes every task-boundary
`GoalDonenessDecision` through `GoalContinuationPolicy` and emits a second SSE `LOG` event:
`Continuation decision: DECISION (...)`. This is visibility-only because no ranked candidates are
supplied at the executor boundary yet; the policy can expose continue/stop reasoning, but it cannot
select step-up, step-down, or change-provider actions from runtime candidates. The existing
goal-stop enforcement flag still uses the conservative doneness stop decisions, so default runtime
behavior and provider/model selection remain unchanged. `SequentialExecutorSoftCompletionTest`
pins the `STOP_DONE` continuation event on completeTask+passing-smoke.

Ninth slice 2026-06-26: existing `TaskWaveScheduler` dependency-wave planning was extended with
`scheduleBounded(...)`, a pure safe-parallelism policy seam. It preserves topological dependency
waves, enforces a hard max wave size, groups only tasks with declared disjoint `touchesFiles`, splits
same-file and parent/child path conflicts, and serializes tasks with unknown or broad touch sets.
`SequentialExecutor` still runs sequentially; this pins the conditions under which future parallel
execution may be considered without allowing unsafe workspace mutation. `TaskWaveSchedulerTest`
now covers cap enforcement, same-file conflict, parent-directory conflict, unknown/broad
serialization, and dependency preservation.

Tenth slice 2026-06-26: `GoalContinuationAdvisorRequest` and `GoalContinuationAdvisor` now provide
the runtime-facing candidate-backed decision function. The advisor builds candidates through
`ModelProviderCandidateService`, ranks them with `ModelProviderRankingPolicy`, identifies the
current provider/model candidate, and feeds the result into `GoalContinuationPolicy`. This is the
first seam that can recommend `STEP_UP_MODEL`, `STEP_DOWN_MODEL`, or `CHANGE_PROVIDER` from live
candidate data while still being advisory-only: it does not dispatch workers, mutate a run, or
override strict-provider/user pins. `GoalContinuationAdvisorTest` pins same-provider step-up,
provider change, cheaper eligible step-down, and propagation of provider/model filters plus
availability/preference inputs to the candidate collector.

Eleventh slice 2026-06-26: `SequentialExecutor` now uses `GoalContinuationAdvisor` at every
task-boundary goal decision when the advisor bean is available. The executor passes the actual
dispatched provider/model, task category, a conservative required quality threshold, and YAML
failover preference order into the advisor, then emits the candidate-backed
`Continuation decision: DECISION (...)` log. The call is fail-soft: missing/throwing advisor falls
back to the pure `GoalContinuationPolicy`. This still does not execute provider/model switching or
rerun work; it only makes step-up, step-down, and change-provider recommendations visible from live
candidates. `SequentialExecutorSoftCompletionTest` pins advisor-backed `STEP_UP_MODEL` visibility
on validation-failed-with-progress and verifies the advisor request fields.

Twelfth slice 2026-06-26: task-boundary goal and continuation decisions are now durable and
visible in run detail. V250 adds `agent_task.goal_doneness_json` and
`agent_task.continuation_decision_json`; `SequentialExecutor` writes both after emitting the SSE log
events; `RunMapper` exposes them as parsed `TaskSummary.goalDoneness` and
`TaskSummary.continuationDecision`; and the IDE run-result task list renders a compact
`Goal ... -> ... · Next ...` line. This improves web/CLI-consumable visibility without enabling
automatic provider/model switching.

Thirteenth slice 2026-06-26: live run-event visibility no longer requires clients to scrape the
legacy `LOG` payload. `RunEvent` now has explicit `GOAL_DECISION` and `CONTINUATION_DECISION`
events with `schemaVersion=1` and structured `data` fields for state, recommended decision, reason,
evidence, risks, user-input flag, selected candidate, and continuation reasons. `SequentialExecutor`
emits those typed events alongside the existing `Goal decision: ...` and `Continuation decision: ...`
log lines, preserving old web/CLI/VS Code readers while giving terminal/TUI clients a stable
machine-readable contract.

Fourteenth slice 2026-06-26: continuation advisory ceilings are no longer hardcoded at the executor
boundary. `application.yml` now exposes `execution.continuation.required-quality-threshold`,
`max-provider-switches-per-run`, `max-model-step-ups-per-task`,
`max-model-step-downs-per-task`, and
`require-confirmation-before-expensive-continuation`; `SequentialExecutor` sanitizes those values,
passes them into `GoalContinuationAdvisor` / fallback `GoalContinuationPolicy`, and includes the
applied policy in both typed `CONTINUATION_DECISION` SSE data and durable
`agent_task.continuation_decision_json`.

Fifteenth slice 2026-06-26: `SequentialExecutor` gained the selected-candidate acting path on non-terminal
continuation decisions. Automatic selected-candidate reruns are now enabled by default (`execution.continuation.acting.enabled=true`).
When enabled, the executor only acts on `STEP_UP_MODEL`, `STEP_DOWN_MODEL`, or `CHANGE_PROVIDER`
decisions that carry a selected `ModelProviderScore`; it emits a typed `CONTINUATION_ACTION` event
plus the legacy log line, reruns the same task once with that selected provider/model while
retaining the original tier caps, and reports the final rerun outcome as the task outcome with
aggregate tool-call/latency accounting. It does not loop, does not act on `CONTINUE_SAME_MODEL_PROVIDER`,
does not act during resume, and does not invent a provider/model outside the advisor-selected
candidate.

Sixteenth slice 2026-06-26: continuation acting now hard-enforces the configured ceilings at the
runtime boundary instead of trusting advisory request fields alone. `SequentialExecutor` tracks
actual `CHANGE_PROVIDER` usage per run and actual `STEP_UP_MODEL` / `STEP_DOWN_MODEL` usage per
task, reserves the relevant slot before dispatching a selected-candidate rerun, clears the counters
at run end, and emits typed `CONTINUATION_ACTION_SKIPPED` plus the legacy log when a recommendation
is blocked by `maxProviderSwitches`, `maxModelStepUps`, or `maxModelStepDowns`. Successful
`CONTINUATION_ACTION` events also carry limit metadata (`limitName`, `usedLimit`, `maxLimit`) so
clients can explain both executed and blocked escalations.

Seventeenth slice 2026-06-26: goal evidence now carries explicit spend, token, and wall-clock
proof for waste-guard decisions. `GoalEvidence` stores `budgetCeilingReason`, input/output/cache
read/cache write/reasoning/total token counts, cost USD, and task wall-clock milliseconds.
`SequentialExecutor` builds that evidence from `RunCostTracker.snapshot(runId)` plus the
`WorkerResult` latency, appends the values to `GOAL_DECISION` evidence, and treats positive
`execution.continuation.max-total-cost-usd`, `max-total-tokens`, or `max-task-wall-clock-ms`
crossings as `WASTE_RISK` / `STOP_WASTE_GUARD` before proof of completion. The default values stay
`-1`, so existing runs are unchanged until an operator enables a ceiling.

Eighteenth slice 2026-06-26: budget evidence is now a structured client contract instead of only
an evidence-string convention. `GoalDonenessDecision` carries the same cost/token/cache/reasoning
and wall-clock fields as first-class JSON properties; `GOAL_DECISION`, durable
`agent_task.goal_doneness_json`, and `TaskSummary.goalDoneness` expose them directly. The applied
continuation policy also includes `maxTotalCostUsd`, `maxTotalTokens`, and
`maxTaskWallClockMs`. `IdeRunResult`'s Steps tab renders cost/tokens/time against enabled ceilings,
the concrete ceiling reason, remaining items, and risks so operators can see why the run continued
or stopped without opening raw logs.

Nineteenth slice 2026-06-26: run-scoped agent sessions now receive the same goal/continuation
signals through their durable action-event stream. `AgentRunEventBridge` listens to typed local
`RunEvent`s emitted by `SseBroadcaster` and appends `RUN_GOAL_DECISION`,
`RUN_CONTINUATION_DECISION`, `RUN_CONTINUATION_ACTION`, and
`RUN_CONTINUATION_ACTION_SKIPPED` rows to every active `agent_session` with the matching `run_id`.
The payload preserves the original run-event type, run/task ids, summary, timestamp,
`schemaVersion`, and structured `data`, so web/CLI/VS Code clients can reload goal state,
next-action advice, executed reruns, and limit-blocked rerun recommendations from
`/api/agent/sessions/{id}/events` without scraping run SSE logs.

Twentieth slice 2026-06-26: validation-report evidence ledger surfacing is now reloadable for
run-scoped agent sessions too. `ValidationReportService` publishes a small
`ValidationReportEvidenceEvent` after a report row is saved; `AgentValidationReportEventBridge`
appends a `RUN_VALIDATION_EVIDENCE` row to every active `agent_session` with the matching `run_id`.
The payload includes the report id, verified/rubric scores, pipeline result, created timestamp, and
the same `verificationLedger[]` rows already returned by `/api/runs/{id}/report` and rendered in
the run-result Evidence tab. This gives web/CLI/VS Code clients a durable final evidence summary
through `/api/agent/sessions/{id}/events`.

Twenty-first slice 2026-06-26: final validation ledgers now produce a conservative run-level goal
proof instead of staying as raw explanation only. `ValidationReportGoalProofEvaluator` maps the
saved report's `pipelineResult` plus `verificationLedger[]` into the shared
`GoalDonenessDecision` contract. It returns `DONE / STOP_DONE` only when final report result,
delivery, build, concrete tests, reviewer, report issues, spec-adherence, and any optional
endpoint/test-coverage proof all pass; missing/unknown/warning evidence keeps the proof
`UNCERTAIN`, and failed completion evidence maps to `FAILED / STOP_FAILED`. The
`ValidationReportEvidenceEvent` carries this `runGoalDecision`, and
`AgentValidationReportEventBridge` includes it in `RUN_VALIDATION_EVIDENCE` payloads so web/CLI/VS
Code can reload the final proof beside the raw ledger.

Twenty-second slice 2026-06-26: the final proof is now part of the primary validation-report
surface, not only the agent-session event stream. `ValidationReportDTO` includes `runGoalDecision`,
`GET /api/runs/{id}/report` documents it in `openapi.yaml`, the frontend API type reuses the same
goal decision shape as task summaries, and the run-result Evidence tab renders a "Final goal proof"
card above the raw evidence ledger.

Twenty-third slice 2026-06-26: the task-boundary waste guard no longer hardcodes no-progress and
false-done thresholds. `application.yml` exposes
`execution.continuation.max-no-progress-iterations` (default 28) and
`max-false-done-claims` (default 2); `SequentialExecutor` passes them into `GoalEvidence`, records
`current/max` evidence strings in typed `GOAL_DECISION` data and durable task JSON, and includes the
same limits in the applied continuation policy payload. `GoalEvidence` keeps the existing
`<=0`-means-disabled normalization for these thresholds.

Twenty-fourth slice 2026-06-26: safe parallelism is now visible and capped at the executor
boundary without enabling concurrent workspace mutation. `application.yml` exposes
`execution.continuation.max-parallel-tasks` (default 3); `SequentialExecutor` runs the current plan
through `TaskWaveScheduler.scheduleBounded(...)` at run start; emits typed `TASK_WAVE_PLAN` SSE data
with `executionMode=SEQUENTIAL_ADVISORY`, bounded waves, serialized tasks, missing/unscheduled
dependency metadata, and the configured cap; and includes `maxParallelTasks` in the applied
continuation policy payload. Runtime task dispatch remains sequential for mutating work; the only
automatic runtime parallelism is the separate pure read-only worker tool batch path gated by
`execution.continuation.parallel-read-tools-enabled`.

Twenty-fifth slice 2026-06-26: advisory task-wave plans are now reloadable through run-scoped
agent sessions, not only transient run SSE. `AgentRunEventBridge` maps typed `TASK_WAVE_PLAN`
events into `RUN_TASK_WAVE_PLAN` `agent_action_event` rows for active sessions with the matching
`run_id`, preserving the original summary, timestamp, `schemaVersion`, and structured wave data.
This gives web/CLI/VS Code clients the same bounded safe-parallelism evidence after reload as they
already get for goal decisions, continuation advice, executed reruns, limit skips, and final
validation evidence.

Twenty-sixth slice 2026-06-26: required model/provider quality thresholds are now category-aware at
the executor/advisor boundary. `application.yml` still keeps the global
`execution.continuation.required-quality-threshold` fallback, but adds
`required-quality-thresholds.task-low`, `.task-medium`, and `.task-high` overrides for the current
`TASK_LOW` / `TASK_MEDIUM` / `TASK_HIGH` categories derived from `PlannedTask.complexity`.
`SequentialExecutor` sends the applied category-specific threshold into
`GoalContinuationAdvisor`, and the typed/durable continuation policy payload records
`taskCategory`, `requiredQualityThreshold`, `defaultRequiredQualityThreshold`, and the resolved
per-category threshold map. Broader semantic categories such as `BUG_FIX`, `TEST_FIX`, or
`TERMINAL_TASK` remain a later classifier/planner concern.

Twenty-seventh slice 2026-06-26: `SequentialExecutor` now derives a conservative semantic task
category from existing `PlannedTask` title / description / hard-constraint / touched-file hints
before falling back to `TASK_LOW` / `TASK_MEDIUM` / `TASK_HIGH`. The first semantic set is
`GREENFIELD_BUILD`, `PROJECT_PATCH`, `BUG_FIX`, `TEST_FIX`, `REFACTOR`, `BENCHMARK_PATCH`,
`TERMINAL_TASK`, and `READ_ONLY_HELP`; all are default-off threshold override keys under
`execution.continuation.required-quality-thresholds.*`. This keeps the planner schema stable while
letting benchmark/local validation signals and quality thresholds line up on real task categories
when the task text is explicit enough.

Twenty-eighth slice 2026-06-26: every `GoalDonenessDecision` now carries an explicit
`recommendedNextStep` string, closing the last "Every exit must explain" field without introducing a
second decision type. The value is deterministic from the recommended decision and whether user
input or another model/provider might help; typed `GOAL_DECISION` SSE data, durable
`agent_task.goal_doneness_json`, `TaskSummary.goalDoneness`, `/api/runs/{id}/report`
`runGoalDecision`, OpenAPI, and the run-result Steps/Evidence tabs all expose the same field.

Twenty-ninth slice 2026-06-26: the waste guard now carries explicit repeated-failure evidence.
`GoalEvidence` includes `repeatedFailureCount` / `maxRepeatedFailures`, `application.yml` exposes
`execution.continuation.max-repeated-failures` (default 3, disabled by `<=0` through the same
normalization as no-progress and false-done), and `GoalDonenessEvaluator` maps repeated
tool/command/validation failures to `WASTE_RISK / STOP_WASTE_GUARD`. `SequentialExecutor` derives
the count from repeated worker tool refusals/errors and from post-retry smoke validation failures,
then publishes `repeatedFailureCount=current/max` in typed `GOAL_DECISION` data and durable task
JSON.

Thirtieth slice 2026-06-26: the waste guard now carries same-file edit-churn evidence without
changing worker dispatch. `GoalEvidence` includes `editChurnCount` / `maxEditChurn`,
`application.yml` exposes `execution.continuation.max-edit-churn` (default 6, disabled by `<=0`),
and `SequentialExecutor` derives the count from path-bearing `writeFile` / `editFile` /
`multiEdit` worker tool-log entries. When validation still fails and the same path reaches the
configured threshold, `GoalDonenessEvaluator` maps it to `WASTE_RISK / STOP_WASTE_GUARD` and typed
goal evidence publishes `editChurnCount=current/max`.

Thirty-first slice 2026-06-26: executor-built goal evidence now populates the existing
`blockedByEnvironment` field for clear external blockers instead of leaving it pure-model-only.
`SequentialExecutor` marks environment blocks from conservative failure signatures such as missing
credentials/API keys, auth/permission denial, DNS/network timeouts, or explicitly missing required
files, publishes `blockedByEnvironment=true` in typed `GOAL_DECISION` evidence, and lets
`GoalDonenessEvaluator` map those cases to `BLOCKED / STOP_BLOCKED` with `userInputRequired=true`.

Thirty-second slice 2026-06-26: executor-built goal evidence now also populates the existing
`unsafeContinuation` field for clear sandbox/path/destructive-action blockers. `SequentialExecutor`
marks unsafe continuation from conservative failure signatures such as path traversal, outside
workspace writes, sandbox-blocked commands, destructive commands, refused overwrites, code-abuse,
exfiltration, and cloud-metadata access, publishes `unsafeContinuation=true` in typed
`GOAL_DECISION` evidence, and lets `GoalDonenessEvaluator` map those cases to
`BLOCKED / STOP_BLOCKED` with `userInputRequired=true`.

Thirty-third slice 2026-06-26: worker terminal results now carry `repeatedAssistantTextCount`.
`SequentialExecutor` maps repeated normalized assistant text with no new file-write or validation
proof into `GoalEvidence.staleExplanationCount`, publishes `staleExplanationCount=current/max`, and
`GoalDonenessEvaluator` maps the configured `execution.continuation.max-stale-explanations`
threshold to `WASTE_RISK / STOP_WASTE_GUARD`.

Thirty-fourth slice 2026-06-27: terminal `STOP_WASTE_GUARD` / `STOP_FAILED` continuation decisions
now make low-confidence-of-improvement explicit. If the goal decision says another model/provider
might help but `GoalContinuationPolicy` cannot select a stronger/switch candidate within the
configured step-up/provider-switch limits, the stop reason appends that no useful model/provider
escalation remains within configured limits.

Thirty-fifth slice 2026-06-27: the worker read-only tool batch path is now explicitly configurable
through `execution.continuation.parallel-read-tools-enabled` (default true). When enabled, same-turn
pure read batches emit start/done server logs plus legacy `LOG` SSE lines with labels and `ok`/`err`
results; batches that include mutation, remote dispatch, timeout, or partial completion still run
through the sequential path.

Thirty-sixth slice 2026-06-27: final validation-ledger goal proof is now persisted back onto
`pipeline_run.run_goal_doneness_json` (V251) when the validation report is generated. `RunDetail`
exposes it as `runGoalDoneness`, and the IDE Steps tab shows the final run-level proof before the
task cards so the run goal state survives reloads without requiring a second report fetch.

Thirty-seventh slice 2026-06-27: final run-level proof now names the actual submitted run goal
instead of only a generic run-id proof label. `ValidationReportService` passes
`PipelineRun.userRequest` into `ValidationReportGoalProofEvaluator` when generating/persisting
proof; report reads use the same value through `StateStore.findRun`, falling back to planner
title/summary and then the legacy run-id label. This closes the final-exit explanation gap for
"what goal was being attempted" on persisted JSON, `/api/runs/{id}/report`, run detail, and
run-scoped validation-evidence events.

Thirty-eighth slice 2026-06-27: provider/model candidate evaluation is now bounded-parallel instead
of always sequential. `execution.continuation.parallel-provider-evaluation-enabled` defaults true,
reuses `execution.continuation.max-parallel-tasks` as the cap, and only activates after allow-list
filtering leaves more than one independent model/provider candidate. `ModelProviderCandidateService`
logs start labels and done result labels, preserves deterministic model-order output by collecting futures in input
order, falls back to sequential on executor failure/interruption, and writes the parallel/sequential
evaluation mode into each candidate's reasons so continuation advice remains explainable.

Thirty-ninth slice 2026-06-27: repeated command/test failures now use stable command signatures
instead of exact failure-line equality. Normal `WorkerAgent` tool-call log entries include
`-> OK` / `-> ERR`; `SequentialExecutor` collapses result-bearing `shell`, `bash`, `buildProject`,
and `runTests` failures to their tool/argument call before counting
`repeatedFailureCount=current/max`. This catches the same command or test failing repeatedly even
when stderr differs between attempts, while existing exact-line signatures still cover refusals,
critic gaps, and verify-before-complete nudges.

Fortieth slice 2026-06-27: runtime candidate scoring now uses persisted benchmark results as a real
quality prior. `ModelProviderCandidateService` reads the latest DONE `benchmark_run` rows fail-soft,
converts each provider/model run's `resolved_tasks / total_tasks` into a
`benchmarkQualityScore`, and merges that signal with caller-supplied benchmark/validation signals
and local 24-hour performance before the strict quality/cost/latency ranking policy runs. This keeps
benchmarks informative without making them the sole decision-maker or letting the candidate service
mutate run state.

Forty-first slice 2026-06-27: selected-candidate continuation acting is now default-on. When a task
fails or produces low-quality evidence and `GoalContinuationAdvisor` selects `STEP_UP_MODEL`,
`STEP_DOWN_MODEL`, or `CHANGE_PROVIDER`, `SequentialExecutor` reruns that same task once with the
selected provider/model unless `execution.continuation.acting.enabled=false`, a configured ceiling
is exhausted, or `require-confirmation-before-expensive-continuation=true` blocks the spend. The
confirmation-blocked path now keeps the proposed candidate on the `STOP_BLOCKED` decision so UI/CLI
clients can show the exact upgrade being requested. Candidate reasons also explicitly say when a
provider/model has no benchmark quality prior, so unbenchmarked models do not look fully measured.

Forty-second slice 2026-06-27: persisted benchmark rows now have an explicit sample-size gate before
they can influence provider/model quality. `execution.continuation.benchmark-min-sample-tasks`
defaults to 20. `ModelProviderCandidateService` still reads smaller DONE `benchmark_run` rows and
shows their resolved count in candidate reasons, but it leaves `benchmarkQualityScore` unset for
those rows so ranking falls back to YAML/local/validation evidence. This prevents a tiny 5/5 or 1/1
benchmark from being treated as a fully measured model while preserving operator visibility.

Forty-third slice 2026-06-27: run submit now has a per-run confirmation guard for automatic
continuation spend. `RunRequest.requireConfirmationBeforeExpensiveContinuation=true` writes a
one-shot `RunContinuationConfirmationFlag`; `RunOrchestrator` consumes it at pipeline start and
passes it into `ExecutionRequest`; `SequentialExecutor` applies `serverConfig || perRunFlag` before
calling `GoalContinuationAdvisor` / `GoalContinuationPolicy`. This lets a user choose "ask before
upgrading this run" without allowing them to bypass an operator-level confirmation requirement.

Forty-fourth slice 2026-06-27: confirmation-blocked continuation proposals now have a concrete
approval path. `POST /api/runs/{id}/tasks/{taskId}/continuation/approve` reads the task's persisted
`continuationDecisionJson`, requires `STOP_BLOCKED` plus either explicit spend confirmation or
benchmark-confidence confirmation, extracts the stored selected provider/model, and queues the
existing same-task retry machinery with that override. The run-detail Steps tab shows an Approve
upgrade action only for those persisted states, so generic blocked tasks cannot silently spend on a
stronger model.

Forty-fifth slice 2026-06-27: automatic continuation now respects benchmark confidence. Candidate
scores carry `benchmarkQualityPriorAvailable`, which is true only when a persisted or external
benchmark-quality signal met the configured sample gate. The new default-on
`execution.continuation.require-benchmark-prior-for-automatic-continuation` blocks otherwise
selected `STEP_UP_MODEL` / `CHANGE_PROVIDER` / `STEP_DOWN_MODEL` candidates as `STOP_BLOCKED` when
they lack usable benchmark priors, preserving the selected candidate for the approval endpoint
instead of auto-spending from YAML/default assumptions alone.

Forty-sixth slice 2026-06-27: the approval path now accepts the benchmark-confidence blocked state
created by the forty-fifth slice. Both backend validation and the run-detail `Approve upgrade`
predicate now treat `STOP_BLOCKED` with
`policy.requireBenchmarkPriorForAutomaticContinuation=true` and
`selectedCandidate.benchmarkQualityPriorAvailable=false` as approvable. Candidates that are already
benchmark-backed still cannot use that reason to bypass normal stop semantics.

Forty-seventh slice 2026-06-27: continuation advice now sees consumed escalation budget before it
selects the next model/provider action. `SequentialExecutor` passes current provider-switch,
model-step-up, and model-step-down usage counters into `GoalContinuationAdvisorRequest` on every
task-boundary decision, including rerun decisions after an automatic continuation already consumed a
counter. The typed/persisted policy now exposes `providerSwitchesUsed`, `modelStepUpsUsed`, and
`modelStepDownsUsed` beside the max values, so the policy can stop as "no useful escalation remains"
instead of recommending an impossible upgrade that only the action-skip layer rejects later.

Forty-eighth slice 2026-06-27: web run submission now exposes the per-run confirmation guard for
automatic model/provider upgrades. `IdeCompose` adds a `Confirm model upgrades` advanced checkbox,
sends `requireConfirmationBeforeExpensiveContinuation=true` on `POST /api/runs` when selected, and
shows the setting in the Review step. This makes the user-config path available from the main web
surface instead of only through raw API/CLI payloads.

Forty-ninth slice 2026-06-27: approved blocked-continuation retries now consume the same escalation
budget as automatic continuation. Confirmation-blocked policy decisions store `policy.proposedAction`,
the approval path carries that action into the retry override, and `ExecutionRequest` can seed initial
provider-switch / model-step usage before `SequentialExecutor` evaluates the resumed task. Legacy
persisted proposals that only have the proposed action in reason text are parsed defensively, so older
blocked approvals still count against the same configured ceilings.

Fiftieth slice 2026-06-27: automatic continuation actions now survive reloads. V252 adds
`agent_task.continuation_actions_json`; `SequentialExecutor` appends executed or skipped
continuation action records with decision, provider/model, limit name, used/max counts, and reason;
`TaskSummary.continuationActions` exposes that history; and the run-detail Steps card renders the
latest action. This closes the observability gap where the rerun's final continuation decision could
overwrite the earlier "system stepped up" advisory on the task row.

Fifty-first slice 2026-06-27: final review now follows a successful automatic quality recovery.
`TaskOutcome` carries the executed continuation action provider/model from a same-task rerun, and
`RunOrchestrator` promotes the BUILD-mode reviewer route to the latest successful or warning-level
`STEP_UP_MODEL` / `CHANGE_PROVIDER` action. Normal complexity tier routing does not trigger this,
failed actions do not trigger this, and `STEP_DOWN_MODEL` does not weaken the reviewer.

Fifty-second slice 2026-06-27: approved blocked-continuation retries now share the same durable
action trail as automatic reruns. `RunLifecycleService.approveContinuation` appends an `APPROVED`
entry to `agent_task.continuation_actions_json` only after the stored provider/model retry passes
normal retry validation and is queued, while still seeding the provider-switch / model-step usage
counters from the proposed action.

Fifty-third slice 2026-06-27: approved blocked-continuation retries now also emit live typed
continuation-action evidence. `RunEvent.CONTINUATION_ACTION` carries a `status` data field
(`EXECUTED` for automatic reruns, `APPROVED` for user-approved upgrades), and
`RunLifecycleService.approveContinuation` emits the approved action after the retry is accepted.
`AgentRunEventBridge` mirrors that event into active run-scoped agent sessions without a separate
event type.

Fifty-fourth slice 2026-06-27: soft-completed edits now count as low-confidence quality evidence
for continuation. When a worker writes files but does not cleanly prove completion,
`SequentialExecutor` emits `completionConfidenceLow=true` in `GOAL_DECISION`; the pure
`GoalDonenessEvaluator` keeps the task in `NOT_DONE_BUT_PROGRESSING` but marks
`anotherModelProviderMightHelp=true`. That lets the existing quality-first, benchmark/confirmation
guarded advisor propose a bounded step-up or provider change even when smoke validation passes.

Fifty-fifth slice 2026-06-27: reviewer low-quality evidence is now explicit at final proof time.
`ValidationReportGoalProofEvaluator` maps reviewer `WARN` / `FAIL` ledger rows to
`FAILED / STOP_FAILED` with reviewer-specific incomplete/risk items and
`anotherModelProviderMightHelp=true`, instead of leaving unresolved-review runs as a generic
uncertain final proof. This is still a proof/visibility slice; automatic post-review worker reruns
remain a separate control-flow follow-up.

Fifty-sixth slice 2026-06-27: post-review reviewer failure now enters the same continuation advisor.
`RunOrchestrator` watches the BUILD-mode reviewer result and, when it sees `UNRESOLVED_ISSUES`,
`REVIEW_FAILED`, or a reviewer exception, emits a post-review `CONTINUATION_DECISION` for the latest
usable worker task. The decision uses the same quality-first candidate ranking, benchmark-prior
guard, confirmation guard, provider-switch ceiling, and model-step ceiling as task-boundary
continuation. This still stops at decision emission; the remaining control-flow work is to execute
the selected post-review task rerun, re-run reviewer on the updated workspace, and attribute the
second worker/reviewer token deltas honestly.

Fifty-seventh slice 2026-06-27: post-review selected-candidate execution is wired. When the
post-review decision is `STEP_UP_MODEL` or `CHANGE_PROVIDER`, `RunOrchestrator` emits a typed
`CONTINUATION_ACTION`, appends durable action history, resets the target task to `PENDING`, reruns
only that task through `SequentialExecutor.execute(..., ResumeRequest)` with the selected
provider/model, and seeds the escalation counters with both prior continuation actions and the
selected post-review action. After the rerun it persists task rows, attributes the second worker
delta, runs lint, re-runs the BUILD-mode reviewer, and attributes the second reviewer delta.
`STOP_BLOCKED` remains approval-only, and `STEP_DOWN_MODEL` does not execute for reviewer failure.

Fifty-eighth slice 2026-06-27: second-pass reviewer accounting is now additive. `RunTokenAttributor`
has an additive phase-attribution path for repeated phases, and the post-review reviewer rerun uses
it so `pipeline_run.reviewer_input_tokens` / `reviewer_output_tokens` retain the first reviewer pass
plus the post-review reviewer pass. Run totals still receive only the new second-pass delta once.

Fifty-ninth slice 2026-06-27: post-review worker accounting is now target-task-only. The
resume-aware executor returns synthetic `SKIPPED` outcomes for already-terminal tasks, so the
post-review worker delta now uses a dedicated single-task attribution path that writes only the
rerun target task row from its `RunCostTracker.snapshotTask` provider/model evidence and adds the
worker delta to run totals once.

Sixtieth slice 2026-06-27: reviewer exceptions now preserve reviewer-failure evidence. Initial and
post-review reviewer exceptions are converted to a synthetic `ReviewerResult.REVIEW_FAILED`, and
terminal status resolution checks `REVIEW_FAILED` before failed final-build evidence so the run and
validation report keep `REVIEWER_FAILED` instead of degrading to `NO_REVIEW_RESULT` or
`FINAL_BUILD_FAILED`.

Sixty-first slice 2026-06-27: benchmark-prior guarded automatic escalation is now selection-aware.
When `execution.continuation.require-benchmark-prior-for-automatic-continuation=true` and the user
has not explicitly approved the upgrade, `GoalContinuationPolicy` first searches for a
benchmark-backed escalation candidate. If one fits, the task can auto-step-up / change provider
without stopping on a higher-scoring unbenchmarked candidate; if none fits, the best unbenchmarked
proposal is still blocked as `STOP_BLOCKED` for approval.

Sixty-second slice 2026-06-27: worker/provider breakdown now reaches the continuation advisor.
`SequentialExecutor` derives `providerBehaviorPoor=true` from unrecoverable worker `ERROR` outcomes
and provider/router/timeout-style failure text, passes it into `GoalContinuationAdvisor`, mirrors it
through fallback `GoalContinuationPolicy`, and persists it in the continuation policy JSON. This
lets breakdowns prefer bounded `CHANGE_PROVIDER` advice instead of treating every failure as generic
low-quality or missing-proof evidence.

Sixty-third slice 2026-06-27: advisor-backed continuation decisions now preserve ranking evidence.
`GoalContinuationAdvisor` adds compact `rankingPolicy`, ranking reasons, top eligible candidates,
and rejected-below-threshold candidates to the continuation policy payload. The selected model was
already quality-first internally; this makes the quality > cost > latency ordering visible in SSE,
durable task JSON, and reloadable run evidence.

Sixty-fourth slice 2026-06-27: the run-detail Steps tab now renders continuation ranking evidence.
`TaskSummary.continuationDecision.policy` is typed with ranking-policy fields and compact candidate
rows, and `IdeRunResult` shows the ranking rule, top eligible candidates, quality/cost/latency
metrics, benchmark-prior marker, and rejected-below-quality-threshold count. This closes the UI gap
where the backend persisted ranking evidence but operators still had to inspect raw JSON to see why
one model/provider was preferred over another.

Sixty-fifth slice 2026-06-28: no-write worker stalls now count as provider/model breakdown
evidence. `SequentialExecutor` sets `providerBehaviorPoor=true` not only for hard worker `ERROR`
and provider/router/timeout text, but also for zero-write `STOPPED_WITHOUT_COMPLETE`,
`ITERATION_LIMIT_EXCEEDED`, `COMPLETED_BY_STAGNATION`, and `WALL_CLOCK_EXCEEDED` outcomes. If the
worker made real file edits, the same terminal outcomes are still treated as partial-progress
evidence rather than automatic provider breakdown.

Sixty-fifth slice 2026-06-27: provider-breakdown escalation is now pinned against the benchmark
confidence guard. Focused policy/advisor tests cover the DeepSeek-style failure mode: when the
current provider breaks down and benchmark-prior gating is enabled, the policy auto-selects a
benchmark-backed alternate provider before a higher-scoring unbenchmarked candidate; if no
benchmark-backed alternate fits, it stops as `STOP_BLOCKED` while preserving the unbenchmarked
provider-change proposal for approval.

Sixty-sixth slice 2026-06-27: approved continuation retries now carry explicit user-confirmation
state into the resumed executor pass. `ExecutionRequest` has `userConfirmedExpensiveContinuation`;
`RunLifecycleService.approveContinuation` sets it when dispatching the approved retry; and
`SequentialExecutor` passes it into both advisor-backed and fallback continuation policy, then
persists it in the policy JSON. This prevents an approved unbenchmarked/expensive proposal from
being treated as unconfirmed during the resumed retry while preserving the consumed escalation
counters.

Sixty-seventh slice 2026-06-27: approved continuation confirmation is now scoped, not blanket.
`ExecutionRequest`, `GoalContinuationAdvisorRequest`, and `GoalContinuationRequest` carry the
approved action/provider/model alongside `userConfirmedExpensiveContinuation`. `RunLifecycleService`
fills that scope from the stored blocked proposal, `SequentialExecutor` persists it in policy JSON,
and `GoalContinuationPolicy` bypasses benchmark-prior or confirmation guards only when the selected
candidate exactly matches the approved action/provider/model. A different later provider, model, or
action still stops as `STOP_BLOCKED` for a fresh approval.

Sixty-eighth slice 2026-06-27: approved post-review continuation now regenerates reviewer proof.
`RunLifecycleService.approveContinuation` parses `policy.source=POST_REVIEW` from the persisted
blocked proposal. Task-boundary approvals still rerun the worker task and resolve terminal status
from that retry, but post-review approvals rerun the task with the selected provider/model, then run
reviewer and validation before terminal status. The approval API response now includes `postReview`
so clients can distinguish the heavier reviewer-backed approval path.

Sixty-ninth slice 2026-06-27: approved continuation retries now account for upgrade spend.
`RunLifecycleService` snapshots `RunCostTracker` around the approved worker retry and attributes the
delta to the target task with `RunTokenAttributor.attributeWorkerAdditionalTask`. When the approval
came from post-review continuation, the reviewer rerun also records its token delta through
`attributePhaseAdditional(REVIEWER)`. This keeps cost evidence honest for user-approved upgrades
instead of hiding retry/reviewer spend behind the control-plane path.

Seventieth slice 2026-06-27: persisted benchmark priors are now task-category compatible, not just
sample-size gated. `ModelProviderCandidateService` still reads DONE `benchmark_run` rows fail-soft
and keeps incompatible rows visible in candidate reasons, but it sets `benchmarkQualityScore` only
when `total_tasks >= execution.continuation.benchmark-min-sample-tasks` and the suite applies to the
current task category. Terminal-Bench backs `TERMINAL_TASK` / `BENCHMARK_PATCH`; SWE-bench backs
bug/test/project patch categories; ProgramBench backs bug/test/benchmark patch categories. This
prevents Terminal-Bench evidence from automatically approving normal bug-fix or greenfield BUILD
upgrades while preserving user-visible approval proposals for unbenchmarked or unrelated-suite
candidates.

Seventy-first slice 2026-06-27: automatic continuation now respects provider key/org-policy
availability. `ProviderAvailabilityMap` converts `AvailableProviderResolver.resolve(runId)` into
the candidate-service availability map, including the `gemini` / `google` alias pair. Task-boundary
`SequentialExecutor` and post-review `RunOrchestrator` both pass that map into
`GoalContinuationAdvisor`, so unavailable providers stay visible in ranking evidence but are forced
below the quality threshold and cannot be selected for automatic `STEP_UP_MODEL` /
`CHANGE_PROVIDER` actions.

Seventy-second slice 2026-06-27: build-smoke failures can now upgrade before the weak retry.
`SequentialExecutor` evaluates `GoalDonenessEvaluator` / `GoalContinuationAdvisor` immediately after
the primary worker attempt fails smoke validation, before the legacy same-provider build-error retry.
When the selected continuation candidate is a different provider/model and the relevant
provider-switch or model-step limit still has capacity, the executor emits the same durable
goal/continuation/action evidence and reruns the task with that candidate. If no eligible candidate
exists or the limit is exhausted, the existing same-provider retry path continues unchanged.

Seventy-third slice 2026-06-27: approval-blocked pre-retry upgrades now stop before spending the
weak retry. When the pre-retry continuation decision is `STOP_BLOCKED` with a selected candidate
because benchmark-prior or confirmation policy blocks auto-spend, `SequentialExecutor` marks the
task `PARTIAL`, persists the selected-candidate proposal, emits the normal goal/continuation
events, and returns without the same-provider retry. Existing approval flow can then resume with
that stored provider/model. Non-candidate blockers and exhausted action limits keep existing
behavior.

Seventy-fourth slice 2026-06-27: typed goal/continuation events now refresh live run detail.
`RunDetailSseService` treats `GOAL_DECISION`, `CONTINUATION_DECISION`, `CONTINUATION_ACTION`, and
`CONTINUATION_ACTION_SKIPPED` as detail-refresh triggers and emits a fresh `RUN_DETAIL` payload.
That makes blocked upgrade proposals, approval buttons, and continuation action history visible in
the Steps tab as soon as the decision is persisted, not only after a later task-status event or
manual reload.

Seventy-fifth slice 2026-06-27: continuation proof is now persisted before live decision events.
`SequentialExecutor` writes `agent_task.goal_doneness_json` and `continuation_decision_json` before
emitting `GOAL_DECISION` / `CONTINUATION_DECISION`, so the `RUN_DETAIL` refresh triggered by those
events reads the same proof/proposal that caused the event. This closes the race where a blocked
upgrade proposal could be emitted live but the refreshed Steps payload still reflected the previous
task row.

Seventy-sixth slice 2026-06-27: blocked upgrade approvals now carry an explicit approval blocker.
Task-boundary `SequentialExecutor` decisions and post-review `RunOrchestrator` decisions write
`approvalRequiredReason` when `STOP_BLOCKED` keeps a selected provider/model candidate for approval.
The reason distinguishes missing compatible benchmark evidence (`BENCHMARK_PRIOR_REQUIRED`) from
explicit spend confirmation (`EXPENSIVE_CONTINUATION_CONFIRMATION_REQUIRED`). The run-detail Steps tab renders the reason beside the Approve
upgrade action, with policy-based fallback inference for older persisted decisions.

Seventy-seventh slice 2026-06-28: benchmark-confidence gating is now configurable per submitted
run. `RunRequest.requireBenchmarkPriorForAutomaticContinuation` is stored as a one-shot
`RunContinuationBenchmarkPriorFlag`, consumed by `RunOrchestrator`, carried through
`ExecutionRequest`, and applied by `SequentialExecutor` / post-review continuation decisions
instead of blindly using only the server default. Web Compose exposes this as "Require benchmark
evidence": checked sends the one-shot `true` override and keeps unbenchmarked selected candidates
blocked for approval; unchecked omits the field and leaves the server default in control. Approved
blocked-continuation retries preserve the stored policy from the proposal.

Seventy-eighth slice 2026-06-28: submit-time continuation policy markers are now written before the
async pipeline can consume them. REST submit passes `requireConfirmationBeforeExpensiveContinuation`
and `requireBenchmarkPriorForAutomaticContinuation` into `RunOrchestrator.submitRun`; the
orchestrator applies the Redis markers after persisting the run and before `pool.submit(...)`.
This removes the race where the controller wrote those flags after `submitRun` returned while the
pipeline thread could already be starting and consuming server defaults.

Seventy-ninth slice 2026-06-28: candidate ranking now implements the documented near-quality tie
rule instead of requiring exact equal quality scores before cost and latency matter. `ModelProviderRankingPolicy`
keeps the hard quality threshold, builds anchored quality bands from the best remaining score using
`execution.continuation.quality-tie-band` (default 1.0), and uses cost then latency inside each
narrow band. `GoalContinuationAdvisor` persists `rankingQualityTieBand` in continuation policy JSON
so operators can audit whether an upgrade chose a slightly cheaper/faster near-quality candidate or
a clearly higher-quality one.

Eightieth slice 2026-06-28: the run-detail Steps ranking panel now renders the applied quality tie
band and benchmark-confidence mode beside the candidate order. This makes the unbenchmarked-model
behavior explicit: operators can see whether unbenchmarked upgrades need approval or are allowed to
auto-run for that submitted run, alongside the candidate's `bench` / `no bench` marker.

Eighty-first slice 2026-06-28: validated ENTERPRISE per-run provider/model pins are now hard
constraints for automatic continuation. `RunOrchestrator` threads the validated pin state into
`ExecutionRequest.routePinned`; `SequentialExecutor` and post-review continuation pass it through
the advisor; and `GoalContinuationPolicy` stops proposed step-up, step-down, or provider-change
actions as `STOP_BLOCKED` with `blockedAutomaticContinuationReason=ROUTE_PINNED` while keeping the
better candidate visible. This closes the silent override gap without deciding the separate product
question of whether a future explicit user approval may override a pinned route.

Eighty-second slice 2026-06-28: route-pinned blocks no longer masquerade as approval-blocked
upgrades in the pre-smoke retry path. `SequentialExecutor.approvalBlockedContinuationProposal`
now returns true only for benchmark-prior or expensive-confirmation blocks. When a pinned route
blocks the stronger candidate, the old same-provider/model build retry is still allowed instead of
immediately marking the task `PARTIAL`.

Eighty-third slice 2026-06-28: automatic upgrade no longer requires benchmark priors by default.
`execution.continuation.require-benchmark-prior-for-automatic-continuation` now defaults false in
`application.yml`, the task-boundary and post-review `@Value` fallbacks match that default, and web
Compose opens with "Require benchmark evidence" unchecked. This lets the quality-first ranking use
YAML/local/available benchmark evidence automatically before every model has a compatible benchmark
row, while preserving benchmark-required mode as an explicit per-run/server guard.

Eighty-fourth slice 2026-06-28: web Compose no longer sends a negative benchmark-prior override
when "Require benchmark evidence" is unchecked. `buildIdeComposeRunRequest` omits
`requireBenchmarkPriorForAutomaticContinuation` unless the checkbox is checked, so unchecked means
"use the server default" instead of forcibly disabling an operator-level benchmark-required config.
`IdeSurfaces.test` pins both unchecked omission and checked `true` emission.

Eighty-fifth slice 2026-06-28: final reviewer build failure now enters post-review automatic
continuation. If the BUILD-mode reviewer completes but still reports `finalBuildPassed=false` after
its normal fix-build pass, `RunOrchestrator` treats that as reviewer low-quality evidence, emits the
same post-review continuation decision, and can automatically rerun the latest usable worker task
with the selected stronger provider/model. The low-quality goal evidence is labelled
`FINAL_BUILD_FAILED` so run events and durable task policy distinguish it from reviewer exceptions
or unresolved semantic issues.

Eighty-sixth slice 2026-06-28: low final validation reports now enter automatic continuation before
terminal status. `RunValidationKickoff.generateValidationReport` returns the saved report to
`RunOrchestrator`; if the report result is `FAILED` / `NEEDS_ATTENTION` or `verifiedScore` is below
`execution.continuation.post-review.required-quality-threshold`, the orchestrator emits a
`POST_VALIDATION_REPORT` continuation decision for the latest usable worker task. Eligible
`STEP_UP_MODEL` / `CHANGE_PROVIDER` actions rerun the task, rerun reviewer, and regenerate the
validation report. If no action runs, terminal status is stamped `PARTIAL` with
`VALIDATION_REPORT_LOW_QUALITY` instead of leaving a low-score run as clean completion.

Eighty-seventh slice 2026-06-28: run-detail continuation visibility now includes the proposal/action
source. `TaskSummary`'s frontend type includes `continuationDecision.source`,
`continuationDecision.policy.source`, and `continuationActions[].source`; the IDE Steps tab formats
`POST_REVIEW` as reviewer/build-triggered and `POST_VALIDATION_REPORT` as final-validation-triggered
in the goal line, approval reason, action history, and ranking panel. This keeps automatic step-up
behavior inspectable after reload: operators can tell whether the rerun came from task execution,
reviewer/build failure, or low final validation quality.

Eighty-eighth slice 2026-06-28: unbenchmarked gateway coding models now have explicit YAML quality
fallbacks for automatic continuation ranking. The candidate scorer already falls back to
`quality-class` before `chain-tier` / `cost-class` when benchmark/local/validation signals are
missing; this slice fills the missing `quality-class` metadata for the newer OpenRouter/Fireworks
coding rows, including the `UTILITY`-tier `qwen3-coder-next` row that would otherwise fall below the
default 70 quality threshold despite being a viable coder. The result is deterministic
quality-first ranking for unbenchmarked candidates while `benchmarkQualityPriorAvailable=false`
still keeps benchmark-required mode approval-gated.

Eighty-ninth slice 2026-06-28: the advisor now has regression coverage for the cheap-DeepSeek
starting route. A current `deepseek-v4-flash-native` candidate with insufficient quality can select
`deepseek-v4-pro-native` as `STEP_UP_MODEL` when the run is not route-pinned and benchmark-prior
approval mode is not enabled. The test also asserts that the advisor does not pass a model allow-list
by default, so stronger same-provider candidates remain visible. Docs now call out the operational
rule: use YAML/org matrix defaults to start cheap and allow automatic step-up; a per-run `modelName`
pin means "do not silently change this route."

Ninetieth slice 2026-06-28: LOW-complexity worker starts are now configurable without turning them
into route pins. `execution.continuation.low-complexity-worker-start-tier` defaults to `WORKHORSE`
to preserve the post-regression safe default, but accepts `LIGHTWEIGHT` so an operator can start
simple tasks on DeepSeek Flash / mini / Haiku and let the existing automatic continuation policy
step up to a stronger model when completion or final quality is weak. Invalid values fail closed to
`WORKHORSE`. `SequentialExecutorTierConfigTest` pins default, opt-in, and invalid-value behavior.

## Open Questions

- Should the planner emit an explicit `taskCategory` field in `PlannedTask`, or should the
  executor-local semantic classifier remain the compatibility bridge until enough run data proves
  the category set?
- Should user-selected provider be a hard pin, or can ExecuteSpec ask for confirmation to override it on quality grounds?
- Validation-report evidence-ledger surfacing now reaches `/report`, the run-result Evidence tab,
  run detail, and run-scoped agent-session action events, and produces a conservative
  `runGoalDecision` proof that is durably cached on the run.
- Should a failed step-up count against credits if it stops as waste-risk before producing useful output?
