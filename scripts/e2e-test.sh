#!/usr/bin/env bash
#
# ExecuteSpec E2E Test Harness
# Usage:
#   ./scripts/e2e-test.sh <provider>   # Run 7 tests (google|anthropic|openai) — standard set
#   ./scripts/e2e-test.sh report       # Generate consolidated report from all batches
#
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="${E2E_EMAIL:?E2E_EMAIL environment variable is required}"
PASSWORD="${E2E_PASSWORD:?E2E_PASSWORD environment variable is required}"
POLL_INTERVAL=10       # seconds between status polls
PLAN_WAIT_TIMEOUT=120  # 2 minutes to wait for plan (cancel test)

# Per-language timeout ceilings (seconds)
# Simple: Python CLI, Static HTML, Ruby script
# Moderate: Java Maven, TypeScript, PHP
# Complex: Go (compilation), Rust (cargo build), multi-file JS/Express
RUN_TIMEOUT_DEFAULT=600  # fallback for unknown languages

# Timeout lookup (bash 3.2 compatible — no associative arrays on macOS)
get_language_timeout() {
    case "$1" in
        Python)       echo 300 ;;
        JavaScript)   echo 600 ;;
        Java)         echo 600 ;;
        TypeScript)   echo 600 ;;
        "Static HTML") echo 300 ;;
        Cancel)       echo 180 ;;
        Go)           echo 900 ;;
        Rust)         echo 900 ;;
        Ruby)         echo 300 ;;
        PHP)          echo 600 ;;
        *)            echo "$RUN_TIMEOUT_DEFAULT" ;;
    esac
}
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESULTS_DIR="$SCRIPT_DIR"

# ── Colors ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

# ── Test cases (standard set — same for ALL providers for comparable results) ──
declare -a LANGUAGES=(
    "Python"
    "JavaScript"
    "Java"
    "TypeScript"
    "Go"
    "Static HTML"
    "Cancel"
)

declare -a REQUESTS=(
    "Create a Python CLI calculator with add, subtract, multiply, divide operations. Single file, no external dependencies."
    "Create a Node.js Express API with GET /health and POST /echo endpoints. Include package.json."
    "Create a Java Maven project with a Main class that prints hello world. Include pom.xml."
    "Create a TypeScript Node.js script that validates email addresses using regex. Include tsconfig.json and package.json."
    "Create a Go HTTP server with GET /health and POST /echo endpoints. Single main.go file with go.mod."
    "Create a static HTML page with CSS that shows a responsive portfolio layout. Single index.html file."
    "Build a complex microservices architecture with 20 services including API gateway, service mesh, event sourcing"
)

# ── Helper functions ────────────────────────────────────────────────────────

log() { echo -e "${BLUE}[$(date +%H:%M:%S)]${NC} $*" >&2; }
ok()  { echo -e "${GREEN}  ✅ $*${NC}" >&2; }
fail() { echo -e "${RED}  ❌ $*${NC}" >&2; }
warn() { echo -e "${YELLOW}  ⚠️  $*${NC}" >&2; }

# Authenticate and get JWT token
auth_login() {
    local resp
    resp=$(curl -s -X POST "$BASE_URL/api/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" 2>&1)

    TOKEN=$(echo "$resp" | jq -r '.accessToken // empty')
    if [[ -z "$TOKEN" ]]; then
        echo -e "${RED}Failed to authenticate: $resp${NC}"
        exit 1
    fi
    log "Authenticated as $EMAIL"
}

# Submit a run. Args: $1=userRequest, $2=skipApproval (true/false), $3=aiProvider (optional)
submit_run() {
    local request="$1"
    local skip="${2:-true}"
    local ai_provider="${3:-}"
    local provider_field=""
    if [[ -n "$ai_provider" ]]; then
        provider_field=",\"aiProvider\":\"$(echo "$ai_provider" | tr '[:lower:]' '[:upper:]')\""
    fi
    local resp
    resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/runs" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"userRequest\":\"$request\",\"skipApproval\":$skip$provider_field}")

    local http_code=$(echo "$resp" | tail -1)
    local body=$(echo "$resp" | sed '$d')

    if [[ "$http_code" != "202" && "$http_code" != "200" ]]; then
        echo "SUBMIT_FAILED|$http_code|$body"
        return 1
    fi

    local run_id=$(echo "$body" | jq -r '.runId // empty')
    if [[ -z "$run_id" ]]; then
        echo "SUBMIT_FAILED|$http_code|no runId in response"
        return 1
    fi
    echo "$run_id"
}

