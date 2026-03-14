# ── Project ──────────────────────────────────────────────────────────────────

variable "project_id" {
  description = "GCP project ID"
  type        = string
}

variable "region" {
  description = "GCP region"
  type        = string
  default     = "us-central1"
}

variable "zone" {
  description = "GCP zone (within region)"
  type        = string
  default     = "us-central1-a"
}

variable "environment" {
  description = "Environment name (dev or prod)"
  type        = string
  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "Environment must be 'dev' or 'prod'."
  }
}

# ── Domain ──────────────────────────────────────────────────────────────────

variable "domain" {
  description = "Domain for the application (e.g., executespec.ai)"
  type        = string
}

variable "subdomain_prefix" {
  description = "Subdomain prefix (e.g., 'app' → app.executespec.ai, 'dev' → dev.executespec.ai)"
  type        = string
  default     = ""
}

# ── Compute ─────────────────────────────────────────────────────────────────

variable "machine_type" {
  description = "GCE machine type"
  type        = string
  default     = "e2-standard-2"
}

variable "instance_count" {
  description = "Number of GCE instances (MIG target size)"
  type        = number
  default     = 1
}

variable "boot_disk_size_gb" {
  description = "Boot disk size in GB"
  type        = number
  default     = 50
}

variable "boot_disk_type" {
  description = "Boot disk type"
  type        = string
  default     = "pd-balanced"
}

# ── Database ────────────────────────────────────────────────────────────────

variable "db_tier" {
  description = "Cloud SQL machine tier"
  type        = string
  default     = "db-g1-small"
}

variable "db_disk_size_gb" {
  description = "Cloud SQL disk size in GB"
  type        = number
  default     = 10
}

variable "db_ha_enabled" {
  description = "Enable Cloud SQL high availability"
  type        = bool
  default     = false
}

variable "db_name" {
  description = "PostgreSQL database name"
  type        = string
  default     = "executespec"
}

variable "db_user" {
  description = "PostgreSQL user"
  type        = string
  default     = "postgres"
}

# ── Redis ───────────────────────────────────────────────────────────────────

variable "redis_memory_gb" {
  description = "Redis memory size in GB"
  type        = number
  default     = 1
}

variable "redis_tier" {
  description = "Redis tier (BASIC or STANDARD_HA)"
  type        = string
  default     = "BASIC"
}

# ── Load Balancer ───────────────────────────────────────────────────────────

variable "enable_lb" {
  description = "Enable HTTPS load balancer"
  type        = bool
  default     = true
}

variable "enable_iap" {
  description = "Enable IAP for SSH access"
  type        = bool
  default     = true
}

# ── Labels ──────────────────────────────────────────────────────────────────

variable "labels" {
  description = "Labels to apply to all resources"
  type        = map(string)
  default     = {}
}
