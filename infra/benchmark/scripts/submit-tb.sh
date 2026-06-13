#!/bin/bash
# submit-tb.sh — upload ONE completed Terminal-Bench job to Harbor Hub, submit it to the
# Terminal-Bench leaderboard, then reflect the submission on the in-app /benchmark dashboard
# (SUBMISSION_STARTED → SUBMITTED{url} | SUBMISSION_FAILED, so submissionStatus + leaderboardUrl
# show in-login). Run AFTER a model's run-tb.sh finishes and you've validated its dashboard.
#
# Prereq: `harbor auth login` done once on this box (GitHub OAuth device flow — ~/.harbor/credentials.json
# must be populated; an empty {} means NOT authed and upload will fail).
#
# Usage:
#   JOB_NAME=es-tb-flash-k5 RUN_ID=<benchmark_run id> \
#   METADATA_FILE=~/es-tb-jobs/metadata-flash-tb21.yaml bash submit-tb.sh
#   (RUN_ID optional — if omitted, read from the bridge log "benchmark_run id = …".)
#
# Env overrides: JOBS_DIR TB_DIR REPO ES_BASE ADMIN_ENV HARBOR HARBOR_UPLOAD_ARGS
#                LEADERBOARD_SLUG METADATA_FILE SUBMIT_LEADERBOARD REQUIRE_ATIF
#                REQUIRE_RUN_ID DRY_RUN
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
LEADERBOARD_SLUG="${LEADERBOARD_SLUG:-terminal-bench-2-1}"
METADATA_FILE="${METADATA_FILE:-}"
SUBMIT_LEADERBOARD="${SUBMIT_LEADERBOARD:-1}"
REQUIRE_ATIF="${REQUIRE_ATIF:-1}"
REQUIRE_RUN_ID="${REQUIRE_RUN_ID:-0}"
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
[ "$REQUIRE_RUN_ID" != "1" ] || [ -n "$RUN_ID" ] || { LOG "FATAL: REQUIRE_RUN_ID=1 but no RUN_ID was found"; exit 1; }

post_event(){ # $1=json-body  (best-effort; needs TK + RUN_ID)
  [ -z "$RUN_ID" ] || [ -z "$TK" ] && return 0
  curl -s -X POST "$ES_BASE/api/v2/test/benchmark/$RUN_ID/event" \
    -H "Authorization: Bearer $TK" -H 'Content-Type: application/json' -d "$1" >/dev/null 2>&1
}

# ── 1. fail fast if Harbor leaderboard validation would reject trajectories ──
if [ "$REQUIRE_ATIF" = "1" ]; then
  python3 - "$JOB_DIR" <<'PY'
import json
import sys
from pathlib import Path

job_dir = Path(sys.argv[1])
results = sorted(p for p in job_dir.rglob("result.json") if p.parent != job_dir)
if not results:
    print(f"FATAL: no result.json files found under {job_dir}", file=sys.stderr)
    sys.exit(2)

passing = 0
missing = []
for result_file in results:
    try:
        data = json.loads(result_file.read_text())
    except Exception as exc:
        print(f"FATAL: cannot parse {result_file}: {exc}", file=sys.stderr)
        sys.exit(2)
    verifier = data.get("verifier_result") or {}
    rewards = verifier.get("rewards") or {}
    reward = (
        rewards.get("reward")
        if isinstance(rewards, dict) else None
    )
    if reward is None:
        reward = verifier.get("reward", data.get("reward", 0))
    try:
        reward_value = float(reward or 0)
    except Exception:
        reward_value = 0.0
    if reward_value <= 0:
        continue
    passing += 1
    trajectory = result_file.parent / "agent" / "trajectory.json"
    if not trajectory.is_file():
        missing.append(str(result_file.parent.relative_to(job_dir)))

print(f"ATIF preflight: {len(results)} trials, {passing} passing, {len(missing)} missing passing trajectories")
if missing:
    print("FATAL: passing trials missing agent/trajectory.json:", file=sys.stderr)
    for item in missing[:30]:
        print(f"  - {item}", file=sys.stderr)
    if len(missing) > 30:
        print(f"  ... {len(missing) - 30} more", file=sys.stderr)
    sys.exit(2)
PY
  RC=$?
  [ "$RC" -eq 0 ] || { post_event '{"type":"SUBMISSION_FAILED","message":"ATIF preflight failed — passing trials missing trajectory.json"}'; exit "$RC"; }
  HARBOR_PY="${HARBOR%/harbor}/python"
  if [ -x "$HARBOR_PY" ]; then
    "$HARBOR_PY" - "$JOB_DIR" <<'PY'
import json
import sys
from pathlib import Path

from harbor.utils.trajectory_validator import TrajectoryValidator

job_dir = Path(sys.argv[1])
checked = 0
failures = []
for result_file in sorted(p for p in job_dir.rglob("result.json") if p.parent != job_dir):
    data = json.loads(result_file.read_text())
    verifier = data.get("verifier_result") or {}
    rewards = verifier.get("rewards") or {}
    reward = rewards.get("reward") if isinstance(rewards, dict) else None
    if reward is None:
        reward = verifier.get("reward", data.get("reward", 0))
    try:
        reward_value = float(reward or 0)
    except Exception:
        reward_value = 0.0
    if reward_value <= 0:
        continue
    trajectory = result_file.parent / "agent" / "trajectory.json"
    validator = TrajectoryValidator()
    if not validator.validate(trajectory):
        failures.append((str(trajectory.relative_to(job_dir)), validator.get_errors()))
    checked += 1

