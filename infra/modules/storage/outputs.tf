output "deploy_bucket_name" {
  description = "GCS deploy bucket name"
  value       = google_storage_bucket.deploy.name
}

output "deploy_bucket_url" {
  description = "GCS deploy bucket URL"
  value       = google_storage_bucket.deploy.url
}

output "workspace_bucket_name" {
  description = "GCS workspace bucket name"
  value       = google_storage_bucket.workspaces.name
}

output "workspace_bucket_url" {
  description = "GCS workspace bucket URL"
  value       = google_storage_bucket.workspaces.url
}

output "docker_registry_url" {
  description = "Artifact Registry Docker URL"
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.docker.repository_id}"
}
