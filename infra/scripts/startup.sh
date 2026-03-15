#!/bin/bash
# ── ExecuteSpec GCE Startup Script ───────────────────────────────────────────
# This script runs on first boot AND every reboot of the GCE instance.
# It's idempotent — safe to run multiple times.
#
# Responsibilities:
#   1. Install Java 21, Docker, Node.js LTS, Python 3 + tools
#   2. Read metadata for DB/Redis/bucket config
#   3. Write systemd service + environment file
#   4. Fetch latest JAR from GCS and start the app
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

LOG_TAG="executespec-startup"
log() { logger -t "$LOG_TAG" "$1"; echo "[$(date '+%H:%M:%S')] $1"; }

METADATA_URL="http://metadata.google.internal/computeMetadata/v1/instance/attributes"
meta() { curl -sf -H "Metadata-Flavor: Google" "$METADATA_URL/$1" 2>/dev/null || echo ""; }

APP_DIR="/opt/app"
RELEASES_DIR="$APP_DIR/releases"
ENV_FILE="$APP_DIR/executespec.env"

# ── 1. System packages ──────────────────────────────────────────────────────

log "Phase 1: System packages"

export DEBIAN_FRONTEND=noninteractive

if ! command -v java &>/dev/null || ! java -version 2>&1 | grep -q "21"; then
  log "Installing Java 21..."
  apt-get update -qq
  apt-get install -y -qq openjdk-21-jre-headless
fi
log "Java: $(java -version 2>&1 | head -1)"

# ── 2. Docker ────────────────────────────────────────────────────────────────

log "Phase 2: Docker"

if ! command -v docker &>/dev/null; then
  log "Installing Docker..."
  apt-get install -y -qq ca-certificates curl gnupg
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  chmod a+r /etc/apt/keyrings/docker.gpg
  echo \
    "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
    $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null
  apt-get update -qq
  apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-compose-plugin
  systemctl enable docker
  systemctl start docker
fi
log "Docker: $(docker --version)"

# ── 3. Node.js LTS ──────────────────────────────────────────────────────────

log "Phase 3: Node.js"

if ! command -v node &>/dev/null; then
  log "Installing Node.js LTS..."
  curl -fsSL https://deb.nodesource.com/setup_lts.x | bash -
  apt-get install -y -qq nodejs
fi
log "Node: $(node --version), npm: $(npm --version)"

# ── 4. Python 3 + linting tools ─────────────────────────────────────────────

log "Phase 4: Python"

if ! command -v python3 &>/dev/null; then
  log "Installing Python 3..."
  apt-get install -y -qq python3 python3-pip python3-venv
fi

# Install global Python tools (validator fallbacks)
pip3 install --quiet --break-system-packages mypy ruff pytest 2>/dev/null \
  || pip3 install --quiet mypy ruff pytest 2>/dev/null \
  || log "Python tools install skipped (may already exist)"
log "Python: $(python3 --version)"

# ── 5. Additional tools ─────────────────────────────────────────────────────

log "Phase 5: Additional tools"

# Git (usually pre-installed on Ubuntu)
if ! command -v git &>/dev/null; then
  apt-get install -y -qq git
fi

# gsutil / gcloud (usually pre-installed on GCE)
if ! command -v gsutil &>/dev/null; then
  log "Installing Google Cloud SDK..."
  apt-get install -y -qq google-cloud-sdk
fi

# ── 6. Read metadata and write environment file ─────────────────────────────

log "Phase 6: Environment configuration"

mkdir -p "$APP_DIR" "$RELEASES_DIR"
chmod 755 "$APP_DIR" "$RELEASES_DIR"

ES_ENV=$(meta "es-environment")
DB_HOST=$(meta "es-db-host")
DB_NAME=$(meta "es-db-name")
DB_USER=$(meta "es-db-user")
DB_PASSWORD=$(meta "es-db-password")
REDIS_HOST=$(meta "es-redis-host")
REDIS_PORT=$(meta "es-redis-port")
DEPLOY_BUCKET=$(meta "es-deploy-bucket")
WORKSPACE_BUCKET=$(meta "es-workspace-bucket")

# ── 6b. Load SMTP credentials from GCS (if uploaded) ──────────────────────

