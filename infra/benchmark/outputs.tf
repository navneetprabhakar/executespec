output "instance_name" {
  description = "Name of the benchmark VM"
  value       = google_compute_instance.benchmark.name
}

output "zone" {
  value = google_compute_instance.benchmark.zone
}

output "internal_ip" {
  description = "Internal IP (no public IP — egress via Cloud NAT, inbound via IAP-SSH)"
  value       = google_compute_instance.benchmark.network_interface[0].network_ip
}

output "ssh_command" {
  description = "SSH in over the IAP tunnel (no public SSH exposure)"
  value       = "gcloud compute ssh ${google_compute_instance.benchmark.name} --zone ${google_compute_instance.benchmark.zone} --project ${var.project_id} --tunnel-through-iap"
}

output "bootstrap_check" {
  description = "Run on the VM to confirm the install finished"
  value       = "tail -f /var/log/benchmark-bootstrap.log   # ready when /var/log/benchmark-ready exists"
}

output "teardown_command" {
  description = "Destroy the VM + firewall to stop all billing"
  value       = "terraform destroy"
}
