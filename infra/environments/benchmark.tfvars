# ── ExecuteSpec — Benchmark Environment (EPHEMERAL) ──────────────────────────
# A throwaway full stack for a benchmarking activity, then DESTROYED to free
# resources. Tracks the `benchmark` branch (cut from `main` = prod-grade code).
# Sized for throughput (docker cleanrooms + parallel eval), not cost.
#
# Stand up:  terraform workspace select benchmark && terraform apply -var-file=environments/benchmark.tfvars
# Tear down: terraform destroy -var-file=environments/benchmark.tfvars
# project_id via TF_VAR_project_id=executespecv1 (never committed).

environment = "benchmark"

# ── Project ─────────────────────────────────────────────────────────────────
region = "us-east4"
zone   = "us-east4-b"

# ── Domain ──────────────────────────────────────────────────────────────────
domain           = "executespec.ai"
subdomain_prefix = "benchmark"   # → benchmark.executespec.ai

# ── Compute (beefy — docker cleanrooms + parallel benchmark suites) ──────────
machine_type      = "e2-standard-16"   # 16 vCPU, 64 GB RAM
boot_disk_size_gb = 500                 # SWE-bench images are heavy; bump to 1000 for the full Lite image set
boot_disk_type    = "pd-ssd"            # image-build / I/O heavy

# ── Database (Cloud SQL — mirrors dev/prod; eval data is light) ──────────────
db_tier         = "db-custom-2-7680"   # 2 vCPU, 7.5 GB RAM
db_disk_size_gb = 20
db_ha_enabled   = false                # ephemeral — no HA
db_name         = "executespec"
db_user         = "postgres"

# ── Redis (Memorystore) ─────────────────────────────────────────────────────
redis_memory_gb = 1
redis_tier      = "BASIC"

# ── Load Balancer + IAP ─────────────────────────────────────────────────────
enable_lb  = true     # public HTTPS at benchmark.executespec.ai (the UI)
enable_iap = true     # IAP SSH so deploy.yml can reach the VM

# ── Labels ──────────────────────────────────────────────────────────────────
labels = {
  cost_center = "engineering"
  team        = "platform"
  lifecycle   = "ephemeral"   # destroyed after each benchmarking activity
}
