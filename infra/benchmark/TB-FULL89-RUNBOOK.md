# Terminal-Bench 2.1 — internal one-pass runbook

> Operator runbook for the internal TB 2.1 full-89 benchmark on the amd64 benchmark VM.
> This is **not a leaderboard submission**. It is k=1 only, tracked through the in-app
> `/benchmark` dashboard.

**Why the amd64 VM, not the laptop:** the Mac is arm64, so it emulates the amd64 TB task images,
which destabilizes runs and understates results. Run this on the benchmark VM.

## Scope

- Dataset: `terminal-bench/terminal-bench-2-1`
- Attempts: `k=1` only
- Tracking: in-app `/benchmark` bridge + HTML tracker
- Submission: disabled; do not run `submit-tb.sh` for this internal pass
- Excluded from this pass: Gemini, GPT, Sarvam, and frontier routes

## Models

Run one at a time through `scripts/run-tb-internal-once-matrix.sh`.

| # | Model | `ES_PROVIDER` | `ES_MODEL` | `HARBOR_MODEL` |
|---|-------|---------------|------------|----------------|
| 1 | DeepSeek V4 Pro native | `deepseek` | `deepseek-v4-pro-native` | `deepseek/deepseek-v4-pro` |
| 2 | DeepSeek V4 Flash native | `deepseek` | `deepseek-v4-flash-native` | `deepseek/deepseek-v4-flash` |
| 3 | Sonnet 5 | `anthropic` | `claude-sonnet-5` | `anthropic/claude-sonnet-5` |
| 4 | Sonnet 4.6 | `anthropic` | `claude-sonnet-4-6` | `anthropic/claude-sonnet-4.6` |
| 5 | GLM 5.2 | `openrouter` | `glm-5.2` | `z-ai/glm-5.2` |

`es_harbor_agent` resolves the real ExecuteSpec route from `ES_PROVIDER` + `ES_MODEL`.
`HARBOR_MODEL` is the cosmetic model name Harbor records.

## Pre-flight

1. VM code is current on this branch.
2. Backend can run with `local`, `dev`, or `benchmark` profile.
3. SUPER_ADMIN credentials are available in `ADMIN_ENV` or `BR_TOKEN` is set.
4. Provider keys exist for DeepSeek native, Anthropic, and OpenRouter.
5. `/benchmark` is reachable as SUPER_ADMIN.
6. `~/tbench` has Harbor and the version-controlled `es_harbor_agent.py`.

## Run the approved matrix

```bash
GCS_BUCKET=$(cd ~/executespec/infra/benchmark && terraform output -raw results_bucket) \
REPO=~/executespec JOBS_DIR=~/es-tb-jobs CONCURRENCY=8 \
  nohup bash ~/executespec/infra/benchmark/scripts/run-tb-internal-once-matrix.sh \
  > ~/es-tb-jobs/tb21-internal-matrix.out 2>&1 &
```

The matrix script hard-codes `K=1`, `SUBMIT_TB=off`, `BRIDGE=on`, and the approved model list.
`run-tb.sh` refuses `K != 1` unless `ALLOW_TB_MULTI_PASS=true` is explicitly set for a separate
future campaign.

## Watch it

- In-app dashboard: `https://<vm>/benchmark` as SUPER_ADMIN.
- Bridge log: `~/es-tb-jobs/<job>-bridge.log` contains the `benchmark_run id`.
- HTML tracker: `~/es-tb-jobs/<job>-dashboard.html` and optional GCS copy.
- Truth source: each Harbor trial's `result.json`.

Expected `/benchmark` behavior:

- one row per model
- `suite=TERMINAL_BENCH`
- `dataset=terminal-bench-2-1`
- `k=1`
- live `TASK_SCORED` updates with resolved count, cost, and token totals
- terminal `RUN_DONE` event

## Submission

Do not submit this run. `submit-tb.sh` remains for a future explicitly approved leaderboard run,
but this internal benchmark is run-only.
