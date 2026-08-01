# `harness/` — V2 pipeline orchestration

Approved continuation resumes carry an exact target task, provider, model, action, and
schema-versioned proposal digest. `SequentialExecutor` applies the strict provider/model override
only to that target. The exact path uses a mechanical budget-only config and never invokes initial
routing, tier/model resolution, adaptive routing, or cross-model failover; unavailable approved
providers fail closed before `WorkerAgent` can enter the provider boundary. It also verifies
physical provider-call evidence before persistence. Fresh and crash-recovered runs without such
approval retain normal matrix routing. `ExecutorResumeRequest` owns exact resume identity/route validation;
`PlanTaskMetadata` owns the compact dependency/file views used by wave scheduling so the executor
does not absorb those policies.

Recovery fencing: `SequentialExecutor` snapshots each task's `recovery_attempt` generation before
dispatch. Task status, goal/continuation evidence, and action-history persistence use a pessimistic
row lock while takeover uses its atomic generation CAS, so a stale worker cannot overwrite the
resumed worker's terminal state. `RunPersistenceWriter` takes the same lock and skips late
aggregate updates once a task is terminal; it only repairs a non-terminal row when the worker died
before its first status flush. This is a durable write fence, not proof that an in-flight provider
call itself can be replayed across JVMs.

Every pessimistic-lock task write (`findByIdForUpdate`) MUST run inside a transaction. Status
transitions go through `TaskStatusPersistenceService.persist` (`@Transactional` + `@Retryable`), and
the V250 goal/continuation evidence goes through `TaskStatusPersistenceService.persistGoalDecisionJson`
(same seam, added 2026-07-17). The executor's inline lock in `persistGoalAndContinuationDecision`
survives only as the mocked-repo/bounded-constructor fallback. Reaching a pessimistic lock without an
ambient transaction throws `TransactionRequiredException`: on the normal terminal path a transaction
exists, but on the fast-fail path (early worker router/provider error) it does not — before the fix
that silently dropped the goal decision exactly when an operator needed it (RL-2026-07-070).

> Last reviewed: 2026-06-10 (1.0.0-M1).
>
> V1 `PipelineHarness` was deleted in deprecation P1-P6 (2026-04-13). The live entry point is `integration/RunOrchestrator`; `harness/` now holds the V2 sequential executor + the workspace + gate primitives that survived from the V1 era.

## Key files

Initial provider/model routing is evidence-gated by `execution.initial-routing.enabled`.
When enabled, `SequentialExecutor` emits a typed `INITIAL_ROUTING_DECISION` event with the
requested and selected routes, candidate/rejection labels, evidence-use marker, and reasons.
This event is audit-only; it does not enable routing or change the default-off policy.

Run-scoped provider/model intent is durable from V275 onward. `RunRoutePolicyService` reads the
persisted route contract (with a bounded cache), and exact-lock runs must bind the same
`RunModelOverrides` route after restart or retry. Binding failure is terminal for an exact route;
the executor must not silently return to tier defaults.

