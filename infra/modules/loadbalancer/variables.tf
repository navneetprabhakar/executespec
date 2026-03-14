variable "project_id" {
  type = string
}

variable "region" {
  type = string
}

variable "name_prefix" {
  type = string
}

variable "domain" {
  description = "Domain for SSL certificate"
  type        = string
}

variable "instance_group" {
  description = "Instance group self_link for backend"
  type        = string
}
