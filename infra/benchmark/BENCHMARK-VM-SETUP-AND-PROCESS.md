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

**SWE-bench Lite:** run `k-eval.py generate` first, then score the resulting non-empty patches
with the official SWE-bench v4.1 standard harness. `score-watcher.py` is **Pro-only** (it invokes
`SWE-bench_Pro-os/swe_bench_pro_eval.py` against the Pro dataset) and must not be used for Lite.
Rebuild the Lite dataset from HF and use the pinned benchmark virtualenv on the VM:

Use one frozen dataset, one output directory, one campaign ledger and one tracking sidecar for the
entire C1 → C2 → C4 qualification. Before each command, set the server's
`SWEBENCH_MAX_CONCURRENT_RUNS` to the command's `--concurrency` and restart it cleanly. Do not create
a new output directory between stages.

```bash
COMMON=(
  python3 infra/benchmark/scripts/k-eval.py generate
  --provider openrouter --model glm-5.2
  --dataset /path/to/frozen-lite10.json --expected-total 10
  --k 1 --out /path/to/glm-lite10
  --per-task-cap-usd 2 --campaign-cap-usd 20 --opening-balance-usd 24.897977
  --task-timeout-seconds 3600 --shutdown-reserve-seconds 120
)

# C1: cumulative manifest must become 1/10.
"${COMMON[@]}" --concurrency 1 --max-new-tasks 1

# C2: same output/ledger/tracker; cumulative manifest must become 3/10.
"${COMMON[@]}" --concurrency 2 --max-new-tasks 2

# C4: same output/ledger/tracker; cumulative manifest must become 7/10.
"${COMMON[@]}" --concurrency 4 --max-new-tasks 4

# C4 qualified: finish the exact denominator; cumulative manifest must become 10/10.
"${COMMON[@]}" --concurrency 4 --max-new-tasks 3
```

All three monetary flags and the exact cardinality are mandatory. The campaign cap spans every
requested `k` pass; the command refuses before login/tracking unless it can reserve
`per-task-cap × expected-total × k`. Tracking creation and event
publication fail closed. A 401 refreshes once and retries the same reserved request; a second 401
or an indeterminate in-flight request leaves its reservation outstanding so a resume cannot
silently double-spend. The canonical manifest contains only unique HTTP-200 terminal rows; failed
attempts remain in the separate `passN.attempts.jsonl` audit trail. The command-wide budget ledger
uses an OS file lock, so a duplicate process using the same output directory sees current
reservations instead of oversubscribing from stale memory.

`--max-new-tasks N` is positive-only and pass@1-only. It launches only the next N unfinished
dataset rows, writes canonical terminal rows exactly as normal, publishes `RUN_PROGRESS` rather
than `RUN_FAILED`, and exits successfully for an intentional partial wave. Resume uses the same
manifest, campaign ledger, tracking sidecar and exact expected cardinality; completed tasks are
never relaunched.

Generation is wave-based: it never refills a wave while requests are in flight. The runner steps
4 → 2 → 1 only after pressure. Only an HTTP 429 carrying ExecuteSpec's exact
`benchmark generation concurrency exhausted` message proves the semaphore rejected the request
before provider dispatch; that reservation is released and that one task may be retried at the next
lower wave. A generic 429, rate limit, connect timeout/reset, 5xx pressure, or any ambiguous request
keeps its reservation and stops every later wave. Reconcile that provider request manually before
resuming. At concurrency 1, another explicit semaphore 429 stops instead of looping.

Each qualification stage passes only when the cumulative manifest count is exactly 1, 3, 7, then
10; every new ledger row is `SETTLED`; there is no `dispatchClassification`, 429/5xx/connect timeout,
unresolved reservation, stale active attempt, orphan `bench-gen-*` container, OOM, or missing
trajectory hash; and observed heap/RSS/available memory retain operator-approved headroom. Any
adaptive step-down means the requested level did not qualify even if lower-concurrency retries
finished successfully.

### Provider-free recovery of a post-provider capture failure

Never rerun a task whose model completed but whose patch-capture response failed. Use
`k-eval.py recover-capture` only when the original attempt is exactly
`PRESERVED_INDETERMINATE`, its pipeline remains truthfully `FAILED` with the capture-failure
prefix, every physical LLM intent has terminal usage/outbox evidence, and the frozen workspace and
complete trajectory still exist. The command does not authenticate or call a provider. It requires
an immutable image digest, uses `docker --pull=never --network none` against an evidence copy,
records `RECOVERED_PROVIDER_FREE` (never fabricates HTTP 200), writes artifacts/audit/manifest
atomically, and settles the existing reservation only after the manifest is durable.

Create a separate 10-task tracker before recovery and pass its ID as `--new-tracker-id`. The failed
`passN.tracking.json` stays immutable; the recovery command writes validated lineage to
`passN.recovery-tracking.json`, which later `generate` resumes prefer. A malformed or contradictory
lineage aborts before generation. Keep the server/DB evidence JSON, source workspace hash, exact
patch bytes/hash, trajectory bytes/hash/event count, original tracker and new tracker IDs in the
campaign directory. Run the command twice: the second invocation must be idempotent and must not
change the manifest, predictions, patch, audit cardinality or settled cost.

The generation runner computes an absolute epoch deadline immediately before each HTTP request.
The same deadline and reserve reach the worker and tool-dispatch budget; the HTTP timeout is 60
seconds longer than the task deadline so the client does not abandon server work. The server may
tighten the requested deadline to `swebench.task-wall-timeout-minutes` and enforces a minimum
120-second shutdown reserve. A requested deadline that cannot leave that reserve is rejected before
container setup or provider dispatch.

```bash
SCORE_WORKERS=4
PREDS=/path/to/lite-preds.jsonl
RUN_ID=executespec-lite-$(date +%Y%m%d-%H%M%S)
~/swebench-bench/.venv/bin/python -m swebench.harness.run_evaluation \
  --dataset_name SWE-bench/SWE-bench_Lite --split test \
  --predictions_path "$PREDS" --max_workers "$SCORE_WORKERS" \
  --timeout 1800 --run_id "$RUN_ID" --cache_level instance
```

Generation and scoring are separate concurrency controls. `k-eval.py generate --concurrency N`
limits in-flight ExecuteSpec runs; `--max_workers M` limits concurrent official verifier
containers. Set `M` to the smaller of the VM-safe bound and the number of non-empty patches;
when fewer than `M` patches exist, the harness starts only that many workers (it does not create
idle containers). Empty patches are still part of the submission denominator and must be carried
as explicit unresolved/empty results by the scorer rather than silently omitted.

After the official harness finishes, publish only its complete report to the existing `/benchmark`
run. The publish command is fail-closed: with a manifest it requires an exact result for every
manifest instance (including explicit unresolved/error classifications) before emitting
`TASK_SCORED` events and `RUN_DONE`:

```bash
python3 infra/benchmark/scripts/k-eval.py publish \
  --tracking-file out/pass1.tracking.json \
  --manifest out/pass1.manifest.jsonl \
  --results /path/to/<official-run-report>.json
```

If no manifest is available, pass `--expected-total` explicitly; a partial report is rejected.
Keep verifier concurrency below the VM's container/memory budget. The Pro driver remains separate:
`swe-pro-iteration.py --score-workers N` → `swe_bench_pro_eval.py --num_workers N`.

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
