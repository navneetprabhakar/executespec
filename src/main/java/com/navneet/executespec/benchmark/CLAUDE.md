# `benchmark/` — SWE-bench execution harness + common in-app tracking layer

> Added 2026-05-24 (RL-2026-05-028). Branch `feature/swebench-harness` (not yet merged).

> **V296 (2026-07-18):** tracking ingestion locks the parent run while allocating event sequence numbers and upserting task attempts; `benchmark_event(benchmark_run_id, seq)` has a database uniqueness backstop. The `k-eval.py publish` command closes the generate→official-score→RUN_DONE loop.
> Benchmark generation exposes post-run `RunCostTracker` input/output totals in `GenerateResult`.
> `k-eval.py generate` copies them into each manifest row and `publish` forwards them as
> `inputTokens`/`outputTokens` on `TASK_SCORED`; tracking persists per-attempt values and
> recomputes the run totals shown in `/benchmark`. Never infer tokens from patch size or wall time.
> Historical/interrupted publishers can be repaired with the SUPER_ADMIN-only
> `POST /api/v2/test/benchmark/{id}/reconcile-usage` endpoint. It ignores client-supplied numbers,
> follows each immutable `benchmark_task_result.es_run_id`, and rebuilds only token/cost projections
> from canonical `run_step_usage` physical-attempt rows. Reward, outcome, task identity and run
> linkage are never changed. A changed reconciliation appends one `USAGE_RECONCILED` audit event;
> an exact replay is a no-op.

> **This package now holds TWO distinct concerns** — keep them straight:
>
> 1. **SWE-bench _execution_ harness** — `BenchmarkController` (`/api/admin/benchmark`)
>    \+ `BenchmarkGenerationService`. Actually *runs* SWE-bench instances through the shared
>    `CodeGenEngine` single-task seam.
> 2. **Common in-app _tracking_ layer** (RL-2026-06, V236 — see the bottom section) — `BenchmarkTrackingController`
>    (`/api/v2/test/benchmark`) + `BenchmarkTrackingService` + the `BenchmarkRun` / `BenchmarkTaskResult` /
>    `BenchmarkEvent` entities + `BenchmarkSuite`. ExecuteSpec is the event **sink + display**; an external
>    verifier (Harbor / SWE-bench / ProgramBench harness) *publishes* events here. Suite-agnostic. Does NOT run
>    anything. **Distinct class names + path** from concern (1) to avoid a collision.

Runs public SWE-bench instances through the **same code-generation seam used by PATCH-mode product
flows** to measure + publicly showcase ExecuteSpec's code-gen system. Supersedes the old
`swebench/SwebenchTaskService` oracle adapter (kept only for its sandbox plumbing — see below).

## The two principles this package exists to honour

1. **Single codegen seam, sandbox-only.** The active benchmark path calls `CodeGenEngine.solve(...)`,
   the same single-task worker seam used by PATCH-mode product orchestration. Only the *sandbox*
   (cleanroom container, blind issue→spec input, diff capture, external scoring) is
   benchmark-specific.
2. **Blind.** The agent sees ONLY the official task inputs — the issue `problem_statement`
   plus, for SWE-bench Pro, the annotator-written `requirements` and `interface` dataset fields
   (added 2026-07-04; the official evaluation setting includes all three in the agent prompt,
   arXiv:2509.16941, so injecting them is leaderboard-legal, and omitting them made our runs
   strictly harder than every leaderboard entry) — never the hidden `FAIL_TO_PASS`/`PASS_TO_PASS`
   ids or the gold `test_patch`. Those are applied **at scoring time, outside the agent**, by the
   external scorer. This is what makes the number leaderboard-comparable. The old adapter violated
   this (it injected the gold test into the prompt → oracle setting → non-publishable).
   `BenchmarkBlindSpecTest` pins both directions (sections present; held-out signals absent).

## Codegen path = `CodeGenEngine` (single active benchmark path)

`POST /api/admin/benchmark/run` and `POST /api/admin/benchmark/generate` are now aliases over
`BenchmarkGenerationService.generate(...)`. Both prepare the cleanroom, create the minimal run row the
worker needs, call `CodeGenEngine.solve(...)`, then capture the official patch artifacts. The old
`BenchmarkRunService` BUILD-stop-at-worker implementation was deleted so it cannot drift into a
second benchmark generation path.

