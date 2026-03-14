# ── Remote state in GCS ──────────────────────────────────────────────────────
# First-time setup:
#   1. Create the bucket:  gsutil mb -l us-central1 gs://executespec-tfstate
#   2. Enable versioning:  gsutil versioning set on gs://executespec-tfstate
#   3. terraform init

terraform {
  backend "gcs" {
    bucket = "executespec-tfstate"
    prefix = "infra"
    # Workspace-based: dev state → infra/dev/, prod state → infra/prod/
  }
}
