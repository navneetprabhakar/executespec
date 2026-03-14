output "instance_group" {
  description = "Instance group self_link (for load balancer backend)"
  value       = google_compute_instance_group_manager.app.instance_group
}

output "instance_name" {
  description = "Base instance name"
  value       = google_compute_instance_group_manager.app.base_instance_name
}

output "template_id" {
  description = "Instance template ID"
  value       = google_compute_instance_template.app.id
}
