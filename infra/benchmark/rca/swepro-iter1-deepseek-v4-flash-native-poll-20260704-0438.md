# SWE-bench Pro iter1 Flash poll RCA - 2026-07-04 04:38 UTC

## Status

Invalid/aborted pre-scoring run. This is not a model score and must not be used in Flash-vs-Pro comparison.

- Model: DeepSeek V4 Flash native
- Tag: `swepro-iter1-s100`
- Runner PID: `1249908` (stopped)
- Log: `/home/admin_executespec_ai/swebench-bench/swepro-iter1-flash-20260704-042821.log`
- Manifest: `/home/admin_executespec_ai/swebench-bench/swepro-iter1-s100-deepseek-v4-flash-native.manifest.jsonl`
- Backend SHA: `fb010a61d5b387155bb7aed46ce4d2d7a88a8d0f`

## Poll Evidence

- Runner active at poll: yes, elapsed about 10m55s.
- Active `bench-gen-*` containers at poll: 8.
- Manifest rows at poll: 10 initially; 11 by DB follow-up.
- HTTP status bucket: `200=10` at first poll sample.
- Backend generation outcome bucket: `ERROR=10` at first poll sample.
- Non-empty patch rows: `4/10` at first poll sample.
- UI row: none. The current runner publishes `/benchmark` only after generation + scoring completes; this run failed during generation and was stopped before publish.

## Failure Examples

First manifest row:

```json
{"instance":"instance_internetarchive__openlibrary-f8cc11d9c1575fdba5ac66aee0befca970da8d64-v13642507b4fc1f8d234172bf8129942da2c2ca26","http":200,"outcome":"ERROR","patchBytes":0,"iterations":3,"toolCalls":4,"costUsd":0.000367,"durationSec":203}
```

DB rows for sampled run IDs show `status_reason=BENCH_GEN:ERROR` even when the underlying `pipeline_run.status` is `COMPLETE` or `PARTIAL`, meaning the benchmark generation wrapper returned error for the benchmark result.

Backend logs during the interval show repeated provider connectivity failures:

```text
OpenAI native tool call failed
I/O error on POST request for "https://api.deepseek.com/v1/chat/completions": HTTP connect timed out
WorkerAgent router failure at iter=18
benchmark-gen END ... outcome=ERROR ... patch=3882B
```

## RCA

Primary root cause: generation concurrency 8 saturated or exposed DeepSeek native API connectivity from the benchmark VM. Multiple parallel worker lanes hit `HTTP connect timed out` to `api.deepseek.com`; the worker treats router failure as terminal `ERROR`, so generation returned `BENCH_GEN:ERROR` rows.

Secondary visibility gap: the 100-task runner publishes a `/benchmark` row only after generation and official scoring. A pre-scoring abort therefore does not show in the UI, which made the operator surface look idle even though generation had started and failed.

This is different from the earlier 404 failure. The 404 was fixed by deploying `SWEBENCH_INSTANCES_FILE=/var/swebench/swebench_pro_full.json` and adding backend selected-instance preflight; current backend now loads `731` instances and the runner preflight passed.

## Classification

- Harness/config: visibility gap for pre-scoring failures.
- Provider/API: DeepSeek native connection timeouts under concurrency 8.
- Resource saturation: possible outbound connection/API saturation, not VM CPU/RAM proven yet.
- Model behavior: not assessable; failures occurred at provider transport level.
- Scoring/verifier: not reached.

## Required Fixes Before Relaunch

1. Add runner early UI publication: create a `SWE_BENCH` benchmark run at start and post `RUN_FAILED` if generation aborts before scoring, so the UI never looks idle.
2. Reduce DeepSeek generation concurrency for iter1 to a level that does not produce connect-timeout storms. Start with 4 for DeepSeek Flash/Pro unless a live preflight proves 8 is stable.
3. Keep scoring workers at 8; official scoring is local/docker and independent of provider API pressure.
4. Keep server hard cap at 8 but runner feed concurrency lower for DeepSeek. The cap remains useful for other providers or later tuning.
5. Preserve exact same 100-instance set for Flash and Pro.

## Decision

Aborted run is invalid. Do not count it as an iteration. Relaunch after visibility fix and concurrency adjustment.
