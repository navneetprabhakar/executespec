#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════
# Dev Environment Cleanup & Reset
# ═══════════════════════════════════════════════════════════════
#
# Deletes all run data, resets usage limits, preserves user/org/
# project/billing config. Run this on the dev VM.
#
# Usage:
#   ./scripts/dev-cleanup.sh
#
# Requires: psql, gsutil (optional), redis-cli (optional)
# ═══════════════════════════════════════════════════════════════

set -euo pipefail

# ── Configuration (edit these for your environment) ───────────
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-executespec}"
DB_USER="${DB_USER:-postgres}"
GCS_BUCKET="${GCS_BUCKET:-executespec-workspaces}"
REDIS_HOST="${REDIS_HOST:-localhost}"
REDIS_PORT="${REDIS_PORT:-6379}"

# Workspace path: resolve relative to where the app runs
# The app uses agent.workspace=./workspace, so it's relative to CWD
# On VM, this is typically where the JAR is executed from
APP_DIR="${APP_DIR:-$(pwd)}"
WORKSPACE_DIR="${APP_DIR}/workspace"

# ── Colors ────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log()  { echo -e "${GREEN}[CLEANUP]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()  { echo -e "${RED}[ERROR]${NC} $*"; }

# ── Confirmation ──────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  Dev Environment Cleanup & Reset"
echo "═══════════════════════════════════════════════════════════"
echo ""
echo "This will:"
echo "  1. DELETE all pipeline runs and related data from the database"
echo "  2. RESET user/org run counts and billing usage to zero"
echo "  3. RESTORE all purchased run credits"
echo "  4. DELETE workspace files from disk"
echo "  5. DELETE workspace archives from GCS"
echo "  6. FLUSH Redis cache"
echo ""
echo "Preserved: users, orgs, projects, billing plans, subscriptions,"
echo "           deployment credentials, Slack integrations"
echo ""
echo -e "${YELLOW}Database: ${DB_HOST}:${DB_PORT}/${DB_NAME}${NC}"
echo -e "${YELLOW}Workspace: ${WORKSPACE_DIR}${NC}"
echo -e "${YELLOW}GCS Bucket: gs://${GCS_BUCKET}${NC}"
echo ""
read -p "Are you sure? Type 'yes' to proceed: " CONFIRM
if [ "$CONFIRM" != "yes" ]; then
    echo "Aborted."
    exit 0
fi

# ── Step 1: Database cleanup ─────────────────────────────────
log "Step 1/4: Cleaning database..."

PSQL_CMD="psql -h ${DB_HOST} -p ${DB_PORT} -U ${DB_USER} -d ${DB_NAME} -v ON_ERROR_STOP=1"

${PSQL_CMD} <<'SQL'
BEGIN;

-- ═══ DELETE RUN DATA (child tables first — no FK CASCADE on V1 tables) ═══
DELETE FROM test_result;
DELETE FROM test_task;
DELETE FROM test_plan;
DELETE FROM validation_report;
DELETE FROM plan_version;
DELETE FROM merge_conflict;
DELETE FROM model_task_metric;
DELETE FROM slack_notification_log;
DELETE FROM integration_event_log;
DELETE FROM integration_run;
DELETE FROM deployment;
DELETE FROM run_repo;
DELETE FROM run_recovery;
DELETE FROM compaction_event;
DELETE FROM gate_event;
DELETE FROM file_lock;
DELETE FROM agent_inbox;
DELETE FROM agent_message;
DELETE FROM agent_task;
DELETE FROM prompt_audit_entry;
DELETE FROM activity_audit;
DELETE FROM pipeline_run;

-- ═══ RESET USER/ORG USAGE COUNTERS ═══
UPDATE app_user SET personal_run_count = 0, run_count_reset_at = NOW();
UPDATE org SET monthly_run_count = 0, run_count_reset_at = NOW();

-- ═══ RESET BILLING USAGE ═══
DELETE FROM billing_usage;
DELETE FROM billing_usage_alert;
DELETE FROM billing_webhook_event;

-- ═══ RESTORE RUN CREDITS (remaining = purchased quantity) ═══
UPDATE run_topup SET remaining = quantity;

COMMIT;

-- ═══ VERIFY ═══
SELECT 'pipeline_run' AS tbl, count(*) FROM pipeline_run
UNION ALL SELECT 'agent_task', count(*) FROM agent_task
UNION ALL SELECT 'agent_message', count(*) FROM agent_message
UNION ALL SELECT 'app_user (preserved)', count(*) FROM app_user
UNION ALL SELECT 'org (preserved)', count(*) FROM org
UNION ALL SELECT 'project (preserved)', count(*) FROM project;
SQL

log "Database cleanup complete."

# ── Step 2: Workspace cleanup ─────────────────────────────────
log "Step 2/4: Cleaning workspace files..."

if [ -d "${WORKSPACE_DIR}" ]; then
    # Count before
    BEFORE=$(find "${WORKSPACE_DIR}" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | wc -l | tr -d ' ')
    log "Found ${BEFORE} workspace directories"

    # Delete all project workspaces
    rm -rf "${WORKSPACE_DIR}"/projects/* 2>/dev/null || true
    rm -rf "${WORKSPACE_DIR}"/worktrees/* 2>/dev/null || true

    # Recreate empty structure
    mkdir -p "${WORKSPACE_DIR}/projects"

    log "Workspace files cleaned."
else
    warn "Workspace directory not found at ${WORKSPACE_DIR}"
    warn "Set APP_DIR to the directory where the app JAR runs, e.g.:"
    warn "  APP_DIR=/opt/executespec ./scripts/dev-cleanup.sh"
fi

# ── Step 3: GCS cleanup ──────────────────────────────────────
log "Step 3/4: Cleaning GCS archives..."

if command -v gsutil &> /dev/null; then
    # Check if bucket has objects
    OBJ_COUNT=$(gsutil ls "gs://${GCS_BUCKET}/" 2>/dev/null | head -5 | wc -l | tr -d ' ')
    if [ "$OBJ_COUNT" -gt 0 ]; then
        gsutil -m rm -r "gs://${GCS_BUCKET}/**" 2>/dev/null || true
        log "GCS archives cleaned."
    else
        log "GCS bucket already empty."
    fi
else
    warn "gsutil not found. Skipping GCS cleanup."
    warn "Run manually: gsutil -m rm -r gs://${GCS_BUCKET}/**"
fi

# ── Step 4: Redis flush ──────────────────────────────────────
log "Step 4/4: Flushing Redis cache..."

if command -v redis-cli &> /dev/null; then
    redis-cli -h "${REDIS_HOST}" -p "${REDIS_PORT}" FLUSHDB 2>/dev/null || true
    log "Redis flushed."
else
    warn "redis-cli not found. Skipping Redis flush."
    warn "Run manually: redis-cli -h ${REDIS_HOST} FLUSHDB"
fi

# ── Summary ───────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════════"
echo -e "  ${GREEN}Cleanup complete!${NC}"
echo "═══════════════════════════════════════════════════════════"
echo ""
echo "Preserved:"
echo "  - Users, orgs, projects, billing subscriptions"
echo "  - Deployment credentials, Slack integrations"
echo "  - Tier config, model pricing, billing plans"
echo ""
echo "Reset:"
echo "  - All run data deleted"
echo "  - User/org run counts → 0"
echo "  - Billing usage → 0"
echo "  - Run credits restored to purchased amounts"
echo "  - Workspace files deleted"
echo "  - GCS archives deleted"
echo "  - Redis cache flushed"
echo ""
echo "Users can now test with a fresh environment."
