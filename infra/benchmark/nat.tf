# ── Cloud NAT — outbound internet for the internal-only VM ───────────────────
# The org policy constraints/compute.vmExternalIpAccess forbids public IPs on VMs
# in this project, so the benchmark VM has no external IP (see main.tf). It still
# needs egress for Docker Hub pulls, apt, GitHub clones, and LLM API calls — Cloud
# NAT provides that without a public IP. Regional, covers every subnet of the
# auto-mode benchmark network. `terraform destroy` removes both.
resource "google_compute_router" "benchmark" {
  name    = "${var.instance_name}-router"
  region  = var.region
  network = google_compute_network.benchmark.id
}

resource "google_compute_router_nat" "benchmark" {
  name                               = "${var.instance_name}-nat"
  router                             = google_compute_router.benchmark.name
  region                             = var.region
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"

  log_config {
    enable = false
    filter = "ERRORS_ONLY"
  }
}
