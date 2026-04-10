#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════════════════════
# setup-preview-vm.sh — One-time setup for a preview gateway/worker VM
#
# Run on the target VM:
#   curl -sL https://raw.githubusercontent.com/.../setup-preview-vm.sh | sudo bash
# Or SCP + run:
#   scp infra/preview-engine/bin/setup-preview-vm.sh user@vm:/tmp/
#   ssh user@vm 'sudo bash /tmp/setup-preview-vm.sh'
#
# Installs: Docker, Caddy (as container), directory structure, deploy scripts.
# ══════════════════════════════════════════════════════════════════════════════
set -euo pipefail

log() { echo "[setup] $(date -u +%H:%M:%S) $*"; }

# ── 1. Install Docker ────────────────────────────────────────────────────
if ! command -v docker &>/dev/null; then
    log "Installing Docker..."
    curl -fsSL https://get.docker.com | sh
    systemctl enable --now docker
    log "Docker installed"
else
    log "Docker already installed: $(docker --version)"
fi

# Add current user to docker group
SUDO_USER="${SUDO_USER:-$(whoami)}"
usermod -aG docker "${SUDO_USER}" 2>/dev/null || true

# ── 2. Create directory structure ─────────────────────────────────────────
log "Creating directory structure..."
mkdir -p /opt/preview-engine/{bin,caddy/sites,previews}

# ── 3. Copy deploy scripts ───────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

for script in deploy-preview.sh teardown-preview.sh write-caddy-config.sh extend-preview.sh cleanup-expired.sh; do
    if [ -f "${SCRIPT_DIR}/${script}" ]; then
        cp "${SCRIPT_DIR}/${script}" /opt/preview-engine/bin/
        chmod +x "/opt/preview-engine/bin/${script}"
        log "Installed ${script}"
    else
        log "WARN: ${script} not found in ${SCRIPT_DIR}"
    fi
done

# ── 4. Set up Caddy ──────────────────────────────────────────────────────
log "Setting up Caddy..."

# Main Caddyfile that imports per-preview site configs
cat > /opt/preview-engine/caddy/Caddyfile <<'CADDY_EOF'
{
    admin off
    auto_https disable_redirects
}

# Import all preview site configs
import /etc/caddy/sites/*.caddy
CADDY_EOF

# Start Caddy container if not running
if ! docker ps --format '{{.Names}}' | grep -q caddy-proxy; then
    docker run -d \
        --name caddy-proxy \
        --restart unless-stopped \
        --network host \
        -v /opt/preview-engine/caddy/Caddyfile:/etc/caddy/Caddyfile:ro \
        -v /opt/preview-engine/caddy/sites:/etc/caddy/sites:ro \
        -v caddy-data:/data \
        -v caddy-config:/config \
        caddy:2-alpine
    log "Caddy started"
else
    log "Caddy already running"
fi

# ── 5. Set up cron for expired preview cleanup ───────────────────────────
CRON_CMD="*/5 * * * * /opt/preview-engine/bin/cleanup-expired.sh >> /var/log/preview-cleanup.log 2>&1"
(crontab -l 2>/dev/null | grep -v cleanup-expired; echo "${CRON_CMD}") | crontab -
log "Cleanup cron installed (every 5 min)"

# ── 6. Set permissions ──────────────────────────────────────────────────
chown -R "${SUDO_USER}:${SUDO_USER}" /opt/preview-engine
chmod -R 755 /opt/preview-engine/bin

log "Setup complete. Preview engine ready at /opt/preview-engine/"
log "Deploy scripts: /opt/preview-engine/bin/"
log "Caddy config:   /opt/preview-engine/caddy/"
log "Previews:       /opt/preview-engine/previews/"
