# `benchmark/` — SWE-bench execution harness + common in-app tracking layer

> Added 2026-05-24 (RL-2026-05-028). Branch `feature/swebench-harness` (not yet merged).

> **This package now holds TWO distinct concerns** — keep them straight:
>
> 1. **SWE-bench _execution_ harness** (the original, below) — `BenchmarkController` (`/api/admin/benchmark`)
>    \+ `BenchmarkRunService` / `BenchmarkGenerationService`. Actually *runs* SWE-bench instances through the
>    real production pipeline.
> 2. **Common in-app _tracking_ layer** (RL-2026-06, V236 — see the bottom section) — `BenchmarkTrackingController`
>    (`/api/v2/test/benchmark`) + `BenchmarkTrackingService` + the `BenchmarkRun` / `BenchmarkTaskResult` /
>    `BenchmarkEvent` entities + `BenchmarkSuite`. ExecuteSpec is the event **sink + display**; an external
>    verifier (Harbor / SWE-bench / ProgramBench harness) *publishes* events here. Suite-agnostic. Does NOT run
>    anything. **Distinct class names + path** from concern (1) to avoid a collision.

Runs public SWE-bench instances through the **real production pipeline** to measure + publicly
showcase ExecuteSpec's code-gen system. Supersedes the old `swebench/SwebenchTaskService` oracle
adapter (kept only for its sandbox plumbing — see below).

## The two principles this package exists to honour

1. **Real system, sandbox-only.** The code-gen path is untouched production — the real
   `RunOrchestrator.submitRun` entry, real tier routing, real worker + tools. Only the *sandbox*
   (cleanroom container, blind issue→spec input, diff capture, external scoring) is
   benchmark-specific. _(Memory: `feedback_benchmark_real_system`.)_
2. **Blind.** The agent sees ONLY the issue `problem_statement` + offline/environment context —
   never the hidden `FAIL_TO_PASS`/`PASS_TO_PASS` ids or the gold `test_patch`. Those are applied
   **at scoring time, outside the agent**, by the external scorer. This is what makes the number
   leaderboard-comparable. The old adapter violated this (it injected the gold test into the
   prompt → oracle setting → non-publishable).

## Run mode = BUILD, stopped at the worker (RL-2026-05-050)

We submit `runMode=BUILD` + `source="swebench-codegen"` → the **REAL pipeline**:
classifier → planner → worker (the **system decides its own strategy** — SINGLE_SHOT / SMALL / … —
with the full tool set + tier routing). The orchestrator's **code-gen-only gate** (`RunOrchestrator`
`── 2.9`, keyed on the `swebench-codegen` source) then `terminate(COMPLETE, "CODEGEN_ONLY")` **right
after the worker, before the reviewer** — so the captured diff is a clean, scoreable minimal patch.

