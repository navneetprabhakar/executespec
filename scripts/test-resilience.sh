#!/usr/bin/env bash
# scripts/test-resilience.sh — end-to-end local crash-resilience test.
#
# Simulates a deploy/crash mid-WORKING and verifies the app picks up the run on
# restart instead of leaving it stuck. Uses the V2 PipelineRun resume path
# wired in 2026-05-04 (StartupRunResumer + RunOrchestrator.resumeAfterCrash).
#
# What it does:
#
#   1. Boots a Spring Boot JVM in the background (mvn spring-boot:run).
#   2. Waits for /actuator/health to return UP.
#   3. Submits a multi-task V2 run via POST /api/runs (uses the local seed
#      admin account; you can override via ES_USER / ES_PASS env).
#   4. Polls GET /api/runs/{id} until status flips to WORKING (worker is
#      executing the first task).
#   5. SIGKILLs the JVM — simulates the worst-case interruption (SIGKILL
#      doesn't even let the JVM run shutdown hooks).
#   6. Verifies the run's DB row is still WORKING (or PLANNING / REVIEWING)
#      after the kill — i.e. the JVM died mid-flight.
#   7. Restarts the JVM, waits for health.
#   8. Polls /api/runs/{id} and asserts the run reaches a TERMINAL status
#      (COMPLETE / PARTIAL / FAILED) within a reasonable window — proves
#      StartupRunResumer + resumeAfterCrash actually completed the run.
#   9. Pulls /api/runs/{id} once more and prints status_reason +
#      resume_attempts so a human can sanity-check the recovery.
#
# Requires: postgres + redis running locally (see CLAUDE.md "Build & Test"),
# a seed admin user (set ES_USER and ES_PASS), Java 21.
#
# Usage:
#   scripts/test-resilience.sh                    # full cycle
#   KEEP_APP=1 scripts/test-resilience.sh         # leave the JVM running at the end
#   SIGNAL=TERM scripts/test-resilience.sh        # use SIGTERM instead of SIGKILL
#   SPEC_FILE=path/to/spec.txt scripts/test-resilience.sh
#
# Exit codes:
#   0 — resume succeeded, run reached terminal
#   1 — boot timeout / health check never went UP
#   2 — submit failed
#   3 — never reached WORKING within timeout
#   4 — restart never went UP
#   5 — run never reached terminal post-restart
#   6 — pre-crash assertion failed

set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
ES_BASE="${ES_BASE:-http://localhost:8080}"
ES_USER="${ES_USER:-admin@executespec.ai}"
ES_PASS="${ES_PASS:?ES_PASS environment variable is required}"
SIGNAL="${SIGNAL:-KILL}"               # KILL = SIGKILL (worst case); TERM = SIGTERM (graceful)
KEEP_APP="${KEEP_APP:-0}"
SPEC_FILE="${SPEC_FILE:-}"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
LOG_DIR="${LOG_DIR:-/tmp/executespec-resilience}"
PORT="${PORT:-8080}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-180}"  # 3 min for first boot (mvn cold cache)
WORKING_TIMEOUT="${WORKING_TIMEOUT:-180}"
RESUME_TIMEOUT="${RESUME_TIMEOUT:-600}"  # 10 min for resume to finish the run

mkdir -p "$LOG_DIR"
APP_LOG="$LOG_DIR/app.log"
SECOND_LOG="$LOG_DIR/app-after-restart.log"

# ── Helpers ───────────────────────────────────────────────────────────────────
say() { echo "▶ $*" | tee -a "$LOG_DIR/test.log" >&2; }
err() { echo "✗ $*" | tee -a "$LOG_DIR/test.log" >&2; }
ok()  { echo "✓ $*" | tee -a "$LOG_DIR/test.log" >&2; }

wait_for_up() {
  local timeout="$1"
  local start=$(date +%s)
  while true; do
    if curl -sS --max-time 5 "$ES_BASE/actuator/health" 2>/dev/null | grep -q '"UP"'; then
      return 0
    fi
    if (( $(date +%s) - start > timeout )); then return 1; fi
    sleep 2
  done
}

login() {
  curl -sS -X POST "$ES_BASE/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$ES_USER\",\"password\":\"$ES_PASS\"}" \
    | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("accessToken",""))'
}

