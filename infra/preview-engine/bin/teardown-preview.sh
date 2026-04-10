#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════════════════════
# teardown-preview.sh — Stop and remove a preview container + Caddy config
#
# Usage: teardown-preview.sh <preview-id>
# ══════════════════════════════════════════════════════════════════════════════
set -euo pipefail

PREVIEW_ID="${1:?Usage: teardown-preview.sh <preview-id>}"

PREVIEW_BASE="/opt/preview-engine/previews"
CADDY_SITES="/opt/preview-engine/caddy/sites"
CADDY_LOCK="/tmp/caddy-reload.lock"
LOG_PREFIX="[teardown:${PREVIEW_ID}]"

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) ${LOG_PREFIX} $*"; }

log "Tearing down preview..."

# ── 1. Stop and remove containers ────────────────────────────────────────
PREVIEW_DIR="${PREVIEW_BASE}/${PREVIEW_ID}"

if [ -d "${PREVIEW_DIR}" ]; then
    cd "${PREVIEW_DIR}"
    COMPOSE_FILE=$(ls docker-compose.yml docker-compose.yaml compose.yml 2>/dev/null | head -1)
    if [ -n "${COMPOSE_FILE}" ]; then
        docker compose -f "${COMPOSE_FILE}" -p "preview-${PREVIEW_ID}" down --volumes --remove-orphans 2>&1 || true
    fi
fi

# Also try standalone container
docker rm -f "preview-${PREVIEW_ID}" 2>/dev/null || true

# Remove dangling images for this preview
docker images --filter "reference=preview-${PREVIEW_ID}" -q | xargs -r docker rmi 2>/dev/null || true

log "Containers removed"

# ── 2. Remove Caddy config (brief global lock for reload) ────────────────
CADDY_CONFIG="${CADDY_SITES}/${PREVIEW_ID}.caddy"
if [ -f "${CADDY_CONFIG}" ]; then
    rm -f "${CADDY_CONFIG}"
    (
        flock -w 10 201 || { log "WARN: Caddy reload lock timeout"; exit 0; }
        if docker ps --format '{{.Names}}' | grep -q caddy-proxy; then
            docker exec caddy-proxy caddy reload --config /etc/caddy/Caddyfile 2>&1 || true
        fi
    ) 201>"${CADDY_LOCK}"
    log "Caddy config removed and reloaded"
fi

# ── 3. Remove preview directory ──────────────────────────────────────────
rm -rf "${PREVIEW_DIR}"
rm -f "/tmp/preview-lock-${PREVIEW_ID}.lock"

log "Teardown complete"
