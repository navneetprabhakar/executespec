variable "project_id" {
  type = string
}

variable "region" {
  type = string
}

variable "zone" {
  type = string
}

variable "name_prefix" {
  type = string
}

variable "environment" {
  type = string
}

variable "machine_type" {
  type    = string
  default = "e2-standard-2"
}

variable "boot_disk_size_gb" {
  type    = number
  default = 50
}

variable "boot_disk_type" {
  type    = string
  default = "pd-balanced"
}

variable "network_id" {
  type = string
}

variable "subnet_id" {
  type = string
}

variable "service_account" {
  description = "Service account email for GCE instances"
  type        = string
}

# ── App configuration (passed to startup script via metadata) ───────────────

variable "db_host" {
  type = string
}

variable "db_name" {
  type = string
}

variable "db_user" {
  type = string
}

variable "db_password" {
  type      = string
  sensitive = true
}

variable "redis_host" {
  type = string
}

variable "redis_port" {
  type = number
}

variable "deploy_bucket" {
  type = string
}

variable "workspace_bucket" {
  type = string
}

variable "labels" {
  type    = map(string)
  default = {}
}