SMTP_USERNAME=""
SMTP_PASSWORD=""
if [ -n "$DEPLOY_BUCKET" ]; then
  # SMTP credentials stored as a simple key=value file in GCS
  SMTP_CREDS_FILE="/tmp/smtp-creds"
  if gsutil -q stat "gs://${DEPLOY_BUCKET}/config/smtp-credentials" 2>/dev/null; then
    gsutil cp "gs://${DEPLOY_BUCKET}/config/smtp-credentials" "$SMTP_CREDS_FILE"
    SMTP_USERNAME=$(grep '^SMTP_USERNAME=' "$SMTP_CREDS_FILE" | cut -d= -f2-)
    SMTP_PASSWORD=$(grep '^SMTP_PASSWORD=' "$SMTP_CREDS_FILE" | cut -d= -f2-)
    rm -f "$SMTP_CREDS_FILE"
    log "SMTP credentials loaded from GCS"
  else
    log "No SMTP credentials in GCS — emails will be skipped"
  fi
fi

# ── 6c. API Keys ─────────────────────────────────────────────────────────
#
# PRIMARY: GCP Secret Manager (loaded by SecretManagerConfigLoader at app startup).
#          Secrets are labelled app=executespec and read via ADC.
#
# FALLBACK: GCS plain-text file (for bootstrapping before Secret Manager is seeded).
#          Upload once: gsutil cp api-keys gs://<DEPLOY_BUCKET>/config/api-keys
#
# The env file always gets empty defaults so Spring can start.
# Secret Manager values (if present) override these at runtime.

ANTHROPIC_API_KEY=""
OPENAI_API_KEY=""
GOOGLE_API_KEY=""
GITHUB_PAT=""
if [ -n "$DEPLOY_BUCKET" ]; then
  API_KEYS_FILE="/tmp/api-keys"
  if gsutil -q stat "gs://${DEPLOY_BUCKET}/config/api-keys" 2>/dev/null; then
    gsutil cp "gs://${DEPLOY_BUCKET}/config/api-keys" "$API_KEYS_FILE"
    ANTHROPIC_API_KEY=$(grep '^ANTHROPIC_API_KEY=' "$API_KEYS_FILE" | cut -d= -f2-)
    OPENAI_API_KEY=$(grep '^OPENAI_API_KEY=' "$API_KEYS_FILE" | cut -d= -f2-)
    GOOGLE_API_KEY=$(grep '^GOOGLE_API_KEY=' "$API_KEYS_FILE" | cut -d= -f2-)
    GITHUB_PAT=$(grep '^GITHUB_PAT=' "$API_KEYS_FILE" | cut -d= -f2-)
    rm -f "$API_KEYS_FILE"
    log "API keys loaded from GCS fallback (${#ANTHROPIC_API_KEY} / ${#OPENAI_API_KEY} / ${#GOOGLE_API_KEY} / ${#GITHUB_PAT} chars)"
  else
    log "No GCS api-keys file — app will load secrets from Secret Manager at startup"
  fi
fi

# ── 6d. Derive public URL and CORS origins from environment ─────────────────

if [ "$ES_ENV" = "prod" ]; then
  APP_PUBLIC_URL="https://app.executespec.ai"
elif [ "$ES_ENV" = "dev" ]; then
  APP_PUBLIC_URL="https://dev.executespec.ai"
else
  APP_PUBLIC_URL="http://localhost:8080"
fi
CORS_ORIGINS="https://app.executespec.ai,https://dev.executespec.ai,http://localhost:5173"

cat > "$ENV_FILE" << ENVEOF
# Auto-generated by startup script — do not edit manually
# Environment: ${ES_ENV:-unknown}
# Generated: $(date -u +"%Y-%m-%dT%H:%M:%SZ")

# Database
POSTGRES_HOST=${DB_HOST}
POSTGRES_PORT=5432
POSTGRES_DB=${DB_NAME}
POSTGRES_USER=${DB_USER}
POSTGRES_PASSWORD=${DB_PASSWORD}

# Redis
REDIS_HOST=${REDIS_HOST}
REDIS_PORT=${REDIS_PORT}

# GCS
GCS_WORKSPACE_BUCKET=${WORKSPACE_BUCKET}
DEPLOY_BUCKET=${DEPLOY_BUCKET}

# Mail (SMTP)
MAIL_TRANSPORT=smtp
MAIL_FROM=admin@executespec.ai
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=${SMTP_USERNAME}
SMTP_PASSWORD=${SMTP_PASSWORD}

# API Keys (loaded from GCS)
ANTHROPIC_API_KEY=${ANTHROPIC_API_KEY}
OPENAI_API_KEY=${OPENAI_API_KEY}
GOOGLE_API_KEY=${GOOGLE_API_KEY}
GITHUB_PAT=${GITHUB_PAT}

