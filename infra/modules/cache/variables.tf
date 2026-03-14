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

variable "redis_memory_gb" {
  type    = number
  default = 1
}

variable "redis_tier" {
  type    = string
  default = "BASIC"
}

variable "labels" {
  type    = map(string)
  default = {}
}
