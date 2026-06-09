# In-app benchmark tracking (common, multi-suite) — design + build plan

> **Created:** 2026-06-09 · **Status:** building. **Goal:** a COMMON in-app layer that TRACKS any benchmark
> run + its submission + events **inside ExecuteSpec** (in-login dashboard) — **Terminal-Bench first**, SWE-bench
> + ProgramBench next via the same event contract. The verifier/harness runs **externally and publishes
> events**; ExecuteSpec **ingests → stores → re-broadcasts (SSE) → shows**.

## Common / multi-suite (suite-agnostic by design)

The `benchmark/` layer doesn't know about Terminal-Bench specifically — `benchmark_run.suite`
(`BenchmarkSuite`: TERMINAL_BENCH, SWE_BENCH, PROGRAM_BENCH) discriminates, and every suite's external
harness publishes the **same** event shapes (`TASK_SCORED {task, attempt, reward, esRunId?, cost}`,
`RUN_PROGRESS`, `RUN_DONE`, `SUBMISSION_*`) to the **same** ingest endpoint. The `/benchmark` dashboard
renders any of them (grid + resolved% + cost + event log + submission). TB is the first consumer
(`tb-tracker.py`/`run-tb.sh` POST the hooks); SWE-bench + ProgramBench plug in with **no schema change** —
their harness just POSTs the same events. Suite-specific *execution* stays in `terminalbench/` / `swebench/`
/ `programbench/`; the **common tracking + display** is `benchmark/`.

## Scope — ExecuteSpec is the event sink + display, NOT an orchestrator

The external Harbor run (the existing `run-tb.sh` / adapter) **publishes events** to ExecuteSpec as the
verifier scores tasks and on submission. ExecuteSpec persists them, re-broadcasts over SSE, and renders the
in-login `/benchmark` dashboard. **No in-app Harbor subprocess, no Harbor-on-VM** — the verifier stays where
it is; we only receive + show what it publishes. (Harbor still owns the verifier + the official `harbor upload`.)

## Architecture

```
external Harbor run ──POST /api/admin/benchmark/start {suite,dataset,provider,model,k,total} ─▶ {id}   (creates benchmark_run RUNNING)
   run-tb.sh exports BENCHMARK_RUN_ID=id, then publishes events to /{id}/event:
     ├─ TASK_SCORED   {task, attempt, reward, esRunId?, cost} ─▶ upsert benchmark_task_result + event + update resolved/cost + SSE
     ├─ RUN_PROGRESS  {scored, total, ...}                    ─▶ event + SSE
     ├─ RUN_DONE      {resolved, cost}                        ─▶ finalize benchmark_run DONE + SSE
     ├─ SUBMISSION_*  {status, url}                           ─▶ benchmark_run.submission_* + event + SSE
GET  /api/admin/benchmark             ← list
GET  /api/admin/benchmark/{id}        ← run + task results + events (from DB)
GET  /api/admin/benchmark/{id}/events ← SSE live (reuse sse/SseBroadcaster)
/benchmark (frontend, in-login)       ← live grid + resolved% + cost + submission status + event log
```
`TASK_SCORED` can carry the executing `pipeline_run` id (`esRunId`) → execution detail + score together.

## Persistence (V236)

- **`benchmark_run`** — id, suite, dataset, provider, model, k, total/resolved_tasks, status, cost_usd,
  harbor_job_name, **submission_status**, **leaderboard_url**, created_by_user_id, created/started/finished_at, error.
- **`benchmark_task_result`** — per-task scores (task_name, attempt, reward 1/0, es_run_id, cost). Unique (run, task, attempt).
- **`benchmark_event`** — the event stream: seq, type, message, payload (JSON), created_at.

## Phases

1. **Persistence** (run + task_result + event) — *done.*
2. **Ingest + SSE** — `BenchmarkController` (`/start`, `/{id}/event`, `/{id}`, list, `/{id}/events` SSE) +
   `BenchmarkService` (persist + aggregate + broadcast). SUPER_ADMIN + `@Profile({"local","dev","benchmark"})`.
3. **Event hooks** — `run-tb.sh` / `tb-tracker.py` POST events as the verifier publishes (a thin curl per event).
4. **`/benchmark` frontend** — live grid + event log + submission status, in-login, SSE-fed.

## Decisions

- **Track + display only** — ExecuteSpec receives the verifier's published events; it does not run or reimplement Harbor.
- **Reuse** `sse/SseBroadcaster` + the persistence; each scored task can link its `pipeline_run`.
- **Operator-only** — SUPER_ADMIN + `@Profile`. Built feature→dev→main; deployed to the bench env.