# GCP (for Secret Manager + runtime config)
GCP_PROJECT=$(curl -sf -H "Metadata-Flavor: Google" "http://metadata.google.internal/computeMetadata/v1/project/project-id" 2>/dev/null || echo "")

# App
SPRING_PROFILES_ACTIVE=${ES_ENV:-prod}
CORS_ALLOWED_ORIGINS=${CORS_ORIGINS}
DASHBOARD_URL=${APP_PUBLIC_URL}
APP_BASE_URL=${APP_PUBLIC_URL}
JAVA_OPTS="-Xms1g -Xmx6g -XX:+UseG1GC"
ENVEOF

chmod 600 "$ENV_FILE"
log "Environment file written to $ENV_FILE (MAIL_TRANSPORT=smtp)"

# ── 7. Systemd service ──────────────────────────────────────────────────────

log "Phase 7: Systemd service"

cat > /etc/systemd/system/executespec.service << 'SVCEOF'
[Unit]
Description=ExecuteSpec Application
After=network-online.target docker.service
Wants=network-online.target
Requires=docker.service

[Service]
Type=simple
User=root
WorkingDirectory=/opt/app
EnvironmentFile=/opt/app/executespec.env
ExecStart=/usr/bin/java $JAVA_OPTS -jar /opt/app/app.jar
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal
SyslogIdentifier=executespec

# Security hardening
NoNewPrivileges=false
ProtectSystem=false
ProtectHome=false

# Resource limits
LimitNOFILE=65535
LimitNPROC=65535

[Install]
WantedBy=multi-user.target
SVCEOF

systemctl daemon-reload
systemctl enable executespec
log "Systemd service configured"

# ── 8. Fetch latest JAR from GCS ────────────────────────────────────────────

log "Phase 8: Fetch application JAR"

if [ -n "$DEPLOY_BUCKET" ]; then
  CURRENT_SHA=$(gsutil cat "gs://${DEPLOY_BUCKET}/CURRENT_SHA" 2>/dev/null || echo "")
  if [ -n "$CURRENT_SHA" ]; then
    JAR_PATH="$RELEASES_DIR/app-${CURRENT_SHA}.jar"
    if [ ! -f "$JAR_PATH" ]; then
      log "Downloading app-${CURRENT_SHA}.jar from GCS..."
      if ! gsutil cp "gs://${DEPLOY_BUCKET}/releases/app-${CURRENT_SHA}.jar" "$JAR_PATH" 2>/dev/null; then
        log "WARN: CURRENT_SHA JAR not found in GCS (pruned?). Falling back to latest available..."
        LATEST_JAR=$(gsutil ls "gs://${DEPLOY_BUCKET}/releases/app-*.jar" 2>/dev/null | sort | tail -1 || echo "")
        if [ -n "$LATEST_JAR" ]; then
          FALLBACK_NAME=$(basename "$LATEST_JAR")
          JAR_PATH="$RELEASES_DIR/$FALLBACK_NAME"
          gsutil cp "$LATEST_JAR" "$JAR_PATH"
          log "Downloaded fallback JAR: $FALLBACK_NAME"
        else
          log "ERROR: No JARs available in GCS — waiting for CI/CD deploy"
          JAR_PATH=""
        fi
      fi
    fi
    if [ -n "$JAR_PATH" ] && [ -f "$JAR_PATH" ]; then
      ln -sf "$JAR_PATH" "$APP_DIR/app.jar"
      log "Linked app.jar → $(basename "$JAR_PATH")"
    fi
  else
    log "No CURRENT_SHA found in GCS — skipping JAR download (first deploy?)"
  fi
else
  log "No deploy bucket configured — skipping JAR download"
fi

# ── 9. Start the app ────────────────────────────────────────────────────────

log "Phase 9: Starting application"

if [ -f "$APP_DIR/app.jar" ]; then
  systemctl restart executespec
  log "Application started. Check: journalctl -u executespec -f"
else
  log "No app.jar found — waiting for first deployment via CI/CD"
fi

# ── 10. Docker cleanup cron ─────────────────────────────────────────────────

log "Phase 10: Docker cleanup cron"

cat > /etc/cron.daily/docker-cleanup << 'CRONEOF'
#!/bin/bash
# Clean up unused Docker resources (dangling images, stopped containers)
docker system prune -f --filter "until=24h" 2>/dev/null || true
CRONEOF
chmod +x /etc/cron.daily/docker-cleanup

# ── Done ────────────────────────────────────────────────────────────────────

log "Startup script complete. Environment: ${ES_ENV:-unknown}"