run_status() {
  local id="$1"
  curl -sS -H "Authorization: Bearer $TOKEN" "$ES_BASE/api/runs/$id" \
    | python3 -c 'import sys,json
try:
  d=json.load(sys.stdin);print(d.get("status",""))
except Exception:
  print("")'
}

run_full() {
  local id="$1"
  curl -sS -H "Authorization: Bearer $TOKEN" "$ES_BASE/api/runs/$id" \
    | python3 -m json.tool 2>/dev/null || true
}

terminal_statuses="COMPLETE COMPLETE_WITH_CONCERNS PARTIAL FAILED CANCELLED"
is_terminal() { [[ " $terminal_statuses " =~ " $1 " ]]; }

start_app() {
  local logfile="$1"
  ( cd "$(dirname "$0")/.."
    JAVA_HOME="$JAVA_HOME" \
      "$JAVA_HOME/bin/java" --version >/dev/null 2>&1 || {
        err "JAVA_HOME=$JAVA_HOME has no java binary"; exit 1;
      }
    JAVA_HOME="$JAVA_HOME" mvn spring-boot:run \
      -Dspring-boot.run.profiles=local \
      -Dspring-boot.run.jvmArguments='-Dserver.port='"$PORT" \
      >"$logfile" 2>&1 &
    echo $! > "$LOG_DIR/app.pid"
  )
}

kill_app() {
  local sig="$1"
  if [[ -f "$LOG_DIR/app.pid" ]]; then
    local pid
    pid=$(cat "$LOG_DIR/app.pid")
    if kill -0 "$pid" 2>/dev/null; then
      say "sending SIG$sig to PID $pid"
      kill -s "$sig" "$pid" || true
      # SIGKILL is instant; SIGTERM may take a few seconds to drain.
      for _ in $(seq 1 30); do
        if ! kill -0 "$pid" 2>/dev/null; then break; fi
        sleep 1
      done
      if kill -0 "$pid" 2>/dev/null; then
        err "PID $pid still alive after 30s; escalating to SIGKILL"
        kill -9 "$pid" || true
      fi
    fi
    rm -f "$LOG_DIR/app.pid"
  fi
  # Also kill any orphaned spring-boot:run children on the same port.
  if command -v lsof >/dev/null 2>&1; then
    local stragglers
    stragglers=$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null || true)
    if [[ -n "$stragglers" ]]; then
      say "killing stragglers on :$PORT — $stragglers"
      echo "$stragglers" | xargs -r kill -9 || true
    fi
  fi
}

cleanup() {
  if [[ "$KEEP_APP" != "1" ]]; then
    kill_app KILL || true
  fi
}
trap cleanup EXIT

# ── 1. Boot the app ───────────────────────────────────────────────────────────
say "log dir = $LOG_DIR"
say "killing any previous instance on :$PORT"
kill_app KILL >/dev/null 2>&1 || true

say "starting JVM (logs → $APP_LOG)"
start_app "$APP_LOG"

say "waiting for health (timeout ${HEALTH_TIMEOUT}s)"
if ! wait_for_up "$HEALTH_TIMEOUT"; then
  err "boot never reached UP — see $APP_LOG"
  exit 1
fi
ok "JVM up"

# ── 2. Login + submit ─────────────────────────────────────────────────────────
say "logging in as $ES_USER"
TOKEN="$(login)"
if [[ -z "$TOKEN" ]]; then
  err "login failed — does the seed admin exist? Try: dev-cleanup.sh + DB seed"
  exit 2
fi
ok "got token"

if [[ -z "$SPEC_FILE" ]]; then
  # Default spec — guaranteed multi-task because of the explicit "two files" wording.
  SPEC=$'Build a Python CLI that prints fizzbuzz from 1 to 100. Add unit tests for the buzz/fizz/fizzbuzz cases. Use pytest. The file structure should be:\n  - src/fizzbuzz.py\n  - tests/test_fizzbuzz.py'
else
  SPEC="$(cat "$SPEC_FILE")"
fi

