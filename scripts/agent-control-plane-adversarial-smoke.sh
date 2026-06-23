#!/usr/bin/env bash
#
# Local adversarial smoke for the agent control plane + managed-AI read APIs.
#
# It uses deterministic API paths only: no live LLM call and no run submission.
# A Markdown report is written under scripts/agent-control-plane-smoke-results/.
#
# Usage:
#   ./scripts/agent-control-plane-adversarial-smoke.sh
#   BASE_URL=http://localhost:8080 EMAIL=revalidate-ent@test.local PASSWORD=... \
#     ./scripts/agent-control-plane-adversarial-smoke.sh
#
# Env:
#   BASE_URL   default http://localhost:8080
#   TOKEN      optional bearer/PAT; skips login when set
#   EMAIL      optional login email; defaults to ENTERPRISE row in ~/Desktop/local_creds.txt
#   PASSWORD   optional login password; defaults to matching local_creds row
#   CRED_FILE  default ~/Desktop/local_creds.txt
#   PROJECT_ID optional project id; when absent, code-mutating web requests must stay proposal-only

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
CRED_FILE="${CRED_FILE:-$HOME/Desktop/local_creds.txt}"
AUTH_TOKEN="${TOKEN:-${ACCESS_TOKEN:-}}"
EMAIL="${EMAIL:-}"
PASSWORD="${PASSWORD:-}"
TIER="${TIER:-ENTERPRISE}"
PROJECT_ID="${PROJECT_ID:-}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="${SCRIPT_DIR}/agent-control-plane-smoke-results"
mkdir -p "$OUT_DIR"
TS="$(date +%Y%m%d-%H%M%S)"
REPORT="${OUT_DIR}/agent-control-plane-smoke-${TS}.md"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/agent-control-plane-smoke.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

SESSION_ID="smoke-${TS}-${RANDOM}"
RESULTS=()

status_icon() {
  case "$1" in
    PASS) echo "PASS" ;;
    FAIL) echo "FAIL" ;;
    SKIP) echo "SKIP" ;;
    *) echo "$1" ;;
  esac
}

escape_cell() {
  local value="${1:-}"
  value="${value//$'\n'/ }"
  value="${value//$'\r'/ }"
  value="${value//|/\\|}"
  printf '%s' "$value"
}

add_result() {
  local status="$1" check="$2" expected="$3" observed="$4" notes="${5:-}"
  RESULTS+=(
    "| $(status_icon "$status") | $(escape_cell "$check") | $(escape_cell "$expected") | $(escape_cell "$observed") | $(escape_cell "$notes") |"
  )
}

json_post() {
  local path="$1" body="$2" out="$3"
  curl -sS -w '%{http_code}' -o "$out" -X POST \
    -H "Authorization: Bearer ${AUTH_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "$body" \
    "${BASE_URL}${path}"
}

json_get() {
  local path="$1" out="$2"
  curl -sS -w '%{http_code}' -o "$out" \
    -H "Authorization: Bearer ${AUTH_TOKEN}" \
    "${BASE_URL}${path}"
}

load_local_creds() {
  if [[ -n "$EMAIL" && -n "$PASSWORD" ]]; then
    return
  fi
  if [[ ! -f "$CRED_FILE" ]]; then
    return
  fi
  local row
  row="$(awk -F'\t' -v tier="$TIER" 'NR > 1 && $3 == tier {print $1 "\t" $2; exit}' "$CRED_FILE")"
  if [[ -n "$row" ]]; then
    EMAIL="${EMAIL:-$(printf '%s' "$row" | cut -f1)}"
    PASSWORD="${PASSWORD:-$(printf '%s' "$row" | cut -f2)}"
  fi
}

