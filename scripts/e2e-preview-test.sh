#!/usr/bin/env bash
#
# ExecuteSpec E2E Preview & Code Editor Test Suite
#
# Tests preview deployment, code file access, and pipeline execution
# across all 3 AI providers with single-task and multi-task runs.
#
# Usage:
#   ./scripts/e2e-preview-test.sh              # Run all 6 tests (default: localhost)
#   BASE_URL=https://dev.executespec.ai ./scripts/e2e-preview-test.sh   # Against dev
#   ./scripts/e2e-preview-test.sh report       # Generate report from last run
#
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="${E2E_EMAIL:-admin@executespec.ai}"
PASSWORD="${E2E_PASSWORD:?E2E_PASSWORD environment variable is required}"
POLL_INTERVAL=10
RUN_TIMEOUT=600        # 10 minutes max per run
PREVIEW_WAIT=60        # extra seconds to wait for preview after COMPLETE
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESULTS_FILE="$SCRIPT_DIR/e2e-preview-results.json"
TOKEN=""

# ── Colors ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

# ── Test definitions ────────────────────────────────────────────────────────
# Format: PROVIDER|TYPE|DESCRIPTION|USER_REQUEST
TESTS=(
  "ANTHROPIC|single|Counter app|Create a simple counter app with HTML, CSS, and JavaScript. Single page with increment/decrement buttons and a display showing the current count."
  "ANTHROPIC|multi|Todo list app|Build a todo list app with: 1) An HTML page with input field and add button 2) CSS styling with a dark theme 3) JavaScript for add, delete, and mark-complete functionality with localStorage persistence"
  "GOOGLE|single|Digital clock|Create a digital clock webpage that shows the current time updating every second, with hours:minutes:seconds in a large centered display."
  "GOOGLE|multi|Weather dashboard|Build a weather dashboard with: 1) HTML layout with city search input and weather display cards 2) CSS with responsive grid layout and gradient backgrounds 3) JavaScript that fetches from a mock weather API and displays temperature, humidity, conditions"
  "OPENAI|single|Tip calculator|Create a tip calculator webpage with inputs for bill amount and tip percentage, showing calculated tip and total."
  "OPENAI|multi|Quiz app|Build a quiz app with: 1) HTML page with question display and multiple choice buttons 2) CSS with card-based layout and animations for correct/wrong answers 3) JavaScript with 5 hardcoded trivia questions, score tracking, and a results screen at the end"
)

# ── Logging ─────────────────────────────────────────────────────────────────
log()  { echo -e "${BLUE}[$(date +%H:%M:%S)]${NC} $*" >&2; }
ok()   { echo -e "${GREEN}  PASS${NC} $*" >&2; }
fail() { echo -e "${RED}  FAIL${NC} $*" >&2; }
warn() { echo -e "${YELLOW}  WARN${NC} $*" >&2; }
info() { echo -e "${CYAN}  INFO${NC} $*" >&2; }

# ── Auth ────────────────────────────────────────────────────────────────────
auth_login() {
    local resp
    resp=$(curl -s -X POST "$BASE_URL/api/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" 2>&1)

    TOKEN=$(echo "$resp" | jq -r '.accessToken // empty')
    if [[ -z "$TOKEN" ]]; then
        echo -e "${RED}Authentication failed: $resp${NC}" >&2
        exit 1
    fi
    log "Authenticated as $EMAIL against $BASE_URL"
}

# ── API helpers ─────────────────────────────────────────────────────────────
api_get() {
    curl -s "$BASE_URL$1" -H "Authorization: Bearer $TOKEN"
}

submit_run() {
    local request="$1" provider="$2"
    local resp http_code body

    resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/runs" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"userRequest\":$(echo "$request" | jq -Rs .),\"source\":\"MANUAL\",\"skipApproval\":true,\"executionMode\":\"PROTOTYPE\",\"aiProvider\":\"$provider\"}")

    http_code=$(echo "$resp" | tail -1)
    body=$(echo "$resp" | sed '$d')

    if [[ "$http_code" != "202" && "$http_code" != "200" ]]; then
        echo "SUBMIT_FAILED|$http_code|$body"
        return 1
    fi

    echo "$body" | jq -r '.runId // empty'
}