The admin request's optional `hardCapUsd` is tighten-only: the effective worker cap is
`min(swebench.hard-cap-usd, request.hardCapUsd)`, and a non-finite/non-positive request is rejected
before worker dispatch. `k-eval.py generate` always supplies this field from its required
`--per-task-cap-usd` and separately reserves the required aggregate campaign cap in one atomic,
OS-file-locked ledger spanning every requested `k` pass. A stale in-flight reservation fails closed
on resume rather than risking a second provider dispatch.

This prevents two benchmark codegen paths from drifting. Product BUILD runs still use
classifier/planner/reviewer; product PATCH/autofix and benchmark generation share the engine seam.

## Files

| File | Purpose |
|------|---------|
| `BenchmarkGenerationService.java` | **The active benchmark code-gen API.** `generate()` enforces the shared `swebench.max-concurrent-runs` semaphore before setup/codegen, sets up the cleanroom (timed `docker pull`, skip-if-present), creates ONE minimal `PipelineRun` row (only for the worker's `hardCapUsd` + `sourceType="benchmark"` gate-off), builds an `ExecutionContext`, calls the shared code-gen engine synchronously, captures the diff → `submission.patch` + `preds.json`, and for SWE-bench Pro also `swebench_pro_patches.json`; returns `{runId, outcome, patchBytes, costUsd, inputTokens, outputTokens, …}`. Workspace under the non-Documents output base by design. |
| `LowCodingFixtureHarness.java` | Operator/test-only fixed-repo LOW fixture. Creates a tiny deterministic Python addition task, requires a non-blank external ledger reservation id before dispatch, runs the canonical `WorkerAgent` with LOW caps, and emits provider/model/tier/flag/prompt/tool/harness fingerprints plus a deterministic evaluator result. The test response also carries the transient final source so an independent held-out evaluator can hash and execute the patch before the disposable workspace is removed. No production route is added. |
| `ProviderHandoffFixtureHarness.java` | Versioned (`v1`) operator-only paired fixture harness for repository localization, edit/test/repair, and deliberate stuck/handoff cases. It requires exactly two allow-listed providers, distinct per-pass ledger reservations, an explicit handoff trigger, and sets `WorkerTaskRequest.strictProvider=true`; only an explicit second pass may use the fallback. It emits final-artifact, evaluator, and harness fingerprints. |
| `ProviderHandoffFixtureController.java` | `POST /api/v2/test/provider-handoff`, local/dev/benchmark profile + `SUPER_ADMIN`; complete JSON route contract, no default fixture/fallback/reservation, disposable workspace. |
| `BenchmarkController.java` | `@RequestMapping("/api/admin/benchmark")` `@PreAuthorize("hasRole('SUPER_ADMIN')")`. `POST /run` and `POST /generate` both call `BenchmarkGenerationService.generate(...)`; `GET /preview/{id}` resolves image/repo/baseCommit; `GET /instances` returns loaded id count. |

Diff capture hardening (RL-2026-06-030): both benchmark paths drain shell output while the process
runs instead of waiting for process exit before reading stdout, so large diffs cannot deadlock the
capture helper. Redis `appendonlydir/**` runtime artifacts are excluded; NodeBB/Redis smoke runs can
otherwise create 100KB+ AOF noise and hide a valid source diff behind a timeout/empty patch.

## Reused from `swebench/` (sandbox layer — sound, not the problem)

- `SwebenchProperties` — `enabled` flag (default OFF), `outputBase`, `instancesFile`,
  `imageTemplate`, `benchProjectId`/`benchUserId`/`benchOrgId`, `taskWallTimeoutMinutes`,
  `hardCapUsd` (historical default $8, env-overridable for bounded repeated-attempt campaigns),
  `imageInspectTimeoutSeconds` (30s) and `imagePullTimeoutSeconds` (1800s; env-overridable),
  `defaultProvider`, plus `suite`, `proImageRepository`, and `predictionPrefix` for SWE-bench Pro.
  Set in `application-local.yml` locally and env-driven in `application-benchmark.yml`.
- `SwebenchInstanceLoader` — `findById`, `imageNameFor`, `allInstanceIds`; parses both classic
  `FAIL_TO_PASS`/`PASS_TO_PASS` JSON-string fields and SWE-bench Pro lowercase array fields.
- `SwebenchContainerStore` — per-run container registry + workdir consulted by the worker's tools.

## Gate scoping (lives in `engine/CodeGenEngineImpl.java`)

PATCH mode normally enforces a **test-verification gate** (`requireTestVerification=true`): the
worker can't call `completeTask` until `runTests` shows `failed:[]`/`errored:[]`/`exit_code:0`. That
gate is correct for the autofix surfaces (the failing test IS the spec). It is **wrong for a blind
benchmark run** — we hold out the target test, so the gate can never be satisfied and the worker
loops to the iter cap. `CodeGenEngineImpl` therefore reads the run's `sourceType` and sets
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
- **`generate()` blocks until the worker is terminal**, so a runner that scores off the HTTP response
  must set a generous client timeout (Live runs are minutes each; a frozen run blocks until the engine
  wall/cost/iteration limits fire). The response includes the patch artifact paths directly.
- **`generate()` now owns the same concurrency guard operators configure for the benchmark VM.**
  `swebench.max-concurrent-runs` caps concurrent cleanroom/codegen calls, and
  `swebench.submit-queue-wait-seconds` controls how long a caller waits before receiving HTTP 429.
  Driver shard/concurrency values should feed this limit, not replace it.

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
native x86_64 infra). Current internal runs should use either `/run` or `/generate`; both hit the same
`BenchmarkGenerationService` path.

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

