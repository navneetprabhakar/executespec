# `benchmark/` — clean-slate blind SWE-bench harness

> Added 2026-05-24 (RL-2026-05-028). Branch `feature/swebench-harness` (not yet merged).

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

## Run mode = PATCH (not BUILD)

We submit with `runMode=PATCH`, **the production autofix engine** — the same thin-worker loop that
powers the four customer autofix surfaces (`finding/FindingPatchSubmitter`). This is **not** a
benchmark fork: PATCH is a first-class production code-gen path.

Why PATCH and not BUILD (the originally-planned mode): the BUILD-mode smoke (`astroid-1268`) scored
verifiedScore 94 but its diff was product churn — a 469-line reformat of `rebuilder.py` plus
scaffolding files and a fixture rename — not the fix. BUILD's machinery (PostEditFormatter,
scaffolding, auto-commit) pollutes the diff so it can't be scored as a minimal patch. PATCH emits
the minimal source diff a leaderboard submission needs. (The plan doc
[`docs/plans/swebench-harness.md`](../../../../../../../docs/plans/swebench-harness.md) §12 locked
D2=BUILD on paper-review; the smoke flipped it to PATCH — see §13 there + RL-2026-05-028.)

## Files

| File | Purpose |
|------|---------|
| `BenchmarkRunService.java` | The harness. `submit()` → `orchestrator.submitRun(source="swebench-build", repoMode="NONE", repoUrl=null, runMode=PATCH, executionMode=PROTOTYPE, skipApproval=true)`. A synchronous `@EventListener onRunStarted` does `docker pull` → `docker cp /testbed/.` into the host workspace → `docker run -d --rm --network none -v <workspace>:/testbed` → registers the container id in `SwebenchContainerStore` (so `ShellTool`/`BuildProjectTool`/`RunTestsTool` dispatch via `docker exec`). On setup failure it marks the run FAILED (status_reason `BENCH_SETUP_FAILED`) so a bad container can't poison benchmark stats. `captureOnTerminalAsync` polls to terminal then writes `submission.patch` + SWE-bench `preds.json` (the `git diff <baseCommit>` with run-infra excludes: `.agent-context`, `Dockerfile`, `.dockerignore`, `README.md`, `__pycache__`, `.pytest_cache`, `*.egg-info`). `synthesizeBlindSpec` builds the issue-only spec with an "existing repo at /testbed, fix don't scaffold, don't modify tests, pin down EXACT expected behavior + match codebase conventions" framing. |
| `BenchmarkGenerationService.java` | **Lean, synchronous code-gen API** (RL-2026-05-028 follow-up). `generate()` reuses the code-gen components **directly** — sets up the cleanroom (timed `docker pull`, skip-if-present), creates ONE minimal `PipelineRun` row (only for the worker's `hardCapUsd` + `sourceType="benchmark"` gate-off), builds an `ExecutionContext`, calls `patchAgent.run(...)` **synchronously**, captures the diff → `submission.patch` + `preds.json`, returns `{runId, outcome, patchBytes, costUsd, …}`. **No `submitRun`** → no async pipeline, no event listeners (email/preview/report), no credits, no harvesting. Workspace under the (non-Documents) output base by design. This is the going-forward benchmark path: one instance = one call → score. |
| `BenchmarkController.java` | `@RequestMapping("/api/admin/benchmark")` `@PreAuthorize("hasRole('SUPER_ADMIN')")`. `POST /run` (async, via `submitRun` — `BenchmarkRunService`), **`POST /generate` (synchronous lean code-gen — `BenchmarkGenerationService`; preferred)**, `GET /preview/{id}` (resolved image/repo/baseCommit), `GET /instances` (loaded id count). |

## Reused from `swebench/` (sandbox layer — sound, not the problem)

- `SwebenchProperties` — `enabled` flag (default OFF), `outputBase`, `instancesFile`,
  `imageTemplate`, `benchProjectId`/`benchUserId`/`benchOrgId`, `taskWallTimeoutMinutes`,
  `defaultProvider`. Set in `application-local.yml` (gitignored; never committed).
- `SwebenchInstanceLoader` — `findById`, `imageNameFor`, `allInstanceIds`.
- `SwebenchContainerStore` — per-run container registry consulted by the worker's tools.

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

## Status

Validated locally on SWE-bench **Lite** (iteration surface) + **Live** (publish surface) across all
three providers, blind. **First blind Live run (2026-05-24, Anthropic Sonnet): 5/11 = 45% resolved**
before stopping — full result + miss-analysis in
[`docs/benchmarks/swebench-live-2026-05-24.md`](../../../../../../../docs/benchmarks/swebench-live-2026-05-24.md).
Dominant miss mode: **under-scoping multi-file changes** (the actionable scaffold lever). Run stopped
at 11/50 because the local arm64/Rosetta environment kept stalling (a credible full run belongs on
native x86_64 infra). `BenchmarkGenerationService` (`POST /generate`) is **compile-verified but not
yet runtime-tested** — the Live run used the older `submitRun` path (`POST /run`).

Not yet merged; not wired to dev/prod (the `swebench.enabled` flag is OFF by default and only set in
the local-only profile). Follow-up: runtime-test `/generate`, then remove the superseded oracle path
(`swebench/SwebenchTaskService` + the old `SwebenchController`) once this is the validated default.
