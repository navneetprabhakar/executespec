#!/usr/bin/env bash
#
# Per-MODEL sample eval. Submits one full PROTOTYPE run per (model, band), pinning the model
# via RunRequest.modelName (ENTERPRISE-gated) and VERIFYING the model actually used from the
# backend log — so a silent provider-default fallback (e.g. a 404 model quietly resolving to
# OpenRouter's claude-sonnet default) is flagged, never scored as the pinned model.
#
# Distinct from v2-eval.sh (which routes by provider only and cannot target a specific model).
#
# Usage:
#   BANDS=low ./scripts/model-sample-eval.sh
#   BANDS=medium,high ./scripts/model-sample-eval.sh
#   MODELS="$(cat list)" BANDS=low ./scripts/model-sample-eval.sh   # custom list
#
# Env: BASE_URL, EMAIL (ENTERPRISE), PASSWORD, LOG (backend log), TIMEOUT, BANDS
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SPEC_DIR="$SCRIPT_DIR/v2-eval-specs"
OUT_DIR="$SCRIPT_DIR/model-sample-results"
mkdir -p "$OUT_DIR"

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="${EMAIL:-revalidate-ent@test.local}"
PASSWORD="${PASSWORD:-REDACTED_FOR_PUBLIC_RELEASE}"
LOG="${LOG:-/tmp/es-backend.log}"
TIMEOUT="${TIMEOUT:-1200}"
BANDS="${BANDS:-low}"

# label|PROVIDER|modelName   (empty modelName = provider default / native, no pin)
MODELS_DEFAULT='claude-sonnet|ANTHROPIC|
gpt-5.4|OPENAI|
gemini-flash|GEMINI|
devstral-2|OPENROUTER|devstral-2
codestral-2508|OPENROUTER|codestral-2508
minimax-m3|OPENROUTER|minimax-m3
gpt-5.3-codex|OPENROUTER|gpt-5.3-codex
grok-4.20|OPENROUTER|grok-4.20
glm-5.2|OPENROUTER|glm-5.2
kimi-k2.7-code|OPENROUTER|kimi-k2.7-code
minimax-m2.7|OPENROUTER|minimax-m2.7
qwen3-coder-next|OPENROUTER|qwen3-coder-next'
MODELS="${MODELS:-$MODELS_DEFAULT}"

TS="$(date +%Y%m%d-%H%M%S)"
JSON="$OUT_DIR/sample-$TS.jsonl"
MD="$OUT_DIR/sample-$TS.md"
log(){ printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }

TOKEN=$(curl -s -X POST "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' \
  -d "$(jq -n --arg e "$EMAIL" --arg p "$PASSWORD" '{email:$e,password:$p}')" \
  | jq -r '.accessToken // .token // empty')
[ -z "$TOKEN" ] && { echo "auth failed for $EMAIL" >&2; exit 1; }

run_one(){
  local label="$1" prov="$2" model="$3" band="$4"
  local spec; spec=$(cat "$SPEC_DIR/$band.txt")
  local body
  if [ -n "$model" ]; then
    body=$(jq -n --arg r "$spec" --arg p "$prov" --arg m "$model" \
      '{userRequest:$r,skipApproval:true,executionMode:"PROTOTYPE",aiProvider:$p,modelName:$m}')
  else
    body=$(jq -n --arg r "$spec" --arg p "$prov" \
      '{userRequest:$r,skipApproval:true,executionMode:"PROTOTYPE",aiProvider:$p}')
  fi
  local rid; rid=$(curl -s -X POST "$BASE_URL/api/runs" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d "$body" | jq -r '.runId // .id // empty')
  if [ -z "$rid" ]; then
    log "$label/$band SUBMIT FAILED"
    echo "{\"label\":\"$label\",\"band\":\"$band\",\"error\":\"submit_failed\",\"pinned\":\"$model\"}" >> "$JSON"
    return
  fi
  log "$label/$band runId=$rid (pin=${model:-none})"
  local el=0 st=""
  while [ $el -lt "$TIMEOUT" ]; do
    st=$(curl -s "$BASE_URL/api/runs/$rid" -H "Authorization: Bearer $TOKEN" | jq -r '.status // ""')
    case "$st" in COMPLETE|PARTIAL|FAILED|CANCELLED) break;; esac
    sleep 5; el=$((el+5))
  done
  local run rep
  run=$(curl -s "$BASE_URL/api/runs/$rid" -H "Authorization: Bearer $TOKEN"); echo "$run" | jq . >/dev/null 2>&1 || run='{}'
  rep=$(curl -s "$BASE_URL/api/runs/$rid/report" -H "Authorization: Bearer $TOKEN"); echo "$rep" | jq . >/dev/null 2>&1 || rep='{}'
  local actual; actual=$(grep -aE "RunCostTracker record run=$rid" "$LOG" 2>/dev/null \
    | grep -aoE "model=[^ ]+" | sort | uniq -c | tr -s ' ' | tr '\n' ';')
  local worker; worker=$(grep -aE "WorkerAgent START run=$rid" "$LOG" 2>/dev/null \
    | grep -aoE "model=[^ ]+" | head -1 | sed 's/model=//')
  local pinok="n/a"
  if [ -n "$model" ]; then
    if echo "$actual" | grep -q "model=$model;"  || echo "$actual" | grep -q "model=$model$"; then pinok="yes"; else pinok="NO_FALLBACK"; fi
  fi
  jq -n --arg label "$label" --arg band "$band" --arg rid "$rid" --arg model "$model" --arg prov "$prov" \
        --argjson run "$run" --argjson rep "$rep" --arg actual "$actual" --arg worker "$worker" --arg pinok "$pinok" \
    '{label:$label,band:$band,rid:$rid,pinned:$model,provider:$prov,
      status:($run.status),planStrategy:($run.planStrategy),
      tasksTotal:(($run.tasks//[])|length),tasksDone:(($run.tasks//[])|map(select(.status=="COMPLETE"))|length),
      verifiedScore:($rep.verifiedScore),cost:($run.actualCostUsd),
      startedAt:($run.startedAt),completedAt:($run.completedAt),
      durationSec:(($run.completedAt) as $e | ($run.startedAt) as $s | if ($e and $s) then (($e|sub("\\.[0-9]+Z$";"Z")|fromdateiso8601) - ($s|sub("\\.[0-9]+Z$";"Z")|fromdateiso8601)) else null end),
      workerModelLogged:$worker,actualCalls:$actual,pinOK:$pinok}' >> "$JSON"
  log "done $label/$band status=$st score=$(echo "$rep"|jq -r '.verifiedScore//"-"') pinOK=$pinok"
}

log "models=$(printf '%s\n' "$MODELS" | grep -c '|') bands=$BANDS timeout=${TIMEOUT}s out=$JSON"
IFS=',' read -ra BARR <<< "$BANDS"
while IFS='|' read -r label prov model; do
  [ -z "${label// /}" ] && continue
  for band in "${BARR[@]}"; do
    [ -f "$SPEC_DIR/$band.txt" ] || { log "skip $band (no spec)"; continue; }
    run_one "$label" "$prov" "$model" "$band"
  done
done <<< "$MODELS"

{
  echo "# Model sample eval — $TS  (bands: $BANDS)"
  echo
  echo "| Model | Band | Status | Plan | Tasks | verifiedScore | Latency(s) | Cost \$ | Worker model (logged) | pin OK |"
  echo "|---|---|---|---|---|---|---|---|---|---|"
  jq -r '"| \(.label) | \(.band) | \(.status//.error//"?") | \(.planStrategy//"-") | \(.tasksDone)/\(.tasksTotal) | \(.verifiedScore//"-") | \(.durationSec//"-") | \(.cost//0) | \(.workerModelLogged//"-") | \(.pinOK//"-") |"' "$JSON"
  echo
  echo "_pin OK = the pinned model actually appears in RunCostTracker records (else NO_FALLBACK = silently ran on provider default; that row is invalid)._"
} > "$MD"
log "REPORT: $MD"
cat "$MD"