**Suite-agnostic by construction.** Every suite's harness POSTs the *same* event shapes (`TASK_STARTED`
/ `RUN_PROGRESS` / `TASK_SCORED` `{task, attempt, reward, esRunId?, cost, inputTokens?, outputTokens?,
failureClassification?, trajectoryLocation?, trajectorySha256?}` / `RUN_DONE` / `SUBMISSION_*`) to the *same*
ingest endpoint; `benchmark_run.suite` (`BenchmarkSuite`: TERMINAL_BENCH / SWE_BENCH / PROGRAM_BENCH —
stored as a String, not a JPA enum, so a new suite needs no migration) is the only discriminator. TB is the
first consumer; SWE-bench + ProgramBench plug in with no schema change.

### Files

| File | Purpose |
|------|---------|
| `BenchmarkTrackingController.java` | `@RequestMapping("/api/v2/test/benchmark")` + `@Profile({"local","dev","benchmark"})` + `@PreAuthorize("hasRole('SUPER_ADMIN')")` (the `/api/v2/test/**` matcher is also SUPER_ADMIN-gated — same operator-only surface as `terminalbench/`). `POST /start` (register a tracked run → `BenchmarkRun`), `POST /{id}/event` (ingest one published event), `POST /{id}/reconcile-usage` (server-side usage repair), `GET` (list 50), `GET /{id}` (run + task results + events), `GET /{id}/events` (live SSE). DTOs are inline records. **Named distinctly from the SWE-bench `BenchmarkController`** (concern 1) to avoid a class/path collision. |
| `BenchmarkTrackingService.java` | `start` / `ingest` / `reconcileUsage` / reads + `subscribe`. `TASK_STARTED` and task-bearing `RUN_PROGRESS` upsert the durable, concurrent active-attempt projection; `TASK_SCORED` removes that attempt and idempotently inserts its terminal row with normalized failure class plus trajectory location/hash. Run rows persist active/completed counts, last progress, ETA and submission readiness. `RUN_DONE` marks a submission-required run READY only when every expected attempt exists and has paired trajectory provenance; incomplete/missing evidence is BLOCKED. SSE contains the same progress projection used by reload, while detail reads rebuild from PostgreSQL rather than process memory. `reconcileUsage` follows task `esRunId` links and replaces usage projections from canonical `run_step_usage` rows without changing verifier evidence. |
| `BenchmarkRun` / `BenchmarkActiveAttempt` / `BenchmarkTaskResult` / `BenchmarkEvent` | Entities (`@Data @Builder`, app-set `String id`). V297's active-attempt relation supports concurrency without collapsing several workers into one misleading current-task field. |
| `BenchmarkTrajectoryStore.java` | Registers only durable benchmark/SWE/Program/TB runs, captures their SSE envelopes as bounded append-only NDJSON under an OS file lock, recursively redacts before append, fsyncs each write, expires files after seven days, and re-parses/re-redacts/re-scans a point-in-time copy before export. Eligibility uses a bounded eight-hour cache with database fallback after restart; missing rows are not negative-cached. The 256 MiB per-run cap fails closed rather than emitting a silently truncated leaderboard trajectory. |
| `BenchmarkTrajectoryController.java` | Profile-local/dev/benchmark, SUPER_ADMIN and `RunAccessGuard` protected `GET /api/v2/test/benchmark/runs/{runId}/trajectory`. Streams only the prepared snapshot and returns run id, exact event count, SHA-256, retention and `truncated=false` headers. |
| `BenchmarkSuite` | The suite discriminator enum + `isKnown(String)`. |
| `Benchmark{Run,TaskResult,Event}Repository` | `JpaRepository<X, String>` finders. |

