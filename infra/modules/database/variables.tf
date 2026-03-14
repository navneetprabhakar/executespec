variable "project_id" {
  type = string
}

variable "region" {
  type = string
}

variable "name_prefix" {
  type = string
}

variable "network_id" {
  description = "VPC network self_link"
  type        = string
}

variable "db_tier" {
  type    = string
  default = "db-g1-small"
}

variable "db_disk_size_gb" {
  type    = number
  default = 10
}

variable "db_ha_enabled" {
  type    = bool
  default = false
}

variable "db_name" {
  type    = string
  default = "executespec"
}

variable "db_user" {
  type    = string
  default = "postgres"
}

variable "labels" {
  type    = map(string)
  default = {}
}
