# SWE-bench Pro 100-task iteration sequence - 2026-07-04

Purpose: run a controlled Flash-vs-Pro SWE-bench Pro comparison before any full-731 decision.
This is an internal benchmark loop, not a public submission.

## Current gate

Do not launch the 100-task loop until the operator explicitly approves it.

Before launch:

1. The current sample20 iter2 Pro job must be terminal or intentionally abandoned by the operator.
2. The benchmark backend must run a build that includes `BenchmarkGenerationService` generate-path
   concurrency enforcement.
3. `SWEBENCH_MAX_CONCURRENT_RUNS` must be set to the intended cap, default `8`.
4. The selected 100-task instance set must be identical for Flash and Pro.

## Confirmed concurrency model

- Generation concurrency is controlled twice:
  - runner feed concurrency: `swe-pro-iteration.py --concurrency 8`
  - server hard cap: `swebench.max-concurrent-runs`, sourced from
    `SWEBENCH_MAX_CONCURRENT_RUNS=8` on the benchmark VM
- Scoring concurrency is independent of ExecuteSpec and is controlled by the Scale harness:
  - `swe-pro-iteration.py --score-workers 8`
  - passed to `swe_bench_pro_eval.py --num_workers 8`

The server cap is authoritative. If a runner feeds more requests than the configured cap, the
generate endpoint waits up to `swebench.submit-queue-wait-seconds` and then returns HTTP 429.

## Iteration 1 commands

Run Flash first:

```bash
cd /home/admin_executespec_ai/swebench-bench
python3 /home/admin_executespec_ai/executespec/infra/benchmark/scripts/swe-pro-iteration.py \
  --provider deepseek \
  --model deepseek-v4-flash-native \
  --tag swepro-iter1-s100 \
  --limit 100 \
  --seed 42 \
  --concurrency 8 \
  --score-workers 8
```

Run Pro on the same instance set:

```bash
cd /home/admin_executespec_ai/swebench-bench
python3 /home/admin_executespec_ai/executespec/infra/benchmark/scripts/swe-pro-iteration.py \
  --provider deepseek \
  --model deepseek-v4-pro-native \
  --tag swepro-iter1-s100 \
  --set-file /home/admin_executespec_ai/swebench-bench/swepro-iter1-s100.instances.txt \
  --concurrency 8 \
  --score-workers 8
```

## Polling and RCA rule

Do not poll randomly. Poll every 10 minutes while a 100-task run is active.

Every poll must produce or append one iteration RCA document under:

```text
infra/benchmark/rca/swepro-iter<N>-<model>-poll-<YYYYMMDD-HHMM>.md
```

Each poll RCA must include:

- manifest row count
- active `bench-gen-*` container count
- generated outcomes by bucket
- non-empty patch count
- current DB row status if already published
- concrete failure examples from new failed rows since the previous poll
- whether failures look like harness, model behavior, verifier/scoring, provider/API, timeout, or
  resource saturation

## Iteration 1 decision

After Flash and Pro both finish:

1. Compare selected count, manifest rows, scored rows, resolved rows, no-patch rows, non-COMPLETED
   rows, cost, iterations, and tool calls.
2. Identify generic fixes only. Do not add model-specific hacks.
3. Deploy the generic fixes to the benchmark VM.

## Iteration 2

Maximum one additional 100-task run per model.

Use the same selected instance set unless the operator explicitly approves a new set. Run Flash then
Pro again, using tags:

```text
swepro-iter2-s100
```

Produce final report:

```text
infra/benchmark/SWE-BENCH-PRO-FLASH-VS-PRO-100TASK-REPORT-2026-07-04.md
```

The final report must separate:

- model quality
- completion/reliability
- scoring/verifier failures
- harness defects
- cost and latency
- whether the evidence justifies a larger sample or full-731 run
