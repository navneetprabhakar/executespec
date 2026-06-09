# GCP eval environment — plan + overnight runbook

Stand the laptop-bound SWE-bench eval up on a GCP VM so it runs **parallel + overnight**,
off your machine. Builds on the `infra/benchmark/` Terraform module (VM + 1 TB disk +
IAP SSH + Docker/Java21/Maven/Postgres/Redis/swebench already done).

## Decisions (locked)

| Decision | Choice | Why |
|---|---|---|
| Lifecycle | **Stop/start persistent** | keep the ~600 GB of pulled images + repo warm; stop *compute* billing when idle (~$6/day disk persists). `destroy` only when fully done. |
| Concurrency | **8** (`swebench.max-concurrent-runs`, a property — no code change) | ~3–4× serial, low 429 risk; eval can't fail over so we stay under provider limits. |
| Secrets | **scp a keys file** (+ optionally `application-local.yml`) | keys never touch Terraform/the image. |
| Results | **GCS bucket** (`gcs.tf`) | survive `terraform destroy`; pull for analysis. |
| Gemini | **2 shards** while others run 8 | it 429s easiest; the eval never provider-shifts. |

## What runs where

- **Host (your laptop):** `terraform apply/destroy`, `scp`, `gcloud … ssh/stop/start`.
- **VM (`benchmark-instance`, Ubuntu, bash 5):** the backend + `scripts/*` below, driving the
  existing `~/swebench-bench/{run-core-codegen.sh,score-core-codegen.sh}` over IAP.

| Script (`infra/benchmark/scripts/`) | Role |
|---|---|
| `deploy.sh` | idempotent: pull repo → source keys → launch backend @concurrency=8 → wait health → **set eval flags** |
| `run-sharded.sh` | split a set into N shards, run N `run-core-codegen.sh` in parallel, merge manifests |
| `eval-campaign.sh` | **the overnight script** — ensure-deploy → per-arm shard+score → GCS sync → summary |
| `sync-results.sh` | `gsutil rsync` manifests/scores/patches → bucket |
| `make-full300.sh` | generate `full300.txt` from the dataset |

## ⚠️ Two pre-flight gotchas (read before launching)

