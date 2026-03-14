# ── Memorystore Redis ────────────────────────────────────────────────────────

resource "google_redis_instance" "cache" {
  name               = "${var.name_prefix}-redis"
  project            = var.project_id
  region             = var.region
  tier               = var.redis_tier
  memory_size_gb     = var.redis_memory_gb
  redis_version      = "REDIS_7_0"
  authorized_network = var.network_id
  connect_mode       = "PRIVATE_SERVICE_ACCESS"

  redis_configs = {
    maxmemory-policy = "allkeys-lru"
  }

  maintenance_policy {
    weekly_maintenance_window {
      day = "SUNDAY"
      start_time {
        hours   = 4
        minutes = 0
      }
    }
  }

  labels = var.labels
}
