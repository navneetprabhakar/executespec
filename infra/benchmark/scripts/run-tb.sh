#!/bin/bash
# run-tb.sh — Terminal-Bench internal driver (one-pass by default).
# Works locally OR on the benchmark VM. Key fixes vs the ad-hoc runners:
#   • PERSISTENT jobs_dir (NOT /tmp — /tmp is wiped on reboot, which lost our data once)
#   • live visual tracker (HTML → GCS + Telegram pings) via tb-tracker.py
#   • GCS results sync at the end (survives VM teardown)
#   • in-app /benchmark bridge enabled by default
#   • no leaderboard submission; use submit-tb.sh manually if a future run is explicitly approved
# Env overrides (all optional): ES_PROVIDER ES_MODEL HARBOR_MODEL TB_DATASET BENCHMARK_DATASET
#                               K CONCURRENCY HARBOR_AGENT_TIMEOUT_SEC COMPACTION TASK_LIMIT
#                               JOB_NAME GCS_BUCKET TB_DIR REPO JOBS_DIR
set -uo pipefail

TB_DIR="${TB_DIR:-$HOME/tbench}"                       # harbor venv + es_harbor_agent + dataset cache
REPO="${REPO:-$HOME/Documents/antigravity/executespec}"
JOBS_DIR="${JOBS_DIR:-$HOME/es-tb-jobs}"               # PERSISTENT — survives reboot
PROVIDER="${ES_PROVIDER:-deepseek}"
MODEL="${ES_MODEL:-deepseek-v4-pro-native}"            # ExecuteSpec model id
HARBOR_MODEL="${HARBOR_MODEL:-deepseek/deepseek-v4-pro}"
TB_DATASET="${TB_DATASET:-terminal-bench/terminal-bench-2-1}"
BENCHMARK_DATASET="${BENCHMARK_DATASET:-${TB_DATASET##*/}}"
K="${K:-1}"                                            # internal benchmark: exactly one attempt/task
TASK_LIMIT="${TASK_LIMIT:-}"                           # optional sanity subset; full TB leaves empty
TOTAL_TASKS="${TOTAL_TASKS:-${TASK_LIMIT:-89}}"
CONCURRENCY="${CONCURRENCY:-4}"                        # bump to 8-16 on a big VM
HARBOR_AGENT_TIMEOUT_SEC="${HARBOR_AGENT_TIMEOUT_SEC:-1350}" # higher than ES TB wall-clock; avoids Harbor killing first
COMPACTION="${COMPACTION:-on}"                         # on|off  (TB: only history-compaction fires)
JOB_NAME="${JOB_NAME:-es-tb-${PROVIDER}-k${K}}"
GCS_BUCKET="${GCS_BUCKET:-}"                           # gs://bucket/path  (optional → tracker + results)
TRACKER="$REPO/infra/benchmark/scripts/tb-tracker.py"
TG="${TG:-/tmp/tg-send.py}"
ADMIN_ENV="${ADMIN_ENV:-$HOME/swebench-bench/.espec-admin.env}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
mkdir -p "$JOBS_DIR"
LOG(){ echo "$(date '+%H:%M:%S') [run-tb] $*"; }

if [ "$K" != "1" ] && [ "${ALLOW_TB_MULTI_PASS:-}" != "true" ]; then
  echo "Refusing K=$K. This runner is locked to one-pass internal TB runs. Set ALLOW_TB_MULTI_PASS=true only for a separately approved campaign." >&2
  exit 2
fi
if [ "${SUBMIT_TB:-off}" != "off" ]; then
  echo "Refusing SUBMIT_TB=${SUBMIT_TB}. This runner never submits; run submit-tb.sh manually only after explicit approval." >&2
  exit 2
fi

# ── 1. harbor config on the fly (persistent jobs_dir) ───────────────────────
CFG="$TB_DIR/es-tb-runtb-${JOB_NAME}.yaml"
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
if [ -n "$TASK_LIMIT" ]; then
  cat >> "$CFG" <<EOF
    n_tasks: $TASK_LIMIT
EOF
fi
LOG "config: $CFG (dataset=$TB_DATASET, jobs_dir=$JOBS_DIR, k=$K, conc=$CONCURRENCY, agent_timeout=${HARBOR_AGENT_TIMEOUT_SEC}s, internal-only=true)"

# ── 2. backend up + compaction flags (via API, never DB) ────────────────────
start_backend(){ pkill -f 'spring-boot:run' 2>/dev/null; sleep 3
  ( cd "$REPO" && JAVA_HOME=$JAVA_HOME mvn -q spring-boot:run -Dspring-boot.run.fork=false \
      -Dspring-boot.run.profiles=local > "$JOBS_DIR/backend.log" 2>&1 ) &
  curl -s --retry 90 --retry-delay 3 --retry-connrefused --retry-all-errors --max-time 6 \
      localhost:8080/actuator/health >/dev/null 2>&1; }