# Get run detail. Args: $1=runId
get_run() {
    curl -s "$BASE_URL/api/runs/$1" \
        -H "Authorization: Bearer $TOKEN"
}

# Poll until terminal state. Args: $1=runId, $2=timeout
# Returns: status|duration_seconds
poll_until_terminal() {
    local run_id="$1"
    local timeout="${2:-$RUN_TIMEOUT_DEFAULT}"
    local start_time=$(date +%s)
    local last_status=""

    while true; do
        local elapsed=$(( $(date +%s) - start_time ))
        if (( elapsed > timeout )); then
            echo "TIMEOUT|$elapsed"
            return 1
        fi

        local detail=$(get_run "$run_id")
        local status=$(echo "$detail" | jq -r '.status // "UNKNOWN"')

        if [[ "$status" != "$last_status" ]]; then
            log "  Run $run_id: $status (${elapsed}s)"
            last_status="$status"
        fi

        case "$status" in
            COMPLETE|PARTIAL|FAILED|CANCELLED|REJECTED)
                echo "$status|$elapsed"
                return 0
                ;;
            *)
                sleep $POLL_INTERVAL
                ;;
        esac
    done
}

# Wait for specific state. Args: $1=runId, $2=target_status, $3=timeout
wait_for_state() {
    local run_id="$1"
    local target="$2"
    local timeout="${3:-$PLAN_WAIT_TIMEOUT}"
    local start_time=$(date +%s)

    while true; do
        local elapsed=$(( $(date +%s) - start_time ))
        if (( elapsed > timeout )); then
            return 1
        fi

        local detail=$(get_run "$run_id")
        local status=$(echo "$detail" | jq -r '.status // "UNKNOWN"')

        if [[ "$status" == "$target" ]]; then
            return 0
        fi

        # If it already passed the target state, bail
        case "$status" in
            COMPLETE|PARTIAL|FAILED|CANCELLED|REJECTED)
                return 1
                ;;
        esac

        sleep 2
    done
}

# Cancel a run. Args: $1=runId
cancel_run() {
    curl -s -X DELETE "$BASE_URL/api/runs/$1" \
        -H "Authorization: Bearer $TOKEN"
}

# Get validation report. Args: $1=runId
get_report() {
    local resp
    resp=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/runs/$1/report" \
        -H "Authorization: Bearer $TOKEN")
    local http_code=$(echo "$resp" | tail -1)
    local body=$(echo "$resp" | sed '$d')

    if [[ "$http_code" == "200" ]]; then
        echo "HAS_REPORT"
    else
        echo "NO_REPORT|$http_code"
    fi
}

# Share report and verify public access. Args: $1=runId
test_share_report() {
    local share_resp
    share_resp=$(curl -s -X POST "$BASE_URL/api/runs/$1/report/share" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json")

    local token=$(echo "$share_resp" | jq -r '.shareToken // empty')
    if [[ -z "$token" ]]; then
        echo "SHARE_FAILED"
        return
    fi

    # Access shared report publicly (no auth)
    local public_resp
    public_resp=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/reports/shared/$token")
    local http_code=$(echo "$public_resp" | tail -1)

    if [[ "$http_code" == "200" ]]; then
        echo "SHARE_OK"
    else
        echo "SHARE_FAILED|$http_code"
    fi
}

# Start preview and check it's running. Args: $1=runId
test_preview() {
    local run_id="$1"
    local resp
    resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/runs/$run_id/preview/start" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d '{}')
    local http_code=$(echo "$resp" | tail -1)
    local body=$(echo "$resp" | sed '$d')

    if [[ "$http_code" != "200" ]]; then
        echo "PREVIEW_START_FAILED|$http_code"
        return
    fi

    # Poll preview status for up to 120s
    local start_time=$(date +%s)
    while true; do
        local elapsed=$(( $(date +%s) - start_time ))
        if (( elapsed > 120 )); then
            echo "PREVIEW_TIMEOUT"
            return
        fi

        local status_resp
        status_resp=$(curl -s "$BASE_URL/api/runs/$run_id/preview/status" \
            -H "Authorization: Bearer $TOKEN")
        local preview_status=$(echo "$status_resp" | jq -r '.status // "UNKNOWN"')

        case "$preview_status" in
            RUNNING)
                local preview_url=$(echo "$status_resp" | jq -r '.url // "none"')
                echo "PREVIEW_OK|$preview_url"
                return
                ;;
            FAILED)
                echo "PREVIEW_FAILED"
                return
                ;;
            *)
                sleep 5
                ;;
        esac
    done
}

