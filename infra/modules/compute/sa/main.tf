# ── Service Account for GCE instances ────────────────────────────────────────

resource "google_service_account" "gce" {
  account_id   = "${var.name_prefix}-gce"
  display_name = "ExecuteSpec GCE Service Account (${var.name_prefix})"
  project      = var.project_id
}

# ── IAM Roles ───────────────────────────────────────────────────────────────

# Access Cloud SQL
resource "google_project_iam_member" "cloudsql_client" {
  project = var.project_id
  role    = "roles/cloudsql.client"
  member  = "serviceAccount:${google_service_account.gce.email}"
}

# Access Secret Manager (for DB password, API keys)
resource "google_project_iam_member" "secret_accessor" {
  project = var.project_id
  role    = "roles/secretmanager.secretAccessor"
  member  = "serviceAccount:${google_service_account.gce.email}"
}

# Write logs to Cloud Logging
resource "google_project_iam_member" "log_writer" {
  project = var.project_id
  role    = "roles/logging.logWriter"
  member  = "serviceAccount:${google_service_account.gce.email}"
}

# Write metrics to Cloud Monitoring
resource "google_project_iam_member" "metric_writer" {
  project = var.project_id
  role    = "roles/monitoring.metricWriter"
  member  = "serviceAccount:${google_service_account.gce.email}"
}

# Push/pull Docker images from Artifact Registry
resource "google_project_iam_member" "artifact_registry" {
  project = var.project_id
  role    = "roles/artifactregistry.writer"
  member  = "serviceAccount:${google_service_account.gce.email}"
}

# SSH into GCE instances via IAP (CI/CD deploy step)
resource "google_project_iam_member" "compute_instance_admin" {
  project = var.project_id
  role    = "roles/compute.instanceAdmin.v1"
  member  = "serviceAccount:${google_service_account.gce.email}"
}

# Use IAP tunnels for SSH
resource "google_project_iam_member" "iap_tunnel_user" {
  project = var.project_id
  role    = "roles/iap.tunnelResourceAccessor"
  member  = "serviceAccount:${google_service_account.gce.email}"
}

# Act as service account (required to SSH into instances running with this SA)
resource "google_project_iam_member" "service_account_user" {
  project = var.project_id
  role    = "roles/iam.serviceAccountUser"
  member  = "serviceAccount:${google_service_account.gce.email}"
}

# Access deploy bucket (read JARs on GCE + write JARs from CI/CD via WIF)
resource "google_storage_bucket_iam_member" "deploy_rw" {
  bucket = var.deploy_bucket_name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.gce.email}"
}

# Access workspace bucket (read/write archives)
resource "google_storage_bucket_iam_member" "workspace_rw" {
  bucket = var.workspace_bucket_name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.gce.email}"
}