| File | Purpose |
|------|---------|
| `SequentialExecutor.java` | Per-task tier-routed worker dispatch. For each task: compose system + user prompt → `WorkerAgent.run()` → if worker hit `completeTask`, run `buildProject` smoke (final task only on multi-task plans) → pass=SUCCESS / fail=ONE retry with build error injected unless continuation policy selects a stronger provider/model first. Aggregate: ALL_SUCCESS / PARTIAL_WITH_WARNINGS / FAILED / ABORTED. Per-task tier-config: LOW defaults to WORKHORSE with LOW caps (60 iters / 4k tokens / 30-min wall-clock) but can start on LIGHTWEIGHT via `execution.continuation.low-complexity-worker-start-tier=LIGHTWEIGHT`, MEDIUM=WORKHORSE (120 / 8k / 45-min), HIGH=PLANNING (180 / 16k / 75-min); project-run multiplier ×2 on iters + wall-clock. PATCH-mode caps now live in `engine/CodeGenEngineImpl`: Anthropic/DeepSeek/OpenRouter/Fireworks 220 / OpenAI 150 / Gemini 180. **Provider prompt tuning split (2026-06-10):** BUILD-mode appends only `prompts/fragments/provider/<provider>/build.md`; PATCH-mode appends `.../patch.md` in `CodeGenEngineImpl`, so SWE-bench/repro/source-only directives no longer bleed into normal BUILD tasks. **Soft-completion gate** (Fix C of `feature/worker-runaway-fixes`): when worker outcome is `COMPLETED_BY_STAGNATION` or `STOPPED_WITHOUT_COMPLETE` with `totalWriteCalls > 0`, falls through to the same smoke gate that COMPLETED uses — multi-task intermediate task with writes → `COMPLETE`; final smoke fail → `PARTIAL`. Emits typed `TASK_WAVE_PLAN` at executor start using `TaskWaveScheduler.scheduleBounded(...)` and configured `execution.continuation.max-parallel-tasks`; the event is explicitly `SEQUENTIAL_ADVISORY` and does not enable concurrent writes. **Goal-decision control (RL-2026-06-198):** terminal task branches build conservative `GoalEvidence`, run the pure `GoalDonenessEvaluator`, attach the resulting `GoalDonenessDecision` to `TaskOutcome`, and emit both typed `GOAL_DECISION` SSE data plus the legacy `Goal decision: STATE -> DECISION (...)` `LOG`. Goal decision data includes `recommendedNextStep`; goal evidence includes validation/build/diff signals plus clear environment-block, unsafe-continuation, and low-completion-confidence signatures, `RunCostTracker` input/output/cache/reasoning tokens, cost USD, task wall-clock, configured `max-no-progress-iterations` / `max-false-done-claims` / `max-repeated-failures` / `max-edit-churn` thresholds, and a concrete budget-ceiling reason; positive `execution.continuation.max-total-cost-usd`, `max-total-tokens`, or `max-task-wall-clock-ms` values can trip `WASTE_RISK` / `STOP_WASTE_GUARD` before proof of completion. They also pass that doneness decision, dispatched provider/model, task category, failover preference order, provider-breakdown evidence (hard worker `ERROR`, provider/router/timeout text, or zero-write `STOPPED_WITHOUT_COMPLETE` / `ITERATION_LIMIT_EXCEEDED` / `COMPLETED_BY_STAGNATION` / `WALL_CLOCK_EXCEEDED`), already-consumed provider-switch / model-step counters, and `execution.continuation.*` advisory limits through `GoalContinuationAdvisor` when available; task category is derived from existing `PlannedTask` text/file hints into `GREENFIELD_BUILD`, `PROJECT_PATCH`, `BUG_FIX`, `TEST_FIX`, `REFACTOR`, `BENCHMARK_PATCH`, `TERMINAL_TASK`, or `READ_ONLY_HELP`, then falls back to `TASK_LOW` / `TASK_MEDIUM` / `TASK_HIGH`. Any of those categories can override the global `required-quality-threshold` via `required-quality-thresholds.*`, and the applied threshold plus resolved threshold map are included in `CONTINUATION_DECISION` policy data. The advisor ranks runtime candidates, persists both payloads to `agent_task.goal_doneness_json` / `continuation_decision_json` including the applied continuation policy, consumed counters, and LOW start-tier evidence (`lowComplexityWorkerStartTierConfigured` / `lowComplexityWorkerStartTierEffective`), then emits typed `CONTINUATION_DECISION` SSE data plus the legacy `Continuation decision: DECISION (...)` log. `TaskSummary` exposes the persisted payloads to run detail clients. If the advisor is absent or fails, the executor falls back to the pure `GoalContinuationPolicy` with the same configured limits. Selected-candidate acting is default-on through `execution.continuation.acting.enabled`; it permits one same-task rerun for `STEP_UP_MODEL`, `STEP_DOWN_MODEL`, or `CHANGE_PROVIDER`; on primary build-smoke failure, this selected-candidate rerun is considered before the legacy same-provider build-error retry, so a weak model can be upgraded immediately when policy has a different eligible candidate. If the selected upgrade is blocked only by benchmark-confidence or confirmation policy, the task is marked `PARTIAL`, the `STOP_BLOCKED` proposal is persisted for approval, and the weak same-provider retry is skipped. The executor reserves actual provider-switch / model-step usage before dispatch, emits typed `CONTINUATION_ACTION` plus the legacy log when a rerun executes, emits typed `CONTINUATION_ACTION_SKIPPED` plus the legacy log when a configured ceiling blocks action, appends both executed/skipped actions to `agent_task.continuation_actions_json` for `TaskSummary.continuationActions`, and never invents a provider/model outside the advisor-selected candidate. Approved blocked-continuation retries seed the same provider-switch / model-step usage counters from the stored proposed action and set `ExecutionRequest.userConfirmedExpensiveContinuation=true` before the resumed task is evaluated; `RunLifecycleService` appends an `APPROVED` history row only after the retry is accepted so approval does not reset the escalation budget, lose the audit trail, or write false approval evidence for unqueueable retries. V249 seeds `execution.goal-decision.enforcement.enabled` default OFF; when enabled, `STOP_BLOCKED`, `STOP_FAILED`, and `STOP_WASTE_GUARD` abort remaining sequential tasks, append synthetic `SKIPPED` outcomes, emit `Goal stop enforced: ...`, and return aggregate `ABORTED`. Task-boundary `STOP_DONE` remains informational even after V251 final run-level proof, because one task proving done does not prove a multi-task run is done; final proof is persisted on `pipeline_run.run_goal_doneness_json` after validation-report generation. Provider/model switching is available only through the bounded selected-candidate continuation action path, and `TaskOutcome` carries the executed continuation action provider/model so `RunOrchestrator` can promote the final reviewer after a successful `STEP_UP_MODEL` or `CHANGE_PROVIDER` recovery. **2026-05-10:** `wallClockSeconds` per tier-config sibling to `maxIterations` (whichever fires first wins); `Outcome.WALL_CLOCK_EXCEEDED`. **`persistTaskStatus`** carries a defensive 4000-char cap on `failureReason` (V184 widens the column to TEXT). Also exposes the boot-time `execute(req, ResumeRequest)` overload that walks per-task status to decide SKIP_TERMINAL / RESUME_FROM_SNAPSHOT / RUN_FRESH on JVM restart. **2026-05-19:** `SYSTEM_PROMPT_BASE` carries a tests-are-mandatory directive (any task touching a function/endpoint/module must ship happy-path + error/edge test before `completeTask`, or justify why untestable); exposed read-only via static `workerSystemPromptBase()` so tier-eval-v2 WORKHORSE cases drive the exact production prompt. **2026-05-25 (RL-2026-05-034, Transfer #1):** `runSmokeTest` now, after a clean compile, RUNS the worker's tests in-loop via `WorkspaceTestRunner` (language-neutral `TestCommandResolver`); a real test failure flips the smoke result to FAILED and reuses the existing build-fail retry. Behind `build.test-exec-gate.enabled` (seeded OFF by V209), fail-safe (no runner → compile-only, today's behavior). The in-loop analog of PATCH reproduce-first — "tests mandatory" now means tests *pass*, not just *compile*. **2026-05-31 (RL-2026-05-059): flipped ON for dev** alongside V217 `worker.build-tdd.enabled`; the worker `VERIFY` directive in `SYSTEM_PROMPT_BASE` called the auto-bound `runWorkspaceTests` tool before `completeTask`. **⚠️ 2026-06-01 (RL-2026-05-061): REVERTED.** The `runWorkspaceTests` tool in native-DeepSeek's schema caused reasoning-only no-edit stalls, so V217 + V209 are **back OFF** and the `runWorkspaceTests` mention is removed from point-4. `SYSTEM_PROMPT_BASE` points 2+4 are now **symptom-anchored**: reproduce with the issue's verbatim MWE; verify the issue's exact symptom changed (*"if your edit didn't change the output, you're in the wrong place — trace where the bad output is produced and fix THERE"*); match the issue's stated contract. Generic (shared base, all providers), no new tool. Validated GPT 0/11 → 4/11 on the addressable gap; net full-50 × all-providers is the owed gate. |
| `WorkerResult.java` | Worker terminal result contract. In addition to outcome, iteration/tool counts, transient final assistant text, tool log, and latency, it carries `repeatedAssistantTextCount` so `SequentialExecutor` can record stale repeated explanations in memory without persisting the text. Durable tool-log entries contain only tool name, opaque argument signature, safe mutation target and `-> OK` / `-> ERR`; `SequentialExecutor` groups repeated failures by that signature. For code-changing tasks, goal evidence intersects this task's successful mutating tool paths with `RunChangeEvidenceService`'s admitted baseline-to-final product diff; raw write-call counts remain diagnostics and cannot satisfy DONE. |
| `ProviderBehaviorPolicy.java` | Failure-scoped classification of real provider breakdown. Completed workers with no explicit failure do not scan success prose for broad transport phrases, preventing requirement text such as "API errors" from causing a false provider switch. |
| `PromptComposer.java` / `ExecutionRequest.originalGoal` / `SourceRequirementEvidence.java` | Every BUILD provider receives the same trust-bounded source contract and exact task-scoped requirement rows. Single-task runs receive the original specification verbatim. Multi-task runs receive whole, untruncated original source lines selected for the task's assigned requirement IDs; if any assignment cannot be mapped with bounded lexical evidence, the composer fails safe to the complete original specification instead of dropping a clause. The planner's task description remains implementation guidance and cannot silently replace or weaken the persisted requirement ledger. Shared worker policy derives adversarial tests from every semantic clause, including applying a later ordered/versioned migration to an already initialized schema; `CREATE IF NOT EXISTS` or one baseline DDL file is initialization only. Provider-specific fragments remain transport/efficiency tuning only. |

Continuation advice resolves the latest successful physical task route from `run_step_usage` before
ranking alternatives. `TaskOutcome.dispatched*` remains the requested/tier-resolved route; policy
evidence records it separately from `currentProvider` / `currentModel` and
`currentRouteAttribution`, preventing a failover response from being evaluated as though the
failed requested provider produced it.

Shared worker policy also preserves clean-state journeys across task boundaries. Conditional empty,
loading, or error branches cannot hide required primary actions; restored collections must retain
complete/unique shape and interactive validation; DOM order/component presence cannot substitute
for responsive, focus, or contrast evidence. The same contract is present in full/skeleton planning,
task expansion, general review, and accuracy assessment, so provider-specific overlays cannot bypass it.

Task-level `GOAL_DECISION` usage fields come from `RunCostTracker.snapshotTask(runId, taskId)`, not
the cumulative run snapshot. Run-wide cost/token snapshots remain separate inputs to configured
`max-total-*` waste ceilings. Do not expose cumulative spend as if each later task consumed it; the
2026-07-24 Kanban run made task 7 appear to use 2.4M input tokens although its attributed calls used
about 98K.

BUILD planning and worker policy must agree on verification ownership: focused maintained tests
travel in the same task as the production code they verify. A later test-only task may add broader
integration/concurrency/E2E coverage, but cannot be the first verification of an earlier task. All
six provider BUILD fragments repeat the conventional-test-tree rule and direct one-off repros to
`/tmp`; root-level scratch smoke files are not product artifacts. Soft-completion counts are
successful mutation calls, not distinct files, and customer/operator wording must say so.

Core host/container command paths used by workers (`ShellTool`, `RunTestsTool`,
`BuildProjectTool`, and `WorkspaceTestRunner`) delegate to `testgen.BoundedProcess`. stdout/stderr is
drained concurrently under a reachable wall-clock deadline and a 1M-character capture cap; never
restore the `waitFor()`-then-`readAllBytes()` ordering, which deadlocks when a verbose test/build
fills the OS pipe buffer.
| `TaskOutcomeMetric` wiring | `SequentialExecutor` records each real terminal task through `TaskOutcomeMetricService` after any bounded continuation rerun, including the final provider/model, semantic task category, deterministic validation result, task-scoped output tokens, worker iterations, and latency. Synthetic skipped outcomes have no provider/model and are ignored by the fail-soft recorder. |

Provider-breakdown text classification is failure-scoped. A worker that reached `completeTask` with
no explicit failure reason is not classified as unhealthy from its success prose; phrases such as
"stable API errors" previously matched the broad `api error` transport signature and caused a green
task to rerun on another provider.
| `WorkerToolLogEvidence.java` | **RR-1599 (2026-07-20)** — capability-keyed reading of worker tool-log entries for the executor's waste guards and delivery evidence. Replaces three literal-name predicates that were blind to registry aliases: `repeatedCommandFailureSignature` never grouped a command issued as `executeCommand` / `build` / `runBuild` / `background_process` (so `max-repeated-failures` could not fire for it), and `countFileWrites` ignored `ast_edit` / `move` / `delete`. `mutationPathSignature` additionally only understood the legacy `"path": "…"` argument form while the live log hashes arguments and carries the path in a `[target=…]` marker — so on the current log format it returned null for EVERY entry and edit-churn evidence was dead. Fail-closed direction is per predicate: an unclassified call-shaped entry IS grouped by the waste guard, but is NOT counted as a file write (over-counting would falsely clear the zero-write provider-breakdown signal). |
| `TaskSimplicityClassifier.java` | Deterministic STEP_DOWN_MODEL eligibility evidence. Only HIGH-complexity, bounded brownfield repairs (BUG_FIX / TEST_FIX / PROJECT_PATCH) that cleanly complete with a small observed iteration/tool/write footprint qualify; greenfield, broad, salvaged, looping, or escalation-worthy tasks remain ineligible. The signal and its bounded evidence are persisted in continuation policy JSON. |
| `ContinuationConfirmationScope.java` | Immutable per-decision confirmation authority plus `TaskSimplicityClassifier` evidence. It keeps step-down eligibility and its persisted policy evidence out of `SequentialExecutor`'s continuation plumbing. |
| `TaskWaveScheduler.java` | Pure dependency-wave scheduler. `schedule(...)` groups tasks by `dependsOn` topological levels. `scheduleBounded(...)` adds a hard wave-size cap and conservative `touchesFiles` conflict checks: unknown/broad touch sets serialize alone, same file or parent/child path conflicts split waves, and only declared disjoint file sets can share a wave. `SequentialExecutor` uses `scheduleBounded(...)` at run start to emit advisory `TASK_WAVE_PLAN` evidence; runtime mutating task dispatch remains sequential. |
| `WorkspaceInitService.java` | Creates fresh git repo for each run. Writes `.gitignore`, `.gitattributes`. Multi-repo runs clone each linked repo into `<workspace>/<runId>/repos/<name>/` via `cloneMultipleRepos`. `copyFromRunRequired` delegates the fail-closed local/GCS lineage copy to `RequiredIterationWorkspaceCopier`: only Git-tracked regular files cross the boundary, nested project repos retain Git metadata/remotes, symlinks and missing/unreadable sources fail the child before planning, and new per-repo baseline snapshots are recorded. Captures per-repo SHA snapshot into `run_repo_snapshot` (V100) for the "reset to run state" feature. |
| `GateStore.java` | Legacy compatibility surface for non-C03C callers plus transient Redis signal cleanup. **RR-241/RR-346 (2026-07-21):** whether a run reaches the plan gate is decided by `integration/PlanGateBypassPolicy`, not the caller's `skipApproval` bit. It is neither a waiter nor production decision/continuation authority: all customer APPROVE/REJECT and pre-approval CANCEL operations use `integration/PlanGateDecisionService`, and all approved continuation uses `PlanGateResumeDispatcher`. Redis is only a short-lived compatibility signal/cleanup aid. |
| `GateRecord` RequirementIR binding | V352 allows ENFORCED `BLOCKED` only for an exact structurally valid immutable RequirementIR artifact whose persisted completeness/blocker summary proves it is not approval-ready. The frozen evidence projection also surfaces deterministic derived blocker codes that older issue ledgers omitted. APPROVE remains restricted to `BOUND`, `READY`, zero blockers, while REJECT/CANCEL remain available. |
| `entity/PlanGateResumeOutbox.java` + V346–V348 gate fields | C03C durable gate protocol. V347 atomically persists exact binding, WAITING continuation intent, and `PLANNING → AWAITING_APPROVAL`; V348 atomically persists canonical decision evidence/request identity and makes APPROVE, REJECT, and CANCEL distinct. The receipt cross-binds provider/mode/fixed-six-decimal `estimateHighUsd`/separate nullable `hardCapUsd`/credits/payer/continuation/report and exact typed-ack fields. New acknowledgements persist uppercase `BREAKING`; complete pre-V348 tuples remain byte-exact and are semantically checked case-insensitively after trimming, with REJECT/CANCEL recorded as `NOT_EVALUATED`. An absent legacy client fingerprint stays absent/null, while a supplied fingerprint must match and ENFORCED requires it. APPROVE commits gate `PENDING → APPROVED`, run `AWAITING_APPROVAL → APPROVAL_RESUMING`, and outbox `WAITING → READY`; REJECT/CANCEL cancel run/outbox together. `PlanGateResumeDispatcher` owns durable selection, validation, handoff, retry and local dispatch; focused collaborator `PlanGateWorkspaceRecovery` owns only bounded workspace I/O and lease/queued-owner renewal. The dispatcher claims READY or expired PROCESSING work with a fenced lease, validates before workspace restore, revalidates at handoff, and commits run `APPROVAL_RESUMING → WORKING` with outbox `PROCESSING → COMPLETED` before executor dispatch. COMPLETED means durable WORKING handoff, not terminal run success. A process-unique `:plan-gate-queued:` execution owner identifies only pre-start Futures; the dispatcher may recover that queued lane with its persisted backoff/attempt budget, while the Future's first instruction atomically transfers ownership to the normal runtime host. Runtime-started crashes are deliberately left to generic checkpoint recovery. Scheduled/startup sweeps recover the outbox; generic startup recovery does not dispatch APPROVAL_RESUMING or queued COMPLETED handoffs. Direct child evidence deletion is blocked while parent-run cascade remains valid; the new report→run FK checks new writes but is `NOT VALID` for historical orphans. `requirement.ir.gate.mode` remains LEGACY. |
| `UiStyleResolver.java` | Resolves design-system / UI style from user request or project defaults. 3-signal priority: explicit hint > planner `ui_style` > project type heuristic. |
| `PromptResourceReader.java` | Single fail-closed reader for optional provider/project prompt overlays; validates dynamic keys as one safe classpath segment and preserves the base prompt when an overlay is missing. |
| `PromptComposer.java` | **P1-4 (2026-08-01):** brownfield BUILD workers receive a deterministic `ADMITTED_HTTP_SURFACE` evidence block — the exact public routes (context-path included) the publication gate verifies, bounded at 60 rows, plus context-path reconciliation guidance — sourced fail-soft from `BrownfieldHttpContractGuard.protectedRoutes`. This closes the live dev failure where a worker wrote a naive controller mapping on a context-path-prefixed app and produced green code + green self-test that the deterministic HTTP gate then correctly failed. Package-local prompt assembly seam for worker system/user prompts, run-journal blocks, and provider/model/project overlay lookup; `SequentialExecutor` retains compatibility facades. Generated task title/description and retry build errors are delimiter-safe `UserMessage` evidence, while fixed worker policy and trusted classpath overlays remain system authority. |
| `DesignSystemProperties.java` | YAML config for the company design-system library. Filesystem-backed (54 design systems shipped). |
| `WorkerTaskRequest.java` | Per-task request shape — `taskId`, `complexity`, model + tier, `maxIterations`, `wallClockSeconds`, `requireBuildVerification`, `requireTestVerification` (patch-mode gate). |
| `ExecutionRequest.java` | Executor input shape. `localHandsMode=true` is set only by CLI local-hands runs and appends a local-only scope prompt so empty working-tree edits do not sprout unsolicited scaffolds/tests. `lowComplexityWorkerStartTier=LIGHTWEIGHT` is the per-run non-pinning way to start LOW tasks on the cheap tier while keeping automatic step-up/change-provider available. |
| `ApprovalGate.java` | Plan-gate interface. |

Zero-write smoke failure is NOT success (RR-262). When the primary AND retry worker both wrote zero
files and the build smoke is still red, the executor previously assumed the failure was
environmental and persisted `COMPLETE` / `TaskOutcome.Status.SUCCESS`, keeping `smokeTestPassed=false`
only as a subordinate field. That is the exact shape of a real broken baseline, an unimplemented
task or a wrong build command, and it is the state in which a success claim is least defensible.
There is no environment-failure classifier and no verify-only task contract to attribute it, so the
task now terminalises `PARTIAL` / `COMPLETED_WITH_WARNING` with an explicit reason. Reinstating a
success path requires typed resolver/runner infrastructure outcomes plus an explicit verify-only
task contract in the plan — never an inference from zero writes.

Continuation approval scope: approved blocked-continuation retries carry both `userConfirmedExpensiveContinuation=true` and the exact approved action/provider/model on `ExecutionRequest`. `GoalContinuationPolicy` may bypass confirmation or benchmark-prior guards only when the later selected continuation matches that stored scope; a different provider, model, or action remains blocked for a fresh approval. `ExecutionRequest.routePinned=true` is set only for a validated ENTERPRISE per-run provider/model pin and blocks automatic `STEP_UP_MODEL` / `STEP_DOWN_MODEL` / `CHANGE_PROVIDER` execution with `blockedAutomaticContinuationReason=ROUTE_PINNED`; the better candidate remains visible as evidence, but the executor does not silently override the pinned route. Route-pinned blocks are not approval-eligible, so the pre-smoke path still allows the legacy same-route build retry instead of marking the task `PARTIAL` immediately. `ExecutionRequest.requireBenchmarkPriorForAutomaticContinuation` can override the server default for one executor pass; web Compose stores it through `RunContinuationBenchmarkPriorFlag` inside `RunOrchestrator.submitRun` before the async pipeline can consume it, and approved retries preserve the saved benchmark-prior policy from the blocked proposal. Persisted and live `STOP_BLOCKED` continuation payloads include `approvalRequiredReason` so clients can distinguish `BENCHMARK_PRIOR_REQUIRED`, `EXPENSIVE_CONTINUATION_CONFIRMATION_REQUIRED`, and `ROUTE_PINNED` instead of inferring from policy internals. Task-boundary, `POST_REVIEW`, and `POST_VALIDATION_REPORT` approvals all use the exact approved route inside the reversible checkpoint → worker → read-only reviewer → deterministic validation → accept/rollback lifecycle. Exact fenced physical worker/reviewer attempts are cross-checked against the approval before attribution and publication; no source keeps a lighter mutation path. Approved retries attribute worker token deltas to the target task via `RunTokenAttributor.attributeWorkerAdditionalTask` and add reviewer usage from the physical attempt ledger.

Automatic cross-model/provider task recovery shares the current workspace but starts a fresh worker conversation at iteration 1. It receives only the bounded `ProviderHandoffDossier.recoveryNote`; the prior model's complete transcript is never replayed into the replacement model. Every BUILD attempt, including smoke retries and selected continuation reruns, receives the configured `execution.continuation.max-edit-churn` live force threshold. Selected replacement requests also set `zeroWriteSalvageAllowed=false`: their attempt-local write counter starts at zero even though the shared workspace already contains the primary patch, so stagnation stops at the progress bound instead of inventing a best-guess edit.

Continuation provider availability: task-boundary `SequentialExecutor` advice now resolves run-specific provider availability through `AvailableProviderResolver` and passes it to `GoalContinuationAdvisor`. Candidate ranking keeps unavailable providers visible but forced below threshold, preventing automatic `STEP_UP_MODEL` / `CHANGE_PROVIDER` actions from selecting providers without keys or allowed org policy. The same availability map is also supplied by `RunOrchestrator` for post-review continuation.

Actual provider/model handoffs also write a bounded atomic `.agent-context/provider-handoff.json` dossier
with the from/to route, decision, reason, and observed failure. When a continuation snapshot is
reloaded, the worker receives one explicit durable handoff note telling it to inspect the existing
workspace and verify the failure rather than blindly repeating the prior route. This is evidence
for bounded retry/recovery, not a claim that an in-flight provider future survived a JVM boundary.

## Pipeline sequencing (PRODUCTION mode)

1. `RunOrchestrator.submitRun(req)` persists the run row + estimate.
2. `SmartClassifierAgent.classify(spec)` → `PlanStrategy`.
3. Strategy router emits the plan via `OutlinePlannerAgent` (FULL_PLAN / SKELETON_ONLY) + optional `TaskExpanderAgent` dependency-wave expansion (parallel only within each independent wave; established prerequisite contracts flow into consumers) + `CoherencePlannerAgent` patch.
   `PipelineExecutorPool` owns these orchestration/expansion executors as a maximum-phase
   `SmartLifecycle`: deployment shutdown interrupts them before Redis/JPA teardown, and pipeline
   owners preserve the durable non-terminal checkpoint for boot recovery rather than terminalizing
   a lifecycle cancellation as FAILED.
4. **Plan approval gate** — `integration/PlanGateOpenService.open(...)` atomically persists exact gate binding, WAITING continuation, and `AWAITING_APPROVAL`; PRODUCTION pauses and only policy-authorized internal automation may bypass. `PlanGateDecisionService` then makes one atomic APPROVE/REJECT/CANCEL decision with canonical evidence and request fingerprint. It cross-binds provider/mode/cost/credits/payer/continuation/report/ack evidence, records an omitted legacy client plan fingerprint as absent/null rather than matched, and performs no filesystem, SSE, executor, Redis, or provider side effect in the transaction.
5. **Approved continuation** — APPROVE commits `APPROVAL_RESUMING` + READY after charging/evaluating policy. `PlanGateResumeDispatcher` leases READY work, runs a database preflight before workspace restore, restores while heartbeating, repeats the fence at atomic WORKING/COMPLETED handoff, and only after commit submits the normal resumed pipeline. AFTER_COMMIT is a latency hint; scheduled/startup outbox sweeps provide recovery. Recovery registers its `FutureTask` before executor submission so fast execution cannot leave a stale active-run handle; completion/rejection cleanup uses compare-and-remove of that exact Future, so a stale task cannot erase a newer registration.
6. **`SequentialExecutor.execute(req)`** runs tasks one at a time. Tier-routed per-task model. Soft-completion gate covers `COMPLETED_BY_STAGNATION` / `STOPPED_WITHOUT_COMPLETE` paths. Final-task smoke + one-retry on fail, except selected-candidate continuation can step up/change provider before that same-provider retry.
7. `ReviewerAgent.review(workspace, plan)` — general + project-type-aware wiring pass + post-edit formatter + a fix-the-red-build third pass (flag `reviewer.third-pass.enabled`, default ON, V202) when the final build is still red.
8. `AccuracyAssessmentService` reads key files and classifies the canonical `Plan.requirements()` ledger without lexical grep overrides (legacy empty-ledger runs fall back to original-spec extraction); it grades `testCoverageLevel` against explicit NONE/PARTIAL/GOOD/COMPREHENSIVE bands instead of defaulting to PARTIAL.
9. `ValidationReportService` computes `verifiedScore` — additive-weighted by default (flag `scoring.additive-v2`, V201): CODE_DELIVERED(30)+BUILD(25)+TESTS(20)+REVIEWER(20)+CRITICALS(5), weighted-average over fired slots, hard 0-floor when zero files generated. Legacy multiplicative formula stays behind the flag-off path.
10. **Mark run terminal** (`COMPLETE / PARTIAL / FAILED`) with `status_reason`.
11. **Background: auto-preview** (5-min TTL) + `TestGenOrchestrator` (plan → generate → compile → execute against live preview) + `MergeCompleted` event (drives Slack / JIRA / Confluence outbound comments).
12. **Auto-push:** `ScmPushService.pushMultiRepo` opens per-repo PRs with cross-link.

PATCH mode: `RunOrchestrator.runPatchPipeline` calls `CodeGenEngine.solve`, which runs one `WorkerAgent` loop, skipping classifier / planner / reviewer. Used by the 4 autofix surfaces + SWE-bench / ProgramBench adapters.

## Worker outcomes + resilience

| Outcome | Meaning |
|---------|---------|
| `COMPLETED` | Worker called `completeTask` cleanly. |
| `COMPLETED_BY_STAGNATION` | Per-tier non-write progress watchdog (`progressForceAfter`) force-terminated; falls through to soft-completion gate + Lever B salvage. |
| `STOPPED_WITHOUT_COMPLETE` | Worker hit terminal without calling `completeTask`. Falls through to soft-completion gate. |
| `ITERATION_LIMIT_EXCEEDED` | Per-tier cap hit. |
| `WALL_CLOCK_EXCEEDED` | Per-tier wall-clock cap hit (2026-05-10). |
| `BUDGET_EXCEEDED` | Hard-cap dollar ceiling hit. Lever B salvage fires when 0 writes happened. |
| `ERROR` | Router or tool error. |

**One progress signal** in `WorkerAgent` (RL-2026-05-005, unifying the old Lever A + Lever C + stagnation warn/force): `itersSinceProgress` counts consecutive iterations with no `writeFile`/`editFile`; crossing `progressWarnAfter` (per-tier `req.stagnationWarnAfter`) injects ONE corrective UserMessage nudge — which also fires on a premature text-only stop; crossing `progressForceAfter` force-terminates with `COMPLETED_BY_STAGNATION`. **Lever B** (best-guess salvage — one final LLM call on iter-limit / budget-exceeded / progress-force when `totalWriteCalls=0`) remains available to ordinary attempts but is explicitly disabled for selected shared-workspace continuation attempts. The dollar budget cap survives unchanged. UserMessage (not SystemMessage) so Gemini's implicit-cache prefix stays intact.

Model-specific tuning is an optional, fail-closed overlay: `model-matrix.capability-profiles` may supply `stagnationWarnAfter` / `stagnationForceAfter` for `provider/model` (or provider) keys. `SequentialExecutor.tierConfigFor` applies a profile only when the separately seeded `worker.capability-profiles.enabled` flag (V271) is ON, both values are positive, and `force > warn`; model-specific lookup canonicalizes legacy aliases before falling back to provider scope. Absent, malformed, unavailable, or partial profiles preserve the complexity defaults. `ToolRegistry` uses the same gate before binding profile `optional-tools`, so a YAML edit alone cannot widen a worker schema. Build prompts also accept an optional classpath fragment at `prompts/fragments/model/<provider>/<model>/build.md` when `worker.provider-prompt-tuning.enabled` is ON; model keys are normalized to one safe segment and missing fragments are no-ops, preserving provider-only and base-prompt behavior. Enable model-specific overlays only after a fixed-corpus, k>=3, budget-reconciled comparison against a disabled-profile baseline; keep both gates OFF by default.

## Concurrency

- **Bounded thread pool** at the orchestrator entry: `ThreadPoolExecutor` with `CallerRunsPolicy` backpressure. Size = `PoolSizing.coreMultiple(1, 4, 16)`.
- **Per-task sequential** within a run (no intra-run parallelism). The executor now emits an advisory `TASK_WAVE_PLAN` from the bounded scheduler with configured `maxParallelTasks`, but it does not dispatch tasks concurrently.
- **Plan-gate continuation** PostgreSQL outbox-backed, leased and restart-safe. No executor thread or Redis poll waits for approval; READY/expired PROCESSING work is recovered by the dispatcher.
- **Plan-gate evidence retention** Direct deletion cannot erase a versioned gate, continuation
  outbox, or referenced/acknowledged contract report while the run exists. Parent-run deletion still
  cascades for retention/GDPR. V348's report→run FK is `NOT VALID` only to tolerate historical orphan
  rows during deployment; all new rows must name a real run.
- **Async work** in virtual threads wrapped with `ContextSnapshotFactory.captureAll().wrap(runnable)` so traceId + RequestAttributes survive.
- **`AsyncAllowlistTest`** (ArchUnit) pins every `@Async` method to a curated allowlist (regression guard against the `@Async` + lazy-JPA class of bugs).

## Patterns

- Run ownership (`userId`, `orgId`, `projectId`) stamped BEFORE the async pipeline thread starts.
- `.agent-context/project.json` written from planner output — used by preview / deploy for project-type detection.
- `gitCommit` denied for workers via `ToolRegistry.bindForWorker(ctx)` — auto-push happens at run-end.
- Cost estimate + credits computed after planner emits the plan; persisted to `creditsCharged` + `hardCapUsd` via `RunOrchestrator.persistEstimate` (clamps with `userCostCap` when present; skips the platform cap entirely for BYOK orgs).
- Credits charged at plan approval (not submission). Refunded on FAILURE.
- Cost audit: `RunCostLog` entity persisted after each run completion — tracks estimated vs actual, margin, credits, budget-hit for pricing analytics.
- **Worker base-prompt discipline (RL-2026-06-008, vix-leverage W1.2/W1.5).** `SYSTEM_PROMPT_BASE` gained a general **"BUILD ONLY WHAT THE TASK ASKS"** anti-over-engineering section (minimum implementation; no defensive code for impossible cases; validate only at trust boundaries; no comments on untouched code; delete unused outright; a few concrete lines over a premature abstraction — applies to ALL tasks, not just the bug-fix path; explicitly does NOT waive tests-mandatory) + a steer to use `editFile` over in-place shell edits (`sed -i`). **CLI local-hands addendum (RL-2026-06-033):** `localHandsMode=true` appends a narrower prompt guard for direct working-tree edits and a final override of the generic test mandate: obey exact file scope and do not invent validation/test scaffolds for data/content/config-only tasks in empty workspaces. **Eval-owed:** unflagged prompt change → RL-2026-05-061 cross-provider 0-regression gate (Anthropic/OpenAI/Gemini/DeepSeek) before promotion to `dev`.
- **Execution-boundary extraction (2026-07-26).** `ExecutionResultSummarizer` owns run-level task aggregation, `SmokeTestResult` is the package result contract, and `RequiredIterationWorkspaceCopier` owns fail-closed tracked-file lineage copies. `RunCancellationFence` and `DurableRunCancellationException` keep cancellation provenance explicit: interrupted ERROR/STOPPED worker results remain raw provider unwinds that propagate with a recoverable task row, while DB-backed cancellation and a nominal worker response interrupted by the owning future take the durable ABORTED/PARTIAL-and-skip path.
- **Final test-proof alignment (2026-07-24).** `WorkerTestGatePolicy` makes `worker.auto-verify.enabled` imply maintained-test execution at the final smoke boundary. The edit-burst gate remains bounded for model recovery, but a concrete red suite can no longer be forgotten after its refusal budget and converted into `ALL_SUCCESS` by a compile-only final smoke. No runner / empty / parser-error results remain fail-safe.
- **Repository localization and context budget (RL-2026-07-10).** The worker starts with narrow inventory and exact-symbol/error search, reads bounded ranges, reuses unchanged evidence, and keeps context headroom for edits, test output, and repair turns. `scripts/validate-prompt-manifest.mjs` validates every resource prompt's base and role-qualified fragment edges, literal Java prompt-caller targets, and manifest `runtimeCoverage` identities for dynamic provider/project/scaffold resolution (including caller source tokens, roles, tools, and profiles), so a deleted fragment or renamed classpath prompt cannot silently change an assembled prompt or trigger a runtime fallback.


## Harness-resolution (2026-07-04, feature/harness-resolution — flag-gated OFF)

- `SequentialExecutor`: run journal (`execution.run-journal.enabled`) — per-task outcome one-liners accumulated every run and atomically retained in `<workspace>/.agent-context/run-journal.json` (latest 20 bounded entries); when ON, `journalBlock` (plan outline + last 5 entries) is injected into subsequent task prompts (pinned by `SequentialExecutorRunJournalTest`). On restart the journal is reloaded for prompt continuity, but remains advisory context rather than task-state proof. Repo-orientation block injected via `RepoOrientationService` (`worker.localization.enabled`). `executeOne` canonical overload is now 7-arg (… resumeFrom, providerModelOverride, runJournal).
- Bounded re-plan (CoherencePlannerAgent once/run on structural failure) is a designed follow-up — see HARNESS-RESOLUTION-LOG §task 12.
### Selected handoff persistence

When a selected continuation rerun is authorized, the executor reopens the primary terminal task
row to `PENDING` before publishing the rerun's `WORKING` and terminal result. This keeps stale-worker
fencing from discarding the handoff's durable outcome; recovery-attempt fencing still protects
cross-JVM stale writers.

## Vendored-path removal targets the exact subtree (RR-649, 2026-07-21)

`VendoredPathGuard.purgeStagedVendored` derived its `git rm --cached -r` target with
`rootSegmentOf`, so a normal nested path like `frontend/node_modules/pkg/index.js` resolved to
`frontend` — the pre-commit guard meant to discard dependencies could unstage the entire
application subtree from the index, and the following commit would remove all tracked frontend
source from the customer branch. Both call sites now use `firstVendoredSegmentOf`, which returns the
exact prefix ending at the first vendored segment.

Two further corrections in the same class: `firstVendoredSegmentOf` was case-SENSITIVE while
`isVendored` is `(?i)`, so `Frontend/Node_Modules/x` passed the vendored test and then fell through
to the same `rootSegmentOf` widening — reachable from the committed-history scrub path too. And it
no longer falls back at all: an unidentifiable path yields `null`, the caller logs and SKIPS it, and
`isSafeVendoredRemovalTarget` re-checks every target (relative, traversal-free, non-option-looking,
final segment itself vendored) before Git is invoked. Pathspecs are passed after `--`.
**Refusing an ambiguous removal is the contract; never widen to a parent directory.**

## P1 audit-remediation batch (harness lane, 2026-07-22)

Fail-closed / evidence-honest corrections; no migration.

- **RR-261** — `WorkerToolLogEvidence.countFileWrites` now excludes `-> ERR` / `→ REFUSED` entries,
  so a worker that attempted only rejected edits counts zero delivered files and cannot be laundered
  into a soft completion.
- **RR-263** — `SequentialExecutor` failed-retry (`COMPLETED_WITH_WARNING`) return now calls
  `persistTaskStatus(..., "PARTIAL", ...)` + emits the terminal SSE before constructing the outcome,
  closing the WORKING-row window a crash could re-execute.
- **RR-265** — continuation-action history append moved into
  `TaskStatusPersistenceService.appendContinuationAction` (proxied `@Transactional`, row-locked,
  recovery-generation fenced), mirroring `persistGoalDecisionJson`. The executor's inline
  `findByIdForUpdate` (no ambient transaction, all-swallowing) is now the bounded-constructor fallback
  only.
- **RR-268** — `totalTrackedTokens` is a NON-overlapping physical total (`input + output +
  cacheWrite`); `cacheRead` (OpenAI/Gemini prompt subset) and `reasoning` (output subset) are no
  longer double-counted, so the `max-total-tokens` waste guard stops honestly.
- **RR-269** — the legacy `maybeCrossProviderRetry` fallback lane is DELETED (per the finding's
  recommendation). It dispatched the next YAML failover provider directly, consulting neither route
  pins, org key policy, provider availability, strict mode nor benchmark/confirmation policy, with a
  racy ceiling. Every provider/model change now flows through the single continuation-policy /
  admission path; the orphaned `worker.cross-provider-fallback.enabled` flag (V220) is now unread.
- **RR-271** — a cancelled run summarises as `ABORTED` (never `ALL_SUCCESS`), appends `SKIPPED`
  outcomes for the interrupted and un-run tasks, and preserves the thread interrupt. Cancellation
  is fenced before every task/worker retry dispatch and again after each worker return; the worker
  uses a durable scalar `pipeline_run` status query before and after every provider call, so a late
  successful response cannot dispatch tools, persist `COMPLETE`, or advance to the next task.
- **2026-07-23 deploy-interruption correction** — `WorkerResult.ERROR` is never eligible for the
  write-based soft-completion path while the executor thread is interrupted.
  `WorkerInterruptionPolicy` throws a cancellation signal before any terminal task write, and
  `RunOrchestrator` preserves the durable lifecycle row instead of overwriting cancellation or a
  crash-recoverable `WORKING` checkpoint.
- **RR-650** — `VendoredPathGuard.ScrubResult` gained `committed`; a failed scrub commit (removals
  staged only) is surfaced instead of reported as a durable success.
- **RR-685** — `GateStore.openPlanGate` distinguishes a genuine duplicate-key race
  (`DataIntegrityViolationException`, swallowed) from a real `DataAccessException` (rethrown), so a
  run never enters `AWAITING_APPROVAL` with no durable continuation payload.
- **RR-1054** — `UiStyleResolver.enforceTokenLimit` enforces the char ceiling independently of
  heading shape; a DESIGN.md with ≤4 `##` sections is no longer returned unbounded.
- **RR-152** — `WorkspaceInitService.runWorkspacePath` / `repoDirectoryName` validate the
  caller-derived segment through `security.SafeWorkspacePath.requireSafeRelativePosixPath` (no
  traversal/reserved/absolute), enforcing containment at the filesystem edge.
- **RR-153** — `copyFromRun` fails closed: a `git ls-files` failure (or empty tracked set) no longer
  triggers a full-directory copy of untracked spill/secrets; the target starts empty instead.
- **2026-07-26 iteration workspace contract** — public V2 iteration uses
  `copyFromRunRequired`, not the legacy empty-workspace fallback. Missing sources, invalid Git
  metadata, zero tracked files, or tracked symlinks fail closed; multi-repo children copy each
  nested repository and persist a fresh baseline before the first planner call.
- **RR-1563** — `seedInitialContinuationActionState` reconstructs consumed escalation counters from
  durable `agent_task.continuation_actions_json` (EXECUTED/APPROVED rows) on every resume, so
  crash-recovery no longer restores a fresh full escalation budget.
- **2026-07-24 task-proof presentation correction** — an intermediate task with no task-level
  validation remains `UNCERTAIN` evidence pending downstream build/test/reviewer proof; the
  customer Steps view hides that internal continuation detail once the durable task status is
  complete instead of falsely upgrading deferred validation to `DONE`. Empty budget-ceiling text
  also stays empty unless a real ceiling fired.
- **2026-07-24 multi-task scope and trust correction** — `PromptComposer` omits the repeated full
  `ORIGINAL_SPEC` block when exact task-scoped source clauses can be allocated, emits those whole
  lines as `TASK_SOURCE_REQUIREMENTS`, and falls back to the complete source if any assigned
  requirement cannot be mapped by exact normalized containment. It also emits
  `RESERVED_FUTURE_SCOPE` with later task/file ownership.
  The shared worker contract treats the current task as the sole deliverable and treats earlier
  generated code/tests as untrusted intermediate output: later tasks remove contradictory security
  fallbacks instead of preserving them to keep self-authored tests green. Immutable replay tests
  compare serialized status/body/contract headers, not only parsed fields.
