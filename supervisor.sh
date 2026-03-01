#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════════
#  AgentTeam Process Supervisor
#  Starts, monitors and auto-restarts the Spring Boot application.
#
#  State management: .supervisor/state  (key=value, sourced as bash env)
#  PID tracking:     .supervisor/app.pid   (JVM process)
#                    .supervisor/monitor.pid (this watcher loop)
#  Logs:             .supervisor/app.log
#                    .supervisor/monitor.log
#
#  Usage:
#    ./supervisor.sh start    — build JAR if needed, start app + watcher
#    ./supervisor.sh stop     — graceful shutdown of app + watcher
#    ./supervisor.sh restart  — stop then start
#    ./supervisor.sh status   — pretty-print current state
#    ./supervisor.sh logs     — tail app log (Ctrl-C to exit)
#    ./supervisor.sh build    — rebuild JAR without starting
# ═══════════════════════════════════════════════════════════════════════════════
set -uo pipefail

# ── Config ────────────────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SUP_DIR="$SCRIPT_DIR/.supervisor"
STATE_FILE="$SUP_DIR/state"
APP_PID_FILE="$SUP_DIR/app.pid"
MONITOR_PID_FILE="$SUP_DIR/monitor.pid"
APP_LOG="$SUP_DIR/app.log"
MONITOR_LOG="$SUP_DIR/monitor.log"

HEALTH_URL="http://localhost:8080/api/health"
HEALTH_TIMEOUT=5          # curl timeout per attempt
STARTUP_WAIT=120          # seconds to wait for first successful health check
HEALTH_INTERVAL=20        # seconds between health polls while running
MAX_CRASHES=10            # stop auto-restarting after this many crashes
INITIAL_BACKOFF=5         # seconds before first restart attempt
MAX_BACKOFF=120           # cap on exponential backoff

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
JAVA_BIN="$JAVA_HOME/bin/java"
MVN_BIN="$(which mvn 2>/dev/null || echo 'mvn')"
JAR_GLOB="$SCRIPT_DIR/target/agentteam*.jar"

# ── Colours ───────────────────────────────────────────────────────────────────

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

# ── State helpers ─────────────────────────────────────────────────────────────

init_state() {
    mkdir -p "$SUP_DIR"
    if [ ! -f "$STATE_FILE" ]; then
        write_state "STOPPED" 0 0 0 "" "" ""
    fi
}

# write_state STATUS PID START_COUNT CRASH_COUNT STARTED_AT LAST_CRASH_AT LAST_CRASH_REASON
write_state() {
    local status="$1" pid="$2" starts="$3" crashes="$4"
    local started_at="$5" last_crash_at="$6" last_crash_reason="$7"
    cat > "$STATE_FILE" << EOF
STATUS=$status
PID=$pid
START_COUNT=$starts
CRASH_COUNT=$crashes
STARTED_AT=$started_at
LAST_CRASH_AT=$last_crash_at
LAST_CRASH_REASON=$last_crash_reason
UPDATED_AT=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
EOF
}

read_state() {
    [ -f "$STATE_FILE" ] && source "$STATE_FILE" || true
    STATUS="${STATUS:-STOPPED}"
    PID="${PID:-0}"
    START_COUNT="${START_COUNT:-0}"
    CRASH_COUNT="${CRASH_COUNT:-0}"
    STARTED_AT="${STARTED_AT:-}"
    LAST_CRASH_AT="${LAST_CRASH_AT:-}"
    LAST_CRASH_REASON="${LAST_CRASH_REASON:-}"
}

read_pid() { [ -f "$APP_PID_FILE" ] && cat "$APP_PID_FILE" || echo 0; }
read_monitor_pid() { [ -f "$MONITOR_PID_FILE" ] && cat "$MONITOR_PID_FILE" || echo 0; }

process_alive() {
    local pid="$1"
    [ "$pid" -gt 0 ] 2>/dev/null && kill -0 "$pid" 2>/dev/null
}

monitor_log() { echo "[$(date '+%H:%M:%S')] $*" | tee -a "$MONITOR_LOG"; }

# ── Health check ──────────────────────────────────────────────────────────────

check_health() {
    curl -sf --max-time "$HEALTH_TIMEOUT" "$HEALTH_URL" > /dev/null 2>&1
}

