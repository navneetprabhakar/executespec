# ── ExecuteSpec Application Secrets ─────────────────────────────────────────
#
# Creates empty Secret Manager secrets for the application.
# Values are populated manually via gcloud CLI or the Admin API.
#
# Usage:
#   gcloud secrets versions add executespec-anthropic-api-key \
#     --data-file=- <<< "sk-ant-..."
#
# The GCE service account already has roles/secretmanager.secretAccessor
# (granted in modules/compute/sa/main.tf).

variable "project_id" {
  type = string
}

variable "name_prefix" {
  type    = string
  default = "executespec"
}

variable "environment" {
  type    = string
  default = "dev"
}

variable "labels" {
  type    = map(string)
  default = {}
}

locals {
  # All secrets managed by the app — label-filtered by SecretManagerConfigLoader
  secrets = [
    "anthropic-api-key",
    "openai-api-key",
    "google-api-key",
    "github-pat",
    "jwt-secret",
    "razorpay-key-id",
    "razorpay-key-secret",
    "razorpay-webhook-secret",
    "smtp-username",
    "smtp-password",
    "slack-client-secret",
    "slack-signing-secret",
    "jira-api-token",
    "confluence-api-token",
  ]

  merged_labels = merge(var.labels, {
    app = "executespec"
    env = var.environment
  })
}

resource "google_secret_manager_secret" "app_secrets" {
  for_each  = toset(local.secrets)
  project   = var.project_id
  secret_id = "${var.name_prefix}-${each.key}"

  replication {
    auto {}
  }

  labels = local.merged_labels
}

output "secret_ids" {
  value = { for k, v in google_secret_manager_secret.app_secrets : k => v.secret_id }
}
