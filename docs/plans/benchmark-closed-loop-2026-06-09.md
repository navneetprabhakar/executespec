# Closed-loop in-app benchmark (Terminal-Bench) — design + build plan

> **Created:** 2026-06-09 · **Status:** building (Phase 1). **Goal:** trigger + track + score Terminal-Bench
> **inside ExecuteSpec** (API + in-login dashboard), instead of operator shell scripts + an external tracker.

## The hard constraint (why this *orchestrates* Harbor, not reimplements it)

Terminal-Bench's **task containers, pass/fail verifier, and `harbor upload` (official leaderboard submission)
are Harbor's** — a third-party framework. Reimplementing the verifier would make our scores diverge from the
official leaderboard and invalidate submission. So the closed loop **drives Harbor under the hood** (runs it as
a subprocess on the box, captures its results) and ExecuteSpec owns the **trigger + tracking + display + submit**.

## Architecture

```
POST /api/admin/benchmark/start {suite, dataset, provider, model, k, tasks?}     ← trigger (SUPER_ADMIN, @Profile {local,dev,benchmark})
   └─ BenchmarkOrchestratorService (virtual thread)
        1. persist benchmark_run (RUNNING, model, k, total)
        2. generate the harbor config (agent = es_harbor_agent → localhost; this same ExecuteSpec runs the worker)
        3. run `harbor run -c <cfg>` as a subprocess (Docker already present on the box)
        4. poll the harbor job dir → as each <task>__*/verifier/reward.txt lands:
              persist benchmark_task_result(task, attempt, reward, es_run_id, cost) + broadcast SSE
        5. on done: finalize benchmark_run (resolved/total, cost, DONE) + SSE terminal event
GET  /api/admin/benchmark            ← list runs
GET  /api/admin/benchmark/{id}       ← run + per-task results (from DB)
GET  /api/admin/benchmark/{id}/events← SSE live updates (reuse sse/SseBroadcaster)
POST /api/admin/benchmark/{id}/submit← run `harbor upload <job>` → official leaderboard
DELETE /api/admin/benchmark/{id}     ← cancel (kill the harbor subprocess)
/benchmark  (frontend, in-login admin page) ← live grid + resolved% + cost, rendered from the DB via SSE
```

**Each TB task is still a `PipelineRun`** (the existing Layer-1 execution tracking — status/model/tokens/cost).
`benchmark_task_result.es_run_id` links to it, so execution detail + the benchmark score live together.

## Persistence (V236)

- **`benchmark_run`** — `id` (UUID), `suite`, `dataset`, `provider`, `model`, `k`, `total_tasks`,
  `resolved_tasks`, `status`, `cost_usd`, `harbor_job_name`, `created_by_user_id`, `created_at`,
  `started_at`, `finished_at`, `error`.
- **`benchmark_task_result`** — `id`, `benchmark_run_id` (FK), `task_name`, `attempt`, `reward` (1/0),
  `es_run_id`, `cost_usd`, `created_at`. Unique `(benchmark_run_id, task_name, attempt)`.

## Phases

1. **Persistence + orchestrator core (backend)** — V236 + entities + repos + `BenchmarkOrchestratorService`
   (harbor-config gen, subprocess, job-dir poller, result persistence) + `BenchmarkController` (start/get/list/
   events/cancel) + SSE. SUPER_ADMIN + `@Profile({"local","dev","benchmark"})`. **Dev against `profile=local`
   + local `~/tbench` Harbor.**
2. **Harbor on the bench VM** — install harbor 0.13.1 + dataset + the agent on `executespec-benchmark-vm`
   (Docker present) via `startup.sh`/a setup step, so the orchestrator runs it server-side. Config: a
   `benchmark.harbor.*` block (harbor bin, jobs dir, dataset).
2. **`/benchmark` frontend** — admin page: list runs + live grid (the tb-tracker dashboard, native + auth-gated)
   + per-task drill-in (links to the run) + resolved% + cost, fed by the DB + SSE.
4. **Submit + polish** — `POST /{id}/submit` → `harbor upload`; per-run notifications; cancel/cleanup.

## Decisions

- **Orchestrate, don't reimplement** Harbor (see constraint).
- **Reuse, don't fork:** the worker run path (`TerminalBenchRunService` via the TB endpoint the harbor agent
  already calls), the SSE infra (`sse/SseBroadcaster`), the cost tracker. The orchestrator is a thin
  manager + persistence + poller around Harbor.
- **Operator-only / `@Profile`-gated / SUPER_ADMIN** — same posture as the TB endpoint; not a customer surface.
- **Branch:** built feature→dev→main (permanent product code); deployed to the bench env for use.

## Owed before "done"

- Harbor-on-VM provisioning (Phase 2) — until then the orchestrator runs Harbor wherever ExecuteSpec runs
  (local works; the bench VM needs the install).
- The frontend page (Phase 3).
- The external `tb-tracker.py` / `run-tb.sh` remain valid + are the fallback until this lands.
