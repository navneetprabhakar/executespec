# Terminal-Bench 2.1 — full-89 sweep runbook (4 models, k=5, leaderboard)

> Operator runbook for the full 89-task TB 2.1 sweep on the amd64 benchmark VM, with **live
> in-app token tracking** (`/benchmark`) + **leaderboard submission** per model.
> Pairs with [`EVAL-ENV-PLAN.md`](EVAL-ENV-PLAN.md) (VM stand-up) and the scripts in `scripts/`.

**Why the amd64 VM, not the laptop:** the Mac is arm64 → it emulates the amd64 TB task images,
which destabilises runs and *understates* results. Run this on the `e2-standard-16` benchmark VM.

## Models + order (locked)

Run **one at a time**, in this order. Gate after Pro.

| # | Model | `ES_PROVIDER` | `ES_MODEL` | `HARBOR_MODEL` | Submit |
|---|-------|---------------|------------|----------------|--------|
| 1 | DeepSeek V4 Flash | `deepseek` | `deepseek-v4-flash-native` | `deepseek/deepseek-v4-flash-native` | ✅ |
| 2 | DeepSeek V4 Pro | `deepseek` | `deepseek-v4-pro-native` | `deepseek/deepseek-v4-pro-native` | ✅ → **then GATE** |
| 3 | Sonnet 4.6 | `anthropic` | `claude-sonnet-4-6` | `anthropic/claude-sonnet-4-6` | ✅ |
| 4 | MiMo v2.5 Pro | `openrouter` | `mimo-v2.5-pro` | `openrouter/xiaomi-mimo-v2.5-pro` | run-only* |

- `es_harbor_agent` resolves the model from **`ES_PROVIDER`+`ES_MODEL` env** — `HARBOR_MODEL` is just
  the cosmetic `model_name` harbor records. `run-tb.sh` exports all three.
- **Pro is native thinking-mode.** Native `reasoning_content` capture+replay is implemented
  (`OpenAiNativeToolClient`, RL-2026-05-052) and the TB path handles thinking-mode `tool_choice`
  (RL-2026-06-004). The `application.yml` "not tier-active, use flash" note is **stale** for the TB
  path (TB sets the model explicitly, not via tier routing). **Still: validate Pro on its first few
  trials** via `/benchmark` — if it produces 0 edits / reasoning round-trip 400s, fall back to
  OpenRouter Pro (`ES_PROVIDER=openrouter ES_MODEL=deepseek-v4-pro`).
- *MiMo: per the latest call, submission targets are Flash + Pro + Sonnet. MiMo runs full-89 for the
  data + in-app tracking; submit only if you opt in (`bash submit-tb.sh` works the same).

## Config (locked)

- **k = 5** attempts/task · **n_concurrent_trials = 8** · full `terminal-bench/terminal-bench-2-1` (89 tasks).
- 8 on a 16-core/64 GB box leaves ~2 cores/trial headroom (each trial = 1 TB container + the remote
  WorkerAgent driving it; the backend runs on the same box).
- **No provider-shift** — `cross-provider-fallback=OFF` (a 429 is a failed trial, never a silent model swap).
- Harbor compliance: the ExecuteSpec Harbor agent writes `agent/trajectory.json` during every trial
  (`SUPPORTS_ATIF=true`). `submit-tb.sh` fails before upload if any passing trial is missing that
  file, because Terminal-Bench leaderboard validation rejects passing trials without trajectories.

## Pre-flight (once, after the VM is up + `setup-tb.sh` has run)

1. **Code is current** — the VM `git pull`s `codex/review-fixes-docs-2026-06-10`, which carries the
   TB-extraction + token-tracking commits + this driver. ✔ (pushed)
2. **Keys** — `scp` the rotated key file + `application-local.yml`; backend up via `deploy.sh`.
3. **harbor auth (interactive — operator only):**
   ```bash
   cd ~/tbench && ./.venv/bin/harbor auth login --no-browser   # GitHub OAuth device code; paste at github.com/login/device
   ```
   Only needed before the first `submit-tb.sh`. Can be done while Flash is running.
   Verify: `cat ~/.harbor/credentials.json` is non-empty (`{}` = NOT authed).