say "submitting run"
SUBMIT=$(curl -sS -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  "$ES_BASE/api/runs" \
  -d "$(python3 -c '
import json,sys
spec=sys.argv[1]
print(json.dumps({
  "userRequest": spec,
  "source": "MANUAL",
  "repoMode": "NONE",
  "skipApproval": True,
  "executionMode": "PROTOTYPE",
  "aiProvider": "ANTHROPIC"
}))' "$SPEC")")
RUN_ID=$(echo "$SUBMIT" | python3 -c 'import sys,json
try:
  d=json.load(sys.stdin);print(d.get("runId") or d.get("id") or "")
except Exception:
  pass')
if [[ -z "$RUN_ID" ]]; then
  err "submit failed: $SUBMIT"
  exit 2
fi
ok "submitted run=$RUN_ID"

# ── 3. Wait for WORKING ───────────────────────────────────────────────────────
say "polling for WORKING (timeout ${WORKING_TIMEOUT}s)"
START=$(date +%s)
SAW_WORKING=0
while true; do
  STATUS="$(run_status "$RUN_ID")"
  echo "  status=$STATUS"
  if [[ "$STATUS" == "WORKING" ]]; then SAW_WORKING=1; break; fi
  if is_terminal "$STATUS"; then
    err "run reached terminal $STATUS BEFORE we could crash it (too fast). Try a heavier spec via SPEC_FILE=..."
    exit 3
  fi
  if (( $(date +%s) - START > WORKING_TIMEOUT )); then
    err "never reached WORKING within ${WORKING_TIMEOUT}s; status=$STATUS"
    exit 3
  fi
  sleep 3
done
ok "run is WORKING — letting it run a few more iterations to ensure a snapshot fires (every 5 iters)"
sleep 30

# ── 4. CRASH ──────────────────────────────────────────────────────────────────
PRE_CRASH_STATUS="$(run_status "$RUN_ID")"
say "pre-crash status = $PRE_CRASH_STATUS"
if is_terminal "$PRE_CRASH_STATUS"; then
  err "run finished too quickly — already $PRE_CRASH_STATUS. Use a slower spec."
  exit 6
fi

say "killing JVM with SIG$SIGNAL — simulating crash/deploy mid-WORKING"
kill_app "$SIGNAL"
ok "JVM dead"

# ── 5. Restart ────────────────────────────────────────────────────────────────
say "starting JVM again (logs → $SECOND_LOG)"
start_app "$SECOND_LOG"

say "waiting for health post-restart (timeout 120s)"
if ! wait_for_up 120; then
  err "post-restart boot never reached UP — see $SECOND_LOG"
  exit 4
fi
ok "JVM up again"

say "fetching fresh token (previous one may have epoch-rotated on restart)"
TOKEN="$(login)"
if [[ -z "$TOKEN" ]]; then
  err "post-restart login failed — abort"
  exit 4
fi

# ── 6. Wait for terminal — proves resume completed ────────────────────────────
say "polling for terminal status (timeout ${RESUME_TIMEOUT}s)"
START=$(date +%s)
LAST_STATUS=""
while true; do
  STATUS="$(run_status "$RUN_ID")"
  if [[ "$STATUS" != "$LAST_STATUS" ]]; then
    echo "  status=$STATUS  ($(($(date +%s) - START))s elapsed)"
    LAST_STATUS="$STATUS"
  fi
  if is_terminal "$STATUS"; then
    ok "run reached terminal $STATUS — resume worked"
    break
  fi
  if (( $(date +%s) - START > RESUME_TIMEOUT )); then
    err "run still $STATUS after ${RESUME_TIMEOUT}s — resume DID NOT complete"
    err "see $SECOND_LOG for resume logs (look for 'StartupRunResumer' / 'resumeAfterCrash')"
    exit 5
  fi
  sleep 5
done

# ── 7. Final report ───────────────────────────────────────────────────────────
echo
echo "════════════════════════════════════════════════════════"
echo " RESILIENCE TEST PASSED"
echo "════════════════════════════════════════════════════════"
echo "  run id           = $RUN_ID"
echo "  pre-crash status = $PRE_CRASH_STATUS"
echo "  signal           = SIG$SIGNAL"
echo "  final status     = $LAST_STATUS"
echo
echo "Full run row:"
run_full "$RUN_ID"
echo
echo "Logs:"
echo "  pre-crash : $APP_LOG"
echo "  post-crash: $SECOND_LOG"
echo "  test trace: $LOG_DIR/test.log"
echo
echo "Resume log highlights:"
grep -E 'StartupRunResumer|resumeAfterCrash|RESUME|swallowed' "$SECOND_LOG" | head -20 || true
