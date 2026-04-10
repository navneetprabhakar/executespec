#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════════════════════
# write-caddy-config.sh — Add Caddy reverse proxy config for a preview
#
# Usage: write-caddy-config.sh <preview-id> <target-ip> <port>
#
# Used when preview runs on a worker VM (not the gateway itself).
# Writes Caddy site config and reloads with brief global lock.
# ══════════════════════════════════════════════════════════════════════════════
set -euo pipefail

PREVIEW_ID="${1:?Usage: write-caddy-config.sh <preview-id> <target-ip> <port>}"
TARGET_IP="${2:?Usage: write-caddy-config.sh <preview-id> <target-ip> <port>}"
PORT="${3:?Usage: write-caddy-config.sh <preview-id> <target-ip> <port>}"

CADDY_SITES="/opt/preview-engine/caddy/sites"
CADDY_LOCK="/tmp/caddy-reload.lock"

mkdir -p "${CADDY_SITES}"

cat > "${CADDY_SITES}/${PREVIEW_ID}.caddy" <<EOF
${PREVIEW_ID}.preview.executespec.ai {
    reverse_proxy ${TARGET_IP}:${PORT}
    header {
        X-Preview-Id ${PREVIEW_ID}
    }
}
EOF

# Brief global lock for Caddy reload only
(
    flock -w 10 201 || { echo "WARN: Caddy reload lock timeout"; exit 0; }
    if docker ps --format '{{.Names}}' | grep -q caddy-proxy; then
        docker exec caddy-proxy caddy reload --config /etc/caddy/Caddyfile 2>&1 || true
    fi
) 201>"${CADDY_LOCK}"

echo "Caddy config written for ${PREVIEW_ID} -> ${TARGET_IP}:${PORT}"