### Migration

- **V236** `V236__benchmark_tables.sql` — creates `benchmark_run` / `benchmark_task_result`
  (UNIQUE `uq_benchmark_task` on run+task+attempt, FK→run ON DELETE CASCADE) / `benchmark_event` (FK→run
  ON DELETE CASCADE) + supporting indexes. **Boot-validated** against `ddl-auto:validate` (Flyway applies,
  Hibernate validates the 3 entities; app starts clean).
- **V254** `V254__benchmark_task_outcome_reason.sql` — adds `benchmark_task_result.outcome` and
  `outcome_reason` so `/benchmark` can distinguish agent/model failures from infra/verifier/timeouts
  while keeping resolved% reward-based.
- **V296** `V296__benchmark_event_sequence_unique.sql` — serializes parallel event/task ingestion
  on the parent run row and adds a database uniqueness backstop for `(benchmark_run_id, seq)`. The
  migration fails with a diagnostic if historical duplicate sequences exist; it never silently
  rewrites audit history. `scripts/k-eval.py publish` emits scored task events plus `RUN_DONE` after
  an external scorer completes, while `generate` remains explicitly open/UNSCORED.
- **V297** `V297__benchmark_durable_progress_and_trajectory.sql` — persists concurrent active
  attempts, completed count, progress timestamp, ETA, normalized terminal failure classification,
  trajectory location/SHA-256 and deterministic submission readiness. Existing tracked runs remain
  `submission_required=false` / `NOT_REQUIRED`; leaderboard-intended starts opt in explicitly.
  Trajectory content is never stored in these tables: only a secret-free local/object reference
  (`gs://`, `s3://`, `artifact://` or a path without traversal/query/credential material) and the
  lowercase SHA-256 are admitted, with paired database constraints as a second backstop.
  Active attempts carry a persisted 15-minute heartbeat deadline. A backend restart reloads the
  rows; expired rows remain visible as `stale=true` instead of being silently counted as healthy or
  deleted, and the harness can reconcile them with a new progress/terminal event.

### Status

Phases 1 (persistence) + 2 (ingest + SSE) **done**. Phase 3 is wired for Terminal-Bench through
`infra/benchmark/tb/tb-bench-bridge.py`, launched by `infra/benchmark/scripts/run-tb.sh` for the
internal TB 2.1 one-pass matrix. Phase 4 (the in-login `/benchmark` frontend grid + event log +
submission status) is implemented as `frontend/src/ide/IdeBenchmark.tsx`; the browser polls list
and detail endpoints because EventSource cannot send the bearer token. Current internal benchmark
scope: k=1, no submission, DeepSeek V4 native Pro/Flash, Sonnet 5, Sonnet 4.6, GLM 5.2 via
OpenRouter.

### 2026-07-18 local verification boundary

- Full backend `mvn -q verify`: 3,243/3,243, zero failures/errors/skips.
- Trajectory store/controller focused tests: 10/10.
- `infra/benchmark/scripts/test_k_eval.py`: 39/39; runner and Harbor adapter bytecode compile.
- Web benchmark projection: 61 files/269 tests plus production build/format/lint.
- V296→V297 Testcontainers upgrade and clean-install/Hibernate validation pass.

These prove the local implementation, not the completion gate. Before paid dispatch, deploy an exact
tuple with a persistent trajectory root and prove browser/API reload, backend restart, harness download,
event-count/SHA parity and zero missing trajectories. A horizontally scaled deployment must mount one
shared root or replace it with an object-store-backed implementation; node-local directories are not a
valid multi-JVM contract.