wait_for_health() {
    local deadline=$(( $(date +%s) + STARTUP_WAIT ))
    local attempt=0
    while [ "$(date +%s)" -lt "$deadline" ]; do
        attempt=$(( attempt + 1 ))
        if check_health; then
            monitor_log "✅ Health check passed (attempt $attempt)"
            return 0
        fi
        sleep 3
    done
    monitor_log "❌ Health check never passed within ${STARTUP_WAIT}s"
    return 1
}

# ── JAR management ────────────────────────────────────────────────────────────

find_jar() { ls $JAR_GLOB 2>/dev/null | head -1; }

build_jar() {
    echo -e "${BLUE}🔨 Building JAR (this takes ~30s)…${NC}"
    JAVA_HOME="$JAVA_HOME" "$MVN_BIN" package -q -DskipTests \
        --file "$SCRIPT_DIR/pom.xml" 2>&1 | tee -a "$MONITOR_LOG"
    local rc=${PIPESTATUS[0]}
    if [ $rc -ne 0 ]; then
        echo -e "${RED}❌ Build failed — check $MONITOR_LOG${NC}"
        return 1
    fi
    echo -e "${GREEN}✅ Build succeeded${NC}"
}

# ── App lifecycle ─────────────────────────────────────────────────────────────

launch_app() {
    local jar
    jar="$(find_jar)"
    if [ -z "$jar" ]; then
        monitor_log "No JAR found — building first"
        build_jar || return 1
        jar="$(find_jar)"
    fi

    monitor_log "🚀 Launching: $JAVA_BIN -jar $jar"
    # Launch the JVM in background, redirect stdout/stderr to app log
    JAVA_HOME="$JAVA_HOME" "$JAVA_BIN" -jar "$jar" \
        >> "$APP_LOG" 2>&1 &
    local pid=$!
    echo "$pid" > "$APP_PID_FILE"
    monitor_log "   PID=$pid → $APP_LOG"
    echo "$pid"
}

kill_app() {
    local pid
    pid="$(read_pid)"
    if process_alive "$pid"; then
        monitor_log "🛑 Sending SIGTERM to PID $pid"
        kill -TERM "$pid" 2>/dev/null || true
        # Wait up to 15s for graceful shutdown
        local waited=0
        while process_alive "$pid" && [ $waited -lt 15 ]; do
            sleep 1; waited=$(( waited + 1 ))
        done
        if process_alive "$pid"; then
            monitor_log "⚠️  Still alive after 15s — sending SIGKILL"
            kill -KILL "$pid" 2>/dev/null || true
        fi
    fi
    rm -f "$APP_PID_FILE"
}

# ── Monitor loop (runs in background) ────────────────────────────────────────

