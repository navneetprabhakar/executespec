#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════════════════════
# deploy-preview.sh — Deploy a preview container on this VM
#
# Usage: deploy-preview.sh <preview-id> <project-dir> <ttl-hours>
#
# Supports fully parallel deploys — each preview gets its own lock.
# Only the Caddy reload step is globally serialized (< 1 second).
#
# Output: Prints PREVIEW_JSON:{...} on success for the Java backend to parse.
# ══════════════════════════════════════════════════════════════════════════════
set -euo pipefail

PREVIEW_ID="${1:?Usage: deploy-preview.sh <preview-id> <project-dir> <ttl-hours>}"
PROJECT_DIR="${2:?Usage: deploy-preview.sh <preview-id> <project-dir> <ttl-hours>}"
TTL_HOURS="${3:-2}"

PREVIEW_BASE="/opt/preview-engine/previews"
CADDY_SITES="/opt/preview-engine/caddy/sites"
CADDY_LOCK="/tmp/caddy-reload.lock"
LOG_PREFIX="[deploy:${PREVIEW_ID}]"

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) ${LOG_PREFIX} $*"; }

echo "=== Deploy started at $(date -u) ==="

# ── 1. Per-preview lock (prevents double-deploy of same preview) ──────────
LOCK_FILE="/tmp/preview-lock-${PREVIEW_ID}.lock"
exec 200>"${LOCK_FILE}"
if ! flock -n 200; then
    log "ERROR: Preview ${PREVIEW_ID} is already being deployed"
    exit 1
fi
trap 'rm -f "${LOCK_FILE}"' EXIT

# ── 2. Set up preview directory ───────────────────────────────────────────
PREVIEW_DIR="${PREVIEW_BASE}/${PREVIEW_ID}"
mkdir -p "${PREVIEW_DIR}"

# Copy project files
cp -r "${PROJECT_DIR}/." "${PREVIEW_DIR}/"
log "Project files copied to ${PREVIEW_DIR}"

# ── 3. Find a free port ──────────────────────────────────────────────────
find_free_port() {
    local port
    for port in $(shuf -i 10000-60000 -n 50); do
        if ! ss -tlnp | grep -q ":${port} "; then
            echo "${port}"
            return 0
        fi
    done
    echo "ERROR: No free port found" >&2
    return 1
}

APP_PORT=$(find_free_port)
log "Allocated port: ${APP_PORT}"

# Write .env for docker-compose (port isolation)
echo "APP_PORT=${APP_PORT}" > "${PREVIEW_DIR}/.env"

# ── 4. Build and start containers (fully parallel — no global lock) ──────
cd "${PREVIEW_DIR}"

if [ -f "docker-compose.yml" ] || [ -f "docker-compose.yaml" ] || [ -f "compose.yml" ]; then
    COMPOSE_FILE=$(ls docker-compose.yml docker-compose.yaml compose.yml 2>/dev/null | head -1)
    log "Building with docker compose (${COMPOSE_FILE})..."
    docker compose -f "${COMPOSE_FILE}" -p "preview-${PREVIEW_ID}" build --quiet 2>&1 | tail -5
    log "Starting containers..."
    docker compose -f "${COMPOSE_FILE}" -p "preview-${PREVIEW_ID}" up -d 2>&1 | tail -5
elif [ -f "Dockerfile" ]; then
    log "Building with Dockerfile..."
    docker build -t "preview-${PREVIEW_ID}" --quiet . 2>&1 | tail -3
    log "Starting container..."
    docker run -d \
        --name "preview-${PREVIEW_ID}" \
        --restart unless-stopped \
        --memory 512m \
        --cpus 1 \
        -p "${APP_PORT}:${APP_PORT}" \
        -e "PORT=${APP_PORT}" \
        -e "HOST=0.0.0.0" \
        --security-opt no-new-privileges:true \
        --cap-drop ALL \
        "preview-${PREVIEW_ID}"
else
    log "ERROR: No docker-compose.yml or Dockerfile found"
    exit 1
fi

log "Containers started on port ${APP_PORT}"

# ── 5. Write Caddy config (brief global lock for reload only) ────────────
mkdir -p "${CADDY_SITES}"
CADDY_CONFIG="${CADDY_SITES}/${PREVIEW_ID}.caddy"

cat > "${CADDY_CONFIG}" <<CADDY_EOF
${PREVIEW_ID}.preview.executespec.ai {
    reverse_proxy localhost:${APP_PORT}
    header {
        X-Preview-Id ${PREVIEW_ID}
        X-Preview-TTL ${TTL_HOURS}h
    }
}
CADDY_EOF

# Globally serialized Caddy reload (sub-second, non-blocking for other deploys)
(
    flock -w 10 201 || { log "WARN: Caddy reload lock timeout, skipping reload"; exit 0; }
    if docker ps --format '{{.Names}}' | grep -q caddy-proxy; then
        docker exec caddy-proxy caddy reload --config /etc/caddy/Caddyfile 2>&1 || true
    fi
) 201>"${CADDY_LOCK}"

log "Caddy config written and reloaded"

# ── 6. Health check ──────────────────────────────────────────────────────
HEALTHY=false
PREVIEW_URL="https://${PREVIEW_ID}.preview.executespec.ai"

for i in $(seq 1 30); do
    if curl -sf -o /dev/null --max-time 3 "http://localhost:${APP_PORT}" 2>/dev/null; then
        HEALTHY=true
        log "Health check passed (attempt ${i})"
        break
    fi
    sleep 2
done

if [ "${HEALTHY}" = "false" ]; then
    log "WARN: Health check failed after 30 attempts, marking unhealthy"
fi

# ── 7. Schedule TTL cleanup ─────────────────────────────────────────────
TTL_SECONDS=$((TTL_HOURS * 3600))
(
    sleep "${TTL_SECONDS}"
    /opt/preview-engine/bin/teardown-preview.sh "${PREVIEW_ID}" 2>/dev/null || true
) &
CLEANUP_PID=$!
disown "${CLEANUP_PID}" 2>/dev/null || true
log "Cleanup scheduled in ${TTL_HOURS}h (PID ${CLEANUP_PID})"

# ── 8. Output result for Java backend ───────────────────────────────────
cat <<RESULT_EOF
PREVIEW_JSON:{"previewId":"${PREVIEW_ID}","url":"${PREVIEW_URL}","port":${APP_PORT},"healthy":${HEALTHY},"ttlHours":${TTL_HOURS}}
RESULT_EOF

log "Deploy complete: ${PREVIEW_URL}"