# Push to GitHub (new repo). Args: $1=runId
test_github_new() {
    local run_id="$1"
    local resp
    resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/runs/$run_id/push-github" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d '{"repoMode":"NEW"}')
    local http_code=$(echo "$resp" | tail -1)
    local body=$(echo "$resp" | sed '$d')

    if [[ "$http_code" == "200" ]]; then
        local repo_url=$(echo "$body" | jq -r '.repoUrl // empty')
        if [[ -n "$repo_url" ]]; then
            echo "GITHUB_NEW_OK|$repo_url"
        else
            echo "GITHUB_NEW_FAILED|no repoUrl"
        fi
    else
        echo "GITHUB_NEW_FAILED|$http_code"
    fi
}

# Push to GitHub (existing repo + PR). Args: $1=runId, $2=repoUrl
test_github_existing() {
    local run_id="$1"
    local repo_url="$2"
    local resp
    resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/runs/$run_id/push-github" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"repoMode\":\"EXISTING\",\"repoUrl\":\"$repo_url\"}")
    local http_code=$(echo "$resp" | tail -1)
    local body=$(echo "$resp" | sed '$d')

    if [[ "$http_code" == "200" ]]; then
        local pr_url=$(echo "$body" | jq -r '.prUrl // empty')
        if [[ -n "$pr_url" && "$pr_url" != "null" ]]; then
            echo "GITHUB_PR_OK|$pr_url"
        else
            echo "GITHUB_PR_FAILED|no prUrl"
        fi
    else
        echo "GITHUB_PR_FAILED|$http_code"
    fi
}

# Copilot chat test. Args: $1=runId
test_copilot_chat() {
    local run_id="$1"

    # Send a chat message
    local resp
    resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/runs/$run_id/chat" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d '{"message":"What files were created in this run?"}')
    local http_code=$(echo "$resp" | tail -1)
    local body=$(echo "$resp" | sed '$d')

    if [[ "$http_code" != "200" ]]; then
        echo "CHAT_FAILED|$http_code"
        return
    fi

    local reply=$(echo "$body" | jq -r '.reply // empty')
    if [[ -z "$reply" ]]; then
        echo "CHAT_FAILED|empty reply"
        return
    fi

    echo "CHAT_OK"
}

# Apply suggestion test. Args: $1=runId
test_apply_suggestion() {
    local run_id="$1"
    local resp
    resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/runs/$run_id/apply" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d '{"suggestion":"Add a dark mode toggle","source":"COPILOT"}')
    local http_code=$(echo "$resp" | tail -1)
    local body=$(echo "$resp" | sed '$d')

    if [[ "$http_code" == "200" ]]; then
        local task_count=$(echo "$body" | jq -r '.taskCount // 0')
        if [[ "$task_count" -gt 0 ]]; then
            echo "APPLY_OK|$task_count tasks"
        else
            echo "APPLY_FAILED|0 tasks"
        fi
    else
        echo "APPLY_FAILED|$http_code"
    fi
}

# Retry failed tasks (up to 2 retries). Args: $1=runId
retry_failed_tasks() {
    local run_id="$1"
    local max_retries=2
    local retried=0

    for attempt in $(seq 1 $max_retries); do
        local detail=$(get_run "$run_id")
        local failed_tasks
        failed_tasks=$(echo "$detail" | jq -r '.tasks[]? | select(.status == "FAILED") | .taskId' 2>/dev/null)

        if [[ -z "$failed_tasks" ]]; then
            break
        fi

        log "  Retry attempt $attempt: $(echo "$failed_tasks" | wc -l | tr -d ' ') failed task(s)"

        while IFS= read -r task_id; do
            [[ -z "$task_id" ]] && continue
            curl -s -X POST "$BASE_URL/api/runs/$run_id/tasks/$task_id/retry" \
                -H "Authorization: Bearer $TOKEN" > /dev/null 2>&1
            retried=$((retried + 1))
        done <<< "$failed_tasks"

        # Wait for retries to complete
        sleep 30
        poll_until_terminal "$run_id" 300 > /dev/null 2>&1 || true
    done

    echo "$retried"
}

