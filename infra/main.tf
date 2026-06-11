# ── ExecuteSpec Infrastructure ───────────────────────────────────────────────
# Usage:
#   terraform workspace new dev   (first time only)
#   terraform workspace new prod  (first time only)
#
#   terraform workspace select dev
#   terraform apply -var-file=environments/dev.tfvars
#
#   terraform workspace select prod
#   terraform apply -var-file=environments/prod.tfvars

locals {
  # Naming convention: executespec-{env}-{resource}
  name_prefix = "executespec-${var.environment}"

  # Merge default labels with user-provided labels
  common_labels = merge({
    project     = "executespec"
    environment = var.environment
    managed_by  = "terraform"
  }, var.labels)

  # Full domain for the environment
  fqdn = var.subdomain_prefix != "" ? "${var.subdomain_prefix}.${var.domain}" : var.domain
}

# ── Provider ─────────────────────────────────────────────────────────────────

provider "google" {
  project = var.project_id
  region  = var.region
}

provider "google-beta" {
  project = var.project_id
  region  = var.region
}

# ── Enable required APIs ─────────────────────────────────────────────────────

resource "google_project_service" "apis" {
  for_each = toset([
    "compute.googleapis.com",
    "sqladmin.googleapis.com",
    "redis.googleapis.com",
    "storage.googleapis.com",
    "artifactregistry.googleapis.com",
    "iap.googleapis.com",
    "secretmanager.googleapis.com",
    "servicenetworking.googleapis.com",
    "cloudtrace.googleapis.com",
    "monitoring.googleapis.com",
  ])

  project            = var.project_id
  service            = each.value
  disable_on_destroy = false
}

# ── Network ──────────────────────────────────────────────────────────────────

module "network" {
  source = "./modules/network"

  project_id  = var.project_id
  region      = var.region
  name_prefix = local.name_prefix
  enable_iap  = var.enable_iap
  labels      = local.common_labels

  depends_on = [google_project_service.apis]
}

# ── Database (Cloud SQL PostgreSQL) ──────────────────────────────────────────

module "database" {
  source = "./modules/database"

  project_id        = var.project_id
  region            = var.region
  name_prefix       = local.name_prefix
  network_id        = module.network.network_id
  db_tier           = var.db_tier
  db_disk_size_gb   = var.db_disk_size_gb
  db_ha_enabled     = var.db_ha_enabled
  db_name           = var.db_name
  db_user           = var.db_user
  labels            = local.common_labels

  depends_on = [module.network, google_project_service.apis]
}

# ── Cache (Memorystore Redis) ───────────────────────────────────────────────

module "cache" {
  source = "./modules/cache"

  project_id     = var.project_id
  region         = var.region
  name_prefix    = local.name_prefix
  network_id     = module.network.network_id
  redis_memory_gb = var.redis_memory_gb
  redis_tier     = var.redis_tier
  labels         = local.common_labels

  depends_on = [module.network, google_project_service.apis]
}

# ── Storage (GCS buckets) ───────────────────────────────────────────────────

module "storage" {
  source = "./modules/storage"

  project_id  = var.project_id
  region      = var.region
  name_prefix = local.name_prefix
  labels      = local.common_labels

  depends_on = [google_project_service.apis]
}

# ── Compute (GCE instances) ─────────────────────────────────────────────────

module "compute" {
  source = "./modules/compute"

  project_id        = var.project_id
  region            = var.region
  zone              = var.zone
  name_prefix       = local.name_prefix
  environment       = var.environment
  machine_type      = var.machine_type
  boot_disk_size_gb = var.boot_disk_size_gb
  boot_disk_type    = var.boot_disk_type
  network_id        = module.network.network_id
  subnet_id         = module.network.subnet_id
  service_account   = module.compute_sa.email

  # Passed to startup script as metadata
  db_host           = module.database.private_ip
  db_name           = var.db_name
  db_user           = var.db_user
  db_password       = module.database.password
  redis_host        = module.cache.host
  redis_port        = module.cache.port
  deploy_bucket     = module.storage.deploy_bucket_name
  workspace_bucket  = module.storage.workspace_bucket_name

  labels = local.common_labels

  depends_on = [module.network, module.database, module.cache, module.storage]
}

# ── Service Account for GCE ─────────────────────────────────────────────────

module "compute_sa" {
  source = "./modules/compute/sa"

  project_id  = var.project_id
  name_prefix = local.name_prefix
  deploy_bucket_name    = module.storage.deploy_bucket_name
  workspace_bucket_name = module.storage.workspace_bucket_name

  depends_on = [google_project_service.apis]
}

# ── Load Balancer ───────────────────────────────────────────────────────────

module "loadbalancer" {
  count  = var.enable_lb ? 1 : 0
  source = "./modules/loadbalancer"

  project_id     = var.project_id
  region         = var.region
  name_prefix    = local.name_prefix
  domain         = local.fqdn
  instance_group = module.compute.instance_group

  depends_on = [module.compute, google_project_service.apis]
}

# ── Deploy-SA access to the deploy bucket ────────────────────────────────────
# Build & Deploy (GitHub Actions) impersonates a single WIF SA
# (var.deploy_service_account = executespec-dev-gce) to upload the JAR to EVERY env's deploy
# bucket. The compute_sa module only grants the env's OWN compute SA (which equals the deploy SA
# for dev only), so non-dev deploy buckets (benchmark/prod) must grant the shared deploy SA
# explicitly — otherwise `gsutil cp` 403s and the deploy fails at "Upload JAR to GCS". Idempotent
# with the dev env (redundant grant there).
resource "google_storage_bucket_iam_member" "deploy_sa_deploy_bucket" {
  bucket = module.storage.deploy_bucket_name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${var.deploy_service_account}"
}
