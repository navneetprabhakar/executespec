# Benchmark VM — STANDALONE root module with LOCAL state.
#
# Intentionally NOT wired to the GCS backend that ../ (prod/dev infra) uses, and
# NOT sharing its state. This VM is a throwaway you spin up for a full SWE-bench
# run and `terraform destroy` afterwards — keeping it isolated means a destroy
# here can never touch prod/dev infrastructure.
terraform {
  required_version = ">= 1.5"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 5.0"
    }
  }
  # Local state on purpose (see above). State file: ./terraform.tfstate
}