# Extract task counts from run detail. Args: $1=run_detail_json
get_task_counts() {
    local detail="$1"
    # Try to get task info from the run detail
    local total=$(echo "$detail" | jq -r '.taskCount // 0')
    local completed=$(echo "$detail" | jq -r '.completedTaskCount // 0')

    # If those fields aren't available, try alternate paths
    if [[ "$total" == "0" || "$total" == "null" ]]; then
        # Count from tasks array if present
        total=$(echo "$detail" | jq -r '.tasks | length // 0' 2>/dev/null || echo "?")
        completed=$(echo "$detail" | jq -r '[.tasks[]? | select(.status == "COMPLETE")] | length' 2>/dev/null || echo "?")
    fi

    echo "$completed/$total"
}

# ── Run a single test case ──────────────────────────────────────────────────

run_test_case() {
    local idx=$1
    local provider="$2"
    local language="${LANGUAGES[$idx]}"
    local request="${REQUESTS[$idx]}"
    local start_time=$(date +%s)

    echo ""
    log "━━━ Test $((idx+1))/${#LANGUAGES[@]}: $language ━━━"

    # Special case: cancel test
    if [[ "$language" == "Cancel" ]]; then
        run_cancel_test "$idx" "$provider"
        return
    fi

    # 1. Submit run (pass provider explicitly)
    local run_id
    run_id=$(submit_run "$request" "true" "$provider") || true

    if [[ "$run_id" == SUBMIT_FAILED* ]]; then
        fail "Submit failed: $run_id"
        save_result "$idx" "$provider" "$language" "SUBMIT_FAILED" "0/0" "0" "" "NO_REPORT" "NONE" "$run_id"
        return
    fi
    ok "Submitted: $run_id"

    # 2. Poll until terminal
    local result
    local lang_timeout=$(get_language_timeout "$language")
    result=$(poll_until_terminal "$run_id" "$lang_timeout") || true

    local status=$(echo "$result" | cut -d'|' -f1)
    local duration=$(echo "$result" | cut -d'|' -f2)

    if [[ "$status" == "TIMEOUT" ]]; then
        fail "Timed out after ${duration}s"
        # Cancel the timed-out run
        cancel_run "$run_id" > /dev/null 2>&1
        save_result "$idx" "$provider" "$language" "TIMEOUT" "?/?" "$duration" "" "NO_REPORT" "NONE" "Timed out after ${duration}s"
        return
    fi

    # 2.5 Retry failed tasks (up to 2 times) if run is PARTIAL
    local retries_done=0
    if [[ "$status" == "PARTIAL" || "$status" == "FAILED" ]]; then
        log "  Attempting task retries..."
        retries_done=$(retry_failed_tasks "$run_id")
        if [[ "$retries_done" -gt 0 ]]; then
            log "  Retried $retries_done task(s), re-checking status..."
            local retry_result
            retry_result=$(poll_until_terminal "$run_id" 300) || true
            status=$(echo "$retry_result" | cut -d'|' -f1)
            duration=$(( $(date +%s) - $(date -d "$(echo "$detail" | jq -r '.startedAt // empty')" +%s 2>/dev/null || echo "$start_time") ))
        fi
    fi

    # 3. Get run detail
    local detail=$(get_run "$run_id")
    local tasks=$(get_task_counts "$detail")
    local active_provider=$(echo "$detail" | jq -r '.provider // "unknown"')
    local model=$(echo "$detail" | jq -r '.workerModel // .plannerModel // "unknown"')
    # Prefix model with provider for cross-provider analysis clarity
    if [[ "$active_provider" != "unknown" && "$active_provider" != "null" ]]; then
        model="${active_provider}:${model}"
    fi
    local failure_reason=$(echo "$detail" | jq -r '.failureReason // empty')

    # 4. Check validation report (wait up to 60s for background validation)
    local report_status="NO_REPORT"
    if [[ "$status" == "COMPLETE" || "$status" == "PARTIAL" ]]; then
        log "  Waiting for validation report (up to 90s)..."
        for i in $(seq 1 18); do
            report_status=$(get_report "$run_id")
            if [[ "$report_status" == "HAS_REPORT" ]]; then
                break
            fi
            sleep 5
        done
    fi

    # 4.5 Check compilation status from report
    local compilation_status="UNKNOWN"
    local test_gen_status="UNKNOWN"
    if [[ "$report_status" == "HAS_REPORT" ]]; then
        local report_json
        report_json=$(curl -s "$BASE_URL/api/runs/$run_id/report" \
            -H "Authorization: Bearer $TOKEN")
        local checks_passed=$(echo "$report_json" | jq -r '.checksPassed // []')
        local pipeline_result=$(echo "$report_json" | jq -r '.pipelineResult // "UNKNOWN"')

        if echo "$checks_passed" | jq -e 'index("COMPILATION")' > /dev/null 2>&1; then
            compilation_status="PASS"
            ok "Compilation: PASS"
        else
            compilation_status="FAIL"
            fail "Compilation: FAIL"
        fi

        if echo "$checks_passed" | jq -e 'index("INTEGRATION_TESTS")' > /dev/null 2>&1; then
            test_gen_status="PASS"
            ok "Test generation: PASS"
        else
            test_gen_status="GENERATED"  # tests may exist but fail execution
            warn "Test generation: tests generated but execution check inconclusive"
        fi

        ok "Pipeline result: $pipeline_result"
    fi

    # 5. Test report sharing (only for COMPLETE/PARTIAL with report)
    local share_status="SKIPPED"
    if [[ "$report_status" == "HAS_REPORT" ]]; then
        share_status=$(test_share_report "$run_id")
    fi

    # 6. Test preview (only for COMPLETE runs)
    local preview_status="SKIPPED"
    if [[ "$status" == "COMPLETE" ]]; then
        log "  Testing preview..."
        preview_status=$(test_preview "$run_id")
        case "$preview_status" in
            PREVIEW_OK*) ok "Preview: $(echo "$preview_status" | cut -d'|' -f2)" ;;
            *)           fail "Preview: $preview_status" ;;
        esac
    fi

    # 7. Test GitHub new repo (only for COMPLETE runs, only for first test case)
    local github_new_status="SKIPPED"
    local github_pr_status="SKIPPED"
    if [[ "$status" == "COMPLETE" && "$idx" == "0" ]]; then
        log "  Testing GitHub new repo..."
        github_new_status=$(test_github_new "$run_id")
        case "$github_new_status" in
            GITHUB_NEW_OK*) ok "GitHub new repo: $(echo "$github_new_status" | cut -d'|' -f2)" ;;
            *)              fail "GitHub new repo: $github_new_status" ;;
        esac

        # If new repo created, test existing repo + PR on a second COMPLETE run
        local new_repo_url=$(echo "$github_new_status" | cut -d'|' -f2)
        if [[ "$github_new_status" == GITHUB_NEW_OK* && -n "$new_repo_url" ]]; then
            log "  Testing GitHub existing repo + PR..."
            # Submit a small follow-up run to test PR creation
            local pr_run_id
            pr_run_id=$(submit_run "Add a README.md with project description and usage instructions" "true" "$provider") || true
            if [[ "$pr_run_id" != SUBMIT_FAILED* ]]; then
                local pr_result
                pr_result=$(poll_until_terminal "$pr_run_id" 300) || true
                local pr_run_status=$(echo "$pr_result" | cut -d'|' -f1)
                if [[ "$pr_run_status" == "COMPLETE" ]]; then
                    github_pr_status=$(test_github_existing "$pr_run_id" "$new_repo_url")
                    case "$github_pr_status" in
                        GITHUB_PR_OK*) ok "GitHub PR: $(echo "$github_pr_status" | cut -d'|' -f2)" ;;
                        *)             fail "GitHub PR: $github_pr_status" ;;
                    esac
                else
                    github_pr_status="PR_RUN_FAILED|$pr_run_status"
                    fail "GitHub PR: helper run failed ($pr_run_status)"
                fi
            fi
        fi
    fi

    # 8. Test copilot chat (only for COMPLETE runs)
    local chat_status="SKIPPED"
    if [[ "$status" == "COMPLETE" ]]; then
        log "  Testing copilot chat..."
        chat_status=$(test_copilot_chat "$run_id")
        case "$chat_status" in
            CHAT_OK) ok "Copilot chat: OK" ;;
            *)       fail "Copilot chat: $chat_status" ;;
        esac
    fi

    # 9. Log result
    case "$status" in
        COMPLETE) ok "COMPLETE — tasks: $tasks, duration: ${duration}s, model: $model, report: $report_status, compilation: $compilation_status, preview: $preview_status, chat: $chat_status" ;;
        PARTIAL)  warn "PARTIAL — tasks: $tasks, duration: ${duration}s, model: $model, retries: $retries_done, report: $report_status" ;;
        FAILED)   fail "FAILED — tasks: $tasks, duration: ${duration}s, retries: $retries_done, reason: ${failure_reason:-unknown}" ;;
        *)        fail "$status — tasks: $tasks, duration: ${duration}s" ;;
    esac

    # 10. Save result (extended fields)
    local notes=""
    [[ -n "$failure_reason" ]] && notes="$failure_reason"
    [[ "$retries_done" -gt 0 ]] && notes="$notes [retries:$retries_done]"
    [[ "$compilation_status" != "UNKNOWN" ]] && notes="$notes [compile:$compilation_status]"
    [[ "$preview_status" != "SKIPPED" ]] && notes="$notes [preview:$(echo "$preview_status" | cut -d'|' -f1)]"
    [[ "$chat_status" != "SKIPPED" ]] && notes="$notes [chat:$chat_status]"
    [[ "$github_new_status" != "SKIPPED" ]] && notes="$notes [github_new:$(echo "$github_new_status" | cut -d'|' -f1)]"
    [[ "$github_pr_status" != "SKIPPED" ]] && notes="$notes [github_pr:$(echo "$github_pr_status" | cut -d'|' -f1)]"
    save_result "$idx" "$provider" "$language" "$status" "$tasks" "$duration" "$model" "$report_status" "$share_status" "$notes"
}

