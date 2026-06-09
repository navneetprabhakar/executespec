# Benchmark Environment — Deployment Record & Runbook

> **Created:** 2026-06-09 · **Status:** standing up (first deploy in progress) · **Lifecycle:** EPHEMERAL
> A new, **isolated** full-stack environment (UI + backend + DB) on GCP for running benchmarks
> (SWE-bench / Terminal-Bench / eval matrices) against **prod-grade code** without touching dev/prod data.
> Tracks the `benchmark` git branch (cut from `main`). Stood up for a benchmarking activity, then
> **torn down** with `terraform destroy` to free resources.

---

## 1. Why this exists

A dedicated benchmark layer keeps eval runs reproducible and isolated:
- **Own database** → eval data never mixes into dev/prod.
- **Own backend** → benchmark load is isolated; runs the real production code-gen pipeline.
- **Own UI + URL** (`benchmark.executespec.ai`) → view results separately.
- **Branch from `main`** → benchmarks run against production-grade code, not dev WIP.

It is the third deployed environment alongside **dev** (`dev.executespec.ai`) and **prod**
(`app.executespec.ai`, not currently provisioned). It is **NOT** the same as the throwaway SWE-bench/TB
eval runner box in [`infra/benchmark/`](../../infra/benchmark/) (that is an IAP-locked harness runner with
no UI/DB) — this is a deployed app environment.

---

## 2. The cluster (as provisioned)

`project: executespecv1` · `region: us-east4` · `zone: us-east4-b` · TF workspace: `benchmark` (isolated state in `gs://executespec-tfstate`)

| Component | Value |
|---|---|
| **Git branch** | `benchmark` (cut from `main`, migration head V235) |
| **Compute VM** | `executespec-benchmark-vm` · `e2-standard-16` (16 vCPU / 64 GB) · 500 GB `pd-ssd` |
| **Database** | Cloud SQL `executespec-benchmark-pg` · Postgres 15 · `db-custom-2-7680` (2 vCPU / 7.5 GB) · 20 GB · ZONAL (no HA) · private IP `10.101.1.3` |
| **Cache** | Memorystore Redis `10.101.0.3:6379` · 1 GB · BASIC |
| **Network** | VPC `executespec-benchmark-vpc` / subnet `executespec-benchmark-subnet` · Cloud NAT egress · IAP-only SSH (`35.235.240.0/20`) |
| **Storage** | `gs://executespec-benchmark-deploy` (JARs + config) · `gs://executespec-benchmark-workspaces` · Artifact Registry `executespec-benchmark-docker` |
| **Load balancer** | global IP `8.233.134.200` · managed cert `executespec-benchmark-ssl` |
| **URL** | `https://benchmark.executespec.ai` |
| **DNS** | Cloudflare `A benchmark → 8.233.134.200` — **grey cloud / DNS-only** (required for the GCP managed cert to validate) |
| **Runtime SA** | `executespec-benchmark-gce@executespecv1.iam.gserviceaccount.com` |
| **CI deploy SA** | `executespec-dev-gce@executespecv1.iam.gserviceaccount.com` (WIF identity, reused via the `development` GitHub environment) |

**Estimated cost while running** (ephemeral — billed only while up): VM ~$390 + disk ~$85 + Cloud SQL ~$100 + Redis ~$36 + LB ~$18 + NAT ~$32 ≈ **~$660/mo (~$0.90/hr)**. `terraform destroy` reduces this to $0.

---

## 3. Changes made (code — committed on the `benchmark` branch)

