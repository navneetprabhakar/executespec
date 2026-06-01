variable "project_id" {
  description = "GCP project ID"
  type        = string
  default     = "executespecv1"
}

variable "region" {
  description = "GCP region"
  type        = string
  default     = "us-east4"
}

variable "zone" {
  description = "GCP zone (within region)"
  type        = string
  default     = "us-east4-a"
}

variable "instance_name" {
  description = "Name of the benchmark VM"
  type        = string
  default     = "benchmark-instance"
}

variable "machine_type" {
  description = "GCE machine type. e2-standard-16 = 16 vCPU / 64 GB — fits ~10-15 parallel SWE-bench containers. Bump to e2-standard-32 for more parallelism."
  type        = string
  default     = "e2-standard-16"
}

variable "disk_size_gb" {
  description = "Boot disk size in GB. Full 300 SWE-bench Lite images ≈ 600 GB; 1000 leaves headroom for OS + backend + Postgres + scratch."
  type        = number
  default     = 1000
}

variable "disk_type" {
  description = "Boot disk type. pd-ssd is worth it — SWE-bench is image-build / I/O heavy. pd-balanced saves a few $ if you don't mind slower builds."
  type        = string
  default     = "pd-ssd"
}

variable "boot_image" {
  description = "Boot image. Ubuntu 22.04 LTS = clean Docker + no PEP-668 pip friction."
  type        = string
  default     = "ubuntu-os-cloud/ubuntu-2204-lts"
}

variable "ssh_source_ranges" {
  description = "CIDRs allowed to reach tcp/22. Default is the GCP IAP range — SSH via `gcloud compute ssh --tunnel-through-iap` with no public exposure. Add your office/home IP/32 here for direct SSH."
  type        = list(string)
  default     = ["35.235.240.0/20"]
}

variable "use_spot" {
  description = "Run as a Spot VM (~60-90% cheaper, but GCP can preempt it). Leave false for a long unattended full-300 run; flip true only if your runner checkpoints/resumes."
  type        = bool
  default     = false
}
