#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════════════════════
# extend-preview.sh — Extend a preview's TTL
#
# Usage: extend-preview.sh <preview-id> <extra-hours>
#
# Kills the existing cleanup timer and schedules a new one.
# ══════════════════════════════════════════════════════════════════════════════
set -euo pipefail

PREVIEW_ID="${1:?Usage: extend-preview.sh <preview-id> <extra-hours>}"
EXTRA_HOURS="${2:?Usage: extend-preview.sh <preview-id> <extra-hours>}"

LOG_PREFIX="[extend:${PREVIEW_ID}]"
log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) ${LOG_PREFIX} $*"; }

# Kill any existing cleanup process for this preview
pkill -f "teardown-preview.sh ${PREVIEW_ID}" 2>/dev/null || true

# Schedule new cleanup
TTL_SECONDS=$((EXTRA_HOURS * 3600))
(
    sleep "${TTL_SECONDS}"
    /opt/preview-engine/bin/teardown-preview.sh "${PREVIEW_ID}" 2>/dev/null || true
) &
CLEANUP_PID=$!
disown "${CLEANUP_PID}" 2>/dev/null || true

log "Extended by ${EXTRA_HOURS}h (new cleanup PID ${CLEANUP_PID})"
