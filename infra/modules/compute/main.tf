# ── GCE Instance (standalone — no MIG) ───────────────────────────────────────

resource "google_compute_instance" "app" {
  name         = "${var.name_prefix}-vm"
  project      = var.project_id
  zone         = var.zone
  machine_type = var.machine_type
  tags         = ["executespec-server"]

  # Allow metadata/config changes without destroying the VM
  allow_stopping_for_update = true
  desired_status            = "RUNNING"

  boot_disk {
    initialize_params {
      image = "projects/ubuntu-os-cloud/global/images/family/ubuntu-2204-lts"
      size  = var.boot_disk_size_gb
      type  = var.boot_disk_type
    }
  }

  network_interface {
    network    = var.network_id
    subnetwork = var.subnet_id
    # No external IP — outbound via Cloud NAT, inbound via LB
  }

  service_account {
    email  = var.service_account
    scopes = ["cloud-platform"]
  }

  # Startup script + env vars passed as metadata
  metadata = {
    startup-script = file("${path.module}/../../scripts/startup.sh")

    # Application configuration injected into systemd environment file
    es-environment      = var.environment
    es-db-host          = var.db_host
    es-db-name          = var.db_name
    es-db-user          = var.db_user
    es-db-password      = var.db_password
    es-redis-host       = var.redis_host
    es-redis-port       = var.redis_port
    es-deploy-bucket    = var.deploy_bucket
    es-workspace-bucket = var.workspace_bucket
    es-ar-region        = var.region
    es-ar-repo          = "${var.name_prefix}-docker"
    es-ar-host          = "${var.region}-docker.pkg.dev"
  }

  labels = var.labels
}

# ── Unmanaged Instance Group (for LB backend) ────────────────────────────────
# The LB backend service needs an instance group to route traffic.
# An unmanaged group with our single VM preserves the existing LB wiring.

resource "google_compute_instance_group" "app" {
  name    = "${var.name_prefix}-group"
  project = var.project_id
  zone    = var.zone

  instances = [google_compute_instance.app.id]

  named_port {
    name = "http"
    port = 8080
  }
}