run_monitor() {
    local starts=0 crashes=0 backoff=$INITIAL_BACKOFF

    monitor_log "═══ Supervisor monitor started (PID $$) ═══"
    echo $$ > "$MONITOR_PID_FILE"

    # Initial launch
    starts=$(( starts + 1 ))
    local pid
    pid="$(launch_app)" || { monitor_log "❌ Failed to launch — exiting monitor"; exit 1; }
    write_state "STARTING" "$pid" "$starts" "$crashes" \
        "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" "" ""

    if ! wait_for_health; then
        monitor_log "❌ App failed to become healthy on startup — giving up"
        kill_app
        write_state "CRASHED" 0 "$starts" "$crashes" "" \
            "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" "Never became healthy"
        exit 1
    fi

    write_state "RUNNING" "$pid" "$starts" "$crashes" \
        "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" "" ""

    # ── Main polling loop ──────────────────────────────────────────────────────
    while true; do
        sleep "$HEALTH_INTERVAL"

        pid="$(read_pid)"

        # Check 1: process still alive?
        if ! process_alive "$pid"; then
            crashes=$(( crashes + 1 ))
            local reason="Process $pid exited unexpectedly"
            monitor_log "💀 $reason (crash #$crashes)"
            write_state "CRASHED" 0 "$starts" "$crashes" \
                "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
                "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" "$reason"
        # Check 2: HTTP health endpoint
        elif ! check_health; then
            crashes=$(( crashes + 1 ))
            local reason="Health check failed (process $pid still alive)"
            monitor_log "🤒 $reason (crash #$crashes)"
            kill_app
            write_state "UNHEALTHY" 0 "$starts" "$crashes" \
                "" "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" "$reason"
        else
            # Healthy — reset backoff
            backoff=$INITIAL_BACKOFF
            monitor_log "💚 Healthy (PID=$pid, crashes=$crashes)"
            continue
        fi

        # ── Decide whether to restart ──────────────────────────────────────────
        if [ "$crashes" -ge "$MAX_CRASHES" ]; then
            monitor_log "🚫 Reached MAX_CRASHES=$MAX_CRASHES — stopping supervisor"
            write_state "FAILED" 0 "$starts" "$crashes" "" \
                "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
                "Max crash limit reached — manual intervention required"
            rm -f "$MONITOR_PID_FILE"
            exit 1
        fi

        monitor_log "⏳ Waiting ${backoff}s before restart attempt…"
        write_state "RESTARTING" 0 "$starts" "$crashes" \
            "" "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
            "${LAST_CRASH_REASON:-crash}"
        sleep "$backoff"

        # Exponential backoff (cap at MAX_BACKOFF)
        backoff=$(( backoff * 2 ))
        [ "$backoff" -gt "$MAX_BACKOFF" ] && backoff=$MAX_BACKOFF

        # Restart
        starts=$(( starts + 1 ))
        monitor_log "♻️  Restart attempt #$starts"
        pid="$(launch_app)" || { monitor_log "❌ Launch failed"; continue; }
        write_state "STARTING" "$pid" "$starts" "$crashes" \
            "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" "" ""

        if wait_for_health; then
            write_state "RUNNING" "$pid" "$starts" "$crashes" \
                "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" "" ""
            backoff=$INITIAL_BACKOFF   # reset on successful start
        else
            monitor_log "❌ Restart failed health check"
        fi
    done
}

# ── Commands ──────────────────────────────────────────────────────────────────

cmd_start() {
    init_state
    read_state

    local existing_pid
    existing_pid="$(read_pid)"
    if process_alive "$existing_pid"; then
        echo -e "${YELLOW}⚠️  App already running (PID $existing_pid)${NC}"
        cmd_status; exit 0
    fi

    local monitor_pid
    monitor_pid="$(read_monitor_pid)"
    if process_alive "$monitor_pid"; then
        echo -e "${YELLOW}⚠️  Monitor already running (PID $monitor_pid)${NC}"
        cmd_status; exit 0
    fi

    mkdir -p "$SUP_DIR"
    echo "" >> "$APP_LOG"        # separator in log
    echo "" >> "$MONITOR_LOG"

    echo -e "${CYAN}${BOLD}▶ Starting AgentTeam supervisor…${NC}"
    echo -e "  App log:     ${BLUE}$APP_LOG${NC}"
    echo -e "  Monitor log: ${BLUE}$MONITOR_LOG${NC}"
    echo -e "  State file:  ${BLUE}$STATE_FILE${NC}"
    echo ""

    # Launch the monitor in background (it will launch the app)
    nohup bash "$0" _monitor >> "$MONITOR_LOG" 2>&1 &
    local sup_pid=$!
    echo "$sup_pid" > "$MONITOR_PID_FILE"

    echo -e "${GREEN}✅ Supervisor started (monitor PID $sup_pid)${NC}"
    echo -e "   Waiting for app to become healthy (up to ${STARTUP_WAIT}s)…"

    # Poll state until RUNNING or FAILED
    local waited=0
    while [ $waited -lt $(( STARTUP_WAIT + 10 )) ]; do
        sleep 3; waited=$(( waited + 3 ))
        read_state 2>/dev/null || true
        case "$STATUS" in
            RUNNING)
                echo -e "${GREEN}${BOLD}✅ App is UP${NC} — PID $PID"
                echo -e "   Health: ${HEALTH_URL}"
                break ;;
            CRASHED|FAILED)
                echo -e "${RED}❌ App failed to start — check $APP_LOG${NC}"
                exit 1 ;;
            *) printf "." ;;
        esac
    done
    echo ""
}