1. **The VM must have the new VERIFY prompt.** The `SequentialExecutor.java` prompt edit (the
   thing we're validating) is **uncommitted on the laptop**. The VM gets code via `git pull`, so it
   will NOT have the edit unless you either:
   - **push the branch** with the prompt change and have the VM check it out (clean — `deploy.sh`
     does `git pull`), **or**
   - `scp` the modified `SequentialExecutor.java` to the VM after clone.
   *(Pushing needs your OK per the standing rule — decide after the local control result lands.)*

2. **Flags must match the validated config.** A fresh VM's Flyway defaults are NOT the validated
   set (`provider-prompt-tuning` seeds OFF). `deploy.sh` fixes this automatically — it sets
   `provider-prompt-tuning=ON`, `test-exec-gate=OFF`, `build-tdd=OFF`,
   `salvage-force-tool-choice=ON`, `cross-provider-fallback=OFF`. Don't skip `deploy.sh`.

## One-time setup (daytime, ~15 min)

```bash
# 0. Auth + bring up the box (from infra/benchmark/)
gcloud auth application-default login && gcloud config set project executespecv1
terraform init && terraform apply          # VM + results bucket; bootstrap runs ~3-5 min after boot

# 1. SSH in over IAP (terraform output ssh_command prints this)
gcloud compute ssh benchmark-instance --zone us-east4-a --project executespecv1 --tunnel-through-iap
tail -n5 /var/log/benchmark-bootstrap.log   # ready when /var/log/benchmark-ready exists
newgrp docker

# 2. ON THE VM — get the code + scripts + creds + keys onto it:
git clone <repo-url> ~/executespec            # the repo (carries infra/benchmark/scripts/)
#    from your laptop, scp the eval harness + creds + keys:
#      scp -r ~/swebench-bench            benchmark-instance:~/        (runner, sets, .espec-admin.env, dataset json)
#      scp ~/eval-keys.env                benchmark-instance:~/        (ANTHROPIC_API_KEY=… OPENAI_API_KEY=… GOOGLE_API_KEY=… DEEPSEEK_API_KEY=…)
#      scp ~/.../application-local.yml    benchmark-instance:~/        (optional — profile=local config)

# 3. First deploy (build + boot + flags). ~3-5 min the first time.
bash ~/executespec/infra/benchmark/scripts/deploy.sh
```

## The overnight run (evening — one command)

```bash
# default = VALIDATION campaign: set50 × {GPT, Sonnet, Gemini, DeepSeek}, results to GCS, then stop the VM.
GCS_BUCKET=$(cd ~/executespec/infra/benchmark && terraform output -raw results_bucket 2>/dev/null) \
STOP_ON_DONE=true \
  nohup bash ~/executespec/infra/benchmark/scripts/eval-campaign.sh > ~/campaign.out 2>&1 &
```

Walk away. It ensures the backend is up, runs all arms (resilient — one arm failing won't abort the
rest), scores each, syncs to GCS, prints a per-arm RESOLVED summary, and (with `STOP_ON_DONE=true`)
stops the VM so only the disk bills overnight.

### Presets

```bash
# A) Validation (default) — confirms the GPT lever's NET effect + cross-provider regression
CAMPAIGN=validation SET_FILE=set50.txt        # ARMS default to all 4

# B) Full-300 DeepSeek (the cheap big benchmark; ~$5-6 LLM, ~2-3 hr @ 8 shards)
bash scripts/make-full300.sh
CAMPAIGN=full300-ds SET_FILE=full300.txt ARMS="DEEPSEEK:deepseek-v4-flash-native:8" \
  STOP_ON_DONE=true nohup bash scripts/eval-campaign.sh > ~/campaign.out 2>&1 &
```

Model pins (baseline-matched): GPT `gpt-5.4` · Sonnet `claude-sonnet-4-6` · Gemini `gemini-3.5-flash` · DeepSeek `deepseek-v4-flash-native`.

## Morning

```bash
tail -n40 ~/campaign.out                                   # the SUMMARY block (per-arm RESOLVED)
gsutil -m rsync -r <bucket>/<campaign>-<ts> ./eval-results # pull results to the laptop for analysis
# VM already stopped (STOP_ON_DONE). To iterate again: gcloud compute instances start benchmark-instance …
```

## Parallelism + safety

- `run-sharded.sh` keeps **shards == semaphore (8)** so submits never queue-timeout
  (`swebench.submitQueueWaitSeconds=180`). Round-robin split so no single shard stalls on a slow repo.
- **8 on a 16-core/64 GB box** leaves ~2 cores/run headroom; scoring's docker parallelism
  (`--max_workers 4`) is a *separate* phase.
- **429 discipline:** the runner already detects 429 and bails an arm after 3 consecutive. If
  Anthropic/OpenAI hit limits at 8, drop that arm's shard count (e.g. `…:4`). Gemini ships at 2.
- **No provider-shift:** `cross-provider-fallback=OFF` (deploy.sh enforces) — a 429 is a failed
  instance, never a silent model swap. Per the eval-integrity rule.

## Cost + teardown

- `e2-standard-16` ≈ $0.55–0.60/hr **running**; **stopped** = $0 compute, ~$6/day disk only.
- Results bucket is **destroy-protected** (`results_bucket_force_destroy=false`) so a teardown can't
  delete them. To fully tear down: pull results, then `gsutil rm -r gs://…` (or set the flag true) and
  `terraform destroy`.
- **Standing rule:** never delete the SWE-bench eval images.

---

## Terminal-Bench path (added 2026-06-09)

Same VM module; TB layer added alongside the SWE-bench scripts.

| File | Role |
|---|---|
| `scripts/setup-tb.sh` | one-time: uv + **harbor 0.13.1** venv in `~/tbench` + the `es_harbor_agent` from `tb/` |
| `scripts/run-tb.sh` | the k=N driver — **persistent `jobs_dir` (`~/es-tb-jobs`, NOT /tmp)**, backend+compaction-flags (via API), harbor run, live tracker, GCS results sync |
| `scripts/tb-tracker.py` | live **visual dashboard** (per-task grid + metrics, auto-refresh) → HTML to GCS + Telegram pings |
| `tb/es_harbor_agent.py` | the remote-brain Harbor adapter (version-controlled copy of `~/tbench/es_harbor_agent.py`) |

**Why persistent jobs_dir:** the laptop runs wrote to `/tmp/es-tb-jobs` and a **reboot wiped them** (2026-06-09). `run-tb.sh` writes to `~/es-tb-jobs` + syncs to GCS so results survive reboot/stop-start/teardown.

**Run (k=5, pro, compaction ON, with tracker+GCS):**
```bash
# on the VM, after setup-tb.sh:
GCS_BUCKET=$(cd ~/executespec/infra/benchmark && terraform output -raw results_bucket) \
ES_MODEL=deepseek-v4-pro-native HARBOR_MODEL=deepseek/deepseek-v4-pro K=5 CONCURRENCY=12 \
  nohup bash ~/executespec/infra/benchmark/scripts/run-tb.sh > ~/tb.out 2>&1 &
# watch: open  gs://<bucket>/tb-tracker.html  (auto-refresh) + Telegram pings every 5 min.
# submit: ./.venv/bin/harbor upload <job> ; ./.venv/bin/harbor leaderboard …
```

**⚠️ Pre-flight:** the box gets code via `git pull` → it needs the **TB-extraction commit pushed** (currently held). Push the branch or scp `TerminalBenchRunService.java` first.