authenticate() {
  if [[ -n "$AUTH_TOKEN" ]]; then
    add_result "PASS" "auth source" "provided bearer token" "TOKEN/ACCESS_TOKEN present" "secret not printed"
    return
  fi

  load_local_creds
  if [[ -z "$EMAIL" || -z "$PASSWORD" ]]; then
    add_result "FAIL" "local credentials" "EMAIL/PASSWORD or $CRED_FILE $TIER row" "missing" "secret not printed"
    return 1
  fi

  local body out status
  body="$(jq -n --arg email "$EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')"
  out="${TMP_DIR}/login.json"
  status="$(curl -sS -w '%{http_code}' -o "$out" -X POST \
    -H 'Content-Type: application/json' -d "$body" \
    "${BASE_URL}/api/auth/login")"
  AUTH_TOKEN="$(jq -r '.accessToken // empty' "$out")"
  if [[ "$status" -ge 200 && "$status" -lt 300 && -n "$AUTH_TOKEN" ]]; then
    add_result "PASS" "local credential login" "HTTP 2xx + accessToken" "HTTP ${status}" "email=${EMAIL}; password not printed"
  else
    add_result "FAIL" "local credential login" "HTTP 2xx + accessToken" "HTTP ${status}" "email=${EMAIL}; password not printed"
    return 1
  fi
}

create_session() {
  local out status body
  out="${TMP_DIR}/session.json"
  body="$(
    jq -n --arg id "$SESSION_ID" --arg projectId "$PROJECT_ID" \
      '{id:$id, surface:"WEB", title:"Agent control-plane adversarial smoke", defaultExecutionMode:"REMOTE", approvalPolicy:"manual"}
       | if $projectId != "" then .projectId = $projectId else . end'
  )"
  status="$(json_post "/api/agent/sessions" "$body" "$out")"
  if [[ "$status" -ge 200 && "$status" -lt 300 && "$(jq -r '.id // empty' "$out")" == "$SESSION_ID" ]]; then
    add_result "PASS" "create WEB agent session" "session id echoed" "HTTP ${status}" "session=${SESSION_ID}"
  else
    add_result "FAIL" "create WEB agent session" "session id echoed" "HTTP ${status}" "$(cat "$out")"
  fi
}

check_capabilities() {
  local web_out cli_out status names
  web_out="${TMP_DIR}/cap-web.json"
  status="$(json_get "/api/agent/v2/capabilities?surface=WEB" "$web_out")"
  if [[ "$status" -ge 200 && "$status" -lt 300 ]]; then
    names="$(jq -r '[.[].name] | join(",")' "$web_out")"
    if jq -e '[.[].name] | index("local_code_task") == null and index("local_git_status") == null and index("local_git_diff") == null' "$web_out" >/dev/null; then
      add_result "PASS" "WEB capabilities exclude local hands" "no local_code_task/local_git_*" "$names" "TERM-AUDIT-SERVER-LOCAL-HANDS"
    else
      add_result "FAIL" "WEB capabilities exclude local hands" "no local_code_task/local_git_*" "$names" "local-only action leaked to WEB"
    fi
  else
    add_result "FAIL" "WEB capabilities endpoint" "HTTP 2xx" "HTTP ${status}" "$(cat "$web_out")"
  fi

  cli_out="${TMP_DIR}/cap-cli.json"
  status="$(json_get "/api/agent/v2/capabilities?surface=CLI" "$cli_out")"
  if [[ "$status" -ge 200 && "$status" -lt 300 ]]; then
    names="$(jq -r '[.[].name] | join(",")' "$cli_out")"
    if jq -e '[.[].name] | index("local_code_task") != null' "$cli_out" >/dev/null; then
      add_result "PASS" "CLI capabilities include local hands" "local_code_task present" "$names" "client-owned local action path"
    else
      add_result "FAIL" "CLI capabilities include local hands" "local_code_task present" "$names" "CLI cannot advertise local task capability"
    fi
  else
    add_result "FAIL" "CLI capabilities endpoint" "HTTP 2xx" "HTTP ${status}" "$(cat "$cli_out")"
  fi
}

