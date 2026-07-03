# Terminal-Bench 2.1 operational notes

Living record for TB 2.1-only benchmark changes, observations, and planned fixes.
This is for internal ExecuteSpec harness validation, not leaderboard submission.

## Current campaign

- Dataset: `terminal-bench/terminal-bench-2-1`
- Attempts: `k=1`
- Submission: disabled (`SUBMIT_TB=off`)
- Primary tracking: in-app `/benchmark` plus Harbor trial artifacts
- Approved model set:
  - DeepSeek V4 Pro native: `ES_PROVIDER=deepseek`, `ES_MODEL=deepseek-v4-pro-native`
  - DeepSeek V4 Flash native: `ES_PROVIDER=deepseek`, `ES_MODEL=deepseek-v4-flash-native`
  - Sonnet 5: `ES_PROVIDER=anthropic`, `ES_MODEL=claude-sonnet-5`
  - Sonnet 4.6: `ES_PROVIDER=anthropic`, `ES_MODEL=claude-sonnet-4-6`
  - GLM 5.2: `ES_PROVIDER=openrouter`, `ES_MODEL=glm-5.2`
- Explicit exclusions for this campaign: Gemini, GPT, Sarvam, frontier routes

## Changes already made for TB 2.1

### Shared worker visible-test gate

- Tightened the Terminal-Bench visible-test verifier gate in the shared `WorkerAgent` path.
- Root cause: read-only test inspection, such as `ls /tests` or `cat /tests/test.sh`, could satisfy
  the old proof shape even when the worker never ran a meaningful verifier command.
- Fix intent: require actual test/verifier execution evidence before accepting terminal visible-test
  proof.
- Important boundary: this is not a separate benchmark codegen path; it is in the same worker path
  used by the benchmark adapter.

### One-pass internal runner

- `infra/benchmark/scripts/run-tb.sh` is locked to internal `k=1` by default.
- `SUBMIT_TB` is refused unless it stays `off`; leaderboard submission is intentionally manual and
  outside this internal campaign.
- `CONCURRENCY` defaults to `4` for a single model run.
- `BRIDGE=on` is the default so `/benchmark` receives task-level events.

### Internal matrix runner

- `infra/benchmark/scripts/run-tb-internal-once-matrix.sh` is the approved model list entrypoint.
- The model list is intentionally narrow for this campaign and excludes Gemini/GPT/Sarvam.
- The matrix runner should not be used to start all models until post-Flash RCA/fixes are complete.

### In-app benchmark tracking

- The in-app benchmark bridge records one `TASK_SCORED` event per Harbor trial.
- `/benchmark` is the operator view for score, cost, tokens, and per-task pass/fail status.
- Harbor trial `result.json` and `reward.txt` remain the authoritative raw scoring artifacts.

### Docker image preflight and contaminated-run guard

- `infra/benchmark/scripts/run-tb.sh` now defaults `TB_PREFLIGHT_IMAGES=on`.
- Before Harbor starts, it runs `harbor download <dataset> --cache` and then
  `infra/benchmark/scripts/tb-preflight-images.py`.
- The preflight reads each cached task's `task.toml` `docker_image` value and pulls every prebuilt
  image with retries. If the image cache or Docker Hub is unhealthy, the run fails before any model
  tokens are spent.
- The bridge now treats Harbor setup failures before ExecuteSpec agent execution as
  `INFRA_SETUP_FAILED`, posts `RUN_FAILED`, writes a bridge fail marker, and the runner stops Harbor.
- These rows must not be compared as model `reward=0` failures. A run with setup failures is
  contaminated and must be rerun after the infra issue is cleared.

## Flash gatefix run observations

Flash gatefix run:

- Job name: `tb21-20260702-2342-deepseek-v4-flash-native-gatefix-c4`
- In-app benchmark run id: `8113ed10-9928-4178-8807-077e3879cc03`
- Provider/model: `deepseek` / `deepseek-v4-flash-native`
- Harbor model label: `deepseek/deepseek-v4-flash`
- Concurrency: `4`
- Attempts: `k=1`
- Final result: `43/89` (`48.31%`), cost `$1.286792`, input tokens `78,869,071`,
  output tokens `1,834,725`.
- Previous comparable Flash one-pass job: `tb21-20260702-1848-deepseek-v4-flash-native-fixed`,
  `48/89` (`53.93%`), cost `$1.355904`.
- Net final delta: `-5` tasks (`-5.62pp`), with `11` regressions and `6` improvements.

Observed during this run:

- VM CPU and RAM have generally not been the bottleneck. RAM can dip during heavy containers but
  has recovered without OOM pressure.
- Several Harbor trials hit `AgentTimeoutError` at the 900 second agent timeout.
- Timeout count improved from `10` in the previous comparable run to `8` in this run.
- When Harbor times out first, the adapter can be cancelled while waiting on SSE, after Harbor has
  scored the trial but before ExecuteSpec has cleanly emitted `COMPLETE`.
