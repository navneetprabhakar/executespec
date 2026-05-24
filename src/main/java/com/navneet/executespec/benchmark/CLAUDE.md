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
| `BenchmarkController.java` | `@RequestMapping("/api/admin/benchmark")` `@PreAuthorize("hasRole('SUPER_ADMIN')")`. `POST /run` (submit one instance), `GET /preview/{id}` (resolved image/repo/baseCommit), `GET /instances` (loaded id count). |

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

## Status

Validated locally on SWE-bench **Lite** (iteration surface) + **Live** (publish surface) across all
three providers, blind. Not yet merged; not wired to dev/prod (the `swebench.enabled` flag is OFF by
default and only set in the local-only profile). Follow-up: remove the superseded oracle path
(`swebench/SwebenchTaskService` + the old `SwebenchController`) once this is the validated default.
