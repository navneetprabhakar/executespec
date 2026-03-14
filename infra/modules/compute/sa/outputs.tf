output "email" {
  description = "Service account email"
  value       = google_service_account.gce.email
}

output "id" {
  description = "Service account unique ID"
  value       = google_service_account.gce.unique_id
}
