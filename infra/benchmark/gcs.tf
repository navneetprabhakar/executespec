# Durable eval-results bucket — the sync target for scripts/sync-results.sh.
# Lives in this throwaway module but is destroy-protected by default so a VM
# teardown can't silently delete your results (empty it, or flip the flag, to
# fully tear down).

variable "results_bucket_name" {
  description = "GCS bucket for eval results. Empty = derive from project id."
  type        = string
  default     = ""
}

variable "results_bucket_force_destroy" {
  description = "Allow `terraform destroy` to delete the results bucket even when it holds objects. Default false = protect results."
  type        = bool
  default     = false
}

locals {
  results_bucket = var.results_bucket_name != "" ? var.results_bucket_name : "${var.project_id}-swebench-eval-results"
}

resource "google_storage_bucket" "results" {
  name                        = local.results_bucket
  location                    = var.region
  force_destroy               = var.results_bucket_force_destroy
  uniform_bucket_level_access = true
  labels                      = local.labels

  # Eval artefacts are scratch — auto-expire after 90 days so the bucket can't grow forever.
  lifecycle_rule {
    condition {
      age = 90
    }
    action {
      type = "Delete"
    }
  }
}

output "results_bucket" {
  description = "GCS bucket for eval results (pass as GCS_BUCKET to eval-campaign.sh)"
  value       = "gs://${google_storage_bucket.results.name}"
}
