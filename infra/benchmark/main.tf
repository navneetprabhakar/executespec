locals {
  labels = {
    purpose    = "swebench-benchmark"
    managed_by = "terraform"
    temporary  = "true"
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

# ── Dedicated throwaway network ──────────────────────────────────────────────
# Self-contained on purpose: the prod infra uses a custom VPC and this project
# may have no "default" network, so we create our own. Auto-mode → a subnet in
# every region (incl. us-east4); `terraform destroy` removes it all.
resource "google_compute_network" "benchmark" {
  name                    = "${var.instance_name}-net"
  auto_create_subnetworks = true
}

# ── Firewall: SSH only, locked to the IAP range (or your override) ───────────
# Scoped by network tag so it only applies to this VM.
resource "google_compute_firewall" "ssh" {
  name      = "${var.instance_name}-allow-ssh"
  network   = google_compute_network.benchmark.id
  direction = "INGRESS"

  allow {
    protocol = "tcp"
    ports    = ["22"]
  }

  source_ranges = var.ssh_source_ranges
  target_tags   = [var.instance_name]
}

# ── The benchmark VM ─────────────────────────────────────────────────────────
resource "google_compute_instance" "benchmark" {
  name         = var.instance_name
  machine_type = var.machine_type
  zone         = var.zone
  tags         = [var.instance_name]
  labels       = local.labels

  # Throwaway by design — no accidental-delete guard.
  deletion_protection       = false
  allow_stopping_for_update = true

  boot_disk {
    initialize_params {
      image = var.boot_image
      size  = var.disk_size_gb
      type  = var.disk_type
    }
  }

  network_interface {
    network = google_compute_network.benchmark.id
    # Ephemeral public IP — needed for EGRESS only (Docker Hub image pulls +
    # LLM API calls). Inbound is locked to tcp/22 from the IAP range above.
    access_config {}
  }

  metadata = {
    enable-oslogin = "TRUE"
  }

  metadata_startup_script = file("${path.module}/startup.sh")

  scheduling {
    provisioning_model          = var.use_spot ? "SPOT" : "STANDARD"
    preemptible                 = var.use_spot
    automatic_restart           = var.use_spot ? false : true
    instance_termination_action = var.use_spot ? "STOP" : null
  }

  service_account {
    # Default compute SA; broad scope is fine for a temp VM (lets it pull from
    # Artifact Registry + write logs). The eval itself only needs egress.
    scopes = ["cloud-platform"]
  }
}
