# Goal Doneness, Routing, and Waste Guard

> Status: incremental implementation in progress, default-off runtime enforcement only. Date: 2026-06-26.

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
    enabled: false
    required-quality-threshold: 70.0
    max-no-progress-iterations: 28
    max-task-retries: 1
    max-high-complexity-retries: 2
    max-false-done-claims: 2
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
    acting:
      enabled: false
    max-reruns-per-task: 1
```

The first wired subset is advisory-only: `required-quality-threshold`,
`max-provider-switches-per-run`, `max-model-step-ups-per-task`,
`max-model-step-downs-per-task`, and
`require-confirmation-before-expensive-continuation`. The first evidence-ceiling subset is
disabled unless positive: `max-total-cost-usd`, `max-total-tokens`, and
`max-task-wall-clock-ms` feed `GOAL_DECISION` budget evidence and can stop as
`STOP_WASTE_GUARD` when crossed before proof of completion. The next acting subset is opt-in via
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

Use the existing latency plan's `TaskWaveScheduler` idea. Parallelize only when the plan can prove independent tasks and enforce a low global concurrency cap. Keep worker mutation within one task serial. Keep `WorkerAgent` read-only batch parallelism as-is.

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
returns aggregate `ABORTED`. `STOP_DONE` intentionally remains informational until a run-level goal
proof exists. Provider/model step-up, step-down, and change-provider decisions are still pending.

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

Fifteenth slice 2026-06-26: `SequentialExecutor` can now optionally act on non-terminal
continuation decisions. Default behavior is unchanged (`execution.continuation.acting.enabled=false`).
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

## Open Questions

- What are the initial task categories? Candidate set: `GREENFIELD_BUILD`, `PROJECT_PATCH`, `BUG_FIX`, `TEST_FIX`, `REFACTOR`, `BENCHMARK_PATCH`, `TERMINAL_TASK`, `READ_ONLY_HELP`.
- What is the required quality threshold per category and tier?
- Should user-selected provider be a hard pin, or can ExecuteSpec ask for confirmation to override it on quality grounds?
- Validation-report evidence-ledger surfacing now reaches `/report`, the run-result Evidence tab,
  and run-scoped agent-session action events, and produces a conservative `runGoalDecision` proof;
  remaining work is deciding whether that proof should update persisted run-level goal state or
  stay as final evidence attached to the report/session stream.
- Should a failed step-up count against credits if it stops as waste-risk before producing useful output?
