output "external_ip" {
  description = "Load balancer external IP address"
  value       = google_compute_global_address.lb.address
}

output "ssl_certificate_name" {
  description = "Managed SSL certificate name"
  value       = google_compute_managed_ssl_certificate.app.name
}

output "url_map_name" {
  description = "URL map name"
  value       = google_compute_url_map.app.name
}