check_managed_ai_reads() {
  local out status
  out="${TMP_DIR}/managed-balance.json"
  status="$(json_get "/api/billing/managed-ai/balance" "$out")"
  if [[ "$status" -ge 200 && "$status" -lt 300 && "$(jq -r '.routingState // empty' "$out")" != "" ]]; then
    add_result "PASS" "managed-AI balance" "HTTP 2xx + routingState" "$(jq -r '.routingState' "$out")" "read-only billing view"
  else
    add_result "FAIL" "managed-AI balance" "HTTP 2xx + routingState" "HTTP ${status}" "$(cat "$out")"
  fi

  out="${TMP_DIR}/managed-policy.json"
  status="$(json_get "/api/billing/managed-ai/policy" "$out")"
  if [[ "$status" -ge 200 && "$status" -lt 300 && "$(jq -r '.creditDefinition // empty' "$out")" != "" ]]; then
    add_result "PASS" "managed-AI policy" "HTTP 2xx + creditDefinition" "$(jq -r '.creditDefinition' "$out")" "TERM-AUDIT-SERVER-BILLING-VIEW"
  else
    add_result "FAIL" "managed-AI policy" "HTTP 2xx + creditDefinition" "HTTP ${status}" "$(cat "$out")"
  fi

  out="${TMP_DIR}/managed-events.json"
  status="$(json_get "/api/billing/managed-ai/events?period=current&limit=5" "$out")"
  if [[ "$status" -ge 200 && "$status" -lt 300 && "$(jq -r 'type' "$out")" == "array" ]]; then
    add_result "PASS" "managed-AI current events" "HTTP 2xx + array" "$(jq -r 'length' "$out") event(s)" "read-only event ledger"
  else
    add_result "FAIL" "managed-AI current events" "HTTP 2xx + array" "HTTP ${status}" "$(cat "$out")"
  fi

  out="${TMP_DIR}/managed-events-bad-period.json"
  status="$(json_get "/api/billing/managed-ai/events?period=previous&limit=5" "$out")"
  if [[ "$status" == "400" ]]; then
    add_result "PASS" "managed-AI bad period rejection" "HTTP 400" "HTTP ${status}" "historical pagination is intentionally unsupported"
  else
    add_result "FAIL" "managed-AI bad period rejection" "HTTP 400" "HTTP ${status}" "$(cat "$out")"
  fi
}

orchestrator_case() {
  local label="$1" message="$2" surface="$3" supports_local="$4" expected_route="$5" expected_run_allowed="$6"
  local out status body actual_route run_allowed turn_id
  turn_id="${SESSION_ID}-${label//[^A-Za-z0-9]/-}"
  out="${TMP_DIR}/orch-${label}.json"
  body="$(
    jq -n \
      --arg sessionId "$SESSION_ID" \
      --arg turnId "$turn_id" \
      --arg message "$message" \
      --arg surface "$surface" \
      --arg projectId "$PROJECT_ID" \
      --argjson supportsLocal "$supports_local" \
      '{
        sessionId:$sessionId,
        turnId:$turnId,
        message:$message,
        surface:$surface,
        clientVersion:"local-smoke",
        cwd:"/workspace",
        repoContext:{
          cwdName:"executespec",
          branch:"main",
          hasUncommittedChanges:false,
          changedFiles:[],
          instructionFiles:[],
          detectedStack:["java","react"]
        },
        clientCapabilities:{
          supportsLocalHands:$supportsLocal,
          supportsLocalReadContext:true,
          supportsRunConfirmation:true,
          supportsPlanGate:true,
          supportsEvidence:true,
          supportsDiff:true,
          supportsRevert:$supportsLocal,
          supportsStreaming:true
        },
        mode:{
          permissionMode:"auto",
          requestedExecutionMode:"remote",
          requiresConfirmationByDefault:true
        },
        attachments:[]
      }
      | if $projectId != "" then .projectId = $projectId else . end'
  )"
  status="$(json_post "/api/orchestrator/turn" "$body" "$out")"
  if [[ "$status" -lt 200 || "$status" -ge 300 ]]; then
    add_result "FAIL" "orchestrator ${label}" "${expected_route}, runApiAllowed=${expected_run_allowed}" "HTTP ${status}" "$(cat "$out")"
    return
  fi
  actual_route="$(jq -r '.route // empty' "$out")"
  run_allowed="$(jq -r 'if (.policy | type) == "object" and (.policy | has("runApiAllowed")) then .policy.runApiAllowed else empty end' "$out")"
  if [[ "$actual_route" == "$expected_route" && "$run_allowed" == "$expected_run_allowed" ]]; then
    add_result "PASS" "orchestrator ${label}" "${expected_route}, runApiAllowed=${expected_run_allowed}" "${actual_route}, runApiAllowed=${run_allowed}" "$message"
  else
    add_result "FAIL" "orchestrator ${label}" "${expected_route}, runApiAllowed=${expected_run_allowed}" "${actual_route}, runApiAllowed=${run_allowed}" "$message"
  fi
}