poll_until_terminal() {
    local run_id="$1" timeout="${2:-$RUN_TIMEOUT}"
    local start_time last_status=""
    start_time=$(date +%s)

    while true; do
        local elapsed=$(( $(date +%s) - start_time ))
        if (( elapsed > timeout )); then
            echo "TIMEOUT|$elapsed"
            return 1
        fi

        local status
        status=$(api_get "/api/runs/$run_id" | jq -r '.status // "UNKNOWN"')

        if [[ "$status" != "$last_status" ]]; then
            info "Run $run_id: $status (${elapsed}s)"
            last_status="$status"
        fi

        case "$status" in
            COMPLETE|PARTIAL|FAILED|CANCELLED|REJECTED)
                echo "$status|$elapsed"
                return 0
                ;;
            *)
                sleep "$POLL_INTERVAL"
                ;;
        esac
    done
}

# ── Check functions ─────────────────────────────────────────────────────────

check_files() {
    local run_id="$1"
    local files
    files=$(api_get "/api/runs/$run_id/files")

    local count
    count=$(echo "$files" | jq 'if type == "array" then length else 0 end')

    if (( count > 0 )); then
        echo "OK|$count"
    else
        echo "FAIL|0"
    fi
}

check_file_content() {
    local run_id="$1"
    local resp http_code
    resp=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/runs/$run_id/file?path=index.html" \
        -H "Authorization: Bearer $TOKEN")
    http_code=$(echo "$resp" | tail -1)

    if [[ "$http_code" == "200" ]]; then
        echo "OK"
    else
        echo "FAIL|$http_code"
    fi
}

check_preview() {
    local run_id="$1"

    # Wait up to PREVIEW_WAIT seconds for preview to become RUNNING
    local waited=0
    while (( waited < PREVIEW_WAIT )); do
        local preview_data
        preview_data=$(api_get "/api/runs/$run_id/preview/status" 2>/dev/null || echo '{}')

        local preview_status preview_url
        preview_status=$(echo "$preview_data" | jq -r '.status // "NONE"')
        preview_url=$(echo "$preview_data" | jq -r '.url // empty')

        if [[ "$preview_status" == "RUNNING" && -n "$preview_url" ]]; then
            # Verify the preview URL returns 200
            local http_code
            http_code=$(curl -s -o /dev/null -w "%{http_code}" "$preview_url" --max-time 10)

            if [[ "$http_code" == "200" ]]; then
                echo "OK|$preview_url"
            else
                echo "HTTP_$http_code|$preview_url"
            fi
            return
        fi

        if [[ "$preview_status" == "FAILED" ]]; then
            echo "DEPLOY_FAILED"
            return
        fi

        sleep 5
        waited=$((waited + 5))
    done

    echo "TIMEOUT"
}

# ── Run a single test ───────────────────────────────────────────────────────