| File | Change | Commit |
|---|---|---|
| `infra/environments/benchmark.tfvars` | **new** — benchmark env sizing (e2-standard-16, 500 GB pd-ssd, Cloud SQL, Redis, LB, `subdomain=benchmark`, `lifecycle=ephemeral`) | `04176e6e` |
| `infra/variables.tf` | env validation now accepts `dev \| prod \| benchmark` | `04176e6e` |
| `.github/workflows/deploy.yml` | (1) push trigger `[dev]` → `[dev, benchmark]`; (2) `DEPLOY_ENV` 3-way (`main→prod`, `benchmark→benchmark`, else `dev`); (3) instance derives `executespec-${DEPLOY_ENV}-vm`; (4) benchmark public URL; (5) CORS adds `benchmark.executespec.ai`; (6) health-check window widened (`sleep 45` + 20 attempts) for the cold-DB first boot | `bb958cb5`, `8db5777f` |
| `src/main/resources/application-benchmark.yml` | **new** — the Spring `benchmark` profile: maps `datasource.url` to the Cloud SQL `${POSTGRES_*}` env vars (fixes the localhost crash-loop), Redis, LLM keys, Google/GitHub OAuth (redirect → `benchmark.executespec.ai`, **credentials env-only**), LB forwarded-headers, OTLP off | `8db5777f` |

> The `deploy.yml` + tfvars + validation + `application-benchmark.yml` changes are currently **only on the
> `benchmark` branch**. dev/prod pipelines are untouched. Promote to `main` to make the capability permanent
> (see §7).

## 3b. Changes made (GCP — manual, not in code)

| Action | Command / location | Notes |
|---|---|---|
| Provision the stack | `terraform apply -var-file=environments/benchmark.tfvars` (workspace `benchmark`) | 55 resources, isolated state |
| Seed secrets | `gsutil cp gs://executespec-dev-deploy/config/{api-keys,smtp-credentials} gs://executespec-benchmark-deploy/config/` | reused dev's keys |
| **IAM grant** | `gcloud storage buckets add-iam-policy-binding gs://executespec-benchmark-deploy --member=serviceAccount:executespec-dev-gce@executespecv1.iam.gserviceaccount.com --role=roles/storage.objectAdmin` | the CI deploy SA was dev-scoped; needed write access to the new bucket. **Vanishes on `terraform destroy`** — re-run each spin-up (or codify, see §7). |
| DNS | Cloudflare `A benchmark → 8.233.134.200`, grey cloud | user action |

---

## 4. Stand-up runbook (reproduce from scratch)

Prereqs: `gcloud auth login` **and** `gcloud auth application-default login` (Terraform needs ADC — `gcloud auth login` alone is NOT enough), project `executespecv1`.

```bash
# 1. Provision the stack (~8–12 min; Cloud SQL is the long pole)
cd infra
terraform init
terraform workspace new benchmark        # first time only; else: terraform workspace select benchmark
TF_VAR_project_id=executespecv1 terraform apply -var-file=environments/benchmark.tfvars

# 2. Seed secrets into the bench deploy bucket
gsutil cp gs://executespec-dev-deploy/config/api-keys        gs://executespec-benchmark-deploy/config/api-keys
gsutil cp gs://executespec-dev-deploy/config/smtp-credentials gs://executespec-benchmark-deploy/config/smtp-credentials

# 3. Grant the CI deploy SA write access to the new bucket (mirrors its dev grant)
gcloud storage buckets add-iam-policy-binding gs://executespec-benchmark-deploy \
  --member="serviceAccount:executespec-dev-gce@executespecv1.iam.gserviceaccount.com" \
  --role="roles/storage.objectAdmin"

# 4. DNS — Cloudflare:  A  benchmark → $(terraform output -raw lb_ip)   [grey cloud / DNS-only]

# 5. Deploy the app — push the benchmark branch (deploy.yml builds the JAR + deploys to the VM)
git push origin benchmark
gh run watch <run-id> --exit-status          # then VERIFY via `gh run view <id> --json conclusion`

# 6. OAuth (for Google/GitHub login) — register on the OAuth client's Authorized redirect URIs:
#      https://benchmark.executespec.ai/api/auth/oauth2/callback/google
#      https://benchmark.executespec.ai/api/auth/oauth2/callback/github
#    and ensure GOOGLE_OAUTH_CLIENT_ID/SECRET are in gs://executespec-benchmark-deploy/config/api-keys
```

## 5. Teardown (free all resources)

```bash
cd infra
terraform workspace select benchmark
terraform destroy -var-file=environments/benchmark.tfvars
```

