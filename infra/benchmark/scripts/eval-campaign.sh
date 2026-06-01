#!/usr/bin/env bash
# Overnight SWE-bench eval campaign orchestrator (runs ON the GCP eval VM).
#
# For each provider arm: sharded code-gen → score → record RESOLVED. Then sync
# everything to GCS. RESILIENT: one arm failing does NOT abort the campaign.
# Logs to a timestamped campaign log + prints a final per-arm summary for the
# morning read. This is THE script you launch in the evening:
#
#   nohup bash ~/executespec/infra/benchmark/scripts/eval-campaign.sh > ~/campaign.out 2>&1 &
#
# Config via env (defaults = the GPT-lever VALIDATION campaign, set50 × all 4):
#   CAMPAIGN     name                                   (default validation)
#   SET_FILE     instance set                           (default set50.txt)
#   ARMS         space-sep PROVIDER:MODEL:SHARDS        (default = the 4 below)
#   BENCH_DIR    runner+sets+creds dir                  (default $HOME/swebench-bench)
#   GCS_BUCKET   gs://… results bucket                  (default empty = skip sync)
#   STOP_ON_DONE stop the VM when finished              (default false; needs SA perms)
#
# Preset B (full-300 DeepSeek only):
#   SET_FILE=full300.txt ARMS="DEEPSEEK:deepseek-v4-flash-native:8" CAMPAIGN=full300-ds \
#     bash eval-campaign.sh
set -uo pipefail

BENCH_DIR="${BENCH_DIR:-$HOME/swebench-bench}"
SET_FILE="${SET_FILE:-set50.txt}"
CAMPAIGN="${CAMPAIGN:-validation}"
GCS_BUCKET="${GCS_BUCKET:-}"
STOP_ON_DONE="${STOP_ON_DONE:-false}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# Gemini gets few shards on purpose — it 429s easiest and the eval can't fail over.
ARMS="${ARMS:-OPENAI:gpt-5.4:8 ANTHROPIC:claude-sonnet-4-6:8 GOOGLE:gemini-3.5-flash:2 DEEPSEEK:deepseek-v4-flash-native:8}"

TS=$(date +%Y%m%d-%H%M%S)
mkdir -p "$BENCH_DIR"
LOG="$BENCH_DIR/campaign-${CAMPAIGN}-${TS}.log"
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$LOG"; }

log "=== CAMPAIGN '$CAMPAIGN'  set=$SET_FILE  arms=[$ARMS] ==="

# 1. ensure backend healthy (idempotent deploy if down)
if ! curl -sf -m5 http://localhost:8080/actuator/health >/dev/null 2>&1; then
  log "backend down — running deploy.sh"
  bash "$SCRIPT_DIR/deploy.sh" 2>&1 | tee -a "$LOG" || { log "FATAL: deploy failed"; exit 1; }
fi
curl -sf -m5 http://localhost:8080/actuator/health >/dev/null 2>&1 || { log "FATAL: backend not healthy"; exit 1; }
log "backend healthy"

# 2. run arms (resilient — never abort the campaign on a single arm failure)
SUMMARY=()
for arm in $ARMS; do
  prov="${arm%%:*}"; rest="${arm#*:}"; model="${rest%%:*}"; shards="${rest##*:}"
  tag="${CAMPAIGN}-${prov,,}"
  log "--- ARM $prov  model=$model  shards=$shards ---"
  if PROVIDER="$prov" MODEL="$model" SET_FILE="$SET_FILE" SHARDS="$shards" \
       RUN_TAG="$tag" BENCH_DIR="$BENCH_DIR" \
       bash "$SCRIPT_DIR/run-sharded.sh" 2>&1 | tee -a "$LOG"; then
    man="core-codegen-${tag}.manifest.jsonl"
    log "scoring $man"
    if bash "$BENCH_DIR/score-core-codegen.sh" "$man" 2>&1 | tee -a "$LOG"; then
      res=$(cd "$BENCH_DIR" && ls -t "executespec-"*".json" 2>/dev/null | head -1 \
        | xargs -I{} python3 -c "import json;d=json.load(open('{}'));r=len(d.get('resolved_ids',[]));print(f\"{r}/{r+len(d.get('unresolved_ids',[]))}\")" 2>/dev/null)
      SUMMARY+=("$prov: ${res:-?} resolved")
      log "ARM $prov RESOLVED: ${res:-?}"
    else
      SUMMARY+=("$prov: SCORING FAILED"); log "ARM $prov scoring FAILED"
    fi
  else
    SUMMARY+=("$prov: CODEGEN FAILED"); log "ARM $prov codegen FAILED"
  fi
done

# 3. sync results to GCS (durable; survives `terraform destroy`)
if [ -n "$GCS_BUCKET" ]; then
  log "syncing results → $GCS_BUCKET"
  bash "$SCRIPT_DIR/sync-results.sh" "$GCS_BUCKET" "${CAMPAIGN}-${TS}" 2>&1 | tee -a "$LOG" || log "WARN: GCS sync failed"
fi

# 4. morning summary
log "================ CAMPAIGN SUMMARY ('$CAMPAIGN') ================"
for s in "${SUMMARY[@]}"; do log "  $s"; done
log "full log: $LOG"
log "==============================================================="

# 5. optional self-stop (compute billing halts; disk + warm images persist)
if [ "$STOP_ON_DONE" = "true" ]; then
  log "STOP_ON_DONE — stopping VM"
  NAME=$(curl -s -H "Metadata-Flavor: Google" http://metadata.google.internal/computeMetadata/v1/instance/name)
  ZONE=$(curl -s -H "Metadata-Flavor: Google" http://metadata.google.internal/computeMetadata/v1/instance/zone | awk -F/ '{print $NF}')
  gcloud compute instances stop "$NAME" --zone "$ZONE" --quiet || log "WARN: self-stop failed (compute.instances.stop SA perm?)"
fi