4. **Confirm harbor's upload subcommand** before the first submit (flags can differ by version):
   ```bash
   ~/tbench/.venv/bin/harbor upload --help     # adjust HARBOR_UPLOAD_ARGS in submit-tb.sh if needed
   ```

## Run one model (the per-model command)

`run-tb.sh` brings the backend up + sets compaction flags + launches the **HTML tracker** AND the
**in-app /benchmark bridge** (resolved% + cost + input/output tokens, live), then the harbor run, then
GCS-syncs results.

```bash
GCS_BUCKET=$(cd ~/executespec/infra/benchmark && terraform output -raw results_bucket) \
REPO=~/executespec JOBS_DIR=~/es-tb-jobs \
TB_DATASET=terminal-bench/terminal-bench-2-1 BENCHMARK_DATASET=terminal-bench-2-1 \
ES_PROVIDER=deepseek ES_MODEL=deepseek-v4-flash-native HARBOR_MODEL=deepseek/deepseek-v4-flash-native \
K=5 CONCURRENCY=8 JOB_NAME=es-tb-flash-k5 \
  nohup bash ~/executespec/infra/benchmark/scripts/run-tb.sh > ~/es-tb-jobs/flash.out 2>&1 &
```

Swap the three `ES_*`/`HARBOR_MODEL` + `JOB_NAME` per the table for Pro / Sonnet / MiMo
(`es-tb-pro-k5`, `es-tb-sonnet-k5`, `es-tb-mimo-k5`).

## Watch it

- **In-app dashboard (the token one):** `https://<vm>/benchmark` (SUPER_ADMIN) — live resolved% +
  cost + **aggregate input/output tokens** per run, per-task grid. The bridge prints its
  `benchmark_run id` to `~/es-tb-jobs/<job>-bridge.log`.
- HTML tracker: `~/es-tb-jobs/<job>-dashboard.html` (+ `gs://<bucket>/tb-tracker.html`).
- Telegram pings (start/done + tracker heartbeats).
- Truth = `result.json` per trial (harbor's "Total runtime" display is unreliable).

## Submit one model (after its run finishes + you've validated the dashboard)

```bash
JOB_NAME=es-tb-flash-k5 \
METADATA_FILE=~/es-tb-jobs/metadata-flash-tb21.yaml \
LEADERBOARD_SLUG=terminal-bench-2-1 REQUIRE_ATIF=1 \
  bash ~/executespec/infra/benchmark/scripts/submit-tb.sh
# reads the benchmark_run id from the bridge log, verifies passing trials have agent/trajectory.json,
# runs `harbor upload <job> --public`, then `harbor leaderboard submit -l terminal-bench-2-1 ...`,
# posts SUBMITTED{url} to /benchmark (submissionStatus + leaderboardUrl), pings Telegram.
# Dry-run first if unsure: DRY_RUN=1 JOB_NAME=es-tb-flash-k5 METADATA_FILE=~/es-tb-jobs/metadata-flash-tb21.yaml bash …/submit-tb.sh
```

## The sequence (with the gate)

1. **Flash** — run → validate `/benchmark` → `submit-tb.sh` → confirm on the leaderboard.
2. **Pro** — run → **validate native-Pro early trials** (OpenRouter fallback if broken) → `submit-tb.sh`.
3. **⛔ GATE** — STOP. Wait for explicit operator confirmation before continuing.
4. **Sonnet** — run → `submit-tb.sh`.
5. **MiMo** (last) — run; submit only if opted in.

## Cost / lifecycle

- `e2-standard-16` ≈ $0.55–0.60/hr running; **stopped** = $0 compute, ~$6/day disk.
- DeepSeek runs are cheap (~2-3 hr/model); Sonnet is slower + pricier. Flash+Pro to the gate ≈ first
  ~5-6 hr block.
- Results live in the GCS bucket (destroy-protected) + `~/es-tb-jobs` (persistent, survives reboot).
- When the whole sweep is done + results pulled: `gcloud compute instances stop benchmark-instance …`
  (keep the disk warm) or `terraform destroy` (full teardown — pull results first).
