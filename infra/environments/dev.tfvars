# ── ExecuteSpec — Development Environment ────────────────────────────────────
# Usage: terraform workspace select dev && terraform apply -var-file=environments/dev.tfvars

environment = "dev"

# ── Project ─────────────────────────────────────────────────────────────────
# project_id = "your-gcp-project-id"   # Set via TF_VAR_project_id or -var
region = "us-east4"
zone   = "us-east4-b"

# ── Domain ──────────────────────────────────────────────────────────────────
domain           = "executespec.ai"
subdomain_prefix = "dev"   # → dev.executespec.ai

# ── Compute (smaller for dev) ───────────────────────────────────────────────
machine_type      = "e2-standard-4"   # 4 vCPU, 16 GB RAM (~$97/mo)
boot_disk_size_gb = 50
boot_disk_type    = "pd-balanced"

# ── Database (minimal for dev) ──────────────────────────────────────────────
db_tier         = "db-g1-small"   # 1 vCPU, 1.7 GB RAM (~$25/mo)
db_disk_size_gb = 10
db_ha_enabled   = false            # No HA for dev
db_name         = "executespec"
db_user         = "postgres"

# ── Redis (minimal for dev) ─────────────────────────────────────────────────
redis_memory_gb = 1
redis_tier      = "BASIC"          # No replication (~$36/mo)

# ── Load Balancer ───────────────────────────────────────────────────────────
enable_lb  = true
enable_iap = true

# ── Labels ──────────────────────────────────────────────────────────────────
labels = {
  cost_center = "engineering"
  team        = "platform"
}

# ── Estimated Monthly Cost ──────────────────────────────────────────────────
# Compute (e2-standard-4):    ~$97
# Cloud SQL (db-g1-small):    ~$25
# Redis (1 GB Basic):         ~$36
# Load Balancer + forwarding: ~$18
# Cloud NAT:                  ~$3
# GCS (minimal):              ~$1
# ────────────────────────────────────────────
# Total:                      ~$229/month
