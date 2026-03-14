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

# Access deploy bucket (read JARs)
resource "google_storage_bucket_iam_member" "deploy_read" {
  bucket = var.deploy_bucket_name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.gce.email}"
}

# Access workspace bucket (read/write archives)
resource "google_storage_bucket_iam_member" "workspace_rw" {
  bucket = var.workspace_bucket_name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.gce.email}"
}
