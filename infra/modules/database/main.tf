# ── Cloud SQL PostgreSQL ─────────────────────────────────────────────────────
# NOTE: Depends on google_service_networking_connection in the network module.
# The private_ip_range must be allocated before this instance is created.

resource "google_sql_database_instance" "postgres" {
  name                = "${var.name_prefix}-pg"
  project             = var.project_id
  region              = var.region
  database_version    = "POSTGRES_15"
  deletion_protection = var.db_ha_enabled  # protect prod (HA enabled = prod)

  settings {
    tier              = var.db_tier
    disk_size         = var.db_disk_size_gb
    disk_autoresize   = true
    disk_type         = "PD_SSD"
    availability_type = var.db_ha_enabled ? "REGIONAL" : "ZONAL"

    ip_configuration {
      ipv4_enabled                                  = false
      private_network                               = var.network_id
      enable_private_path_for_google_cloud_services  = true
    }

    backup_configuration {
      enabled                        = true
      start_time                     = "03:00"
      point_in_time_recovery_enabled = var.db_ha_enabled
      transaction_log_retention_days = var.db_ha_enabled ? 7 : 1

      backup_retention_settings {
        retained_backups = var.db_ha_enabled ? 14 : 7
      }
    }

    maintenance_window {
      day          = 7  # Sunday
      hour         = 4  # 4 AM UTC
      update_track = "stable"
    }

    database_flags {
      name  = "max_connections"
      value = "100"
    }

    user_labels = var.labels
  }
}

# ── Database ────────────────────────────────────────────────────────────────

resource "google_sql_database" "app" {
  name     = var.db_name
  project  = var.project_id
  instance = google_sql_database_instance.postgres.name
}

# ── User ────────────────────────────────────────────────────────────────────

resource "random_password" "db_password" {
  length  = 32
  special = false
}

resource "google_sql_user" "app" {
  name     = var.db_user
  project  = var.project_id
  instance = google_sql_database_instance.postgres.name
  password = random_password.db_password.result
}

# ── Store password in Secret Manager ────────────────────────────────────────

resource "google_secret_manager_secret" "db_password" {
  secret_id = "${var.name_prefix}-db-password"
  project   = var.project_id

  replication {
    auto {}
  }

  labels = var.labels
}

resource "google_secret_manager_secret_version" "db_password" {
  secret      = google_secret_manager_secret.db_password.id
  secret_data = random_password.db_password.result
}