- That race can leave the backend `pipeline_run` row in `WORKING`, creating stale backend
  concurrency until manually cancelled.
- Timeout/orphan examples observed so far:
  - `write-compressor`
  - `model-extraction-relu-logits`
  - `gpt2-codegolf`
  - `make-doom-for-mips`
  - `gcode-to-text`
- One confirmed stale backend orphan, `polyglot-rust-c`, was manually cancelled after Harbor had
  scored the trial as `AgentTimeoutError`.
- Event-log checks on the `11` regressed tasks found no `VISIBLE-TEST-GATED` hits, so the
  measured score regression is not currently attributed to an over-strict visible-test gate.
- Two regressions ended with server `WALL_CLOCK_EXCEEDED`: `compile-compcert` and
  `feal-differential-cryptanalysis`.

## TB 2.1 fixes before next benchmark wave

Deploy these before starting the next full model run.

### 1. Separate Harbor timeout from ExecuteSpec wall-clock

Problem:

- Harbor currently kills the agent phase at about `900s`.
- ExecuteSpec TB worker/finalizer behavior is also close to that boundary.
- If both boundaries are too close, Harbor can kill the adapter before the server finalizes.

Target:

- Keep ExecuteSpec TB worker wall-clock lower than Harbor's agent timeout.
- `infra/benchmark/scripts/run-tb.sh` now emits Harbor `agents[].override_timeout_sec`
  and `agents[].max_timeout_sec`.
- Default `HARBOR_AGENT_TIMEOUT_SEC=1350`.
- ExecuteSpec TB worker wall-clock is configurable via
  `terminal-bench.worker-wall-clock-seconds`; default is now `1260s`, leaving a
  90s buffer under Harbor's `1350s` cap for one slow in-flight LLM/tool turn plus
  the terminal finalizer.
- Harbor has no true live pause for an in-flight trial. To preserve benchmark
  progress when changing server values, stop the Harbor launcher/bridge, keep the
  job directory, deploy the backend change, then resume the same job directory.
  Completed trial directories with `result.json` are reused; active incomplete
  trial directories are rerun.

### 2. Adapter finally-cleanup

Problem:

- `es_harbor_agent.py` currently raises when Harbor cancels the streaming agent phase.
- If `run_id` exists and no terminal SSE event was received, the server-side run may remain active.

Target:

- `infra/benchmark/tb/es_harbor_agent.py` now performs best-effort cleanup on abnormal
  adapter exit:
  - if `run_id` exists,
  - and no terminal `COMPLETE` was observed,
  - and `ES_CANCEL_ON_ABORT` is not `false`,
  - call `DELETE /api/runs/{runId}`.
- Cleanup must not hide the original Harbor exception.

### 3. Bridge orphan reconciliation

Problem:

- The bridge can ingest the Harbor score while the corresponding backend run remains `WORKING`.
- Manual cleanup is currently required when this happens.

Target:

- When a Harbor trial has terminal `result.json`/`exception.txt`, especially `AgentTimeoutError`,
  and its mapped `esRunId` is still active, automatically cancel the backend run.
- Record the cleanup in bridge logs so final RCA can distinguish model failure from harness cleanup.

### 4. Parallel wave policy

After the timeout/orphan fix is deployed and Flash is rerun:

- Preferred first parallel wave: two models at `CONCURRENCY=3` each (`3+3`).
- Move to `4+4` only if:
  - RAM remains comfortably above about 20 GiB available,
  - Harbor timeout/orphan rate does not spike,
  - `/benchmark` rows remain cleanly attributable by run id and job dir.
- Do not mix two models into a single Harbor job. Use separate job dirs, separate benchmark run ids,
  and separate Harbor configs.

### 5. Docker registry/setup failure policy

Problem:

- Docker Hub returned registry `500 Internal Server Error` during Harbor environment setup for the
  Pro-native run `tb21-20260703-0655-deepseek-v4-pro-native-timeoutfix-c4`.
- Harbor wrote `result.json` rows with no `agent_result` / no `verifier_result`, so the old bridge
  counted them as `TASK_SCORED reward=0` with `esRunId=null`.
- That makes the in-app resolved rate look like a model score even though ExecuteSpec never ran.

Target:

- Pre-pull all TB task images before the timed run.
- Fail the benchmark if Harbor reports setup failure before agent execution.
- Do not start another model from a contaminated run; mark it invalid in `/benchmark`, stop Harbor,
  and rerun that same model after preflight passes.

## RCA/reporting notes

Final comparison report should separate:

- model/task failures (`reward=0` with clean server terminal state),
- Harbor agent timeout failures,
- backend orphan cleanup events,
- score changes caused by the visible-test gate fix,
- score changes likely caused by model variance or task ordering.

For Flash specifically, compare:

- historical 5-pass Harbor job: per-trial rate and any-pass rate,
- previous one-pass Flash run,
- current gatefix run,
- post-timeout-fix rerun.
