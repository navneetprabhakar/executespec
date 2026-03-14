output "network_id" {
  description = "VPC network self_link"
  value       = google_compute_network.vpc.id
}

output "network_name" {
  description = "VPC network name"
  value       = google_compute_network.vpc.name
}

output "subnet_id" {
  description = "Subnet self_link"
  value       = google_compute_subnetwork.main.id
}

output "subnet_name" {
  description = "Subnet name"
  value       = google_compute_subnetwork.main.name
}

output "private_services_range" {
  description = "Private services IP range name"
  value       = google_compute_global_address.private_services.name
}