cmd_stop() {
    init_state
    echo -e "${CYAN}${BOLD}■ Stopping AgentTeam…${NC}"

    # Stop monitor first
    local monitor_pid
    monitor_pid="$(read_monitor_pid)"
    if process_alive "$monitor_pid"; then
        echo "  Stopping monitor (PID $monitor_pid)"
        kill -TERM "$monitor_pid" 2>/dev/null || true
        sleep 1
    fi
    rm -f "$MONITOR_PID_FILE"

    # Stop app
    local app_pid
    app_pid="$(read_pid)"
    if process_alive "$app_pid"; then
        echo "  Stopping app (PID $app_pid)"
        kill_app
    else
        echo "  App not running"
    fi

    read_state
    write_state "STOPPED" 0 "${START_COUNT:-0}" "${CRASH_COUNT:-0}" \
        "${STARTED_AT:-}" "${LAST_CRASH_AT:-}" "${LAST_CRASH_REASON:-}"

    echo -e "${GREEN}✅ Stopped${NC}"
}

cmd_restart() {
    cmd_stop
    sleep 2
    cmd_start
}

cmd_status() {
    init_state
    read_state

    local app_pid monitor_pid
    app_pid="$(read_pid)"
    monitor_pid="$(read_monitor_pid)"

    echo ""
    echo -e "${BOLD}═══ AgentTeam Supervisor Status ═══${NC}"
    echo ""

    # Status badge
    case "$STATUS" in
        RUNNING)   echo -e "  Status:    ${GREEN}${BOLD}● RUNNING${NC}" ;;
        STARTING)  echo -e "  Status:    ${YELLOW}${BOLD}◌ STARTING${NC}" ;;
        RESTARTING)echo -e "  Status:    ${YELLOW}${BOLD}↻ RESTARTING${NC}" ;;
        CRASHED)   echo -e "  Status:    ${RED}${BOLD}✗ CRASHED${NC}" ;;
        FAILED)    echo -e "  Status:    ${RED}${BOLD}✗✗ FAILED (manual restart needed)${NC}" ;;
        STOPPED)   echo -e "  Status:    ${BLUE}■ STOPPED${NC}" ;;
        UNHEALTHY) echo -e "  Status:    ${RED}${BOLD}⚠ UNHEALTHY${NC}" ;;
        *)         echo -e "  Status:    ${NC}$STATUS" ;;
    esac

    echo -e "  App PID:   $([ "$app_pid" -gt 0 ] 2>/dev/null && \
        (process_alive "$app_pid" && echo "${GREEN}$app_pid (alive)${NC}" || echo "${RED}$app_pid (dead)${NC}") \
        || echo '—')"
    echo -e "  Monitor:   $(process_alive "$monitor_pid" \
        && echo "${GREEN}$monitor_pid (running)${NC}" || echo "—")"
    echo ""
    echo -e "  Starts:    $START_COUNT  |  Crashes: ${CRASH_COUNT}/${MAX_CRASHES}"
    [ -n "$STARTED_AT" ]       && echo -e "  Started:   $STARTED_AT"
    [ -n "$LAST_CRASH_AT" ]    && echo -e "  Last crash: $LAST_CRASH_AT — $LAST_CRASH_REASON"
    echo -e "  Updated:   ${UPDATED_AT:-—}"
    echo ""
    echo -e "  Health:    ${CYAN}$HEALTH_URL${NC}"
    # Live health check
    if check_health 2>/dev/null; then
        echo -e "  Live ping: ${GREEN}✅ UP${NC}"
    else
        echo -e "  Live ping: ${RED}❌ DOWN / unreachable${NC}"
    fi
    echo ""
}

cmd_logs() {
    echo -e "${CYAN}Tailing app log — Ctrl-C to exit${NC}"
    echo -e "${BLUE}$APP_LOG${NC}"
    echo ""
    tail -f "$APP_LOG"
}

cmd_build() {
    build_jar
}

# ── Entry point ───────────────────────────────────────────────────────────────

CMD="${1:-status}"

case "$CMD" in
    start)    cmd_start ;;
    stop)     cmd_stop ;;
    restart)  cmd_restart ;;
    status)   cmd_status ;;
    logs)     cmd_logs ;;
    build)    cmd_build ;;
    _monitor) run_monitor ;;   # internal: called by nohup in cmd_start
    *)
        echo "Usage: $0 {start|stop|restart|status|logs|build}"
        exit 1
        ;;
esac
