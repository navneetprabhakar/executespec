# ── Outputs ──────────────────────────────────────────────────────────────────

output "environment" {
  description = "Current environment"
  value       = var.environment
}

# ── Network ─────────────────────────────────────────────────────────────────

output "network_name" {
  description = "VPC network name"
  value       = module.network.network_name
}

output "subnet_name" {
  description = "Subnet name"
  value       = module.network.subnet_name
}

# ── Database ────────────────────────────────────────────────────────────────

output "db_connection_name" {
  description = "Cloud SQL connection name (project:region:instance)"
  value       = module.database.connection_name
}

output "db_private_ip" {
  description = "Cloud SQL private IP"
  value       = module.database.private_ip
}

output "db_password" {
  description = "Cloud SQL password (sensitive)"
  value       = module.database.password
  sensitive   = true
}

output "jdbc_url" {
  description = "JDBC connection URL for application.yml"
  value       = "jdbc:postgresql://${module.database.private_ip}:5432/${var.db_name}"
}

# ── Redis ───────────────────────────────────────────────────────────────────

output "redis_host" {
  description = "Redis host"
  value       = module.cache.host
}

output "redis_port" {
  description = "Redis port"
  value       = module.cache.port
}

# ── Compute ─────────────────────────────────────────────────────────────────

output "instance_name" {
  description = "GCE instance name"
  value       = module.compute.instance_name
}

output "instance_zone" {
  description = "GCE instance zone"
  value       = var.zone
}

output "service_account_email" {
  description = "GCE service account email"
  value       = module.compute_sa.email
}

# ── Storage ─────────────────────────────────────────────────────────────────

output "deploy_bucket" {
  description = "GCS deploy bucket name"
  value       = module.storage.deploy_bucket_name
}

output "workspace_bucket" {
  description = "GCS workspace bucket name"
  value       = module.storage.workspace_bucket_name
}

# ── Load Balancer ───────────────────────────────────────────────────────────

output "lb_ip" {
  description = "Load balancer external IP"
  value       = var.enable_lb ? module.loadbalancer[0].external_ip : null
}

output "app_url" {
  description = "Application URL"
  value       = var.enable_lb ? "https://${local.fqdn}" : "http://${module.compute.instance_name}:8080"
}
