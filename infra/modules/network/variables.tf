variable "project_id" {
  type = string
}

variable "region" {
  type = string
}

variable "name_prefix" {
  type = string
}

variable "enable_iap" {
  type    = bool
  default = true
}

variable "labels" {
  type    = map(string)
  default = {}
}
