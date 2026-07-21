#!/bin/bash
# run-tb-official.sh — OFFICIAL Terminal-Bench leaderboard campaign runner.
#
# This is deliberately a SEPARATE script from run-tb.sh. run-tb.sh remains the
# internal K=1 runner and keeps its refusal safeguards; nothing here weakens it.
#
# What this runner enforces before Harbor is allowed to spend a single token:
#   • K >= 5                       (internal K=1 is structurally unsubmittable)
#   • official Harbor agent timeout (silent drift is refused; an override needs
#                                    an explicit published-rule reference)
#   • harbor run --upload           (a local-only run is not a submission)
#   • frozen Harbor/dataset/agent/config/container versions, all recorded
#   • trajectory presence for every passing trial + Harbor's own ATIF validator
#   • an immutable, checksummed submission manifest sealed after the run
#
# HONESTY: every gate below is a refusal gate. Passing them does not mean the
# run is correct, that Harbor behaved, or that the leaderboard will accept it.
# The official verifier owns the verdict — never this script, never the
# ExecuteSpec /benchmark dashboard.
#
# Required env (no defaults — an unset value is a refusal, not a guess):
#   K HARBOR_VERSION DATASET_VERSION AGENT_VERSION CONTAINER_IMAGE_DIGEST
#   METADATA_FILE
# Optional: JOBS_DIR TB_DIR REPO GCS_BUCKET CONCURRENCY TB_DATASET
#           LEADERBOARD_SLUG EXPECTED_TASK_COUNT
#           TIMEOUT_OVERRIDE_ALLOWED TIMEOUT_OVERRIDE_RULE_REFERENCE
#           OFFICIAL_DRY_RUN=1  (validate + config only; never starts Harbor)
set -uo pipefail

REPO="${REPO:-$HOME/Documents/antigravity/executespec}"
SCRIPTS="$REPO/infra/benchmark/scripts"
TB_DIR="${TB_DIR:-$HOME/tbench}"
JOBS_DIR="${JOBS_DIR:-$HOME/es-tb-jobs}"
TB_DATASET="${TB_DATASET:-terminal-bench/terminal-bench-2-1}"
LEADERBOARD_SLUG="${LEADERBOARD_SLUG:-terminal-bench-2-1}"
PROVIDER="${ES_PROVIDER:-deepseek}"
MODEL="${ES_MODEL:-deepseek-v4-pro-native}"
HARBOR_MODEL="${HARBOR_MODEL:-deepseek/deepseek-v4-pro}"
K="${K:-0}"
# Official runs use Harbor's default agent timeout. run-tb.sh's 1500s internal
# value happens to match today; if Harbor's default moves, official_profile.py
# is the single place that definition lives and this run will refuse on drift.
HARBOR_AGENT_TIMEOUT_SEC="${HARBOR_AGENT_TIMEOUT_SEC:-1500}"
export HARBOR_UPLOAD="${HARBOR_UPLOAD:-1}"
export INTERNAL_PROFILE=0
JOB_NAME="${JOB_NAME:-es-tb-official-${PROVIDER}-k${K}}"
JOB_DIR="$JOBS_DIR/$JOB_NAME"

LOG(){ echo "$(date '+%H:%M:%S') [run-tb-official] $*"; }
REFUSE(){ echo "$(date '+%H:%M:%S') [run-tb-official] REFUSED — $*" >&2; exit 2; }

# ── concurrency provenance (item 7) ─────────────────────────────────────────
# The default is NOT changed here. It is printed with its honest evidence class
# so an unproven value is never silently presented as a validated one.
CONCURRENCY="${CONCURRENCY:-$(python3 -c "
import sys; sys.path.insert(0, '$SCRIPTS')
import concurrency_profile as c
print(c.resolve('$LEADERBOARD_SLUG').concurrency)")}"
python3 -c "
import sys; sys.path.insert(0, '$SCRIPTS')
import concurrency_profile as c
print('[concurrency] ' + c.describe('$LEADERBOARD_SLUG', $CONCURRENCY))"

