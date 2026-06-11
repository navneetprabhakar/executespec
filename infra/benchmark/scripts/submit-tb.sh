#!/bin/bash
# submit-tb.sh — submit ONE completed Terminal-Bench job to the public leaderboard via
# `harbor upload`, then reflect the submission on the in-app /benchmark dashboard
# (SUBMISSION_STARTED → SUBMITTED{url} | SUBMISSION_FAILED, so submissionStatus + leaderboardUrl
# show in-login). Run AFTER a model's run-tb.sh finishes and you've validated its dashboard.
#
# Prereq: `harbor auth login` done once on this box (GitHub OAuth device flow — ~/.harbor/credentials.json
# must be populated; an empty {} means NOT authed and upload will fail).
#
# Usage:
#   JOB_NAME=es-tb-flash-k5 RUN_ID=<benchmark_run id> bash submit-tb.sh
#   (RUN_ID optional — if omitted, read from the bridge log "benchmark_run id = …".)
#
# Env overrides: JOBS_DIR TB_DIR REPO ES_BASE ADMIN_ENV HARBOR HARBOR_UPLOAD_ARGS DRY_RUN
# REQUIRE_RUN_ID=1 makes UI linkage mandatory: if RUN_ID cannot be resolved from env or the
# bridge log, the script exits before uploading instead of creating an unlinked Harbor submission.
set -uo pipefail

JOB_NAME="${JOB_NAME:?set JOB_NAME (the harbor job_name)}"
JOBS_DIR="${JOBS_DIR:-$HOME/es-tb-jobs}"
TB_DIR="${TB_DIR:-$HOME/tbench}"
REPO="${REPO:-$HOME/executespec}"
ES_BASE="${ES_BASE:-http://localhost:8080}"
ADMIN_ENV="${ADMIN_ENV:-$HOME/swebench-bench/.espec-admin.env}"
HARBOR="${HARBOR:-$TB_DIR/.venv/bin/harbor}"
JOB_DIR="$JOBS_DIR/$JOB_NAME"
# `harbor upload <job-dir> --public` is the known shape; verify with `harbor upload --help`
# on the box first and override HARBOR_UPLOAD_ARGS if the flags differ for 0.13.1.
HARBOR_UPLOAD_ARGS="${HARBOR_UPLOAD_ARGS:-upload $JOB_DIR --public}"
LOG(){ echo "$(date '+%H:%M:%S') [submit-tb] $*"; }

[ -d "$JOB_DIR" ] || { LOG "FATAL: job dir not found: $JOB_DIR"; exit 1; }

# ── auth token for the in-app event API ─────────────────────────────────────
[ -f "$ADMIN_ENV" ] && . "$ADMIN_ENV"
TK=$(curl -s -X POST "$ES_BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"${ESPEC_ADMIN_EMAIL:-}\",\"password\":\"${ESPEC_ADMIN_PW:-}\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('accessToken',''))" 2>/dev/null)

# ── resolve the benchmark_run id (for the dashboard event) ───────────────────
RUN_ID="${RUN_ID:-}"
if [ -z "$RUN_ID" ] && [ -f "$JOBS_DIR/${JOB_NAME}-bridge.log" ]; then
  RUN_ID=$(grep -oE 'benchmark_run id = [a-f0-9-]+' "$JOBS_DIR/${JOB_NAME}-bridge.log" | tail -1 | awk '{print $NF}')
fi
[ -n "$RUN_ID" ] && LOG "in-app benchmark_run = $RUN_ID" || LOG "no RUN_ID — dashboard submission state will be skipped"
if [ "${REQUIRE_RUN_ID:-0}" = "1" ] && [ -z "$RUN_ID" ]; then
  LOG "FATAL: RUN_ID is required but was not provided and could not be read from $JOBS_DIR/${JOB_NAME}-bridge.log"
  exit 2
fi

post_event(){ # $1=json-body  (best-effort; needs TK + RUN_ID)
  [ -z "$RUN_ID" ] || [ -z "$TK" ] && return 0
  curl -s -X POST "$ES_BASE/api/v2/test/benchmark/$RUN_ID/event" \
    -H "Authorization: Bearer $TK" -H 'Content-Type: application/json' -d "$1" >/dev/null 2>&1
}

# ── 1. mark SUBMITTING on the dashboard ─────────────────────────────────────
post_event '{"type":"SUBMISSION_STARTED","message":"harbor upload --public"}'

if [ "${DRY_RUN:-}" = "1" ]; then
  LOG "DRY_RUN — would run: $HARBOR $HARBOR_UPLOAD_ARGS"; exit 0
fi

# ── 2. the upload ───────────────────────────────────────────────────────────
LOG "uploading: $HARBOR $HARBOR_UPLOAD_ARGS"
OUT="$JOBS_DIR/${JOB_NAME}-submit.log"
# shellcheck disable=SC2086
"$HARBOR" $HARBOR_UPLOAD_ARGS 2>&1 | tee "$OUT"
RC=${PIPESTATUS[0]}

# ── 3. reflect the result ───────────────────────────────────────────────────
URL=$(grep -oE 'https://[^ ]*(leaderboard|terminal-bench|tbench)[^ ]*' "$OUT" | head -1)
[ -z "$URL" ] && URL=$(grep -oE 'https://[^ ]+' "$OUT" | head -1)
if [ "$RC" -eq 0 ]; then
  LOG "✓ uploaded. leaderboard: ${URL:-<no url in output — check $OUT>}"
  post_event "{\"type\":\"SUBMITTED\",\"message\":\"leaderboard\",\"payload\":\"{\\\"url\\\":\\\"${URL:-}\\\"}\"}"
  [ -f "$TB_DIR/tg-send.py" ] && python3 "$TB_DIR/tg-send.py" "🏆 TB submitted — $JOB_NAME ${URL:+→ $URL}" >/dev/null 2>&1
else
  LOG "✗ upload failed (rc=$RC) — see $OUT"
  post_event '{"type":"SUBMISSION_FAILED","message":"harbor upload failed — see submit log"}'
  exit "$RC"
fi
