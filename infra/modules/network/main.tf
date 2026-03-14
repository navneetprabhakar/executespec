# ── VPC Network ──────────────────────────────────────────────────────────────

resource "google_compute_network" "vpc" {
  name                    = "${var.name_prefix}-vpc"
  project                 = var.project_id
  auto_create_subnetworks = false
  routing_mode            = "REGIONAL"
}

# ── Subnet ──────────────────────────────────────────────────────────────────

resource "google_compute_subnetwork" "main" {
  name                     = "${var.name_prefix}-subnet"
  project                  = var.project_id
  region                   = var.region
  network                  = google_compute_network.vpc.id
  ip_cidr_range            = "10.0.0.0/24"
  private_ip_google_access = true
}

# ── Private Services Access (for Cloud SQL & Redis) ─────────────────────────

resource "google_compute_global_address" "private_services" {
  name          = "${var.name_prefix}-private-ip"
  project       = var.project_id
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 16
  network       = google_compute_network.vpc.id
}

resource "google_service_networking_connection" "private_vpc" {
  network                 = google_compute_network.vpc.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.private_services.name]
}

# ── Cloud Router + NAT (outbound internet for LLM API calls) ───────────────

resource "google_compute_router" "router" {
  name    = "${var.name_prefix}-router"
  project = var.project_id
  region  = var.region
  network = google_compute_network.vpc.id
}

resource "google_compute_router_nat" "nat" {
  name                               = "${var.name_prefix}-nat"
  project                            = var.project_id
  region                             = var.region
  router                             = google_compute_router.router.name
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"

  log_config {
    enable = true
    filter = "ERRORS_ONLY"
  }
}

# ── Firewall Rules ──────────────────────────────────────────────────────────

# Allow health check probes from Google's health check ranges
resource "google_compute_firewall" "health_check" {
  name    = "${var.name_prefix}-allow-health-check"
  project = var.project_id
  network = google_compute_network.vpc.id

  allow {
    protocol = "tcp"
    ports    = ["8080"]
  }

  source_ranges = [
    "130.211.0.0/22",  # Google health check
    "35.191.0.0/16",   # Google health check
  ]

  target_tags = ["executespec-server"]
}

# Allow HTTPS from load balancer
resource "google_compute_firewall" "lb_to_backend" {
  name    = "${var.name_prefix}-allow-lb"
  project = var.project_id
  network = google_compute_network.vpc.id

  allow {
    protocol = "tcp"
    ports    = ["8080"]
  }

  # Google Cloud Load Balancer source ranges
  source_ranges = [
    "130.211.0.0/22",
    "35.191.0.0/16",
  ]

  target_tags = ["executespec-server"]
}

# Allow SSH via IAP
resource "google_compute_firewall" "iap_ssh" {
  count   = var.enable_iap ? 1 : 0
  name    = "${var.name_prefix}-allow-iap-ssh"
  project = var.project_id
  network = google_compute_network.vpc.id

  allow {
    protocol = "tcp"
    ports    = ["22"]
  }

  # IAP's IP range
  source_ranges = ["35.235.240.0/20"]

  target_tags = ["executespec-server"]
}

# Allow internal communication within the subnet
resource "google_compute_firewall" "internal" {
  name    = "${var.name_prefix}-allow-internal"
  project = var.project_id
  network = google_compute_network.vpc.id

  allow {
    protocol = "tcp"
    ports    = ["0-65535"]
  }

  allow {
    protocol = "udp"
    ports    = ["0-65535"]
  }

  allow {
    protocol = "icmp"
  }

  source_ranges = ["10.0.0.0/24"]
}

# Allow preview ports (10000-10100) from load balancer for preview access
resource "google_compute_firewall" "preview_ports" {
  name    = "${var.name_prefix}-allow-preview"
  project = var.project_id
  network = google_compute_network.vpc.id

  allow {
    protocol = "tcp"
    ports    = ["10000-10100"]
  }

  source_ranges = [
    "130.211.0.0/22",
    "35.191.0.0/16",
  ]

  target_tags = ["executespec-server"]
}