# Cancel test flow
run_cancel_test() {
    local idx=$1
    local provider="$2"
    local start_time=$(date +%s)

    # Submit WITHOUT skipApproval (pass provider)
    local run_id
    run_id=$(submit_run "${REQUESTS[$idx]}" "false" "$provider") || true

    if [[ "$run_id" == SUBMIT_FAILED* ]]; then
        fail "Cancel test: submit failed: $run_id"
        save_result "$idx" "$provider" "Cancel" "SUBMIT_FAILED" "0/0" "0" "" "NO_REPORT" "NONE" "$run_id"
        return
    fi
    ok "Cancel test: submitted $run_id"

    # Wait for AWAITING_APPROVAL
    if wait_for_state "$run_id" "AWAITING_APPROVAL" "$PLAN_WAIT_TIMEOUT"; then
        ok "Cancel test: reached AWAITING_APPROVAL"
    else
        warn "Cancel test: did not reach AWAITING_APPROVAL, cancelling anyway"
    fi

    # Cancel
    cancel_run "$run_id" > /dev/null 2>&1

    # Verify cancelled
    sleep 2
    local detail=$(get_run "$run_id")
    local status=$(echo "$detail" | jq -r '.status // "UNKNOWN"')
    local duration=$(( $(date +%s) - start_time ))

    if [[ "$status" == "CANCELLED" ]]; then
        ok "Cancel test: PASSED — run cancelled in ${duration}s"
        save_result "$idx" "$provider" "Cancel" "PASS" "0/0" "$duration" "" "NO_REPORT" "NONE" "Cancel flow works"
    else
        fail "Cancel test: FAILED — status is $status (expected CANCELLED)"
        save_result "$idx" "$provider" "Cancel" "FAIL" "0/0" "$duration" "" "NO_REPORT" "NONE" "Status: $status"
    fi
}