This removes the VM, Cloud SQL, Redis, LB, buckets, network. The DNS record (Cloudflare) is manual — remove it too if not respinning. The `objectAdmin` IAM grant disappears with the bucket.

---

## 6. Deployment journey — issues hit & fixes (for the next spin-up)

The first three deploys failed; each fix is now codified above. Recorded so a future stand-up doesn't re-learn them:

1. **`AccessDeniedException: 403` on JAR upload** — the CI deploy SA (`executespec-dev-gce`) had `objectAdmin` on `executespec-dev-deploy` but **not** the new `executespec-benchmark-deploy` bucket (its storage grants are per-bucket; its compute/IAP grants are project-level so SSH still worked). → **Fix:** grant `objectAdmin` on the new bucket (§3b / §4 step 3).
2. **Health check failed (`http=000 exit=7`)** — initially looked like a cold-start timing miss; it was actually the app **crash-looping**. The health window was *also* too short for the cold-DB first boot. → **Fix:** widened the window (`sleep 45` + 20 attempts) on the benchmark branch.
3. **App crash-loop — `UnsatisfiedDependencyException` → `flywayInitializer` → `Connection to localhost:5432 refused`.** Root cause: `startup.sh` sets `SPRING_PROFILES_ACTIVE=<env>,gcp`, i.e. `benchmark,gcp` — but **no `application-benchmark.yml` existed**, so the datasource fell back to the base `application.yml` default (`localhost:5432`). The DB env (`POSTGRES_HOST=10.101.1.3`) was correct and reachable; nothing mapped it. → **Fix:** added `application-benchmark.yml` mapping `${POSTGRES_*}`/`${REDIS_*}` (modeled on the dev profile).
4. **Commit blocked — credential leak.** The first `application-benchmark.yml` copied dev's pattern, which **hardcodes the real Google OAuth client secret** as a property default. → **Fix:** made OAuth credentials **env-only** (no secrets in the repo). This also surfaced a pre-existing leak in `application-dev.yml` (tracked separately — rotate + env-ify).

**Lesson for `startup.sh`:** it assumes a per-env Spring profile file exists. Any new env needs `application-<env>.yml` (or the datasource set explicitly), or it crash-loops on localhost.

---

## 7. Outstanding / owed

- [ ] **Verify the current deploy** (`application-benchmark.yml` fix) — `gh run view <id> --json conclusion` + a real `/actuator/health` probe (don't trust the `watch` exit code).
- [ ] **Google login (user):** register the `benchmark.executespec.ai` redirect URI on the OAuth client **and** confirm `GOOGLE_OAUTH_CLIENT_ID/SECRET` are in `gs://executespec-benchmark-deploy/config/api-keys` (dev hardcoded them in YAML, so they may be absent from the bucket).
- [ ] **Promote to `main`** — fold `benchmark.tfvars` + the `variables.tf` validation + the `deploy.yml` wiring + `application-benchmark.yml` onto `main` so the benchmark capability is permanent (not just on the ephemeral branch), and **codify the deploy-SA bucket grant** in Terraform (`google_storage_bucket_iam_member`) so re-spins self-heal.
- [ ] **Security task (spawned):** rotate + env-ify the hardcoded Google/GitHub OAuth secrets in `application-dev.yml`.

## 8. gcloud setup audit (2026-06-09, project `executespecv1`)

Done while standing this up. Posture is lean and mostly clean; recommended changes:
- **Good:** only dev runs (no orphaned prod resources), no unattached disks / orphaned IPs, **0 user-managed SA keys** (WIF), no public buckets, executespec VMs use IAP-scoped SSH.
- **Recommend:** close world-open firewall rules `allow-preview-ssh` (`0.0.0.0/0:22`), `default-allow-ssh` (`0.0.0.0/0:22`), `default-allow-rdp` (`0.0.0.0/0:3389`); set a **billing budget + alert** (the Budgets API isn't even enabled — important with ephemeral ~$660/mo envs that must be torn down); confirm `preview-engine-*` `0.0.0.0/0:10000-60000` is intentional.
- **Left as-is per the operator:** dev VM size (can't reduce), dev Cloud SQL (right-sized).
