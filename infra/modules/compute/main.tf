# ── GCE Instance Template ────────────────────────────────────────────────────

resource "google_compute_instance_template" "app" {
  name_prefix  = "${var.name_prefix}-"
  project      = var.project_id
  machine_type = var.machine_type
  region       = var.region
  tags         = ["executespec-server"]

  disk {
    source_image = "projects/ubuntu-os-cloud/global/images/family/ubuntu-2204-lts"
    auto_delete  = true
    boot         = true
    disk_size_gb = var.boot_disk_size_gb
    disk_type    = var.boot_disk_type
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
    es-environment     = var.environment
    es-db-host         = var.db_host
    es-db-name         = var.db_name
    es-db-user         = var.db_user
    es-db-password     = var.db_password
    es-redis-host      = var.redis_host
    es-redis-port      = var.redis_port
    es-deploy-bucket   = var.deploy_bucket
    es-workspace-bucket = var.workspace_bucket
  }

  labels = var.labels

  lifecycle {
    create_before_destroy = true
  }
}

# ── Managed Instance Group ──────────────────────────────────────────────────

resource "google_compute_instance_group_manager" "app" {
  name               = "${var.name_prefix}-mig"
  project            = var.project_id
  zone               = var.zone
  base_instance_name = "${var.name_prefix}-vm"
  target_size        = var.instance_count

  version {
    instance_template = google_compute_instance_template.app.id
  }

  named_port {
    name = "http"
    port = 8080
  }

  auto_healing_policies {
    health_check      = google_compute_health_check.app.id
    initial_delay_sec = 300  # 5 min for initial startup
  }

  update_policy {
    type                  = "PROACTIVE"
    minimal_action        = "REPLACE"
    max_surge_fixed       = 1
    max_unavailable_fixed = 0
  }
}

# ── Health Check ────────────────────────────────────────────────────────────

resource "google_compute_health_check" "app" {
  name                = "${var.name_prefix}-health"
  project             = var.project_id
  check_interval_sec  = 30
  timeout_sec         = 10
  healthy_threshold   = 2
  unhealthy_threshold = 3

  http_health_check {
    port         = 8080
    request_path = "/api/health"
  }
}
