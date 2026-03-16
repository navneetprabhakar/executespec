# ── ExecuteSpec — Production Environment ─────────────────────────────────────
# Usage: terraform workspace select prod && terraform apply -var-file=environments/prod.tfvars

environment = "prod"

# ── Project ─────────────────────────────────────────────────────────────────
# project_id = "your-gcp-project-id"   # Set via TF_VAR_project_id or -var
region = "asia-south1"
zone   = "asia-south1-a"

# ── Domain ──────────────────────────────────────────────────────────────────
domain           = "executespec.ai"
subdomain_prefix = "app"   # → app.executespec.ai

# ── Compute (production-grade) ──────────────────────────────────────────────
machine_type      = "n2-standard-4"   # 4 vCPU, 16 GB RAM (~$97/mo)
boot_disk_size_gb = 50
boot_disk_type    = "pd-ssd"

# ── Database (production-grade) ─────────────────────────────────────────────
db_tier         = "db-custom-2-3840"   # 2 vCPU, 3.75 GB RAM (~$80/mo)
db_disk_size_gb = 20
db_ha_enabled   = true                  # Regional HA with automatic failover
db_name         = "executespec"
db_user         = "postgres"

# ── Redis (production-grade) ────────────────────────────────────────────────
redis_memory_gb = 1
redis_tier      = "STANDARD_HA"        # With replication (~$73/mo)

# ── Load Balancer ───────────────────────────────────────────────────────────
enable_lb  = true
enable_iap = true

# ── Labels ──────────────────────────────────────────────────────────────────
labels = {
  cost_center = "engineering"
  team        = "platform"
}

# ── Estimated Monthly Cost ──────────────────────────────────────────────────
# Compute (2× n2-standard-4):  ~$194
# Cloud SQL (db-custom-2-3840): ~$80
# Redis (1 GB Standard HA):     ~$73
# Load Balancer + forwarding:   ~$18
# Cloud NAT:                    ~$3
# GCS (moderate):               ~$5
# ────────────────────────────────────────────
# Total:                        ~$373/month
