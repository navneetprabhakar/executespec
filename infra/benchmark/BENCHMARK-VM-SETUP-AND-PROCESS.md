# Benchmark VM — setup & process (simple runbook)

A practical, end-to-end guide for spinning up the disposable GCP benchmark VM, deploying the
current ExecuteSpec build onto it, running the benchmarks, tracking them, and (mandatory) tearing
everything down. Includes the real gotchas hit on 2026-07-18.

> **Golden rules**
> - The VM lives in the **production GCP project `executespecv1`** — stay surgical; only touch
>   `benchmark-instance-*` / `<jobname>-*` resources, never `executespec-dev-*`.
> - **Teardown is mandatory** after every run (`terraform destroy` + verify zero residue).
> - Terraform state here is **LOCAL and isolated** (`infra/benchmark/`), so a destroy here can never
>   touch dev/prod.
> - Deploy the **current build** — `startup.sh` only installs the toolchain, it does NOT build the app.

---

## 0. What gets created

Terraform (`infra/benchmark/*.tf`, local state) creates a self-contained throwaway stack:
- Dedicated auto-mode network + Cloud Router + NAT (the project blocks VM external IPs, so egress
  goes through NAT; the VM has **no public IP**).
- Firewall: SSH from the IAP range only.
- VM `benchmark-instance` — `e2-standard-16`, 1 TB pd-ssd, `us-east4-a`, Ubuntu 22.04. `startup.sh`
  installs Java 21 / Node / Redis / Postgres+pgvector / Docker / swebench toolchain (marker file
  `/var/log/benchmark-ready` when done, ~10–20 min).
- A **global HTTPS load balancer** (static IP + managed cert `benchmark-instance-ssl` for
  `benchmark.executespec.ai`) fronting the VM — this is what Cloudflare points at.
- Results GCS bucket.

Key Terraform variables (`variables.tf`): `project_id=executespecv1`, `zone=us-east4-a`,
`machine_type=e2-standard-16`, `disk_size_gb=1000`, `benchmark_domain=benchmark.executespec.ai`,
`use_spot=false`.

---

## 1. Prerequisites (once per machine / when tokens expire)

```bash
gcloud auth login                          # CLI creds (for gcloud list/ssh/etc.)
gcloud auth application-default login      # ADC — SEPARATE creds that Terraform uses
gcloud config set project executespecv1
```

⚠️ **Gotcha #1 (ADC vs CLI):** Terraform authenticates via **Application Default Credentials**, not
your `gcloud auth login`. If `terraform apply` fails with `invalid_grant / reauth related error
(invalid_rapt)` while `gcloud compute … list` works, you only refreshed the CLI login — run
`gcloud auth application-default login`. Verify: `gcloud auth application-default print-access-token`.

Also need: Terraform ≥1.5, and a Cloudflare login (the DNS step is manual unless you wire the
Cloudflare provider).

---

## 2. Provision the VM + LB

```bash
cd infra/benchmark
cp terraform.tfvars.example terraform.tfvars   # then edit if needed
```
⚠️ **Gotcha #2 (bucket name clash):** the singleton results bucket
`executespecv1-swebench-eval-results` may already exist → `terraform apply` 409s on create. Set a
**unique** `results_bucket_name` in `terraform.tfvars` (e.g. `executespec-swebench-lite-<date>-results`)
and `results_bucket_force_destroy = true` so teardown is clean.

```bash
terraform init
# Fast path: mint just the LB IP first so you can start the Cloudflare step immediately
terraform apply -target=google_compute_global_address.lb -auto-approve
terraform output -raw load_balancer_ip     # e.g. 8.232.173.180  ← Cloudflare target
# Then the rest (VM boots + bootstraps async, cert is created in PROVISIONING state)
terraform apply -auto-approve
terraform output                            # instance_name, load_balancer_ip, ssh_command, teardown_command
```

---

## 3. Cloudflare DNS (the one manual gate)

Add an **A record**: `benchmark.executespec.ai` → the `load_balancer_ip` from step 2, **DNS-only
(grey cloud, NOT proxied)**.

⚠️ **Gotcha #3 (grey cloud):** the Google-managed SSL cert validates by the domain resolving
**directly** to the LB IP. Cloudflare's orange-cloud proxy makes Google see Cloudflare's IP →
cert stays stuck in PROVISIONING. Use **DNS-only**. Cert goes ACTIVE ~15–60 min after DNS is live.
Only touch this one record — never dev/prod DNS.

Verify: `dig +short benchmark.executespec.ai @1.1.1.1` → the LB IP.

---

## 4. Safety auto-stop (prevent idle spend)

The VM has **no built-in idle shutdown**. As soon as it's `RUNNING`, arm a hard backstop over the
IAP tunnel (24h per founder default):
```bash
gcloud compute ssh benchmark-instance --zone us-east4-a --tunnel-through-iap --quiet \
  --command "sudo shutdown +1440 'benchmark safety auto-stop'"     # 1440 min = 24h
```
This is insurance only — the real cost-off is `terraform destroy` (step 8). While iterating without
running, you can `gcloud compute instances stop benchmark-instance --zone us-east4-a` (compute
billing off, disk + toolchain preserved) and `start` later.

---

## 5. Deploy the CURRENT build (you own this — startup.sh does NOT build the app)

⚠️ **Gotcha #4 (build deploy):** `startup.sh` installs only the toolchain. Ship the exact current
committed HEAD, not stale code:
```bash
# From the server repo, capture the exact committed HEAD (clean, gitignored files excluded):
git archive --format=tar.gz -o /tmp/es-build.tgz HEAD
# Copy over IAP + extract on the VM:
gcloud compute scp /tmp/es-build.tgz benchmark-instance:~/es-build.tgz --zone us-east4-a --tunnel-through-iap
gcloud compute ssh benchmark-instance --zone us-east4-a --tunnel-through-iap \
  --command "mkdir -p ~/executespec && tar xzf ~/es-build.tgz -C ~/executespec && cd ~/executespec && mvn -q -DskipTests package"
```
Set the benchmark config/env on the VM (`swebench.*`, `DEEPSEEK_API_KEY`, crypto key — see runbooks).

