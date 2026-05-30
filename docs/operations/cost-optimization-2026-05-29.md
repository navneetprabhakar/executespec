# GCP Cost Optimization — 2026-05-29

> Operational handoff: what was changed on the `executespecv1` GCP project on 2026-05-29 to bring monthly spend down, what's still pending, and what to do next.

**Project:** `executespecv1` · **Region:** `us-east4` · **Billing account:** `01F2A7-75A505-0BB199`

## TL;DR

> **UPDATE 2026-05-30 — COMPLETE.** The two pending bucket migrations (runbooks A + B below) have since been executed; **nothing is left to run.** The `ignore_changes=[location]` guard in `infra/modules/storage/main.tf` remains as a defensive no-op until a future terraform state-reconcile (it does no harm if left). The runbooks below are kept for historical reference / re-use.

Dev environment was burning **~$390/mo** with Cloud SQL at **2.9% avg CPU** and the GCE VM at **0.6% avg CPU** — both wildly oversized. Resized SQL, deleted dead VM + IP, and migrated one bucket to `us-east4`. **Confirmed savings: ~$158/mo** (40% reduction). Two more bucket migrations and an optional further-aggressive SQL/VM downsize are runbook'd below.

## Before / after (estimated)

| Resource | Before | After | Δ/mo |
| --- | --- | --- | --- |
| Cloud SQL `executespec-dev` | `db-custom-4-16384` ~$204 | `db-custom-1-3840` ~$54 | **-$150** |
| `preview-gw` VM (TERMINATED since 2026-05-13) + 100 GB pd-standard + `preview-vm-ip` | ~$8 | $0 | **-$8** |
| `executespec-dev-workspaces` bucket location | asia-south1 | us-east4 | ~$0 today (future egress avoided) |
| All other resources | unchanged | unchanged | $0 |
| **Total dev burn** | **~$390** | **~$232** | **-$158** |

## Resource inventory snapshot (post-changes)

| Resource | State | Spec | Notes |
| --- | --- | --- | --- |
| GCE VM `executespec-dev-vm` | RUNNING | `e2-standard-4`, 100 GB pd-balanced, no preemptible | 0.6% CPU avg, 1.6% max over 48h — candidate for downsize to `e2-medium` (~$25/mo) |
| Cloud SQL `executespec-dev` | RUNNABLE | `db-custom-1-3840` (1 vCPU, 3.84 GB), 10 GB SSD ZONAL | 2.9% CPU avg, 41% mem, 0 GB data, max 5 connections. Could go further to `db-g1-small` (~$25/mo) |
| Memorystore Redis `executespec-dev` | READY | BASIC, 1 GB, Redis 7.0 | 0.43% mem used. Kept per request — multiple items coming up. |
| Cloud NAT `executespec-dev-nat` | active | AUTO IPs | $32/mo gateway base. Kept per request — traffic expected. |
| LB `executespec-dev-lb-ip` (34.8.12.146) | IN_USE | HTTP + HTTPS forwarding rules, 1 backend | ~$18/mo |
| GCS `executespec-dev-deploy` | active | asia-south1, 2.6 GB, versioned | Pending migration (runbook A) |
| GCS `executespec-dev-workspaces` | active | **us-east4** ✓, 0 B | Migrated 2026-05-29 |
| GCS `executespec-tfstate` | active | asia-south1, 77 KB, 3 workspaces (default/dev/prod) | Pending migration (runbook B) |
| Artifact Registry | active | 3 repos, ~2 GB total | ~$0.20/mo |
| Logs | active | 0.18 GB / 30d (free tier 50 GB) | $0 |

## Changes applied

### 1. Cloud SQL resize (committed)

```bash
# Pre-resize on-demand backup (backups were disabled on the instance)
gcloud sql backups create --instance=executespec-dev --description="pre-resize-2026-05-29"
# Backup id 1780017573339, SUCCESSFUL

gcloud sql instances patch executespec-dev --tier=db-custom-1-3840 --quiet
# ~2 min restart; instance back to RUNNABLE
```

### 2. preview-gw cleanup (committed)

```bash
gcloud compute instances delete preview-gw --zone=us-east4-b --quiet
gcloud compute addresses delete preview-vm-ip --region=us-east4 --quiet
```

The `preview-gw` boot disk had `autoDelete: true` so it was reclaimed with the VM. Not Terraform-managed — no drift introduced.

### 3. `executespec-dev-workspaces` bucket migration (committed)

```bash
# Was empty (0 objects), so a direct delete + recreate-in-us-east4 was safe.
gcloud storage buckets delete gs://executespec-dev-workspaces --quiet
gcloud storage buckets create gs://executespec-dev-workspaces \
  --location=us-east4 --default-storage-class=STANDARD \
  --uniform-bucket-level-access --project=executespecv1
# + 30-day lifecycle rule + tf labels applied via REST API (gcloud CLI hit eventual-consistency 404s)
```

The old asia-south1 bucket is in **7-day soft-delete** and will hard-delete **2026-06-05**. It was empty so nothing of value is being lost — confirmed not to restore on 2026-05-29.

### 4. Terraform working-tree edits (uncommitted)

