# Benchmark VM (`benchmark-instance`)

> **For the parallel / overnight eval workflow (stop-start, sharded 8-way, GCS results,
> one-command campaign), see [`EVAL-ENV-PLAN.md`](EVAL-ENV-PLAN.md) + `scripts/`.** This
> README covers the base throwaway-VM module it builds on.

Throwaway GCP VM for running the **full 300 SWE-bench Lite** eval off the laptop
(the laptop can't hold ~600 GB of eval images; this VM gets a 1 TB disk). Spin
up → run → **`terraform destroy`**.

**Standalone module with LOCAL state** — deliberately *not* wired to the prod/dev
GCS backend in `../`, so a `destroy` here can never touch production infra.

Defaults: `e2-standard-16` (16 vCPU / 64 GB), 1 TB `pd-ssd`, Ubuntu 22.04, project
`executespecv1`, zone `us-east4-a`. Inbound SSH is locked to the GCP IAP range.

## Cost (destroy to stop it)
- Compute `e2-standard-16` ≈ **$0.55–0.60/hr** on-demand (set `use_spot=true` for ~60–90% off, preemptible).
- 1 TB `pd-ssd` ≈ **$0.187/GB·mo** → ~**$6/day** prorated; billed while the disk exists.
- DeepSeek-flash LLM for 300 ≈ **$5–6**.
- **A ~10 hr parallel run ≈ $15–25 all-in; a 2-day run ≈ $30–45.** `destroy` ends all of it.

## Use

```bash
# 0. Auth (once)
gcloud auth application-default login
gcloud config set project executespecv1

# 1. Spin up
cd infra/benchmark
terraform init
terraform apply            # ~1 min to create; bootstrap runs ~3-5 min after boot

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
- The bootstrap installs Docker, Java 21 + Maven, Python + the `swebench` harness, Postgres (db `executespec`, `postgres/postgres`) and Redis. The app deploy + keys + image pulls are manual (they need secrets / the repo).
- Disk holds ~500 images comfortably, so **no batch-rotation needed** for the full 300 — unlike the laptop.
- To eval multiple providers in one VM session, run separate `run-core-codegen.sh` arms (the `MODEL`/`PROVIDERS` env vars are per-invocation).
- Spot caveat: a preemption mid-run loses in-flight work; only use `use_spot=true` if you batch + resume.
