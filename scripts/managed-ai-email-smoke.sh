#!/usr/bin/env bash
#
# Operator smoke for managed-AI routing email delivery.
#
# Hits:
#   POST /api/admin/test-managed-ai-email
#
# Usage:
#   TOKEN=... TO=operator@example.com ./scripts/managed-ai-email-smoke.sh
#   BASE_URL=https://dev.executespec.ai TOKEN=... TO=operator@example.com \
#     ./scripts/managed-ai-email-smoke.sh --state EXHAUSTED
#
# Env:
#   BASE_URL  default http://localhost:8080
#   TOKEN     optional pre-minted SUPER_ADMIN bearer/PAT; skips login when set
#   EMAIL     default admin@executespec.ai
#   PASSWORD  default local/admin seed password; not printed
#   TO        required recipient email

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="${SCRIPT_DIR}/managed-ai-email-smoke-results"
mkdir -p "$OUT_DIR"

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="${EMAIL:-admin@executespec.ai}"
PASSWORD="${PASSWORD:?PASSWORD environment variable is required}"
AUTH_TOKEN="${TOKEN:-${ACCESS_TOKEN:-}}"
TO="${TO:-}"
STATE="CHEAP_ONLY"
TS="$(date +%Y%m%d-%H%M%S)"
JSON_OUT="${OUT_DIR}/managed-ai-email-smoke-${TS}.json"
MD_OUT="${OUT_DIR}/managed-ai-email-smoke-${TS}.md"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --to)    TO="$2"; shift 2 ;;
    --state) STATE="$2"; shift 2 ;;
    -h|--help)
      sed -n '3,18p' "$0"
      exit 0
      ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "$TO" ]]; then
  echo "recipient required: set TO=operator@example.com or pass --to operator@example.com" >&2
  exit 2
fi

case "$STATE" in
  FRONTIER_LOCKED|CHEAP_ONLY|EXHAUSTED) ;;
  *)
    echo "invalid --state ${STATE}; expected FRONTIER_LOCKED, CHEAP_ONLY, or EXHAUSTED" >&2
    exit 2
    ;;
esac

if [[ -n "$AUTH_TOKEN" ]]; then
  echo "-> Using provided bearer token."
else
  echo "-> Authenticating as ${EMAIL}..."
  LOGIN_BODY=$(jq -n --arg e "$EMAIL" --arg p "$PASSWORD" '{email:$e, password:$p}')
  LOGIN_JSON="$(mktemp "${TMPDIR:-/tmp}/managed-ai-email-login.XXXXXX.json")"
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

BODY=$(jq -n --arg to "$TO" --arg routingState "$STATE" '{to:$to, routingState:$routingState}')
RESP_JSON="$(mktemp "${TMPDIR:-/tmp}/managed-ai-email-smoke.XXXXXX.json")"

echo "-> POST /api/admin/test-managed-ai-email"
HTTP_STATUS=$(curl -sS -w '%{http_code}' -o "$RESP_JSON" -X POST \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "$BODY" \
  "${BASE_URL}/api/admin/test-managed-ai-email")

if [[ "$HTTP_STATUS" -lt 200 || "$HTTP_STATUS" -ge 300 ]]; then
  echo "managed-AI email smoke failed (HTTP ${HTTP_STATUS})" >&2
  cat "$RESP_JSON" >&2
  jq -n \
    --arg baseUrl "$BASE_URL" \
    --arg to "$TO" \
    --arg routingState "$STATE" \
    --arg status "$HTTP_STATUS" \
    --slurpfile response "$RESP_JSON" \
    '{
      ok: false,
      baseUrl: $baseUrl,
      recipient: $to,
      routingState: $routingState,
      httpStatus: ($status | tonumber),
      response: ($response[0] // {}),
      deliveryProof: "not_attempted_request_failed"
    }' > "$JSON_OUT"
  rm -f "$RESP_JSON"
  exit 1
fi

echo "-> Dispatch accepted."
jq '.' "$RESP_JSON"
if [[ "$BASE_URL" == http://localhost:* || "$BASE_URL" == http://127.0.0.1:* ]]; then
  DELIVERY_PROOF="not_proven_local_environment"
  DELIVERY_NOTE="Local dispatch reached the backend endpoint. Gmail delivery is not proven here; confirm backend logs for the expected local Gmail-unavailable fallback."
else
  DELIVERY_PROOF="dispatch_accepted_external_inbox_confirmation_required"
  DELIVERY_NOTE="Deployed dispatch reached the backend endpoint. Confirm Gmail/OAuth provider logs and the recipient inbox before treating delivery as proven."
fi
jq -n \
  --arg baseUrl "$BASE_URL" \
  --arg to "$TO" \
  --arg routingState "$STATE" \
  --arg status "$HTTP_STATUS" \
  --arg deliveryProof "$DELIVERY_PROOF" \
  --arg deliveryNote "$DELIVERY_NOTE" \
  --slurpfile response "$RESP_JSON" \
  '{
    ok: true,
    baseUrl: $baseUrl,
    recipient: $to,
    routingState: $routingState,
    httpStatus: ($status | tonumber),
    response: ($response[0] // {}),
    deliveryProof: $deliveryProof,
    deliveryNote: $deliveryNote
  }' > "$JSON_OUT"
{
  echo "# Managed AI Email Smoke"
  echo
  echo "- Base URL: \`$BASE_URL\`"
  echo "- Recipient: \`$TO\`"
  echo "- Routing state: \`$STATE\`"
  echo "- HTTP status: \`$HTTP_STATUS\`"
  echo "- Delivery proof: \`$DELIVERY_PROOF\`"
  echo
  echo "$DELIVERY_NOTE"
  echo
  echo "## Response"
  echo
  echo '```json'
  jq '.' "$RESP_JSON"
  echo '```'
} > "$MD_OUT"
rm -f "$RESP_JSON"
echo
echo "Report: $MD_OUT"
echo "JSON:   $JSON_OUT"
echo "$DELIVERY_NOTE"
