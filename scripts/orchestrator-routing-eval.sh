#!/usr/bin/env bash
#
# Optional live model-prompt eval for the agent-session orchestrator router.
#
# Runs the routing corpus against selected provider prompts through:
#   POST /api/admin/eval/orchestrator-routing/run
#
# Usage:
#   ./scripts/orchestrator-routing-eval.sh
#   ./scripts/orchestrator-routing-eval.sh --providers openai,deepseek --limit 50
#
# Env:
#   BASE_URL  default http://localhost:8080
#   TOKEN     optional pre-minted SUPER_ADMIN bearer/PAT; skips login when set
#   EMAIL     default admin@executespec.ai
#   PASSWORD  default local/admin seed password; not printed

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="${SCRIPT_DIR}/orchestrator-routing-eval-results"
mkdir -p "$OUT_DIR"

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="${EMAIL:-admin@executespec.ai}"
PASSWORD="${PASSWORD:?PASSWORD environment variable is required}"
AUTH_TOKEN="${TOKEN:-${ACCESS_TOKEN:-}}"

PROVIDERS=""
LIMIT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --providers) PROVIDERS="$2"; shift 2 ;;
    --limit)     LIMIT="$2"; shift 2 ;;
    -h|--help)   sed -n '3,18p' "$0"; exit 0 ;;
    *)           echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

TS="$(date +%Y%m%d-%H%M%S)"
JSON_OUT="${OUT_DIR}/eval-${TS}.json"
MD_OUT="${OUT_DIR}/eval-${TS}.md"

if [[ -n "$AUTH_TOKEN" ]]; then
  echo "-> Using provided bearer token."
else
  echo "-> Authenticating as ${EMAIL}..."
  LOGIN_BODY=$(jq -n --arg e "$EMAIL" --arg p "$PASSWORD" '{email:$e, password:$p}')
  LOGIN_JSON="$(mktemp "${TMPDIR:-/tmp}/orchestrator-routing-login.XXXXXX.json")"
  LOGIN_STATUS=$(curl -sS -w '%{http_code}' -o "$LOGIN_JSON" -X POST \
    -H 'Content-Type: application/json' -d "$LOGIN_BODY" \
    "${BASE_URL}/api/auth/login")
  AUTH_TOKEN=$(jq -r '.accessToken // empty' "$LOGIN_JSON")
  rm -f "$LOGIN_JSON"
  if [[ "$LOGIN_STATUS" -lt 200 || "$LOGIN_STATUS" -ge 300 || -z "$AUTH_TOKEN" ]]; then
    echo "login failed for ${EMAIL} (HTTP ${LOGIN_STATUS})" >&2
    exit 1
  fi
fi

QS=""
[[ -n "$PROVIDERS" ]] && QS="?providers=${PROVIDERS}"
[[ -n "$LIMIT" ]] && QS="${QS:+${QS}&}limit=${LIMIT}"
[[ -n "$QS" && "$QS" != "?"* ]] && QS="?${QS}"

echo "-> POST /api/admin/eval/orchestrator-routing/run${QS}"
echo "   (optional live LLM calls; keep --limit small for smoke runs)"
EVAL_STATUS=$(curl -sS -w '%{http_code}' -o "$JSON_OUT" -X POST \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  "${BASE_URL}/api/admin/eval/orchestrator-routing/run${QS}")
if [[ "$EVAL_STATUS" -lt 200 || "$EVAL_STATUS" -ge 300 ]]; then
  echo "eval request failed (HTTP ${EVAL_STATUS}); see ${JSON_OUT}" >&2
  cat "$JSON_OUT" >&2
  exit 1
fi
if ! jq -e '.summaries' "$JSON_OUT" > /dev/null 2>&1; then
  echo "eval response missing .summaries; see ${JSON_OUT}" >&2
  cat "$JSON_OUT" >&2
  exit 1
fi

echo "-> Rendering markdown to ${MD_OUT}"
if jq -e '.markdownReport and (.markdownReport | length > 0)' "$JSON_OUT" > /dev/null 2>&1; then
  jq -r '.markdownReport' "$JSON_OUT" > "$MD_OUT"
else
  jq -r '
  "# Orchestrator Routing Eval — \(.startedAt)\n",
  "Evaluation ID: `\(.evaluationId)`",
  "Duration: \(.durationMs / 1000 | floor)s",
  "Rows: \(.results | length)",
  "",
  "## Provider Summary",
  "",
  "| Provider | Rows | Schema failures | Run false positives | Dangerous-to-run | Ambiguous-to-run | Accuracy |",
  "|---|---:|---:|---:|---:|---:|---:|",
  (.summaries[] |
    "| \(.provider) | \(.total) | \(.schemaFailures) | \(.runFalsePositives) | \(.dangerousToRun) | \(.ambiguousToRun) | \(.routeAccuracyPct | floor)% |"),
  "",
  "## Failed Rows",
  "",
  "| Row | Provider | Model | Route | Expected | Reason |",
  "|---|---|---|---|---|---|",
  (
    [.results[] | select((.schemaValid | not) or (.matched | not) or .runFalsePositive or .dangerousToRun or .ambiguousToRun)]
    | if length == 0 then
        "| - | - | - | - | - | none |"
      else
        .[] | "| \(.rowId) | \(.provider) | \(.model) | \(.route // "-") | \(.expectedRoutes | join(",")) | \(.failureReason // "route_mismatch") |"
      end
  )
' "$JSON_OUT" > "$MD_OUT"
fi

echo
echo "Done."
echo "  JSON: ${JSON_OUT}"
echo "  MD:   ${MD_OUT}"
echo
cat "$MD_OUT"