run_single_test() {
    local idx="$1"
    local test_def="${TESTS[$idx]}"

    local provider type desc request
    IFS='|' read -r provider type desc request <<< "$test_def"

    echo "" >&2
    log "━━━ Test $((idx+1))/${#TESTS[@]}: $provider $type — $desc ━━━"

    local result="{}"
    result=$(echo "$result" | jq \
        --arg p "$provider" --arg t "$type" --arg d "$desc" \
        '.provider=$p | .type=$t | .description=$d')

    # 1. Submit
    local run_id
    run_id=$(submit_run "$request" "$provider") || true

    if [[ "$run_id" == SUBMIT_FAILED* || -z "$run_id" ]]; then
        fail "Submit failed: $run_id"
        result=$(echo "$result" | jq '.status="SUBMIT_FAILED" | .runId=null | .duration=0 | .fileCount=0 | .fileAccess="N/A" | .previewStatus="N/A" | .previewUrl=null | .verdict="FAIL"')
        echo "$result"
        return
    fi
    ok "Submitted: $run_id"
    result=$(echo "$result" | jq --arg id "$run_id" '.runId=$id')

    # Rate limit guard between submissions
    sleep 2

    # 2. Poll until terminal
    local poll_result
    poll_result=$(poll_until_terminal "$run_id") || true

    local status duration
    status=$(echo "$poll_result" | cut -d'|' -f1)
    duration=$(echo "$poll_result" | cut -d'|' -f2)

    result=$(echo "$result" | jq --arg s "$status" --argjson d "${duration:-0}" '.status=$s | .duration=$d')

    if [[ "$status" == "TIMEOUT" ]]; then
        fail "Timed out after ${duration}s"
        result=$(echo "$result" | jq '.fileCount=0 | .fileAccess="N/A" | .previewStatus="TIMEOUT" | .previewUrl=null | .verdict="FAIL"')
        echo "$result"
        return
    fi

    if [[ "$status" == "FAILED" ]]; then
        fail "Run FAILED after ${duration}s"
        result=$(echo "$result" | jq '.fileCount=0 | .fileAccess="N/A" | .previewStatus="N/A" | .previewUrl=null | .verdict="FAIL"')
        echo "$result"
        return
    fi

    info "Run $status in ${duration}s"

    # 3. Check files
    local file_result file_status file_count
    file_result=$(check_files "$run_id")
    file_status=$(echo "$file_result" | cut -d'|' -f1)
    file_count=$(echo "$file_result" | cut -d'|' -f2)
    result=$(echo "$result" | jq --argjson c "${file_count:-0}" '.fileCount=$c')

    if [[ "$file_status" == "OK" ]]; then
        ok "Files: $file_count files listed"
    else
        fail "Files: no files returned"
    fi

    # 4. Check file content (index.html)
    local content_status
    content_status=$(check_file_content "$run_id")
    result=$(echo "$result" | jq --arg s "$content_status" '.fileAccess=$s')

    if [[ "$content_status" == "OK" ]]; then
        ok "File content: index.html accessible"
    else
        fail "File content: index.html returned $content_status"
    fi

    # 5. Check preview
    local preview_result preview_status preview_url
    preview_result=$(check_preview "$run_id")
    preview_status=$(echo "$preview_result" | cut -d'|' -f1)
    preview_url=$(echo "$preview_result" | cut -d'|' -f2)
    result=$(echo "$result" | jq --arg s "$preview_status" --arg u "${preview_url:-}" '.previewStatus=$s | .previewUrl=$u')

    if [[ "$preview_status" == "OK" ]]; then
        ok "Preview: HTTP 200 at $preview_url"
    elif [[ "$preview_status" == "TIMEOUT" ]]; then
        warn "Preview: did not start within ${PREVIEW_WAIT}s"
    else
        fail "Preview: $preview_status"
    fi

    # 6. Verdict
    local verdict="PASS"
    if [[ "$status" != "COMPLETE" ]]; then verdict="FAIL"; fi
    if [[ "$file_status" != "OK" ]]; then verdict="FAIL"; fi
    if [[ "$content_status" != "OK" ]]; then verdict="FAIL"; fi
    if [[ "$preview_status" != "OK" ]]; then
        # Preview timeout is a WARN, not a hard fail (TTL may have expired)
        if [[ "$preview_status" != "TIMEOUT" ]]; then
            verdict="FAIL"
        else
            verdict="WARN"
        fi
    fi

    result=$(echo "$result" | jq --arg v "$verdict" '.verdict=$v')

    case "$verdict" in
        PASS) ok "Verdict: PASS" ;;
        WARN) warn "Verdict: WARN (preview not ready)" ;;
        FAIL) fail "Verdict: FAIL" ;;
    esac

    echo "$result"
}

# ── Main runner ─────────────────────────────────────────────────────────────

run_all_tests() {
    auth_login

    log "Running ${#TESTS[@]} tests against $BASE_URL"
    log "Results will be saved to $RESULTS_FILE"

    local all_results="[]"
    local start_time
    start_time=$(date +%s)

    for i in "${!TESTS[@]}"; do
        local result
        result=$(run_single_test "$i")
        all_results=$(echo "$all_results" | jq --argjson r "$result" '. + [$r]')
    done

    local total_duration=$(( $(date +%s) - start_time ))

    # Build final report object
    local report
    report=$(jq -n \
        --arg url "$BASE_URL" \
        --arg ts "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
        --argjson dur "$total_duration" \
        --argjson results "$all_results" \
        '{
            baseUrl: $url,
            timestamp: $ts,
            totalDuration: $dur,
            results: $results
        }')

    echo "$report" | jq . > "$RESULTS_FILE"
    log "Results saved to $RESULTS_FILE"

    # Print summary
    print_summary
}

