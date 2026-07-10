#!/usr/bin/env bash
#
# V2 Eval Harness — 3 complexity bands × 3 providers = 9 runs, then a markdown report.
#
# Requires the server running at BASE_URL with harness.version=v2 (or v2-canary routed
# to this user). Uses PROTOTYPE mode to skip the plan approval gate so runs flow
# through unattended.
#
# Usage:
#   ./scripts/v2-eval.sh                           # run all 9 (default)
#   ./scripts/v2-eval.sh --providers anthropic     # single provider, all 3 bands
#   ./scripts/v2-eval.sh --bands low,medium        # subset of bands, all providers
#   ./scripts/v2-eval.sh --concurrency 3           # max parallel runs (default 1)
#   ./scripts/v2-eval.sh --timeout 900             # per-run timeout in seconds
#
# Env:
#   BASE_URL (default http://localhost:8080)
#   EMAIL    (default admin@executespec.local)
#   PASSWORD (default admin)
#
# Output:
#   scripts/v2-eval-results/v2-eval-<timestamp>.json   # machine-readable
#   scripts/v2-eval-results/v2-eval-<timestamp>.md     # human-readable report

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SPEC_DIR="${SCRIPT_DIR}/v2-eval-specs"
OUT_DIR="${SCRIPT_DIR}/v2-eval-results"
mkdir -p "$OUT_DIR"

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="${EMAIL:-admin@executespec.local}"
PASSWORD="${PASSWORD:?PASSWORD environment variable is required}"

PROVIDERS_DEFAULT=(anthropic openai gemini)
BANDS_DEFAULT=(low medium high)
CONCURRENCY=1
TIMEOUT=900

PROVIDERS=("${PROVIDERS_DEFAULT[@]}")
BANDS=("${BANDS_DEFAULT[@]}")

while [[ $# -gt 0 ]]; do
  case "$1" in
    --providers)   IFS=',' read -r -a PROVIDERS <<< "$2"; shift 2 ;;
    --bands)       IFS=',' read -r -a BANDS <<< "$2"; shift 2 ;;
    --concurrency) CONCURRENCY="$2"; shift 2 ;;
    --timeout)     TIMEOUT="$2"; shift 2 ;;
    -h|--help) sed -n '3,22p' "$0"; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

TS="$(date +%Y%m%d-%H%M%S)"
JSON_OUT="${OUT_DIR}/v2-eval-${TS}.json"
MD_OUT="${OUT_DIR}/v2-eval-${TS}.md"
RAW_DIR="${OUT_DIR}/v2-eval-${TS}-raw"
mkdir -p "$RAW_DIR"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }

# ── auth ────────────────────────────────────────────────────────────────────
log "Authenticating as $EMAIL"
TOKEN=$(curl -s -X POST "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "$(jq -n --arg e "$EMAIL" --arg p "$PASSWORD" '{email:$e,password:$p}')" \
  | jq -r '.accessToken // .token // empty')
if [[ -z "$TOKEN" || "$TOKEN" == "null" ]]; then
  echo "Auth failed" >&2; exit 1
fi

# ── helpers ─────────────────────────────────────────────────────────────────
submit_run() {
  local provider="$1" spec_file="$2"
  local spec; spec=$(cat "$spec_file")
  local api_prov
  case "$provider" in
    gemini) api_prov="GOOGLE" ;;
    *)      api_prov=$(echo "$provider" | tr '[:lower:]' '[:upper:]') ;;
  esac
  local body; body=$(jq -n --arg req "$spec" --arg prov "$api_prov" \
    '{userRequest:$req, skipApproval:true, executionMode:"PROTOTYPE", aiProvider:$prov}')
  # Robust submit: under --concurrency>1 the near-simultaneous POSTs occasionally
  # returned a body we failed to parse a runId from (while the run still launched
  # server-side), so a provider logged "submit FAILED (no runId)" even though it
  # ran. Capture the full response, accept .runId OR .id, retry once, and surface
  # the raw body on a miss so the cause is never invisible again.
  local resp run_id
  for attempt in 1 2; do
    resp=$(curl -s -X POST "$BASE_URL/api/runs" \
      -H "Authorization: Bearer $TOKEN" \
      -H 'Content-Type: application/json' \
      -d "$body")
    run_id=$(printf '%s' "$resp" | jq -r '.runId // .id // empty' 2>/dev/null || echo "")
    if [[ -n "$run_id" ]]; then echo "$run_id"; return 0; fi
    log "  submit($provider) attempt $attempt: no runId — raw: $(printf '%s' "$resp" | tr '\n' ' ' | head -c 200)"
    sleep 2
  done
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
    sleep 5
    elapsed=$((elapsed + 5))
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
       run_id:       ($run[0].runId       // null),
       status:       ($run[0].status      // null),
       harness:      ($run[0].harnessVersion // null),
       startedAt:    ($run[0].startedAt   // null),
       completedAt:  ($run[0].completedAt // null),
       tasks_total:  (($run[0].tasks // []) | length),
       tasks_done:   (($run[0].tasks // []) | map(select(.status == "COMPLETE")) | length),
       tasks_failed: (($run[0].tasks // []) | map(select(.status == "FAILED")) | length),
       review:       ($run[0].reviewJson  // null),
       actual_cost:  ($run[0].actualCostUsd // 0),
       est_low:      ($run[0].costEstimateLow // 0),
       est_high:     ($run[0].costEstimateHigh // 0),
       credits:      ($run[0].creditsCharged // 0),
       total_input:  ($run[0].totalInputTokens  // 0),
       total_output: ($run[0].totalOutputTokens // 0),
       accuracy:     ($report[0].accuracyScore // null),
       report_ok:    (($report[0].accuracyScore // null) != null)
     }' > "${RAW_DIR}/${provider}-${band}.result.json"

  log "done    $label status=$(jq -r '.status // "?"' "${RAW_DIR}/${provider}-${band}.result.json") "
}

# ── execute ────────────────────────────────────────────────────────────────
log "V2 eval — providers=${PROVIDERS[*]} bands=${BANDS[*]} concurrency=$CONCURRENCY timeout=${TIMEOUT}s"

pids=()
slot_busy() { (( ${#pids[@]} >= CONCURRENCY )); }
reap_one() {
  wait -n 2>/dev/null || true
  # trim completed pids (guard against empty array under `set -u`)
  local alive=()
  if (( ${#pids[@]} > 0 )); then
    for p in "${pids[@]}"; do kill -0 "$p" 2>/dev/null && alive+=("$p"); done
  fi
  pids=("${alive[@]+"${alive[@]}"}")
}

for provider in "${PROVIDERS[@]}"; do
  for band in "${BANDS[@]}"; do
    spec_file="${SPEC_DIR}/${band}.txt"
    [[ -f "$spec_file" ]] || { log "skip ${band}: spec missing"; continue; }
    while slot_busy; do reap_one; done
    run_one "$provider" "$band" "$spec_file" &
    pids+=($!)
  done
done

# drain remaining
while (( ${#pids[@]} > 0 )); do reap_one; done

# ── aggregate ──────────────────────────────────────────────────────────────
log "Aggregating results → $JSON_OUT"
jq -s 'sort_by(.provider,.band)' "${RAW_DIR}"/*.result.json > "$JSON_OUT"

# ── markdown report ────────────────────────────────────────────────────────
{
  echo "# V2 Eval Report — ${TS}"
  echo
  echo "- Base URL: \`${BASE_URL}\`"
  echo "- Providers: ${PROVIDERS[*]}"
  echo "- Bands: ${BANDS[*]}"
  echo "- Timeout: ${TIMEOUT}s"
  echo
  echo "## Summary"
  echo
  printf '| Provider | Band | Status | Tasks | Duration | Cost USD | In Tokens | Out Tokens | Review | Accuracy |\n'
  printf '|---|---|---|---|---|---|---|---|---|---|\n'
  jq -r '.[] |
    . as $r |
    ($r.run_id // "-") as $id |
    (($r.startedAt // null) as $st | ($r.completedAt // null) as $end |
      if ($st and $end) then
        (($end | sub("\\.[0-9]+Z$"; "Z") | fromdateiso8601) - ($st | sub("\\.[0-9]+Z$"; "Z") | fromdateiso8601)) | tostring + "s"
      else "-" end) as $dur |
    ($r.review // "" | fromjson? // {}) as $rev |
    "| \($r.provider) | \($r.band) | \($r.status // "?") | \($r.tasks_done)/\($r.tasks_total) | \($dur) | $\($r.actual_cost) | \($r.total_input) | \($r.total_output) | \($rev.overall // "-") | \($r.accuracy // "-") |"' "$JSON_OUT"
  echo
  echo "## Notes"
  echo
  echo "- \`Accuracy\` comes from the validation report. \`-\` means the report did not generate (often OpenAI hitting the Spring AI 1.1.4 extra_body bug — V2 reroutes to Anthropic for the narrative call; ensure an Anthropic key is configured)."
  echo "- \`Review\` is the V2 reviewer verdict (CLEAN / FIXED_ISSUES / UNRESOLVED_ISSUES / REVIEW_FAILED)."
  echo "- Cost excludes preview / test execution; only LLM spend from planner + worker + reviewer."
  echo
  echo "## Raw data"
  echo
  echo "Per-run JSON + full run snapshots live in \`${RAW_DIR#$SCRIPT_DIR/}\`."
} > "$MD_OUT"

log "Report: $MD_OUT"
log "JSON:   $JSON_OUT"
echo "$MD_OUT"
