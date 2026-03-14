# ── GCS Bucket: JAR Releases ─────────────────────────────────────────────────

resource "google_storage_bucket" "deploy" {
  name          = "${var.name_prefix}-deploy"
  project       = var.project_id
  location      = var.region
  force_destroy = false
  storage_class = "STANDARD"

  uniform_bucket_level_access = true

  versioning {
    enabled = true
  }

  lifecycle_rule {
    action {
      type = "Delete"
    }
    condition {
      num_newer_versions = 20  # Keep last 20 versions
    }
  }

  labels = var.labels
}

# ── GCS Bucket: Workspace Archives ──────────────────────────────────────────

resource "google_storage_bucket" "workspaces" {
  name          = "${var.name_prefix}-workspaces"
  project       = var.project_id
  location      = var.region
  force_destroy = false
  storage_class = "STANDARD"

  uniform_bucket_level_access = true

  lifecycle_rule {
    action {
      type = "Delete"
    }
    condition {
      age = 30  # Delete workspace archives older than 30 days
    }
  }

  labels = var.labels
}

# ── Artifact Registry (Docker images for Cloud Run) ─────────────────────────

resource "google_artifact_registry_repository" "docker" {
  location      = var.region
  project       = var.project_id
  repository_id = "${var.name_prefix}-docker"
  description   = "Docker images for ExecuteSpec deployments"
  format        = "DOCKER"

  cleanup_policies {
    id     = "keep-recent"
    action = "KEEP"
    most_recent_versions {
      keep_count = 10
    }
  }

  labels = var.labels
}