# ── Report / summary ───────────────────────────────────────────────────────

print_summary() {
    if [[ ! -f "$RESULTS_FILE" ]]; then
        echo "No results file found at $RESULTS_FILE" >&2
        exit 1
    fi

    local data
    data=$(cat "$RESULTS_FILE")

    echo "" >&2
    echo -e "${BOLD}═══════════════════════════════════════════════════════════${NC}" >&2
    echo -e "${BOLD}  ExecuteSpec E2E Preview Test Report${NC}" >&2
    echo -e "${BOLD}═══════════════════════════════════════════════════════════${NC}" >&2
    echo "" >&2

    echo -e "  ${CYAN}Target:${NC}   $(echo "$data" | jq -r '.baseUrl')" >&2
    echo -e "  ${CYAN}Time:${NC}     $(echo "$data" | jq -r '.timestamp')" >&2
    echo -e "  ${CYAN}Duration:${NC} $(echo "$data" | jq -r '.totalDuration')s" >&2
    echo "" >&2

    # Counts
    local total pass fail_c warn_c
    total=$(echo "$data" | jq '.results | length')
    pass=$(echo "$data" | jq '[.results[] | select(.verdict == "PASS")] | length')
    fail_c=$(echo "$data" | jq '[.results[] | select(.verdict == "FAIL")] | length')
    warn_c=$(echo "$data" | jq '[.results[] | select(.verdict == "WARN")] | length')

    echo -e "  ${BOLD}Summary:${NC} $total tests | ${GREEN}$pass pass${NC} | ${RED}$fail_c fail${NC} | ${YELLOW}$warn_c warn${NC}" >&2
    echo "" >&2

    # Table header
    printf "  %-11s %-7s %-20s %-9s %5s %6s %-8s %-8s %-7s\n" \
        "Provider" "Type" "Description" "Status" "Files" "Time" "Content" "Preview" "Verdict" >&2
    printf "  %-11s %-7s %-20s %-9s %5s %6s %-8s %-8s %-7s\n" \
        "───────────" "───────" "────────────────────" "─────────" "─────" "──────" "────────" "────────" "───────" >&2

    echo "$data" | jq -r '.results[] | "\(.provider)|\(.type)|\(.description)|\(.status)|\(.fileCount)|\(.duration)|\(.fileAccess)|\(.previewStatus)|\(.verdict)"' | \
    while IFS='|' read -r provider type desc status files dur content preview verdict; do
        local color="$NC"
        case "$verdict" in
            PASS) color="$GREEN" ;;
            FAIL) color="$RED" ;;
            WARN) color="$YELLOW" ;;
        esac

        printf "  %-11s %-7s %-20s %-9s %5s %5ss %-8s %-8s ${color}%-7s${NC}\n" \
            "$provider" "$type" "$desc" "$status" "$files" "$dur" "$content" "$preview" "$verdict" >&2
    done

    echo "" >&2

    # Per-provider summary
    echo -e "  ${BOLD}Per-Provider:${NC}" >&2
    for p in ANTHROPIC GOOGLE OPENAI; do
        local p_pass p_total
        p_total=$(echo "$data" | jq --arg p "$p" '[.results[] | select(.provider == $p)] | length')
        p_pass=$(echo "$data" | jq --arg p "$p" '[.results[] | select(.provider == $p and .verdict == "PASS")] | length')
        local p_color="$GREEN"
        if (( p_pass < p_total )); then p_color="$YELLOW"; fi
        if (( p_pass == 0 )); then p_color="$RED"; fi
        echo -e "    $p: ${p_color}${p_pass}/${p_total} pass${NC}" >&2
    done

    echo "" >&2
    echo -e "${BOLD}═══════════════════════════════════════════════════════════${NC}" >&2
}

# ── Entry point ─────────────────────────────────────────────────────────────

case "${1:-run}" in
    run)     run_all_tests ;;
    report)  print_summary ;;
    *)
        echo "Usage: $0 [run|report]"
        echo "  run    — Execute all 6 tests (default)"
        echo "  report — Print summary from last run"
        exit 1
        ;;
esac
