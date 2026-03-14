variable "project_id" {
  type = string
}

variable "name_prefix" {
  type = string
}

variable "deploy_bucket_name" {
  description = "GCS deploy bucket name for IAM binding"
  type        = string
}

variable "workspace_bucket_name" {
  description = "GCS workspace bucket name for IAM binding"
  type        = string
}