stream_turn_case() {
  local label="$1" message="$2" expected_route="$3" expected_action="$4"
  local out status body observed_route observed_action has_done has_error
  out="${TMP_DIR}/stream-${label}.sse"
  body="$(
    jq -n \
      --arg message "$message" \
      --arg projectId "$PROJECT_ID" \
      '{
        message:$message,
        clientCapabilities:{
          localTools:false,
          interactive:true,
          canRenderDiff:true,
          canRenderTui:false
        },
        repoContext:{
          cwdName:"executespec",
          branch:"main",
          changedFiles:[],
          detectedStack:["java","react"]
        }
      }
      | if $projectId != "" then .projectId = $projectId else . end'
  )"
  status="$(json_post "/api/agent/sessions/${SESSION_ID}/turns/stream" "$body" "$out")"
  observed_route="$(grep -E '"route"[[:space:]]*:[[:space:]]*"' "$out" | head -1 | sed -E 's/.*"route"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/' || true)"
  observed_action="$(grep -E '"type"[[:space:]]*:[[:space:]]*"' "$out" | grep -v '"event"' | head -1 | sed -E 's/.*"type"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/' || true)"
  has_done="$(grep -c '^event:done' "$out" || true)"
  has_error="$(grep -c '^event:error' "$out" || true)"
  if [[ "$status" -ge 200 && "$status" -lt 300 \
      && "$observed_route" == "$expected_route" \
      && "$observed_action" == "$expected_action" \
      && "$has_done" -gt 0 \
      && "$has_error" -eq 0 ]]; then
    add_result "PASS" "stream turn ${label}" "${expected_route} + ${expected_action} + done" "${observed_route} + ${observed_action}" "POST-SSE route parity"
  else
    add_result "FAIL" "stream turn ${label}" "${expected_route} + ${expected_action} + done" "HTTP ${status}; route=${observed_route}; action=${observed_action}; done=${has_done}; error=${has_error}" "$(tr '\n' ' ' < "$out" | cut -c1-500)"
  fi
}

check_confirm_rejects_non_run() {
  local out status body turn_id
  turn_id="${SESSION_ID}-local-git"
  out="${TMP_DIR}/confirm-local-git.json"
  body="$(jq -n --arg sessionId "$SESSION_ID" --arg turnId "$turn_id" '{sessionId:$sessionId, turnId:$turnId, confirmed:true}')"
  status="$(json_post "/api/orchestrator/turn/confirm" "$body" "$out")"
  if [[ "$status" == "409" && "$(cat "$out")" == *"not a run route"* ]]; then
    add_result "PASS" "confirm rejects local action" "HTTP 409 not a run route" "HTTP ${status}" "local/git turns cannot submit /api/runs"
  else
    add_result "FAIL" "confirm rejects local action" "HTTP 409 not a run route" "HTTP ${status}" "$(cat "$out")"
  fi
}