# ── Results storage ─────────────────────────────────────────────────────────

init_results() {
    local provider="$1"
    echo '[]' > "$RESULTS_DIR/e2e-results-${provider}.json"
}

save_result() {
    local idx=$1 provider="$2" language="$3" status="$4" tasks="$5"
    local duration="$6" model="$7" report="$8" share="$9" notes="${10:-}"
    local file="$RESULTS_DIR/e2e-results-${provider}.json"

    # Escape notes for JSON
    notes=$(echo "$notes" | sed 's/"/\\"/g' | tr '\n' ' ' | head -c 200)

    local entry="{\"index\":$idx,\"language\":\"$language\",\"status\":\"$status\",\"tasks\":\"$tasks\",\"duration\":$duration,\"model\":\"$model\",\"report\":\"$report\",\"share\":\"$share\",\"notes\":\"$notes\"}"

    local current=$(cat "$file")
    echo "$current" | jq ". + [$entry]" > "$file"
}

# ── Report generation ───────────────────────────────────────────────────────

generate_report() {
    echo ""
    echo "# ExecuteSpec E2E Test Report"
    echo "Generated: $(date '+%Y-%m-%d %H:%M %Z')"
    echo ""

    local total=0 pass=0 fail_count=0 partial=0 timeout=0

    # Check which provider results exist
    local providers=()
    for p in google anthropic openai; do
        if [[ -f "$RESULTS_DIR/e2e-results-${p}.json" ]]; then
            providers+=("$p")
        fi
    done

    if [[ ${#providers[@]} -eq 0 ]]; then
        echo "No results found. Run batches first."
        return
    fi

    # Count totals
    for p in "${providers[@]}"; do
        local file="$RESULTS_DIR/e2e-results-${p}.json"
        local count=$(jq length "$file")
        total=$((total + count))
        pass=$((pass + $(jq '[.[] | select(.status == "COMPLETE" or .status == "PASS")] | length' "$file")))
        fail_count=$((fail_count + $(jq '[.[] | select(.status == "FAILED" or .status == "SUBMIT_FAILED" or .status == "FAIL")] | length' "$file")))
        partial=$((partial + $(jq '[.[] | select(.status == "PARTIAL")] | length' "$file")))
        timeout=$((timeout + $(jq '[.[] | select(.status == "TIMEOUT")] | length' "$file")))
    done

    echo "## Summary"
    echo "- **Total runs:** $total | **Pass:** $pass | **Fail:** $fail_count | **Partial:** $partial | **Timeout:** $timeout"
    echo "- **Providers tested:** ${providers[*]}"
    echo ""

    # Cross-provider comparison
    if [[ ${#providers[@]} -gt 1 ]]; then
        echo "## Cross-Provider Comparison"
        echo "| # | Language | $(printf '%s | ' "${providers[@]}")"
        echo "|---|----------|$(printf -- '---------|' "${providers[@]}")"

        for i in $(seq 0 $((${#LANGUAGES[@]} - 1))); do
            local lang="${LANGUAGES[$i]}"
            local row="| $((i+1)) | $lang |"

            for p in "${providers[@]}"; do
                local file="$RESULTS_DIR/e2e-results-${p}.json"
                local entry=$(jq ".[] | select(.index == $i)" "$file" 2>/dev/null)

                if [[ -z "$entry" ]]; then
                    row="$row — |"
                else
                    local st=$(echo "$entry" | jq -r '.status')
                    local tasks=$(echo "$entry" | jq -r '.tasks')
                    local dur=$(echo "$entry" | jq -r '.duration')

                    case "$st" in
                        COMPLETE|PASS) row="$row ✅ $tasks ${dur}s |" ;;
                        PARTIAL)       row="$row ⚠️ $tasks ${dur}s |" ;;
                        TIMEOUT)       row="$row ⏰ TIMEOUT ${dur}s |" ;;
                        *)             row="$row ❌ $st |" ;;
                    esac
                fi
            done
            echo "$row"
        done
        echo ""
    fi

    # Per-provider detail tables
    for p in "${providers[@]}"; do
        local file="$RESULTS_DIR/e2e-results-${p}.json"
        echo "## Provider: $p"
        echo "| # | Language | Status | Tasks | Duration | Model | Report | Share | Notes |"
        echo "|---|----------|--------|-------|----------|-------|--------|-------|-------|"

        jq -r '.[] | "| \(.index + 1) | \(.language) | \(.status) | \(.tasks) | \(.duration)s | \(.model) | \(.report) | \(.share) | \(.notes) |"' "$file"
        echo ""
    done

    # Issues section
    echo "## Issues Found"
    echo ""

    for p in "${providers[@]}"; do
        local file="$RESULTS_DIR/e2e-results-${p}.json"
        local issues=$(jq -r '.[] | select(.status != "COMPLETE" and .status != "PASS") | "- **[\(.language)]** \(.status): \(.notes)"' "$file")

        if [[ -n "$issues" ]]; then
            echo "### $p"
            echo "$issues"
            echo ""
        fi
    done

    echo "### Cross-Cutting Issues"
    # Find languages that failed on ALL providers
    for i in $(seq 0 $((${#LANGUAGES[@]} - 2))); do
        local lang="${LANGUAGES[$i]}"
        local all_fail=true
        for p in "${providers[@]}"; do
            local file="$RESULTS_DIR/e2e-results-${p}.json"
            local st=$(jq -r ".[] | select(.index == $i) | .status" "$file" 2>/dev/null)
            if [[ "$st" == "COMPLETE" || "$st" == "PASS" ]]; then
                all_fail=false
                break
            fi
        done
        if $all_fail && [[ ${#providers[@]} -gt 1 ]]; then
            echo "- **$lang**: Failed on ALL providers — likely a pipeline/validation issue, not provider-specific"
        fi
    done
    echo ""
}

# ── Main ────────────────────────────────────────────────────────────────────

main() {
    local command="${1:-}"

    if [[ -z "$command" ]]; then
        echo "Usage: $0 <google|anthropic|openai|report>"
        echo ""
        echo "  google     Run 7 tests with Google/Gemini provider"
        echo "  anthropic  Run 7 tests with Anthropic provider"
        echo "  openai     Run 7 tests with OpenAI provider"
        echo "  report     Generate consolidated report from all batches"
        exit 1
    fi

    if [[ "$command" == "report" ]]; then
        generate_report
        exit 0
    fi

    local provider="$command"
    if [[ "$provider" != "google" && "$provider" != "anthropic" && "$provider" != "openai" ]]; then
        echo "Unknown provider: $provider"
        exit 1
    fi

    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  ExecuteSpec E2E Test — Provider: $provider"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""

    # Check server is up
    if ! curl -s "$BASE_URL/actuator/health" > /dev/null 2>&1; then
        echo -e "${RED}Server not reachable at $BASE_URL${NC}"
        echo "Start the server first: JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run"
        exit 1
    fi
    ok "Server reachable at $BASE_URL"

    # Auth
    auth_login

    # Verify active provider matches the requested batch
    local active_provider
    active_provider=$(curl -s "$BASE_URL/api/models/available" \
        -H "Authorization: Bearer $TOKEN" | jq -r '.defaultProvider // empty')
    if [[ -n "$active_provider" && "$active_provider" != "$provider" ]]; then
        warn "Active provider is '$active_provider' but batch is '$provider'"
        warn "Results may be attributed to wrong provider! Verify server config."
    else
        ok "Provider verified: $provider"
    fi

    # Init results
    init_results "$provider"

    # Warm-up: run a throwaway request to prime caches + connections
    log "Running warm-up request..."
    local warmup_id
    warmup_id=$(submit_run "echo hello" "true") 2>/dev/null || true
    if [[ "$warmup_id" != SUBMIT_FAILED* && -n "$warmup_id" ]]; then
        sleep 15
        cancel_run "$warmup_id" > /dev/null 2>&1 || true
        ok "Warm-up complete"
    else
        warn "Warm-up request failed — proceeding anyway"
    fi

    # Run all test cases (standard set)
    local batch_start=$(date +%s)
    local total_tests=${#LANGUAGES[@]}

    for i in $(seq 0 $((total_tests - 1))); do
        # Refresh token before each test to avoid stale JWT
        auth_login 2>/dev/null
        run_test_case "$i" "$provider"
    done

    local batch_duration=$(( $(date +%s) - batch_start ))

    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log "Batch complete for $provider in ${batch_duration}s"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    # Show quick summary
    local file="$RESULTS_DIR/e2e-results-${provider}.json"
    local pass=$(jq '[.[] | select(.status == "COMPLETE" or .status == "PASS")] | length' "$file")
    local total=$(jq length "$file")
    echo ""
    echo -e "  ${GREEN}Pass: $pass${NC} / ${total} total"
    echo "  Results saved to: $file"
    echo ""
}

main "$@"
