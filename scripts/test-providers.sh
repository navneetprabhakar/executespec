#!/usr/bin/env bash
# ExecuteSpec — Provider Eval Script
# Measures: cost, time, accuracy, efficiency across all 3 providers.
#
# Usage:
#   ./scripts/test-providers.sh
#   BASE_URL=http://localhost:8080 ./scripts/test-providers.sh
#   PROVIDERS="ANTHROPIC OPENAI" ./scripts/test-providers.sh   # subset
#   SKIP_REPORT=1 ./scripts/test-providers.sh                  # skip waiting for report
#
# Output:
#   - Per-run table printed to stdout
#   - JSON results appended to scripts/eval-results-$(date).json

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="${E2E_EMAIL:-admin@executespec.ai}"
PASSWORD="${E2E_PASSWORD:-Admin@123}"
PROVIDERS="${PROVIDERS:-ANTHROPIC OPENAI GOOGLE}"
SKIP_REPORT="${SKIP_REPORT:-0}"

POLL_INTERVAL=8
RUN_TIMEOUT=1200    # 20 min per run
REPORT_WAIT=180     # 3 min to wait for report after COMPLETE

RESULTS_FILE="scripts/eval-results-$(date +%Y%m%d-%H%M%S).json"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
log()  { echo -e "${BLUE}[$(date +%H:%M:%S)]${NC} $*"; }
ok()   { echo -e "${GREEN}  ✅ $*${NC}"; }
fail() { echo -e "${RED}  ❌ $*${NC}"; }
warn() { echo -e "${YELLOW}  ⚠️  $*${NC}"; }
hdr()  { echo -e "\n${CYAN}${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n  $*\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}\n"; }

# ── Spec: simple enough to complete but meaningful enough to measure accuracy ──
SPEC='Build a static HTML/CSS/JS todo app. Single index.html file.
Features: add task, mark complete with strikethrough, delete task, task counter.
Clean design: soft shadows, rounded cards, blue/white palette, smooth transitions.
LocalStorage persistence. No backend, no build step.'

TOKEN=""
ALL_RESULTS=()

# ── Auth ───────────────────────────────────────────────────────────────────────
auth_login() {
    local resp
    resp=$(curl -s -X POST "$BASE_URL/api/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")
    TOKEN=$(echo "$resp" | jq -r '.accessToken // empty')
    if [[ -z "$TOKEN" ]]; then
        echo -e "${RED}Auth failed. Response: $resp${NC}"; exit 1
    fi
    log "Authenticated as $EMAIL"
}

# ── Submit ─────────────────────────────────────────────────────────────────────
submit_run() {
    local provider="$1"
    local body
    body=$(jq -n --arg req "$SPEC" --arg prov "$provider" \
        '{userRequest: $req, skipApproval: true, executionMode: "PROTOTYPE", aiProvider: $prov}')
    local resp
    resp=$(curl -s -X POST "$BASE_URL/api/runs" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d "$body")
    echo "$resp" | jq -r '.runId // empty'
}

# ── Poll until terminal — returns JSON detail of the final state ───────────────
poll_run() {
    local run_id="$1" provider="$2"
    local elapsed=0
    local detail status

    while [[ $elapsed -lt $RUN_TIMEOUT ]]; do
        detail=$(curl -s "$BASE_URL/api/runs/$run_id" -H "Authorization: Bearer $TOKEN")
        status=$(echo "$detail" | jq -r '.status // empty')
        local phase
        phase=$(echo "$detail" | jq -r '.phase // ""')
        log "[$provider] status=$status phase=$phase (${elapsed}s)"
        case "$status" in
            COMPLETE|FAILED|PARTIAL) echo "$detail"; return 0 ;;
        esac
        sleep $POLL_INTERVAL
        elapsed=$((elapsed + POLL_INTERVAL))
    done
    # Timeout — return last known detail
    echo "$detail"
}

# ── Fetch validation report ────────────────────────────────────────────────────
fetch_report() {
    local run_id="$1"
    local elapsed=0
    local resp report_status

    while [[ $elapsed -lt $REPORT_WAIT ]]; do
        resp=$(curl -s "$BASE_URL/api/runs/$run_id/report" -H "Authorization: Bearer $TOKEN")
        report_status=$(echo "$resp" | jq -r '.status // "NOT_GENERATED"')
        if [[ "$report_status" == "COMPLETE" ]]; then
            echo "$resp"; return 0
        fi
        sleep 10
        elapsed=$((elapsed + 10))
    done
    echo "{}"
}