check_agent_session_replay() {
  local out status observed
  out="${TMP_DIR}/session-read.json"
  status="$(json_get "/api/agent/sessions/${SESSION_ID}" "$out")"
  if [[ "$status" -ge 200 && "$status" -lt 300 && "$(jq -r '.id // empty' "$out")" == "$SESSION_ID" ]]; then
    add_result "PASS" "agent session read" "HTTP 2xx + same id" "HTTP ${status}" "session=${SESSION_ID}"
  else
    add_result "FAIL" "agent session read" "HTTP 2xx + same id" "HTTP ${status}" "$(cat "$out")"
  fi

  out="${TMP_DIR}/session-list.json"
  status="$(json_get "/api/agent/sessions?limit=5" "$out")"
  if [[ "$status" -ge 200 && "$status" -lt 300 && "$(jq -r 'type' "$out")" == "array" ]]; then
    if jq -e --arg id "$SESSION_ID" 'map(.id) | index($id) != null' "$out" >/dev/null; then
      add_result "PASS" "agent session list" "new session appears" "$(jq -r 'length' "$out") session(s)" "bounded list contract"
    else
      add_result "FAIL" "agent session list" "new session appears" "$(jq -r '[.[].id] | join(",")' "$out")" "session missing from recent list"
    fi
  else
    add_result "FAIL" "agent session list" "HTTP 2xx + array" "HTTP ${status}" "$(cat "$out")"
  fi

  out="${TMP_DIR}/session-turns.json"
  status="$(json_get "/api/agent/sessions/${SESSION_ID}/turns?limit=20" "$out")"
  if [[ "$status" -ge 200 && "$status" -lt 300 && "$(jq -r 'type' "$out")" == "array" ]]; then
    observed="$(jq -r '[.[].route] | map(select(. != null)) | unique | join(",")' "$out")"
    if jq -e '[.[].route] | index("LOCAL_TOOL_ACTION") != null and index("SECURITY_REJECT") != null' "$out" >/dev/null; then
      add_result "PASS" "agent turn replay" "persisted orchestrator routes" "$observed" "turn history hydrates stored decisions"
    else
      add_result "FAIL" "agent turn replay" "LOCAL_TOOL_ACTION + SECURITY_REJECT" "$observed" "stored route decisions missing"
    fi
  else
    add_result "FAIL" "agent turn replay" "HTTP 2xx + array" "HTTP ${status}" "$(cat "$out")"
  fi

  out="${TMP_DIR}/session-events.json"
  status="$(json_get "/api/agent/sessions/${SESSION_ID}/events" "$out")"
  if [[ "$status" -ge 200 && "$status" -lt 300 && "$(jq -r 'type' "$out")" == "array" ]]; then
    observed="$(jq -r '[.[].type] | unique | join(",")' "$out")"
    if jq -e '[.[].type] | index("ORCHESTRATOR_DECISION") != null' "$out" >/dev/null; then
      add_result "PASS" "agent event replay" "ORCHESTRATOR_DECISION persisted" "$observed" "event stream audit trail"
    else
      add_result "FAIL" "agent event replay" "ORCHESTRATOR_DECISION persisted" "$observed" "event missing"
    fi
  else
    add_result "FAIL" "agent event replay" "HTTP 2xx + array" "HTTP ${status}" "$(cat "$out")"
  fi
}

check_action_lifecycle() {
  local out status body action_id decision_status event_status result_status events_out observed
  out="${TMP_DIR}/action-plan.json"
  body="$(
    jq -n --arg projectId "$PROJECT_ID" \
      '{
        message:"commit all files and push to git",
        surface:"CLI",
        repoContext:{cwdName:"executespec", branch:"main", changedFiles:["README.md"], detectedStack:["java"]},
        clientCapabilities:{localTools:true, interactive:true, canRenderDiff:true, canRenderTui:true}
      }
      | if $projectId != "" then .projectId = $projectId else . end'
  )"
  status="$(json_post "/api/agent/v2/sessions/${SESSION_ID}/actions/plan" "$body" "$out")"
  action_id="$(jq -r '.actionId // empty' "$out" 2>/dev/null || true)"
  decision_status="$(jq -r '.decision.proposedAction.type // .decision.intent // empty' "$out" 2>/dev/null || true)"
  if [[ "$status" -ge 200 && "$status" -lt 300 && -n "$action_id" ]]; then
    add_result "PASS" "agent action plan" "HTTP 2xx + actionId" "${action_id} ${decision_status}" "local action planning contract"
  else
    add_result "FAIL" "agent action plan" "HTTP 2xx + actionId" "HTTP ${status}" "$(cat "$out")"
    return
  fi

  out="${TMP_DIR}/action-event.json"
  body="$(jq -n '{type:"PERMISSION_REQUESTED", payload:{kind:"local_git_task", command:"git status"}}')"
  event_status="$(json_post "/api/agent/v2/sessions/${SESSION_ID}/actions/${action_id}/events" "$body" "$out")"
  if [[ "$event_status" -ge 200 && "$event_status" -lt 300 ]]; then
    add_result "PASS" "agent action event write" "HTTP 2xx" "HTTP ${event_status}" "permission event accepted"
  else
    add_result "FAIL" "agent action event write" "HTTP 2xx" "HTTP ${event_status}" "$(cat "$out")"
  fi

  out="${TMP_DIR}/action-result.json"
  body="$(jq -n '{status:"COMPLETED", summary:"smoke completed local git task", touchedFiles:[], verification:{state:"skipped", reason:"api smoke"}}')"
  result_status="$(json_post "/api/agent/v2/sessions/${SESSION_ID}/actions/${action_id}/result" "$body" "$out")"
  if [[ "$result_status" -ge 200 && "$result_status" -lt 300 && "$(jq -r '.status // empty' "$out")" == "COMPLETED" ]]; then
    add_result "PASS" "agent action result write" "HTTP 2xx + COMPLETED" "HTTP ${result_status}" "result status persisted"
  else
    add_result "FAIL" "agent action result write" "HTTP 2xx + COMPLETED" "HTTP ${result_status}" "$(cat "$out")"
  fi

  events_out="${TMP_DIR}/action-events-after.json"
  status="$(json_get "/api/agent/sessions/${SESSION_ID}/events" "$events_out")"
  observed="$(jq -r --arg id "$action_id" '[.[] | select(.actionId == $id) | .type] | unique | join(",")' "$events_out" 2>/dev/null || true)"
  if [[ "$status" -ge 200 && "$status" -lt 300 && "$observed" == *"PERMISSION_REQUESTED"* && "$observed" == *"ACTION_RESULT"* ]]; then
    add_result "PASS" "agent action event replay" "PERMISSION_REQUESTED + ACTION_RESULT" "$observed" "action lifecycle readback"
  else
    add_result "FAIL" "agent action event replay" "PERMISSION_REQUESTED + ACTION_RESULT" "$observed" "$(cat "$events_out")"
  fi
}

