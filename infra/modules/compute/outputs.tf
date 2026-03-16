output "instance_group" {
  description = "Instance group self_link (for load balancer backend)"
  value       = google_compute_instance_group.app.self_link
}

output "instance_name" {
  description = "VM instance name"
  value       = google_compute_instance.app.name
}
