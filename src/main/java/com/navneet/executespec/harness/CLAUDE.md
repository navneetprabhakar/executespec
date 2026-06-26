# `harness/` — V2 pipeline orchestration

> Last reviewed: 2026-06-10 (1.0.0-M1).
>
> V1 `PipelineHarness` was deleted in deprecation P1-P6 (2026-04-13). The live entry point is `integration/RunOrchestrator`; `harness/` now holds the V2 sequential executor + the workspace + gate primitives that survived from the V1 era.

## Key files

| File | Purpose |
|------|---------|
| `SequentialExecutor.java` | Per-task tier-routed worker dispatch. For each task: compose system + user prompt → `WorkerAgent.run()` → if worker hit `completeTask`, run `buildProject` smoke (final task only on multi-task plans) → pass=SUCCESS / fail=ONE retry with build error injected. Aggregate: ALL_SUCCESS / PARTIAL_WITH_WARNINGS / FAILED / ABORTED. Per-task tier-config: LOW=WORKHORSE model w/ LOW caps (60 iters / 4k tokens / 30-min wall-clock — RL-2026-06-052 decouple, was LIGHTWEIGHT), MEDIUM=WORKHORSE (120 / 8k / 45-min), HIGH=PLANNING (180 / 16k / 75-min); project-run multiplier ×2 on iters + wall-clock. PATCH-mode caps now live in `engine/CodeGenEngineImpl`: Anthropic/DeepSeek/OpenRouter/Fireworks 220 / OpenAI 150 / Gemini 180. **Provider prompt tuning split (2026-06-10):** BUILD-mode appends only `prompts/fragments/provider/<provider>/build.md`; PATCH-mode appends `.../patch.md` in `CodeGenEngineImpl`, so SWE-bench/repro/source-only directives no longer bleed into normal BUILD tasks. **Soft-completion gate** (Fix C of `feature/worker-runaway-fixes`): when worker outcome is `COMPLETED_BY_STAGNATION` or `STOPPED_WITHOUT_COMPLETE` with `totalWriteCalls > 0`, falls through to the same smoke gate that COMPLETED uses — multi-task intermediate task with writes → `COMPLETE`; final smoke fail → `PARTIAL`. **Goal-decision control (RL-2026-06-198):** terminal task branches build conservative `GoalEvidence`, run the pure `GoalDonenessEvaluator`, attach the resulting `GoalDonenessDecision` to `TaskOutcome`, and emit `Goal decision: STATE -> DECISION (...)` as a `LOG` event. V249 seeds `execution.goal-decision.enforcement.enabled` default OFF; when enabled, `STOP_BLOCKED`, `STOP_FAILED`, and `STOP_WASTE_GUARD` abort remaining sequential tasks, append synthetic `SKIPPED` outcomes, emit `Goal stop enforced: ...`, and return aggregate `ABORTED`. `STOP_DONE` remains informational until a run-level goal proof exists; provider/model switching is still unchanged. **2026-05-10:** `wallClockSeconds` per tier-config sibling to `maxIterations` (whichever fires first wins); `Outcome.WALL_CLOCK_EXCEEDED`. **`persistTaskStatus`** carries a defensive 4000-char cap on `failureReason` (V184 widens the column to TEXT). Also exposes the boot-time `execute(req, ResumeRequest)` overload that walks per-task status to decide SKIP_TERMINAL / RESUME_FROM_SNAPSHOT / RUN_FRESH on JVM restart. **2026-05-19:** `SYSTEM_PROMPT_BASE` carries a tests-are-mandatory directive (any task touching a function/endpoint/module must ship happy-path + error/edge tests before `completeTask`, or justify why untestable); exposed read-only via static `workerSystemPromptBase()` so tier-eval-v2 WORKHORSE cases drive the exact production prompt. **2026-05-25 (RL-2026-05-034, Transfer #1):** `runSmokeTest` now, after a clean compile, RUNS the worker's tests in-loop via `WorkspaceTestRunner` (language-neutral `TestCommandResolver`); a real test failure flips the smoke result to FAILED and reuses the existing build-fail retry. Behind `build.test-exec-gate.enabled` (seeded OFF by V209), fail-safe (no runner → compile-only, today's behavior). The in-loop analog of PATCH reproduce-first — "tests mandatory" now means tests *pass*, not just *compile*. **2026-05-31 (RL-2026-05-059): flipped ON for dev** alongside V217 `worker.build-tdd.enabled`; the worker `VERIFY` directive in `SYSTEM_PROMPT_BASE` called the auto-bound `runWorkspaceTests` tool before `completeTask`. **⚠️ 2026-06-01 (RL-2026-05-061): REVERTED.** The `runWorkspaceTests` tool in native-DeepSeek's schema caused reasoning-only no-edit stalls, so V217 + V209 are **back OFF** and the `runWorkspaceTests` mention is removed from point-4. `SYSTEM_PROMPT_BASE` points 2+4 are now **symptom-anchored**: reproduce with the issue's verbatim MWE; verify the issue's exact symptom changed (*"if your edit didn't change the output, you're in the wrong place — trace where the bad output is produced and fix THERE"*); match the issue's stated contract. Generic (shared base, all providers), no new tool. Validated GPT 0/11 → 4/11 on the addressable gap; net full-50 × all-providers is the owed gate. |
| `WorkspaceInitService.java` | Creates fresh git repo for each run. Writes `.gitignore`, `.gitattributes`. Multi-repo runs clone each linked repo into `<workspace>/<runId>/repos/<name>/` via `cloneMultipleRepos`. Captures per-repo SHA snapshot into `run_repo_snapshot` (V100) for the "reset to run state" feature. |
| `GateStore.java` | Redis-backed plan-gate storage. 500ms poll, 2h TTL, 24h max wait timeout. `isPlanGateWaiting(runId)` detects when the pipeline thread is gone (server restart during AWAITING_APPROVAL) so `approve()` can fail cleanly with 409. |
| `UiStyleResolver.java` | Resolves design-system / UI style from user request or project defaults. 3-signal priority: explicit hint > planner `ui_style` > project type heuristic. |
| `DesignSystemProperties.java` | YAML config for the company design-system library. Filesystem-backed (54 design systems shipped). |
| `WorkerTaskRequest.java` | Per-task request shape — `taskId`, `complexity`, model + tier, `maxIterations`, `wallClockSeconds`, `requireBuildVerification`, `requireTestVerification` (patch-mode gate). |
| `ExecutionRequest.java` | Executor input shape. `localHandsMode=true` is set only by CLI local-hands runs and appends a local-only scope prompt so empty working-tree edits do not sprout unsolicited scaffolds/tests. |
| `ApprovalGate.java` | Plan-gate interface. |

## Pipeline sequencing (PRODUCTION mode)

1. `RunOrchestrator.submitRun(req)` persists the run row + estimate.
2. `SmartClassifierAgent.classify(spec)` → `PlanStrategy`.
3. Strategy router emits the plan via `OutlinePlannerAgent` (FULL_PLAN / SKELETON_ONLY) + optional `TaskExpanderAgent` parallel expansion + `CoherencePlannerAgent` patch.
4. **Plan approval gate** — `GateStore.openPlanGate(runId)`. SSE `GATE_OPENED` includes credits + estimated cost. PRODUCTION pauses; PROTOTYPE / `skipApproval` bypasses.
5. On approval: charges credits via `TierEnforcementService.assertAndIncrementRunCount(userId, credits)`. FREE blocks specs requiring >1 credit. BYOK orgs skip the platform cap entirely.
6. **`SequentialExecutor.execute(req)`** runs tasks one at a time. Tier-routed per-task model. Soft-completion gate covers `COMPLETED_BY_STAGNATION` / `STOPPED_WITHOUT_COMPLETE` paths. Final-task smoke + one-retry on fail.
7. `ReviewerAgent.review(workspace, plan)` — general + project-type-aware wiring pass + post-edit formatter + a fix-the-red-build third pass (flag `reviewer.third-pass.enabled`, default ON, V202) when the final build is still red.
8. `AccuracyAssessmentService` reads key files, scores requirements coverage (grades `testCoverageLevel` against explicit NONE/PARTIAL/GOOD/COMPREHENSIVE bands — no longer defaults to PARTIAL).
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

**One progress signal** in `WorkerAgent` (RL-2026-05-005, unifying the old Lever A + Lever C + stagnation warn/force): `itersSinceProgress` counts consecutive iterations with no `writeFile`/`editFile`; crossing `progressWarnAfter` (per-tier `req.stagnationWarnAfter`) injects ONE corrective UserMessage nudge — which also fires on a premature text-only stop; crossing `progressForceAfter` force-terminates with `COMPLETED_BY_STAGNATION`. **Lever B** (best-guess salvage — one final LLM call on iter-limit / budget-exceeded / progress-force when `totalWriteCalls=0`) and the dollar budget cap survive unchanged. UserMessage (not SystemMessage) so Gemini's implicit-cache prefix stays intact.

## Concurrency

- **Bounded thread pool** at the orchestrator entry: `ThreadPoolExecutor` with `CallerRunsPolicy` backpressure. Size = `PoolSizing.coreMultiple(1, 4, 16)`.
- **Per-task sequential** within a run (no intra-run parallelism).
- **Plan-gate wait** Redis-backed, 500ms polling, 24h max wait.
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
