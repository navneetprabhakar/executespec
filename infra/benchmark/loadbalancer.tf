# ── Public HTTP(S) Load Balancer ─────────────────────────────────────────────
# Cloudflare points benchmark.executespec.ai at this global static IP. The VM
# stays private; GFE/LB health checks reach the app on port 8080.

locals {
  benchmark_domain = "benchmark.executespec.ai"
}

resource "google_compute_global_address" "lb" {
  name    = "${var.instance_name}-lb-ip"
  project = var.project_id
  labels  = local.labels
}

resource "google_compute_firewall" "lb_to_backend" {
  name      = "${var.instance_name}-allow-lb-backend"
  network   = google_compute_network.benchmark.id
  direction = "INGRESS"

  allow {
    protocol = "tcp"
    ports    = ["8080"]
  }

  source_ranges = [
    "130.211.0.0/22",
    "35.191.0.0/16",
  ]
  target_tags = [var.instance_name]
}

resource "google_compute_instance_group" "benchmark" {
  name    = "${var.instance_name}-group"
  project = var.project_id
  zone    = var.zone

  instances = [google_compute_instance.benchmark.id]

  named_port {
    name = "http"
    port = 8080
  }
}

resource "google_compute_health_check" "lb" {
  name                = "${var.instance_name}-lb-health"
  project             = var.project_id
  check_interval_sec  = 15
  timeout_sec         = 10
  healthy_threshold   = 2
  unhealthy_threshold = 3

  http_health_check {
    port         = 8080
    request_path = "/actuator/health"
  }
}

resource "google_compute_backend_service" "app" {
  name                  = "${var.instance_name}-backend"
  project               = var.project_id
  protocol              = "HTTP"
  port_name             = "http"
  timeout_sec           = 300
  load_balancing_scheme = "EXTERNAL_MANAGED"
  health_checks         = [google_compute_health_check.lb.id]
  session_affinity      = "CLIENT_IP"

  backend {
    group           = google_compute_instance_group.benchmark.id
    balancing_mode  = "UTILIZATION"
    max_utilization = 0.8
  }

  log_config {
    enable      = true
    sample_rate = 1.0
  }
}

resource "google_compute_url_map" "app" {
  name            = "${var.instance_name}-urlmap"
  project         = var.project_id
  default_service = google_compute_backend_service.app.id
}

resource "google_compute_managed_ssl_certificate" "app" {
  name    = "${var.instance_name}-ssl"
  project = var.project_id

  managed {
    domains = [local.benchmark_domain]
  }
}

resource "google_compute_target_https_proxy" "app" {
  name             = "${var.instance_name}-https-proxy"
  project          = var.project_id
  url_map          = google_compute_url_map.app.id
  ssl_certificates = [google_compute_managed_ssl_certificate.app.id]
}

resource "google_compute_url_map" "http_redirect" {
  name    = "${var.instance_name}-http-redirect"
  project = var.project_id

  default_url_redirect {
    https_redirect         = true
    redirect_response_code = "MOVED_PERMANENTLY_DEFAULT"
    strip_query            = false
  }
}

resource "google_compute_target_http_proxy" "http_redirect" {
  name    = "${var.instance_name}-http-proxy"
  project = var.project_id
  url_map = google_compute_url_map.http_redirect.id
}

resource "google_compute_global_forwarding_rule" "https" {
  name                  = "${var.instance_name}-https-rule"
  project               = var.project_id
  ip_address            = google_compute_global_address.lb.address
  port_range            = "443"
  target                = google_compute_target_https_proxy.app.id
  load_balancing_scheme = "EXTERNAL_MANAGED"
}

resource "google_compute_global_forwarding_rule" "http" {
  name                  = "${var.instance_name}-http-rule"
  project               = var.project_id
  ip_address            = google_compute_global_address.lb.address
  port_range            = "80"
  target                = google_compute_target_http_proxy.http_redirect.id
  load_balancing_scheme = "EXTERNAL_MANAGED"
}