print(f"ATIF schema preflight: {checked} passing trajectories validated")
if failures:
    print("FATAL: invalid ATIF trajectories:", file=sys.stderr)
    for path, errors in failures[:10]:
        print(f"  - {path}", file=sys.stderr)
        for error in errors[:5]:
            print(f"      {error}", file=sys.stderr)
    if len(failures) > 10:
        print(f"  ... {len(failures) - 10} more invalid trajectories", file=sys.stderr)
    sys.exit(2)
PY
    RC=$?
    [ "$RC" -eq 0 ] || { post_event '{"type":"SUBMISSION_FAILED","message":"ATIF schema validation failed — see submit log"}'; exit "$RC"; }
  else
    LOG "ATIF schema validation skipped (Harbor venv python not found at $HARBOR_PY)"
  fi
else
  LOG "ATIF preflight skipped (REQUIRE_ATIF=0)"
fi

if [ "$SUBMIT_LEADERBOARD" = "1" ] && [ -z "$METADATA_FILE" ]; then
  LOG "FATAL: SUBMIT_LEADERBOARD=1 requires METADATA_FILE=<metadata yaml>"
  post_event '{"type":"SUBMISSION_FAILED","message":"metadata yaml missing for leaderboard submit"}'
  exit 1
fi
[ -z "$METADATA_FILE" ] || [ -f "$METADATA_FILE" ] || { LOG "FATAL: metadata file not found: $METADATA_FILE"; exit 1; }

# ── 2. mark SUBMITTING on the dashboard ─────────────────────────────────────
post_event '{"type":"SUBMISSION_STARTED","message":"harbor upload --public + leaderboard submit"}'

if [ "${DRY_RUN:-}" = "1" ]; then
  LOG "DRY_RUN — would run: $HARBOR $HARBOR_UPLOAD_ARGS"
  [ "$SUBMIT_LEADERBOARD" = "1" ] && LOG "DRY_RUN — would run: $HARBOR leaderboard submit -l $LEADERBOARD_SLUG -j <uploaded-job-id> -m $METADATA_FILE"
  exit 0
fi

# ── 3. public Harbor upload ─────────────────────────────────────────────────
LOG "uploading: $HARBOR $HARBOR_UPLOAD_ARGS"
OUT="$JOBS_DIR/${JOB_NAME}-submit.log"
# shellcheck disable=SC2086
"$HARBOR" $HARBOR_UPLOAD_ARGS 2>&1 | tee "$OUT"
RC=${PIPESTATUS[0]}

# ── 4. reflect/upload or submit the result ──────────────────────────────────
URL=$(grep -oE 'https://[^ ]*(hub\.harborframework\.com/jobs|leaderboard|terminal-bench|tbench)[^ ]*' "$OUT" | head -1)
[ -z "$URL" ] && URL=$(grep -oE 'https://[^ ]+' "$OUT" | head -1)
if [ "$RC" -ne 0 ]; then
  LOG "✗ upload failed (rc=$RC) — see $OUT"
  post_event '{"type":"SUBMISSION_FAILED","message":"harbor upload failed — see submit log"}'
  exit "$RC"
fi

LOG "uploaded public Harbor job: ${URL:-<no url in output — check $OUT>}"
JOB_ID=$(grep -oE '/jobs/[0-9a-f-]+' "$OUT" | head -1 | cut -d/ -f3)
[ -z "$JOB_ID" ] && JOB_ID=$(grep -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' "$OUT" | head -1)

if [ "$SUBMIT_LEADERBOARD" = "1" ]; then
  [ -n "$JOB_ID" ] || { LOG "FATAL: uploaded but could not parse Harbor job id from $OUT"; exit 1; }
  LB_OUT="$JOBS_DIR/${JOB_NAME}-leaderboard.log"
  LOG "submitting leaderboard: $HARBOR leaderboard submit -l $LEADERBOARD_SLUG -j $JOB_ID -m $METADATA_FILE"
  "$HARBOR" leaderboard submit -l "$LEADERBOARD_SLUG" -j "$JOB_ID" -m "$METADATA_FILE" 2>&1 | tee "$LB_OUT"
  RC=${PIPESTATUS[0]}
  if [ "$RC" -ne 0 ]; then
    LOG "✗ leaderboard submit failed (rc=$RC) — public upload remains at ${URL:-$JOB_ID}; see $LB_OUT"
    post_event '{"type":"SUBMISSION_FAILED","message":"leaderboard submit failed — public upload exists; see leaderboard log"}'
    exit "$RC"
  fi
  LOG "leaderboard submission accepted/pending for $JOB_ID"
  post_event "{\"type\":\"SUBMITTED\",\"message\":\"leaderboard pending\",\"payload\":\"{\\\"url\\\":\\\"${URL:-}\\\",\\\"harborJobId\\\":\\\"$JOB_ID\\\",\\\"leaderboard\\\":\\\"$LEADERBOARD_SLUG\\\"}\"}"
  [ -f "$TB_DIR/tg-send.py" ] && python3 "$TB_DIR/tg-send.py" "TB leaderboard submitted: $JOB_NAME | $LEADERBOARD_SLUG | ${URL:-$JOB_ID}" >/dev/null 2>&1
else
  LOG "leaderboard submit skipped (SUBMIT_LEADERBOARD=0)"
  post_event "{\"type\":\"SUBMITTED\",\"message\":\"public harbor upload\",\"payload\":\"{\\\"url\\\":\\\"${URL:-}\\\",\\\"harborJobId\\\":\\\"${JOB_ID:-}\\\"}\"}"
  [ -f "$TB_DIR/tg-send.py" ] && python3 "$TB_DIR/tg-send.py" "TB public Harbor upload: $JOB_NAME | ${URL:-$JOB_ID}" >/dev/null 2>&1
fi
