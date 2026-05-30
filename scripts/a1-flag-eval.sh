#!/usr/bin/env bash
# A1 worker-lever eval — baseline (all 5 levers OFF) vs ALL-ON, per provider, on one band.
# Budget-capped via per-run userCostCap; runs sequentially; RESETS all flags OFF at the end.
# LOCAL DEV ONLY. Usage: BAND=medium CAP=3.0 ./scripts/a1-flag-eval.sh
set -uo pipefail
BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="${EMAIL:-admin@executespec.local}"
PASSWORD="${PASSWORD:-admin}"
CAP="${CAP:-3.0}"          # per-run hard cost cap (USD); 2 runs/provider => <= 2*CAP/provider
TIMEOUT="${TIMEOUT:-1500}" # per-run poll timeout (s)
BAND="${BAND:-medium}"
DIR="$(cd "$(dirname "$0")" && pwd)"
SPEC_DIR="$DIR/v2-eval-specs"
OUT="$DIR/v2-eval-results/a1-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
FLAGS=(worker.tool-compactor.enabled worker.history-compaction.enabled worker.build-tdd.enabled \
       worker.provider-prompt-tuning.enabled worker.cross-provider-fallback.enabled)
PROVIDERS=(anthropic openai gemini)

log() { echo "[a1-eval $(date +%H:%M:%S)] $*"; }

TOKEN=$(curl -s -X POST "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" | jq -r '.accessToken // .token // empty')
[ -z "$TOKEN" ] && { log "LOGIN FAILED — is the app up + creds right?"; exit 1; }
log "logged in as $EMAIL"

set_flags() { # value(true|false)
  local v="$1"
  for f in "${FLAGS[@]}"; do
    curl -s -o /dev/null -w "%{http_code}" -X PUT "$BASE_URL/api/admin/feature-flags/$f" \
      -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
      -d "{\"value\":\"$v\"}" >/dev/null || true
  done
  log "all 5 levers set -> $v"
}

api_prov() { case "$1" in gemini) echo GOOGLE ;; *) echo "$1" | tr 'a-z' 'A-Z' ;; esac; }

run_one() { # provider condition
  local prov="$1" cond="$2" spec; spec=$(cat "$SPEC_DIR/$BAND.txt")
  local body; body=$(jq -n --arg req "$spec" --arg p "$(api_prov "$prov")" --argjson cap "$CAP" \
    '{userRequest:$req, skipApproval:true, executionMode:"PROTOTYPE", aiProvider:$p, userCostCap:$cap}')
  local rid; rid=$(curl -s -X POST "$BASE_URL/api/runs" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d "$body" | jq -r '.runId // empty')
  if [ -z "$rid" ]; then log "$prov/$cond SUBMIT_FAILED"; echo "$prov,$cond,SUBMIT_FAILED,," >> "$OUT/summary.csv"; return; fi
  log "$prov/$cond submitted run=$rid (cap \$$CAP)"
  local e=0
  while [ "$e" -lt "$TIMEOUT" ]; do
    local d; d=$(curl -s "$BASE_URL/api/runs/$rid" -H "Authorization: Bearer $TOKEN")
    local s; s=$(echo "$d" | jq -r '.status // ""')
    case "$s" in
      COMPLETE|PARTIAL|FAILED|CANCELLED|ABORTED)
        echo "$d" > "$OUT/$prov-$cond.json"
        local vs c; vs=$(echo "$d" | jq -r '.verifiedScore // "na"'); c=$(echo "$d" | jq -r '.actualCostUsd // 0')
        log "$prov/$cond DONE status=$s verifiedScore=$vs cost=\$$c"
        echo "$prov,$cond,$s,$vs,$c" >> "$OUT/summary.csv"
        return ;;
    esac
    sleep 10; e=$((e + 10))
  done
  log "$prov/$cond TIMEOUT after ${TIMEOUT}s (run=$rid)"
  echo "$prov,$cond,TIMEOUT,,," >> "$OUT/summary.csv"
}

echo "provider,condition,status,verifiedScore,costUsd" > "$OUT/summary.csv"
log "=== BASELINE — all 5 worker levers OFF ==="; set_flags false
for p in "${PROVIDERS[@]}"; do run_one "$p" baseline; done
log "=== ALL 5 worker levers ON ==="; set_flags true
for p in "${PROVIDERS[@]}"; do run_one "$p" flagson; done
log "=== RESET — all 5 levers back OFF (default state) ==="; set_flags false
log "DONE. Summary:"; column -s, -t "$OUT/summary.csv" 2>/dev/null || cat "$OUT/summary.csv"
log "Raw run JSON + summary.csv in: $OUT"
