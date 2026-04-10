#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════════════════════
# cleanup-expired.sh — Cron job to clean up expired preview containers
#
# Runs every 5 minutes via cron. Checks /opt/preview-engine/previews/ for
# containers that have exceeded their TTL based on creation time.
#
# This is a safety net — deploy-preview.sh also schedules per-preview cleanup
# via background sleep, but those can be lost on VM reboot.
# ══════════════════════════════════════════════════════════════════════════════
set -euo pipefail

PREVIEW_BASE="/opt/preview-engine/previews"
NOW=$(date +%s)
MAX_AGE_SECONDS=${MAX_PREVIEW_AGE_SECONDS:-7200}  # Default 2h

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) [cleanup] $*"; }

# List all preview directories
for dir in "${PREVIEW_BASE}"/*/; do
    [ -d "${dir}" ] || continue

    PREVIEW_ID=$(basename "${dir}")

    # Check directory age
    DIR_AGE=$(( NOW - $(stat -c %Y "${dir}" 2>/dev/null || stat -f %m "${dir}" 2>/dev/null || echo "${NOW}") ))

    if [ "${DIR_AGE}" -gt "${MAX_AGE_SECONDS}" ]; then
        log "Cleaning up expired preview: ${PREVIEW_ID} (age: ${DIR_AGE}s)"
        /opt/preview-engine/bin/teardown-preview.sh "${PREVIEW_ID}" 2>&1 || true
    fi
done

# Also clean up any orphaned containers with preview- prefix
docker ps -a --format '{{.Names}}' | grep '^preview-' | while read -r container; do
    PREVIEW_ID="${container#preview-}"
    # Skip docker compose project containers (they have _service_1 suffix)
    [[ "${PREVIEW_ID}" == *_* ]] && continue

    if [ ! -d "${PREVIEW_BASE}/${PREVIEW_ID}" ]; then
        log "Removing orphaned container: ${container}"
        docker rm -f "${container}" 2>/dev/null || true
    fi
done
