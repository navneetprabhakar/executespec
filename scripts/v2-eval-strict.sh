#!/usr/bin/env bash
#
# Strict eval harness — runs S3/S4/S5 spec files from eval-scenarios-v2.md
# against a live server. Wrapper around v2-eval.sh that accepts strict-band
# names (s3-medium, s4-large, s5-integration) and mirrors the submit+poll
# pattern with provider selection + real-cost capture.
#
# Requires:
#   - Server running (default http://localhost:8080) with strict-provider mode ON
#   - Provider API keys configured (OPENAI / ANTHROPIC / GOOGLE)
#   - Admin credentials (defaults: admin@executespec.ai / Admin@123)
#
# Usage:
#   ./scripts/v2-eval-strict.sh                          # all S3/S4/S5 × 3 providers = 9 runs
#   ./scripts/v2-eval-strict.sh --bands s3-medium        # only S3, all providers
#   ./scripts/v2-eval-strict.sh --providers anthropic    # single provider
#   ./scripts/v2-eval-strict.sh --dry-run                # print what would run without submitting
#
# Approximate spend (from eval-scenarios-v2.md):
#   S3 × 3 providers ≈ $4.50
#   S4 × 3 providers ≈ $6.00
#   S5 × 1 provider  ≈ $2.00 (multi-repo; skip if no projects seeded)
#   Total            ≈ $12.50 (inside the $25 cap)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SPEC_DIR="${SCRIPT_DIR}/v2-eval-specs"
OUT_DIR="${SCRIPT_DIR}/v2-eval-results"
mkdir -p "$OUT_DIR"

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="${EMAIL:-admin@executespec.ai}"
PASSWORD="${PASSWORD:-Admin@123}"

BANDS_DEFAULT=(s3-medium s4-large s5-integration)
PROVIDERS_DEFAULT=(anthropic openai gemini)
TIMEOUT=1200   # 20 min — S4 can run long
CONCURRENCY=1
DRY_RUN=0

BANDS=("${BANDS_DEFAULT[@]}")
PROVIDERS=("${PROVIDERS_DEFAULT[@]}")

while [[ $# -gt 0 ]]; do
  case "$1" in
    --providers)   IFS=',' read -r -a PROVIDERS <<< "$2"; shift 2 ;;
    --bands)       IFS=',' read -r -a BANDS <<< "$2"; shift 2 ;;
    --concurrency) CONCURRENCY="$2"; shift 2 ;;
    --timeout)     TIMEOUT="$2"; shift 2 ;;
    --dry-run)     DRY_RUN=1; shift ;;
    -h|--help)     sed -n '3,25p' "$0"; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

TS="$(date +%Y%m%d-%H%M%S)"
JSON_OUT="${OUT_DIR}/v2-eval-strict-${TS}.json"
MD_OUT="${OUT_DIR}/v2-eval-strict-${TS}.md"
RAW_DIR="${OUT_DIR}/v2-eval-strict-${TS}-raw"
mkdir -p "$RAW_DIR"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }

# ── Confirm the spec files exist ────────────────────────────────────────────
for band in "${BANDS[@]}"; do
  spec="${SPEC_DIR}/${band}.txt"
  [[ -f "$spec" ]] || { log "ERROR: spec missing: $spec"; exit 1; }
done

if [[ "$DRY_RUN" == "1" ]]; then
  log "Dry run — would submit:"
  for p in "${PROVIDERS[@]}"; do
    for b in "${BANDS[@]}"; do
      log "  ${p}/${b}  (spec: ${SPEC_DIR}/${b}.txt, ${TIMEOUT}s timeout)"
    done
  done
  exit 0
fi

# ── auth ────────────────────────────────────────────────────────────────────
log "Authenticating as $EMAIL against $BASE_URL"
TOKEN=$(curl -s -X POST "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "$(jq -n --arg e "$EMAIL" --arg p "$PASSWORD" '{email:$e,password:$p}')" \
  | jq -r '.accessToken // .token // empty')
if [[ -z "$TOKEN" || "$TOKEN" == "null" ]]; then
  echo "Auth failed — is the server running?" >&2
  exit 1
fi

submit_run() {
  local provider="$1" spec_file="$2"
  local spec; spec=$(cat "$spec_file")
  local api_prov
  case "$provider" in
    gemini) api_prov="GOOGLE" ;;
    *)      api_prov=$(echo "$provider" | tr '[:lower:]' '[:upper:]') ;;
  esac
  local body
  body=$(jq -n --arg req "$spec" --arg prov "$api_prov" \
    '{userRequest:$req, skipApproval:true, executionMode:"PROTOTYPE", aiProvider:$prov}')
  curl -s -X POST "$BASE_URL/api/runs" \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -d "$body" | jq -r '.runId // empty'
}

poll_until_done() {
  local run_id="$1" timeout="$2" dest="$3"
  local elapsed=0 last=""
  while [[ $elapsed -lt $timeout ]]; do
    curl -s "$BASE_URL/api/runs/$run_id" \
      -H "Authorization: Bearer $TOKEN" > "$dest" 2>/dev/null || true
    local status; status=$(jq -r '.status // ""' "$dest" 2>/dev/null || echo "")
    if [[ "$status" != "$last" ]]; then
      log "  $run_id → $status (${elapsed}s)"
      last="$status"
    fi
    case "$status" in
      COMPLETE|PARTIAL|FAILED|CANCELLED) return 0 ;;
    esac
    sleep 10
    elapsed=$((elapsed + 10))
  done
  log "  $run_id TIMED OUT after ${timeout}s"
  jq '. + {status:"TIMEOUT"}' "$dest" > "${dest}.tmp" && mv "${dest}.tmp" "$dest"
  return 1
}

