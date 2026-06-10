# Benchmarks

## Current Benchmark Surfaces

ExecuteSpec has two distinct benchmark concepts:

1. Execution harnesses that run tasks through ExecuteSpec agents.
2. In-app tracking that records benchmark run/task events from an external verifier.

These should stay separate. The app should not pretend to be the official scorer.

## Implemented Pieces

| Surface | Status | Code |
| --- | --- | --- |
| SWE-bench PATCH harness | Exists | `swebench/`, `benchmark/BenchmarkController` |
| ProgramBench adapter | Exists | `programbench/` |
| Terminal-Bench remote-worker adapter | Exists for local/dev operator use | `terminalbench/`, `tool/remote/`, `engine/WorkerAgent` remote dispatch |
| Common benchmark tracking DB | Added V236 | `benchmark_run`, `benchmark_task_result`, `benchmark_event` |
| Tracking ingest/SSE/list/detail APIs | Added | `benchmark/BenchmarkTrackingController` |
| IDE benchmark dashboard | Added | `frontend/src/ide/IdeBenchmark.tsx` |
| Dedicated benchmark server plan | Planned | `docs/plans/benchmark-server-creation-deployment-2026-06-10.md` |

## What The Tracking Layer Does

- Stores benchmark metadata, task results, and event streams.
- Accepts idempotent task-scored and run-progress events.
- Computes resolved percentage and cost totals.
- Rebroadcasts updates to the operator UI.
- Lets external harnesses remain the source of truth for scoring.

## What It Does Not Do

- It does not replace official benchmark scoring.
- It does not make local model numbers publishable by itself.
- It does not make a run reliable unless the harness reports every task event correctly.

## Current Blockers Before Broad Benchmark Claims

- Event hooks from live Terminal-Bench runs into the tracking layer.
- VM-side campaign runner and artifact layout.
- Cleanroom setup failure must abort rather than score false negatives.
- Diff capture must consistently exclude build/test/repro pollution where required.
- Model-route latency A/B must run from the benchmark VM, not only from local.

## Recommended Next Shape

Keep benchmark execution and benchmark tracking separate:

- `benchmark.executespec.ai`: deployed app profile for tracking/dashboard/admin visibility.
- External harness workers: run tasks, verify with official harnesses, publish event envelopes.
- Artifact bucket: immutable raw logs, patches, reports, and submission payloads.
