# ── External IP ──────────────────────────────────────────────────────────────

resource "google_compute_global_address" "lb" {
  name    = "${var.name_prefix}-lb-ip"
  project = var.project_id
}

# ── Managed SSL Certificate ─────────────────────────────────────────────────

resource "google_compute_managed_ssl_certificate" "app" {
  name    = "${var.name_prefix}-ssl"
  project = var.project_id

  managed {
    domains = [var.domain]
  }
}

# ── Backend Service ─────────────────────────────────────────────────────────

resource "google_compute_backend_service" "app" {
  name                  = "${var.name_prefix}-backend"
  project               = var.project_id
  protocol              = "HTTP"
  port_name             = "http"
  timeout_sec           = 300
  load_balancing_scheme = "EXTERNAL_MANAGED"
  health_checks         = [google_compute_health_check.lb.id]

  # Session affinity for WebSocket/SSE connections
  session_affinity = "CLIENT_IP"

  backend {
    group           = var.instance_group
    balancing_mode  = "UTILIZATION"
    max_utilization = 0.8
  }

  log_config {
    enable      = true
    sample_rate = 1.0
  }
}

# ── Health Check (for LB) ──────────────────────────────────────────────────

resource "google_compute_health_check" "lb" {
  name                = "${var.name_prefix}-lb-health"
  project             = var.project_id
  check_interval_sec  = 15
  timeout_sec         = 5
  healthy_threshold   = 2
  unhealthy_threshold = 3

  http_health_check {
    port         = 8080
    request_path = "/api/health"
  }
}

# ── URL Map ─────────────────────────────────────────────────────────────────

resource "google_compute_url_map" "app" {
  name            = "${var.name_prefix}-urlmap"
  project         = var.project_id
  default_service = google_compute_backend_service.app.id
}

# ── HTTPS Proxy ─────────────────────────────────────────────────────────────

resource "google_compute_target_https_proxy" "app" {
  name             = "${var.name_prefix}-https-proxy"
  project          = var.project_id
  url_map          = google_compute_url_map.app.id
  ssl_certificates = [google_compute_managed_ssl_certificate.app.id]
}

# ── HTTP → HTTPS Redirect ──────────────────────────────────────────────────

resource "google_compute_url_map" "http_redirect" {
  name    = "${var.name_prefix}-http-redirect"
  project = var.project_id

  default_url_redirect {
    https_redirect         = true
    redirect_response_code = "MOVED_PERMANENTLY_DEFAULT"
    strip_query            = false
  }
}

resource "google_compute_target_http_proxy" "http_redirect" {
  name    = "${var.name_prefix}-http-proxy"
  project = var.project_id
  url_map = google_compute_url_map.http_redirect.id
}

# ── Forwarding Rules ───────────────────────────────────────────────────────

resource "google_compute_global_forwarding_rule" "https" {
  name                  = "${var.name_prefix}-https-rule"
  project               = var.project_id
  ip_address            = google_compute_global_address.lb.address
  port_range            = "443"
  target                = google_compute_target_https_proxy.app.id
  load_balancing_scheme = "EXTERNAL_MANAGED"
}

resource "google_compute_global_forwarding_rule" "http" {
  name                  = "${var.name_prefix}-http-rule"
  project               = var.project_id
  ip_address            = google_compute_global_address.lb.address
  port_range            = "80"
  target                = google_compute_target_http_proxy.http_redirect.id
  load_balancing_scheme = "EXTERNAL_MANAGED"
}