# ── gate 1: profile declaration (pre-flight, before any spend) ──────────────
export K HARBOR_AGENT_TIMEOUT_SEC LEADERBOARD_SLUG
export HARBOR_VERSION="${HARBOR_VERSION:-}"
export DATASET_VERSION="${DATASET_VERSION:-}"
export AGENT_VERSION="${AGENT_VERSION:-}"
export CONTAINER_IMAGE_DIGEST="${CONTAINER_IMAGE_DIGEST:-}"
export EXPECTED_TASK_COUNT="${EXPECTED_TASK_COUNT:-}"
export TIMEOUT_OVERRIDE_ALLOWED="${TIMEOUT_OVERRIDE_ALLOWED:-0}"
export TIMEOUT_OVERRIDE_RULE_REFERENCE="${TIMEOUT_OVERRIDE_RULE_REFERENCE:-}"

[ -n "${METADATA_FILE:-}" ] || REFUSE "METADATA_FILE is required for an official campaign"
[ -f "${METADATA_FILE}" ] || REFUSE "METADATA_FILE not found: $METADATA_FILE"

# ── build the frozen config, then freeze its own checksum ───────────────────
mkdir -p "$JOBS_DIR"
CFG="$TB_DIR/es-tb-official-${JOB_NAME}.yaml"
cat > "$CFG" <<EOF
job_name: $JOB_NAME
jobs_dir: $JOBS_DIR
n_concurrent_trials: $CONCURRENCY
n_attempts: $K
agents:
  - import_path: es_harbor_agent:ExecuteSpecAgent
    model_name: $HARBOR_MODEL
    override_timeout_sec: $HARBOR_AGENT_TIMEOUT_SEC
    max_timeout_sec: $HARBOR_AGENT_TIMEOUT_SEC
datasets:
  - name: $TB_DATASET
EOF
CONFIG_SHA256=$(python3 -c "
import hashlib, sys
print(hashlib.sha256(open('$CFG','rb').read()).hexdigest())")
export CONFIG_SHA256
LOG "frozen config: $CFG (sha256=$CONFIG_SHA256)"

python3 "$SCRIPTS/official_profile.py" validate-profile || exit 2

if [ "${OFFICIAL_DRY_RUN:-0}" = "1" ]; then
  LOG "OFFICIAL_DRY_RUN=1 — declaration validated, config frozen, Harbor NOT started."
  exit 0
fi

# ── gate 2: the run itself, with --upload ───────────────────────────────────
cd "$TB_DIR" || REFUSE "TB_DIR not found: $TB_DIR"
export ES_PROVIDER="$PROVIDER" ES_MODEL="$MODEL"
LOG "harbor run --upload starting (k=$K, conc=$CONCURRENCY, timeout=${HARBOR_AGENT_TIMEOUT_SEC}s)"
./.venv/bin/harbor run -c "$CFG" --upload -y 2>&1 | tee "$JOBS_DIR/${JOB_NAME}-run.log"
HARBOR_RC=${PIPESTATUS[0]}
[ "$HARBOR_RC" -eq 0 ] || REFUSE "harbor run exited $HARBOR_RC — refusing to seal or submit"

# ── gate 3: structural artifact gates (K completeness + trajectories) ───────
python3 "$SCRIPTS/official_profile.py" gate --job-dir "$JOB_DIR" || exit 2

# ── gate 4: seal the immutable checksummed manifest, then re-verify ─────────
python3 "$SCRIPTS/official_profile.py" seal --job-dir "$JOB_DIR" || exit 2
python3 "$SCRIPTS/official_profile.py" verify --job-dir "$JOB_DIR" || exit 2

# ── gate 5: ATIF schema validation + submission, via the existing script ────
# submit-tb.sh owns the Harbor-venv TrajectoryValidator call; this runner does
# not reimplement it. REQUIRE_ATIF is forced on for the official path.
LOG "handing off to submit-tb.sh (REQUIRE_ATIF=1, SUBMIT_LEADERBOARD=1)"
JOB_NAME="$JOB_NAME" JOBS_DIR="$JOBS_DIR" TB_DIR="$TB_DIR" REPO="$REPO" \
  REQUIRE_ATIF=1 SUBMIT_LEADERBOARD=1 REQUIRE_RUN_ID=1 \
  LEADERBOARD_SLUG="$LEADERBOARD_SLUG" METADATA_FILE="$METADATA_FILE" \
  bash "$SCRIPTS/submit-tb.sh"
SUBMIT_RC=$?

LOG "submit-tb.sh exited $SUBMIT_RC. Reminder: acceptance is the official"
LOG "verifier's call, not this script's and not the /benchmark dashboard's."
exit "$SUBMIT_RC"