⚠️ **Gotcha #5 (crypto gate on `benchmark` profile):** the `benchmark` Spring profile has
`crypto.require-encryption: true`, so the backend refuses to boot without a crypto key
(`CRYPTO_LOCAL_KEY` env, a Base64 32-byte AES key, or a reachable GCP Secret Manager key). Provide
one, or (for a purely local dry-run) use `ES_PROFILE=local` which disables crypto — but `local`
also disables live-LLM, so override `executespec.live-llm.enabled=true` and note `local` reuses an
already-healthy `:8080` backend instead of starting its own.

---

## 6. Run the benchmarks

**Terminal-Bench 2.1 (local Harbor on the VM):** `infra/benchmark/scripts/run-tb.sh`
- Env: `ES_PROVIDER=deepseek ES_MODEL=deepseek-v4-pro-native` (or `-flash-native`), `K`, `CONCURRENCY`,
  `TASK_LIMIT` (subset canary), `JOB_NAME`, `BRIDGE=on` (feeds `/benchmark`). Admin creds in
  `~/swebench-bench/.espec-admin.env`.
- ⚠️ **Gotcha #6 (admin login):** the runner's login reads `ESPEC_ADMIN_EMAIL`/`ESPEC_ADMIN_PW`
  (ESPEC_ prefix), and a stale-cached AppUser can make a DB-reset password fail — use a fresh
  SUPER_ADMIN. It aborts before Harbor if login fails (so no wasted spend).
- **Leaderboard vs internal:** internal validation is `k=1` + custom Harbor timeouts + private. A
  **leaderboard submission** needs `k≥5`, DEFAULT timeouts (no overrides), and `harbor run --upload`
  with public ATIF trajectories — see `PHASE7-BENCHMARK-RULES-DIGEST-2026-07-18.md`.

**SWE-bench Lite:** batched driver over `k-eval.py generate` + `score-watcher.py` (see
`FULL-731-PRO-VM-RUNBOOK-2026-07-07.md` for the batching pattern; Lite is 300 instances, so much
smaller than the 731 Pro run). Rebuild the dataset from HF; clone the official scorer.

Generation and scoring are separate concurrency controls. `k-eval.py generate --concurrency N`
limits in-flight ExecuteSpec runs; the official scorer must use the bounded watcher
`infra/benchmark/scripts/score-watcher.py` with `SCORE_WORKERS=N` (default `2` on the canary VM).
Each scorer worker gets an isolated output directory and emits its reward only after the official
verifier returns. Keep the score worker count below the VM's container/memory budget; parallel
scoring is safe, but unbounded Docker fan-out can make results less reliable. The Pro driver uses
the same rule through `swe-pro-iteration.py --score-workers N` → `swe_bench_pro_eval.py --num_workers N`.

---

## 7. Track & capture

- In-app **`/benchmark`** dashboard (SUPER_ADMIN, profiles local/dev/benchmark) shows resolved%,
  cost, tokens, per-task pass/fail — survives reload/restart (durable DB rows).
- **Export trajectories to disk DURING the run** — the event log is Redis 24h TTL, so dump
  `run_step_usage` + event log per instance (and ATIF for TB) before it expires; leaderboard PRs
  require them. Sync results to the GCS bucket.
- Reconcile TB with `infra/benchmark/scripts/reconcile-tb-result.py` (separates passes / verifier
  fails / timeouts / positive-reward-no-run).

---

## 8. Teardown (MANDATORY)

```bash
cd infra/benchmark
terraform destroy       # removes VM, LB, network, NAT, firewalls, cert, bucket (force_destroy)
```
Then **verify zero residue** (a prior TB21 run left an orphaned LB billing for ~3 days):
```bash
P=benchmark-instance    # or your <job-name>
for t in "instances list" "addresses list --global" "forwarding-rules list --global" \
         "target-https-proxies list" "target-http-proxies list" "url-maps list" \
         "backend-services list" "instance-groups list" "health-checks list" \
         "ssl-certificates list" "firewall-rules list" "routers list" "networks list"; do
  gcloud compute $t --filter="name~$P" --format="value(name)" 2>/dev/null
done | sort -u        # must be empty
```
Delete the results bucket if not force-destroyed. **Remove the Cloudflare A record.** If manual
gcloud deletion is needed (no TF state), delete in dependency order:
forwarding-rules → proxies → url-maps → backend-service → instance-group + health-check → ssl →
address → firewalls → router (removes NAT) → network → bucket.

---

## Quick reference (2026-07-18 session)
- Project `executespecv1`; VM `benchmark-instance` (e2-standard-16 / 1TB / us-east4-a); LB IP
  `8.232.173.180`; domain `benchmark.executespec.ai` (Cloudflare A, DNS-only); results bucket
  `executespec-swebench-lite-20260718-results`; TF state local in `infra/benchmark/`.
- Current state: VM **stopped/parked** (`TERMINATED`) awaiting the benchmark go; not yet running.
- SSH: `gcloud compute ssh benchmark-instance --zone us-east4-a --project executespecv1 --tunnel-through-iap`.
- Related docs: `PHASE7-BENCHMARK-RULES-DIGEST-2026-07-18.md` (submission rules), `TB21-OPERATIONAL-NOTES.md`,
  `FULL-731-PRO-VM-RUNBOOK-2026-07-07.md`, `benchmark-closed-loop` design in `docs/plans/`.