curl -s --max-time 5 localhost:8080/actuator/health 2>/dev/null | grep -q UP || { LOG "starting backend…"; start_backend; }
[ -f "$ADMIN_ENV" ] && . "$ADMIN_ENV"
export ES_ADMIN_EMAIL="${ES_ADMIN_EMAIL:-${ESPEC_ADMIN_EMAIL:-}}"
export ES_ADMIN_PW="${ES_ADMIN_PW:-${ESPEC_ADMIN_PW:-}}"
TK=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d "{\"email\":\"${ESPEC_ADMIN_EMAIL:-}\",\"password\":\"${ESPEC_ADMIN_PW:-}\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('accessToken',''))" 2>/dev/null)
set_flag(){
  local flag="$1" value="$2"
  curl -fsS -X PUT "localhost:8080/api/admin/feature-flags/$flag" -H "Authorization: Bearer $TK" \
    -H 'Content-Type: application/json' -d "{\"value\":\"$value\"}" >/dev/null
}
FV=$([ "$COMPACTION" = on ] && echo true || echo false)
set_flag worker.tool-compactor.enabled "$FV"
set_flag worker.history-compaction.enabled "$FV"
for f in worker.provider-prompt-tuning.enabled worker.critic-pass.enabled \
         worker.verify-before-complete.enabled worker.salvage-force-tool-choice.enabled; do
  set_flag "$f" true
done
LOG "backend UP · compaction=$COMPACTION · benchmark quality flags asserted"

# ── 3. launch the LIVE tracker (loop + telegram + gcs) ──────────────────────
DONE="$JOBS_DIR/${JOB_NAME}.done"; rm -f "$DONE"
GCSARG=(); [ -n "$GCS_BUCKET" ] && GCSARG=(--gcs "$GCS_BUCKET/tb-tracker.html")
nohup python3 "$TRACKER" "$JOBS_DIR/$JOB_NAME" --name "$MODEL · TB k=$K" --total "$TOTAL_TASKS" \
  --loop 300 --telegram --tg-script "$TG" --done-marker "$DONE" \
  --out "$JOBS_DIR/${JOB_NAME}-dashboard.html" "${GCSARG[@]}" > "$JOBS_DIR/tracker.log" 2>&1 &
LOG "tracker live → $JOBS_DIR/${JOB_NAME}-dashboard.html ${GCS_BUCKET:+(+ $GCS_BUCKET/tb-tracker.html)}"

# ── 3b. launch the IN-APP /benchmark bridge (resolved% + cost + TOKEN tracking) ─
# Feeds the in-login /benchmark dashboard (V236/V238): one TASK_SCORED per trial,
# carrying reward + esRunId + cost + input/output tokens from each result.json — so
# aggregate tokens show live. Auth via the admin env already sourced above.
if [ "${BRIDGE:-on}" = on ]; then
  BRIDGE_PY="$REPO/infra/benchmark/tb/tb-bench-bridge.py"
  export BR_ES_BASE="http://localhost:8080" \
         BR_ADMIN_EMAIL="${ESPEC_ADMIN_EMAIL:-}" BR_ADMIN_PW="${ESPEC_ADMIN_PW:-}"
  nohup python3 "$BRIDGE_PY" --job-dir "$JOBS_DIR/$JOB_NAME" \
    --provider "$PROVIDER" --model "$MODEL" --k "$K" --total "$TOTAL_TASKS" \
    --dataset "$BENCHMARK_DATASET" --idle-min 30 --max-min "${BRIDGE_MAX_MIN:-1440}" \
    > "$JOBS_DIR/${JOB_NAME}-bridge.log" 2>&1 &
  LOG "in-app bridge live → http://localhost:8080/benchmark (run id in ${JOB_NAME}-bridge.log)"
else
  LOG "in-app bridge DISABLED (BRIDGE=off)"
fi

# ── 4. the run ──────────────────────────────────────────────────────────────
cd "$TB_DIR" || exit 1
export ES_PROVIDER="$PROVIDER" ES_MODEL="$MODEL" ES_MAX_ITERS="${ES_MAX_ITERS:-120}"
LOG "harbor run starting (provider=$PROVIDER model=$MODEL)…"
RUN_PREFIX=()
command -v caffeinate >/dev/null 2>&1 && RUN_PREFIX=(caffeinate -dimsu)
"${RUN_PREFIX[@]}" ./.venv/bin/harbor run -c "$CFG" -y 2>&1 | tee "$JOBS_DIR/${JOB_NAME}-run.log"

# ── 5. done: marker, final tracker tick, GCS results sync, ping ─────────────
touch "$DONE"; sleep 6   # let the tracker write its final cycle
if [ -n "$GCS_BUCKET" ]; then gsutil -m rsync -r "$JOBS_DIR/$JOB_NAME" "$GCS_BUCKET/$JOB_NAME/" 2>/dev/null; fi
SC=$(find "$JOBS_DIR/$JOB_NAME" -name reward.txt 2>/dev/null | wc -l | tr -d ' ')
PA=$(find "$JOBS_DIR/$JOB_NAME" -name reward.txt -exec grep -l '^1' {} \; 2>/dev/null | wc -l | tr -d ' ')
[ -f "$TG" ] && python3 "$TG" "🏁 TB k=$K DONE — $MODEL · ${PA}/${SC} trials passed. Results: $JOBS_DIR/$JOB_NAME ${GCS_BUCKET:+(+GCS)}." >/dev/null 2>&1
LOG "DONE — ${PA}/${SC} trial-passes. Results in $JOBS_DIR/$JOB_NAME"
