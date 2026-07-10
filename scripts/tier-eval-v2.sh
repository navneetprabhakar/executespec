#!/usr/bin/env bash
#
# Tier eval v2 — production-shaped per-tier evaluation for each (provider, tier) slot.
#
# Hits POST /api/admin/eval/v2/run (requires SUPER_ADMIN) and renders the JSON response into
# a markdown report. Distinct from scripts/v2-eval.sh — that runs full pipelines against
# 3 complexity bands; this one isolates each model + tier slot with prompts that mirror the
# real agents (SmartClassifierAgent, OutlinePlannerAgent).
#
# Usage:
#   ./scripts/tier-eval-v2.sh                                # all tiers, all providers
#   ./scripts/tier-eval-v2.sh --tiers LIGHTWEIGHT            # one tier only
#   ./scripts/tier-eval-v2.sh --providers anthropic,openai   # subset of providers
#
# Env:
#   BASE_URL  default http://localhost:8080
#   EMAIL     default admin@executespec.ai
#   PASSWORD  required if EMAIL is not the default
#
# Output:
#   scripts/tier-eval-v2-results/eval-<timestamp>.json   # raw report
#   scripts/tier-eval-v2-results/eval-<timestamp>.md     # human-readable

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="${SCRIPT_DIR}/tier-eval-v2-results"
mkdir -p "$OUT_DIR"

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="${EMAIL:-admin@executespec.ai}"
PASSWORD="${PASSWORD:?PASSWORD environment variable is required}"

TIERS=""
PROVIDERS=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tiers)     TIERS="$2"; shift 2 ;;
    --providers) PROVIDERS="$2"; shift 2 ;;
    -h|--help)   sed -n '3,21p' "$0"; exit 0 ;;
    *)           echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

TS="$(date +%Y%m%d-%H%M%S)"
JSON_OUT="${OUT_DIR}/eval-${TS}.json"
MD_OUT="${OUT_DIR}/eval-${TS}.md"

echo "→ Authenticating as ${EMAIL}..."
LOGIN_BODY=$(jq -n --arg e "$EMAIL" --arg p "$PASSWORD" '{email:$e, password:$p}')
TOKEN=$(curl -s -X POST -H 'Content-Type: application/json' -d "$LOGIN_BODY" \
  "${BASE_URL}/api/auth/login" | jq -r '.accessToken // empty')
if [[ -z "$TOKEN" ]]; then
  echo "✗ login failed for ${EMAIL}" >&2
  exit 1
fi

QS=""
[[ -n "$TIERS" ]]     && QS="?tiers=${TIERS}"
[[ -n "$PROVIDERS" ]] && QS="${QS:+${QS}&}providers=${PROVIDERS}"
[[ -n "$QS" && "$QS" != "?"* ]] && QS="?${QS}"

echo "→ POST /api/admin/eval/v2/run${QS}"
echo "   (this can take a few minutes — sequential, real LLM calls)"
curl -s -X POST -H "Authorization: Bearer ${TOKEN}" \
  "${BASE_URL}/api/admin/eval/v2/run${QS}" > "$JSON_OUT"
if ! jq -e '.results' "$JSON_OUT" > /dev/null 2>&1; then
  echo "✗ eval response missing .results — see ${JSON_OUT}" >&2
  cat "$JSON_OUT" >&2
  exit 1
fi

echo "→ Rendering markdown to ${MD_OUT}"
jq -r '
  def fmtCost: . | tostring | if test("e") then . else (. * 10000 | round / 10000 | tostring) end;
  "# Tier Eval v2 — \(.startedAt)\n",
  "Evaluation ID: `\(.evaluationId)`",
  "Duration: \(.durationMs / 1000 | floor)s",
  "Total cases: \(.results | length)",
  "",
  "## Results grid",
  "",
  "| Case | Tier | Provider | Model | Score | Mode | Tool | Latency | In tok | Out tok | Cache% | Cost | Detail |",
  "|---|---|---|---|---:|---|---|---:|---:|---:|---:|---:|---|",
  (.results[] |
    "| \(.caseName) | \(.tier) | \(.provider) | \(.model) | \(.correctnessScore) | \(.failureMode) | \(.firstToolCallName // "-") | \(.latencyMs)ms | \(.inputTokens) | \(.outputTokens) | \((if (.inputTokens + .cacheReadTokens) > 0 then ((.cacheReadTokens * 100) / (.inputTokens + .cacheReadTokens) | floor) else 0 end))% | $\(.costUsd | fmtCost) | \(.detail // "") |"),
  "",
  "## Per-provider summary",
  "",
  (
    [.results | group_by(.provider)[] |
      {
        provider: .[0].provider,
        cases: length,
        avgScore: (map(.correctnessScore) | add / length | floor),
        okRate: (((map(select(.failureMode == "OK")) | length) * 100) / length | floor),
        totalCost: (map(.costUsd) | add),
        avgLatency: (map(.latencyMs) | add / length | floor)
      }
    ] |
    "| Provider | Cases | Avg score | OK rate | Avg latency | Total cost |",
    "|---|---:|---:|---:|---:|---:|",
    (.[] | "| \(.provider) | \(.cases) | \(.avgScore)/100 | \(.okRate)% | \(.avgLatency)ms | $\(.totalCost | fmtCost) |")
  ),
  "",
  "## Per-tier summary",
  "",
  (
    [.results | group_by(.tier)[] |
      {
        tier: .[0].tier,
        avgScore: (map(.correctnessScore) | add / length | floor),
        byProvider: [group_by(.provider)[] | {provider: .[0].provider, score: (map(.correctnessScore) | add / length | floor)}]
      }
    ] |
    "| Tier | Anthropic | OpenAI | Gemini |",
    "|---|---:|---:|---:|",
    (.[] | "| \(.tier) | \([.byProvider[] | select(.provider == "anthropic") | .score][0] // "-") | \([.byProvider[] | select(.provider == "openai") | .score][0] // "-") | \([.byProvider[] | select(.provider == "gemini") | .score][0] // "-") |")
  )
' "$JSON_OUT" > "$MD_OUT"

echo
echo "✓ Done."
echo "  JSON: ${JSON_OUT}"
echo "  MD:   ${MD_OUT}"
echo
head -60 "$MD_OUT"