fetch_report() {
  local run_id="$1"
  curl -s "$BASE_URL/api/runs/$run_id/report" \
    -H "Authorization: Bearer $TOKEN" 2>/dev/null || echo "{}"
}

run_one() {
  local provider="$1" band="$2" spec_file="$3"
  local label="${provider}/${band}"
  log "submit  $label"

  local run_id; run_id=$(submit_run "$provider" "$spec_file")
  if [[ -z "$run_id" ]]; then
    log "submit  $label FAILED (no runId)"
    echo "{\"provider\":\"$provider\",\"band\":\"$band\",\"error\":\"submit_failed\"}" \
      > "${RAW_DIR}/${provider}-${band}.result.json"
    return 1
  fi
  log "  $label runId=$run_id"

  local run_json="${RAW_DIR}/${provider}-${band}.run.json"
  poll_until_done "$run_id" "$TIMEOUT" "$run_json" || true

  local report_json="${RAW_DIR}/${provider}-${band}.report.json"
  fetch_report "$run_id" > "$report_json"

  jq -n \
    --arg provider "$provider" \
    --arg band     "$band" \
    --slurpfile run    "$run_json" \
    --slurpfile report "$report_json" \
    '{
       provider:$provider, band:$band,
       run_id:           ($run[0].runId       // null),
       status:           ($run[0].status      // null),
       status_reason:    ($run[0].statusReason // null),
       startedAt:        ($run[0].startedAt   // null),
       completedAt:      ($run[0].completedAt // null),
       tasks_total:      (($run[0].tasks // []) | length),
       tasks_done:       (($run[0].tasks // []) | map(select(.status == "COMPLETE")) | length),
       tasks_failed:     (($run[0].tasks // []) | map(select(.status == "FAILED")) | length),
       actual_cost:      ($run[0].actualCostUsd // 0),
       est_low:          ($run[0].costEstimateLow // 0),
       est_high:         ($run[0].costEstimateHigh // 0),
       credits:          ($run[0].creditsCharged // 0),
       total_input:      ($run[0].totalInputTokens  // 0),
       total_output:     ($run[0].totalOutputTokens // 0),
       accuracy_llm:     ($report[0].accuracyScore // null),
       verified_score:   ($report[0].verifiedScore // null),
       reviewer_verdict: ($report[0].reviewerVerdict // null),
       final_build:      ($report[0].finalBuildPassed // null),
       tests_passed:     ($report[0].testsPassed // null),
       tests_total:      ($report[0].testsTotal // null)
     }' > "${RAW_DIR}/${provider}-${band}.result.json"

  log "done    $label status=$(jq -r '.status // "?"' "${RAW_DIR}/${provider}-${band}.result.json") verified=$(jq -r '.verified_score // "-"' "${RAW_DIR}/${provider}-${band}.result.json")"
}

# ── execute ─────────────────────────────────────────────────────────────────
log "Strict eval — providers=${PROVIDERS[*]} bands=${BANDS[*]} concurrency=$CONCURRENCY timeout=${TIMEOUT}s"

pids=()
slot_busy() { (( ${#pids[@]} >= CONCURRENCY )); }
reap_one() {
  wait -n 2>/dev/null || true
  local alive=()
  if (( ${#pids[@]} > 0 )); then
    for p in "${pids[@]}"; do kill -0 "$p" 2>/dev/null && alive+=("$p"); done
  fi
  pids=("${alive[@]+"${alive[@]}"}")
}

for provider in "${PROVIDERS[@]}"; do
  for band in "${BANDS[@]}"; do
    spec_file="${SPEC_DIR}/${band}.txt"
    while slot_busy; do reap_one; done
    run_one "$provider" "$band" "$spec_file" &
    pids+=($!)
  done
done

while (( ${#pids[@]} > 0 )); do reap_one; done

# ── aggregate ───────────────────────────────────────────────────────────────
log "Aggregating → $JSON_OUT"
jq -s 'sort_by(.provider,.band)' "${RAW_DIR}"/*.result.json > "$JSON_OUT"

{
  echo "# V2 Strict Eval Report — ${TS}"
  echo
  echo "- Base URL: \`${BASE_URL}\`"
  echo "- Providers: ${PROVIDERS[*]}"
  echo "- Bands: ${BANDS[*]}"
  echo "- Timeout: ${TIMEOUT}s per run"
  echo "- Strict-provider mode: **should be ON** (\`-Dexecutespec.strict-provider=true\`)"
  echo
  echo "## Summary"
  echo
  printf '| Provider | Band | Status | Verified | Reason | Tasks | Build | Tests | Est. | Actual |\n'
  printf '|---|---|---|---|---|---|---|---|---|---|\n'
  jq -r '.[] |
    "| \(.provider) | \(.band) | \(.status // "?") | \(.verified_score // "-") | \(.status_reason // "-") | \(.tasks_done)/\(.tasks_total) | \(.final_build // "-") | \(.tests_passed // "-")/\(.tests_total // "-") | \(.accuracy_llm // "-") | $\(.actual_cost // 0) |"' \
    "$JSON_OUT"
  echo
  echo "## Notes"
  echo
  echo "- \`Verified\` is the V106 deterministic score; \`Est.\` is the demoted LLM accuracy."
  echo "- \`Reason\` maps to the status_reason taxonomy (V107)."
  echo "- S5 multi-repo runs require a pre-seeded project with 2 linked repos. Set PROJECT_ID env var and extend this script if the default /api/runs path doesn't hydrate the project context."
  echo
  echo "## Raw"
  echo
  echo "Per-run JSON snapshots: \`${RAW_DIR#$SCRIPT_DIR/}\`"
} > "$MD_OUT"

log "Report: $MD_OUT"
log "JSON:   $JSON_OUT"
echo "$MD_OUT"