# ── Run one provider and collect all 4 dimensions ─────────────────────────────
run_provider() {
    local provider="$1"
    hdr "Provider: $provider"

    # Submit
    local run_id
    run_id=$(submit_run "$provider")
    if [[ -z "$run_id" ]]; then
        fail "[$provider] Submit failed"
        ALL_RESULTS+=("{\"provider\":\"$provider\",\"error\":\"SUBMIT_FAILED\"}")
        return
    fi
    ok "[$provider] Run $run_id submitted"
    local start_epoch
    start_epoch=$(date +%s)

    # ── TIME: measure wall-clock duration ──
    local run_detail
    run_detail=$(poll_run "$run_id" "$provider")
    local end_epoch
    end_epoch=$(date +%s)
    local wall_seconds=$(( end_epoch - start_epoch ))

    # ── STATUS ──
    local status
    status=$(echo "$run_detail" | jq -r '.status // "UNKNOWN"')

    # ── COST ──
    local actual_cost estimated_low estimated_high credits charged hard_cap
    actual_cost=$(echo "$run_detail"   | jq -r '.actualCostUsd      // "null"')
    estimated_low=$(echo "$run_detail" | jq -r '.costEstimateLow    // "null"')
    estimated_high=$(echo "$run_detail"| jq -r '.costEstimateHigh   // "null"')
    credits=$(echo "$run_detail"       | jq -r '.creditsCharged     // "null"')
    hard_cap=$(echo "$run_detail"      | jq -r '.hardCapUsd         // "null"')

    # ── EFFICIENCY: tokens + task counts ──
    local in_tokens out_tokens tasks_total tasks_done tasks_failed tasks_partial
    in_tokens=$(echo "$run_detail"       | jq -r '.totalInputTokens  // 0')
    out_tokens=$(echo "$run_detail"      | jq -r '.totalOutputTokens // 0')
    tasks_total=$(echo "$run_detail"     | jq -r '.tasks | length // 0')
    tasks_done=$(echo "$run_detail"      | jq -r '[.tasks[]? | select(.status=="COMPLETE")] | length')
    tasks_failed=$(echo "$run_detail"    | jq -r '[.tasks[]? | select(.status=="FAILED")]   | length')
    tasks_partial=$(echo "$run_detail"   | jq -r '[.tasks[]? | select(.status=="PARTIAL")]  | length')

    # tokens per task (avoid div/0)
    local tokens_per_task="N/A"
    if [[ "$tasks_total" -gt 0 ]]; then
        local total_tokens=$(( in_tokens + out_tokens ))
        tokens_per_task=$(( total_tokens / tasks_total ))
    fi

    # ── ACCURACY: from validation report ──
    local accuracy_score="N/A" accuracy_grade="N/A"
    local issues_total="N/A" build_breakers="N/A" review_failures="N/A"
    local report_status="SKIPPED"

    if [[ "$SKIP_REPORT" == "0" && ( "$status" == "COMPLETE" || "$status" == "PARTIAL" ) ]]; then
        log "[$provider] Waiting for validation report…"
        local report_json
        report_json=$(fetch_report "$run_id")
        report_status=$(echo "$report_json" | jq -r '.status // "NOT_GENERATED"')

        if [[ "$report_status" == "COMPLETE" ]]; then
            # accuracyScore is a top-level field on ValidationReportDTO (not nested under .content)
            accuracy_score=$(echo "$report_json"   | jq -r '.accuracyScore   // "N/A"')
            accuracy_grade=$(echo "$report_json"   | jq -r '.accuracyGrade   // "N/A"')
            issues_total=$(echo "$report_json"     | jq -r '.issuesCaught    // 0')
            build_breakers=$(echo "$report_json"   | jq -r '.buildBreakers   // 0')
            review_failures=$(echo "$report_json"  | jq -r '.reviewFailures  // 0')
            ok "[$provider] Report: accuracy=$accuracy_score ($accuracy_grade), issues=$issues_total (build=$build_breakers, review=$review_failures)"
        else
            warn "[$provider] Report not ready within ${REPORT_WAIT}s (status=$report_status)"
        fi
    fi

    # ── Print per-provider summary ──
    echo ""
    echo -e "${BOLD}  ┌─ $provider ─────────────────────────────────────────────┐${NC}"
    printf "  │  %-22s  %s\n"  "Status:"           "$status"
    printf "  │\n"
    echo -e "  │  ${CYAN}TIME${NC}"
    printf "  │  %-22s  %ds\n"  "Wall clock:"       "$wall_seconds"
    printf "  │\n"
    echo -e "  │  ${CYAN}COST${NC}"
    printf "  │  %-22s  \$%s\n" "Actual cost:"      "$actual_cost"
    printf "  │  %-22s  \$%s – \$%s\n" "Estimated range:"  "$estimated_low" "$estimated_high"
    printf "  │  %-22s  %s credits (cap \$%s)\n" "Credits charged:" "$credits" "$hard_cap"
    printf "  │\n"
    echo -e "  │  ${CYAN}EFFICIENCY${NC}"
    printf "  │  %-22s  %s in / %s out\n" "Tokens:"       "$in_tokens" "$out_tokens"
    printf "  │  %-22s  %s\n"  "Tokens/task:"      "$tokens_per_task"
    printf "  │  %-22s  %s total (%s done, %s partial, %s failed)\n" \
           "Tasks:"  "$tasks_total" "$tasks_done" "$tasks_partial" "$tasks_failed"
    printf "  │\n"
    echo -e "  │  ${CYAN}ACCURACY${NC}"
    printf "  │  %-22s  %s (%s)\n"  "Spec adherence:"  "$accuracy_score" "$accuracy_grade"
    printf "  │  %-22s  %s total (%s build, %s review)\n" \
           "Issues found:"  "$issues_total" "$build_breakers" "$review_failures"
    printf "  │  %-22s  %s\n"  "Report status:"   "$report_status"
    echo -e "  └────────────────────────────────────────────────────────┘"
    echo ""

    # Emit status color
    case "$status" in
        COMPLETE) ok  "[$provider] COMPLETE" ;;
        PARTIAL)  warn "[$provider] PARTIAL" ;;
        *)        fail "[$provider] $status" ;;
    esac

    # Collect JSON for file output
    local result_json
    result_json=$(jq -n \
        --arg provider "$provider" \
        --arg run_id "$run_id" \
        --arg status "$status" \
        --argjson wall_seconds "$wall_seconds" \
        --arg actual_cost "$actual_cost" \
        --arg estimated_low "$estimated_low" \
        --arg estimated_high "$estimated_high" \
        --arg credits "$credits" \
        --arg hard_cap "$hard_cap" \
        --argjson in_tokens "$in_tokens" \
        --argjson out_tokens "$out_tokens" \
        --argjson tasks_total "$tasks_total" \
        --argjson tasks_done "$tasks_done" \
        --argjson tasks_failed "$tasks_failed" \
        --argjson tasks_partial "$tasks_partial" \
        --arg accuracy_score "$accuracy_score" \
        --arg accuracy_grade "$accuracy_grade" \
        --arg issues_total "$issues_total" \
        --arg build_breakers "$build_breakers" \
        --arg review_failures "$review_failures" \
        --arg report_status "$report_status" \
        --arg timestamp "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
        '{
            provider: $provider,
            run_id: $run_id,
            timestamp: $timestamp,
            status: $status,
            time: {
                wall_seconds: $wall_seconds
            },
            cost: {
                actual_usd: $actual_cost,
                estimated_low_usd: $estimated_low,
                estimated_high_usd: $estimated_high,
                credits_charged: $credits,
                hard_cap_usd: $hard_cap
            },
            efficiency: {
                input_tokens: $in_tokens,
                output_tokens: $out_tokens,
                tasks_total: $tasks_total,
                tasks_done: $tasks_done,
                tasks_failed: $tasks_failed,
                tasks_partial: $tasks_partial
            },
            accuracy: {
                score: $accuracy_score,
                grade: $accuracy_grade,
                issues_total: $issues_total,
                build_breakers: $build_breakers,
                review_failures: $review_failures,
                report_status: $report_status
            }
        }')

    ALL_RESULTS+=("$result_json")
}

