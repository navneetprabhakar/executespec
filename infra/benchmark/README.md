# Benchmark VM (`benchmark-instance`)

> **For the parallel / overnight eval workflow (stop-start, sharded 8-way, GCS results,
> one-command campaign), see [`EVAL-ENV-PLAN.md`](EVAL-ENV-PLAN.md) + `scripts/`.** This
> README covers the base throwaway-VM module it builds on.

Throwaway GCP VM for running the **full 300 SWE-bench Lite** eval off the laptop
(the laptop can't hold ~600 GB of eval images; this VM gets a 1 TB disk). Spin
up → run → **`terraform destroy`**.

**Standalone module with LOCAL state** — deliberately *not* wired to the prod/dev
GCS backend in `../`, so a `destroy` here can never touch production infra.

For a parallel Terminal-Bench 2.1 instance, use a copied working directory and
[`terraform-tb21-isolated.tfvars.example`](terraform-tb21-isolated.tfvars.example).
The isolated profile requires a unique VM name, results bucket, and DNS name; do
not reuse `benchmark.executespec.ai` or the singleton local state file.

Defaults: `e2-standard-16` (16 vCPU / 64 GB), 1 TB `pd-ssd`, Ubuntu 22.04, project
`executespecv1`, zone `us-east4-a`. The VM has no external IP; SSH is over IAP
and package/Docker/LLM egress goes through Cloud NAT. `terraform apply` also
creates a global HTTP(S) load balancer and outputs the static IP to use for the
Cloudflare `benchmark.executespec.ai` A record.

## Cost (destroy to stop it)
- Compute `e2-standard-16` ≈ **$0.55–0.60/hr** on-demand (set `use_spot=true` for ~60–90% off, preemptible).
- 1 TB `pd-ssd` ≈ **$0.187/GB·mo** → ~**$6/day** prorated; billed while the disk exists.
- DeepSeek-flash LLM for 300 ≈ **$5–6**.
- **A ~10 hr parallel run ≈ $15–25 all-in; a 2-day run ≈ $30–45.** `destroy` ends all of it.

## Use

### Isolated TB 2.1 instance (recommended)

```bash
RUN_ID=tb21-20260715-a1b2
WORKDIR=/tmp/executespec-$RUN_ID
mkdir -p "$WORKDIR"
cp -R infra/benchmark/. "$WORKDIR/"
cd "$WORKDIR"
cp terraform-tb21-isolated.tfvars.example terraform.tfvars
terraform init -backend=false
terraform validate
terraform plan -var-file=terraform.tfvars -out=tfplan
# Review the plan, then apply only after confirming the unique names/domain.
terraform apply tfplan
```

The generated outputs are the authoritative SSH command, zone, load-balancer
address, and GCS results bucket for that instance. Keep this directory and its
state separate from the singleton benchmark environment.

```bash
# 0. Auth (once)
gcloud auth application-default login
gcloud config set project executespecv1

# 1. Spin up
cd infra/benchmark
terraform init
terraform apply            # ~1 min to create; bootstrap runs ~3-5 min after boot
terraform output -raw load_balancer_ip   # Cloudflare A-record target

# Optional app deploy path after GitHub secrets are configured:
#   push to branch `benchmark`, or run Build & Deploy with target_env=benchmark.
# The workflow deploys the built JAR directly to benchmark-instance over IAP/SCP.

# 2. SSH in (IAP tunnel — no public SSH). `terraform output ssh_command` prints this:
gcloud compute ssh benchmark-instance --zone us-east4-a --project executespecv1 --tunnel-through-iap
#    confirm bootstrap finished:
tail -n 20 /var/log/benchmark-bootstrap.log      # ready when /var/log/benchmark-ready exists
newgrp docker                                    # pick up docker-group membership (first login)

# 3. Deploy backend + eval harness ON THE VM (needs the repo + your API keys):
git clone <your repo url> executespec && cd executespec
export OPENAI_API_KEY=... ANTHROPIC_API_KEY=... DEEPSEEK_API_KEY=...   # whichever providers you eval
JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 mvn -q spring-boot:run &   # backend on :8080
#    copy your ~/swebench-bench scripts (run-core-codegen.sh, set*.txt, set-admin.sh, .espec-admin.env)
#    then, for the full 300 on DeepSeek flash:
PROVIDERS=DEEPSEEK SET_FILE=full300.txt DEEPSEEK_N=300 RUN_TAG=ds-full300 \
  MODEL=deepseek-v4-flash-native bash run-core-codegen.sh
bash score-core-codegen.sh core-codegen-ds-full300.manifest.jsonl

# 4. TEARDOWN — stops ALL billing (VM + disk + firewall)
terraform destroy
```

## Notes
- The bootstrap installs the dev-VM-aligned runtime/toolchain set: Docker `29.3.1`, Compose `5.1.1`, Java `21.0.11`, Maven `3.6.3`, Node.js `24.14.1`, Python validator tools + the `swebench` harness, Postgres 14 (db `executespec`, `postgres/postgres`), pgvector `v0.8.0`, and Redis from the official Redis package repo. Redis must be `>=6.2` because Spring Data paths in the app use `GETDEL`; Ubuntu 22.04's stock Redis 6.0 is not sufficient. The GitHub Actions deploy path then ships the JAR and patches the app env.
- Benchmark GitHub environment secrets must include the deploy WIF values plus model keys used by the internal run: `ANTHROPIC_API_KEY`, `DEEPSEEK_API_KEY`, and `OPENROUTER_API_KEY`. Use `BENCHMARK_GITHUB_PAT` for the app's GitHub token; GitHub rejects environment secret names beginning with `GITHUB_`.
- Disk holds ~500 images comfortably, so **no batch-rotation needed** for the full 300 — unlike the laptop.
- To eval multiple providers in one VM session, run separate `run-core-codegen.sh` arms (the `MODEL`/`PROVIDERS` env vars are per-invocation).
- Spot caveat: a preemption mid-run loses in-flight work; only use `use_spot=true` if you batch + resume.

## Internal runs vs. official submissions

`run-tb.sh` is the **internal** Terminal-Bench runner. It is locked to K=1, refuses
`SUBMIT_TB`, and its output is never leaderboard evidence. Nothing in the official path
below relaxes any of that.

`run-tb-official.sh` is the **separate official** campaign runner. It refuses to start or
to seal a submission unless every one of these holds:

| Gate | Refusal code | Meaning |
| --- | --- | --- |
| K >= 5 | `REFUSE_K_TOO_LOW` | An internal K=1 job can never be submitted. |
| Internal marker absent | `REFUSE_INTERNAL_PROFILE` | Jobs from `run-tb.sh` are structurally unsubmittable; there is no override. |
| Official Harbor timeout | `REFUSE_TIMEOUT_DRIFT` | A non-default timeout needs `TIMEOUT_OVERRIDE_ALLOWED=1` **and** a published-rule reference. |
| `harbor run --upload` | `REFUSE_UPLOAD_DISABLED` | A local-only run is not a submission. |
| Frozen versions recorded | `REFUSE_MISSING_FROZEN_VERSION` | Harbor / dataset / agent / config-sha256 / container digest must all be pinned. |
| Exactly K terminal attempts per task | `REFUSE_INCOMPLETE_ATTEMPTS` | A killed or partial campaign cannot be submitted as if it had finished. |
| Trajectory for every passing trial | `REFUSE_MISSING_TRAJECTORY` | Presence check only — Harbor's own ATIF validator still runs in `submit-tb.sh`. |
| Sealed manifest matches disk | `REFUSE_CORRUPT_CHECKSUM` | Detects post-hoc edits, deletions and smuggled-in files. |
| Manifest written once | `REFUSE_MANIFEST_IMMUTABLE` | Evidence is not rewritten; delete the job and rerun. |

Refusal logic lives in `scripts/official_profile.py` (also usable as a CLI:
`validate-profile` / `gate` / `seal` / `verify`, exit 2 on every refusal).

**These are refusal gates, not blessings.** Passing them means only that the artifacts are
internally consistent and the run was configured as an official campaign. The official
verifier — never `run-tb-official.sh` and never the in-app `/benchmark` dashboard — owns the
benchmark verdict.

## Supervised artifact sync

`scripts/sync-results.sh` remains the one-shot copy. `scripts/supervised_sync.py` is the
supervised supplement for long campaigns: an interval loop with capped exponential backoff,
durable last-success / last-error state, and a final sync on SIGTERM/SIGINT so artifacts
survive `terraform destroy`. It **never deletes remote objects** — `-d` / `--delete` raise
`UnsafeSyncCommand` at construction rather than being silently stripped.

```bash
python3 scripts/supervised_sync.py --source ~/es-tb-jobs/<job> \
  --dest gs://bucket/prefix --state ~/es-tb-jobs/<job>.sync.json --interval 300
```

## Read-only health monitor

`scripts/benchmark_health_monitor.py` emits one versioned JSON health/evidence report across
suites. It is **structurally incapable of altering a campaign**: it imports no `subprocess`,
`signal`, or network module and opens no file for writing. `assert_read_only_source()` proves
that from the AST of its own source, and a unit test calls it, so a future edit that adds a
mutation primitive fails the suite instead of quietly gaining authority over a live run.

```bash
python3 scripts/benchmark_health_monitor.py --job-dir ~/es-tb-jobs/<job> --k 5 \
  --expected-tasks 89 --sync-state ~/es-tb-jobs/<job>.sync.json
```

## Concurrency provenance

`scripts/concurrency_profile.py` records the concurrency used per suite **and how strong the
evidence for it is**. The operational default is unchanged at 4. Terminal-Bench is recorded as
`OBSERVED` (an 89-task K=1 campaign completed at 4 without observed failure) — that is an
absence of observed failure, not a saturation measurement; the ceiling was never probed. Every
other suite is `UNMEASURED` and inherits 4 as a guess. Only a controlled RAM/CPU/provider/DB/
Redis/Harbor saturation measurement may be recorded as `MEASURED`.

## Deterministic tests

```bash
python3 -m pytest -q infra/benchmark/tb/test_tb_bench_bridge.py \
  infra/benchmark/scripts/test_k_eval.py infra/benchmark/scripts/test_benchmark_infra.py
```

These use fakes and temp directories. They prove script logic only — never Harbor behaviour,
real GCS behaviour, official verifier acceptance, or campaign health.