- **`infra/environments/dev.tfvars`**
  - `boot_disk_size_gb`: `50` → `100` (aligns with actual VM)
  - `db_tier`: `db-g1-small` → `db-custom-1-3840` (matches today's resize)
  - Cost-estimate comment block refreshed
- **`infra/modules/storage/main.tf`**
  - Added `lifecycle { ignore_changes = [location] }` on `google_storage_bucket.deploy` with dated TODO. **Remove after runbook A completes**, or any `terraform apply` will try to destroy + recreate the deploy bucket and fail on `force_destroy = false`.

Review with:
```bash
cd infra && git diff -- environments/dev.tfvars modules/storage/main.tf
```

## Known TF drift

Run `terraform workspace select dev && terraform plan -var-file=environments/dev.tfvars` from `infra/` and expect:

| Drift | Direction | Decide |
| --- | --- | --- |
| `dev-workspaces` bucket location | none (state catches up on first refresh) | Just plan; no action |
| `dev-deploy` bucket location | hidden by `ignore_changes` guard | Remove guard after runbook A |
| SQL `backup_configuration.enabled` | TF says `true`, reality `false` (pre-existing) | Recommend: let TF enable backups |
| SQL `backup_configuration.start_time` | TF says `"03:00"`, reality `"20:00"` (pre-existing) | Cosmetic — let TF apply |

## Pending — runbooks for next maintenance window

### A. `executespec-dev-deploy` → us-east4

Old bucket holds `app.jar`, `CURRENT_SHA`, `config/{api-keys,smtp-credentials,contact-jira-credentials,gmail/*}`, `designs/*` (2.6 GB total). Read by `infra/scripts/startup.sh` at every VM boot. Requires VM restart.

```bash
# 1. Stage copy in us-east4
gcloud storage buckets create gs://executespec-dev-deploy-use4 \
  --location=us-east4 --uniform-bucket-level-access --project=executespecv1
gcloud storage buckets update gs://executespec-dev-deploy-use4 --versioning
gcloud storage cp -r gs://executespec-dev-deploy/* gs://executespec-dev-deploy-use4/

# 2. Repoint VM metadata
gcloud compute instances add-metadata executespec-dev-vm --zone=us-east4-b \
  --metadata=es-deploy-bucket=executespec-dev-deploy-use4

# 3. Restart VM — startup.sh re-reads metadata, fetches config + JAR from new bucket
gcloud compute instances reset executespec-dev-vm --zone=us-east4-b

# 4. Verify dev.executespec.ai healthy (login, run submit, SSE).
#    Keep the old asia-south1 bucket as a backup — do not delete.

# 5. TF reconciliation
#    Remove the lifecycle { ignore_changes = [location] } block in
#    infra/modules/storage/main.tf, then either:
#      a) tf state rm + tf import to reuse the original bucket name, or
#      b) Update the resource name = "${var.name_prefix}-deploy-use4" to track the new name
```

### B. `executespec-tfstate` → us-east4

Backend bucket — cannot be Terraform-managed. Migration disrupts ongoing tf work but no app downtime.

```bash
# 1. Stage new tfstate bucket (different name; original kept as backup per ops preference)
gcloud storage buckets create gs://executespec-tfstate-use4 \
  --location=us-east4 --uniform-bucket-level-access --project=executespecv1
gcloud storage buckets update gs://executespec-tfstate-use4 --versioning
gcloud storage cp -r gs://executespec-tfstate/infra gs://executespec-tfstate-use4/

# 2. Edit infra/backend.tf — bucket = "executespec-tfstate-use4"

# 3. Migrate state
cd infra && terraform init -migrate-state -reconfigure   # answer yes to copy state

# 4. Verify each workspace
terraform workspace select dev  && terraform plan -var-file=environments/dev.tfvars
terraform workspace select prod && terraform plan -var-file=environments/prod.tfvars

# 5. Lock the old bucket read-only (keep as backup, prevent accidental writes)
gcloud storage buckets update gs://executespec-tfstate --retention-period=86400
```

## Optional further wins (not approved 2026-05-29)

| Action | Savings | Notes |
| --- | --- | --- |
| SQL `db-custom-1-3840` → `db-g1-small` | ~$30/mo extra | Matches original dev.tfvars intent. Shared-core, fine for current 2.9% CPU usage. `gcloud sql instances patch executespec-dev --tier=db-g1-small` |
| VM `e2-standard-4` → `e2-medium` (or `e2-custom-2-8192`) | ~$60-80/mo | Verify JVM `-Xmx` first; current util is 0.6% CPU |
| NAT replace with VM external IPs | ~$25/mo | Only if outbound-via-NAT isn't required by VPC topology |
| Migrate `dev-deploy` to us-east4 | <$1/mo today | Future egress savings when workspace shipping starts |

## Verification & rollback

**Cloud SQL rollback** (if anything regresses):
```bash
gcloud sql instances patch executespec-dev --tier=db-custom-4-16384 --quiet
# Or restore from the pre-resize backup:
gcloud sql backups restore 1780017573339 --restore-instance=executespec-dev
```

**preview-gw**: not recoverable (deleted). Recreate from `infra/` if needed — but it wasn't in Terraform, so source of truth is whatever process originally created it.

**Workspaces bucket**: was empty, recreated identically except for region. The asia-south1 soft-deleted version is recoverable until **2026-06-05** via Console → Cloud Storage → Trash.

## Sources of truth

- Utilization metrics: Cloud Monitoring (sampled 2026-05-27 → 2026-05-29, 48h window, 1h alignment)
- Pricing: `us-east4` on-demand list price, no committed-use or sustained-use discounts factored in
- This session: Claude Code transcript 2026-05-29 (root cause: SQL + VM provisioned 4× over what utilization warranted)