Why this and not the old `runMode=PATCH`: PATCH was a thin worker loop that SKIPPED the
classifier/planner — so the system never decided its own approach (founder: "use the actual system,
not hardcoded"). The originally-planned full BUILD was abandoned because the reviewer's
`PostEditFormatter` (a 469-line whole-file reformat on the `astroid-1268` smoke) + scaffolding +
auto-commit polluted the diff → unscoreable. **Those steps all run in/after the reviewer**, so
stopping at the worker removes them: we get the real classifier/planner decisioning AND a clean
diff. (Earlier history: plan doc §12 locked D2=BUILD; the smoke flipped it to PATCH for the
diff-pollution reason — RL-2026-05-028; RL-2026-05-050 restores BUILD-stop-at-worker now that the
gate exists.)

The gate is **tightly scoped to the benchmark source** — every production customer run (MANUAL /
GITHUB_* / REST) runs the full pipeline incl. reviewer, byte-identical.

> Harness-improvement track (RL-2026-05-050, in progress): better localization tools + higher
> iteration budget + reproduce-first/minimal-diff discipline — landing in the real product, to
> meet/beat Claude Code + Codex on the same blind set.

## Files

| File | Purpose |
|------|---------|
| `BenchmarkRunService.java` | The harness. `submit()` → `orchestrator.submitRun(source="swebench-codegen", repoMode="NONE", repoUrl=null, runMode=BUILD, executionMode=PROTOTYPE, skipApproval=true)` — the real classifier→planner→worker path, stopped after the worker by the orchestrator code-gen-only gate (RL-2026-05-050). A synchronous `@EventListener onRunStarted` does `docker pull` → `docker cp <workdir>/.` into the host workspace → `docker run -d --rm --network none -v <workspace>:<workdir> -w <workdir>` → registers the container id + workdir in `SwebenchContainerStore` (classic SWE-bench `/testbed`; SWE-bench Pro `/app`). On setup failure it marks the run FAILED (status_reason `BENCH_SETUP_FAILED`) so a bad container can't poison benchmark stats. `captureOnTerminalAsync` polls to terminal then writes `submission.patch` + classic SWE-bench `preds.json`; for SWE-bench Pro it also writes `swebench_pro_patches.json` in the official list-shaped `{instance_id, patch, prefix}` format. Diff capture keeps the existing run-infra/build/test/repro excludes so the submitted patch is source-only. `synthesizeBlindSpec` builds the issue-only spec with an "existing repo at <workdir>, fix don't scaffold, don't modify tests, pin down EXACT expected behavior + match codebase conventions" framing. |
| `BenchmarkGenerationService.java` | **Lean, synchronous code-gen API** (RL-2026-05-028 follow-up). `generate()` reuses the code-gen components **directly** — sets up the cleanroom (timed `docker pull`, skip-if-present), creates ONE minimal `PipelineRun` row (only for the worker's `hardCapUsd` + `sourceType="benchmark"` gate-off), builds an `ExecutionContext`, calls the code-gen engine **synchronously**, captures the diff → `submission.patch` + `preds.json`, and for SWE-bench Pro also `swebench_pro_patches.json`; returns `{runId, outcome, patchBytes, costUsd, …}`. **No `submitRun`** → no async pipeline, no event listeners (email/preview/report), no credits, no harvesting. Workspace under the (non-Documents) output base by design. This is the going-forward benchmark path: one instance = one call → score. |
| `BenchmarkController.java` | `@RequestMapping("/api/admin/benchmark")` `@PreAuthorize("hasRole('SUPER_ADMIN')")`. `POST /run` (async, via `submitRun` — `BenchmarkRunService`), **`POST /generate` (synchronous lean code-gen — `BenchmarkGenerationService`; preferred)**, `GET /preview/{id}` (resolved image/repo/baseCommit), `GET /instances` (loaded id count). |

## Reused from `swebench/` (sandbox layer — sound, not the problem)

- `SwebenchProperties` — `enabled` flag (default OFF), `outputBase`, `instancesFile`,
  `imageTemplate`, `benchProjectId`/`benchUserId`/`benchOrgId`, `taskWallTimeoutMinutes`,
  `defaultProvider`, plus `suite`, `proImageRepository`, and `predictionPrefix` for SWE-bench Pro.
  Set in `application-local.yml` locally and env-driven in `application-benchmark.yml`.
- `SwebenchInstanceLoader` — `findById`, `imageNameFor`, `allInstanceIds`; parses both classic
  `FAIL_TO_PASS`/`PASS_TO_PASS` JSON-string fields and SWE-bench Pro lowercase array fields.
- `SwebenchContainerStore` — per-run container registry + workdir consulted by the worker's tools.

## Gate scoping (lives in `agent/PatchAgent.java`)

PATCH mode normally enforces a **test-verification gate** (`requireTestVerification=true`): the
worker can't call `completeTask` until `runTests` shows `failed:[]`/`errored:[]`/`exit_code:0`. That
gate is correct for the autofix surfaces (the failing test IS the spec). It is **wrong for a blind
benchmark run** — we hold out the target test, so the gate can never be satisfied and the worker
loops to the iter cap. `PatchAgent.run` therefore reads the run's `sourceType` and sets
`requireTestVerification = !(src startsWith "swebench" | "benchmark")` — gate OFF for benchmark, ON
for `finding:*`. (RL-2026-05-028.)

## Operational gotchas + hardening (2026-05-24)

- **Run workspace must NOT live under a macOS TCC-protected dir (`~/Documents`).** The default
  `agent.workspace: ./workspace` resolves under the repo (often in `~/Documents`). If macOS revokes
  the JVM's disk access mid-run (TCC), the worker loses access to its own workspace and **freezes**
  on the next file op (observed: workers stalled at iter ~4, no in-container process). Run the
  benchmark with `agent.workspace` pointed outside `~/Documents` (e.g. `~/swebench-bench/espec-ws`).
- **`onRunStarted` skips `docker pull` when the image is already present** — a present-image pull
  still round-trips the registry and timed out under host load; pre-pull images and this is a no-op.
- **`status_reason` is `VARCHAR(80)`** — the setup-failure path truncates `BENCH_SETUP_FAILED: …`
  to 80 chars, else the FAILED-mark save throws and the run falls through to a containerless host
  run. (Both fixes: RL-2026-05-028 follow-up.)
- **`submit()` blocks until the run is terminal**, so a runner that scores off the HTTP response
  must set a generous client timeout (Live runs ~2–4 min each; a *frozen* run blocks the full
  timeout). The `scripts`-side runner pre-pulls per instance + waits on the run, then scores the
  `submission.patch`.
- **`captureOnTerminalAsync` now best-effort-captures on wall-timeout** (RL-2026-05-052). Previously a
  run that never reached terminal within the wall window fell through to teardown (`docker rm`) with
  **no `preds.json`** — a completed-but-slow/frozen run was silently uncaptured + counted unresolved
  (this was the Anthropic "16→15" artifact: `django-14855` timed out → no preds). The timeout branch
  now calls `captureSubmission(..., "TIMEOUT")` BEFORE the `finally` removes the container, so a
  `preds.json` (whatever diff exists at the deadline) is always written.

## Status

Validated locally on SWE-bench **Lite** (iteration surface) + **Live** (publish surface) across all
three providers, blind. **First blind Live run (2026-05-24, Anthropic Sonnet): 5/11 = 45% resolved**
before stopping — full result + miss-analysis was in `docs/benchmarks/swebench-live-2026-05-24.md`
(cleared 2026-05-26 — archived in git history; the canonical SWE-bench record is now
`docs/reports/swebench-exec-brief.{html,pdf}`). **2026-06-12:** SWE-bench Pro manifest/image
resolution smoke is local-green for the non-spend read-only path (`BenchmarkControllerSmokeTest`,
`SwebenchInstanceLoaderTest`, `RunTestsToolTest`): Pro rows resolve `dockerhub_tag` to
`jefzda/sweap-images:<tag>`, expose `/app`, and emit the official `swebench_pro_patches.json`
shape. Full Pro generation still needs the benchmark VM smoke against real Docker images.
Dominant miss mode: **under-scoping multi-file changes** (the actionable scaffold lever). Run stopped
at 11/50 because the local arm64/Rosetta environment kept stalling (a credible full run belongs on
native x86_64 infra). `BenchmarkGenerationService` (`POST /generate`) is **compile-verified but not
yet runtime-tested** — the Live run used the older `submitRun` path (`POST /run`).

Not yet merged; not wired to dev/prod (the `swebench.enabled` flag is OFF by default and only set in
the local-only profile). **Oracle path removed (RL-2026-05-062):** `swebench/SwebenchController` (the
`/api/admin/swebench` oracle entry) is deleted, and the gold-`test_patch` injection in
`SwebenchTaskService` (`formatTestPatchSnippet` + `captureFailingTestFilesFallback`) is neutralised
to emit nothing — so no SWE-bench prompt can leak the hidden tests. `SwebenchTaskService` is retained
(method signatures intact) for its event-listener / instance-loading surface that the blind harness
+ `AsyncAllowlistTest` reference.

---

## In-app benchmark tracking layer (RL-2026-06, V236) — common / multi-suite

> Design: [`docs/plans/benchmark-closed-loop-2026-06-09.md`](../../../../../../../docs/plans/benchmark-closed-loop-2026-06-09.md).
> **Concern (2)** from the top of this file — separate from the SWE-bench *execution* harness above.

A COMMON, suite-agnostic layer that **tracks** any benchmark run + its submission + events **inside
ExecuteSpec** (in-login `/benchmark` dashboard). ExecuteSpec is the event **sink + display, not an
orchestrator**: an external verifier/harness (Harbor for Terminal-Bench; the SWE-bench / ProgramBench
harnesses next) runs the benchmark and **publishes events** here; ExecuteSpec **ingests → stores →
re-broadcasts (SSE) → shows**. No in-app Harbor subprocess — Harbor still owns the verifier + the official
`harbor upload`.

**Suite-agnostic by construction.** Every suite's harness POSTs the *same* event shapes (`TASK_SCORED`
`{task, attempt, reward, esRunId?, cost}` / `RUN_PROGRESS` / `RUN_DONE` / `SUBMISSION_*`) to the *same*
ingest endpoint; `benchmark_run.suite` (`BenchmarkSuite`: TERMINAL_BENCH / SWE_BENCH / PROGRAM_BENCH —
stored as a String, not a JPA enum, so a new suite needs no migration) is the only discriminator. TB is the
first consumer; SWE-bench + ProgramBench plug in with no schema change.

### Files

| File | Purpose |
|------|---------|
| `BenchmarkTrackingController.java` | `@RequestMapping("/api/v2/test/benchmark")` + `@Profile({"local","dev","benchmark"})` + `@PreAuthorize("hasRole('SUPER_ADMIN')")` (the `/api/v2/test/**` matcher is also SUPER_ADMIN-gated — same operator-only surface as `terminalbench/`). `POST /start` (register a tracked run → `BenchmarkRun`), `POST /{id}/event` (ingest one published event), `GET` (list 50), `GET /{id}` (run + task results + events), `GET /{id}/events` (live SSE). DTOs are inline records. **Named distinctly from the SWE-bench `BenchmarkController`** (concern 1) to avoid a class/path collision. |
| `BenchmarkTrackingService.java` | `start` / `ingest` / reads + `subscribe`. `ingest` is event-typed: `TASK_SCORED` → idempotent upsert of `benchmark_task_result` on (run, task, attempt) + recompute `resolved_tasks` (distinct tasks with ≥1 passing attempt) + `cost_usd` (sum of attempt costs); `RUN_DONE`/`RUN_FAILED` → finalize; `SUBMISSION_*` → `submission_status` + `leaderboard_url` (from the event payload JSON). Unknown types are persisted + broadcast verbatim (forward-compatible). Each event is persisted with a per-run `seq` and re-broadcast via `sse/SseBroadcaster` (keyed by the benchmark-run id, so Redis pub/sub + replay come for free); `RUN_DONE`/`RUN_FAILED` also `complete()` the stream. SSE is best-effort (the event is already persisted + replayable). |
| `BenchmarkRun` / `BenchmarkTaskResult` / `BenchmarkEvent` | Entities (`@Data @Builder`, app-set `String id`). See **V236**. |
| `BenchmarkSuite` | The suite discriminator enum + `isKnown(String)`. |
| `Benchmark{Run,TaskResult,Event}Repository` | `JpaRepository<X, String>` finders. |

### Migration

- **V236** `V236__benchmark_tables.sql` — creates `benchmark_run` / `benchmark_task_result`
  (UNIQUE `uq_benchmark_task` on run+task+attempt, FK→run ON DELETE CASCADE) / `benchmark_event` (FK→run
  ON DELETE CASCADE) + supporting indexes. **Boot-validated** against `ddl-auto:validate` (Flyway applies,
  Hibernate validates the 3 entities; app starts clean).

### Status

Phases 1 (persistence) + 2 (ingest + SSE) **done** — compiled + boot-validated locally on the `benchmark`
branch. Phase 3 (the external `run-tb.sh` / `tb-tracker.py` event hooks — a thin curl per published event) and
Phase 4 (the in-login `/benchmark` frontend grid + event log + submission status) are next. Built feature →
dev → main; deployed to the dedicated `benchmark` env.