# ── Comparison table ───────────────────────────────────────────────────────────
print_comparison_table() {
    hdr "Comparison Table"
    printf "${BOLD}  %-12s  %-8s  %-6s  %-12s  %-10s  %-14s  %-8s  %-10s${NC}\n" \
        "Provider" "Status" "Time" "Cost(actual)" "Credits" "Tokens(in+out)" "Accuracy" "Issues"

    for result in "${ALL_RESULTS[@]}"; do
        local p status t cost cr tok acc iss
        p=$(echo "$result"      | jq -r '.provider')
        status=$(echo "$result" | jq -r '.status')
        t=$(echo "$result"      | jq -r '.time.wall_seconds')
        cost=$(echo "$result"   | jq -r '.cost.actual_usd')
        cr=$(echo "$result"     | jq -r '.cost.credits_charged')
        local in_t out_t
        in_t=$(echo "$result"   | jq -r '.efficiency.input_tokens')
        out_t=$(echo "$result"  | jq -r '.efficiency.output_tokens')
        tok="${in_t}+${out_t}"
        local sc grade
        sc=$(echo "$result"     | jq -r '.accuracy.score')
        grade=$(echo "$result"  | jq -r '.accuracy.grade')
        acc="${sc}(${grade})"
        iss=$(echo "$result"    | jq -r '.accuracy.issues_total')

        local color="$NC"
        [[ "$status" == "COMPLETE" ]] && color="$GREEN"
        [[ "$status" == "PARTIAL"  ]] && color="$YELLOW"
        [[ "$status" == "FAILED"   ]] && color="$RED"

        printf "  ${color}%-12s  %-8s  %-6s  %-12s  %-10s  %-14s  %-8s  %-10s${NC}\n" \
            "$p" "$status" "${t}s" "\$$cost" "${cr}cr" "$tok" "$acc" "$iss"
    done
    echo ""
}

# ── Main ───────────────────────────────────────────────────────────────────────
hdr "ExecuteSpec — Provider Eval (cost · time · accuracy · efficiency)"
auth_login

for PROVIDER in $PROVIDERS; do
    run_provider "$PROVIDER"
done

print_comparison_table

# ── Write JSON results file ────────────────────────────────────────────────────
printf '[' > "$RESULTS_FILE"
first=1
for result in "${ALL_RESULTS[@]}"; do
    [[ $first -eq 0 ]] && printf ',' >> "$RESULTS_FILE"
    printf '%s' "$result" >> "$RESULTS_FILE"
    first=0
done
printf ']' >> "$RESULTS_FILE"

log "Results written to $RESULTS_FILE"
hdr "Done"
