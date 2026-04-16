#!/usr/bin/env bash
# Dev-only: wipe runs + projects + workspace + Redis, reset monthly run
# counters. Takes a pg_dump first so rollback is possible.
#
# Defaults match application.yml (localhost:5432 / postgres / executespec).
# Override via DB_HOST / DB_PORT / DB_USER / DB_NAME env vars.

set -euo pipefail

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_USER="${DB_USER:-postgres}"
DB_NAME="${DB_NAME:-executespec}"
WORKSPACE_DIR="${WORKSPACE_DIR:-workspace}"
REDIS_HOST="${REDIS_HOST:-localhost}"
REDIS_PORT="${REDIS_PORT:-6379}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BACKUP_DIR="$HOME/executespec-backups"
mkdir -p "$BACKUP_DIR"

STAMP="$(date +%Y%m%d-%H%M%S)"
DUMP_FILE="$BACKUP_DIR/executespec-${STAMP}.dump"

echo "▶ pg_dump → $DUMP_FILE"
PGPASSWORD="${PGPASSWORD:-postgres}" pg_dump \
    -Fc \
    -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" \
    -d "$DB_NAME" \
    -f "$DUMP_FILE"

echo "▶ Running SQL wipe on $DB_NAME"
PGPASSWORD="${PGPASSWORD:-postgres}" psql \
    -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" \
    -d "$DB_NAME" \
    -v ON_ERROR_STOP=1 \
    -f "$REPO_DIR/scripts/dev-wipe-runs-and-projects.sql"

if [[ -d "$REPO_DIR/$WORKSPACE_DIR" ]]; then
    echo "▶ Clearing $REPO_DIR/$WORKSPACE_DIR/*"
    find "$REPO_DIR/$WORKSPACE_DIR" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
else
    echo "  (no $WORKSPACE_DIR directory to clean)"
fi

if command -v redis-cli >/dev/null 2>&1; then
    echo "▶ Clearing Redis keys under project:* / sse:*"
    redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" --scan --pattern 'project:*' \
        | xargs -r redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" DEL >/dev/null
    redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" --scan --pattern 'sse:*' \
        | xargs -r redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" DEL >/dev/null
    redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" --scan --pattern 'run:*' \
        | xargs -r redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" DEL >/dev/null
else
    echo "  (redis-cli not on PATH — skipped Redis clear)"
fi

echo "✓ Done. Backup: $DUMP_FILE"
echo "  Restore with: pg_restore -c -d $DB_NAME $DUMP_FILE"
