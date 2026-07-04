output "instance_name" {
  description = "Name of the benchmark VM"
  value       = google_compute_instance.benchmark.name
}

output "zone" {
  value = google_compute_instance.benchmark.zone
}

output "external_ip" {
  description = "Global load balancer IP for benchmark.executespec.ai"
  value       = google_compute_global_address.lb.address
}

output "load_balancer_ip" {
  description = "Cloudflare A-record target for benchmark.executespec.ai"
  value       = google_compute_global_address.lb.address
}

output "ssl_certificate_name" {
  description = "Managed SSL certificate name"
  value       = google_compute_managed_ssl_certificate.app.name
}

output "ssl_certificate_domain" {
  description = "Managed SSL certificate domain"
  value       = local.benchmark_domain
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
