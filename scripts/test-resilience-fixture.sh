#!/usr/bin/env bash
# Provider-free JVM restart proof for the local/dev web fixture.
#
# This intentionally uses a separate HTTP port and a deterministic SUPER_ADMIN fixture.  The
# fixture's RECOVERY transition leaves a WORKING run with terminal task evidence; on the next JVM
# boot StartupRunResumer must salvage it to COMPLETE without dispatching an LLM/provider call.  The
# same fixture's AWAITING_APPROVAL row must remain a user-facing gate across the restart.
#
# Requires local PostgreSQL/Redis and an admin credential.  It never calls a provider.  It cleans
# all web-qa rows at the end.  Use a dedicated local profile/port, never a shared dev server.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE="${BASE:-http://localhost:8097}"
PORT="${PORT:-8097}"
USER_EMAIL="${USER_EMAIL:-admin@executespec.ai}"
USER_PASSWORD="${USER_PASSWORD:?USER_PASSWORD is required}"
LOG_DIR="${LOG_DIR:-/tmp/executespec-resilience-fixture}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-180}"
mkdir -p "$LOG_DIR"

APP_PID=""
TOKEN=""
say() { echo "[fixture-resilience] $*"; }
fail() { echo "[fixture-resilience] FAIL: $*" >&2; exit 1; }

wait_up() {
  local started now
  started=$(date +%s)
  while true; do
    if curl -fsS --max-time 4 "$BASE/actuator/health" | grep -q '"UP"'; then return 0; fi
    now=$(date +%s)
    (( now - started > BOOT_TIMEOUT )) && return 1
    sleep 2
  done
}

start_app() {
  local log="$1"
  (cd "$ROOT" && \
    JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}" \
      mvn -q spring-boot:run \
        -Dspring-boot.run.profiles=local \
        -Dspring-boot.run.jvmArguments="-Dserver.port=$PORT -Dagent.workspace=$LOG_DIR/workspace" \
        >"$log" 2>&1) &
  APP_PID=$!
}

stop_app() {
  if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then kill -9 "$APP_PID" 2>/dev/null || true; fi
  if command -v lsof >/dev/null 2>&1; then
    lsof -tiTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | xargs -r kill -9 || true
  fi
}
cleanup() {
  if [[ -n "$TOKEN" ]]; then
    curl -fsS -X DELETE "$BASE/api/v2/test/web-fixture" \
      -H "Authorization: Bearer $TOKEN" >/dev/null 2>&1 || true
  fi
  stop_app
}
trap cleanup EXIT

say "starting isolated local JVM on $BASE"
start_app "$LOG_DIR/boot-1.log"
wait_up || fail "first JVM did not become healthy; see $LOG_DIR/boot-1.log"

TOKEN=$(curl -fsS -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$USER_EMAIL\",\"password\":\"$USER_PASSWORD\"}" |
  python3 -c 'import json,sys; print(json.load(sys.stdin).get("accessToken", ""))')
[[ -n "$TOKEN" ]] || fail "login failed"

FIXTURE=$(curl -fsS -X POST "$BASE/api/v2/test/web-fixture" -H "Authorization: Bearer $TOKEN")
GATE_ID=$(echo "$FIXTURE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["gateRunId"])')
RECOVERY_ID=$(echo "$FIXTURE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["recoveryRunId"])')
say "seeded plan gate $GATE_ID"

# The recovery fixture is deterministic: no provider call, task is already terminal, run remains
# WORKING.  The separate gate row stays AWAITING_APPROVAL throughout this restart.
say "armed provider-free WORKING recovery row; killing first JVM"
stop_app
APP_PID=""

start_app "$LOG_DIR/boot-2.log"
wait_up || fail "restarted JVM did not become healthy; see $LOG_DIR/boot-2.log"
TOKEN=$(curl -fsS -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$USER_EMAIL\",\"password\":\"$USER_PASSWORD\"}" |
  python3 -c 'import json,sys; print(json.load(sys.stdin).get("accessToken", ""))')

RECOVERY_STATUS=""
GATE_STATUS=""
for _ in $(seq 1 30); do
  ROW=$(curl -fsS "$BASE/api/runs/$RECOVERY_ID" -H "Authorization: Bearer $TOKEN")
  RECOVERY_STATUS=$(echo "$ROW" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("status", ""))')
  [[ "$RECOVERY_STATUS" == "COMPLETE" || "$RECOVERY_STATUS" == "PARTIAL" ]] && break
  sleep 2
done
[[ "$RECOVERY_STATUS" == "COMPLETE" || "$RECOVERY_STATUS" == "PARTIAL" ]] || \
  fail "boot did not salvage recovery row; status=$RECOVERY_STATUS"

# The fixture seed has a separate gate row. Find it by title and assert restart left it pending.
ROWS=$(curl -fsS "$BASE/api/runs?size=50" -H "Authorization: Bearer $TOKEN")
GATE_STATUS=$(echo "$ROWS" | python3 -c '
import json,sys
d=json.load(sys.stdin); rows=d.get("content", d if isinstance(d,list) else [])
for r in rows:
  if r.get("runTitle") == "Release QA approval gate": print(r.get("status", "")); break
')
[[ "$GATE_STATUS" == "AWAITING_APPROVAL" ]] || fail "plan gate changed across restart: $GATE_STATUS"

say "PASS: provider-free restart recovery=$RECOVERY_STATUS; plan-gate=$GATE_STATUS"
say "logs: $LOG_DIR/boot-1.log $LOG_DIR/boot-2.log"