write_report() {
  local pass fail skip
  pass="$(printf '%s\n' "${RESULTS[@]}" | grep -c '^| PASS |' || true)"
  fail="$(printf '%s\n' "${RESULTS[@]}" | grep -c '^| FAIL |' || true)"
  skip="$(printf '%s\n' "${RESULTS[@]}" | grep -c '^| SKIP |' || true)"
  {
    echo "# Agent Control Plane Adversarial Smoke - ${TS}"
    echo
    echo "- Base URL: \`${BASE_URL}\`"
    echo "- Local credentials file checked: \`${CRED_FILE}\`"
    echo "- Account used: \`${EMAIL:-token-auth}\`"
    echo "- Password/token values: not printed"
    echo "- Summary: ${pass} pass, ${fail} fail, ${skip} skip"
    echo
    echo "| Status | Check | Expected | Observed | Notes |"
    echo "| --- | --- | --- | --- | --- |"
    printf '%s\n' "${RESULTS[@]}"
    echo
    if [[ "$fail" -gt 0 ]]; then
      echo "## Action Items"
      echo
      echo "- Investigate every FAIL row before calling the launch criteria complete."
    else
      echo "## Action Items"
      echo
      echo "- None for local deterministic smoke. External live smokes remain separate: provider prompt eval and Gmail OAuth delivery."
    fi
  } > "$REPORT"
  echo "$REPORT"
}

main() {
  authenticate || true
  if [[ -n "$AUTH_TOKEN" ]]; then
    create_session
    check_capabilities
    check_managed_ai_reads
    orchestrator_case "read-only" "Explain this repo" "WEB" false "READ_ONLY_CODE_HELP" false
    orchestrator_case "plan-only" "Write a plan to add OAuth" "WEB" false "PLAN_ONLY" false
    orchestrator_case "chat-only-patch" "Generate a patch in chat only" "WEB" false "CHAT_ONLY" false
    orchestrator_case "local-git" "commit all files and push to git" "CLI" true "LOCAL_TOOL_ACTION" false
    if [[ -n "$PROJECT_ID" ]]; then
      orchestrator_case "code-run-proposal" "implement OAuth login" "WEB" false "CODE_RUN_REQUIRED" true
    else
      orchestrator_case "code-run-missing-context" "implement OAuth login" "WEB" false "IMPLEMENTATION_PROPOSAL" false
    fi
    orchestrator_case "security-reject" "delete everything" "WEB" false "SECURITY_REJECT" false
    stream_turn_case "local-git" "commit all files and push to git" "LOCAL_TOOL_ACTION" "local_tool_action"
    check_confirm_rejects_non_run
    check_agent_session_replay
    check_action_lifecycle
  fi
  report_path="$(write_report)"
  echo "Report: ${report_path}"
  if grep -q '^| FAIL |' "$report_path"; then
    exit 1
  fi
}

main "$@"
